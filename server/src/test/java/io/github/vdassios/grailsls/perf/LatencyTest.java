package io.github.vdassios.grailsls.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.Analyzer;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.compile.AnalysisJar;
import io.github.vdassios.grailsls.compile.CompileContext;
import io.github.vdassios.grailsls.compile.Workspace;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.GradleBridge;
import io.github.vdassios.grailsls.project.ModuleRecord;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * Latency of compiles and queries on artefact files, against the C6 budget (first diagnostics within 3 s, completion
 * within 300 ms). Not part of {@code test}: run with {@code ./gradlew perfTest}. Measures the grails4 fixture, or the
 * built project in {@code GLS_PERF_PROJECT} (resolved with the JDK in {@code GLS_PERF_JDK}). Prints and writes counts
 * and timings only, never names: {@code server/build/perf/latency.txt}.
 *
 * <p>Per file, at the first member access {@code receiver.member} in its code: compile; first query (builds the node
 * lookup); hover; completion after the dot; signature help; and, with the member deleted (a bare {@code receiver.} as
 * just typed, which does not parse), the compile of that text and the completion, which compiles a copy with a
 * placeholder. The editor waits for both. A query that throws is counted, not timed.
 */
@Tag("perf")
@Timeout(value = 2, unit = TimeUnit.HOURS)
class LatencyTest {
  /** A member access in code: receiver, dot, member. */
  private static final Pattern ACCESS = Pattern.compile("\\b([a-z]\\w*)\\.([a-zA-Z]\\w*)");
  private static final Pattern STATIC = Pattern.compile("@(Grails)?CompileStatic\\b");
  private static final Pattern TRANSACTIONAL = Pattern.compile("(?m)^[ \\t]*@Transactional\\b");
  private static final List<String> ARTEFACT_DIRS = List.of("controllers", "services", "domain", "taglib");

  @TempDir
  Path tmp;

  private final Map<String, List<Long>> timings = new LinkedHashMap<>();
  private final Map<String, Integer> counts = new LinkedHashMap<>();

  @Test
  void latencyOfQueriesOnArtefactFiles() throws Exception {
    ProjectModel model = model();
    int limit = Integer.parseInt(System.getenv().getOrDefault("GLS_PERF_FILES", "40"));
    try (Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp))) {
      long start = System.nanoTime();
      workspace.index();
      record("index", start);
      symbolIndex(workspace);
      List<Path> files = sample(artefactFiles(model), limit);
      count("files", files.size());
      for (Path file : files) {
        CompileContext context = workspace.contextOf(file);
        if (context != null) {
          measure(workspace, context, file);
        }
        write(report()); // a run cut short still reports
      }
    }
    String report = report();
    System.out.println(report);
    write(report);
    assertFalse(timings.getOrDefault("completion", List.of()).isEmpty(), "nothing measured:\n" + report);
  }

  /**
   * Compiles every source file marked {@code @CompileStatic} or {@code @GrailsCompileStatic} (C2: those compile on to
   * instruction selection, for inferred types): time and errors on the project's own, valid code. Writes
   * {@code server/build/perf/static.txt}.
   */
  @Test
  void compileOfStaticallyCompiledFiles() throws Exception {
    ProjectModel model = model();
    try (Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp))) {
      workspace.index();
      List<Path> files = new ArrayList<>();
      for (CompileContext context : workspace.contexts()) {
        for (Path file : workspace.declarations().sources(context.ownRoots)) {
          if (workspace.contextOf(file) == context && STATIC.matcher(Files.readString(file)).find()) {
            files.add(file);
          }
        }
      }
      count("files", files.size());
      for (Path file : files) {
        CompileContext context = workspace.contextOf(file);
        CompileRequest request = new CompileRequest(file.toString(), Files.readString(file), workspace.lookup(context, p -> null), () -> false);
        long start = System.nanoTime();
        CompileResult result = context.compile(request).get(5, TimeUnit.MINUTES);
        record("compile", start);
        long errors = result.diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR && d.path.equals(file.toString())).count();
        count("errors", (int) errors);
        count("files with errors", errors > 0 ? 1 : 0);
        context.forget(file.toString());
        write(report(), "static.txt");
      }
    }
    System.out.println(report());
  }

  /**
   * Compiles every Groovy file of every source set as the open file (M2): time, errors on the project's own valid code,
   * and whether Grails's members are there, per artefact kind and per kind of module (with or without
   * {@code grails-app}). Writes counts to {@code server/build/perf/sweep.txt}; the errors themselves, which name project
   * classes, only to the file in {@code GLS_PERF_DETAILS}, if set.
   */
  @Test
  void artefactSweep() throws Exception {
    ProjectModel model = model();
    String details = System.getenv("GLS_PERF_DETAILS");
    StringBuilder errorsOut = new StringBuilder();
    try (Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp))) {
      workspace.index();
      for (CompileContext context : workspace.contexts()) {
        boolean grailsModule = context.ownRoots.stream().anyMatch(r -> r.toString().contains("/grails-app/"));
        String sourceSet = context.name.substring(context.name.lastIndexOf(' ') + 1);
        for (Path file : workspace.declarations().sources(context.ownRoots)) {
          if (workspace.contextOf(file) != context) {
            continue;
          }
          String text = Files.readString(file);
          String kind = kind(file) + (grailsModule ? "" : ", module without grails-app") + (sourceSet.equals("main") ? "" : ", " + sourceSet);
          CompileRequest request = new CompileRequest(file.toString(), text, workspace.lookup(context, p -> null), () -> false);
          long start = System.nanoTime();
          CompileResult result = context.compile(request).get(5, TimeUnit.MINUTES);
          record("compile " + kind, start);
          count("files " + kind, 1);
          List<Diagnostic> errors = result.diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR && d.path.equals(file.toString())).collect(Collectors.toList());
          count("errors " + kind, errors.size());
          count("files with errors " + kind, errors.isEmpty() ? 0 : 1);
          errors.forEach(e -> errorsOut.append(file).append(": ").append(e).append('\n'));
          List<Diagnostic> warnings = result.diagnostics.stream().filter(d -> d.severity == Diagnostic.WARNING && d.path.equals(file.toString())).collect(Collectors.toList());
          count("warnings " + kind, warnings.size());
          warnings.forEach(w -> errorsOut.append(file).append(": warning: ").append(w).append('\n'));
          List<Diagnostic> hints = result.diagnostics.stream().filter(d -> d.severity == Diagnostic.HINT && d.path.equals(file.toString())).collect(Collectors.toList());
          count("hints " + kind, hints.size());
          hints.forEach(h -> errorsOut.append(file).append(": hint: ").append(h).append('\n'));
          String trait = traitFound(context, file, text, kind(file));
          if (trait != null) {
            count(trait, 1);
          }
          context.forget(file.toString());
          write(report(), "sweep.txt");
        }
      }
      System.gc();
      count("heap MB after the sweep, after GC", (int) ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
    }
    if (details != null) {
      Files.writeString(Path.of(details), errorsOut);
    }
    write(report(), "sweep.txt");
    System.out.println(report());
  }

  private static final Pattern CONVENTION = Pattern.compile("(?<![\\w.])(view|template|controller|action)\\s*:\\s*(['\"])[^'\"$\\n]*\\2|static\\s+(?:String\\s+)?(layout)\\s*=\\s*(['\"])[^'\"$\\n]*\\4");

  /**
   * Convention references (M4): in every source file with a string literal after {@code view:}, {@code template:},
   * {@code controller:}, {@code action:} or {@code static layout =}, asks for the definition of each, and counts per key
   * how many resolve, and the hints for missing views. Writes counts only to {@code server/build/perf/conventions.txt};
   * the positions that give nothing go to {@code GLS_PERF_DETAILS} when set.
   */
  @Test
  void conventionReferences() throws Exception {
    ProjectModel model = model();
    String details = System.getenv("GLS_PERF_DETAILS");
    StringBuilder unresolved = new StringBuilder();
    try (Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp))) {
      workspace.index();
      for (CompileContext context : workspace.contexts()) {
        for (Path file : workspace.declarations().sources(context.ownRoots)) {
          if (workspace.contextOf(file) != context) {
            continue;
          }
          String text = Files.readString(file);
          Matcher matcher = CONVENTION.matcher(text);
          List<int[]> strings = new ArrayList<>();
          List<String> keys = new ArrayList<>();
          while (matcher.find()) {
            int quote = matcher.group(1) != null ? matcher.start(2) : matcher.start(4);
            int line = (int) text.substring(0, quote).chars().filter(c -> c == '\n').count();
            strings.add(new int[] { line, quote - (text.lastIndexOf('\n', quote) + 1) + 1 });
            keys.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(3));
          }
          if (strings.isEmpty()) {
            continue;
          }
          CompileRequest request = new CompileRequest(file.toString(), text, workspace.lookup(context, p -> null), () -> false);
          CompileResult result = context.compile(request).get(5, TimeUnit.MINUTES);
          count("files", 1);
          count("hints", (int) result.diagnostics.stream().filter(d -> d.severity == Diagnostic.HINT && d.path.equals(file.toString())).count());
          for (int i = 0; i < strings.size(); i++) {
            int[] at = strings.get(i);
            String key = keys.get(i);
            long start = System.nanoTime();
            io.github.vdassios.grailsls.api.Location found = context.query(a -> a.definition(file.toString(), at[0], at[1])).get(1, TimeUnit.MINUTES);
            record("definition " + key, start);
            count(key + (found != null ? " resolved" : " gives nothing") + (kind(file).equals("other") ? ", outside grails-app" : ""), 1);
            if (found == null) {
              unresolved.append(file).append(':').append(at[0] + 1).append(':').append(at[1] + 1).append(' ').append(key).append('\n');
            }
          }
          context.forget(file.toString());
        }
      }
    }
    if (details != null) {
      Files.writeString(Path.of(details), unresolved);
    }
    write(report(), "conventions.txt");
    System.out.println(report());
  }

  /**
   * GSP pages (M5a): every {@code .gsp} file below a module's {@code grails-app}, "compiled" (tags read, hints given) in
   * its page context. Valid views should get no hint: counts per hint kind, and the compile time. Writes counts only to
   * {@code server/build/perf/gsp.txt}; the hints go to {@code GLS_PERF_DETAILS} when set.
   */
  @Test
  void gspPages() throws Exception {
    ProjectModel model = model();
    String details = System.getenv("GLS_PERF_DETAILS");
    StringBuilder hintsOut = new StringBuilder();
    try (Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp))) {
      workspace.index();
      for (ModuleRecord module : model.modules) {
        Path app = Path.of(module.projectDir, "grails-app");
        if (!Files.isDirectory(app)) {
          continue;
        }
        List<Path> pages;
        try (Stream<Path> files = Files.walk(app)) {
          pages = files.filter(f -> f.toString().endsWith(".gsp")).sorted().collect(Collectors.toList());
        }
        for (Path page : pages) {
          CompileContext context = workspace.contextOf(page);
          if (context == null) {
            count("pages without a context", 1);
            continue;
          }
          String text = Files.readString(page);
          CompileRequest request = new CompileRequest(page.toString(), text, workspace.lookup(context, p -> null), () -> false);
          long start = System.nanoTime();
          CompileResult result = context.compile(request).get(5, TimeUnit.MINUTES);
          record("compile page", start);
          count("pages", 1);
          for (Diagnostic d : result.diagnostics) {
            String kind = d.message.startsWith("Unknown tag") ? "unknown tag"
                : d.message.contains("needs the attribute") ? "missing required attribute"
                    : d.message.startsWith("No ") ? "unresolved " + d.message.split(" ")[1] : "other";
            count((d.severity == Diagnostic.HINT ? "hints " : "non-hint diagnostics ") + kind, 1);
            hintsOut.append(page).append(':').append(d.startLine + 1).append(':').append(d.startColumn + 1).append(' ').append(d.message).append('\n');
          }
          count("pages with hints", result.diagnostics.isEmpty() ? 0 : 1);
          context.forget(page.toString());
        }
      }
    }
    if (details != null) {
      Files.writeString(Path.of(details), hintsOut);
    }
    write(report(), "gsp.txt");
    System.out.println(report());
  }

  /**
   * Memory and loader lifetime (C6 budget: under 1.5 GB resident with ten files open; C1: loaders closed), in the
   * context with the most artefact files: ten artefact files compiled and kept, as ten open documents (controllers
   * first, then domain classes, services, taglibs); fifty compiles each of the median and the slowest file of its first
   * artefact kind; then the workspace closed, and whether the context's loader is collected. Writes
   * {@code server/build/perf/memory.txt}.
   */
  @Test
  void keptFilesRepeatsAndClose() throws Exception {
    ProjectModel model = model();
    Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp));
    workspace.index();
    count("heap max MB", (int) (Runtime.getRuntime().maxMemory() >> 20));
    count("contexts", workspace.contexts().size());
    CompileContext context = null;
    Map<String, List<Path>> artefacts = null;
    for (CompileContext candidate : workspace.contexts()) {
      Map<String, List<Path>> files = artefactFiles(workspace, candidate);
      if (artefacts == null || size(files) > size(artefacts)) {
        context = candidate;
        artefacts = files;
      }
    }
    artefacts.forEach((kind, files) -> count("measured context: " + kind, files.size()));
    count("measured context: Java classes from build output", context.javaClasses());
    count("RSS MB before compiling", rssMb());

    List<Path> ten = new ArrayList<>();
    for (List<Path> files : artefacts.values()) {
      ten.addAll(files.subList(0, Math.min(files.size(), 10 - ten.size())));
    }
    long start = System.nanoTime();
    for (Path file : ten) {
      count("ten files: errors", errors(compile(workspace, context, file), file));
    }
    record("ten files, all compiles", start);
    count("ten files: files", ten.size());
    count("ten files: RSS MB", rssMb());
    count("ten files: heap MB after GC", heapMb());

    List<Path> kind = artefacts.values().stream().filter(files -> !files.isEmpty()).findFirst().orElse(List.of());
    Map<Path, Long> once = new LinkedHashMap<>();
    for (Path file : kind) {
      once.put(file, compile(workspace, context, file).millis);
      if (!ten.contains(file)) {
        context.forget(file.toString());
      }
    }
    List<Path> byTime = kind.stream().sorted((a, b) -> Long.compare(once.get(a), once.get(b))).collect(Collectors.toList());
    for (String which : byTime.isEmpty() ? List.<String>of() : List.of("median", "slowest")) {
      Path file = byTime.get(which.equals("median") ? byTime.size() / 2 : byTime.size() - 1);
      List<Integer> heap = new ArrayList<>();
      for (int k = 1; k <= 50; k++) {
        timings.computeIfAbsent("repeat x50, " + which + " file", n -> new ArrayList<>()).add(compile(workspace, context, file).millis);
        if (k % 10 == 0) {
          heap.add(heapMb());
        }
      }
      count("repeat x50, " + which + " file: heap MB after GC, after 10 compiles", heap.get(0));
      count("repeat x50, " + which + " file: heap MB after GC, after 50 compiles", heap.get(4));
    }
    count("RSS MB before close", rssMb());

    // the analyzer is loaded by the context's module loader
    WeakReference<ClassLoader> loader = new WeakReference<>(context.analyzer().getClass().getClassLoader());
    int heapBefore = heapMb();
    int classesBefore = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
    workspace.close();
    context = null;
    workspace = null;
    for (int k = 0; k < 40 && loader.get() != null; k++) {
      System.gc();
      Thread.sleep(100);
    }
    count("close: loader collected (1 = yes)", loader.get() == null ? 1 : 0);
    if (loader.get() != null) {
      clearSoftReferences();
    }
    count("close: loader collected once soft references are cleared (1 = yes)", loader.get() == null ? 1 : 0);
    count("close: heap MB after GC, before", heapBefore);
    count("close: heap MB after GC, after", heapMb());
    count("close: loaded classes, before", classesBefore);
    count("close: loaded classes, after", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
    count("close: RSS MB", rssMb());
    write(report(), "memory.txt");
    System.out.println(report());
  }

  /** The context's own Groovy artefact files by kind: controllers, domain, services, taglib. */
  private static Map<String, List<Path>> artefactFiles(Workspace workspace, CompileContext context) {
    Map<String, List<Path>> files = new LinkedHashMap<>();
    for (String kind : List.of("controllers", "domain", "services", "taglib")) {
      files.put(kind, new ArrayList<>());
    }
    for (Path file : workspace.declarations().sources(context.ownRoots)) {
      List<Path> ofKind = files.get(kind(file));
      if (ofKind != null && file.toString().endsWith(".groovy") && workspace.contextOf(file) == context) {
        ofKind.add(file);
      }
    }
    files.values().forEach(Collections::sort);
    return files;
  }

  private static int size(Map<String, List<Path>> files) {
    return files.values().stream().mapToInt(List::size).sum();
  }

  private static CompileResult compile(Workspace workspace, CompileContext context, Path file) throws Exception {
    CompileRequest request = new CompileRequest(file.toString(), Files.readString(file), workspace.lookup(context, p -> null), () -> false);
    return context.compile(request).get(5, TimeUnit.MINUTES);
  }

  private static int errors(CompileResult result, Path file) {
    return (int) result.diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR && d.path.equals(file.toString())).count();
  }

  private static int rssMb() throws IOException {
    Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid())).start();
    return (int) (Long.parseLong(new String(ps.getInputStream().readAllBytes()).trim()) >> 10);
  }

  private static int heapMb() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      Thread.sleep(100);
    }
    return (int) (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20);
  }

  /**
   * Fills the heap until it runs out: the JVM clears every soft reference before it throws {@link OutOfMemoryError}, so
   * whatever a soft cache alone still held (on hoist-core, the loader) can be collected afterwards.
   */
  private static void clearSoftReferences() throws InterruptedException {
    List<byte[]> fill = new ArrayList<>();
    try {
      while (true) {
        fill.add(new byte[16 << 20]);
      }
    } catch (OutOfMemoryError expected) {
      fill.clear();
    }
    heapMb();
  }

  /** The artefact directory below {@code grails-app}, or {@code other}. */
  private static String kind(Path file) {
    String path = file.toString().replace('\\', '/');
    int at = path.indexOf("/grails-app/");
    if (at < 0) {
      return "other";
    }
    String rest = path.substring(at + "/grails-app/".length());
    String dir = rest.substring(0, Math.max(rest.indexOf('/'), 0));
    return ARTEFACT_DIRS.contains(dir) ? dir : "grails-app/" + dir;
  }

  /**
   * For an artefact file, whether the members Grails adds are on its class (named like the file) after the compile:
   * a counter name, or null for other files. Files Grails does not take as artefacts are counted apart: no class named
   * like the file (an enum, a trait), a controllers-dir class not named {@code *Controller}, a service with no
   * {@code @Transactional} outside comments.
   */
  private static String traitFound(CompileContext context, Path file, String text, String kind) throws Exception {
    String simple = file.getFileName().toString().replace(".groovy", "");
    boolean artefact = Pattern.compile("\\bclass\\s+" + Pattern.quote(simple) + "\\b").matcher(text).find();
    String expected;
    switch (kind) {
      case "controllers":
        artefact &= simple.endsWith("Controller");
        expected = "grails.artefact.Controller";
        break;
      case "domain":
        expected = "org.grails.datastore.gorm.GormEntity";
        break;
      case "taglib":
        artefact &= simple.endsWith("TagLib");
        expected = "grails.artefact.TagLibrary";
        break;
      case "services":
        if (!TRANSACTIONAL.matcher(text).find()) {
          return "services without @Transactional";
        }
        expected = "getTransactionManager";
        break;
      default:
        return null;
    }
    if (!artefact) {
      return kind + ": not an artefact (no class named like the file, or not named by the convention)";
    }
    Matcher packageLine = Pattern.compile("(?m)^package\\s+([\\w.]+)").matcher(text);
    String className = packageLine.find() ? packageLine.group(1) + "." + simple : simple;
    CompiledClass compiled = context.query(a -> a.compiledClass(file.toString(), className)).get(1, TimeUnit.MINUTES);
    if (compiled == null) {
      return kind + ": no class named like the file";
    }
    boolean found = compiled.interfaces.contains(expected) || compiled.methods.contains(expected);
    return kind + (kind.equals("services") ? " with @Transactional" : "") + (found ? ": Grails members found" : ": Grails members missing");
  }

  /**
   * Fills the symbol index as the server does (every context at once), then parses each context's files again batch
   * by batch: a compile submitted while the index fills waits for one batch at most. Then a few searches.
   */
  private void symbolIndex(Workspace workspace) throws Exception {
    long start = System.nanoTime();
    workspace.indexSymbols().get(30, TimeUnit.MINUTES);
    record("symbol index, all contexts", start);
    count("symbol index files", workspace.symbols().files());
    count("symbols", workspace.symbols().search("").size());
    for (CompileContext context : workspace.contexts()) {
      List<Path> files = workspace.declarations().sources(context.ownRoots);
      for (int from = 0; from < files.size(); from += Workspace.SYMBOL_BATCH) {
        List<String> batch = files.subList(from, Math.min(from + Workspace.SYMBOL_BATCH, files.size())).stream().map(Path::toString).collect(Collectors.toList());
        start = System.nanoTime();
        context.query(a -> a.declarations(batch)).get(5, TimeUnit.MINUTES);
        record("symbol batch of " + Workspace.SYMBOL_BATCH, start);
      }
    }
    for (String query : List.of("", "e", "service")) {
      start = System.nanoTime();
      workspace.symbols().search(query);
      record("workspace symbol search", start);
    }
    System.gc();
    count("heap MB after the symbol index, after GC", (int) ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
  }

  private void measure(Workspace workspace, CompileContext context, Path file) throws Exception {
    String text = Files.readString(file);
    int[] at = firstAccess(text);
    if (at == null) {
      count("files without a member access", 1);
      return;
    }
    String path = file.toString();
    CompileRequest request = new CompileRequest(path, text, workspace.lookup(context, p -> null), () -> false);
    int line = at[0];
    int dot = at[1];

    long start = System.nanoTime();
    context.compile(request).get(5, TimeUnit.MINUTES);
    record("compile", start);
    timed(context, "first query", a -> a.hover(path, line, dot - 1));
    timed(context, "hover", a -> a.hover(path, line, dot + 1));
    CompletionList list = timed(context, "completion", a -> a.completion(request, line, dot + 1));
    count("completions with no items", list != null && list.items.isEmpty() ? 1 : 0);
    timed(context, "signature help", a -> a.signatureHelp(request, line, dot + 1));

    // the member deleted: "receiver." as just typed, which does not parse
    String typed = withoutMember(text, line, dot + 1);
    CompileRequest typedRequest = new CompileRequest(path, typed, request.lookup, () -> false);
    start = System.nanoTime();
    context.compile(typedRequest).get(5, TimeUnit.MINUTES);
    record("compile with a typed dot", start);
    CompletionList typedList = timed(context, "completion after a typed dot", a -> a.completion(typedRequest, line, dot + 1));
    count("completions after a typed dot with no items", typedList != null && typedList.items.isEmpty() ? 1 : 0);
    context.forget(path);
  }

  /** Runs and times a query; a query that throws is counted under "<name> errors" and gives null. */
  private <T> T timed(CompileContext context, String name, Function<Analyzer, T> query) throws Exception {
    long start = System.nanoTime();
    try {
      T result = context.query(query).get(5, TimeUnit.MINUTES);
      record(name, start);
      return result;
    } catch (ExecutionException e) {
      count(name + " errors", 1);
      return null;
    }
  }

  private static void write(String report) throws IOException {
    write(report, "latency.txt");
  }

  private static void write(String report, String name) throws IOException {
    Path out = Path.of("build/perf", name);
    Files.createDirectories(out.getParent());
    Files.writeString(out, report);
  }

  private void record(String name, long startNanos) {
    timings.computeIfAbsent(name, k -> new ArrayList<>()).add((System.nanoTime() - startNanos) / 1_000_000);
  }

  private void count(String name, int n) {
    counts.merge(name, n, Integer::sum);
  }

  private String report() {
    StringBuilder out = new StringBuilder("metric: n, median, p90, max ms\n");
    timings.forEach((name, list) -> {
      List<Long> sorted = new ArrayList<>(list);
      Collections.sort(sorted);
      out.append(String.format("%s: %d, %d, %d, %d%n", name, sorted.size(), sorted.get(sorted.size() / 2), sorted.get((int) Math.ceil(sorted.size() * 0.9) - 1),
          sorted.get(sorted.size() - 1)));
    });
    counts.forEach((name, n) -> out.append(name).append(": ").append(n).append('\n'));
    return out.toString();
  }

  /** Line and column of the dot of the first member access outside comments, imports and strings; null if none. */
  private static int[] firstAccess(String text) {
    String[] lines = text.split("\n", -1);
    boolean comment = false;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      String trimmed = line.trim();
      if (comment || trimmed.startsWith("/*")) {
        comment = !trimmed.contains("*/");
        continue;
      }
      if (trimmed.startsWith("//") || trimmed.startsWith("import ") || trimmed.startsWith("package ") || trimmed.startsWith("@")) {
        continue;
      }
      Matcher matcher = ACCESS.matcher(line);
      while (matcher.find()) {
        String before = line.substring(0, matcher.start());
        if (before.chars().filter(c -> c == '"' || c == '\'').count() % 2 == 0 && !before.contains("//")) {
          return new int[] { i, matcher.end(1) };
        }
      }
    }
    return null;
  }

  private static String withoutMember(String text, int line, int column) {
    String[] lines = text.split("\n", -1);
    String target = lines[line];
    int end = column;
    while (end < target.length() && Character.isJavaIdentifierPart(target.charAt(end))) {
      end++;
    }
    lines[line] = target.substring(0, column) + target.substring(end);
    return String.join("\n", lines);
  }

  private ProjectModel model() throws Exception {
    String project = System.getenv("GLS_PERF_PROJECT");
    if (project == null || project.isEmpty()) {
      return Fixtures.built("grails4", "GLS_TEST_JDK11");
    }
    String jdk = System.getenv().getOrDefault("GLS_PERF_JDK", System.getenv("GLS_TEST_JDK11"));
    return new GradleBridge(tmp.resolve("cache"), Path.of(jdk), Duration.ofMinutes(10)).resolve(Path.of(project), false);
  }

  private static List<Path> artefactFiles(ProjectModel model) throws IOException {
    List<Path> files = new ArrayList<>();
    for (ModuleRecord module : model.modules) {
      for (String dir : ARTEFACT_DIRS) {
        Path root = Path.of(module.projectDir, "grails-app", dir);
        if (Files.isDirectory(root)) {
          try (Stream<Path> walk = Files.walk(root)) {
            files.addAll(walk.filter(p -> p.toString().endsWith(".groovy")).sorted().collect(Collectors.toList()));
          }
        }
      }
    }
    return files;
  }

  /** At most {@code limit} files, spread evenly over the list. */
  private static List<Path> sample(List<Path> files, int limit) {
    if (files.size() <= limit) {
      return files;
    }
    List<Path> sample = new ArrayList<>();
    for (int i = 0; i < limit; i++) {
      sample.add(files.get((int) ((long) i * files.size() / limit)));
    }
    return sample;
  }
}
