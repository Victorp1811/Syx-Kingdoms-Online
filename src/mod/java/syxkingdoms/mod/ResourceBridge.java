package syxkingdoms.mod;

import game.faction.FResources.RTYPE;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import settlement.main.SETT;
import settlement.room.main.throne.THRONE;
import snake2d.util.datatypes.COORDINATE;

import java.util.Locale;

final class ResourceBridge {
    private ResourceBridge() {
    }

    static String canonicalKey(String input) {
        RESOURCE resource = resource(input);
        return resource == null ? null : resource.key;
    }

    static int escrow(String resourceKey, int requestedAmount) {
        RESOURCE resource = require(resourceKey);
        return resource.remove(requestedAmount, RTYPE.DIPLOMACY);
    }

    static void deliver(String resourceKey, int amount) {
        if (amount < 1 || amount > 10_000) {
            throw new IllegalArgumentException("Resource delivery amount is outside the supported range");
        }
        RESOURCE resource = require(resourceKey);
        COORDINATE throne = THRONE.coo();
        if (throne == null || !SETT.IN_BOUNDS(throne)) {
            throw new IllegalStateException("A completed throne is required to receive multiplayer resources");
        }
        SETT.THINGS().resources.create(throne.x(), throne.y(), resource, amount);
    }

    private static RESOURCE require(String key) {
        RESOURCE resource = resource(key);
        if (resource == null) {
            throw new IllegalArgumentException("Unknown Songs of Syx resource: " + key);
        }
        return resource;
    }

    private static RESOURCE resource(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        RESOURCE resource = RESOURCES.map().tryGet(key.trim());
        if (resource == null) {
            resource = RESOURCES.map().tryGet(key.trim().toUpperCase(Locale.ROOT));
        }
        if (resource == null) {
            resource = RESOURCES.map().tryGet(key.trim().toLowerCase(Locale.ROOT));
        }
        return resource;
    }
}
