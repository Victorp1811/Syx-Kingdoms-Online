package syxkingdoms.server;

final class PlayerRecord {
    final String playerId;
    final String authHash;
    final int slot;
    final int x;
    final int y;
    String playerName;
    String cityName;
    String gameVersion;
    String modVersion;
    int population;
    long credits;
    long lastSeenEpochMillis;
    long crowns = 1_000;
    int levies = 100;
    long economyUpdatedEpochMillis = System.currentTimeMillis();
    int levyProgressMinutes;

    PlayerRecord(String playerId, String authHash, int slot, int x, int y) {
        this.playerId = playerId;
        this.authHash = authHash;
        this.slot = slot;
        this.x = x;
        this.y = y;
    }
}
