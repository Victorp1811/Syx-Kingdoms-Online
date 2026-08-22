package syxkingdoms.server;

import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.Protocol;
import syxkingdoms.protocol.ProtocolException;
import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class WorldPersistenceTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("syx-kingdoms-world-test-");
        ServerConfig config = config(temp.resolve("world.properties"));
        SharedWorldTerrain terrain = new SharedWorldTerrain(config.worldSeed());
        check(config.worldWidth() == 1000 && config.worldHeight() == 1000,
                "shared world uses the stable canonical 1000-square geometry");
        check(SharedWorld.SECTOR_COUNT == 100 && SharedWorld.PROVINCE_TILE_DIMENSION == 100,
                "shared world keeps one hundred strategic provinces");
        String tokenA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String tokenB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        World world = new World(config);
        World.JoinResult alice = world.join(hello("alice-id", tokenA, "Alice", "Oakhold"));
        World.JoinResult bob = world.join(hello("bob-id", tokenB, "Bob", "Ashkeep"));
        check(alice.player().x >= 0 && alice.player().x < config.worldWidth(), "first city x bound");
        check(alice.player().y >= 0 && alice.player().y < config.worldHeight(), "first city y bound");
        check(alice.player().x != bob.player().x || alice.player().y != bob.player().y, "distinct city slots");
        check(distanceSquared(alice.player(), bob.player()) >= (long) SharedWorld.PLAYER_CITY_MIN_DISTANCE
                * SharedWorld.PLAYER_CITY_MIN_DISTANCE, "player cities receive isolated founding clusters");
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
        check(world.snapshot().cities().size() == 8, "two players plus six nearby NPC cities in snapshot");
        check(world.snapshot().cities().stream().filter(WorldPersistenceTest::isNpc).count() == 6,
                "exactly three neutral cities exist per player");
        check(world.snapshot().cities().stream().filter(Messages.City::online).count() == 2, "online snapshot");
        world.leave("alice-id");
        world.leave("bob-id");
        world.save();
        Properties persisted = new Properties();
        try (var reader = Files.newBufferedReader(config.worldFile())) {
            persisted.load(reader);
        }
        check("13".equals(persisted.getProperty("format")), "persistent world format");
        check("6".equals(persisted.getProperty("npcs.count")), "NPC cities persist in the world file");
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
        Messages.Hello extraMod = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0|Example Mod@1.0.0", 0, 0, SharedWorld.WORLD_ID, "modded-client",
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
        verifyRequestedFoundingReload(temp.resolve("requested-founding.properties"));
        verifyLocalChat(temp.resolve("local-chat.properties"));
        verifyCrests(temp.resolve("crests.properties"));
        verifyLegacyFoundingMigration(temp.resolve("legacy-founding.properties"));
        verify2560GeometryMigration(temp.resolve("geometry-migration.properties"));
        verifyModeration(temp.resolve("moderated"));
        System.out.println("WorldPersistenceTest: PASS");
    }

    private static void verifyLocalChat(Path worldFile) throws Exception {
        ServerConfig localConfig = config(worldFile);
        World world = new World(localConfig);
        String actorId = "local-actor";
        String actorToken = "77777777777777777777777777777777";
        World.JoinResult actor = world.join(hello(actorId, actorToken, "Province Herald", "Herald's Rest"));

        World.JoinResult neighbor = null;
        for (int i = 0; i < localConfig.maxPlayers() - 2 && neighbor == null; i++) {
            String id = "local-neighbor-" + i;
            World.JoinResult candidate = world.join(hello(id, String.format("%032d", 8_000 + i),
                    "Neighbor " + i, "Neighbor City " + i));
            if (withinLocalChat(actor.player(), candidate.player())) {
                neighbor = candidate;
            }
        }
        check(neighbor != null, "isolated allocation still supplies a nearby local-chat listener");

        World.JoinResult distant = null;
        for (int i = 0; i < 100 && distant == null; i++) {
            String id = "distant-listener-" + i;
            World.JoinResult candidate = world.join(hello(id, String.format("%032d", 9_000 + i),
                    "Distant " + i, "Distant City " + i));
            if (!withinLocalChat(actor.player(), candidate.player())) {
                distant = candidate;
            }
        }
        check(distant != null, "balanced placement supplies a listener outside local-chat range");

        long actorCursor = drainEventCursor(world, actorId);
        Messages.Command localChat = new Messages.Command("local-chat-0001", "LOCAL_CHAT", "", "",
                0, 0, "Neighbors, the market is open");
        World.SyncResult actorLocal = world.applyUpdate(actorId,
                new Messages.ClientUpdate(1, 0, 0, actorCursor, 0, List.of(localChat)));
        check(actorLocal.commandResults().getFirst().accepted()
                        && "LOCAL_MESSAGE_SENT".equals(actorLocal.commandResults().getFirst().code()),
                "local chat command accepted");
        check(actorLocal.events().stream().anyMatch(event -> "LOCAL_CHAT".equals(event.type())
                        && "Province Herald".equals(event.actorName())),
                "local chat is stamped with the server-owned chosen ruler name");
        check(hasVisibleEvent(world, neighbor.player().playerId, event -> "LOCAL_CHAT".equals(event.type())
                        && "Neighbors, the market is open".equals(event.text())),
                "local chat is visible within the sender's local radius");
        check(!hasVisibleEvent(world, distant.player().playerId, event -> "LOCAL_CHAT".equals(event.type())),
                "local chat is hidden outside the sender's local radius");
        long distantCursor = drainEventCursor(world, distant.player().playerId);

        world.leave(actorId);
        world.join(hello(actorId, actorToken, "Spoofed Name", "Spoofed Capital"));
        Messages.Command globalChat = new Messages.Command("global-chat-0001", "CHAT", "", "",
                0, 0, "This one reaches every province");
        world.applyUpdate(actorId, new Messages.ClientUpdate(2, 0, 0,
                actorLocal.eventCursor(), 0, List.of(globalChat)));
        World.SyncResult distantGlobal = world.initialSync(distant.player().playerId, distantCursor);
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
        List<PlayerRecord> allocated = new ArrayList<>();
        for (int i = 0; i < config.maxPlayers(); i++) {
            String id = "player-" + i;
            String token = String.format("%032d", i);
            World.JoinResult joined;
            try {
                joined = world.join(hello(id, token, "Ruler " + i, "City " + i));
            } catch (ProtocolException e) {
                throw new AssertionError("cluster allocation failed at player " + i
                        + " of configured capacity " + config.maxPlayers(), e);
            }
            PlayerRecord player = joined.player();
            check(player.x >= 0 && player.x < config.worldWidth(), "allocated x bound");
            check(player.y >= 0 && player.y < config.worldHeight(), "allocated y bound");
            check(player.x % config.citySpacing() == 0 && player.y % config.citySpacing() == 0,
                    "allocated spacing");
            check(terrain.canFoundCity(player.x, player.y), "allocated city is on buildable land");
            check(coordinates.add(player.x + "," + player.y), "allocated coordinate uniqueness");
            for (PlayerRecord other : allocated) {
                check(distanceSquared(player, other) >= (long) SharedWorld.PLAYER_CITY_MIN_DISTANCE
                        * SharedWorld.PLAYER_CITY_MIN_DISTANCE, "allocated player minimum distance");
            }
            allocated.add(player);
            List<Messages.City> nearbyNpcs = world.snapshot().cities().stream()
                    .filter(city -> city.playerId().startsWith("npc-" + id + "-")).toList();
            check(nearbyNpcs.size() == SharedWorld.NPC_CITIES_PER_PLAYER,
                    "each allocated player receives three NPC cities");
            for (Messages.City npc : nearbyNpcs) {
                check(npc.cityName().startsWith(player.cityName + " "),
                        "NPC city name is derived from its patron capital");
                long distance = distanceSquared(player.x, player.y, npc.x(), npc.y());
                check(distance >= (long) SharedWorld.NPC_CITY_MIN_DISTANCE * SharedWorld.NPC_CITY_MIN_DISTANCE
                                && distance <= (long) SharedWorld.NPC_CITY_MAX_DISTANCE
                                * SharedWorld.NPC_CITY_MAX_DISTANCE,
                        "NPC city is inside its player's nearby ring");
                check(coordinates.add(npc.x() + "," + npc.y()), "NPC coordinate uniqueness");
            }
            world.leave(id);
        }
        check(world.snapshot().cities().size() == config.maxPlayers()
                * (SharedWorld.NPC_CITIES_PER_PLAYER + 1), "full allocation preserves four-city clusters");
    }

    private static void verifyFoundingAndProtection(Path worldFile) throws Exception {
        ServerConfig protectedConfig = config(worldFile, 432_000_000L);
        World world = new World(protectedConfig);
        Messages.Hello blank = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0", 0, 0, SharedWorld.WORLD_ID, "blank-player",
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
        Messages.Hello duplicateKingdom = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0", 0, 0, SharedWorld.WORLD_ID, "duplicate-player",
                "22222222222222222222222222222222", "Second Ruler", first.player().kingdomName,
                "Second Capital", "");
        try {
            world.join(duplicateKingdom);
            throw new AssertionError("duplicate kingdom name should be rejected");
        } catch (ProtocolException expected) {
            check("KINGDOM_NAME_TAKEN".equals(expected.code()), "duplicate-kingdom error code");
            check(world.metrics().registeredPlayers() == 1, "duplicate name consumes no player slot");
        }
        Messages.Hello unknownPlacement = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0", 0, 0, SharedWorld.WORLD_ID, "unknown-neighbor",
                "44444444444444444444444444444444", "Neighbor Ruler", "Neighbor Realm",
                "Neighbor Capital", "Missing Realm");
        try {
            world.join(unknownPlacement);
            throw new AssertionError("invalid founding coordinate should be rejected");
        } catch (ProtocolException expected) {
            check("FOUNDING_LOCATION_INVALID".equals(expected.code()), "invalid-location error code");
            check(world.metrics().registeredPlayers() == 1, "unknown placement consumes no player slot");
        }

        World.JoinResult second = world.join(hello("second-player", "22222222222222222222222222222222",
                "Second Ruler", "Second Capital"));
        check(distanceSquared(first.player(), second.player()) >= (long) SharedWorld.PLAYER_CITY_MIN_DISTANCE
                        * SharedWorld.PLAYER_CITY_MIN_DISTANCE,
                "automatic founding keeps kingdoms outside the player exclusion radius");

        Messages.City neutral = world.snapshot().cities().stream()
                .filter(city -> city.playerId().startsWith("npc-first-player-")).findFirst().orElseThrow();
        long protectionBeforePve = first.player().protectionEndsEpochMillis;
        World.SyncResult pveMarch = world.applyUpdate("first-player", new Messages.ClientUpdate(1, 0, 0, 0, 0,
                List.of(new Messages.Command("neutral-army-01", "SEND_ARMY", neutral.playerId(), "", 50, 0, ""))));
        check(pveMarch.commandResults().getFirst().accepted()
                        && "NPC_ARMY_SENT".equals(pveMarch.commandResults().getFirst().code()),
                "neutral NPC city can be attacked without a House war");
        check(first.player().protectionEndsEpochMillis == protectionBeforePve,
                "PvE attacks do not end beginner protection");
        long pveArrival = pveMarch.armies().stream().filter(army -> "NPC_ATTACK".equals(army.mission()))
                .findFirst().orElseThrow().arrivesEpochMillis();
        world.resolveArmies(pveArrival + 1);
        check(world.initialSync("first-player", 0).battleReports().stream()
                        .anyMatch(report -> neutral.playerId().equals(report.defenderPlayerId())
                                && report.outcome().startsWith("NPC_")),
                "neutral battle resolves into a persistent PvE report");
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

    private static void verifyRequestedFoundingReload(Path worldFile) throws Exception {
        ServerConfig config = config(worldFile);
        SharedWorldTerrain terrain = new SharedWorldTerrain(config.worldSeed());
        World world = new World(config);
        String playerId = "requested-location-player";
        String token = "12121212121212121212121212121212";
        World.JoinResult founded = null;
        for (int y = 0; y < config.worldHeight() && founded == null; y++) {
            for (int x = 0; x < config.worldWidth() && founded == null; x++) {
                if ((x % config.citySpacing() == 0 && y % config.citySpacing() == 0)
                        || !terrain.canFoundCity(x, y)) {
                    continue;
                }
                Messages.Hello requested = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                        "Syx Kingdoms Online@0.14.0", 0, 0, SharedWorld.WORLD_ID, playerId, token,
                        "Requested Ruler", "Requested Realm", "Requested Capital", x + "," + y);
                try {
                    founded = world.join(requested);
                } catch (ProtocolException expectedWhenClusterIsBlocked) {
                    check(expectedWhenClusterIsBlocked.code().startsWith("FOUNDING_"),
                            "requested founding candidate failure has a founding error code");
                }
            }
        }
        check(founded != null, "a non-grid native capital coordinate can be founded");
        check(founded.player().x % config.citySpacing() != 0 || founded.player().y % config.citySpacing() != 0,
                "requested capital is not restricted to the automatic-allocation grid");
        int foundedX = founded.player().x;
        int foundedY = founded.player().y;
        world.leave(playerId);
        world.save();

        World reloaded = new World(config);
        World.JoinResult joinedAgain = reloaded.join(hello(playerId, token,
                "Ignored Reconnect Ruler", "Ignored Reconnect Capital"));
        check(joinedAgain.player().x == foundedX && joinedAgain.player().y == foundedY,
                "requested non-grid capital coordinate survives server restart");
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
        Messages.Hello blank = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0", 0, 0, SharedWorld.WORLD_ID, "legacy-player", token,
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

    private static void verify2560GeometryMigration(Path worldFile) throws Exception {
        String playerId = "geometry-player";
        String token = "44444444444444444444444444444444";
        ServerConfig config = config(worldFile);
        World original = new World(config);
        original.join(hello(playerId, token, "Geometry Ruler", "Geometry Capital"));
        original.leave(playerId);
        original.save();

        Properties legacy = new Properties();
        try (var reader = Files.newBufferedReader(worldFile)) {
            legacy.load(reader);
        }
        legacy.setProperty("world.width", "2560");
        legacy.setProperty("world.height", "2560");
        legacy.setProperty("world.terrainFingerprint", "legacy-2560-fingerprint");
        legacy.setProperty("player.0.x", "1014");
        legacy.setProperty("player.0.y", "862");
        try (var writer = Files.newBufferedWriter(worldFile)) {
            legacy.store(writer, "simulated 2560-square persistent world");
        }

        World migrated = new World(config);
        World.JoinResult joined = migrated.join(hello(
                playerId, token, "Ignored Ruler", "Ignored Capital"));
        check(Math.abs(joined.player().x - 396) <= 32 && Math.abs(joined.player().y - 337) <= 32,
                "2560 player coordinate migrates proportionally onto nearby buildable 1000 terrain");
        check(migrated.snapshot().cities().size() == 4,
                "geometry migration recreates the player's three deterministic NPC cities");
        migrated.leave(playerId);
        migrated.save();

        Properties canonical = new Properties();
        try (var reader = Files.newBufferedReader(worldFile)) {
            canonical.load(reader);
        }
        check("1000".equals(canonical.getProperty("world.width"))
                        && "1000".equals(canonical.getProperty("world.height")),
                "migrated world is persisted with canonical geometry");
        check(new SharedWorldTerrain(config.worldSeed()).fingerprint()
                        .equals(canonical.getProperty("world.terrainFingerprint")),
                "migrated world receives the canonical 1000 terrain fingerprint");
    }

    static ServerConfig config(Path worldFile) {
        return config(worldFile, 0);
    }

    static ServerConfig config(Path worldFile, long beginnerProtectionMillis) {
        return ServerConfig.validate(new ServerConfig("127.0.0.1", 0, SharedWorld.WORLD_ID, SharedWorld.WORLD_NAME,
                worldFile, SharedWorld.WIDTH, SharedWorld.HEIGHT, 8, SharedWorld.WORLD_SEED,
                "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0",
                1000, 1.0, SharedWorld.MAX_PLAYER_CITIES, beginnerProtectionMillis, 5000, 1000));
    }

    static Messages.Hello hello(String id, String token, String name, String city) {
        return new Messages.Hello(Protocol.VERSION, "0.71.44", "0.14.0",
                "Syx Kingdoms Online@0.14.0", 0, 0, SharedWorld.WORLD_ID, id, token,
                name, "Realm " + id, city, "");
    }

    static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static long drainEventCursor(World world, String playerId) throws Exception {
        long cursor = 0;
        for (int page = 0; page < 100; page++) {
            World.SyncResult sync = world.initialSync(playerId, cursor);
            if (sync.eventCursor() == cursor) {
                return cursor;
            }
            cursor = sync.eventCursor();
            if (sync.events().size() < Protocol.MAX_EVENTS_PER_SYNC) {
                return cursor;
            }
        }
        throw new AssertionError("event cursor did not drain");
    }

    private static boolean hasVisibleEvent(World world, String playerId,
            java.util.function.Predicate<Messages.WorldEvent> predicate) throws Exception {
        long cursor = 0;
        for (int page = 0; page < 100; page++) {
            World.SyncResult sync = world.initialSync(playerId, cursor);
            if (sync.events().stream().anyMatch(predicate)) {
                return true;
            }
            if (sync.eventCursor() == cursor || sync.events().size() < Protocol.MAX_EVENTS_PER_SYNC) {
                return false;
            }
            cursor = sync.eventCursor();
        }
        throw new AssertionError("event visibility scan did not drain");
    }

    private static boolean isNpc(Messages.City city) {
        return city.playerId().startsWith("npc-");
    }

    private static boolean withinLocalChat(PlayerRecord first, PlayerRecord second) {
        long radius = SharedWorld.LOCAL_CHAT_RADIUS;
        return distanceSquared(first, second) <= radius * radius;
    }

    private static long distanceSquared(PlayerRecord first, PlayerRecord second) {
        return distanceSquared(first.x, first.y, second.x, second.y);
    }

    private static long distanceSquared(int x1, int y1, int x2, int y2) {
        long dx = (long) x1 - x2;
        long dy = (long) y1 - y2;
        return dx * dx + dy * dy;
    }
}
