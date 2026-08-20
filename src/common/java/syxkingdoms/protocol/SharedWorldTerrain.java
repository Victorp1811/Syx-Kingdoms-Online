package syxkingdoms.protocol;

public final class SharedWorldTerrain {
    public enum Terrain {
        WATER,
        PLAINS,
        FOREST,
        MOUNTAIN
    }

    public static final int CELL_SIZE = 64;
    public static final int GRID_WIDTH = SharedWorld.WIDTH / CELL_SIZE;
    public static final int GRID_HEIGHT = SharedWorld.HEIGHT / CELL_SIZE;

    private final long seedHash;
    private final Terrain[] cells = new Terrain[GRID_WIDTH * GRID_HEIGHT];

    public SharedWorldTerrain(String seed) {
        if (seed == null || seed.isBlank()) {
            throw new IllegalArgumentException("Shared-world terrain seed cannot be blank");
        }
        seedHash = hashSeed(seed);
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                cells[x + y * GRID_WIDTH] = generate(x, y);
            }
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

    private Terrain generate(int cellX, int cellY) {
        int total = 0;
        int weights = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int weight = 3 - Math.max(Math.abs(dx), Math.abs(dy));
                total += sample(seedHash, cellX + dx, cellY + dy) * weight;
                weights += weight;
            }
        }
        int broadElevation = total / weights;
        int detail = sample(seedHash ^ 0x6A09E667F3BCC909L, cellX, cellY);
        int elevation = (broadElevation * 3 + detail) / 4;
        int moisture = sample(seedHash ^ 0xBB67AE8584CAA73BL, cellX, cellY);

        if (elevation < 110) {
            return Terrain.WATER;
        }
        if (elevation > 160) {
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
