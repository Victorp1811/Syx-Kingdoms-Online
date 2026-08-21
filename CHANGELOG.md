# Changelog

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
