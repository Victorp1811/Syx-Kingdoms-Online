package world.map.road;

import snake2d.util.misc.ACTION;

/**
 * Online replacement for V71's global region-road connector.
 *
 * <p>GenRoad, GenPort, and GenPolish have already created each native region's
 * local roads and settlement entry points before this class is constructed.
 * The stock GenPlayer then performs repeated whole-map flood fills solely to
 * merge every generated single-player region into one road group. On a
 * 1000x1000 online world that is effectively unbounded and is unnecessary:
 * cross-kingdom travel is server-authoritative. Keep the loading callback but
 * omit that final global merge.</p>
 */
final class GenPlayer {
    GenPlayer(ACTION progress) {
        progress.exe();
    }
}
