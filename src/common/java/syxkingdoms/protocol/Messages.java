package syxkingdoms.protocol;

import java.util.ArrayList;
import java.util.List;

public final class Messages {
    private Messages() {
    }

    public record Hello(
            int protocolVersion,
            String gameVersion,
            String modVersion,
            String modManifest,
            long lastEventId,
            long lastCrestRevision,
            String worldId,
            String playerId,
            String authToken,
            String playerName,
            String kingdomName,
            String cityName,
            String placementReference) {
        public String encode() {
            return Protocol.frame("HELLO",
                    Integer.toString(protocolVersion),
                    Protocol.encodeText(gameVersion),
                    Protocol.encodeText(modVersion),
                    Protocol.encodeText(modManifest),
                    Long.toString(lastEventId),
                    Long.toString(lastCrestRevision),
                    Protocol.encodeText(worldId),
                    Protocol.encodeText(playerId),
                    Protocol.encodeText(authToken),
                    Protocol.encodeText(playerName),
                    Protocol.encodeText(kingdomName),
                    Protocol.encodeText(cityName),
                    Protocol.encodeText(placementReference));
        }

        public static Hello decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HELLO", 14);
            return new Hello(
                    Protocol.parseInt(f[1], "protocolVersion", 1, Integer.MAX_VALUE),
                    Protocol.decodeText(f[2], "gameVersion"),
                    Protocol.decodeText(f[3], "modVersion"),
                    Protocol.decodeText(f[4], "modManifest"),
                    Protocol.parseLong(f[5], "lastEventId", 0, Long.MAX_VALUE),
                    Protocol.parseLong(f[6], "lastCrestRevision", 0, Long.MAX_VALUE),
                    Protocol.decodeText(f[7], "worldId"),
                    Protocol.decodeText(f[8], "playerId"),
                    Protocol.decodeText(f[9], "authToken"),
                    Protocol.decodeText(f[10], "playerName"),
                    Protocol.decodeText(f[11], "kingdomName"),
                    Protocol.decodeText(f[12], "cityName"),
                    Protocol.decodeText(f[13], "placementReference"));
        }
    }

    public record Heartbeat(long sequence, int population, long credits) {
        public String encode() {
            return Protocol.frame("HEARTBEAT", Long.toString(sequence), Integer.toString(population), Long.toString(credits));
        }

        public static Heartbeat decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HEARTBEAT", 4);
            return new Heartbeat(
                    Protocol.parseLong(f[1], "sequence", 0, Long.MAX_VALUE),
                    Protocol.parseInt(f[2], "population", 0, 100_000_000),
                    Protocol.parseLong(f[3], "credits", -1_000_000_000_000_000L, 1_000_000_000_000_000L));
        }
    }

    public record Welcome(
            int protocolVersion,
            String worldId,
            String worldName,
            long serverTick,
            long tickMillis,
            double fixedSpeed,
            int cityX,
            int cityY,
            int worldWidth,
            int worldHeight,
            String worldSeed) {
        public String encode() {
            return Protocol.frame("WELCOME",
                    Integer.toString(protocolVersion),
                    Protocol.encodeText(worldId),
                    Protocol.encodeText(worldName),
                    Long.toString(serverTick),
                    Long.toString(tickMillis),
                    Double.toString(fixedSpeed),
                    Integer.toString(cityX),
                    Integer.toString(cityY),
                    Integer.toString(worldWidth),
                    Integer.toString(worldHeight),
                    Protocol.encodeText(worldSeed));
        }

        public static Welcome decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "WELCOME", 12);
            return new Welcome(
                    Protocol.parseInt(f[1], "protocolVersion", 1, Integer.MAX_VALUE),
                    Protocol.decodeText(f[2], "worldId"),
                    Protocol.decodeText(f[3], "worldName"),
                    Protocol.parseLong(f[4], "serverTick", 0, Long.MAX_VALUE),
                    Protocol.parseLong(f[5], "tickMillis", 50, 86_400_000),
                    Protocol.parseDouble(f[6], "fixedSpeed", 0.01, 1000.0),
                    Protocol.parseInt(f[7], "cityX", -1_000_000, 1_000_000),
                    Protocol.parseInt(f[8], "cityY", -1_000_000, 1_000_000),
                    Protocol.parseInt(f[9], "worldWidth", 8, 100_000),
                    Protocol.parseInt(f[10], "worldHeight", 8, 100_000),
                    Protocol.decodeText(f[11], "worldSeed"));
        }
    }

    public record City(
            String playerId,
            String playerName,
            String kingdomName,
            String cityName,
            int x,
            int y,
            int population,
            long credits,
            boolean online,
            long lastSeenEpochMillis,
            long protectionEndsEpochMillis) {
        public String encode() {
            return Protocol.frame("CITY",
                    Protocol.encodeText(playerId),
                    Protocol.encodeText(playerName),
                    Protocol.encodeText(kingdomName),
                    Protocol.encodeText(cityName),
                    Integer.toString(x),
                    Integer.toString(y),
                    Integer.toString(population),
                    Long.toString(credits),
                    online ? "1" : "0",
                    Long.toString(lastSeenEpochMillis),
                    Long.toString(protectionEndsEpochMillis));
        }

        public static City decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "CITY", 12);
            if (!"0".equals(f[9]) && !"1".equals(f[9])) {
                throw new ProtocolException("BAD_BOOLEAN", "online must be 0 or 1");
            }
            return new City(
                    Protocol.decodeText(f[1], "playerId"),
                    Protocol.decodeText(f[2], "playerName"),
                    Protocol.decodeText(f[3], "kingdomName"),
                    Protocol.decodeText(f[4], "cityName"),
                    Protocol.parseInt(f[5], "x", -1_000_000, 1_000_000),
                    Protocol.parseInt(f[6], "y", -1_000_000, 1_000_000),
                    Protocol.parseInt(f[7], "population", 0, 100_000_000),
                    Protocol.parseLong(f[8], "credits", -1_000_000_000_000_000L, 1_000_000_000_000_000L),
                    "1".equals(f[9]),
                    Protocol.parseLong(f[10], "lastSeenEpochMillis", 0, Long.MAX_VALUE),
                    Protocol.parseLong(f[11], "protectionEndsEpochMillis", 0, Long.MAX_VALUE));
        }
    }

    public record WorldState(
            long serverTick,
            long serverTimeEpochMillis,
            double fixedSpeed,
            int worldWidth,
            int worldHeight,
            String worldSeed,
            List<City> cities) {
        public String header() {
            return Protocol.frame("STATE", Long.toString(serverTick), Long.toString(serverTimeEpochMillis),
                    Double.toString(fixedSpeed), Integer.toString(worldWidth), Integer.toString(worldHeight),
                    Protocol.encodeText(worldSeed), Integer.toString(cities.size()));
        }

        public static Header decodeHeader(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "STATE", 8);
            return new Header(
                    Protocol.parseLong(f[1], "serverTick", 0, Long.MAX_VALUE),
                    Protocol.parseLong(f[2], "serverTimeEpochMillis", 0, Long.MAX_VALUE),
                    Protocol.parseDouble(f[3], "fixedSpeed", 0.01, 1000.0),
                    Protocol.parseInt(f[4], "worldWidth", 8, 100_000),
                    Protocol.parseInt(f[5], "worldHeight", 8, 100_000),
                    Protocol.decodeText(f[6], "worldSeed"),
                    Protocol.parseInt(f[7], "cityCount", 0, Protocol.MAX_CITIES_PER_STATE));
        }

        public record Header(
                long serverTick,
                long serverTimeEpochMillis,
                double fixedSpeed,
                int worldWidth,
                int worldHeight,
                String worldSeed,
                int cityCount) {
            public WorldState withCities(List<City> cities) throws ProtocolException {
                if (cities.size() != cityCount) {
                    throw new ProtocolException("BAD_STATE", "City count does not match state header");
                }
                return new WorldState(serverTick, serverTimeEpochMillis, fixedSpeed,
                        worldWidth, worldHeight, worldSeed, List.copyOf(cities));
            }
        }

        public static List<String> encodeFrames(WorldState state) {
            List<String> frames = new ArrayList<>(state.cities.size() + 2);
            frames.add(state.header());
            for (City city : state.cities) {
                frames.add(city.encode());
            }
            frames.add("STATE_END");
            return frames;
        }
    }

    public record ClientUpdate(
            long sequence,
            int population,
            long credits,
            long lastEventId,
            long lastCrestRevision,
            List<Command> commands) {
        public String header() {
            return Protocol.frame("UPDATE", Long.toString(sequence), Integer.toString(population),
                    Long.toString(credits), Long.toString(lastEventId), Long.toString(lastCrestRevision),
                    Integer.toString(commands.size()));
        }

        public static Header decodeHeader(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "UPDATE", 7);
            return new Header(
                    Protocol.parseLong(f[1], "sequence", 0, Long.MAX_VALUE),
                    Protocol.parseInt(f[2], "population", 0, 100_000_000),
                    Protocol.parseLong(f[3], "credits", -1_000_000_000_000_000L, 1_000_000_000_000_000L),
                    Protocol.parseLong(f[4], "lastEventId", 0, Long.MAX_VALUE),
                    Protocol.parseLong(f[5], "lastCrestRevision", 0, Long.MAX_VALUE),
                    Protocol.parseInt(f[6], "commandCount", 0, Protocol.MAX_COMMANDS_PER_UPDATE));
        }

        public record Header(long sequence, int population, long credits, long lastEventId,
                long lastCrestRevision, int commandCount) {
            public ClientUpdate withCommands(List<Command> commands) throws ProtocolException {
                if (commands.size() != commandCount) {
                    throw new ProtocolException("BAD_UPDATE", "Command count does not match update header");
                }
                return new ClientUpdate(sequence, population, credits, lastEventId,
                        lastCrestRevision, List.copyOf(commands));
            }
        }
    }

    public record Command(
            String commandId,
            String type,
            String targetPlayerId,
            String argument,
            long value1,
            long value2,
            String text) {
        public String encode() {
            return Protocol.frame("COMMAND", commandId, type,
                    Protocol.encodeText(targetPlayerId), Protocol.encodeText(argument),
                    Long.toString(value1), Long.toString(value2), Protocol.encodeText(text));
        }

        public static Command decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "COMMAND", 8);
            if (!f[1].matches("[A-Za-z0-9-]{8,64}")) {
                throw new ProtocolException("BAD_COMMAND", "Invalid command ID");
            }
            if (!f[2].matches("[A-Z_]{2,24}")) {
                throw new ProtocolException("BAD_COMMAND", "Invalid command type");
            }
            return new Command(f[1], f[2],
                    Protocol.decodeText(f[3], "targetPlayerId"),
                    Protocol.decodeText(f[4], "argument"),
                    Protocol.parseLong(f[5], "value1", Long.MIN_VALUE, Long.MAX_VALUE),
                    Protocol.parseLong(f[6], "value2", Long.MIN_VALUE, Long.MAX_VALUE),
                    Protocol.decodeText(f[7], "commandText"));
        }
    }

    public record CommandResult(String commandId, boolean accepted, String code, String message) {
        public String encode() {
            return Protocol.frame("COMMAND_RESULT", commandId, accepted ? "1" : "0", code,
                    Protocol.encodeText(message));
        }

        public static CommandResult decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "COMMAND_RESULT", 5);
            if (!f[1].matches("[A-Za-z0-9-]{8,64}")) {
                throw new ProtocolException("BAD_RESULT", "Invalid command result ID");
            }
            if (!"0".equals(f[2]) && !"1".equals(f[2])) {
                throw new ProtocolException("BAD_RESULT", "accepted must be 0 or 1");
            }
            if (!f[3].matches("[A-Z_]{2,32}")) {
                throw new ProtocolException("BAD_RESULT", "Invalid command result code");
            }
            return new CommandResult(f[1], "1".equals(f[2]), f[3],
                    Protocol.decodeText(f[4], "commandResultMessage"));
        }
    }

    public record ResultsHeader(int resultCount) {
        public String encode() {
            return Protocol.frame("RESULTS", Integer.toString(resultCount));
        }

        public static ResultsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "RESULTS", 2);
            return new ResultsHeader(Protocol.parseInt(f[1], "resultCount", 0,
                    Protocol.MAX_COMMANDS_PER_UPDATE));
        }
    }

    public record WorldEvent(
            long eventId,
            long epochMillis,
            String type,
            String actorPlayerId,
            String actorName,
            String targetPlayerId,
            String text,
            String argument,
            long value1,
            long value2) {
        public String encode() {
            return Protocol.frame("EVENT", Long.toString(eventId), Long.toString(epochMillis), type,
                    Protocol.encodeText(actorPlayerId), Protocol.encodeText(actorName),
                    Protocol.encodeText(targetPlayerId), Protocol.encodeText(text),
                    Protocol.encodeText(argument), Long.toString(value1), Long.toString(value2));
        }

        public static WorldEvent decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "EVENT", 11);
            if (!f[3].matches("[A-Z_]{2,32}")) {
                throw new ProtocolException("BAD_EVENT", "Invalid event type");
            }
            return new WorldEvent(
                    Protocol.parseLong(f[1], "eventId", 1, Long.MAX_VALUE),
                    Protocol.parseLong(f[2], "eventEpochMillis", 0, Long.MAX_VALUE),
                    f[3], Protocol.decodeText(f[4], "eventActorId"),
                    Protocol.decodeText(f[5], "eventActorName"),
                    Protocol.decodeText(f[6], "eventTargetId"),
                    Protocol.decodeText(f[7], "eventText"),
                    Protocol.decodeText(f[8], "eventArgument"),
                    Protocol.parseLong(f[9], "eventValue1", Long.MIN_VALUE, Long.MAX_VALUE),
                    Protocol.parseLong(f[10], "eventValue2", Long.MIN_VALUE, Long.MAX_VALUE));
        }
    }

    public record EventsHeader(long cursor, int eventCount) {
        public String encode() {
            return Protocol.frame("EVENTS", Long.toString(cursor), Integer.toString(eventCount));
        }

        public static EventsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "EVENTS", 3);
            return new EventsHeader(
                    Protocol.parseLong(f[1], "eventCursor", 0, Long.MAX_VALUE),
                    Protocol.parseInt(f[2], "eventCount", 0, Protocol.MAX_EVENTS_PER_SYNC));
        }
    }

    public record Crest(
            long revision,
            String playerId,
            String hash,
            String pixels,
            String palette) {
        public String encode() {
            return Protocol.frame("CREST", Long.toString(revision), Protocol.encodeText(playerId), hash,
                    Protocol.encodeText(pixels), Protocol.encodeText(palette));
        }

        public static Crest decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "CREST", 6);
            long revision = Protocol.parseLong(f[1], "crestRevision", 1, Long.MAX_VALUE);
            String playerId = Protocol.decodeText(f[2], "crestPlayerId");
            String hash = f[3];
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new ProtocolException("BAD_CREST", "Crest hash must be a lowercase SHA-256 digest");
            }
            String pixels = Protocol.decodeText(f[4], "crestPixels");
            String palette = Protocol.decodeText(f[5], "crestPalette");
            CrestDesign design = CrestDesign.decode(pixels, palette);
            if (!hash.equals(design.hash())) {
                throw new ProtocolException("BAD_CREST", "Crest hash does not match its pixel and palette data");
            }
            return new Crest(revision, playerId, hash, pixels, palette);
        }

        public CrestDesign design() throws ProtocolException {
            CrestDesign design = CrestDesign.decode(pixels, palette);
            if (!hash.equals(design.hash())) {
                throw new ProtocolException("BAD_CREST", "Crest hash does not match its pixel and palette data");
            }
            return design;
        }
    }

    public record CrestsHeader(long cursor, int crestCount) {
        public String encode() {
            return Protocol.frame("CRESTS", Long.toString(cursor), Integer.toString(crestCount));
        }

        public static CrestsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "CRESTS", 3);
            return new CrestsHeader(
                    Protocol.parseLong(f[1], "crestCursor", 0, Long.MAX_VALUE),
                    Protocol.parseInt(f[2], "crestCount", 0, Protocol.MAX_CRESTS_PER_SYNC));
        }
    }

    public record House(
            String houseId,
            String name,
            String tag,
            String sovereignPlayerId,
            String announcement,
            int memberCount,
            int capitalProvinceX,
            int capitalProvinceY,
            int seasonPoints,
            String crestHash,
            String crestPixels,
            String crestPalette) {
        public String encode() {
            return Protocol.frame("HOUSE", Protocol.encodeText(houseId), Protocol.encodeText(name),
                    tag, Protocol.encodeText(sovereignPlayerId), Protocol.encodeText(announcement),
                    Integer.toString(memberCount), Integer.toString(capitalProvinceX),
                    Integer.toString(capitalProvinceY), Integer.toString(seasonPoints), crestHash,
                    Protocol.encodeText(crestPixels), Protocol.encodeText(crestPalette));
        }

        public static House decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSE", 13);
            String hash = f[10];
            String pixels = Protocol.decodeText(f[11], "houseCrestPixels");
            String palette = Protocol.decodeText(f[12], "houseCrestPalette");
            if (!hash.isBlank()) {
                if (!hash.matches("[0-9a-f]{64}")) {
                    throw new ProtocolException("BAD_HOUSE", "House crest hash is invalid");
                }
                CrestDesign crest = CrestDesign.decode(pixels, palette);
                if (!hash.equals(crest.hash())) {
                    throw new ProtocolException("BAD_HOUSE", "House crest hash does not match its design");
                }
            } else if (!pixels.isBlank() || !palette.isBlank()) {
                throw new ProtocolException("BAD_HOUSE", "House crest data has no hash");
            }
            String tag = f[3];
            if (!tag.matches("[A-Z0-9]{3,5}")) {
                throw new ProtocolException("BAD_HOUSE", "House tag is invalid");
            }
            return new House(Protocol.decodeText(f[1], "houseId"),
                    Protocol.decodeText(f[2], "houseName"), tag,
                    Protocol.decodeText(f[4], "houseSovereign"),
                    Protocol.decodeText(f[5], "houseAnnouncement"),
                    Protocol.parseInt(f[6], "houseMemberCount", 1, 20),
                    Protocol.parseInt(f[7], "houseCapitalX", -1, 9),
                    Protocol.parseInt(f[8], "houseCapitalY", -1, 9),
                    Protocol.parseInt(f[9], "houseSeasonPoints", 0, 1_000_000_000),
                    hash, pixels, palette);
        }

        public CrestDesign crest() throws ProtocolException {
            return crestHash.isBlank() ? null : CrestDesign.decode(crestPixels, crestPalette);
        }
    }

    public record HousesHeader(int houseCount) {
        public String encode() { return Protocol.frame("HOUSES", Integer.toString(houseCount)); }
        public static HousesHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSES", 2);
            return new HousesHeader(Protocol.parseInt(f[1], "houseCount", 0, Protocol.MAX_HOUSES_PER_SYNC));
        }
    }

    public record HouseMember(String playerId, String houseId, String role, long joinedEpochMillis) {
        public String encode() {
            return Protocol.frame("HOUSE_MEMBER", Protocol.encodeText(playerId), Protocol.encodeText(houseId),
                    role, Long.toString(joinedEpochMillis));
        }
        public static HouseMember decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSE_MEMBER", 5);
            String role = houseRole(f[3]);
            return new HouseMember(Protocol.decodeText(f[1], "memberPlayerId"),
                    Protocol.decodeText(f[2], "memberHouseId"), role,
                    Protocol.parseLong(f[4], "memberJoined", 0, Long.MAX_VALUE));
        }
    }

    public record HouseMembersHeader(int memberCount) {
        public String encode() { return Protocol.frame("HOUSE_MEMBERS", Integer.toString(memberCount)); }
        public static HouseMembersHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSE_MEMBERS", 2);
            return new HouseMembersHeader(Protocol.parseInt(f[1], "houseMemberCount", 0,
                    Protocol.MAX_HOUSE_MEMBERS_PER_SYNC));
        }
    }

    public record HouseRequest(String requestId, String houseId, String playerId, String type,
            long createdEpochMillis, long expiresEpochMillis) {
        public String encode() {
            return Protocol.frame("HOUSE_REQUEST", Protocol.encodeText(requestId),
                    Protocol.encodeText(houseId), Protocol.encodeText(playerId), type,
                    Long.toString(createdEpochMillis), Long.toString(expiresEpochMillis));
        }
        public static HouseRequest decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSE_REQUEST", 7);
            if (!"INVITE".equals(f[4]) && !"APPLICATION".equals(f[4])) {
                throw new ProtocolException("BAD_HOUSE_REQUEST", "Unknown House request type");
            }
            long created = Protocol.parseLong(f[5], "houseRequestCreated", 0, Long.MAX_VALUE);
            long expires = Protocol.parseLong(f[6], "houseRequestExpires", created, Long.MAX_VALUE);
            return new HouseRequest(Protocol.decodeText(f[1], "houseRequestId"),
                    Protocol.decodeText(f[2], "requestHouseId"),
                    Protocol.decodeText(f[3], "requestPlayerId"), f[4], created, expires);
        }
    }

    public record HouseRequestsHeader(int requestCount) {
        public String encode() { return Protocol.frame("HOUSE_REQUESTS", Integer.toString(requestCount)); }
        public static HouseRequestsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSE_REQUESTS", 2);
            return new HouseRequestsHeader(Protocol.parseInt(f[1], "houseRequestCount", 0,
                    Protocol.MAX_HOUSE_REQUESTS_PER_SYNC));
        }
    }

    public record Province(int x, int y, String ownerHouseId, String occupierHouseId,
            int occupationInfluence, long blockadeEndsEpochMillis, long tributeCrowns) {
        public String encode() {
            return Protocol.frame("PROVINCE", Integer.toString(x), Integer.toString(y),
                    Protocol.encodeText(ownerHouseId), Protocol.encodeText(occupierHouseId),
                    Integer.toString(occupationInfluence), Long.toString(blockadeEndsEpochMillis),
                    Long.toString(tributeCrowns));
        }
        public static Province decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "PROVINCE", 8);
            return new Province(Protocol.parseInt(f[1], "provinceX", 0, 9),
                    Protocol.parseInt(f[2], "provinceY", 0, 9),
                    Protocol.decodeText(f[3], "provinceOwner"),
                    Protocol.decodeText(f[4], "provinceOccupier"),
                    Protocol.parseInt(f[5], "provinceInfluence", 0, 100),
                    Protocol.parseLong(f[6], "provinceBlockadeEnds", 0, Long.MAX_VALUE),
                    Protocol.parseLong(f[7], "provinceTribute", 0, 1_000_000_000L));
        }
    }

    public record ProvincesHeader(int provinceCount) {
        public String encode() { return Protocol.frame("PROVINCES", Integer.toString(provinceCount)); }
        public static ProvincesHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "PROVINCES", 2);
            return new ProvincesHeader(Protocol.parseInt(f[1], "provinceCount", 0,
                    Protocol.MAX_PROVINCES_PER_SYNC));
        }
    }

    public record HouseWar(String warId, String attackerHouseId, String defenderHouseId,
            String phase, long declaredEpochMillis, long startsEpochMillis, long endsEpochMillis,
            int attackerScore, int defenderScore, long truceEndsEpochMillis, String surrenderedHouseId) {
        public String encode() {
            return Protocol.frame("HOUSE_WAR", Protocol.encodeText(warId),
                    Protocol.encodeText(attackerHouseId), Protocol.encodeText(defenderHouseId), phase,
                    Long.toString(declaredEpochMillis), Long.toString(startsEpochMillis),
                    Long.toString(endsEpochMillis), Integer.toString(attackerScore),
                    Integer.toString(defenderScore), Long.toString(truceEndsEpochMillis),
                    Protocol.encodeText(surrenderedHouseId));
        }
        public static HouseWar decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "HOUSE_WAR", 12);
            if (!"PREPARING".equals(f[4]) && !"ACTIVE".equals(f[4])
                    && !"TRUCE".equals(f[4]) && !"ENDED".equals(f[4])) {
                throw new ProtocolException("BAD_WAR", "Unknown House war phase");
            }
            long declared = Protocol.parseLong(f[5], "warDeclared", 0, Long.MAX_VALUE);
            long starts = Protocol.parseLong(f[6], "warStarts", declared, Long.MAX_VALUE);
            long ends = Protocol.parseLong(f[7], "warEnds", starts, Long.MAX_VALUE);
            return new HouseWar(Protocol.decodeText(f[1], "warId"),
                    Protocol.decodeText(f[2], "warAttackerHouse"),
                    Protocol.decodeText(f[3], "warDefenderHouse"), f[4], declared, starts, ends,
                    Protocol.parseInt(f[8], "warAttackerScore", 0, 1_000_000_000),
                    Protocol.parseInt(f[9], "warDefenderScore", 0, 1_000_000_000),
                    Protocol.parseLong(f[10], "warTruceEnds", 0, Long.MAX_VALUE),
                    Protocol.decodeText(f[11], "warSurrenderedHouse"));
        }
    }

    public record WarsHeader(int warCount) {
        public String encode() { return Protocol.frame("WARS", Integer.toString(warCount)); }
        public static WarsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "WARS", 2);
            return new WarsHeader(Protocol.parseInt(f[1], "warCount", 0, Protocol.MAX_WARS_PER_SYNC));
        }
    }

    public record BattleReport(String reportId, long epochMillis, String attackerPlayerId,
            String defenderPlayerId, String warId, String outcome, int attackingTroops,
            int defendingTroops, int attackerSurvivors, int defenderSurvivors, long crownsLooted,
            int provinceX, int provinceY, int warScore) {
        public String encode() {
            return Protocol.frame("BATTLE_REPORT", Protocol.encodeText(reportId), Long.toString(epochMillis),
                    Protocol.encodeText(attackerPlayerId), Protocol.encodeText(defenderPlayerId),
                    Protocol.encodeText(warId), outcome, Integer.toString(attackingTroops),
                    Integer.toString(defendingTroops), Integer.toString(attackerSurvivors),
                    Integer.toString(defenderSurvivors), Long.toString(crownsLooted),
                    Integer.toString(provinceX), Integer.toString(provinceY), Integer.toString(warScore));
        }
        public static BattleReport decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "BATTLE_REPORT", 15);
            if (!"ATTACKER_WIN".equals(f[6]) && !"DEFENDER_WIN".equals(f[6])
                    && !"REINFORCED".equals(f[6])) {
                throw new ProtocolException("BAD_REPORT", "Unknown battle report outcome");
            }
            return new BattleReport(Protocol.decodeText(f[1], "reportId"),
                    Protocol.parseLong(f[2], "reportTime", 0, Long.MAX_VALUE),
                    Protocol.decodeText(f[3], "reportAttacker"),
                    Protocol.decodeText(f[4], "reportDefender"),
                    Protocol.decodeText(f[5], "reportWar"), f[6],
                    Protocol.parseInt(f[7], "reportAttacking", 0, 10_000),
                    Protocol.parseInt(f[8], "reportDefending", 0, 10_000),
                    Protocol.parseInt(f[9], "reportAttackerSurvivors", 0, 10_000),
                    Protocol.parseInt(f[10], "reportDefenderSurvivors", 0, 10_000),
                    Protocol.parseLong(f[11], "reportLoot", 0, 1_000_000_000L),
                    Protocol.parseInt(f[12], "reportProvinceX", 0, 9),
                    Protocol.parseInt(f[13], "reportProvinceY", 0, 9),
                    Protocol.parseInt(f[14], "reportWarScore", 0, 1_000_000));
        }
    }

    public record ReportsHeader(int reportCount) {
        public String encode() { return Protocol.frame("REPORTS", Integer.toString(reportCount)); }
        public static ReportsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "REPORTS", 2);
            return new ReportsHeader(Protocol.parseInt(f[1], "reportCount", 0, Protocol.MAX_REPORTS_PER_SYNC));
        }
    }

    public record Season(int number, long startsEpochMillis, long endsEpochMillis,
            String winnerHouseId, String phase) {
        public String encode() {
            return Protocol.frame("SEASON", Integer.toString(number), Long.toString(startsEpochMillis),
                    Long.toString(endsEpochMillis), Protocol.encodeText(winnerHouseId), phase);
        }
        public static Season decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "SEASON", 6);
            if (!"ACTIVE".equals(f[5]) && !"COMPLETE".equals(f[5])) {
                throw new ProtocolException("BAD_SEASON", "Unknown season phase");
            }
            long starts = Protocol.parseLong(f[2], "seasonStarts", 0, Long.MAX_VALUE);
            return new Season(Protocol.parseInt(f[1], "seasonNumber", 1, Integer.MAX_VALUE), starts,
                    Protocol.parseLong(f[3], "seasonEnds", starts, Long.MAX_VALUE),
                    Protocol.decodeText(f[4], "seasonWinner"), f[5]);
        }
    }

    public record Relation(
            String playerId,
            String playerName,
            String yourStance,
            String theirStance,
            String effectiveStance) {
        public String encode() {
            return Protocol.frame("RELATION", Protocol.encodeText(playerId), Protocol.encodeText(playerName),
                    yourStance, theirStance, effectiveStance);
        }

        public static Relation decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "RELATION", 6);
            String your = stance(f[3]);
            String their = stance(f[4]);
            String effective = stance(f[5]);
            return new Relation(Protocol.decodeText(f[1], "relationPlayerId"),
                    Protocol.decodeText(f[2], "relationPlayerName"), your, their, effective);
        }
    }

    public record RelationsHeader(int relationCount) {
        public String encode() {
            return Protocol.frame("RELATIONS", Integer.toString(relationCount));
        }

        public static RelationsHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "RELATIONS", 2);
            return new RelationsHeader(Protocol.parseInt(f[1], "relationCount", 0,
                    Protocol.MAX_RELATIONS_PER_SYNC));
        }
    }

    public record PlayerState(long crowns, int levies, long protectionEndsEpochMillis) {
        public String encode() {
            return Protocol.frame("PLAYER_STATE", Long.toString(crowns), Integer.toString(levies),
                    Long.toString(protectionEndsEpochMillis));
        }

        public static PlayerState decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "PLAYER_STATE", 4);
            return new PlayerState(
                    Protocol.parseLong(f[1], "crowns", 0, 1_000_000_000L),
                    Protocol.parseInt(f[2], "levies", 0, 10_000),
                    Protocol.parseLong(f[3], "protectionEndsEpochMillis", 0, Long.MAX_VALUE));
        }
    }

    public record Army(
            String armyId,
            String ownerPlayerId,
            String targetPlayerId,
            int troops,
            long departedEpochMillis,
            long arrivesEpochMillis,
            String mission,
            String warId) {
        public Army(String armyId, String ownerPlayerId, String targetPlayerId, int troops,
                long departedEpochMillis, long arrivesEpochMillis) {
            this(armyId, ownerPlayerId, targetPlayerId, troops, departedEpochMillis,
                    arrivesEpochMillis, "ATTACK", "");
        }

        public String encode() {
            return Protocol.frame("ARMY", Protocol.encodeText(armyId), Protocol.encodeText(ownerPlayerId),
                    Protocol.encodeText(targetPlayerId), Integer.toString(troops),
                    Long.toString(departedEpochMillis), Long.toString(arrivesEpochMillis), mission,
                    Protocol.encodeText(warId));
        }

        public static Army decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "ARMY", 9);
            long departed = Protocol.parseLong(f[5], "armyDeparted", 0, Long.MAX_VALUE);
            long arrives = Protocol.parseLong(f[6], "armyArrives", 0, Long.MAX_VALUE);
            if (arrives < departed) {
                throw new ProtocolException("BAD_ARMY", "Army arrival precedes departure");
            }
            if (!"ATTACK".equals(f[7]) && !"REINFORCE".equals(f[7])) {
                throw new ProtocolException("BAD_ARMY", "Unknown army mission");
            }
            return new Army(Protocol.decodeText(f[1], "armyId"),
                    Protocol.decodeText(f[2], "armyOwnerId"),
                    Protocol.decodeText(f[3], "armyTargetId"),
                    Protocol.parseInt(f[4], "armyTroops", 0, 10_000), departed, arrives, f[7],
                    Protocol.decodeText(f[8], "armyWarId"));
        }
    }

    public record ArmiesHeader(int armyCount) {
        public String encode() {
            return Protocol.frame("ARMIES", Integer.toString(armyCount));
        }

        public static ArmiesHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "ARMIES", 2);
            return new ArmiesHeader(Protocol.parseInt(f[1], "armyCount", 0, Protocol.MAX_ARMIES_PER_SYNC));
        }
    }

    public record Delivery(
            String deliveryId,
            String senderPlayerId,
            String resourceKey,
            int amount,
            long arrivedEpochMillis) {
        public String encode() {
            return Protocol.frame("DELIVERY", Protocol.encodeText(deliveryId),
                    Protocol.encodeText(senderPlayerId), Protocol.encodeText(resourceKey),
                    Integer.toString(amount), Long.toString(arrivedEpochMillis));
        }

        public static Delivery decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "DELIVERY", 6);
            return new Delivery(Protocol.decodeText(f[1], "deliveryId"),
                    Protocol.decodeText(f[2], "deliverySenderId"),
                    Protocol.decodeText(f[3], "deliveryResourceKey"),
                    Protocol.parseInt(f[4], "deliveryAmount", 1, 10_000),
                    Protocol.parseLong(f[5], "deliveryArrived", 0, Long.MAX_VALUE));
        }
    }

    public record DeliveriesHeader(int deliveryCount) {
        public String encode() {
            return Protocol.frame("DELIVERIES", Integer.toString(deliveryCount));
        }

        public static DeliveriesHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "DELIVERIES", 2);
            return new DeliveriesHeader(Protocol.parseInt(f[1], "deliveryCount", 0,
                    Protocol.MAX_DELIVERIES_PER_SYNC));
        }
    }

    public record MarketOffer(
            String offerId,
            String sellerPlayerId,
            String sellerName,
            String resourceKey,
            int quantity,
            long unitPrice,
            long createdEpochMillis) {
        public String encode() {
            return Protocol.frame("OFFER", Protocol.encodeText(offerId),
                    Protocol.encodeText(sellerPlayerId), Protocol.encodeText(sellerName),
                    Protocol.encodeText(resourceKey), Integer.toString(quantity),
                    Long.toString(unitPrice), Long.toString(createdEpochMillis));
        }

        public static MarketOffer decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "OFFER", 8);
            return new MarketOffer(Protocol.decodeText(f[1], "offerId"),
                    Protocol.decodeText(f[2], "offerSellerId"),
                    Protocol.decodeText(f[3], "offerSellerName"),
                    Protocol.decodeText(f[4], "offerResourceKey"),
                    Protocol.parseInt(f[5], "offerQuantity", 1, 10_000),
                    Protocol.parseLong(f[6], "offerUnitPrice", 1, 1_000_000L),
                    Protocol.parseLong(f[7], "offerCreated", 0, Long.MAX_VALUE));
        }
    }

    public record OffersHeader(int offerCount) {
        public String encode() {
            return Protocol.frame("OFFERS", Integer.toString(offerCount));
        }

        public static OffersHeader decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "OFFERS", 2);
            return new OffersHeader(Protocol.parseInt(f[1], "offerCount", 0,
                    Protocol.MAX_MARKET_OFFERS_PER_SYNC));
        }
    }

    private static String stance(String value) throws ProtocolException {
        if (!"NEUTRAL".equals(value) && !"ALLY".equals(value) && !"WAR".equals(value)) {
            throw new ProtocolException("BAD_STANCE", "Stance must be NEUTRAL, ALLY, or WAR");
        }
        return value;
    }

    private static String houseRole(String value) throws ProtocolException {
        if (!"SOVEREIGN".equals(value) && !"MARSHAL".equals(value)
                && !"STEWARD".equals(value) && !"HERALD".equals(value)
                && !"MEMBER".equals(value)) {
            throw new ProtocolException("BAD_HOUSE_ROLE", "Unknown House role");
        }
        return value;
    }

    public record Error(String code, String message) {
        public String encode() {
            return Protocol.frame("ERROR", code, Protocol.encodeText(message));
        }

        public static Error decode(String line) throws ProtocolException {
            String[] f = Protocol.split(line, "ERROR", 3);
            if (!f[1].matches("[A-Z_]{2,32}")) {
                throw new ProtocolException("BAD_ERROR", "Invalid error code");
            }
            return new Error(f[1], Protocol.decodeText(f[2], "errorMessage"));
        }
    }
}
