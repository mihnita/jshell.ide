package com.github.mnita.jshellide.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.theme.TextMateTheme;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.junit.Test;

public class EditorSettingsTest {

    @Test
    public void testDefaultSettingsAndConfigDir() {
        EditorSettings settings = new EditorSettings();
        assertFalse(settings.getFontName().isBlank());
        assertEquals(13, settings.getFontSize());
        assertEquals(SWT.NORMAL, settings.getFontStyle());
        assertTrue(settings.getConfigDir().toString().endsWith(
                ".config" + File.separator + "jshell-ide"));
        assertEquals("default-light.tmTheme", settings.getThemeName());
    }

    @Test
    public void testUpdateFromFontDataAndToFontData() {
        EditorSettings settings = new EditorSettings();
        FontData fd = new FontData("JetBrains Mono", 16, SWT.BOLD);
        settings.updateFromFontData(fd);

        assertEquals("JetBrains Mono", settings.getFontName());
        assertEquals(16, settings.getFontSize());
        assertEquals(SWT.BOLD, settings.getFontStyle());

        FontData roundTrip = settings.toFontData();
        assertEquals("JetBrains Mono", roundTrip.getName());
        assertEquals(16, roundTrip.getHeight());
        assertEquals(SWT.BOLD, roundTrip.getStyle());
    }

    @Test
    public void testSaveAndLoadSettingsWithRelativeThemeName() throws Exception {
        Path tempDir = Files.createTempDirectory("jshell-ide-config-test");
        try {
            EditorSettings s1 = new EditorSettings(tempDir);
            s1.ensureThemesDirSeeded();
            assertTrue(Files.isRegularFile(s1.getThemesDir().resolve("monokai-dark.tmTheme")));

            s1.setFontName("Courier New");
            s1.setFontSize(18);
            s1.setFontStyle(SWT.ITALIC);
            // Passing a path should strip directories and store only the relative filename
            s1.setThemeName("/any/folder/monokai-dark.tmTheme");
            assertEquals("monokai-dark.tmTheme", s1.getThemeName());
            s1.saveToFile(s1.getSettingsFile());

            EditorSettings s2 = new EditorSettings(tempDir);
            s2.loadFromFile(s2.getSettingsFile());
            assertEquals("Courier New", s2.getFontName());
            assertEquals(18, s2.getFontSize());
            assertEquals(SWT.ITALIC, s2.getFontStyle());
            assertEquals("monokai-dark.tmTheme", s2.getThemeName());

            TextMateTheme activeTheme = s2.loadActiveTheme();
            assertEquals("Monokai Dark", activeTheme.getName());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testLoadInitialScratchpadCodeFallbackAndDefaultJsh() throws Exception {
        Path tempDir = Files.createTempDirectory("jshell-ide-jsh-test");
        try {
            EditorSettings settings = new EditorSettings(tempDir);
            // Without default.jsh, should return fallback Hello world
            assertEquals(
                    "System.out.println(\"Hello world!\");",
                    settings.loadInitialScratchpadCode());

            // With default.jsh present in configDir, should load its contents
            String customDefault = "int customInit = 99;\nSystem.out.println(customInit);";
            Files.writeString(
                    settings.getDefaultScratchpadFile(), customDefault, StandardCharsets.UTF_8);
            assertEquals(customDefault, settings.loadInitialScratchpadCode());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testLoadInitialContentFromCliFileAndSave() throws Exception {
        Path tempDir = Files.createTempDirectory("jshell-ide-cli-test");
        try {
            EditorSettings settings = new EditorSettings(tempDir);
            Path cliFile = tempDir.resolve("myscript.jsh");

            // Non-existent CLI file starts empty, then saving creates it
            assertEquals("", settings.loadInitialContent(cliFile));

            String savedCode = "int x = 123;\nSystem.out.println(x);";
            EditorSettings.saveContentToFile(cliFile, savedCode);
            assertTrue(Files.isRegularFile(cliFile));
            assertEquals(savedCode, settings.loadInitialContent(cliFile));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root != null && Files.exists(root)) {
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }
}
