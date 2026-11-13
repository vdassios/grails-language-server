package io.github.vdassios.grailsls.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
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
import io.github.vdassios.grailsls.api.CompletionList;
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
    Path out = Path.of("build/perf/latency.txt");
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
