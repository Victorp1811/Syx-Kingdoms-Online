package syxkingdoms.server;

import syxkingdoms.protocol.Messages;

final class WorldEventRecord {
    final long eventId;
    final long epochMillis;
    final String commandId;
    final String type;
    final String actorPlayerId;
    final String actorName;
    final String targetPlayerId;
    final String text;
    final String argument;
    final long value1;
    final long value2;

    WorldEventRecord(long eventId, long epochMillis, String commandId, String type,
            String actorPlayerId, String actorName, String targetPlayerId,
            String text, String argument, long value1, long value2) {
        this.eventId = eventId;
        this.epochMillis = epochMillis;
        this.commandId = commandId;
        this.type = type;
        this.actorPlayerId = actorPlayerId;
        this.actorName = actorName;
        this.targetPlayerId = targetPlayerId;
        this.text = text;
        this.argument = argument;
        this.value1 = value1;
        this.value2 = value2;
    }

    Messages.WorldEvent message() {
        return new Messages.WorldEvent(eventId, epochMillis, type, actorPlayerId, actorName,
                targetPlayerId, text, argument, value1, value2);
    }

    boolean visibleTo(String playerId) {
        return "CHAT".equals(type) || "STANCE".equals(type)
                || "ARMY_SENT".equals(type) || "BATTLE".equals(type)
                || "KINGDOM_FOUNDED".equals(type) || "PROTECTION_ENDED".equals(type)
                || "CREST_UPDATED".equals(type)
                || type.startsWith("OFFER_")
                || actorPlayerId.equals(playerId) || targetPlayerId.equals(playerId);
    }
}
