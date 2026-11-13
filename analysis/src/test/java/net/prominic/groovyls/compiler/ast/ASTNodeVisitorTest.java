package net.prominic.groovyls.compiler.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
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

  /**
   * A Grails controller pulls in hundreds of sources; visiting them all for the first query took as long as the
   * compile. Only the document is visited; the classes of the other sources are still found, with their source.
   */
  @Test
  void aDocumentVisitIndexesTheDocumentsNodesAndOnlyTheClassesOfTheOthers() {
    CompilerConfiguration config = new CompilerConfiguration();
    CompilationUnit unit = new CompilationUnit(config);
    URI a = URI.create("file:/src/A.groovy");
    URI b = URI.create("file:/src/B.groovy");
    unit.addSource(new SourceUnit("A.groovy", new StringReaderSourceWithURI("class A {\n  def run() { new B().go() }\n}\n", a, config), config,
        unit.getClassLoader(), unit.getErrorCollector()));
    unit.addSource(new SourceUnit("B.groovy", new StringReaderSourceWithURI("class B {\n  def go() { 1 }\n  static class Inner {}\n}\n", b, config),
        config, unit.getClassLoader(), unit.getErrorCollector()));
    unit.compile(Phases.CANONICALIZATION);

    ASTNodeVisitor visitor = new ASTNodeVisitor();
    visitor.visitDocument(unit, a);

    assertTrue(visitor.getNodes(b).isEmpty(), "the other source was visited");
    assertTrue(visitor.getNodes(a).size() > 3);
    ClassNode classB = unit.getAST().getClass("B");
    ClassNode inner = unit.getAST().getClass("B$Inner");
    assertTrue(visitor.getClassNodes().contains(classB));
    assertTrue(visitor.getClassNodes().contains(inner));
    MethodNode go = classB.getMethods("go").get(0);
    assertEquals(b, visitor.getURI(classB));
    assertEquals(b, visitor.getURI(go));
    assertNull(visitor.getParent(go));
    assertNull(visitor.getURI(ClassNode.SUPER), "a class of no source in the unit");
  }
}
