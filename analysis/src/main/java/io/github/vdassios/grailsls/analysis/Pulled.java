package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * The sources a compile of a document pulled in, which the next compile of the document reuses instead of parsing and
 * resolving them again: in a module that pulls in a sibling module's sources, they are most of each compile's work.
 * Groovy skips every phase operation on a source already past the unit's phase, so the reused sources are added to
 * the new unit as they are, their modules with them. They are reused only when a compile of them now would give the
 * same classes:
 *
 * <ul>
 * <li>the lookup answers as it did, and none of the files changed ({@link Inputs#same});
 * <li>the same configuration script applies (its imports apply to them);
 * <li>the new compile ends in the same phase, so it runs no operation on them;
 * <li>none of them names a class the document declares, in this compile or the donor's: their references to it are to
 * the donor's document, and Groovy resolves such names through the unit, which {@link Inputs} does not see.
 * </ul>
 *
 * <p>The compile ran every phase, and Groovy reported only syntax errors, each with its file: those of the pulled-in
 * files are given again with each compile that reuses them ({@link #diagnostics}).
 */
final class Pulled {
  /** The sources, at the donor's last phase. */
  final List<SourceUnit> sources;
  /** The diagnostics of other files than the document. */
  final List<Diagnostic> diagnostics;
  /** The configuration script's class, or null. */
  final Object script;
  /** The simple names of the classes the donor's document declares. */
  private final Set<String> declared;

  private Pulled(List<SourceUnit> sources, List<Diagnostic> diagnostics, Object script, Set<String> declared) {
    this.sources = sources;
    this.diagnostics = diagnostics;
    this.script = script;
    this.declared = declared;
  }

  /** The pulled-in sources of a compile that ran every phase, or null when Groovy reported more than syntax errors. */
  @SuppressWarnings("unchecked") // Groovy 2.5 returns raw lists
  static Pulled of(CompilationUnit unit, SourceUnit document, String path, List<Diagnostic> diagnostics, Object script) {
    ErrorCollector collector = unit.getErrorCollector();
    if (collector.getWarningCount() > 0) {
      return null;
    }
    for (Object error : collector.getErrors() == null ? List.of() : (List<Object>) collector.getErrors()) {
      if (!(error instanceof SyntaxErrorMessage) || ((SyntaxErrorMessage) error).getCause().getSourceLocator() == null) {
        return null;
      }
    }
    List<SourceUnit> sources = new ArrayList<>();
    for (Iterator<SourceUnit> each = unit.iterator(); each.hasNext();) {
      SourceUnit source = each.next();
      if (source != document) {
        sources.add(source);
      }
    }
    List<Diagnostic> elsewhere = new ArrayList<>();
    for (Diagnostic diagnostic : diagnostics) {
      if (!diagnostic.path.equals(path)) {
        elsewhere.add(diagnostic);
      }
    }
    return new Pulled(Collections.unmodifiableList(sources), elsewhere, script, declared(document.getAST()));
  }

  /** Whether a compile of the document may start with the donor's sources: it pulled some in, and none changed. */
  static boolean reusable(GroovyAnalyzer.Kept donor, SourceLookup lookup) {
    if (donor == null || donor.pulled == null || donor.pulled.sources.isEmpty()) {
      return false;
    }
    for (SourceUnit source : donor.pulled.sources) {
      if (source.getPhase() != donor.phase || !source.isPhaseComplete()) {
        return false;
      }
    }
    return donor.inputs.same(lookup);
  }

  /**
   * After the document is converted: whether the compile, ending in phase {@code last}, may go on with the sources,
   * compiled to {@code phase}.
   */
  boolean fits(SourceUnit document, int last, int phase) {
    if (last != phase) {
      return false;
    }
    Set<String> names = declared(document.getAST());
    names.addAll(declared);
    for (SourceUnit source : sources) {
      String text = StringReaderSourceWithURI.text(source);
      if (text == null || names.stream().anyMatch(name -> named(text, name))) {
        return false;
      }
    }
    return true;
  }

  /** The simple names of a module's classes, nested ones included, and each part of a name with a dollar sign. */
  private static Set<String> declared(ModuleNode module) {
    Set<String> names = new HashSet<>();
    if (module != null) {
      for (ClassNode type : module.getClasses()) {
        add(type, names);
      }
    }
    return names;
  }

  private static void add(ClassNode type, Set<String> names) {
    String name = type.getNameWithoutPackage();
    names.add(name);
    for (String part : name.split("\\$")) {
      if (!part.isEmpty() && Character.isJavaIdentifierStart(part.charAt(0))) {
        names.add(part);
      }
    }
    for (Iterator<InnerClassNode> inner = type.getInnerClasses(); inner.hasNext();) {
      add(inner.next(), names);
    }
  }

  /** Whether {@code name} occurs in the text as a word: anywhere, comments and strings included. */
  private static boolean named(String text, String name) {
    for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
      int end = at + name.length();
      if ((at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1))) && (end == text.length() || !Character.isJavaIdentifierPart(text.charAt(end)))) {
        return true;
      }
    }
    return false;
  }
}
