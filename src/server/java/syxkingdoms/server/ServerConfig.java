package syxkingdoms.server;

import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ServerConfig(
        String bindAddress,
        int port,
        String worldId,
        String worldName,
        Path worldFile,
        int worldWidth,
        int worldHeight,
        int citySpacing,
        String worldSeed,
        String requiredGameVersion,
        String requiredModVersion,
        String requiredModManifest,
        long tickMillis,
        double fixedGameSpeed,
        int maxPlayers,
        long beginnerProtectionMillis,
        long heartbeatTimeoutMillis,
        long saveIntervalMillis) {

    public static ServerConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Path configuredWorldFile = Path.of(value(properties, "WORLD_FILE", "../server-data/syx-kingdoms-world-v1.properties"));
        if (!configuredWorldFile.isAbsolute()) {
            Path absoluteConfig = path.toAbsolutePath().normalize();
            Path configDirectory = absoluteConfig.getParent();
            configuredWorldFile = (configDirectory == null ? Path.of("").toAbsolutePath() : configDirectory)
                    .resolve(configuredWorldFile).normalize();
        }
        return validate(new ServerConfig(
                value(properties, "BIND_ADDRESS", "0.0.0.0"),
                integer(properties, "PORT", 49720),
                value(properties, "WORLD_ID", SharedWorld.WORLD_ID),
                value(properties, "WORLD_NAME", SharedWorld.WORLD_NAME),
                configuredWorldFile,
                integer(properties, "WORLD_WIDTH", SharedWorld.WIDTH),
                integer(properties, "WORLD_HEIGHT", SharedWorld.HEIGHT),
                integer(properties, "CITY_SPACING", 8),
                value(properties, "WORLD_SEED", SharedWorld.WORLD_SEED),
                value(properties, "REQUIRED_GAME_VERSION", "0.71.44"),
                value(properties, "REQUIRED_MOD_VERSION", "0.13.0"),
                value(properties, "REQUIRED_MOD_MANIFEST", "Syx Kingdoms Online@0.13.0"),
                longValue(properties, "TICK_MILLIS", 1000L),
                doubleValue(properties, "FIXED_GAME_SPEED", SharedWorld.FIXED_GAME_SPEED),
                integer(properties, "MAX_PLAYERS", 2048),
                longValue(properties, "BEGINNER_PROTECTION_MILLIS", 432_000_000L),
                longValue(properties, "HEARTBEAT_TIMEOUT_MILLIS", 15_000L),
                longValue(properties, "SAVE_INTERVAL_MILLIS", 5_000L)));
    }

    public static ServerConfig validate(ServerConfig config) {
        if (config.bindAddress == null || config.bindAddress.isBlank()) {
            throw new IllegalArgumentException("BIND_ADDRESS cannot be blank");
        }
        if (config.port < 0 || config.port > 65535) {
            throw new IllegalArgumentException("PORT must be between 0 and 65535");
        }
        if (!SharedWorld.WORLD_ID.equals(config.worldId)) {
            throw new IllegalArgumentException("WORLD_ID is fixed to the one persistent world: "
                    + SharedWorld.WORLD_ID);
        }
        if (config.worldName.isBlank() || config.worldName.length() > 80) {
            throw new IllegalArgumentException("WORLD_NAME must contain 1-80 characters");
        }
        if (config.worldWidth != SharedWorld.WIDTH || config.worldHeight != SharedWorld.HEIGHT) {
            throw new IllegalArgumentException("Syx Kingdoms shared worlds must be "
                    + SharedWorld.WIDTH + "x" + SharedWorld.HEIGHT
                    + " (10x the base-game world-map width and height)");
        }
        if (config.citySpacing < 1 || config.citySpacing > Math.min(config.worldWidth, config.worldHeight)) {
            throw new IllegalArgumentException("CITY_SPACING is outside the supported range");
        }
        if (!SharedWorld.WORLD_SEED.equals(config.worldSeed)) {
            throw new IllegalArgumentException("WORLD_SEED is fixed; the persistent multiplayer map cannot be rerolled");
        }
        if (!config.requiredGameVersion.matches("0\\.\\d+\\.\\d+")
                || !config.requiredModVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("Required game or mod version is malformed");
        }
        if (config.requiredModManifest.isBlank() || config.requiredModManifest.length() > 512) {
            throw new IllegalArgumentException("REQUIRED_MOD_MANIFEST must contain 1-512 characters");
        }
        if (config.tickMillis < 50 || config.tickMillis > 86_400_000) {
            throw new IllegalArgumentException("TICK_MILLIS is outside the supported range");
        }
        if (Double.compare(config.fixedGameSpeed, SharedWorld.FIXED_GAME_SPEED) != 0) {
            throw new IllegalArgumentException("FIXED_GAME_SPEED is locked to "
                    + SharedWorld.FIXED_GAME_SPEED + " for the canonical online ruleset");
        }
        if (config.maxPlayers < 1 || config.maxPlayers > 2048) {
            throw new IllegalArgumentException("MAX_PLAYERS must be between 1 and 2048");
        }
        long coordinateCapacity = (long) ((config.worldWidth - 1) / config.citySpacing + 1)
                * ((config.worldHeight - 1) / config.citySpacing + 1);
        if (config.maxPlayers > coordinateCapacity) {
            throw new IllegalArgumentException("MAX_PLAYERS exceeds the available spaced city coordinates");
        }
        long buildableCapacity = new SharedWorldTerrain(config.worldSeed)
                .cityCoordinateCapacity(config.citySpacing);
        if (config.maxPlayers > buildableCapacity) {
            throw new IllegalArgumentException("MAX_PLAYERS exceeds the available plains/forest city coordinates");
        }
        if (config.beginnerProtectionMillis < 0 || config.beginnerProtectionMillis > 2_592_000_000L) {
            throw new IllegalArgumentException("BEGINNER_PROTECTION_MILLIS must be between 0 and 30 days");
        }
        if (config.heartbeatTimeoutMillis < 2_000 || config.heartbeatTimeoutMillis > 600_000) {
            throw new IllegalArgumentException("HEARTBEAT_TIMEOUT_MILLIS is outside the supported range");
        }
        if (config.saveIntervalMillis < 500 || config.saveIntervalMillis > 600_000) {
            throw new IllegalArgumentException("SAVE_INTERVAL_MILLIS is outside the supported range");
        }
        return config;
    }

    private static String value(Properties properties, String key, String fallback) {
        return properties.getProperty(key, fallback).trim();
    }

    private static int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(value(properties, key, Integer.toString(fallback)));
    }

    private static long longValue(Properties properties, String key, long fallback) {
        return Long.parseLong(value(properties, key, Long.toString(fallback)));
    }

    private static double doubleValue(Properties properties, String key, double fallback) {
        return Double.parseDouble(value(properties, key, Double.toString(fallback)));
    }
}
