package syxkingdoms.server;

import syxkingdoms.protocol.Messages;

final class ArmyRecord {
    final String armyId;
    final String ownerPlayerId;
    final String targetPlayerId;
    final int troops;
    final long departedEpochMillis;
    final long arrivesEpochMillis;
    final String mission;
    final String warId;

    ArmyRecord(String armyId, String ownerPlayerId, String targetPlayerId, int troops,
            long departedEpochMillis, long arrivesEpochMillis) {
        this(armyId, ownerPlayerId, targetPlayerId, troops, departedEpochMillis,
                arrivesEpochMillis, "ATTACK", "");
    }

    ArmyRecord(String armyId, String ownerPlayerId, String targetPlayerId, int troops,
            long departedEpochMillis, long arrivesEpochMillis, String mission, String warId) {
        this.armyId = armyId;
        this.ownerPlayerId = ownerPlayerId;
        this.targetPlayerId = targetPlayerId;
        this.troops = troops;
        this.departedEpochMillis = departedEpochMillis;
        this.arrivesEpochMillis = arrivesEpochMillis;
        this.mission = mission;
        this.warId = warId;
    }

    Messages.Army message() {
        return new Messages.Army(armyId, ownerPlayerId, targetPlayerId, troops,
                departedEpochMillis, arrivesEpochMillis, mission, warId);
    }
}
