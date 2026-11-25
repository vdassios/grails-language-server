package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.CompilationUnitAware;

/**
 * The compiler configuration script of a source set (Gradle's {@code groovyOptions.configurationScript}), run on each
 * compile's configuration the way {@code groovyc --configscript} and Gradle run it: with the configuration bound as
 * {@code configuration}, and with {@code CompilerCustomizationBuilder}'s static methods ({@code withConfig}) imported.
 * The script is compiled once, and again when its file changes. A script that cannot be compiled or run is shown to
 * the user once, and files compile without it. A script that is not there is only logged: Grails 7 sets a script on
 * each Groovy compile task, and writes it only when the task runs.
 */
final class ConfigurationScript {
  private final Path path;
  private final ClassLoader parent;
  private final Reports reports;
  private FileTime compiledAt;
  private Class<?> compiled;

  ConfigurationScript(String path, ClassLoader parent, Reports reports) {
    this.path = Path.of(path);
    this.parent = parent;
    this.reports = reports;
  }

  /**
   * Runs the script on {@code config}. Of the customizers it adds, those of a phase after canonicalization (the
   * static type checker of {@code ast(CompileStatic)}) apply to {@code documentPath} only: the classes a compile pulls
   * in from source are there for their declarations, and may belong to a module the script is not for. Returns the
   * last phase those customizers run in, or -1 when the script failed and {@code config} must be built again.
   */
  int apply(CompilerConfiguration config, String documentPath) {
    Class<?> script = compiled();
    if (script == null) {
      return -1;
    }
    List<CompilationCustomizer> customizers = config.getCompilationCustomizers();
    int before = customizers.size();
    try {
      Binding binding = new Binding();
      binding.setVariable("configuration", config);
      Script run = InvokerHelper.createScript(script, binding);
      run.run();
    } catch (RuntimeException | LinkageError e) {
      failed("run", e);
      return -1;
    }
    int last = Phases.CANONICALIZATION;
    for (int i = before; i < customizers.size(); i++) {
      CompilationCustomizer customizer = customizers.get(i);
      int phase = customizer.getPhase().getPhaseNumber();
      if (phase > Phases.CANONICALIZATION) {
        customizers.set(i, new DocumentOnly(customizer, documentPath));
        last = Math.max(last, phase);
      }
    }
    return last;
  }

  /** The script's class, compiled again when the file changed; null when it cannot be read or compiled (reported). */
  private Class<?> compiled() {
    FileTime modified;
    try {
      modified = Files.getLastModifiedTime(path);
    } catch (IOException e) {
      reports.log("configuration script read", "the Groovy configuration script " + path + " (groovyOptions.configurationScript) cannot be read: " + e
          + ". Files compile without it.");
      return null;
    }
    if (!modified.equals(compiledAt)) {
      compiledAt = modified;
      compiled = null;
      CompilerConfiguration scriptConfig = new CompilerConfiguration();
      ImportCustomizer imports = new ImportCustomizer();
      imports.addStaticStars("org.codehaus.groovy.control.customizers.builder.CompilerCustomizationBuilder");
      scriptConfig.addCompilationCustomizers(imports);
      try {
        compiled = new GroovyClassLoader(parent, scriptConfig).parseClass(path.toFile());
      } catch (IOException | RuntimeException | LinkageError e) {
        failed("compile", e);
      }
    }
    return compiled;
  }

  private void failed(String what, Throwable e) {
    String message = String.valueOf(e.getMessage() != null ? e.getMessage() : e).trim();
    reports.show("configuration script " + what, "cannot " + what + " the Groovy configuration script " + path + " (groovyOptions.configurationScript): "
        + message + ". Files compile without it.");
  }

  /** A customizer that runs on the document's classes only. */
  private static final class DocumentOnly extends CompilationCustomizer implements CompilationUnitAware {
    private final CompilationCustomizer customizer;
    private final String documentPath;

    DocumentOnly(CompilationCustomizer customizer, String documentPath) {
      super(customizer.getPhase());
      this.customizer = customizer;
      this.documentPath = documentPath;
    }

    @Override
    public void setCompilationUnit(CompilationUnit unit) {
      if (customizer instanceof CompilationUnitAware) {
        ((CompilationUnitAware) customizer).setCompilationUnit(unit);
      }
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
      if (documentPath.equals(source.getName())) {
        customizer.call(source, context, classNode);
      }
    }
  }
}
