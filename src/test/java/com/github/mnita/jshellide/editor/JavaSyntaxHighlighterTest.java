package com.github.mnita.jshellide.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter.StyledSpan;
import com.github.mnita.jshellide.editor.JavaSyntaxHighlighter.TokenType;
import com.github.mnita.jshellide.engine.JShellEngine.DiagnosticItem;
import java.util.List;
import org.junit.Test;

public class JavaSyntaxHighlighterTest {

    private final JavaSyntaxHighlighter highlighter = new JavaSyntaxHighlighter();

    @Test
    public void testTokenizeKeywordsLiteralsStringsAndComments() {
        String code = """
                // line comment
                @Override
                public int compute() {
                    String s = "hello \\"world\\"";
                    return 42;
                }
                """;
        List<StyledSpan> spans = highlighter.tokenize(code);
        assertFalse(spans.isEmpty());

        assertEquals(TokenType.COMMENT, spans.get(0).getTokenType());
        assertEquals(TokenType.ANNOTATION, spans.get(1).getTokenType());
        assertEquals(TokenType.KEYWORD, spans.get(2).getTokenType()); // public
        assertEquals(TokenType.TYPE_OR_LITERAL, spans.get(3).getTokenType()); // int
        assertEquals(TokenType.STRING, spans.get(4).getTokenType()); // "hello \"world\""
        assertEquals(TokenType.KEYWORD, spans.get(5).getTokenType()); // return
        assertEquals(TokenType.NUMBER, spans.get(6).getTokenType()); // 42
    }

    @Test
    public void testComputeMergedSpansWithErrorSquigglesIsNonOverlapping() {
        String code = "public int broken = \"bad\";";
        DiagnosticItem diag = new DiagnosticItem(7, 25, 20, "incompatible types", true);

        List<StyledSpan> merged = highlighter.computeMergedSpans(code, List.of(diag));
        assertFalse(merged.isEmpty());

        int prevEnd = 0;
        boolean sawSquiggleOnKeywordOrType = false;
        for (StyledSpan span : merged) {
            assertTrue("Spans must be sorted and non-overlapping", span.getStart() >= prevEnd);
            assertTrue(span.getLength() > 0);
            prevEnd = span.getEnd();

            if (span.getTokenType() == TokenType.TYPE_OR_LITERAL && span.hasErrorSquiggle()) {
                sawSquiggleOnKeywordOrType = true;
            }
        }
        assertTrue(
                "Expected 'int' token inside error range to preserve both type highlight and squiggle",
                sawSquiggleOnKeywordOrType);
    }

    @Test
    public void testFindDiagnosticMessageAtOffset() {
        String code = "int x = unknownVar;";
        DiagnosticItem diag = new DiagnosticItem(8, 18, 8, "cannot find symbol: variable unknownVar", true);
        List<DiagnosticItem> diags = List.of(diag);

        assertNull(highlighter.findDiagnosticMessageAt(2, code.length(), diags));
        String msg = highlighter.findDiagnosticMessageAt(10, code.length(), diags);
        assertNotNull(msg);
        assertTrue(msg.contains("unknownVar"));
    }
}
