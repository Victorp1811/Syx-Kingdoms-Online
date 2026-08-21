package syxkingdoms.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProtocolTest {
    public static void main(String[] args) throws Exception {
        Messages.Hello hello = new Messages.Hello(Protocol.VERSION, "0.71.44", "0.13.0",
                "Syx Kingdoms Online@0.13.0", 0, 0, "world-one",
                "player-123", "a-secure-token-that-is-long-enough", "Ruler Name",
                "Northern Realm", "Cité 北", "Friendly Realm");
        Messages.Hello decoded = Messages.Hello.decode(hello.encode());
        equal(hello, decoded, "hello round trip");

        Messages.Welcome welcome = new Messages.Welcome(Protocol.VERSION, "world-one", "World One",
                12, 1000, 1.0, 32, 48, SharedWorld.WIDTH, SharedWorld.HEIGHT, "test-seed");
        equal(welcome, Messages.Welcome.decode(welcome.encode()), "welcome round trip");

        Messages.WorldState state = new Messages.WorldState(42, 123456789L, 1.0,
                SharedWorld.WIDTH, SharedWorld.HEIGHT, "test-seed", List.of(
                new Messages.City("p1", "Alice", "Oak Realm", "Oak", 0, 0,
                        123, 456, true, 1000, 9000),
                new Messages.City("p2", "Bob", "Ash Realm", "Ash", 1, 0,
                        987, -20, false, 2000, 0)));
        List<String> frames = Messages.WorldState.encodeFrames(state);
        Messages.WorldState.Header header = Messages.WorldState.decodeHeader(frames.getFirst());
        Messages.WorldState rebuilt = header.withCities(List.of(
                Messages.City.decode(frames.get(1)), Messages.City.decode(frames.get(2))));
        equal(state, rebuilt, "world state round trip");
        equal("STATE_END", frames.getLast(), "state terminator");

        Messages.Command command = new Messages.Command("command-123", "MAIL", "p2", "", 12, 34, "hello");
        equal(command, Messages.Command.decode(command.encode()), "command round trip");
        Messages.ClientUpdate update = new Messages.ClientUpdate(7, 100, 200, 6, 4, List.of(command));
        Messages.ClientUpdate.Header updateHeader = Messages.ClientUpdate.decodeHeader(update.header());
        equal(update, updateHeader.withCommands(List.of(command)), "client update round trip");
        Messages.CommandResult result = new Messages.CommandResult("command-123", true, "MAIL_SENT", "sent");
        equal(result, Messages.CommandResult.decode(result.encode()), "command result round trip");
        Messages.WorldEvent event = new Messages.WorldEvent(9, 1234, "MAIL", "p1", "Alice", "p2",
                "hello", "", 0, 0);
        equal(event, Messages.WorldEvent.decode(event.encode()), "event round trip");
        Messages.Relation relation = new Messages.Relation("p2", "Bob", "ALLY", "ALLY", "ALLY");
        equal(relation, Messages.Relation.decode(relation.encode()), "relation round trip");
        Messages.PlayerState playerState = new Messages.PlayerState(999, 88, 9000);
        equal(playerState, Messages.PlayerState.decode(playerState.encode()), "player state round trip");
        Messages.Army army = new Messages.Army("army-123", "p1", "p2", 50, 1000, 2000);
        equal(army, Messages.Army.decode(army.encode()), "army round trip");
        Messages.Delivery delivery = new Messages.Delivery("delivery-123", "p1", "WOOD", 25, 3000);
        equal(delivery, Messages.Delivery.decode(delivery.encode()), "delivery round trip");
        Messages.MarketOffer offer = new Messages.MarketOffer("market-offer-123", "p1", "Alice",
                "WOOD", 100, 3, 4000);
        equal(offer, Messages.MarketOffer.decode(offer.encode()), "market offer round trip");
        Messages.OffersHeader offers = new Messages.OffersHeader(12);
        equal(offers, Messages.OffersHeader.decode(offers.encode()), "offers header round trip");

        CrestDesign crest = CrestDesign.blank()
                .withColor(1, 0x123456)
                .withColor(15, 0xFEDCBA)
                .withPixel(0, 0, 1)
                .withPixel(31, 31, 15);
        equal(crest, CrestDesign.decode(crest.pixelsEncoded(), crest.paletteHex()), "crest design round trip");
        equal(683, crest.pixelsEncoded().length(), "32x32 packed crest size");
        equal(96, crest.paletteHex().length(), "16-color crest palette size");
        Messages.Crest crestMessage = new Messages.Crest(3, "p1", crest.hash(),
                crest.pixelsEncoded(), crest.paletteHex());
        equal(crestMessage, Messages.Crest.decode(crestMessage.encode()), "crest frame round trip");
        equal(crest.mirrorHorizontal().pixel(31, 0), 1, "horizontal crest mirror");
        equal(crest.mirrorVertical().pixel(31, 0), 15, "vertical crest mirror");
        Messages.House house = new Messages.House("house-123", "Oak Council", "OAK", "p1",
                "Stand together", 2, 1, 2, 500, crest.hash(), crest.pixelsEncoded(), crest.paletteHex());
        equal(house, Messages.House.decode(house.encode()), "House frame round trip");
        Messages.HouseMember member = new Messages.HouseMember("p1", "house-123", "SOVEREIGN", 5000);
        equal(member, Messages.HouseMember.decode(member.encode()), "House member round trip");
        Messages.HouseRequest request = new Messages.HouseRequest("request-123", "house-123", "p2",
                "INVITE", 5000, 6000);
        equal(request, Messages.HouseRequest.decode(request.encode()), "House request round trip");
        Messages.Province province = new Messages.Province(1, 2, "house-123", "house-456", 75, 8000, 900);
        equal(province, Messages.Province.decode(province.encode()), "province round trip");
        Messages.HouseWar war = new Messages.HouseWar("war-12345", "house-123", "house-456",
                "ACTIVE", 1000, 2000, 3000, 10, 8, 0, "");
        equal(war, Messages.HouseWar.decode(war.encode()), "House war round trip");
        Messages.BattleReport report = new Messages.BattleReport("report-123", 4000, "p1", "p2",
                "war-12345", "ATTACKER_WIN", 100, 60, 40, 0, 200, 1, 2, 12);
        equal(report, Messages.BattleReport.decode(report.encode()), "battle report round trip");
        Messages.Season season = new Messages.Season(2, 1000, 3000, "house-123", "ACTIVE");
        equal(season, Messages.Season.decode(season.encode()), "season round trip");

        SharedWorldTerrain terrain = new SharedWorldTerrain("test-seed");
        SharedWorldTerrain sameTerrain = new SharedWorldTerrain("test-seed");
        SharedWorldTerrain otherTerrain = new SharedWorldTerrain("other-seed");
        equal(terrain.fingerprint(), sameTerrain.fingerprint(), "deterministic terrain fingerprint");
        equal(false, terrain.fingerprint().equals(otherTerrain.fingerprint()), "seed changes terrain fingerprint");
        int buildable = 0;
        int water = 0;
        int changed = 0;
        for (int y = 0; y < SharedWorldTerrain.GRID_HEIGHT; y++) {
            for (int x = 0; x < SharedWorldTerrain.GRID_WIDTH; x++) {
                SharedWorldTerrain.Terrain cell = terrain.cell(x, y);
                equal(cell, sameTerrain.cell(x, y), "deterministic terrain cell");
                if (cell == SharedWorldTerrain.Terrain.PLAINS || cell == SharedWorldTerrain.Terrain.FOREST) {
                    buildable++;
                }
                if (cell == SharedWorldTerrain.Terrain.WATER) {
                    water++;
                }
                if (cell != otherTerrain.cell(x, y)) {
                    changed++;
                }
            }
        }
        equal(true, buildable > 0, "terrain has buildable land");
        equal(true, water > 0, "terrain has water");
        equal(true, changed > 0, "terrain seed changes geography");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineIO.writeLine(output, hello.encode());
        String line = LineIO.readLine(new ByteArrayInputStream(output.toByteArray()));
        equal(hello.encode(), line, "line I/O round trip");

        expectProtocol(() -> Messages.Heartbeat.decode("HEARTBEAT\t0\t-1\t0"), "negative population");
        expectProtocol(() -> Messages.Hello.decode("HELLO\t1"), "truncated hello");
        expectProtocol(() -> Messages.Command.decode("COMMAND\tshort\tCHAT\t\t\t0\t0\t"), "bad command ID");
        expectProtocol(() -> Messages.Relation.decode("RELATION\tcDI\tQm9i\tALLY\tALLY\tINVALID"), "bad stance");
        expectProtocol(() -> Protocol.decodeText("%%%", "bad"), "invalid base64url");
        expectProtocol(() -> CrestDesign.decode("00", crest.paletteHex()), "bad crest dimensions");
        String badHashFrame = new Messages.Crest(3, "p1", "0".repeat(64),
                crest.pixelsEncoded(), crest.paletteHex()).encode();
        expectProtocol(() -> Messages.Crest.decode(badHashFrame), "mismatched crest hash");

        byte[] oversized = ("x".repeat(Protocol.MAX_LINE_BYTES + 1) + "\n").getBytes(StandardCharsets.UTF_8);
        expectProtocol(() -> LineIO.readLine(new ByteArrayInputStream(oversized)), "oversized frame");
        System.out.println("ProtocolTest: PASS");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectProtocol(Throwing action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError(label + ": expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
