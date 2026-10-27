package com.github.mnita.jshellide.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.engine.JShellEngine;
import com.github.mnita.jshellide.engine.JShellEngine.EvaluationResult;
import java.io.File;
import java.util.List;
import org.junit.Test;

public class MavenDependencyResolverTest {

    @Test
    public void testCoordinateValidation() {
        assertTrue(MavenDependencyResolver.isValidCoordinate("com.google.code.gson:gson:2.11.0"));
        assertTrue(MavenDependencyResolver.isValidCoordinate("junit:junit:4.13.2"));
        assertFalse(MavenDependencyResolver.isValidCoordinate(""));
        assertFalse(MavenDependencyResolver.isValidCoordinate("invalid-coordinate"));
        assertFalse(MavenDependencyResolver.isValidCoordinate("only:two"));
    }

    @Test
    public void testResolveArtifactAndUseInJShellEngine() throws Exception {
        MavenDependencyResolver resolver = new MavenDependencyResolver();
        List<String> jars = resolver.resolveCoordinate("junit:junit:4.13.2");
        assertFalse("Expected resolved JARs for junit:junit:4.13.2", jars.isEmpty());
        for (String jarPath : jars) {
            assertTrue("Resolved JAR should exist on disk: " + jarPath, new File(jarPath).isFile());
        }

        try (JShellEngine engine = new JShellEngine()) {
            engine.setClasspathEntries(jars);
            EvaluationResult eval = engine.evaluateScratchpad("""
                    import org.junit.Assert;
                    Assert.assertEquals(4, 2 + 2);
                    "JUnit loaded ok"
                    """);
            assertTrue(eval.getDiagnostics().isEmpty());
            assertEquals("\"JUnit loaded ok\"", eval.getSnippetResults().get(2).getValue());
        }
    }
}
