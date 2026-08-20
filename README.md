# Syx Kingdoms Online

Syx Kingdoms Online is an experimental persistent multiplayer layer for Songs of
Syx. It is aimed at a Stronghold Kingdoms-style structure: every player owns and
simulates a separate city, while a dedicated server owns the shared world clock,
player identity, settlement roster, trade, diplomacy, and armies.
There is one canonical persistent world: its ID, dimensions, geography seed, and
terrain-generation version are fixed rather than chosen by individual players.

This repository currently contains a playable shared-world MVP:

- a dedicated Java server with an atomically persisted world;
- authenticated, reconnectable player identities;
- one server-side city slot per player;
- a persistent `2560 × 2560` server world—ten times the base game's `256 × 256`
  width and height—with collision-free city coordinates distributed across it;
- live population and treasury summaries for every connected city;
- a fixed 1x online game speed (pause and speed changes are immediately undone);
- a V71 Songs of Syx script mod with automatic reconnect and an in-game shared
  city map;
- an exclusive-mod handshake: online mode refuses to run alongside any other mod;
- persistent chat, mail, diplomacy, server crowns/levies, crown transfers,
  physical-resource shipments, public market offers, traveling armies, and raid
  resolution;
- clickable shared-map controls using Songs of Syx's native text prompts;
- replay-safe commands that are durably saved before acknowledgment;
- TLS client support for the single public authoritative endpoint;
- protocol, persistence, and two-client integration tests.

The map panel is currently a multiplayer overlay; it does **not** yet insert
remote cities as interactive regions into the vanilla world map. That deeper
client integration is the next major milestone. The original Syx Together source
supplied for reference remains in the ignored `reference/` directory; none of its
copied game-class overrides are packaged into this mod.

## Build

Requirements:

- JDK 21 or newer (`javac` and `jar` on `PATH`)
- Songs of Syx installed through Steam, or `SONGS_OF_SYX_JAR` set to its JAR

```powershell
./tools/build.ps1
```

The build runs all tests and creates:

```text
build/dist/Syx Kingdoms Online/       installable game mod
build/dist/server/                    dedicated server
```

## Local two-player test

1. Build the project.
2. Start the server:

   ```powershell
   ./tools/run-server.ps1
   ```

3. Install the client mod, or copy `build/dist/Syx Kingdoms Online` into each
   player's Songs of Syx mods directory:

   ```powershell
   ./tools/install-mod.ps1
   ```

   Then enable it in the launcher and disable every other mod. The client checks
   Songs of Syx's active-mod list before connecting, and the server also requires
   the exact `Syx Kingdoms Online@0.5.0` manifest. The installer preserves an
   existing client `config.txt`; pass `-ResetConfig` only when you intentionally
   want the defaults.
4. Give each player a unique `PLAYER_NAME` and `CITY_NAME` in `config.txt`.
   Identity and a strong authentication token are generated automatically under
   the player's Songs of Syx application-data directory.
5. Start or load a city. The client log reports the server connection and the
   number of visible online cities. The overlay shows every claimed city on the
   server-defined shared map; your city is orange, online rivals are green, and
   offline cities are gray. Grid lines divide the online world into 100 sectors;
   every sector is the same `256 × 256` size as a normal base-game world map.
   Click another city to select it. Controls below the map provide global chat,
   private mail, diplomacy, crown transfers, armies, and goods shipments. Goods
   use `RESOURCE_KEY AMOUNT`, for example `WOOD 100`. Public sell offers use
   `RESOURCE_KEY QUANTITY UNIT_PRICE`, for example `WOOD 100 3`; Prev/Next browse
   the canonical order book, Buy accepts a quantity, and sellers can cancel their
   current offer to receive unsold goods back.

The 10× measurement is linear: the online grid is ten base maps wide and ten
base maps tall. Its total coordinate area is therefore 100 times the base map.
The dedicated server stores city claims and a fingerprint of the canonical
terrain in `server-data/syx-kingdoms-world-v1.properties`; it verifies the map on
every restart rather than generating a new world.

The default server listens on raw TCP port `49720` for local development. Never
forward that port to the public internet. The client supports TLS with normal
certificate/hostname verification and optional pinning; terminate public TLS in a
TCP reverse proxy and forward only to loopback. See [DEPLOYMENT.md](DEPLOYMENT.md).

The exclusive-mod handshake prevents accidental mixed-mod sessions and gives the
server a strict compatibility gate. It is not yet an anti-cheat attestation system:
a deliberately altered client could falsify its reported manifest or resource
escrow. Standard clients use a crash-safe local ledger, server-owned offers, and
idempotent commands, but a public competitive launch still needs a trusted account
provider and stronger client/resource attestation.

## Development direction

See [ARCHITECTURE.md](ARCHITECTURE.md) for the authority boundary and staged plan.
