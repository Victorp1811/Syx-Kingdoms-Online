package syxkingdoms.mod;

import syxkingdoms.protocol.CrestDesign;

public final class CrestEditorModelTest {
    public static void main(String[] args) {
        CrestEditorModel editor = new CrestEditorModel(CrestDesign.blank());
        editor.selectPalette(7);
        editor.beginStroke();
        editor.paint(2, 3);
        editor.paint(3, 3);
        editor.endStroke();
        check(editor.design().pixel(2, 3) == 7 && editor.design().pixel(3, 3) == 7,
                "one drag stroke paints multiple indexed pixels");
        editor.undo();
        check(editor.design().pixel(2, 3) == 0 && editor.design().pixel(3, 3) == 0,
                "one undo restores the complete drag stroke");
        editor.redo();
        check(editor.design().pixel(2, 3) == 7 && editor.design().pixel(3, 3) == 7,
                "redo restores the drag stroke");
        editor.setSelectedColor(0x123456);
        check(editor.design().color(7) == 0x123456, "selected palette color is editable");
        editor.mirrorHorizontal();
        check(editor.design().pixel(29, 3) == 7 && editor.design().pixel(28, 3) == 7,
                "horizontal mirror uses the full 32-pixel width");
        editor.mirrorVertical();
        check(editor.design().pixel(29, 28) == 7, "vertical mirror uses the full 32-pixel height");
        editor.clear();
        check(!editor.design().hasVisiblePixels(), "clear makes every pixel transparent");
        System.out.println("CrestEditorModelTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
