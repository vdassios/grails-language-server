package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import io.github.vdassios.grailsls.api.Analyzer;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Diagnostic;

/**
 * Compiles one document per {@link CompilationUnit}, in the module loader that loaded this class: the project's own
 * Groovy, its AST transformations included. Classes the document uses come from source through {@link PathResolver},
 * or from the loader (jars, class directories, compiled Java).
 */
public final class GroovyAnalyzer implements Analyzer {
  private final Map<String, CompilationUnit> units = new ConcurrentHashMap<>();

  public GroovyAnalyzer() {
    // @Grab would download and load code at compile time
    System.setProperty("groovy.grape.enable", "false");
  }

  @Override
  public String groovyVersion() {
    return GroovySystem.getVersion();
  }

  @Override
  public CompileResult compile(CompileRequest request) {
    long start = System.nanoTime();
    units.remove(request.path);
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    config.addCompilationCustomizers(new AstTestRemover());
    GroovyClassLoader loader = new GroovyClassLoader(GroovyAnalyzer.class.getClassLoader(), config, false);
    // source lookup goes through the resolver only, never through the loader (no URL-named sources)
    loader.setResourceLoader(name -> null);
    DocumentUnit unit = new DocumentUnit(config, loader);
    SourceUnit document = unit.addSource(new SourceUnit(request.path, request.text, config, loader, unit.getErrorCollector()));
    PathResolver resolver = new PathResolver(request.lookup, document);
    unit.setClassNodeResolver(resolver);

    List<Diagnostic> diagnostics = new ArrayList<>();
    for (int phase = Phases.INITIALIZATION; phase <= Phases.CANONICALIZATION; phase++) {
      if (request.cancelled.getAsBoolean()) {
        return new CompileResult(List.of(), true, resolver.sources(), millisSince(start));
      }
      try {
        unit.compile(phase);
      } catch (CompilationFailedException e) {
        // its messages are in the error collector
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        diagnostics.add(new Diagnostic(request.path, 0, 0, 0, 0, Diagnostic.ERROR, "internal compiler error in phase " + phase + ": " + e));
        break;
      }
    }
    collect(unit.getErrorCollector(), request.path, diagnostics);
    units.put(request.path, unit);
    return new CompileResult(diagnostics, false, resolver.sources(), millisSince(start));
  }

  @Override
  public CompiledClass compiledClass(String documentPath, String className) {
    CompilationUnit unit = units.get(documentPath);
    ClassNode node = unit == null ? null : unit.getAST().getClass(className);
    if (node == null) {
      return null;
    }
    List<String> interfaces = new ArrayList<>();
    for (ClassNode type : node.getAllInterfaces()) {
      interfaces.add(type.getName());
    }
    TreeSet<String> methods = new TreeSet<>();
    for (MethodNode method : node.getMethods()) {
      methods.add(method.getName());
    }
    SourceUnit source = node.getModule() == null ? null : node.getModule().getContext();
    return new CompiledClass(node.getName(), source == null ? null : source.getName(), interfaces, new ArrayList<>(methods));
  }

  @Override
  public void forget(String documentPath) {
    units.remove(documentPath);
  }

  @Override
  public void close() {
    units.clear();
  }

  /** Groovy reports some errors once per generated node (a property's field and accessors): keep one of each. */
  @SuppressWarnings("unchecked") // Groovy 2.5 returns raw lists
  private static void collect(ErrorCollector collector, String documentPath, List<Diagnostic> out) {
    Set<String> seen = new HashSet<>();
    for (Diagnostic diagnostic : out) {
      seen.add(diagnostic.toString());
    }
    List<Object> messages = new ArrayList<>();
    if (collector.getErrors() != null) {
      messages.addAll(collector.getErrors());
    }
    int warningsFrom = messages.size();
    if (collector.getWarnings() != null) {
      messages.addAll(collector.getWarnings());
    }
    boolean syntaxError = messages.stream().anyMatch(m -> m instanceof SyntaxErrorMessage);
    for (int i = 0; i < messages.size(); i++) {
      if (syntaxError && isParserException(messages.get(i))) {
        continue;
      }
      Diagnostic diagnostic = toDiagnostic((Message) messages.get(i), documentPath, i < warningsFrom ? Diagnostic.ERROR : Diagnostic.WARNING);
      if (seen.add(diagnostic.toString())) {
        out.add(diagnostic);
      }
    }
  }

  /**
   * Groovy 4's parser, not stopped by the first error, also reports its internal ANTLR exception (line 1, no
   * position): a repeat of the syntax error already reported.
   */
  private static boolean isParserException(Object message) {
    return message instanceof ExceptionMessage && ((ExceptionMessage) message).getCause() != null
        && ((ExceptionMessage) message).getCause().getClass().getName().startsWith("groovyjarjarantlr");
  }

  private static Diagnostic toDiagnostic(Message message, String documentPath, int severity) {
    if (message instanceof SyntaxErrorMessage) {
      SyntaxException cause = ((SyntaxErrorMessage) message).getCause();
      String path = cause.getSourceLocator() != null ? cause.getSourceLocator() : documentPath;
      int line = zeroBased(cause.getStartLine());
      int column = zeroBased(cause.getStartColumn());
      int endLine = Math.max(line, zeroBased(cause.getEndLine()));
      int endColumn = zeroBased(cause.getEndColumn());
      if (endLine == line && endColumn <= column) {
        endColumn = column + 1;
      }
      return new Diagnostic(path, line, column, endLine, endColumn, cause.isFatal() ? Diagnostic.ERROR : severity, cause.getOriginalMessage().trim());
    }
    String text = message instanceof ExceptionMessage ? String.valueOf(((ExceptionMessage) message).getCause())
        : message instanceof SimpleMessage ? ((SimpleMessage) message).getMessage() : message.toString();
    text = text.trim();
    return new Diagnostic(documentPath, 0, 0, 0, 1, severity, text);
  }

  /** Groovy counts from 1 and uses -1 for unknown. */
  private static int zeroBased(int groovy) {
    return Math.max(groovy - 1, 0);
  }

  private static long millisSince(long nanos) {
    return (System.nanoTime() - nanos) / 1_000_000;
  }

  /** A unit whose errors do not stop it: the following phases still run, so one unresolved name hides nothing else. */
  private static final class DocumentUnit extends CompilationUnit {
    DocumentUnit(CompilerConfiguration config, GroovyClassLoader loader) {
      super(config, null, loader);
      this.errorCollector = new ErrorCollector(config) {
        private static final long serialVersionUID = 1L;

        @Override
        protected void failIfErrors() {}
      };
    }
  }
}
