package syxkingdoms.server;

final class NpcCityRecord {
    final String npcId;
    final String patronPlayerId;
    final String cityName;
    final int x;
    final int y;
    final int level;
    int levies;
    long crowns;
    long economyUpdatedEpochMillis;
    int levyProgressMinutes;

    NpcCityRecord(String npcId, String patronPlayerId, String cityName,
            int x, int y, int level, int levies, long crowns, long economyUpdatedEpochMillis) {
        this.npcId = npcId;
        this.patronPlayerId = patronPlayerId;
        this.cityName = cityName;
        this.x = x;
        this.y = y;
        this.level = level;
        this.levies = levies;
        this.crowns = crowns;
        this.economyUpdatedEpochMillis = economyUpdatedEpochMillis;
    }
}
