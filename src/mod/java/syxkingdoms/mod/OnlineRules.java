package syxkingdoms.mod;

import game.GAME;
import init.trade.TR;
import init.trade.TRADABLE;
import snake2d.KEYCODES;
import snake2d.KeyBoard;
import syxkingdoms.protocol.SharedWorld;
import view.keyboard.KEYS;
import view.keyboard.KeyPoller;
import view.main.VIEW;
import world.WORLD;

/** Enforces the parts of the online ruleset exposed by the public script API. */
final class OnlineRules {
    private final OverlayTextInput textInput;
    private final KeyPoller inputFilter;
    private long nextWorldWarningAt;

    OnlineRules(OverlayTextInput textInput) {
        this.textInput = textInput;
        this.inputFilter = events -> {
            boolean capture = textInput.capturesGameplayKeys();
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
                KEYS.get().poll(events);
            }
            consumeRestrictedKeys();
        };
    }

    void enforce(boolean simulationReady) {
        armInputFilter();
        enforceSpeed(simulationReady);
        blockVanillaWorldScreen();
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
        KEYS.MAIN().SWAP.consumeClick();
    }

    private static void enforceSpeed(boolean simulationReady) {
        double required = simulationReady ? SharedWorld.FIXED_GAME_SPEED : 0.0;
        if (Double.compare(GAME.SPEED.speedTarget(), required) != 0
                || Double.compare(GAME.SPEED.speed(), required) != 0) {
            GAME.SPEED.speedSet(required);
        }
    }

    private void blockVanillaWorldScreen() {
        if (!VIEW.world().isActive()) {
            return;
        }
        VIEW.s().activate();
        long now = System.currentTimeMillis();
        if (now >= nextWorldWarningAt) {
            nextWorldWarningAt = now + 5_000L;
            GAME.WarnLight("The vanilla world screen is disabled; use the persistent multiplayer map");
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
}
