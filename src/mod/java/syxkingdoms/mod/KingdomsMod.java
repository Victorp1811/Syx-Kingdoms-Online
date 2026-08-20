package syxkingdoms.mod;

import game.GAME;
import game.VERSION;
import game.faction.FACTIONS;
import script.SCRIPT;
import settlement.stats.POP;
import snake2d.LOG;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import init.sprite.UI.UI;
import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.SharedWorld;
import syxkingdoms.protocol.SharedWorldTerrain;
import view.main.VIEW;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class KingdomsMod implements SCRIPT {
    private static RuntimeInstance active;

    @Override
    public CharSequence name() {
        return "Syx Kingdoms Online";
    }

    @Override
    public CharSequence desc() {
        return "Persistent shared-world multiplayer with independent player cities.";
    }

    @Override
    public boolean isSelectable() {
        return false;
    }

    @Override
    public boolean forceInit() {
        return true;
    }

    @Override
    public SCRIPT_INSTANCE createInstance() {
        if (active != null) {
            active.close();
        }
        active = RuntimeInstance.create();
        return active;
    }

    private static final class RuntimeInstance implements SCRIPT_INSTANCE, AutoCloseable {
        private final OnlineClient client;
        private final String startupError;
        private final TradeLedger tradeLedger;
        private long nextStatsAt;
        private String lastStatus = "";
        private int lastOnlineCount = -1;
        private int mouseX = -1;
        private int mouseY = -1;
        private String terrainSeed;
        private SharedWorldTerrain terrain;
        private String hoveredPlayerId;
        private String selectedPlayerId;
        private int chatX1 = -1;
        private int chatX2 = -1;
        private int mailX1 = -1;
        private int mailX2 = -1;
        private int neutralX1 = -1;
        private int neutralX2 = -1;
        private int allyX1 = -1;
        private int allyX2 = -1;
        private int warX1 = -1;
        private int warX2 = -1;
        private int actionY1 = -1;
        private int actionY2 = -1;
        private int transferX1 = -1;
        private int transferX2 = -1;
        private int armyX1 = -1;
        private int armyX2 = -1;
        private int strategicY1 = -1;
        private int strategicY2 = -1;
        private int goodsX1 = -1;
        private int goodsX2 = -1;
        private int marketY1 = -1;
        private int marketY2 = -1;
        private int createOfferX1 = -1;
        private int createOfferX2 = -1;
        private int previousOfferX1 = -1;
        private int previousOfferX2 = -1;
        private int nextOfferX1 = -1;
        private int nextOfferX2 = -1;
        private int buyOfferX1 = -1;
        private int buyOfferX2 = -1;
        private int cancelOfferX1 = -1;
        private int cancelOfferX2 = -1;
        private int marketOfferIndex;

        private RuntimeInstance(OnlineClient client, String startupError, TradeLedger tradeLedger) {
            this.client = client;
            this.startupError = startupError;
            this.tradeLedger = tradeLedger;
        }

        static RuntimeInstance create() {
            try {
                ClientConfig config = ClientConfig.load();
                if (!config.enabled()) {
                    LOG.ln("[Syx Kingdoms] Disabled by config.");
                    return new RuntimeInstance(null, null, null);
                }
                String modManifest = ModCompatibility.requireExclusiveSet();
                Identity identity = Identity.loadOrCreate(config.identityFile(), config.resetIdentity());
                TradeLedger tradeLedger = TradeLedger.load(config.identityFile());
                OnlineClient client = new OnlineClient(config, identity, VERSION.VERSION_STRING, modManifest,
                        (message, error) -> {
                            LOG.ln("[Syx Kingdoms] " + message);
                            if (error != null) {
                                error.printStackTrace();
                            }
                        });
                RuntimeInstance runtime = new RuntimeInstance(client, null, tradeLedger);
                runtime.restoreResourceEscrows();
                client.start();
                LOG.ln("[Syx Kingdoms] Client started as " + config.playerName() + " / " + config.cityName());
                return runtime;
            } catch (Exception e) {
                LOG.err("[Syx Kingdoms] Could not start: " + e.getMessage());
                e.printStackTrace();
                String message = e.getMessage();
                return new RuntimeInstance(null,
                        message == null || message.isBlank() ? e.getClass().getSimpleName() : message, null);
            }
        }

        @Override
        public void update(double ds) {
            if (client == null) {
                return;
            }
            client.touchGameUpdate();
            long now = System.currentTimeMillis();
            if (now >= nextStatsAt) {
                nextStatsAt = now + 1_000;
                try {
                    int population = POP.pop();
                    long credits = Math.round(FACTIONS.player().credits().credits());
                    client.updateCityStats(population, credits);
                } catch (RuntimeException e) {
                    client.updateCityStats(0, 0);
                }
                reportStatus();
            }
            processResourceTrade();

            if (client.connected()) {
                double fixed = client.fixedSpeed();
                if (Math.abs(GAME.SPEED.speedTarget() - fixed) > 0.000_001) {
                    GAME.SPEED.speedSet(fixed);
                }
            }
        }

        private void reportStatus() {
            String status = client.status();
            if (!status.equals(lastStatus)) {
                lastStatus = status;
                LOG.ln("[Syx Kingdoms] Status: " + status);
            }
            Messages.WorldState state = client.worldState();
            int onlineCount = 0;
            for (Messages.City city : state.cities()) {
                if (city.online()) {
                    onlineCount++;
                }
            }
            if (client.connected() && onlineCount != lastOnlineCount) {
                lastOnlineCount = onlineCount;
                LOG.ln("[Syx Kingdoms] Online cities visible: " + onlineCount + " / " + state.cities().size());
            }
        }

        private void restoreResourceEscrows() {
            if (client == null || tradeLedger == null) {
                return;
            }
            for (TradeLedger.Escrow escrow : tradeLedger.outbound()) {
                if ("CREATE_OFFER".equals(escrow.commandType())) {
                    client.createMarketOffer(escrow.commandId(), escrow.resourceKey(),
                            escrow.amount(), escrow.unitPrice());
                } else {
                    client.sendResource(escrow.commandId(), escrow.targetPlayerId(),
                            escrow.resourceKey(), escrow.amount());
                }
            }
        }

        private void processResourceTrade() {
            if (client == null || tradeLedger == null) {
                return;
            }
            for (Messages.CommandResult result : client.drainCommandResults()) {
                TradeLedger.Escrow escrow = tradeLedger.escrow(result.commandId());
                if (escrow == null) {
                    continue;
                }
                try {
                    if (!result.accepted()) {
                        ResourceBridge.deliver(escrow.resourceKey(), escrow.amount());
                        GAME.WarnLight("Multiplayer trade rejected; escrowed goods were returned");
                    }
                    tradeLedger.remove(result.commandId());
                } catch (Exception e) {
                    LOG.err("[Syx Kingdoms] Could not finalize resource escrow: " + e.getMessage());
                }
            }
            for (Messages.Delivery delivery : client.deliveries()) {
                try {
                    if (!tradeLedger.deliveryApplied(delivery.deliveryId())) {
                        ResourceBridge.deliver(delivery.resourceKey(), delivery.amount());
                        tradeLedger.markDeliveryApplied(delivery.deliveryId());
                        GAME.Notify("Multiplayer delivery received: " + delivery.amount()
                                + " " + delivery.resourceKey());
                    }
                    client.acknowledgeDelivery(delivery.deliveryId());
                } catch (Exception e) {
                    LOG.err("[Syx Kingdoms] Delivery remains pending: " + e.getMessage());
                }
            }
        }

        @Override
        public void render(Renderer renderer, float ds) {
            if (startupError != null) {
                renderStartupError(renderer);
                return;
            }
            if (client == null || !client.config().showOverlay()) {
                return;
            }
            Messages.WorldState state = client.worldState();
            int maxCities = client.config().overlayMaxCities();
            int shown = 0;
            int onlineCount = 0;
            for (Messages.City city : state.cities()) {
                if (city.online()) {
                    onlineCount++;
                    if (shown < maxCities) {
                        shown++;
                    }
                }
            }
            int rows = 1 + shown;
            int x1 = 10;
            int y1 = 86;
            int width = client.config().showWorldMap()
                    ? Math.max(430, client.config().worldMapWidth() + 12)
                    : 430;
            int rowHeight = Math.max(14, UI.FONT().S.height() + 2);
            int rosterHeight = 8 + rows * rowHeight;
            int mapBlockHeight = client.config().showWorldMap()
                    ? rowHeight + client.config().worldMapHeight() + 10
                    : 0;
            int socialRows = Math.min(4, client.recentEvents().size());
            int socialHeight = (socialRows + 6) * rowHeight + 22;
            int panelY2 = y1 + rosterHeight + mapBlockHeight + socialHeight;
            COLOR.BLACK.render(renderer, x1, x1 + width, y1, panelY2);
            COLOR.WHITE100.bind();
            String header = client.connected()
                    ? "Syx Kingdoms Online - " + onlineCount + " cities online - fixed " + client.fixedSpeed() + "x"
                    : "Syx Kingdoms Online - " + client.status();
            UI.FONT().S.render(renderer, header, x1 + 6, y1 + 4);
            int y = y1 + 4 + rowHeight;
            int remaining = maxCities;
            for (Messages.City city : state.cities()) {
                if (!city.online() || remaining-- <= 0) {
                    continue;
                }
                String line = city.cityName() + " - " + city.playerName()
                        + " | pop " + city.population() + " | treasury " + city.credits();
                UI.FONT().S.render(renderer, line, x1 + 12, y);
                y += rowHeight;
            }
            COLOR.unbind();

            if (client.config().showWorldMap()) {
                renderSharedWorld(renderer, state, x1 + 6, y1 + rosterHeight,
                        client.config().worldMapWidth(), client.config().worldMapHeight(), rowHeight);
            }
            renderSocial(renderer, state, x1 + 6, y1 + rosterHeight + mapBlockHeight,
                    width - 12, rowHeight, socialRows);
        }

        private void renderStartupError(Renderer renderer) {
            int x1 = 10;
            int y1 = 86;
            int width = 640;
            int rowHeight = Math.max(14, UI.FONT().S.height() + 2);
            COLOR.BLACK.render(renderer, x1, x1 + width, y1, y1 + rowHeight * 3 + 12);
            COLOR.RED100.renderFrame(renderer, x1, x1 + width, y1, y1 + rowHeight * 3 + 12, 0, 2);
            COLOR.RED100.bind();
            UI.FONT().S.render(renderer, "Syx Kingdoms Online could not start", x1 + 8, y1 + 5);
            COLOR.WHITE100.bind();
            UI.FONT().S.render(renderer, startupError, x1 + 8, y1 + 5 + rowHeight);
            UI.FONT().S.render(renderer, "Check config.txt, disable every other mod, then restart Songs of Syx.",
                    x1 + 8, y1 + 5 + rowHeight * 2);
            COLOR.unbind();
        }

        private void renderSharedWorld(Renderer renderer, Messages.WorldState state,
                int mapX1, int headerY, int mapWidth, int mapHeight, int rowHeight) {
            int mapY1 = headerY + rowHeight;
            int mapX2 = mapX1 + mapWidth;
            int mapY2 = mapY1 + mapHeight;
            SharedWorldTerrain sharedTerrain = terrain(state.worldSeed());
            for (int cellY = 0; cellY < SharedWorldTerrain.GRID_HEIGHT; cellY++) {
                int cellY1 = mapY1 + cellY * mapHeight / SharedWorldTerrain.GRID_HEIGHT;
                int cellY2 = mapY1 + (cellY + 1) * mapHeight / SharedWorldTerrain.GRID_HEIGHT;
                for (int cellX = 0; cellX < SharedWorldTerrain.GRID_WIDTH; cellX++) {
                    int cellX1 = mapX1 + cellX * mapWidth / SharedWorldTerrain.GRID_WIDTH;
                    int cellX2 = mapX1 + (cellX + 1) * mapWidth / SharedWorldTerrain.GRID_WIDTH;
                    terrainColor(sharedTerrain.cell(cellX, cellY))
                            .render(renderer, cellX1, cellX2, cellY1, cellY2);
                }
            }
            COLOR.WHITE35.renderFrame(renderer, mapX1, mapX2, mapY1, mapY2, 0, 1);

            int drawableWidth = Math.max(1, mapWidth - 10);
            int drawableHeight = Math.max(1, mapHeight - 10);
            for (int sector = 1; sector < SharedWorld.LINEAR_SCALE; sector++) {
                int worldCoordinate = sector * SharedWorld.BASE_GAME_TILE_DIMENSION;
                int gridX = mapX1 + 5 + scale(worldCoordinate, state.worldWidth(), drawableWidth);
                int gridY = mapY1 + 5 + scale(worldCoordinate, state.worldHeight(), drawableHeight);
                COLOR.WHITE10.render(renderer, gridX, gridX + 1, mapY1 + 1, mapY2 - 1);
                COLOR.WHITE10.render(renderer, mapX1 + 1, mapX2 - 1, gridY, gridY + 1);
            }
            renderArmies(renderer, state, mapX1, mapY1, drawableWidth, drawableHeight);
            Messages.City hovered = null;
            hoveredPlayerId = null;
            for (Messages.City city : state.cities()) {
                int px = mapX1 + 5 + scale(city.x(), state.worldWidth(), drawableWidth);
                int py = mapY1 + 5 + scale(city.y(), state.worldHeight(), drawableHeight);
                COLOR nodeColor;
                if (city.playerId().equals(client.ownPlayerId())) {
                    nodeColor = COLOR.ORANGE100;
                } else if ("WAR".equals(effectiveStance(city.playerId()))) {
                    nodeColor = COLOR.RED100;
                } else if ("ALLY".equals(effectiveStance(city.playerId()))) {
                    nodeColor = COLOR.BLUE100;
                } else if (city.online()) {
                    nodeColor = COLOR.GREEN100;
                } else {
                    nodeColor = COLOR.WHITE35;
                }
                nodeColor.render(renderer, px - 3, px + 4, py - 3, py + 4);
                if (Math.abs(mouseX - px) <= 5 && Math.abs(mouseY - py) <= 5) {
                    hovered = city;
                    hoveredPlayerId = city.playerId();
                }
            }

            COLOR.WHITE100.bind();
            UI.FONT().S.render(renderer,
                    "10x world " + state.worldWidth() + "x" + state.worldHeight()
                            + " - " + SharedWorld.SECTOR_COUNT + " base-map sectors - "
                            + state.cities().size() + " cities",
                    mapX1, headerY + 1);
            if (hovered != null) {
                String detail = hovered.cityName() + " - " + hovered.playerName()
                        + " | " + (hovered.online() ? "online" : "offline")
                        + " | " + effectiveStance(hovered.playerId()).toLowerCase()
                        + " | sector " + (SharedWorld.sectorX(hovered.x()) + 1) + ","
                        + (SharedWorld.sectorY(hovered.y()) + 1)
                        + " | " + hovered.x() + "," + hovered.y();
                int tooltipY = Math.min(mapY2 - rowHeight, Math.max(mapY1 + 2, mouseY + 8));
                COLOR.unbind();
                COLOR.BLACK.render(renderer, mapX1 + 4, mapX2 - 4, tooltipY, tooltipY + rowHeight);
                COLOR.WHITE100.bind();
                UI.FONT().S.render(renderer, detail, mapX1 + 8, tooltipY + 1);
            }
            COLOR.unbind();
        }

        private void renderSocial(Renderer renderer, Messages.WorldState state,
                int x1, int y1, int width, int rowHeight, int eventRows) {
            COLOR.WHITE100.bind();
            Messages.PlayerState strategic = client.playerState();
            UI.FONT().S.render(renderer, "Persistent world events | crowns " + strategic.crowns()
                    + " | levies " + strategic.levies() + " | armies " + client.armies().size()
                    + " | deliveries " + client.deliveries().size()
                    + " | offers " + client.marketOffers().size(), x1, y1 + 1);
            List<Messages.WorldEvent> events = client.recentEvents();
            int start = Math.max(0, events.size() - eventRows);
            int y = y1 + rowHeight;
            for (int i = start; i < events.size(); i++) {
                UI.FONT().S.render(renderer, truncate(eventLine(events.get(i), state), 82), x1 + 6, y);
                y += rowHeight;
            }

            Messages.City selected = selectedCity(state);
            String selectedLine = selected == null
                    ? "Select another city node for mail and diplomacy."
                    : "Selected: " + selected.cityName() + " - " + selected.playerName()
                            + " | relation " + effectiveStance(selected.playerId()).toLowerCase();
            UI.FONT().S.render(renderer, truncate(selectedLine, 82), x1, y);
            y += rowHeight;
            COLOR.unbind();

            actionY1 = y;
            actionY2 = y + rowHeight + 2;
            chatX1 = x1;
            chatX2 = x1 + 88;
            mailX1 = chatX2 + 6;
            mailX2 = mailX1 + 82;
            neutralX1 = mailX2 + 6;
            neutralX2 = neutralX1 + 88;
            allyX1 = neutralX2 + 6;
            allyX2 = allyX1 + 68;
            warX1 = allyX2 + 6;
            warX2 = Math.min(x1 + width, warX1 + 68);
            renderButton(renderer, chatX1, chatX2, actionY1, actionY2, "Global chat", true);
            renderButton(renderer, mailX1, mailX2, actionY1, actionY2, "Mail", selected != null);
            renderButton(renderer, neutralX1, neutralX2, actionY1, actionY2, "Neutral", selected != null);
            renderButton(renderer, allyX1, allyX2, actionY1, actionY2, "Ally", selected != null);
            renderButton(renderer, warX1, warX2, actionY1, actionY2, "War", selected != null);

            strategicY1 = actionY2 + 3;
            strategicY2 = strategicY1 + rowHeight + 2;
            transferX1 = x1;
            transferX2 = x1 + 128;
            armyX1 = transferX2 + 6;
            armyX2 = armyX1 + 106;
            goodsX1 = armyX2 + 6;
            goodsX2 = Math.min(x1 + width, goodsX1 + 128);
            renderButton(renderer, transferX1, transferX2, strategicY1, strategicY2,
                    "Transfer crowns", selected != null);
            renderButton(renderer, armyX1, armyX2, strategicY1, strategicY2,
                    "Send army", selected != null && "WAR".equals(effectiveStance(selected.playerId())));
            renderButton(renderer, goodsX1, goodsX2, strategicY1, strategicY2,
                    "Send goods", selected != null && !"WAR".equals(effectiveStance(selected.playerId())));

            int marketInfoY = strategicY2 + 3;
            Messages.MarketOffer offer = selectedMarketOffer();
            COLOR.WHITE100.bind();
            String marketLine = offer == null
                    ? "Public market: no active offers"
                    : "Market " + (marketOfferIndex + 1) + "/" + client.marketOffers().size()
                            + " | " + offer.quantity() + " " + offer.resourceKey()
                            + " @ " + offer.unitPrice() + " crowns | " + offer.sellerName()
                            + " | id " + shortId(offer.offerId());
            UI.FONT().S.render(renderer, truncate(marketLine, 82), x1, marketInfoY + 1);
            COLOR.unbind();

            marketY1 = marketInfoY + rowHeight;
            marketY2 = marketY1 + rowHeight + 2;
            createOfferX1 = x1;
            createOfferX2 = x1 + 110;
            previousOfferX1 = createOfferX2 + 5;
            previousOfferX2 = previousOfferX1 + 55;
            nextOfferX1 = previousOfferX2 + 5;
            nextOfferX2 = nextOfferX1 + 55;
            buyOfferX1 = nextOfferX2 + 5;
            buyOfferX2 = buyOfferX1 + 72;
            cancelOfferX1 = buyOfferX2 + 5;
            cancelOfferX2 = Math.min(x1 + width, cancelOfferX1 + 84);
            renderButton(renderer, createOfferX1, createOfferX2, marketY1, marketY2,
                    "Create offer", true);
            renderButton(renderer, previousOfferX1, previousOfferX2, marketY1, marketY2,
                    "Prev", offer != null);
            renderButton(renderer, nextOfferX1, nextOfferX2, marketY1, marketY2,
                    "Next", offer != null);
            renderButton(renderer, buyOfferX1, buyOfferX2, marketY1, marketY2,
                    "Buy", offer != null && !offer.sellerPlayerId().equals(client.ownPlayerId()));
            renderButton(renderer, cancelOfferX1, cancelOfferX2, marketY1, marketY2,
                    "Cancel", offer != null && offer.sellerPlayerId().equals(client.ownPlayerId()));
        }

        private void renderArmies(Renderer renderer, Messages.WorldState state,
                int mapX1, int mapY1, int drawableWidth, int drawableHeight) {
            long now = System.currentTimeMillis();
            for (Messages.Army army : client.armies()) {
                Messages.City owner = city(army.ownerPlayerId(), state);
                Messages.City target = city(army.targetPlayerId(), state);
                if (owner == null || target == null) {
                    continue;
                }
                double duration = Math.max(1.0, army.arrivesEpochMillis() - army.departedEpochMillis());
                double progress = Math.max(0.0, Math.min(1.0,
                        (now - army.departedEpochMillis()) / duration));
                double worldX = owner.x() + (target.x() - owner.x()) * progress;
                double worldY = owner.y() + (target.y() - owner.y()) * progress;
                int px = mapX1 + 5 + scale((int) Math.round(worldX), state.worldWidth(), drawableWidth);
                int py = mapY1 + 5 + scale((int) Math.round(worldY), state.worldHeight(), drawableHeight);
                COLOR color = army.ownerPlayerId().equals(client.ownPlayerId()) ? COLOR.ORANGE150 : COLOR.RED100;
                color.render(renderer, px - 2, px + 3, py - 2, py + 3);
            }
        }

        private void renderButton(Renderer renderer, int x1, int x2, int y1, int y2,
                String label, boolean enabled) {
            COLOR fill = enabled && mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2
                    ? COLOR.WHITE35 : COLOR.WHITE15;
            fill.render(renderer, x1, x2, y1, y2);
            (enabled ? COLOR.WHITE65 : COLOR.WHITE25).renderFrame(renderer, x1, x2, y1, y2, 0, 1);
            (enabled ? COLOR.WHITE100 : COLOR.WHITE35).bind();
            UI.FONT().S.render(renderer, label, x1 + 4, y1 + 1);
            COLOR.unbind();
        }

        private Messages.City selectedCity(Messages.WorldState state) {
            if (selectedPlayerId == null) {
                return null;
            }
            for (Messages.City city : state.cities()) {
                if (city.playerId().equals(selectedPlayerId) && !city.playerId().equals(client.ownPlayerId())) {
                    return city;
                }
            }
            selectedPlayerId = null;
            return null;
        }

        private Messages.MarketOffer selectedMarketOffer() {
            List<Messages.MarketOffer> offers = client.marketOffers();
            if (offers.isEmpty()) {
                marketOfferIndex = 0;
                return null;
            }
            marketOfferIndex = Math.floorMod(marketOfferIndex, offers.size());
            return offers.get(marketOfferIndex);
        }

        private String effectiveStance(String playerId) {
            for (Messages.Relation relation : client.relations()) {
                if (relation.playerId().equals(playerId)) {
                    return relation.effectiveStance();
                }
            }
            return "NEUTRAL";
        }

        private String eventLine(Messages.WorldEvent event, Messages.WorldState state) {
            return switch (event.type()) {
                case "CHAT" -> "[Global] " + event.actorName() + ": " + event.text();
                case "MAIL" -> "[Mail] " + event.actorName() + ": " + event.text();
                case "STANCE" -> "[Diplomacy] " + event.actorName() + " -> "
                        + cityName(event.targetPlayerId(), state) + ": " + event.argument();
                case "TRANSFER" -> "[Transfer] " + event.actorName() + " sent " + event.value1()
                        + " crowns to " + cityName(event.targetPlayerId(), state);
                case "ARMY_SENT" -> "[Army] " + event.actorName() + " sent " + event.value1()
                        + " levies toward " + cityName(event.targetPlayerId(), state);
                case "BATTLE" -> "[Battle] " + event.actorName() + " at "
                        + cityName(event.targetPlayerId(), state) + ": " + event.text()
                        + " | survivors " + event.value1() + " | crowns " + event.value2();
                case "SHIPMENT_SENT" -> "[Trade] " + event.actorName() + " sent " + event.value1()
                        + " " + event.text() + " to " + cityName(event.targetPlayerId(), state);
                case "SHIPMENT_ARRIVED" -> "[Trade] " + event.value1() + " " + event.text()
                        + " arrived for " + cityName(event.targetPlayerId(), state);
                case "DELIVERY_CLAIMED" -> "[Trade] " + event.actorName() + " received "
                        + event.value1() + " " + event.text();
                case "OFFER_CREATED" -> "[Market] " + event.actorName() + " listed "
                        + event.value1() + " " + event.text() + " @ " + event.value2();
                case "OFFER_BOUGHT" -> "[Market] " + event.actorName() + " bought "
                        + event.value1() + " " + event.text() + " for " + event.value2() + " crowns";
                case "OFFER_CANCELLED" -> "[Market] " + event.actorName() + " cancelled "
                        + event.value1() + " " + event.text();
                default -> "[" + event.type() + "] " + event.actorName() + " " + event.text();
            };
        }

        private static String cityName(String playerId, Messages.WorldState state) {
            for (Messages.City city : state.cities()) {
                if (city.playerId().equals(playerId)) {
                    return city.cityName();
                }
            }
            return playerId;
        }

        private static Messages.City city(String playerId, Messages.WorldState state) {
            for (Messages.City city : state.cities()) {
                if (city.playerId().equals(playerId)) {
                    return city;
                }
            }
            return null;
        }

        private static String truncate(String value, int max) {
            return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
        }

        private SharedWorldTerrain terrain(String seed) {
            if (terrain == null || !seed.equals(terrainSeed)) {
                terrainSeed = seed;
                terrain = new SharedWorldTerrain(seed);
            }
            return terrain;
        }

        private static COLOR terrainColor(SharedWorldTerrain.Terrain terrain) {
            return switch (terrain) {
                case WATER -> COLOR.BLUEDARK;
                case PLAINS -> COLOR.MEDIUM_BROWN;
                case FOREST -> COLOR.DARK_GREEN;
                case MOUNTAIN -> COLOR.WHITE35;
            };
        }

        private static int scale(int coordinate, int worldSize, int drawableSize) {
            if (worldSize <= 1) {
                return 0;
            }
            return (int) Math.round((double) coordinate * drawableSize / (worldSize - 1.0));
        }

        @Override
        public void hover(COORDINATE mouse, boolean mouseHasMoved) {
            if (mouse != null) {
                mouseX = mouse.x();
                mouseY = mouse.y();
            }
        }

        @Override
        public void mouseClick(MButt button) {
            if (button != MButt.LEFT || client == null || !client.connected()) {
                return;
            }
            if (hoveredPlayerId != null && !hoveredPlayerId.equals(client.ownPlayerId())) {
                selectedPlayerId = hoveredPlayerId;
                GAME.Notify("Selected " + selectedPlayerId);
                return;
            }
            Messages.City selected = selectedCity(client.worldState());
            if (mouseY >= actionY1 && mouseY < actionY2) {
                if (insideX(chatX1, chatX2)) {
                    requestText("Global multiplayer chat", text -> client.sendGlobalChat(text));
                    return;
                }
                if (selected == null) {
                    return;
                }
                if (insideX(mailX1, mailX2)) {
                    requestText("Private mail to " + selected.cityName(),
                            text -> client.sendMail(selected.playerId(), text));
                } else if (insideX(neutralX1, neutralX2)) {
                    queueStance(selected, "NEUTRAL");
                } else if (insideX(allyX1, allyX2)) {
                    queueStance(selected, "ALLY");
                } else if (insideX(warX1, warX2)) {
                    queueStance(selected, "WAR");
                }
                return;
            }
            if (mouseY >= marketY1 && mouseY < marketY2) {
                Messages.MarketOffer offer = selectedMarketOffer();
                if (insideX(createOfferX1, createOfferX2)) {
                    requestText("Create market offer (RESOURCE QUANTITY UNIT_PRICE)",
                            this::queueMarketOffer);
                } else if (offer != null && insideX(previousOfferX1, previousOfferX2)) {
                    marketOfferIndex--;
                } else if (offer != null && insideX(nextOfferX1, nextOfferX2)) {
                    marketOfferIndex++;
                } else if (offer != null && insideX(buyOfferX1, buyOfferX2)
                        && !offer.sellerPlayerId().equals(client.ownPlayerId())) {
                    requestPositiveLong("Quantity to buy from " + offer.sellerName(), amount -> {
                        if (amount > Integer.MAX_VALUE) {
                            GAME.WarnLight("Purchase quantity is too large");
                        } else {
                            client.buyMarketOffer(offer.offerId(), (int) amount);
                        }
                    });
                } else if (offer != null && insideX(cancelOfferX1, cancelOfferX2)
                        && offer.sellerPlayerId().equals(client.ownPlayerId())) {
                    client.cancelMarketOffer(offer.offerId());
                    GAME.Notify("Market cancellation queued");
                }
                return;
            }
            if (selected == null || mouseY < strategicY1 || mouseY >= strategicY2) {
                return;
            }
            if (insideX(transferX1, transferX2)) {
                requestPositiveLong("Crowns to transfer to " + selected.cityName(),
                        amount -> client.transferCrowns(selected.playerId(), amount));
            } else if (insideX(armyX1, armyX2) && "WAR".equals(effectiveStance(selected.playerId()))) {
                requestPositiveLong("Levies to send against " + selected.cityName(),
                        amount -> {
                            if (amount > Integer.MAX_VALUE) {
                                GAME.WarnLight("Army size is too large");
                            } else {
                                client.sendArmy(selected.playerId(), (int) amount);
                            }
                        });
            } else if (insideX(goodsX1, goodsX2) && !"WAR".equals(effectiveStance(selected.playerId()))) {
                requestText("Send goods to " + selected.cityName() + " (RESOURCE_KEY AMOUNT)",
                        text -> queueResourceShipment(selected, text));
            }
        }

        private boolean insideX(int x1, int x2) {
            return mouseX >= x1 && mouseX < x2;
        }

        private void requestText(String title, java.util.function.Consumer<String> receiver) {
            VIEW.inters().input.requestInput(value -> {
                if (value == null) {
                    return;
                }
                String text = value.toString().trim();
                if (!text.isEmpty()) {
                    receiver.accept(text);
                    GAME.Notify("Multiplayer action queued");
                }
            }, title);
        }

        private void queueStance(Messages.City selected, String stance) {
            client.setStance(selected.playerId(), stance);
            GAME.Notify("Diplomacy action queued: " + stance + " toward " + selected.cityName());
        }

        private void requestPositiveLong(String title, java.util.function.LongConsumer receiver) {
            requestText(title, text -> {
                try {
                    long amount = Long.parseLong(text.trim());
                    if (amount < 1) {
                        throw new NumberFormatException();
                    }
                    receiver.accept(amount);
                } catch (NumberFormatException e) {
                    GAME.WarnLight("Enter a positive whole number");
                }
            });
        }

        private void queueResourceShipment(Messages.City selected, String input) {
            String[] fields = input.trim().split("\\s+");
            if (fields.length != 2) {
                GAME.WarnLight("Enter a resource key and amount, for example: WOOD 100");
                return;
            }
            String resourceKey = ResourceBridge.canonicalKey(fields[0]);
            int amount;
            try {
                amount = Integer.parseInt(fields[1]);
            } catch (NumberFormatException e) {
                GAME.WarnLight("Resource amount must be a whole number");
                return;
            }
            if (resourceKey == null || amount < 1 || amount > 10_000) {
                GAME.WarnLight("Unknown resource or amount outside 1-10000");
                return;
            }
            int removed = 0;
            try {
                removed = ResourceBridge.escrow(resourceKey, amount);
                if (removed != amount) {
                    if (removed > 0) {
                        ResourceBridge.deliver(resourceKey, removed);
                    }
                    GAME.WarnLight("Not enough unreserved " + resourceKey + " in stockpiles");
                    return;
                }
                String commandId = UUID.randomUUID().toString();
                TradeLedger.Escrow escrow = new TradeLedger.Escrow(commandId,
                        selected.playerId(), resourceKey, amount);
                tradeLedger.add(escrow);
                client.sendResource(commandId, selected.playerId(), resourceKey, amount);
                GAME.Notify("Resource shipment escrowed and queued");
            } catch (Exception e) {
                if (removed == amount) {
                    try {
                        ResourceBridge.deliver(resourceKey, amount);
                    } catch (Exception refundError) {
                        LOG.err("[Syx Kingdoms] CRITICAL: resource escrow refund failed: "
                                + refundError.getMessage());
                    }
                }
                GAME.WarnLight("Could not queue shipment: " + e.getMessage());
            }
        }

        private void queueMarketOffer(String input) {
            String[] fields = input.trim().split("\\s+");
            if (fields.length != 3) {
                GAME.WarnLight("Enter resource, quantity, and unit price, for example: WOOD 100 3");
                return;
            }
            String resourceKey = ResourceBridge.canonicalKey(fields[0]);
            int amount;
            long unitPrice;
            try {
                amount = Integer.parseInt(fields[1]);
                unitPrice = Long.parseLong(fields[2]);
            } catch (NumberFormatException e) {
                GAME.WarnLight("Quantity and unit price must be whole numbers");
                return;
            }
            if (resourceKey == null || amount < 1 || amount > 10_000
                    || unitPrice < 1 || unitPrice > 1_000_000) {
                GAME.WarnLight("Unknown resource, quantity outside 1-10000, or price outside 1-1000000");
                return;
            }
            int removed = 0;
            try {
                removed = ResourceBridge.escrow(resourceKey, amount);
                if (removed != amount) {
                    if (removed > 0) {
                        ResourceBridge.deliver(resourceKey, removed);
                    }
                    GAME.WarnLight("Not enough unreserved " + resourceKey + " in stockpiles");
                    return;
                }
                String commandId = UUID.randomUUID().toString();
                tradeLedger.add(TradeLedger.Escrow.marketOffer(commandId, resourceKey, amount, unitPrice));
                client.createMarketOffer(commandId, resourceKey, amount, unitPrice);
                GAME.Notify("Market goods escrowed and offer queued");
            } catch (Exception e) {
                if (removed == amount) {
                    try {
                        ResourceBridge.deliver(resourceKey, amount);
                    } catch (Exception refundError) {
                        LOG.err("[Syx Kingdoms] CRITICAL: market escrow refund failed: "
                                + refundError.getMessage());
                    }
                }
                GAME.WarnLight("Could not create offer: " + e.getMessage());
            }
        }

        private static String shortId(String value) {
            return value.length() <= 8 ? value : value.substring(0, 8);
        }

        @Override
        public void save(FilePutter file) {
            file.i(1);
        }

        @Override
        public void load(FileGetter file) throws IOException {
            file.i();
        }

        @Override
        public boolean handleBrokenSavedState() {
            return true;
        }

        @Override
        public void close() {
            if (client != null) {
                client.close();
            }
        }
    }
}
