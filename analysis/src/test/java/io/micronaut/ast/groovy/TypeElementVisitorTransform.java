package io.micronaut.ast.groovy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

/**
 * Test stand-in for Micronaut's global transformation of that name: like Micronaut 4, it reads each annotation member
 * through the annotation type's method of that name, so an alias that was not expanded yet (no methods) makes it fail.
 * Registered only by tests that serve a services file for it.
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class TypeElementVisitorTransform implements ASTTransformation {
  public static final AtomicInteger METHODS_READ = new AtomicInteger();

  @Override
  public void visit(ASTNode[] nodes, SourceUnit source) {
    ModuleNode module = (ModuleNode) nodes[0];
    for (ClassNode type : module.getClasses()) {
      for (MethodNode method : type.getMethods()) {
        read(method);
        METHODS_READ.incrementAndGet();
      }
    }
  }

  private static void read(AnnotatedNode node) {
    for (AnnotationNode annotation : node.getAnnotations()) {
      for (String member : annotation.getMembers().keySet()) {
        List<MethodNode> methods = annotation.getClassNode().getMethods(member);
        methods.get(0);
      }
    }
  }
}
