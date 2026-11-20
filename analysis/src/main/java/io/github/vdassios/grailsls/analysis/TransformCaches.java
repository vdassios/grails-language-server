package io.github.vdassios.grailsls.analysis;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static caches of Groovy's compiler and of Grails, GORM and Micronaut AST transformations, keyed by AST nodes or class
 * names, emptied after every compile. They are filled during a compile and never emptied, so each one keeps every AST
 * it ever saw (measured: a sweep of a Grails 4 module grew the heap to 2.5 GB with nothing kept), and a later compile
 * could read class nodes of an older one. A compile needs them only while it runs. Classes missing in the project are
 * skipped; a class present without its field, or with a field of another shape, is logged once and skipped.
 * Grails's class injectors also remember what they did, and skip it in the next compile: emptied too.
 */
final class TransformCaches {
  /**
   * {@code {class, static field}}, plus a third element for a field only some versions of its class have (from
   * that version on): its absence is not logged.
   */
  private static final String[][] FIELDS = {
      // controller and command object data binding (Grails 4 to 7)
      {"org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_WHITE_LIST_PROPERTY_NAMES"},
      {"org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_EXPLICITLY_BINDABLE_SPECIAL_PROPERTY_NAMES", "Grails 7"},
      // GORM (Grails 4 to 7): a @Memoized method, and properties by class name
      {"org.grails.datastore.mapping.reflect.AstUtils", "memoizedMethodClosure$isDomainClassClassNode"},
      {"org.grails.datastore.gorm.transform.AstPropertyResolveUtils", "cachedClassProperties"},
      // Micronaut's Groovy visitors (Micronaut 1 to 4, in apps that use them): annotation metadata by AST node
      // (measured: 126,114 entries after two dozen compiles of a Grails 7 app, holding every compile's AST)
      {"io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder", "MUTATED_ANNOTATION_METADATA"},
      // Micronaut OpenAPI's visitor: endpoints and constructors, as AST elements
      {"io.micronaut.openapi.visitor.Utils", "endpointInfos"},
      {"io.micronaut.openapi.visitor.Utils", "creatorConstructorsCache"},
      // Micronaut's type element visitors: emptied by its transform at the end of a full compile, which the server
      // never reaches; holds the compile unit (a static map in Micronaut 1, a thread-local in 3 and 4)
      {"io.micronaut.ast.groovy.TypeElementVisitorTransform", "loadedVisitors"},
      {"io.micronaut.ast.groovy.TypeElementVisitorTransform", "beanDefinitionBuilders", "Micronaut 3"},
      // Groovy 2.5 to 5: parameterized types by generics placeholders; the keys hold class nodes, up to 64 per loader
      // (measured: with 51 contexts, mdm-core's sweep filled 4 GB of heap after ~550 compiles)
      {"org.codehaus.groovy.ast.tools.GenericsUtils", "PARAMETERIZED_TYPE_CACHE"},
  };

  /** Grails's class injectors, created once per loader and kept in these static arrays (Grails 4 to 7). */
  private static final String INJECTORS = "org.grails.compiler.injection.GrailsAwareInjectionOperation";
  private static final List<String> INJECTOR_ARRAYS = List.of("classInjectors", "globalClassInjectors");
  /**
   * What an injector has done, by class name or class node: it skips those classes from then on, so from the second
   * compile on a class pulled in again lost the members it adds, e.g. a domain class's {@code (Map)} constructor
   * (artefact transformers, global injectors, domain {@code toString}).
   */
  private static final Set<String> INJECTOR_STATE = Set.of("classesTransformedByThis", "processesClassNames", "classesWithInjectedToString");

  private static final String NOT_EMPTIED = "that cache is not emptied after compiles, so the server's memory may grow";

  private final ClassLoader loader;
  private final String[][] listed;
  private final Reports reports;
  private List<Field> fields;
  private List<Field> injectorArrays;
  private final Map<Class<?>, List<Field>> injectorState = new HashMap<>();

  TransformCaches(ClassLoader loader, Reports reports) {
    this(loader, FIELDS, reports);
  }

  /** With other {@code {class, static field}} pairs than {@link #FIELDS}, for tests. */
  TransformCaches(ClassLoader loader, String[][] listed, Reports reports) {
    this.loader = loader;
    this.listed = listed;
    this.reports = reports;
  }

  /** Empties every cache. Call between compiles, on the compile thread. */
  void clear() {
    for (Field field : fields()) {
      try {
        Object value = field.get(null);
        if (value instanceof ThreadLocal) {
          ((ThreadLocal<?>) value).remove();
          continue;
        }
        Map<?, ?> map = value instanceof Map ? (Map<?, ?>) value : memoizeCache(value);
        if (map != null) {
          map.clear();
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // a different shape in this version: leave it
        String name = field.getDeclaringClass().getName() + "." + field.getName();
        reports.log("cache shape " + name, name + " has a shape the server does not know (" + e + "): " + NOT_EMPTIED);
      }
    }
    for (Field array : injectorArrays()) {
      try {
        Object[] injectors = (Object[]) array.get(null);
        for (Object injector : injectors == null ? new Object[0] : injectors) {
          for (Field field : injectorState.computeIfAbsent(injector.getClass(), TransformCaches::stateFields)) {
            Object value = field.get(injector);
            if (value instanceof Collection) {
              ((Collection<?>) value).clear();
            }
          }
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // a different shape in this version: leave it
      }
    }
  }

  private List<Field> injectorArrays() {
    if (injectorArrays == null) {
      List<Field> found = new ArrayList<>();
      for (String name : INJECTOR_ARRAYS) {
        Field field = staticField(INJECTORS, name, true);
        if (field != null && field.getType().isArray()) {
          found.add(field);
        }
      }
      injectorArrays = found;
    }
    return injectorArrays;
  }

  /** The {@link #INJECTOR_STATE} fields of an injector class and its superclasses. */
  private static List<Field> stateFields(Class<?> type) {
    List<Field> found = new ArrayList<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) && INJECTOR_STATE.contains(field.getName())) {
          field.setAccessible(true);
          found.add(field);
        }
      }
    }
    return found;
  }

  private List<Field> fields() {
    if (fields == null) {
      List<Field> found = new ArrayList<>();
      for (String[] entry : listed) {
        Field field = staticField(entry[0], entry[1], entry.length < 3);
        if (field != null) {
          found.add(field);
        }
      }
      fields = found;
    }
    return fields;
  }

  /**
   * A static field, made accessible; null when its class is not in the project, or (logged) when the class is there
   * but the field is not ({@code always}: every version of the class has it), is not static or cannot be made accessible.
   */
  private Field staticField(String className, String fieldName, boolean always) {
    Class<?> type;
    try {
      type = Class.forName(className, false, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      return null; // not in this project
    }
    String name = className + "." + fieldName;
    try {
      Field field = type.getDeclaredField(fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        reports.log("cache field " + name, name + " is not static: " + NOT_EMPTIED);
        return null;
      }
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      if (always) {
        reports.log("cache field " + name, name + " is missing: " + NOT_EMPTIED);
      }
    } catch (RuntimeException e) {
      reports.log("cache field " + name, name + " cannot be read (" + e + "): " + NOT_EMPTIED);
    }
    return null;
  }

  /** The cache of a closure made by {@code @Memoized}: a {@code Map} in Groovy 2.5 to 4. */
  private static Map<?, ?> memoizeCache(Object closure) throws ReflectiveOperationException {
    if (closure == null) {
      return null;
    }
    Field cache = closure.getClass().getDeclaredField("cache");
    cache.setAccessible(true);
    Object value = cache.get(closure);
    return value instanceof Map ? (Map<?, ?>) value : null;
  }
}
