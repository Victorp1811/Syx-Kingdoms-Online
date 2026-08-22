package syxkingdoms.launcher;

import java.nio.file.Path;
import java.util.jar.JarFile;

/** Loads the two V71 targets without initializing the game, verifying agent transformations. */
public final class AgentSmoke {
    private AgentSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Pass the V71 SongsOfSyx.jar path");
        }
        ClassLoader loader = AgentSmoke.class.getClassLoader();
        Class.forName("menu.ScMain", false, loader);
        Class.forName("menu.ScMain$1", false, loader);
        try (JarFile game = new JarFile(Path.of(arguments[0]).toFile())) {
            byte[] world = game.getInputStream(game.getJarEntry("world/WORLD$Data.class")).readAllBytes();
            byte[] patchedWorld = MenuAgent.patchWorldLimit(world);
            if (countSipush(patchedWorld, 512) != 0 || countSipush(patchedWorld, 1000) != 2) {
                throw new AssertionError("Native world limit was not patched to 1000");
            }
            byte[] heightmap = game.getInputStream(
                    game.getJarEntry("world/WorldGen$WorldGenMapType.class")).readAllBytes();
            byte[] patchedHeightmap = MenuAgent.patchHeightmapScale(heightmap);
            if (unsignedShort(patchedHeightmap, 8) != unsignedShort(heightmap, 8) + 2) {
                throw new AssertionError("Heightmap normalization constant was not appended");
            }
            byte[] pathData = game.getInputStream(game.getJarEntry("util/GUTIL$Data.class")).readAllBytes();
            byte[] patchedPathData = MenuAgent.patchPathUtilitySize(pathData);
            if (countSipush(patchedPathData, 1000) != 1) {
                throw new AssertionError("Global flood-fill grid was not patched to 1000");
            }
            byte[] gameWindow = game.getInputStream(
                    game.getJarEntry("view/subview/GameWindow.class")).readAllBytes();
            byte[] patchedGameWindow = MenuAgent.patchWorldZoomLimit(gameWindow);
            if (!contains(patchedGameWindow,
                    new byte[] { 0x2A, 0x07, (byte) 0xB5, 0x00, 0x42 })) {
                throw new AssertionError("World-map zoom cap was not patched from 2 to 4");
            }
        }
        System.out.println("OnlineEngineAgentSmokeTest: PASS "
                + "(menu, 1000 world/pathing, fixed heightmap scale, zoom level 4)");
    }

    private static int countSipush(byte[] bytes, int value) {
        int count = 0;
        for (int offset = 0; offset + 2 < bytes.length; offset++) {
            if ((bytes[offset] & 0xFF) == 0x11 && unsignedShort(bytes, offset + 1) == value) count++;
        }
        return count;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF);
    }

    private static boolean contains(byte[] bytes, byte[] pattern) {
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            boolean equal = true;
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    equal = false;
                    break;
                }
            }
            if (equal) return true;
        }
        return false;
    }
}
