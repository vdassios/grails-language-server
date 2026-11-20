package io.github.vdassios.grailsls.project;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Reads the project model by running the project's Gradle wrapper with {@code model.init.gradle}. Runs project code
 * (its build scripts), so callers must only use it for trusted workspaces. The result is cached on disk, keyed by a
 * hash of the build files, the JDK and the init script, and used only while every jar it names exists; failures are
 * not cached.
 */
public final class GradleBridge {
  static final String TASK = "grailsLanguageServerModel";
  static final String SOURCES_TASK = "grailsLanguageServerSources";
  private static final String INIT_SCRIPT = "model.init.gradle";
  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
  private static final String WRAPPER = WINDOWS ? "gradlew.bat" : "gradlew";
  /** Directories not searched for build files (build output, caches, VCS and tool dirs), unless one holds a build script: a module may be named so. */
  private static final Set<String> SKIP_DIRS = Set.of("build", "out", "bin", "target", ".gradle", ".git", ".idea", "node_modules");

  private final Gson gson = new Gson();
  private final Path cacheDir;
  private final Path javaHome;
  private final Duration timeout;

  /**
   * @param cacheDir where models, logs and the init script are kept, one subdirectory per build
   * @param javaHome JDK that runs Gradle ({@code JAVA_HOME} of the child process)
   * @param timeout  how long one Gradle run may take before it is killed
   */
  public GradleBridge(Path cacheDir, Path javaHome, Duration timeout) {
    this.cacheDir = cacheDir;
    this.javaHome = realPath(javaHome);
    this.timeout = timeout;
  }

  /** {@code $XDG_CACHE_HOME/grails-language-server}, else {@code ~/.cache/grails-language-server}. */
  public static Path defaultCacheDir() {
    String xdg = System.getenv("XDG_CACHE_HOME");
    Path base = xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
    return base.resolve("grails-language-server");
  }

  /** The JDK this server runs on: the project's JDK. */
  public static Path defaultJavaHome() {
    return Path.of(System.getProperty("java.home"));
  }

  /**
   * Returns the project model of the Gradle build that contains {@code workspaceRoot}.
   *
   * @param force ignore the cache and run Gradle
   * @throws BridgeException      no wrapper, Gradle failed or timed out, or wrote no model
   * @throws InterruptedException the thread was interrupted; the Gradle process has been killed
   */
  public ProjectModel resolve(Path workspaceRoot, boolean force) throws BridgeException, InterruptedException {
    Path buildRoot = findBuildRoot(realPath(workspaceRoot));
    Path dir = cacheDir.resolve(sha256(buildRoot.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16));
    Path cacheFile = dir.resolve("model.json");
    String key = cacheKey(buildRoot);
    if (!force) {
      CachedModel cached = readCache(cacheFile);
      if (cached != null && key.equals(cached.key) && jarsExist(cached.modules)) {
        return new ProjectModel(buildRoot, cached.modules, true);
      }
    }
    List<ModuleRecord> modules = runGradle(buildRoot, dir);
    CachedModel fresh = new CachedModel();
    fresh.key = key;
    fresh.modules = modules;
    writeAtomically(cacheFile, gson.toJson(fresh));
    return new ProjectModel(buildRoot, modules, false);
  }

  /**
   * The workspace folder itself, if it holds the wrapper. A wrapper is never taken from a parent directory: trusting
   * {@code proj/app} must not run {@code proj/gradlew}. A parent's wrapper is named in the message as the folder to open.
   */
  static Path findBuildRoot(Path workspaceRoot) throws BridgeException {
    if (Files.isRegularFile(workspaceRoot.resolve(WRAPPER))) {
      return workspaceRoot;
    }
    for (Path dir = workspaceRoot.getParent(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve(WRAPPER))) {
        throw new BridgeException("no Gradle wrapper (" + WRAPPER + ") in " + workspaceRoot + "; it belongs to the build in " + dir
            + ": open " + dir + " as the workspace");
      }
    }
    throw new BridgeException("no Gradle wrapper (" + WRAPPER + ") in " + workspaceRoot);
  }

  /** Hash of everything that can change the model: build files, the JDK and the init script. */
  String cacheKey(Path buildRoot) {
    MessageDigest digest = newDigest();
    digest.update(("java=" + javaHome + "\0").getBytes(StandardCharsets.UTF_8));
    digest.update(initScript());
    for (Path file : buildFiles(buildRoot)) {
      digest.update((buildRoot.relativize(file).toString() + "\0").getBytes(StandardCharsets.UTF_8));
      try {
        digest.update(Files.readAllBytes(file));
      } catch (IOException e) {
        digest.update("unreadable".getBytes(StandardCharsets.UTF_8));
      }
    }
    return hex(digest.digest());
  }

  /**
   * Gradle scripts, properties, version catalogs, dependency lock files ({@code gradle.lockfile}, the
   * {@code gradle/dependency-locks/} of Gradle 5 and 6: all named {@code *.lockfile}), the wrapper properties and
   * everything under {@code buildSrc/src}.
   */
  static List<Path> buildFiles(Path buildRoot) {
    List<Path> files = new ArrayList<>();
    Path buildSrc = buildRoot.resolve("buildSrc").resolve("src");
    try {
      Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          boolean skip = !dir.equals(buildRoot) && SKIP_DIRS.contains(dir.getFileName().toString())
              && !Files.isRegularFile(dir.resolve("build.gradle")) && !Files.isRegularFile(dir.resolve("build.gradle.kts"));
          return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          String name = file.getFileName().toString();
          if (name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.equals("gradle.properties") || name.equals("gradle-wrapper.properties")
              || name.endsWith(".versions.toml") || name.endsWith(".lockfile") || file.startsWith(buildSrc)) {
            files.add(file);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    files.sort(Comparator.naturalOrder());
    return files;
  }

  /**
   * Downloads the sources jars of the libraries on the build's compile classpaths that have none on disk yet, into
   * Gradle's own cache (where the server already looks for them), by running the wrapper with the init script's
   * second task. Nothing in the project changes and the model cache is left alone.
   *
   * @throws BridgeException      no wrapper, Gradle failed or timed out
   * @throws InterruptedException the thread was interrupted; the Gradle process has been killed
   */
  public SourcesReport downloadSources(Path workspaceRoot) throws BridgeException, InterruptedException {
    Path buildRoot = findBuildRoot(realPath(workspaceRoot));
    Path dir = cacheDir.resolve(sha256(buildRoot.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16));
    Path out = run(buildRoot, dir, "sources", SOURCES_TASK);
    try {
      return sourcesReport(out);
    } finally {
      deleteQuietly(out);
    }
  }

  private SourcesReport sourcesReport(Path out) throws BridgeException {
    Set<String> available = new TreeSet<>();
    Set<String> unavailable = new TreeSet<>();
    Set<String> failed = new TreeSet<>();
    String failure = null;
    try (Stream<Path> files = Files.list(out)) {
      for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
        try (Reader reader = Files.newBufferedReader(file)) {
          SourcesRecord record = gson.fromJson(reader, SourcesRecord.class);
          available.addAll(record.available);
          unavailable.addAll(record.unavailable);
          failed.addAll(record.failed);
          failure = failure != null ? failure : record.failure;
        }
      }
    } catch (IOException | JsonParseException | NullPointerException e) {
      throw new BridgeException("cannot read the sources report: " + e.getMessage(), e);
    }
    // modules share libraries, and one module may fetch what another could not
    unavailable.removeAll(available);
    failed.removeAll(available);
    return new SourcesReport(available.size(), unavailable.size(), failed.size(), failed.isEmpty() ? null : failure);
  }

  private List<ModuleRecord> runGradle(Path buildRoot, Path dir) throws BridgeException, InterruptedException {
    Path out = run(buildRoot, dir, "out", TASK);
    List<ModuleRecord> modules;
    try {
      modules = readRecords(out);
    } finally {
      deleteQuietly(out);
    }
    if (modules.isEmpty()) {
      throw new BridgeException("Gradle wrote no project model (log: " + dir.resolve("gradle.log") + ")");
    }
    return modules;
  }

  /**
   * Runs one task of the init script in every project; returns the new directory it wrote its records to, which the
   * caller deletes. Each run has its own, so two servers on one build never read or delete each other's records.
   */
  private Path run(Path buildRoot, Path dir, String outName, String task) throws BridgeException, InterruptedException {
    Path init = dir.resolve(INIT_SCRIPT);
    Path log = dir.resolve(outName.equals("out") ? "gradle.log" : outName + ".log");
    Path out;
    try {
      Files.createDirectories(dir);
      out = Files.createTempDirectory(dir, outName + "-");
      writeAtomically(init, initScript());
    } catch (IOException e) {
      throw new BridgeException("cannot write to the cache directory " + dir + ": " + e.getMessage(), e);
    }
    try {
      runIn(buildRoot, init, out, log, task);
    } catch (BridgeException | InterruptedException | RuntimeException e) {
      deleteQuietly(out);
      throw e;
    }
    return out;
  }

  private void runIn(Path buildRoot, Path init, Path out, Path log, String task) throws BridgeException, InterruptedException {
    List<String> command = new ArrayList<>();
    if (WINDOWS) {
      command.addAll(List.of("cmd", "/c"));
    }
    command.addAll(List.of(buildRoot.resolve(WRAPPER).toString(), "--init-script", init.toString(), "-Pgls.out=" + out, "--console=plain", task));
    Path wrapper = buildRoot.resolve(WRAPPER);
    if (!WINDOWS && !Files.isExecutable(wrapper)) {
      throw new BridgeException("cannot start " + WRAPPER + ": " + wrapper + " is not executable (chmod +x " + WRAPPER + ")");
    }
    ProcessBuilder builder = new ProcessBuilder(command).directory(buildRoot.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().put("JAVA_HOME", javaHome.toString());
    Process process;
    try {
      process = builder.start();
      process.getOutputStream().close();
    } catch (IOException e) {
      throw new BridgeException("cannot start " + WRAPPER + ": " + e.getMessage(), e);
    }
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        kill(process);
        throw new BridgeException("Gradle did not finish within " + timeout.toSeconds() + " s (log: " + log + ")");
      }
    } catch (InterruptedException e) {
      kill(process);
      throw e;
    }
    if (process.exitValue() != 0) {
      throw new BridgeException(failure(buildRoot, log));
    }
  }

  /** Output of a Gradle or a build that does not run on the JDK it was given (measured: too new, then too old). */
  private static final List<String> JDK_MISMATCH = List.of("Unsupported class file major version", "requires at least JVM runtime version");

  /**
   * Gradle's reason, with the wrapper's Gradle version and the JDK it ran on, and a pointer to the JDK when the log
   * shows a mismatch: the usual cause of a build that works on the command line but not in the server.
   */
  private String failure(Path buildRoot, Path log) {
    String gradle = wrapperVersion(buildRoot);
    String jdk = jdkVersion(javaHome);
    String message = "Gradle" + (gradle == null ? "" : " " + gradle) + " failed on JDK " + jdk + ": " + failureReason(log) + " (log: " + log + ")";
    String output;
    try {
      output = Files.readString(log);
    } catch (IOException | UncheckedIOException e) {
      output = "";
    }
    for (String signature : JDK_MISMATCH) {
      if (output.contains(signature)) {
        return message + ". The build does not run on JDK " + jdk + ": start the server on the JDK the project builds with (see docs/editors.md)";
      }
    }
    return message;
  }

  /** The Gradle version in {@code gradle/wrapper/gradle-wrapper.properties}' {@code distributionUrl}, or null. */
  static String wrapperVersion(Path buildRoot) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(buildRoot.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
      properties.load(reader);
    } catch (IOException e) {
      return null;
    }
    Matcher version = Pattern.compile("gradle-([^/]+?)-(?:bin|all)\\.zip$").matcher(properties.getProperty("distributionUrl", ""));
    return version.find() ? version.group(1) : null;
  }

  /** {@code JAVA_VERSION} from the JDK's {@code release} file, else its path. */
  static String jdkVersion(Path javaHome) {
    Properties release = new Properties();
    try (Reader reader = Files.newBufferedReader(javaHome.resolve("release"))) {
      release.load(reader);
    } catch (IOException e) {
      return javaHome.toString();
    }
    String version = release.getProperty("JAVA_VERSION");
    return version == null ? javaHome.toString() : version.replace("\"", "");
  }

  private List<ModuleRecord> readRecords(Path out) throws BridgeException {
    List<ModuleRecord> modules = new ArrayList<>();
    try (Stream<Path> files = Files.list(out)) {
      for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
        try (Reader reader = Files.newBufferedReader(file)) {
          modules.add(gson.fromJson(reader, ModuleRecord.class));
        }
      }
    } catch (IOException | JsonParseException e) {
      throw new BridgeException("cannot read the project model: " + e.getMessage(), e);
    }
    modules.sort(Comparator.comparing(m -> m.path));
    return modules;
  }

  /** The text under Gradle's "What went wrong:" heading, else the last line of the log. */
  static String failureReason(Path log) {
    List<String> lines;
    try {
      lines = Files.readAllLines(log);
    } catch (IOException e) {
      return "no log";
    }
    int heading = lines.indexOf("* What went wrong:");
    if (heading >= 0) {
      StringBuilder reason = new StringBuilder();
      for (int i = heading + 1; i < lines.size() && !lines.get(i).isBlank(); i++) {
        reason.append(reason.length() == 0 ? "" : " ").append(lines.get(i).trim());
      }
      return reason.toString();
    }
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (!lines.get(i).isBlank()) {
        return lines.get(i).trim();
      }
    }
    return "no output";
  }

  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /** The cached model, or null when there is none or it does not read as one: then Gradle runs again. */
  private CachedModel readCache(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    CachedModel cached;
    try (Reader reader = Files.newBufferedReader(file)) {
      cached = gson.fromJson(reader, CachedModel.class);
    } catch (IOException | JsonParseException e) {
      return null;
    }
    return cached != null && cached.key != null && complete(cached.modules) ? cached : null;
  }

  /** Whether every value the server reads from the records is there (a JSON {@code null} replaces a field's default). */
  private static boolean complete(List<ModuleRecord> modules) {
    if (modules == null || modules.isEmpty()) {
      return false;
    }
    for (ModuleRecord module : modules) {
      if (module == null || module.path == null || module.projectDir == null || module.dependsOn == null || module.errors == null || module.sourceSets == null) {
        return false;
      }
      for (ModuleRecord.SourceSetRecord sourceSet : module.sourceSets) {
        if (sourceSet == null || sourceSet.name == null || sourceSet.sourceRoots == null || sourceSet.resourceRoots == null || sourceSet.compileClasspath == null
            || sourceSet.classesDirs == null || sourceSet.outputsOf == null || sourceSet.runtimeOnly == null) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Whether every jar the model names, outside the modules' build directories, is on disk: a cleaned or pruned Gradle
   * cache leaves the model pointing at jars that are gone, and resolving again downloads them.
   */
  private static boolean jarsExist(List<ModuleRecord> modules) {
    List<Path> buildDirs = new ArrayList<>();
    modules.forEach(module -> buildDirs.add(module.buildDir()));
    for (ModuleRecord module : modules) {
      for (ModuleRecord.SourceSetRecord sourceSet : module.sourceSets) {
        for (List<String> entries : List.of(sourceSet.compileClasspath, sourceSet.runtimeOnly)) {
          for (String entry : entries) {
            Path path = Path.of(entry);
            if (entry.endsWith(".jar") && buildDirs.stream().noneMatch(path::startsWith) && !Files.isRegularFile(path)) {
              return false;
            }
          }
        }
      }
    }
    return true;
  }

  private static void writeAtomically(Path file, String text) throws BridgeException {
    try {
      writeAtomically(file, text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new BridgeException("cannot write the model cache " + file + ": " + e.getMessage(), e);
    }
  }

  /** Written next to the file and moved over it, so that a reader (another server's Gradle) sees the old or the new bytes. */
  private static void writeAtomically(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
    try {
      Files.write(tmp, bytes);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static void deleteQuietly(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(p);
      }
    } catch (IOException | UncheckedIOException e) {
      // left behind
    }
  }

  private static byte[] initScript() {
    try (InputStream in = GradleBridge.class.getResourceAsStream(INIT_SCRIPT)) {
      return in.readAllBytes();
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("missing resource " + INIT_SCRIPT, e);
    }
  }

  /** Symlinks resolved, so one JDK or build reached through two paths (e.g. SDKMAN's {@code current}) is one key. */
  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(byte[] bytes) {
    return hex(newDigest().digest(bytes));
  }

  private static String hex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }

  private static final class SourcesRecord {
    List<String> available = List.of();
    List<String> unavailable = List.of();
    List<String> failed = List.of();
    String failure;
  }

  private static final class CachedModel {
    String key;
    List<ModuleRecord> modules;
  }
}
