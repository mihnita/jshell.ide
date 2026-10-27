package com.github.mnita.jshellide.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final String DEPS_PREFIX = "//DEPS";
    private static final Pattern MIRROR_URL_PATTERN =
            Pattern.compile("<mirror>.*?<url>\\s*([^<\\s]+)\\s*</url>.*?</mirror>", Pattern.DOTALL);
    private static final Pattern DEPS_TOKEN_PATTERN = Pattern.compile("[^\\s,]+");

    /** Represents a single Maven dependency coordinate extracted from a {@code //DEPS} line. */
    public static final class DepsDirective {
        private final String coordinate;
        private final int startOffset;
        private final int endOffset;

        public DepsDirective(String coordinate, int startOffset, int endOffset) {
            this.coordinate = coordinate;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public String getCoordinate() {
            return coordinate;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }
    }

    private final File localRepoDir;
    private final List<RemoteRepository> remoteRepositories;
    private final RepositorySystem repoSystem;
    private final Map<String, List<String>> resolvedCache = new ConcurrentHashMap<>();

    public MavenDependencyResolver() {
        this(defaultLocalRepoDir(), discoverRemoteRepositories());
    }

    public MavenDependencyResolver(File localRepoDir, List<RemoteRepository> remoteRepositories) {
        this.localRepoDir = localRepoDir;
        this.remoteRepositories = new ArrayList<>(remoteRepositories);
        this.repoSystem = newRepositorySystem();
    }

    /**
     * Extracts all JBang-style {@code //DEPS} Maven coordinates declared at the beginning
     * of lines in {@code sourceText}, along with their character offsets in the document.
     */
    public static List<DepsDirective> extractDepsDirectives(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return Collections.emptyList();
        }
        List<DepsDirective> directives = new ArrayList<>();
        int length = sourceText.length();
        int lineStart = 0;

        while (lineStart < length) {
            int lineEnd = lineStart;
            while (lineEnd < length
                    && sourceText.charAt(lineEnd) != '\n'
                    && sourceText.charAt(lineEnd) != '\r') {
                lineEnd++;
            }

            String line = sourceText.substring(lineStart, lineEnd);
            if (line.startsWith(DEPS_PREFIX)
                    && (line.length() == DEPS_PREFIX.length()
                            || Character.isWhitespace(line.charAt(DEPS_PREFIX.length())))) {
                String remainder = line.substring(DEPS_PREFIX.length());
                Matcher matcher = DEPS_TOKEN_PATTERN.matcher(remainder);
                while (matcher.find()) {
                    String token = matcher.group();
                    int tokenStart = lineStart + DEPS_PREFIX.length() + matcher.start();
                    int tokenEnd = lineStart + DEPS_PREFIX.length() + matcher.end();
                    directives.add(new DepsDirective(token, tokenStart, tokenEnd));
                }
            }

            if (lineEnd < length && sourceText.charAt(lineEnd) == '\r') {
                lineEnd++;
            }
            if (lineEnd < length && sourceText.charAt(lineEnd) == '\n') {
                lineEnd++;
            }
            lineStart = lineEnd;
        }
        return directives;
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
     * Checks whether the primary artifact for the given Maven coordinate is already
     * downloaded in the local Maven repository ({@code ~/.m2/repository}) without
     * making any network requests.
     */
    public boolean isCoordinateDownloaded(String coordinate) {
        if (!isValidCoordinate(coordinate)) {
            return false;
        }
        try {
            Artifact artifact = new DefaultArtifact(coordinate.trim());
            DefaultRepositorySystemSession session = newSession();
            String relativePath = session.getLocalRepositoryManager().getPathForLocalArtifact(artifact);
            if (relativePath == null || relativePath.isBlank()) {
                return false;
            }
            File localFile = new File(localRepoDir, relativePath);
            return localFile.isFile();
        } catch (Exception e) {
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

        Set<String> resolvedPaths = new LinkedHashSet<>();
        DefaultRepositorySystemSession session = null;
        DependencyFilter classpathFilter = null;

        for (String coord : coordinates) {
            if (coord == null || coord.isBlank()) {
                continue;
            }
            String trimmed = coord.trim();
            List<String> cached = resolvedCache.get(trimmed);
            if (cached != null && allFilesExist(cached)) {
                resolvedPaths.addAll(cached);
                continue;
            }

            if (session == null) {
                session = newSession();
                classpathFilter =
                        DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME);
            }

            Artifact artifact = new DefaultArtifact(trimmed);
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRoot(new Dependency(artifact, JavaScopes.COMPILE));
            for (RemoteRepository repo : remoteRepositories) {
                collectRequest.addRepository(repo);
            }

            DependencyRequest dependencyRequest =
                    new DependencyRequest(collectRequest, classpathFilter);
            DependencyResult result = repoSystem.resolveDependencies(session, dependencyRequest);
            List<String> singleCoordPaths = new ArrayList<>();
            for (ArtifactResult artifactResult : result.getArtifactResults()) {
                if (artifactResult.getArtifact() != null
                        && artifactResult.getArtifact().getFile() != null) {
                    String absPath = artifactResult.getArtifact().getFile().getAbsolutePath();
                    singleCoordPaths.add(absPath);
                    resolvedPaths.add(absPath);
                }
            }
            if (!singleCoordPaths.isEmpty()) {
                resolvedCache.put(trimmed, Collections.unmodifiableList(singleCoordPaths));
            }
        }
        return new ArrayList<>(resolvedPaths);
    }

    private static boolean allFilesExist(List<String> paths) {
        if (paths.isEmpty()) {
            return false;
        }
        for (String path : paths) {
            if (!new File(path).isFile()) {
                return false;
            }
        }
        return true;
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
