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

    record Debit(long revision, Escrow escrow) {
    }

    private static final int MAX_APPLIED_DELIVERIES = 10_000;
    private static final int MAX_ACCEPTED_DEBITS = 10_000;
    private final Path path;
    private final Map<String, Debit> outbound = new LinkedHashMap<>();
    private final Map<String, Debit> acceptedDebits = new LinkedHashMap<>();
    private final Set<String> appliedDeliveries = new LinkedHashSet<>();
    private long revision;
    private long retainedFloorRevision;

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
        ledger.revision = Long.parseLong(properties.getProperty("ledger.revision", "0"));
        ledger.retainedFloorRevision = Long.parseLong(properties.getProperty("ledger.retainedFloorRevision", "0"));
        if (ledger.revision < 0 || ledger.retainedFloorRevision < 0
                || ledger.retainedFloorRevision > ledger.revision) {
            throw new IOException("Trade ledger revision state is invalid");
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
            long escrowRevision = Long.parseLong(properties.getProperty(prefix + "revision", "0"));
            if (escrowRevision == 0) {
                escrowRevision = ++ledger.revision;
            }
            validateDebitRevision(escrowRevision, ledger.revision);
            if (ledger.outbound.put(escrow.commandId(), new Debit(escrowRevision, escrow)) != null) {
                throw new IOException("Trade ledger contains a duplicate command ID");
            }
        }
        int acceptedCount = Integer.parseInt(properties.getProperty("accepted.count", "0"));
        if (acceptedCount < 0 || acceptedCount > MAX_ACCEPTED_DEBITS) {
            throw new IOException("Trade ledger accepted-debit count is invalid");
        }
        for (int i = 0; i < acceptedCount; i++) {
            String prefix = "accepted." + i + ".";
            Escrow escrow = readEscrow(properties, prefix);
            long debitRevision = Long.parseLong(required(properties, prefix + "revision"));
            validateDebitRevision(debitRevision, ledger.revision);
            if (ledger.outbound.containsKey(escrow.commandId())
                    || ledger.acceptedDebits.put(escrow.commandId(), new Debit(debitRevision, escrow)) != null) {
                throw new IOException("Trade ledger contains a duplicate debit command ID");
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
        return outbound.values().stream().map(Debit::escrow).toList();
    }

    synchronized Escrow escrow(String commandId) {
        Debit debit = outbound.get(commandId);
        return debit == null ? null : debit.escrow();
    }

    synchronized void add(Escrow escrow) throws IOException {
        validate(escrow);
        if (outbound.size() >= 64 || acceptedDebits.containsKey(escrow.commandId())) {
            throw new IOException("Too many pending resource escrows");
        }
        if (outbound.containsKey(escrow.commandId())) {
            return;
        }
        Debit debit = new Debit(++revision, escrow);
        outbound.put(escrow.commandId(), debit);
        save();
    }

    synchronized void reject(String commandId) throws IOException {
        if (outbound.remove(commandId) != null) {
            save();
        }
    }

    synchronized void accept(String commandId) throws IOException {
        Debit debit = outbound.remove(commandId);
        if (debit == null) {
            return;
        }
        acceptedDebits.put(commandId, debit);
        while (acceptedDebits.size() > MAX_ACCEPTED_DEBITS) {
            Debit removed = acceptedDebits.remove(acceptedDebits.keySet().iterator().next());
            retainedFloorRevision = Math.max(retainedFloorRevision, removed.revision());
        }
        save();
    }

    synchronized long revision() {
        return revision;
    }

    synchronized List<Debit> debitsAfter(long savedRevision) throws IOException {
        if (savedRevision < retainedFloorRevision || savedRevision > revision) {
            throw new IOException("Save revision " + savedRevision
                    + " is outside the retained multiplayer rollback journal");
        }
        List<Debit> result = new ArrayList<>();
        for (Debit debit : acceptedDebits.values()) {
            if (debit.revision() > savedRevision) {
                result.add(debit);
            }
        }
        for (Debit debit : outbound.values()) {
            if (debit.revision() > savedRevision) {
                result.add(debit);
            }
        }
        result.sort(java.util.Comparator.comparingLong(Debit::revision));
        return List.copyOf(result);
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
        properties.setProperty("ledger.revision", Long.toString(revision));
        properties.setProperty("ledger.retainedFloorRevision", Long.toString(retainedFloorRevision));
        List<Debit> pending = new ArrayList<>(outbound.values());
        properties.setProperty("outbound.count", Integer.toString(pending.size()));
        for (int i = 0; i < pending.size(); i++) {
            Debit debit = pending.get(i);
            String prefix = "outbound." + i + ".";
            properties.setProperty(prefix + "revision", Long.toString(debit.revision()));
            writeEscrow(properties, prefix, debit.escrow());
        }
        List<Debit> accepted = new ArrayList<>(acceptedDebits.values());
        properties.setProperty("accepted.count", Integer.toString(accepted.size()));
        for (int i = 0; i < accepted.size(); i++) {
            Debit debit = accepted.get(i);
            String prefix = "accepted." + i + ".";
            properties.setProperty(prefix + "revision", Long.toString(debit.revision()));
            writeEscrow(properties, prefix, debit.escrow());
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
            properties.store(writer, "Syx Kingdoms Online crash-safe escrow, delivery, and rollback journal");
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

    private static Escrow readEscrow(Properties properties, String prefix) throws IOException {
        Escrow escrow = new Escrow(required(properties, prefix + "commandId"),
                properties.getProperty(prefix + "commandType", "SEND_RESOURCE").trim(),
                properties.getProperty(prefix + "targetId", "").trim(),
                required(properties, prefix + "resource"),
                Integer.parseInt(required(properties, prefix + "amount")),
                Long.parseLong(properties.getProperty(prefix + "unitPrice", "0")));
        validate(escrow);
        return escrow;
    }

    private static void writeEscrow(Properties properties, String prefix, Escrow escrow) {
        properties.setProperty(prefix + "commandId", escrow.commandId());
        properties.setProperty(prefix + "commandType", escrow.commandType());
        properties.setProperty(prefix + "targetId", escrow.targetPlayerId());
        properties.setProperty(prefix + "resource", escrow.resourceKey());
        properties.setProperty(prefix + "amount", Integer.toString(escrow.amount()));
        properties.setProperty(prefix + "unitPrice", Long.toString(escrow.unitPrice()));
    }

    private static void validateDebitRevision(long debitRevision, long ledgerRevision) throws IOException {
        if (debitRevision < 1 || debitRevision > ledgerRevision) {
            throw new IOException("Trade ledger contains an invalid debit revision");
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
