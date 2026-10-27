package com.github.mnita.jshellide.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.engine.JShellEngine.CompletionResult;
import com.github.mnita.jshellide.engine.JShellEngine.DiagnosticItem;
import com.github.mnita.jshellide.engine.JShellEngine.EvaluationResult;
import com.github.mnita.jshellide.engine.JShellEngine.SnippetResult;
import com.github.mnita.jshellide.engine.JShellEngine.SnippetSlice;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JShellEngineTest {

    private JShellEngine engine;

    @Before
    public void setUp() {
        engine = new JShellEngine();
    }

    @After
    public void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    public void testSplitSnippets() {
        String code = """
                import java.util.List;
                int x = 10;
                int y = x * 2
                """;
        List<SnippetSlice> slices = engine.splitSnippets(code);
        assertEquals(3, slices.size());
        assertTrue(slices.get(0).getSource().contains("import java.util.List;"));
        assertTrue(slices.get(1).getSource().contains("int x = 10;"));
        assertTrue(slices.get(2).getSource().contains("int y = x * 2"));
    }

    @Test
    public void testEvaluateScratchpadAndCaptureOutput() {
        String code = """
                int a = 20;
                int b = 22;
                System.out.println("Sum is " + (a + b));
                a + b
                """;
        EvaluationResult result = engine.evaluateScratchpad(code);
        assertNotNull(result);
        assertTrue(result.getDiagnostics().isEmpty());
        assertEquals(4, result.getSnippetResults().size());

        SnippetResult printSnippet = result.getSnippetResults().get(2);
        assertTrue(printSnippet.getStdout().contains("Sum is 42"));

        SnippetResult exprSnippet = result.getSnippetResults().get(3);
        assertEquals("42", exprSnippet.getValue());
        assertTrue(result.getFormattedOutput().contains("==> 42"));
    }

    @Test
    public void testScratchpadUsesCleanStateBetweenEvaluations() {
        engine.evaluateScratchpad("int counter = 100;");
        // Evaluate new scratchpad code that does not define 'counter'
        EvaluationResult secondRun = engine.evaluateScratchpad("counter + 1");
        assertFalse(
                "Expected error because 'counter' should not persist across clean scratchpad runs",
                secondRun.getDiagnostics().isEmpty());
    }

    @Test
    public void testErrorDiagnosticsOffsetsForSquiggles() {
        String code = "int valid = 1;\nint broken = \"notAnInt\";\n";
        EvaluationResult result = engine.evaluateScratchpad(code);
        List<DiagnosticItem> diags = result.getDiagnostics();
        assertFalse(diags.isEmpty());

        DiagnosticItem diag = diags.get(0);
        int SecondLineStart = code.indexOf("int broken");
        assertTrue(diag.getStartOffset() >= SecondLineStart);
        assertTrue(diag.getEndOffset() > diag.getStartOffset());
        assertTrue(diag.getEndOffset() <= code.length());
        assertFalse(diag.getMessage().isBlank());
    }

    @Test
    public void testRuntimeExceptionCapture() {
        String code = "int x = 1 / 0;";
        EvaluationResult result = engine.evaluateScratchpad(code);
        assertEquals(1, result.getSnippetResults().size());
        SnippetResult sr = result.getSnippetResults().get(0);
        assertNotNull(sr.getExceptionMessage());
        assertTrue(sr.getExceptionMessage().contains("ArithmeticException"));
    }

    @Test
    public void testCompletionsIncludeEditorDeclarations() {
        String setupCode = "String customGreeting = \"hello\";\n";
        engine.evaluateScratchpad(setupCode);

        String editorWithPrefix = setupCode + "customGreet";
        CompletionResult completions =
                engine.getCompletions(editorWithPrefix, editorWithPrefix.length());
        assertTrue(
                "Expected completion suggestions to include 'customGreeting'",
                completions.getSuggestions().stream().anyMatch(s -> s.contains("customGreeting")));
    }
}
