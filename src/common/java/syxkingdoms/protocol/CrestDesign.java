package syxkingdoms.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public final class CrestDesign {
    public static final int WIDTH = 32;
    public static final int HEIGHT = 32;
    public static final int PIXEL_COUNT = WIDTH * HEIGHT;
    public static final int PALETTE_SIZE = 16;
    public static final int PIXELS_ENCODED_LENGTH = 683;
    public static final int PALETTE_HEX_LENGTH = PALETTE_SIZE * 6;

    private static final int[] DEFAULT_PALETTE = {
            0x000000, 0x263A63, 0xD5A62E, 0xEFE4C4,
            0x8E2637, 0x386B3C, 0x704B8E, 0xB8612C,
            0x2C778E, 0xBD4E75, 0x6E7782, 0xA58B6F,
            0x181818, 0x5A321F, 0xB7B7B7, 0xFFFFFF
    };

    private final byte[] pixels;
    private final int[] palette;

    private CrestDesign(byte[] pixels, int[] palette) {
        this.pixels = pixels;
        this.palette = palette;
    }

    public static CrestDesign blank() {
        return new CrestDesign(new byte[PIXEL_COUNT], DEFAULT_PALETTE.clone());
    }

    public static CrestDesign of(byte[] pixels, int[] palette) {
        if (pixels == null || pixels.length != PIXEL_COUNT) {
            throw new IllegalArgumentException("Crest must contain exactly " + PIXEL_COUNT + " pixels");
        }
        if (palette == null || palette.length != PALETTE_SIZE) {
            throw new IllegalArgumentException("Crest must contain exactly " + PALETTE_SIZE + " palette entries");
        }
        byte[] pixelCopy = pixels.clone();
        for (byte pixel : pixelCopy) {
            if (Byte.toUnsignedInt(pixel) >= PALETTE_SIZE) {
                throw new IllegalArgumentException("Crest pixel has an invalid palette index");
            }
        }
        int[] paletteCopy = palette.clone();
        for (int color : paletteCopy) {
            if (color < 0 || color > 0xFFFFFF) {
                throw new IllegalArgumentException("Crest color is outside 24-bit RGB");
            }
        }
        paletteCopy[0] = 0;
        return new CrestDesign(pixelCopy, paletteCopy);
    }

    public static CrestDesign decode(String pixelsEncoded, String paletteHex) throws ProtocolException {
        if (pixelsEncoded == null || pixelsEncoded.length() != PIXELS_ENCODED_LENGTH
                || !pixelsEncoded.matches("[A-Za-z0-9_-]{" + PIXELS_ENCODED_LENGTH + "}")) {
            throw new ProtocolException("BAD_CREST", "Crest pixels must be exactly "
                    + PIXELS_ENCODED_LENGTH + " base64url characters");
        }
        if (paletteHex == null || paletteHex.length() != PALETTE_HEX_LENGTH
                || !paletteHex.matches("[0-9A-Fa-f]{" + PALETTE_HEX_LENGTH + "}")) {
            throw new ProtocolException("BAD_CREST", "Crest palette must contain exactly 16 RGB colors");
        }
        byte[] pixels = new byte[PIXEL_COUNT];
        try {
            byte[] packed = Base64.getUrlDecoder().decode(pixelsEncoded);
            if (packed.length != PIXEL_COUNT / 2) {
                throw new IllegalArgumentException("Wrong packed crest size");
            }
            for (int i = 0; i < packed.length; i++) {
                int value = Byte.toUnsignedInt(packed[i]);
                pixels[i * 2] = (byte) (value >>> 4);
                pixels[i * 2 + 1] = (byte) (value & 0x0F);
            }
            int[] palette = new int[PALETTE_SIZE];
            for (int i = 0; i < PALETTE_SIZE; i++) {
                palette[i] = Integer.parseInt(paletteHex.substring(i * 6, i * 6 + 6), 16);
            }
            return of(pixels, palette);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("BAD_CREST", "Crest data is malformed", e);
        }
    }

    public int pixel(int x, int y) {
        requireCoordinate(x, y);
        return Byte.toUnsignedInt(pixels[x + y * WIDTH]);
    }

    public int color(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Palette index is outside 0-15");
        }
        return palette[paletteIndex];
    }

    public boolean hasVisiblePixels() {
        for (byte pixel : pixels) {
            if (pixel != 0) {
                return true;
            }
        }
        return false;
    }

    public CrestDesign withPixel(int x, int y, int paletteIndex) {
        requireCoordinate(x, y);
        if (paletteIndex < 0 || paletteIndex >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Palette index is outside 0-15");
        }
        int offset = x + y * WIDTH;
        if (Byte.toUnsignedInt(pixels[offset]) == paletteIndex) {
            return this;
        }
        byte[] copy = pixels.clone();
        copy[offset] = (byte) paletteIndex;
        return new CrestDesign(copy, palette.clone());
    }

    public CrestDesign withColor(int paletteIndex, int rgb) {
        if (paletteIndex <= 0 || paletteIndex >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Only visible palette slots 1-15 are editable");
        }
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("Color is outside 24-bit RGB");
        }
        if (palette[paletteIndex] == rgb) {
            return this;
        }
        int[] copy = palette.clone();
        copy[paletteIndex] = rgb;
        return new CrestDesign(pixels.clone(), copy);
    }

    public CrestDesign clear() {
        return new CrestDesign(new byte[PIXEL_COUNT], palette.clone());
    }

    public CrestDesign mirrorHorizontal() {
        byte[] copy = new byte[PIXEL_COUNT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                copy[(WIDTH - 1 - x) + y * WIDTH] = pixels[x + y * WIDTH];
            }
        }
        return new CrestDesign(copy, palette.clone());
    }

    public CrestDesign mirrorVertical() {
        byte[] copy = new byte[PIXEL_COUNT];
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(pixels, y * WIDTH, copy, (HEIGHT - 1 - y) * WIDTH, WIDTH);
        }
        return new CrestDesign(copy, palette.clone());
    }

    public String pixelsEncoded() {
        byte[] packed = new byte[PIXEL_COUNT / 2];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = (byte) ((Byte.toUnsignedInt(pixels[i * 2]) << 4)
                    | Byte.toUnsignedInt(pixels[i * 2 + 1]));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
    }

    public String paletteHex() {
        StringBuilder result = new StringBuilder(PALETTE_HEX_LENGTH);
        for (int color : palette) {
            result.append(String.format(java.util.Locale.ROOT, "%06x", color));
        }
        return result.toString();
    }

    public String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pixelsEncoded().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(paletteHex().getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireCoordinate(int x, int y) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
            throw new IllegalArgumentException("Crest coordinate is outside 32x32");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CrestDesign design
                && Arrays.equals(pixels, design.pixels)
                && Arrays.equals(palette, design.palette);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(pixels) + Arrays.hashCode(palette);
    }
}
