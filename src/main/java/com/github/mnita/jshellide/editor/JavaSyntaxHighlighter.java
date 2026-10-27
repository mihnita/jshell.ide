package com.github.mnita.jshellide.editor;

import com.github.mnita.jshellide.engine.JShellEngine.DiagnosticItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lexical scanner for Java / JShell source code that produces syntax tokens
 * and merges them with compiler diagnostic squiggles into non-overlapping spans.
 */
public class JavaSyntaxHighlighter {

    /** Token types recognized by the Java syntax highlighter. */
    public enum TokenType {
        NORMAL,
        KEYWORD,
        TYPE_OR_LITERAL,
        STRING,
        NUMBER,
        COMMENT,
        ANNOTATION
    }

    /** A contiguous span of characters with a syntax token type and optional error squiggle. */
    public static final class StyledSpan {
        private final int start;
        private final int length;
        private final TokenType tokenType;
        private final boolean errorSquiggle;
        private final String errorMessage;

        public StyledSpan(
                int start,
                int length,
                TokenType tokenType,
                boolean errorSquiggle,
                String errorMessage) {
            this.start = start;
            this.length = length;
            this.tokenType = tokenType;
            this.errorSquiggle = errorSquiggle;
            this.errorMessage = errorMessage;
        }

        public int getStart() {
            return start;
        }

        public int getLength() {
            return length;
        }

        public int getEnd() {
            return start + length;
        }

        public TokenType getTokenType() {
            return tokenType;
        }

        public boolean hasErrorSquiggle() {
            return errorSquiggle;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue",
            "default", "do", "else", "enum", "exports", "extends", "final", "finally",
            "for", "goto", "if", "implements", "import", "instanceof", "interface", "module",
            "native", "new", "non-sealed", "open", "opens", "package", "permits", "private",
            "protected", "provides", "public", "record", "requires", "return", "sealed",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "to", "transient", "transitive", "try", "uses", "var", "volatile", "while",
            "with", "yield");

    private static final Set<String> JAVA_TYPES_AND_LITERALS = Set.of(
            "boolean", "byte", "char", "double", "false", "float", "int", "long",
            "null", "short", "true", "void");

    /**
     * Scans the given Java source code into non-overlapping {@link StyledSpan} tokens.
     */
    public List<StyledSpan> tokenize(String source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }

        List<StyledSpan> tokens = new ArrayList<>();
        int len = source.length();
        int i = 0;

        while (i < len) {
            char c = source.charAt(i);

            // Single-line comment
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
                int start = i;
                i += 2;
                while (i < len && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    i++;
                }
                tokens.add(new StyledSpan(start, i - start, TokenType.COMMENT, false, null));
                continue;
            }

            // Multi-line comment
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                while (i < len) {
                    if (source.charAt(i) == '*' && i + 1 < len && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                tokens.add(new StyledSpan(start, i - start, TokenType.COMMENT, false, null));
                continue;
            }

            // Text block """ ... """ or String literal "..."
            if (c == '"') {
                int start = i;
                if (i + 2 < len && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                    i += 3;
                    while (i < len) {
                        if (source.charAt(i) == '\\' && i + 1 < len) {
                            i += 2;
                            continue;
                        }
                        if (source.charAt(i) == '"'
                                && i + 2 < len
                                && source.charAt(i + 1) == '"'
                                && source.charAt(i + 2) == '"') {
                            i += 3;
                            break;
                        }
                        i++;
                    }
                } else {
                    i++;
                    while (i < len && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                        if (source.charAt(i) == '\\' && i + 1 < len) {
                            i += 2;
                            continue;
                        }
                        if (source.charAt(i) == '"') {
                            i++;
                            break;
                        }
                        i++;
                    }
                }
                tokens.add(new StyledSpan(start, i - start, TokenType.STRING, false, null));
                continue;
            }

            // Character literal '...'
            if (c == '\'') {
                int start = i;
                i++;
                while (i < len && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    if (source.charAt(i) == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (source.charAt(i) == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                tokens.add(new StyledSpan(start, i - start, TokenType.STRING, false, null));
                continue;
            }

            // Annotation @Identifier
            if (c == '@' && i + 1 < len && Character.isJavaIdentifierStart(source.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < len && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new StyledSpan(start, i - start, TokenType.ANNOTATION, false, null));
                continue;
            }

            // Numeric literal
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < len && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                i++;
                while (i < len) {
                    char nc = source.charAt(i);
                    if (Character.isLetterOrDigit(nc) || nc == '.' || nc == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new StyledSpan(start, i - start, TokenType.NUMBER, false, null));
                continue;
            }

            // Identifier / Keyword
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                i++;
                while (i < len && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                String word = source.substring(start, i);
                if (JAVA_KEYWORDS.contains(word)) {
                    tokens.add(new StyledSpan(start, i - start, TokenType.KEYWORD, false, null));
                } else if (JAVA_TYPES_AND_LITERALS.contains(word)) {
                    tokens.add(new StyledSpan(
                            start, i - start, TokenType.TYPE_OR_LITERAL, false, null));
                }
                continue;
            }

            i++;
        }

        return tokens;
    }

    /**
     * Combines syntax tokens and diagnostic squiggle ranges into sorted, non-overlapping
     * {@link StyledSpan} intervals suitable for SWT {@code StyledText}.
     */
    public List<StyledSpan> computeMergedSpans(String source, List<DiagnosticItem> diagnostics) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        int docLength = source.length();
        List<StyledSpan> syntaxSpans = tokenize(source);
        List<DiagnosticItem> safeDiags =
                diagnostics != null ? diagnostics : Collections.emptyList();

        if (safeDiags.isEmpty()) {
            return syntaxSpans;
        }

        // Collect all split boundaries within [0, docLength]
        TreeSet<Integer> boundaries = new TreeSet<>();
        for (StyledSpan s : syntaxSpans) {
            boundaries.add(clamp(s.getStart(), 0, docLength));
            boundaries.add(clamp(s.getEnd(), 0, docLength));
        }
        for (DiagnosticItem d : safeDiags) {
            int s = clamp(d.getStartOffset(), 0, docLength);
            int e = clamp(d.getEndOffset(), 0, docLength);
            if (e <= s && docLength > 0) {
                if (s < docLength) {
                    e = s + 1;
                } else {
                    s = docLength - 1;
                    e = docLength;
                }
            }
            boundaries.add(s);
            boundaries.add(e);
        }

        List<Integer> points = new ArrayList<>(boundaries);
        List<StyledSpan> result = new ArrayList<>();

        for (int idx = 0; idx < points.size() - 1; idx++) {
            int segStart = points.get(idx);
            int segEnd = points.get(idx + 1);
            if (segEnd <= segStart) {
                continue;
            }

            TokenType tokenType = TokenType.NORMAL;
            for (StyledSpan s : syntaxSpans) {
                if (s.getStart() <= segStart && s.getEnd() >= segEnd) {
                    tokenType = s.getTokenType();
                    break;
                }
            }

            boolean squiggle = false;
            String errorMsg = null;
            for (DiagnosticItem d : safeDiags) {
                int ds = clamp(d.getStartOffset(), 0, docLength);
                int de = clamp(d.getEndOffset(), 0, docLength);
                if (de <= ds && docLength > 0) {
                    if (ds < docLength) {
                        de = ds + 1;
                    } else {
                        ds = docLength - 1;
                        de = docLength;
                    }
                }
                if (ds <= segStart && de >= segEnd) {
                    squiggle = true;
                    errorMsg = d.getMessage();
                    break;
                }
            }

            if (tokenType != TokenType.NORMAL || squiggle) {
                result.add(new StyledSpan(
                        segStart, segEnd - segStart, tokenType, squiggle, errorMsg));
            }
        }

        return result;
    }

    /**
     * Finds the diagnostic error message at {@code offset} in the editor, or {@code null} if none.
     */
    public String findDiagnosticMessageAt(
            int offset, int docLength, List<DiagnosticItem> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty() || offset < 0 || offset > docLength) {
            return null;
        }
        for (DiagnosticItem d : diagnostics) {
            int ds = clamp(d.getStartOffset(), 0, docLength);
            int de = clamp(d.getEndOffset(), 0, docLength);
            if (de <= ds && docLength > 0) {
                de = Math.min(docLength, ds + 1);
            }
            if (offset >= ds && offset <= de) {
                return d.getMessage();
            }
        }
        return null;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
