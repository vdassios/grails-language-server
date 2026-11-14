package io.github.vdassios.grailsls.analysis;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.GenericsVisitor;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import groovy.lang.GroovyClassLoader;

/**
 * Groovy checks type argument bounds ({@code GenericsVisitor}, semantic analysis) before AST transformations run: a
 * trait that extends a trait still names it as its superclass, which the check does not follow, and GORM adds
 * {@code GormEntity} to a domain class only at canonicalization. Gradle compiles against the class files of other
 * modules and source sets, where both are done; the server pulls those classes in as sources, so valid code failed
 * the check (groovyc fails the same way with all the files in one compile). Checking the document's classes again
 * once its compile is done tells which bound errors still hold.
 */
final class TypeBounds {
  private static final String BOUND_ERROR = "is not a valid substitute for the bounded parameter";

  private TypeBounds() {}

  /** The document's bound errors that a check after its last phase no longer reports. */
  static Set<Object> stale(SourceUnit document, ErrorCollector errors, CompilerConfiguration config, GroovyClassLoader loader) {
    Set<Object> reported = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Object message : list(errors)) {
      SyntaxException cause = boundError(message);
      if (cause != null && document.getName().equals(cause.getSourceLocator())) {
        reported.add(message);
      }
    }
    if (reported.isEmpty() || document.getAST() == null) {
      return Collections.emptySet();
    }
    ErrorCollector again = new ErrorCollector(config);
    try {
      GenericsVisitor visitor = new GenericsVisitor(new SourceUnit(document.getName(), "", config, loader, again));
      for (ClassNode type : document.getAST().getClasses()) {
        // as Groovy does: synthetic classes are not checked
        if (!type.isSynthetic()) {
          visitor.visitClass(type);
        }
      }
    } catch (RuntimeException e) {
      // the transformed tree did not check: keep every error
      return Collections.emptySet();
    }
    Set<String> still = new HashSet<>();
    for (Object message : list(again)) {
      SyntaxException cause = boundError(message);
      if (cause != null) {
        still.add(key(cause));
      }
    }
    reported.removeIf(message -> still.contains(key(boundError(message))));
    return reported;
  }

  @SuppressWarnings("unchecked") // Groovy 2.5 returns raw lists
  private static List<Object> list(ErrorCollector errors) {
    List<Object> list = errors.getErrors();
    return list != null ? list : Collections.emptyList();
  }

  private static SyntaxException boundError(Object message) {
    if (message instanceof SyntaxErrorMessage) {
      SyntaxException cause = ((SyntaxErrorMessage) message).getCause();
      if (cause.getOriginalMessage().contains(BOUND_ERROR)) {
        return cause;
      }
    }
    return null;
  }

  private static String key(SyntaxException cause) {
    return cause.getStartLine() + ":" + cause.getStartColumn() + ":" + cause.getOriginalMessage().trim();
  }
}
