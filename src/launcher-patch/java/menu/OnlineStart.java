package menu;

import game.GAME;
import game.faction.FACTIONS;
import game.save.GameLoader;
import init.constant.Config;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/** Starts an unmodified game instance with neutral bonuses and forced online scripts. */
final class OnlineStart {
    private OnlineStart() {
    }

    static void start(ScMain main) {
        try {
            Field field = ScMain.class.getDeclaredField("menu");
            field.setAccessible(true);
            Menu menu = (Menu) field.get(main);
            menu.start(() -> {
                forceOnlineWorldSize();
                Path template = worldTemplate();
                var view = loadPackagedWorld(template);
                FACTIONS.player().bonusesCustom.clear();
                return view;
            });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not start the Syx Kingdoms online game mode", e);
        }
    }

    private static snake2d.CORE_STATE loadPackagedWorld(Path template) {
        if (Files.isRegularFile(template)) {
            return new GameLoader(template).getState();
        }
        if (!System.getProperty("syxkingdoms.exportWorldDirectory", "").isBlank()) {
            return GAME.create(new String[0]);
        }
        throw new IllegalStateException("The Syx Kingdoms Online world is missing: " + template
                + ". Reinstall the standalone online package.");
    }

    private static Path worldTemplate() {
        try {
            Path agent = Path.of(OnlineStart.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            Path directory = Files.isDirectory(agent) ? agent : agent.getParent();
            return directory.resolve("world").resolve("syx-kingdoms-native-world-1000-v1.save");
        } catch (Exception e) {
            throw new IllegalStateException("Could not locate the packaged online world", e);
        }
    }

    /** The online agent patches V71's stock 512-tile ceiling before this value is consumed. */
    private static void forceOnlineWorldSize() {
        try {
            Object worldConfig = Config.world();
            Field worldSize = worldConfig.getClass().getDeclaredField("WORLD_SIZE");
            worldSize.setAccessible(true);
            worldSize.setInt(worldConfig, 1000);
            if (Config.world().WORLD_SIZE != 1000) {
                throw new IllegalStateException("V71 world-size override was not applied");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not select the 1000x1000 online world", e);
        }
    }
}
