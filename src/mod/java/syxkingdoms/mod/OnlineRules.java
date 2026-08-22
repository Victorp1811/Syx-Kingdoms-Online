package syxkingdoms.mod;

import game.GAME;
import game.faction.FACTIONS;
import init.trade.TR;
import init.trade.TRADABLE;
import snake2d.KEYCODES;
import snake2d.KeyBoard;
import syxkingdoms.protocol.SharedWorld;
import view.keyboard.KEYS;
import view.keyboard.KeyPoller;
import view.main.VIEW;
import view.world.generator.WorldViewGenerator;
import world.WORLD;

import java.lang.reflect.Field;

/** Enforces the parts of the online ruleset exposed by the public script API. */
final class OnlineRules {
    private static final Field GENERATOR_TITLE_FLAG = generatorTitleFlag();
    private final OverlayTextInput textInput;
    private final KeyPoller inputFilter;
    private boolean generatorTitlesHandled;
    private boolean selectedTitleWarningShown;

    OnlineRules(OverlayTextInput textInput) {
        this.textInput = textInput;
        this.inputFilter = events -> {
            boolean capture = textInput.capturesGameplayKeys();
            boolean toggleWorldView = false;
            if (capture) {
                for (int index = 0; index < events.size(); index++) {
                    KeyBoard.KeyEvent event = events.get(index);
                    if (event.code() == KEYCODES.KEY_ESCAPE
                            && event.action() == KeyBoard.KEYACTION.PRESS
                            && textInput.active()) {
                        textInput.cancel();
                    }
                }
            } else {
                for (int index = 0; index < events.size(); index++) {
                    KeyBoard.KeyEvent event = events.get(index);
                    if (event.code() == KEYCODES.KEY_TAB
                            && event.action() == KeyBoard.KEYACTION.PRESS) {
                        toggleWorldView = true;
                    }
                }
                KEYS.get().poll(events);
                if (toggleWorldView) {
                    // VIEW normally consumes MAIN.SWAP during its next update. The online
                    // input filter is re-armed every frame, so handle the physical Tab
                    // event now and consume the deferred binding to prevent a double swap.
                    KEYS.MAIN().SWAP.consumeClick();
                    toggleWorldView();
                }
            }
            consumeRestrictedKeys();
        };
    }

    void enforce(boolean simulationReady) {
        armInputFilter();
        enforceSpeed(simulationReady);
        disableVanillaTitles();
        disableVanillaTrade();
    }

    void enforceFrame(boolean simulationReady) {
        armInputFilter();
        enforceSpeed(simulationReady);
    }

    String runtimeViolation() {
        try {
            if (GAME.script().currentScripts().length != 1) {
                return "SCRIPT_SET_MISMATCH: no additional Songs of Syx scripts may run with online mode";
            }
            if (WORLD.GEN().isEditing) {
                return "WORLD_EDITOR_BLOCKED: the vanilla world editor is not permitted in an online kingdom";
            }
        } catch (RuntimeException ignored) {
            return "WORLD_CONTRACT_UNAVAILABLE: the local world could not be verified";
        }
        return "";
    }

    private void armInputFilter() {
        textInput.listenForFrame();
        VIEW.setKeyPoller(inputFilter);
    }

    private static void consumeRestrictedKeys() {
        KEYS.MAIN().PAUSE.consumeClick();
        KEYS.MAIN().SPEED1.consumeClick();
        KEYS.MAIN().SPEED2.consumeClick();
        KEYS.MAIN().SPEED3.consumeClick();
        KEYS.MAIN().QUICKLOAD.consumeClick();
        KEYS.MAIN().DEBUGGER.consumeClick();
    }

    private static void toggleWorldView() {
        try {
            if (VIEW.world().isActive()) {
                VIEW.s().activate();
            } else if (VIEW.s().isActive()) {
                VIEW.world().activate();
            }
        } catch (RuntimeException error) {
            GAME.WarnLight("The multiplayer world map could not be opened");
        }
    }

    private static void enforceSpeed(boolean simulationReady) {
        double required = simulationReady ? SharedWorld.FIXED_GAME_SPEED : 0.0;
        if (Double.compare(GAME.SPEED.speedTarget(), required) != 0
                || Double.compare(GAME.SPEED.speed(), required) != 0) {
            GAME.SPEED.speedSet(required);
        }
    }

    private static void disableVanillaTrade() {
        for (TRADABLE tradable : TR.ALL()) {
            if (tradable.pb().limit.get() != 0) {
                tradable.pb().limit.set(0);
            }
            if (tradable.ps().limit.get() != 0) {
                tradable.ps().limit.set(0);
            }
        }
    }

    private void disableVanillaTitles() {
        boolean removed = false;
        try {
            for (var title : FACTIONS.player().titles.all()) {
                if (title.selected()) {
                    title.select(false);
                    removed = true;
                }
            }
        } catch (RuntimeException ignored) {
            // The player faction is not available during the earliest loading frames.
        }
        if (removed && !selectedTitleWarningShown) {
            selectedTitleWarningShown = true;
            GAME.WarnLight("Vanilla title bonuses are disabled in Syx Kingdoms Online");
        }
        skipGeneratorTitleSelection();
    }

    private void skipGeneratorTitleSelection() {
        if (generatorTitlesHandled || GENERATOR_TITLE_FLAG == null
                || !(VIEW.current() instanceof WorldViewGenerator generator)) {
            return;
        }
        try {
            generatorTitlesHandled = true;
            if (!GENERATOR_TITLE_FLAG.getBoolean(generator)) {
                GENERATOR_TITLE_FLAG.setBoolean(generator, true);
                generator.set();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            generatorTitlesHandled = false;
        }
    }

    private static Field generatorTitleFlag() {
        try {
            Field field = WorldViewGenerator.class.getDeclaredField("hasSelectedTitles");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
