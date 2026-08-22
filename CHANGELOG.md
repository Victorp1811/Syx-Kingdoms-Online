# Changelog

## Unreleased

- Restored Songs of Syx V71's complete native world finalization on the stable
  1000x1000 map so city shards receive valid road entry points and initialized
  faction, army, mercenary, and statistics data before play.
- Moved that expensive native finalization into the canonical packaged world
  build. Normal joins now attach the player's kingdom to the nearest existing
  region and never regenerate regions, roads, pathing, factions, or armies.
- Suppressed V71's native region-centre town sprites in the dedicated online
  launcher and neutralized its generated single-player factions. Only the
  server's player cities and three-NPC clusters are visible and authoritative.
- Replaced V71's repeated whole-map native road-group merger with a bounded
  online finalizer. Native local roads and settlement entry points remain, while
  cross-kingdom travel and connectivity stay server-authoritative.
- Restored V71's Tab city/world toggle and made the overlay's World Map tab open
  the native persistent world directly. Online markers remain server-owned and
  the fixed city simulation continues at the enforced speed in either view.

## 2026-08-21 - Stable 1000-square persistent world

- Reduced the canonical native world from 2560×2560 to 1000×1000 after V71
  watchdog and rendering tests showed the larger geometry was impractical.
- Retained the complete normalized heightmap and 10×10 strategic province grid.
- Set the fully allocation-tested capacity to 90 isolated kingdoms plus three
  NPC cities each (360 city nodes total) while retaining the 64-tile player
  separation rule.

## 2026-08-21 - Native capital-region bootstrap

- Added a lightweight 3x3 native player region at the server-approved capital.
- Supplies V71 city mineral/terrain generation with a real player capital region
  without running the full 2560x2560 single-player region and road generators.

## 0.14.0 - 2026-08-21

- Added the generated native 2560×2560 V71 world template based on the canonical
  grayscale heightmap. Normal Multiplayer joins load this save directly and never
  enter random map generation.
- Expanded the exact-V71 online engine patch to raise the native world constructor
  limit and global generation flooder to 2560, and to normalize heightmap samples
  against 256 instead of the world width. The 2560 world now completes generation
  and persists successfully.
- Raised the online native world-map camera cap from zoom level 2 to level 4. This
  is the farthest level that keeps V71's 16-pixel land tiles visible; the unusable
  level 5 black-ground case is blocked. City and battle views keep their caps.
- Inset the canonical heightmap behind a four-percent ocean border so native
  generation preserves its outer coastline and islands instead of clipping them.
- Replaced the native faction/banner modal in first-join flow with the online
  32×32 crest editor, including all sixteen palette slots and a larger preview.
- Preserved a verified 512×512 native template as a recovery fallback while the
  2560×2560 template remains the canonical production world.
- Added an opt-in online launcher agent for exact V71. It changes the root
  **Play** entry to **Multiplayer** and starts a neutral, forced-online game
  specification directly, bypassing Random Game and all single-player tweak
  presets without modifying `SongsOfSyx.jar` or the normal single-player launch.
- Fixed the dedicated server closing rejected sockets before their protocol error
  could be delivered. First-join responses now include `FOUNDING_REQUIRED` plus a
  complete public shared-map snapshot, covered by a wire integration test.
- Added interactive capital founding on the shared map. The client collects names,
  lets the player click and confirm a plains/forest coordinate, and the server
  rejects occupied, unbuildable, too-close, or three-NPC-incompatible locations
  without consuming a player slot.
- Increased the deterministic strategy-map presentation from 40×40 to 160×160
  terrain cells, enlarged coherent terrain features, and replaced near-black map
  colors with readable water, plains, forest, and mountain colors. Empty V1 test
  worlds migrate automatically; populated worlds retain strict fingerprint safety.
- Replaced the broken overlay-on-top-of-the-vanilla-wizard startup with an
  exact-V71 multiplayer bootstrap. The shared-world connection and founding
  prompts now run from the generator-safe frame hook, and that hook also keeps
  the authenticated session alive while native modal stages are present.
- Made the multiplayer screen cover the vanilla wizard until the server accepts
  the persistent kingdom. After the player chooses a gameplay species, the mod
  skips native faction/banner customization, titles, random map selection,
  capital placement, and the world-inspection/start screen.
- Added automatic local-shard creation from one fixed V71 seed and deterministic
  capital-site scan. Server-owned ruler, kingdom, and capital names are written
  into the native game before its initial save, while the persistent 2560-square
  strategic map remains the visible multiplayer world.
- Replaced player-selected nearby/social founding with authoritative isolated
  clusters and a 64-tile minimum distance between player capitals.
- Added exactly three persistent neutral PvE cities beside every player capital,
  with deterministic buildable placement, level 1-3 garrisons, treasuries, and
  real-time regeneration.
- Added neutral-city marches and server-resolved battles, survivor returns, crown
  loot, persistent reports/events, and UI selection/actions. PvE requires no House
  war and does not cancel beginner protection.
- Upgraded the wire protocol to version 10 and persistent world format to 13;
  older player worlds automatically receive their missing three-city NPC rings.
- Removed the fourth founding prompt and made old clients that request social
  placement fail without consuming a player slot.
- Set and exhaustively allocation-tested the canonical capacity at 512 isolated
  player clusters / 2,048 total strategic city nodes.

## 0.13.1 - 2026-08-21

- Disabled vanilla title-derived bonuses by clearing every selected title for the
  entire online session.
- Added an exact-V71 world-generator hook that bypasses the title-selection stage;
  continuous clearing remains the fail-safe if the generator UI changes.
- Corrected the mod author metadata to Victorp1811.
- Fixed `run-server.ps1` so the documented execution-policy bypass command resolves
  its default server configuration correctly.
- Clarified that the persistent 2560×2560 world is the authoritative strategic
  coordinate plane rendered by the multiplayer client, not a single vanilla world
  allocation; Songs of Syx 0.71.44 hard-limits physical worlds to 512×512.

## 0.13.0 - 2026-08-20

- Replaced every native modal multiplayer text prompt with a live overlay input
  listener, covering founding, all chat channels, mail, House actions, market and
  province orders, army/transfer quantities, shipments, and crest palette colors.
- Captured keyboard events while an overlay prompt is active, including a short
  submit/cancel grace window, so typing and Enter/Escape cannot trigger settlement
  hotkeys while the city continues at the canonical 1x speed.
- Added visible Submit/Cancel controls and explicit Enter/Escape instructions;
  first-join prompts can now be completed by mouse while simulation remains frozen
  awaiting the server's founding acceptance.
- Added a 48-client shared-world soak test with eight Houses, four wars and battles,
  command replay, mass disconnect/reconnect, six persistence reloads, and 5,760
  synchronization updates. Server CI and release builds run it automatically.

## 0.12.0 - 2026-08-20

- Upgraded the wire protocol to version 9 and persistent world format to 12.
- Made the existing 10×10 sector grid authoritative as 100 persistent provinces.
- Added House province claims, capital provinces, adjacency validation, ownership,
  siege influence, six-hour blockades, occupation, annexation, daily tribute, and map colors.
- Added 30-day seasons, House scoring, a 30-province domination victory condition,
  highest-score fallback, winner history, and season rollover that preserves
  ownership while clearing wars and occupation state.
- Added province, war, report, and season persistence/restart validation.

## 0.11.0 - 2026-08-20

- Replaced instant WAR stances with formal House wars: twelve hours of visible
  preparation, up to seven days active, surrender, and a 72-hour truce.
- Added House-allied reinforcements, server-owned offense/defense resolution,
  survivor returns, detailed private/allied battle reports, and concealed troop
  counts for uninvolved observers.
- Added offline defense, per-target daily loot caps, repeat-target score and
  occupation limits, war score, surrender without city deletion, and automatic
  return of armies whose war ends before arrival.

## 0.10.0 - 2026-08-20

- Added persistent Houses with unique names, 3-5 character tags, a 20-member
  cap, invitations, applications, acceptance/rejection, leaving, kicking, and a
  24-hour membership-change cooldown.
- Added Sovereign, Marshal, Steward, Herald, and Member roles with server-side
  permission checks and leadership transfer.
- Added House chat, announcements, public rosters/profiles, audit events, and
  House crests using the existing 32×32/16-color editor.
- Replaced the single crowded overlay workflow with tabs for World Map,
  Chat/Mail, House, Armies/Reports, Market, and Profile/Crest.

## 0.9.0 - 2026-08-20

- Upgraded the wire protocol to version 6 and persistent world format to 9.
- Added a server-owned 32×32 kingdom crest format with sixteen indexed palette
  slots: one transparent and fifteen independently editable 24-bit RGB colors.
- Added a non-modal in-game crest editor with left-drag painting, right-drag
  erasing, palette selection, RGB/hex color input, undo, redo, clear, horizontal
  and vertical mirrors, and automatic native Songs of Syx banner import.
- Added 64-pixel kingdom-map previews and a 128-pixel live editor preview.
- Added SHA-256 design hashes and paged revision cursors so validated crests are
  persisted and synchronized once rather than retransmitted every heartbeat.
- Added protocol, persistence, two-client wire, malformed-data, and editor-history
  tests for the new crest system.

## 0.8.0 - 2026-08-20

- Added persistent province-local chat alongside global chat and private mail.
- Defined a local channel as the sender's base-map-sized `256 × 256` world
  province; the server filters recipients and records the originating province.
- Stamped local and global messages with the permanent server-owned ruler name,
  preventing clients and reconnect attempts from supplying a chat nickname.
- Added separate Local and Global chat controls and labels to the shared-world
  overlay, with mute, rate-limit, replay, and persistence behavior shared by both.
- Added persistence tests proving same-province delivery, cross-province privacy,
  global delivery, and chosen-name attribution.

## 0.7.0 - 2026-08-20

- Locked the canonical server and client simulation speed to exactly 1x.
- Added fail-closed simulation gating: cities freeze while connecting,
  disconnected, founding, or behind on server heartbeat synchronization.
- Added an input filter for pause, speed, quickload, debugger, and vanilla-world
  switch hotkeys, with update/render speed correction as a second layer.
- Rejected Songs of Syx development mode and world-editor sessions.
- Disabled vanilla AI imports/exports and redirected the vanilla strategic map to
  the persistent multiplayer map.
- Bound new game saves to their persistent player identity and canonical world;
  V0.6 saves migrate on their next save.
- Extended the crash-safe escrow file into a revisioned rollback journal; loading
  an older save re-applies later pending/accepted multiplayer resource debits.
- Changed rejection refunds and inbound deliveries to journal-before-mutation
  ordering so a ledger write failure cannot duplicate local resources.
- Added a documented feature matrix and explicit engine/anti-cheat limitations.

## 0.6.0 - 2026-08-20

- Upgraded the wire protocol to version 5 and persisted world to format 8.
- Added native first-join prompts for a permanent ruler, unique kingdom, capital,
  and optional placement near an existing kingdom.
- Made founding atomic: invalid, reserved, or duplicate names consume neither a
  player record nor a world coordinate.
- Added deterministic balanced/social placement and migration that preserves the
  coordinates of pre-founding player records.
- Added five-day beginner protection, protected-target army rejection, an outgoing
  army warning, and automatic loss of protection after the first accepted march.
- Added protection/founding state to server snapshots, client UI, persistence,
  protocol, and integration tests.

## 0.5.0 - 2026-08-20

- Upgraded the wire protocol to version 4 and the persisted world to format 7.
- Added a persistent public market with escrowed sell offers, partial purchases,
  atomic crown settlement, distance-based delivery, replay protection, and
  cancellation refunds.
- Added in-game market browsing, offer creation, buying, and cancellation controls.
- Added live-loaded player bans and chat/mail mutes plus an operator moderation tool.
- Added crash-safe market escrow replay, market persistence/wire integration tests,
  optional moderation-state backups, and crown-cap conservation checks.

## 0.4.0 - 2026-08-20

- Upgraded the wire protocol to version 3 with bounded command, result, event,
  relation, army, strategic player-state, and delivery frames.
- Added persistent global chat, private mail, diplomacy declarations, mutual
  alliances, and effective war state.
- Added replay-safe command IDs, explicit results, event cursors, rate limiting,
  and synchronous durable saves before successful responses.
- Added server-owned crowns and levies, crown transfers, distance-based armies,
  deterministic raids, and physical resource shipments with durable delivery.
- Added clickable map controls and native chat, mail, amount, diplomacy, army,
  transfer, and goods prompts.
- Added TLS client support, container deployment, backup tooling, deployment
  guidance, container CI, and persisted world format 6.

## 0.3.0 - 2026-08-20

- Defined the online world as `2560 × 2560` coordinates: exactly ten times the
  installed base game's 256-tile world dimension along both axes.
- Divided the client map into a visible 10-by-10 grid of base-map-sized sectors.
- Added deterministic seed-derived water, plains, forests, and mountains; the
  server founds cities only on plains or forest, and every client renders the
  same geography locally from the world seed.
- Locked the map ID and seed to one canonical world and persisted a terrain
  generation/fingerprint that is verified on every server restart.
- Added a safe client upgrade mapping from the old `local-dev` identifier to the
  canonical world without replacing the player's existing configuration/identity.
- Increased the dedicated-server default capacity to 2,048 persistent cities.
- Added shared geometry constants and tests that prove the tenfold linear scale
  and hundredfold coordinate area.
- Started new default worlds in `syx-kingdoms-world-v1.properties`, avoiding accidental
  reuse of earlier 256-square development worlds.

## 0.2.0 - 2026-08-20

- Added a persistent server-defined shared-world contract with dimensions, seed,
  city spacing, and exact game/mod compatibility requirements.
- Added deterministic, collision-free city coordinate allocation distributed
  across the world.
- Added an in-game shared-world map showing claimed cities, presence, population,
  treasury, and hover details.
- Added client validation for world-contract changes, out-of-bounds cities,
  duplicate identities, and duplicate coordinates.
- Made online play exclusive to Syx Kingdoms Online: the client blocks startup
  when another mod is active and the server rejects a mismatched mod manifest.
- Upgraded the wire protocol to version 2 and the persisted world format to 2.
- Added repository-safe server CI that does not require Songs of Syx binaries.

## 0.1.0 - 2026-08-20

- Added the dedicated persistent server, authenticated client identities,
  city summaries, automatic reconnect, fixed online speed, roster overlay,
  packaging scripts, and end-to-end two-client tests.
