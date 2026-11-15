package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.ClassNodeResolver;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Finds project classes in source through the server's declaration index, and adds each declaring file under its
 * file path. Groovy's default lookup adds a source by URL and maps {@code a.b.C} to {@code a/b/C.groovy} only; Grails
 * decides artefacts by path, so URL-named domain classes get no GORM members, and nested types, classes in a file of
 * another name and packages that differ from their directory are not found. Everything else (jars, class directories)
 * goes to the default resolver.
 *
 * <p>A class read from a class file (a built {@code .java} class, a jar) names the types of its members, and Groovy
 * resolves those through the resolver that found the class. There it takes only a converted class: for a source this
 * resolver has just added it throws {@code NoClassDefFoundError}, and a source queued from inside a transformation
 * makes Groovy 4 run the phase's transformations a second time. Such sources are recorded ({@link #early}) for the
 * analyzer, which compiles again with them in the unit from the start ({@link #add}).
 */
final class PathResolver extends ClassNodeResolver {
  private final SourceLookup lookup;
  private final Map<String, SourceUnit> sources = new HashMap<>();

  private final Set<String> early = new LinkedHashSet<>();
  /** Finds the classes of class files; the classes it reads resolve their own references through it. */
  private final ClassNodeResolver classFiles = new ClassNodeResolver() {
    @Override
    public LookupResult resolveName(String name, CompilationUnit unit) {
      LookupResult found = PathResolver.this.resolveName(name, unit);
      if (found != null && found.isSourceUnit()) {
        early.add(found.getSourceUnit().getName());
      }
      return found;
    }
  };

  PathResolver(SourceLookup lookup, SourceUnit document) {
    this.lookup = lookup;
    sources.put(document.getName(), document);
  }

  /** Sources in the unit: the document and the files added for it. */
  int sources() {
    return sources.size();
  }

  /** Paths of the sources a class file named before they were converted. */
  Set<String> early() {
    return early;
  }

  /** Adds a source file to the unit, if it is not there: before the first phase, it is converted with the document. */
  SourceUnit add(String path, CompilationUnit unit) {
    SourceUnit source = sources.get(path);
    if (source == null) {
      String text = lookup.openText(path);
      source = text != null
          ? GroovyAnalyzer.source(path, text, unit.getConfiguration(), unit.getClassLoader(), unit.getErrorCollector())
          : new SourceUnit(new File(path), unit.getConfiguration(), unit.getClassLoader(), unit.getErrorCollector());
      source = unit.addSource(source);
      sources.put(path, source);
    }
    return source;
  }

  @Override
  public LookupResult findClassNode(String name, CompilationUnit unit) {
    String path = lookup.fileOf(name);
    if (path == null) {
      return classFiles.findClassNode(name, unit);
    }
    ClassNode node = unit.getAST().getClass(name);
    if (node != null) {
      return new LookupResult(null, node);
    }
    return new LookupResult(add(path, unit), null);
  }
}
