package io.github.vdassios.grailsls.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Where the classes a compile needs are declared. Owned by the server: its declaration index and open documents. */
public interface SourceLookup {
  /** File declaring the class with this binary name ({@code pkg.Outer$Inner}), or null when no source declares it. */
  String fileOf(String className);

  /** Current text of the document open at this path, or null to read the file. */
  String openText(String path);

  /**
   * Top-level classes declared in the sources this lookup answers from, for completion of classes not imported yet
   * ({@link Symbol#name} is the qualified name). May be incomplete while the server is still indexing.
   */
  default List<Symbol> types() {
    return List.of();
  }

  /**
   * The packages directly below a package that lead to classes in the class path's jars or the JDK's exported
   * packages, qualified ({@code java.util.concurrent} below {@code java.util}); the top-level ones below {@code ""}. For
   * import completion; the project's own packages are those of {@link #types()}.
   */
  default List<String> classpathPackages(String parent) {
    return List.of();
  }

  /** The top-level classes of a package in the class path's jars or the JDK, qualified ({@code java.util.ArrayList}). */
  default List<String> classpathClasses(String packageName) {
    return List.of();
  }

  /**
   * Binary names of the top-level classes with this simple name that the sources declare, in search order; for names
   * Grails resolves by convention, such as the bean {@code bookService} to the class {@code BookService}.
   */
  default List<String> classesNamed(String simpleName) {
    return List.of();
  }

  /**
   * File holding the source of a class the compile reads from a class file, by the binary name of its top-level class:
   * a {@code .java} file of the project, or a file extracted from a library's sources jar or the JDK's
   * {@code src.zip}. Null when no such source is on disk.
   */
  default String classSource(String className) {
    return null;
  }

  /**
   * The {@code grails-app} directories of the project's modules, the compiled document's own module first; for names
   * Grails resolves by convention: views, templates, layouts and controllers.
   */
  default List<String> grailsAppDirs() {
    return List.of();
  }

  /**
   * The classes in the class path's jars, and in jars only on the run-time class path, whose simple name ends in
   * {@code TagLib}: the taglibs of Grails and its plugins, for GSP tags. Binary name to the jar's path; a jar only on the
   * run-time class path is not in the compile's loader, so its class files are read from the jar. The project's own
   * taglibs are read from source.
   */
  default Map<String, String> taglibClasses() {
    return Map.of();
  }

  /**
   * Files below {@code grails-app} that jars of the class path and of the run-time class path supply, so that a
   * name Grails resolves by convention may come from a plugin: the views a jar lists as precompiled in its
   * {@code gsp/views.properties} ({@code views/layouts/main.gsp}), and the files below a jar's {@code META-INF/assets/},
   * by their path below an asset directory ({@code assets/application.js}).
   */
  default Set<String> pluginFiles() {
    return Set.of();
  }
}
