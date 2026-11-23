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
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Where a class the compile read from a class file (a jar, the JDK, the project's compiled Java), or one of its members,
 * is declared in its source file, when {@link SourceLookup#classSource} has one. A method a trait gave to a class of
 * the compile (Grails's {@code render}, {@code save}, or a trait of the project) is looked up in the trait.
 *
 * <p>A declaration of the compile's own sources is found the same way, in its source's text, for its documentation
 * comment: Groovy 2.5 attaches none to its nodes.
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

  /** Null when the node is no class or member, and when its class has no source on disk. */
  static ClassFileSource of(ASTNode node, SourceLookup lookup) {
    ClassNode owner = owner(node);
    if (owner == null) {
      return null;
    }
    String name = owner.getName();
    String path;
    String text;
    if (owner.getModule() != null) {
      // a class of the compile: the text it was compiled from (the open document's is not the file's)
      SourceUnit unit = owner.getModule().getContext();
      path = unit == null ? null : Navigation.path(unit.getSource().getURI());
      text = path == null ? null : StringReaderSourceWithURI.text(unit);
    } else {
      int nested = name.indexOf('$', name.lastIndexOf('.') + 1);
      path = lookup == null ? null : lookup.classSource(nested < 0 ? name : name.substring(0, nested));
      try {
        text = path == null ? null : new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
      } catch (IOException e) {
        text = null;
      }
    }
    if (text == null) {
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
      String property = method instanceof ConstructorNode ? null : accessed(method);
      SourceText.Found type = property == null || found == null ? null : source.find(classes, null, false, List.of());
      if (type != null && type.range.startLine == found.range.startLine && type.range.startColumn == found.range.startColumn) {
        // no such method in the text: the accessor of a Groovy property
        found = source.find(classes, property, false, List.of());
      }
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
   * The class whose source declares the node: the node itself or its declaring class, the trait when a class of the
   * compile got the method from one. Null for a member of a source class that has no position (generated), and for
   * anything that is not a class or a member.
   */
  private static ClassNode owner(ASTNode node) {
    if (node instanceof ClassNode) {
      ClassNode type = ((ClassNode) node).redirect();
      while (type.isArray()) {
        type = type.getComponentType().redirect();
      }
      return type.isGenericsPlaceHolder() ? null : type;
    }
    ClassNode declaring = node instanceof MethodNode ? ((MethodNode) node).getDeclaringClass()
        : node instanceof FieldNode ? ((FieldNode) node).getDeclaringClass() : node instanceof PropertyNode ? ((PropertyNode) node).getDeclaringClass() : null;
    if (declaring == null) {
      return null;
    }
    declaring = declaring.redirect();
    if (declaring.getModule() == null || node.getLineNumber() > 0) {
      return declaring;
    }
    if (node instanceof MethodNode) {
      for (AnnotationNode annotation : ((MethodNode) node).getAnnotations()) {
        Expression trait = annotation.getClassNode().getName().equals(TRAIT_BRIDGE) ? annotation.getMember("traitClass") : null;
        if (trait instanceof ClassExpression) {
          return trait.getType().redirect();
        }
      }
    }
    return null;
  }

  /** The property a method is the accessor of by its name and parameter count, or null. */
  private static String accessed(MethodNode method) {
    String name = method.getName();
    int prefix = name.startsWith("is") ? 2 : name.startsWith("get") || name.startsWith("set") ? 3 : -1;
    if (prefix < 0 || name.length() == prefix || method.getParameters().length != (name.startsWith("set") ? 1 : 0)) {
      return null;
    }
    return Character.toLowerCase(name.charAt(prefix)) + name.substring(prefix + 1);
  }

  private static String simpleName(ClassNode type) {
    while (type.isArray()) {
      type = type.getComponentType();
    }
    String name = type.getNameWithoutPackage();
    return name.substring(name.lastIndexOf('$') + 1);
  }
}
