package io.github.vdassios.grailsls.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import groovy.transform.CompilationUnitAware;

/**
 * Global transformations that must run after Groovy has expanded annotation aliases ({@code @AnnotationCollector}).
 * Groovy adds global transformations to a phase before the alias expansion of that phase, so they see an alias with no
 * members, such as Grails 7's {@code @GrailsCompileStatic(TypeCheckingMode.SKIP)}: Micronaut 4's type element visitors
 * then fail on every source that uses one (measured on a Grails 7 app: 71 of 74 errors). Gradle compiles such modules
 * only because they also have Java sources: a joint compile resolves and expands annotations one phase earlier.
 * These transformations are disabled by name and added back after the unit's own operations, in the same phase.
 */
final class LateTransforms {
  private static final List<String> NAMES = List.of("io.micronaut.ast.groovy.TypeElementVisitorTransform");
  private static final String SERVICES = "META-INF/services/org.codehaus.groovy.transform.ASTTransformation";

  /** The names Groovy would run as global transformations with this loader: only those are moved. */
  private final Set<String> names;

  LateTransforms(ClassLoader loader) {
    Set<String> registered = new HashSet<>();
    try {
      for (URL service : Collections.list(loader.getResources(SERVICES))) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(service.openStream(), StandardCharsets.UTF_8))) {
          for (String line = in.readLine(); line != null; line = in.readLine()) {
            registered.add(line.trim());
          }
        }
      }
    } catch (IOException e) {
      // none found: nothing is moved
    }
    registered.retainAll(NAMES);
    this.names = registered;
  }

  /** Call on the configuration before the unit is created. */
  void disable(CompilerConfiguration config) {
    if (names.isEmpty()) {
      return;
    }
    Set<String> disabled = new HashSet<>(names);
    if (config.getDisabledGlobalASTTransformations() != null) {
      disabled.addAll(config.getDisabledGlobalASTTransformations());
    }
    config.setDisabledGlobalASTTransformations(disabled);
  }

  /** Call right after the unit is created, so that its own operations of the same phase come first. */
  void add(CompilationUnit unit) {
    for (String name : names) {
      try {
        Class<?> type = Class.forName(name, false, unit.getTransformLoader());
        GroovyASTTransformation phase = type.getAnnotation(GroovyASTTransformation.class);
        ASTTransformation transform = (ASTTransformation) type.getDeclaredConstructor().newInstance();
        if (transform instanceof CompilationUnitAware) {
          ((CompilationUnitAware) transform).setCompilationUnit(unit);
        }
        unit.addPhaseOperation(new CompilationUnit.SourceUnitOperation() {
          @Override
          public void call(SourceUnit source) {
            transform.visit(new ASTNode[] {source.getAST()}, source);
          }
        }, phase.phase().getPhaseNumber());
      } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
        // cannot be loaded or created: Groovy would have reported that and skipped it
      }
    }
  }
}
