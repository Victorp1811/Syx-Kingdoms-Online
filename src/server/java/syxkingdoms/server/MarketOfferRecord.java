package syxkingdoms.server;

import syxkingdoms.protocol.Messages;

final class MarketOfferRecord {
    final String offerId;
    final String sellerPlayerId;
    final String resourceKey;
    int quantity;
    final long unitPrice;
    final long createdEpochMillis;

    MarketOfferRecord(String offerId, String sellerPlayerId, String resourceKey,
            int quantity, long unitPrice, long createdEpochMillis) {
        this.offerId = offerId;
        this.sellerPlayerId = sellerPlayerId;
        this.resourceKey = resourceKey;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.createdEpochMillis = createdEpochMillis;
    }

    Messages.MarketOffer message(PlayerRecord seller) {
        return new Messages.MarketOffer(offerId, sellerPlayerId, seller.playerName,
                resourceKey, quantity, unitPrice, createdEpochMillis);
    }
}
