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
        check(config.maxPlayers() == SharedWorld.MAX_PLAYER_CITIES, "canonical player capacity");
        check(config.beginnerProtectionMillis() == 432_000_000L, "canonical five-day beginner protection");
        check(Double.compare(config.fixedGameSpeed(), SharedWorld.FIXED_GAME_SPEED) == 0,
                "canonical fixed 1x simulation speed");
        check(new SharedWorldTerrain(config.worldSeed()).cityCoordinateCapacity(config.citySpacing())
                >= config.maxPlayers(), "canonical buildable capacity");
        try {
            ServerConfig.validate(new ServerConfig(config.bindAddress(), config.port(), config.worldId(),
                    config.worldName(), config.worldFile(), config.worldWidth(), config.worldHeight(),
                    config.citySpacing(), config.worldSeed(), config.requiredGameVersion(),
                    config.requiredModVersion(), config.requiredModManifest(), config.tickMillis(), 5.0,
                    config.maxPlayers(), config.beginnerProtectionMillis(),
                    config.heartbeatTimeoutMillis(), config.saveIntervalMillis()));
            throw new AssertionError("noncanonical speed should be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("locked"), "fixed-speed rejection message");
        }
        System.out.println("ServerConfigTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
