package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;

import groovyjarjarasm.asm.AnnotationVisitor;
import groovyjarjarasm.asm.ClassReader;
import groovyjarjarasm.asm.ClassVisitor;
import groovyjarjarasm.asm.FieldVisitor;
import groovyjarjarasm.asm.MethodVisitor;
import groovyjarjarasm.asm.Opcodes;
import groovyjarjarasm.asm.Type;

import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * The GSP tags a module can use, by namespace: the GSP compiler's own ({@code g:if}, {@code g:each}, ...), those of
 * the taglib classes in the module's jars (Grails's {@code g:} tags, plugins such as {@code asset:}), and those of the
 * project's {@code grails-app/taglib} sources. A taglib's namespace is its {@code static namespace}, default {@code g}.
 * Its tags are what Grails registers: on every version its {@code Closure} properties, its own and inherited (in a
 * class file Grails's transformation has typed each tag property {@code Closure}; in source a property assigned a
 * closure counts too); from Grails 8 also the methods its {@code TagDiscoveryRules} accepts, declared in the taglib or
 * woven in from a trait ({@link #isTagMethod}). A taglib that inherits from a class or trait that cannot be read
 * leaves its namespace {@link Index#complete incomplete}. Attributes and documentation come from the tag's doc
 * comment ({@code @attr name REQUIRED text}), for jar taglibs from their sources jar; a jar taglib with none has tags
 * without attributes.
 */
final class Taglibs {
  /** An attribute a tag documents. */
  static final class Attribute {
    final String name;
    final boolean required;
    final String doc;

    Attribute(String name, boolean required, String doc) {
      this.name = name;
      this.required = required;
      this.doc = doc;
    }
  }

  /** What a tag's doc comment says: its description (markdown, or null) and its attributes. */
  static final class Docs {
    static final Docs NONE = new Docs(null, List.of(), null);

    final String description;
    final List<Attribute> attributes;
    /** Where the tag is declared, or null (the GSP compiler's tags, jar taglibs without sources). */
    final Location location;

    Docs(String description, List<Attribute> attributes, Location location) {
      this.description = description;
      this.attributes = attributes;
      this.location = location;
    }

    Attribute attribute(String name) {
      for (Attribute attribute : attributes) {
        if (attribute.name.equals(name)) {
          return attribute;
        }
      }
      return null;
    }
  }

  /** A tag; {@code owner} is the binary name of the class or trait declaring it, null for the GSP compiler's own. */
  static final class Tag {
    final String namespace;
    final String name;
    final String owner;
    final boolean method;
    private final Function<Tag, Docs> reader;
    private volatile Docs docs;

    Tag(String namespace, String name, String owner, boolean method, Function<Tag, Docs> reader) {
      this.namespace = namespace;
      this.name = name;
      this.owner = owner;
      this.method = method;
      this.reader = reader;
    }

    Tag(String namespace, String name, Docs docs) {
      this(namespace, name, null, false, null);
      this.docs = docs;
    }

    /** Read on first use: for jar taglibs this extracts and reads the source file. */
    Docs docs() {
      Docs read = docs;
      if (read == null) {
        read = reader.apply(this);
        docs = read == null ? Docs.NONE : read;
      }
      return docs;
    }

    String simpleOwner() {
      return owner == null ? null : owner.substring(owner.lastIndexOf('.') + 1);
    }
  }

  /** Every tag by namespace and name, and the namespaces that may have tags not among them. */
  static final class Index {
    final Map<String, Map<String, Tag>> tags;
    private final Set<String> incomplete;

    Index(Map<String, Map<String, Tag>> tags, Set<String> incomplete) {
      this.tags = tags;
      this.incomplete = incomplete;
    }

    /**
     * False when a taglib of the namespace inherits from a class or trait that cannot be read, or when a taglib class
     * cannot be read at all (its namespace is unknown, so then no namespace is complete): a tag not in {@link #tags}
     * may still exist.
     */
    boolean complete(String namespace) {
      return !incomplete.contains(ANY) && !incomplete.contains(namespace);
    }
  }

  private static final String ANY = "*";
  private static final String CLOSURE = "Lgroovy/lang/Closure;";
  private static final Pattern ATTR = Pattern.compile("^\\s*\\*?\\s*@attr\\s+([\\w.-]+)\\s*(REQUIRED|OPTIONAL)?\\s*(.*)$", Pattern.MULTILINE);

  /** Grails 8's tag rules: on the module's class path, a taglib's methods are tags too. Grails 4 to 7 have none. */
  private static final String TAG_DISCOVERY_RULES = "org/grails/taglib/discovery/TagDiscoveryRules.class";
  /** Names TagDiscoveryRules never takes for a tag: Groovy's and Object's, and those of Grails's taglib traits. */
  private static final Set<String> NOT_TAGS = Set.of("invokeMethod", "methodMissing", "propertyMissing", "getProperty", "setProperty", "getMetaClass",
      "setMetaClass", "equals", "hashCode", "toString", "afterPropertiesSet", "currentRequestAttributes", "destroy", "initializeTagLibrary",
      "onApplicationEvent", "raw", "throwTagError", "withCodec");
  /** The packages a Groovy source imports by default, for the name of a superclass or trait. */
  private static final List<String> DEFAULT_IMPORTS = List.of("java.lang.", "java.util.", "java.io.", "java.net.", "groovy.lang.", "groovy.util.");

  /**
   * The highest API level of this Groovy's ASM, read at run time ({@code analysis} compiles against Groovy 2.5): from
   * ASM7 a visitor takes the {@code NestMembers} attribute that every class with a nested class has, Grails 8's
   * taglibs among them; ASM5 throws on it.
   */
  static final int ASM_API = asmApi();

  private static int asmApi() {
    for (String level : List.of("ASM9", "ASM8", "ASM7", "ASM6")) {
      try {
        return Opcodes.class.getField(level).getInt(null);
      } catch (ReflectiveOperationException e) {
        // an older ASM
      }
    }
    return Opcodes.ASM5;
  }

  /** The GSP compiler's tags (org.grails.gsp.compiler.tags, the same in Grails 4 to 8): not taglib closures. */
  private static final List<Tag> BUILT_IN = List.of(
      builtIn("if", "Renders its body when `test` is true, or when the environment is `env`.", "test", false, "The condition", "env", false, "An environment name"),
      builtIn("elseif", "After `g:if`: renders its body when no earlier branch did and `test` is true.", "test", false, "The condition", "env", false, "An environment name"),
      builtIn("else", "After `g:if`: renders its body when no earlier branch did."),
      builtIn("unless", "Renders its body when `test` is false.", "test", false, "The condition", "env", false, "An environment name"),
      builtIn("each", "Renders its body for each element of `in`.", "in", true, "The collection", "var", false, "Name of the element variable (default `it`)", "status", false, "Name of the index variable (needs `var`)"),
      builtIn("while", "Renders its body while `test` is true.", "test", true, "The condition"),
      builtIn("collect", "Renders its body for each result of `expr` over `in`.", "in", true, "The collection", "expr", true, "The expression applied to each element (`it`)"),
      builtIn("findAll", "Renders its body for each element of `in` that `expr` matches.", "in", true, "The collection", "expr", true, "The condition (`it` is the element)"),
      builtIn("grep", "Renders its body for each element of `in` that `filter` matches.", "in", true, "The collection", "filter", true, "A class, pattern or closure"),
      builtIn("def", "Declares a page variable.", "var", true, "The variable's name", "value", true, "Its value", "type", false, "Its type (default `def`)"));

  private static Tag builtIn(String name, String doc, Object... attributes) {
    List<Attribute> list = new ArrayList<>();
    for (int i = 0; i < attributes.length; i += 3) {
      list.add(new Attribute((String) attributes[i], (Boolean) attributes[i + 1], (String) attributes[i + 2]));
    }
    return new Tag("g", name, new Docs(doc, list, null));
  }

  private final BiFunction<String, String, List<ClassNode>> parse;
  private final Reports reports;
  /** The module's loader: superclasses and traits in its jars are read from there. */
  private final ClassLoader loader;
  /** Whether the module's Grails takes methods for tags (Grails 8). */
  private final boolean methodTags;
  /** Tags of the jar taglibs; read once (the class path does not change). */
  private volatile Index jarIndex;
  /** Class files read, by binary name; empty when there is none or it cannot be read. */
  private final Map<String, Optional<Members>> classFiles = new ConcurrentHashMap<>();
  /** The top-level classes of each project file read for tags, kept while the file's text is the same. */
  private final Map<String, FileClasses> sourceFiles = new ConcurrentHashMap<>();

  private static final class FileClasses {
    final String text;
    final List<Members> classes;

    FileClasses(String text, List<Members> classes) {
      this.text = text;
      this.classes = classes;
    }
  }

  /** One reading of the tags: its lookup, the class files it could not read and the project files it read. */
  private static final class Walk {
    final SourceLookup lookup;
    final List<String> unreadable = new ArrayList<>();
    /** The classes of each project file read, each file read once. */
    final Map<String, List<Members>> files = new HashMap<>();

    Walk(SourceLookup lookup) {
      this.lookup = lookup;
    }
  }

  /**
   * {@code parse} parses a project file (parse only); {@code loader} is the module's, whose Grails decides whether
   * methods are tags; class files that cannot be read go to {@code reports}.
   */
  Taglibs(BiFunction<String, String, List<ClassNode>> parse, Reports reports, ClassLoader loader) {
    this.parse = parse;
    this.reports = reports;
    this.loader = loader;
    this.methodTags = loader.getResource(TAG_DISCOVERY_RULES) != null;
  }

  /** Every tag, by namespace and name: project taglibs over jar taglibs over the GSP compiler's. */
  Map<String, Map<String, Tag>> all(SourceLookup lookup) {
    return index(lookup).tags;
  }

  /** {@link #all}, and the namespaces that may have more tags. */
  Index index(SourceLookup lookup) {
    Map<String, Map<String, Tag>> all = new TreeMap<>();
    for (Tag tag : BUILT_IN) {
      all.computeIfAbsent(tag.namespace, ns -> new TreeMap<>()).put(tag.name, tag);
    }
    Index jars = jarIndex(lookup);
    for (Map.Entry<String, Map<String, Tag>> entry : jars.tags.entrySet()) {
      all.computeIfAbsent(entry.getKey(), ns -> new TreeMap<>()).putAll(entry.getValue());
    }
    Set<String> incomplete = new HashSet<>(jars.incomplete);
    for (Tag tag : projectTags(lookup, incomplete)) {
      all.computeIfAbsent(tag.namespace, ns -> new TreeMap<>()).put(tag.name, tag);
    }
    return new Index(all, incomplete);
  }

  // a class or trait as far as tags go, from a class file or source

  /** A class's or trait's own tag members, and what it inherits from. */
  private static final class Members {
    /** Binary name. */
    final String name;
    /** Its source file and text; null for a class file. */
    final String path;
    final String text;
    /** For a class file, the jar of the taglib it was reached from, looked in before the loader; else null. */
    final String jar;
    /** Its {@code static namespace}, null when it sets none. */
    String namespace;
    boolean trait;
    final Set<String> closures = new LinkedHashSet<>();
    final Set<String> methods = new LinkedHashSet<>();
    /** The names its superclass may have (a class file's binary name, or those a source's imports allow); null for none. */
    List<String> superclass;
    final List<List<String>> interfaces = new ArrayList<>();

    Members(String name, String path, String text, String jar) {
      this.name = name;
      this.path = path;
      this.text = text;
      this.jar = jar;
    }
  }

  /** A method as Grails 8's TagDiscoveryRules sees it, from a class file or a syntax tree. */
  private static final class MethodShape {
    final String name;
    /** Its modifiers; {@code ACC_SYNTHETIC} for what Grails takes for generated. */
    final int access;
    /** Each parameter's type: {@code Map}, {@code Closure} or another (null). */
    final String[] types;
    /** Parameters without a default value. */
    final int required;
    /** Each parameter's name, an element null for one the class file does not name; null when it names none. */
    String[] names;
    /** {@code @Tag}: TRUE, {@code @NotATag}: FALSE, neither: null. */
    Boolean annotated;

    MethodShape(String name, int access, String[] types, int required) {
      this.name = name;
      this.access = access;
      this.types = types;
      this.required = required;
    }

    boolean is(int index, String type, String expectedName) {
      return type.equals(types[index]) && (names == null || names[index] == null || names[index].equals(expectedName));
    }
  }

  /**
   * Grails 8's TagDiscoveryRules.isTagMethod: a public instance method that is not generated, and is annotated
   * {@code @Tag}, or is not annotated {@code @NotATag}, is none of {@link #NOT_TAGS}, is no property accessor and can be
   * called as {@code (Map attrs)}, {@code (Closure body)} or {@code (Map attrs, Closure body)}. Parameter names are
   * checked where the class file keeps them; in source Grails checks them when the build keeps them, which is not
   * known here, so any name passes.
   */
  private static boolean isTagMethod(MethodShape method) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0 || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
      return false;
    }
    if (method.annotated != null) {
      return method.annotated;
    }
    String name = method.name;
    int count = method.types.length;
    if (name.isEmpty() || name.charAt(0) == '<' || name.indexOf('$') >= 0 || NOT_TAGS.contains(name)
        || count == 0 && (name.startsWith("get") || name.startsWith("is")) || count == 1 && name.startsWith("set")) {
      return false;
    }
    for (int arity = Math.max(method.required, 1); arity <= Math.min(count, 2); arity++) {
      if (arity == 1 && (method.is(0, "Map", "attrs") || method.is(0, "Closure", "body"))
          || arity == 2 && method.is(0, "Map", "attrs") && method.is(1, "Closure", "body")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTagName(String name) {
    return !name.contains("$") && !name.contains("__") && !name.equals("metaClass") && !name.equals("invokeMethod");
  }

  /** The property a getter reads, as Groovy names it ({@code getURL}: {@code URL}). */
  private static String property(String getter) {
    String name = getter.substring(3);
    return name.length() > 1 && Character.isUpperCase(name.charAt(1)) ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  /**
   * Adds the tags of {@code type} (the taglib when {@code own}) and of what it inherits to {@code found}, in namespace
   * {@code ns}, the subclass's first. False when a superclass or trait cannot be read.
   */
  private boolean collect(Members type, boolean own, String ns, Map<String, Tag> found, Set<String> seen, Walk walk) {
    if (!seen.add(type.name)) {
      return true;
    }
    for (String name : type.closures) {
      found.putIfAbsent(name, tag(ns, name, type, false, walk.lookup));
    }
    // Grails 8 dispatches to the methods the taglib declares: a trait's are woven in, a superclass's are not
    if (own && methodTags) {
      for (String name : type.methods) {
        found.putIfAbsent(name, tag(ns, name, type, true, walk.lookup));
      }
    }
    boolean complete = true;
    // a class file has its traits' members woven in already: only a source class or a trait is followed to its traits
    if (type.path != null || type.trait) {
      for (List<String> names : type.interfaces) {
        Members implemented = resolve(type, names, walk);
        if (implemented == null) {
          complete = false;
        } else if (implemented.trait) {
          complete &= collect(implemented, own, ns, found, seen, walk);
        }
      }
    }
    if (type.superclass != null) {
      Members superclass = resolve(type, type.superclass, walk);
      complete &= superclass != null && collect(superclass, false, ns, found, seen, walk);
    }
    return complete;
  }

  private static Tag tag(String ns, String name, Members owner, boolean method, SourceLookup lookup) {
    if (owner.path == null) {
      return new Tag(ns, name, owner.name, method, tag -> jarDocs(tag, lookup));
    }
    Tag tag = new Tag(ns, name, owner.name, method, null);
    tag.docs = docs(tag, owner.path, owner.text);
    return tag;
  }

  /** The first of {@code names} a project source (from a source class only) or a class file declares; null when none does. */
  private Members resolve(Members from, List<String> names, Walk walk) {
    for (String name : names) {
      if (from.path != null) {
        Members source = sourceClass(name, from.path, walk);
        String file = source == null ? walk.lookup.fileOf(name) : null;
        if (file != null) {
          source = sourceClass(name, file, walk);
        }
        if (source != null) {
          return source;
        }
      }
      Members classFile = classFile(name, from.jar, walk);
      if (classFile != null) {
        return classFile;
      }
    }
    return null;
  }

  // class files: the jars' taglibs, and superclasses and traits in jars

  private Index jarIndex(SourceLookup lookup) {
    Index read = jarIndex;
    if (read == null) {
      Map<String, Map<String, Tag>> tags = new TreeMap<>();
      Set<String> incomplete = new HashSet<>();
      Walk walk = new Walk(lookup);
      for (Map.Entry<String, String> taglib : lookup.taglibClasses().entrySet()) {
        Members type = classFile(taglib.getKey(), taglib.getValue(), walk);
        if (type == null) {
          incomplete.add(ANY);
          continue;
        }
        String ns = type.namespace == null ? "g" : type.namespace;
        Map<String, Tag> found = new LinkedHashMap<>();
        if (!collect(type, true, ns, found, new HashSet<>(), walk)) {
          incomplete.add(ns);
        }
        Map<String, Tag> namespace = tags.computeIfAbsent(ns, key -> new TreeMap<>());
        found.values().forEach(tag -> namespace.putIfAbsent(tag.name, tag));
      }
      if (!walk.unreadable.isEmpty()) {
        List<String> unreadable = walk.unreadable;
        reports.show("taglib class files", "the tags of " + unreadable.size() + (unreadable.size() == 1 ? " taglib class are" : " taglib classes are")
            + " unknown: " + unreadable.get(0) + (unreadable.size() > 1 ? " (and " + (unreadable.size() - 1) + " more)" : ""));
      }
      read = new Index(tags, incomplete);
      jarIndex = read;
    }
    return read;
  }

  /**
   * A class file: from {@code jar} when not null (a taglib's jar, which may not be on the compile's class path), else
   * through the module's loader. Null when there is none, or when it cannot be read; then why goes to the walk.
   */
  private Members classFile(String className, String jar, Walk walk) {
    Optional<Members> kept = classFiles.get(className);
    if (kept != null) {
      return kept.orElse(null);
    }
    String resource = className.replace('.', '/') + ".class";
    String where = jar;
    Members read = null;
    try {
      byte[] bytes = null;
      if (jar != null) {
        try (ZipFile zip = new ZipFile(jar)) {
          ZipEntry entry = zip.getEntry(resource);
          if (entry != null) {
            try (InputStream in = zip.getInputStream(entry)) {
              bytes = in.readAllBytes();
            }
          }
        }
      }
      if (bytes == null) {
        where = "the class path";
        try (InputStream in = loader.getResourceAsStream(resource)) {
          bytes = in == null ? null : in.readAllBytes();
        }
      }
      read = bytes == null ? null : classFile(className, bytes, jar);
    } catch (IOException | RuntimeException e) {
      // a jar that cannot be opened, a class file this Groovy's ASM cannot read
      walk.unreadable.add("cannot read " + className + " in " + where + ": " + e);
    }
    classFiles.put(className, Optional.ofNullable(read));
    return read;
  }

  private static Members classFile(String className, byte[] bytes, String jar) {
    Members type = new Members(className, null, null, jar);
    new ClassReader(bytes).accept(new ClassVisitor(ASM_API) {
      @Override
      public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if (superName != null && !superName.equals("java/lang/Object")) {
          type.superclass = List.of(superName.replace('/', '.'));
        }
        for (String implemented : interfaces == null ? new String[0] : interfaces) {
          type.interfaces.add(List.of(implemented.replace('/', '.')));
        }
      }

      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        type.trait |= descriptor.equals("Lgroovy/transform/Trait;");
        return null;
      }

      @Override
      public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if ((access & Opcodes.ACC_STATIC) == 0 && CLOSURE.equals(descriptor) && isTagName(name)) {
          type.closures.add(name);
        }
        return null;
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (name.equals("<clinit>")) {
          return namespaceReader(className.replace('.', '/'), type);
        }
        if ((access & Opcodes.ACC_PUBLIC) == 0 || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
          return null;
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        // a Closure property, which Grails reads through its getter: one woven in from a trait has no field of its name
        if (arguments.length == 0 && name.length() > 3 && name.startsWith("get") && CLOSURE.equals(Type.getReturnType(descriptor).getDescriptor())
            && isTagName(property(name))) {
          type.closures.add(property(name));
        }
        String[] types = new String[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
          String argument = arguments[i].getDescriptor();
          types[i] = argument.equals("Ljava/util/Map;") ? "Map" : argument.equals(CLOSURE) ? "Closure" : null;
        }
        MethodShape method = new MethodShape(name, access, types, types.length);
        return new MethodVisitor(ASM_API) {
          private final List<String> names = new ArrayList<>();

          @Override
          public void visitParameter(String parameter, int parameterAccess) {
            names.add(parameter);
          }

          @Override
          public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
            if (annotation.equals("Lgrails/gsp/Tag;") || annotation.equals("Lgrails/gsp/NotATag;")) {
              method.annotated = annotation.equals("Lgrails/gsp/Tag;");
            }
            return null;
          }

          @Override
          public void visitEnd() {
            // the MethodParameters attribute, which reflection's Parameter.isNamePresent reads
            method.names = names.size() == types.length ? names.toArray(new String[0]) : null;
            if (isTagMethod(method)) {
              type.methods.add(name);
            }
          }
        };
      }
    }, ClassReader.SKIP_FRAMES);
    return type;
  }

  /** Reads {@code static namespace = 'x'} from the static initializer: the string constant last loaded before it is set. */
  private static MethodVisitor namespaceReader(String owner, Members type) {
    return new MethodVisitor(ASM_API) {
      private Object last;

      @Override
      public void visitLdcInsn(Object value) {
        last = value;
      }

      @Override
      public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
        if (opcode == Opcodes.PUTSTATIC && fieldOwner.equals(owner) && name.equals("namespace") && last instanceof String && type.namespace == null) {
          type.namespace = (String) last;
        }
      }
    };
  }

  private static Docs jarDocs(Tag tag, SourceLookup lookup) {
    String topLevel = tag.owner.contains("$") ? tag.owner.substring(0, tag.owner.indexOf('$')) : tag.owner;
    String file = lookup.classSource(topLevel);
    if (file == null) {
      return Docs.NONE;
    }
    try {
      return docs(tag, file, Files.readString(Path.of(file)));
    } catch (IOException | RuntimeException e) {
      return Docs.NONE;
    }
  }

  /** The tag's declaration and doc comment in a source text, found by name: a property, else a method. */
  private static Docs docs(Tag tag, String file, String text) {
    SourceText source = new SourceText(text);
    SourceText.Found found = source.find(List.of(tag.simpleOwner()), tag.name, tag.method, tag.method ? List.of("Map") : null);
    if (found == null) {
      return Docs.NONE;
    }
    Location location = new Location(file, found.range);
    if (found.comment == null) {
      return new Docs(null, List.of(), location);
    }
    return new Docs(Javadoc.markdown(found.comment), attributes(found.comment), location);
  }

  /** The {@code @attr} tags of a doc comment, a text running on to the next tag. */
  static List<Attribute> attributes(String comment) {
    String body = comment.replaceFirst("^/\\*\\*", "").replaceFirst("\\*/$", "");
    Map<String, Attribute> found = new LinkedHashMap<>();
    Matcher matcher = ATTR.matcher(body);
    while (matcher.find()) {
      String doc = matcher.group(3).trim();
      found.putIfAbsent(matcher.group(1), new Attribute(matcher.group(1), "REQUIRED".equals(matcher.group(2)), doc.isEmpty() ? null : doc));
    }
    return new ArrayList<>(found.values());
  }

  // project taglibs: parsed from source (open text first), every module's grails-app/taglib

  private List<Tag> projectTags(SourceLookup lookup, Set<String> incomplete) {
    List<String> files = new ArrayList<>();
    for (String app : lookup.grailsAppDirs()) {
      Path dir = Path.of(app, "taglib");
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.filter(file -> file.getFileName().toString().endsWith("TagLib.groovy")).sorted().map(Path::toString).filter(path -> !files.contains(path))
            .forEach(files::add);
      } catch (IOException e) {
        // an unreadable directory: its taglibs are unknown
      }
    }
    Walk walk = new Walk(lookup);
    List<Tag> tags = new ArrayList<>();
    for (String path : files) {
      for (Members type : sourceClasses(path, walk)) {
        if (!type.name.endsWith("TagLib")) {
          continue;
        }
        String ns = type.namespace == null ? "g" : type.namespace;
        Map<String, Tag> found = new LinkedHashMap<>();
        if (!collect(type, true, ns, found, new HashSet<>(), walk)) {
          incomplete.add(ns);
        }
        tags.addAll(found.values());
      }
    }
    for (String unreadable : walk.unreadable) {
      reports.show("taglib class " + unreadable, "the tags a project taglib inherits are unknown: " + unreadable);
    }
    sourceFiles.keySet().retainAll(walk.files.keySet());
    return tags;
  }

  /** The top-level class {@code name} of a project file, or null. */
  private Members sourceClass(String name, String path, Walk walk) {
    for (Members type : sourceClasses(path, walk)) {
      if (type.name.equals(name)) {
        return type;
      }
    }
    return null;
  }

  private List<Members> sourceClasses(String path, Walk walk) {
    return walk.files.computeIfAbsent(path, file -> sourceClasses(file, walk.lookup));
  }

  private List<Members> sourceClasses(String path, SourceLookup lookup) {
    String text = lookup.openText(path);
    if (text == null) {
      try {
        text = Files.readString(Path.of(path));
      } catch (IOException e) {
        return List.of();
      }
    }
    FileClasses kept = sourceFiles.get(path);
    if (kept != null && kept.text.equals(text)) {
      return kept.classes;
    }
    List<Members> classes = new ArrayList<>();
    for (ClassNode type : parse.apply(path, text)) {
      if (type.getOuterClass() == null) {
        classes.add(sourceMembers(type, path, text));
      }
    }
    sourceFiles.put(path, new FileClasses(text, classes));
    return classes;
  }

  private static Members sourceMembers(ClassNode type, String path, String text) {
    Members members = new Members(type.getName(), path, text, null);
    FieldNode namespace = type.getField("namespace");
    if (namespace != null && namespace.isStatic() && namespace.getInitialExpression() instanceof ConstantExpression) {
      members.namespace = String.valueOf(((ConstantExpression) namespace.getInitialExpression()).getValue());
    }
    for (AnnotationNode annotation : type.getAnnotations()) {
      members.trait |= named(annotation.getClassNode(), "groovy.transform.Trait");
    }
    for (FieldNode field : type.getFields()) {
      if (!field.isStatic() && isTagName(field.getName()) && (field.getInitialExpression() instanceof ClosureExpression || named(field.getType(), "groovy.lang.Closure"))) {
        members.closures.add(field.getName());
      }
    }
    for (MethodNode method : type.getMethods()) {
      Parameter[] parameters = method.getParameters();
      String[] types = new String[parameters.length];
      int required = 0;
      for (int i = 0; i < parameters.length; i++) {
        types[i] = named(parameters[i].getType(), "java.util.Map") ? "Map" : named(parameters[i].getType(), "groovy.lang.Closure") ? "Closure" : null;
        required += parameters[i].hasInitialExpression() ? 0 : 1;
      }
      // Grails takes an abstract method in source for generated: a trait's super accessors
      MethodShape shape = new MethodShape(method.getName(), method.getModifiers() | (method.isSynthetic() || method.isAbstract() ? Opcodes.ACC_SYNTHETIC : 0), types, required);
      for (AnnotationNode annotation : method.getAnnotations()) {
        if (named(annotation.getClassNode(), "grails.gsp.Tag") || named(annotation.getClassNode(), "grails.gsp.NotATag")) {
          shape.annotated = named(annotation.getClassNode(), "grails.gsp.Tag");
        }
      }
      if (isTagMethod(shape)) {
        members.methods.add(method.getName());
      }
    }
    ClassNode superclass = type.getUnresolvedSuperClass(false);
    if (superclass != null && !superclass.getName().equals("java.lang.Object")) {
      members.superclass = candidates(type, superclass.getName());
    }
    for (ClassNode implemented : type.getInterfaces()) {
      members.interfaces.add(candidates(type, implemented.getName()));
    }
    return members;
  }

  /** Whether a type as written (not resolved: the parse stops before) is {@code qualified}, by that or its simple name. */
  private static boolean named(ClassNode type, String qualified) {
    String name = type.getName();
    return name.equals(qualified) || name.equals(qualified.substring(qualified.lastIndexOf('.') + 1));
  }

  /** The classes a name written in a source may be, in Groovy's order: imported, same package, star imports, default imports. */
  private static List<String> candidates(ClassNode type, String written) {
    if (written.indexOf('.') >= 0) {
      return List.of(written);
    }
    ModuleNode module = type.getModule();
    ImportNode imported = module == null ? null : module.getImport(written);
    if (imported != null) {
      return List.of(imported.getType().getName());
    }
    List<String> names = new ArrayList<>();
    String pkg = module == null || module.getPackageName() == null ? "" : module.getPackageName();
    names.add(pkg.isEmpty() || pkg.endsWith(".") ? pkg + written : pkg + "." + written);
    if (module != null) {
      for (ImportNode star : module.getStarImports()) {
        names.add(star.getPackageName() + written);
      }
    }
    DEFAULT_IMPORTS.forEach(prefix -> names.add(prefix + written));
    return names.stream().distinct().collect(Collectors.toList());
  }
}
