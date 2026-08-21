package syxkingdoms.mod;

import init.paths.ModInfo;
import init.paths.PATHS;
import snake2d.util.sets.LIST;

final class ModCompatibility {
    static final String REQUIRED_MANIFEST = "Syx Kingdoms Online@0.12.0";

    private ModCompatibility() {
    }

    static String requireExclusiveSet() {
        if (PATHS.isDevelop()) {
            throw new IllegalStateException(
                    "DEVELOPMENT_MODE_BLOCKED: Online play cannot run with Songs of Syx development/debug mode enabled");
        }
        LIST<ModInfo> activeMods = PATHS.currentMods();
        String manifest = manifest(activeMods);
        if (activeMods.size() != 1 || !REQUIRED_MANIFEST.equals(manifest)) {
            String reported = manifest.isBlank() ? "(none)" : manifest;
            throw new IllegalStateException("MOD_SET_MISMATCH: Online play requires only "
                    + REQUIRED_MANIFEST + ". Active mods: " + reported);
        }
        return manifest;
    }

    private static String manifest(LIST<ModInfo> activeMods) {
        StringBuilder result = new StringBuilder();
        for (ModInfo mod : activeMods) {
            if (!result.isEmpty()) {
                result.append('|');
            }
            result.append(mod.name).append('@').append(mod.version);
        }
        return result.toString();
    }
}
