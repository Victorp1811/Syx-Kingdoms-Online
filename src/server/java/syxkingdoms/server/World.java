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
    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin", "administrator", "moderator", "server", "system", "syx kingdoms");

    record JoinResult(PlayerRecord player, Messages.Welcome welcome) {
    }

    record Metrics(int registeredPlayers, int onlinePlayers, int retainedEvents,
            int armies, int shipments, int deliveries, int marketOffers) {
    }

    record SyncResult(
            Messages.WorldState worldState,
            Messages.PlayerState playerState,
            List<Messages.CommandResult> commandResults,
            long eventCursor,
            List<Messages.WorldEvent> events,
            long crestCursor,
            List<Messages.Crest> crests,
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
    private final Set<String> online = new HashSet<>();
    private final List<WorldEventRecord> events = new ArrayList<>();
    private final Map<String, Messages.CommandResult> processedCommands = new HashMap<>();
    private final Map<String, String> stances = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> commandWindows = new HashMap<>();
    private final List<ArmyRecord> armies = new ArrayList<>();
    private final List<ShipmentRecord> shipments = new ArrayList<>();
    private final List<DeliveryRecord> deliveries = new ArrayList<>();
    private final List<MarketOfferRecord> marketOffers = new ArrayList<>();
    private int nextSlot;
    private long nextEventId;
    private long nextCrestRevision;
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
                    || player.x % config.citySpacing() != 0 || player.y % config.citySpacing() != 0
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
        events.addAll(loaded.events());
        for (WorldEventRecord event : events) {
            if (!players.containsKey(event.actorPlayerId)
                    || (!event.targetPlayerId.isBlank() && !players.containsKey(event.targetPlayerId))) {
                throw new IOException("Persistent event refers to an unknown player: " + event.eventId);
            }
            restoreEventState(event);
        }
        Set<String> armyIds = new HashSet<>();
        for (ArmyRecord army : loaded.armies()) {
            if (!armyIds.add(army.armyId) || !players.containsKey(army.ownerPlayerId)
                    || !players.containsKey(army.targetPlayerId)
                    || army.ownerPlayerId.equals(army.targetPlayerId)
                    || army.troops < 1 || army.troops > 10_000
                    || army.departedEpochMillis < 0 || army.arrivesEpochMillis < army.departedEpochMillis) {
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
                int[] coordinate = coordinateForFounding(hello.placementReference());
                player = new PlayerRecord(hello.playerId(), authHash, nextSlot, coordinate[0], coordinate[1]);
                nextSlot++;
                players.put(player.playerId, player);
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
        List<Messages.City> cities = new ArrayList<>(ordered.size());
        for (PlayerRecord player : ordered) {
            cities.add(new Messages.City(player.playerId, player.playerName, player.kingdomName, player.cityName,
                    player.x, player.y, player.population, player.credits,
                    online.contains(player.playerId), player.lastSeenEpochMillis,
                    player.protectionEndsEpochMillis));
        }
        return new Messages.WorldState(tick(), System.currentTimeMillis(), config.fixedGameSpeed(),
                config.worldWidth(), config.worldHeight(), config.worldSeed(), List.copyOf(cities));
    }

    synchronized Metrics metrics() {
        return new Metrics(players.size(), online.size(), events.size(), armies.size(),
                shipments.size(), deliveries.size(), marketOffers.size());
    }

    synchronized void saveIfDirty() throws IOException {
        if (!dirty) {
            return;
        }
        WorldStore.save(config.worldFile(), config, epochMillis, nextSlot, new ArrayList<>(players.values()),
                nextEventId, nextCrestRevision, new ArrayList<>(events), new ArrayList<>(armies),
                new ArrayList<>(shipments), new ArrayList<>(deliveries), new ArrayList<>(marketOffers));
        dirty = false;
    }

    synchronized void save() throws IOException {
        WorldStore.save(config.worldFile(), config, epochMillis, nextSlot, new ArrayList<>(players.values()),
                nextEventId, nextCrestRevision, new ArrayList<>(events), new ArrayList<>(armies),
                new ArrayList<>(shipments), new ArrayList<>(deliveries), new ArrayList<>(marketOffers));
        dirty = false;
    }

    long tick() {
        return Math.max(0L, (System.currentTimeMillis() - epochMillis) / config.tickMillis());
    }

    synchronized void maintenance() {
        resolveArmies(System.currentTimeMillis());
    }

    synchronized void resolveArmies(long now) {
        updateEconomy(now);
        var iterator = armies.iterator();
        while (iterator.hasNext()) {
            ArmyRecord army = iterator.next();
            if (army.arrivesEpochMillis > now) {
                continue;
            }
            PlayerRecord attacker = players.get(army.ownerPlayerId);
            PlayerRecord defender = players.get(army.targetPlayerId);
            int defendingLevies = Math.min(defender.levies, army.troops);
            int attackerSurvivors = Math.max(0, army.troops - defendingLevies);
            int defenderSurvivors = Math.max(0, defender.levies - army.troops);
            defender.levies = defenderSurvivors;
            long stolenCrowns = 0;
            String outcome;
            if (attackerSurvivors > 0) {
                stolenCrowns = Math.min(Math.min(defender.crowns, attackerSurvivors * 5L),
                        1_000_000_000L - attacker.crowns);
                defender.crowns -= stolenCrowns;
                attacker.crowns += stolenCrowns;
                outcome = "ATTACKER_WIN";
            } else {
                outcome = "DEFENDER_WIN";
            }
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
                List.copyOf(visibleCrests), relationsFor(playerId),
                armies.stream().map(ArmyRecord::message).toList(), deliveriesFor(playerId), marketOffers());
    }

    private boolean eventVisibleTo(WorldEventRecord event, String playerId) {
        if (!"LOCAL_CHAT".equals(event.type)) {
            return event.visibleTo(playerId);
        }
        if (event.actorPlayerId.equals(playerId)) {
            return true;
        }
        PlayerRecord listener = players.get(playerId);
        return listener != null
                && SharedWorld.sectorX(listener.x) == event.value1
                && SharedWorld.sectorY(listener.y) == event.value2;
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
                || "MAIL".equals(command.type())) && muted(actor.playerId)) {
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
                int sectorX = SharedWorld.sectorX(actor.x);
                int sectorY = SharedWorld.sectorY(actor.y);
                appendEvent(command, "LOCAL_CHAT", actor, "", text, "", sectorX, sectorY);
                yield accepted(command, "LOCAL_MESSAGE_SENT",
                        "Local message sent to province " + sectorX + "," + sectorY);
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
            case "STANCE" -> {
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                String stance = normalizeStance(command.argument());
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
                PlayerRecord target = targetPlayer(actor, command.targetPlayerId());
                long now = System.currentTimeMillis();
                if (target.protectionEndsEpochMillis > now) {
                    throw new ProtocolException("TARGET_PROTECTED",
                            target.kingdomName + " is under beginner protection until "
                                    + target.protectionEndsEpochMillis);
                }
                if (!"WAR".equals(effectiveStance(actor.playerId, target.playerId))) {
                    throw new ProtocolException("NOT_AT_WAR", "Armies can only target a city with an effective WAR stance");
                }
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
                long arrives = departed + travelMillis(actor, target);
                if (actor.protectionEndsEpochMillis > now) {
                    actor.protectionEndsEpochMillis = 0;
                    appendSystemEvent("PROTECTION_ENDED", actor, "", actor.kingdomName,
                            "ARMY_LAUNCHED", 0, 0);
                }
                actor.levies -= troops;
                armies.add(new ArmyRecord(command.commandId(), actor.playerId, target.playerId,
                        troops, departed, arrives));
                appendEvent(command, "ARMY_SENT", actor, target.playerId, "", "EN_ROUTE", troops, arrives);
                yield accepted(command, "ARMY_SENT", troops + " levies are marching toward " + target.playerName);
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
            String effective = "WAR".equals(your) || "WAR".equals(their)
                    ? "WAR" : ("ALLY".equals(your) && "ALLY".equals(their) ? "ALLY" : "NEUTRAL");
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
        String firstStance = stance(first, second);
        String secondStance = stance(second, first);
        if ("WAR".equals(firstStance) || "WAR".equals(secondStance)) {
            return "WAR";
        }
        return "ALLY".equals(firstStance) && "ALLY".equals(secondStance) ? "ALLY" : "NEUTRAL";
    }

    private long travelMillis(PlayerRecord from, PlayerRecord to) {
        double distance = Math.hypot((double) from.x - to.x, (double) from.y - to.y);
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

    private int[] coordinateForFounding(String placementReference) throws ProtocolException {
        Set<Long> occupied = new HashSet<>();
        for (PlayerRecord existing : players.values()) {
            occupied.add(coordinateKey(existing.x, existing.y));
        }

        PlayerRecord reference = null;
        if (placementReference != null && !placementReference.isBlank()) {
            String referenceName = foundingName(placementReference, "placement-reference kingdom");
            String referenceKey = nameKey(referenceName);
            reference = players.values().stream()
                    .filter(PlayerRecord::foundingComplete)
                    .filter(player -> referenceKey.equals(nameKey(player.kingdomName)))
                    .findFirst()
                    .orElseThrow(() -> new ProtocolException("PLACEMENT_REFERENCE_NOT_FOUND",
                            "No existing kingdom named '" + referenceName + "' was found"));
        }

        long selected = -1;
        long closestDistance = Long.MAX_VALUE;
        for (long coordinate : cityCoordinates) {
            if (occupied.contains(coordinate)) {
                continue;
            }
            if (reference == null) {
                return decodeCoordinate(coordinate);
            }
            int x = (int) (coordinate >>> 32);
            int y = (int) coordinate;
            long dx = (long) x - reference.x;
            long dy = (long) y - reference.y;
            long distance = dx * dx + dy * dy;
            if (distance < closestDistance) {
                selected = coordinate;
                closestDistance = distance;
            }
        }
        if (selected >= 0) {
            return decodeCoordinate(selected);
        }
        throw new ProtocolException("WORLD_FULL", "No buildable shared-world city coordinates remain");
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
