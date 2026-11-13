package net.prominic.groovyls.compiler.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.junit.jupiter.api.Test;

import net.prominic.groovyls.compiler.control.io.StringReaderSourceWithURI;

class ASTNodeVisitorTest {
  /**
   * Groovy 2.5.14 (Grails 4) hashes an {@code ASTNode} by its position alone, so the nodes AST transformations add
   * without one all collide; a lookup keyed by that hash took up to 136 s to build for one Grails controller. The test
   * Groovy versions hash by identity, so these nodes override it the way 2.5.14 does.
   */
  @Test
  void theNodeLookupGoesByIdentityNotByTheNodesHashCode() {
    AtomicInteger hashCodes = new AtomicInteger();
    CompilerConfiguration config = new CompilerConfiguration();
    CompilationUnit unit = new CompilationUnit(config);
    URI uri = URI.create("file:/src/A.groovy");
    unit.addSource(new SourceUnit("A.groovy", new StringReaderSourceWithURI("class A {\n  def run() {\n  }\n}\n", uri, config), config,
        unit.getClassLoader(), unit.getErrorCollector()));
    unit.compile(Phases.CANONICALIZATION);

    ClassNode a = unit.getAST().getClasses().stream().filter(c -> c.getName().equals("A")).findFirst().orElseThrow();
    BlockStatement body = (BlockStatement) a.getMethods("run").get(0).getCode();
    List<ExpressionStatement> added = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      ConstantExpression constant = new ConstantExpression(i) {
        @Override
        public int hashCode() {
          hashCodes.incrementAndGet();
          return 0; // no position: what Groovy 2.5.14 gives every such node
        }
      };
      ExpressionStatement statement = new ExpressionStatement(constant);
      body.addStatement(statement);
      added.add(statement);
    }

    ASTNodeVisitor visitor = new ASTNodeVisitor();
    visitor.visitCompilationUnit(unit);
    for (ExpressionStatement statement : added) {
      assertSame(statement, visitor.getParent(statement.getExpression()));
      assertEquals(uri, visitor.getURI(statement.getExpression()));
    }
    assertEquals(0, hashCodes.get(), "the lookup hashed the nodes");
  }
}
