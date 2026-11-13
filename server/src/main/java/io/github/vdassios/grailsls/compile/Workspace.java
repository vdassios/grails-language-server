package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;
import io.github.vdassios.grailsls.index.DeclarationIndex;
import io.github.vdassios.grailsls.index.Declarations;
import io.github.vdassios.grailsls.index.SymbolIndex;
import io.github.vdassios.grailsls.project.ModuleRecord;
import io.github.vdassios.grailsls.project.ModuleRecord.SourceSetRecord;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * The compile setup of a resolved project: one {@link CompileContext} per source set of each Groovy module, and the
 * declaration index of all their roots. Replaced as a whole when the project model changes.
 *
 * <p>Per source set: the loader holds its compile classpath without any module's build output, the class
 * directories of sibling modules it depends on, those siblings' own dependencies (after its own, so its versions
 * win), and the compiled classes of its own {@code .java} files. Groovy classes resolve from source: its own roots,
 * then {@code main}'s (for other source sets), then the siblings'. A sibling's class pulled in from source compiles
 * in this loader, so it needs the sibling's {@code implementation} dependencies, which Gradle keeps off this classpath.
 */
public final class Workspace implements AutoCloseable {
  private final List<CompileContext> contexts = new ArrayList<>();
  /** Files parsed per call when filling the symbol index. */
  public static final int SYMBOL_BATCH = 50;

  private final DeclarationIndex index = new DeclarationIndex();
  private final SymbolIndex symbols = new SymbolIndex();

  public Workspace(ProjectModel model, Path analysisJar) {
    for (ModuleRecord module : model.modules) {
      if (module.groovyVersion == null) {
        continue;
      }
      for (SourceSetRecord sourceSet : module.sourceSets) {
        if (!ownRoots(module, sourceSet).isEmpty()) {
          contexts.add(context(model, module, sourceSet, analysisJar));
        }
      }
    }
  }

  /** Scans every source root into the declaration index. */
  public void index() {
    for (CompileContext context : contexts) {
      index.addRoots(context.searchRoots);
    }
  }

  public DeclarationIndex declarations() {
    return index;
  }

  public SymbolIndex symbols() {
    return symbols;
  }

  /**
   * Fills the symbol index in the background: each context parses its own files, {@link #SYMBOL_BATCH} at a time on its
   * compile thread, so a compile waits for one batch at most. Completes when every context is done (or closed).
   */
  public CompletableFuture<Void> indexSymbols() {
    List<CompletableFuture<Void>> all = new ArrayList<>();
    for (CompileContext context : contexts) {
      List<Path> files = index.sources(context.ownRoots).stream().filter(file -> contextOf(file) == context).collect(Collectors.toList());
      all.add(indexSymbols(context, files, 0));
    }
    return CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0]));
  }

  private CompletableFuture<Void> indexSymbols(CompileContext context, List<Path> files, int from) {
    if (from >= files.size()) {
      return CompletableFuture.completedFuture(null);
    }
    List<String> batch = files.subList(from, Math.min(from + SYMBOL_BATCH, files.size())).stream().map(Path::toString).collect(Collectors.toList());
    return context.query(analyzer -> analyzer.declarations(batch)).thenCompose(found -> {
      found.forEach((file, declared) -> symbols.put(Path.of(file), declared));
      return indexSymbols(context, files, from + SYMBOL_BATCH);
    }).exceptionally(error -> null); // closed: a newer workspace indexes again
  }

  /** Re-reads the symbols of a file that was saved, created or deleted, in the background. */
  public void reindexSymbols(Path file) {
    CompileContext context = contextOf(file);
    if (context == null || !file.toString().endsWith(".groovy")) {
      return;
    }
    context.query(analyzer -> analyzer.declarations(List.of(file.toString()))).thenAccept(found -> {
      List<Symbol> declared = found.get(file.toString());
      if (Files.isRegularFile(file) && declared != null) {
        symbols.put(file, declared);
      } else {
        symbols.remove(file);
      }
    });
  }

  public List<CompileContext> contexts() {
    return List.copyOf(contexts);
  }

  /** The context whose source root contains {@code file} (the deepest root wins), or null when it is no source. */
  public CompileContext contextOf(Path file) {
    CompileContext best = null;
    int depth = -1;
    for (CompileContext context : contexts) {
      for (Path root : context.ownRoots) {
        if (file.startsWith(root) && root.getNameCount() > depth && DeclarationIndex.isSource(root, file)) {
          best = context;
          depth = root.getNameCount();
        }
      }
    }
    return best;
  }

  /** Class lookup for compiles in {@code context}: the declaration index over its search roots, and open text. */
  public SourceLookup lookup(CompileContext context, Function<Path, String> openText) {
    return new SourceLookup() {
      @Override
      public String fileOf(String className) {
        Path file = index.fileOf(className, context.searchRoots);
        return file == null ? null : file.toString();
      }

      @Override
      public String openText(String file) {
        return openText.apply(Path.of(file));
      }

      @Override
      public List<Symbol> types() {
        return symbols.types(context.searchRoots);
      }
    };
  }

  /** Status detail: empty, or what limits the results (Java sources not compiled yet, and the tasks that build them). */
  public String summary() {
    int notBuilt = contexts.stream().mapToInt(c -> c.javaFilesNotBuilt).sum();
    if (notBuilt == 0) {
      return "";
    }
    String tasks = contexts.stream().filter(c -> c.javaFilesNotBuilt > 0).map(c -> c.classesTask).collect(Collectors.joining(" "));
    return ", " + notBuilt + " Java " + (notBuilt == 1 ? "file" : "files") + " not built (run Gradle " + tasks + ")";
  }

  /** Gradle's task for a source set's classes: {@code classes} for {@code main}, {@code <name>Classes} otherwise. */
  static String classesTask(String modulePath, String sourceSet) {
    String task = sourceSet.equals("main") ? "classes" : sourceSet + "Classes";
    return modulePath.equals(":") ? ":" + task : modulePath + ":" + task;
  }

  @Override
  public void close() {
    contexts.forEach(CompileContext::close);
  }

  private static CompileContext context(ProjectModel model, ModuleRecord module, SourceSetRecord sourceSet, Path analysisJar) {
    SourceSetRecord main = sourceSet.name.equals("main") ? null : module.sourceSet("main");
    List<ModuleRecord> siblings = siblings(model, module);

    List<Path> own = ownRoots(module, sourceSet);
    Set<Path> search = new LinkedHashSet<>(own);
    if (main != null) {
      search.addAll(ownRoots(module, main));
    }
    for (ModuleRecord sibling : siblings) {
      SourceSetRecord siblingMain = sibling.sourceSet("main");
      if (siblingMain != null) {
        search.addAll(ownRoots(sibling, siblingMain));
      }
    }

    List<Path> buildDirs = model.modules.stream().map(m -> Path.of(m.projectDir).resolve("build")).collect(Collectors.toList());
    Set<Path> classpath = new LinkedHashSet<>();
    classpath.add(analysisJar);
    addOutsideBuildDirs(classpath, sourceSet.compileClasspath, buildDirs);
    for (ModuleRecord sibling : siblings) {
      SourceSetRecord siblingMain = sibling.sourceSet("main");
      if (siblingMain != null) {
        siblingMain.classesDirs.forEach(dir -> classpath.add(Path.of(dir)));
      }
    }
    for (ModuleRecord sibling : siblings) {
      SourceSetRecord siblingMain = sibling.sourceSet("main");
      if (siblingMain != null) {
        addOutsideBuildDirs(classpath, siblingMain.compileClasspath, buildDirs);
      }
    }

    Map<String, Path> javaClasses = new HashMap<>();
    int notBuilt = javaClasses(sourceSet, javaClasses);
    if (main != null) {
      javaClasses(main, javaClasses); // main's are counted by main's own context
    }
    String name = module.path + " " + sourceSet.name;
    return new CompileContext(name, own, new ArrayList<>(search), new ArrayList<>(classpath), javaClasses, notBuilt, classesTask(module.path, sourceSet.name));
  }

  private static void addOutsideBuildDirs(Set<Path> classpath, List<String> entries, List<Path> buildDirs) {
    for (String entry : entries) {
      Path path = Path.of(entry);
      if (buildDirs.stream().noneMatch(path::startsWith)) {
        classpath.add(path);
      }
    }
  }

  /** Source roots without build output and tool directories below the module. */
  private static List<Path> ownRoots(ModuleRecord module, SourceSetRecord sourceSet) {
    Path projectDir = Path.of(module.projectDir);
    return sourceSet.sourceRoots.stream().map(Path::of).filter(root -> !root.startsWith(projectDir) || DeclarationIndex.isSource(projectDir, root))
        .collect(Collectors.toList());
  }

  /** Modules this one depends on, directly or through another sibling, in breadth-first order. */
  private static List<ModuleRecord> siblings(ProjectModel model, ModuleRecord module) {
    List<ModuleRecord> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>(List.of(module.path));
    Deque<String> queue = new ArrayDeque<>(module.dependsOn);
    while (!queue.isEmpty()) {
      String path = queue.poll();
      ModuleRecord sibling = model.module(path);
      if (sibling != null && seen.add(path)) {
        result.add(sibling);
        queue.addAll(sibling.dependsOn);
      }
    }
    return result;
  }

  /**
   * Adds the class files of the source set's {@code .java} files: each top-level type's class and its {@code $}
   * classes, found in the source set's class directories. Returns how many files had no class there.
   */
  private static int javaClasses(SourceSetRecord sourceSet, Map<String, Path> out) {
    int notBuilt = 0;
    for (String javaFile : sourceSet.javaFiles) {
      String text;
      try {
        text = Files.readString(Path.of(javaFile), StandardCharsets.UTF_8);
      } catch (IOException e) {
        continue;
      }
      boolean found = false;
      for (String type : Declarations.of(text)) {
        if (type.contains("$")) {
          continue;
        }
        String resource = type.replace('.', '/');
        int slash = resource.lastIndexOf('/');
        String simple = resource.substring(slash + 1);
        for (String dir : sourceSet.classesDirs) {
          Path packageDir = slash < 0 ? Path.of(dir) : Path.of(dir, resource.substring(0, slash));
          if (!Files.isRegularFile(packageDir.resolve(simple + ".class"))) {
            continue;
          }
          found = true;
          String prefix = slash < 0 ? "" : resource.substring(0, slash + 1);
          try (DirectoryStream<Path> files = Files.newDirectoryStream(packageDir, simple + "{.class,$*.class}")) {
            for (Path file : files) {
              out.putIfAbsent(prefix + file.getFileName(), file);
            }
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }
      if (!found) {
        notBuilt++;
      }
    }
    return notBuilt;
  }
}
