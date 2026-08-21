package syxkingdoms.protocol;

public final class SharedWorld {
    public static final String WORLD_ID = "syx-kingdoms-one-world";
    public static final String WORLD_NAME = "Syx Kingdoms Persistent World";
    public static final String WORLD_SEED = "syx-kingdoms-canonical-world-v1";
    public static final int TERRAIN_GENERATION = 1;
    public static final int BASE_GAME_TILE_DIMENSION = 256;
    public static final int LINEAR_SCALE = 10;
    public static final int WIDTH = BASE_GAME_TILE_DIMENSION * LINEAR_SCALE;
    public static final int HEIGHT = BASE_GAME_TILE_DIMENSION * LINEAR_SCALE;
    public static final int SECTOR_COUNT = LINEAR_SCALE * LINEAR_SCALE;
    public static final double FIXED_GAME_SPEED = 1.0;

    private SharedWorld() {
    }

    public static int sectorX(int worldX) {
        return worldX / BASE_GAME_TILE_DIMENSION;
    }

    public static int sectorY(int worldY) {
        return worldY / BASE_GAME_TILE_DIMENSION;
    }
}
