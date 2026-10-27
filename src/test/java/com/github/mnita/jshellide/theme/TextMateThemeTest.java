package com.github.mnita.jshellide.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter.TokenType;
import com.github.mnita.jshellide.theme.TextMateTheme.TokenStyle;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.junit.Test;

public class TextMateThemeTest {

    @Test
    public void testLoadBundledDefaultAndMonokaiThemes() {
        TextMateTheme light = TextMateTheme.loadBundledDefault();
        assertNotNull(light);
        assertEquals("Eclipse Light (Default)", light.getName());
        assertEquals(new RGB(255, 255, 255), light.getBackground());
        assertEquals(new RGB(127, 0, 85), light.getStyleForToken(TokenType.KEYWORD).getForeground());
        assertEquals(SWT.BOLD, light.getStyleForToken(TokenType.KEYWORD).getFontStyle());
        assertEquals(SWT.ITALIC, light.getStyleForToken(TokenType.COMMENT).getFontStyle());

        TextMateTheme dark = TextMateTheme.loadFromResource("/themes/monokai-dark.tmTheme");
        assertNotNull(dark);
        assertEquals("Monokai Dark", dark.getName());
        assertEquals(new RGB(39, 40, 34), dark.getBackground());
        assertEquals(new RGB(249, 38, 114), dark.getStyleForToken(TokenType.KEYWORD).getForeground());
        assertEquals(new RGB(230, 219, 116), dark.getStyleForToken(TokenType.STRING).getForeground());
    }

    @Test
    public void testParseCustomTmThemeAndScopeSpecificity() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>name</key>
                    <string>Test Custom Theme</string>
                    <key>settings</key>
                    <array>
                        <dict>
                            <key>settings</key>
                            <dict>
                                <key>background</key>
                                <string>#102030</string>
                                <key>foreground</key>
                                <string>#E0E0E0</string>
                            </dict>
                        </dict>
                        <dict>
                            <key>name</key>
                            <string>General Keyword</string>
                            <key>scope</key>
                            <string>keyword</string>
                            <key>settings</key>
                            <dict>
                                <key>foreground</key>
                                <string>#112233</string>
                            </dict>
                        </dict>
                        <dict>
                            <key>name</key>
                            <string>Control Keyword</string>
                            <key>scope</key>
                            <string>keyword.control</string>
                            <key>settings</key>
                            <dict>
                                <key>foreground</key>
                                <string>#AABBCC</string>
                                <key>fontStyle</key>
                                <string>bold italic</string>
                            </dict>
                        </dict>
                    </array>
                </dict>
                </plist>
                """;
        TextMateTheme theme = TextMateTheme.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Test Custom Theme", theme.getName());
        assertEquals(new RGB(16, 32, 48), theme.getBackground());
        assertEquals(new RGB(224, 224, 224), theme.getForeground());

        // More specific rule "keyword.control" should win for KEYWORD token
        TokenStyle kwStyle = theme.getStyleForToken(TokenType.KEYWORD);
        assertEquals(new RGB(170, 187, 204), kwStyle.getForeground());
        assertTrue((kwStyle.getFontStyle() & SWT.BOLD) != 0);
        assertTrue((kwStyle.getFontStyle() & SWT.ITALIC) != 0);
    }

    @Test
    public void testParseHexColorVariants() {
        assertEquals(new RGB(255, 0, 170), TextMateTheme.parseHexColor("#F0A"));
        assertEquals(new RGB(18, 52, 86), TextMateTheme.parseHexColor("#123456"));
        assertEquals(new RGB(18, 52, 86), TextMateTheme.parseHexColor("#123456FF"));
        assertNull(TextMateTheme.parseHexColor("not-a-color"));
        assertNull(TextMateTheme.parseHexColor(null));
    }
}
