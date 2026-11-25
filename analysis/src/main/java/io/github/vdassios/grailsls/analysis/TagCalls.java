package io.github.vdassios.grailsls.analysis;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;

/**
 * Tags called as methods: {@code g.link(controller: 'book')} and {@code ns.tag(...)} in Groovy and in page code, where
 * the receiver is a name no code declares that is a namespace of the taglib index ({@link Taglibs}); and in page code
 * also a call with no receiver that is no method of the page, {@code message(code: 'x')}, which Grails sends to the
 * {@code g} namespace.
 *
 * <p>
 * {@code namespaces} gives the tags of a namespace by name (null when no taglib declares it), read only when a call
 * can be a tag's; {@code page}: the code is page code.
 */
final class TagCalls {
  private TagCalls() {
  }

  /** The tags of a namespace a receiver names, or null: a variable no code declares, named as a namespace. */
  static Map<String, Taglibs.Tag> namespaceOf(Expression receiver, Function<String, Map<String, Taglibs.Tag>> namespaces) {
    if (!(receiver instanceof VariableExpression)) {
      return null;
    }
    VariableExpression variable = (VariableExpression) receiver;
    if (!(variable.getAccessedVariable() instanceof DynamicVariable)) {
      return null;
    }
    return namespaces.apply(variable.getName());
  }

  /** The {@code g} tags a call with no receiver may name in page code. */
  static Map<String, Taglibs.Tag> pageTags(Function<String, Map<String, Taglibs.Tag>> namespaces) {
    Map<String, Taglibs.Tag> tags = namespaces.apply("g");
    return tags == null ? Map.of() : tags;
  }

  /** The tag a node at a position calls (the call, or its method name), or null. */
  static Taglibs.Tag at(ASTNode node, ASTNodeVisitor ast, Function<String, Map<String, Taglibs.Tag>> namespaces, boolean page) {
    if (node instanceof ConstantExpression && ast.getParent(node) instanceof MethodCallExpression
        && ((MethodCallExpression) ast.getParent(node)).getMethod() == node) {
      node = ast.getParent(node);
    }
    return node instanceof MethodCallExpression ? called((MethodCallExpression) node, ast, namespaces, page) : null;
  }

  private static Taglibs.Tag called(MethodCallExpression call, ASTNodeVisitor ast, Function<String, Map<String, Taglibs.Tag>> namespaces, boolean page) {
    String name = call.getMethodAsString();
    if (name == null) {
      return null;
    }
    Map<String, Taglibs.Tag> namespace = namespaceOf(call.getObjectExpression(), namespaces);
    if (namespace != null) {
      return namespace.get(name);
    }
    // a Groovy extension method by the tag's name (collect, each, findAll on Object) does not hide the tag
    if (page && call.isImplicitThis() && GroovyASTUtils.getMethodOverloadsFromCallExpression(call, ast).stream().allMatch(ExtensionMethodNode.class::isInstance)) {
      return pageTags(namespaces).get(name);
    }
    return null;
  }

  /** The attributes not given yet of the tag whose named arguments a name is typed in: {@code g.link(con)}. */
  static List<String> argumentKeys(VariableExpression typed, ASTNodeVisitor ast, Function<String, Map<String, Taglibs.Tag>> namespaces, boolean page) {
    MethodCall call = GroovyASTUtils.callWithArguments(ast.getParent(typed), ast);
    if (!(call instanceof MethodCallExpression)) {
      return List.of();
    }
    Taglibs.Tag tag = called((MethodCallExpression) call, ast, namespaces, page);
    if (tag == null) {
      return List.of();
    }
    List<String> names = tag.docs().attributes.stream().map(attribute -> attribute.name).collect(Collectors.toList());
    return Gorm.unusedKeys(names, (TupleExpression) ((MethodCallExpression) call).getArguments());
  }
}
