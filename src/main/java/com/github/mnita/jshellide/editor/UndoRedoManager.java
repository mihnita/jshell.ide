package com.github.mnita.jshellide.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages undo and redo history for text edits in the editor.
 */
public class UndoRedoManager {

    /** Abstraction over an editable text widget to apply undo/redo operations. */
    public interface TextEditorTarget {
        void replaceTextRange(int start, int length, String text);

        void setCaretOffset(int offset);
    }

    /** Represents a single reversible text modification. */
    public static final class TextEdit {
        private final int start;
        private final String replacedText;
        private final String insertedText;

        public TextEdit(int start, String replacedText, String insertedText) {
            this.start = start;
            this.replacedText = replacedText != null ? replacedText : "";
            this.insertedText = insertedText != null ? insertedText : "";
        }

        public int getStart() {
            return start;
        }

        public String getReplacedText() {
            return replacedText;
        }

        public String getInsertedText() {
            return insertedText;
        }
    }

    private static final int DEFAULT_MAX_HISTORY = 500;

    private final int maxHistory;
    private final Deque<TextEdit> undoStack = new ArrayDeque<>();
    private final Deque<TextEdit> redoStack = new ArrayDeque<>();
    private boolean applyingUndoOrRedo = false;

    public UndoRedoManager() {
        this(DEFAULT_MAX_HISTORY);
    }

    public UndoRedoManager(int maxHistory) {
        if (maxHistory <= 0) {
            throw new IllegalArgumentException("maxHistory must be positive");
        }
        this.maxHistory = maxHistory;
    }

    /**
     * Records a text modification from an editor event.
     * Ignored if the modification is currently being caused by an undo or redo operation.
     */
    public void recordEdit(int start, String replacedText, String insertedText) {
        if (applyingUndoOrRedo) {
            return;
        }
        String safeReplaced = replacedText != null ? replacedText : "";
        String safeInserted = insertedText != null ? insertedText : "";
        if (safeReplaced.isEmpty() && safeInserted.isEmpty()) {
            return;
        }

        if (undoStack.size() >= maxHistory) {
            undoStack.removeLast();
        }
        undoStack.push(new TextEdit(start, safeReplaced, safeInserted));
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Undoes the most recent edit on the given {@link TextEditorTarget}.
     *
     * @return {@code true} if an edit was undone, {@code false} if the undo stack was empty.
     */
    public boolean undo(TextEditorTarget target) {
        if (undoStack.isEmpty() || target == null) {
            return false;
        }
        TextEdit edit = undoStack.pop();
        applyingUndoOrRedo = true;
        try {
            target.replaceTextRange(
                    edit.getStart(),
                    edit.getInsertedText().length(),
                    edit.getReplacedText());
            target.setCaretOffset(edit.getStart() + edit.getReplacedText().length());
            redoStack.push(edit);
            return true;
        } finally {
            applyingUndoOrRedo = false;
        }
    }

    /**
     * Redoes the most recently undone edit on the given {@link TextEditorTarget}.
     *
     * @return {@code true} if an edit was redone, {@code false} if the redo stack was empty.
     */
    public boolean redo(TextEditorTarget target) {
        if (redoStack.isEmpty() || target == null) {
            return false;
        }
        TextEdit edit = redoStack.pop();
        applyingUndoOrRedo = true;
        try {
            target.replaceTextRange(
                    edit.getStart(),
                    edit.getReplacedText().length(),
                    edit.getInsertedText());
            target.setCaretOffset(edit.getStart() + edit.getInsertedText().length());
            undoStack.push(edit);
            return true;
        } finally {
            applyingUndoOrRedo = false;
        }
    }

    /** Clears both undo and redo stacks (e.g. when loading a new file). */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    public int getUndoCount() {
        return undoStack.size();
    }

    public int getRedoCount() {
        return redoStack.size();
    }
}
