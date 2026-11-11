# Architecture

## Why the shared world is a server layer

Songs of Syx is a single-player Java game whose simulation is organized around
global game, faction, world, and settlement instances. A single process cannot
cleanly simulate many independent player cities. Syx Kingdoms therefore treats
each player's normal Songs of Syx process as a city shard and gives the dedicated
server authority over state that crosses city boundaries.

Songs of Syx 0.71.44 defines its base world with a tile dimension of 256. The
online coordinate plane is exactly `1000 × 1000`, divided into 100 persistent
`100 × 100` strategic provinces. City coordinates, travel routes, House capitals,
and territorial ownership all use this persistent server coordinate system.
The bundled grayscale heightmap deterministically produces the native water,
plains, forest, and mountain terrain. The server samples that same image into its
strategic placement grid, and only plains and forest accept city slots.
The map ID and seed are compile-time canonical values. Persistence stores the
terrain-generation number and a deterministic terrain fingerprint, and startup
fails if either differs. Running the authoritative service as a single public
deployment is the remaining operational step that makes this one logical world
globally, rather than merely one verified world file per server installation.
Exact V71 normally validates world configuration at no more than 512×512 and its
global generation flooder is sized to the 768-tile settlement. The dedicated
online Java agent raises both limits to 1000, normalizes heightmap sampling against
the image's 0–255 range, and raises the native world camera cap from zoom 2 to zoom
4. The source is inset behind a four-percent ocean safety border before generation.
A generated native world is saved once and distributed as an immutable launcher
template. Normal joins load that exact save; they never invoke random generation.
The server remains authoritative for shared identities, city claims, diplomacy,
trade, armies, Houses, wars, and persistence, while each client simulates only its
own settlement.
The selected shared coordinate is a requested capital anchor, not an unchecked
client write. The server verifies buildable terrain, exact occupancy, the 64-tile
player exclusion radius, and the availability of three separated nearby NPC sites
in one synchronized founding transaction.

The ordinary V71 menu exists before gameplay scripts are instantiated. A small
opt-in Java instrumentation agent therefore owns the dedicated online entry path:
it changes the root Play label/action to Multiplayer and loads the fixed online
world with neutral bonuses and forced scripts. Six verified V71 classes are
transformed in memory for the menu, native size check, generator pathing,
heightmap scale, and world-camera cap. The original game JAR and normal
single-player launch are not modified.

| Owner | State |
| --- | --- |
| Client city shard | citizens, rooms, jobs, local production, local rendering |
| Dedicated server | identity, founding names, city slot, protection, world clock, presence, trade, Houses, wars, provinces, seasons, armies |
| Derived/cached on client | remote city summaries and shared-world presentation |

Clients submit bounded commands. The server validates and persists them, then
returns authoritative results. A client never writes another player's city state.

## Current vertical slice

The V9 wire protocol uses bounded UTF-8, tab-separated frames with base64url text
fields. On connection, a client authenticates its stable player ID and token and
reports its exact game, client, and active-mod versions. The client refuses to
connect when another mod is active, and the server independently enforces the
required manifest. A new identity submits ruler, kingdom, and capital names
with a requested capital coordinate from the shared map. The server normalizes
and uniquely reserves the names, validates the coordinate and its isolated
four-city cluster, and creates the player in one synchronized operation.
Automatic placement remains a compatibility fallback for clients and test
fixtures that omit a coordinate; nearby-friend placement is not enabled.
Returning identities use the stored authoritative profile rather than client
configuration. Pre-V0.6 players retain their existing
coordinate and complete the same profile on their next login. The server returns the
authoritative world dimensions, seed, tick settings, and sends a roster after each
heartbeat. World persistence uses a temporary file plus atomic replacement where
the filesystem supports it.

Updates carry a monotonic sequence, event and crest cursors, and up to eight UUID-addressed
commands. Accepted effects are saved before the response. Replayed IDs return the
recorded result without duplicating chat, transfers, armies, shipments, offers, or
purchases. Public, province-local, and private events are paged through a
visibility-aware cursor. Local messages record the sender's base-map-sized sector
and are delivered only to identities whose cities occupy that same sector; all
chat actor names come from the permanent server profile.
Persisted world format 12 records founding profiles, protection deadlines,
validated 32×32 indexed-color kingdom and House crests, House rosters/requests,
100 provinces, formal wars, battle reports, and season state in addition to the
public order book and partial fills. Each crest has one transparent and fifteen editable RGB palette
slots, a SHA-256 content hash, and a monotonic revision. Paged crest frames let
clients cache designs without including every crest in every heartbeat. New kingdoms receive a
configurable five-day default protection window. Incoming armies are rejected
until it expires; the first accepted outgoing army clears the attacker's window.

The client script normally runs on the game's update hook and uses its render/frame
hook as the V71 generator-safe bootstrap path, because native modal world stages do
not call `SCRIPT.update()`. A fresh server heartbeat
unlocks exactly 1x simulation; joining, disconnection, or a stale session locks it
to 0x. An input filter consumes pause, speed, quickload, debugger, and vanilla
world-switch hotkeys before the normal game handler sees them, while update and
render hooks correct the actual speed. The ruleset also disables vanilla AI
trade, redirects the vanilla world screen, rejects development mode, and binds a
save to its persistent player/world identity. It publishes population and local
treasury summaries without granting the server arbitrary access to the local save.
All multiplayer text and numeric actions use a frame-driven character listener
rendered inside the overlay. The listener captures gameplay key events while it
is active and does not enter the game's modal interaction stack, so the settlement
continues at 1x while the player types.

## Milestones

1. **Foundation (implemented):** server, identity, persistent city allocation,
   roster, city summaries, fixed time speed, reconnect, integration tests.
2. **Shared-world contract and panel (implemented):** a persistent 1000-square
   coordinate plane, 100 visible strategic provinces, deterministic city coordinates,
   exact version/mod-set checks, and an in-game remote-city map.
3. **Kingdom founding (implemented):** permanent ruler/kingdom/capital names,
   isolated server placement with a 64-tile player exclusion radius, three nearby
   persistent neutral PvE cities, atomic claims, legacy migration, and beginner
   protection. Social/near-friend placement is deliberately disabled.
4. **Vanilla world-map projection:** materialize remote cities as player-owned
   regions and make shared locations interactive in the normal game UI.
5. **Strategic economy (implemented MVP):** server crowns/levies, transfers,
   recoverable physical-resource escrow, shipments, durable inboxes, and delivery
   acknowledgements, plus persistent public sell offers, partial purchases, crown
   settlement, and cancellation refunds.
6. **Diplomacy and chat (implemented MVP):** province-local chat, global chat,
   private mail, permanent ruler-name attribution, mutual alliances, war
   declarations, visibility filtering, rate limits, and live-loaded ban/mute
   controls.
7. **Armies and raids (implemented MVP):** authoritative levy commitment, routes,
   arrival times, defender resolution, raid spoils, and replay protection. Full
   tactical battle instances remain future work.
8. **Operations (partially implemented):** TLS client, container service, atomic
   persistence, backups, strict compatibility, CI, command auditing, rate limiting,
   moderation tools, and periodic operational counts. A Steam or central account
   provider, external metrics/alerting, and stronger attestation remain before a
   public competitive launch.
9. **Online ruleset hardening (implemented MVP):** connection-gated simulation,
   canonical 1x speed, restricted local inputs/world/trade, development-mode
   rejection, save identity binding, a retained resource-debit rollback journal,
   and non-modal multiplayer text/numeric input. Trusted client/account attestation
   remains future work.
10. **Kingdom identity (implemented MVP):** server-owned 32×32/16-slot crests,
    native-banner import, an in-game pixel editor, large previews, persistence,
    validation, hashes, and revision-aware client caches.
11. **Houses and multiplayer workspace (implemented):** six focused UI tabs;
    persistent unique Houses; invitations/applications; five permissioned roles;
    a 20-member cap; House chat, announcements, rosters, audit events, map color,
    membership cooldowns, and House crests. Shared treasuries and passive House
    bonuses are intentionally excluded from this release.
12. **Formal PvP (implemented strategic layer):** House declarations, 12-hour
    preparation, seven-day wars, reinforcements, concealed composition, offline
    defense, reports, daily loot/repeat-target limits, surrender, truces, and no
    city deletion. Full tactical battle-instance co-simulation remains future work.
13. **Province warfare and seasons (implemented strategic layer):** authoritative
    ownership of all 100 provinces, capital/adjacent claims, occupation influence,
    annexation, blockades, tribute, war score, 30-day seasons, domination victory, high-score
    fallback, and persistent winner state.
14. **Persistence soak and live input (implemented):** non-modal founding/action
    prompts with hotkey capture, plus release-gating coverage for 48 clients,
    eight Houses, four wars, six restarts, reconnect storms, command replay, and
    thousands of synchronization updates.
15. **Player clusters and neutral PvE (implemented):** a configured capacity of
    90 player kingdoms and 270 neutral cities with 64-tile player separation,
    exactly three levelled neutral cities per player, persistent NPC
    economy/garrisons, PvE marches, loot, reports, regeneration, and protocol/world
    migrations.

## Non-goals

- Lockstep synchronization of every NPC in several cities.
- Loading another player's complete save into the current process.
- Treating standard-client resource escrow as strong anti-cheat attestation.
- Requiring all of Syx Together's full-class overrides for the foundation.
