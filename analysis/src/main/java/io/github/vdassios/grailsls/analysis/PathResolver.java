package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

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
 */
final class PathResolver extends ClassNodeResolver {
  private final SourceLookup lookup;
  private final Map<String, SourceUnit> sources = new HashMap<>();

  PathResolver(SourceLookup lookup, SourceUnit document) {
    this.lookup = lookup;
    sources.put(document.getName(), document);
  }

  /** Sources in the unit: the document and the files added for it. */
  int sources() {
    return sources.size();
  }

  @Override
  public LookupResult findClassNode(String name, CompilationUnit unit) {
    String path = lookup.fileOf(name);
    if (path == null) {
      return super.findClassNode(name, unit);
    }
    ClassNode node = unit.getAST().getClass(name);
    if (node != null) {
      return new LookupResult(null, node);
    }
    SourceUnit source = sources.get(path);
    if (source == null) {
      String text = lookup.openText(path);
      source = text != null
          ? new SourceUnit(path, text, unit.getConfiguration(), unit.getClassLoader(), unit.getErrorCollector())
          : new SourceUnit(new File(path), unit.getConfiguration(), unit.getClassLoader(), unit.getErrorCollector());
      source = unit.addSource(source);
      sources.put(path, source);
    }
    return new LookupResult(source, null);
  }
}
