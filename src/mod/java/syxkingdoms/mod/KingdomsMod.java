package syxkingdoms.mod;

import game.GAME;
import game.VERSION;
import game.faction.FACTIONS;
import init.sprite.BitmapSprite;
import script.SCRIPT;
import settlement.stats.POP;
import snake2d.LOG;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import init.sprite.UI.UI;
import syxkingdoms.protocol.Messages;
import syxkingdoms.protocol.CrestDesign;
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
        private static final int CREST_PANEL_X1 = 10;
        private static final int CREST_PANEL_Y1 = 86;
        private static final int CREST_PANEL_X2 = 630;
        private static final int CREST_PANEL_Y2 = 548;
        private static final int CREST_GRID_X1 = 22;
        private static final int CREST_GRID_Y1 = 126;
        private static final int CREST_CELL = 12;
        private static final int CREST_SIDE_X1 = 420;
        private static final int CREST_PALETTE_Y1 = 132;
        private static final int CREST_PALETTE_CELL = 28;
        private static final int CREST_PALETTE_GAP = 2;
        private final OnlineClient client;
        private final String startupError;
        private final TradeLedger tradeLedger;
        private final OnlineRules onlineRules = new OnlineRules();
        private volatile String fatalOnlineError;
        private long loadedLedgerRevision;
        private boolean rollbackReconciliationPending;
        private long nextStatsAt;
        private String lastStatus = "";
        private int lastOnlineCount = -1;
        private int mouseX = -1;
        private int mouseY = -1;
        private String terrainSeed;
        private SharedWorldTerrain terrain;
        private String hoveredPlayerId;
        private String selectedPlayerId;
        private int localChatX1 = -1;
        private int localChatX2 = -1;
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
        private int crestEditX1 = -1;
        private int crestEditX2 = -1;
        private int crestEditY1 = -1;
        private int crestEditY2 = -1;
        private boolean crestEditorOpen;
        private CrestEditorModel crestEditor;
        private boolean foundingPromptActive;
        private long nextFoundingPromptAt;

        private RuntimeInstance(OnlineClient client, String startupError, TradeLedger tradeLedger) {
            this.client = client;
            this.startupError = startupError;
            this.tradeLedger = tradeLedger;
            this.loadedLedgerRevision = tradeLedger == null ? 0 : tradeLedger.revision();
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
                LOG.ln("[Syx Kingdoms] Client started; the server will load or found its kingdom profile.");
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
            boolean simulationReady = client != null && fatalOnlineError == null && client.simulationReady();
            onlineRules.enforce(simulationReady);
            if (client == null) {
                return;
            }
            client.touchGameUpdate();
            if (fatalOnlineError == null) {
                String violation = onlineRules.runtimeViolation();
                if (!violation.isBlank()) {
                    setFatalOnlineError(violation);
                }
            }
            if (fatalOnlineError != null) {
                return;
            }
            reconcileRollbackJournal();
            if (fatalOnlineError != null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (client.foundingRequired() && !foundingPromptActive && now >= nextFoundingPromptAt) {
                beginFoundingPrompts();
            }
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
                        tradeLedger.reject(result.commandId());
                        ResourceBridge.deliver(escrow.resourceKey(), escrow.amount());
                        GAME.WarnLight("Multiplayer trade rejected; escrowed goods were returned");
                    } else {
                        tradeLedger.accept(result.commandId());
                    }
                } catch (Exception e) {
                    LOG.err("[Syx Kingdoms] Could not finalize resource escrow: " + e.getMessage());
                }
            }
            for (Messages.Delivery delivery : client.deliveries()) {
                try {
                    if (!tradeLedger.deliveryApplied(delivery.deliveryId())) {
                        tradeLedger.markDeliveryApplied(delivery.deliveryId());
                        ResourceBridge.deliver(delivery.resourceKey(), delivery.amount());
                        GAME.Notify("Multiplayer delivery received: " + delivery.amount()
                                + " " + delivery.resourceKey());
                    }
                    client.acknowledgeDelivery(delivery.deliveryId());
                } catch (Exception e) {
                    LOG.err("[Syx Kingdoms] Delivery remains pending: " + e.getMessage());
                }
            }
        }

        private void reconcileRollbackJournal() {
            if (!rollbackReconciliationPending || tradeLedger == null) {
                return;
            }
            rollbackReconciliationPending = false;
            try {
                for (TradeLedger.Debit debit : tradeLedger.debitsAfter(loadedLedgerRevision)) {
                    TradeLedger.Escrow escrow = debit.escrow();
                    int removed = ResourceBridge.escrow(escrow.resourceKey(), escrow.amount());
                    if (removed != escrow.amount()) {
                        setFatalOnlineError("ROLLBACK_RESOURCE_DIVERGENCE: save does not contain enough "
                                + escrow.resourceKey() + " to reapply multiplayer debit "
                                + escrow.commandId());
                        return;
                    }
                }
                if (tradeLedger.revision() > loadedLedgerRevision) {
                    GAME.Notify("Multiplayer rollback journal reapplied later resource debits");
                }
            } catch (Exception e) {
                setFatalOnlineError("ROLLBACK_JOURNAL_ERROR: " + e.getMessage());
            }
        }

        @Override
        public void render(Renderer renderer, float ds) {
            boolean simulationReady = client != null && fatalOnlineError == null && client.simulationReady();
            onlineRules.enforceFrame(simulationReady);
            String activeError = fatalOnlineError == null ? startupError : fatalOnlineError;
            if (activeError != null) {
                renderStartupError(renderer, activeError);
                return;
            }
            if (client == null || !client.config().showOverlay()) {
                return;
            }
            if (crestEditorOpen && crestEditor != null) {
                renderCrestEditor(renderer);
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
            String header = client.simulationReady()
                    ? "Syx Kingdoms Online - " + onlineCount + " cities online - fixed " + client.fixedSpeed() + "x"
                    : "Syx Kingdoms Online - CITY FROZEN - " + client.status();
            UI.FONT().S.render(renderer, header, x1 + 6, y1 + 4);
            crestEditX1 = x1 + width - 86;
            crestEditX2 = x1 + width - 6;
            crestEditY1 = y1 + 2;
            crestEditY2 = y1 + rowHeight + 3;
            renderButton(renderer, crestEditX1, crestEditX2, crestEditY1, crestEditY2,
                    "Edit crest", client.simulationReady());
            int y = y1 + 4 + rowHeight;
            int remaining = maxCities;
            for (Messages.City city : state.cities()) {
                if (!city.online() || remaining-- <= 0) {
                    continue;
                }
                String line = city.cityName() + " - " + city.kingdomName() + " (" + city.playerName() + ")"
                        + " | reported population " + city.population();
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

        private void renderStartupError(Renderer renderer, String error) {
            int x1 = 10;
            int y1 = 86;
            int width = 640;
            int rowHeight = Math.max(14, UI.FONT().S.height() + 2);
            COLOR.BLACK.render(renderer, x1, x1 + width, y1, y1 + rowHeight * 3 + 12);
            COLOR.RED100.renderFrame(renderer, x1, x1 + width, y1, y1 + rowHeight * 3 + 12, 0, 2);
            COLOR.RED100.bind();
            UI.FONT().S.render(renderer, "Syx Kingdoms Online could not start", x1 + 8, y1 + 5);
            COLOR.WHITE100.bind();
            UI.FONT().S.render(renderer, error, x1 + 8, y1 + 5 + rowHeight);
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
                String detail = hovered.cityName() + " - " + hovered.kingdomName()
                        + " (" + hovered.playerName() + ")"
                        + " | " + (hovered.online() ? "online" : "offline")
                        + (protectedNow(hovered) ? " | protected " + protectionRemaining(hovered.protectionEndsEpochMillis()) : "")
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
            Messages.City previewCity = hovered != null ? hovered : selectedCityIncludingSelf(state);
            if (previewCity != null) {
                CrestDesign preview = client.crest(previewCity.playerId());
                if (preview != null) {
                    int previewX = mapX2 - 72;
                    int previewY = mapY1 + 6;
                    COLOR.BLACK.render(renderer, previewX - 3, previewX + 67, previewY - 3, previewY + 67);
                    COLOR.WHITE35.renderFrame(renderer, previewX - 3, previewX + 67,
                            previewY - 3, previewY + 67, 0, 1);
                    renderCrest(renderer, preview, previewX, previewY, 2, false);
                }
            }
            COLOR.unbind();
        }

        private void renderCrestEditor(Renderer renderer) {
            updateCrestStroke();
            COLOR.BLACK.render(renderer, CREST_PANEL_X1, CREST_PANEL_X2, CREST_PANEL_Y1, CREST_PANEL_Y2);
            COLOR.WHITE65.renderFrame(renderer, CREST_PANEL_X1, CREST_PANEL_X2,
                    CREST_PANEL_Y1, CREST_PANEL_Y2, 0, 2);
            COLOR.WHITE100.bind();
            UI.FONT().S.render(renderer, "Kingdom crest editor - 32x32 pixels - 16 palette slots",
                    CREST_PANEL_X1 + 12, CREST_PANEL_Y1 + 8);
            UI.FONT().S.render(renderer, "Left-drag paints | right-drag erases | slot 0 is transparent",
                    CREST_PANEL_X1 + 12, CREST_PANEL_Y1 + 24);
            COLOR.unbind();

            CrestDesign design = crestEditor.design();
            renderCrest(renderer, design, CREST_GRID_X1, CREST_GRID_Y1, CREST_CELL, true);
            int hoverPixelX = (mouseX - CREST_GRID_X1) / CREST_CELL;
            int hoverPixelY = (mouseY - CREST_GRID_Y1) / CREST_CELL;
            if (insideCrestGrid() && hoverPixelX >= 0 && hoverPixelY >= 0
                    && hoverPixelX < CrestDesign.WIDTH && hoverPixelY < CrestDesign.HEIGHT) {
                int x1 = CREST_GRID_X1 + hoverPixelX * CREST_CELL;
                int y1 = CREST_GRID_Y1 + hoverPixelY * CREST_CELL;
                COLOR.WHITE100.renderFrame(renderer, x1, x1 + CREST_CELL, y1, y1 + CREST_CELL, 0, 1);
            }

            COLOR.WHITE100.bind();
            UI.FONT().S.render(renderer, "Palette", CREST_SIDE_X1, CREST_PALETTE_Y1 - 16);
            COLOR.unbind();
            for (int index = 0; index < CrestDesign.PALETTE_SIZE; index++) {
                int column = index % 4;
                int row = index / 4;
                int x1 = CREST_SIDE_X1 + column * (CREST_PALETTE_CELL + CREST_PALETTE_GAP);
                int y1 = CREST_PALETTE_Y1 + row * (CREST_PALETTE_CELL + CREST_PALETTE_GAP);
                if (index == 0) {
                    COLOR.WHITE10.render(renderer, x1, x1 + CREST_PALETTE_CELL,
                            y1, y1 + CREST_PALETTE_CELL);
                    COLOR.RED100.renderFrame(renderer, x1 + 5, x1 + CREST_PALETTE_CELL - 5,
                            y1 + 5, y1 + CREST_PALETTE_CELL - 5, 0, 1);
                } else {
                    renderRgb(renderer, design.color(index), x1, x1 + CREST_PALETTE_CELL,
                            y1, y1 + CREST_PALETTE_CELL);
                }
                COLOR.WHITE35.renderFrame(renderer, x1, x1 + CREST_PALETTE_CELL,
                        y1, y1 + CREST_PALETTE_CELL, 0, 1);
                if (index == crestEditor.selectedPalette()) {
                    COLOR.WHITE100.renderFrame(renderer, x1 - 2, x1 + CREST_PALETTE_CELL + 2,
                            y1 - 2, y1 + CREST_PALETTE_CELL + 2, 0, 2);
                }
            }

            renderButton(renderer, 420, 538, 260, 282, "Edit selected color",
                    crestEditor.selectedPalette() != 0);
            COLOR.WHITE100.bind();
            UI.FONT().S.render(renderer, "Large preview", 420, 294);
            COLOR.unbind();
            COLOR.WHITE10.render(renderer, 420, 552, 310, 442);
            renderCrest(renderer, design, 422, 312, 4, false);
            COLOR.WHITE35.renderFrame(renderer, 420, 552, 310, 442, 0, 1);

            renderButton(renderer, 420, 474, 452, 474, "Undo", crestEditor.canUndo());
            renderButton(renderer, 480, 534, 452, 474, "Redo", crestEditor.canRedo());
            renderButton(renderer, 540, 610, 452, 474, "Clear", design.hasVisiblePixels());
            renderButton(renderer, 420, 480, 480, 502, "Mirror H", design.hasVisiblePixels());
            renderButton(renderer, 486, 546, 480, 502, "Mirror V", design.hasVisiblePixels());
            renderButton(renderer, 552, 618, 480, 502, "Import", true);
            renderButton(renderer, 22, 112, 516, 540, "Save crest", design.hasVisiblePixels());
            renderButton(renderer, 120, 210, 516, 540, "Cancel", true);
        }

        private void renderCrest(Renderer renderer, CrestDesign design, int x1, int y1,
                int pixelSize, boolean checker) {
            for (int y = 0; y < CrestDesign.HEIGHT; y++) {
                for (int x = 0; x < CrestDesign.WIDTH; x++) {
                    int px1 = x1 + x * pixelSize;
                    int py1 = y1 + y * pixelSize;
                    int paletteIndex = design.pixel(x, y);
                    if (paletteIndex == 0) {
                        if (checker) {
                            ((x + y) % 2 == 0 ? COLOR.WHITE10 : COLOR.WHITE15).render(renderer,
                                    px1, px1 + pixelSize, py1, py1 + pixelSize);
                        }
                    } else {
                        renderRgb(renderer, design.color(paletteIndex), px1, px1 + pixelSize,
                                py1, py1 + pixelSize);
                    }
                }
            }
            COLOR.WHITE35.renderFrame(renderer, x1, x1 + CrestDesign.WIDTH * pixelSize,
                    y1, y1 + CrestDesign.HEIGHT * pixelSize, 0, 1);
        }

        private static void renderRgb(Renderer renderer, int rgb, int x1, int x2, int y1, int y2) {
            ColorImp.TMP.set((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF)
                    .render(renderer, x1, x2, y1, y2);
        }

        private void renderSocial(Renderer renderer, Messages.WorldState state,
                int x1, int y1, int width, int rowHeight, int eventRows) {
            COLOR.WHITE100.bind();
            Messages.PlayerState strategic = client.playerState();
            UI.FONT().S.render(renderer, "Persistent world events | crowns " + strategic.crowns()
                    + " | levies " + strategic.levies() + " | armies " + client.armies().size()
                    + " | deliveries " + client.deliveries().size()
                    + " | offers " + client.marketOffers().size()
                    + (strategic.protectionEndsEpochMillis() > System.currentTimeMillis()
                            ? " | protected " + protectionRemaining(strategic.protectionEndsEpochMillis()) : ""),
                    x1, y1 + 1);
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
                    : "Selected: " + selected.cityName() + " - " + selected.kingdomName()
                            + " (" + selected.playerName() + ") | relation "
                            + effectiveStance(selected.playerId()).toLowerCase()
                            + (protectedNow(selected) ? " | beginner protection" : "");
            UI.FONT().S.render(renderer, truncate(selectedLine, 82), x1, y);
            y += rowHeight;
            COLOR.unbind();

            actionY1 = y;
            actionY2 = y + rowHeight + 2;
            localChatX1 = x1;
            localChatX2 = x1 + 76;
            chatX1 = localChatX2 + 6;
            chatX2 = chatX1 + 82;
            mailX1 = chatX2 + 6;
            mailX2 = mailX1 + 56;
            neutralX1 = mailX2 + 6;
            neutralX2 = neutralX1 + 74;
            allyX1 = neutralX2 + 6;
            allyX2 = allyX1 + 52;
            warX1 = allyX2 + 6;
            warX2 = Math.min(x1 + width, warX1 + 52);
            renderButton(renderer, localChatX1, localChatX2, actionY1, actionY2, "Local", true);
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
                    "Send army", selected != null && !protectedNow(selected)
                            && "WAR".equals(effectiveStance(selected.playerId())));
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
                case "LOCAL_CHAT" -> "[Local] " + event.actorName() + ": " + event.text();
                case "CREST_UPDATED" -> "[Crest] " + event.actorName() + " updated their kingdom crest";
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
                case "KINGDOM_FOUNDED" -> "[World] " + event.actorName() + " founded "
                        + event.text() + ", capital " + event.argument();
                case "PROTECTION_ENDED" -> "[World] " + event.actorName()
                        + " ended beginner protection by launching an army";
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

        private Messages.City selectedCityIncludingSelf(Messages.WorldState state) {
            String playerId = selectedPlayerId == null ? client.ownPlayerId() : selectedPlayerId;
            return city(playerId, state);
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
            if (client == null || !client.simulationReady() || fatalOnlineError != null) {
                return;
            }
            if (crestEditorOpen && crestEditor != null) {
                handleCrestEditorClick(button);
                return;
            }
            if (button != MButt.LEFT) {
                return;
            }
            if (inside(crestEditX1, crestEditX2, crestEditY1, crestEditY2)) {
                openCrestEditor();
                return;
            }
            if (hoveredPlayerId != null && !hoveredPlayerId.equals(client.ownPlayerId())) {
                selectedPlayerId = hoveredPlayerId;
                GAME.Notify("Selected " + selectedPlayerId);
                return;
            }
            Messages.City selected = selectedCity(client.worldState());
            if (mouseY >= actionY1 && mouseY < actionY2) {
                if (insideX(localChatX1, localChatX2)) {
                    requestText("Local province chat", text -> client.sendLocalChat(text));
                    return;
                }
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
            } else if (insideX(armyX1, armyX2) && !protectedNow(selected)
                    && "WAR".equals(effectiveStance(selected.playerId()))) {
                String warning = client.playerState().protectionEndsEpochMillis() > System.currentTimeMillis()
                        ? "WARNING: marching ends beginner protection. Levies against "
                        : "Levies to send against ";
                requestPositiveLong(warning + selected.cityName(),
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

        private void openCrestEditor() {
            CrestDesign current = client.crest(client.ownPlayerId());
            crestEditor = new CrestEditorModel(current == null ? importNativeCrest() : current);
            crestEditorOpen = true;
            GAME.Notify(current == null
                    ? "Imported the current Songs of Syx banner into the 32x32 crest editor"
                    : "Opened the synchronized kingdom crest");
        }

        private void handleCrestEditorClick(MButt button) {
            if (button != MButt.LEFT && button != MButt.RIGHT) {
                return;
            }
            if (insideCrestGrid()) {
                crestEditor.beginStroke();
                int x = (mouseX - CREST_GRID_X1) / CREST_CELL;
                int y = (mouseY - CREST_GRID_Y1) / CREST_CELL;
                if (button == MButt.RIGHT) {
                    crestEditor.erase(x, y);
                } else {
                    crestEditor.paint(x, y);
                }
                return;
            }
            if (button != MButt.LEFT) {
                return;
            }
            for (int index = 0; index < CrestDesign.PALETTE_SIZE; index++) {
                int column = index % 4;
                int row = index / 4;
                int x1 = CREST_SIDE_X1 + column * (CREST_PALETTE_CELL + CREST_PALETTE_GAP);
                int y1 = CREST_PALETTE_Y1 + row * (CREST_PALETTE_CELL + CREST_PALETTE_GAP);
                if (inside(x1, x1 + CREST_PALETTE_CELL, y1, y1 + CREST_PALETTE_CELL)) {
                    crestEditor.endStroke();
                    crestEditor.selectPalette(index);
                    return;
                }
            }
            if (inside(420, 538, 260, 282) && crestEditor.selectedPalette() != 0) {
                requestCrestColor();
            } else if (inside(420, 474, 452, 474)) {
                crestEditor.undo();
            } else if (inside(480, 534, 452, 474)) {
                crestEditor.redo();
            } else if (inside(540, 610, 452, 474)) {
                crestEditor.clear();
            } else if (inside(420, 480, 480, 502)) {
                crestEditor.mirrorHorizontal();
            } else if (inside(486, 546, 480, 502)) {
                crestEditor.mirrorVertical();
            } else if (inside(552, 618, 480, 502)) {
                crestEditor.replace(importNativeCrest());
                GAME.Notify("Imported the current Songs of Syx banner");
            } else if (inside(22, 112, 516, 540)) {
                if (!crestEditor.design().hasVisiblePixels()) {
                    GAME.WarnLight("A kingdom crest must contain at least one visible pixel");
                    return;
                }
                String commandId = client.setCrest(crestEditor.design());
                if (commandId.isBlank()) {
                    GAME.WarnLight("The multiplayer command queue is full; try saving again shortly");
                    return;
                }
                crestEditorOpen = false;
                GAME.Notify("Kingdom crest queued for server validation");
            } else if (inside(120, 210, 516, 540)) {
                crestEditorOpen = false;
                crestEditor = null;
            }
        }

        private void updateCrestStroke() {
            boolean left = MButt.LEFT.isDown();
            boolean right = MButt.RIGHT.isDown();
            if (!left && !right) {
                crestEditor.endStroke();
                return;
            }
            if (!insideCrestGrid()) {
                return;
            }
            int x = (mouseX - CREST_GRID_X1) / CREST_CELL;
            int y = (mouseY - CREST_GRID_Y1) / CREST_CELL;
            crestEditor.beginStroke();
            if (right) {
                crestEditor.erase(x, y);
            } else {
                crestEditor.paint(x, y);
            }
        }

        private boolean insideCrestGrid() {
            return inside(CREST_GRID_X1, CREST_GRID_X1 + CrestDesign.WIDTH * CREST_CELL,
                    CREST_GRID_Y1, CREST_GRID_Y1 + CrestDesign.HEIGHT * CREST_CELL);
        }

        private boolean inside(int x1, int x2, int y1, int y2) {
            return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
        }

        private void requestCrestColor() {
            int selected = crestEditor.selectedPalette();
            int current = crestEditor.design().color(selected);
            VIEW.inters().input.requestInput(value -> {
                if (value == null) {
                    return;
                }
                try {
                    int rgb = parseRgb(value.toString());
                    crestEditor.setSelectedColor(rgb);
                    GAME.Notify(String.format(java.util.Locale.ROOT,
                            "Palette slot %d changed to #%06X", selected, rgb));
                } catch (IllegalArgumentException e) {
                    GAME.WarnLight("Use #RRGGBB or three RGB values from 0 to 255");
                }
            }, String.format(java.util.Locale.ROOT,
                    "Palette slot %d color (current #%06X)", selected, current));
        }

        private static int parseRgb(String input) {
            String value = input == null ? "" : input.trim();
            if (value.startsWith("#")) {
                value = value.substring(1);
            }
            if (value.matches("[0-9A-Fa-f]{6}")) {
                return Integer.parseInt(value, 16);
            }
            String[] components = value.split("[\\s,]+");
            if (components.length != 3) {
                throw new IllegalArgumentException("Wrong RGB component count");
            }
            int rgb = 0;
            for (String component : components) {
                int channel = Integer.parseInt(component);
                if (channel < 0 || channel > 255) {
                    throw new IllegalArgumentException("RGB channel outside 0-255");
                }
                rgb = (rgb << 8) | channel;
            }
            return rgb;
        }

        private static CrestDesign importNativeCrest() {
            var banner = FACTIONS.player().banner();
            CrestDesign design = CrestDesign.blank()
                    .withColor(1, colorRgb(banner.colorBG()))
                    .withColor(2, colorRgb(banner.colorFG()))
                    .withColor(3, colorRgb(banner.colorBorder()))
                    .withColor(4, colorRgb(banner.colorPole()));
            for (int y = 0; y < CrestDesign.HEIGHT; y++) {
                int sourceY = y * BitmapSprite.HEIGHT / CrestDesign.HEIGHT;
                for (int x = 0; x < CrestDesign.WIDTH; x++) {
                    int sourceX = x * BitmapSprite.WIDTH / CrestDesign.WIDTH;
                    design = design.withPixel(x, y, banner.sprite.is(sourceX, sourceY) ? 2 : 1);
                }
            }
            return design;
        }

        private static int colorRgb(COLOR color) {
            return (Byte.toUnsignedInt(color.red()) << 16)
                    | (Byte.toUnsignedInt(color.green()) << 8)
                    | Byte.toUnsignedInt(color.blue());
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

        private void beginFoundingPrompts() {
            foundingPromptActive = true;
            String serverMessage = client.foundingMessage();
            if (!serverMessage.isBlank()) {
                GAME.Notify(serverMessage);
            }
            requestFoundingName("Choose your ruler name (3-32 characters)", ruler ->
                    requestFoundingName("Choose your unique kingdom name", kingdom ->
                            requestFoundingName("Choose your capital city name", capital ->
                                    requestPlacementReference(ruler, kingdom, capital))));
        }

        private void requestFoundingName(String title, java.util.function.Consumer<String> receiver) {
            VIEW.inters().input.requestInput(value -> {
                if (value == null) {
                    cancelFoundingPrompt();
                    return;
                }
                String text = value.toString().trim().replaceAll("\\s+", " ");
                if (!validFoundingName(text)) {
                    GAME.WarnLight("Use 3-32 letters, numbers, spaces, apostrophes, or hyphens");
                    cancelFoundingPrompt();
                    return;
                }
                receiver.accept(text);
            }, title);
        }

        private void requestPlacementReference(String ruler, String kingdom, String capital) {
            VIEW.inters().input.requestInput(value -> {
                if (value == null) {
                    cancelFoundingPrompt();
                    return;
                }
                String placement = value.toString().trim().replaceAll("\\s+", " ");
                if (!placement.isEmpty() && !validFoundingName(placement)) {
                    GAME.WarnLight("Nearby kingdom must use a valid 3-32 character kingdom name");
                    cancelFoundingPrompt();
                    return;
                }
                client.submitFounding(ruler, kingdom, capital, placement);
                foundingPromptActive = false;
                GAME.Notify("Kingdom founding claim submitted");
            }, "Nearby kingdom name (leave blank for balanced placement)");
        }

        private void cancelFoundingPrompt() {
            foundingPromptActive = false;
            nextFoundingPromptAt = System.currentTimeMillis() + 5_000;
        }

        private static boolean validFoundingName(String value) {
            if (value.length() < 3 || value.length() > 32) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (!Character.isLetterOrDigit(character) && character != ' '
                        && character != '\'' && character != '-') {
                    return false;
                }
            }
            return true;
        }

        private static boolean protectedNow(Messages.City city) {
            return city.protectionEndsEpochMillis() > System.currentTimeMillis();
        }

        private static String protectionRemaining(long endsEpochMillis) {
            long remaining = Math.max(0, endsEpochMillis - System.currentTimeMillis());
            long hours = (remaining + 3_599_999L) / 3_600_000L;
            if (hours >= 48) {
                return ((hours + 23) / 24) + "d";
            }
            return hours + "h";
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
            file.i(3);
            file.chars(client == null ? "" : client.ownPlayerId());
            file.chars(client == null ? "" : client.config().worldId());
            file.l(tradeLedger == null ? 0 : tradeLedger.revision());
        }

        @Override
        public void load(FileGetter file) throws IOException {
            int format = file.i();
            if (format == 1) {
                return;
            }
            if (format != 2 && format != 3) {
                setFatalOnlineError("SAVE_BINDING_UNSUPPORTED: this save uses an unknown multiplayer binding format");
                return;
            }
            String savedPlayerId = file.chars();
            String savedWorldId = file.chars();
            long savedLedgerRevision = format == 3 ? file.l()
                    : (tradeLedger == null ? 0 : tradeLedger.revision());
            if (client != null && (!client.ownPlayerId().equals(savedPlayerId)
                    || !client.config().worldId().equals(savedWorldId))) {
                setFatalOnlineError("SAVE_IDENTITY_MISMATCH: this city belongs to a different multiplayer identity or world");
                return;
            }
            if (format == 3 && tradeLedger != null) {
                loadedLedgerRevision = savedLedgerRevision;
                rollbackReconciliationPending = true;
            } else if (tradeLedger != null) {
                loadedLedgerRevision = tradeLedger.revision();
            }
        }

        @Override
        public boolean handleBrokenSavedState() {
            return true;
        }

        private void setFatalOnlineError(String message) {
            fatalOnlineError = message;
            if (client != null) {
                client.close();
            }
            LOG.err("[Syx Kingdoms] " + message);
        }

        @Override
        public void close() {
            if (client != null) {
                client.close();
            }
        }
    }
}
