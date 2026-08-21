package syxkingdoms.server;

import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.Protocol;
import syxkingdoms.protocol.ProtocolException;
import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class WorldPersistenceTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("syx-kingdoms-world-test-");
        ServerConfig config = config(temp.resolve("world.properties"));
        SharedWorldTerrain terrain = new SharedWorldTerrain(config.worldSeed());
        check(config.worldWidth() == SharedWorld.BASE_GAME_TILE_DIMENSION * SharedWorld.LINEAR_SCALE,
                "shared world is ten base maps wide");
        check(config.worldHeight() == SharedWorld.BASE_GAME_TILE_DIMENSION * SharedWorld.LINEAR_SCALE,
                "shared world is ten base maps tall");
        check((long) config.worldWidth() * config.worldHeight()
                == (long) SharedWorld.BASE_GAME_TILE_DIMENSION * SharedWorld.BASE_GAME_TILE_DIMENSION * 100L,
                "shared world contains one hundred base-map areas");
        String tokenA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String tokenB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        World world = new World(config);
        World.JoinResult alice = world.join(hello("alice-id", tokenA, "Alice", "Oakhold"));
        World.JoinResult bob = world.join(hello("bob-id", tokenB, "Bob", "Ashkeep"));
        check(alice.player().x >= 0 && alice.player().x < config.worldWidth(), "first city x bound");
        check(alice.player().y >= 0 && alice.player().y < config.worldHeight(), "first city y bound");
        check(alice.player().x != bob.player().x || alice.player().y != bob.player().y, "distinct city slots");
        check(alice.player().x % config.citySpacing() == 0 && alice.player().y % config.citySpacing() == 0,
                "first city spacing");
        check(terrain.canFoundCity(alice.player().x, alice.player().y), "first city is on buildable land");
        world.heartbeat("alice-id", new Messages.Heartbeat(1, 1200, 5555));
        Messages.Command persistentChat = new Messages.Command("persistent-chat", "CHAT", "", "",
                0, 0, "The world remembers");
        Messages.Command persistentArmy = new Messages.Command("persistent-army", "SEND_ARMY", "bob-id", "",
                20, 0, "");
        Messages.Command persistentShipment = new Messages.Command("persistent-shipment", "SEND_RESOURCE", "bob-id",
                "WOOD", 25, 0, "");
        Messages.Command persistentOffer = new Messages.Command("persistent-offer", "CREATE_OFFER", "",
                "STONE", 30, 2, "");
        World.SyncResult social = world.applyUpdate("alice-id",
                new Messages.ClientUpdate(2, 1200, 5555, 0, 0,
                        List.of(persistentChat, persistentShipment, persistentOffer)));
        check(social.commandResults().stream().allMatch(Messages.CommandResult::accepted),
                "persistent social commands accepted");
        World.SyncResult aliceHouse = world.applyUpdate("alice-id", new Messages.ClientUpdate(3, 1200, 5555,
                social.eventCursor(), 0, List.of(new Messages.Command("alice-house-01", "CREATE_HOUSE", "",
                "OAK", 0, 0, "Oak Alliance"))));
        World.SyncResult bobHouse = world.applyUpdate("bob-id", new Messages.ClientUpdate(2, 0, 0,
                0, 0, List.of(new Messages.Command("bob-house-0001", "CREATE_HOUSE", "",
                "ASH", 0, 0, "Ash League"))));
        check(aliceHouse.commandResults().getFirst().accepted() && bobHouse.commandResults().getFirst().accepted(),
                "persistent Houses created");
        World.SyncResult declaration = world.applyUpdate("alice-id", new Messages.ClientUpdate(4, 1200, 5555,
                aliceHouse.eventCursor(), 0, List.of(new Messages.Command("persistent-war", "DECLARE_HOUSE_WAR", "",
                "bob-house-0001", 0, 0, ""))));
        check(declaration.commandResults().getFirst().accepted()
                && "PREPARING".equals(declaration.wars().getFirst().phase()), "formal war preparation persisted");
        world.resolveStrategicTime(System.currentTimeMillis() + 13L * 3_600_000L);
        World.SyncResult military = world.applyUpdate("alice-id", new Messages.ClientUpdate(5, 1200, 5555,
                declaration.eventCursor(), 0, List.of(persistentArmy)));
        check(military.commandResults().getFirst().accepted(), "army accepted during active formal war");
        check(military.playerState().levies() == 80 && military.armies().size() == 1,
                "persistent army committed from server levies");
        long armyArrival = military.armies().getFirst().arrivesEpochMillis();
        long shipmentArrival = social.events().stream()
                .filter(event -> "SHIPMENT_SENT".equals(event.type()))
                .mapToLong(Messages.WorldEvent::value2).findFirst().orElseThrow();
        check(world.snapshot().cities().size() == 2, "two city snapshot");
        check(world.snapshot().cities().stream().filter(Messages.City::online).count() == 2, "online snapshot");
        world.leave("alice-id");
        world.leave("bob-id");
        world.save();
        Properties persisted = new Properties();
        try (var reader = Files.newBufferedReader(config.worldFile())) {
            persisted.load(reader);
        }
        check("12".equals(persisted.getProperty("format")), "persistent world format");
        check(Integer.toString(SharedWorld.TERRAIN_GENERATION)
                .equals(persisted.getProperty("world.terrainGeneration")), "persistent terrain generation");
        check(terrain.fingerprint().equals(persisted.getProperty("world.terrainFingerprint")),
                "persistent terrain fingerprint");

        World reloaded = new World(config);
        World.JoinResult aliceAgain = reloaded.join(hello("alice-id", tokenA, "Alice II", "Oakhold"));
        reloaded.join(hello("bob-id", tokenB, "Bob", "Ashkeep"));
        check(aliceAgain.player().x == alice.player().x && aliceAgain.player().y == alice.player().y
                && aliceAgain.player().population == 1200, "persistent city state");
        World.SyncResult remembered = reloaded.initialSync("alice-id", 0);
        check(remembered.events().stream()
                .anyMatch(event -> "CHAT".equals(event.type()) && "The world remembers".equals(event.text())),
                "persistent event history");
        check(remembered.relations().stream()
                .anyMatch(relation -> relation.playerId().equals("bob-id")
                        && "WAR".equals(relation.effectiveStance())), "persistent diplomacy state");
        check(remembered.playerState().levies() == 80 && remembered.armies().size() == 1,
                "persistent traveling army");
        check(remembered.marketOffers().stream().anyMatch(offer -> offer.offerId().equals("persistent-offer")
                && "STONE".equals(offer.resourceKey()) && offer.quantity() == 30 && offer.unitPrice() == 2),
                "persistent market offer");
        reloaded.resolveArmies(Math.max(armyArrival, shipmentArrival) + 1);
        World.SyncResult battle = reloaded.initialSync("alice-id", remembered.eventCursor());
        check(battle.armies().isEmpty(), "arrived army removed from traveling state");
        check(battle.events().stream().anyMatch(event -> "BATTLE".equals(event.type())
                && "DEFENDER_WIN".equals(event.text())), "persistent army arrival resolved");
        World.SyncResult bobDelivery = reloaded.initialSync("bob-id", 0);
        check(bobDelivery.deliveries().stream().anyMatch(delivery -> delivery.deliveryId().equals("persistent-shipment")
                && "WOOD".equals(delivery.resourceKey()) && delivery.amount() == 25),
                "persistent resource shipment becomes durable delivery");
        Messages.Command acknowledge = new Messages.Command("acknowledge-delivery", "ACK_DELIVERY", "",
                "persistent-shipment", 0, 0, "");
        World.SyncResult acknowledged = reloaded.applyUpdate("bob-id",
                new Messages.ClientUpdate(4, 0, 0, bobDelivery.eventCursor(), 0, List.of(acknowledge)));
        check(acknowledged.commandResults().getFirst().accepted() && acknowledged.deliveries().isEmpty(),
                "delivery acknowledgment clears durable inbox");
        Messages.Command marketPeace = new Messages.Command("market-peace-01", "SURRENDER_HOUSE_WAR", "",
                "persistent-war", 0, 0, "");
        World.SyncResult peaceful = reloaded.applyUpdate("alice-id",
                new Messages.ClientUpdate(5, 1200, 5555, acknowledged.eventCursor(), 0, List.of(marketPeace)));
        check(peaceful.commandResults().getFirst().accepted(), "formal war surrendered before market purchase");
        Messages.Command buyOffer = new Messages.Command("market-buy-0001", "BUY_OFFER", "",
                "persistent-offer", 10, 0, "");
        World.SyncResult purchased = reloaded.applyUpdate("bob-id",
                new Messages.ClientUpdate(6, 0, 0, peaceful.eventCursor(), 0, List.of(buyOffer)));
        check(purchased.commandResults().getFirst().accepted()
                && purchased.playerState().crowns() == acknowledged.playerState().crowns() - 20
                && purchased.marketOffers().getFirst().quantity() == 20,
                "partial market purchase transfers crowns and reduces offer: result="
                        + purchased.commandResults().getFirst() + ", crowns=" + purchased.playerState().crowns()
                        + ", offer=" + purchased.marketOffers().getFirst());
        Messages.Command cancelOffer = new Messages.Command("market-cancel-01", "CANCEL_OFFER", "",
                "persistent-offer", 0, 0, "");
        World.SyncResult cancelled = reloaded.applyUpdate("alice-id",
                new Messages.ClientUpdate(7, 1200, 5555, purchased.eventCursor(), 0, List.of(cancelOffer)));
        check(cancelled.commandResults().getFirst().accepted() && cancelled.marketOffers().isEmpty()
                && cancelled.deliveries().stream().anyMatch(delivery -> delivery.deliveryId().equals("market-cancel-01")
                        && delivery.amount() == 20 && "STONE".equals(delivery.resourceKey())),
                "offer cancellation returns unsold escrow as a durable self-delivery");
        reloaded.resolveArmies(System.currentTimeMillis() + 86_400_001L);
        World.SyncResult purchasedDelivery = reloaded.initialSync("bob-id", purchased.eventCursor());
        check(purchasedDelivery.deliveries().stream().anyMatch(delivery -> delivery.deliveryId().equals("market-buy-0001")
                && delivery.amount() == 10 && "STONE".equals(delivery.resourceKey())),
                "market purchase becomes a traveling resource delivery");
        World.SyncResult duplicate = reloaded.applyUpdate("alice-id",
                new Messages.ClientUpdate(3, 1200, 5555, battle.eventCursor(), 0, List.of(persistentChat)));
        check(duplicate.commandResults().size() == 1 && duplicate.commandResults().getFirst().accepted(),
                "persistent command idempotence");
        reloaded.leave("alice-id");
        reloaded.leave("bob-id");
        try {
            reloaded.join(hello("alice-id", tokenB, "Intruder", "Stolen"));
            throw new AssertionError("wrong token should be rejected");
        } catch (ProtocolException expected) {
            check("AUTH_FAILED".equals(expected.code()), "wrong-token error code");
        }
        Messages.Hello outdated = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.1.0",
                "Syx Kingdoms Online@0.1.0",
                0, 0, SharedWorld.WORLD_ID, "old-client", "cccccccccccccccccccccccccccccccc",
                "Old Ruler", "Old Realm", "Old City", "");
        try {
            reloaded.join(outdated);
            throw new AssertionError("outdated mod should be rejected");
        } catch (ProtocolException expected) {
            check("MOD_VERSION_MISMATCH".equals(expected.code()), "mod-version error code");
        }
        Messages.Hello extraMod = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0|Example Mod@1.0.0", 0, 0, SharedWorld.WORLD_ID, "modded-client",
                "dddddddddddddddddddddddddddddddd", "Modded Ruler", "Modded Realm", "Modded City", "");
        try {
            reloaded.join(extraMod);
            throw new AssertionError("extra mod should be rejected");
        } catch (ProtocolException expected) {
            check("MOD_SET_MISMATCH".equals(expected.code()), "mod-set error code");
        }
        persisted.setProperty("world.terrainFingerprint", "corrupted");
        try (var writer = Files.newBufferedWriter(config.worldFile())) {
            persisted.store(writer, "intentional test corruption");
        }
        try {
            new World(config);
            throw new AssertionError("changed persistent terrain should be rejected");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("terrain fingerprint"), "terrain-fingerprint rejection");
        }
        verifyCoordinateAllocation(temp.resolve("coordinates.properties"));
        verifyFoundingAndProtection(temp.resolve("founding.properties"));
        verifyLocalChat(temp.resolve("local-chat.properties"));
        verifyCrests(temp.resolve("crests.properties"));
        verifyLegacyFoundingMigration(temp.resolve("legacy-founding.properties"));
        verifyModeration(temp.resolve("moderated"));
        System.out.println("WorldPersistenceTest: PASS");
    }

    private static void verifyLocalChat(Path worldFile) throws Exception {
        World world = new World(config(worldFile));
        String actorId = "local-actor";
        String actorToken = "77777777777777777777777777777777";
        World.JoinResult actor = world.join(hello(actorId, actorToken, "Province Herald", "Herald's Rest"));

        World.JoinResult neighbor = null;
        for (int i = 0; i < 20 && neighbor == null; i++) {
            String id = "local-neighbor-" + i;
            Messages.Hello base = hello(id, String.format("%032d", 8_000 + i),
                    "Neighbor " + i, "Neighbor City " + i);
            Messages.Hello nearby = new Messages.Hello(base.protocolVersion(), base.gameVersion(),
                    base.modVersion(), base.modManifest(), base.lastEventId(), base.lastCrestRevision(), base.worldId(),
                    base.playerId(), base.authToken(), base.playerName(), base.kingdomName(), base.cityName(),
                    actor.player().kingdomName);
            World.JoinResult candidate = world.join(nearby);
            if (SharedWorld.sectorX(candidate.player().x) == SharedWorld.sectorX(actor.player().x)
                    && SharedWorld.sectorY(candidate.player().y) == SharedWorld.sectorY(actor.player().y)) {
                neighbor = candidate;
            }
        }
        check(neighbor != null, "social placement supplies a listener in the actor's local province");

        World.JoinResult distant = null;
        for (int i = 0; i < 100 && distant == null; i++) {
            String id = "distant-listener-" + i;
            World.JoinResult candidate = world.join(hello(id, String.format("%032d", 9_000 + i),
                    "Distant " + i, "Distant City " + i));
            if (SharedWorld.sectorX(candidate.player().x) != SharedWorld.sectorX(actor.player().x)
                    || SharedWorld.sectorY(candidate.player().y) != SharedWorld.sectorY(actor.player().y)) {
                distant = candidate;
            }
        }
        check(distant != null, "balanced placement supplies a listener outside the actor's local province");

        Messages.Command localChat = new Messages.Command("local-chat-0001", "LOCAL_CHAT", "", "",
                0, 0, "Neighbors, the market is open");
        World.SyncResult actorLocal = world.applyUpdate(actorId,
                new Messages.ClientUpdate(1, 0, 0, 0, 0, List.of(localChat)));
        check(actorLocal.commandResults().getFirst().accepted()
                        && "LOCAL_MESSAGE_SENT".equals(actorLocal.commandResults().getFirst().code()),
                "local chat command accepted");
        check(actorLocal.events().stream().anyMatch(event -> "LOCAL_CHAT".equals(event.type())
                        && "Province Herald".equals(event.actorName())),
                "local chat is stamped with the server-owned chosen ruler name");
        World.SyncResult neighborLocal = world.initialSync(neighbor.player().playerId, 0);
        check(neighborLocal.events().stream().anyMatch(event -> "LOCAL_CHAT".equals(event.type())
                        && "Neighbors, the market is open".equals(event.text())),
                "local chat is visible within the sender's 256-by-256 province");
        World.SyncResult distantLocal = world.initialSync(distant.player().playerId, 0);
        check(distantLocal.events().stream().noneMatch(event -> "LOCAL_CHAT".equals(event.type())),
                "local chat is hidden outside the sender's province");

        world.leave(actorId);
        world.join(hello(actorId, actorToken, "Spoofed Name", "Spoofed Capital"));
        Messages.Command globalChat = new Messages.Command("global-chat-0001", "CHAT", "", "",
                0, 0, "This one reaches every province");
        world.applyUpdate(actorId, new Messages.ClientUpdate(2, 0, 0,
                actorLocal.eventCursor(), 0, List.of(globalChat)));
        World.SyncResult distantGlobal = world.initialSync(distant.player().playerId, distantLocal.eventCursor());
        check(distantGlobal.events().stream().anyMatch(event -> "CHAT".equals(event.type())
                        && "Province Herald".equals(event.actorName())
                        && "This one reaches every province".equals(event.text())),
                "global chat remains world-wide and reconnects cannot spoof the chosen ruler name");
    }

    private static void verifyCrests(Path worldFile) throws Exception {
        String artistId = "crest-artist";
        String artistToken = "66666666666666666666666666666666";
        World world = new World(config(worldFile));
        world.join(hello(artistId, artistToken, "Crest Artist", "Palette Keep"));
        World.JoinResult viewer = world.join(hello("crest-viewer", "55555555555555555555555555555555",
                "Crest Viewer", "Gallery City"));

        CrestDesign design = CrestDesign.blank()
                .withColor(1, 0x1122EE)
                .withColor(2, 0xF0A030)
                .withPixel(0, 0, 1)
                .withPixel(31, 31, 2)
                .withPixel(15, 16, 2);
        Messages.Command saveCrest = new Messages.Command("crest-save-0001", "SET_CREST", "",
                design.pixelsEncoded(), 0, 0, design.paletteHex());
        World.SyncResult saved = world.applyUpdate(artistId,
                new Messages.ClientUpdate(1, 0, 0, 0, 0, List.of(saveCrest)));
        check(saved.commandResults().getFirst().accepted()
                        && "CREST_UPDATED".equals(saved.commandResults().getFirst().code()),
                "32x32 crest command accepted");
        check(saved.crests().size() == 1 && saved.crests().getFirst().design().equals(design)
                        && saved.crests().getFirst().hash().equals(design.hash()),
                "server returns the exact validated 16-color crest");
        World.SyncResult seen = world.initialSync(viewer.player().playerId, 0, 0);
        check(seen.crests().stream().anyMatch(crest -> crest.playerId().equals(artistId)
                        && crest.hash().equals(design.hash())),
                "other kingdoms synchronize a saved crest");
        World.SyncResult cached = world.initialSync(viewer.player().playerId, seen.eventCursor(), seen.crestCursor());
        check(cached.crests().isEmpty() && cached.crestCursor() == seen.crestCursor(),
                "crest revision cursor avoids retransmitting cached designs");

        Messages.Command blankCrest = new Messages.Command("crest-blank-001", "SET_CREST", "",
                CrestDesign.blank().pixelsEncoded(), 0, 0, CrestDesign.blank().paletteHex());
        World.SyncResult rejected = world.applyUpdate(artistId,
                new Messages.ClientUpdate(2, 0, 0, saved.eventCursor(), saved.crestCursor(), List.of(blankCrest)));
        check(!rejected.commandResults().getFirst().accepted()
                        && "BAD_CREST".equals(rejected.commandResults().getFirst().code())
                        && rejected.crests().isEmpty(),
                "empty or invisible crest is rejected without changing the synchronized design");

        world.save();
        World reloaded = new World(config(worldFile));
        reloaded.join(hello(artistId, artistToken, "Spoofed Artist", "Spoofed Keep"));
        World.SyncResult persisted = reloaded.initialSync(artistId, 0, 0);
        check(persisted.crests().size() == 1 && persisted.crests().getFirst().design().equals(design),
                "32x32 crest and 16-color palette persist across server restart");
    }

    private static void verifyModeration(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path worldFile = directory.resolve("world.properties");
        Files.writeString(directory.resolve("moderation.properties"),
                "banned=bad-player\nmuted=muted-player\n");
        World world = new World(config(worldFile));
        try {
            world.join(hello("bad-player", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "Bad", "Bad City"));
            throw new AssertionError("banned player should be rejected");
        } catch (ProtocolException expected) {
            check("ACCOUNT_BANNED".equals(expected.code()), "banned-player error code: " + expected.code()
                    + "; cause=" + expected.getCause());
        }
        world.join(hello("muted-player", "ffffffffffffffffffffffffffffffff", "Muted", "Muted City"));
        Messages.Command chat = new Messages.Command("muted-chat", "CHAT", "", "", 0, 0, "hello");
        World.SyncResult result = world.applyUpdate("muted-player",
                new Messages.ClientUpdate(1, 0, 0, 0, 0, List.of(chat)));
        check(!result.commandResults().getFirst().accepted()
                && "ACCOUNT_MUTED".equals(result.commandResults().getFirst().code()), "muted chat rejected");
        world.leave("muted-player");
    }

    private static void verifyCoordinateAllocation(Path worldFile) throws Exception {
        ServerConfig config = config(worldFile);
        World world = new World(config);
        SharedWorldTerrain terrain = new SharedWorldTerrain(config.worldSeed());
        Set<String> coordinates = new HashSet<>();
        for (int i = 0; i < config.maxPlayers(); i++) {
            String id = "player-" + i;
            String token = String.format("%032d", i);
            World.JoinResult joined = world.join(hello(id, token, "Ruler " + i, "City " + i));
            PlayerRecord player = joined.player();
            check(player.x >= 0 && player.x < config.worldWidth(), "allocated x bound");
            check(player.y >= 0 && player.y < config.worldHeight(), "allocated y bound");
            check(player.x % config.citySpacing() == 0 && player.y % config.citySpacing() == 0,
                    "allocated spacing");
            check(terrain.canFoundCity(player.x, player.y), "allocated city is on buildable land");
            check(coordinates.add(player.x + "," + player.y), "allocated coordinate uniqueness");
            world.leave(id);
        }
    }

    private static void verifyFoundingAndProtection(Path worldFile) throws Exception {
        ServerConfig protectedConfig = config(worldFile, 432_000_000L);
        World world = new World(protectedConfig);
        Messages.Hello blank = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, SharedWorld.WORLD_ID, "blank-player",
                "11111111111111111111111111111111", "", "", "", "");
        try {
            world.join(blank);
            throw new AssertionError("blank founding profile should be rejected");
        } catch (ProtocolException expected) {
            check("FOUNDING_REQUIRED".equals(expected.code()), "founding-required error code");
            check(world.metrics().registeredPlayers() == 0, "rejected founding claim consumes no player slot");
        }

        World.JoinResult first = world.join(hello("first-player", "11111111111111111111111111111111",
                "First Ruler", "First Capital"));
        check(first.player().protectionEndsEpochMillis > System.currentTimeMillis(),
                "new kingdom receives beginner protection");
        Messages.Hello duplicateKingdom = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, SharedWorld.WORLD_ID, "duplicate-player",
                "22222222222222222222222222222222", "Second Ruler", first.player().kingdomName,
                "Second Capital", "");
        try {
            world.join(duplicateKingdom);
            throw new AssertionError("duplicate kingdom name should be rejected");
        } catch (ProtocolException expected) {
            check("KINGDOM_NAME_TAKEN".equals(expected.code()), "duplicate-kingdom error code");
            check(world.metrics().registeredPlayers() == 1, "duplicate name consumes no player slot");
        }
        Messages.Hello unknownPlacement = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, SharedWorld.WORLD_ID, "unknown-neighbor",
                "44444444444444444444444444444444", "Neighbor Ruler", "Neighbor Realm",
                "Neighbor Capital", "Missing Realm");
        try {
            world.join(unknownPlacement);
            throw new AssertionError("unknown placement reference should be rejected");
        } catch (ProtocolException expected) {
            check("PLACEMENT_REFERENCE_NOT_FOUND".equals(expected.code()), "unknown-placement error code");
            check(world.metrics().registeredPlayers() == 1, "unknown placement consumes no player slot");
        }

        World.JoinResult second = world.join(hello("second-player", "22222222222222222222222222222222",
                "Second Ruler", "Second Capital"));
        Messages.Hello nearbyHello = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, SharedWorld.WORLD_ID, "nearby-player",
                "44444444444444444444444444444444", "Nearby Ruler", "Nearby Realm",
                "Nearby Capital", first.player().kingdomName);
        World.JoinResult nearby = world.join(nearbyHello);
        long nearbyDx = (long) nearby.player().x - first.player().x;
        long nearbyDy = (long) nearby.player().y - first.player().y;
        check(nearbyDx * nearbyDx + nearbyDy * nearbyDy <= 2L * protectedConfig.citySpacing()
                        * protectedConfig.citySpacing(),
                "social placement chooses an immediately nearby buildable coordinate");
        world.leave("nearby-player");
        world.applyUpdate("first-player", new Messages.ClientUpdate(1, 0, 0, 0, 0, List.of(
                new Messages.Command("first-house-001", "CREATE_HOUSE", "", "FIR", 0, 0, "First House"))));
        world.applyUpdate("second-player", new Messages.ClientUpdate(1, 0, 0, 0, 0, List.of(
                new Messages.Command("second-house-01", "CREATE_HOUSE", "", "SEC", 0, 0, "Second House"))));
        world.applyUpdate("first-player", new Messages.ClientUpdate(2, 0, 0, 0, 0, List.of(
                new Messages.Command("protection-war", "DECLARE_HOUSE_WAR", "", "second-house-01", 0, 0, ""))));
        world.resolveStrategicTime(System.currentTimeMillis() + 13L * 3_600_000L);
        World.SyncResult blocked = world.applyUpdate("first-player", new Messages.ClientUpdate(3, 0, 0, 0, 0, List.of(
                new Messages.Command("protected-army", "SEND_ARMY", "second-player", "", 10, 0, ""))));
        check(!blocked.commandResults().getFirst().accepted()
                        && "TARGET_PROTECTED".equals(blocked.commandResults().getFirst().code()),
                "protected kingdom cannot be attacked");
        second.player().protectionEndsEpochMillis = 0;
        World.SyncResult launched = world.applyUpdate("first-player", new Messages.ClientUpdate(4, 0, 0, 0, 0, List.of(
                new Messages.Command("unprotected-army", "SEND_ARMY", "second-player", "", 10, 0, ""))));
        check(launched.commandResults().getFirst().accepted()
                        && launched.playerState().protectionEndsEpochMillis() == 0,
                "launching an army ends attacker protection");
        check(launched.events().stream().anyMatch(event -> "PROTECTION_ENDED".equals(event.type())),
                "protection-ending event is public");
    }

    private static void verifyLegacyFoundingMigration(Path worldFile) throws Exception {
        String token = "33333333333333333333333333333333";
        World original = new World(config(worldFile));
        World.JoinResult founded = original.join(hello("legacy-player", token, "Legacy Ruler", "Legacy Capital"));
        int originalX = founded.player().x;
        int originalY = founded.player().y;
        original.leave("legacy-player");
        original.save();

        Properties legacy = new Properties();
        try (var reader = Files.newBufferedReader(worldFile)) {
            legacy.load(reader);
        }
        legacy.setProperty("format", "7");
        legacy.remove("player.0.kingdom");
        legacy.remove("player.0.founded");
        legacy.remove("player.0.protectionEnds");
        try (var writer = Files.newBufferedWriter(worldFile)) {
            legacy.store(writer, "simulated pre-founding world");
        }

        World migrated = new World(config(worldFile));
        Messages.Hello blank = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, SharedWorld.WORLD_ID, "legacy-player", token,
                "", "", "", "");
        try {
            migrated.join(blank);
            throw new AssertionError("legacy profile should require founding completion");
        } catch (ProtocolException expected) {
            check("FOUNDING_REQUIRED".equals(expected.code()), "legacy player receives founding prompt");
            check(migrated.metrics().registeredPlayers() == 1, "legacy player keeps registered slot");
        }
        World.JoinResult completed = migrated.join(hello(
                "legacy-player", token, "Legacy Ruler", "Legacy Capital"));
        check(completed.player().x == originalX && completed.player().y == originalY,
                "legacy founding completion preserves city coordinate");
        check(completed.player().foundingComplete(), "legacy profile is upgraded to complete founding state");
    }

    static ServerConfig config(Path worldFile) {
        return config(worldFile, 0);
    }

    static ServerConfig config(Path worldFile, long beginnerProtectionMillis) {
        return ServerConfig.validate(new ServerConfig("127.0.0.1", 0, SharedWorld.WORLD_ID, SharedWorld.WORLD_NAME,
                worldFile, SharedWorld.WIDTH, SharedWorld.HEIGHT, 8, SharedWorld.WORLD_SEED,
                "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0",
                1000, 1.0, 2048, beginnerProtectionMillis, 5000, 1000));
    }

    static Messages.Hello hello(String id, String token, String name, String city) {
        return new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, SharedWorld.WORLD_ID, id, token,
                name, "Realm " + id, city, "");
    }

    static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
