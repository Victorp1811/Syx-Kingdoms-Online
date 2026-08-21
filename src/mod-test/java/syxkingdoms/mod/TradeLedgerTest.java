package syxkingdoms.mod;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TradeLedgerTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("syx-kingdoms-trade-ledger-");
        Path identity = temp.resolve("identity.properties");
        TradeLedger ledger = TradeLedger.load(identity);
        TradeLedger.Escrow escrow = new TradeLedger.Escrow(
                "resource-command", "target-player", "WOOD", 25);
        TradeLedger.Escrow offerEscrow = TradeLedger.Escrow.marketOffer(
                "market-command-1", "STONE", 40, 3);
        ledger.add(escrow);
        long firstRevision = ledger.revision();
        ledger.add(offerEscrow);
        ledger.markDeliveryApplied("delivery-command");
        ledger.accept("resource-command");

        TradeLedger reloaded = TradeLedger.load(identity);
        check(reloaded.revision() == 2, "ledger revision persisted");
        check(reloaded.outbound().size() == 1, "pending outbound escrow persisted");
        check(reloaded.escrow("resource-command") == null, "accepted escrow leaves pending queue");
        check(reloaded.escrow("market-command-1").equals(offerEscrow), "market escrow contents persisted");
        check(reloaded.deliveryApplied("delivery-command"), "delivery application persisted");
        check(reloaded.debitsAfter(0).size() == 2, "pending and accepted rollback debits retained");
        check(reloaded.debitsAfter(firstRevision).size() == 1
                && reloaded.debitsAfter(firstRevision).getFirst().escrow().equals(offerEscrow),
                "rollback journal filters by save revision");
        reloaded.reject("market-command-1");
        check(TradeLedger.load(identity).outbound().isEmpty(), "completed escrows removed");
        check(TradeLedger.load(identity).debitsAfter(0).size() == 1,
                "accepted debit remains after pending command is rejected");
        try {
            reloaded.debitsAfter(3);
            throw new AssertionError("future save revision should be rejected");
        } catch (java.io.IOException expected) {
        }
        Path legacy = temp.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("trade-ledger.properties"), """
                outbound.count=1
                outbound.0.commandId=legacy-command-1
                outbound.0.commandType=SEND_RESOURCE
                outbound.0.targetId=target-player
                outbound.0.resource=WOOD
                outbound.0.amount=12
                outbound.0.unitPrice=0
                applied.count=0
                """);
        TradeLedger migrated = TradeLedger.load(legacy.resolve("identity.properties"));
        check(migrated.revision() == 1 && migrated.outbound().size() == 1,
                "legacy escrow ledger receives a rollback revision");
        System.out.println("TradeLedgerTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
