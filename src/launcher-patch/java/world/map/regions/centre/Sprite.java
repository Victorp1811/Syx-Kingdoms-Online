package world.map.regions.centre;

import world.WRenContext;

/**
 * Online-only replacement for V71's native region-centre renderer.
 *
 * <p>The prebuilt world retains native regions internally because settlement
 * roads, minerals, statistics, and pathing depend on them. Their stock town
 * sprites are not online cities, though, so the dedicated launcher suppresses
 * them. Syx Kingdoms renders only server-authoritative player and NPC city
 * markers over the native world.</p>
 */
public final class Sprite {
    public Sprite() {
    }

    public void renderGround(WRenContext context) {
    }

    public void renderAbove(WRenContext context) {
    }

    public void renderAboveTerrain(WRenContext context) {
    }
}
