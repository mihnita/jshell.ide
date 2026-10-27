package com.github.mnita.jshellide.keys;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.eclipse.swt.SWT;

/**
 * Manages configurable keyboard shortcuts for all IDE commands, including
 * parsing/formatting accelerator strings and persisting user customizations.
 */
public class KeyBindingManager {

    /** All bindable IDE actions. */
    public enum ActionId {
        FILE_NEW("New Scratchpad", "Ctrl+N"),
        FILE_OPEN("Open File...", "Ctrl+O"),
        FILE_SAVE("Save File", "Ctrl+S"),
        FILE_SAVE_AS("Save File As...", "Ctrl+Shift+S"),
        EDIT_UNDO("Undo", "Ctrl+Z"),
        EDIT_REDO("Redo", "Ctrl+Y"),
        AUTO_COMPLETE("Trigger Auto-Complete", "Ctrl+Space"),
        RUN_EVALUATE("Evaluate Scratchpad Now", "Ctrl+Enter"),
        TOGGLE_LIVE_EVAL("Toggle Live Evaluation", "Ctrl+Shift+L"),
        CLEAR_OUTPUT("Clear Output Panel", "Ctrl+L"),
        CONFIGURE_CLASSPATH("Classpath & Maven Dependencies...", "Ctrl+Shift+D"),
        CONFIGURE_FONT("Configure Editor Font...", "Ctrl+Shift+F"),
        CONFIGURE_THEME("Select TextMate Theme (.tmTheme)...", "Ctrl+Shift+T"),
        CONFIGURE_KEYS("Configure Key Bindings...", "Ctrl+Shift+K");

        private final String displayName;
        private final String defaultShortcut;

        ActionId(String displayName, String defaultShortcut) {
            this.displayName = displayName;
            this.defaultShortcut = defaultShortcut;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultShortcut() {
            return defaultShortcut;
        }
    }

    private final Map<ActionId, Integer> bindings = new EnumMap<>(ActionId.class);

    public KeyBindingManager() {
        resetToDefaults();
    }

    /** Resets all action shortcuts to their default bindings. */
    public final void resetToDefaults() {
        bindings.clear();
        for (ActionId action : ActionId.values()) {
            bindings.put(action, parseShortcut(action.getDefaultShortcut()));
        }
    }

    /** Returns the SWT accelerator integer for the given action. */
    public int getAccelerator(ActionId action) {
        return bindings.getOrDefault(action, 0);
    }

    /** Returns the human-readable shortcut string for the given action. */
    public String getShortcutText(ActionId action) {
        return formatShortcut(getAccelerator(action));
    }

    /** Sets a new shortcut for the given action using a human-readable string (e.g. "Ctrl+Enter"). */
    public void setShortcut(ActionId action, String shortcutText) {
        int accel = parseShortcut(shortcutText);
        if (accel == 0) {
            throw new IllegalArgumentException("Invalid shortcut: " + shortcutText);
        }
        bindings.put(action, accel);
    }

    /** Sets a new SWT accelerator code directly for the given action. */
    public void setAccelerator(ActionId action, int accelerator) {
        bindings.put(action, accelerator);
    }

    /** Finds the action matching a raw SWT key event (`stateMask` + `keyCode`), or {@code null}. */
    public ActionId findMatchingAction(int stateMask, int keyCode) {
        int normalizedKey = normalizeKeyCode(keyCode);
        int candidate = (stateMask & (SWT.CTRL | SWT.ALT | SWT.SHIFT | SWT.COMMAND)) | normalizedKey;
        for (Map.Entry<ActionId, Integer> entry : bindings.entrySet()) {
            if (entry.getValue() != 0 && entry.getValue() == candidate) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Exports current key bindings to a {@link Properties} object. */
    public Properties toProperties() {
        Properties props = new Properties();
        for (ActionId action : ActionId.values()) {
            props.setProperty(action.name(), getShortcutText(action));
        }
        return props;
    }

    /** Loads key bindings from a {@link Properties} object. */
    public void fromProperties(Properties props) {
        if (props == null) {
            return;
        }
        for (ActionId action : ActionId.values()) {
            String val = props.getProperty(action.name());
            if (val != null && !val.isBlank()) {
                int parsed = parseShortcut(val);
                if (parsed != 0) {
                    bindings.put(action, parsed);
                }
            }
        }
    }

    /** Saves bindings to a file on disk. */
    public void saveToFile(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Properties props = toProperties();
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "JShell IDE Key Bindings");
        }
    }

    /** Loads bindings from a file on disk if it exists. */
    public void loadFromFile(Path path) throws IOException {
        if (path != null && Files.isRegularFile(path)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            }
            fromProperties(props);
        }
    }

    /**
     * Parses a human-readable shortcut such as {@code "Ctrl+Space"}, {@code "Ctrl+Shift+L"},
     * {@code "Ctrl+Enter"}, or {@code "F5"} into an SWT accelerator integer.
     */
    public static int parseShortcut(String shortcut) {
        if (shortcut == null || shortcut.isBlank()) {
            return 0;
        }
        String[] parts = shortcut.trim().split("\\+");
        int modifiers = 0;
        int key = 0;

        for (String rawPart : parts) {
            String token = rawPart.trim().toUpperCase(Locale.ROOT);
            if (token.isEmpty()) {
                return 0;
            }
            switch (token) {
                case "CTRL", "CONTROL" -> modifiers |= SWT.CTRL;
                case "SHIFT" -> modifiers |= SWT.SHIFT;
                case "ALT", "OPTION" -> modifiers |= SWT.ALT;
                case "CMD", "COMMAND", "META" -> modifiers |= SWT.COMMAND;
                case "SPACE" -> key = ' ';
                case "ENTER", "RETURN", "CR" -> key = SWT.CR;
                case "TAB" -> key = SWT.TAB;
                case "ESC", "ESCAPE" -> key = SWT.ESC;
                case "BACKSPACE", "BS" -> key = SWT.BS;
                case "DEL", "DELETE" -> key = SWT.DEL;
                case "UP" -> key = SWT.ARROW_UP;
                case "DOWN" -> key = SWT.ARROW_DOWN;
                case "LEFT" -> key = SWT.ARROW_LEFT;
                case "RIGHT" -> key = SWT.ARROW_RIGHT;
                case "F1" -> key = SWT.F1;
                case "F2" -> key = SWT.F2;
                case "F3" -> key = SWT.F3;
                case "F4" -> key = SWT.F4;
                case "F5" -> key = SWT.F5;
                case "F6" -> key = SWT.F6;
                case "F7" -> key = SWT.F7;
                case "F8" -> key = SWT.F8;
                case "F9" -> key = SWT.F9;
                case "F10" -> key = SWT.F10;
                case "F11" -> key = SWT.F11;
                case "F12" -> key = SWT.F12;
                default -> {
                    if (token.length() == 1) {
                        key = Character.toUpperCase(token.charAt(0));
                    } else {
                        return 0;
                    }
                }
            }
        }
        return key == 0 ? 0 : (modifiers | key);
    }

    /** Formats an SWT accelerator integer into a canonical string like {@code "Ctrl+Shift+L"}. */
    public static String formatShortcut(int accelerator) {
        if (accelerator == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if ((accelerator & SWT.CTRL) != 0) {
            sb.append("Ctrl+");
        }
        if ((accelerator & SWT.ALT) != 0) {
            sb.append("Alt+");
        }
        if ((accelerator & SWT.SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((accelerator & SWT.COMMAND) != 0) {
            sb.append("Cmd+");
        }

        int key = accelerator & ~(SWT.CTRL | SWT.ALT | SWT.SHIFT | SWT.COMMAND);
        key = normalizeKeyCode(key);

        String keyName = switch (key) {
            case ' ' -> "Space";
            case SWT.CR, SWT.LF -> "Enter";
            case SWT.TAB -> "Tab";
            case SWT.ESC -> "Esc";
            case SWT.BS -> "Backspace";
            case SWT.DEL -> "Delete";
            case SWT.ARROW_UP -> "Up";
            case SWT.ARROW_DOWN -> "Down";
            case SWT.ARROW_LEFT -> "Left";
            case SWT.ARROW_RIGHT -> "Right";
            case SWT.F1 -> "F1";
            case SWT.F2 -> "F2";
            case SWT.F3 -> "F3";
            case SWT.F4 -> "F4";
            case SWT.F5 -> "F5";
            case SWT.F6 -> "F6";
            case SWT.F7 -> "F7";
            case SWT.F8 -> "F8";
            case SWT.F9 -> "F9";
            case SWT.F10 -> "F10";
            case SWT.F11 -> "F11";
            case SWT.F12 -> "F12";
            default -> (key > 32 && key < 127)
                    ? String.valueOf((char) Character.toUpperCase(key))
                    : "";
        };

        if (keyName.isEmpty()) {
            return "";
        }
        sb.append(keyName);
        return sb.toString();
    }

    private static int normalizeKeyCode(int keyCode) {
        if (keyCode == SWT.LF || keyCode == SWT.KEYPAD_CR) {
            return SWT.CR;
        }
        if (keyCode >= 'a' && keyCode <= 'z') {
            return Character.toUpperCase(keyCode);
        }
        return keyCode;
    }
}
