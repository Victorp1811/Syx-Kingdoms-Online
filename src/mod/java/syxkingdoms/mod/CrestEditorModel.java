package syxkingdoms.mod;

import syxkingdoms.protocol.CrestDesign;

import java.util.ArrayDeque;

final class CrestEditorModel {
    private static final int MAX_HISTORY = 64;

    private final ArrayDeque<CrestDesign> undo = new ArrayDeque<>();
    private final ArrayDeque<CrestDesign> redo = new ArrayDeque<>();
    private CrestDesign design;
    private int selectedPalette = 1;
    private boolean strokeActive;

    CrestEditorModel(CrestDesign design) {
        this.design = design == null ? CrestDesign.blank() : design;
    }

    CrestDesign design() {
        return design;
    }

    int selectedPalette() {
        return selectedPalette;
    }

    void selectPalette(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= CrestDesign.PALETTE_SIZE) {
            throw new IllegalArgumentException("Palette index is outside 0-15");
        }
        selectedPalette = paletteIndex;
    }

    void beginStroke() {
        if (!strokeActive) {
            checkpoint();
            strokeActive = true;
        }
    }

    void endStroke() {
        strokeActive = false;
    }

    void paint(int x, int y) {
        design = design.withPixel(x, y, selectedPalette);
    }

    void erase(int x, int y) {
        design = design.withPixel(x, y, 0);
    }

    void setSelectedColor(int rgb) {
        if (selectedPalette == 0) {
            throw new IllegalStateException("Transparent palette slot has no editable color");
        }
        mutate(design.withColor(selectedPalette, rgb));
    }

    void clear() {
        mutate(design.clear());
    }

    void mirrorHorizontal() {
        mutate(design.mirrorHorizontal());
    }

    void mirrorVertical() {
        mutate(design.mirrorVertical());
    }

    void replace(CrestDesign replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("Replacement crest is required");
        }
        mutate(replacement);
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void undo() {
        endStroke();
        if (undo.isEmpty()) {
            return;
        }
        redo.push(design);
        design = undo.pop();
    }

    void redo() {
        endStroke();
        if (redo.isEmpty()) {
            return;
        }
        pushBounded(undo, design);
        design = redo.pop();
    }

    private void mutate(CrestDesign replacement) {
        endStroke();
        if (replacement.equals(design)) {
            return;
        }
        checkpoint();
        design = replacement;
    }

    private void checkpoint() {
        pushBounded(undo, design);
        redo.clear();
    }

    private static void pushBounded(ArrayDeque<CrestDesign> history, CrestDesign design) {
        history.push(design);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }
}
