package com.github.mnita.jshellide.ui;

import com.github.mnita.jshellide.keys.KeyBindingManager;
import com.github.mnita.jshellide.keys.KeyBindingManager.ActionId;
import java.util.EnumMap;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Modal dialog that allows viewing and remapping keyboard shortcuts for all IDE commands.
 */
public class KeyBindingsDialog {

    private final Shell parent;
    private final KeyBindingManager keyManager;
    private final Map<ActionId, Integer> stagedBindings = new EnumMap<>(ActionId.class);
    private boolean confirmed = false;

    public KeyBindingsDialog(Shell parent, KeyBindingManager keyManager) {
        this.parent = parent;
        this.keyManager = keyManager;
        for (ActionId action : ActionId.values()) {
            stagedBindings.put(action, keyManager.getAccelerator(action));
        }
    }

    /** Opens the dialog and returns {@code true} if changes were applied. */
    public boolean open() {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dialog.setText("Configure Key Bindings");
        dialog.setLayout(new GridLayout(1, false));
        dialog.setSize(560, 440);

        Table table = new Table(dialog, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn actionCol = new TableColumn(table, SWT.LEFT);
        actionCol.setText("Command");
        actionCol.setWidth(290);

        TableColumn keyCol = new TableColumn(table, SWT.LEFT);
        keyCol.setText("Key Binding");
        keyCol.setWidth(210);

        for (ActionId action : ActionId.values()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setData(action);
            item.setText(0, action.getDisplayName());
            item.setText(1, KeyBindingManager.formatShortcut(stagedBindings.get(action)));
        }

        Composite editorRow = new Composite(dialog, SWT.NONE);
        editorRow.setLayout(new GridLayout(3, false));
        editorRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lbl = new Label(editorRow, SWT.NONE);
        lbl.setText("Shortcut (press keys or type):");

        Text shortcutInput = new Text(editorRow, SWT.BORDER);
        shortcutInput.setMessage("e.g. Ctrl+Enter or F5");
        shortcutInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button assignBtn = new Button(editorRow, SWT.PUSH);
        assignBtn.setText("Assign");

        table.addListener(SWT.Selection, e -> {
            int idx = table.getSelectionIndex();
            if (idx >= 0) {
                ActionId selected = (ActionId) table.getItem(idx).getData();
                shortcutInput.setText(KeyBindingManager.formatShortcut(stagedBindings.get(selected)));
            }
        });

        // Allow direct key press capture inside shortcutInput when modifiers or function keys are used
        shortcutInput.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CTRL
                    || e.keyCode == SWT.SHIFT
                    || e.keyCode == SWT.ALT
                    || e.keyCode == SWT.COMMAND) {
                return;
            }
            boolean hasModifier = (e.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND)) != 0;
            boolean isFunctionKey = (e.keyCode >= SWT.F1 && e.keyCode <= SWT.F12);
            if (hasModifier || isFunctionKey) {
                e.doit = false;
                int accel = (e.stateMask & (SWT.CTRL | SWT.ALT | SWT.SHIFT | SWT.COMMAND)) | e.keyCode;
                String formatted = KeyBindingManager.formatShortcut(accel);
                if (!formatted.isEmpty()) {
                    shortcutInput.setText(formatted);
                }
            }
        });

        assignBtn.addListener(SWT.Selection, e -> {
            int idx = table.getSelectionIndex();
            if (idx < 0) {
                return;
            }
            ActionId selected = (ActionId) table.getItem(idx).getData();
            String text = shortcutInput.getText().trim();
            int parsed = KeyBindingManager.parseShortcut(text);
            if (parsed == 0) {
                MessageBox mb = new MessageBox(dialog, SWT.ICON_WARNING | SWT.OK);
                mb.setText("Invalid Shortcut");
                mb.setMessage("Could not parse shortcut: \"" + text
                        + "\". Examples: Ctrl+Enter, Ctrl+Space, Alt+R, F5.");
                mb.open();
                return;
            }
            stagedBindings.put(selected, parsed);
            table.getItem(idx).setText(1, KeyBindingManager.formatShortcut(parsed));
        });

        Composite bottomBar = new Composite(dialog, SWT.NONE);
        bottomBar.setLayout(new GridLayout(3, false));
        bottomBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button resetBtn = new Button(bottomBar, SWT.PUSH);
        resetBtn.setText("Reset Defaults");
        resetBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));

        Button applyBtn = new Button(bottomBar, SWT.PUSH);
        applyBtn.setText("Apply && Close");

        Button cancelBtn = new Button(bottomBar, SWT.PUSH);
        cancelBtn.setText("Cancel");

        resetBtn.addListener(SWT.Selection, e -> {
            for (int i = 0; i < table.getItemCount(); i++) {
                TableItem item = table.getItem(i);
                ActionId action = (ActionId) item.getData();
                int defAccel = KeyBindingManager.parseShortcut(action.getDefaultShortcut());
                stagedBindings.put(action, defAccel);
                item.setText(1, KeyBindingManager.formatShortcut(defAccel));
            }
            int idx = table.getSelectionIndex();
            if (idx >= 0) {
                ActionId selected = (ActionId) table.getItem(idx).getData();
                shortcutInput.setText(KeyBindingManager.formatShortcut(stagedBindings.get(selected)));
            }
        });

        applyBtn.addListener(SWT.Selection, e -> {
            for (Map.Entry<ActionId, Integer> entry : stagedBindings.entrySet()) {
                keyManager.setAccelerator(entry.getKey(), entry.getValue());
            }
            confirmed = true;
            dialog.close();
        });

        cancelBtn.addListener(SWT.Selection, e -> {
            confirmed = false;
            dialog.close();
        });

        if (table.getItemCount() > 0) {
            table.setSelection(0);
            ActionId first = (ActionId) table.getItem(0).getData();
            shortcutInput.setText(KeyBindingManager.formatShortcut(stagedBindings.get(first)));
        }

        dialog.open();
        Display display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return confirmed;
    }
}
