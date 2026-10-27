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

    @Test
    public void testDepsDirectiveResolvesAndDoesNotMutateDialogClasspath() {
        String codeWithDeps = """
                //DEPS junit:junit:4.13.2
                import org.junit.Assert;
                Assert.assertEquals(10, 5 + 5);
                "deps-ok"
                """;
        EvaluationResult result = engine.evaluateScratchpad(codeWithDeps);
        assertTrue("Expected no diagnostics when //DEPS resolves cleanly", result.getDiagnostics().isEmpty());
        assertEquals(3, result.getSnippetResults().size());
        assertEquals("\"deps-ok\"", result.getSnippetResults().get(2).getValue());
        assertFalse(
                "Effective classpath should include resolved //DEPS JARs",
                engine.getClasspathEntries().isEmpty());
        assertTrue(
                "//DEPS must not be added to dialog classpath entries",
                engine.getDialogClasspathEntries().isEmpty());
    }

    @Test
    public void testIncrementalDepsCachingAndClasspathUpdatesOnEdit() {
        engine.setClasspathEntries(List.of("/dialog/configured.jar"));
        assertEquals(0, engine.getDepsResolutionCount());

        String firstEdit = """
                //DEPS org.hamcrest:hamcrest-core:1.3
                int a = 1;
                """;
        assertTrue(engine.updateDepsFromSource(firstEdit));
        assertEquals(1, engine.getDepsResolutionCount());
        assertTrue(engine.getClasspathEntries().contains("/dialog/configured.jar"));
        assertTrue(engine.getClasspathEntries().stream().anyMatch(p -> p.contains("hamcrest-core-1.3.jar")));

        // Editing non-DEPS code should not trigger any //DEPS work
        String secondEditSameDeps = """
                //DEPS org.hamcrest:hamcrest-core:1.3
                int a = 2;
                int b = 3;
                """;
        assertFalse(engine.updateDepsFromSource(secondEditSameDeps));
        assertEquals(1, engine.getDepsResolutionCount());

        // Adding a second //DEPS coordinate should resolve ONLY the new coordinate
        String thirdEditAddedDep = """
                //DEPS org.hamcrest:hamcrest-core:1.3
                //DEPS junit:junit:4.13.2
                int a = 2;
                """;
        assertTrue(engine.updateDepsFromSource(thirdEditAddedDep));
        assertEquals(2, engine.getDepsResolutionCount());
        assertTrue(engine.getClasspathEntries().contains("/dialog/configured.jar"));
        assertTrue(engine.getClasspathEntries().stream().anyMatch(p -> p.contains("hamcrest-core-1.3.jar")));
        assertTrue(engine.getClasspathEntries().stream().anyMatch(p -> p.contains("junit-4.13.2.jar")));

        // Removing junit from //DEPS should remove junit JAR from effective classpath without re-resolving hamcrest
        String fourthEditRemovedDep = """
                //DEPS org.hamcrest:hamcrest-core:1.3
                int a = 2;
                """;
        assertTrue(engine.updateDepsFromSource(fourthEditRemovedDep));
        assertEquals(2, engine.getDepsResolutionCount());
        assertTrue(engine.getClasspathEntries().contains("/dialog/configured.jar"));
        assertTrue(engine.getClasspathEntries().stream().anyMatch(p -> p.contains("hamcrest-core-1.3.jar")));
        assertFalse(engine.getClasspathEntries().stream().anyMatch(p -> p.contains("junit-4.13.2.jar")));
        assertEquals(List.of("/dialog/configured.jar"), engine.getDialogClasspathEntries());
    }

    @Test
    public void testInvalidDepsDirectiveEmitsDiagnosticAtTokenOffset() {
        String code = "//DEPS not-a-valid-coord\nint x = 1;\n";
        EvaluationResult result = engine.evaluateScratchpad(code);
        List<DiagnosticItem> diags = result.getDiagnostics();
        assertEquals(1, diags.size());
        DiagnosticItem diag = diags.get(0);
        assertEquals(
                "not-a-valid-coord",
                code.substring(diag.getStartOffset(), diag.getEndOffset()));
        assertTrue(diag.getMessage().contains("not-a-valid-coord"));
    }
}
