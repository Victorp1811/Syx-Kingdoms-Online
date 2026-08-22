package syxkingdoms.protocol;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

public final class SharedWorldTerrain {
    public enum Terrain {
        WATER,
        PLAINS,
        FOREST,
        MOUNTAIN
    }

    /** Sixteen shared tiles per rendered cell keeps geography readable on the wire. */
    public static final int CELL_SIZE = 16;
    public static final int GRID_WIDTH = (SharedWorld.WIDTH + CELL_SIZE - 1) / CELL_SIZE;
    public static final int GRID_HEIGHT = (SharedWorld.HEIGHT + CELL_SIZE - 1) / CELL_SIZE;

    private final long seedHash;
    private final Terrain[] cells = new Terrain[GRID_WIDTH * GRID_HEIGHT];

    public SharedWorldTerrain(String seed) {
        if (seed == null || seed.isBlank()) {
            throw new IllegalArgumentException("Shared-world terrain seed cannot be blank");
        }
        seedHash = hashSeed(seed);
        try (InputStream input = SharedWorldTerrain.class.getResourceAsStream(SharedWorld.HEIGHTMAP_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Canonical online heightmap is missing: "
                        + SharedWorld.HEIGHTMAP_RESOURCE);
            }
            BufferedImage heightmap = ImageIO.read(input);
            if (heightmap == null || heightmap.getWidth() != heightmap.getHeight()) {
                throw new IllegalStateException("Canonical online heightmap must be a square PNG");
            }
            for (int y = 0; y < GRID_HEIGHT; y++) {
                for (int x = 0; x < GRID_WIDTH; x++) {
                    cells[x + y * GRID_WIDTH] = generate(x, y, heightmap);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the canonical online heightmap", e);
        }
    }

    public Terrain cell(int x, int y) {
        if (x < 0 || x >= GRID_WIDTH || y < 0 || y >= GRID_HEIGHT) {
            throw new IndexOutOfBoundsException("Terrain cell is outside the shared world");
        }
        return cells[x + y * GRID_WIDTH];
    }

    public Terrain at(int worldX, int worldY) {
        if (worldX < 0 || worldX >= SharedWorld.WIDTH || worldY < 0 || worldY >= SharedWorld.HEIGHT) {
            throw new IndexOutOfBoundsException("Coordinate is outside the shared world");
        }
        return cell(worldX / CELL_SIZE, worldY / CELL_SIZE);
    }

    public boolean canFoundCity(int worldX, int worldY) {
        Terrain terrain = at(worldX, worldY);
        return terrain == Terrain.PLAINS || terrain == Terrain.FOREST;
    }

    public long cityCoordinateCapacity(int spacing) {
        long count = 0;
        for (int y = 0; y < SharedWorld.HEIGHT; y += spacing) {
            for (int x = 0; x < SharedWorld.WIDTH; x += spacing) {
                if (canFoundCity(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }

    public String fingerprint() {
        long hash = 0xCBF29CE484222325L;
        hash = fingerprintValue(hash, SharedWorld.TERRAIN_GENERATION);
        hash = fingerprintValue(hash, GRID_WIDTH);
        hash = fingerprintValue(hash, GRID_HEIGHT);
        for (Terrain terrain : cells) {
            hash = fingerprintValue(hash, terrain.ordinal());
        }
        return Long.toUnsignedString(hash, 16);
    }

    private Terrain generate(int cellX, int cellY, BufferedImage heightmap) {
        int imageX = Math.min(heightmap.getWidth() - 1,
                (int) (((long) cellX * 2 + 1) * heightmap.getWidth() / (GRID_WIDTH * 2L)));
        int imageY = Math.min(heightmap.getHeight() - 1,
                (int) (((long) cellY * 2 + 1) * heightmap.getHeight() / (GRID_HEIGHT * 2L)));
        int elevation = (heightmap.getRGB(imageX, imageY) >>> 8) & 0xFF;
        int moisture = sample(seedHash ^ 0xBB67AE8584CAA73BL, cellX, cellY);

        if (elevation < 72) {
            return Terrain.WATER;
        }
        if (elevation > 205) {
            return Terrain.MOUNTAIN;
        }
        if (moisture > 138) {
            return Terrain.FOREST;
        }
        return Terrain.PLAINS;
    }

    private static int sample(long seed, int x, int y) {
        long value = seed;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        return (int) (mix64(value) >>> 56);
    }

    private static long hashSeed(String seed) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < seed.length(); i++) {
            hash ^= seed.charAt(i);
            hash *= 0x100000001B3L;
        }
        return mix64(hash);
    }

    private static long fingerprintValue(long hash, int value) {
        hash ^= value;
        return hash * 0x100000001B3L;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
