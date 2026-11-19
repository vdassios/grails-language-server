package io.github.vdassios.grailsls.analysis;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;

/**
 * Tags called as methods: {@code g.link(controller: 'book')} and {@code ns.tag(...)} in Groovy and in page code, where
 * the receiver is a name no code declares that is a namespace of the taglib index ({@link Taglibs}); and in page code
 * also a call with no receiver that is no method of the page, {@code message(code: 'x')}, which Grails sends to the
 * {@code g} namespace.
 */
final class TagCalls {
  private final Supplier<Map<String, Map<String, Taglibs.Tag>>> all;
  /** In page code: calls with no receiver are tags too. */
  final boolean page;
  private Map<String, Map<String, Taglibs.Tag>> read;

  /** {@code all} gives every tag by namespace and name; read once, on first use. */
  TagCalls(Supplier<Map<String, Map<String, Taglibs.Tag>>> all, boolean page) {
    this.all = all;
    this.page = page;
  }

  private synchronized Map<String, Map<String, Taglibs.Tag>> all() {
    if (read == null) {
      read = all.get();
    }
    return read;
  }

  /** The tags of a namespace a receiver names, or null: a variable no code declares, named as a namespace. */
  Map<String, Taglibs.Tag> namespaceOf(Expression receiver) {
    if (!(receiver instanceof VariableExpression)) {
      return null;
    }
    VariableExpression variable = (VariableExpression) receiver;
    if (!(variable.getAccessedVariable() instanceof DynamicVariable)) {
      return null;
    }
    return all().get(variable.getName());
  }

  /** The {@code g} tags a call with no receiver may name, in page code; empty elsewhere. */
  Map<String, Taglibs.Tag> pageTags() {
    Map<String, Taglibs.Tag> tags = page ? all().get("g") : null;
    return tags == null ? Map.of() : tags;
  }

  /** The tag a node at a position calls (the call, or its method name), or null. */
  Taglibs.Tag at(ASTNode node, ASTNodeVisitor ast) {
    if (node instanceof ConstantExpression && ast.getParent(node) instanceof MethodCallExpression
        && ((MethodCallExpression) ast.getParent(node)).getMethod() == node) {
      node = ast.getParent(node);
    }
    return node instanceof MethodCallExpression ? called((MethodCallExpression) node, ast) : null;
  }

  private Taglibs.Tag called(MethodCallExpression call, ASTNodeVisitor ast) {
    String name = call.getMethodAsString();
    if (name == null) {
      return null;
    }
    Map<String, Taglibs.Tag> namespace = namespaceOf(call.getObjectExpression());
    if (namespace != null) {
      return namespace.get(name);
    }
    if (page && call.isImplicitThis() && GroovyASTUtils.getMethodOverloadsFromCallExpression(call, ast).isEmpty()) {
      return pageTags().get(name);
    }
    return null;
  }

  /** The attributes not given yet of the tag whose named arguments a name is typed in: {@code g.link(con)}. */
  List<String> argumentKeys(VariableExpression typed, ASTNodeVisitor ast) {
    ASTNode arguments = ast.getParent(typed);
    ASTNode parent = arguments == null ? null : ast.getParent(arguments);
    if (!(arguments instanceof TupleExpression) || !(parent instanceof MethodCallExpression) || ((MethodCallExpression) parent).getArguments() != arguments) {
      return List.of();
    }
    Taglibs.Tag tag = called((MethodCallExpression) parent, ast);
    if (tag == null) {
      return List.of();
    }
    List<String> names = tag.docs().attributes.stream().map(attribute -> attribute.name).collect(Collectors.toList());
    return Gorm.unusedKeys(names, (TupleExpression) arguments);
  }
}
