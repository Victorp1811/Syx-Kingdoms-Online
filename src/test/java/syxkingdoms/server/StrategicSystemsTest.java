package syxkingdoms.server;

import syxkingdoms.protocol.CrestDesign;
import syxkingdoms.protocol.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class StrategicSystemsTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("syx-kingdoms-strategy-test-");
        World world = new World(WorldPersistenceTest.config(temp.resolve("world.properties")));
        World.JoinResult alice = world.join(WorldPersistenceTest.hello("alice-strategy",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alice", "Oakspire"));
        World.JoinResult bob = world.join(WorldPersistenceTest.hello("bob-strategy",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Bob", "Birchwall"));
        World.JoinResult charlie = world.join(WorldPersistenceTest.hello("charlie-strategy",
                "cccccccccccccccccccccccccccccccc", "Charlie", "Cedarwatch"));

        World.SyncResult created = command(world, "alice-strategy", 1,
                cmd("house-create-001", "CREATE_HOUSE", "", "OAK", 0, 0, "Oak Council"));
        checkAccepted(created, "House creation");
        String oakHouseId = "house-create-001";
        World.SyncResult invited = command(world, "alice-strategy", 2,
                cmd("house-invite-01", "INVITE_HOUSE", "bob-strategy", "", 0, 0, ""));
        checkAccepted(invited, "House invitation");
        World.SyncResult bobInbox = world.initialSync("bob-strategy", 0);
        WorldPersistenceTest.check(bobInbox.houseRequests().stream().anyMatch(request ->
                request.requestId().equals("house-invite-01") && "INVITE".equals(request.type())),
                "invited player sees invitation");
        checkAccepted(command(world, "bob-strategy", 1,
                cmd("invite-accept-01", "ACCEPT_HOUSE_REQUEST", "", "house-invite-01", 0, 0, "")),
                "invitation acceptance");

        checkAccepted(command(world, "charlie-strategy", 1,
                cmd("charlie-apply-01", "APPLY_HOUSE", "", oakHouseId, 0, 0, "")),
                "House application");
        World.SyncResult aliceRequests = world.initialSync("alice-strategy", 0);
        WorldPersistenceTest.check(aliceRequests.houseRequests().stream().anyMatch(request ->
                request.requestId().equals("charlie-apply-01") && "APPLICATION".equals(request.type())),
                "House officer sees application");
        checkAccepted(command(world, "alice-strategy", 3,
                cmd("apply-accept-001", "ACCEPT_HOUSE_REQUEST", "", "charlie-apply-01", 0, 0, "")),
                "application acceptance");
        checkAccepted(command(world, "alice-strategy", 4,
                cmd("role-marshal-001", "SET_HOUSE_ROLE", "bob-strategy", "MARSHAL", 0, 0, "")),
                "Marshal assignment");
        checkAccepted(command(world, "alice-strategy", 5,
                cmd("role-herald-0001", "SET_HOUSE_ROLE", "charlie-strategy", "HERALD", 0, 0, "")),
                "Herald assignment");
        checkAccepted(command(world, "alice-strategy", 6,
                cmd("house-announce-01", "HOUSE_ANNOUNCEMENT", "", "", 0, 0, "Stand together")),
                "House announcement");

        CrestDesign crest = CrestDesign.blank().withColor(1, 0x2255AA).withColor(2, 0xFFCC22)
                .withPixel(0, 0, 1).withPixel(31, 31, 2);
        checkAccepted(command(world, "charlie-strategy", 2,
                cmd("house-crest-0001", "SET_HOUSE_CREST", "", crest.pixelsEncoded(), 0, 0,
                        crest.paletteHex())), "Herald House crest edit");
        World.SyncResult houseState = world.initialSync("alice-strategy", 0);
        Messages.House oak = houseState.houses().stream().filter(house -> house.houseId().equals(oakHouseId))
                .findFirst().orElseThrow();
        WorldPersistenceTest.check(oak.memberCount() == 3 && "Stand together".equals(oak.announcement())
                && crest.hash().equals(oak.crestHash()), "House roster, announcement, and crest synchronize");

        world.join(WorldPersistenceTest.hello("evan-strategy",
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "Evan", "Elmrest"));
        checkAccepted(command(world, "alice-strategy", 7,
                cmd("invite-evan-001", "INVITE_HOUSE", "evan-strategy", "", 0, 0, "")),
                "temporary member invitation");
        checkAccepted(command(world, "evan-strategy", 1,
                cmd("evan-accept-0001", "ACCEPT_HOUSE_REQUEST", "", "invite-evan-001", 0, 0, "")),
                "temporary member joins");
        checkAccepted(command(world, "alice-strategy", 8,
                cmd("evan-kick-00001", "KICK_HOUSE", "evan-strategy", "", 0, 0, "")),
                "member kick");
        World.SyncResult cooldown = command(world, "evan-strategy", 2,
                cmd("evan-reapply-001", "APPLY_HOUSE", "", oakHouseId, 0, 0, ""));
        WorldPersistenceTest.check(!cooldown.commandResults().getFirst().accepted()
                && "HOUSE_COOLDOWN".equals(cooldown.commandResults().getFirst().code()),
                "kick starts 24-hour House-change cooldown");

        World.SyncResult houseChat = command(world, "bob-strategy", 2,
                cmd("house-chat-0001", "HOUSE_CHAT", "", "", 0, 0, "For the Council"));
        checkAccepted(houseChat, "House chat");
        World.JoinResult dave = world.join(WorldPersistenceTest.hello("dave-strategy",
                "dddddddddddddddddddddddddddddddd", "Dave", "Dunefort"));
        World.SyncResult daveHistory = world.initialSync("dave-strategy", 0);
        WorldPersistenceTest.check(daveHistory.events().stream().noneMatch(event -> "HOUSE_CHAT".equals(event.type())),
                "House chat is invisible outside the House");
        WorldPersistenceTest.check(world.initialSync("alice-strategy", 0).events().stream().anyMatch(event ->
                "HOUSE_CHAT".equals(event.type()) && "For the Council".equals(event.text())),
                "House chat is visible to House members");

        alice.player().levies = 40;
        World.SyncResult reinforcement = command(world, "bob-strategy", 3,
                cmd("reinforce-alice", "SEND_REINFORCEMENT", "alice-strategy", "", 30, 0, ""));
        checkAccepted(reinforcement, "House reinforcement launch");
        long reinforcementArrival = reinforcement.armies().stream()
                .filter(army -> army.armyId().equals("reinforce-alice")).findFirst().orElseThrow().arrivesEpochMillis();
        alice.player().economyUpdatedEpochMillis = reinforcementArrival;
        bob.player().economyUpdatedEpochMillis = reinforcementArrival;
        world.resolveArmies(reinforcementArrival + 1);
        World.SyncResult reinforced = world.initialSync("alice-strategy", reinforcement.eventCursor());
        WorldPersistenceTest.check(reinforced.playerState().levies() == 70
                && reinforced.battleReports().stream().anyMatch(report -> "REINFORCED".equals(report.outcome())),
                "reinforcement arrives in server-owned levy balance and creates a report");

        World.SyncResult rivalCreated = command(world, "dave-strategy", 1,
                cmd("rival-house-001", "CREATE_HOUSE", "", "RIV", 0, 0, "Rival Pact"));
        checkAccepted(rivalCreated, "rival House creation");
        World.SyncResult legacyWar = command(world, "alice-strategy", 7,
                cmd("legacy-war-0001", "STANCE", "dave-strategy", "WAR", 0, 0, ""));
        WorldPersistenceTest.check(!legacyWar.commandResults().getFirst().accepted()
                && "FORMAL_WAR_REQUIRED".equals(legacyWar.commandResults().getFirst().code()),
                "instant WAR stance is disabled");
        World.SyncResult declared = command(world, "bob-strategy", 4,
                cmd("formal-war-0001", "DECLARE_HOUSE_WAR", "", "rival-house-001", 0, 0, ""));
        checkAccepted(declared, "Marshal formal war declaration");
        WorldPersistenceTest.check("PREPARING".equals(declared.wars().getFirst().phase())
                && declared.wars().getFirst().startsEpochMillis() - declared.wars().getFirst().declaredEpochMillis()
                == 12L * 3_600_000L, "war has exact 12-hour preparation");
        World.SyncResult premature = command(world, "alice-strategy", 8,
                cmd("premature-army", "SEND_ARMY", "dave-strategy", "", 10, 0, ""));
        WorldPersistenceTest.check(!premature.commandResults().getFirst().accepted()
                && "NOT_AT_WAR".equals(premature.commandResults().getFirst().code()),
                "preparation phase blocks combat");

        world.resolveStrategicTime(System.currentTimeMillis() + 13L * 3_600_000L);
        alice.player().levies = 100;
        dave.player().levies = 10;
        dave.player().crowns = 10_000;
        World.SyncResult attack = command(world, "alice-strategy", 9,
                cmd("active-army-001", "SEND_ARMY", "dave-strategy", "", 100, 0, ""));
        checkAccepted(attack, "active-war army launch");
        long arrival = attack.armies().stream().filter(army -> army.armyId().equals("active-army-001"))
                .findFirst().orElseThrow().arrivesEpochMillis();
        alice.player().economyUpdatedEpochMillis = arrival;
        dave.player().economyUpdatedEpochMillis = arrival;
        world.resolveArmies(arrival + 1);
        World.SyncResult battle = world.initialSync("alice-strategy", attack.eventCursor());
        Messages.BattleReport report = battle.battleReports().stream()
                .filter(item -> item.reportId().equals("active-army-001")).findFirst().orElseThrow();
        WorldPersistenceTest.check("ATTACKER_WIN".equals(report.outcome()) && report.crownsLooted() > 0
                && battle.playerState().levies() == 90, "battle returns survivors and applies capped loot");
        Messages.Province contested = battle.provinces().stream()
                .filter(province -> province.x() == report.provinceX() && province.y() == report.provinceY())
                .findFirst().orElseThrow();
        WorldPersistenceTest.check(oakHouseId.equals(contested.occupierHouseId())
                && contested.occupationInfluence() > 0 && contested.blockadeEndsEpochMillis() > arrival,
                "victory creates occupation influence and blockade");
        World.SyncResult earlyAnnex = command(world, "alice-strategy", 10,
                cmd("early-annex-001", "ANNEX_PROVINCE", "", "",
                        report.provinceX(), report.provinceY(), ""));
        WorldPersistenceTest.check(!earlyAnnex.commandResults().getFirst().accepted()
                && "ANNEXATION_NOT_READY".equals(earlyAnnex.commandResults().getFirst().code()),
                "annexation requires full occupation influence");
        World.SyncResult earlyTribute = command(world, "alice-strategy", 11,
                cmd("early-tribute-01", "COLLECT_TRIBUTE", "", "",
                        report.provinceX(), report.provinceY(), ""));
        WorldPersistenceTest.check(!earlyTribute.commandResults().getFirst().accepted()
                && "NOT_OCCUPIER".equals(earlyTribute.commandResults().getFirst().code()),
                "tribute requires full occupation");

        for (int i = 2; i <= 3; i++) {
            alice.player().levies = 100;
            dave.player().levies = 0;
            String id = "active-army-00" + i;
            World.SyncResult repeated = command(world, "alice-strategy", 9 + i,
                    cmd(id, "SEND_ARMY", "dave-strategy", "", 100, 0, ""));
            checkAccepted(repeated, "repeat-target attack " + i);
            long repeatedArrival = repeated.armies().stream().filter(army -> army.armyId().equals(id))
                    .findFirst().orElseThrow().arrivesEpochMillis();
            alice.player().economyUpdatedEpochMillis = repeatedArrival;
            dave.player().economyUpdatedEpochMillis = repeatedArrival;
            world.resolveArmies(repeatedArrival + 1);
        }
        World.SyncResult beforeLimitedAttack = world.initialSync("alice-strategy", 0);
        int influenceBeforeLimit = beforeLimitedAttack.provinces().stream()
                .filter(province -> province.x() == report.provinceX() && province.y() == report.provinceY())
                .findFirst().orElseThrow().occupationInfluence();
        alice.player().levies = 100;
        dave.player().levies = 0;
        World.SyncResult fourthAttack = command(world, "alice-strategy", 13,
                cmd("active-army-004", "SEND_ARMY", "dave-strategy", "", 100, 0, ""));
        long fourthArrival = fourthAttack.armies().stream().filter(army -> army.armyId().equals("active-army-004"))
                .findFirst().orElseThrow().arrivesEpochMillis();
        alice.player().economyUpdatedEpochMillis = fourthArrival;
        dave.player().economyUpdatedEpochMillis = fourthArrival;
        world.resolveArmies(fourthArrival + 1);
        World.SyncResult limitedBattle = world.initialSync("alice-strategy", 0);
        Messages.BattleReport limitedReport = limitedBattle.battleReports().stream()
                .filter(item -> item.reportId().equals("active-army-004")).findFirst().orElseThrow();
        int influenceAfterLimit = limitedBattle.provinces().stream()
                .filter(province -> province.x() == report.provinceX() && province.y() == report.provinceY())
                .findFirst().orElseThrow().occupationInfluence();
        WorldPersistenceTest.check(limitedReport.warScore() == 0 && limitedReport.crownsLooted() == 0
                && influenceAfterLimit == influenceBeforeLimit,
                "fourth same-day target is loot-, score-, and occupation-limited");

        Messages.Province home = battle.provinces().stream().filter(province -> oakHouseId.equals(province.ownerHouseId()))
                .findFirst().orElseThrow();
        int claimX = home.x() < 9 ? home.x() + 1 : home.x() - 1;
        int claimY = home.y();
        Messages.Province claimCandidate = battle.provinces().stream()
                .filter(province -> province.x() == claimX && province.y() == claimY).findFirst().orElseThrow();
        if (claimCandidate.ownerHouseId().isBlank()) {
            checkAccepted(command(world, "bob-strategy", 5,
                    cmd("province-claim-01", "CLAIM_PROVINCE", "", "", claimX, claimY, "")),
                    "adjacent province claim");
        }

        World.SyncResult surrendered = command(world, "dave-strategy", 2,
                cmd("war-surrender-01", "SURRENDER_HOUSE_WAR", "", "formal-war-0001", 0, 0, ""));
        checkAccepted(surrendered, "war surrender");
        Messages.HouseWar truce = surrendered.wars().stream().filter(war -> war.warId().equals("formal-war-0001"))
                .findFirst().orElseThrow();
        WorldPersistenceTest.check("TRUCE".equals(truce.phase())
                && truce.truceEndsEpochMillis() > System.currentTimeMillis(),
                "surrender creates 72-hour truce without deleting a city");
        WorldPersistenceTest.check(world.snapshot().cities().stream().anyMatch(city -> city.playerId().equals("dave-strategy")),
                "defeated city persists");
        checkAccepted(command(world, "alice-strategy", 10,
                cmd("transfer-leader", "SET_HOUSE_ROLE", "bob-strategy", "SOVEREIGN", 0, 0, "")),
                "Sovereign leadership transfer");

        int previousSeason = surrendered.season().number();
        world.resolveStrategicTime(System.currentTimeMillis() + 31L * 86_400_000L);
        World.SyncResult nextSeason = world.initialSync("alice-strategy", surrendered.eventCursor());
        WorldPersistenceTest.check(nextSeason.season().number() == previousSeason + 1
                && !nextSeason.season().winnerHouseId().isBlank(), "season ends with a persistent winner and advances");
        world.save();
        world.leave("alice-strategy");
        world.leave("bob-strategy");
        world.leave("charlie-strategy");
        world.leave("dave-strategy");
        world.leave("evan-strategy");

        World restored = new World(WorldPersistenceTest.config(temp.resolve("world.properties")));
        restored.join(WorldPersistenceTest.hello("alice-strategy", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alice", "Oakspire"));
        World.SyncResult restoredState = restored.initialSync("alice-strategy", 0);
        WorldPersistenceTest.check(restoredState.houses().stream().anyMatch(item -> item.houseId().equals(oakHouseId)
                && item.memberCount() == 3 && item.sovereignPlayerId().equals("bob-strategy")
                && crest.hash().equals(item.crestHash())), "House roster, leadership, and crest survive restart");
        WorldPersistenceTest.check(restoredState.battleReports().stream().anyMatch(item ->
                item.reportId().equals("active-army-001")) && restoredState.season().number() == previousSeason + 1,
                "battle report and season survive restart");
        System.out.println("StrategicSystemsTest: PASS");
    }

    private static World.SyncResult command(World world, String playerId, long sequence,
            Messages.Command command) throws Exception {
        return world.applyUpdate(playerId, new Messages.ClientUpdate(sequence, 0, 0, 0, 0, List.of(command)));
    }

    private static Messages.Command cmd(String id, String type, String target, String argument,
            long value1, long value2, String text) {
        return new Messages.Command(id, type, target, argument, value1, value2, text);
    }

    private static void checkAccepted(World.SyncResult result, String label) {
        WorldPersistenceTest.check(result.commandResults().size() == 1
                && result.commandResults().getFirst().accepted(), label + ": "
                + (result.commandResults().isEmpty() ? "no result" : result.commandResults().getFirst()));
    }
}
