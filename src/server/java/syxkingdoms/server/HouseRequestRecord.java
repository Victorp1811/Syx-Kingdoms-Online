package syxkingdoms.server;

final class HouseRequestRecord {
    final String requestId;
    final String houseId;
    final String playerId;
    final String type;
    final long createdEpochMillis;
    final long expiresEpochMillis;

    HouseRequestRecord(String requestId, String houseId, String playerId, String type,
            long createdEpochMillis, long expiresEpochMillis) {
        this.requestId = requestId;
        this.houseId = houseId;
        this.playerId = playerId;
        this.type = type;
        this.createdEpochMillis = createdEpochMillis;
        this.expiresEpochMillis = expiresEpochMillis;
    }
}
