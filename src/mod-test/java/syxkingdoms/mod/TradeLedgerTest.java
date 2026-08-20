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
        ledger.add(offerEscrow);
        ledger.markDeliveryApplied("delivery-command");

        TradeLedger reloaded = TradeLedger.load(identity);
        check(reloaded.outbound().size() == 2, "outbound escrows persisted");
        check(reloaded.escrow("resource-command").equals(escrow), "escrow contents persisted");
        check(reloaded.escrow("market-command-1").equals(offerEscrow), "market escrow contents persisted");
        check(reloaded.deliveryApplied("delivery-command"), "delivery application persisted");
        reloaded.remove("resource-command");
        reloaded.remove("market-command-1");
        check(TradeLedger.load(identity).outbound().isEmpty(), "completed escrows removed");
        System.out.println("TradeLedgerTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
