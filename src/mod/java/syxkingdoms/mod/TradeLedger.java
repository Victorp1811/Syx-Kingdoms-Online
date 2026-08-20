package syxkingdoms.mod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class TradeLedger {
    record Escrow(String commandId, String commandType, String targetPlayerId,
            String resourceKey, int amount, long unitPrice) {
        Escrow(String commandId, String targetPlayerId, String resourceKey, int amount) {
            this(commandId, "SEND_RESOURCE", targetPlayerId, resourceKey, amount, 0);
        }

        static Escrow marketOffer(String commandId, String resourceKey, int amount, long unitPrice) {
            return new Escrow(commandId, "CREATE_OFFER", "", resourceKey, amount, unitPrice);
        }
    }

    private static final int MAX_APPLIED_DELIVERIES = 10_000;
    private final Path path;
    private final Map<String, Escrow> outbound = new LinkedHashMap<>();
    private final Set<String> appliedDeliveries = new LinkedHashSet<>();

    private TradeLedger(Path path) {
        this.path = path;
    }

    static TradeLedger load(Path identityFile) throws IOException {
        Path parent = identityFile.toAbsolutePath().getParent();
        Path base = parent == null ? Path.of("").toAbsolutePath() : parent;
        Path path = base.resolve("trade-ledger.properties");
        TradeLedger ledger = new TradeLedger(path);
        if (!Files.exists(path)) {
            return ledger;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        int outboundCount = Integer.parseInt(properties.getProperty("outbound.count", "0"));
        if (outboundCount < 0 || outboundCount > 64) {
            throw new IOException("Trade ledger outbound count is invalid");
        }
        for (int i = 0; i < outboundCount; i++) {
            String prefix = "outbound." + i + ".";
            Escrow escrow = new Escrow(required(properties, prefix + "commandId"),
                    properties.getProperty(prefix + "commandType", "SEND_RESOURCE").trim(),
                    properties.getProperty(prefix + "targetId", "").trim(),
                    required(properties, prefix + "resource"),
                    Integer.parseInt(required(properties, prefix + "amount")),
                    Long.parseLong(properties.getProperty(prefix + "unitPrice", "0")));
            validate(escrow);
            if (ledger.outbound.put(escrow.commandId(), escrow) != null) {
                throw new IOException("Trade ledger contains a duplicate command ID");
            }
        }
        int appliedCount = Integer.parseInt(properties.getProperty("applied.count", "0"));
        if (appliedCount < 0 || appliedCount > MAX_APPLIED_DELIVERIES) {
            throw new IOException("Trade ledger delivery count is invalid");
        }
        for (int i = 0; i < appliedCount; i++) {
            String id = required(properties, "applied." + i);
            if (!id.matches("[A-Za-z0-9-]{8,64}") || !ledger.appliedDeliveries.add(id)) {
                throw new IOException("Trade ledger contains an invalid delivery ID");
            }
        }
        return ledger;
    }

    synchronized List<Escrow> outbound() {
        return List.copyOf(outbound.values());
    }

    synchronized Escrow escrow(String commandId) {
        return outbound.get(commandId);
    }

    synchronized void add(Escrow escrow) throws IOException {
        validate(escrow);
        if (outbound.size() >= 64) {
            throw new IOException("Too many pending resource escrows");
        }
        outbound.put(escrow.commandId(), escrow);
        save();
    }

    synchronized void remove(String commandId) throws IOException {
        if (outbound.remove(commandId) != null) {
            save();
        }
    }

    synchronized boolean deliveryApplied(String deliveryId) {
        return appliedDeliveries.contains(deliveryId);
    }

    synchronized void markDeliveryApplied(String deliveryId) throws IOException {
        if (!deliveryId.matches("[A-Za-z0-9-]{8,64}")) {
            throw new IOException("Invalid delivery ID");
        }
        if (!appliedDeliveries.add(deliveryId)) {
            return;
        }
        while (appliedDeliveries.size() > MAX_APPLIED_DELIVERIES) {
            appliedDeliveries.remove(appliedDeliveries.iterator().next());
        }
        save();
    }

    private void save() throws IOException {
        Properties properties = new Properties();
        List<Escrow> escrows = new ArrayList<>(outbound.values());
        properties.setProperty("outbound.count", Integer.toString(escrows.size()));
        for (int i = 0; i < escrows.size(); i++) {
            Escrow escrow = escrows.get(i);
            String prefix = "outbound." + i + ".";
            properties.setProperty(prefix + "commandId", escrow.commandId());
            properties.setProperty(prefix + "commandType", escrow.commandType());
            properties.setProperty(prefix + "targetId", escrow.targetPlayerId());
            properties.setProperty(prefix + "resource", escrow.resourceKey());
            properties.setProperty(prefix + "amount", Integer.toString(escrow.amount()));
            properties.setProperty(prefix + "unitPrice", Long.toString(escrow.unitPrice()));
        }
        properties.setProperty("applied.count", Integer.toString(appliedDeliveries.size()));
        int index = 0;
        for (String deliveryId : appliedDeliveries) {
            properties.setProperty("applied." + index++, deliveryId);
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "Syx Kingdoms Online crash-safe resource escrow/delivery ledger");
        }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validate(Escrow escrow) throws IOException {
        boolean shipment = "SEND_RESOURCE".equals(escrow.commandType())
                && escrow.targetPlayerId().matches("[A-Za-z0-9._-]{3,64}")
                && escrow.unitPrice() == 0;
        boolean offer = "CREATE_OFFER".equals(escrow.commandType())
                && escrow.targetPlayerId().isEmpty()
                && escrow.unitPrice() >= 1 && escrow.unitPrice() <= 1_000_000;
        if (!escrow.commandId().matches("[A-Za-z0-9-]{8,64}")
                || (!shipment && !offer)
                || !escrow.resourceKey().matches("[A-Za-z0-9._-]{1,64}")
                || escrow.amount() < 1 || escrow.amount() > 10_000) {
            throw new IOException("Invalid resource escrow record");
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Trade ledger is missing " + key);
        }
        return value.trim();
    }
}
