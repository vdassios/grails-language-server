package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;

import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Where a class the compile read from a class file (a jar, the JDK, the project's compiled Java), or one of its members,
 * is declared in its source file, when {@link SourceLookup#classSource} has one. A method a trait gave to a class of
 * the compile (Grails's {@code render}, {@code save}) is looked up in the trait.
 */
final class ClassFileSource {
  private static final String TRAIT_BRIDGE = "org.codehaus.groovy.transform.trait.Traits$TraitBridge";

  final String path;
  final Range range;
  /** Markdown of the declaration's documentation comment, or null. */
  final String documentation;

  private ClassFileSource(String path, Range range, String documentation) {
    this.path = path;
    this.range = range;
    this.documentation = documentation;
  }

  /** Null for a node declared in the compile's sources, and when the class has no source on disk. */
  static ClassFileSource of(ASTNode node, SourceLookup lookup) {
    ClassNode owner = owner(node);
    if (owner == null || lookup == null) {
      return null;
    }
    String name = owner.getName();
    int nested = name.indexOf('$', name.lastIndexOf('.') + 1);
    String topLevel = nested < 0 ? name : name.substring(0, nested);
    String path = lookup.classSource(topLevel);
    if (path == null) {
      return null;
    }
    String text;
    try {
      text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
    // an anonymous or local class (Outer$1) is not found by name: its enclosing class instead
    List<String> classes = new ArrayList<>();
    boolean named = true;
    for (String simpleName : name.substring(name.lastIndexOf('.') + 1).split("\\$")) {
      named = !simpleName.isEmpty() && Character.isJavaIdentifierStart(simpleName.charAt(0));
      if (!named) {
        break;
      }
      classes.add(simpleName);
    }
    if (classes.isEmpty()) {
      return null;
    }
    SourceText source = new SourceText(text);
    SourceText.Found found;
    if (!named || node instanceof ClassNode) {
      found = source.find(classes, null, false, List.of());
    } else if (node instanceof MethodNode) {
      MethodNode method = (MethodNode) node;
      List<String> parameterTypes = new ArrayList<>();
      for (Parameter parameter : method.getParameters()) {
        parameterTypes.add(simpleName(parameter.getType()));
      }
      String member = method instanceof ConstructorNode ? classes.get(classes.size() - 1) : method.getName();
      found = source.find(classes, member, true, parameterTypes);
    } else {
      String member = node instanceof FieldNode ? ((FieldNode) node).getName() : ((PropertyNode) node).getName();
      found = source.find(classes, member, false, List.of());
    }
    if (found == null) {
      // the file is the class's all the same (a class the scan does not see): its start
      return new ClassFileSource(path, new Range(0, 0, 0, 0), null);
    }
    return new ClassFileSource(path, found.range, found.comment == null ? null : Javadoc.markdown(found.comment));
  }

  /**
   * The class whose source declares the node: the node itself or its declaring class when that comes from a class
   * file, the trait when a class of the compile got the method from one. Null otherwise.
   */
  private static ClassNode owner(ASTNode node) {
    if (node instanceof ClassNode) {
      ClassNode type = ((ClassNode) node).redirect();
      while (type.isArray()) {
        type = type.getComponentType().redirect();
      }
      return type.getModule() == null && !type.isGenericsPlaceHolder() ? type : null;
    }
    ClassNode declaring = node instanceof MethodNode ? ((MethodNode) node).getDeclaringClass()
        : node instanceof FieldNode ? ((FieldNode) node).getDeclaringClass() : node instanceof PropertyNode ? ((PropertyNode) node).getDeclaringClass() : null;
    if (declaring == null) {
      return null;
    }
    declaring = declaring.redirect();
    if (declaring.getModule() == null) {
      return declaring;
    }
    if (node instanceof MethodNode && node.getLineNumber() == -1) {
      for (AnnotationNode annotation : ((MethodNode) node).getAnnotations()) {
        Expression trait = annotation.getClassNode().getName().equals(TRAIT_BRIDGE) ? annotation.getMember("traitClass") : null;
        if (trait instanceof ClassExpression) {
          ClassNode type = trait.getType().redirect();
          return type.getModule() == null ? type : null;
        }
      }
    }
    return null;
  }

  private static String simpleName(ClassNode type) {
    while (type.isArray()) {
      type = type.getComponentType();
    }
    String name = type.getNameWithoutPackage();
    return name.substring(name.lastIndexOf('$') + 1);
  }
}
