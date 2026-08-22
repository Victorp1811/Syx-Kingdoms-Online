# Authoritative server deployment

Syx Kingdoms Online is designed around one canonical persistent service. Running
several independent servers produces identical terrain but separate city ledgers,
so only the official deployment endpoint should be distributed to players.

## Container service

The repository includes a multi-stage `Dockerfile` that compiles only the common
protocol and dedicated server; no Songs of Syx game files are copied into the
image. Start the raw service on loopback:

```sh
mkdir -p server-data
# Linux hosts: make the bind mount writable by the container's unprivileged UID.
sudo chown 10001:10001 server-data
docker compose up -d --build
```

`compose.yml` binds TCP `49720` only to `127.0.0.1` and persists the canonical
world in `./server-data`. Do not expose this raw port publicly.

## TLS termination

Place a TCP-capable TLS reverse proxy or load balancer in front of loopback port
`49720`. It must present a publicly trusted certificate for the exact hostname
configured as `SERVER_HOST`, forward the decrypted byte stream without HTTP
translation, and enforce TLS 1.2 or newer. Expose only that TLS listener through
the firewall.

Client configuration for the official endpoint:

```text
SERVER_HOST = kingdoms.example.com
SERVER_PORT = 443
USE_TLS = true
TLS_CERTIFICATE_SHA256 =
```

Java performs normal certificate-chain and hostname verification. The optional
SHA-256 field pins the leaf certificate in addition to those checks; if used, it
must be updated deliberately whenever the certificate changes.

## Persistence and backups

The authoritative file is `server-data/syx-kingdoms-1000-world-v1.properties`. It
contains player authentication hashes, city ownership, event history, diplomacy,
server crowns/levies, founding profiles, real-time protection deadlines, traveling
armies, shipments, and pending deliveries. The server also stores the public
market order book and partial fills, Houses, membership requests and roles,
House/kingdom crests, all 100 provinces, formal wars, battle reports, and season
state. It writes the
world by temporary file plus atomic replacement and verifies the fixed world
geometry and terrain fingerprint during startup. Live bans and mutes are stored in
the sibling `moderation.properties` file.

Create a timestamped archive and SHA-256 sidecar:

```powershell
./tools/backup-server.ps1
```

The backup includes `moderation.properties` when present. Copy archives to storage
outside the server. Test restoration periodically on an
offline instance: stop the service, verify the archive hash, restore the world
file and matching server build/config, then start and inspect the fingerprint and
join logs before reopening traffic.

## Operational minimums

- Run exactly one writable authoritative instance.
- Keep `FIXED_GAME_SPEED=1.0`; V0.14 rejects every other value on server startup
  and every non-1x welcome on the client.
- Keep `BEGINNER_PROTECTION_MILLIS=432000000` for the canonical five-day ruleset;
  changing it affects only kingdoms founded afterward.
- Keep raw TCP loopback-only and expose only TLS.
- Back up before every release or persistence migration.
- Retain server logs and monitor rejected authentication, protocol, and rate-limit events.
- Scrape or ship the five-minute `Status registered=...` line for basic population,
  event, army, shipment, delivery, market-offer, House, and war monitoring.
- Use `tools/moderate-player.ps1 -Action Ban|Mute -PlayerId <id>` for live moderation;
  the server reloads `moderation.properties` within five seconds.
- Stop the container gracefully so the Java shutdown hook performs a final save.
- Never publish client identity files or `server-data` in Git.
