package syxkingdoms.server;

final class ShipmentRecord {
    final String shipmentId;
    final String senderPlayerId;
    final String targetPlayerId;
    final String resourceKey;
    final int amount;
    final long departedEpochMillis;
    final long arrivesEpochMillis;

    ShipmentRecord(String shipmentId, String senderPlayerId, String targetPlayerId,
            String resourceKey, int amount, long departedEpochMillis, long arrivesEpochMillis) {
        this.shipmentId = shipmentId;
        this.senderPlayerId = senderPlayerId;
        this.targetPlayerId = targetPlayerId;
        this.resourceKey = resourceKey;
        this.amount = amount;
        this.departedEpochMillis = departedEpochMillis;
        this.arrivesEpochMillis = arrivesEpochMillis;
    }
}
