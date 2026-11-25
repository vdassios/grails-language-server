package io.github.vdassios.grailsls.analysis;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Starts a call at its name. From Groovy 3, a call without a receiver that starts a chain, {@code count(3)} in
 * {@code count(3).intValue()}, spans only its arguments. Its name keeps its own span, but semantic analysis turns a
 * call to a static method of the class into a {@code StaticMethodCallExpression}, which has no node for its name and
 * takes the call's span. Static compilation then makes a call on the class with a name at that span. Without this, no
 * node holds the name, and hover and definition on it find nothing.
 */
final class CallPositions extends CompilationCustomizer {
  CallPositions() {
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
      public void visitMethodCallExpression(MethodCallExpression call) {
        Expression name = call.getMethod();
        if (name.getLineNumber() != -1 && call.getLineNumber() != -1 && (name.getLineNumber() < call.getLineNumber()
            || name.getLineNumber() == call.getLineNumber() && name.getColumnNumber() < call.getColumnNumber())) {
          call.setLineNumber(name.getLineNumber());
          call.setColumnNumber(name.getColumnNumber());
        }
        super.visitMethodCallExpression(call);
      }
    }.visitClass(classNode);
  }
}
