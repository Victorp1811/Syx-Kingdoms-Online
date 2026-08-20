package syxkingdoms.server;

import syxkingdoms.protocol.Messages;

final class DeliveryRecord {
    final String deliveryId;
    final String senderPlayerId;
    final String targetPlayerId;
    final String resourceKey;
    final int amount;
    final long arrivedEpochMillis;

    DeliveryRecord(String deliveryId, String senderPlayerId, String targetPlayerId,
            String resourceKey, int amount, long arrivedEpochMillis) {
        this.deliveryId = deliveryId;
        this.senderPlayerId = senderPlayerId;
        this.targetPlayerId = targetPlayerId;
        this.resourceKey = resourceKey;
        this.amount = amount;
        this.arrivedEpochMillis = arrivedEpochMillis;
    }

    Messages.Delivery message() {
        return new Messages.Delivery(deliveryId, senderPlayerId, resourceKey, amount, arrivedEpochMillis);
    }
}
