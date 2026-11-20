package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.vdassios.grailsls.api.Analyzer;
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
 * directories of sibling modules it depends on, those siblings' own dependencies (only artifacts it does not have:
 * its own versions stay the only ones), and the compiled classes of its own {@code .java} files. Groovy classes resolve from source: its own roots,
 * then {@code main}'s (for other source sets), then the siblings'. A sibling's class pulled in from source compiles
 * in this loader, so it needs the sibling's {@code implementation} dependencies, which Gradle keeps off this classpath.
 */
public final class Workspace implements AutoCloseable {
  private final List<CompileContext> contexts = new ArrayList<>();
  /** One ANTLR runtime per Groovy jar, shared by the contexts of that jar (null value: the jar has none). */
  private final Map<Path, AntlrLoader> antlr = new HashMap<>();
  /** Files parsed per call when filling the symbol index. */
  public static final int SYMBOL_BATCH = 50;

  private final DeclarationIndex index = new DeclarationIndex();
  private final SymbolIndex symbols = new SymbolIndex();
  private final ClassSources classSources;
  /** Every module's {@code grails-app} directory that exists. */
  private final List<Path> grailsApps = new ArrayList<>();
  /** The taglib classes of each jar of the contexts' class paths, read once. */
  private final Map<Path, List<String>> jarTaglibs = new ConcurrentHashMap<>();
  /**
   * Per context, jars not on its class path whose taglibs its pages may use: its own runtime-only jars
   * ({@link SourceSetRecord#runtimeOnly}, e.g. Grails 7's {@code RenderTagLib}), then the main class path and
   * runtime-only jars of every module depending on it (a plugin's pages render inside the app that uses it).
   */
  private final Map<CompileContext, List<Path>> pageJars = new HashMap<>();
  /** Packages and classes of the jars, for import completion. */
  private final ClasspathNames classpathNames = new ClasspathNames();
  /** One context per Groovy version of the modules, for {@link #jdkClassFileError()}. */
  private final Map<String, CompileContext> probes = new LinkedHashMap<>();
  /** The analysis layer's jar: first on every context's class path, but not the project's. */
  private final Path analysisJar;

  /** Reports to stderr: for tests. */
  public Workspace(ProjectModel model, Path analysisJar) {
    this(model, analysisJar, (show, message) -> System.err.println("grails-language-server: " + message));
  }

  /**
   * @param analysisJar the analysis layer's jar, in the server's cache directory; library sources are extracted next to it
   * @param reporter    where failures go, each prefixed with its context's name: {@code true} for those to show to the
   *                    user (a feature is off), {@code false} for those only to log
   */
  public Workspace(ProjectModel model, Path analysisJar, BiConsumer<Boolean, String> reporter) {
    this.analysisJar = analysisJar;
    classSources = new ClassSources(analysisJar.resolveSibling("sources"));
    for (ModuleRecord module : model.modules) {
      Path grailsApp = Path.of(module.projectDir, "grails-app");
      if (Files.isDirectory(grailsApp)) {
        grailsApps.add(grailsApp);
      }
    }
    Map<String, CompileContext> mains = new HashMap<>();
    for (ModuleRecord module : model.modules) {
      if (module.groovyVersion == null) {
        continue;
      }
      for (SourceSetRecord sourceSet : module.sourceSets) {
        if (!ownRoots(module, sourceSet).isEmpty()) {
          CompileContext context = context(model, module, sourceSet, analysisJar, antlr, reporter);
          contexts.add(context);
          probes.putIfAbsent(module.groovyVersion, context);
          if (sourceSet.name.equals("main")) {
            mains.put(module.path, context);
          }
        }
      }
    }
    for (ModuleRecord module : model.modules) {
      CompileContext context = mains.get(module.path);
      if (context == null) {
        continue;
      }
      Set<Path> jars = new LinkedHashSet<>(paths(module.sourceSet("main").runtimeOnly));
      for (ModuleRecord dependent : model.modules) {
        if (dependent != module && mains.containsKey(dependent.path) && siblings(model, dependent).contains(module)) {
          jars.addAll(paths(dependent.sourceSet("main").compileClasspath));
          jars.addAll(paths(dependent.sourceSet("main").runtimeOnly));
        }
      }
      jars.removeAll(context.classpath());
      jars.removeIf(jar -> !jar.getFileName().toString().endsWith(".jar") || !Files.isRegularFile(jar));
      if (!jars.isEmpty()) {
        pageJars.put(context, List.copyOf(jars));
      }
    }
  }

  private static List<Path> paths(List<String> paths) {
    return paths.stream().map(Path::of).collect(Collectors.toList());
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

  /**
   * The context whose source root contains {@code file} (the deepest root wins), or null when it is no source. A GSP
   * page belongs to the context of the {@code grails-app} it is in: the one compiling that module's artefacts.
   */
  public CompileContext contextOf(Path file) {
    if (file.getFileName() != null && file.getFileName().toString().endsWith(".gsp")) {
      return pageContext(file);
    }
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

  private CompileContext pageContext(Path page) {
    Path app = null;
    for (Path candidate : grailsApps) {
      if (page.startsWith(candidate) && (app == null || candidate.getNameCount() > app.getNameCount())) {
        app = candidate;
      }
    }
    for (CompileContext context : app == null ? List.<CompileContext>of() : contexts) {
      for (Path root : context.ownRoots) {
        if (root.startsWith(app)) {
          return context;
        }
      }
    }
    return null;
  }

  /**
   * The classes named {@code *TagLib} in the jars of a context's class path and of its {@link #pageJars}, by binary
   * name, each with its jar; the first jar holding a name wins, as in a class loader.
   */
  Map<String, String> taglibClasses(CompileContext context) {
    Map<String, String> found = new LinkedHashMap<>();
    List<Path> jars = new ArrayList<>(context.classpath());
    jars.addAll(pageJars.getOrDefault(context, List.of()));
    for (Path entry : jars) {
      if (entry.getFileName() != null && entry.getFileName().toString().endsWith(".jar")) {
        for (String name : jarTaglibs.computeIfAbsent(entry, Workspace::taglibsIn)) {
          found.putIfAbsent(name, entry.toString());
        }
      }
    }
    return found;
  }

  /** A class file in one of the context's {@link #pageJars}, as a {@code jar:} URL; null when none holds it. */
  private URL pageClassFile(CompileContext context, String resource) {
    for (Path jar : pageJars.getOrDefault(context, List.of())) {
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        if (zip.getEntry(resource) != null) {
          return new URL("jar:" + jar.toUri() + "!/" + resource);
        }
      } catch (IOException e) {
        // unreadable: skip it
      }
    }
    return null;
  }

  private static List<String> taglibsIn(Path jar) {
    List<String> names = new ArrayList<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (name.endsWith("TagLib.class") && !name.contains("$") && !name.endsWith("/TagLib.class") && !name.startsWith("META-INF/")) {
          names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
        }
      }
    } catch (IOException e) {
      // not a jar: the class path skips it too
    }
    return names;
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

      @Override
      public List<String> classpathPackages(String parent) {
        return classpathNames.packages(projectClasspath(context), parent);
      }

      @Override
      public List<String> classpathClasses(String packageName) {
        return classpathNames.classes(projectClasspath(context), packageName);
      }

      @Override
      public List<String> classesNamed(String simpleName) {
        return index.classesNamed(simpleName, context.searchRoots);
      }

      @Override
      public List<String> grailsAppDirs() {
        return Workspace.this.grailsAppDirs(context);
      }

      @Override
      public Map<String, String> taglibClasses() {
        return Workspace.this.taglibClasses(context);
      }

      @Override
      public String classSource(String className) {
        String resource = className.replace('.', '/');
        // the project's own Java, compiled by Gradle
        for (Path root : context.searchRoots) {
          Path file = root.resolve(resource + ".java");
          if (Files.isRegularFile(file)) {
            return file.toString();
          }
        }
        ClassLoader loader = context.loader();
        URL classFile = loader == null ? null : loader.getResource(resource + ".class");
        if (classFile == null) {
          classFile = pageClassFile(context, resource + ".class");
        }
        Path source = classFile == null ? null : classSources.sourceOf(className, classFile);
        return source == null ? null : source.toString();
      }
    };
  }

  private List<Path> projectClasspath(CompileContext context) {
    return context.classpath().stream().filter(entry -> !entry.equals(analysisJar)).collect(Collectors.toList());
  }

  /** Every module's {@code grails-app}, first the one of the module {@code context} belongs to (the deepest module holding its roots). */
  List<String> grailsAppDirs(CompileContext context) {
    Path own = null;
    for (Path app : grailsApps) {
      Path module = app.getParent();
      if (context.ownRoots.stream().anyMatch(root -> root.startsWith(module)) && (own == null || module.getNameCount() > own.getParent().getNameCount())) {
        own = app;
      }
    }
    List<String> dirs = new ArrayList<>();
    if (own != null) {
      dirs.add(own.toString());
    }
    for (Path app : grailsApps) {
      if (!app.equals(own)) {
        dirs.add(app.toString());
      }
    }
    return dirs;
  }

  /**
   * Why the project's Groovy cannot compile on the JDK the server runs on, or null when it can: each Groovy version of
   * the modules reads one of the JDK's class files in a context of its own (see {@link Analyzer#jdkClassFileError}).
   * The contexts started here are the ones the first compiles and the symbol index use.
   */
  public String jdkClassFileError() {
    for (CompileContext context : probes.values()) {
      String found;
      try {
        found = context.query(analyzer -> {
          String error = analyzer.jdkClassFileError();
          return error == null ? null : "Groovy " + analyzer.groovyVersion() + " cannot read the class files of JDK " + System.getProperty("java.version")
              + " (" + error + "): run the server on a JDK this Groovy supports, see docs/editors.md";
        }).get();
      } catch (ExecutionException e) {
        throw new IllegalStateException("cannot check the JDK in " + context.name + ": " + e.getCause(), e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
      if (found != null) {
        return found;
      }
    }
    return null;
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
    antlr.values().stream().filter(loader -> loader != null).forEach(AntlrLoader::close);
  }

  private static CompileContext context(ProjectModel model, ModuleRecord module, SourceSetRecord sourceSet, Path analysisJar,
      Map<Path, AntlrLoader> antlr, BiConsumer<Boolean, String> reporter) {
    SourceSetRecord main = sourceSet.name.equals("main") ? null : module.sourceSet("main");
    List<ModuleRecord> siblings = siblings(model, module);

    List<Path> own = ownRoots(module, sourceSet);
    Set<Path> search = new LinkedHashSet<>(own);
    if (main != null) {
      search.addAll(ownRoots(module, main));
    }
    List<SourceSetRecord> others = others(module, sourceSet);
    for (SourceSetRecord other : others) {
      search.addAll(ownRoots(module, other));
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
    // Gradle resolves one version of each artifact per source set; a sibling's other version would sit next to it
    // (two Groovy versions stop Groovy's runtime from starting), so a sibling adds only artifacts not yet there
    Set<String> artifacts = classpath.stream().map(Workspace::artifact).filter(a -> a != null).collect(Collectors.toSet());
    for (ModuleRecord sibling : siblings) {
      SourceSetRecord siblingMain = sibling.sourceSet("main");
      if (siblingMain != null) {
        Set<Path> extra = new LinkedHashSet<>();
        addOutsideBuildDirs(extra, siblingMain.compileClasspath, buildDirs);
        for (Path jar : extra) {
          String artifact = artifact(jar);
          if (artifact == null || artifacts.add(artifact)) {
            classpath.add(jar);
          }
        }
      }
    }

    Map<String, Path> classFiles = new HashMap<>();
    int notBuilt = javaClasses(sourceSet, classFiles);
    if (main != null) {
      javaClasses(main, classFiles); // main's are counted by main's own context
    }
    for (SourceSetRecord other : others) {
      javaClasses(other, classFiles);
    }
    // Gradle puts main's output (classes and resources) on the other source sets' classpath and siblings' jars on
    // every one, so their extension modules apply: statically compiled code calls their methods
    Map<String, List<Path>> descriptors = new HashMap<>();
    if (main != null) {
      extensionModules(main, descriptors, classFiles);
    }
    for (ModuleRecord sibling : siblings) {
      SourceSetRecord siblingMain = sibling.sourceSet("main");
      if (siblingMain != null) {
        extensionModules(siblingMain, descriptors, classFiles);
      }
    }
    String name = module.path + " " + sourceSet.name;
    Path groovyJar = AntlrLoader.groovyJar(classpath);
    if (groovyJar != null && !antlr.containsKey(groovyJar)) {
      antlr.put(groovyJar, AntlrLoader.of(groovyJar)); // null kept too: the jar is read once
    }
    AntlrLoader shared = groovyJar == null ? null : antlr.get(groovyJar);
    return new CompileContext(name, own, new ArrayList<>(search), new ArrayList<>(classpath), classFiles, descriptors, notBuilt,
        classesTask(module.path, sourceSet.name), shared, reporter);
  }

  /**
   * Adds the extension module descriptors in the source set's resource roots, and the class files of the extension
   * classes they name from its class output. A class not built is left out: Groovy skips it with a warning.
   */
  private static void extensionModules(SourceSetRecord sourceSet, Map<String, List<Path>> descriptors, Map<String, Path> classFiles) {
    for (String root : sourceSet.resourceRoots) {
      for (String resource : ModuleClassLoader.EXTENSION_DESCRIPTORS) {
        Path descriptor = Path.of(root, resource);
        if (!Files.isRegularFile(descriptor)) {
          continue;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
          properties.load(reader);
        } catch (IOException e) {
          continue;
        }
        descriptors.computeIfAbsent(resource, k -> new ArrayList<>()).add(descriptor);
        for (String key : List.of("extensionClasses", "staticExtensionClasses")) {
          for (String type : properties.getProperty(key, "").trim().split("[,; ]+")) {
            if (!type.isEmpty()) {
              classFiles(type, sourceSet.classesDirs, classFiles);
            }
          }
        }
      }
    }
  }

  private static void addOutsideBuildDirs(Set<Path> classpath, List<String> entries, List<Path> buildDirs) {
    for (String entry : entries) {
      Path path = Path.of(entry);
      if (buildDirs.stream().noneMatch(path::startsWith)) {
        classpath.add(path);
      }
    }
  }

  /**
   * Group, artifact and classifier of a jar in Gradle's cache ({@code files-2.1/<group>/<artifact>/<version>/<hash>/})
   * or a Maven repository ({@code .../<artifact>/<version>/<artifact>-<version>[-<classifier>].jar}), without the
   * version; null for any other file.
   */
  static String artifact(Path jar) {
    String file = jar.getFileName().toString();
    int n = jar.getNameCount();
    if (n >= 6 && jar.getName(n - 6).toString().equals("files-2.1")) {
      String artifact = jar.getName(n - 4).toString();
      String prefix = artifact + "-" + jar.getName(n - 3);
      return jar.getName(n - 5) + ":" + artifact + ":" + (file.startsWith(prefix) ? file.substring(prefix.length()) : file);
    }
    Path versionDir = jar.getParent();
    Path artifactDir = versionDir == null ? null : versionDir.getParent();
    if (artifactDir == null || artifactDir.getFileName() == null) {
      return null;
    }
    String prefix = artifactDir.getFileName() + "-" + versionDir.getFileName();
    return file.startsWith(prefix) ? artifactDir + ":" + file.substring(prefix.length()) : null;
  }

  /**
   * The module's source sets other than main whose output Gradle adds to this one's classpath, such as {@code test} for
   * Grails' {@code integrationTest}: their sources are searched like main's, since their output is build output.
   */
  private static List<SourceSetRecord> others(ModuleRecord module, SourceSetRecord sourceSet) {
    return sourceSet.outputsOf.stream().filter(name -> !name.equals("main")).map(module::sourceSet).filter(s -> s != null)
        .collect(Collectors.toList());
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
        if (!type.contains("$")) {
          found |= classFiles(type, sourceSet.classesDirs, out);
        }
      }
      if (!found) {
        notBuilt++;
      }
    }
    return notBuilt;
  }

  /** Adds a top-level type's class file and its {@code $} classes from the class directories; false if none has it. */
  private static boolean classFiles(String type, List<String> classesDirs, Map<String, Path> out) {
    boolean found = false;
    String resource = type.replace('.', '/');
    int slash = resource.lastIndexOf('/');
    String simple = resource.substring(slash + 1);
    for (String dir : classesDirs) {
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
    return found;
  }
}
