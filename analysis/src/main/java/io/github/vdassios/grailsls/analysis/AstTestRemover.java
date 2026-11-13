package io.github.vdassios.grailsls.analysis;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Removes {@code @ASTTest} before semantic analysis collects it: its closure is code from the file, run by the
 * compiler. The project's other AST transformations still run; that is what gives Grails members.
 */
final class AstTestRemover extends CompilationCustomizer {
  AstTestRemover() {
    super(CompilePhase.CONVERSION);
  }

  @Override
  public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
    new ClassCodeVisitorSupport() {
      @Override
      protected SourceUnit getSourceUnit() {
        return source;
      }

      @Override
      public void visitAnnotations(AnnotatedNode node) {
        node.getAnnotations().removeIf(annotation -> {
          String name = annotation.getClassNode().getName();
          return name.equals("ASTTest") || name.equals("groovy.transform.ASTTest");
        });
        super.visitAnnotations(node);
      }
    }.visitClass(classNode);
  }
}
