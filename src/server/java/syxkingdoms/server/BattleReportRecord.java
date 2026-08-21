package syxkingdoms.server;

import syxkingdoms.protocol.Messages;

final class BattleReportRecord {
    final String reportId;
    final long epochMillis;
    final String attackerPlayerId;
    final String defenderPlayerId;
    final String warId;
    final String outcome;
    final int attackingTroops;
    final int defendingTroops;
    final int attackerSurvivors;
    final int defenderSurvivors;
    final long crownsLooted;
    final int provinceX;
    final int provinceY;
    final int warScore;

    BattleReportRecord(String reportId, long epochMillis, String attackerPlayerId,
            String defenderPlayerId, String warId, String outcome, int attackingTroops,
            int defendingTroops, int attackerSurvivors, int defenderSurvivors,
            long crownsLooted, int provinceX, int provinceY, int warScore) {
        this.reportId = reportId;
        this.epochMillis = epochMillis;
        this.attackerPlayerId = attackerPlayerId;
        this.defenderPlayerId = defenderPlayerId;
        this.warId = warId;
        this.outcome = outcome;
        this.attackingTroops = attackingTroops;
        this.defendingTroops = defendingTroops;
        this.attackerSurvivors = attackerSurvivors;
        this.defenderSurvivors = defenderSurvivors;
        this.crownsLooted = crownsLooted;
        this.provinceX = provinceX;
        this.provinceY = provinceY;
        this.warScore = warScore;
    }

    Messages.BattleReport message() {
        return new Messages.BattleReport(reportId, epochMillis, attackerPlayerId,
                defenderPlayerId, warId, outcome, attackingTroops, defendingTroops,
                attackerSurvivors, defenderSurvivors, crownsLooted, provinceX, provinceY, warScore);
    }
}
