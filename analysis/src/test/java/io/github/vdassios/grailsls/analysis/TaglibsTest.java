package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import groovy.lang.GroovySystem;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * The taglib index: which tags a namespace has, on Grails 4 to 7 (tags are closures) and Grails 8 (tags are methods
 * too), declared in the taglib or inherited from a superclass or trait.
 */
class TaglibsTest {
  /** Grails 8's grails-gsp jar (FormTagLib, ValidationTagLib, ...), from the build. */
  private static final Path GRAILS8 = Path.of(System.getProperty("grails8.taglibs"));

  @TempDir
  Path tmp;

  private final List<String> shown = new CopyOnWriteArrayList<>();
  private final List<String> logged = new CopyOnWriteArrayList<>();
  private final Reports reports = new Reports((show, message) -> (show ? shown : logged).add(message));
  private final List<URLClassLoader> loaders = new ArrayList<>();

  @AfterEach
  void close() throws IOException {
    for (URLClassLoader loader : loaders) {
      loader.close();
    }
  }

  // lookups, loaders, compiled classes

  private static SourceLookup lookup(Map<String, String> taglibClasses, Map<String, String> sources, List<String> grailsAppDirs) {
    return new SourceLookup() {
      @Override
      public String fileOf(String className) {
        return sources.get(className);
      }

      @Override
      public String openText(String path) {
        return null;
      }

      @Override
      public List<String> grailsAppDirs() {
        return grailsAppDirs;
      }

      @Override
      public Map<String, String> taglibClasses() {
        return taglibClasses;
      }
    };
  }

  private URLClassLoader loader(Path... entries) throws IOException {
    URL[] urls = new URL[entries.length];
    for (int i = 0; i < entries.length; i++) {
      urls[i] = entries[i].toUri().toURL();
    }
    URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
    loaders.add(loader);
    return loader;
  }

  /** A Grails 8 project's loader: grails-taglib's TagDiscoveryRules is on its class path (a resource only, never loaded). */
  private ClassLoader grails8() throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("grails-taglib-8/org/grails/taglib/discovery"));
    Files.write(dir.resolve("TagDiscoveryRules.class"), new byte[0]);
    return loader(tmp.resolve("grails-taglib-8"));
  }

  /** A Grails 4 to 7 project's loader: no TagDiscoveryRules. */
  private ClassLoader grails7() throws IOException {
    return loader(Files.createDirectories(tmp.resolve("empty")));
  }

  private Taglibs taglibs(ClassLoader loader) {
    return new Taglibs(GroovyAnalyzer::parsedClasses, reports, loader);
  }

  /** Compiles Groovy sources (file name to text) into a directory, against classes in {@code classpath}. */
  private Path compile(String dir, Map<String, String> sources, Path... classpath) {
    Path out = tmp.resolve(dir);
    CompilerConfiguration config = new CompilerConfiguration();
    config.setTargetDirectory(out.toFile());
    config.setClasspathList(Stream.of(classpath).map(Path::toString).collect(Collectors.toList()));
    CompilationUnit unit = new CompilationUnit(config);
    sources.forEach(unit::addSource);
    unit.compile();
    return out;
  }

  private Path jar(Path classes, String name) throws IOException {
    Path jar = tmp.resolve(name);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar)); Stream<Path> walk = Files.walk(classes)) {
      for (Path file : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
        zip.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
    return jar;
  }

  private Path write(String path, String text) throws IOException {
    Path file = tmp.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    return file;
  }

  /** The tags of each namespace, as {@code name} or {@code name (Owner)} where the owner is not the namespace's first taglib. */
  private static Map<String, Set<String>> names(Map<String, Map<String, Taglibs.Tag>> all, boolean withBuiltIns) {
    Map<String, Set<String>> names = new TreeMap<>();
    all.forEach((namespace, tags) -> tags.values().stream().filter(tag -> withBuiltIns || tag.owner != null)
        .forEach(tag -> names.computeIfAbsent(namespace, ns -> new TreeSet<>()).add(tag.name)));
    return names;
  }

  private static Set<String> owned(Map<String, Map<String, Taglibs.Tag>> all, String namespace, String owner) {
    return all.getOrDefault(namespace, Map.of()).values().stream().filter(tag -> owner.equals(tag.owner)).map(tag -> tag.name)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  // Grails 8: tags are methods, in Java 21 class files

  /** The taglib classes of a jar, as the workspace lists them: binary name to the jar. */
  private static Map<String, String> taglibClassesIn(Path jar) throws IOException {
    Map<String, String> found = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      for (ZipEntry entry : Collections.list(zip.entries())) {
        String name = entry.getName();
        if (name.endsWith("TagLib.class") && !name.contains("$") && !name.startsWith("META-INF/")) {
          found.put(name.substring(0, name.length() - ".class".length()).replace('/', '.'), jar.toString());
        }
      }
    }
    return found;
  }

  /** Grails 8's own index of a jar's taglibs, written by its build from TagDiscoveryRules: class to namespace and tags. */
  private static Map<String, Properties> descriptors(Path jar) throws IOException {
    Map<String, Properties> found = new TreeMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.getName().startsWith("META-INF/grails/taglibs/") && entry.getName().endsWith("TagLib.properties")) {
          Properties properties = new Properties();
          try (InputStream in = zip.getInputStream(entry)) {
            properties.load(in);
          }
          found.put(properties.getProperty("class"), properties);
        }
      }
    }
    return found;
  }

  /** Whether this Groovy's ASM reads Java 21 class files (version 65): Grails 8 is compiled for Java 21. */
  private static boolean readsJava21() {
    try {
      groovyjarjarasm.asm.Opcodes.class.getField("V21");
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  @Test
  void grails8TagsAreTheMethodsGrailsCountsAsTags() throws Exception {
    Map<String, String> classes = taglibClassesIn(GRAILS8);
    Map<String, Properties> descriptors = descriptors(GRAILS8);
    assertEquals(new TreeSet<>(classes.keySet()), descriptors.keySet(), "Grails 8 indexes each of its taglibs");
    Map<String, Map<String, Taglibs.Tag>> all = taglibs(grails8()).all(lookup(classes, Map.of(), List.of()));
    if (!readsJava21()) {
      // an older Groovy's ASM (Groovy 2.5.14's stops at Java 15): the tags are unknown, and the user is told once
      assertEquals(Map.of(), names(all, false), GroovySystem.getVersion());
      assertEquals(1, shown.size(), shown.toString());
      assertTrue(shown.get(0).startsWith("the tags of " + classes.size() + " taglib classes are unknown: "), shown.get(0));
      assertTrue(shown.get(0).contains("Unsupported class file major version 65"), shown.get(0));
      return;
    }
    assertEquals(List.of(), shown);
    for (Map.Entry<String, Properties> descriptor : descriptors.entrySet()) {
      Set<String> expected = new TreeSet<>(List.of(descriptor.getValue().getProperty("tags").split(",")));
      assertEquals(expected, owned(all, descriptor.getValue().getProperty("namespace"), descriptor.getKey()), descriptor.getKey());
    }
    Set<String> g = all.get("g").keySet();
    assertTrue(g.containsAll(List.of("form", "textField", "message")), g.toString());
    assertFalse(g.contains("messageImpl") || g.contains("raw") || g.contains("out"), g.toString());
  }

  // project taglibs: closures on every version, methods from Grails 8

  private static final String SHAPES = "package demo\n"
      + "import grails.gsp.NotATag\n"
      + "import grails.gsp.Tag\n"
      + "class ShapeTagLib {\n"
      + "  static namespace = 'shape'\n"
      + "  def closure = { attrs -> }\n"
      + "  Closure typed = { attrs, body -> }\n"
      + "  def attrsOnly(Map attrs) {}\n"
      + "  def attrsAndBody(Map attrs, Closure body) {}\n"
      + "  def bodyOnly(Closure body) {}\n"
      + "  def optionalBody(Map attrs, Closure body = null) {}\n"
      + "  @Tag def annotated(String text) {}\n"
      + "  @NotATag def helper(Map attrs) {}\n"
      + "  private def hidden(Map attrs) {}\n"
      + "  static def shared(Map attrs) {}\n"
      + "  def untyped(attrs) {}\n"
      + "  def extra(Map attrs, String text) {}\n"
      + "  def getThing() {}\n"
      + "  def setThing(Map attrs) {}\n"
      + "  def raw(Map attrs) {}\n"
      + "  def withCodec(Map attrs, Closure body) {}\n"
      + "  def methodMissing(String name, args) {}\n"
      + "}\n";

  private SourceLookup project(String... files) throws IOException {
    Path app = tmp.resolve("app/grails-app");
    for (int i = 0; i < files.length; i += 2) {
      write("app/grails-app/taglib/" + files[i], files[i + 1]);
    }
    return lookup(Map.of(), Map.of(), List.of(app.toString()));
  }

  @Test
  void beforeGrails8OnlyClosuresAreTags() throws Exception {
    SourceLookup lookup = project("demo/ShapeTagLib.groovy", SHAPES);
    assertEquals(Map.of("shape", Set.of("closure", "typed")), names(taglibs(grails7()).all(lookup), false));
  }

  @Test
  void onGrails8MethodsGrailsCountsAsTagsAreTags() throws Exception {
    SourceLookup lookup = project("demo/ShapeTagLib.groovy", SHAPES);
    assertEquals(Map.of("shape", Set.of("closure", "typed", "attrsOnly", "attrsAndBody", "bodyOnly", "optionalBody", "annotated")),
        names(taglibs(grails8()).all(lookup), false));
  }

  // inherited tags: a superclass's closures, a trait's closures and (Grails 8) methods

  private static final String BASE = "package demo\n"
      + "class Base {\n"
      + "  /** From the base class.\n   * @attr from REQUIRED where */\n"
      + "  def inherited = { attrs -> }\n"
      + "  def baseMethod(Map attrs) {}\n"
      + "}\n";

  private static final String HELPERS = "package demo.helpers\n"
      + "trait Helpers {\n"
      + "  def fromTrait = { attrs -> }\n"
      + "  def traitMethod(Map attrs) {}\n"
      + "}\n";

  private static final String CHILD = "package demo\n"
      + "import demo.helpers.*\n"
      + "class ChildTagLib extends Base implements Helpers, Serializable {\n"
      + "  static namespace = 'kid'\n"
      + "  def own = { attrs -> }\n"
      + "}\n";

  @Test
  void tagsInheritedFromProjectClassesAndTraits() throws Exception {
    Path base = write("app/src/main/groovy/demo/Base.groovy", BASE);
    Path helpers = write("app/src/main/groovy/demo/helpers/Helpers.groovy", HELPERS);
    write("app/grails-app/taglib/demo/ChildTagLib.groovy", CHILD);
    SourceLookup lookup = lookup(Map.of(), Map.of("demo.Base", base.toString(), "demo.helpers.Helpers", helpers.toString()),
        List.of(tmp.resolve("app/grails-app").toString()));
    Taglibs.Index grails7 = taglibs(grails7()).index(lookup);
    assertEquals(Map.of("kid", Set.of("own", "inherited", "fromTrait")), names(grails7.tags, false));
    assertTrue(grails7.complete("kid"));
    Taglibs.Index grails8 = taglibs(grails8()).index(lookup);
    // a trait's methods are woven into the taglib, a superclass's are not: Grails 8 dispatches to declared methods
    assertEquals(Map.of("kid", Set.of("own", "inherited", "fromTrait", "traitMethod")), names(grails8.tags, false));
    Taglibs.Tag inherited = grails8.tags.get("kid").get("inherited");
    assertEquals("demo.Base", inherited.owner);
    assertEquals(base.toString(), inherited.docs().location.path);
    assertEquals("from", inherited.docs().attributes.get(0).name);
    assertEquals("demo.helpers.Helpers", grails8.tags.get("kid").get("traitMethod").owner);
    assertEquals(List.of(), shown);
  }

  /** A superclass no source and no class file declares: its tags are unknown, so the namespace is not complete. */
  @Test
  void aSuperclassThatCannotBeFoundLeavesItsNamespaceIncomplete() throws Exception {
    SourceLookup lookup = project("demo/OddTagLib.groovy", "package demo\nclass OddTagLib extends Missing {\n  static namespace = 'odd'\n  def own = { attrs -> }\n}\n",
        "demo/EvenTagLib.groovy", "package demo\nclass EvenTagLib {\n  static namespace = 'even'\n  def own = { attrs -> }\n}\n");
    Taglibs.Index index = taglibs(grails7()).index(lookup);
    assertEquals(Map.of("even", Set.of("own"), "odd", Set.of("own")), names(index.tags, false));
    assertFalse(index.complete("odd"));
    assertTrue(index.complete("even"));
  }

  /** The page gets no "Unknown tag" hint for a namespace that may have more tags, and keeps it for the others. */
  @Test
  void noUnknownTagHintInAnIncompleteNamespace() throws Exception {
    SourceLookup lookup = project("demo/OddTagLib.groovy", "package demo\nclass OddTagLib extends Missing {\n  static namespace = 'odd'\n  def own = { attrs -> }\n}\n",
        "demo/EvenTagLib.groovy", "package demo\nclass EvenTagLib {\n  static namespace = 'even'\n  def own = { attrs -> }\n}\n");
    GroovyAnalyzer analyzer = new GroovyAnalyzer(grails7(), (show, message) -> (show ? shown : logged).add(message));
    CompileResult result;
    try {
      result = analyzer.compile(new CompileRequest(tmp.resolve("app/grails-app/views/a.gsp").toString(), "<odd:inherited/>\n<even:nope/>\n", lookup, () -> false));
    } finally {
      analyzer.close();
    }
    assertEquals(List.of("Unknown tag <even:nope>: no taglib of namespace 'even' declares it"),
        result.diagnostics.stream().map(d -> d.message).collect(Collectors.toList()));
  }

  /** On Grails 8 a page's method tags are known: no "Unknown tag" hint. */
  @Test
  void aGrails8PageKnowsMethodTags() throws Exception {
    SourceLookup lookup = project("demo/ShapeTagLib.groovy", SHAPES);
    GroovyAnalyzer analyzer = new GroovyAnalyzer(grails8(), (show, message) -> (show ? shown : logged).add(message));
    CompileResult result;
    try {
      result = analyzer.compile(new CompileRequest(tmp.resolve("app/grails-app/views/a.gsp").toString(), "<shape:attrsOnly/>\n<shape:helper/>\n", lookup, () -> false));
    } finally {
      analyzer.close();
    }
    assertEquals(List.of("Unknown tag <shape:helper>: no taglib of namespace 'shape' declares it"),
        result.diagnostics.stream().map(d -> d.message).collect(Collectors.toList()));
  }

  /** Jar taglibs compiled by Groovy: a superclass in the same jar, one only on the module's class path, a woven trait. */
  @Test
  void jarTaglibsInheritTags() throws Exception {
    Path parent = compile("parent-classes", Map.of("Parent.groovy", "package lib\nclass Parent { Closure fromLoader = { attrs -> } }\n"));
    Path classes = compile("lib-classes", Map.of(
        "Base.groovy", "package lib\nclass Base extends Parent { Closure fromJar = { attrs -> } }\n",
        "KidTagLib.groovy", "package lib\nclass KidTagLib extends Base {\n  static namespace = 'kid'\n  Closure own = { attrs -> }\n}\n",
        "Helpers.groovy", "package lib\ntrait Helpers {\n  Closure fromTrait = { attrs -> }\n  def traitMethod(Map attrs) {}\n}\n",
        "WovenTagLib.groovy", "package lib\nclass WovenTagLib implements Helpers {\n  static namespace = 'woven'\n}\n",
        "LostTagLib.groovy", "package lib\nclass LostTagLib extends Parent {\n  static namespace = 'lost'\n  Closure own = { attrs -> }\n}\n"), parent);
    // Parent is on the others' class path only: the jar does not hold it
    Path jar = jar(classes, "lib.jar");
    Map<String, String> taglibs = Map.of("lib.KidTagLib", jar.toString(), "lib.WovenTagLib", jar.toString(), "lib.LostTagLib", jar.toString());
    Taglibs.Index withParent = taglibs(loader(parent)).index(lookup(taglibs, Map.of(), List.of()));
    assertEquals(Map.of("kid", Set.of("own", "fromJar", "fromLoader"), "lost", Set.of("own", "fromLoader"), "woven", Set.of("fromTrait")),
        names(withParent.tags, false));
    assertTrue(withParent.complete("kid") && withParent.complete("lost") && withParent.complete("woven"));
    Taglibs.Index withoutParent = taglibs(grails7()).index(lookup(taglibs, Map.of(), List.of()));
    assertEquals(Map.of("kid", Set.of("own", "fromJar"), "lost", Set.of("own"), "woven", Set.of("fromTrait")), names(withoutParent.tags, false));
    assertFalse(withoutParent.complete("kid") || withoutParent.complete("lost"));
    assertTrue(withoutParent.complete("woven"));
    Taglibs.Index grails8 = taglibs(grails8()).index(lookup(taglibs, Map.of(), List.of()));
    assertEquals(Set.of("fromTrait", "traitMethod"), names(grails8.tags, false).get("woven"));
    assertEquals(List.of(), shown);
  }
}
