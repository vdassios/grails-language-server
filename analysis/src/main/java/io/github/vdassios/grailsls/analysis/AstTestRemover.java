package io.github.vdassios.grailsls.analysis;

import java.util.HashSet;
import java.util.Set;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Removes {@code @ASTTest} before semantic analysis collects it: its closure is code from the file, run by the
 * compiler. The project's other AST transformations still run; that is what gives Grails members. At this phase
 * annotation names are as written, so the names an import gives it ({@code import groovy.transform.ASTTest as T}) count.
 */
final class AstTestRemover extends CompilationCustomizer {
  AstTestRemover() {
    super(CompilePhase.CONVERSION);
  }

  @Override
  public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
    Set<String> names = new HashSet<>(Set.of("ASTTest", "groovy.transform.ASTTest"));
    for (ImportNode imported : source.getAST().getImports()) {
      if (imported.getType().getName().equals("groovy.transform.ASTTest")) {
        names.add(imported.getAlias());
      }
    }
    new ClassCodeVisitorSupport() {
      @Override
      protected SourceUnit getSourceUnit() {
        return source;
      }

      @Override
      public void visitAnnotations(AnnotatedNode node) {
        node.getAnnotations().removeIf(annotation -> names.contains(annotation.getClassNode().getName()));
        super.visitAnnotations(node);
      }
    }.visitClass(classNode);
  }
}
