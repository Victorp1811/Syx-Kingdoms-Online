package syxkingdoms.mod;

import script.SCRIPT;

public final class ModLoadSmokeTest {
    public static void main(String[] args) {
        KingdomsMod mod = new KingdomsMod();
        if (!(mod instanceof SCRIPT)) {
            throw new AssertionError("Mod does not implement SCRIPT");
        }
        if (!"Syx Kingdoms Online".contentEquals(mod.name())) {
            throw new AssertionError("Unexpected mod name");
        }
        if (!mod.forceInit() || mod.isSelectable()) {
            throw new AssertionError("Mod must be forced and non-selectable");
        }
        System.out.println("ModLoadSmokeTest: PASS");
    }
}
