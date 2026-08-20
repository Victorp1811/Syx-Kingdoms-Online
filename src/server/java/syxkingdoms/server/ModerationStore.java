package syxkingdoms.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

final class ModerationStore {
    private final Path path;
    private Set<String> banned = Set.of();
    private Set<String> muted = Set.of();
    private long nextReloadMillis;
    private long loadedModifiedMillis = Long.MIN_VALUE;

    ModerationStore(Path worldFile) {
        path = worldFile.toAbsolutePath().resolveSibling("moderation.properties");
    }

    synchronized boolean banned(String playerId) throws IOException {
        reloadIfNeeded();
        return banned.contains(playerId);
    }

    synchronized boolean muted(String playerId) throws IOException {
        reloadIfNeeded();
        return muted.contains(playerId);
    }

    private void reloadIfNeeded() throws IOException {
        long now = System.currentTimeMillis();
        if (now < nextReloadMillis) {
            return;
        }
        nextReloadMillis = now + 5_000;
        long modified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
        if (modified == loadedModifiedMillis) {
            return;
        }
        if (!Files.exists(path)) {
            banned = Set.of();
            muted = Set.of();
            loadedModifiedMillis = -1;
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        banned = ids(properties.getProperty("banned", ""));
        muted = ids(properties.getProperty("muted", ""));
        loadedModifiedMillis = modified;
    }

    private static Set<String> ids(String value) throws IOException {
        Set<String> result = new HashSet<>();
        for (String raw : value.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!id.matches("[A-Za-z0-9._-]{3,64}")) {
                throw new IOException("moderation.properties contains an invalid player ID");
            }
            result.add(id);
        }
        return Set.copyOf(result);
    }
}
