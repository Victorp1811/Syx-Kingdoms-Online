# Syx Kingdoms Online

Syx Kingdoms Online is an experimental persistent multiplayer layer for Songs of
Syx. It is aimed at a Stronghold Kingdoms-style structure: every player owns and
simulates a separate city, while a dedicated server owns the shared world clock,
player identity, settlement roster, trade, diplomacy, and armies.
There is one canonical persistent world: its ID, dimensions, geography seed, and
terrain-generation version are fixed rather than chosen by individual players.

This repository currently contains the V0.14 playable shared-world vertical slice:

- a dedicated Java server with an atomically persisted world;
- authenticated, reconnectable player identities;
- an in-game first-join flow for a permanent, uniquely named ruler, kingdom, and
  capital, followed by server-assigned isolated placement;
- one server-owned four-city cluster per player: the player capital and exactly
  three nearby persistent neutral PvE settlements;
- a 64-tile minimum distance between player capitals, no player-selected nearby
  placement, and verified capacity for 90 player clusters / 360 city nodes;
- one pre-generated native `1000 × 1000` Songs of Syx world, built from the
  canonical grayscale heightmap and bundled as an immutable launcher template;
- live population and treasury summaries for every connected city;
- a fixed 1x online game speed (pause and speed changes are immediately undone);
- a connection gate that freezes the city while joining, disconnected, or behind
  on authoritative server heartbeats;
- a dedicated exact-V71 launcher patch that expands native world allocation,
  generation pathing, heightmap sampling, and world-map zoom only in online mode;
- a V71 Songs of Syx script mod with automatic reconnect and an in-game shared
  city roster over the native world map;
- an exclusive-mod handshake: online mode refuses to run alongside any other mod;
- an online ruleset that blocks development/debug mode, quickload, the vanilla
  strategic world screen, and vanilla AI imports/exports, and binds saves to the
  player's persistent identity;
- disabled vanilla title selection and title-derived kingdom bonuses;
- persistent province-local and world-wide chat, private mail, diplomacy,
  server crowns/levies, crown transfers,
  physical-resource shipments, public market offers, traveling armies, and raid
  resolution;
- server-owned 32×32 kingdom crests with 16 editable palette slots, native-banner
  import, revision caching, and large map/profile previews;
- persistent Houses with unique tags, invitations/applications, a 20-member cap,
  five permissioned roles, House chat, announcements, audit events, and editable
  32×32 House crests;
- formal House warfare with a 12-hour preparation phase, seven-day war window,
  allied reinforcements, concealed army strength, server-resolved offline defense,
  private/allied reports, capped loot, anti-farming, surrender, and 72-hour truces;
- 100 persistent provinces with House ownership, capital provinces, adjacency
  claims, occupation influence, annexation, blockades, daily tribute, map colors, 30-day
  seasons, war score, and domination/high-score victory conditions;
- five real days of beginner protection; protected kingdoms cannot be attacked,
  and launching an army against a player ends the attacker's remaining protection;
- neutral cities with three difficulty levels, persistent garrisons and treasuries,
  real travel, regeneration, crown loot, and server-owned PvE battle reports;
- clickable shared-map controls with live, non-modal overlay text entry that does
  not pause the local city while naming, chatting, trading, or issuing orders;
- replay-safe commands that are durably saved before acknowledgment;
- TLS client support for the single public authoritative endpoint;
- protocol, persistence, two-client socket integration, and 48-client restart/
  reconnect soak tests.

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
build/dist/launcher/                  dedicated online launcher and fixed world
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
   the exact `Syx Kingdoms Online@0.14.0` manifest. The installer preserves an
   existing client `config.txt`; pass `-ResetConfig` only when you intentionally
   want the defaults.
4. Start the dedicated online entry point:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\run-online.ps1
   ```

   This opt-in launcher leaves the original game JAR untouched, changes the root
   **Play** entry to **Multiplayer**, and bypasses Random Game and its single-player
   tweak presets. Click **Multiplayer**. It loads the bundled persistent
   `1000 × 1000` native world directly; it never asks the player for a seed, map
   type, bonuses, or a newly generated world. On the first connection, live multiplayer prompts
   ask for a ruler name, unique kingdom name, and capital name. Click a plains or
   forest location on the shared map and confirm it. The server verifies that it
   is buildable, unoccupied, at least 64 shared tiles from every other player, and
   has space for three nearby neutral settlements. The server reserves all
   names and the four-city cluster together, so a
   rejected name never consumes a slot. Identity and a strong authentication
   token are generated automatically under the player's Songs of Syx
   application-data directory. The city remains at the canonical 1x speed while
   the player types. After server acceptance, choose the species that will inhabit the
   city. The mod skips native faction customization, titles, random map selection,
   capital placement, and world inspection. The player's settlement opens from
   the fixed world template; do not choose or generate a vanilla map manually.
5. The client log reports the server connection and the
   number of visible online cities. The overlay shows every claimed city on the
   server-defined shared map; your city is orange, online rivals are green, and
   offline cities are gray, and neutral PvE cities are brighter gray. Grid lines
   divide the online world into 100 persistent `100 × 100` provinces.
   On the native world screen, mouse-wheel zoom now reaches level 4 in online
   mode—two steps farther out than the stock world camera. Settlement and battle
   zoom limits remain unchanged.
   Click another city to select it, then use the six tabs: **World Map**,
   **Chat/Mail**, **House**, **Armies/Reports**, **Market**, and
   **Profile/Crest**. Local chat reaches rulers inside your current `256 × 256`
   province; global chat reaches the entire world; House chat reaches only your
   current House. Every chat line uses the permanent ruler name chosen at founding;
   the server never accepts a nickname supplied with a message. Goods
   use `RESOURCE_KEY AMOUNT`, for example `WOOD 100`. Public sell offers use
   `RESOURCE_KEY QUANTITY UNIT_PRICE`, for example `WOOD 100 3`; Prev/Next browse
   the canonical order book, Buy accepts a quantity, and sellers can cancel their
   current offer to receive unsold goods back.

   Use **Profile/Crest** to edit a kingdom crest or, with the Sovereign/Herald
   role, the House crest. In the shared 32×32 editor, left-drag paints,
   right-drag erases, and the sixteen palette slots include one
   transparent slot plus fifteen independently editable RGB colors. The editor
   supports undo, redo, clear, horizontal/vertical mirror, native-banner import,
   and a 128-pixel preview. Saving sends the design to the authoritative server;
   other players receive and cache the validated revision automatically.

   A newly founded kingdom is protected from incoming player armies for five real
   days. The overlay shows the remaining protection. Sending an army against a
   player while protected displays a warning and permanently ends the sender's
   remaining protection. Attacking a neutral PvE city does not cancel protection.

   Every player capital has three selectable neutral cities nearby. They are level
   1-3 PvE targets with persistent garrisons and treasuries. Select one and use
   **Attack NPC** or the Armies tab; no House war is required. Travel and battle
   resolve on the server, surviving levies return, and the neutral city gradually
   rebuilds its garrison and crowns.

   PvP requires both kingdoms to belong to different Houses. A Sovereign or
   Marshal declares war from **Armies/Reports**. The server announces the war,
   enforces twelve hours of preparation, then permits attacks for seven days.
   Defenders use their server-owned levy balance even while offline. Victories
   generate reports, capped crown loot, war score, and occupation influence in
   the defender's province. Surrender never deletes a city and starts a 72-hour
   truce. A season lasts 30 days; owning 30 provinces wins immediately, otherwise
   the highest combined province/war score wins at the deadline.

The authoritative online world is 1000 tiles wide and 1000 tiles tall. Exact V71
normally rejects native worlds above 512×512. The dedicated online launcher
patches that allocation check, the global world-generation flooder, heightmap
normalization, and the world camera in memory; it does not edit `SongsOfSyx.jar`
and does not affect normal single-player launches. The resulting native world is
generated once, stored at
`world/syx-kingdoms-native-world-1000-v1.save`, and copied into every launcher
build. The server stores city claims and the corresponding terrain fingerprint in
`server-data/syx-kingdoms-1000-world-v1.properties` and verifies it on restart.
The canonical 1024×1024 heightmap includes a four-percent ocean safety border so
outer islands and coastlines are not clipped by native generation.

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
See [ONLINE_RULES.md](ONLINE_RULES.md) for the exact enabled/disabled feature
matrix and the remaining single-player-engine security limitations.
