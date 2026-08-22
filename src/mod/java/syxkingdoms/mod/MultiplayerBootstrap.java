package syxkingdoms.mod;

import game.GAME;
import game.faction.FACTIONS;
import snake2d.CORE;
import snake2d.LOG;
import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.SharedWorld;
import view.main.VIEW;
import view.subview.GameWindow;
import view.world.generator.WorldViewGenerator;
import world.WORLD;
import world.WorldGen;
import world.map.regions.Region;
import world.map.regions.centre.WorldCentrePlacablity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replaces V71's single-player world wizard after race selection.
 *
 * <p>Every client loads the same prebuilt 1000x1000 native V71 world. The server
 * owns settlement claims and online systems while Songs of Syx owns terrain,
 * rendering, zooming, and panning. Native generation is retained only for the
 * developer-only template export workflow.</p>
 */
final class MultiplayerBootstrap {
    private static final String CANONICAL_MAP = "Syx_Kingdoms_Online";
    private static final Field RACE_SELECTED = field("hasSeletedRace");
    private static final Field PROFILE_SELECTED = field("hasProfiled");
    private static final Field TITLES_SELECTED = field("hasSelectedTitles");
    private static final Method GENERATOR_RESET = method(WorldViewGenerator.class, "reset");
    private static final Field GENERATOR_WINDOW = field(WorldViewGenerator.class, "window");
    private static final Class<?> STAGE_CAPITOL = type("view.world.generator.StageCapitol");
    private static final Method CAPITOL_CLEAR = method(STAGE_CAPITOL, "clear");
    private static final Method CAPITOL_GENERATE = method(STAGE_CAPITOL, "generate");

    private boolean running;
    private boolean nativeWorldPrepared;
    private boolean completed;
    private String phase = "Waiting for the multiplayer server";
    private String failure = "";

    boolean generatorActive() {
        return VIEW.current() instanceof WorldViewGenerator;
    }

    boolean waitingForSpecies() {
        if (!(VIEW.current() instanceof WorldViewGenerator generator)) {
            return false;
        }
        try {
            return !RACE_SELECTED.getBoolean(generator);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("Could not read the V71 race-selection stage", e);
            return false;
        }
    }

    boolean completed() {
        return completed;
    }

    boolean nativeWorldPrepared() {
        return nativeWorldPrepared;
    }

    String phase() {
        return phase;
    }

    String failure() {
        return failure;
    }

    void advance(OnlineClient client) {
        if (running || completed || !failure.isBlank()
                || !(VIEW.current() instanceof WorldViewGenerator generator) || client == null) {
            return;
        }
        if (waitingForSpecies() || !failure.isBlank()) {
            phase = "Choose the species that will inhabit your capital";
            return;
        }
        if (!nativeWorldPrepared && isCanonicalNativeWorld()) {
            running = true;
            try {
                activateCanonicalNativeWorld(generator);
            } catch (Throwable e) {
                fail("The fixed 1000x1000 V71 world could not be loaded", e);
            } finally {
                running = false;
            }
            if (!failure.isBlank()) {
                return;
            }
        }
        if (!nativeWorldPrepared) {
            running = true;
            try {
                prepareNativeWorld(generator);
            } catch (Throwable e) {
                fail("The fixed 1000x1000 V71 world could not be generated", e);
            } finally {
                running = false;
            }
            return;
        }
        if (!client.simulationReady()) {
            phase = "Choose a capital site on the persistent native world";
            return;
        }
        Messages.City ownCity = client.worldState().cities().stream()
                .filter(city -> city.playerId().equals(client.ownPlayerId()))
                .findFirst().orElse(null);
        if (ownCity == null) {
            phase = "Synchronizing your persistent kingdom";
            return;
        }

        running = true;
        try {
            bootstrap(generator, ownCity);
            completed = true;
            phase = "Entering " + ownCity.cityName();
        } catch (Throwable e) {
            fail("The canonical V71 city shard could not be generated", e);
        } finally {
            running = false;
        }
    }

    private void bootstrap(WorldViewGenerator generator, Messages.City ownCity) throws Exception {
        phase = "Applying your persistent kingdom profile";
        setNativeNames(ownCity);
        PROFILE_SELECTED.setBoolean(generator, true);
        TITLES_SELECTED.setBoolean(generator, true);

        if (!nativeWorldPrepared) {
            prepareNativeWorld(generator);
        }
        WorldGen world = WORLD.GEN();
        GENERATOR_RESET.invoke(generator);

        phase = "Claiming the server-approved capital site";
        int[] capital = findCapitalSite(ownCity.x(), ownCity.y());
        world.playerX = capital[0] + 1;
        world.playerY = capital[1] + 1;

        if (world.isDone) {
            /*
             * The packaged online world already contains V71's regions, roads,
             * pathing, factions, armies, and statistics. Attach this client to
             * the existing native region nearest its server-owned city instead
             * of regenerating the shared world on every join.
             */
            phase = "Attaching your kingdom to the prebuilt shared world";
            attachPlayerCapital(world, capital[0], capital[1], ownCity.kingdomName());
        } else {
            /*
             * Developer-only template creation. V71's settlement generator
             * needs the complete stock finalizer: road entry points plus army,
             * faction, mercenary, and statistics scaffolding. This expensive
             * pass is persisted in the canonical template and never repeated
             * by normal multiplayer joins.
             */
            phase = "Finalizing the one prebuilt world: regions and roads";
            CAPITOL_GENERATE.invoke(null);

            phase = "Finalizing the one prebuilt world: factions and armies";
            GAME.factions().prime();
            WORLD.ARMIES().saver().generate(WorldViewGenerator.loadPrint);
            neutralizeNativeFactions();
            world.isDone = true;
            exportWorldTemplateIfRequested(world);
        }
        WORLD.FOW().toggled.set(true);

        phase = "Building the local city shard";
        VIEW.world().activate();
        GAME.s().CreateFromWorldMap(world.playerX - 1, world.playerY - 1, false);
        setNativeNames(ownCity);
        GAME.saver().saveNew();
        CORE.getInput().clearAllInput();
    }

    /** Moves the native player realm onto the region containing its server city. */
    private static void attachPlayerCapital(WorldGen world, int preferredX, int preferredY,
            CharSequence kingdomName) {
        Region target = findNativeRegion(preferredX, preferredY);
        Region previous = FACTIONS.player().capitolRegion();
        game.faction.Faction displaced = target.faction();

        if (target != previous) {
            target.fationSet(FACTIONS.player(), false);
            target.setCapitol();
            if (previous != null) {
                previous.fationSet(displaced, false);
                if (displaced != null && displaced != FACTIONS.player()) {
                    previous.setCapitol();
                }
            }
        }
        target.info.name().clear().add(kingdomName);
        world.playerX = target.cx() + 1;
        world.playerY = target.cy() + 1;
        if (FACTIONS.player().capitolRegion() != target) {
            throw new IllegalStateException("V71 did not retain the attached online capital region");
        }
    }

    private static Region findNativeRegion(int preferredX, int preferredY) {
        int maxRadius = Math.max(WORLD.TWIDTH(), WORLD.THEIGHT());
        for (int radius = 0; radius < maxRadius; radius++) {
            int minX = Math.max(0, preferredX - radius);
            int maxX = Math.min(WORLD.TWIDTH() - 1, preferredX + radius);
            int minY = Math.max(0, preferredY - radius);
            int maxY = Math.min(WORLD.THEIGHT() - 1, preferredY + radius);
            for (int x = minX; x <= maxX; x++) {
                Region top = WORLD.REGIONS().map.get(x, minY);
                if (top != null && top.active()) return top;
                Region bottom = WORLD.REGIONS().map.get(x, maxY);
                if (bottom != null && bottom.active()) return bottom;
            }
            for (int y = minY + 1; y < maxY; y++) {
                Region left = WORLD.REGIONS().map.get(minX, y);
                if (left != null && left.active()) return left;
                Region right = WORLD.REGIONS().map.get(maxX, y);
                if (right != null && right.active()) return right;
            }
        }
        throw new IllegalStateException("the prebuilt world contains no native region near the server city");
    }

    /** Keeps native regions as pathing data but removes their single-player owners. */
    private static void neutralizeNativeFactions() {
        Region playerCapital = FACTIONS.player().capitolRegion();
        for (Region region : WORLD.REGIONS().all()) {
            if (region != playerCapital && region.faction() != null) {
                region.fationSet(null, false);
            }
        }
        WORLD.MINIMAP().repaint();
    }

    private void prepareNativeWorld(WorldViewGenerator generator) throws Exception {
        if (isCanonicalNativeWorld()) {
            activateCanonicalNativeWorld(generator);
            return;
        }
        phase = "Generating the fixed 1000x1000 Songs of Syx world";
        PROFILE_SELECTED.setBoolean(generator, true);
        TITLES_SELECTED.setBoolean(generator, true);
        WorldGen world = WORLD.GEN();
        if (world.playerX >= 0) {
            CAPITOL_CLEAR.invoke(null);
        }
        GENERATOR_RESET.invoke(generator);
        world.seed = SharedWorld.LOCAL_SHARD_SEED;
        world.lat = 0.5;
        world.map = CANONICAL_MAP;
        world.playerX = -1;
        world.playerY = -1;
        world.hasGeneratedTerrain = false;
        world.isDone = false;
        WORLD.TERRAIN().saver().generate(WorldViewGenerator.loadPrint);
        WORLD.LANDMARKS().saver().generate(WorldViewGenerator.loadPrint);
        WorldViewGenerator.loadPrint.exe();
        WORLD.MINIMAP().repaint();
        world.hasGeneratedTerrain = true;
        GENERATOR_RESET.invoke(generator);
        nativeWorldPrepared = true;
        phase = "Choose a capital site on the persistent native world";
    }

    /** Clears the template's native faction/banner modal before multiplayer founding. */
    private void activateCanonicalNativeWorld(WorldViewGenerator generator) throws Exception {
        PROFILE_SELECTED.setBoolean(generator, true);
        TITLES_SELECTED.setBoolean(generator, true);
        FACTIONS.player().bonusesCustom.clear();
        GENERATOR_RESET.invoke(generator);
        WORLD.MINIMAP().repaint();
        nativeWorldPrepared = true;
        phase = "Choose a capital site on the persistent native world";
    }

    private static boolean isCanonicalNativeWorld() {
        WorldGen world = WORLD.GEN();
        return WORLD.TWIDTH() == SharedWorld.WIDTH
                && WORLD.THEIGHT() == SharedWorld.HEIGHT
                && world.hasGeneratedTerrain
                && CANONICAL_MAP.equals(world.map)
                && world.seed == SharedWorld.LOCAL_SHARD_SEED;
    }

    private static void exportWorldTemplateIfRequested(WorldGen world) {
        String exportDirectory = System.getProperty("syxkingdoms.exportWorldDirectory", "").trim();
        if (exportDirectory.isEmpty()) {
            return;
        }
        boolean wasEditing = world.isEditing;
        try {
            Path directory = Path.of(exportDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            world.isEditing = false;
            System.setProperty("syxkingdoms.worldTemplateWrite", "true");
            Path saved;
            try {
                saved = GAME.saver().save(directory, "syx-kingdoms-native-world-1000-v1", false);
            } finally {
                System.clearProperty("syxkingdoms.worldTemplateWrite");
            }
            if (saved == null || !Files.isRegularFile(saved)) {
                throw new IllegalStateException("V71 did not produce the requested world template");
            }
            LOG.ln("[Syx Kingdoms] Exported native online world: " + saved);
            System.clearProperty("syxkingdoms.exportWorldDirectory");
        } catch (Exception e) {
            throw new IllegalStateException("Could not export the native online world template", e);
        } finally {
            world.isEditing = wasEditing;
        }
    }

    int hoveredWorldX() {
        GameWindow window = generatorWindow();
        return window == null ? -1 : window.tile().x();
    }

    int hoveredWorldY() {
        GameWindow window = generatorWindow();
        return window == null ? -1 : window.tile().y();
    }

    int[] screenPoint(int worldX, int worldY) {
        GameWindow window = generatorWindow();
        if (window == null) {
            return null;
        }
        int pixelX = worldX * 64 + 32;
        int pixelY = worldY * 64 + 32;
        int screenX = window.view().x1() + ((pixelX - window.pixels().x1()) >> window.zoomout());
        int screenY = window.view().y1() + ((pixelY - window.pixels().y1()) >> window.zoomout());
        if (!window.view().holdsPoint(screenX, screenY)) {
            return null;
        }
        return new int[] { screenX, screenY };
    }

    boolean worldViewportContains(int screenX, int screenY) {
        GameWindow window = generatorWindow();
        return window != null && window.view().holdsPoint(screenX, screenY);
    }

    boolean canFoundCapital(int worldX, int worldY) {
        return worldX >= 1 && worldY >= 1 && worldX < WORLD.TWIDTH() - 2
                && worldY < WORLD.THEIGHT() - 2 && validCapital(worldX, worldY);
    }

    private GameWindow generatorWindow() {
        if (!(VIEW.current() instanceof WorldViewGenerator generator)) {
            return null;
        }
        try {
            return (GameWindow) GENERATOR_WINDOW.get(generator);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("Could not access the native V71 world viewport", e);
            return null;
        }
    }

    private static void setNativeNames(Messages.City ownCity) {
        FACTIONS.player().rulerName.clear().add(ownCity.playerName());
        FACTIONS.player().name.clear().add(ownCity.kingdomName());
    }

    private static int[] findCapitalSite(int preferredX, int preferredY) {
        int centreX = Math.max(1, Math.min(WORLD.TWIDTH() - 3, preferredX));
        int centreY = Math.max(1, Math.min(WORLD.THEIGHT() - 3, preferredY));
        int maxRadius = Math.max(WORLD.TWIDTH(), WORLD.THEIGHT());
        for (int radius = 0; radius < maxRadius; radius++) {
            int minX = Math.max(1, centreX - radius);
            int maxX = Math.min(WORLD.TWIDTH() - 3, centreX + radius);
            int minY = Math.max(1, centreY - radius);
            int maxY = Math.min(WORLD.THEIGHT() - 3, centreY + radius);
            for (int x = minX; x <= maxX; x++) {
                if (validCapital(x, minY)) return new int[] { x, minY };
                if (maxY != minY && validCapital(x, maxY)) return new int[] { x, maxY };
            }
            for (int y = minY + 1; y < maxY; y++) {
                if (validCapital(minX, y)) return new int[] { minX, y };
                if (maxX != minX && validCapital(maxX, y)) return new int[] { maxX, y };
            }
        }
        throw new IllegalStateException("fixed terrain contains no valid capital site");
    }

    private static boolean validCapital(int x, int y) {
        try {
            return WorldCentrePlacablity.terrain(x, y) == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void fail(String message, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String detail = cause.getMessage();
        StackTraceElement origin = cause.getStackTrace().length == 0 ? null : cause.getStackTrace()[0];
        if (origin != null) {
            detail = (detail == null || detail.isBlank() ? cause.getClass().getSimpleName() : detail)
                    + " @ " + origin.getClassName() + "." + origin.getMethodName()
                    + ":" + origin.getLineNumber();
        }
        failure = message + (detail == null || detail.isBlank()
                ? " (" + cause.getClass().getSimpleName() + ")" : ": " + detail);
        phase = failure;
        LOG.err("[Syx Kingdoms] " + failure);
        error.printStackTrace();
        writeDiagnostic(error);
    }

    private static void writeDiagnostic(Throwable error) {
        String directory = System.getProperty("syxkingdoms.exportWorldDirectory", "").trim();
        if (directory.isEmpty()) {
            return;
        }
        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            Files.createDirectories(Path.of(directory));
            Files.writeString(Path.of(directory).resolve("last-bootstrap-error.txt"), trace.toString());
        } catch (Exception diagnosticError) {
            LOG.err("[Syx Kingdoms] Could not write bootstrap diagnostic: " + diagnosticError.getMessage());
        }
    }

    private static Field field(String name) {
        return field(WorldViewGenerator.class, name);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Method method(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
