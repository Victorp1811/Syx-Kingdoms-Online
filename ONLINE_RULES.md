# Canonical online ruleset

Syx Kingdoms Online treats each Songs of Syx process as one local city shard and
the dedicated service as the authority for everything shared between kingdoms.
The following rules are mandatory while the mod is enabled.

## Enforced in V0.9

| Area | Online rule |
| --- | --- |
| Connection | The local city is frozen at 0x while connecting, founding, disconnected, or more than three heartbeat intervals behind the server. |
| Time | A fresh session runs at exactly 1x. Pause and all vanilla speed hotkeys are consumed, and the target/actual speed is corrected during updates and rendering. |
| Compatibility | Exactly Syx Kingdoms Online V0.9 and its one forced script may be active. Extra scripts and Songs of Syx development/debug mode are rejected. |
| Debug/loading hotkeys | The debugger and quickload hotkeys are consumed. Quicksave remains available. |
| Saves | New saves contain the persistent world ID, player identity, and trade-ledger revision. Loading that city with another identity freezes and closes its online client. Later resource debits are re-applied when an older save is loaded. V0.6 saves are migrated on their next save. |
| World strategy | The vanilla world screen is redirected back to the settlement. The multiplayer map is the only shared strategic map. |
| Trade | Vanilla AI import/export limits are locked to zero. Player shipments and public offers use server commands and the crash-safe escrow ledger. |
| Shared actions | Province-local chat, global chat, mail, diplomacy, crowns, levies, protection, armies, deliveries, and market offers are validated and persisted by the server. Local chat reaches only kingdoms in the sender's `256 × 256` province. Every message is stamped with the sender's server-owned permanent ruler name. |
| Crests | Kingdom crests use exactly 32×32 indexed pixels, one transparent slot, and fifteen editable RGB colors. The server validates, hashes, revisions, persists, and distributes each design; clients cannot attach an arbitrary image file. |
| Mods | Any additional active mod blocks startup; the server independently requires the exact manifest. |

Local construction, citizens, production, rooms, research, and ordinary city
management remain enabled. They are the player's city shard. A client cannot
write another player's city and cannot change server crowns, levies, protection,
army travel, diplomacy, or market state directly.

The local city does not advance while offline. Server-owned real-time strategy
state continues from authoritative wall-clock time and is synchronized on the
next connection. Population is explicitly presented as client-reported status;
the shared economy uses server crowns instead of the local vanilla treasury.

## Engine limitations that are not security boundaries

Songs of Syx is a single-player engine and the public script API is not an
anti-cheat platform. Native modal menus and text prompts temporarily suspend the
local simulation even though the pause and speed controls are disabled. This is
a disadvantage rather than a way to accelerate the city, but removing it fully
requires a non-modal UI or a small audited engine patch.

The standard client also owns its local save and resource files. Identity
binding, exclusive-mod checks, version checks, and the escrow/rollback journal
prevent accidental mismatches and common duplication paths; they cannot prove that a
hostile user did not modify the client, save, identity, or ledger. A competitive
public launch needs trusted accounts plus either signed/attested clients or a
server-owned city economy. Server-owned strategic values are already designed so
that this stronger model can replace client-reported local values incrementally.

Local AI raids and settlement events remain city-shard PvE. They are not
multiplayer attacks and do not alter the persistent shared-world army ledger.
