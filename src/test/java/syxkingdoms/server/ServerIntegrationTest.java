package syxkingdoms.server;

import syxkingdoms.protocol.LineIO;
import syxkingdoms.protocol.Messages;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ServerIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("syx-kingdoms-server-test-");
        try (KingdomsServer server = new KingdomsServer(WorldPersistenceTest.config(temp.resolve("world.properties")))) {
            server.start();
            try (TestClient alice = TestClient.connect(server.port(),
                    WorldPersistenceTest.hello("alice-id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alice", "Oakhold"));
                 TestClient bob = TestClient.connect(server.port(),
                    WorldPersistenceTest.hello("bob-id", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Bob", "Ashkeep"))) {
                WorldPersistenceTest.check(alice.initial.cities().size() == 1, "first initial state");
                WorldPersistenceTest.check(bob.initial.cities().size() == 2, "second initial state");

                Sync aliceSync = alice.update(1, 321, 9000, List.of());
                Messages.WorldState aliceView = aliceSync.world();
                WorldPersistenceTest.check(aliceView.cities().size() == 2, "alice sees two cities");
                WorldPersistenceTest.check(aliceView.cities().stream().filter(Messages.City::online).count() == 2,
                        "alice sees two online cities");
                Messages.City aliceCity = aliceView.cities().stream()
                        .filter(city -> city.playerId().equals("alice-id")).findFirst().orElseThrow();
                WorldPersistenceTest.check(aliceCity.population() == 321 && aliceCity.credits() == 9000,
                        "server applies heartbeat stats");

                Messages.WorldState bobView = bob.update(1, 654, 8000, List.of()).world();
                WorldPersistenceTest.check(bobView.cities().stream()
                        .anyMatch(city -> city.playerId().equals("bob-id") && city.population() == 654),
                        "bob heartbeat round trip");

                Messages.Command chat = command("chat-command", "CHAT", "", "", "Hello persistent world");
                Sync chatAccepted = alice.update(2, 321, 9000, List.of(chat));
                WorldPersistenceTest.check(chatAccepted.results().size() == 1
                        && chatAccepted.results().getFirst().accepted(), "chat command accepted");
                WorldPersistenceTest.check(chatAccepted.events().stream()
                        .anyMatch(event -> "CHAT".equals(event.type()) && event.text().equals("Hello persistent world")),
                        "chat event returned to actor");
                Sync bobSeesChat = bob.update(2, 654, 8000, List.of());
                WorldPersistenceTest.check(bobSeesChat.events().stream()
                        .anyMatch(event -> "CHAT".equals(event.type()) && event.actorPlayerId().equals("alice-id")),
                        "global chat visible to other player");

                Messages.Command mail = command("mail-command", "MAIL", "bob-id", "", "For Bob only");
                alice.update(3, 321, 9000, List.of(mail));
                Sync bobMail = bob.update(3, 654, 8000, List.of());
                WorldPersistenceTest.check(bobMail.events().stream()
                        .anyMatch(event -> "MAIL".equals(event.type()) && event.text().equals("For Bob only")),
                        "private mail visible to target");

                Messages.Command aliceAlly = command("alice-ally", "STANCE", "bob-id", "ALLY", "");
                Messages.Command bobAlly = command("bob-ally", "STANCE", "alice-id", "ALLY", "");
                alice.update(4, 321, 9000, List.of(aliceAlly));
                Sync allied = bob.update(4, 654, 8000, List.of(bobAlly));
                Messages.Relation aliceRelation = allied.relations().stream()
                        .filter(relation -> relation.playerId().equals("alice-id")).findFirst().orElseThrow();
                WorldPersistenceTest.check("ALLY".equals(aliceRelation.effectiveStance()),
                        "mutual alliance becomes effective");

                Sync replay = alice.update(5, 321, 9000, List.of(chat));
                WorldPersistenceTest.check(replay.results().size() == 1 && replay.results().getFirst().accepted(),
                        "replayed command receives idempotent result");
                WorldPersistenceTest.check(replay.events().stream()
                        .noneMatch(event -> event.eventId() > chatAccepted.eventCursor()
                                && "CHAT".equals(event.type()) && event.text().equals("Hello persistent world")),
                        "replayed command does not duplicate event");

                Messages.Command shipment = new Messages.Command("shipment-wood", "SEND_RESOURCE",
                        "bob-id", "WOOD", 25, 0, "");
                Sync shipmentSent = alice.update(6, 321, 9000, List.of(shipment));
                WorldPersistenceTest.check(shipmentSent.results().getFirst().accepted()
                        && shipmentSent.events().stream().anyMatch(event -> "SHIPMENT_SENT".equals(event.type())),
                        "resource shipment accepted and recorded");

                Messages.Command transfer = new Messages.Command("transfer-crowns", "TRANSFER_CROWNS",
                        "bob-id", "", 50, 0, "");
                Sync transferred = alice.update(7, 321, 9000, List.of(transfer));
                WorldPersistenceTest.check(transferred.playerState().crowns() == 950,
                        "server-owned crowns are debited by transfer");

                Messages.Command createOffer = new Messages.Command("wire-offer-0001", "CREATE_OFFER",
                        "", "STONE", 40, 2, "");
                Sync offerCreated = alice.update(8, 321, 9000, List.of(createOffer));
                WorldPersistenceTest.check(offerCreated.results().getFirst().accepted()
                        && offerCreated.marketOffers().stream().anyMatch(offer -> offer.offerId().equals("wire-offer-0001")),
                        "market offer is created and synchronized over the wire");
                Sync bobMarket = bob.update(5, 654, 8000, List.of());
                WorldPersistenceTest.check(bobMarket.marketOffers().size() == 1,
                        "other player sees public market offer");
                Messages.Command buyOffer = new Messages.Command("wire-buy-00001", "BUY_OFFER",
                        "", "wire-offer-0001", 10, 0, "");
                Sync bought = bob.update(6, 654, 8000, List.of(buyOffer));
                WorldPersistenceTest.check(bought.results().getFirst().accepted()
                        && bought.playerState().crowns() == 1030
                        && bought.marketOffers().getFirst().quantity() == 30,
                        "market purchase transfers crowns and partially fills offer over the wire");
                Sync replayedBuy = bob.update(7, 654, 8000, List.of(buyOffer));
                WorldPersistenceTest.check(replayedBuy.results().getFirst().accepted()
                        && replayedBuy.playerState().crowns() == 1030
                        && replayedBuy.marketOffers().getFirst().quantity() == 30,
                        "replayed market purchase cannot charge crowns or fill goods twice");

                Messages.Command declareWar = command("alice-war", "STANCE", "bob-id", "WAR", "");
                alice.update(9, 321, 9000, List.of(declareWar));
                Messages.Command sendArmy = new Messages.Command("send-army-01", "SEND_ARMY",
                        "bob-id", "", 10, 0, "");
                Sync marching = alice.update(10, 321, 9000, List.of(sendArmy));
                WorldPersistenceTest.check(marching.playerState().levies() == 90,
                        "server-owned levies are committed to army");
                WorldPersistenceTest.check(marching.armies().stream()
                        .anyMatch(army -> army.armyId().equals("send-army-01") && army.troops() == 10),
                        "traveling army appears in sync");
            }
        }
        System.out.println("ServerIntegrationTest: PASS");
    }

    private static Messages.Command command(String id, String type, String target, String argument, String text) {
        return new Messages.Command(id, type, target, argument, 0, 0, text);
    }

    private record Sync(Messages.WorldState world, Messages.PlayerState playerState,
            List<Messages.CommandResult> results, long eventCursor, List<Messages.WorldEvent> events,
            List<Messages.Relation> relations, List<Messages.Army> armies,
            List<Messages.Delivery> deliveries, List<Messages.MarketOffer> marketOffers) {
    }

    private static final class TestClient implements AutoCloseable {
        final Socket socket;
        final Messages.Welcome welcome;
        final Messages.WorldState initial;
        long eventCursor;

        private TestClient(Socket socket, Messages.Welcome welcome, Sync initialSync) {
            this.socket = socket;
            this.welcome = welcome;
            this.initial = initialSync.world();
            this.eventCursor = initialSync.eventCursor();
        }

        static TestClient connect(int port, Messages.Hello hello) throws Exception {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            LineIO.writeLine(socket.getOutputStream(), hello.encode());
            Messages.Welcome welcome = Messages.Welcome.decode(LineIO.readLine(socket.getInputStream()));
            return new TestClient(socket, welcome, readSync(socket));
        }

        Sync update(long sequence, int population, long credits, List<Messages.Command> commands) throws Exception {
            Messages.ClientUpdate update = new Messages.ClientUpdate(sequence, population, credits,
                    eventCursor, commands);
            LineIO.writeLine(socket.getOutputStream(), update.header());
            for (Messages.Command command : commands) {
                LineIO.writeLine(socket.getOutputStream(), command.encode());
            }
            Sync sync = readSync(socket);
            eventCursor = sync.eventCursor();
            return sync;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }

        private static Sync readSync(Socket socket) throws Exception {
            Messages.WorldState.Header header = Messages.WorldState.decodeHeader(LineIO.readLine(socket.getInputStream()));
            List<Messages.City> cities = new ArrayList<>();
            for (int i = 0; i < header.cityCount(); i++) {
                cities.add(Messages.City.decode(LineIO.readLine(socket.getInputStream())));
            }
            if (!"STATE_END".equals(LineIO.readLine(socket.getInputStream()))) {
                throw new AssertionError("missing STATE_END");
            }
            Messages.WorldState world = header.withCities(cities);
            Messages.PlayerState playerState = Messages.PlayerState.decode(LineIO.readLine(socket.getInputStream()));
            Messages.ResultsHeader resultsHeader = Messages.ResultsHeader.decode(LineIO.readLine(socket.getInputStream()));
            List<Messages.CommandResult> results = new ArrayList<>();
            for (int i = 0; i < resultsHeader.resultCount(); i++) {
                results.add(Messages.CommandResult.decode(LineIO.readLine(socket.getInputStream())));
            }
            Messages.EventsHeader eventsHeader = Messages.EventsHeader.decode(LineIO.readLine(socket.getInputStream()));
            List<Messages.WorldEvent> events = new ArrayList<>();
            for (int i = 0; i < eventsHeader.eventCount(); i++) {
                events.add(Messages.WorldEvent.decode(LineIO.readLine(socket.getInputStream())));
            }
            Messages.RelationsHeader relationsHeader = Messages.RelationsHeader.decode(
                    LineIO.readLine(socket.getInputStream()));
            List<Messages.Relation> relations = new ArrayList<>();
            for (int i = 0; i < relationsHeader.relationCount(); i++) {
                relations.add(Messages.Relation.decode(LineIO.readLine(socket.getInputStream())));
            }
            Messages.ArmiesHeader armiesHeader = Messages.ArmiesHeader.decode(LineIO.readLine(socket.getInputStream()));
            List<Messages.Army> armies = new ArrayList<>();
            for (int i = 0; i < armiesHeader.armyCount(); i++) {
                armies.add(Messages.Army.decode(LineIO.readLine(socket.getInputStream())));
            }
            Messages.DeliveriesHeader deliveriesHeader = Messages.DeliveriesHeader.decode(
                    LineIO.readLine(socket.getInputStream()));
            List<Messages.Delivery> deliveries = new ArrayList<>();
            for (int i = 0; i < deliveriesHeader.deliveryCount(); i++) {
                deliveries.add(Messages.Delivery.decode(LineIO.readLine(socket.getInputStream())));
            }
            Messages.OffersHeader offersHeader = Messages.OffersHeader.decode(
                    LineIO.readLine(socket.getInputStream()));
            List<Messages.MarketOffer> marketOffers = new ArrayList<>();
            for (int i = 0; i < offersHeader.offerCount(); i++) {
                marketOffers.add(Messages.MarketOffer.decode(LineIO.readLine(socket.getInputStream())));
            }
            if (!"SYNC_END".equals(LineIO.readLine(socket.getInputStream()))) {
                throw new AssertionError("missing SYNC_END");
            }
            return new Sync(world, playerState, List.copyOf(results), eventsHeader.cursor(),
                    List.copyOf(events), List.copyOf(relations), List.copyOf(armies),
                    List.copyOf(deliveries), List.copyOf(marketOffers));
        }
    }
}
