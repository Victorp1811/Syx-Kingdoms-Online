package syxkingdoms.server;

final class HouseWarRecord {
    final String warId;
    final String attackerHouseId;
    final String defenderHouseId;
    final long declaredEpochMillis;
    final long startsEpochMillis;
    final long endsEpochMillis;
    String phase;
    int attackerScore;
    int defenderScore;
    long truceEndsEpochMillis;
    String surrenderedHouseId = "";

    HouseWarRecord(String warId, String attackerHouseId, String defenderHouseId,
            long declaredEpochMillis, long startsEpochMillis, long endsEpochMillis, String phase) {
        this.warId = warId;
        this.attackerHouseId = attackerHouseId;
        this.defenderHouseId = defenderHouseId;
        this.declaredEpochMillis = declaredEpochMillis;
        this.startsEpochMillis = startsEpochMillis;
        this.endsEpochMillis = endsEpochMillis;
        this.phase = phase;
    }

    boolean involves(String houseId) {
        return attackerHouseId.equals(houseId) || defenderHouseId.equals(houseId);
    }

    String enemyOf(String houseId) {
        return attackerHouseId.equals(houseId) ? defenderHouseId
                : defenderHouseId.equals(houseId) ? attackerHouseId : "";
    }
}
