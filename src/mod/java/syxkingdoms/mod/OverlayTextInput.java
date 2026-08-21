package syxkingdoms.mod;

import snake2d.Input;

/**
 * Frame-driven text entry for the multiplayer overlay.
 *
 * <p>Songs of Syx clears its raw character listener after every input poll, so
 * the runtime must call {@link #listenForFrame()} from every update while this
 * prompt is active. Unlike the native request-input dialog, this listener does
 * not suspend the settlement simulation.</p>
 */
final class OverlayTextInput extends Input.CHAR_LISTENER {
    @FunctionalInterface
    interface Submission {
        void accept(String value);
    }

    private static final long KEY_CAPTURE_GRACE_MILLIS = 250L;

    private String title = "";
    private Submission submission;
    private Runnable cancellation;
    private boolean active;
    private long captureGameplayKeysUntil;

    OverlayTextInput() {
        super(1_024);
    }

    void open(String title, String initialValue, Submission submission, Runnable cancellation) {
        if (submission == null) {
            throw new IllegalArgumentException("A text-input submission handler is required");
        }
        this.title = title == null ? "Multiplayer input" : title.trim();
        this.submission = submission;
        this.cancellation = cancellation;
        set(initialValue == null ? "" : initialValue);
        active = true;
        captureGameplayKeysUntil = Long.MAX_VALUE;
        listen();
    }

    void listenForFrame() {
        if (active) {
            listen();
        }
    }

    boolean active() {
        return active;
    }

    String title() {
        return title;
    }

    String value() {
        return text().toString();
    }

    boolean capturesGameplayKeys() {
        return active || System.currentTimeMillis() <= captureGameplayKeysUntil;
    }

    void submit() {
        if (!active) {
            return;
        }
        String submittedValue = value();
        Submission handler = submission;
        close();
        handler.accept(submittedValue);
    }

    void cancel() {
        if (!active) {
            return;
        }
        Runnable handler = cancellation;
        close();
        if (handler != null) {
            handler.run();
        }
    }

    private void close() {
        active = false;
        submission = null;
        cancellation = null;
        captureGameplayKeysUntil = System.currentTimeMillis() + KEY_CAPTURE_GRACE_MILLIS;
    }

    @Override
    protected void acceptChar(char character) {
        if (active) {
            super.acceptChar(character);
        }
    }

    @Override
    protected void backspace() {
        if (active) {
            super.backspace();
        }
    }

    @Override
    public void del() {
        if (active) {
            super.del();
        }
    }

    @Override
    protected void enter() {
        submit();
    }

    @Override
    protected void change() {
        // Rendering reads the listener's current buffer directly.
    }
}
