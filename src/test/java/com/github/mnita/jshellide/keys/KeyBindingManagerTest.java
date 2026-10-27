package com.github.mnita.jshellide.keys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.keys.KeyBindingManager.ActionId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.junit.Test;

public class KeyBindingManagerTest {

    @Test
    public void testParseAndFormatShortcuts() {
        int ctrlSpace = KeyBindingManager.parseShortcut("Ctrl+Space");
        assertTrue((ctrlSpace & SWT.CTRL) != 0);
        assertEquals("Ctrl+Space", KeyBindingManager.formatShortcut(ctrlSpace));

        int ctrlShiftL = KeyBindingManager.parseShortcut("Ctrl+Shift+L");
        assertEquals("Ctrl+Shift+L", KeyBindingManager.formatShortcut(ctrlShiftL));

        int f5 = KeyBindingManager.parseShortcut("F5");
        assertEquals(SWT.F5, f5);
        assertEquals("F5", KeyBindingManager.formatShortcut(f5));
    }

    @Test
    public void testRemapAndMatchKeyEvent() {
        KeyBindingManager mgr = new KeyBindingManager();
        assertEquals(
                ActionId.RUN_EVALUATE,
                mgr.findMatchingAction(SWT.CTRL, SWT.CR));

        mgr.setShortcut(ActionId.RUN_EVALUATE, "F5");
        assertEquals("F5", mgr.getShortcutText(ActionId.RUN_EVALUATE));
        assertEquals(ActionId.RUN_EVALUATE, mgr.findMatchingAction(0, SWT.F5));
    }

    @Test
    public void testSaveAndLoadBindingsFile() throws Exception {
        Path tempFile = Files.createTempFile("keybindings-test", ".properties");
        try {
            KeyBindingManager mgr1 = new KeyBindingManager();
            mgr1.setShortcut(ActionId.AUTO_COMPLETE, "Alt+Space");
            mgr1.saveToFile(tempFile);

            KeyBindingManager mgr2 = new KeyBindingManager();
            mgr2.loadFromFile(tempFile);
            assertEquals("Alt+Space", mgr2.getShortcutText(ActionId.AUTO_COMPLETE));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
