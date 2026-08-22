package syxkingdoms.protocol;

public final class SharedWorld {
    public static final String WORLD_ID = "syx-kingdoms-one-world";
    public static final String WORLD_NAME = "Syx Kingdoms Persistent World";
    public static final String WORLD_SEED = "syx-kingdoms-canonical-world-v1";
    public static final String HEIGHTMAP_RESOURCE = "/syxkingdoms/world/Syx_Kingdoms_Online.png";
    public static final int TERRAIN_GENERATION = 4;
    /** Fixed V71 seed for the private physical city shard generated beneath the shared strategy map. */
    public static final int LOCAL_SHARD_SEED = 0x53594B4F;
    public static final int BASE_GAME_TILE_DIMENSION = 256;
    /** Canonical native world dimension selected for stable V71 generation and rendering. */
    public static final int WIDTH = 1000;
    public static final int HEIGHT = 1000;
    public static final int PROVINCE_GRID = 10;
    public static final int PROVINCE_TILE_DIMENSION = WIDTH / PROVINCE_GRID;
    public static final int SECTOR_COUNT = PROVINCE_GRID * PROVINCE_GRID;
    public static final double FIXED_GAME_SPEED = 1.0;
    public static final int NPC_CITIES_PER_PLAYER = 3;
    public static final int PLAYER_CITY_MIN_DISTANCE = 64;
    public static final int NPC_CITY_MIN_DISTANCE = 8;
    public static final int NPC_CITY_MAX_DISTANCE = 24;
    /** Local chat reaches nearby kingdoms without being coupled to House provinces. */
    public static final int LOCAL_CHAT_RADIUS = 256;
    /** Verified capacity with 64-tile separation: 90 kingdoms and 270 nearby NPC cities. */
    public static final int MAX_PLAYER_CITIES = 90;

    private SharedWorld() {
    }

    public static int sectorX(int worldX) {
        return Math.min(PROVINCE_GRID - 1, Math.max(0, worldX / PROVINCE_TILE_DIMENSION));
    }

    public static int sectorY(int worldY) {
        return Math.min(PROVINCE_GRID - 1, Math.max(0, worldY / PROVINCE_TILE_DIMENSION));
    }
}
