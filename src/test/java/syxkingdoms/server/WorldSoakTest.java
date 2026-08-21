package syxkingdoms.server;

import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exercises a busy persistent world through repeated disconnect and restart cycles. */
public final class WorldSoakTest {
    private static final int PLAYER_COUNT = 48;
    private static final int HOUSE_COUNT = 8;
    private static final int MEMBERS_PER_HOUSE = PLAYER_COUNT / HOUSE_COUNT;
    private static final int RESTART_CYCLES = 6;
    private static final int SYNC_ROUNDS_PER_CYCLE = 20;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("syx-kingdoms-soak-test-");
        Path worldFile = directory.resolve("world.properties");
        ServerConfig config = WorldPersistenceTest.config(worldFile);
        List<Messages.Hello> identities = identities();
        Map<String, String> coordinates = new HashMap<>();
        Map<String, World.JoinResult> sessions = new HashMap<>();
        Map<String, Long> eventCursors = new HashMap<>();

        World world = new World(config);
        for (Messages.Hello hello : identities) {
            World.JoinResult joined = world.join(hello);
            sessions.put(hello.playerId(), joined);
            coordinates.put(hello.playerId(), joined.player().x + "," + joined.player().y);
            joined.player().protectionEndsEpochMillis = 0;
        }
        check(world.metrics().registeredPlayers() == PLAYER_COUNT
                && world.metrics().onlinePlayers() == PLAYER_COUNT, "all soak clients join concurrently");
        check(new HashSet<>(coordinates.values()).size() == PLAYER_COUNT,
                "all soak kingdoms receive unique persistent coordinates");

        populateSocialAndHouseState(world, identities);
        createWarsAndBattles(world, identities, sessions);

        Messages.Command replayedChat = command("soak-chat-000", "CHAT", "", "", 0, 0,
                "Persistent hello from player zero");
        World.SyncResult originalChat = update(world, identities.getFirst().playerId(), 50, replayedChat);
        checkAccepted(originalChat, "original replay-protected chat");
        int eventsBeforeReplay = world.metrics().retainedEvents();
        World.SyncResult immediateReplay = update(world, identities.getFirst().playerId(), 51, replayedChat);
        checkAccepted(immediateReplay, "immediate idempotent command replay");
        check(world.metrics().retainedEvents() == eventsBeforeReplay,
                "immediate command replay creates no duplicate event");

        int expectedReports = world.initialSync(identities.getFirst().playerId(), 0).battleReports().size();
        check(expectedReports >= 1, "soak war produces a persistent report for the participating kingdom");
        world.save();
        leaveAll(world, identities);

        for (int cycle = 1; cycle <= RESTART_CYCLES; cycle++) {
            world = new World(config);
            check(world.metrics().registeredPlayers() == PLAYER_COUNT && world.metrics().onlinePlayers() == 0,
                    "restart " + cycle + " restores registrations without ghost sessions");

            for (Messages.Hello identity : identities) {
                Messages.Hello reconnect = spoofFoundingNames(identity);
                World.JoinResult joined = world.join(reconnect);
                check(coordinates.get(identity.playerId()).equals(joined.player().x + "," + joined.player().y),
                        "restart " + cycle + " preserves coordinate for " + identity.playerId());
                check(identity.playerName().equals(joined.player().playerName)
                                && identity.kingdomName().equals(joined.player().kingdomName)
                                && identity.cityName().equals(joined.player().cityName),
                        "restart " + cycle + " preserves founding identity for " + identity.playerId());
            }

            World.SyncResult full = world.initialSync(identities.getFirst().playerId(), 0);
            check(full.worldState().cities().size() == PLAYER_COUNT
                            && full.houses().size() == HOUSE_COUNT
                            && full.wars().size() == HOUSE_COUNT / 2
                            && full.battleReports().size() == expectedReports,
                    "restart " + cycle + " restores cities, Houses, wars, and reports");

            for (int round = 0; round < SYNC_ROUNDS_PER_CYCLE; round++) {
                for (int player = 0; player < identities.size(); player++) {
                    Messages.Hello identity = identities.get(player);
                    long eventCursor = eventCursors.getOrDefault(identity.playerId(), 0L);
                    World.SyncResult sync = world.applyUpdate(identity.playerId(), new Messages.ClientUpdate(
                            cycle * 1_000L + round,
                            1_000 + cycle * 100 + player,
                            10_000L + round * 100L + player,
                            eventCursor,
                            0,
                            List.of()));
                    check(sync.worldState().cities().size() == PLAYER_COUNT,
                            "sync roster remains complete in cycle " + cycle);
                    eventCursors.put(identity.playerId(), Math.max(eventCursor, sync.eventCursor()));
                }
            }

            for (int player = 1; player < identities.size(); player += 2) {
                world.leave(identities.get(player).playerId());
            }
            check(world.metrics().onlinePlayers() == PLAYER_COUNT / 2,
                    "restart " + cycle + " tracks mass disconnects");
            for (int player = 1; player < identities.size(); player += 2) {
                world.join(spoofFoundingNames(identities.get(player)));
            }
            check(world.metrics().onlinePlayers() == PLAYER_COUNT,
                    "restart " + cycle + " accepts mass reconnects");

            if (cycle == 1) {
                int eventsBeforeRestartReplay = world.metrics().retainedEvents();
                World.SyncResult restartReplay = update(world, identities.getFirst().playerId(), 9_999, replayedChat);
                checkAccepted(restartReplay, "restart-safe idempotent command replay");
                check(world.metrics().retainedEvents() == eventsBeforeRestartReplay,
                        "command replay after restart creates no duplicate event");
            }

            world.save();
            leaveAll(world, identities);
        }

        World finalWorld = new World(config);
        check(finalWorld.metrics().registeredPlayers() == PLAYER_COUNT
                        && finalWorld.metrics().onlinePlayers() == 0
                        && finalWorld.metrics().houses() == HOUSE_COUNT
                        && finalWorld.metrics().wars() == HOUSE_COUNT / 2,
                "final persisted world has no lost or phantom strategic state");
        Set<String> finalCoordinates = new HashSet<>();
        for (Messages.City city : finalWorld.snapshot().cities()) {
            finalCoordinates.add(city.x() + "," + city.y());
        }
        check(finalCoordinates.equals(new HashSet<>(coordinates.values())),
                "all coordinates remain stable after the full soak");
        System.out.println("WorldSoakTest: PASS (" + PLAYER_COUNT + " clients, "
                + RESTART_CYCLES + " restarts, "
                + (long) PLAYER_COUNT * RESTART_CYCLES * SYNC_ROUNDS_PER_CYCLE + " sync updates)");
    }

    private static void populateSocialAndHouseState(World world, List<Messages.Hello> identities) throws Exception {
        for (int house = 0; house < HOUSE_COUNT; house++) {
            int leaderIndex = house * MEMBERS_PER_HOUSE;
            String leaderId = identities.get(leaderIndex).playerId();
            String houseId = String.format("soak-house-%02d", house);
            checkAccepted(update(world, leaderId, 1,
                    command(houseId, "CREATE_HOUSE", "", String.format("H%02d", house),
                            0, 0, "Soak House " + house)), "create House " + house);

            for (int offset = 1; offset < MEMBERS_PER_HOUSE; offset++) {
                String memberId = identities.get(leaderIndex + offset).playerId();
                String inviteId = String.format("soak-invite-%02d-%02d", house, offset);
                checkAccepted(update(world, leaderId, 2 + offset,
                        command(inviteId, "INVITE_HOUSE", memberId, "", 0, 0, "")),
                        "invite member " + memberId);
                checkAccepted(update(world, memberId, 1,
                        command(String.format("soak-accept-%02d-%02d", house, offset),
                                "ACCEPT_HOUSE_REQUEST", "", inviteId, 0, 0, "")),
                        "accept member " + memberId);
            }

            CrestDesign crest = CrestDesign.blank()
                    .withColor(1, 0x202020 + house * 0x080808)
                    .withColor(2, 0xE0A020 + house * 0x030405)
                    .withPixel(house, house, 1)
                    .withPixel(31 - house, 31 - house, 2);
            checkAccepted(update(world, leaderId, 10,
                    command(String.format("soak-crest-%02d", house), "SET_HOUSE_CREST", "",
                            crest.pixelsEncoded(), 0, 0, crest.paletteHex())),
                    "set House crest " + house);
        }

        for (int player = 0; player < identities.size(); player++) {
            Messages.Hello identity = identities.get(player);
            checkAccepted(update(world, identity.playerId(), 20,
                    command(String.format("soak-local-%03d", player), "LOCAL_CHAT", "", "", 0, 0,
                            "Local hello " + player)), "local chat " + player);
        }
    }

    private static void createWarsAndBattles(World world, List<Messages.Hello> identities,
            Map<String, World.JoinResult> sessions) throws Exception {
        List<String> attackIds = new ArrayList<>();
        long latestArrival = 0;
        for (int pair = 0; pair < HOUSE_COUNT / 2; pair++) {
            int attackerHouse = pair * 2;
            int defenderHouse = attackerHouse + 1;
            String attackerId = identities.get(attackerHouse * MEMBERS_PER_HOUSE).playerId();
            String defenderId = identities.get(defenderHouse * MEMBERS_PER_HOUSE).playerId();
            checkAccepted(update(world, attackerId, 30,
                    command(String.format("soak-war-%02d", pair), "DECLARE_HOUSE_WAR", "",
                            String.format("soak-house-%02d", defenderHouse), 0, 0, "")),
                    "declare soak war " + pair);
            world.resolveStrategicTime(System.currentTimeMillis() + 13L * 3_600_000L);
            World.JoinResult attacker = sessions.get(attackerId);
            World.JoinResult defender = sessions.get(defenderId);
            attacker.player().levies = 200;
            defender.player().levies = 10;
            String attackId = String.format("soak-attack-%02d", pair);
            World.SyncResult launched = update(world, attackerId, 31,
                    command(attackId, "SEND_ARMY", defenderId, "", 100, 0, ""));
            checkAccepted(launched, "launch soak attack " + pair);
            long arrival = launched.armies().stream().filter(army -> army.armyId().equals(attackId))
                    .findFirst().orElseThrow().arrivesEpochMillis();
            attacker.player().economyUpdatedEpochMillis = arrival;
            defender.player().economyUpdatedEpochMillis = arrival;
            latestArrival = Math.max(latestArrival, arrival);
            attackIds.add(attackId);
        }
        world.resolveArmies(latestArrival + 1);
        for (int pair = 0; pair < attackIds.size(); pair++) {
            String attackId = attackIds.get(pair);
            String attackerId = identities.get(pair * 2 * MEMBERS_PER_HOUSE).playerId();
            World.SyncResult reports = world.initialSync(attackerId, 0);
            check(reports.battleReports().stream().anyMatch(report -> report.reportId().equals(attackId)),
                    "battle report exists for " + attackId);
        }
    }

    private static World.SyncResult update(World world, String playerId, long sequence,
            Messages.Command command) throws Exception {
        return world.applyUpdate(playerId,
                new Messages.ClientUpdate(sequence, 0, 0, 0, 0, List.of(command)));
    }

    private static Messages.Command command(String id, String type, String target, String argument,
            long value1, long value2, String text) {
        return new Messages.Command(id, type, target, argument, value1, value2, text);
    }

    private static List<Messages.Hello> identities() {
        List<Messages.Hello> identities = new ArrayList<>(PLAYER_COUNT);
        for (int player = 0; player < PLAYER_COUNT; player++) {
            String id = String.format("soak-player-%03d", player);
            String token = String.format("%032x", player + 1L);
            identities.add(WorldPersistenceTest.hello(id, token,
                    String.format("Ruler %03d", player), String.format("Soak City %03d", player)));
        }
        return List.copyOf(identities);
    }

    private static Messages.Hello spoofFoundingNames(Messages.Hello original) {
        return new Messages.Hello(original.protocolVersion(), original.gameVersion(), original.modVersion(),
                original.modManifest(), 0, 0, original.worldId(), original.playerId(), original.authToken(),
                "Spoofed Ruler", "Spoofed Kingdom", "Spoofed City", "");
    }

    private static void leaveAll(World world, List<Messages.Hello> identities) {
        for (Messages.Hello identity : identities) {
            world.leave(identity.playerId());
        }
    }

    private static void checkAccepted(World.SyncResult result, String label) {
        check(result.commandResults().size() == 1 && result.commandResults().getFirst().accepted(),
                label + ": " + (result.commandResults().isEmpty()
                        ? "no command result" : result.commandResults().getFirst()));
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
