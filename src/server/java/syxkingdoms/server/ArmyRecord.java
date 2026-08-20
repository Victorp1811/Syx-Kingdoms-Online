package syxkingdoms.server;

import syxkingdoms.protocol.Messages;

final class ArmyRecord {
    final String armyId;
    final String ownerPlayerId;
    final String targetPlayerId;
    final int troops;
    final long departedEpochMillis;
    final long arrivesEpochMillis;

    ArmyRecord(String armyId, String ownerPlayerId, String targetPlayerId, int troops,
            long departedEpochMillis, long arrivesEpochMillis) {
        this.armyId = armyId;
        this.ownerPlayerId = ownerPlayerId;
        this.targetPlayerId = targetPlayerId;
        this.troops = troops;
        this.departedEpochMillis = departedEpochMillis;
        this.arrivesEpochMillis = arrivesEpochMillis;
    }

    Messages.Army message() {
        return new Messages.Army(armyId, ownerPlayerId, targetPlayerId, troops,
                departedEpochMillis, arrivesEpochMillis);
    }
}
