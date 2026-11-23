package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.Location;

/**
 * Names a GSP page uses without declaring them, made properties of its translation so that completion offers them and
 * queries type them:
 * <ul>
 * <li>model variables: the keys of the literal model maps of the actions rendering the view, or of the actions and
 * pages rendering the template ({@link GspConventions#models}), typed when the controller's code states the value's type
 * (a typed declaration or parameter, {@code new}, a cast, a literal), else by a compile of the file holding the map;
 * their definition is the map entry;
 * <li>{@code g:set var} variables, typed after the compile by their {@code value} ({@link #link}); their definition is
 * the {@code var} attribute.
 * </ul>
 * {@code g:each var} variables are locals of the translation ({@code for (var in ...)}); {@link #link} gives them, and
 * {@code it} in a {@code g:each} without {@code var}, the element type of their collection.
 */
final class PageVariables {
  /** Property declarations for the generated class, on one line. */
  final String declarations;
  /** Where each declared name is defined. */
  final Map<String, Location> definitions;

  private PageVariables(String declarations, Map<String, Location> definitions) {
    this.declarations = declarations;
    this.definitions = definitions;
  }

  static final PageVariables NONE = new PageVariables("", Map.of());

  /**
   * The variables of a page; {@code taken} are names declared already (implicit objects), {@code resolves} says
   * whether a qualified class name resolves in the page's module, {@code compiled} types a model entry the parse does
   * not (null when it cannot).
   */
  static PageVariables of(String path, GspScanner.Scan page, GspConventions conventions, Set<String> taken, Predicate<String> resolves, Function<GspConventions.ModelEntry, String> compiled) {
    Map<String, String> types = new LinkedHashMap<>();
    Map<String, Location> definitions = new LinkedHashMap<>();
    for (GspConventions.ModelEntry model : conventions.models(path)) {
      Expression key = model.entry.getKeyExpression();
      String name = key instanceof ConstantExpression && ((ConstantExpression) key).getValue() instanceof String ? (String) ((ConstantExpression) key).getValue() : null;
      if (!declarable(name, taken)) {
        continue;
      }
      String type = typeOf(model, resolves);
      if (type == null) {
        type = compiled.apply(model);
      }
      if (!types.containsKey(name)) {
        types.put(name, type);
        definitions.put(name, new Location(model.path, model.keyRange()));
      } else if (types.get(name) != null && !types.get(name).equals(type)) {
        // actions disagree: untyped
        types.put(name, null);
      }
    }
    Lines lines = null;
    for (GspScanner.Tag tag : page.tags) {
      GspScanner.Attribute var = tag.closing || !"g".equals(tag.namespace) || !"set".equals(tag.name) ? null : tag.attribute("var");
      if (var != null && var.valueStart >= 0 && declarable(var.value, taken) && !types.containsKey(var.value)) {
        lines = lines == null ? new Lines(page.text) : lines;
        types.put(var.value, null);
        definitions.put(var.value, new Location(path, lines.range(var.valueStart, var.valueEnd)));
      }
    }
    if (types.isEmpty()) {
      return NONE;
    }
    StringBuilder declarations = new StringBuilder();
    types.forEach((name, type) -> declarations.append(type == null ? "def" : type).append(' ').append(name).append("; "));
    return new PageVariables(declarations.toString(), definitions);
  }

  /**
   * After the page's compile: each {@code g:set} property takes the type of its first {@code value}, each loop variable
   * the element type of its collection ({@link GroovyASTUtils#VALUE_OF}, {@link GroovyASTUtils#ELEMENT_OF}).
   */
  static void link(ClassNode page) {
    MethodNode run = page.getMethod("run", Parameter.EMPTY_ARRAY);
    if (run == null || run.getCode() == null) {
      return;
    }
    run.getCode().visit(new ClassCodeVisitorSupport() {
      @Override
      protected SourceUnit getSourceUnit() {
        return null;
      }

      @Override
      public void visitMethodCallExpression(MethodCallExpression call) {
        List<Expression> arguments = call.getArguments() instanceof TupleExpression ? ((TupleExpression) call.getArguments()).getExpressions() : List.of();
        if (call.isImplicitThis() && "invokeTag".equals(call.getMethodAsString()) && arguments.size() >= 4 && "set".equals(constant(arguments.get(0)))
            && "g".equals(constant(arguments.get(1))) && arguments.get(3) instanceof MapExpression) {
          String name = null;
          Expression value = null;
          for (MapEntryExpression entry : ((MapExpression) arguments.get(3)).getMapEntryExpressions()) {
            String key = constant(entry.getKeyExpression());
            if ("var".equals(key)) {
              name = constant(entry.getValueExpression());
            } else if ("value".equals(key)) {
              value = entry.getValueExpression();
            }
          }
          if (name != null && value != null) {
            PropertyNode property = page.getProperty(name);
            FieldNode field = page.getField(name);
            for (Variable variable : new Variable[] { property, field }) {
              if (variable != null && variable.isDynamicTyped() && ((ASTNode) variable).getNodeMetaData(GroovyASTUtils.VALUE_OF) == null) {
                ((ASTNode) variable).putNodeMetaData(GroovyASTUtils.VALUE_OF, value);
              }
            }
          }
        }
        super.visitMethodCallExpression(call);
      }

      @Override
      public void visitForLoop(ForStatement loop) {
        Parameter variable = loop.getVariable();
        if (variable != null && variable != ForStatement.FOR_LOOP_DUMMY && variable.isDynamicTyped()) {
          variable.putNodeMetaData(GroovyASTUtils.ELEMENT_OF, loop.getCollectionExpression());
          if (changesIt(loop)) {
            linkIt(loop.getLoopBlock(), loop.getCollectionExpression());
          }
        }
        super.visitForLoop(loop);
      }
    });
  }

  /**
   * Whether a loop is a {@code g:each} without {@code var}: Grails names its variable {@code _it<n>} and makes each
   * element the page's {@code it} ({@code changeItVariable(_it<n>)}).
   */
  private static boolean changesIt(ForStatement loop) {
    Statement block = loop.getLoopBlock();
    List<Statement> statements = block instanceof BlockStatement ? ((BlockStatement) block).getStatements() : List.of(block);
    Statement first = statements.isEmpty() ? null : statements.get(0);
    Expression call = first instanceof ExpressionStatement ? ((ExpressionStatement) first).getExpression() : null;
    return loop.getVariable().getName().startsWith("_it") && call instanceof MethodCallExpression && ((MethodCallExpression) call).isImplicitThis()
        && "changeItVariable".equals(((MethodCallExpression) call).getMethodAsString());
  }

  /** Each {@code it} of a loop's body takes the element type of its collection; not inside closures, nor in an inner loop that changes it again. */
  private static void linkIt(Statement body, Expression collection) {
    body.visit(new ClassCodeVisitorSupport() {
      @Override
      protected SourceUnit getSourceUnit() {
        return null;
      }

      @Override
      public void visitVariableExpression(VariableExpression expression) {
        if ("it".equals(expression.getName()) && expression.getNodeMetaData(GroovyASTUtils.ELEMENT_OF) == null) {
          expression.putNodeMetaData(GroovyASTUtils.ELEMENT_OF, collection);
        }
      }

      @Override
      public void visitClosureExpression(ClosureExpression closure) {
        // its own it
      }

      @Override
      public void visitForLoop(ForStatement loop) {
        if (loop.getVariable() != null && loop.getVariable() != ForStatement.FOR_LOOP_DUMMY && changesIt(loop)) {
          loop.getCollectionExpression().visit(this);
          return;
        }
        super.visitForLoop(loop);
      }
    });
  }

  private static String constant(Expression expression) {
    return expression instanceof ConstantExpression && ((ConstantExpression) expression).getValue() instanceof String ? (String) ((ConstantExpression) expression).getValue() : null;
  }

  // model types, from the controller's parse

  /** The qualified type of a model entry's value when the controller's code states it, else null. */
  private static String typeOf(GspConventions.ModelEntry model, Predicate<String> resolves) {
    Expression value = model.entry.getValueExpression();
    if (value instanceof ConstantExpression) {
      Object constant = ((ConstantExpression) value).getValue();
      return constant instanceof String || constant instanceof Number || constant instanceof Boolean ? constant.getClass().getName() : null;
    }
    if (value instanceof GStringExpression) {
      return "java.lang.String";
    }
    if (value instanceof ListExpression) {
      return "java.util.List";
    }
    if (value instanceof MapExpression) {
      return "java.util.Map";
    }
    ClassNode type = null;
    if (value instanceof ConstructorCallExpression || value instanceof CastExpression) {
      type = value.getType();
    } else if (value instanceof VariableExpression && model.action != null) {
      type = declaredType(((VariableExpression) value).getName(), model);
    }
    // in a page, classes are named as imported there: left to the page's compile
    return type == null || model.controller == null ? null : name(type, model.controller.getModule(), resolves);
  }

  /**
   * The declared type of a name at a model entry: the last typed declaration of it in the action before the entry, else
   * a typed parameter of the action, else a typed field or property of the controller.
   */
  private static ClassNode declaredType(String name, GspConventions.ModelEntry model) {
    Statement code = model.action instanceof MethodNode ? ((MethodNode) model.action).getCode() : ((ClosureExpression) ((PropertyNode) model.action).getInitialExpression()).getCode();
    int line = model.entry.getLineNumber();
    ClassNode[] found = new ClassNode[1];
    boolean[] declared = new boolean[1];
    if (code != null) {
      code.visit(new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression declaration) {
          if (!declaration.isMultipleAssignmentDeclaration() && declaration.getLineNumber() <= line && declaration.getVariableExpression().getName().equals(name)) {
            declared[0] = true;
            found[0] = declaration.getVariableExpression().isDynamicTyped() ? null : declaration.getVariableExpression().getOriginType();
          }
          super.visitDeclarationExpression(declaration);
        }
      });
    }
    if (declared[0]) {
      return found[0];
    }
    Parameter[] parameters = model.action instanceof MethodNode ? ((MethodNode) model.action).getParameters() : ((ClosureExpression) ((PropertyNode) model.action).getInitialExpression()).getParameters();
    for (Parameter parameter : parameters == null ? new Parameter[0] : parameters) {
      if (parameter.getName().equals(name)) {
        return parameter.isDynamicTyped() ? null : parameter.getOriginType();
      }
    }
    FieldNode field = model.controller.getField(name);
    return field == null || field.isDynamicTyped() ? null : field.getOriginType();
  }

  /**
   * A type a compile inferred, as a page declares it: qualified, with its type arguments when each is a known class;
   * null for {@code Object}, a type variable or a class that does not resolve in the page's module.
   */
  static String declared(ClassNode type, Predicate<String> resolves) {
    if (type == null || type.isGenericsPlaceHolder() || ClassHelper.OBJECT_TYPE.equals(type) || ClassHelper.DYNAMIC_TYPE.equals(type)) {
      return null;
    }
    if (ClassHelper.isPrimitiveType(type)) {
      return type.getName();
    }
    if (type.isArray()) {
      String component = declared(type.getComponentType(), resolves);
      return component == null ? null : component + "[]";
    }
    String binary = type.getName();
    if (!resolves.test(binary)) {
      return null;
    }
    String qualified = binary.replace('$', '.');
    GenericsType[] generics = type.getGenericsTypes();
    if (generics == null || generics.length == 0) {
      return qualified;
    }
    List<String> arguments = new ArrayList<>();
    for (GenericsType generic : generics) {
      String argument = generic.isWildcard() || generic.isPlaceholder() ? null : declared(generic.getType(), resolves);
      if (argument == null) {
        return qualified;
      }
      arguments.add(argument);
    }
    return qualified + "<" + String.join(", ", arguments) + ">";
  }

  private static final List<String> DEFAULT_PACKAGES = List.of("java.lang.", "java.util.", "java.io.", "java.net.", "groovy.lang.", "groovy.util.");

  /**
   * A type as written in a controller, qualified as Groovy would resolve it there (imports, its package, star imports,
   * Groovy's default imports), with its type arguments; null when a name does not resolve in the page's module.
   */
  private static String name(ClassNode type, ModuleNode module, Predicate<String> resolves) {
    if (ClassHelper.isPrimitiveType(type)) {
      return type.getName();
    }
    if (type.isArray()) {
      String component = name(type.getComponentType(), module, resolves);
      return component == null ? null : component + "[]";
    }
    String qualified = qualify(type.getName(), module, resolves);
    GenericsType[] generics = type.getGenericsTypes();
    if (qualified == null || generics == null || generics.length == 0) {
      return qualified;
    }
    List<String> arguments = new ArrayList<>();
    for (GenericsType generic : generics) {
      String argument = generic.isWildcard() || generic.isPlaceholder() ? null : name(generic.getType(), module, resolves);
      if (argument == null) {
        return qualified;
      }
      arguments.add(argument);
    }
    return qualified + "<" + String.join(", ", arguments) + ">";
  }

  private static String qualify(String written, ModuleNode module, Predicate<String> resolves) {
    List<String> candidates = new ArrayList<>();
    if (written.contains(".") || module == null) {
      candidates.add(written);
    } else {
      ImportNode imported = module.getImport(written);
      if (imported != null) {
        candidates.add(imported.getType().getName());
      }
      String packageName = module.getPackageName();
      candidates.add(packageName == null ? written : (packageName.endsWith(".") ? packageName : packageName + ".") + written);
      module.getStarImports().forEach(star -> candidates.add(star.getPackageName() + written));
      DEFAULT_PACKAGES.forEach(prefix -> candidates.add(prefix + written));
      if (written.equals("BigDecimal") || written.equals("BigInteger")) {
        candidates.add("java.math." + written);
      }
    }
    return candidates.stream().filter(resolves).findFirst().orElse(null);
  }

  // names

  private static final Set<String> RESERVED = Set.of("abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "def", "default", "do",
      "double", "else", "enum", "extends", "false", "final", "finally", "float", "for", "goto", "if", "implements", "import", "in", "instanceof", "int", "interface", "it", "long",
      "native", "new", "null", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
      "trait", "transient", "true", "try", "var", "void", "volatile", "while");

  private static boolean declarable(String name, Set<String> taken) {
    if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || name.indexOf('$') >= 0 || RESERVED.contains(name) || taken.contains(name)) {
      return false;
    }
    return name.chars().allMatch(Character::isJavaIdentifierPart);
  }
}
