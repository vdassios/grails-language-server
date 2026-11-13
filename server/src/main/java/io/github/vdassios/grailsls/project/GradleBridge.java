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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Reads the project model by running the project's Gradle wrapper with {@code model.init.gradle}. Runs project code
 * (its build scripts), so callers must only use it for trusted workspaces. The result is cached on disk, keyed by a
 * hash of the build files, the JDK and the init script; failures are not cached.
 */
public final class GradleBridge {
  static final String TASK = "grailsLanguageServerModel";
  private static final String INIT_SCRIPT = "model.init.gradle";
  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
  private static final String WRAPPER = WINDOWS ? "gradlew.bat" : "gradlew";
  /** Directories never searched for build files: build output, caches, VCS and tool dirs. */
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
      if (cached != null && key.equals(cached.key)) {
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

  static Path findBuildRoot(Path start) throws BridgeException {
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve(WRAPPER))) {
        return dir;
      }
    }
    throw new BridgeException("no Gradle wrapper (" + WRAPPER + ") in " + start + " or a parent directory");
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

  /** Gradle scripts, properties, version catalogs, the wrapper properties and everything under {@code buildSrc/src}. */
  static List<Path> buildFiles(Path buildRoot) {
    List<Path> files = new ArrayList<>();
    Path buildSrc = buildRoot.resolve("buildSrc").resolve("src");
    try {
      Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          return !dir.equals(buildRoot) && SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          String name = file.getFileName().toString();
          if (name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.equals("gradle.properties") || name.equals("gradle-wrapper.properties")
              || name.endsWith(".versions.toml") || file.startsWith(buildSrc)) {
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

  private List<ModuleRecord> runGradle(Path buildRoot, Path dir) throws BridgeException, InterruptedException {
    Path out = dir.resolve("out");
    Path init = dir.resolve(INIT_SCRIPT);
    Path log = dir.resolve("gradle.log");
    try {
      deleteTree(out);
      Files.createDirectories(out);
      Files.write(init, initScript());
    } catch (IOException e) {
      throw new BridgeException("cannot write to the cache directory " + dir + ": " + e.getMessage(), e);
    }
    List<String> command = new ArrayList<>();
    if (WINDOWS) {
      command.addAll(List.of("cmd", "/c"));
    }
    command.addAll(List.of(buildRoot.resolve(WRAPPER).toString(), "--init-script", init.toString(), "-Pgls.out=" + out, "--console=plain", TASK));
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
      throw new BridgeException("Gradle failed: " + failureReason(log) + " (log: " + log + ")");
    }
    List<ModuleRecord> modules = readRecords(out);
    if (modules.isEmpty()) {
      throw new BridgeException("Gradle wrote no project model (log: " + log + ")");
    }
    return modules;
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

  private CachedModel readCache(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try (Reader reader = Files.newBufferedReader(file)) {
      return gson.fromJson(reader, CachedModel.class);
    } catch (IOException | JsonParseException e) {
      return null; // unreadable cache: run Gradle again
    }
  }

  private static void writeAtomically(Path file, String text) throws BridgeException {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = Files.createTempFile(file.getParent(), "model", ".tmp");
      Files.writeString(tmp, text);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new BridgeException("cannot write the model cache " + file + ": " + e.getMessage(), e);
    }
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(p);
      }
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

  private static final class CachedModel {
    String key;
    List<ModuleRecord> modules;
  }
}
