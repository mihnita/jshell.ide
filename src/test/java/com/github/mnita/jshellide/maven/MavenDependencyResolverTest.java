package com.github.mnita.jshellide.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.mnita.jshellide.engine.JShellEngine;
import com.github.mnita.jshellide.engine.JShellEngine.EvaluationResult;
import com.github.mnita.jshellide.maven.MavenDependencyResolver.DepsDirective;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.Test;

public class MavenDependencyResolverTest {

    @Test
    public void testExtractDepsDirectives() {
        String source = """
                //DEPS junit:junit:4.13.2
                //DEPS org.hamcrest:hamcrest-core:1.3, com.google.code.gson:gson:2.11.0
                  //DEPS ignored:because:indented
                //DEPS-not-a-directive foo:bar:1.0
                int x = 1;
                """;
        List<DepsDirective> directives = MavenDependencyResolver.extractDepsDirectives(source);
        assertEquals(3, directives.size());

        assertEquals("junit:junit:4.13.2", directives.get(0).getCoordinate());
        assertEquals(
                "junit:junit:4.13.2",
                source.substring(directives.get(0).getStartOffset(), directives.get(0).getEndOffset()));

        assertEquals("org.hamcrest:hamcrest-core:1.3", directives.get(1).getCoordinate());
        assertEquals(
                "org.hamcrest:hamcrest-core:1.3",
                source.substring(directives.get(1).getStartOffset(), directives.get(1).getEndOffset()));

        assertEquals("com.google.code.gson:gson:2.11.0", directives.get(2).getCoordinate());
        assertEquals(
                "com.google.code.gson:gson:2.11.0",
                source.substring(directives.get(2).getStartOffset(), directives.get(2).getEndOffset()));
    }

    @Test
    public void testCoordinateValidation() {
        assertTrue(MavenDependencyResolver.isValidCoordinate("com.google.code.gson:gson:2.11.0"));
        assertTrue(MavenDependencyResolver.isValidCoordinate("junit:junit:4.13.2"));
        assertFalse(MavenDependencyResolver.isValidCoordinate(""));
        assertFalse(MavenDependencyResolver.isValidCoordinate("invalid-coordinate"));
        assertFalse(MavenDependencyResolver.isValidCoordinate("only:two"));
    }

    @Test
    public void testIsCoordinateDownloaded() {
        MavenDependencyResolver resolver = new MavenDependencyResolver();
        assertTrue(resolver.isCoordinateDownloaded("junit:junit:4.13.2"));
        assertFalse(resolver.isCoordinateDownloaded("com.example.nonexistent:missing-artifact:9.9.9"));
        assertFalse(resolver.isCoordinateDownloaded("invalid"));
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

    @Test
    public void testDownloadArtifactIntoCleanLocalRepository() throws Exception {
        Path tempLocalRepo = Files.createTempDirectory("jshell-ide-m2-test");
        try {
            File sourceRepoDir = new MavenDependencyResolver().getLocalRepoDir();
            RemoteRepository fileRemote = new RemoteRepository.Builder(
                    "local-mirror", "default", sourceRepoDir.toURI().toString()).build();
            MavenDependencyResolver cleanResolver =
                    new MavenDependencyResolver(tempLocalRepo.toFile(), List.of(fileRemote));

            assertFalse(cleanResolver.isCoordinateDownloaded("org.hamcrest:hamcrest-core:1.3"));
            List<String> downloadedJars =
                    cleanResolver.resolveCoordinate("org.hamcrest:hamcrest-core:1.3");
            assertFalse(downloadedJars.isEmpty());
            assertTrue(cleanResolver.isCoordinateDownloaded("org.hamcrest:hamcrest-core:1.3"));
            for (String jar : downloadedJars) {
                assertTrue(jar.startsWith(tempLocalRepo.toAbsolutePath().toString()));
                assertTrue(new File(jar).isFile());
            }
        } finally {
            Files.walk(tempLocalRepo)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> p.toFile().delete());
        }
    }
}
