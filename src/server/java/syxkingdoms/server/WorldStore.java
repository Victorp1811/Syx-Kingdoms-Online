package syxkingdoms.server;

import syxkingdoms.protocol.Protocol;
import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.ProtocolException;
import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

final class WorldStore {
    record LoadedWorld(long epochMillis, int nextSlot, List<PlayerRecord> players,
            long nextEventId, long nextCrestRevision, List<WorldEventRecord> events, List<ArmyRecord> armies,
            List<ShipmentRecord> shipments, List<DeliveryRecord> deliveries,
            List<MarketOfferRecord> marketOffers, List<HouseRecord> houses,
            List<HouseRequestRecord> houseRequests, List<ProvinceRecord> provinces,
            List<HouseWarRecord> wars, List<BattleReportRecord> reports,
            int seasonNumber, long seasonStartsEpochMillis, long seasonEndsEpochMillis,
            String seasonWinnerHouseId) {
    }

    private WorldStore() {
    }

    static LoadedWorld load(Path path, ServerConfig config) throws IOException {
        if (!Files.exists(path)) {
            long now = System.currentTimeMillis();
            return new LoadedWorld(now, 0, List.of(), 1, 1,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), 1, now,
                    now + 30L * 86_400_000L, "");
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        String storedWorldId = p.getProperty("world.id", "");
        if (!config.worldId().equals(storedWorldId)) {
            throw new IOException("World file belongs to '" + storedWorldId + "', not '" + config.worldId() + "'");
        }
        int format = Integer.parseInt(p.getProperty("format", "1"));
        if (format > 12) {
            throw new IOException("World file format " + format + " is newer than this server supports");
        }
        if (format >= 2) {
            int storedWidth = Integer.parseInt(required(p, "world.width"));
            int storedHeight = Integer.parseInt(required(p, "world.height"));
            int storedSpacing = Integer.parseInt(required(p, "world.citySpacing"));
            String storedSeed = required(p, "world.seed");
            if (storedWidth != config.worldWidth() || storedHeight != config.worldHeight()
                    || storedSpacing != config.citySpacing() || !storedSeed.equals(config.worldSeed())) {
                throw new IOException("World geometry/seed does not match server.properties; use a new world file or restore its original settings");
            }
        }
        if (format >= 3) {
            int terrainGeneration = Integer.parseInt(required(p, "world.terrainGeneration"));
            String terrainFingerprint = required(p, "world.terrainFingerprint");
            String expectedFingerprint = new SharedWorldTerrain(config.worldSeed()).fingerprint();
            if (terrainGeneration != SharedWorld.TERRAIN_GENERATION
                    || !terrainFingerprint.equals(expectedFingerprint)) {
                throw new IOException("Persistent world terrain fingerprint does not match this server build");
            }
        }
        long epoch = Long.parseLong(p.getProperty("world.epochMillis"));
        int nextSlot = Integer.parseInt(p.getProperty("world.nextSlot", "0"));
        long nextCrestRevision = Long.parseLong(p.getProperty("crests.nextRevision", "1"));
        List<PlayerRecord> players = new ArrayList<>();
        int count = Integer.parseInt(p.getProperty("players.count", "0"));
        for (int i = 0; i < count; i++) {
            String prefix = "player." + i + ".";
            String id = decode(p.getProperty(prefix + "id"), "player id");
            String authHash = required(p, prefix + "authHash");
            int slot = Integer.parseInt(required(p, prefix + "slot"));
            PlayerRecord player = new PlayerRecord(id, authHash, slot,
                    Integer.parseInt(required(p, prefix + "x")),
                    Integer.parseInt(required(p, prefix + "y")));
            player.playerName = decode(p.getProperty(prefix + "name", ""), "player name");
            player.kingdomName = decode(p.getProperty(prefix + "kingdom", ""), "kingdom name");
            player.cityName = decode(p.getProperty(prefix + "city", ""), "city name");
            player.foundedEpochMillis = Long.parseLong(p.getProperty(prefix + "founded", "0"));
            player.protectionEndsEpochMillis = Long.parseLong(p.getProperty(prefix + "protectionEnds", "0"));
            player.gameVersion = decode(p.getProperty(prefix + "gameVersion", ""), "game version");
            player.modVersion = decode(p.getProperty(prefix + "modVersion", ""), "mod version");
            player.population = Integer.parseInt(p.getProperty(prefix + "population", "0"));
            player.credits = Long.parseLong(p.getProperty(prefix + "credits", "0"));
            player.lastSeenEpochMillis = Long.parseLong(p.getProperty(prefix + "lastSeen", "0"));
            player.crowns = Long.parseLong(p.getProperty(prefix + "crowns", "1000"));
            player.levies = Integer.parseInt(p.getProperty(prefix + "levies", "100"));
            player.economyUpdatedEpochMillis = Long.parseLong(
                    p.getProperty(prefix + "economyUpdated", Long.toString(System.currentTimeMillis())));
            player.levyProgressMinutes = Integer.parseInt(p.getProperty(prefix + "levyProgressMinutes", "0"));
            player.crestRevision = Long.parseLong(p.getProperty(prefix + "crestRevision", "0"));
            player.houseId = decode(p.getProperty(prefix + "houseId", ""), "player house ID");
            player.houseRole = p.getProperty(prefix + "houseRole", "");
            player.houseJoinedEpochMillis = Long.parseLong(p.getProperty(prefix + "houseJoined", "0"));
            player.houseEligibleEpochMillis = Long.parseLong(p.getProperty(prefix + "houseEligible", "0"));
            String crestPixels = decode(p.getProperty(prefix + "crestPixels", ""), "crest pixels");
            String crestPalette = decode(p.getProperty(prefix + "crestPalette", ""), "crest palette");
            if (player.crestRevision == 0) {
                if (!crestPixels.isBlank() || !crestPalette.isBlank()) {
                    throw new IOException("Player crest data has no revision for " + player.playerId);
                }
            } else {
                try {
                    player.crest = CrestDesign.decode(crestPixels, crestPalette);
                } catch (ProtocolException e) {
                    throw new IOException("Invalid player crest for " + player.playerId, e);
                }
            }
            players.add(player);
        }
        long nextEventId = Long.parseLong(p.getProperty("events.nextId", "1"));
        List<WorldEventRecord> events = new ArrayList<>();
        int eventCount = Integer.parseInt(p.getProperty("events.count", "0"));
        if (eventCount < 0 || eventCount > 10_000) {
            throw new IOException("Persistent event count is outside the supported range");
        }
        long previousEventId = 0;
        for (int i = 0; i < eventCount; i++) {
            String prefix = "event." + i + ".";
            long eventId = Long.parseLong(required(p, prefix + "id"));
            if (eventId <= previousEventId || eventId >= nextEventId) {
                throw new IOException("Persistent world events are not strictly ordered");
            }
            previousEventId = eventId;
            String commandId = decode(p.getProperty(prefix + "commandId", ""), "event command ID");
            if (!commandId.isBlank() && !commandId.matches("[A-Za-z0-9-]{8,64}")) {
                throw new IOException("Persistent world event has an invalid command ID");
            }
            String type = required(p, prefix + "type");
            if (!type.matches("[A-Z_]{2,32}")) {
                throw new IOException("Persistent world event has an invalid type");
            }
            events.add(new WorldEventRecord(eventId,
                    Long.parseLong(required(p, prefix + "epochMillis")), commandId, type,
                    decode(p.getProperty(prefix + "actorId", ""), "event actor ID"),
                    decode(p.getProperty(prefix + "actorName", ""), "event actor name"),
                    decode(p.getProperty(prefix + "targetId", ""), "event target ID"),
                    decode(p.getProperty(prefix + "text", ""), "event text"),
                    decode(p.getProperty(prefix + "argument", ""), "event argument"),
                    Long.parseLong(p.getProperty(prefix + "value1", "0")),
                    Long.parseLong(p.getProperty(prefix + "value2", "0"))));
        }
        if (nextEventId < 1 || (previousEventId > 0 && nextEventId <= previousEventId)) {
            throw new IOException("Persistent next event ID is invalid");
        }
        int armyCount = Integer.parseInt(p.getProperty("armies.count", "0"));
        if (armyCount < 0 || armyCount > Protocol.MAX_ARMIES_PER_SYNC) {
            throw new IOException("Persistent army count is outside the supported range");
        }
        List<ArmyRecord> armies = new ArrayList<>(armyCount);
        for (int i = 0; i < armyCount; i++) {
            String prefix = "army." + i + ".";
            armies.add(new ArmyRecord(
                    decode(required(p, prefix + "id"), "army ID"),
                    decode(required(p, prefix + "ownerId"), "army owner ID"),
                    decode(required(p, prefix + "targetId"), "army target ID"),
                    Integer.parseInt(required(p, prefix + "troops")),
                    Long.parseLong(required(p, prefix + "departed")),
                    Long.parseLong(required(p, prefix + "arrives")),
                    p.getProperty(prefix + "mission", "ATTACK"),
                    decode(p.getProperty(prefix + "warId", ""), "army war ID")));
        }
        int shipmentCount = boundedCount(p, "shipments.count", Protocol.MAX_DELIVERIES_PER_SYNC);
        List<ShipmentRecord> shipments = new ArrayList<>(shipmentCount);
        for (int i = 0; i < shipmentCount; i++) {
            String prefix = "shipment." + i + ".";
            shipments.add(new ShipmentRecord(
                    decode(required(p, prefix + "id"), "shipment ID"),
                    decode(required(p, prefix + "senderId"), "shipment sender ID"),
                    decode(required(p, prefix + "targetId"), "shipment target ID"),
                    decode(required(p, prefix + "resource"), "shipment resource"),
                    Integer.parseInt(required(p, prefix + "amount")),
                    Long.parseLong(required(p, prefix + "departed")),
                    Long.parseLong(required(p, prefix + "arrives"))));
        }
        int deliveryCount = boundedCount(p, "deliveries.count", Protocol.MAX_DELIVERIES_PER_SYNC);
        List<DeliveryRecord> deliveries = new ArrayList<>(deliveryCount);
        for (int i = 0; i < deliveryCount; i++) {
            String prefix = "delivery." + i + ".";
            deliveries.add(new DeliveryRecord(
                    decode(required(p, prefix + "id"), "delivery ID"),
                    decode(required(p, prefix + "senderId"), "delivery sender ID"),
                    decode(required(p, prefix + "targetId"), "delivery target ID"),
                    decode(required(p, prefix + "resource"), "delivery resource"),
                    Integer.parseInt(required(p, prefix + "amount")),
                    Long.parseLong(required(p, prefix + "arrived"))));
        }
        int offerCount = boundedCount(p, "offers.count", Protocol.MAX_MARKET_OFFERS_PER_SYNC);
        List<MarketOfferRecord> offers = new ArrayList<>(offerCount);
        for (int i = 0; i < offerCount; i++) {
            String prefix = "offer." + i + ".";
            offers.add(new MarketOfferRecord(
                    decode(required(p, prefix + "id"), "market offer ID"),
                    decode(required(p, prefix + "sellerId"), "market seller ID"),
                    decode(required(p, prefix + "resource"), "market resource"),
                    Integer.parseInt(required(p, prefix + "quantity")),
                    Long.parseLong(required(p, prefix + "unitPrice")),
                    Long.parseLong(required(p, prefix + "created"))));
        }
        int houseCount = boundedCount(p, "houses.count", Protocol.MAX_HOUSES_PER_SYNC);
        List<HouseRecord> houses = new ArrayList<>(houseCount);
        for (int i = 0; i < houseCount; i++) {
            String prefix = "house." + i + ".";
            HouseRecord house = new HouseRecord(decode(required(p, prefix + "id"), "house ID"),
                    decode(required(p, prefix + "name"), "house name"),
                    required(p, prefix + "tag"),
                    decode(required(p, prefix + "sovereignId"), "house sovereign"),
                    Long.parseLong(required(p, prefix + "created")));
            house.announcement = decode(p.getProperty(prefix + "announcement", ""), "house announcement");
            house.capitalProvinceX = Integer.parseInt(p.getProperty(prefix + "capitalX", "-1"));
            house.capitalProvinceY = Integer.parseInt(p.getProperty(prefix + "capitalY", "-1"));
            house.seasonPoints = Integer.parseInt(p.getProperty(prefix + "seasonPoints", "0"));
            house.crestRevision = Long.parseLong(p.getProperty(prefix + "crestRevision", "0"));
            String pixels = decode(p.getProperty(prefix + "crestPixels", ""), "house crest pixels");
            String palette = decode(p.getProperty(prefix + "crestPalette", ""), "house crest palette");
            if (house.crestRevision > 0) {
                try {
                    house.crest = CrestDesign.decode(pixels, palette);
                } catch (ProtocolException e) {
                    throw new IOException("Invalid crest for House " + house.houseId, e);
                }
            }
            houses.add(house);
        }
        int requestCount = boundedCount(p, "houseRequests.count", Protocol.MAX_HOUSE_REQUESTS_PER_SYNC);
        List<HouseRequestRecord> houseRequests = new ArrayList<>(requestCount);
        for (int i = 0; i < requestCount; i++) {
            String prefix = "houseRequest." + i + ".";
            houseRequests.add(new HouseRequestRecord(
                    decode(required(p, prefix + "id"), "house request ID"),
                    decode(required(p, prefix + "houseId"), "request house ID"),
                    decode(required(p, prefix + "playerId"), "request player ID"),
                    required(p, prefix + "type"), Long.parseLong(required(p, prefix + "created")),
                    Long.parseLong(required(p, prefix + "expires"))));
        }
        int provinceCount = boundedCount(p, "provinces.count", Protocol.MAX_PROVINCES_PER_SYNC);
        List<ProvinceRecord> provinces = new ArrayList<>(provinceCount);
        for (int i = 0; i < provinceCount; i++) {
            String prefix = "province." + i + ".";
            ProvinceRecord province = new ProvinceRecord(Integer.parseInt(required(p, prefix + "x")),
                    Integer.parseInt(required(p, prefix + "y")));
            province.ownerHouseId = decode(p.getProperty(prefix + "ownerHouseId", ""), "province owner");
            province.occupierHouseId = decode(p.getProperty(prefix + "occupierHouseId", ""), "province occupier");
            province.occupationInfluence = Integer.parseInt(p.getProperty(prefix + "influence", "0"));
            province.blockadeEndsEpochMillis = Long.parseLong(p.getProperty(prefix + "blockadeEnds", "0"));
            province.tributeCrowns = Long.parseLong(p.getProperty(prefix + "tribute", "0"));
            province.lastTributeEpochMillis = Long.parseLong(p.getProperty(prefix + "lastTribute", "0"));
            provinces.add(province);
        }
        int warCount = boundedCount(p, "wars.count", Protocol.MAX_WARS_PER_SYNC);
        List<HouseWarRecord> wars = new ArrayList<>(warCount);
        for (int i = 0; i < warCount; i++) {
            String prefix = "war." + i + ".";
            HouseWarRecord war = new HouseWarRecord(decode(required(p, prefix + "id"), "war ID"),
                    decode(required(p, prefix + "attackerHouseId"), "attacker House"),
                    decode(required(p, prefix + "defenderHouseId"), "defender House"),
                    Long.parseLong(required(p, prefix + "declared")),
                    Long.parseLong(required(p, prefix + "starts")),
                    Long.parseLong(required(p, prefix + "ends")), required(p, prefix + "phase"));
            war.attackerScore = Integer.parseInt(p.getProperty(prefix + "attackerScore", "0"));
            war.defenderScore = Integer.parseInt(p.getProperty(prefix + "defenderScore", "0"));
            war.truceEndsEpochMillis = Long.parseLong(p.getProperty(prefix + "truceEnds", "0"));
            war.surrenderedHouseId = decode(p.getProperty(prefix + "surrenderedHouseId", ""), "surrendered House");
            wars.add(war);
        }
        int reportCount = boundedCount(p, "reports.count", Protocol.MAX_REPORTS_PER_SYNC);
        List<BattleReportRecord> reports = new ArrayList<>(reportCount);
        for (int i = 0; i < reportCount; i++) {
            String prefix = "report." + i + ".";
            reports.add(new BattleReportRecord(decode(required(p, prefix + "id"), "report ID"),
                    Long.parseLong(required(p, prefix + "epochMillis")),
                    decode(required(p, prefix + "attackerId"), "report attacker"),
                    decode(required(p, prefix + "defenderId"), "report defender"),
                    decode(p.getProperty(prefix + "warId", ""), "report war ID"),
                    required(p, prefix + "outcome"), Integer.parseInt(required(p, prefix + "attacking")),
                    Integer.parseInt(required(p, prefix + "defending")),
                    Integer.parseInt(required(p, prefix + "attackerSurvivors")),
                    Integer.parseInt(required(p, prefix + "defenderSurvivors")),
                    Long.parseLong(required(p, prefix + "loot")),
                    Integer.parseInt(required(p, prefix + "provinceX")),
                    Integer.parseInt(required(p, prefix + "provinceY")),
                    Integer.parseInt(required(p, prefix + "warScore"))));
        }
        long defaultSeasonStart = System.currentTimeMillis();
        return new LoadedWorld(epoch, nextSlot, List.copyOf(players), nextEventId, nextCrestRevision,
                List.copyOf(events), List.copyOf(armies), List.copyOf(shipments),
                List.copyOf(deliveries), List.copyOf(offers), List.copyOf(houses),
                List.copyOf(houseRequests), List.copyOf(provinces), List.copyOf(wars),
                List.copyOf(reports), Integer.parseInt(p.getProperty("season.number", "1")),
                Long.parseLong(p.getProperty("season.starts", Long.toString(defaultSeasonStart))),
                Long.parseLong(p.getProperty("season.ends", Long.toString(defaultSeasonStart + 30L * 86_400_000L))),
                decode(p.getProperty("season.winnerHouseId", ""), "season winner House"));
    }

    static void save(Path path, ServerConfig config, long epochMillis, int nextSlot,
            List<PlayerRecord> input, long nextEventId, long nextCrestRevision,
            List<WorldEventRecord> inputEvents,
            List<ArmyRecord> inputArmies, List<ShipmentRecord> inputShipments,
            List<DeliveryRecord> inputDeliveries, List<MarketOfferRecord> inputOffers,
            List<HouseRecord> inputHouses, List<HouseRequestRecord> inputHouseRequests,
            List<ProvinceRecord> inputProvinces, List<HouseWarRecord> inputWars,
            List<BattleReportRecord> inputReports, int seasonNumber, long seasonStartsEpochMillis,
            long seasonEndsEpochMillis, String seasonWinnerHouseId) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", "12");
        p.setProperty("world.id", config.worldId());
        p.setProperty("world.epochMillis", Long.toString(epochMillis));
        p.setProperty("world.nextSlot", Integer.toString(nextSlot));
        p.setProperty("crests.nextRevision", Long.toString(nextCrestRevision));
        p.setProperty("world.width", Integer.toString(config.worldWidth()));
        p.setProperty("world.height", Integer.toString(config.worldHeight()));
        p.setProperty("world.citySpacing", Integer.toString(config.citySpacing()));
        p.setProperty("world.seed", config.worldSeed());
        p.setProperty("world.terrainGeneration", Integer.toString(SharedWorld.TERRAIN_GENERATION));
        p.setProperty("world.terrainFingerprint", new SharedWorldTerrain(config.worldSeed()).fingerprint());
        List<PlayerRecord> players = new ArrayList<>(input);
        players.sort(Comparator.comparingInt(player -> player.slot));
        p.setProperty("players.count", Integer.toString(players.size()));
        for (int i = 0; i < players.size(); i++) {
            PlayerRecord player = players.get(i);
            String prefix = "player." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(player.playerId));
            p.setProperty(prefix + "authHash", player.authHash);
            p.setProperty(prefix + "slot", Integer.toString(player.slot));
            p.setProperty(prefix + "x", Integer.toString(player.x));
            p.setProperty(prefix + "y", Integer.toString(player.y));
            p.setProperty(prefix + "name", Protocol.encodeText(orEmpty(player.playerName)));
            p.setProperty(prefix + "kingdom", Protocol.encodeText(orEmpty(player.kingdomName)));
            p.setProperty(prefix + "city", Protocol.encodeText(orEmpty(player.cityName)));
            p.setProperty(prefix + "founded", Long.toString(player.foundedEpochMillis));
            p.setProperty(prefix + "protectionEnds", Long.toString(player.protectionEndsEpochMillis));
            p.setProperty(prefix + "gameVersion", Protocol.encodeText(orEmpty(player.gameVersion)));
            p.setProperty(prefix + "modVersion", Protocol.encodeText(orEmpty(player.modVersion)));
            p.setProperty(prefix + "population", Integer.toString(player.population));
            p.setProperty(prefix + "credits", Long.toString(player.credits));
            p.setProperty(prefix + "lastSeen", Long.toString(player.lastSeenEpochMillis));
            p.setProperty(prefix + "crowns", Long.toString(player.crowns));
            p.setProperty(prefix + "levies", Integer.toString(player.levies));
            p.setProperty(prefix + "economyUpdated", Long.toString(player.economyUpdatedEpochMillis));
            p.setProperty(prefix + "levyProgressMinutes", Integer.toString(player.levyProgressMinutes));
            p.setProperty(prefix + "crestRevision", Long.toString(player.crestRevision));
            p.setProperty(prefix + "crestPixels", Protocol.encodeText(
                    player.crest == null ? "" : player.crest.pixelsEncoded()));
            p.setProperty(prefix + "crestPalette", Protocol.encodeText(
                    player.crest == null ? "" : player.crest.paletteHex()));
            p.setProperty(prefix + "houseId", Protocol.encodeText(orEmpty(player.houseId)));
            p.setProperty(prefix + "houseRole", orEmpty(player.houseRole));
            p.setProperty(prefix + "houseJoined", Long.toString(player.houseJoinedEpochMillis));
            p.setProperty(prefix + "houseEligible", Long.toString(player.houseEligibleEpochMillis));
        }
        List<WorldEventRecord> events = new ArrayList<>(inputEvents);
        events.sort(Comparator.comparingLong(event -> event.eventId));
        p.setProperty("events.nextId", Long.toString(nextEventId));
        p.setProperty("events.count", Integer.toString(events.size()));
        for (int i = 0; i < events.size(); i++) {
            WorldEventRecord event = events.get(i);
            String prefix = "event." + i + ".";
            p.setProperty(prefix + "id", Long.toString(event.eventId));
            p.setProperty(prefix + "epochMillis", Long.toString(event.epochMillis));
            p.setProperty(prefix + "commandId", Protocol.encodeText(orEmpty(event.commandId)));
            p.setProperty(prefix + "type", event.type);
            p.setProperty(prefix + "actorId", Protocol.encodeText(orEmpty(event.actorPlayerId)));
            p.setProperty(prefix + "actorName", Protocol.encodeText(orEmpty(event.actorName)));
            p.setProperty(prefix + "targetId", Protocol.encodeText(orEmpty(event.targetPlayerId)));
            p.setProperty(prefix + "text", Protocol.encodeText(orEmpty(event.text)));
            p.setProperty(prefix + "argument", Protocol.encodeText(orEmpty(event.argument)));
            p.setProperty(prefix + "value1", Long.toString(event.value1));
            p.setProperty(prefix + "value2", Long.toString(event.value2));
        }
        List<ArmyRecord> armies = new ArrayList<>(inputArmies);
        armies.sort(Comparator.comparingLong(army -> army.arrivesEpochMillis));
        p.setProperty("armies.count", Integer.toString(armies.size()));
        for (int i = 0; i < armies.size(); i++) {
            ArmyRecord army = armies.get(i);
            String prefix = "army." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(army.armyId));
            p.setProperty(prefix + "ownerId", Protocol.encodeText(army.ownerPlayerId));
            p.setProperty(prefix + "targetId", Protocol.encodeText(army.targetPlayerId));
            p.setProperty(prefix + "troops", Integer.toString(army.troops));
            p.setProperty(prefix + "departed", Long.toString(army.departedEpochMillis));
            p.setProperty(prefix + "arrives", Long.toString(army.arrivesEpochMillis));
            p.setProperty(prefix + "mission", army.mission);
            p.setProperty(prefix + "warId", Protocol.encodeText(orEmpty(army.warId)));
        }
        List<ShipmentRecord> shipments = new ArrayList<>(inputShipments);
        shipments.sort(Comparator.comparingLong(shipment -> shipment.arrivesEpochMillis));
        p.setProperty("shipments.count", Integer.toString(shipments.size()));
        for (int i = 0; i < shipments.size(); i++) {
            ShipmentRecord shipment = shipments.get(i);
            String prefix = "shipment." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(shipment.shipmentId));
            p.setProperty(prefix + "senderId", Protocol.encodeText(shipment.senderPlayerId));
            p.setProperty(prefix + "targetId", Protocol.encodeText(shipment.targetPlayerId));
            p.setProperty(prefix + "resource", Protocol.encodeText(shipment.resourceKey));
            p.setProperty(prefix + "amount", Integer.toString(shipment.amount));
            p.setProperty(prefix + "departed", Long.toString(shipment.departedEpochMillis));
            p.setProperty(prefix + "arrives", Long.toString(shipment.arrivesEpochMillis));
        }
        List<DeliveryRecord> deliveries = new ArrayList<>(inputDeliveries);
        deliveries.sort(Comparator.comparingLong(delivery -> delivery.arrivedEpochMillis));
        p.setProperty("deliveries.count", Integer.toString(deliveries.size()));
        for (int i = 0; i < deliveries.size(); i++) {
            DeliveryRecord delivery = deliveries.get(i);
            String prefix = "delivery." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(delivery.deliveryId));
            p.setProperty(prefix + "senderId", Protocol.encodeText(delivery.senderPlayerId));
            p.setProperty(prefix + "targetId", Protocol.encodeText(delivery.targetPlayerId));
            p.setProperty(prefix + "resource", Protocol.encodeText(delivery.resourceKey));
            p.setProperty(prefix + "amount", Integer.toString(delivery.amount));
            p.setProperty(prefix + "arrived", Long.toString(delivery.arrivedEpochMillis));
        }
        List<MarketOfferRecord> offers = new ArrayList<>(inputOffers);
        offers.sort(Comparator.comparingLong(offer -> offer.createdEpochMillis));
        p.setProperty("offers.count", Integer.toString(offers.size()));
        for (int i = 0; i < offers.size(); i++) {
            MarketOfferRecord offer = offers.get(i);
            String prefix = "offer." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(offer.offerId));
            p.setProperty(prefix + "sellerId", Protocol.encodeText(offer.sellerPlayerId));
            p.setProperty(prefix + "resource", Protocol.encodeText(offer.resourceKey));
            p.setProperty(prefix + "quantity", Integer.toString(offer.quantity));
            p.setProperty(prefix + "unitPrice", Long.toString(offer.unitPrice));
            p.setProperty(prefix + "created", Long.toString(offer.createdEpochMillis));
        }
        List<HouseRecord> houses = new ArrayList<>(inputHouses);
        houses.sort(Comparator.comparing(house -> house.houseId));
        p.setProperty("houses.count", Integer.toString(houses.size()));
        for (int i = 0; i < houses.size(); i++) {
            HouseRecord house = houses.get(i);
            String prefix = "house." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(house.houseId));
            p.setProperty(prefix + "name", Protocol.encodeText(house.name));
            p.setProperty(prefix + "tag", house.tag);
            p.setProperty(prefix + "sovereignId", Protocol.encodeText(house.sovereignPlayerId));
            p.setProperty(prefix + "created", Long.toString(house.createdEpochMillis));
            p.setProperty(prefix + "announcement", Protocol.encodeText(orEmpty(house.announcement)));
            p.setProperty(prefix + "capitalX", Integer.toString(house.capitalProvinceX));
            p.setProperty(prefix + "capitalY", Integer.toString(house.capitalProvinceY));
            p.setProperty(prefix + "seasonPoints", Integer.toString(house.seasonPoints));
            p.setProperty(prefix + "crestRevision", Long.toString(house.crestRevision));
            p.setProperty(prefix + "crestPixels", Protocol.encodeText(house.crest == null ? "" : house.crest.pixelsEncoded()));
            p.setProperty(prefix + "crestPalette", Protocol.encodeText(house.crest == null ? "" : house.crest.paletteHex()));
        }
        p.setProperty("houseRequests.count", Integer.toString(inputHouseRequests.size()));
        for (int i = 0; i < inputHouseRequests.size(); i++) {
            HouseRequestRecord request = inputHouseRequests.get(i);
            String prefix = "houseRequest." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(request.requestId));
            p.setProperty(prefix + "houseId", Protocol.encodeText(request.houseId));
            p.setProperty(prefix + "playerId", Protocol.encodeText(request.playerId));
            p.setProperty(prefix + "type", request.type);
            p.setProperty(prefix + "created", Long.toString(request.createdEpochMillis));
            p.setProperty(prefix + "expires", Long.toString(request.expiresEpochMillis));
        }
        p.setProperty("provinces.count", Integer.toString(inputProvinces.size()));
        for (int i = 0; i < inputProvinces.size(); i++) {
            ProvinceRecord province = inputProvinces.get(i);
            String prefix = "province." + i + ".";
            p.setProperty(prefix + "x", Integer.toString(province.x));
            p.setProperty(prefix + "y", Integer.toString(province.y));
            p.setProperty(prefix + "ownerHouseId", Protocol.encodeText(orEmpty(province.ownerHouseId)));
            p.setProperty(prefix + "occupierHouseId", Protocol.encodeText(orEmpty(province.occupierHouseId)));
            p.setProperty(prefix + "influence", Integer.toString(province.occupationInfluence));
            p.setProperty(prefix + "blockadeEnds", Long.toString(province.blockadeEndsEpochMillis));
            p.setProperty(prefix + "tribute", Long.toString(province.tributeCrowns));
            p.setProperty(prefix + "lastTribute", Long.toString(province.lastTributeEpochMillis));
        }
        p.setProperty("wars.count", Integer.toString(inputWars.size()));
        for (int i = 0; i < inputWars.size(); i++) {
            HouseWarRecord war = inputWars.get(i);
            String prefix = "war." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(war.warId));
            p.setProperty(prefix + "attackerHouseId", Protocol.encodeText(war.attackerHouseId));
            p.setProperty(prefix + "defenderHouseId", Protocol.encodeText(war.defenderHouseId));
            p.setProperty(prefix + "declared", Long.toString(war.declaredEpochMillis));
            p.setProperty(prefix + "starts", Long.toString(war.startsEpochMillis));
            p.setProperty(prefix + "ends", Long.toString(war.endsEpochMillis));
            p.setProperty(prefix + "phase", war.phase);
            p.setProperty(prefix + "attackerScore", Integer.toString(war.attackerScore));
            p.setProperty(prefix + "defenderScore", Integer.toString(war.defenderScore));
            p.setProperty(prefix + "truceEnds", Long.toString(war.truceEndsEpochMillis));
            p.setProperty(prefix + "surrenderedHouseId", Protocol.encodeText(orEmpty(war.surrenderedHouseId)));
        }
        p.setProperty("reports.count", Integer.toString(inputReports.size()));
        for (int i = 0; i < inputReports.size(); i++) {
            BattleReportRecord report = inputReports.get(i);
            String prefix = "report." + i + ".";
            p.setProperty(prefix + "id", Protocol.encodeText(report.reportId));
            p.setProperty(prefix + "epochMillis", Long.toString(report.epochMillis));
            p.setProperty(prefix + "attackerId", Protocol.encodeText(report.attackerPlayerId));
            p.setProperty(prefix + "defenderId", Protocol.encodeText(report.defenderPlayerId));
            p.setProperty(prefix + "warId", Protocol.encodeText(orEmpty(report.warId)));
            p.setProperty(prefix + "outcome", report.outcome);
            p.setProperty(prefix + "attacking", Integer.toString(report.attackingTroops));
            p.setProperty(prefix + "defending", Integer.toString(report.defendingTroops));
            p.setProperty(prefix + "attackerSurvivors", Integer.toString(report.attackerSurvivors));
            p.setProperty(prefix + "defenderSurvivors", Integer.toString(report.defenderSurvivors));
            p.setProperty(prefix + "loot", Long.toString(report.crownsLooted));
            p.setProperty(prefix + "provinceX", Integer.toString(report.provinceX));
            p.setProperty(prefix + "provinceY", Integer.toString(report.provinceY));
            p.setProperty(prefix + "warScore", Integer.toString(report.warScore));
        }
        p.setProperty("season.number", Integer.toString(seasonNumber));
        p.setProperty("season.starts", Long.toString(seasonStartsEpochMillis));
        p.setProperty("season.ends", Long.toString(seasonEndsEpochMillis));
        p.setProperty("season.winnerHouseId", Protocol.encodeText(orEmpty(seasonWinnerHouseId)));

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(writer, "Syx Kingdoms Online persistent world - do not edit while the server is running");
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null) {
            throw new IOException("World file is missing " + key);
        }
        return value;
    }

    private static int boundedCount(Properties p, String key, int max) throws IOException {
        int count = Integer.parseInt(p.getProperty(key, "0"));
        if (count < 0 || count > max) {
            throw new IOException(key + " is outside the supported range");
        }
        return count;
    }

    private static String decode(String encoded, String field) throws IOException {
        try {
            return Protocol.decodeText(encoded == null ? "" : encoded, field);
        } catch (Exception e) {
            throw new IOException("World file contains invalid " + field, e);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
