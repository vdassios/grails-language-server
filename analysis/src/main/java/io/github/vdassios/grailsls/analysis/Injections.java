package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Untyped injection (C3). Grails autowires artefacts by bean name, so {@code def bookService} holds an instance of the
 * service class {@code BookService}, whatever its package. The compile pulls that class in from source, and the
 * property and its field are marked with its class node for queries ({@link #typeOf}).
 *
 * <p>Only services declared in a {@code grails-app/services} source root, with exactly one class of that name: plugin
 * services from jars are not looked up, and an ambiguous name types nothing.
 */
public final class Injections {
  /** Node metadata key: the injected class node of an untyped property or its field. */
  public static final String TYPE = Injections.class.getName() + ".type";
  private static final Pattern BEAN = Pattern.compile("[a-z]\\w*Service");

  /** Each untyped service property of the document, with the binary name of its class. */
  private final Map<PropertyNode, String> wanted = new IdentityHashMap<>();

  private Injections() {}

  /**
   * After conversion: finds the document's untyped service properties and adds each service's source to the unit
   * through {@code resolver}, so the following phases compile it with the document.
   */
  static Injections queue(SourceUnit document, SourceLookup lookup, PathResolver resolver, CompilationUnit unit) {
    Injections injections = new Injections();
    if (document.getAST() == null) {
      return injections;
    }
    for (ClassNode type : document.getAST().getClasses()) {
      for (PropertyNode property : type.getProperties()) {
        if (property.isStatic() || !property.getField().isDynamicTyped() || !BEAN.matcher(property.getName()).matches()) {
          continue;
        }
        String className = serviceNamed(lookup, Character.toUpperCase(property.getName().charAt(0)) + property.getName().substring(1));
        if (className != null) {
          injections.wanted.put(property, className);
          resolver.findClassNode(className, unit);
        }
      }
    }
    return injections;
  }

  /** The one service class declared in source with this simple name, or null. */
  private static String serviceNamed(SourceLookup lookup, String simpleName) {
    List<String> services = new ArrayList<>();
    for (String className : lookup.classesNamed(simpleName)) {
      String path = lookup.fileOf(className);
      if (path != null && path.replace(File.separatorChar, '/').contains("/grails-app/services/")) {
        services.add(className);
      }
    }
    return services.size() == 1 ? services.get(0) : null;
  }

  /** After the compile: marks each property and its field with the class node of its service. */
  void mark(CompilationUnit unit) {
    wanted.forEach((property, className) -> {
      ClassNode service = unit.getAST().getClass(className);
      if (service != null) {
        property.putNodeMetaData(TYPE, service);
        property.getField().putNodeMetaData(TYPE, service);
      }
    });
  }

  /** The injected class of a property or field, or null. */
  public static ClassNode typeOf(ASTNode definition) {
    Object type = definition == null ? null : definition.getNodeMetaData(TYPE);
    return type instanceof ClassNode ? (ClassNode) type : null;
  }
}
