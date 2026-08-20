package syxkingdoms.server;

import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;

import java.nio.file.Path;

public final class ServerConfigTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the shipped server.properties path");
        }
        ServerConfig config = ServerConfig.load(Path.of(args[0]));
        check(SharedWorld.WORLD_ID.equals(config.worldId()), "canonical world ID");
        check(SharedWorld.WORLD_NAME.equals(config.worldName()), "canonical world name");
        check(SharedWorld.WORLD_SEED.equals(config.worldSeed()), "canonical world seed");
        check(config.worldWidth() == SharedWorld.WIDTH && config.worldHeight() == SharedWorld.HEIGHT,
                "canonical world dimensions");
        check(config.maxPlayers() == 2048, "canonical player capacity");
        check(new SharedWorldTerrain(config.worldSeed()).cityCoordinateCapacity(config.citySpacing())
                >= config.maxPlayers(), "canonical buildable capacity");
        System.out.println("ServerConfigTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
