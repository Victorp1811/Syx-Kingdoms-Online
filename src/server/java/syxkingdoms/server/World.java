package syxkingdoms.server;

import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.ProtocolException;
import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class World {
    private static final int MAX_RETAINED_EVENTS = 10_000;
    private static final long HOUSE_REQUEST_MILLIS = 7L * 86_400_000L;
    private static final long HOUSE_CHANGE_COOLDOWN_MILLIS = 24L * 3_600_000L;
    private static final long WAR_PREPARATION_MILLIS = 12L * 3_600_000L;
    private static final long WAR_DURATION_MILLIS = 7L * 86_400_000L;
    private static final long TRUCE_MILLIS = 72L * 3_600_000L;
    private static final long SEASON_MILLIS = 30L * 86_400_000L;
    private static final long TRIBUTE_INTERVAL_MILLIS = 24L * 3_600_000L;
    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin", "administrator", "moderator", "server", "system", "syx kingdoms");

    record JoinResult(PlayerRecord player, Messages.Welcome welcome) {
    }

    private record FoundingCluster(int[] playerCoordinate, List<Long> npcCoordinates) {
    }

    record Metrics(int registeredPlayers, int npcCities, int onlinePlayers, int retainedEvents,
            int armies, int shipments, int deliveries, int marketOffers, int houses, int wars) {
    }

    record SyncResult(
            Messages.WorldState worldState,
            Messages.PlayerState playerState,
            List<Messages.CommandResult> commandResults,
            long eventCursor,
            List<Messages.WorldEvent> events,
            long crestCursor,
            List<Messages.Crest> crests,
            List<Messages.House> houses,
            List<Messages.HouseMember> houseMembers,
            List<Messages.HouseRequest> houseRequests,
            List<Messages.Province> provinces,
            List<Messages.HouseWar> wars,
            List<Messages.BattleReport> battleReports,
            Messages.Season season,
            List<Messages.Relation> relations,
            List<Messages.Army> armies,
            List<Messages.Delivery> deliveries,
            List<Messages.MarketOffer> marketOffers) {
    }

    private final ServerConfig config;
    private final SharedWorldTerrain terrain;
    private final long[] cityCoordinates;
    private final ModerationStore moderation;
    private final long epochMillis;
    private final Map<String, PlayerRecord> players = new HashMap<>();
    private final Map<String, NpcCityRecord> npcCities = new HashMap<>();
    private final Set<String> online = new HashSet<>();
    private final List<WorldEventRecord> events = new ArrayList<>();
    private final Map<String, Messages.CommandResult> processedCommands = new HashMap<>();
    private final Map<String, String> stances = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> commandWindows = new HashMap<>();
    private final List<ArmyRecord> armies = new ArrayList<>();
    private final List<ShipmentRecord> shipments = new ArrayList<>();
    private final List<DeliveryRecord> deliveries = new ArrayList<>();
    private final List<MarketOfferRecord> marketOffers = new ArrayList<>();
    private final Map<String, HouseRecord> houses = new HashMap<>();
    private final List<HouseRequestRecord> houseRequests = new ArrayList<>();
    private final Map<String, ProvinceRecord> provinces = new HashMap<>();
    private final List<HouseWarRecord> wars = new ArrayList<>();
    private final List<BattleReportRecord> battleReports = new ArrayList<>();
    private int nextSlot;
    private long nextEventId;
    private long nextCrestRevision;
    private int seasonNumber;
    private long seasonStartsEpochMillis;
    private long seasonEndsEpochMillis;
    private String seasonWinnerHouseId;
    private boolean dirty;

    World(ServerConfig config) throws IOException {
        this.config = config;
        this.terrain = new SharedWorldTerrain(config.worldSeed());
        this.cityCoordinates = buildCityCoordinates();
        this.moderation = new ModerationStore(config.worldFile());
        WorldStore.LoadedWorld loaded = WorldStore.load(config.worldFile(), config);
        this.epochMillis = loaded.epochMillis();
        this.nextSlot = loaded.nextSlot();
        this.nextEventId = loaded.nextEventId();
        this.nextCrestRevision = loaded.nextCrestRevision();
        this.seasonNumber = loaded.seasonNumber();
        this.seasonStartsEpochMillis = loaded.seasonStartsEpochMillis();
        this.seasonEndsEpochMillis = loaded.seasonEndsEpochMillis();
        this.seasonWinnerHouseId = loaded.seasonWinnerHouseId();
        if (nextCrestRevision < 1) {
            throw new IOException("Persistent next crest revision is invalid");
        }
        if (nextSlot < 0 || nextSlot > config.maxPlayers()) {
            throw new IOException("Persistent next city slot is outside the configured player capacity");
        }
        Set<String> occupiedCoordinates = new HashSet<>();
        Set<Integer> occupiedSlots = new HashSet<>();
        Set<String> rulerNames = new HashSet<>();
        Set<String> kingdomNames = new HashSet<>();
        Set<String> capitalNames = new HashSet<>();
        Set<Long> crestRevisions = new HashSet<>();
        for (PlayerRecord player : loaded.players()) {
            if (player.slot < 0 || player.slot >= loaded.nextSlot()
                    || player.x < 0 || player.x >= config.worldWidth()
                    || player.y < 0 || player.y >= config.worldHeight()
                    || !terrain.canFoundCity(player.x, player.y)) {
                throw new IOException("Invalid city slot/coordinate in world file for " + player.playerId);
            }
            if (!occupiedSlots.add(player.slot)) {
                throw new IOException("Duplicate city slot in world file: " + player.slot);
            }
            if (!occupiedCoordinates.add(player.x + "," + player.y)) {
                throw new IOException("Duplicate city coordinate in world file: " + player.x + "," + player.y);
            }
            if (players.put(player.playerId, player) != null) {
                throw new IOException("Duplicate player ID in world file: " + player.playerId);
            }
            if (player.crowns < 0 || player.crowns > 1_000_000_000L
                    || player.levies < 0 || player.levies > 10_000
                    || player.levyProgressMinutes < 0 || player.levyProgressMinutes >= 5
                    || player.foundedEpochMillis < 0 || player.protectionEndsEpochMillis < 0) {
                throw new IOException("Invalid strategic economy state for " + player.playerId);
            }
            if ((player.crest == null) != (player.crestRevision == 0)
                    || player.crestRevision < 0 || player.crestRevision >= nextCrestRevision
                    || (player.crestRevision > 0 && !crestRevisions.add(player.crestRevision))) {
                throw new IOException("Invalid crest state for " + player.playerId);
            }
            if (player.foundingComplete()) {
                try {
                    String ruler = foundingName(player.playerName, "ruler name");
                    String kingdom = foundingName(player.kingdomName, "kingdom name");
                    String capital = foundingName(player.cityName, "capital name");
                    if (!rulerNames.add(nameKey(ruler)) || !kingdomNames.add(nameKey(kingdom))
                            || !capitalNames.add(nameKey(capital))) {
                        throw new IOException("Duplicate founding name in world file for " + player.playerId);
                    }
                } catch (ProtocolException e) {
                    throw new IOException("Invalid founding profile for " + player.playerId + ": " + e.getMessage(), e);
                }
            } else if ((player.kingdomName != null && !player.kingdomName.isBlank())
                    || player.foundedEpochMillis != 0 || player.protectionEndsEpochMillis != 0) {
                throw new IOException("Incomplete founding profile in world file for " + player.playerId);
            }
        }
        Map<String, Integer> npcCountByPatron = new HashMap<>();
        for (NpcCityRecord npc : loaded.npcCities()) {
            if (npc.npcId == null || !npc.npcId.matches("npc-[A-Za-z0-9-]{8,72}")
                    || !players.containsKey(npc.patronPlayerId)
                    || npc.cityName == null || npc.cityName.isBlank() || npc.cityName.length() > 48
                    || npc.x < 0 || npc.x >= config.worldWidth()
                    || npc.y < 0 || npc.y >= config.worldHeight()
                    || npc.x % config.citySpacing() != 0 || npc.y % config.citySpacing() != 0
                    || !terrain.canFoundCity(npc.x, npc.y)
                    || npc.level < 1 || npc.level > SharedWorld.NPC_CITIES_PER_PLAYER
                    || npc.levies < 0 || npc.levies > npcMaxLevies(npc.level)
                    || npc.crowns < 0 || npc.crowns > npcMaxCrowns(npc.level)
                    || npc.economyUpdatedEpochMillis < 0
                    || npc.levyProgressMinutes < 0 || npc.levyProgressMinutes >= npcLevyMinutes(npc.level)
                    || !occupiedCoordinates.add(npc.x + "," + npc.y)
                    || npcCities.put(npc.npcId, npc) != null) {
                throw new IOException("Invalid persistent NPC city: " + npc.npcId);
            }
            npcCountByPatron.merge(npc.patronPlayerId, 1, Integer::sum);
        }
        for (PlayerRecord player : players.values()) {
            if (!player.foundingComplete()) {
                continue;
            }
            int npcCount = npcCountByPatron.getOrDefault(player.playerId, 0);
            if (npcCount == 0) {
                try {
                    createNpcCities(player, player.cityName, nearbyNpcCoordinates(player.x, player.y));
                    dirty = true;
                } catch (ProtocolException e) {
                    throw new IOException("Could not migrate NPC cities for " + player.playerId, e);
                }
            } else if (npcCount != SharedWorld.NPC_CITIES_PER_PLAYER) {
                throw new IOException("Player " + player.playerId + " must have exactly "
                        + SharedWorld.NPC_CITIES_PER_PLAYER + " persistent NPC cities");
            }
        }
        events.addAll(loaded.events());
        for (WorldEventRecord event : events) {
            if (!players.containsKey(event.actorPlayerId)
                    || (!event.targetPlayerId.isBlank() && !players.containsKey(event.targetPlayerId)
                    && !npcCities.containsKey(event.targetPlayerId))) {
                throw new IOException("Persistent event refers to an unknown player: " + event.eventId);
            }
            restoreEventState(event);
        }
        Set<String> armyIds = new HashSet<>();
        for (ArmyRecord army : loaded.armies()) {
            if (!armyIds.add(army.armyId) || !players.containsKey(army.ownerPlayerId)
                    || (!players.containsKey(army.targetPlayerId) && !npcCities.containsKey(army.targetPlayerId))
                    || army.ownerPlayerId.equals(army.targetPlayerId)
                    || army.troops < 1 || army.troops > 10_000
                    || army.departedEpochMillis < 0 || army.arrivesEpochMillis < army.departedEpochMillis
                    || (!"ATTACK".equals(army.mission) && !"REINFORCE".equals(army.mission)
                    && !"NPC_ATTACK".equals(army.mission))) {
                throw new IOException("Invalid persistent army: " + army.armyId);
            }
            armies.add(army);
        }
        Set<String> transitIds = new HashSet<>(armyIds);
        for (ShipmentRecord shipment : loaded.shipments()) {
            if (!validTransit(transitIds, shipment.shipmentId, shipment.senderPlayerId,
                    shipment.targetPlayerId, shipment.resourceKey, shipment.amount,
                    shipment.departedEpochMillis, shipment.arrivesEpochMillis)) {
                throw new IOException("Invalid persistent shipment: " + shipment.shipmentId);
            }
            shipments.add(shipment);
        }
        for (DeliveryRecord delivery : loaded.deliveries()) {
            if (!validDelivery(transitIds, delivery.deliveryId, delivery.senderPlayerId,
                    delivery.targetPlayerId, delivery.resourceKey, delivery.amount,
                    delivery.arrivedEpochMillis)) {
                throw new IOException("Invalid persistent delivery: " + delivery.deliveryId);
            }
            deliveries.add(delivery);
        }
        for (MarketOfferRecord offer : loaded.marketOffers()) {
            if (!validMarketOffer(transitIds, offer)) {
                throw new IOException("Invalid persistent market offer: " + offer.offerId);
            }
            marketOffers.add(offer);
        }
        Set<String> houseNames = new HashSet<>();
        Set<String> houseTags = new HashSet<>();
        for (HouseRecord house : loaded.houses()) {
            try {
                if (houses.put(house.houseId, house) != null || !house.houseId.matches("[A-Za-z0-9-]{8,64}")
                        || !houseNames.add(nameKey(houseName(house.name)))
                        || !houseTags.add(houseTag(house.tag))
                        || house.seasonPoints < 0
                        || (house.crest == null) != (house.crestRevision == 0)
                        || house.crestRevision < 0 || house.crestRevision >= nextCrestRevision
                        || (house.crestRevision > 0 && !crestRevisions.add(house.crestRevision))) {
                    throw new IOException("Invalid or duplicate House: " + house.houseId);
                }
            } catch (ProtocolException e) {
                throw new IOException("Invalid House profile: " + house.houseId, e);
            }
        }
        for (PlayerRecord player : players.values()) {
            if (!player.houseId.isBlank()) {
                HouseRecord house = houses.get(player.houseId);
                if (house == null || !validHouseRole(player.houseRole)) {
                    throw new IOException("Invalid House membership for " + player.playerId);
                }
            } else if (!player.houseRole.isBlank()) {
                throw new IOException("House role without membership for " + player.playerId);
            }
        }
        for (HouseRecord house : houses.values()) {
            PlayerRecord sovereign = players.get(house.sovereignPlayerId);
            if (sovereign == null || !house.houseId.equals(sovereign.houseId)
                    || !"SOVEREIGN".equals(sovereign.houseRole)) {
                throw new IOException("House has no valid sovereign: " + house.houseId);
            }
            if (houseMemberCount(house.houseId) > HouseRecord.MAX_MEMBERS) {
                throw new IOException("House exceeds its 20-member capacity: " + house.houseId);
            }
        }
        Set<String> requestIds = new HashSet<>();
        for (HouseRequestRecord request : loaded.houseRequests()) {
            if (!requestIds.add(request.requestId) || !houses.containsKey(request.houseId)
                    || !players.containsKey(request.playerId)
                    || (!"INVITE".equals(request.type) && !"APPLICATION".equals(request.type))
                    || request.createdEpochMillis < 0 || request.expiresEpochMillis < request.createdEpochMillis) {
                throw new IOException("Invalid persistent House request: " + request.requestId);
            }
            houseRequests.add(request);
        }
        if (loaded.provinces().isEmpty()) {
            for (int y = 0; y < SharedWorld.PROVINCE_GRID; y++) {
                for (int x = 0; x < SharedWorld.PROVINCE_GRID; x++) {
                    provinces.put(provinceKey(x, y), new ProvinceRecord(x, y));
                }
            }
        } else {
            for (ProvinceRecord province : loaded.provinces()) {
                if (province.x < 0 || province.x >= SharedWorld.PROVINCE_GRID
                        || province.y < 0 || province.y >= SharedWorld.PROVINCE_GRID
                        || provinces.put(provinceKey(province.x, province.y), province) != null
                        || (!province.ownerHouseId.isBlank() && !houses.containsKey(province.ownerHouseId))
                        || (!province.occupierHouseId.isBlank() && !houses.containsKey(province.occupierHouseId))) {
                    throw new IOException("Invalid persistent province " + province.x + "," + province.y);
                }
            }
            if (provinces.size() != SharedWorld.SECTOR_COUNT) {
                throw new IOException("Persistent world must contain exactly " + SharedWorld.SECTOR_COUNT + " provinces");
            }
        }
        Set<String> warIds = new HashSet<>();
        for (HouseWarRecord war : loaded.wars()) {
            if (!houses.containsKey(war.attackerHouseId) || !houses.containsKey(war.defenderHouseId)
                    || war.attackerHouseId.equals(war.defenderHouseId) || !validWarPhase(war.phase)
                    || !warIds.add(war.warId)) {
                throw new IOException("Invalid persistent House war " + war.warId);
            }
            wars.add(war);
        }
        for (ArmyRecord army : armies) {
            if (("ATTACK".equals(army.mission) && !army.warId.isBlank() && !warIds.contains(army.warId))
                    || (("REINFORCE".equals(army.mission) || "NPC_ATTACK".equals(army.mission))
                    && !army.warId.isBlank())) {
                throw new IOException("Persistent army has an invalid war binding: " + army.armyId);
            }
        }
        Set<String> reportIds = new HashSet<>();
        for (BattleReportRecord report : loaded.reports()) {
            if (!reportIds.add(report.reportId) || !players.containsKey(report.attackerPlayerId)
                    || (!players.containsKey(report.defenderPlayerId) && !npcCities.containsKey(report.defenderPlayerId))
                    || report.provinceX < 0 || report.provinceX >= SharedWorld.PROVINCE_GRID
                    || report.provinceY < 0 || report.provinceY >= SharedWorld.PROVINCE_GRID) {
                throw new IOException("Invalid persistent battle report: " + report.reportId);
            }
            battleReports.add(report);
        }
        if (seasonNumber < 1 || seasonStartsEpochMillis < 0 || seasonEndsEpochMillis <= seasonStartsEpochMillis) {
            throw new IOException("Invalid persistent season state");
        }
    }

    synchronized JoinResult join(Messages.Hello hello) throws ProtocolException {
        if (hello.protocolVersion() != syxkingdoms.protocol.Protocol.VERSION) {
            throw new ProtocolException("PROTOCOL_MISMATCH", "Server requires protocol " + syxkingdoms.protocol.Protocol.VERSION);
        }
        if (!config.worldId().equals(hello.worldId())) {
            throw new ProtocolException("WORLD_MISMATCH", "This server hosts world '" + config.worldId() + "'");
        }
        validateIdentity(hello.playerId(), hello.authToken());
        boolean banned;
        try {
            banned = moderation.banned(hello.playerId());
        } catch (IOException e) {
            throw new ProtocolException("MODERATION_UNAVAILABLE", "Server moderation state could not be verified", e);
        }
        if (banned) {
            throw new ProtocolException("ACCOUNT_BANNED", "This player identity is banned from the persistent world");
        }
        if (!config.requiredGameVersion().equals(hello.gameVersion())) {
            throw new ProtocolException("GAME_VERSION_MISMATCH",
                    "Server requires Songs of Syx " + config.requiredGameVersion() + ", client reported " + hello.gameVersion());
        }
        if (!config.requiredModVersion().equals(hello.modVersion())) {
            throw new ProtocolException("MOD_VERSION_MISMATCH",
                    "Server requires Syx Kingdoms " + config.requiredModVersion() + ", client reported " + hello.modVersion());
        }
        if (!config.requiredModManifest().equals(hello.modManifest())) {
            throw new ProtocolException("MOD_SET_MISMATCH",
                    "Online worlds require exactly: " + config.requiredModManifest()
                            + "; client reported: " + hello.modManifest());
        }
        if (online.contains(hello.playerId())) {
            throw new ProtocolException("ALREADY_ONLINE", "This player identity already has an active session");
        }

        String authHash = hashToken(hello.playerId(), hello.authToken());
        PlayerRecord player = players.get(hello.playerId());
        if (player != null && !MessageDigest.isEqual(player.authHash.getBytes(StandardCharsets.US_ASCII),
                authHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new ProtocolException("AUTH_FAILED", "Authentication token does not match this player identity");
        }

        if (player == null || !player.foundingComplete()) {
            if (hello.playerName().isBlank() && hello.kingdomName().isBlank() && hello.cityName().isBlank()) {
                throw new ProtocolException("FOUNDING_REQUIRED",
                        "Found your persistent kingdom before entering the shared world");
            }
            String playerName = foundingName(hello.playerName(), "ruler name");
            String kingdomName = foundingName(hello.kingdomName(), "kingdom name");
            String cityName = foundingName(hello.cityName(), "capital name");
            ensureFoundingNamesAvailable(player, playerName, kingdomName, cityName);

            if (player == null) {
                if (players.size() >= config.maxPlayers()) {
                    throw new ProtocolException("WORLD_FULL", "This world has reached its player limit");
                }
                FoundingCluster cluster;
                if (hello.placementReference() == null || hello.placementReference().isBlank()) {
                    // Compatibility fallback for pre-placement clients and persisted test fixtures.
                    cluster = clusterForFounding();
                } else {
                    int[] requestedLocation = foundingLocation(hello.placementReference());
                    cluster = clusterForFounding(requestedLocation[0], requestedLocation[1]);
                }
                int[] coordinate = cluster.playerCoordinate();
                player = new PlayerRecord(hello.playerId(), authHash, nextSlot, coordinate[0], coordinate[1]);
                nextSlot++;
                players.put(player.playerId, player);
                createNpcCities(player, cityName, cluster.npcCoordinates());
            }
            long now = System.currentTimeMillis();
            player.playerName = playerName;
            player.kingdomName = kingdomName;
            player.cityName = cityName;
            player.foundedEpochMillis = now;
            player.protectionEndsEpochMillis = now + config.beginnerProtectionMillis();
            appendSystemEvent("KINGDOM_FOUNDED", player, "", kingdomName, cityName,
                    player.x, player.y);
        }

        player.gameVersion = hello.gameVersion();
        player.modVersion = hello.modVersion();
        player.lastSeenEpochMillis = System.currentTimeMillis();
        online.add(player.playerId);
        dirty = true;
        return new JoinResult(player, new Messages.Welcome(
                syxkingdoms.protocol.Protocol.VERSION,
                config.worldId(),
                config.worldName(),
                tick(),
                config.tickMillis(),
                config.fixedGameSpeed(),
                player.x,
                player.y,
                config.worldWidth(),
                config.worldHeight(),
                config.worldSeed()));
    }

    synchronized void heartbeat(String playerId, Messages.Heartbeat heartbeat) throws ProtocolException {
        PlayerRecord player = players.get(playerId);
        if (player == null || !online.contains(playerId)) {
            throw new ProtocolException("NOT_JOINED", "Heartbeat arrived before a valid join");
        }
        player.population = heartbeat.population();
        player.credits = heartbeat.credits();
        player.lastSeenEpochMillis = System.currentTimeMillis();
        dirty = true;
    }

    synchronized SyncResult initialSync(String playerId, long afterEventId) throws ProtocolException, IOException {
        return initialSync(playerId, afterEventId, 0);
    }

    synchronized SyncResult initialSync(String playerId, long afterEventId, long afterCrestRevision)
            throws ProtocolException, IOException {
        requireOnlinePlayer(playerId);
        SyncResult result = sync(playerId, afterEventId, afterCrestRevision, List.of());
        save();
        return result;
    }

    synchronized SyncResult applyUpdate(String playerId, Messages.ClientUpdate update) throws ProtocolException, IOException {
        boolean banned;
        try {
            banned = moderation.banned(playerId);
        } catch (IOException e) {
            throw new ProtocolException("MODERATION_UNAVAILABLE", "Server moderation state could not be verified", e);
        }
        if (banned) {
            throw new ProtocolException("ACCOUNT_BANNED", "This player identity is banned from the persistent world");
        }
        heartbeat(playerId, new Messages.Heartbeat(update.sequence(), update.population(), update.credits()));
        resolveStrategicTime(System.currentTimeMillis());
        List<Messages.CommandResult> results = applyCommands(playerId, update.commands());
        SyncResult result = sync(playerId, update.lastEventId(), update.lastCrestRevision(), results);
        if (!update.commands().isEmpty()) {
            save();
        }
        return result;
    }

    synchronized void leave(String playerId) {
        if (playerId != null && online.remove(playerId)) {
            PlayerRecord player = players.get(playerId);
            if (player != null) {
                player.lastSeenEpochMillis = System.currentTimeMillis();
            }
            dirty = true;
        }
    }

    synchronized Messages.WorldState snapshot() {
        List<PlayerRecord> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(player -> player.slot));
        List<Messages.City> cities = new ArrayList<>(ordered.size() + npcCities.size());
        for (PlayerRecord player : ordered) {
            cities.add(new Messages.City(player.playerId, player.playerName, player.kingdomName, player.cityName,
                    player.x, player.y, player.population, player.credits,
                    online.contains(player.playerId), player.lastSeenEpochMillis,
                    player.protectionEndsEpochMillis));
        }
        npcCities.values().stream().sorted(Comparator.comparing(npc -> npc.npcId)).forEach(npc ->
                cities.add(new Messages.City(npc.npcId, "NPC level " + npc.level,
                        "Neutral settlements", npc.cityName, npc.x, npc.y, npc.levies, npc.crowns,
                        false, npc.economyUpdatedEpochMillis, 0)));
        return new Messages.WorldState(tick(), System.currentTimeMillis(), config.fixedGameSpeed(),
                config.worldWidth(), config.worldHeight(), config.worldSeed(), List.copyOf(cities));
    }

    synchronized Metrics metrics() {
        return new Metrics(players.size(), npcCities.size(), online.size(), events.size(), armies.size(),
                shipments.size(), deliveries.size(), marketOffers.size(), houses.size(), wars.size());
    }

    synchronized void saveIfDirty() throws IOException {
        if (!dirty) {
            return;
        }
        WorldStore.save(config.worldFile(), config, epochMillis, nextSlot, new ArrayList<>(players.values()),
                new ArrayList<>(npcCities.values()),
                nextEventId, nextCrestRevision, new ArrayList<>(events), new ArrayList<>(armies),
                new ArrayList<>(shipments), new ArrayList<>(deliveries), new ArrayList<>(marketOffers),
                new ArrayList<>(houses.values()), new ArrayList<>(houseRequests),
                orderedProvinces(), new ArrayList<>(wars), new ArrayList<>(battleReports),
                seasonNumber, seasonStartsEpochMillis, seasonEndsEpochMillis, seasonWinnerHouseId);
        dirty = false;
    }

    synchronized void save() throws IOException {
        WorldStore.save(config.worldFile(), config, epochMillis, nextSlot, new ArrayList<>(players.values()),
                new ArrayList<>(npcCities.values()),
                nextEventId, nextCrestRevision, new ArrayList<>(events), new ArrayList<>(armies),
                new ArrayList<>(shipments), new ArrayList<>(deliveries), new ArrayList<>(marketOffers),
                new ArrayList<>(houses.values()), new ArrayList<>(houseRequests),
                orderedProvinces(), new ArrayList<>(wars), new ArrayList<>(battleReports),
                seasonNumber, seasonStartsEpochMillis, seasonEndsEpochMillis, seasonWinnerHouseId);
        dirty = false;
    }

    long tick() {
        return Math.max(0L, (System.currentTimeMillis() - epochMillis) / config.tickMillis());
    }

    synchronized void maintenance() {
        long now = System.currentTimeMillis();
        resolveStrategicTime(now);
        resolveArmies(now);
    }

    synchronized void resolveArmies(long now) {
        resolveStrategicTime(now);
        updateEconomy(now);
        var iterator = armies.iterator();
        while (iterator.hasNext()) {
            ArmyRecord army = iterator.next();
            if (army.arrivesEpochMillis > now) {
                continue;
            }
            PlayerRecord attacker = players.get(army.ownerPlayerId);
            PlayerRecord defender = players.get(army.targetPlayerId);
            if (attacker == null) {
                iterator.remove();
                dirty = true;
                continue;
            }
            if ("NPC_ATTACK".equals(army.mission)) {
                NpcCityRecord npc = npcCities.get(army.targetPlayerId);
                if (npc == null) {
                    attacker.levies = Math.min(10_000, attacker.levies + army.troops);
                } else {
                    resolveNpcBattle(army, attacker, npc, now);
                }
                iterator.remove();
                dirty = true;
                continue;
            }
            if (defender == null) {
                iterator.remove();
                dirty = true;
                continue;
            }
            if ("REINFORCE".equals(army.mission)) {
                int received = Math.min(army.troops, 10_000 - defender.levies);
                defender.levies += received;
                int returned = army.troops - received;
                if (returned > 0) {
                    attacker.levies = Math.min(10_000, attacker.levies + returned);
                }
                int px = SharedWorld.sectorX(defender.x);
                int py = SharedWorld.sectorY(defender.y);
                addReport(new BattleReportRecord(army.armyId, now, attacker.playerId,
                        defender.playerId, "", "REINFORCED", army.troops, defender.levies,
                        received, defender.levies, 0, px, py, 0));
                appendSystemEvent("REINFORCEMENT_ARRIVED", attacker, defender.playerId,
                        "", "", received, 0);
                iterator.remove();
                dirty = true;
                continue;
            }
            HouseWarRecord war = activeWar(attacker.houseId, defender.houseId, now);
            if (war == null || !war.warId.equals(army.warId)) {
                attacker.levies = Math.min(10_000, attacker.levies + army.troops);
                appendSystemEvent("ARMY_RETURNED", attacker, defender.playerId,
                        "War ended before arrival", "", army.troops, 0);
                iterator.remove();
                dirty = true;
                continue;
            }
            int defendingLevies = Math.min(defender.levies, army.troops);
            int attackerSurvivors = Math.max(0, army.troops - defendingLevies);
            int defenderSurvivors = Math.max(0, defender.levies - army.troops);
            defender.levies = defenderSurvivors;
            long stolenCrowns = 0;
            String outcome;
            if (attackerSurvivors > 0) {
                long dailyCap = Math.min(50_000L, Math.max(100L, defender.crowns / 10L));
                long alreadyLooted = lootAgainstToday(attacker.playerId, defender.playerId, now);
                long remainingCap = Math.max(0, dailyCap - alreadyLooted);
                stolenCrowns = Math.min(Math.min(Math.min(defender.crowns, attackerSurvivors * 5L), remainingCap),
                        1_000_000_000L - attacker.crowns);
                defender.crowns -= stolenCrowns;
                attacker.crowns += stolenCrowns;
                attacker.levies = Math.min(10_000, attacker.levies + attackerSurvivors);
                outcome = "ATTACKER_WIN";
            } else {
                outcome = "DEFENDER_WIN";
            }
            int provinceX = SharedWorld.sectorX(defender.x);
            int provinceY = SharedWorld.sectorY(defender.y);
            boolean farmLimited = attacksAgainstToday(attacker.playerId, defender.playerId, now) >= 3;
            int score = farmLimited ? 0 : attackerSurvivors > 0 ? Math.max(1, Math.min(25, attackerSurvivors / 20 + 1))
                    : Math.max(1, Math.min(15, defenderSurvivors / 25 + 1));
            if (attackerSurvivors > 0) {
                awardWarScore(war, attacker.houseId, score);
                if (!farmLimited) {
                    applyOccupation(war, attacker.houseId, defender.houseId, provinceX, provinceY,
                            Math.max(10, Math.min(40, score * 2)), now);
                }
            } else {
                awardWarScore(war, defender.houseId, score);
            }
            addReport(new BattleReportRecord(army.armyId, now, attacker.playerId, defender.playerId,
                    war.warId, outcome, army.troops, defendingLevies, attackerSurvivors,
                    defenderSurvivors, stolenCrowns, provinceX, provinceY, score));
            appendSystemEvent("BATTLE", attacker, defender.playerId, outcome,
                    Integer.toString(defenderSurvivors), attackerSurvivors, stolenCrowns);
            iterator.remove();
            dirty = true;
        }
        var shipmentIterator = shipments.iterator();
        while (shipmentIterator.hasNext()) {
            ShipmentRecord shipment = shipmentIterator.next();
            if (shipment.arrivesEpochMillis > now) {
                continue;
            }
            deliveries.add(new DeliveryRecord(shipment.shipmentId, shipment.senderPlayerId,
                    shipment.targetPlayerId, shipment.resourceKey, shipment.amount, now));
            PlayerRecord sender = players.get(shipment.senderPlayerId);
            appendSystemEvent("SHIPMENT_ARRIVED", sender, shipment.targetPlayerId,
                    shipment.resourceKey, "DELIVERY_READY", shipment.amount, now);
            shipmentIterator.remove();
            dirty = true;
        }
    }

    private void resolveNpcBattle(ArmyRecord army, PlayerRecord attacker, NpcCityRecord npc, long now) {
        int defendingLevies = Math.min(npc.levies, army.troops);
        int attackerSurvivors = Math.max(0, army.troops - defendingLevies);
        int defenderSurvivors = Math.max(0, npc.levies - army.troops);
        npc.levies = defenderSurvivors;
        long stolenCrowns = 0;
        String outcome;
        if (attackerSurvivors > 0) {
            long dailyCap = 500L * npc.level;
            long alreadyLooted = lootAgainstToday(attacker.playerId, npc.npcId, now);
            long remainingCap = Math.max(0, dailyCap - alreadyLooted);
            stolenCrowns = Math.min(Math.min(npc.crowns, attackerSurvivors * 5L), remainingCap);
            stolenCrowns = Math.min(stolenCrowns, 1_000_000_000L - attacker.crowns);
            npc.crowns -= stolenCrowns;
            attacker.crowns += stolenCrowns;
            attacker.levies = Math.min(10_000, attacker.levies + attackerSurvivors);
            outcome = "NPC_ATTACKER_WIN";
        } else {
            outcome = "NPC_DEFENDER_WIN";
        }
        int provinceX = SharedWorld.sectorX(npc.x);
        int provinceY = SharedWorld.sectorY(npc.y);
        addReport(new BattleReportRecord(army.armyId, now, attacker.playerId, npc.npcId,
                "", outcome, army.troops, defendingLevies, attackerSurvivors,
                defenderSurvivors, stolenCrowns, provinceX, provinceY, 0));
        appendSystemEvent("NPC_BATTLE", attacker, npc.npcId, outcome,
                npc.cityName, attackerSurvivors, stolenCrowns);
    }

    private SyncResult sync(String playerId, long afterEventId, long afterCrestRevision,
            List<Messages.CommandResult> results) {
        resolveArmies(System.currentTimeMillis());
        List<Messages.WorldEvent> visibleEvents = new ArrayList<>();
        long cursor = afterEventId;
        for (WorldEventRecord event : events) {
            if (event.eventId <= afterEventId) {
                continue;
            }
            cursor = event.eventId;
            if (eventVisibleTo(event, playerId)) {
                visibleEvents.add(event.message());
                if (visibleEvents.size() >= syxkingdoms.protocol.Protocol.MAX_EVENTS_PER_SYNC) {
                    break;
                }
            }
        }
        if (visibleEvents.size() < syxkingdoms.protocol.Protocol.MAX_EVENTS_PER_SYNC) {
            cursor = Math.max(cursor, nextEventId - 1);
        }
        List<PlayerRecord> changedCrests = players.values().stream()
                .filter(candidate -> candidate.crest != null && candidate.crestRevision > afterCrestRevision)
                .sorted(Comparator.comparingLong(candidate -> candidate.crestRevision))
                .toList();
        List<Messages.Crest> visibleCrests = new ArrayList<>();
        long crestCursor = afterCrestRevision;
        for (PlayerRecord candidate : changedCrests) {
            crestCursor = candidate.crestRevision;
            visibleCrests.add(new Messages.Crest(candidate.crestRevision, candidate.playerId,
                    candidate.crest.hash(), candidate.crest.pixelsEncoded(), candidate.crest.paletteHex()));
            if (visibleCrests.size() >= syxkingdoms.protocol.Protocol.MAX_CRESTS_PER_SYNC) {
                break;
            }
        }
        if (visibleCrests.size() < syxkingdoms.protocol.Protocol.MAX_CRESTS_PER_SYNC) {
            crestCursor = Math.max(crestCursor, nextCrestRevision - 1);
        }
        PlayerRecord player = players.get(playerId);
        return new SyncResult(snapshot(), new Messages.PlayerState(
                player.crowns, player.levies, player.protectionEndsEpochMillis),
                List.copyOf(results), cursor, List.copyOf(visibleEvents), crestCursor,
                List.copyOf(visibleCrests), houseMessages(), houseMemberMessages(),
                houseRequestMessages(player), provinceMessages(), warMessages(), reportsFor(player),
                new Messages.Season(seasonNumber, seasonStartsEpochMillis, seasonEndsEpochMillis,
                        seasonWinnerHouseId, System.currentTimeMillis() < seasonEndsEpochMillis ? "ACTIVE" : "COMPLETE"),
                relationsFor(playerId), armiesFor(player), deliveriesFor(playerId), marketOffers());
    }

    private boolean eventVisibleTo(WorldEventRecord event, String playerId) {
        if ("HOUSE_CHAT".equals(event.type)) {
            PlayerRecord listener = players.get(playerId);
            return listener != null && !listener.houseId.isBlank()
                    && listener.houseId.equals(event.argument);
        }
        if (!"LOCAL_CHAT".equals(event.type)) {
            return event.visibleTo(playerId);
        }
        if (event.actorPlayerId.equals(playerId)) {
            return true;
        }
        PlayerRecord listener = players.get(playerId);
        if (listener == null) {
            return false;
        }
        long dx = listener.x - event.value1;
        long dy = listener.y - event.value2;
        long radius = SharedWorld.LOCAL_CHAT_RADIUS;
        return dx * dx + dy * dy <= radius * radius;
    }

    private List<Messages.CommandResult> applyCommands(String playerId, List<Messages.Command> commands) {
        List<Messages.CommandResult> results = new ArrayList<>(commands.size());
        for (Messages.Command command : commands) {
            Messages.CommandResult existing = processedCommands.get(command.commandId());
            if (existing != null) {
                results.add(existing);
                continue;
            }
            Messages.CommandResult result;
            if (!allowCommand(playerId)) {
                result = rejected(command, "RATE_LIMITED", "At most 20 multiplayer actions are allowed per minute");
            } else {
                try {
                    result = executeCommand(playerId, command);
                } catch (ProtocolException e) {
                    result = rejected(command, e.code(), e.getMessage());
                }
            }
            processedCommands.put(command.commandId(), result);
            results.add(result);
        }
        return results;
    }

    private Messages.CommandResult executeCommand(String playerId, Messages.Command command) throws ProtocolException {
        PlayerRecord actor = requireOnlinePlayer(playerId);
        if (("CHAT".equals(command.type()) || "LOCAL_CHAT".equals(command.type())
                || "MAIL".equals(command.type()) || "HOUSE_CHAT".equals(command.type()))
                && muted(actor.playerId)) {
            throw new ProtocolException("ACCOUNT_MUTED", "This player identity is muted from chat and mail");
        }
        return switch (command.type()) {
            case "CHAT" -> {
                requireBlankTarget(command);
                String text = socialText(command.text(), "chat message");
                appendEvent(command, "CHAT", actor, "", text, "", 0, 0);
                yield accepted(command, "MESSAGE_SENT", "Global message sent");
            }
            case "LOCAL_CHAT" -> {
                requireBlankTarget(command);
                String text = socialText(command.text(), "local chat message");
                appendEvent(command, "LOCAL_CHAT", actor, "", text, "", actor.x, actor.y);
                yield accepted(command, "LOCAL_MESSAGE_SENT",
                        "Local message sent within " + SharedWorld.LOCAL_CHAT_RADIUS + " world tiles");
            }
            case "SET_CREST" -> {
                requireBlankTarget(command);
                if (command.value1() != 0 || command.value2() != 0) {
                    throw new ProtocolException("BAD_CREST", "Crest commands must not contain numeric values");
                }
                CrestDesign crest = CrestDesign.decode(command.argument(), command.text());
                if (!crest.hasVisiblePixels()) {
                    throw new ProtocolException("BAD_CREST", "A kingdom crest must contain at least one visible pixel");
                }
                actor.crest = crest;
                actor.crestRevision = nextCrestRevision++;
                appendEvent(command, "CREST_UPDATED", actor, "", crest.hash(), "",
                        actor.crestRevision, 0);
                yield accepted(command, "CREST_UPDATED", "Kingdom crest saved and synchronized");
            }
            case "MAIL" -> {
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                String text = socialText(command.text(), "mail message");
                appendEvent(command, "MAIL", actor, target.playerId, text, "", 0, 0);
                yield accepted(command, "MAIL_SENT", "Private mail sent to " + target.playerName);
            }
            case "CREATE_HOUSE" -> {
                requireBlankTarget(command);
                if (!actor.houseId.isBlank()) {
                    throw new ProtocolException("ALREADY_IN_HOUSE", "Leave the current House before creating another");
                }
                long now = System.currentTimeMillis();
                if (actor.houseEligibleEpochMillis > now) {
                    throw new ProtocolException("HOUSE_COOLDOWN", "House membership changes are on cooldown until "
                            + actor.houseEligibleEpochMillis);
                }
                if (houses.size() >= syxkingdoms.protocol.Protocol.MAX_HOUSES_PER_SYNC) {
                    throw new ProtocolException("HOUSE_LIMIT", "The world has reached its House capacity");
                }
                String name = houseName(command.text());
                String tag = houseTag(command.argument());
                ensureHouseNameAvailable(name, tag);
                HouseRecord house = new HouseRecord(command.commandId(), name, tag, actor.playerId, now);
                houses.put(house.houseId, house);
                joinHouse(actor, house, "SOVEREIGN", now);
                ProvinceRecord home = province(SharedWorld.sectorX(actor.x), SharedWorld.sectorY(actor.y));
                if (home.ownerHouseId.isBlank()) {
                    home.ownerHouseId = house.houseId;
                    house.capitalProvinceX = home.x;
                    house.capitalProvinceY = home.y;
                }
                appendEvent(command, "HOUSE_CREATED", actor, "", name, tag, home.x, home.y);
                yield accepted(command, "HOUSE_CREATED", "House " + name + " [" + tag + "] founded");
            }
            case "APPLY_HOUSE" -> {
                requireBlankTarget(command);
                requireNoHouse(actor);
                HouseRecord house = house(command.argument());
                long now = System.currentTimeMillis();
                requireHouseEligible(actor, now);
                requireHouseCapacity(house);
                HouseRequestRecord request = createHouseRequest(command.commandId(), house.houseId,
                        actor.playerId, "APPLICATION", now);
                houseRequests.add(request);
                appendEvent(command, "HOUSE_APPLICATION", actor, "", house.name, house.houseId, 0, 0);
                yield accepted(command, "HOUSE_APPLICATION", "Application sent to " + house.name);
            }
            case "INVITE_HOUSE" -> {
                HouseRecord house = requireHouseOfficer(actor, false);
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                requireNoHouse(target);
                requireHouseEligible(target, System.currentTimeMillis());
                requireHouseCapacity(house);
                long now = System.currentTimeMillis();
                HouseRequestRecord request = createHouseRequest(command.commandId(), house.houseId,
                        target.playerId, "INVITE", now);
                houseRequests.add(request);
                appendEvent(command, "HOUSE_INVITE", actor, target.playerId, house.name, house.houseId, 0, 0);
                yield accepted(command, "HOUSE_INVITE", "Invitation sent to " + target.playerName);
            }
            case "ACCEPT_HOUSE_REQUEST" -> {
                requireBlankTarget(command);
                HouseRequestRecord request = houseRequest(command.argument());
                HouseRecord house = house(request.houseId);
                PlayerRecord applicant = players.get(request.playerId);
                long now = System.currentTimeMillis();
                if ("INVITE".equals(request.type)) {
                    if (!actor.playerId.equals(applicant.playerId)) {
                        throw new ProtocolException("NOT_REQUEST_RECIPIENT", "Only the invited player can accept this request");
                    }
                } else {
                    HouseRecord actorHouse = requireHouseOfficer(actor, false);
                    if (!actorHouse.houseId.equals(house.houseId)) {
                        throw new ProtocolException("HOUSE_PERMISSION", "Only an officer of this House can accept the application");
                    }
                }
                requireNoHouse(applicant);
                requireHouseEligible(applicant, now);
                requireHouseCapacity(house);
                joinHouse(applicant, house, "MEMBER", now);
                houseRequests.removeIf(item -> item.playerId.equals(applicant.playerId));
                appendEvent(command, "HOUSE_JOINED", actor, applicant.playerId, house.name,
                        applicant.playerName, 0, 0);
                yield accepted(command, "HOUSE_JOINED", applicant.playerName + " joined " + house.name);
            }
            case "REJECT_HOUSE_REQUEST" -> {
                requireBlankTarget(command);
                HouseRequestRecord request = houseRequest(command.argument());
                if ("INVITE".equals(request.type)) {
                    if (!actor.playerId.equals(request.playerId)
                            && !house(request.houseId).houseId.equals(requireHouseOfficer(actor, false).houseId)) {
                        throw new ProtocolException("HOUSE_PERMISSION", "This request is not yours to reject");
                    }
                } else if (!house(request.houseId).houseId.equals(requireHouseOfficer(actor, false).houseId)
                        && !actor.playerId.equals(request.playerId)) {
                    throw new ProtocolException("HOUSE_PERMISSION", "This request is not yours to reject");
                }
                houseRequests.remove(request);
                appendEvent(command, "HOUSE_REQUEST_REJECTED", actor, request.playerId,
                        request.type, request.houseId, 0, 0);
                yield accepted(command, "HOUSE_REQUEST_REJECTED", "House request rejected");
            }
            case "LEAVE_HOUSE" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouse(actor);
                if ("SOVEREIGN".equals(actor.houseRole) && houseMemberCount(house.houseId) > 1) {
                    throw new ProtocolException("TRANSFER_LEADERSHIP", "Transfer the Sovereign role before leaving");
                }
                removeFromHouse(actor, house, System.currentTimeMillis());
                appendEvent(command, "HOUSE_LEFT", actor, "", house.name, house.houseId, 0, 0);
                yield accepted(command, "HOUSE_LEFT", "Left " + house.name);
            }
            case "KICK_HOUSE" -> {
                HouseRecord house = requireHouseOfficer(actor, true);
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                if (!house.houseId.equals(target.houseId) || "SOVEREIGN".equals(target.houseRole)) {
                    throw new ProtocolException("HOUSE_PERMISSION", "That player cannot be removed by this role");
                }
                if (!canManageRole(actor.houseRole, target.houseRole)) {
                    throw new ProtocolException("HOUSE_PERMISSION", "You cannot remove an equal or higher House role");
                }
                removeFromHouse(target, house, System.currentTimeMillis());
                appendEvent(command, "HOUSE_KICKED", actor, target.playerId, house.name, "", 0, 0);
                yield accepted(command, "HOUSE_KICKED", target.playerName + " removed from " + house.name);
            }
            case "SET_HOUSE_ROLE" -> {
                HouseRecord house = requireHouse(actor);
                if (!"SOVEREIGN".equals(actor.houseRole)) {
                    throw new ProtocolException("HOUSE_PERMISSION", "Only the Sovereign can assign House roles");
                }
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                if (!house.houseId.equals(target.houseId)) {
                    throw new ProtocolException("HOUSE_PERMISSION", "Target is not a member of your House");
                }
                String role = normalizeHouseRole(command.argument());
                if ("SOVEREIGN".equals(role)) {
                    actor.houseRole = "MARSHAL";
                    target.houseRole = "SOVEREIGN";
                    house.sovereignPlayerId = target.playerId;
                } else {
                    target.houseRole = role;
                }
                appendEvent(command, "HOUSE_ROLE", actor, target.playerId, role, house.houseId, 0, 0);
                yield accepted(command, "HOUSE_ROLE", target.playerName + " is now " + role);
            }
            case "HOUSE_ANNOUNCEMENT" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouseOfficer(actor, false);
                house.announcement = socialText(command.text(), "House announcement");
                appendEvent(command, "HOUSE_ANNOUNCEMENT", actor, "", house.announcement,
                        house.houseId, 0, 0);
                yield accepted(command, "HOUSE_ANNOUNCEMENT", "House announcement updated");
            }
            case "HOUSE_CHAT" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouse(actor);
                String text = socialText(command.text(), "House chat message");
                appendEvent(command, "HOUSE_CHAT", actor, "", text, house.houseId, 0, 0);
                yield accepted(command, "HOUSE_MESSAGE_SENT", "House message sent");
            }
            case "SET_HOUSE_CREST" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouse(actor);
                if (!"SOVEREIGN".equals(actor.houseRole) && !"HERALD".equals(actor.houseRole)) {
                    throw new ProtocolException("HOUSE_PERMISSION", "Only the Sovereign or Herald can edit the House crest");
                }
                CrestDesign crest = CrestDesign.decode(command.argument(), command.text());
                if (!crest.hasVisiblePixels()) {
                    throw new ProtocolException("BAD_CREST", "A House crest must contain a visible pixel");
                }
                house.crest = crest;
                house.crestRevision = nextCrestRevision++;
                appendEvent(command, "HOUSE_CREST_UPDATED", actor, "", house.name,
                        house.houseId, house.crestRevision, 0);
                yield accepted(command, "HOUSE_CREST_UPDATED", "House crest synchronized");
            }
            case "DECLARE_HOUSE_WAR" -> {
                requireBlankTarget(command);
                HouseRecord attackerHouse = requireHouseWarOfficer(actor);
                HouseRecord defenderHouse = house(command.argument());
                if (attackerHouse.houseId.equals(defenderHouse.houseId)) {
                    throw new ProtocolException("BAD_WAR", "A House cannot declare war on itself");
                }
                long now = System.currentTimeMillis();
                if (warBetween(attackerHouse.houseId, defenderHouse.houseId, now) != null) {
                    throw new ProtocolException("WAR_EXISTS", "These Houses already have a war or active truce");
                }
                wars.removeIf(war -> "ENDED".equals(war.phase));
                if (wars.size() >= syxkingdoms.protocol.Protocol.MAX_WARS_PER_SYNC) {
                    throw new ProtocolException("WAR_LIMIT", "The persistent world war capacity is full");
                }
                HouseWarRecord war = new HouseWarRecord(command.commandId(), attackerHouse.houseId,
                        defenderHouse.houseId, now, now + WAR_PREPARATION_MILLIS,
                        now + WAR_PREPARATION_MILLIS + WAR_DURATION_MILLIS, "PREPARING");
                wars.add(war);
                appendEvent(command, "WAR_DECLARED", actor, defenderHouse.sovereignPlayerId,
                        attackerHouse.name, defenderHouse.name, war.startsEpochMillis, war.endsEpochMillis);
                yield accepted(command, "WAR_DECLARED", "War preparation started; fighting begins in 12 hours");
            }
            case "SURRENDER_HOUSE_WAR" -> {
                requireBlankTarget(command);
                HouseRecord surrendering = requireHouseWarOfficer(actor);
                HouseWarRecord war = war(command.argument());
                if (!war.involves(surrendering.houseId) || !("ACTIVE".equals(war.phase) || "PREPARING".equals(war.phase))) {
                    throw new ProtocolException("BAD_WAR", "This House cannot surrender that war");
                }
                endWar(war, surrendering.houseId, System.currentTimeMillis());
                appendEvent(command, "WAR_SURRENDERED", actor, "", surrendering.name,
                        war.warId, war.attackerScore, war.defenderScore);
                yield accepted(command, "WAR_SURRENDERED", surrendering.name + " surrendered; 72-hour truce active");
            }
            case "CLAIM_PROVINCE" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouseWarOfficer(actor);
                int x = coordinate(command.value1(), "province x");
                int y = coordinate(command.value2(), "province y");
                ProvinceRecord province = province(x, y);
                if (!province.ownerHouseId.isBlank()) {
                    throw new ProtocolException("PROVINCE_OWNED", "This province already has an owner");
                }
                if (!canClaimProvince(house, actor, x, y)) {
                    throw new ProtocolException("PROVINCE_NOT_ADJACENT", "Claim must touch House territory or contain your capital city");
                }
                province.ownerHouseId = house.houseId;
                if (house.capitalProvinceX < 0) {
                    house.capitalProvinceX = x;
                    house.capitalProvinceY = y;
                }
                house.seasonPoints += 25;
                appendEvent(command, "PROVINCE_CLAIMED", actor, "", house.name, house.houseId, x, y);
                yield accepted(command, "PROVINCE_CLAIMED", "Province " + x + "," + y + " claimed");
            }
            case "ANNEX_PROVINCE" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouseWarOfficer(actor);
                int x = coordinate(command.value1(), "province x");
                int y = coordinate(command.value2(), "province y");
                ProvinceRecord province = province(x, y);
                if (province.ownerHouseId.isBlank() || province.ownerHouseId.equals(house.houseId)
                        || !house.houseId.equals(province.occupierHouseId)
                        || province.occupationInfluence < 100) {
                    throw new ProtocolException("ANNEXATION_NOT_READY", "Province requires 100% occupation influence against an enemy owner");
                }
                HouseRecord defenderHouse = house(province.ownerHouseId);
                HouseWarRecord war = activeWar(house.houseId, defenderHouse.houseId, System.currentTimeMillis());
                if (war == null) {
                    throw new ProtocolException("NOT_AT_WAR", "Annexation requires an active formal House war");
                }
                if (!canClaimProvince(house, actor, x, y)) {
                    throw new ProtocolException("PROVINCE_NOT_ADJACENT", "Annexed territory must touch your House territory");
                }
                if (defenderHouse.capitalProvinceX == x && defenderHouse.capitalProvinceY == y
                        && ownedProvinceCount(defenderHouse.houseId) > 1) {
                    throw new ProtocolException("CAPITAL_LAST", "A House capital can only be annexed after its other provinces");
                }
                province.ownerHouseId = house.houseId;
                province.occupierHouseId = "";
                province.occupationInfluence = 0;
                province.blockadeEndsEpochMillis = 0;
                province.tributeCrowns = 0;
                house.seasonPoints += 100;
                awardWarScore(war, house.houseId, 100);
                if (house.capitalProvinceX < 0) {
                    house.capitalProvinceX = x;
                    house.capitalProvinceY = y;
                }
                if (defenderHouse.capitalProvinceX == x && defenderHouse.capitalProvinceY == y) {
                    defenderHouse.capitalProvinceX = -1;
                    defenderHouse.capitalProvinceY = -1;
                }
                appendEvent(command, "PROVINCE_ANNEXED", actor, defenderHouse.sovereignPlayerId,
                        house.name, defenderHouse.name, x, y);
                yield accepted(command, "PROVINCE_ANNEXED", "Province " + x + "," + y + " annexed");
            }
            case "COLLECT_TRIBUTE" -> {
                requireBlankTarget(command);
                HouseRecord house = requireHouse(actor);
                int x = coordinate(command.value1(), "province x");
                int y = coordinate(command.value2(), "province y");
                ProvinceRecord province = province(x, y);
                if (!house.houseId.equals(province.occupierHouseId)
                        || province.occupationInfluence < 100) {
                    throw new ProtocolException("NOT_OCCUPIER", "Your House does not fully occupy this province");
                }
                long now = System.currentTimeMillis();
                if (province.lastTributeEpochMillis + TRIBUTE_INTERVAL_MILLIS > now) {
                    throw new ProtocolException("TRIBUTE_COOLDOWN", "Tribute can be collected once per day");
                }
                long amount = Math.min(province.tributeCrowns, 10_000L);
                if (amount < 1) {
                    throw new ProtocolException("NO_TRIBUTE", "No tribute is ready in this province");
                }
                actor.crowns = Math.min(1_000_000_000L, actor.crowns + amount);
                province.tributeCrowns -= amount;
                province.lastTributeEpochMillis = now;
                appendEvent(command, "TRIBUTE_COLLECTED", actor, "", house.name, house.houseId, amount, x * 100L + y);
                yield accepted(command, "TRIBUTE_COLLECTED", amount + " crowns of tribute collected");
            }
            case "STANCE" -> {
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                String stance = normalizeStance(command.argument());
                if ("WAR".equals(stance)) {
                    throw new ProtocolException("FORMAL_WAR_REQUIRED", "War requires a House declaration and 12-hour preparation phase");
                }
                appendEvent(command, "STANCE", actor, target.playerId, "", stance, 0, 0);
                yield accepted(command, "STANCE_RECORDED", "Stance toward " + target.playerName + " set to " + stance);
            }
            case "TRANSFER_CROWNS" -> {
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                long amount = command.value1();
                if (amount < 1 || amount > actor.crowns) {
                    throw new ProtocolException("INSUFFICIENT_CROWNS", "Transfer must be within the available crown balance");
                }
                if (target.crowns > 1_000_000_000L - amount) {
                    throw new ProtocolException("CROWN_CAP", "The target city cannot receive more crowns right now");
                }
                actor.crowns -= amount;
                target.crowns += amount;
                appendEvent(command, "TRANSFER", actor, target.playerId, "", "CROWNS", amount, 0);
                yield accepted(command, "TRANSFER_RECORDED", amount + " crowns transferred to " + target.playerName);
            }
            case "SEND_ARMY" -> {
                long now = System.currentTimeMillis();
                if (command.value1() < 1 || command.value1() > 10_000) {
                    throw new ProtocolException("BAD_ARMY_SIZE", "Army size must be between 1 and 10000");
                }
                int troops = (int) command.value1();
                if (troops > actor.levies) {
                    throw new ProtocolException("INSUFFICIENT_LEVIES", "Army size must be within the available levy balance");
                }
                long activeOwned = armies.stream().filter(army -> army.ownerPlayerId.equals(actor.playerId)).count();
                if (activeOwned >= 20) {
                    throw new ProtocolException("ARMY_LIMIT", "A city may have at most 20 traveling armies");
                }
                if (armies.size() >= syxkingdoms.protocol.Protocol.MAX_ARMIES_PER_SYNC) {
                    throw new ProtocolException("WORLD_ARMY_LIMIT", "The persistent world army capacity is currently full");
                }
                long departed = now;
                NpcCityRecord npc = npcCities.get(command.targetPlayerId());
                if (npc != null) {
                    long arrives = departed + travelMillis(actor.x, actor.y, npc.x, npc.y);
                    actor.levies -= troops;
                    armies.add(new ArmyRecord(command.commandId(), actor.playerId, npc.npcId,
                            troops, departed, arrives, "NPC_ATTACK", ""));
                    appendEvent(command, "NPC_ARMY_SENT", actor, npc.npcId,
                            npc.cityName, "PVE_EN_ROUTE", troops, arrives);
                    yield accepted(command, "NPC_ARMY_SENT",
                            troops + " levies are marching toward neutral " + npc.cityName);
                }
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                if (target.protectionEndsEpochMillis > now) {
                    throw new ProtocolException("TARGET_PROTECTED",
                            target.kingdomName + " is under beginner protection until "
                                    + target.protectionEndsEpochMillis);
                }
                HouseWarRecord activeWar = activeWar(actor.houseId, target.houseId, now);
                if (activeWar == null) {
                    throw new ProtocolException("NOT_AT_WAR", "Player armies require an active formal House war");
                }
                long arrives = departed + travelMillis(actor, target);
                if (actor.protectionEndsEpochMillis > now) {
                    actor.protectionEndsEpochMillis = 0;
                    appendSystemEvent("PROTECTION_ENDED", actor, "", actor.kingdomName,
                            "ARMY_LAUNCHED", 0, 0);
                }
                actor.levies -= troops;
                armies.add(new ArmyRecord(command.commandId(), actor.playerId, target.playerId,
                        troops, departed, arrives, "ATTACK", activeWar.warId));
                appendEvent(command, "ARMY_SENT", actor, target.playerId, "", "EN_ROUTE", troops, arrives);
                yield accepted(command, "ARMY_SENT", troops + " levies are marching toward " + target.playerName);
            }
            case "SEND_REINFORCEMENT" -> {
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                if (actor.houseId.isBlank() || !actor.houseId.equals(target.houseId)) {
                    throw new ProtocolException("NOT_HOUSE_ALLY", "Reinforcements may only be sent to a House member");
                }
                if (command.value1() < 1 || command.value1() > actor.levies) {
                    throw new ProtocolException("INSUFFICIENT_LEVIES", "Reinforcement size exceeds available levies");
                }
                int troops = (int) command.value1();
                long departed = System.currentTimeMillis();
                long arrives = departed + travelMillis(actor, target);
                actor.levies -= troops;
                armies.add(new ArmyRecord(command.commandId(), actor.playerId, target.playerId,
                        troops, departed, arrives, "REINFORCE", ""));
                appendEvent(command, "REINFORCEMENT_SENT", actor, target.playerId, "", "", troops, arrives);
                yield accepted(command, "REINFORCEMENT_SENT", troops + " levies sent to reinforce " + target.playerName);
            }
            case "SEND_RESOURCE" -> {
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                if ("WAR".equals(effectiveStance(actor.playerId, target.playerId))) {
                    throw new ProtocolException("AT_WAR", "Resource shipments cannot be sent to a city at war");
                }
                String resource = resourceKey(command.argument());
                if (command.value1() < 1 || command.value1() > 10_000) {
                    throw new ProtocolException("BAD_SHIPMENT", "Shipment amount must be between 1 and 10000");
                }
                if (tradeEntryCount() >= syxkingdoms.protocol.Protocol.MAX_MARKET_OFFERS_PER_SYNC) {
                    throw new ProtocolException("SHIPMENT_LIMIT", "The persistent shipment/delivery capacity is full");
                }
                int amount = (int) command.value1();
                long departed = System.currentTimeMillis();
                long arrives = departed + travelMillis(actor, target);
                shipments.add(new ShipmentRecord(command.commandId(), actor.playerId, target.playerId,
                        resource, amount, departed, arrives));
                appendEvent(command, "SHIPMENT_SENT", actor, target.playerId,
                        resource, "EN_ROUTE", amount, arrives);
                yield accepted(command, "SHIPMENT_SENT", amount + " " + resource + " sent to " + target.playerName);
            }
            case "CREATE_OFFER" -> {
                requireBlankTarget(command);
                String resource = resourceKey(command.argument());
                if (command.value1() < 1 || command.value1() > 10_000) {
                    throw new ProtocolException("BAD_OFFER", "Offer quantity must be between 1 and 10000");
                }
                if (command.value2() < 1 || command.value2() > 1_000_000) {
                    throw new ProtocolException("BAD_OFFER", "Offer unit price must be between 1 and 1000000 crowns");
                }
                long ownedOffers = marketOffers.stream()
                        .filter(offer -> offer.sellerPlayerId.equals(actor.playerId)).count();
                if (ownedOffers >= 20) {
                    throw new ProtocolException("OFFER_LIMIT", "A city may have at most 20 active market offers");
                }
                if (tradeEntryCount() >= syxkingdoms.protocol.Protocol.MAX_MARKET_OFFERS_PER_SYNC) {
                    throw new ProtocolException("MARKET_CAPACITY", "The persistent trade queue is full");
                }
                int quantity = (int) command.value1();
                long created = System.currentTimeMillis();
                marketOffers.add(new MarketOfferRecord(command.commandId(), actor.playerId,
                        resource, quantity, command.value2(), created));
                appendEvent(command, "OFFER_CREATED", actor, "", resource,
                        command.commandId(), quantity, command.value2());
                yield accepted(command, "OFFER_CREATED", quantity + " " + resource
                        + " listed at " + command.value2() + " crowns each");
            }
            case "BUY_OFFER" -> {
                requireBlankTarget(command);
                MarketOfferRecord offer = marketOffer(command.argument());
                if (offer.sellerPlayerId.equals(actor.playerId)) {
                    throw new ProtocolException("OWN_OFFER", "A city cannot buy its own market offer");
                }
                PlayerRecord seller = players.get(offer.sellerPlayerId);
                if ("WAR".equals(effectiveStance(actor.playerId, seller.playerId))) {
                    throw new ProtocolException("AT_WAR", "Cities at war cannot trade through the market");
                }
                if (command.value1() < 1 || command.value1() > offer.quantity) {
                    throw new ProtocolException("BAD_PURCHASE", "Purchase quantity exceeds the available offer");
                }
                int quantity = (int) command.value1();
                if (quantity != offer.quantity
                        && tradeEntryCount() >= syxkingdoms.protocol.Protocol.MAX_MARKET_OFFERS_PER_SYNC) {
                    throw new ProtocolException("MARKET_CAPACITY", "The persistent trade queue is full");
                }
                if (quantity > 1_000_000_000L / offer.unitPrice) {
                    throw new ProtocolException("BAD_PURCHASE", "Purchase total exceeds the supported crown range");
                }
                long total = quantity * offer.unitPrice;
                if (actor.crowns < total) {
                    throw new ProtocolException("INSUFFICIENT_CROWNS", "Not enough crowns to buy this offer");
                }
                if (seller.crowns > 1_000_000_000L - total) {
                    throw new ProtocolException("CROWN_CAP", "The seller cannot receive more crowns right now");
                }
                long departed = System.currentTimeMillis();
                long arrives = departed + travelMillis(seller, actor);
                actor.crowns -= total;
                seller.crowns += total;
                offer.quantity -= quantity;
                if (offer.quantity == 0) {
                    marketOffers.remove(offer);
                }
                shipments.add(new ShipmentRecord(command.commandId(), seller.playerId, actor.playerId,
                        offer.resourceKey, quantity, departed, arrives));
                appendEvent(command, "OFFER_BOUGHT", actor, seller.playerId, offer.resourceKey,
                        offer.offerId, quantity, total);
                yield accepted(command, "OFFER_BOUGHT", quantity + " " + offer.resourceKey
                        + " purchased for " + total + " crowns");
            }
            case "CANCEL_OFFER" -> {
                requireBlankTarget(command);
                MarketOfferRecord offer = marketOffer(command.argument());
                if (!offer.sellerPlayerId.equals(actor.playerId)) {
                    throw new ProtocolException("NOT_OFFER_OWNER", "Only the seller can cancel this market offer");
                }
                marketOffers.remove(offer);
                deliveries.add(new DeliveryRecord(command.commandId(), actor.playerId,
                        actor.playerId, offer.resourceKey, offer.quantity, System.currentTimeMillis()));
                appendEvent(command, "OFFER_CANCELLED", actor, "", offer.resourceKey,
                        offer.offerId, offer.quantity, offer.unitPrice);
                yield accepted(command, "OFFER_CANCELLED", "Market offer cancelled; unsold goods returned");
            }
            case "ACK_DELIVERY" -> {
                String deliveryId = command.argument();
                DeliveryRecord delivery = deliveries.stream()
                        .filter(item -> item.deliveryId.equals(deliveryId)
                                && item.targetPlayerId.equals(actor.playerId))
                        .findFirst().orElseThrow(() -> new ProtocolException(
                                "UNKNOWN_DELIVERY", "Delivery is not pending for this city"));
                deliveries.remove(delivery);
                appendEvent(command, "DELIVERY_CLAIMED", actor, delivery.senderPlayerId,
                        delivery.resourceKey, delivery.deliveryId, delivery.amount, 0);
                yield accepted(command, "DELIVERY_ACKNOWLEDGED", "Resource delivery acknowledged");
            }
            default -> throw new ProtocolException("UNKNOWN_COMMAND", "Unsupported multiplayer command: " + command.type());
        };
    }

    private void appendEvent(Messages.Command command, String type, PlayerRecord actor, String targetPlayerId,
            String text, String argument, long value1, long value2) {
        WorldEventRecord event = new WorldEventRecord(nextEventId++, System.currentTimeMillis(),
                command.commandId(), type, actor.playerId, actor.playerName, targetPlayerId,
                text, argument, value1, value2);
        events.add(event);
        restoreEventState(event);
        if (events.size() > MAX_RETAINED_EVENTS) {
            WorldEventRecord removed = events.removeFirst();
            processedCommands.remove(removed.commandId);
        }
        dirty = true;
    }

    private void appendSystemEvent(String type, PlayerRecord actor, String targetPlayerId,
            String text, String argument, long value1, long value2) {
        WorldEventRecord event = new WorldEventRecord(nextEventId++, System.currentTimeMillis(), "", type,
                actor.playerId, actor.playerName, targetPlayerId, text, argument, value1, value2);
        events.add(event);
        if (events.size() > MAX_RETAINED_EVENTS) {
            WorldEventRecord removed = events.removeFirst();
            processedCommands.remove(removed.commandId);
        }
        dirty = true;
    }

    private void restoreEventState(WorldEventRecord event) {
        if (!event.commandId.isBlank()) {
            processedCommands.put(event.commandId,
                    new Messages.CommandResult(event.commandId, true, resultCode(event.type), "Command already recorded"));
        }
        if ("STANCE".equals(event.type)) {
            stances.put(stanceKey(event.actorPlayerId, event.targetPlayerId), event.argument);
        }
    }

    private List<Messages.Relation> relationsFor(String playerId) {
        List<PlayerRecord> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(player -> player.slot));
        List<Messages.Relation> relations = new ArrayList<>(Math.max(0, ordered.size() - 1));
        for (PlayerRecord other : ordered) {
            if (other.playerId.equals(playerId)) {
                continue;
            }
            String your = stance(playerId, other.playerId);
            String their = stance(other.playerId, playerId);
            String effective = effectiveStance(playerId, other.playerId);
            relations.add(new Messages.Relation(other.playerId, other.playerName, your, their, effective));
        }
        return List.copyOf(relations);
    }

    private String stance(String actor, String target) {
        return stances.getOrDefault(stanceKey(actor, target), "NEUTRAL");
    }

    private static String stanceKey(String actor, String target) {
        return actor + '\u0000' + target;
    }

    private List<Messages.House> houseMessages() {
        return houses.values().stream().sorted(Comparator.comparing(house -> house.name))
                .map(house -> new Messages.House(house.houseId, house.name, house.tag,
                        house.sovereignPlayerId, house.announcement, houseMemberCount(house.houseId),
                        house.capitalProvinceX, house.capitalProvinceY, house.seasonPoints,
                        house.crest == null ? "" : house.crest.hash(),
                        house.crest == null ? "" : house.crest.pixelsEncoded(),
                        house.crest == null ? "" : house.crest.paletteHex()))
                .toList();
    }

    private List<Messages.HouseMember> houseMemberMessages() {
        return players.values().stream().filter(player -> !player.houseId.isBlank())
                .sorted(Comparator.comparingInt(player -> player.slot))
                .map(player -> new Messages.HouseMember(player.playerId, player.houseId,
                        player.houseRole, player.houseJoinedEpochMillis)).toList();
    }

    private List<Messages.HouseRequest> houseRequestMessages(PlayerRecord listener) {
        long now = System.currentTimeMillis();
        return houseRequests.stream().filter(request -> request.expiresEpochMillis > now)
                .filter(request -> request.playerId.equals(listener.playerId)
                        || (!listener.houseId.isBlank() && request.houseId.equals(listener.houseId)
                        && isOfficer(listener.houseRole)))
                .limit(syxkingdoms.protocol.Protocol.MAX_HOUSE_REQUESTS_PER_SYNC)
                .map(request -> new Messages.HouseRequest(request.requestId, request.houseId,
                        request.playerId, request.type, request.createdEpochMillis, request.expiresEpochMillis))
                .toList();
    }

    private List<Messages.Province> provinceMessages() {
        return orderedProvinces().stream().map(province -> new Messages.Province(province.x,
                province.y, province.ownerHouseId, province.occupierHouseId,
                province.occupationInfluence, province.blockadeEndsEpochMillis,
                province.tributeCrowns)).toList();
    }

    private List<Messages.HouseWar> warMessages() {
        return wars.stream().filter(war -> !"ENDED".equals(war.phase))
                .map(war -> new Messages.HouseWar(war.warId, war.attackerHouseId,
                        war.defenderHouseId, war.phase, war.declaredEpochMillis,
                        war.startsEpochMillis, war.endsEpochMillis, war.attackerScore,
                        war.defenderScore, war.truceEndsEpochMillis, war.surrenderedHouseId))
                .toList();
    }

    private List<Messages.BattleReport> reportsFor(PlayerRecord listener) {
        return battleReports.stream().filter(report -> reportVisibleTo(report, listener))
                .sorted(Comparator.comparingLong((BattleReportRecord report) -> report.epochMillis).reversed())
                .limit(syxkingdoms.protocol.Protocol.MAX_REPORTS_PER_SYNC)
                .map(BattleReportRecord::message).toList();
    }

    private boolean reportVisibleTo(BattleReportRecord report, PlayerRecord listener) {
        if (report.attackerPlayerId.equals(listener.playerId) || report.defenderPlayerId.equals(listener.playerId)) {
            return true;
        }
        PlayerRecord attacker = players.get(report.attackerPlayerId);
        PlayerRecord defender = players.get(report.defenderPlayerId);
        return !listener.houseId.isBlank() && ((attacker != null && listener.houseId.equals(attacker.houseId))
                || (defender != null && listener.houseId.equals(defender.houseId)));
    }

    private List<Messages.Army> armiesFor(PlayerRecord listener) {
        return armies.stream().map(army -> {
            PlayerRecord owner = players.get(army.ownerPlayerId);
            PlayerRecord target = players.get(army.targetPlayerId);
            boolean detailed = army.ownerPlayerId.equals(listener.playerId)
                    || army.targetPlayerId.equals(listener.playerId)
                    || (!listener.houseId.isBlank()
                    && ((owner != null && listener.houseId.equals(owner.houseId))
                    || (target != null && listener.houseId.equals(target.houseId))));
            return new Messages.Army(army.armyId, army.ownerPlayerId, army.targetPlayerId,
                    detailed ? army.troops : 0, army.departedEpochMillis, army.arrivesEpochMillis,
                    army.mission, army.warId);
        }).toList();
    }

    private List<ProvinceRecord> orderedProvinces() {
        return provinces.values().stream()
                .sorted(Comparator.comparingInt((ProvinceRecord p) -> p.y).thenComparingInt(p -> p.x))
                .toList();
    }

    private HouseRecord house(String houseId) throws ProtocolException {
        HouseRecord house = houses.get(houseId == null ? "" : houseId.trim());
        if (house == null) {
            throw new ProtocolException("UNKNOWN_HOUSE", "House does not exist");
        }
        return house;
    }

    private HouseRecord requireHouse(PlayerRecord player) throws ProtocolException {
        if (player.houseId.isBlank()) {
            throw new ProtocolException("HOUSE_REQUIRED", "Join or create a House first");
        }
        return house(player.houseId);
    }

    private HouseRecord requireHouseOfficer(PlayerRecord player, boolean strict) throws ProtocolException {
        HouseRecord house = requireHouse(player);
        boolean allowed = "SOVEREIGN".equals(player.houseRole) || "MARSHAL".equals(player.houseRole)
                || (!strict && "STEWARD".equals(player.houseRole));
        if (!allowed) {
            throw new ProtocolException("HOUSE_PERMISSION", "This House role cannot perform that action");
        }
        return house;
    }

    private HouseRecord requireHouseWarOfficer(PlayerRecord player) throws ProtocolException {
        HouseRecord house = requireHouse(player);
        if (!"SOVEREIGN".equals(player.houseRole) && !"MARSHAL".equals(player.houseRole)) {
            throw new ProtocolException("HOUSE_PERMISSION", "Only the Sovereign or Marshal controls wars and provinces");
        }
        return house;
    }

    private void requireNoHouse(PlayerRecord player) throws ProtocolException {
        if (!player.houseId.isBlank()) {
            throw new ProtocolException("ALREADY_IN_HOUSE", "Player already belongs to a House");
        }
    }

    private static void requireHouseEligible(PlayerRecord player, long now) throws ProtocolException {
        if (player.houseEligibleEpochMillis > now) {
            throw new ProtocolException("HOUSE_COOLDOWN", "House membership changes are on cooldown until "
                    + player.houseEligibleEpochMillis);
        }
    }

    private void requireHouseCapacity(HouseRecord house) throws ProtocolException {
        if (houseMemberCount(house.houseId) >= HouseRecord.MAX_MEMBERS) {
            throw new ProtocolException("HOUSE_FULL", "A House may have at most 20 members");
        }
    }

    private int houseMemberCount(String houseId) {
        return (int) players.values().stream().filter(player -> houseId.equals(player.houseId)).count();
    }

    private void joinHouse(PlayerRecord player, HouseRecord house, String role, long now) {
        player.houseId = house.houseId;
        player.houseRole = role;
        player.houseJoinedEpochMillis = now;
        player.houseEligibleEpochMillis = 0;
        dirty = true;
    }

    private void removeFromHouse(PlayerRecord player, HouseRecord house, long now) {
        player.houseId = "";
        player.houseRole = "";
        player.houseJoinedEpochMillis = 0;
        player.houseEligibleEpochMillis = now + HOUSE_CHANGE_COOLDOWN_MILLIS;
        houseRequests.removeIf(request -> request.playerId.equals(player.playerId));
        if (houseMemberCount(house.houseId) == 0) {
            houses.remove(house.houseId);
            provinces.values().forEach(province -> {
                if (house.houseId.equals(province.ownerHouseId)) province.ownerHouseId = "";
                if (house.houseId.equals(province.occupierHouseId)) {
                    province.occupierHouseId = "";
                    province.occupationInfluence = 0;
                }
            });
            wars.removeIf(war -> war.involves(house.houseId));
            houseRequests.removeIf(request -> request.houseId.equals(house.houseId));
        }
        dirty = true;
    }

    private HouseRequestRecord createHouseRequest(String id, String houseId, String playerId,
            String type, long now) throws ProtocolException {
        houseRequests.removeIf(request -> request.expiresEpochMillis <= now);
        if (houseRequests.size() >= syxkingdoms.protocol.Protocol.MAX_HOUSE_REQUESTS_PER_SYNC) {
            throw new ProtocolException("HOUSE_REQUEST_LIMIT", "The persistent House request queue is full");
        }
        boolean duplicate = houseRequests.stream().anyMatch(request -> request.houseId.equals(houseId)
                && request.playerId.equals(playerId) && request.type.equals(type)
                && request.expiresEpochMillis > now);
        if (duplicate) {
            throw new ProtocolException("HOUSE_REQUEST_EXISTS", "A matching House request is already pending");
        }
        return new HouseRequestRecord(id, houseId, playerId, type, now, now + HOUSE_REQUEST_MILLIS);
    }

    private HouseRequestRecord houseRequest(String requestId) throws ProtocolException {
        long now = System.currentTimeMillis();
        return houseRequests.stream().filter(request -> request.requestId.equals(requestId)
                && request.expiresEpochMillis > now).findFirst()
                .orElseThrow(() -> new ProtocolException("UNKNOWN_HOUSE_REQUEST", "House request is missing or expired"));
    }

    private static boolean canManageRole(String actorRole, String targetRole) {
        return houseRoleRank(actorRole) > houseRoleRank(targetRole);
    }

    private static int houseRoleRank(String role) {
        return switch (role) {
            case "SOVEREIGN" -> 5;
            case "MARSHAL" -> 4;
            case "STEWARD" -> 3;
            case "HERALD" -> 2;
            case "MEMBER" -> 1;
            default -> 0;
        };
    }

    private static boolean isOfficer(String role) {
        return houseRoleRank(role) >= houseRoleRank("STEWARD");
    }

    private static boolean validHouseRole(String role) {
        return houseRoleRank(role) > 0;
    }

    private static String normalizeHouseRole(String role) throws ProtocolException {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!validHouseRole(normalized)) {
            throw new ProtocolException("BAD_HOUSE_ROLE", "Role must be SOVEREIGN, MARSHAL, STEWARD, HERALD, or MEMBER");
        }
        return normalized;
    }

    private static String houseName(String input) throws ProtocolException {
        String value = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        if (value.length() < 3 || value.length() > 32) {
            throw new ProtocolException("BAD_HOUSE_NAME", "House name must contain 3-32 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != ' '
                    && character != '\'' && character != '-') {
                throw new ProtocolException("BAD_HOUSE_NAME", "House name contains unsupported characters");
            }
        }
        return value;
    }

    private static String houseTag(String input) throws ProtocolException {
        String value = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9]{3,5}")) {
            throw new ProtocolException("BAD_HOUSE_TAG", "House tag must be 3-5 letters or numbers");
        }
        return value;
    }

    private void ensureHouseNameAvailable(String name, String tag) throws ProtocolException {
        for (HouseRecord house : houses.values()) {
            if (nameKey(name).equals(nameKey(house.name))) {
                throw new ProtocolException("HOUSE_NAME_TAKEN", "That House name is already in use");
            }
            if (tag.equals(house.tag)) {
                throw new ProtocolException("HOUSE_TAG_TAKEN", "That House tag is already in use");
            }
        }
    }

    private ProvinceRecord province(int x, int y) throws ProtocolException {
        ProvinceRecord province = provinces.get(provinceKey(x, y));
        if (province == null) {
            throw new ProtocolException("BAD_PROVINCE", "Province coordinate is outside the world");
        }
        return province;
    }

    private static String provinceKey(int x, int y) {
        return x + "," + y;
    }

    private static int coordinate(long value, String field) throws ProtocolException {
        if (value < 0 || value >= SharedWorld.PROVINCE_GRID) {
            throw new ProtocolException("BAD_PROVINCE", field + " must be between 0 and 9");
        }
        return (int) value;
    }

    private boolean canClaimProvince(HouseRecord house, PlayerRecord actor, int x, int y) {
        if (SharedWorld.sectorX(actor.x) == x && SharedWorld.sectorY(actor.y) == y) {
            return true;
        }
        return provinces.values().stream().anyMatch(province -> house.houseId.equals(province.ownerHouseId)
                && Math.abs(province.x - x) + Math.abs(province.y - y) == 1);
    }

    private HouseWarRecord war(String warId) throws ProtocolException {
        return wars.stream().filter(war -> war.warId.equals(warId)).findFirst()
                .orElseThrow(() -> new ProtocolException("UNKNOWN_WAR", "House war does not exist"));
    }

    private HouseWarRecord warBetween(String first, String second, long now) {
        return wars.stream().filter(war -> war.involves(first) && war.involves(second)
                && (!"ENDED".equals(war.phase) || war.truceEndsEpochMillis > now)).findFirst().orElse(null);
    }

    private HouseWarRecord activeWar(String first, String second, long now) {
        if (first == null || second == null || first.isBlank() || second.isBlank()) {
            return null;
        }
        return wars.stream().filter(war -> "ACTIVE".equals(war.phase) && war.involves(first)
                && war.involves(second) && now < war.endsEpochMillis)
                .findFirst().orElse(null);
    }

    private static boolean validWarPhase(String phase) {
        return "PREPARING".equals(phase) || "ACTIVE".equals(phase)
                || "TRUCE".equals(phase) || "ENDED".equals(phase);
    }

    private void endWar(HouseWarRecord war, String surrenderedHouseId, long now) {
        war.phase = "TRUCE";
        war.surrenderedHouseId = surrenderedHouseId == null ? "" : surrenderedHouseId;
        war.truceEndsEpochMillis = now + TRUCE_MILLIS;
        HouseRecord attacker = houses.get(war.attackerHouseId);
        HouseRecord defender = houses.get(war.defenderHouseId);
        if (attacker != null) attacker.seasonPoints += war.attackerScore;
        if (defender != null) defender.seasonPoints += war.defenderScore;
        dirty = true;
    }

    void resolveStrategicTime(long now) {
        if (houseRequests.removeIf(request -> request.expiresEpochMillis <= now)) dirty = true;
        for (HouseWarRecord war : wars) {
            if ("PREPARING".equals(war.phase) && now >= war.startsEpochMillis) {
                war.phase = "ACTIVE";
                HouseRecord attacker = houses.get(war.attackerHouseId);
                if (attacker != null) {
                    PlayerRecord sovereign = players.get(attacker.sovereignPlayerId);
                    if (sovereign != null) appendSystemEvent("WAR_ACTIVE", sovereign, "",
                            attacker.name, war.defenderHouseId, war.endsEpochMillis, 0);
                }
                dirty = true;
            }
            if ("ACTIVE".equals(war.phase) && now >= war.endsEpochMillis) {
                endWar(war, "", now);
            } else if ("TRUCE".equals(war.phase) && now >= war.truceEndsEpochMillis) {
                war.phase = "ENDED";
                dirty = true;
            }
        }
        boolean domination = houses.values().stream().anyMatch(house -> ownedProvinceCount(house.houseId) >= 30);
        if (now >= seasonEndsEpochMillis || domination) {
            HouseRecord winner = houses.values().stream().max(Comparator.comparingInt(
                    house -> house.seasonPoints + ownedProvinceCount(house.houseId) * 100)).orElse(null);
            seasonWinnerHouseId = winner == null ? "" : winner.houseId;
            if (winner != null) {
                PlayerRecord sovereign = players.get(winner.sovereignPlayerId);
                if (sovereign != null) appendSystemEvent("SEASON_WON", sovereign, "", winner.name,
                        winner.houseId, seasonNumber, ownedProvinceCount(winner.houseId));
            }
            seasonNumber++;
            seasonStartsEpochMillis = now;
            seasonEndsEpochMillis = now + SEASON_MILLIS;
            houses.values().forEach(house -> house.seasonPoints = 0);
            wars.forEach(war -> {
                if (!"ENDED".equals(war.phase)) {
                    war.phase = "ENDED";
                    war.truceEndsEpochMillis = now;
                }
            });
            provinces.values().forEach(province -> {
                province.occupierHouseId = "";
                province.occupationInfluence = 0;
                province.blockadeEndsEpochMillis = 0;
                province.tributeCrowns = 0;
            });
            dirty = true;
        }
    }

    private int ownedProvinceCount(String houseId) {
        return (int) provinces.values().stream().filter(province -> houseId.equals(province.ownerHouseId)).count();
    }

    private void awardWarScore(HouseWarRecord war, String houseId, int score) {
        if (war.attackerHouseId.equals(houseId)) war.attackerScore += score;
        else if (war.defenderHouseId.equals(houseId)) war.defenderScore += score;
    }

    private void applyOccupation(HouseWarRecord war, String attackingHouseId, String defendingHouseId,
            int x, int y, int influence, long now) {
        ProvinceRecord province = provinces.get(provinceKey(x, y));
        if (province == null || !defendingHouseId.equals(province.ownerHouseId)) return;
        if (!attackingHouseId.equals(province.occupierHouseId)) {
            province.occupierHouseId = attackingHouseId;
            province.occupationInfluence = 0;
        }
        province.occupationInfluence = Math.min(100, province.occupationInfluence + influence);
        province.blockadeEndsEpochMillis = Math.max(province.blockadeEndsEpochMillis, now + 6L * 3_600_000L);
        province.tributeCrowns = Math.min(1_000_000_000L, province.tributeCrowns + influence * 25L);
        if (province.occupationInfluence == 100) {
            awardWarScore(war, attackingHouseId, 50);
            HouseRecord house = houses.get(attackingHouseId);
            if (house != null) house.seasonPoints += 50;
        }
    }

    private long lootAgainstToday(String attackerId, String defenderId, long now) {
        long dayStart = now - Math.floorMod(now, 86_400_000L);
        return battleReports.stream().filter(report -> report.epochMillis >= dayStart
                && report.attackerPlayerId.equals(attackerId)
                && report.defenderPlayerId.equals(defenderId)).mapToLong(report -> report.crownsLooted).sum();
    }

    private long attacksAgainstToday(String attackerId, String defenderId, long now) {
        long dayStart = now - Math.floorMod(now, 86_400_000L);
        return battleReports.stream().filter(report -> report.epochMillis >= dayStart
                && report.attackerPlayerId.equals(attackerId)
                && report.defenderPlayerId.equals(defenderId)
                && !"REINFORCED".equals(report.outcome)).count();
    }

    private void addReport(BattleReportRecord report) {
        battleReports.add(report);
        while (battleReports.size() > syxkingdoms.protocol.Protocol.MAX_REPORTS_PER_SYNC) {
            battleReports.removeFirst();
        }
        dirty = true;
    }

    private boolean allowCommand(String playerId) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = commandWindows.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && times.getFirst() < now - 60_000) {
            times.removeFirst();
        }
        if (times.size() >= 20) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    private PlayerRecord requireOnlinePlayer(String playerId) throws ProtocolException {
        PlayerRecord player = players.get(playerId);
        if (player == null || !online.contains(playerId)) {
            throw new ProtocolException("NOT_JOINED", "Multiplayer action arrived before a valid join");
        }
        return player;
    }

    private PlayerRecord targetPlayer(PlayerRecord actor, String targetId) throws ProtocolException {
        PlayerRecord target = players.get(targetId);
        if (target == null) {
            throw new ProtocolException("UNKNOWN_PLAYER", "Target player does not exist in the persistent world");
        }
        if (target.playerId.equals(actor.playerId)) {
            throw new ProtocolException("BAD_TARGET", "A city cannot target itself with this action");
        }
        return target;
    }

    private static void requireBlankTarget(Messages.Command command) throws ProtocolException {
        if (!command.targetPlayerId().isBlank()) {
            throw new ProtocolException("BAD_TARGET", "This command must not specify a target player");
        }
    }

    private static String socialText(String input, String field) throws ProtocolException {
        String value = input == null ? "" : input.trim().replaceAll("\\s+", " ");
        if (value.isEmpty() || value.length() > 240) {
            throw new ProtocolException("BAD_TEXT", field + " must contain 1-240 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new ProtocolException("BAD_TEXT", field + " contains a control character");
            }
        }
        return value;
    }

    private static String normalizeStance(String value) throws ProtocolException {
        String stance = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"NEUTRAL".equals(stance) && !"ALLY".equals(stance) && !"WAR".equals(stance)) {
            throw new ProtocolException("BAD_STANCE", "Stance must be NEUTRAL, ALLY, or WAR");
        }
        return stance;
    }

    private static Messages.CommandResult accepted(Messages.Command command, String code, String message) {
        return new Messages.CommandResult(command.commandId(), true, code, message);
    }

    private static Messages.CommandResult rejected(Messages.Command command, String code, String message) {
        return new Messages.CommandResult(command.commandId(), false, code, message);
    }

    private static String resultCode(String eventType) {
        return switch (eventType) {
            case "CHAT" -> "MESSAGE_SENT";
            case "LOCAL_CHAT" -> "LOCAL_MESSAGE_SENT";
            case "CREST_UPDATED" -> "CREST_UPDATED";
            case "MAIL" -> "MAIL_SENT";
            case "STANCE" -> "STANCE_RECORDED";
            case "TRANSFER" -> "TRANSFER_RECORDED";
            case "ARMY_SENT" -> "ARMY_SENT";
            case "SHIPMENT_SENT" -> "SHIPMENT_SENT";
            case "DELIVERY_CLAIMED" -> "DELIVERY_ACKNOWLEDGED";
            case "OFFER_CREATED" -> "OFFER_CREATED";
            case "OFFER_BOUGHT" -> "OFFER_BOUGHT";
            case "OFFER_CANCELLED" -> "OFFER_CANCELLED";
            default -> "COMMAND_RECORDED";
        };
    }

    private String effectiveStance(String first, String second) {
        PlayerRecord firstPlayer = players.get(first);
        PlayerRecord secondPlayer = players.get(second);
        if (firstPlayer != null && secondPlayer != null) {
            if (!firstPlayer.houseId.isBlank() && firstPlayer.houseId.equals(secondPlayer.houseId)) {
                return "ALLY";
            }
            if (activeWar(firstPlayer.houseId, secondPlayer.houseId, System.currentTimeMillis()) != null) {
                return "WAR";
            }
        }
        String firstStance = stance(first, second);
        String secondStance = stance(second, first);
        return "ALLY".equals(firstStance) && "ALLY".equals(secondStance) ? "ALLY" : "NEUTRAL";
    }

    private long travelMillis(PlayerRecord from, PlayerRecord to) {
        return travelMillis(from.x, from.y, to.x, to.y);
    }

    private long travelMillis(int fromX, int fromY, int toX, int toY) {
        double distance = Math.hypot((double) fromX - toX, (double) fromY - toY);
        return Math.max(60_000L, Math.min(86_400_000L, (long) Math.ceil(distance * 1_000.0)));
    }

    private void updateEconomy(long now) {
        for (PlayerRecord player : players.values()) {
            long elapsed = Math.max(0, now - player.economyUpdatedEpochMillis);
            long minutes = elapsed / 60_000L;
            if (minutes <= 0) {
                continue;
            }
            player.economyUpdatedEpochMillis = now - elapsed % 60_000L;
            player.crowns = Math.min(1_000_000_000L, player.crowns + minutes);
            long levyMinutes = player.levyProgressMinutes + minutes;
            player.levies = (int) Math.min(10_000L, player.levies + levyMinutes / 5L);
            player.levyProgressMinutes = (int) (levyMinutes % 5L);
            dirty = true;
        }
        for (NpcCityRecord npc : npcCities.values()) {
            long elapsed = Math.max(0, now - npc.economyUpdatedEpochMillis);
            long minutes = elapsed / 60_000L;
            if (minutes <= 0) {
                continue;
            }
            npc.economyUpdatedEpochMillis = now - elapsed % 60_000L;
            npc.crowns = Math.min(npcMaxCrowns(npc.level), npc.crowns + minutes * npc.level);
            long levyMinutes = npc.levyProgressMinutes + minutes;
            int interval = npcLevyMinutes(npc.level);
            npc.levies = (int) Math.min(npcMaxLevies(npc.level), npc.levies + levyMinutes / interval);
            npc.levyProgressMinutes = (int) (levyMinutes % interval);
            dirty = true;
        }
    }

    private List<Messages.Delivery> deliveriesFor(String playerId) {
        return deliveries.stream().filter(delivery -> delivery.targetPlayerId.equals(playerId))
                .map(DeliveryRecord::message).toList();
    }

    private List<Messages.MarketOffer> marketOffers() {
        return marketOffers.stream()
                .sorted(Comparator.comparingLong(offer -> offer.createdEpochMillis))
                .map(offer -> offer.message(players.get(offer.sellerPlayerId))).toList();
    }

    private MarketOfferRecord marketOffer(String offerId) throws ProtocolException {
        if (offerId == null || !offerId.matches("[A-Za-z0-9-]{8,64}")) {
            throw new ProtocolException("BAD_OFFER", "Market offer ID is invalid");
        }
        return marketOffers.stream().filter(offer -> offer.offerId.equals(offerId)).findFirst()
                .orElseThrow(() -> new ProtocolException("UNKNOWN_OFFER", "Market offer no longer exists"));
    }

    private int tradeEntryCount() {
        return marketOffers.size() + shipments.size() + deliveries.size();
    }

    private static String resourceKey(String value) throws ProtocolException {
        String resource = value == null ? "" : value.trim();
        if (!resource.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new ProtocolException("BAD_RESOURCE", "Resource key contains unsupported characters");
        }
        return resource;
    }

    private boolean muted(String playerId) throws ProtocolException {
        try {
            return moderation.muted(playerId);
        } catch (IOException e) {
            throw new ProtocolException("MODERATION_UNAVAILABLE", "Server moderation state could not be verified", e);
        }
    }

    private boolean validTransit(Set<String> ids, String id, String senderId, String targetId,
            String resource, int amount, long departed, long arrives) {
        return id != null && id.matches("[A-Za-z0-9-]{8,64}") && ids.add(id)
                && players.containsKey(senderId) && players.containsKey(targetId) && !senderId.equals(targetId)
                && resource != null && resource.matches("[A-Za-z0-9._-]{1,64}")
                && amount >= 1 && amount <= 10_000 && departed >= 0 && arrives >= departed;
    }

    private boolean validDelivery(Set<String> ids, String id, String senderId, String targetId,
            String resource, int amount, long arrived) {
        return id != null && id.matches("[A-Za-z0-9-]{8,64}") && ids.add(id)
                && players.containsKey(senderId) && players.containsKey(targetId)
                && resource != null && resource.matches("[A-Za-z0-9._-]{1,64}")
                && amount >= 1 && amount <= 10_000 && arrived >= 0;
    }

    private boolean validMarketOffer(Set<String> ids, MarketOfferRecord offer) {
        return offer.offerId != null && offer.offerId.matches("[A-Za-z0-9-]{8,64}")
                && ids.add(offer.offerId) && players.containsKey(offer.sellerPlayerId)
                && offer.resourceKey != null && offer.resourceKey.matches("[A-Za-z0-9._-]{1,64}")
                && offer.quantity >= 1 && offer.quantity <= 10_000
                && offer.unitPrice >= 1 && offer.unitPrice <= 1_000_000
                && offer.createdEpochMillis >= 0;
    }

    private static void validateIdentity(String playerId, String token) throws ProtocolException {
        if (playerId == null || !playerId.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new ProtocolException("BAD_IDENTITY", "Player ID must contain 3-64 safe characters");
        }
        if (token == null || token.length() < 24 || token.length() > 256) {
            throw new ProtocolException("BAD_IDENTITY", "Authentication token must contain 24-256 characters");
        }
    }

    private static String foundingName(String input, String field) throws ProtocolException {
        String value = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        if (value.length() < 3 || value.length() > 32) {
            throw new ProtocolException("BAD_FOUNDING_NAME", field + " must contain 3-32 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != ' '
                    && character != '\'' && character != '-') {
                throw new ProtocolException("BAD_FOUNDING_NAME",
                        field + " may only use letters, numbers, spaces, apostrophes, and hyphens");
            }
        }
        if (RESERVED_NAMES.contains(nameKey(value))) {
            throw new ProtocolException("RESERVED_NAME", field + " is reserved by the server");
        }
        return value;
    }

    private static String nameKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private void ensureFoundingNamesAvailable(PlayerRecord current, String ruler, String kingdom, String capital)
            throws ProtocolException {
        String rulerKey = nameKey(ruler);
        String kingdomKey = nameKey(kingdom);
        String capitalKey = nameKey(capital);
        for (PlayerRecord other : players.values()) {
            if (other == current || !other.foundingComplete()) {
                continue;
            }
            if (rulerKey.equals(nameKey(other.playerName))) {
                throw new ProtocolException("RULER_NAME_TAKEN", "That ruler name already exists in this world");
            }
            if (kingdomKey.equals(nameKey(other.kingdomName))) {
                throw new ProtocolException("KINGDOM_NAME_TAKEN", "That kingdom name already exists in this world");
            }
            if (capitalKey.equals(nameKey(other.cityName))) {
                throw new ProtocolException("CAPITAL_NAME_TAKEN", "That capital name already exists in this world");
            }
        }
    }

    private static String hashToken(String playerId, String token) throws ProtocolException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(playerId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new ProtocolException("SERVER_ERROR", "SHA-256 is unavailable", e);
        }
    }

    private long[] buildCityCoordinates() {
        long columns = (config.worldWidth() - 1L) / config.citySpacing() + 1L;
        long rows = (config.worldHeight() - 1L) / config.citySpacing() + 1L;
        long capacity = columns * rows;
        long hash = Integer.toUnsignedLong(config.worldSeed().hashCode());
        long start = Math.floorMod(hash * 1_103_515_245L + 12_345L, capacity);
        long step = capacity == 1 ? 1 : Math.floorMod(hash * 2_654_435_761L + 1L, capacity - 1L) + 1L;
        while (greatestCommonDivisor(step, capacity) != 1L) {
            step++;
            if (step >= capacity) {
                step = 1L;
            }
        }
        long[] result = new long[(int) terrain.cityCoordinateCapacity(config.citySpacing())];
        int count = 0;
        for (long candidate = 0; candidate < capacity; candidate++) {
            long index = Math.floorMod(start + candidate * step, capacity);
            int x = (int) (index % columns) * config.citySpacing();
            int y = (int) (index / columns) * config.citySpacing();
            if (!terrain.canFoundCity(x, y)) {
                continue;
            }
            result[count++] = coordinateKey(x, y);
        }
        if (count != result.length) {
            throw new IllegalStateException("Shared-world buildable coordinate count changed during initialization");
        }
        return result;
    }

    private FoundingCluster clusterForFounding() throws ProtocolException {
        for (long coordinate : cityCoordinates) {
            int[] decoded = decodeCoordinate(coordinate);
            try {
                return clusterForFounding(decoded[0], decoded[1]);
            } catch (ProtocolException ignored) {
                // Continue until a full isolated four-city cluster is found.
            }
        }
        throw new ProtocolException("WORLD_FULL",
                "No isolated four-city founding cluster remains on the persistent world");
    }

    private FoundingCluster clusterForFounding(int x, int y) throws ProtocolException {
        Set<Long> occupied = new HashSet<>();
        for (PlayerRecord existing : players.values()) {
            occupied.add(coordinateKey(existing.x, existing.y));
        }
        for (NpcCityRecord npc : npcCities.values()) {
            occupied.add(coordinateKey(npc.x, npc.y));
        }
        if (x < 0 || x >= SharedWorld.WIDTH || y < 0 || y >= SharedWorld.HEIGHT
                || !terrain.canFoundCity(x, y)) {
            throw new ProtocolException("FOUNDING_LOCATION_INVALID",
                    "Choose a plains or forest location inside the shared world");
        }
        if (occupied.contains(coordinateKey(x, y))) {
            throw new ProtocolException("FOUNDING_LOCATION_TAKEN", "Another city already occupies that location");
        }
        long playerMinimumSquared = (long) SharedWorld.PLAYER_CITY_MIN_DISTANCE
                * SharedWorld.PLAYER_CITY_MIN_DISTANCE;
        boolean isolated = players.values().stream()
                .allMatch(player -> distanceSquared(x, y, player.x, player.y) >= playerMinimumSquared);
        if (!isolated) {
            throw new ProtocolException("FOUNDING_LOCATION_TOO_CLOSE",
                    "New capitals must be at least " + SharedWorld.PLAYER_CITY_MIN_DISTANCE
                            + " shared-world tiles from another player");
        }
        try {
            return new FoundingCluster(new int[] { x, y }, nearbyNpcCoordinates(x, y));
        } catch (ProtocolException e) {
            throw new ProtocolException("FOUNDING_CLUSTER_BLOCKED",
                    "Choose a more open location that can support three nearby neutral cities");
        }
    }

    private static int[] foundingLocation(String input) throws ProtocolException {
        if (input == null || input.isBlank()) {
            throw new ProtocolException("FOUNDING_LOCATION_REQUIRED",
                    "Select your capital location on the shared-world map");
        }
        String[] parts = input.trim().split(",", -1);
        try {
            if (parts.length != 2) throw new NumberFormatException();
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (NumberFormatException e) {
            throw new ProtocolException("FOUNDING_LOCATION_INVALID",
                    "The requested shared-world coordinate is invalid");
        }
    }

    private List<Long> nearbyNpcCoordinates(int playerX, int playerY) throws ProtocolException {
        Set<Long> occupied = new HashSet<>();
        for (PlayerRecord player : players.values()) {
            occupied.add(coordinateKey(player.x, player.y));
        }
        for (NpcCityRecord npc : npcCities.values()) {
            occupied.add(coordinateKey(npc.x, npc.y));
        }
        occupied.add(coordinateKey(playerX, playerY));
        long minimumSquared = (long) SharedWorld.NPC_CITY_MIN_DISTANCE * SharedWorld.NPC_CITY_MIN_DISTANCE;
        long maximumSquared = (long) SharedWorld.NPC_CITY_MAX_DISTANCE * SharedWorld.NPC_CITY_MAX_DISTANCE;
        List<Long> selected = new ArrayList<>(SharedWorld.NPC_CITIES_PER_PLAYER);
        for (long coordinate : cityCoordinates) {
            if (occupied.contains(coordinate)) {
                continue;
            }
            int x = (int) (coordinate >>> 32);
            int y = (int) coordinate;
            long anchorDistance = distanceSquared(x, y, playerX, playerY);
            if (anchorDistance < minimumSquared || anchorDistance > maximumSquared) {
                continue;
            }
            boolean clearOfOtherPlayers = players.values().stream()
                    .filter(player -> player.x != playerX || player.y != playerY)
                    .allMatch(player -> distanceSquared(x, y, player.x, player.y) >= maximumSquared);
            if (!clearOfOtherPlayers || selected.stream().anyMatch(other -> {
                int otherX = (int) (other >>> 32);
                int otherY = (int) (long) other;
                return distanceSquared(x, y, otherX, otherY) < minimumSquared;
            })) {
                continue;
            }
            selected.add(coordinate);
            if (selected.size() == SharedWorld.NPC_CITIES_PER_PLAYER) {
                return List.copyOf(selected);
            }
        }
        throw new ProtocolException("WORLD_FULL", "This location cannot support three nearby neutral cities");
    }

    private void createNpcCities(PlayerRecord player, String capitalName, List<Long> coordinates)
            throws ProtocolException {
        if (coordinates.size() != SharedWorld.NPC_CITIES_PER_PLAYER) {
            throw new ProtocolException("SERVER_ERROR", "A founding cluster must contain exactly three NPC cities");
        }
        String[] suffixes = { "Freehold", "Watch", "Redoubt" };
        long now = System.currentTimeMillis();
        for (int i = 0; i < coordinates.size(); i++) {
            int level = i + 1;
            int[] coordinate = decodeCoordinate(coordinates.get(i));
            String npcId = "npc-" + player.playerId + "-" + level;
            String cityName = capitalName + " " + suffixes[i];
            NpcCityRecord npc = new NpcCityRecord(npcId, player.playerId, cityName,
                    coordinate[0], coordinate[1], level, 40 * level,
                    500L * level, now);
            if (npcCities.putIfAbsent(npcId, npc) != null) {
                throw new ProtocolException("SERVER_ERROR", "Duplicate NPC city ID while founding a kingdom");
            }
        }
    }

    private static long distanceSquared(int x1, int y1, int x2, int y2) {
        long dx = (long) x1 - x2;
        long dy = (long) y1 - y2;
        return dx * dx + dy * dy;
    }

    private static int npcMaxLevies(int level) {
        return 200 * level;
    }

    private static long npcMaxCrowns(int level) {
        return 2_000L * level;
    }

    private static int npcLevyMinutes(int level) {
        return Math.max(2, 6 - level);
    }

    private static long coordinateKey(int x, int y) {
        return ((long) x << 32) | Integer.toUnsignedLong(y);
    }

    private static int[] decodeCoordinate(long coordinate) {
        return new int[] { (int) (coordinate >>> 32), (int) coordinate };
    }

    private static long greatestCommonDivisor(long a, long b) {
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return Math.abs(a);
    }
}
