package syxkingdoms.launcher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Opt-in V71 menu hook used only by the dedicated online launcher. */
public final class MenuAgent {
    private static final String MAIN_MENU = "menu/ScMain";
    private static final String PLAY_ACTION = "menu/ScMain$1";
    private static final String WORLD_DATA = "world/WORLD$Data";
    private static final String HEIGHTMAP_TYPE = "world/WorldGen$WorldGenMapType";
    private static final String GLOBAL_PATH_DATA = "util/GUTIL$Data";
    private static final String GAME_WINDOW = "view/subview/GameWindow";
    private static final String REGION_CENTRE_SPRITE = "world/map/regions/centre/Sprite";
    private static final String GLOBAL_REGION_ROAD_CONNECTOR = "world/map/road/GenPlayer";
    private static final int STOCK_WORLD_LIMIT = 512;
    private static final int ONLINE_WORLD_LIMIT = 1000;
    private static final int ONLINE_WORLD_ZOOM_LIMIT = 4;

    private MenuAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("syxkingdoms.online")) {
            return;
        }
        instrumentation.addTransformer(new OnlineMenuTransformer());
        System.out.println("[Syx Kingdoms] Online main-menu entry enabled");
    }

    private static final class OnlineMenuTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                if (MAIN_MENU.equals(className)) {
                    return replacePlayLabel(classfileBuffer);
                }
                if (PLAY_ACTION.equals(className)) {
                    return replacementClass("menu/ScMain$1.class");
                }
                if (WORLD_DATA.equals(className)) {
                    return patchWorldLimit(classfileBuffer);
                }
                if (HEIGHTMAP_TYPE.equals(className)) {
                    return patchHeightmapScale(classfileBuffer);
                }
                if (GLOBAL_PATH_DATA.equals(className)) {
                    return patchPathUtilitySize(classfileBuffer);
                }
                if (GAME_WINDOW.equals(className)) {
                    return patchWorldZoomLimit(classfileBuffer);
                }
                if (REGION_CENTRE_SPRITE.equals(className)) {
                    return replacementClass("world/map/regions/centre/Sprite.class");
                }
                if (GLOBAL_REGION_ROAD_CONNECTOR.equals(className)) {
                    return replacementClass("world/map/road/GenPlayer.class");
                }
            } catch (Exception e) {
                throw new IllegalStateException("Could not install the Syx Kingdoms V71 menu entry", e);
            }
            return null;
        }
    }

    /** Rewrites the two native WORLD constructor comparisons from 512 to 1000. */
    static byte[] patchWorldLimit(byte[] original) throws IOException {
        byte[] patched = original.clone();
        int replacements = 0;
        for (int offset = 0; offset + 2 < patched.length; offset++) {
            if ((patched[offset] & 0xFF) == 0x11
                    && unsignedShort(patched, offset + 1) == STOCK_WORLD_LIMIT) {
                patched[offset + 1] = (byte) (ONLINE_WORLD_LIMIT >>> 8);
                patched[offset + 2] = (byte) ONLINE_WORLD_LIMIT;
                replacements++;
            }
        }
        if (replacements != 2) {
            throw new IOException("Expected two V71 native world-limit checks, found " + replacements);
        }
        return patched;
    }

    /**
     * V71 divides grayscale samples by the world width, which only works for its
     * original 256-wide generator. Online normalizes samples by 256 so the same
     * heightmap works at 1000x1000.
     */
    static byte[] patchHeightmapScale(byte[] original) throws IOException {
        int constantPoolCount = unsignedShort(original, 8);
        int constantPoolEnd = constantPoolEnd(original, constantPoolCount);
        int scaleConstantIndex = constantPoolCount;
        byte[] patched = new byte[original.length + 9];
        System.arraycopy(original, 0, patched, 0, constantPoolEnd);
        patched[8] = (byte) ((constantPoolCount + 2) >>> 8);
        patched[9] = (byte) (constantPoolCount + 2);
        int cursor = constantPoolEnd;
        patched[cursor++] = 6; // CONSTANT_Double
        long bits = Double.doubleToRawLongBits(1.0 / 256.0);
        for (int shift = 56; shift >= 0; shift -= 8) {
            patched[cursor++] = (byte) (bits >>> shift);
        }
        System.arraycopy(original, constantPoolEnd, patched, cursor, original.length - constantPoolEnd);

        byte[] pattern = { 0x2A, 0x0F, 0x1C, (byte) 0x87, 0x6F, (byte) 0xB5 };
        int match = findUnique(patched, pattern, "V71 heightmap scale");
        patched[match + 1] = 0x14; // ldc2_w
        patched[match + 2] = (byte) (scaleConstantIndex >>> 8);
        patched[match + 3] = (byte) scaleConstantIndex;
        patched[match + 4] = 0x00; // nop; preserves code and stack-map offsets
        return patched;
    }

    /** Expands the shared flood-fill grid from the 768-wide settlement to the online world. */
    static byte[] patchPathUtilitySize(byte[] original) throws IOException {
        byte[] patched = original.clone();
        // new PathUtilOnline; dup; getstatic SETT.TWIDTH; invokespecial <init>(I)
        byte[] pattern = { (byte) 0xBB, 0x00, 0x59, 0x59, (byte) 0xB2,
                0x00, 0x5B, (byte) 0xB7, 0x00, 0x61 };
        int match = findUnique(patched, pattern, "V71 global path utility");
        patched[match + 4] = 0x11; // sipush
        patched[match + 5] = (byte) (ONLINE_WORLD_LIMIT >>> 8);
        patched[match + 6] = (byte) ONLINE_WORLD_LIMIT;
        return patched;
    }

    /**
     * The world view is the only main view that keeps GameWindow's stock default
     * zoom cap of 2. Settlement and battle views explicitly install their own cap,
     * so raising the default to 4 adds two useful world-map zoom levels without
     * changing the city or battle cameras.
     */
    static byte[] patchWorldZoomLimit(byte[] original) throws IOException {
        byte[] patched = original.clone();
        // aload_0; iconst_2; putfield maxZoomOut
        byte[] pattern = { 0x2A, 0x05, (byte) 0xB5, 0x00, 0x42 };
        int match = findUnique(patched, pattern, "V71 GameWindow default zoom cap");
        patched[match + 1] = (byte) (0x03 + ONLINE_WORLD_ZOOM_LIMIT); // iconst_4
        return patched;
    }

    private static int constantPoolEnd(byte[] classFile, int count) throws IOException {
        int offset = 10;
        for (int index = 1; index < count; index++) {
            if (offset >= classFile.length) throw new IOException("Truncated class constant pool");
            int tag = classFile[offset++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    int length = unsignedShort(classFile, offset);
                    offset += 2 + length;
                }
                case 3, 4 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 15 -> offset += 3;
                default -> throw new IOException("Unsupported class constant-pool tag " + tag);
            }
        }
        if (offset > classFile.length) throw new IOException("Truncated class constant pool");
        return offset;
    }

    private static int findUnique(byte[] bytes, byte[] pattern, String description) throws IOException {
        int match = -1;
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            boolean equal = true;
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                if (match >= 0) throw new IOException(description + " pattern was not unique");
                match = offset;
            }
        }
        if (match < 0) throw new IOException(description + " pattern was not found");
        return match;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF);
    }

    private static byte[] replacementClass(String entryName) throws IOException {
        CodeSource source = MenuAgent.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IOException("Online launcher agent has no code source");
        }
        final java.nio.file.Path agentPath;
        try {
            agentPath = java.nio.file.Paths.get(source.getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Online launcher path is invalid", e);
        }
        try (JarFile jar = new JarFile(agentPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new IOException("Online launcher is missing " + entryName);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    /** Rewrites only the constant-pool text used by ScMain's root Play button. */
    private static byte[] replacePlayLabel(byte[] original) throws IOException {
        DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(original));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(original.length + 32);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(input.readInt());
        output.writeShort(input.readUnsignedShort());
        output.writeShort(input.readUnsignedShort());
        int constantPoolCount = input.readUnsignedShort();
        output.writeShort(constantPoolCount);
        boolean replaced = false;
        for (int index = 1; index < constantPoolCount; index++) {
            int tag = input.readUnsignedByte();
            output.writeByte(tag);
            switch (tag) {
                case 1 -> {
                    int length = input.readUnsignedShort();
                    byte[] value = input.readNBytes(length);
                    String text = new String(value, StandardCharsets.UTF_8);
                    if (text.endsWith("play") && text.length() <= 8) {
                        int prefixLength = value.length - "play".length();
                        byte[] replacement = new byte[prefixLength + "multiplayer".length()];
                        System.arraycopy(value, 0, replacement, 0, prefixLength);
                        System.arraycopy("multiplayer".getBytes(StandardCharsets.US_ASCII), 0,
                                replacement, prefixLength, "multiplayer".length());
                        value = replacement;
                        replaced = true;
                    }
                    output.writeShort(value.length);
                    output.write(value);
                }
                case 3, 4 -> output.write(input.readNBytes(4));
                case 5, 6 -> {
                    output.write(input.readNBytes(8));
                    index++;
                }
                case 7, 8, 16, 19, 20 -> output.write(input.readNBytes(2));
                case 9, 10, 11, 12, 17, 18 -> output.write(input.readNBytes(4));
                case 15 -> output.write(input.readNBytes(3));
                default -> throw new IOException("Unsupported V71 constant-pool tag " + tag);
            }
        }
        output.write(input.readAllBytes());
        if (!replaced) {
            throw new IOException("V71 Play label was not found");
        }
        return bytes.toByteArray();
    }
}
