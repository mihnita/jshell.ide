package com.github.mnita.jshellide.editor;

import com.github.mnita.jshellide.theme.TextMateTheme;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;

/**
 * Stores and persists user-configurable editor settings such as font family, size, style,
 * and active TextMate {@code .tmTheme} filename inside {@code ~/.config/jshell-ide/}.
 */
public class EditorSettings {

    public static final String FALLBACK_SCRATCHPAD_CODE = "System.out.println(\"Hello world!\");";

    private static final String KEY_FONT_NAME = "editor.font.name";
    private static final String KEY_FONT_SIZE = "editor.font.size";
    private static final String KEY_FONT_STYLE = "editor.font.style";
    private static final String KEY_THEME_NAME = "editor.theme";
    private static final String KEY_MAVEN_DEPENDENCIES = "maven.dependencies";
    private static final String KEY_CLASSPATH_ENTRIES = "classpath.entries";
    private static final String KEY_CLASSPATH_ACTIVE = "classpath.active";

    private final Path configDir;
    private String fontName;
    private int fontSize;
    private int fontStyle;
    private String themeName;
    private final List<String> mavenCoordinates = new ArrayList<>();
    private final List<String> classpathEntries = new ArrayList<>();
    private final List<String> activeClasspathEntries = new ArrayList<>();

    public EditorSettings() {
        this(defaultConfigDir());
    }

    public EditorSettings(Path configDir) {
        this.configDir = configDir != null ? configDir : defaultConfigDir();
        this.fontName = defaultFontName();
        this.fontSize = 13;
        this.fontStyle = SWT.NORMAL;
        this.themeName = "default-light.tmTheme";
    }

    public static Path defaultConfigDir() {
        return Path.of(System.getProperty("user.home", "."), ".config", "jshell-ide");
    }

    public static String defaultFontName() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac")
                ? "Menlo"
                : "Monospaced";
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Path getThemesDir() {
        return configDir.resolve("themes");
    }

    public Path getSettingsFile() {
        return configDir.resolve("settings.properties");
    }

    public Path getKeyBindingsFile() {
        return configDir.resolve("keybindings.properties");
    }

    public Path getDefaultScratchpadFile() {
        return configDir.resolve("default.jsh");
    }

    /**
     * Ensures {@code ~/.config/jshell-ide/themes/} exists and copies the bundled
     * {@code .tmTheme} files into it if they are not already present.
     */
    public void ensureThemesDirSeeded() {
        Path themesDir = getThemesDir();
        try {
            Files.createDirectories(themesDir);
            seedBundledThemeIfMissing(themesDir, "default-light.tmTheme");
            seedBundledThemeIfMissing(themesDir, "monokai-dark.tmTheme");
        } catch (IOException ignored) {
            // Non-fatal if directory creation fails
        }
    }

    private static void seedBundledThemeIfMissing(Path themesDir, String fileName) {
        Path target = themesDir.resolve(fileName);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = TextMateTheme.class.getResourceAsStream("/themes/" + fileName)) {
            if (in != null) {
                Files.copy(in, target);
            }
        } catch (IOException ignored) {
            // Non-fatal if seeding fails
        }
    }

    /**
     * Loads {@code default.jsh} from the configuration directory if it exists,
     * otherwise returns {@link #FALLBACK_SCRATCHPAD_CODE}.
     */
    public String loadInitialScratchpadCode() {
        Path defaultJsh = getDefaultScratchpadFile();
        if (Files.isRegularFile(defaultJsh)) {
            try {
                return Files.readString(defaultJsh, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fall back to default code below
            }
        }
        return FALLBACK_SCRATCHPAD_CODE;
    }

    /**
     * Loads the initial editor content given an optional command-line file path.
     * If {@code cliFile} is non-null and exists, reads its contents; if {@code cliFile}
     * is non-null but does not exist yet on disk, returns an empty string so saving
     * creates the new file. If {@code cliFile} is {@code null}, delegates to
     * {@link #loadInitialScratchpadCode()}.
     */
    public String loadInitialContent(Path cliFile) throws IOException {
        if (cliFile != null) {
            if (Files.isRegularFile(cliFile)) {
                return Files.readString(cliFile, StandardCharsets.UTF_8);
            }
            return "";
        }
        return loadInitialScratchpadCode();
    }

    /**
     * Saves the editor content to {@code targetFile}, creating parent directories if needed.
     */
    public static void saveContentToFile(Path targetFile, String content) throws IOException {
        if (targetFile == null) {
            throw new IllegalArgumentException("targetFile must not be null");
        }
        Path abs = targetFile.toAbsolutePath().normalize();
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        Files.writeString(abs, content != null ? content : "", StandardCharsets.UTF_8);
    }

    public String getFontName() {
        return fontName;
    }

    public void setFontName(String fontName) {
        if (fontName != null && !fontName.isBlank()) {
            this.fontName = fontName.trim();
        }
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        if (fontSize > 0) {
            this.fontSize = fontSize;
        }
    }

    public int getFontStyle() {
        return fontStyle;
    }

    public void setFontStyle(int fontStyle) {
        this.fontStyle = fontStyle & (SWT.NORMAL | SWT.BOLD | SWT.ITALIC);
    }

    public FontData toFontData() {
        return new FontData(fontName, fontSize, fontStyle);
    }

    public void updateFromFontData(FontData fontData) {
        if (fontData == null) {
            return;
        }
        setFontName(fontData.getName());
        setFontSize(fontData.getHeight());
        setFontStyle(fontData.getStyle());
    }

    /**
     * Returns the configured theme filename (relative to {@link #getThemesDir()}, never an absolute path).
     */
    public String getThemeName() {
        return themeName;
    }

    /**
     * Sets the theme filename (relative to {@link #getThemesDir()}). If a path with directories
     * is passed, only its file name component is kept.
     */
    public void setThemeName(String themeName) {
        if (themeName == null || themeName.isBlank()) {
            this.themeName = "";
            return;
        }
        Path fileNamePart = Path.of(themeName.trim()).getFileName();
        this.themeName = fileNamePart != null ? fileNamePart.toString() : "";
    }

    /**
     * Loads the active {@link TextMateTheme} from {@code <configDir>/themes/<themeName>}
     * if set and readable, or falls back to the bundled default {@code .tmTheme}.
     */
    public TextMateTheme loadActiveTheme() {
        if (themeName != null && !themeName.isBlank()) {
            Path p = getThemesDir().resolve(themeName);
            if (Files.isRegularFile(p)) {
                try {
                    return TextMateTheme.loadFromFile(p);
                } catch (IOException ignored) {
                    // Fall back to bundled theme
                }
            }
        }
        return TextMateTheme.loadBundledDefault();
    }

    public List<String> getMavenCoordinates() {
        return Collections.unmodifiableList(new ArrayList<>(mavenCoordinates));
    }

    public void setMavenCoordinates(List<String> coords) {
        copyUniqueNonBlank(coords, this.mavenCoordinates);
    }

    public List<String> getClasspathEntries() {
        return Collections.unmodifiableList(new ArrayList<>(classpathEntries));
    }

    public void setClasspathEntries(List<String> entries) {
        copyUniqueNonBlank(entries, this.classpathEntries);
    }

    public List<String> getActiveClasspathEntries() {
        return Collections.unmodifiableList(new ArrayList<>(activeClasspathEntries));
    }

    public void setActiveClasspathEntries(List<String> activeEntries) {
        copyUniqueNonBlank(activeEntries, this.activeClasspathEntries);
    }

    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty(KEY_FONT_NAME, fontName);
        props.setProperty(KEY_FONT_SIZE, Integer.toString(fontSize));
        props.setProperty(KEY_FONT_STYLE, Integer.toString(fontStyle));
        props.setProperty(KEY_THEME_NAME, themeName);
        props.setProperty(KEY_MAVEN_DEPENDENCIES, String.join("\n", mavenCoordinates));
        props.setProperty(KEY_CLASSPATH_ENTRIES, String.join("\n", classpathEntries));
        props.setProperty(KEY_CLASSPATH_ACTIVE, String.join("\n", activeClasspathEntries));
        return props;
    }

    public void fromProperties(Properties props) {
        if (props == null) {
            return;
        }
        String name = props.getProperty(KEY_FONT_NAME);
        if (name != null && !name.isBlank()) {
            setFontName(name);
        }
        String sizeStr = props.getProperty(KEY_FONT_SIZE);
        if (sizeStr != null) {
            try {
                setFontSize(Integer.parseInt(sizeStr.trim()));
            } catch (NumberFormatException ignored) {
                // Keep existing font size
            }
        }
        String styleStr = props.getProperty(KEY_FONT_STYLE);
        if (styleStr != null) {
            try {
                setFontStyle(Integer.parseInt(styleStr.trim()));
            } catch (NumberFormatException ignored) {
                // Keep existing font style
            }
        }
        String theme = props.getProperty(KEY_THEME_NAME);
        if (theme != null) {
            setThemeName(theme);
        }
        String mavenStr = props.getProperty(KEY_MAVEN_DEPENDENCIES);
        if (mavenStr != null) {
            setMavenCoordinates(parseLinesList(mavenStr));
        }
        String cpEntriesStr = props.getProperty(KEY_CLASSPATH_ENTRIES);
        if (cpEntriesStr != null) {
            setClasspathEntries(parseLinesList(cpEntriesStr));
        }
        String cpActiveStr = props.getProperty(KEY_CLASSPATH_ACTIVE);
        if (cpActiveStr != null) {
            setActiveClasspathEntries(parseLinesList(cpActiveStr));
        }
    }

    private static List<String> parseLinesList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private static void copyUniqueNonBlank(List<String> source, List<String> target) {
        target.clear();
        if (source == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String item : source) {
            if (item != null && !item.isBlank()) {
                seen.add(item.trim());
            }
        }
        target.addAll(seen);
    }

    public void saveToFile(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Properties props = toProperties();
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "JShell IDE Editor Settings");
        }
    }

    public void loadFromFile(Path path) throws IOException {
        if (path != null && Files.isRegularFile(path)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            }
            fromProperties(props);
        }
    }
}
