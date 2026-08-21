package syxkingdoms.server;

import syxkingdoms.protocol.LineIO;
import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.ProtocolException;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KingdomsServer implements AutoCloseable {
    private final ServerConfig config;
    private final World world;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "syx-kingdoms-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private long nextMetricsEpochMillis;

    public KingdomsServer(ServerConfig config) throws IOException {
        this.config = config;
        this.world = new World(config);
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(config.bindAddress()), config.port()));
        acceptThread = Thread.ofPlatform().name("syx-kingdoms-accept").daemon(true).start(this::acceptLoop);
        maintenance.scheduleWithFixedDelay(this::saveWorld,
                config.saveIntervalMillis(), config.saveIntervalMillis(), TimeUnit.MILLISECONDS);
        log("Listening on " + config.bindAddress() + ":" + port() + " for world '" + config.worldId() + "'");
        log("Persistent world: " + config.worldFile());
        logMetrics();
    }

    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket socket = serverSocket.accept();
                clients.submit(() -> handle(socket));
            }
        } catch (SocketException e) {
            if (running.get()) {
                log("Accept loop failed: " + e.getMessage());
            }
        } catch (IOException e) {
            if (running.get()) {
                log("Accept loop failed: " + e.getMessage());
            }
        } finally {
            if (running.get()) {
                close();
            }
        }
    }

    private void handle(Socket socket) {
        String playerId = null;
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, config.heartbeatTimeoutMillis()));

            Messages.Hello hello = Messages.Hello.decode(LineIO.readLine(socket.getInputStream()));
            World.JoinResult joined = world.join(hello);
            playerId = joined.player().playerId;
            LineIO.writeLine(socket.getOutputStream(), joined.welcome().encode());
            sendSync(socket, world.initialSync(playerId, hello.lastEventId(), hello.lastCrestRevision()));
            log("Joined " + joined.player().playerName + " / " + joined.player().cityName + " from " + remote);

            long lastSequence = -1;
            while (running.get()) {
                Messages.ClientUpdate.Header header = Messages.ClientUpdate.decodeHeader(
                        LineIO.readLine(socket.getInputStream()));
                if (header.sequence() <= lastSequence) {
                    throw new ProtocolException("REPLAYED_UPDATE", "Update sequence must increase within a session");
                }
                lastSequence = header.sequence();
                List<Messages.Command> commands = new ArrayList<>(header.commandCount());
                for (int i = 0; i < header.commandCount(); i++) {
                    commands.add(Messages.Command.decode(LineIO.readLine(socket.getInputStream())));
                }
                Messages.ClientUpdate update = header.withCommands(commands);
                World.SyncResult sync = world.applyUpdate(playerId, update);
                for (Messages.CommandResult result : sync.commandResults()) {
                    log("Command player=" + playerId + " id=" + result.commandId()
                            + " accepted=" + result.accepted() + " code=" + result.code());
                }
                sendSync(socket, sync);
            }
        } catch (SocketTimeoutException e) {
            sendErrorQuietly(socket, "HEARTBEAT_TIMEOUT", "No heartbeat arrived before the session timeout");
        } catch (EOFException ignored) {
            // Normal client disconnect.
        } catch (ProtocolException e) {
            sendErrorQuietly(socket, e.code(), e.getMessage());
            log("Rejected " + remote + ": " + e.code() + " - " + e.getMessage());
        } catch (IOException e) {
            if (running.get()) {
                log("Connection error for " + remote + ": " + e.getMessage());
            }
        } catch (RuntimeException e) {
            sendErrorQuietly(socket, "SERVER_ERROR", "Unexpected server error");
            log("Unexpected connection error for " + remote + ": " + e);
        } finally {
            world.leave(playerId);
            if (playerId != null) {
                log("Left " + playerId + " from " + remote);
            }
        }
    }

    private static void sendSync(Socket socket, World.SyncResult sync) throws IOException {
        List<String> frames = Messages.WorldState.encodeFrames(sync.worldState());
        for (String frame : frames) {
            LineIO.writeLine(socket.getOutputStream(), frame);
        }
        LineIO.writeLine(socket.getOutputStream(), sync.playerState().encode());
        LineIO.writeLine(socket.getOutputStream(), new Messages.ResultsHeader(sync.commandResults().size()).encode());
        for (Messages.CommandResult result : sync.commandResults()) {
            LineIO.writeLine(socket.getOutputStream(), result.encode());
        }
        LineIO.writeLine(socket.getOutputStream(),
                new Messages.EventsHeader(sync.eventCursor(), sync.events().size()).encode());
        for (Messages.WorldEvent event : sync.events()) {
            LineIO.writeLine(socket.getOutputStream(), event.encode());
        }
        LineIO.writeLine(socket.getOutputStream(),
                new Messages.CrestsHeader(sync.crestCursor(), sync.crests().size()).encode());
        for (Messages.Crest crest : sync.crests()) {
            LineIO.writeLine(socket.getOutputStream(), crest.encode());
        }
        LineIO.writeLine(socket.getOutputStream(), new Messages.RelationsHeader(sync.relations().size()).encode());
        for (Messages.Relation relation : sync.relations()) {
            LineIO.writeLine(socket.getOutputStream(), relation.encode());
        }
        LineIO.writeLine(socket.getOutputStream(), new Messages.ArmiesHeader(sync.armies().size()).encode());
        for (Messages.Army army : sync.armies()) {
            LineIO.writeLine(socket.getOutputStream(), army.encode());
        }
        LineIO.writeLine(socket.getOutputStream(),
                new Messages.DeliveriesHeader(sync.deliveries().size()).encode());
        for (Messages.Delivery delivery : sync.deliveries()) {
            LineIO.writeLine(socket.getOutputStream(), delivery.encode());
        }
        LineIO.writeLine(socket.getOutputStream(),
                new Messages.OffersHeader(sync.marketOffers().size()).encode());
        for (Messages.MarketOffer offer : sync.marketOffers()) {
            LineIO.writeLine(socket.getOutputStream(), offer.encode());
        }
        LineIO.writeLine(socket.getOutputStream(), "SYNC_END");
    }

    private static void sendErrorQuietly(Socket socket, String code, String message) {
        try {
            if (socket != null && !socket.isClosed()) {
                LineIO.writeLine(socket.getOutputStream(), new Messages.Error(code, message).encode());
            }
        } catch (Exception ignored) {
        }
    }

    private void saveWorld() {
        try {
            world.maintenance();
            world.saveIfDirty();
            if (System.currentTimeMillis() >= nextMetricsEpochMillis) {
                logMetrics();
            }
        } catch (IOException e) {
            log("Could not save world: " + e.getMessage());
        }
    }

    private void logMetrics() {
        nextMetricsEpochMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        World.Metrics metrics = world.metrics();
        log("Status registered=" + metrics.registeredPlayers()
                + " online=" + metrics.onlinePlayers()
                + " events=" + metrics.retainedEvents()
                + " armies=" + metrics.armies()
                + " shipments=" + metrics.shipments()
                + " deliveries=" + metrics.deliveries()
                + " offers=" + metrics.marketOffers());
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        maintenance.shutdownNow();
        clients.shutdownNow();
        try {
            world.save();
        } catch (IOException e) {
            log("Final world save failed: " + e.getMessage());
        }
        stopped.countDown();
        log("Server stopped");
    }

    private static void log(String message) {
        System.out.println("[" + Instant.now() + "] [Syx Kingdoms] " + message);
    }
}
