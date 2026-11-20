package io.github.vdassios.grailsls.analysis;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Range;

/**
 * What GORM resolves at run time, read from the compiled classes: the persistent properties of a domain class, dynamic
 * finders ({@link FinderName}), and the property names used in criteria, {@code constraints} and {@code mapping} blocks
 * and query settings. A domain class is one the compile gave GORM's entity trait, from source or from a class file.
 */
public final class Gorm {
  private static final ClassNode ENTITY = ClassHelper.make("org.grails.datastore.gorm.GormEntity");
  private static final String DELEGATES_TO = "groovy.lang.DelegatesTo";
  /** Static fields of a domain class whose closure names properties: {@code title blank: false}. */
  private static final Set<String> PROPERTY_BLOCKS = Set.of("constraints", "mapping");
  /** Static methods of a domain class that take a criteria closure about that class. */
  private static final Set<String> CRITERIA_METHODS = Set.of("withCriteria", "createCriteria", "where", "whereAny", "whereLazy", "find", "findAll");
  /** Those of them whose closure GORM rewrites at compile time: bare names in it are properties. */
  private static final Set<String> WHERE_METHODS = Set.of("where", "whereAny", "whereLazy", "find", "findAll");
  /** Keys of the settings map a query takes: {@code Book.list(max: 10, sort: 'title')}. */
  static final List<String> QUERY_SETTINGS = List.of("max", "offset", "sort", "order", "fetch", "readOnly", "cache", "ignoreCase");
  static final List<String> CONSTRAINTS = List.of("nullable", "blank", "size", "maxSize", "minSize", "min", "max", "inList", "matches", "email", "url", "unique",
      "validator", "range", "scale", "notEqual", "creditCard");
  /** Bean properties GORM never maps ({@code ClassPropertyFetcher.EXCLUDED_PROPERTIES}). */
  private static final Set<String> NOT_PERSISTENT = Set.of("class", "metaClass", "properties");
  /** Key of the finder a call names, kept on the call: one method node per call, however often it is asked for. */
  private static final String FINDER_KEY = "grailsls.finder";
  private static final Object NO_FINDER = new Object();

  private Gorm() {}

  /** A persistent property of a domain class. */
  public static final class Property {
    public final String name;
    public final ClassNode type;
    /** The class of the elements of a {@code hasMany} collection, else null. */
    public final ClassNode element;
    /** The class that declares it. */
    public final ClassNode owner;
    /** Where it is declared: the property, its key in {@code hasMany}, {@code belongsTo} or {@code hasOne}, or the getter a trait gave the class; null without source. */
    public final ASTNode declaration;
    /** The property or field node; hover shows it. */
    public final ASTNode node;

    Property(String name, ClassNode type, ClassNode element, ClassNode owner, ASTNode declaration, ASTNode node) {
      this.name = name;
      this.type = type;
      this.element = element;
      this.owner = owner;
      this.declaration = declaration;
      this.node = node;
    }

    /** The domain class a criteria block on this property is about, or null when it is no association. */
    ClassNode association() {
      ClassNode target = element != null ? element : type;
      return isDomain(target) ? target : null;
    }
  }

  /** A node another node refers to, and the class whose source holds it. */
  public static final class Target {
    public final ASTNode node;
    public final ClassNode owner;

    Target(ASTNode node, ClassNode owner) {
      this.node = node;
      this.owner = owner;
    }
  }

  public static boolean isDomain(ClassNode type) {
    return type != null && GroovyASTUtils.members(() -> type.implementsInterface(ENTITY), false);
  }

  /**
   * The persistent properties of a domain class and its superclasses, nearest first: declared ones that are neither
   * static nor transient, and those Grails adds ({@code id}, {@code version}, one per {@code hasMany} and
   * {@code belongsTo} entry). A class read from a class file has no property nodes: its fields with a getter instead.
   * The properties of a trait are fields of the class that implements it, in source and class file alike. A getter
   * and setter pair without a field is one too, as GORM maps every bean property that can be read and written
   * ({@code GormMappingConfigurationStrategy.getPersistentProperties}).
   */
  public static Map<String, Property> properties(ClassNode domain) {
    Map<String, Property> found = new LinkedHashMap<>();
    Set<ClassNode> seen = new HashSet<>();
    for (ClassNode current = domain.redirect(); current != null && !current.equals(ClassHelper.OBJECT_TYPE) && seen.add(current); current = superClass(current)) {
      ClassNode type = current;
      Set<String> transients = transients(type);
      Map<String, MapEntryExpression> associations = new LinkedHashMap<>();
      for (String field : List.of("hasMany", "belongsTo", "hasOne")) {
        associations.putAll(entries(type, field));
      }
      Map<String, MapEntryExpression> hasMany = entries(type, "hasMany");
      List<PropertyNode> declared = GroovyASTUtils.members(type::getProperties, List.<PropertyNode>of());
      for (PropertyNode property : declared) {
        String name = property.getName();
        if (property.isStatic() || Modifier.isTransient(property.getModifiers()) || transients.contains(name) || name.indexOf('$') >= 0) {
          continue;
        }
        MapEntryExpression entry = associations.get(name);
        ASTNode declaration = property.getLineNumber() > 0 ? property : entry == null ? null : entry.getKeyExpression();
        found.putIfAbsent(name, new Property(name, property.getType(), elementOf(hasMany.get(name)), type, declaration, property));
      }
      for (FieldNode field : GroovyASTUtils.members(type::getFields, List.<FieldNode>of())) {
        // a property a trait gave the class is the field <trait name with underscores>__<name>
        int fromTrait = field.getName().lastIndexOf("__");
        if (fromTrait < 0 && !declared.isEmpty()) {
          continue;
        }
        String name = fromTrait < 0 ? field.getName() : field.getName().substring(fromTrait + 2);
        if (field.isStatic() || Modifier.isTransient(field.getModifiers()) || transients.contains(name) || name.isEmpty() || name.indexOf('$') >= 0) {
          continue;
        }
        MethodNode getter = GroovyASTUtils.members(() -> type.getGetterMethod("get" + FinderName.capitalized(name)), null);
        if (getter == null || isTransient(getter)) {
          continue;
        }
        found.putIfAbsent(name, fromTrait < 0 ? new Property(name, field.getType(), null, type, null, field) : new Property(name, field.getType(), elementOf(hasMany.get(name)), type, getter, getter));
      }
      for (MethodNode getter : GroovyASTUtils.members(type::getMethods, List.<MethodNode>of())) {
        String name = readProperty(getter);
        FieldNode field = name == null ? null : GroovyASTUtils.members(() -> type.getDeclaredField(name), null);
        if (name == null || found.containsKey(name) || NOT_PERSISTENT.contains(name) || transients.contains(name) || isTransient(getter) || woven(getter)
            || field != null && (field.isStatic() || Modifier.isTransient(field.getModifiers())) || !writable(type, getter)) {
          continue;
        }
        found.put(name, new Property(name, getter.getReturnType(), elementOf(hasMany.get(name)), type, getter, getter));
      }
    }
    return found;
  }

  /**
   * The property a public instance getter reads ({@code getURL} reads {@code URL}, as {@code Introspector} names it),
   * or null: no getter, or one of type {@code Object}, which GORM does not map.
   */
  private static String readProperty(MethodNode method) {
    String name = method.getName();
    ClassNode type = method.getReturnType();
    int at = name.startsWith("get") ? 3 : name.startsWith("is") && type.equals(ClassHelper.boolean_TYPE) ? 2 : -1;
    if (at < 0 || name.length() == at || !method.isPublic() || method.isStatic() || method.isSynthetic() || method.getParameters().length > 0
        || type.equals(ClassHelper.VOID_TYPE) || type.equals(ClassHelper.OBJECT_TYPE)) {
      return null;
    }
    String rest = name.substring(at);
    return rest.length() > 1 && Character.isUpperCase(rest.charAt(0)) && Character.isUpperCase(rest.charAt(1)) ? rest : Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
  }

  /** True when the class has a public instance setter for what a getter reads. */
  private static boolean writable(ClassNode type, MethodNode getter) {
    String name = "set" + getter.getName().substring(getter.getName().startsWith("is") ? 2 : 3);
    for (MethodNode setter : GroovyASTUtils.members(() -> type.getMethods(name), List.<MethodNode>of())) {
      if (setter.isPublic() && !setter.isStatic() && setter.getParameters().length == 1 && setter.getParameters()[0].getType().equals(getter.getReturnType())) {
        return true;
      }
    }
    return false;
  }

  /** A method a trait gave the class: GORM's own ({@code errors}, ...) are no columns, and a trait's properties are its fields. */
  private static boolean woven(MethodNode method) {
    for (AnnotationNode annotation : GroovyASTUtils.members(method::getAnnotations, List.<AnnotationNode>of())) {
      if (annotation.getClassNode().getName().endsWith("TraitBridge")) {
        return true;
      }
    }
    return false;
  }

  /** GORM's own traits mark what is not persistent ({@code errors}) with JPA's {@code @Transient} on the getter. */
  private static boolean isTransient(MethodNode getter) {
    for (AnnotationNode annotation : GroovyASTUtils.members(getter::getAnnotations, List.<AnnotationNode>of())) {
      if (annotation.getClassNode().getNameWithoutPackage().equals("Transient")) {
        return true;
      }
    }
    return false;
  }

  private static ClassNode superClass(ClassNode type) {
    ClassNode superClass = GroovyASTUtils.members(type::getSuperClass, null);
    return superClass == null ? null : superClass.redirect();
  }

  private static Expression initialValue(ClassNode type, String staticField) {
    FieldNode field = GroovyASTUtils.members(() -> type.getDeclaredField(staticField), null);
    return field == null || !field.isStatic() ? null : field.getInitialExpression();
  }

  /** {@code static transients = ['a', 'b']}. */
  private static Set<String> transients(ClassNode type) {
    Set<String> names = new HashSet<>();
    Expression value = initialValue(type, "transients");
    if (value instanceof ListExpression) {
      for (Expression name : ((ListExpression) value).getExpressions()) {
        if (name instanceof ConstantExpression) {
          names.add(name.getText());
        }
      }
    }
    return names;
  }

  /** {@code static hasMany = [books: Book]}: the entries by key. */
  private static Map<String, MapEntryExpression> entries(ClassNode type, String staticField) {
    Map<String, MapEntryExpression> entries = new LinkedHashMap<>();
    Expression value = initialValue(type, staticField);
    if (value instanceof MapExpression) {
      for (MapEntryExpression entry : ((MapExpression) value).getMapEntryExpressions()) {
        if (entry.getKeyExpression() instanceof ConstantExpression) {
          entries.put(entry.getKeyExpression().getText(), entry);
        }
      }
    }
    return entries;
  }

  private static ClassNode elementOf(MapEntryExpression entry) {
    return entry != null && entry.getValueExpression() instanceof ClassExpression ? entry.getValueExpression().getType() : null;
  }

  // dynamic finders

  /** A call of a dynamic finder. */
  public static final class Finder {
    public final ClassNode domain;
    public final FinderName name;
    public final Map<String, Property> properties;
    private MethodNode method;

    Finder(ClassNode domain, FinderName name, Map<String, Property> properties) {
      this.domain = domain;
      this.name = name;
      this.properties = properties;
    }

    /**
     * The finder as a method of the domain class, with one parameter per argument, for signatures and for the type of
     * the call: the domain class, a list of it, or a count.
     */
    public synchronized MethodNode method() {
      if (method == null) {
        List<Parameter> parameters = new ArrayList<>();
        if (!name.prefix.equals("listOrderBy")) {
          for (FinderName.Condition condition : name.conditions) {
            Property property = properties.get(condition.property);
            ClassNode type = property == null ? ClassHelper.OBJECT_TYPE : property.type;
            String comparator = condition.comparator == null ? "" : condition.comparator;
            if (comparator.equals("Between")) {
              parameters.add(new Parameter(type, condition.property + "From"));
              parameters.add(new Parameter(type, condition.property + "To"));
            } else if (comparator.endsWith("InList")) {
              parameters.add(new Parameter(ClassHelper.LIST_TYPE.getPlainNodeReference(), condition.property));
            } else if (comparator.equals("InRange")) {
              parameters.add(new Parameter(ClassHelper.RANGE_TYPE.getPlainNodeReference(), condition.property));
            } else if (comparator.endsWith("like")) {
              parameters.add(new Parameter(ClassHelper.STRING_TYPE, condition.property));
            } else if (condition.arguments() == 1) {
              parameters.add(new Parameter(type, condition.property));
            }
          }
        }
        ClassNode returns;
        if (name.prefix.equals("countBy")) {
          returns = ClassHelper.Integer_TYPE;
        } else if (name.prefix.equals("findAllBy") || name.prefix.equals("listOrderBy")) {
          returns = ClassHelper.LIST_TYPE.getPlainNodeReference();
          returns.setGenericsTypes(new GenericsType[] { new GenericsType(domain) });
        } else {
          returns = domain;
        }
        method = new MethodNode(name.name, Modifier.PUBLIC | Modifier.STATIC, returns, parameters.toArray(new Parameter[0]), ClassNode.EMPTY_ARRAY, null);
        method.setDeclaringClass(domain);
        method.putNodeMetaData(FinderName.class, name);
      }
      return method;
    }
  }

  /**
   * The finder a call names: a static call on a domain class, or one without a receiver inside it, of a name with a
   * finder prefix that the class does not declare. Null for every other call.
   *
   * @param enclosing the class the call is in
   */
  public static Finder finder(MethodCallExpression call, ClassNode enclosing) {
    Object kept = call.getNodeMetaData(FINDER_KEY);
    if (kept != null) {
      return kept == NO_FINDER ? null : (Finder) kept;
    }
    Finder finder = readFinder(call, enclosing);
    call.putNodeMetaData(FINDER_KEY, finder == null ? NO_FINDER : finder);
    return finder;
  }

  private static Finder readFinder(MethodCallExpression call, ClassNode enclosing) {
    String name = call.getMethodAsString();
    if (name == null || FinderName.prefixOf(name) == null) {
      return null;
    }
    Expression receiver = call.getObjectExpression();
    ClassNode type = receiver instanceof ClassExpression ? receiver.getType() : call.isImplicitThis() ? enclosing : null;
    // the class itself, not a reference to it: definition goes to its source
    ClassNode domain = type == null ? null : type.redirect();
    if (!isDomain(domain) || !GroovyASTUtils.members(() -> domain.getMethods(name), List.<MethodNode>of()).isEmpty()) {
      return null;
    }
    return finder(domain, name);
  }

  /** A finder of a domain class by its name; null when the name has no finder prefix. */
  public static Finder finder(ClassNode domain, String name) {
    Map<String, Property> properties = properties(domain);
    FinderName parsed = FinderName.parse(name, properties.keySet());
    return parsed == null ? null : new Finder(domain, parsed, properties);
  }

  public static Finder finder(MethodCallExpression call, ASTNodeVisitor ast) {
    return finder(call, (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(call, ClassNode.class, ast));
  }

  /** The words of the finder a method stands for, as hover documentation; null for any other method. */
  public static String documentation(ASTNode method) {
    FinderName finder = method.getNodeMetaData(FinderName.class);
    return finder == null ? null : "GORM dynamic finder: `" + finder.words() + "`";
  }

  /**
   * Warnings for the finder calls of a document that GORM would reject at run time: a condition on no property of the
   * class, {@code And} mixed with {@code Or}, the wrong number of arguments.
   */
  static List<Diagnostic> finderProblems(ModuleNode module, String path) {
    List<Diagnostic> problems = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ClassNode type : module.getClasses()) {
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
          super.visitMethodCallExpression(call);
          Expression name = call.getMethod();
          Finder finder;
          try {
            finder = name.getLineNumber() > 0 ? finder(call, type) : null;
          } catch (GroovyBugError | RuntimeException | LinkageError e) {
            // a class this call needs cannot be loaded: no warning for it, and the others still come
            return;
          }
          if (finder == null) {
            return;
          }
          int line = name.getLineNumber() - 1;
          int column = name.getColumnNumber() - 1;
          List<FinderName.Problem> found = new ArrayList<>(finder.name.problems);
          String arguments = found.isEmpty() ? argumentProblem(finder, call) : null;
          if (arguments != null) {
            found.add(new FinderName.Problem(arguments, 0, finder.name.name.length()));
          }
          for (FinderName.Problem problem : found) {
            Diagnostic diagnostic = new Diagnostic(path, line, column + problem.start, line, column + problem.end, Diagnostic.WARNING,
                problem.message + " (" + finder.domain.getNameWithoutPackage() + "." + finder.name.name + ")");
            // a transformation may leave a copy of the call
            if (seen.add(diagnostic.toString())) {
              problems.add(diagnostic);
            }
          }
        }
      }.visitClass(type);
    }
    return problems;
  }

  /**
   * GORM rejects a call with fewer arguments than its conditions need ({@code DynamicFinder.createFinderInvocation});
   * after them it reads a map of query settings and ignores the rest. A closure at the end is further criteria, not
   * an argument; one in a variable is counted, which can only hide a warning.
   */
  private static String argumentProblem(Finder finder, MethodCallExpression call) {
    if (!(call.getArguments() instanceof ArgumentListExpression)) {
      return null;
    }
    List<Expression> arguments = new ArrayList<>(((ArgumentListExpression) call.getArguments()).getExpressions());
    if (arguments.stream().anyMatch(a -> a instanceof SpreadExpression)) {
      return null;
    }
    if (!arguments.isEmpty() && arguments.get(arguments.size() - 1) instanceof ClosureExpression) {
      arguments.remove(arguments.size() - 1);
    }
    int needed = finder.name.arguments();
    if (arguments.size() >= needed) {
      return null;
    }
    return "This finder takes " + needed + (needed == 1 ? " argument" : " arguments") + ", not " + arguments.size();
  }

  // names that refer to properties

  /**
   * What a GORM name at a node refers to, when Groovy itself cannot say: a condition of a finder (its property; the
   * class from the prefix), a property named in a string of a criteria block or of {@code sort:}, or by an entry of a
   * {@code constraints} or {@code mapping} block. Null for anything else.
   */
  public static Target target(ASTNode node, ASTNodeVisitor ast, String text, int line, int column) {
    ASTNode parent = ast.getParent(node);
    if (node instanceof ConstantExpression && parent instanceof MethodCallExpression && ((MethodCallExpression) parent).getMethod() == node) {
      Finder finder = finder((MethodCallExpression) parent, ast);
      if (finder != null) {
        Range range = NodeRanges.of(node);
        FinderName.Condition condition = range == null ? null : finder.name.conditionAt(column - range.startColumn);
        Property property = condition == null ? null : finder.properties.get(condition.property);
        return property == null ? new Target(finder.domain, finder.domain) : target(property);
      }
    }
    Property property = named(node, ast, text, line, column);
    return property == null ? null : target(property);
  }

  private static Target target(Property property) {
    return new Target(property.declaration != null ? property.declaration : property.owner, property.owner);
  }

  /** The property a name outside a finder refers to ({@link #target}), as hover describes it; null when it names none. */
  public static ASTNode described(ASTNode node, ASTNodeVisitor ast, String text, int line, int column) {
    Property property = named(node, ast, text, line, column);
    return property == null ? null : property.node;
  }

  private static Property named(ASTNode node, ASTNodeVisitor ast, String text, int line, int column) {
    if (node instanceof ClosureExpression) {
      return whereProperty((ClosureExpression) node, ast, text, line, column);
    }
    if (!(node instanceof ConstantExpression)) {
      return null;
    }
    ASTNode parent = ast.getParent(node);
    if (parent instanceof MethodCallExpression && ((MethodCallExpression) parent).getMethod() == node) {
      MethodCallExpression call = (MethodCallExpression) parent;
      ClassNode owner = call.isImplicitThis() ? propertyBlockOwner(call, ast) : null;
      return owner == null ? null : properties(owner).get(call.getMethodAsString());
    }
    ClassNode domain = namedIn(node, ast);
    return domain == null ? null : properties(domain).get(node.getText());
  }

  /**
   * The property a name in a {@code where} block refers to: {@code Book.where { author.name == wanted }}. GORM's
   * transformation replaces the block's code with criteria calls that have no position, so the name is read from the
   * text: the identifier at the position and those before it, joined by dots, as a path from the block's class.
   */
  private static Property whereProperty(ClosureExpression block, ASTNodeVisitor ast, String text, int line, int column) {
    ClassNode domain = whereDomain(block, ast);
    if (domain == null || text == null) {
      return null;
    }
    String[] lines = text.split("\n", -1);
    String source = line < lines.length ? lines[line] : "";
    int end = Math.min(column, source.length());
    while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
      end++;
    }
    int start = end;
    while (start > 0 && (Character.isJavaIdentifierPart(source.charAt(start - 1)) || source.charAt(start - 1) == '.')) {
      start--;
    }
    Property property = null;
    for (String name : source.substring(start, end).split("\\.")) {
      property = domain == null ? null : properties(domain).get(name);
      if (property == null) {
        return null;
      }
      domain = property.association();
    }
    return property;
  }

  /** The domain class a block is a {@code where} query of ({@code where}, {@code find} or {@code findAll} with a closure), or null. */
  static ClassNode whereDomain(ClosureExpression block, ASTNodeVisitor ast) {
    MethodCall call = callTaking(block, ast);
    return call instanceof MethodCallExpression && WHERE_METHODS.contains(call.getMethodAsString()) ? criteriaDomain(block, ast) : null;
  }

  /**
   * The domain class whose properties a string names, or null when it names none: the first argument of a call in a
   * criteria block ({@code eq('title', x)}), or the value of {@code sort:} in the settings of a query.
   */
  static ClassNode namedIn(ASTNode string, ASTNodeVisitor ast) {
    if (!(string instanceof ConstantExpression) || !(((ConstantExpression) string).getValue() instanceof String)) {
      return null;
    }
    ASTNode parent = ast.getParent(string);
    if (parent instanceof MapEntryExpression) {
      MapEntryExpression entry = (MapEntryExpression) parent;
      if (entry.getValueExpression() != string || !"sort".equals(entry.getKeyExpression().getText())) {
        return null;
      }
      ASTNode call = ast.getParent(ast.getParent(parent));
      if (!(call instanceof MethodCallExpression)) {
        call = ast.getParent(call);
      }
      return call instanceof MethodCallExpression ? queried((MethodCallExpression) call, ast) : null;
    }
    ASTNode call = ast.getParent(parent);
    if (!(parent instanceof TupleExpression) || !(call instanceof MethodCallExpression) || !((MethodCallExpression) call).isImplicitThis()
        || ((TupleExpression) parent).getExpressions().indexOf(string) != 0) {
      return null;
    }
    return criteriaDomain(call, ast);
  }

  /** The domain class a query call is about: its receiver, or the class its criteria receiver was made from. */
  private static ClassNode queried(MethodCallExpression call, ASTNodeVisitor ast) {
    Expression receiver = call.getObjectExpression();
    if (receiver instanceof ClassExpression) {
      return isDomain(receiver.getType()) ? receiver.getType() : null;
    }
    if (call.isImplicitThis()) {
      return null;
    }
    return madeFrom(receiver, ast, 0);
  }

  /** {@code Book.createCriteria()}, {@code Book.where { }}, or a variable holding one: {@code Book}. */
  private static ClassNode madeFrom(Expression criteria, ASTNodeVisitor ast, int depth) {
    if (criteria instanceof MethodCallExpression) {
      MethodCallExpression call = (MethodCallExpression) criteria;
      Expression receiver = call.getObjectExpression();
      if (receiver instanceof ClassExpression) {
        return CRITERIA_METHODS.contains(call.getMethodAsString()) && isDomain(receiver.getType()) ? receiver.getType() : null;
      }
      // Book.where { }.eq(...): a chain keeps its class
      return depth < 8 && !call.isImplicitThis() ? madeFrom(receiver, ast, depth + 1) : null;
    }
    if (criteria instanceof VariableExpression && depth < 8) {
      ASTNode declared = GroovyASTUtils.getDefinition(criteria, false, ast);
      if (declared instanceof Variable && ((Variable) declared).hasInitialExpression()) {
        return madeFrom(((Variable) declared).getInitialExpression(), ast, depth + 1);
      }
      ASTNode declaration = declared == null ? null : ast.getParent(declared);
      if (declaration instanceof DeclarationExpression) {
        return madeFrom(((DeclarationExpression) declaration).getRightExpression(), ast, depth + 1);
      }
    }
    return null;
  }

  /**
   * The domain class the criteria block around a node is about, or null outside one: the class the outermost block
   * was called on, then the associated class for each block named after an association, {@code books { ... }}.
   */
  static ClassNode criteriaDomain(ASTNode node, ASTNodeVisitor ast) {
    // the calls whose closure argument holds the node, innermost first
    List<MethodCallExpression> calls = new ArrayList<>();
    for (ASTNode current = node; current != null; current = ast.getParent(current)) {
      if (current instanceof ClosureExpression) {
        MethodCall call = callTaking((ClosureExpression) current, ast);
        if (!(call instanceof MethodCallExpression)) {
          break;
        }
        calls.add((MethodCallExpression) call);
      }
    }
    for (int root = 0; root < calls.size(); root++) {
      MethodCallExpression call = calls.get(root);
      ClassNode domain = call.getObjectExpression() instanceof ClassExpression
          ? (CRITERIA_METHODS.contains(call.getMethodAsString()) && isDomain(call.getObjectExpression().getType()) ? call.getObjectExpression().getType() : null)
          : call.isImplicitThis() ? null : madeFrom(call.getObjectExpression(), ast, 0);
      if (domain == null) {
        continue;
      }
      for (int inner = root - 1; inner >= 0 && domain != null; inner--) {
        Property property = calls.get(inner).isImplicitThis() ? properties(domain).get(calls.get(inner).getMethodAsString()) : null;
        if (property != null && property.association() != null) {
          domain = property.association();
        }
      }
      return domain;
    }
    return null;
  }

  /** The call a closure is an argument of, or null. */
  static MethodCall callTaking(ClosureExpression closure, ASTNodeVisitor ast) {
    ASTNode arguments = ast.getParent(closure);
    ASTNode call = arguments == null ? null : ast.getParent(arguments);
    return arguments instanceof TupleExpression && call instanceof MethodCall && ((MethodCall) call).getArguments() == arguments ? (MethodCall) call : null;
  }

  /**
   * The class whose {@code static constraints = { }} or {@code static mapping = { }} closure holds a node directly
   * (not in a nested closure), or null.
   */
  static ClassNode propertyBlockOwner(ASTNode node, ASTNodeVisitor ast) {
    for (ASTNode current = ast.getParent(node); current != null; current = ast.getParent(current)) {
      if (current instanceof ClosureExpression) {
        ASTNode holder = ast.getParent(current);
        boolean block = (holder instanceof FieldNode || holder instanceof PropertyNode) && Modifier.isStatic(((Variable) holder).getModifiers()) && PROPERTY_BLOCKS.contains(((Variable) holder).getName())
            && ((Variable) holder).getInitialExpression() == current;
        return block ? (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(holder, ClassNode.class, ast) : null;
      }
    }
    return null;
  }

  // completion

  /**
   * Names to offer where a string names a property ({@link #namedIn}); null when the string names none.
   */
  static List<String> propertyNames(ASTNode string, ASTNodeVisitor ast) {
    ClassNode domain = namedIn(string, ast);
    return domain == null ? null : new ArrayList<>(properties(domain).keySet());
  }

  /**
   * Keys to offer for a name typed as an argument: the constraints, in an entry of a {@code constraints} block
   * ({@code title nul}); the query settings, in a query on a domain class ({@code Book.list(ma)}). Empty elsewhere.
   */
  static List<String> argumentKeys(VariableExpression typed, ASTNodeVisitor ast) {
    ASTNode arguments = ast.getParent(typed);
    ASTNode parent = arguments == null ? null : ast.getParent(arguments);
    if (!(arguments instanceof TupleExpression) || !(parent instanceof MethodCallExpression) || ((MethodCallExpression) parent).getArguments() != arguments) {
      return List.of();
    }
    MethodCallExpression call = (MethodCallExpression) parent;
    List<String> keys;
    if (call.isImplicitThis()) {
      ClassNode owner = propertyBlockOwner(call, ast);
      ASTNode holder = owner == null ? null : holderOf(call, ast);
      if (!(holder instanceof Variable) || !((Variable) holder).getName().equals("constraints") || !properties(owner).containsKey(call.getMethodAsString())) {
        return List.of();
      }
      keys = CONSTRAINTS;
    } else if (queried(call, ast) != null) {
      keys = QUERY_SETTINGS;
    } else {
      return List.of();
    }
    return unusedKeys(keys, (TupleExpression) arguments);
  }

  /** The keys not given yet among a call's named arguments. */
  static List<String> unusedKeys(List<String> keys, TupleExpression arguments) {
    Set<String> used = new HashSet<>();
    for (Expression argument : arguments.getExpressions()) {
      if (argument instanceof MapExpression) {
        ((MapExpression) argument).getMapEntryExpressions().forEach(entry -> used.add(entry.getKeyExpression().getText()));
      }
    }
    List<String> offered = new ArrayList<>(keys);
    offered.removeAll(used);
    return offered;
  }

  /** The field or property whose closure holds a node directly. */
  private static ASTNode holderOf(ASTNode node, ASTNodeVisitor ast) {
    for (ASTNode current = ast.getParent(node); current != null; current = ast.getParent(current)) {
      if (current instanceof ClosureExpression) {
        return ast.getParent(current);
      }
    }
    return null;
  }

  // closure delegates

  /**
   * The type a closure's calls go to when its own class has no such method: the {@code @DelegatesTo} type of the
   * parameter it is passed for ({@code withCriteria}, a criteria's {@code list}). Null without one.
   */
  public static ClassNode delegateOf(ClosureExpression closure, ASTNodeVisitor ast) {
    MethodCall call = callTaking(closure, ast);
    if (call == null) {
      return null;
    }
    List<Expression> arguments = ((TupleExpression) call.getArguments()).getExpressions();
    int index = arguments.indexOf(closure);
    for (MethodNode method : GroovyASTUtils.getMethodOverloadsFromCallExpression(call, ast)) {
      Parameter[] parameters = method.getParameters();
      // a closure after the parentheses is the last argument, whatever settings come before it
      Parameter parameter = index == arguments.size() - 1 && parameters.length > 0 ? parameters[parameters.length - 1] : index < parameters.length ? parameters[index] : null;
      if (parameter == null) {
        continue;
      }
      for (AnnotationNode annotation : parameter.getAnnotations()) {
        Expression value = annotation.getClassNode().getName().equals(DELEGATES_TO) ? annotation.getMember("value") : null;
        if (value instanceof ClassExpression) {
          return value.getType();
        }
      }
    }
    return null;
  }
}
