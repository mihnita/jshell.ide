package com.github.mnita.jshellide.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/**
 * Resolves and downloads Maven artifacts and their transitive dependencies
 * using the embedded Eclipse Maven Resolver (Aether).
 */
public class MavenDependencyResolver {

    private static final String DEFAULT_CENTRAL_URL = "https://repo.maven.apache.org/maven2/";
    private static final Pattern MIRROR_URL_PATTERN =
            Pattern.compile("<mirror>.*?<url>\\s*([^<\\s]+)\\s*</url>.*?</mirror>", Pattern.DOTALL);

    private final File localRepoDir;
    private final List<RemoteRepository> remoteRepositories;
    private final RepositorySystem repoSystem;

    public MavenDependencyResolver() {
        this(defaultLocalRepoDir(), discoverRemoteRepositories());
    }

    public MavenDependencyResolver(File localRepoDir, List<RemoteRepository> remoteRepositories) {
        this.localRepoDir = localRepoDir;
        this.remoteRepositories = new ArrayList<>(remoteRepositories);
        this.repoSystem = newRepositorySystem();
    }

    /**
     * Validates whether a Maven coordinate string has a valid format
     * ({@code groupId:artifactId:version} or {@code groupId:artifactId:ext:version}
     * or {@code groupId:artifactId:ext:classifier:version}).
     */
    public static boolean isValidCoordinate(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) {
            return false;
        }
        String trimmed = coordinate.trim();
        String[] parts = trimmed.split(":");
        if (parts.length < 3 || parts.length > 5) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return false;
            }
        }
        try {
            new DefaultArtifact(trimmed);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Resolves a single Maven coordinate (and its transitive runtime dependencies)
     * and returns the absolute file paths of all resolved JARs.
     */
    public List<String> resolveCoordinate(String coordinate) throws DependencyResolutionException {
        return resolveCoordinates(Collections.singletonList(coordinate));
    }

    /**
     * Resolves multiple Maven coordinates (and their transitive runtime dependencies)
     * and returns a deduplicated list of absolute JAR paths.
     */
    public List<String> resolveCoordinates(List<String> coordinates)
            throws DependencyResolutionException {
        if (coordinates == null || coordinates.isEmpty()) {
            return Collections.emptyList();
        }

        DefaultRepositorySystemSession session = newSession();
        DependencyFilter classpathFilter =
                DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME);

        Set<String> resolvedPaths = new LinkedHashSet<>();
        for (String coord : coordinates) {
            if (coord == null || coord.isBlank()) {
                continue;
            }
            Artifact artifact = new DefaultArtifact(coord.trim());
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRoot(new Dependency(artifact, JavaScopes.COMPILE));
            for (RemoteRepository repo : remoteRepositories) {
                collectRequest.addRepository(repo);
            }

            DependencyRequest dependencyRequest =
                    new DependencyRequest(collectRequest, classpathFilter);
            DependencyResult result = repoSystem.resolveDependencies(session, dependencyRequest);
            for (ArtifactResult artifactResult : result.getArtifactResults()) {
                if (artifactResult.getArtifact() != null
                        && artifactResult.getArtifact().getFile() != null) {
                    resolvedPaths.add(artifactResult.getArtifact().getFile().getAbsolutePath());
                }
            }
        }
        return new ArrayList<>(resolvedPaths);
    }

    public File getLocalRepoDir() {
        return localRepoDir;
    }

    public List<RemoteRepository> getRemoteRepositories() {
        return Collections.unmodifiableList(remoteRepositories);
    }

    private DefaultRepositorySystemSession newSession() {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(localRepoDir);
        session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));
        return session;
    }

    @SuppressWarnings("deprecation")
    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        RepositorySystem system = locator.getService(RepositorySystem.class);
        if (system == null) {
            throw new IllegalStateException("Failed to initialize Eclipse Maven RepositorySystem");
        }
        return system;
    }

    private static File defaultLocalRepoDir() {
        String userHome = System.getProperty("user.home", ".");
        return new File(userHome, ".m2/repository");
    }

    private static List<RemoteRepository> discoverRemoteRepositories() {
        List<RemoteRepository> repos = new ArrayList<>();
        List<Path> settingsFiles = List.of(
                Path.of(System.getProperty("user.home", "."), ".m2", "settings.xml"),
                Path.of("/etc/maven/airlock-settings.xml"));

        int idx = 1;
        for (Path settingsPath : settingsFiles) {
            if (Files.isRegularFile(settingsPath)) {
                try {
                    String xml = Files.readString(settingsPath);
                    Matcher matcher = MIRROR_URL_PATTERN.matcher(xml);
                    while (matcher.find()) {
                        String url = matcher.group(1).trim();
                        if (!url.isEmpty()) {
                            repos.add(new RemoteRepository.Builder(
                                    "mirror-" + idx++, "default", url).build());
                        }
                    }
                } catch (Exception ignored) {
                    // Fall back to default central repo
                }
            }
        }

        repos.add(new RemoteRepository.Builder("central", "default", DEFAULT_CENTRAL_URL).build());
        return repos;
    }
}
