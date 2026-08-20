package syxkingdoms.mod;

import syxkingdoms.protocol.LineIO;
import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.Protocol;
import syxkingdoms.protocol.ProtocolException;
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
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class OnlineClient implements AutoCloseable {
    interface Logger {
        void log(String message, Throwable error);
    }

    private static final String MOD_VERSION = "0.5.0";
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
    private volatile Messages.PlayerState playerState = new Messages.PlayerState(0, 0);
    private volatile List<Messages.Delivery> deliveries = List.of();
    private volatile List<Messages.MarketOffer> marketOffers = List.of();
    private volatile long eventCursor;
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

    double fixedSpeed() {
        Messages.WorldState state = worldState;
        if (connected.get() && state.serverTimeEpochMillis() > 0) {
            return state.fixedSpeed();
        }
        Messages.Welcome currentWelcome = welcome;
        return currentWelcome == null ? config.fallbackFixedSpeed() : currentWelcome.fixedSpeed();
    }

    String status() {
        return status;
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

            Messages.Hello hello = new Messages.Hello(Protocol.VERSION, gameVersion, MOD_VERSION,
                    modManifest, eventCursor, config.worldId(), identity.playerId(), identity.authToken(),
                    config.playerName(), config.cityName());
            LineIO.writeLine(socket.getOutputStream(), hello.encode());
            String first = LineIO.readLine(socket.getInputStream());
            if (first.startsWith("ERROR\t")) {
                Messages.Error error = Messages.Error.decode(first);
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
            readAndApplySync(socket);
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
                        eventCursor, commands);
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
        worldState = state;
        playerState = newPlayerState;
        relations = List.copyOf(newRelations);
        armies = List.copyOf(newArmies);
        deliveries = List.copyOf(newDeliveries);
        marketOffers = List.copyOf(newMarketOffers);
        eventCursor = eventsHeader.cursor();
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
