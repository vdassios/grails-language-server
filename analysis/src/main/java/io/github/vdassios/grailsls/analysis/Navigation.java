package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;

import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.compiler.util.GroovydocUtils;
import net.prominic.groovyls.util.GroovyNodeToStringUtils;

/**
 * Definition, hover and document symbols over a visited compile. Ported from groovy-language-server's
 * {@code DefinitionProvider}, {@code HoverProvider} and {@code DocumentSymbolProvider}, returning {@code api} types.
 */
public final class Navigation {
  private Navigation() {}

  public static Location definition(ASTNodeVisitor ast, URI document, SourceLookup lookup, String text, int line, int column) {
    ASTNode offsetNode = ast.getNodeAtLineAndColumn(document, line, column);
    if (offsetNode == null) {
      return null;
    }
    Gorm.Target gorm = Gorm.target(offsetNode, ast, text, line, column);
    ASTNode definition = gorm != null ? gorm.node : GroovyASTUtils.getDefinition(offsetNode, true, ast);
    if (definition == null || definition.getLineNumber() == -1 || definition.getColumnNumber() == -1) {
      // not declared in the compile's sources: a class file's source, when one is on disk
      ClassFileSource source = ClassFileSource.of(definition != null ? definition : GroovyASTUtils.getDefinition(offsetNode, false, ast), lookup);
      return source == null ? null : new Location(source.path, source.range);
    }
    URI uri = ast.getURI(definition);
    if (uri == null && gorm != null) {
      // a key of hasMany: in the source of its class
      uri = ast.getURI(gorm.owner);
    }
    String path = path(uri == null ? document : uri);
    Range range = NodeRanges.of(definition);
    return path == null || range == null ? null : new Location(path, range);
  }

  public static String hover(ASTNodeVisitor ast, URI document, SourceLookup lookup, String text, int line, int column) {
    ASTNode offsetNode = ast.getNodeAtLineAndColumn(document, line, column);
    if (offsetNode == null) {
      return null;
    }
    ASTNode definition = GroovyASTUtils.getDefinition(offsetNode, false, ast);
    if (definition == null) {
      definition = Gorm.described(offsetNode, ast, text, line, column);
    }
    String content = definition == null ? null : signature(definition, ast);
    if (content == null) {
      return null;
    }
    StringBuilder markdown = new StringBuilder("```groovy\n").append(content).append("\n```");
    String documentation = documentation(definition, lookup);
    if (documentation != null) {
      markdown.append("\n\n---\n\n").append(documentation);
    }
    return markdown.toString();
  }

  /**
   * The documentation of a declaration as markdown, or null: the Groovydoc on its node (Groovy 3 and later, the
   * compile's sources), what GORM documents, else the comment before it in its source text (class files with a source
   * on disk, and the compile's sources on Groovy 2.5).
   */
  static String documentation(ASTNode declaration, SourceLookup lookup) {
    String documentation = declaration instanceof AnnotatedNode ? GroovydocUtils.groovydocToMarkdownDescription((AnnotatedNode) declaration) : null;
    if (documentation == null) {
      documentation = Gorm.documentation(declaration);
    }
    if (documentation == null) {
      ClassFileSource source = ClassFileSource.of(declaration, lookup);
      documentation = source == null ? null : source.documentation;
    }
    return documentation;
  }

  public static List<Symbol> symbols(ASTNodeVisitor ast, URI document) {
    List<Symbol> symbols = new ArrayList<>();
    for (ASTNode node : ast.getNodes(document)) {
      if (!(node instanceof ClassNode || node instanceof MethodNode || node instanceof FieldNode || node instanceof PropertyNode)) {
        continue;
      }
      Range range = NodeRanges.of(node);
      if (range == null) {
        continue;
      }
      if (node instanceof ClassNode) {
        symbols.add(new Symbol(((ClassNode) node).getName(), kind(node), null, range));
        continue;
      }
      ClassNode owner = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class, ast);
      String name = node instanceof MethodNode ? ((MethodNode) node).getName() : ((Variable) node).getName();
      symbols.add(new Symbol(name, kind(node), owner == null ? null : owner.getName(), range));
    }
    return symbols;
  }

  static String signature(ASTNode node, ASTNodeVisitor ast) {
    if (node instanceof ClassNode) {
      return GroovyNodeToStringUtils.classToString((ClassNode) node, ast);
    } else if (node instanceof MethodNode) {
      return GroovyNodeToStringUtils.methodToString((MethodNode) node, ast);
    } else if (node instanceof Variable) {
      return GroovyNodeToStringUtils.variableToString((Variable) node, ast);
    }
    return null;
  }

  private static Symbol.Kind kind(ASTNode node) {
    if (node instanceof ClassNode) {
      ClassNode type = (ClassNode) node;
      return type.isInterface() ? Symbol.Kind.INTERFACE : type.isEnum() ? Symbol.Kind.ENUM : Symbol.Kind.CLASS;
    } else if (node instanceof MethodNode) {
      return Symbol.Kind.METHOD;
    } else if (node instanceof FieldNode || node instanceof PropertyNode) {
      return Symbol.Kind.FIELD;
    }
    return Symbol.Kind.VARIABLE;
  }

  /** The file path of a source's URI, or null when it is not a file. */
  static String path(URI uri) {
    return "file".equals(uri.getScheme()) ? new File(uri).getPath() : null;
  }
}
