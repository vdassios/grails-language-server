package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;

import groovyjarjarasm.asm.ClassReader;
import groovyjarjarasm.asm.ClassVisitor;
import groovyjarjarasm.asm.FieldVisitor;
import groovyjarjarasm.asm.MethodVisitor;
import groovyjarjarasm.asm.Opcodes;

import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * The GSP tags a module can use, by namespace: the GSP compiler's own ({@code g:if}, {@code g:each}, ...), those of
 * the taglib classes in the module's jars (Grails's {@code g:} tags, plugins such as {@code asset:}), and those of the
 * project's {@code grails-app/taglib} sources. A taglib's namespace is its {@code static namespace}, default {@code g};
 * a tag is a property assigned a closure or (in source only) a public method whose first parameter is a {@code Map};
 * in a class file Grails's transformation has typed each tag property {@code Closure}, and the public {@code Map}
 * methods there are helpers ({@code messageImpl}) and the generated tag overloads, so only those properties count. Attributes and
 * documentation come from the tag's doc comment ({@code @attr name REQUIRED text}), for jar taglibs from their
 * sources jar; a jar taglib with none has tags without attributes.
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

  /** A tag; {@code owner} is its taglib's binary name, null for the GSP compiler's own. */
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

  private static final String CLOSURE = "Lgroovy/lang/Closure;";
  private static final Pattern ATTR = Pattern.compile("^\\s*\\*?\\s*@attr\\s+([\\w.-]+)\\s*(REQUIRED|OPTIONAL)?\\s*(.*)$", Pattern.MULTILINE);

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
  /** Tags of the jar taglibs, by namespace and name; read once (the class path does not change). */
  private volatile Map<String, Map<String, Tag>> jarTags;
  /** Tags of each project taglib file, kept while the file's text is the same. */
  private final Map<String, FileTags> fileTags = new ConcurrentHashMap<>();

  private static final class FileTags {
    final String text;
    final List<Tag> tags;

    FileTags(String text, List<Tag> tags) {
      this.text = text;
      this.tags = tags;
    }
  }

  /** {@code parse} parses a project file (parse only). */
  Taglibs(BiFunction<String, String, List<ClassNode>> parse) {
    this.parse = parse;
  }

  /** Every tag, by namespace and name: project taglibs over jar taglibs over the GSP compiler's. */
  Map<String, Map<String, Tag>> all(SourceLookup lookup) {
    Map<String, Map<String, Tag>> all = new TreeMap<>();
    for (Tag tag : BUILT_IN) {
      all.computeIfAbsent(tag.namespace, ns -> new TreeMap<>()).put(tag.name, tag);
    }
    for (Map.Entry<String, Map<String, Tag>> entry : jarTags(lookup).entrySet()) {
      all.computeIfAbsent(entry.getKey(), ns -> new TreeMap<>()).putAll(entry.getValue());
    }
    for (Tag tag : projectTags(lookup)) {
      all.computeIfAbsent(tag.namespace, ns -> new TreeMap<>()).put(tag.name, tag);
    }
    return all;
  }

  // jar taglibs: the class file says the namespace and the tags, the sources jar the documentation

  private Map<String, Map<String, Tag>> jarTags(SourceLookup lookup) {
    Map<String, Map<String, Tag>> read = jarTags;
    if (read == null) {
      read = new TreeMap<>();
      for (Map.Entry<String, String> taglib : lookup.taglibClasses().entrySet()) {
        for (Tag tag : classFileTags(taglib.getKey(), taglib.getValue(), lookup)) {
          read.computeIfAbsent(tag.namespace, ns -> new TreeMap<>()).putIfAbsent(tag.name, tag);
        }
      }
      jarTags = read;
    }
    return read;
  }

  /** The tags of a taglib class, read from its class file in {@code jar} (which may not be on the compile's class path). */
  private static List<Tag> classFileTags(String className, String jar, SourceLookup lookup) {
    String internalName = className.replace('.', '/');
    byte[] bytes;
    try (ZipFile zip = new ZipFile(jar)) {
      ZipEntry entry = zip.getEntry(internalName + ".class");
      if (entry == null) {
        return List.of();
      }
      try (InputStream in = zip.getInputStream(entry)) {
        bytes = in.readAllBytes();
      }
    } catch (IOException e) {
      return List.of();
    }
    Set<String> closures = new LinkedHashSet<>();
    String[] namespace = { null };
    try {
      new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
          if ((access & Opcodes.ACC_STATIC) == 0 && CLOSURE.equals(descriptor) && isTagName(name)) {
            closures.add(name);
          }
          return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
          return name.equals("<clinit>") ? namespaceReader(internalName, namespace) : null;
        }
      }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (RuntimeException e) {
      return List.of(); // a class file this Groovy's ASM cannot read
    }
    String ns = namespace[0] == null ? "g" : namespace[0];
    List<Tag> tags = new ArrayList<>();
    for (String name : closures) {
      tags.add(new Tag(ns, name, className, false, tag -> jarDocs(tag, lookup)));
    }
    return tags;
  }

  /** Reads {@code static namespace = 'x'} from the static initializer: the string constant last loaded before it is set. */
  private static MethodVisitor namespaceReader(String owner, String[] namespace) {
    return new MethodVisitor(Opcodes.ASM5) {
      private Object last;

      @Override
      public void visitLdcInsn(Object value) {
        last = value;
      }

      @Override
      public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
        if (opcode == Opcodes.PUTSTATIC && fieldOwner.equals(owner) && name.equals("namespace") && last instanceof String && namespace[0] == null) {
          namespace[0] = (String) last;
        }
      }
    };
  }

  private static boolean isTagName(String name) {
    return !name.contains("$") && !name.contains("__") && !name.equals("metaClass") && !name.equals("invokeMethod");
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

  private List<Tag> projectTags(SourceLookup lookup) {
    List<Tag> tags = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String app : lookup.grailsAppDirs()) {
      Path dir = Path.of(app, "taglib");
      if (!Files.isDirectory(dir)) {
        continue;
      }
      List<Path> files;
      try (Stream<Path> walk = Files.walk(dir)) {
        files = walk.filter(file -> file.getFileName().toString().endsWith("TagLib.groovy")).sorted().collect(java.util.stream.Collectors.toList());
      } catch (IOException e) {
        continue;
      }
      for (Path file : files) {
        String path = file.toString();
        if (seen.add(path)) {
          tags.addAll(fileTags(path, lookup));
        }
      }
    }
    fileTags.keySet().retainAll(seen);
    return tags;
  }

  private List<Tag> fileTags(String path, SourceLookup lookup) {
    String text = lookup.openText(path);
    if (text == null) {
      try {
        text = Files.readString(Path.of(path));
      } catch (IOException e) {
        return List.of();
      }
    }
    FileTags kept = fileTags.get(path);
    if (kept != null && kept.text.equals(text)) {
      return kept.tags;
    }
    List<Tag> tags = new ArrayList<>();
    String source = text;
    for (ClassNode type : parse.apply(path, text)) {
      if (!type.getNameWithoutPackage().endsWith("TagLib") || type.getOuterClass() != null) {
        continue;
      }
      FieldNode namespaceField = type.getField("namespace");
      String ns = namespaceField != null && namespaceField.isStatic() && namespaceField.getInitialExpression() instanceof ConstantExpression
          ? String.valueOf(((ConstantExpression) namespaceField.getInitialExpression()).getValue()) : "g";
      Set<String> names = new LinkedHashSet<>();
      for (FieldNode field : type.getFields()) {
        if (!field.isStatic() && field.getInitialExpression() instanceof ClosureExpression && isTagName(field.getName()) && names.add(field.getName())) {
          tags.add(sourceTag(ns, field.getName(), type.getName(), false, path, source));
        }
      }
      for (MethodNode method : type.getMethods()) {
        Parameter[] parameters = method.getParameters();
        if (method.isPublic() && !method.isStatic() && parameters.length > 0 && isMap(parameters[0]) && isTagName(method.getName()) && names.add(method.getName())) {
          tags.add(sourceTag(ns, method.getName(), type.getName(), true, path, source));
        }
      }
    }
    fileTags.put(path, new FileTags(text, tags));
    return tags;
  }

  private static boolean isMap(Parameter parameter) {
    String type = parameter.getType().getName();
    return type.equals("java.util.Map") || parameter.isDynamicTyped() && parameter.getName().equals("attrs");
  }

  private static Tag sourceTag(String ns, String name, String owner, boolean method, String path, String text) {
    Tag tag = new Tag(ns, name, owner, method, null);
    tag.docs = docs(tag, path, text);
    return tag;
  }
}
