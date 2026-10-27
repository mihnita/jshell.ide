package com.github.mnita.jshellide.theme;

import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter.TokenType;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parser and style resolver for TextMate {@code .tmTheme} (Apple Property List XML) files.
 */
public class TextMateTheme {

    public static final String DEFAULT_BUNDLED_THEME_RESOURCE = "/themes/default-light.tmTheme";

    /** Style attributes resolved for a syntax token or output element. */
    public static final class TokenStyle {
        private final RGB foreground;
        private final RGB background;
        private final int fontStyle;

        public TokenStyle(RGB foreground, RGB background, int fontStyle) {
            this.foreground = foreground;
            this.background = background;
            this.fontStyle = fontStyle;
        }

        public RGB getForeground() {
            return foreground;
        }

        public RGB getBackground() {
            return background;
        }

        public int getFontStyle() {
            return fontStyle;
        }
    }

    /** Single scope rule from a {@code .tmTheme} settings array. */
    public static final class ScopeRule {
        private final String name;
        private final List<String> scopes;
        private final TokenStyle style;

        public ScopeRule(String name, List<String> scopes, TokenStyle style) {
            this.name = name != null ? name : "";
            this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
            this.style = style;
        }

        public String getName() {
            return name;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public TokenStyle getStyle() {
            return style;
        }
    }

    private final String name;
    private final RGB background;
    private final RGB foreground;
    private final RGB selection;
    private final RGB caret;
    private final List<ScopeRule> rules;

    public TextMateTheme(
            String name,
            RGB background,
            RGB foreground,
            RGB selection,
            RGB caret,
            List<ScopeRule> rules) {
        this.name = name != null && !name.isBlank() ? name : "Unnamed Theme";
        this.background = background != null ? background : new RGB(255, 255, 255);
        this.foreground = foreground != null ? foreground : new RGB(31, 31, 31);
        this.selection = selection != null ? selection : new RGB(180, 213, 254);
        this.caret = caret != null ? caret : this.foreground;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /** Loads the bundled default light {@code .tmTheme} resource. */
    public static TextMateTheme loadBundledDefault() {
        return loadFromResource(DEFAULT_BUNDLED_THEME_RESOURCE);
    }

    /** Loads a {@code .tmTheme} from a classpath resource path. */
    public static TextMateTheme loadFromResource(String resourcePath) {
        try (InputStream in = TextMateTheme.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Theme resource not found: " + resourcePath);
            }
            return parse(in);
        } catch (Exception e) {
            return fallbackDefaultTheme();
        }
    }

    /** Loads a {@code .tmTheme} file from the given file system path. */
    public static TextMateTheme loadFromFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse .tmTheme file: " + e.getMessage(), e);
        }
    }

    /** Parses a {@code .tmTheme} XML property list from an {@link InputStream}. */
    public static TextMateTheme parse(InputStream input) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setValidating(false);
        try {
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // Some XML parsers may not support all feature flags
        }

        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document doc = db.parse(input);

        Element root = doc.getDocumentElement();
        Element topDict = findFirstChildElement(root, "dict");
        if (topDict == null) {
            throw new IllegalArgumentException("Invalid .tmTheme: missing root <dict>");
        }

        Map<String, Element> topMap = parseDictElements(topDict);
        String themeName = getTextContent(topMap.get("name"), "Custom Theme");

        RGB globalBg = new RGB(255, 255, 255);
        RGB globalFg = new RGB(31, 31, 31);
        RGB globalSelection = new RGB(180, 213, 254);
        RGB globalCaret = new RGB(0, 0, 0);
        List<ScopeRule> rules = new ArrayList<>();

        Element settingsArray = topMap.get("settings");
        if (settingsArray != null && "array".equals(settingsArray.getTagName())) {
            for (Element ruleDict : getChildElements(settingsArray, "dict")) {
                Map<String, Element> ruleEntries = parseDictElements(ruleDict);
                Element scopeElem = ruleEntries.get("scope");
                Element innerSettingsDict = ruleEntries.get("settings");
                if (innerSettingsDict == null || !"dict".equals(innerSettingsDict.getTagName())) {
                    continue;
                }
                Map<String, Element> styleEntries = parseDictElements(innerSettingsDict);

                if (scopeElem == null) {
                    // Global editor settings block
                    RGB bg = parseHexColor(getTextContent(styleEntries.get("background"), null));
                    RGB fg = parseHexColor(getTextContent(styleEntries.get("foreground"), null));
                    RGB sel = parseHexColor(getTextContent(styleEntries.get("selection"), null));
                    RGB crt = parseHexColor(getTextContent(styleEntries.get("caret"), null));
                    if (bg != null) {
                        globalBg = bg;
                    }
                    if (fg != null) {
                        globalFg = fg;
                    }
                    if (sel != null) {
                        globalSelection = sel;
                    }
                    if (crt != null) {
                        globalCaret = crt;
                    }
                } else {
                    String ruleName = getTextContent(ruleEntries.get("name"), "");
                    String rawScopes = getTextContent(scopeElem, "");
                    List<String> scopes = parseScopeList(rawScopes);
                    if (scopes.isEmpty()) {
                        continue;
                    }
                    RGB fg = parseHexColor(getTextContent(styleEntries.get("foreground"), null));
                    RGB bg = parseHexColor(getTextContent(styleEntries.get("background"), null));
                    int fontStyle = parseFontStyle(getTextContent(styleEntries.get("fontStyle"), ""));
                    rules.add(new ScopeRule(ruleName, scopes, new TokenStyle(fg, bg, fontStyle)));
                }
            }
        }

        return new TextMateTheme(themeName, globalBg, globalFg, globalSelection, globalCaret, rules);
    }

    public String getName() {
        return name;
    }

    public RGB getBackground() {
        return background;
    }

    public RGB getForeground() {
        return foreground;
    }

    public RGB getSelection() {
        return selection;
    }

    public RGB getCaret() {
        return caret;
    }

    public List<ScopeRule> getRules() {
        return rules;
    }

    /**
     * Resolves the best matching {@link TokenStyle} for a list of candidate TextMate scopes
     * in priority order.
     */
    public TokenStyle resolveScope(String... candidateScopes) {
        if (candidateScopes != null) {
            for (String candidate : candidateScopes) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                ScopeRule bestRule = null;
                int bestSpecificity = -1;
                for (ScopeRule rule : rules) {
                    for (String ruleScope : rule.getScopes()) {
                        if (scopeMatches(candidate, ruleScope)
                                && ruleScope.length() > bestSpecificity) {
                            bestRule = rule;
                            bestSpecificity = ruleScope.length();
                        }
                    }
                }
                if (bestRule != null && bestRule.getStyle().getForeground() != null) {
                    return bestRule.getStyle();
                }
            }
        }
        return new TokenStyle(foreground, null, SWT.NORMAL);
    }

    /** Returns the resolved {@link TokenStyle} for a given Java syntax {@link TokenType}. */
    public TokenStyle getStyleForToken(TokenType tokenType) {
        if (tokenType == null) {
            return new TokenStyle(foreground, null, SWT.NORMAL);
        }
        return switch (tokenType) {
            case KEYWORD -> resolveScope(
                    "keyword.control.java",
                    "keyword.control",
                    "keyword.other",
                    "keyword",
                    "storage.modifier.java",
                    "storage.modifier",
                    "storage");
            case TYPE_OR_LITERAL -> resolveScope(
                    "storage.type.primitive.java",
                    "storage.type.java",
                    "storage.type",
                    "constant.language.java",
                    "constant.language",
                    "support.type",
                    "entity.name.type");
            case STRING -> resolveScope(
                    "string.quoted.double.java",
                    "string.quoted.double",
                    "string.quoted.single",
                    "string.quoted",
                    "string");
            case NUMBER -> resolveScope(
                    "constant.numeric.java",
                    "constant.numeric",
                    "constant");
            case COMMENT -> resolveScope(
                    "comment.line.double-slash.java",
                    "comment.line",
                    "comment.block",
                    "comment");
            case ANNOTATION -> resolveScope(
                    "storage.type.annotation.java",
                    "storage.type.annotation",
                    "meta.annotation",
                    "entity.other.attribute-name",
                    "entity.name.tag",
                    "keyword.other");
            case NORMAL -> new TokenStyle(foreground, null, SWT.NORMAL);
        };
    }

    /** Returns the resolved error / squiggle RGB color from the theme. */
    public RGB getErrorColor() {
        TokenStyle errStyle = resolveScope(
                "invalid.illegal", "invalid", "markup.deleted", "message.error");
        if (errStyle.getForeground() != null && !errStyle.getForeground().equals(foreground)) {
            return errStyle.getForeground();
        }
        return new RGB(220, 20, 20);
    }

    /** Returns the resolved style for snippet headers (`[1] ...`) in the output panel. */
    public TokenStyle getOutputHeaderStyle() {
        TokenStyle s = resolveScope(
                "entity.name.function",
                "entity.name.section",
                "markup.heading",
                "support.function",
                "keyword");
        int styleBit = s.getFontStyle() | SWT.BOLD;
        return new TokenStyle(s.getForeground(), s.getBackground(), styleBit);
    }

    /** Returns the resolved style for snippet evaluation values (`==> ...`) in the output panel. */
    public TokenStyle getOutputValueStyle() {
        return resolveScope(
                "markup.inserted",
                "string.quoted",
                "string",
                "constant.numeric");
    }

    /**
     * Parses a hex color string ({@code #RGB}, {@code #RRGGBB}, or {@code #RRGGBBAA})
     * into an SWT {@link RGB}, or {@code null} if invalid.
     */
    public static RGB parseHexColor(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(s.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(s.substring(2, 3), 16) * 17;
                return new RGB(r, g, b);
            } else if (s.length() == 6 || s.length() == 8) {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int g = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                return new RGB(r, g, b);
            }
        } catch (NumberFormatException ignored) {
            // Return null below
        }
        return null;
    }

    /** Parses a TextMate {@code fontStyle} string ("bold", "italic", "bold italic") into SWT flags. */
    public static int parseFontStyle(String fontStyleStr) {
        if (fontStyleStr == null || fontStyleStr.isBlank()) {
            return SWT.NORMAL;
        }
        String lower = fontStyleStr.toLowerCase(Locale.ROOT);
        int flags = SWT.NORMAL;
        if (lower.contains("bold")) {
            flags |= SWT.BOLD;
        }
        if (lower.contains("italic")) {
            flags |= SWT.ITALIC;
        }
        return flags;
    }

    private static boolean scopeMatches(String targetScope, String ruleScope) {
        if (targetScope.equals(ruleScope)) {
            return true;
        }
        return targetScope.startsWith(ruleScope + ".");
    }

    private static List<String> parseScopeList(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : rawScope.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                // If a scope selector has descendant spaces (e.g. "source.java keyword"),
                // take the rightmost token selector for direct token matching
                String[] subTokens = trimmed.split("\\s+");
                result.add(subTokens[subTokens.length - 1]);
            }
        }
        return result;
    }

    private static Map<String, Element> parseDictElements(Element dictElem) {
        Map<String, Element> map = new LinkedHashMap<>();
        NodeList children = dictElem.getChildNodes();
        String currentKey = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element elem = (Element) node;
            if ("key".equals(elem.getTagName())) {
                currentKey = elem.getTextContent().trim();
            } else if (currentKey != null) {
                map.put(currentKey, elem);
                currentKey = null;
            }
        }
        return map;
    }

    private static Element findFirstChildElement(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(((Element) n).getTagName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> getChildElements(Element parent, String tagName) {
        List<Element> list = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(((Element) n).getTagName())) {
                list.add((Element) n);
            }
        }
        return list;
    }

    private static String getTextContent(Element elem, String defaultVal) {
        if (elem == null) {
            return defaultVal;
        }
        return elem.getTextContent();
    }

    private static TextMateTheme fallbackDefaultTheme() {
        List<ScopeRule> rules = List.of(
                new ScopeRule("Keyword", List.of("keyword"),
                        new TokenStyle(new RGB(127, 0, 85), null, SWT.BOLD)),
                new ScopeRule("Type", List.of("storage.type", "constant.language"),
                        new TokenStyle(new RGB(0, 80, 160), null, SWT.BOLD)),
                new ScopeRule("String", List.of("string"),
                        new TokenStyle(new RGB(42, 0, 255), null, SWT.NORMAL)),
                new ScopeRule("Number", List.of("constant.numeric"),
                        new TokenStyle(new RGB(180, 80, 0), null, SWT.NORMAL)),
                new ScopeRule("Comment", List.of("comment"),
                        new TokenStyle(new RGB(63, 127, 95), null, SWT.ITALIC)),
                new ScopeRule("Annotation", List.of("storage.type.annotation"),
                        new TokenStyle(new RGB(100, 100, 100), null, SWT.NORMAL)));
        return new TextMateTheme(
                "Default Light",
                new RGB(255, 255, 255),
                new RGB(31, 31, 31),
                new RGB(180, 213, 254),
                new RGB(0, 0, 0),
                rules);
    }
}
