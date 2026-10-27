package com.github.mnita.jshellide.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.editor.UndoRedoManager.TextEditorTarget;
import org.junit.Before;
import org.junit.Test;

public class UndoRedoManagerTest {

    private static final class MockEditorTarget implements TextEditorTarget {
        private final StringBuilder buffer;
        private final UndoRedoManager manager;
        private int caretOffset;

        MockEditorTarget(String initialText, UndoRedoManager manager) {
            this.buffer = new StringBuilder(initialText);
            this.manager = manager;
            this.caretOffset = initialText.length();
        }

        void userReplace(int start, int length, String newText) {
            String replaced = buffer.substring(start, start + length);
            buffer.replace(start, start + length, newText);
            caretOffset = start + newText.length();
            manager.recordEdit(start, replaced, newText);
        }

        @Override
        public void replaceTextRange(int start, int length, String text) {
            String replaced = buffer.substring(start, start + length);
            buffer.replace(start, start + length, text);
            // Simulate ExtendedModifyListener firing during programmatic replace
            manager.recordEdit(start, replaced, text);
        }

        @Override
        public void setCaretOffset(int offset) {
            this.caretOffset = offset;
        }

        String getText() {
            return buffer.toString();
        }

        int getCaretOffset() {
            return caretOffset;
        }
    }

    private UndoRedoManager undoRedo;
    private MockEditorTarget editor;

    @Before
    public void setUp() {
        undoRedo = new UndoRedoManager();
        editor = new MockEditorTarget("int x = 1;", undoRedo);
    }

    @Test
    public void testSingleInsertUndoAndRedo() {
        assertFalse(undoRedo.canUndo());
        assertFalse(undoRedo.canRedo());

        editor.userReplace(10, 0, "\nint y = 2;");
        assertEquals("int x = 1;\nint y = 2;", editor.getText());
        assertTrue(undoRedo.canUndo());

        assertTrue(undoRedo.undo(editor));
        assertEquals("int x = 1;", editor.getText());
        assertEquals(10, editor.getCaretOffset());
        assertFalse(undoRedo.canUndo());
        assertTrue(undoRedo.canRedo());

        assertTrue(undoRedo.redo(editor));
        assertEquals("int x = 1;\nint y = 2;", editor.getText());
        assertEquals(21, editor.getCaretOffset());
        assertTrue(undoRedo.canUndo());
        assertFalse(undoRedo.canRedo());
    }

    @Test
    public void testReplacementAndMultiStepUndoRedo() {
        editor.userReplace(8, 1, "42"); // "int x = 42;"
        editor.userReplace(4, 1, "answer"); // "int answer = 42;"
        assertEquals("int answer = 42;", editor.getText());

        assertTrue(undoRedo.undo(editor));
        assertEquals("int x = 42;", editor.getText());
        assertEquals(5, editor.getCaretOffset());

        assertTrue(undoRedo.undo(editor));
        assertEquals("int x = 1;", editor.getText());
        assertEquals(9, editor.getCaretOffset());

        assertTrue(undoRedo.redo(editor));
        assertEquals("int x = 42;", editor.getText());

        // New edit after undo clears the redo stack
        editor.userReplace(11, 0, " // comment");
        assertFalse(undoRedo.canRedo());
        assertEquals("int x = 42; // comment", editor.getText());
    }

    @Test
    public void testClearResetsStacks() {
        editor.userReplace(0, 3, "long");
        assertTrue(undoRedo.canUndo());

        undoRedo.clear();
        assertFalse(undoRedo.canUndo());
        assertFalse(undoRedo.canRedo());
    }
}
