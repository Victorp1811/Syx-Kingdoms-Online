package syxkingdoms.server;

import syxkingdoms.protocol.CrestDesign;

final class HouseRecord {
    static final int MAX_MEMBERS = 20;

    final String houseId;
    final String name;
    final String tag;
    final long createdEpochMillis;
    String sovereignPlayerId;
    String announcement = "";
    CrestDesign crest;
    long crestRevision;
    int capitalProvinceX = -1;
    int capitalProvinceY = -1;
    int seasonPoints;

    HouseRecord(String houseId, String name, String tag, String sovereignPlayerId,
            long createdEpochMillis) {
        this.houseId = houseId;
        this.name = name;
        this.tag = tag;
        this.sovereignPlayerId = sovereignPlayerId;
        this.createdEpochMillis = createdEpochMillis;
    }
}
