# Changelog

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
