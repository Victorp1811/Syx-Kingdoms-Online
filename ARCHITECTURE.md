# Architecture

## Why the shared world is a server layer

Songs of Syx is a single-player Java game whose simulation is organized around
global game, faction, world, and settlement instances. A single process cannot
cleanly simulate many independent player cities. Syx Kingdoms therefore treats
each player's normal Songs of Syx process as a city shard and gives the dedicated
server authority over state that crosses city boundaries.

Songs of Syx 0.71.44 defines its base world with a tile dimension of 256. The
online coordinate plane is exactly `2560 × 2560`: ten base maps along each axis,
or 100 base-map-sized sectors. City coordinates, future travel routes, and future
territorial ownership all use this persistent server coordinate system.
The server seed deterministically produces coarse water, plains, forest, and
mountain terrain. Only plains and forest accept city slots. Because generation is
shared protocol code, clients reconstruct identical geography without downloading
a multi-million-tile map on every connection.
The map ID and seed are compile-time canonical values. Persistence stores the
terrain-generation number and a deterministic terrain fingerprint, and startup
fails if either differs. Running the authoritative service as a single public
deployment is the remaining operational step that makes this one logical world
globally, rather than merely one verified world file per server installation.

| Owner | State |
| --- | --- |
| Client city shard | citizens, rooms, jobs, local production, local rendering |
| Dedicated server | identity, city slot, world clock, presence, trade, diplomacy, armies |
| Derived/cached on client | remote city summaries and shared-world presentation |

Clients submit bounded commands. The server validates and persists them, then
returns authoritative results. A client never writes another player's city state.

## Current vertical slice

The V4 wire protocol uses bounded UTF-8, tab-separated frames with base64url text
fields. On connection, a client authenticates its stable player ID and token and
reports its exact game, client, and active-mod versions. The client refuses to
connect when another mod is active, and the server independently enforces the
required manifest. The server allocates a persistent city slot, returns the
authoritative world dimensions, seed, tick settings, and sends a roster after each
heartbeat. World persistence uses a temporary file plus atomic replacement where
the filesystem supports it.

Updates carry a monotonic sequence, event cursor, and up to eight UUID-addressed
commands. Accepted effects are saved before the response. Replayed IDs return the
recorded result without duplicating chat, transfers, armies, shipments, offers, or
purchases. Public and private events are paged through a visibility-aware cursor.
Persisted world format 7 also records the public order book and partial fills.

The client script runs on the game's normal update hook. While connected, it
forces the configured server speed every update, so pause and local speed changes
cannot stop or accelerate an online city. It publishes population and treasury
without granting the server arbitrary access to the local save.

## Milestones

1. **Foundation (implemented):** server, identity, persistent city allocation,
   roster, city summaries, fixed time speed, reconnect, integration tests.
2. **10× shared-world contract and panel (implemented):** a persistent 2560-square
   coordinate plane, 100 visible base-map sectors, deterministic city coordinates,
   exact version/mod-set checks, and an in-game remote-city map.
3. **Vanilla world-map projection:** materialize remote cities as player-owned
   regions and make shared locations interactive in the normal game UI.
4. **Strategic economy (implemented MVP):** server crowns/levies, transfers,
   recoverable physical-resource escrow, shipments, durable inboxes, and delivery
   acknowledgements, plus persistent public sell offers, partial purchases, crown
   settlement, and cancellation refunds.
5. **Diplomacy and chat (implemented MVP):** global chat, private mail, mutual
   alliances, war declarations, visibility filtering, rate limits, and live-loaded
   ban/mute controls.
6. **Armies and raids (implemented MVP):** authoritative levy commitment, routes,
   arrival times, defender resolution, raid spoils, and replay protection. Full
   tactical battle instances remain future work.
7. **Operations (partially implemented):** TLS client, container service, atomic
   persistence, backups, strict compatibility, CI, command auditing, rate limiting,
   moderation tools, and periodic operational counts. A Steam or central account
   provider, external metrics/alerting, and stronger attestation remain before a
   public competitive launch.

## Non-goals

- Lockstep synchronization of every NPC in several cities.
- Loading another player's complete save into the current process.
- Treating standard-client resource escrow as strong anti-cheat attestation.
- Requiring all of Syx Together's full-class overrides for the foundation.
