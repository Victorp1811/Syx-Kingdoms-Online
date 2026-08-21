package syxkingdoms.server;

final class ProvinceRecord {
    final int x;
    final int y;
    String ownerHouseId = "";
    String occupierHouseId = "";
    int occupationInfluence;
    long blockadeEndsEpochMillis;
    long tributeCrowns;
    long lastTributeEpochMillis;

    ProvinceRecord(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
