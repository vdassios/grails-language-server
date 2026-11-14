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
 * Static caches of Grails, GORM and Micronaut AST transformations, keyed by AST nodes or class names, emptied after
 * every compile. They are filled during a compile and never emptied, so each one keeps every AST it ever saw
 * (measured: a sweep of a Grails 4 module grew the heap to 2.5 GB with nothing kept), and a later compile could read
 * class nodes of an older one. A compile needs them only while it runs. Fields missing in the project are skipped.
 * Grails's class injectors also remember what they did, and skip it in the next compile: emptied too.
 */
final class TransformCaches {
  private static final String[][] FIELDS = {
      // controller and command object data binding (Grails 4 to 7)
      {"org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_WHITE_LIST_PROPERTY_NAMES"},
      {"org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_EXPLICITLY_BINDABLE_SPECIAL_PROPERTY_NAMES"},
      // GORM (Grails 4 to 7): a @Memoized method, and properties by class name
      {"org.grails.datastore.mapping.reflect.AstUtils", "memoizedMethodClosure$isDomainClassClassNode"},
      {"org.grails.datastore.gorm.transform.AstPropertyResolveUtils", "cachedClassProperties"},
      // Micronaut's Groovy visitors (Micronaut 1 to 4, in apps that use them): annotation metadata by AST node
      // (measured: 126,114 entries after two dozen compiles of a Grails 7 app, holding every compile's AST)
      {"io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder", "MUTATED_ANNOTATION_METADATA"},
      // Micronaut OpenAPI's visitor: endpoints and constructors, as AST elements
      {"io.micronaut.openapi.visitor.Utils", "endpointInfos"},
      {"io.micronaut.openapi.visitor.Utils", "creatorConstructorsCache"},
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

  private final ClassLoader loader;
  private List<Field> fields;
  private List<Field> injectorArrays;
  private final Map<Class<?>, List<Field>> injectorState = new HashMap<>();

  TransformCaches(ClassLoader loader) {
    this.loader = loader;
  }

  /** Empties every cache. Call between compiles, on the compile thread. */
  void clear() {
    for (Field field : fields()) {
      try {
        Object value = field.get(null);
        Map<?, ?> map = value instanceof Map ? (Map<?, ?>) value : memoizeCache(value);
        if (map != null) {
          map.clear();
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // a different shape in this version: leave it
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
        try {
          Field field = Class.forName(INJECTORS, false, loader).getDeclaredField(name);
          if (Modifier.isStatic(field.getModifiers()) && field.getType().isArray()) {
            field.setAccessible(true);
            found.add(field);
          }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
          // not a Grails project
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
      for (String[] entry : FIELDS) {
        try {
          Field field = Class.forName(entry[0], false, loader).getDeclaredField(entry[1]);
          if (Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true);
            found.add(field);
          }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
          // not in this project
        }
      }
      fields = found;
    }
    return fields;
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
