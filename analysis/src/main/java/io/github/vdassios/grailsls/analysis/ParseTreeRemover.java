package io.github.vdassios.grailsls.analysis;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Drops the parse tree Groovy 4 and 5 leave on the AST. Their parser stores on every parameter of a method,
 * constructor, closure or lambda its parse tree context (whose parents reach the root of the file's tree) and a
 * modifier manager (which holds the parser and its tokens), and on a record its header's parameters; only the parser
 * reads them, while it builds the AST. Kept, they hold every parsed file's tree for as long as its AST lives: a compile
 * that pulls in a thousand sources kept a thousand trees. Groovy 2.5 and 3 store neither.
 */
final class ParseTreeRemover extends CompilationCustomizer {
  ParseTreeRemover() {
    super(CompilePhase.CONVERSION);
  }

  @Override
  public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
    classNode.removeNodeMetaData("_RECORD_HEADER");
    new ClassCodeVisitorSupport() {
      @Override
      protected SourceUnit getSourceUnit() {
        return source;
      }

      @Override
      protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        remove(node.getParameters());
        super.visitConstructorOrMethod(node, isConstructor);
      }

      @Override
      public void visitClosureExpression(ClosureExpression expression) {
        remove(expression.getParameters());
        super.visitClosureExpression(expression);
      }
    }.visitClass(classNode);
  }

  private static void remove(Parameter[] parameters) {
    if (parameters != null) {
      for (Parameter parameter : parameters) {
        parameter.removeNodeMetaData("_PARAMETER_CONTEXT");
        parameter.removeNodeMetaData("_PARAMETER_MODIFIER_MANAGER");
      }
    }
  }
}
