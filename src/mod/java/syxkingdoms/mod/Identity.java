package syxkingdoms.mod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

record Identity(String playerId, String authToken) {
    static Identity loadOrCreate(Path path, boolean reset) throws IOException {
        if (!reset && Files.exists(path)) {
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
            String id = p.getProperty("playerId", "").trim();
            String token = p.getProperty("authToken", "").trim();
            if (id.matches("[A-Za-z0-9._-]{3,64}") && token.length() >= 24 && token.length() <= 256) {
                return new Identity(id, token);
            }
        }

        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Identity identity = new Identity(UUID.randomUUID().toString(), HexFormat.of().formatHex(secret));
        write(path, identity);
        return identity;
    }

    private static void write(Path path, Identity identity) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties p = new Properties();
        p.setProperty("playerId", identity.playerId);
        p.setProperty("authToken", identity.authToken);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(writer, "Syx Kingdoms Online identity - keep the authentication token private");
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
