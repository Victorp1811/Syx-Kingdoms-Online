package syxkingdoms.mod;

import syxkingdoms.protocol.SharedWorld;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record ClientConfig(
        boolean enabled,
        String serverHost,
        int serverPort,
        String worldId,
        String playerName,
        String cityName,
        boolean resetIdentity,
        double fallbackFixedSpeed,
        long heartbeatMillis,
        int connectTimeoutMillis,
        boolean useTls,
        String tlsCertificateSha256,
        boolean showOverlay,
        int overlayMaxCities,
        boolean showWorldMap,
        int worldMapWidth,
        int worldMapHeight,
        boolean debug,
        Path identityFile) {

    static ClientConfig load() throws IOException {
        Path configPath = configPath();
        Map<String, String> values = new HashMap<>();
        if (Files.exists(configPath)) {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                values.put(line.substring(0, equals).trim().toUpperCase(Locale.ROOT),
                        line.substring(equals + 1).trim());
            }
        }

        String appData = System.getenv("APPDATA");
        Path appRoot = appData == null || appData.isBlank()
                ? Paths.get(System.getProperty("user.home", "."), ".songsofsyx")
                : Paths.get(appData, "songsofsyx");
        ClientConfig config = new ClientConfig(
                bool(values, "ENABLED", true),
                value(values, "SERVER_HOST", "127.0.0.1"),
                integer(values, "SERVER_PORT", 49720),
                worldId(values),
                value(values, "PLAYER_NAME", "New Ruler"),
                value(values, "CITY_NAME", "New City"),
                bool(values, "RESET_IDENTITY", false),
                decimal(values, "FALLBACK_FIXED_SPEED", 1.0),
                longValue(values, "HEARTBEAT_MILLIS", 2000),
                integer(values, "CONNECT_TIMEOUT_MILLIS", 5000),
                bool(values, "USE_TLS", false),
                value(values, "TLS_CERTIFICATE_SHA256", ""),
                bool(values, "SHOW_OVERLAY", true),
                integer(values, "OVERLAY_MAX_CITIES", 6),
                bool(values, "SHOW_WORLD_MAP", true),
                integer(values, "WORLD_MAP_WIDTH", 480),
                integer(values, "WORLD_MAP_HEIGHT", 480),
                bool(values, "DEBUG", true),
                appRoot.resolve("syx-kingdoms").resolve("identity.properties"));
        validate(config);
        return config;
    }

    private static void validate(ClientConfig config) {
        if (config.serverHost.isBlank()) {
            throw new IllegalArgumentException("SERVER_HOST cannot be blank");
        }
        if (config.serverPort < 1 || config.serverPort > 65535) {
            throw new IllegalArgumentException("SERVER_PORT must be between 1 and 65535");
        }
        if (!SharedWorld.WORLD_ID.equals(config.worldId)) {
            throw new IllegalArgumentException("WORLD_ID must be " + SharedWorld.WORLD_ID
                    + "; Syx Kingdoms Online has one persistent world");
        }
        if (config.playerName.isBlank() || config.playerName.length() > 48
                || config.cityName.isBlank() || config.cityName.length() > 48) {
            throw new IllegalArgumentException("PLAYER_NAME and CITY_NAME must contain 1-48 characters");
        }
        if (!Double.isFinite(config.fallbackFixedSpeed) || config.fallbackFixedSpeed < 0.01
                || config.fallbackFixedSpeed > 250) {
            throw new IllegalArgumentException("FALLBACK_FIXED_SPEED is outside the supported range");
        }
        if (config.heartbeatMillis < 500 || config.heartbeatMillis > 60_000) {
            throw new IllegalArgumentException("HEARTBEAT_MILLIS is outside the supported range");
        }
        if (config.connectTimeoutMillis < 500 || config.connectTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("CONNECT_TIMEOUT_MILLIS is outside the supported range");
        }
        if (!config.tlsCertificateSha256.isBlank()
                && !config.tlsCertificateSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("TLS_CERTIFICATE_SHA256 must be blank or a 64-character SHA-256 hex digest");
        }
        if (config.overlayMaxCities < 1 || config.overlayMaxCities > 20) {
            throw new IllegalArgumentException("OVERLAY_MAX_CITIES must be between 1 and 20");
        }
        if (config.worldMapWidth < 240 || config.worldMapWidth > 900
                || config.worldMapHeight < 100 || config.worldMapHeight > 500) {
            throw new IllegalArgumentException("WORLD_MAP_WIDTH or WORLD_MAP_HEIGHT is outside the supported range");
        }
    }

    private static Path configPath() {
        try {
            URI uri = ClientConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path jar = Paths.get(uri);
            Path script = jar.getParent();
            Path version = script == null ? null : script.getParent();
            Path modRoot = version == null ? null : version.getParent();
            if (modRoot != null) {
                return modRoot.resolve("config.txt");
            }
        } catch (Exception ignored) {
        }
        return Paths.get("config.txt").toAbsolutePath();
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        return values.getOrDefault(key, fallback).trim();
    }

    private static String worldId(Map<String, String> values) {
        String configured = value(values, "WORLD_ID", SharedWorld.WORLD_ID);
        return "local-dev".equals(configured) ? SharedWorld.WORLD_ID : configured;
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        return Boolean.parseBoolean(value(values, key, Boolean.toString(fallback)));
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        return Integer.parseInt(value(values, key, Integer.toString(fallback)));
    }

    private static long longValue(Map<String, String> values, String key, long fallback) {
        return Long.parseLong(value(values, key, Long.toString(fallback)));
    }

    private static double decimal(Map<String, String> values, String key, double fallback) {
        return Double.parseDouble(value(values, key, Double.toString(fallback)));
    }
}
