package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationUnit;

import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;

/**
 * Completion of the name on an import line, read from the line's text: a line being typed often does not parse, and on
 * Groovy 2.5 an import node may carry the next import's position. The names are the packages directly below the
 * package typed so far and its classes, of the project ({@link SourceLookup#types()}) and of the class path's jars and
 * the JDK, plus the project's classes deeper down or by simple name, as upstream offered them; each item replaces the
 * whole name typed, with its qualified name. On a static import naming a class the
 * compile resolved, the class's static members instead ({@link Completions#staticMembers}).
 */
final class Imports {
  /** {@code import a.b.C}, {@code import static a.b.C.m}: the line up to the position. */
  private static final Pattern LINE = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w$.]*)$");
  /** As many items as {@link Completions} offers at most. */
  private static final int MAX_ITEMS = 1000;

  final boolean isStatic;
  /** What is typed of the name. */
  final String name;
  /** The column the name starts at. */
  final int start;

  private Imports(boolean isStatic, String name, int start) {
    this.isStatic = isStatic;
    this.name = name;
    this.start = start;
  }

  /** The import being typed, from the text of the line before the position; null when the line is not an import's name. */
  static Imports of(String typed) {
    Matcher matcher = LINE.matcher(typed);
    return matcher.matches() ? new Imports(matcher.group(1) != null, matcher.group(2), matcher.start(2)) : null;
  }

  /** The name up to its last dot: the package whose names are offered, or a static import's class. */
  String qualifier() {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(0, dot);
  }

  /** The name after its last dot. */
  String last() {
    return name.substring(name.lastIndexOf('.') + 1);
  }

  /** Packages and classes continuing the name, replacing it from its start to the position. */
  CompletionList names(SourceLookup lookup, int line, int column) {
    String parent = qualifier();
    String part = last();
    String prefix = parent.isEmpty() ? "" : parent + ".";
    Range replace = new Range(line, start, line, column);
    Set<String> packages = new TreeSet<>();
    List<Completion> classes = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Symbol type : lookup.types()) {
      int dot = type.name.lastIndexOf('.');
      if (dot < 0) {
        continue; // the default package cannot be imported from
      }
      String typePackage = type.name.substring(0, dot);
      String simpleName = type.name.substring(dot + 1);
      if (typePackage.length() > prefix.length() && typePackage.startsWith(prefix)) {
        int end = typePackage.indexOf('.', prefix.length());
        packages.add(end < 0 ? typePackage : typePackage.substring(0, end));
      }
      // the project's classes also anywhere below the name, or by simple name: import Gad → other.Gadget
      if ((type.name.startsWith(name) || simpleName.startsWith(name)) && seen.add(type.name)) {
        classes.add(new Completion(type.name, kind(type.kind), null, simpleName.startsWith(part) ? simpleName : null, replace, List.of(), -1));
      }
    }
    packages.addAll(lookup.classpathPackages(parent));
    if (!parent.isEmpty()) {
      for (String className : lookup.classpathClasses(parent)) {
        if (className.startsWith(prefix + part) && seen.add(className)) {
          classes.add(new Completion(className, Completion.Kind.CLASS, null, className.substring(prefix.length()), replace, List.of(), -1));
        }
      }
    }
    List<Completion> items = new ArrayList<>();
    for (String found : packages) {
      if (found.startsWith(prefix + part)) {
        items.add(new Completion(found, Completion.Kind.PACKAGE, null, null, replace, List.of(), -1));
      }
    }
    items.addAll(classes);
    return items.size() > MAX_ITEMS ? new CompletionList(items.subList(0, MAX_ITEMS), true) : new CompletionList(items, false);
  }

  /** The class of a static import of the compile named {@code className}, as resolved; null when it imports none. */
  static ClassNode staticallyImported(CompilationUnit unit, String className) {
    for (ModuleNode module : unit.getAST().getModules()) {
      List<ImportNode> imports = new ArrayList<>(module.getStaticImports().values());
      imports.addAll(module.getStaticStarImports().values());
      for (ImportNode imported : imports) {
        if (imported.getType() != null && imported.getType().getName().equals(className)) {
          return imported.getType();
        }
      }
    }
    return null;
  }

  private static Completion.Kind kind(Symbol.Kind kind) {
    return kind == Symbol.Kind.INTERFACE ? Completion.Kind.INTERFACE : kind == Symbol.Kind.ENUM ? Completion.Kind.ENUM : Completion.Kind.CLASS;
  }
}
