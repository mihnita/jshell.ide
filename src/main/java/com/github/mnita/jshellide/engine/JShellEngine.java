package com.github.mnita.jshellide.engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import jdk.jshell.SourceCodeAnalysis.Completeness;
import jdk.jshell.SourceCodeAnalysis.Suggestion;
import jdk.jshell.UnresolvedReferenceException;

/**
 * Wrapper around {@link jdk.jshell.JShell} and {@link jdk.jshell.SourceCodeAnalysis}
 * supporting scratchpad-style evaluation, diagnostic offset calculation, and
 * context-aware auto-completion.
 */
public class JShellEngine implements AutoCloseable {

    /** Represents a compiler diagnostic mapped to full document offsets. */
    public static final class DiagnosticItem {
        private final int startOffset;
        private final int endOffset;
        private final int positionOffset;
        private final String message;
        private final boolean error;

        public DiagnosticItem(
                int startOffset, int endOffset, int positionOffset, String message, boolean error) {
            this.startOffset = Math.max(0, startOffset);
            this.endOffset = Math.max(this.startOffset, endOffset);
            this.positionOffset = Math.max(0, positionOffset);
            this.message = message != null ? message : "";
            this.error = error;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        public int getPositionOffset() {
            return positionOffset;
        }

        public String getMessage() {
            return message;
        }

        public boolean isError() {
            return error;
        }
    }

    /** Represents the result of evaluating a single snippet in the editor. */
    public static final class SnippetResult {
        private final String source;
        private final int startOffset;
        private final int endOffset;
        private final Snippet.Status status;
        private final String value;
        private final String stdout;
        private final String stderr;
        private final String exceptionMessage;
        private final List<DiagnosticItem> diagnostics;

        public SnippetResult(
                String source,
                int startOffset,
                int endOffset,
                Snippet.Status status,
                String value,
                String stdout,
                String stderr,
                String exceptionMessage,
                List<DiagnosticItem> diagnostics) {
            this.source = source;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.status = status;
            this.value = value;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exceptionMessage = exceptionMessage;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public String getSource() {
            return source;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        public Snippet.Status getStatus() {
            return status;
        }

        public String getValue() {
            return value;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public String getExceptionMessage() {
            return exceptionMessage;
        }

        public List<DiagnosticItem> getDiagnostics() {
            return diagnostics;
        }
    }

    /** Aggregate result of evaluating the entire editor content. */
    public static final class EvaluationResult {
        private final List<SnippetResult> snippetResults;
        private final List<DiagnosticItem> diagnostics;
        private final String formattedOutput;
        private final long durationMillis;

        public EvaluationResult(
                List<SnippetResult> snippetResults,
                List<DiagnosticItem> diagnostics,
                String formattedOutput,
                long durationMillis) {
            this.snippetResults = Collections.unmodifiableList(new ArrayList<>(snippetResults));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
            this.formattedOutput = formattedOutput;
            this.durationMillis = durationMillis;
        }

        public List<SnippetResult> getSnippetResults() {
            return snippetResults;
        }

        public List<DiagnosticItem> getDiagnostics() {
            return diagnostics;
        }

        public String getFormattedOutput() {
            return formattedOutput;
        }

        public long getDurationMillis() {
            return durationMillis;
        }
    }

    /** Represents an auto-completion result for the editor. */
    public static final class CompletionResult {
        private final int anchorOffset;
        private final List<String> suggestions;

        public CompletionResult(int anchorOffset, List<String> suggestions) {
            this.anchorOffset = anchorOffset;
            this.suggestions = Collections.unmodifiableList(new ArrayList<>(suggestions));
        }

        public int getAnchorOffset() {
            return anchorOffset;
        }

        public List<String> getSuggestions() {
            return suggestions;
        }
    }

    /** Slice of the document corresponding to one JShell snippet. */
    public static final class SnippetSlice {
        private final String source;
        private final int startOffset;
        private final int endOffset;
        private final Completeness completeness;

        public SnippetSlice(
                String source, int startOffset, int endOffset, Completeness completeness) {
            this.source = source;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.completeness = completeness;
        }

        public String getSource() {
            return source;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        public Completeness getCompleteness() {
            return completeness;
        }
    }

    private final List<String> classpathEntries = new ArrayList<>();
    private JShell completionShell;

    public JShellEngine() {
        this.completionShell = createLocalJShell(new ByteArrayOutputStream(), new ByteArrayOutputStream());
    }

    /** Returns an unmodifiable view of the configured classpath entries. */
    public synchronized List<String> getClasspathEntries() {
        return Collections.unmodifiableList(new ArrayList<>(classpathEntries));
    }

    /** Replaces the configured classpath entries. */
    public synchronized void setClasspathEntries(List<String> entries) {
        classpathEntries.clear();
        if (entries != null) {
            for (String entry : entries) {
                if (entry != null && !entry.isBlank() && !classpathEntries.contains(entry.trim())) {
                    classpathEntries.add(entry.trim());
                }
            }
        }
    }

    /** Adds a single classpath entry if not already present. */
    public synchronized void addClasspathEntry(String path) {
        if (path != null && !path.isBlank()) {
            String trimmed = path.trim();
            if (!classpathEntries.contains(trimmed)) {
                classpathEntries.add(trimmed);
            }
        }
    }

    /**
     * Splits a multi-snippet source text into individual {@link SnippetSlice} items
     * with exact character offsets in {@code fullText}.
     */
    public List<SnippetSlice> splitSnippets(String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return Collections.emptyList();
        }
        synchronized (this) {
            ensureCompletionShell();
            return splitSnippetsWithAnalysis(completionShell.sourceCodeAnalysis(), fullText);
        }
    }

    private List<SnippetSlice> splitSnippetsWithAnalysis(
            SourceCodeAnalysis analysis, String fullText) {
        List<SnippetSlice> slices = new ArrayList<>();
        int currentOffset = 0;
        int totalLength = fullText.length();

        while (currentOffset < totalLength) {
            String remaining = fullText.substring(currentOffset);
            if (remaining.isBlank()) {
                break;
            }
            CompletionInfo info = analysis.analyzeCompletion(remaining);
            String remainingAfter = info.remaining();
            int consumedLength = remaining.length() - (remainingAfter != null ? remainingAfter.length() : 0);
            if (consumedLength <= 0) {
                consumedLength = remaining.length();
            }

            String rawSlice = remaining.substring(0, consumedLength);
            int leadingTrim = countLeadingWhitespace(rawSlice);
            int trailingTrim = countTrailingWhitespace(rawSlice);

            int sliceStart = currentOffset + leadingTrim;
            int sliceEnd = currentOffset + consumedLength - trailingTrim;
            if (sliceEnd > sliceStart) {
                String evalSource = info.source();
                if (evalSource == null || evalSource.isBlank()) {
                    evalSource = fullText.substring(sliceStart, sliceEnd);
                }
                slices.add(new SnippetSlice(evalSource, sliceStart, sliceEnd, info.completeness()));
            }
            currentOffset += consumedLength;
        }
        return slices;
    }

    /**
     * Evaluates the entire {@code fullText} from a clean JShell state (scratchpad style).
     * Updates the internal warm JShell instance so subsequent code completions have access
     * to all declarations, imports, and classpaths from this run.
     */
    public synchronized EvaluationResult evaluateScratchpad(String fullText) {
        long startNanos = System.nanoTime();
        String sourceText = fullText != null ? fullText : "";

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        JShell shell = createLocalJShell(outBytes, errBytes);
        for (String cp : classpathEntries) {
            shell.addToClasspath(cp);
        }

        List<SnippetResult> snippetResults = new ArrayList<>();
        List<DiagnosticItem> allDiagnostics = new ArrayList<>();
        StringBuilder report = new StringBuilder();

        SourceCodeAnalysis analysis = shell.sourceCodeAnalysis();
        List<SnippetSlice> slices = splitSnippetsWithAnalysis(analysis, sourceText);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream captureOut = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream captureErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        try {
            System.setOut(captureOut);
            System.setErr(captureErr);

            int snippetIndex = 0;
            for (SnippetSlice slice : slices) {
                snippetIndex++;
                outBytes.reset();
                errBytes.reset();

                List<DiagnosticItem> sliceDiags = new ArrayList<>();
                Snippet.Status status = Snippet.Status.VALID;
                String value = null;
                String exceptionText = null;

                if (slice.getCompleteness() == Completeness.DEFINITELY_INCOMPLETE
                        || slice.getCompleteness() == Completeness.CONSIDERED_INCOMPLETE) {
                    // Evaluate anyway so JShell emits accurate syntax diagnostics
                    List<SnippetEvent> events = shell.eval(slice.getSource());
                    for (SnippetEvent event : events) {
                        status = event.status();
                        collectDiagnostics(shell, event.snippet(), sourceText, slice, sliceDiags);
                    }
                    if (sliceDiags.isEmpty()) {
                        sliceDiags.add(new DiagnosticItem(
                                slice.getStartOffset(),
                                slice.getEndOffset(),
                                slice.getEndOffset(),
                                "Incomplete statement or expression",
                                true));
                    }
                } else {
                    List<SnippetEvent> events = shell.eval(slice.getSource());
                    for (SnippetEvent event : events) {
                        if (event.causeSnippet() != null) {
                            continue;
                        }
                        status = event.status();
                        value = event.value();
                        collectDiagnostics(shell, event.snippet(), sourceText, slice, sliceDiags);

                        if (status == Snippet.Status.RECOVERABLE_DEFINED
                                || status == Snippet.Status.RECOVERABLE_NOT_DEFINED) {
                            List<String> unresolved =
                                    shell.unresolvedDependencies(( jdk.jshell.DeclarationSnippet ) event.snippet())
                                            .toList();
                            if (!unresolved.isEmpty()) {
                                String msg = "Unresolved reference(s): " + String.join(", ", unresolved);
                                sliceDiags.add(new DiagnosticItem(
                                        slice.getStartOffset(),
                                        slice.getEndOffset(),
                                        slice.getStartOffset(),
                                        msg,
                                        true));
                            }
                        }

                        JShellException ex = event.exception();
                        if (ex != null) {
                            exceptionText = formatJShellException(ex);
                        }
                    }
                }

                captureOut.flush();
                captureErr.flush();
                String stdout = outBytes.toString(StandardCharsets.UTF_8);
                String stderr = errBytes.toString(StandardCharsets.UTF_8);

                allDiagnostics.addAll(sliceDiags);
                SnippetResult snippetResult = new SnippetResult(
                        slice.getSource(),
                        slice.getStartOffset(),
                        slice.getEndOffset(),
                        status,
                        value,
                        stdout,
                        stderr,
                        exceptionText,
                        sliceDiags);
                snippetResults.add(snippetResult);

                appendSnippetReport(report, snippetIndex, snippetResult);
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            captureOut.close();
            captureErr.close();
        }

        // Replace warm completion shell with this newly evaluated shell
        if (this.completionShell != null) {
            try {
                this.completionShell.close();
            } catch (Exception ignored) {
                // ignore close failure
            }
        }
        this.completionShell = shell;

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        return new EvaluationResult(snippetResults, allDiagnostics, report.toString(), elapsedMillis);
    }

    /**
     * Computes auto-completion suggestions at {@code cursorOffset} within {@code fullText}.
     */
    public synchronized CompletionResult getCompletions(String fullText, int cursorOffset) {
        if (fullText == null) {
            return new CompletionResult(0, Collections.emptyList());
        }
        int safeCursor = Math.max(0, Math.min(cursorOffset, fullText.length()));
        ensureCompletionShell();
        SourceCodeAnalysis analysis = completionShell.sourceCodeAnalysis();

        // Find the snippet containing the cursor so completion anchor is accurate
        int snippetStart = findCurrentSnippetStart(analysis, fullText, safeCursor);
        String snippetPrefix = fullText.substring(snippetStart);
        int relativeCursor = safeCursor - snippetStart;

        int[] anchor = new int[] {-1};
        List<Suggestion> rawSuggestions =
                analysis.completionSuggestions(snippetPrefix, relativeCursor, anchor);

        Set<String> uniqueMatches = new LinkedHashSet<>();
        List<Suggestion> sorted = new ArrayList<>(rawSuggestions);
        sorted.sort(Comparator.comparing(Suggestion::matchesType).reversed()
                .thenComparing(Suggestion::continuation));
        for (Suggestion s : sorted) {
            String cont = s.continuation();
            if (cont != null && !cont.isEmpty()) {
                uniqueMatches.add(cont);
            }
        }

        int absoluteAnchor = (anchor[0] >= 0) ? (snippetStart + anchor[0]) : safeCursor;
        return new CompletionResult(absoluteAnchor, new ArrayList<>(uniqueMatches));
    }

    private int findCurrentSnippetStart(
            SourceCodeAnalysis analysis, String fullText, int cursorOffset) {
        int currentOffset = 0;
        while (currentOffset < cursorOffset) {
            String remaining = fullText.substring(currentOffset);
            if (remaining.isBlank()) {
                break;
            }
            CompletionInfo info = analysis.analyzeCompletion(remaining);
            String remainingAfter = info.remaining();
            int consumed = remaining.length() - (remainingAfter != null ? remainingAfter.length() : 0);
            if (consumed <= 0 || currentOffset + consumed >= cursorOffset) {
                int leading = countLeadingWhitespace(remaining);
                return Math.min(cursorOffset, currentOffset + leading);
            }
            currentOffset += consumed;
        }
        return currentOffset;
    }

    private void collectDiagnostics(
            JShell shell,
            Snippet snippet,
            String fullText,
            SnippetSlice slice,
            List<DiagnosticItem> target) {
        if (snippet == null) {
            return;
        }
        String rawSliceText = fullText.substring(slice.getStartOffset(), slice.getEndOffset());
        String evalSource = slice.getSource();
        // If analyzeCompletion appended a synthetic ';', clamp diagnostic offsets to rawSliceText length
        int maxRelativeOffset = rawSliceText.length();

        List<Diag> diags = shell.diagnostics(snippet).toList();
        for (Diag diag : diags) {
            int relStart = (int) Math.max(0, Math.min(diag.getStartPosition(), maxRelativeOffset));
            int relEnd = (int) Math.max(relStart, Math.min(diag.getEndPosition(), maxRelativeOffset));
            int relPos = (int) Math.max(0, Math.min(diag.getPosition(), maxRelativeOffset));

            int docStart = slice.getStartOffset() + relStart;
            int docEnd = slice.getStartOffset() + relEnd;
            int docPos = slice.getStartOffset() + relPos;

            // Ensure squiggle spans at least 1 character when possible so it is visible in the editor
            if (docEnd <= docStart && fullText.length() > 0) {
                if (docStart < slice.getEndOffset()) {
                    docEnd = docStart + 1;
                } else if (docStart > slice.getStartOffset()) {
                    docStart = docStart - 1;
                    docEnd = docStart + 1;
                }
            }

            String message = diag.getMessage(Locale.ENGLISH);
            if (evalSource != null && message != null) {
                message = message.trim();
            }
            target.add(new DiagnosticItem(docStart, docEnd, docPos, message, diag.isError()));
        }
    }

    private static String formatJShellException(JShellException ex) {
        if (ex instanceof EvalException evalEx) {
            StringBuilder sb = new StringBuilder();
            sb.append(evalEx.getExceptionClassName());
            if (evalEx.getMessage() != null) {
                sb.append(": ").append(evalEx.getMessage());
            }
            StackTraceElement[] trace = evalEx.getStackTrace();
            if (trace != null && trace.length > 0) {
                int limit = Math.min(trace.length, 5);
                for (int i = 0; i < limit; i++) {
                    sb.append("\n  at ").append(trace[i]);
                }
            }
            return sb.toString();
        } else if (ex instanceof UnresolvedReferenceException unresEx) {
            return "Unresolved reference in: " + unresEx.getSnippet().source();
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private static void appendSnippetReport(
            StringBuilder report, int index, SnippetResult result) {
        String compactSource = result.getSource().strip();
        String firstLine = compactSource.lines().findFirst().orElse("");
        if (compactSource.lines().count() > 1) {
            firstLine += " ...";
        }
        report.append("[").append(index).append("] ").append(firstLine).append("\n");

        if (result.getStdout() != null && !result.getStdout().isEmpty()) {
            report.append( indentLines("  out: ", result.getStdout()) );
        }
        if (result.getStderr() != null && !result.getStderr().isEmpty()) {
            report.append( indentLines("  err: ", result.getStderr()) );
        }
        if (result.getValue() != null) {
            report.append("  ==> ").append(result.getValue()).append("\n");
        }
        for (DiagnosticItem diag : result.getDiagnostics()) {
            report.append("  [ERROR] ").append(diag.getMessage()).append("\n");
        }
        if (result.getExceptionMessage() != null) {
            report.append("  [EXCEPTION] ").append(result.getExceptionMessage()).append("\n");
        }
        report.append("\n");
    }

    private static String indentLines(String prefix, String text) {
        StringBuilder sb = new StringBuilder();
        String normalized = text.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        int limit = lines.length;
        if (limit > 0 && lines[limit - 1].isEmpty()) {
            limit--;
        }
        for (int i = 0; i < limit; i++) {
            sb.append(prefix).append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private void ensureCompletionShell() {
        if (completionShell == null) {
            completionShell = createLocalJShell(
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            for (String cp : classpathEntries) {
                completionShell.addToClasspath(cp);
            }
        }
    }

    private static JShell createLocalJShell(
            ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        PrintStream outStream = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        return JShell.builder()
                .out(outStream)
                .err(errStream)
                .executionEngine("local")
                .build();
    }

    private static int countLeadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int countTrailingWhitespace(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return s.length() - i;
    }

    @Override
    public synchronized void close() {
        if (completionShell != null) {
            completionShell.close();
            completionShell = null;
        }
    }
}
