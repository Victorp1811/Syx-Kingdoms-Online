package menu;

import snake2d.util.misc.ACTION;

/** Replacement for V71's root Play action, installed only by the online agent. */
final class ScMain$1 implements ACTION {
    final ScMain this$0;

    ScMain$1(ScMain owner) {
        this.this$0 = owner;
    }

    @Override
    public void exe() {
        OnlineStart.start(this$0);
    }
}
