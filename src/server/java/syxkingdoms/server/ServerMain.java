package syxkingdoms.server;

import java.nio.file.Path;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("server/server.properties");
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = Path.of(args[++i]);
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                System.out.println("Usage: java -jar syx-kingdoms-server.jar [--config path]");
                return;
            } else {
                throw new IllegalArgumentException("Unknown or incomplete argument: " + args[i]);
            }
        }

        ServerConfig config = ServerConfig.load(configPath.toAbsolutePath().normalize());
        KingdomsServer server = new KingdomsServer(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
        server.start();
        server.awaitStopped();
    }
}
