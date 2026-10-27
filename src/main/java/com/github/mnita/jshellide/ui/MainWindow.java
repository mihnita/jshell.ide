package com.github.mnita.jshellide.ui;

import com.github.mnita.jshellide.editor.EditorSettings;
import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter;
import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter.StyledSpan;
import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter.TokenType;
import com.github.mnita.jshellide.editor.UndoRedoManager;
import com.github.mnita.jshellide.editor.UndoRedoManager.TextEditorTarget;
import com.github.mnita.jshellide.engine.JShellEngine;
import com.github.mnita.jshellide.engine.JShellEngine.CompletionResult;
import com.github.mnita.jshellide.engine.JShellEngine.DiagnosticItem;
import com.github.mnita.jshellide.engine.JShellEngine.EvaluationResult;
import com.github.mnita.jshellide.keys.KeyBindingManager;
import com.github.mnita.jshellide.keys.KeyBindingManager.ActionId;
import com.github.mnita.jshellide.maven.MavenDependencyResolver;
import com.github.mnita.jshellide.theme.TextMateTheme;
import com.github.mnita.jshellide.theme.TextMateTheme.TokenStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.FontDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

/**
 * Main SWT window for the JShell IDE application.
 */
public class MainWindow {

    private static final int LIVE_EVAL_DEBOUNCE_MS = 350;

    private final Display display;
    private final Shell shell;
    private final JShellEngine engine;
    private final JavaSyntaxHighlighter highlighter;
    private final UndoRedoManager undoRedoManager;
    private final EditorSettings editorSettings;
    private final KeyBindingManager keyManager;
    private final MavenDependencyResolver mavenResolver;
    private final Path keyBindingsFile;
    private final Path settingsFile;

    private final List<String> mavenCoordinates = new ArrayList<>();
    private final Map<ActionId, MenuItem> actionMenuItems = new EnumMap<>(ActionId.class);

    private StyledText editorText;
    private StyledText outputText;
    private Label statusLabel;
    private Button liveEvalCheckBtn;
    private MenuItem liveEvalMenuItem;
    private Shell completionPopup;
    private org.eclipse.swt.widgets.List completionList;
    private int currentCompletionAnchor = 0;

    private Font monoFont;
    private TextMateTheme activeTheme;
    private Color colorBackground;
    private Color colorForeground;
    private Color colorSelection;
    private Color colorKeyword;
    private Color colorType;
    private Color colorString;
    private Color colorNumber;
    private Color colorComment;
    private Color colorAnnotation;
    private Color colorError;
    private Color colorOutputValue;
    private Color colorOutputHeader;

    private boolean liveEvaluationEnabled = true;
    private Path currentFilePath = null;
    private List<DiagnosticItem> currentDiagnostics = Collections.emptyList();
    private EvaluationResult lastEvaluationResult = null;

    private final AtomicLong evaluationGeneration = new AtomicLong(0);
    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jshell-eval-worker");
        t.setDaemon(true);
        return t;
    });

    public MainWindow(Display display) {
        this(display, null);
    }

    public MainWindow(Display display, Path initialFile) {
        this.display = display;
        this.shell = new Shell(display);
        this.engine = new JShellEngine();
        this.highlighter = new JavaSyntaxHighlighter();
        this.undoRedoManager = new UndoRedoManager();
        this.editorSettings = new EditorSettings();
        this.keyManager = new KeyBindingManager();
        this.mavenResolver = new MavenDependencyResolver();
        this.keyBindingsFile = editorSettings.getKeyBindingsFile();
        this.settingsFile = editorSettings.getSettingsFile();
        if (initialFile != null) {
            this.currentFilePath = initialFile.toAbsolutePath().normalize();
        }

        this.editorSettings.ensureThemesDirSeeded();

        try {
            this.keyManager.loadFromFile(keyBindingsFile);
        } catch (IOException ignored) {
            // Use defaults if file cannot be read
        }
        try {
            this.editorSettings.loadFromFile(settingsFile);
        } catch (IOException ignored) {
            // Use defaults if file cannot be read
        }

        initResources();
        buildUi();
    }

    public void openAndRunLoop() {
        shell.open();
        scheduleEvaluation(0);
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        cleanup();
    }

    private void initResources() {
        monoFont = new Font(display, editorSettings.toFontData());
        applyThemeColors(editorSettings.loadActiveTheme());
    }

    private void applyThemeColors(TextMateTheme theme) {
        disposeThemeColors();
        this.activeTheme = theme;

        colorBackground = new Color(display, theme.getBackground());
        colorForeground = new Color(display, theme.getForeground());
        colorSelection = new Color(display, theme.getSelection());
        colorKeyword = new Color(display, theme.getStyleForToken(TokenType.KEYWORD).getForeground());
        colorType = new Color(display, theme.getStyleForToken(TokenType.TYPE_OR_LITERAL).getForeground());
        colorString = new Color(display, theme.getStyleForToken(TokenType.STRING).getForeground());
        colorNumber = new Color(display, theme.getStyleForToken(TokenType.NUMBER).getForeground());
        colorComment = new Color(display, theme.getStyleForToken(TokenType.COMMENT).getForeground());
        colorAnnotation = new Color(display, theme.getStyleForToken(TokenType.ANNOTATION).getForeground());
        colorError = new Color(display, theme.getErrorColor());
        colorOutputValue = new Color(display, theme.getOutputValueStyle().getForeground());
        colorOutputHeader = new Color(display, theme.getOutputHeaderStyle().getForeground());

        if (editorText != null && !editorText.isDisposed()) {
            editorText.setBackground(colorBackground);
            editorText.setForeground(colorForeground);
            editorText.setSelectionBackground(colorSelection);
        }
        if (outputText != null && !outputText.isDisposed()) {
            outputText.setBackground(colorBackground);
            outputText.setForeground(colorForeground);
            outputText.setSelectionBackground(colorSelection);
        }
        if (completionList != null && !completionList.isDisposed()) {
            completionList.setBackground(colorBackground);
            completionList.setForeground(colorForeground);
        }
    }

    private void buildUi() {
        shell.setText("JShell IDE");
        shell.setSize(1150, 740);
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 6;
        rootLayout.marginHeight = 6;
        shell.setLayout(rootLayout);

        buildMenuBar();
        buildToolbar();

        // Main two-panel SashForm
        SashForm sashForm = new SashForm(shell, SWT.HORIZONTAL);
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Left Panel: Editor
        Composite editorPane = new Composite(sashForm, SWT.NONE);
        GridLayout editorLayout = new GridLayout(1, false);
        editorLayout.marginWidth = 2;
        editorLayout.marginHeight = 2;
        editorPane.setLayout(editorLayout);

        Label editorHeader = new Label(editorPane, SWT.NONE);
        editorHeader.setText("JShell Editor (Auto-Complete & Error Squiggles)");
        editorHeader.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        editorText = new StyledText(
                editorPane, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        editorText.setFont(monoFont);
        editorText.setBackground(colorBackground);
        editorText.setForeground(colorForeground);
        editorText.setSelectionBackground(colorSelection);
        editorText.setTabs(4);
        editorText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        String initialText;
        try {
            initialText = editorSettings.loadInitialContent(currentFilePath);
        } catch (IOException ex) {
            initialText = "";
            showError("Failed to Read File", ex.getMessage());
        }
        editorText.setText(initialText);
        if (currentFilePath != null && currentFilePath.getFileName() != null) {
            shell.setText("JShell IDE - " + currentFilePath.getFileName());
        }

        // Right Panel: Live Output
        Composite outputPane = new Composite(sashForm, SWT.NONE);
        GridLayout outputLayout = new GridLayout(1, false);
        outputLayout.marginWidth = 2;
        outputLayout.marginHeight = 2;
        outputPane.setLayout(outputLayout);

        Label outputHeader = new Label(outputPane, SWT.NONE);
        outputHeader.setText("Live Execution Output");
        outputHeader.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        outputText = new StyledText(
                outputPane, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        outputText.setFont(monoFont);
        outputText.setBackground(colorBackground);
        outputText.setForeground(colorForeground);
        outputText.setSelectionBackground(colorSelection);
        outputText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        sashForm.setWeights(new int[] {55, 45});

        // Bottom Status Bar
        statusLabel = new Label(shell, SWT.BORDER);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Ready.");

        wireEditorListeners();
        refreshSyntaxAndSquiggles();
    }

    private void buildMenuBar() {
        Menu menuBar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(menuBar);

        // File menu
        MenuItem fileMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        fileMenuHeader.setText("&File");
        Menu fileMenu = new Menu(shell, SWT.DROP_DOWN);
        fileMenuHeader.setMenu(fileMenu);

        addBoundMenuItem(fileMenu, ActionId.FILE_NEW, this::doFileNew);
        addBoundMenuItem(fileMenu, ActionId.FILE_OPEN, this::doFileOpen);
        addBoundMenuItem(fileMenu, ActionId.FILE_SAVE, this::doFileSave);
        addBoundMenuItem(fileMenu, ActionId.FILE_SAVE_AS, this::doFileSaveAs);
        new MenuItem(fileMenu, SWT.SEPARATOR);
        MenuItem exitItem = new MenuItem(fileMenu, SWT.PUSH);
        exitItem.setText("E&xit");
        exitItem.addListener(SWT.Selection, e -> shell.close());

        // Edit menu
        MenuItem editMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        editMenuHeader.setText("&Edit");
        Menu editMenu = new Menu(shell, SWT.DROP_DOWN);
        editMenuHeader.setMenu(editMenu);

        addBoundMenuItem(editMenu, ActionId.EDIT_UNDO, this::doUndo);
        addBoundMenuItem(editMenu, ActionId.EDIT_REDO, this::doRedo);
        new MenuItem(editMenu, SWT.SEPARATOR);
        addBoundMenuItem(editMenu, ActionId.AUTO_COMPLETE, this::showAutoCompletePopup);
        addBoundMenuItem(editMenu, ActionId.CLEAR_OUTPUT, () -> outputText.setText(""));

        // Run menu
        MenuItem runMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        runMenuHeader.setText("&Run");
        Menu runMenu = new Menu(shell, SWT.DROP_DOWN);
        runMenuHeader.setMenu(runMenu);

        addBoundMenuItem(runMenu, ActionId.RUN_EVALUATE, () -> scheduleEvaluation(0));

        liveEvalMenuItem = new MenuItem(runMenu, SWT.CHECK);
        liveEvalMenuItem.setSelection(liveEvaluationEnabled);
        actionMenuItems.put(ActionId.TOGGLE_LIVE_EVAL, liveEvalMenuItem);
        liveEvalMenuItem.addListener(SWT.Selection, e -> {
            setLiveEvaluationEnabled(liveEvalMenuItem.getSelection());
        });

        new MenuItem(runMenu, SWT.SEPARATOR);
        addBoundMenuItem(runMenu, ActionId.CONFIGURE_CLASSPATH, this::openClasspathDialog);

        // Settings menu
        MenuItem settingsMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        settingsMenuHeader.setText("&Settings");
        Menu settingsMenu = new Menu(shell, SWT.DROP_DOWN);
        settingsMenuHeader.setMenu(settingsMenu);

        addBoundMenuItem(settingsMenu, ActionId.CONFIGURE_FONT, this::openFontDialog);
        addBoundMenuItem(settingsMenu, ActionId.CONFIGURE_THEME, this::openThemeDialog);
        addBoundMenuItem(settingsMenu, ActionId.CONFIGURE_KEYS, this::openKeyBindingsDialog);

        updateMenuAccelerators();
    }

    private void addBoundMenuItem(Menu parentMenu, ActionId actionId, Runnable handler) {
        MenuItem item = new MenuItem(parentMenu, SWT.PUSH);
        actionMenuItems.put(actionId, item);
        item.addListener(SWT.Selection, e -> handler.run());
    }

    private void updateMenuAccelerators() {
        for (Map.Entry<ActionId, MenuItem> entry : actionMenuItems.entrySet()) {
            ActionId action = entry.getKey();
            MenuItem item = entry.getValue();
            if (item == null || item.isDisposed()) {
                continue;
            }
            String shortcut = keyManager.getShortcutText(action);
            String label = action.getDisplayName();
            if (!shortcut.isEmpty()) {
                label += "\t" + shortcut;
            }
            item.setText(label);
            item.setAccelerator(keyManager.getAccelerator(action));
        }
    }

    private void buildToolbar() {
        Composite bar = new Composite(shell, SWT.NONE);
        GridLayout layout = new GridLayout(11, false);
        layout.marginWidth = 0;
        layout.marginHeight = 2;
        bar.setLayout(layout);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button newBtn = new Button(bar, SWT.PUSH);
        newBtn.setText("New");
        newBtn.addListener(SWT.Selection, e -> doFileNew());

        Button openBtn = new Button(bar, SWT.PUSH);
        openBtn.setText("Open...");
        openBtn.addListener(SWT.Selection, e -> doFileOpen());

        Button saveBtn = new Button(bar, SWT.PUSH);
        saveBtn.setText("Save");
        saveBtn.addListener(SWT.Selection, e -> doFileSave());

        Button saveAsBtn = new Button(bar, SWT.PUSH);
        saveAsBtn.setText("Save As...");
        saveAsBtn.addListener(SWT.Selection, e -> doFileSaveAs());

        Button runBtn = new Button(bar, SWT.PUSH);
        runBtn.setText("Evaluate Now");
        runBtn.addListener(SWT.Selection, e -> scheduleEvaluation(0));

        liveEvalCheckBtn = new Button(bar, SWT.CHECK);
        liveEvalCheckBtn.setText("Live Evaluation (on type)");
        liveEvalCheckBtn.setSelection(liveEvaluationEnabled);
        liveEvalCheckBtn.addListener(SWT.Selection, e -> {
            setLiveEvaluationEnabled(liveEvalCheckBtn.getSelection());
        });

        Button cpBtn = new Button(bar, SWT.PUSH);
        cpBtn.setText("Classpath && Maven...");
        cpBtn.addListener(SWT.Selection, e -> openClasspathDialog());

        Button fontBtn = new Button(bar, SWT.PUSH);
        fontBtn.setText("Font...");
        fontBtn.addListener(SWT.Selection, e -> openFontDialog());

        Button themeBtn = new Button(bar, SWT.PUSH);
        themeBtn.setText("Theme (.tmTheme)...");
        themeBtn.addListener(SWT.Selection, e -> openThemeDialog());

        Button keysBtn = new Button(bar, SWT.PUSH);
        keysBtn.setText("Key Bindings...");
        keysBtn.addListener(SWT.Selection, e -> openKeyBindingsDialog());

        Button clearBtn = new Button(bar, SWT.PUSH);
        clearBtn.setText("Clear Output");
        clearBtn.addListener(SWT.Selection, e -> outputText.setText(""));
    }

    private void wireEditorListeners() {
        editorText.addExtendedModifyListener(event -> {
            String inserted = event.length > 0
                    ? editorText.getTextRange(event.start, event.length)
                    : "";
            undoRedoManager.recordEdit(event.start, event.replacedText, inserted);
        });

        editorText.addModifyListener(e -> {
            refreshSyntaxAndSquiggles();
            if (liveEvaluationEnabled) {
                scheduleEvaluation(LIVE_EVAL_DEBOUNCE_MS);
            }
        });

        editorText.addListener(SWT.KeyDown, e -> {
            if (isCompletionPopupVisible()) {
                if (e.keyCode == SWT.ARROW_DOWN) {
                    e.doit = false;
                    int next = Math.min(
                            completionList.getItemCount() - 1,
                            completionList.getSelectionIndex() + 1);
                    completionList.setSelection(next);
                    return;
                } else if (e.keyCode == SWT.ARROW_UP) {
                    e.doit = false;
                    int prev = Math.max(0, completionList.getSelectionIndex() - 1);
                    completionList.setSelection(prev);
                    return;
                } else if (e.keyCode == SWT.CR || e.keyCode == SWT.LF || e.keyCode == SWT.TAB) {
                    e.doit = false;
                    applySelectedCompletion();
                    return;
                } else if (e.keyCode == SWT.ESC) {
                    e.doit = false;
                    hideCompletionPopup();
                    return;
                }
            }

            ActionId matched = keyManager.findMatchingAction(e.stateMask, e.keyCode);
            if (matched != null) {
                e.doit = false;
                executeAction(matched);
                return;
            }

            // Also support standard macOS Cmd+Z / Cmd+Shift+Z and Ctrl+Shift+Z for Undo/Redo
            int normalizedKey = Character.toUpperCase(e.keyCode);
            boolean primaryMod = (e.stateMask & (SWT.MOD1 | SWT.CTRL)) != 0;
            boolean shiftMod = (e.stateMask & SWT.SHIFT) != 0;
            if (primaryMod && normalizedKey == 'Z') {
                e.doit = false;
                if (shiftMod) {
                    doRedo();
                } else {
                    doUndo();
                }
                return;
            } else if (primaryMod && !shiftMod && normalizedKey == 'Y') {
                e.doit = false;
                doRedo();
                return;
            }

            if (isCompletionPopupVisible()) {
                display.asyncExec(() -> {
                    if (!editorText.isDisposed() && isCompletionPopupVisible()) {
                        showAutoCompletePopup();
                    }
                });
            }
        });

        // Trigger completion suggestions automatically when typing '.'
        editorText.addListener(SWT.Verify, e -> {
            if (".".equals(e.text)) {
                display.timerExec(60, () -> {
                    if (!editorText.isDisposed()) {
                        showAutoCompletePopup();
                    }
                });
            }
        });

        // Show diagnostic message in tooltip & status bar when moving caret or mouse over a squiggle
        editorText.addListener(SWT.MouseMove, e -> {
            int offset = editorText.getOffsetAtPoint(new Point(e.x, e.y));
            String msg = highlighter.findDiagnosticMessageAt(
                    offset, editorText.getCharCount(), currentDiagnostics);
            editorText.setToolTipText(msg);
        });

        editorText.addCaretListener(e -> {
            String msg = highlighter.findDiagnosticMessageAt(
                    e.caretOffset, editorText.getCharCount(), currentDiagnostics);
            if (msg != null) {
                statusLabel.setText("Error: " + msg);
            }
        });

        editorText.addListener(SWT.FocusOut, e -> {
            display.timerExec(150, () -> {
                if (completionPopup != null
                        && !completionPopup.isDisposed()
                        && !completionList.isFocusControl()) {
                    hideCompletionPopup();
                }
            });
        });
    }

    private void executeAction(ActionId action) {
        switch (action) {
            case FILE_NEW -> doFileNew();
            case FILE_OPEN -> doFileOpen();
            case FILE_SAVE -> doFileSave();
            case FILE_SAVE_AS -> doFileSaveAs();
            case EDIT_UNDO -> doUndo();
            case EDIT_REDO -> doRedo();
            case AUTO_COMPLETE -> showAutoCompletePopup();
            case RUN_EVALUATE -> scheduleEvaluation(0);
            case TOGGLE_LIVE_EVAL -> setLiveEvaluationEnabled(!liveEvaluationEnabled);
            case CLEAR_OUTPUT -> outputText.setText("");
            case CONFIGURE_CLASSPATH -> openClasspathDialog();
            case CONFIGURE_FONT -> openFontDialog();
            case CONFIGURE_THEME -> openThemeDialog();
            case CONFIGURE_KEYS -> openKeyBindingsDialog();
        }
    }

    private void doUndo() {
        undoRedoManager.undo(asEditorTarget());
    }

    private void doRedo() {
        undoRedoManager.redo(asEditorTarget());
    }

    private TextEditorTarget asEditorTarget() {
        return new TextEditorTarget() {
            @Override
            public void replaceTextRange(int start, int length, String text) {
                if (editorText != null && !editorText.isDisposed()) {
                    editorText.replaceTextRange(start, length, text);
                }
            }

            @Override
            public void setCaretOffset(int offset) {
                if (editorText != null && !editorText.isDisposed()) {
                    editorText.setCaretOffset(offset);
                }
            }
        };
    }

    private void setLiveEvaluationEnabled(boolean enabled) {
        this.liveEvaluationEnabled = enabled;
        if (liveEvalCheckBtn != null && !liveEvalCheckBtn.isDisposed()) {
            liveEvalCheckBtn.setSelection(enabled);
        }
        if (liveEvalMenuItem != null && !liveEvalMenuItem.isDisposed()) {
            liveEvalMenuItem.setSelection(enabled);
        }
        statusLabel.setText("Live Evaluation: " + (enabled ? "ON" : "OFF"));
        if (enabled) {
            scheduleEvaluation(0);
        }
    }

    private void scheduleEvaluation(int delayMs) {
        long gen = evaluationGeneration.incrementAndGet();
        display.timerExec(delayMs, () -> {
            if (shell.isDisposed() || evaluationGeneration.get() != gen) {
                return;
            }
            String codeSnapshot = editorText.getText();
            statusLabel.setText("Evaluating in JShell...");

            evalExecutor.submit(() -> {
                if (evaluationGeneration.get() != gen) {
                    return;
                }
                EvaluationResult result = engine.evaluateScratchpad(codeSnapshot);
                if (evaluationGeneration.get() != gen) {
                    return;
                }
                display.asyncExec(() -> {
                    if (shell.isDisposed() || evaluationGeneration.get() != gen) {
                        return;
                    }
                    lastEvaluationResult = result;
                    currentDiagnostics = result.getDiagnostics();
                    refreshSyntaxAndSquiggles();
                    renderEvaluationOutput(result);
                });
            });
        });
    }

    private void refreshSyntaxAndSquiggles() {
        if (editorText == null || editorText.isDisposed()) {
            return;
        }
        String code = editorText.getText();
        List<StyledSpan> spans = highlighter.computeMergedSpans(code, currentDiagnostics);
        StyleRange[] ranges = new StyleRange[spans.size()];

        for (int i = 0; i < spans.size(); i++) {
            StyledSpan span = spans.get(i);
            StyleRange sr = new StyleRange();
            sr.start = span.getStart();
            sr.length = span.getLength();

            TokenType tokenType = span.getTokenType();
            TokenStyle tokenStyle = activeTheme.getStyleForToken(tokenType);
            sr.fontStyle = tokenStyle.getFontStyle();

            switch (tokenType) {
                case KEYWORD -> sr.foreground = colorKeyword;
                case TYPE_OR_LITERAL -> sr.foreground = colorType;
                case STRING -> sr.foreground = colorString;
                case NUMBER -> sr.foreground = colorNumber;
                case COMMENT -> sr.foreground = colorComment;
                case ANNOTATION -> sr.foreground = colorAnnotation;
                case NORMAL -> sr.foreground = colorForeground;
            }

            if (span.hasErrorSquiggle()) {
                sr.underline = true;
                sr.underlineStyle = SWT.UNDERLINE_SQUIGGLE;
                sr.underlineColor = colorError;
            }
            ranges[i] = sr;
        }

        editorText.setStyleRanges(ranges);
    }

    private void renderEvaluationOutput(EvaluationResult result) {
        if (outputText == null || outputText.isDisposed() || result == null) {
            return;
        }
        String text = result.getFormattedOutput();
        if (text.isBlank()) {
            text = "(No snippets to evaluate)";
        }
        outputText.setText(text);

        int headerFontStyle = activeTheme.getOutputHeaderStyle().getFontStyle();
        int valueFontStyle = activeTheme.getOutputValueStyle().getFontStyle();

        // Apply line-based color styling to output
        List<StyleRange> ranges = new ArrayList<>();
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            int len = line.length();
            if (len > 0) {
                if (line.startsWith("[")) {
                    StyleRange sr = new StyleRange(
                            offset, len, colorOutputHeader, null, headerFontStyle);
                    ranges.add(sr);
                } else if (line.startsWith("  ==>")) {
                    StyleRange sr = new StyleRange(
                            offset, len, colorOutputValue, null, valueFontStyle);
                    ranges.add(sr);
                } else if (line.startsWith("  [ERROR]")
                        || line.startsWith("  [EXCEPTION]")
                        || line.startsWith("  err:")) {
                    StyleRange sr = new StyleRange(offset, len, colorError, null, SWT.NORMAL);
                    ranges.add(sr);
                }
            }
            offset += len + 1;
        }
        outputText.setStyleRanges(ranges.toArray(new StyleRange[0]));

        int errCount = result.getDiagnostics().size();
        statusLabel.setText(String.format(
                "Evaluated %d snippet(s) in %d ms | Errors: %d | Classpath entries: %d",
                result.getSnippetResults().size(),
                result.getDurationMillis(),
                errCount,
                engine.getClasspathEntries().size()));
    }

    private void showAutoCompletePopup() {
        String code = editorText.getText();
        int caret = editorText.getCaretOffset();
        CompletionResult result = engine.getCompletions(code, caret);
        List<String> suggestions = result.getSuggestions();

        if (suggestions.isEmpty()) {
            hideCompletionPopup();
            statusLabel.setText("No auto-completion suggestions at current position.");
            return;
        }

        currentCompletionAnchor = result.getAnchorOffset();
        if (completionPopup == null || completionPopup.isDisposed()) {
            completionPopup = new Shell(shell, SWT.ON_TOP | SWT.NO_TRIM | SWT.BORDER);
            completionPopup.setLayout(new FillLayout());
            completionList = new org.eclipse.swt.widgets.List(
                    completionPopup, SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
            completionList.setFont(monoFont);
            completionList.setBackground(colorBackground);
            completionList.setForeground(colorForeground);
            completionList.addListener(SWT.DefaultSelection, e -> applySelectedCompletion());
            completionList.addListener(SWT.MouseUp, e -> applySelectedCompletion());
            completionList.addListener(SWT.KeyDown, e -> {
                if (e.keyCode == SWT.CR || e.keyCode == SWT.LF) {
                    applySelectedCompletion();
                } else if (e.keyCode == SWT.ESC) {
                    hideCompletionPopup();
                    editorText.setFocus();
                }
            });
        }

        completionList.removeAll();
        int limit = Math.min(suggestions.size(), 100);
        for (int i = 0; i < limit; i++) {
            completionList.add(suggestions.get(i));
        }
        completionList.setSelection(0);

        Point caretLocation = editorText.getLocationAtOffset(caret);
        Point displayPoint = editorText.toDisplay(
                caretLocation.x, caretLocation.y + editorText.getLineHeight());
        completionPopup.setBounds(displayPoint.x, displayPoint.y, 320, 180);
        completionPopup.setVisible(true);
    }

    private void applySelectedCompletion() {
        if (!isCompletionPopupVisible()) {
            return;
        }
        int idx = completionList.getSelectionIndex();
        if (idx < 0) {
            hideCompletionPopup();
            return;
        }
        String selected = completionList.getItem(idx);
        hideCompletionPopup();

        int caret = editorText.getCaretOffset();
        int replaceStart = Math.max(0, Math.min(currentCompletionAnchor, caret));
        int replaceLength = Math.max(0, caret - replaceStart);

        editorText.replaceTextRange(replaceStart, replaceLength, selected);
        editorText.setCaretOffset(replaceStart + selected.length());
        editorText.setFocus();
    }

    private boolean isCompletionPopupVisible() {
        return completionPopup != null
                && !completionPopup.isDisposed()
                && completionPopup.isVisible();
    }

    private void hideCompletionPopup() {
        if (completionPopup != null && !completionPopup.isDisposed()) {
            completionPopup.setVisible(false);
        }
    }

    private void openClasspathDialog() {
        ClasspathDialog dialog = new ClasspathDialog(
                shell,
                mavenResolver,
                engine.getClasspathEntries(),
                mavenCoordinates);
        if (dialog.open()) {
            mavenCoordinates.clear();
            mavenCoordinates.addAll(dialog.getMavenCoordinates());
            engine.setClasspathEntries(dialog.getClasspathEntries());
            scheduleEvaluation(0);
        }
    }

    private void openFontDialog() {
        FontDialog fd = new FontDialog(shell);
        fd.setText("Select Editor Font");
        if (monoFont != null && !monoFont.isDisposed()) {
            fd.setFontList(monoFont.getFontData());
        } else {
            fd.setFontList(new FontData[] {editorSettings.toFontData()});
        }
        FontData selected = fd.open();
        if (selected != null) {
            editorSettings.updateFromFontData(selected);
            Font newFont = new Font(display, selected);
            Font oldFont = monoFont;
            monoFont = newFont;
            if (editorText != null && !editorText.isDisposed()) {
                editorText.setFont(monoFont);
            }
            if (outputText != null && !outputText.isDisposed()) {
                outputText.setFont(monoFont);
            }
            if (completionList != null && !completionList.isDisposed()) {
                completionList.setFont(monoFont);
            }
            if (oldFont != null && !oldFont.isDisposed()) {
                oldFont.dispose();
            }
            refreshSyntaxAndSquiggles();
            try {
                editorSettings.saveToFile(settingsFile);
            } catch (IOException ignored) {
                // Non-fatal if saving fails
            }
            statusLabel.setText(
                    "Font updated to " + selected.getName() + " " + selected.getHeight() + "pt.");
        }
    }

    private void openThemeDialog() {
        editorSettings.ensureThemesDirSeeded();
        Path themesDir = editorSettings.getThemesDir();

        FileDialog fd = new FileDialog(shell, SWT.OPEN);
        fd.setText("Select TextMate Theme (.tmTheme)");
        fd.setFilterExtensions(new String[] {"*.tmTheme;*.plist;*.xml", "*.*"});
        fd.setFilterPath(themesDir.toAbsolutePath().toString());
        String currentThemeName = editorSettings.getThemeName();
        if (currentThemeName != null && !currentThemeName.isBlank()) {
            fd.setFileName(currentThemeName);
        }

        String selected = fd.open();
        if (selected != null) {
            try {
                Path selectedPath = Path.of(selected).toAbsolutePath().normalize();
                String fileName = selectedPath.getFileName().toString();
                Path targetThemePath = themesDir.resolve(fileName).toAbsolutePath().normalize();
                if (!selectedPath.equals(targetThemePath)) {
                    Files.createDirectories(themesDir);
                    Files.copy(selectedPath, targetThemePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                TextMateTheme loadedTheme = TextMateTheme.loadFromFile(targetThemePath);
                editorSettings.setThemeName(fileName);
                applyThemeColors(loadedTheme);
                refreshSyntaxAndSquiggles();
                if (lastEvaluationResult != null) {
                    renderEvaluationOutput(lastEvaluationResult);
                }
                try {
                    editorSettings.saveToFile(settingsFile);
                } catch (IOException ignored) {
                    // Non-fatal if saving fails
                }
                statusLabel.setText(
                        "Loaded theme: " + loadedTheme.getName() + " (" + fileName + ")");
            } catch (IOException ex) {
                showError("Failed to Load Theme", ex.getMessage());
            }
        }
    }

    private void openKeyBindingsDialog() {
        KeyBindingsDialog dialog = new KeyBindingsDialog(shell, keyManager);
        if (dialog.open()) {
            updateMenuAccelerators();
            try {
                keyManager.saveToFile(keyBindingsFile);
            } catch (IOException ignored) {
                // Non-fatal if saving fails
            }
            statusLabel.setText("Key bindings updated.");
        }
    }

    private void doFileNew() {
        currentFilePath = null;
        editorText.setText("");
        undoRedoManager.clear();
        shell.setText("JShell IDE - Untitled");
        scheduleEvaluation(0);
    }

    private void doFileOpen() {
        FileDialog fd = new FileDialog(shell, SWT.OPEN);
        fd.setText("Open JShell / Java File");
        fd.setFilterExtensions(new String[] {"*.jsh;*.java", "*.*"});
        String selected = fd.open();
        if (selected != null) {
            try {
                Path path = Path.of(selected);
                String content = Files.readString(path, StandardCharsets.UTF_8);
                currentFilePath = path;
                editorText.setText(content);
                undoRedoManager.clear();
                shell.setText("JShell IDE - " + path.getFileName());
                scheduleEvaluation(0);
            } catch (IOException ex) {
                showError("Open Failed", ex.getMessage());
            }
        }
    }

    private void doFileSave() {
        if (currentFilePath == null) {
            doFileSaveAs();
            return;
        }
        saveToCurrentFile();
    }

    private void doFileSaveAs() {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Save Scratchpad As");
        fd.setFilterExtensions(new String[] {"*.jsh", "*.java", "*.*"});
        if (currentFilePath != null) {
            if (currentFilePath.getParent() != null) {
                fd.setFilterPath(currentFilePath.getParent().toString());
            }
            if (currentFilePath.getFileName() != null) {
                fd.setFileName(currentFilePath.getFileName().toString());
            }
        } else {
            fd.setFileName("scratchpad.jsh");
        }
        String selected = fd.open();
        if (selected == null) {
            return;
        }
        currentFilePath = Path.of(selected).toAbsolutePath().normalize();
        saveToCurrentFile();
    }

    private void saveToCurrentFile() {
        try {
            EditorSettings.saveContentToFile(currentFilePath, editorText.getText());
            shell.setText("JShell IDE - " + currentFilePath.getFileName());
            statusLabel.setText("Saved to " + currentFilePath);
        } catch (IOException ex) {
            showError("Save Failed", ex.getMessage());
        }
    }

    private void showError(String title, String message) {
        MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        mb.setText(title);
        mb.setMessage(message != null ? message : "Unexpected error");
        mb.open();
    }

    private void disposeThemeColors() {
        disposeColor(colorBackground);
        disposeColor(colorForeground);
        disposeColor(colorSelection);
        disposeColor(colorKeyword);
        disposeColor(colorType);
        disposeColor(colorString);
        disposeColor(colorNumber);
        disposeColor(colorComment);
        disposeColor(colorAnnotation);
        disposeColor(colorError);
        disposeColor(colorOutputValue);
        disposeColor(colorOutputHeader);
    }

    private static void disposeColor(Color c) {
        if (c != null && !c.isDisposed()) {
            c.dispose();
        }
    }

    private void cleanup() {
        evalExecutor.shutdownNow();
        engine.close();
        if (monoFont != null && !monoFont.isDisposed()) {
            monoFont.dispose();
        }
        disposeThemeColors();
    }
}
