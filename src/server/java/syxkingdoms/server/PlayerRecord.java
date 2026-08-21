package syxkingdoms.server;

import syxkingdoms.protocol.CrestDesign;

final class PlayerRecord {
    final String playerId;
    final String authHash;
    final int slot;
    final int x;
    final int y;
    String playerName;
    String kingdomName;
    String cityName;
    long foundedEpochMillis;
    long protectionEndsEpochMillis;
    String gameVersion;
    String modVersion;
    int population;
    long credits;
    long lastSeenEpochMillis;
    long crowns = 1_000;
    int levies = 100;
    long economyUpdatedEpochMillis = System.currentTimeMillis();
    int levyProgressMinutes;
    CrestDesign crest;
    long crestRevision;

    PlayerRecord(String playerId, String authHash, int slot, int x, int y) {
        this.playerId = playerId;
        this.authHash = authHash;
        this.slot = slot;
        this.x = x;
        this.y = y;
    }

    boolean foundingComplete() {
        return kingdomName != null && !kingdomName.isBlank() && foundedEpochMillis > 0;
    }
}
