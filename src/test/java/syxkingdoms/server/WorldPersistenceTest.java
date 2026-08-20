package syxkingdoms.server;

import syxkingdoms.protocol.Messages;
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
        Messages.Command persistentStance = new Messages.Command("persistent-stance", "STANCE", "bob-id", "WAR",
                0, 0, "");
        Messages.Command persistentArmy = new Messages.Command("persistent-army", "SEND_ARMY", "bob-id", "",
                20, 0, "");
        Messages.Command persistentShipment = new Messages.Command("persistent-shipment", "SEND_RESOURCE", "bob-id",
                "WOOD", 25, 0, "");
        Messages.Command persistentOffer = new Messages.Command("persistent-offer", "CREATE_OFFER", "",
                "STONE", 30, 2, "");
        World.SyncResult social = world.applyUpdate("alice-id",
                new Messages.ClientUpdate(2, 1200, 5555, 0,
                        List.of(persistentChat, persistentShipment, persistentStance, persistentArmy, persistentOffer)));
        check(social.commandResults().stream().allMatch(Messages.CommandResult::accepted),
                "persistent social commands accepted");
        check(social.playerState().levies() == 80 && social.armies().size() == 1,
                "persistent army committed from server levies");
        long armyArrival = social.armies().getFirst().arrivesEpochMillis();
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
        check("7".equals(persisted.getProperty("format")), "persistent world format");
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
                new Messages.ClientUpdate(4, 0, 0, bobDelivery.eventCursor(), List.of(acknowledge)));
        check(acknowledged.commandResults().getFirst().accepted() && acknowledged.deliveries().isEmpty(),
                "delivery acknowledgment clears durable inbox");
        Messages.Command marketPeace = new Messages.Command("market-peace-01", "STANCE", "bob-id",
                "NEUTRAL", 0, 0, "");
        World.SyncResult peaceful = reloaded.applyUpdate("alice-id",
                new Messages.ClientUpdate(5, 1200, 5555, acknowledged.eventCursor(), List.of(marketPeace)));
        check(peaceful.commandResults().getFirst().accepted(), "war stance cleared before market purchase");
        Messages.Command buyOffer = new Messages.Command("market-buy-0001", "BUY_OFFER", "",
                "persistent-offer", 10, 0, "");
        World.SyncResult purchased = reloaded.applyUpdate("bob-id",
                new Messages.ClientUpdate(6, 0, 0, peaceful.eventCursor(), List.of(buyOffer)));
        check(purchased.commandResults().getFirst().accepted()
                && purchased.playerState().crowns() == acknowledged.playerState().crowns() - 20
                && purchased.marketOffers().getFirst().quantity() == 20,
                "partial market purchase transfers crowns and reduces offer: result="
                        + purchased.commandResults().getFirst() + ", crowns=" + purchased.playerState().crowns()
                        + ", offer=" + purchased.marketOffers().getFirst());
        Messages.Command cancelOffer = new Messages.Command("market-cancel-01", "CANCEL_OFFER", "",
                "persistent-offer", 0, 0, "");
        World.SyncResult cancelled = reloaded.applyUpdate("alice-id",
                new Messages.ClientUpdate(7, 1200, 5555, purchased.eventCursor(), List.of(cancelOffer)));
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
                new Messages.ClientUpdate(3, 1200, 5555, battle.eventCursor(), List.of(persistentChat)));
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
                0, SharedWorld.WORLD_ID, "old-client", "cccccccccccccccccccccccccccccccc", "Old", "Old City");
        try {
            reloaded.join(outdated);
            throw new AssertionError("outdated mod should be rejected");
        } catch (ProtocolException expected) {
            check("MOD_VERSION_MISMATCH".equals(expected.code()), "mod-version error code");
        }
        Messages.Hello extraMod = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.5.0",
                "Syx Kingdoms Online@0.5.0|Example Mod@1.0.0", 0, SharedWorld.WORLD_ID, "modded-client",
                "dddddddddddddddddddddddddddddddd", "Modded", "Modded City");
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
        verifyModeration(temp.resolve("moderated"));
        System.out.println("WorldPersistenceTest: PASS");
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
                new Messages.ClientUpdate(1, 0, 0, 0, List.of(chat)));
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

    static ServerConfig config(Path worldFile) {
        return ServerConfig.validate(new ServerConfig("127.0.0.1", 0, SharedWorld.WORLD_ID, SharedWorld.WORLD_NAME,
                worldFile, SharedWorld.WIDTH, SharedWorld.HEIGHT, 8, SharedWorld.WORLD_SEED,
                "0.71.44", "0.5.0",
                "Syx Kingdoms Online@0.5.0",
                1000, 1.0, 2048, 5000, 1000));
    }

    static Messages.Hello hello(String id, String token, String name, String city) {
        return new Messages.Hello(Protocol.VERSION, "0.71.44", "0.5.0",
                "Syx Kingdoms Online@0.5.0", 0, SharedWorld.WORLD_ID, id, token, name, city);
    }

    static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
