package syxkingdoms.mod;

import syxkingdoms.protocol.LineIO;
import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.Protocol;
import syxkingdoms.protocol.ProtocolException;
import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.SharedWorld;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class OnlineClient implements AutoCloseable {
    interface Logger {
        void log(String message, Throwable error);
    }

    record FoundingProfile(String rulerName, String kingdomName, String capitalName, String placementReference) {
    }

    private static final String MOD_VERSION = "0.12.0";
    private static final Set<String> FOUNDING_ERROR_CODES = Set.of(
            "FOUNDING_REQUIRED", "BAD_FOUNDING_NAME", "RESERVED_NAME",
            "RULER_NAME_TAKEN", "KINGDOM_NAME_TAKEN", "CAPITAL_NAME_TAKEN",
            "PLACEMENT_REFERENCE_NOT_FOUND");
    private final ClientConfig config;
    private final Identity identity;
    private final String gameVersion;
    private final String modManifest;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicInteger population = new AtomicInteger();
    private final AtomicLong credits = new AtomicLong();
    private final AtomicLong heartbeatSequence = new AtomicLong();
    private final AtomicLong lastGameUpdateMillis = new AtomicLong(System.currentTimeMillis());
    private final Map<String, Messages.Command> pendingCommands = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Messages.CommandResult> completedCommandResults = new ConcurrentLinkedQueue<>();
    private volatile String status = "not started";
    private volatile Messages.Welcome welcome;
    private volatile Messages.WorldState worldState = new Messages.WorldState(
            0, 0, 1.0, SharedWorld.WIDTH, SharedWorld.HEIGHT, "unknown", List.of());
    private volatile List<Messages.WorldEvent> recentEvents = List.of();
    private volatile List<Messages.Relation> relations = List.of();
    private volatile List<Messages.Army> armies = List.of();
    private volatile Messages.PlayerState playerState = new Messages.PlayerState(0, 0, 0);
    private volatile List<Messages.Delivery> deliveries = List.of();
    private volatile List<Messages.MarketOffer> marketOffers = List.of();
    private volatile List<Messages.House> houses = List.of();
    private volatile List<Messages.HouseMember> houseMembers = List.of();
    private volatile List<Messages.HouseRequest> houseRequests = List.of();
    private volatile List<Messages.Province> provinces = List.of();
    private volatile List<Messages.HouseWar> wars = List.of();
    private volatile List<Messages.BattleReport> battleReports = List.of();
    private volatile Messages.Season season = new Messages.Season(1, 0, 1, "", "ACTIVE");
    private final Map<String, CrestDesign> crests = new ConcurrentHashMap<>();
    private final Map<String, Long> crestRevisions = new ConcurrentHashMap<>();
    private volatile long eventCursor;
    private volatile long crestCursor;
    private volatile long lastServerSyncNanos;
    private volatile boolean foundingRequired;
    private volatile String foundingMessage = "";
    private volatile FoundingProfile foundingProfile;
    private volatile Socket activeSocket;
    private Thread thread;

    OnlineClient(ClientConfig config, Identity identity, String gameVersion, String modManifest, Logger logger) {
        this.config = config;
        this.identity = identity;
        this.gameVersion = gameVersion;
        this.modManifest = modManifest;
        this.logger = logger;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofPlatform().name("syx-kingdoms-client").daemon(true).start(this::run);
    }

    void updateCityStats(int newPopulation, long newCredits) {
        population.set(Math.max(0, newPopulation));
        credits.set(newCredits);
        lastGameUpdateMillis.set(System.currentTimeMillis());
    }

    void touchGameUpdate() {
        lastGameUpdateMillis.set(System.currentTimeMillis());
    }

    boolean connected() {
        return connected.get();
    }

    boolean simulationReady() {
        long lastSync = lastServerSyncNanos;
        if (!connected.get() || lastSync == 0) {
            return false;
        }
        long freshnessMillis = Math.max(3_000L, config.heartbeatMillis() * 3L);
        return System.nanoTime() - lastSync <= freshnessMillis * 1_000_000L;
    }

    double fixedSpeed() {
        Messages.WorldState state = worldState;
        if (connected.get() && state.serverTimeEpochMillis() > 0) {
            return state.fixedSpeed();
        }
        Messages.Welcome currentWelcome = welcome;
        return currentWelcome == null ? SharedWorld.FIXED_GAME_SPEED : currentWelcome.fixedSpeed();
    }

    String status() {
        return status;
    }

    boolean foundingRequired() {
        return foundingRequired && foundingProfile == null;
    }

    String foundingMessage() {
        return foundingMessage;
    }

    void submitFounding(String rulerName, String kingdomName, String capitalName, String placementReference) {
        foundingProfile = new FoundingProfile(rulerName, kingdomName, capitalName,
                placementReference == null ? "" : placementReference);
        status = "submitting kingdom founding claim";
    }

    Messages.WorldState worldState() {
        return worldState;
    }

    ClientConfig config() {
        return config;
    }

    String ownPlayerId() {
        return identity.playerId();
    }

    List<Messages.WorldEvent> recentEvents() {
        return recentEvents;
    }

    List<Messages.Relation> relations() {
        return relations;
    }

    List<Messages.Army> armies() {
        return armies;
    }

    Messages.PlayerState playerState() {
        return playerState;
    }

    List<Messages.Delivery> deliveries() {
        return deliveries;
    }

    List<Messages.MarketOffer> marketOffers() {
        return marketOffers;
    }

    List<Messages.House> houses() { return houses; }
    List<Messages.HouseMember> houseMembers() { return houseMembers; }
    List<Messages.HouseRequest> houseRequests() { return houseRequests; }
    List<Messages.Province> provinces() { return provinces; }
    List<Messages.HouseWar> wars() { return wars; }
    List<Messages.BattleReport> battleReports() { return battleReports; }
    Messages.Season season() { return season; }

    Messages.HouseMember ownHouseMember() {
        return houseMembers.stream().filter(member -> member.playerId().equals(identity.playerId()))
                .findFirst().orElse(null);
    }

    Messages.House ownHouse() {
        Messages.HouseMember member = ownHouseMember();
        return member == null ? null : houses.stream().filter(house -> house.houseId().equals(member.houseId()))
                .findFirst().orElse(null);
    }

    CrestDesign crest(String playerId) {
        return crests.get(playerId);
    }

    List<Messages.CommandResult> drainCommandResults() {
        List<Messages.CommandResult> drained = new ArrayList<>();
        Messages.CommandResult result;
        while ((result = completedCommandResults.poll()) != null) {
            drained.add(result);
        }
        return List.copyOf(drained);
    }

    String sendGlobalChat(String text) {
        return queueCommand("CHAT", "", "", 0, 0, text);
    }

    String sendLocalChat(String text) {
        return queueCommand("LOCAL_CHAT", "", "", 0, 0, text);
    }

    String setCrest(CrestDesign crest) {
        if (crest == null) {
            throw new IllegalArgumentException("Crest is required");
        }
        return queueCommand("SET_CREST", "", crest.pixelsEncoded(), 0, 0, crest.paletteHex());
    }

    String createHouse(String name, String tag) { return queueCommand("CREATE_HOUSE", "", tag, 0, 0, name); }
    String applyToHouse(String houseId) { return queueCommand("APPLY_HOUSE", "", houseId, 0, 0, ""); }
    String inviteToHouse(String playerId) { return queueCommand("INVITE_HOUSE", playerId, "", 0, 0, ""); }
    String acceptHouseRequest(String requestId) { return queueCommand("ACCEPT_HOUSE_REQUEST", "", requestId, 0, 0, ""); }
    String rejectHouseRequest(String requestId) { return queueCommand("REJECT_HOUSE_REQUEST", "", requestId, 0, 0, ""); }
    String leaveHouse() { return queueCommand("LEAVE_HOUSE", "", "", 0, 0, ""); }
    String kickHouseMember(String playerId) { return queueCommand("KICK_HOUSE", playerId, "", 0, 0, ""); }
    String setHouseRole(String playerId, String role) { return queueCommand("SET_HOUSE_ROLE", playerId, role, 0, 0, ""); }
    String setHouseAnnouncement(String text) { return queueCommand("HOUSE_ANNOUNCEMENT", "", "", 0, 0, text); }
    String sendHouseChat(String text) { return queueCommand("HOUSE_CHAT", "", "", 0, 0, text); }
    String setHouseCrest(CrestDesign crest) { return queueCommand("SET_HOUSE_CREST", "", crest.pixelsEncoded(), 0, 0, crest.paletteHex()); }
    String declareHouseWar(String targetHouseId) { return queueCommand("DECLARE_HOUSE_WAR", "", targetHouseId, 0, 0, ""); }
    String surrenderHouseWar(String warId) { return queueCommand("SURRENDER_HOUSE_WAR", "", warId, 0, 0, ""); }
    String claimProvince(int x, int y) { return queueCommand("CLAIM_PROVINCE", "", "", x, y, ""); }
    String annexProvince(int x, int y) { return queueCommand("ANNEX_PROVINCE", "", "", x, y, ""); }
    String collectTribute(int x, int y) { return queueCommand("COLLECT_TRIBUTE", "", "", x, y, ""); }
    String sendReinforcement(String targetPlayerId, int troops) {
        return queueCommand("SEND_REINFORCEMENT", targetPlayerId, "", troops, 0, "");
    }

    String sendMail(String targetPlayerId, String text) {
        return queueCommand("MAIL", targetPlayerId, "", 0, 0, text);
    }

    String setStance(String targetPlayerId, String stance) {
        return queueCommand("STANCE", targetPlayerId, stance, 0, 0, "");
    }

    String transferCrowns(String targetPlayerId, long amount) {
        return queueCommand("TRANSFER_CROWNS", targetPlayerId, "", amount, 0, "");
    }

    String sendArmy(String targetPlayerId, int troops) {
        return queueCommand("SEND_ARMY", targetPlayerId, "", troops, 0, "");
    }

    String sendResource(String commandId, String targetPlayerId, String resourceKey, int amount) {
        return queueCommand(new Messages.Command(commandId, "SEND_RESOURCE", targetPlayerId,
                resourceKey, amount, 0, ""));
    }

    String createMarketOffer(String commandId, String resourceKey, int quantity, long unitPrice) {
        return queueCommand(new Messages.Command(commandId, "CREATE_OFFER", "",
                resourceKey, quantity, unitPrice, ""));
    }

    String buyMarketOffer(String offerId, int quantity) {
        return queueCommand("BUY_OFFER", "", offerId, quantity, 0, "");
    }

    String cancelMarketOffer(String offerId) {
        return queueCommand("CANCEL_OFFER", "", offerId, 0, 0, "");
    }

    String acknowledgeDelivery(String deliveryId) {
        String commandId = UUID.nameUUIDFromBytes(("ack:" + deliveryId).getBytes(StandardCharsets.UTF_8)).toString();
        return queueCommand(new Messages.Command(commandId, "ACK_DELIVERY", "",
                deliveryId, 0, 0, ""));
    }

    private void run() {
        long reconnectDelay = 1_000;
        while (running.get()) {
            if (foundingRequired && foundingProfile == null) {
                sleep(250);
                continue;
            }
            try {
                session();
                reconnectDelay = 1_000;
            } catch (Exception e) {
                if (running.get()) {
                    connected.set(false);
                    status = concise(e);
                    logger.log("Connection lost: " + status, config.debug() ? e : null);
                }
            } finally {
                connected.set(false);
                lastServerSyncNanos = 0;
                activeSocket = null;
            }
            if (!running.get()) {
                break;
            }
            sleep(reconnectDelay);
            reconnectDelay = Math.min(30_000, reconnectDelay * 2);
        }
        status = "stopped";
    }

    private void session() throws IOException {
        status = "connecting to " + config.serverHost() + ":" + config.serverPort();
        try (Socket socket = createSocket()) {
            activeSocket = socket;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(config.serverHost(), config.serverPort()), config.connectTimeoutMillis());
            if (socket instanceof SSLSocket sslSocket) {
                sslSocket.startHandshake();
                verifyCertificatePin(sslSocket);
            }
            socket.setSoTimeout((int) Math.min(60_000, config.heartbeatMillis() + config.connectTimeoutMillis()));

            FoundingProfile profile = foundingProfile;
            Messages.Hello hello = new Messages.Hello(Protocol.VERSION, gameVersion, MOD_VERSION,
                    modManifest, eventCursor, crestCursor, config.worldId(), identity.playerId(), identity.authToken(),
                    profile == null ? "" : profile.rulerName(),
                    profile == null ? "" : profile.kingdomName(),
                    profile == null ? "" : profile.capitalName(),
                    profile == null ? "" : profile.placementReference());
            LineIO.writeLine(socket.getOutputStream(), hello.encode());
            String first = LineIO.readLine(socket.getInputStream());
            if (first.startsWith("ERROR\t")) {
                Messages.Error error = Messages.Error.decode(first);
                if (FOUNDING_ERROR_CODES.contains(error.code())) {
                    foundingRequired = true;
                    foundingProfile = null;
                    foundingMessage = error.message();
                    status = error.code() + ": " + error.message();
                    return;
                }
                throw new ProtocolException(error.code(), error.message());
            }
            welcome = Messages.Welcome.decode(first);
            if (welcome.protocolVersion() != Protocol.VERSION) {
                throw new ProtocolException("PROTOCOL_MISMATCH", "Server welcomed a different protocol version");
            }
            if (!config.worldId().equals(welcome.worldId())) {
                throw new ProtocolException("WORLD_MISMATCH", "Server welcomed the client into an unexpected world");
            }
            if (welcome.worldWidth() != SharedWorld.WIDTH || welcome.worldHeight() != SharedWorld.HEIGHT) {
                throw new ProtocolException("WORLD_SIZE_MISMATCH", "Online worlds must be "
                        + SharedWorld.WIDTH + "x" + SharedWorld.HEIGHT + " tiles");
            }
            if (!SharedWorld.WORLD_SEED.equals(welcome.worldSeed())) {
                throw new ProtocolException("WORLD_MAP_MISMATCH",
                        "Server is not hosting the canonical persistent Syx Kingdoms map");
            }
            if (Double.compare(welcome.fixedSpeed(), SharedWorld.FIXED_GAME_SPEED) != 0) {
                throw new ProtocolException("WORLD_SPEED_MISMATCH",
                        "The canonical multiplayer ruleset requires fixed 1x simulation speed");
            }
            readAndApplySync(socket);
            foundingRequired = false;
            foundingMessage = "";
            foundingProfile = null;
            connected.set(true);
            status = "online in " + welcome.worldName();
            logger.log("Connected to " + welcome.worldName() + " at city slot "
                    + welcome.cityX() + "," + welcome.cityY(), null);

            while (running.get()) {
                if (System.currentTimeMillis() - lastGameUpdateMillis.get() > 10_000) {
                    throw new IOException("game session is no longer updating");
                }
                List<Messages.Command> commands = pendingSnapshot();
                Messages.ClientUpdate update = new Messages.ClientUpdate(
                        heartbeatSequence.incrementAndGet(), population.get(), credits.get(),
                        eventCursor, crestCursor, commands);
                LineIO.writeLine(socket.getOutputStream(), update.header());
                for (Messages.Command command : commands) {
                    LineIO.writeLine(socket.getOutputStream(), command.encode());
                }
                readAndApplySync(socket);
                sleep(config.heartbeatMillis());
            }
        }
    }

    private Socket createSocket() throws IOException {
        if (!config.useTls()) {
            return new Socket();
        }
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        return socket;
    }

    private void verifyCertificatePin(SSLSocket socket) throws IOException {
        String expected = config.tlsCertificateSha256();
        if (expected.isBlank()) {
            return;
        }
        try {
            byte[] encoded = socket.getSession().getPeerCertificates()[0].getEncoded();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            if (!MessageDigest.isEqual(expected.toLowerCase(java.util.Locale.ROOT)
                    .getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("TLS certificate pin does not match the configured Syx Kingdoms server");
            }
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IOException("Could not verify the server TLS certificate pin", e);
        }
    }

    private void readAndApplySync(Socket socket) throws IOException {
        Messages.WorldState state = readState(socket);
        Messages.Welcome contract = welcome;
        if (contract == null
                || state.worldWidth() != contract.worldWidth()
                || state.worldHeight() != contract.worldHeight()
                || !state.worldSeed().equals(contract.worldSeed())
                || Math.abs(state.fixedSpeed() - contract.fixedSpeed()) > 0.000_001) {
            throw new ProtocolException("WORLD_CONTRACT_CHANGED", "Server state no longer matches the welcomed world contract");
        }
        Set<String> playerIds = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        boolean foundSelf = false;
        for (Messages.City city : state.cities()) {
            if (city.x() < 0 || city.x() >= state.worldWidth() || city.y() < 0 || city.y() >= state.worldHeight()) {
                throw new ProtocolException("BAD_STATE", "Server sent a city outside the shared-world bounds");
            }
            if (city.kingdomName().isBlank() || city.protectionEndsEpochMillis() < 0) {
                throw new ProtocolException("BAD_STATE", "Server sent an incomplete kingdom profile");
            }
            if (!playerIds.add(city.playerId()) || !coordinates.add(city.x() + "," + city.y())) {
                throw new ProtocolException("BAD_STATE", "Server sent duplicate player or city coordinates");
            }
            if (city.playerId().equals(identity.playerId())) {
                foundSelf = city.x() == contract.cityX() && city.y() == contract.cityY();
            }
        }
        if (!foundSelf) {
            throw new ProtocolException("BAD_STATE", "Server state does not contain this client's welcomed city slot");
        }
        Messages.PlayerState newPlayerState = Messages.PlayerState.decode(LineIO.readLine(socket.getInputStream()));
        Messages.ResultsHeader resultsHeader = Messages.ResultsHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.CommandResult> results = new ArrayList<>(resultsHeader.resultCount());
        for (int i = 0; i < resultsHeader.resultCount(); i++) {
            results.add(Messages.CommandResult.decode(LineIO.readLine(socket.getInputStream())));
        }
        Messages.EventsHeader eventsHeader = Messages.EventsHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.WorldEvent> newEvents = new ArrayList<>(eventsHeader.eventCount());
        long previousEventId = 0;
        for (int i = 0; i < eventsHeader.eventCount(); i++) {
            Messages.WorldEvent event = Messages.WorldEvent.decode(LineIO.readLine(socket.getInputStream()));
            if (event.eventId() <= previousEventId || event.eventId() > eventsHeader.cursor()) {
                throw new ProtocolException("BAD_EVENTS", "Server events are not ordered within the event cursor");
            }
            previousEventId = event.eventId();
            newEvents.add(event);
        }
        Messages.CrestsHeader crestsHeader = Messages.CrestsHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.Crest> newCrests = new ArrayList<>(crestsHeader.crestCount());
        long previousCrestRevision = 0;
        Set<String> crestPlayerIds = new HashSet<>();
        for (int i = 0; i < crestsHeader.crestCount(); i++) {
            Messages.Crest crest = Messages.Crest.decode(LineIO.readLine(socket.getInputStream()));
            if (crest.revision() <= previousCrestRevision || crest.revision() > crestsHeader.cursor()
                    || !playerIds.contains(crest.playerId()) || !crestPlayerIds.add(crest.playerId())) {
                throw new ProtocolException("BAD_CRESTS", "Server sent invalid or unordered crest revisions");
            }
            previousCrestRevision = crest.revision();
            newCrests.add(crest);
        }
        Messages.HousesHeader housesHeader = Messages.HousesHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.House> newHouses = new ArrayList<>(housesHeader.houseCount());
        Set<String> houseIds = new HashSet<>();
        for (int i = 0; i < housesHeader.houseCount(); i++) {
            Messages.House house = Messages.House.decode(LineIO.readLine(socket.getInputStream()));
            if (!houseIds.add(house.houseId()) || !playerIds.contains(house.sovereignPlayerId())) {
                throw new ProtocolException("BAD_HOUSES", "Server sent duplicate or invalid House state");
            }
            newHouses.add(house);
        }
        Messages.HouseMembersHeader membersHeader = Messages.HouseMembersHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.HouseMember> newMembers = new ArrayList<>(membersHeader.memberCount());
        Set<String> memberIds = new HashSet<>();
        for (int i = 0; i < membersHeader.memberCount(); i++) {
            Messages.HouseMember member = Messages.HouseMember.decode(LineIO.readLine(socket.getInputStream()));
            if (!memberIds.add(member.playerId()) || !playerIds.contains(member.playerId())
                    || !houseIds.contains(member.houseId())) {
                throw new ProtocolException("BAD_HOUSE_MEMBERS", "Server sent invalid House membership");
            }
            newMembers.add(member);
        }
        Messages.HouseRequestsHeader requestsHeader = Messages.HouseRequestsHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.HouseRequest> newRequests = new ArrayList<>(requestsHeader.requestCount());
        Set<String> requestIds = new HashSet<>();
        for (int i = 0; i < requestsHeader.requestCount(); i++) {
            Messages.HouseRequest request = Messages.HouseRequest.decode(LineIO.readLine(socket.getInputStream()));
            if (!requestIds.add(request.requestId()) || !houseIds.contains(request.houseId())
                    || !playerIds.contains(request.playerId())) {
                throw new ProtocolException("BAD_HOUSE_REQUESTS", "Server sent invalid House request");
            }
            newRequests.add(request);
        }
        Messages.ProvincesHeader provincesHeader = Messages.ProvincesHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.Province> newProvinces = new ArrayList<>(provincesHeader.provinceCount());
        Set<String> provinceIds = new HashSet<>();
        for (int i = 0; i < provincesHeader.provinceCount(); i++) {
            Messages.Province province = Messages.Province.decode(LineIO.readLine(socket.getInputStream()));
            if (!provinceIds.add(province.x() + "," + province.y())
                    || (!province.ownerHouseId().isBlank() && !houseIds.contains(province.ownerHouseId()))
                    || (!province.occupierHouseId().isBlank() && !houseIds.contains(province.occupierHouseId()))) {
                throw new ProtocolException("BAD_PROVINCES", "Server sent invalid province state");
            }
            newProvinces.add(province);
        }
        if (newProvinces.size() != SharedWorld.SECTOR_COUNT) {
            throw new ProtocolException("BAD_PROVINCES", "Server must synchronize exactly 100 provinces");
        }
        Messages.WarsHeader warsHeader = Messages.WarsHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.HouseWar> newWars = new ArrayList<>(warsHeader.warCount());
        Set<String> warIds = new HashSet<>();
        for (int i = 0; i < warsHeader.warCount(); i++) {
            Messages.HouseWar war = Messages.HouseWar.decode(LineIO.readLine(socket.getInputStream()));
            if (!warIds.add(war.warId()) || !houseIds.contains(war.attackerHouseId())
                    || !houseIds.contains(war.defenderHouseId())) {
                throw new ProtocolException("BAD_WARS", "Server sent invalid House war state");
            }
            newWars.add(war);
        }
        Messages.ReportsHeader reportsHeader = Messages.ReportsHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.BattleReport> newReports = new ArrayList<>(reportsHeader.reportCount());
        Set<String> reportIds = new HashSet<>();
        for (int i = 0; i < reportsHeader.reportCount(); i++) {
            Messages.BattleReport report = Messages.BattleReport.decode(LineIO.readLine(socket.getInputStream()));
            if (!reportIds.add(report.reportId()) || !playerIds.contains(report.attackerPlayerId())
                    || !playerIds.contains(report.defenderPlayerId())) {
                throw new ProtocolException("BAD_REPORTS", "Server sent invalid battle report");
            }
            newReports.add(report);
        }
        Messages.Season newSeason = Messages.Season.decode(LineIO.readLine(socket.getInputStream()));
        Messages.RelationsHeader relationsHeader = Messages.RelationsHeader.decode(
                LineIO.readLine(socket.getInputStream()));
        List<Messages.Relation> newRelations = new ArrayList<>(relationsHeader.relationCount());
        Set<String> relationIds = new HashSet<>();
        for (int i = 0; i < relationsHeader.relationCount(); i++) {
            Messages.Relation relation = Messages.Relation.decode(LineIO.readLine(socket.getInputStream()));
            if (!relationIds.add(relation.playerId()) || relation.playerId().equals(identity.playerId())) {
                throw new ProtocolException("BAD_RELATIONS", "Server sent duplicate or self diplomacy state");
            }
            newRelations.add(relation);
        }
        Messages.ArmiesHeader armiesHeader = Messages.ArmiesHeader.decode(LineIO.readLine(socket.getInputStream()));
        List<Messages.Army> newArmies = new ArrayList<>(armiesHeader.armyCount());
        Set<String> armyIds = new HashSet<>();
        for (int i = 0; i < armiesHeader.armyCount(); i++) {
            Messages.Army army = Messages.Army.decode(LineIO.readLine(socket.getInputStream()));
            if (!armyIds.add(army.armyId()) || !playerIds.contains(army.ownerPlayerId())
                    || !playerIds.contains(army.targetPlayerId())
                    || army.ownerPlayerId().equals(army.targetPlayerId())) {
                throw new ProtocolException("BAD_ARMIES", "Server sent an invalid traveling army");
            }
            newArmies.add(army);
        }
        Messages.DeliveriesHeader deliveriesHeader = Messages.DeliveriesHeader.decode(
                LineIO.readLine(socket.getInputStream()));
        List<Messages.Delivery> newDeliveries = new ArrayList<>(deliveriesHeader.deliveryCount());
        Set<String> deliveryIds = new HashSet<>();
        for (int i = 0; i < deliveriesHeader.deliveryCount(); i++) {
            Messages.Delivery delivery = Messages.Delivery.decode(LineIO.readLine(socket.getInputStream()));
            if (!deliveryIds.add(delivery.deliveryId()) || !playerIds.contains(delivery.senderPlayerId())) {
                throw new ProtocolException("BAD_DELIVERIES", "Server sent an invalid resource delivery");
            }
            newDeliveries.add(delivery);
        }
        Messages.OffersHeader offersHeader = Messages.OffersHeader.decode(
                LineIO.readLine(socket.getInputStream()));
        List<Messages.MarketOffer> newMarketOffers = new ArrayList<>(offersHeader.offerCount());
        Set<String> offerIds = new HashSet<>();
        for (int i = 0; i < offersHeader.offerCount(); i++) {
            Messages.MarketOffer offer = Messages.MarketOffer.decode(LineIO.readLine(socket.getInputStream()));
            if (!offer.offerId().matches("[A-Za-z0-9-]{8,64}")
                    || !offerIds.add(offer.offerId())
                    || !playerIds.contains(offer.sellerPlayerId())
                    || !offer.resourceKey().matches("[A-Za-z0-9._-]{1,64}")) {
                throw new ProtocolException("BAD_OFFERS", "Server sent an invalid market offer");
            }
            newMarketOffers.add(offer);
        }
        if (!"SYNC_END".equals(LineIO.readLine(socket.getInputStream()))) {
            throw new ProtocolException("BAD_SYNC", "Expected SYNC_END");
        }
        applyCommandResults(results);
        mergeEvents(newEvents);
        if (crestsHeader.cursor() < crestCursor) {
            crests.clear();
            crestRevisions.clear();
        }
        for (Messages.Crest crest : newCrests) {
            long knownRevision = crestRevisions.getOrDefault(crest.playerId(), 0L);
            if (crest.revision() >= knownRevision) {
                crests.put(crest.playerId(), crest.design());
                crestRevisions.put(crest.playerId(), crest.revision());
            }
        }
        worldState = state;
        playerState = newPlayerState;
        relations = List.copyOf(newRelations);
        armies = List.copyOf(newArmies);
        deliveries = List.copyOf(newDeliveries);
        marketOffers = List.copyOf(newMarketOffers);
        houses = List.copyOf(newHouses);
        houseMembers = List.copyOf(newMembers);
        houseRequests = List.copyOf(newRequests);
        provinces = List.copyOf(newProvinces);
        wars = List.copyOf(newWars);
        battleReports = List.copyOf(newReports);
        season = newSeason;
        eventCursor = eventsHeader.cursor();
        crestCursor = crestsHeader.cursor();
        lastServerSyncNanos = System.nanoTime();
    }

    private String queueCommand(String type, String targetPlayerId, String argument,
            long value1, long value2, String text) {
        String commandId = UUID.randomUUID().toString();
        return queueCommand(new Messages.Command(commandId, type,
                targetPlayerId == null ? "" : targetPlayerId,
                argument == null ? "" : argument, value1, value2, text == null ? "" : text));
    }

    private String queueCommand(Messages.Command command) {
        String commandId = command.commandId();
        synchronized (pendingCommands) {
            if (pendingCommands.containsKey(commandId)) {
                return commandId;
            }
            if (pendingCommands.size() >= 64) {
                status = "COMMAND_QUEUE_FULL: wait for pending multiplayer actions";
                return "";
            }
            pendingCommands.put(commandId, command);
        }
        return commandId;
    }

    private List<Messages.Command> pendingSnapshot() {
        synchronized (pendingCommands) {
            return pendingCommands.values().stream()
                    .limit(Protocol.MAX_COMMANDS_PER_UPDATE).toList();
        }
    }

    private void applyCommandResults(List<Messages.CommandResult> results) {
        synchronized (pendingCommands) {
            for (Messages.CommandResult result : results) {
                pendingCommands.remove(result.commandId());
                if (!result.accepted()) {
                    status = result.code() + ": " + result.message();
                    logger.log("Multiplayer action rejected: " + status, null);
                } else {
                    logger.log("Multiplayer action accepted: " + result.message(), null);
                }
                completedCommandResults.add(result);
            }
        }
    }

    private void mergeEvents(List<Messages.WorldEvent> incoming) {
        LinkedHashMap<Long, Messages.WorldEvent> merged = new LinkedHashMap<>();
        for (Messages.WorldEvent event : recentEvents) {
            merged.put(event.eventId(), event);
        }
        for (Messages.WorldEvent event : incoming) {
            merged.put(event.eventId(), event);
        }
        List<Messages.WorldEvent> all = new ArrayList<>(merged.values());
        int from = Math.max(0, all.size() - 64);
        recentEvents = List.copyOf(all.subList(from, all.size()));
    }

    private static Messages.WorldState readState(Socket socket) throws IOException {
        String headerLine = LineIO.readLine(socket.getInputStream());
        if (headerLine.startsWith("ERROR\t")) {
            Messages.Error error = Messages.Error.decode(headerLine);
            throw new ProtocolException(error.code(), error.message());
        }
        Messages.WorldState.Header header = Messages.WorldState.decodeHeader(headerLine);
        List<Messages.City> cities = new ArrayList<>(header.cityCount());
        for (int i = 0; i < header.cityCount(); i++) {
            cities.add(Messages.City.decode(LineIO.readLine(socket.getInputStream())));
        }
        String end = LineIO.readLine(socket.getInputStream());
        if (!"STATE_END".equals(end)) {
            throw new ProtocolException("BAD_STATE", "Expected STATE_END");
        }
        return header.withCities(cities);
    }

    private static String concise(Exception e) {
        if (e instanceof ProtocolException protocol) {
            return protocol.code() + ": " + protocol.getMessage();
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        Socket socket = activeSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
    }
}
