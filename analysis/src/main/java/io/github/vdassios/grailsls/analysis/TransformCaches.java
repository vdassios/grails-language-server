package io.github.vdassios.grailsls.analysis;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static caches of Grails and GORM AST transformations, keyed by class nodes or class names, emptied after every
 * compile. They are filled during a compile and never emptied, so each one keeps every AST it ever saw (measured: a
 * sweep of a Grails 4 module grew the heap to 2.5 GB with nothing kept), and a later compile could read class nodes
 * of an older one. A compile needs them only while it runs. Fields missing in the project's Grails version are skipped.
 */
final class TransformCaches {
  private static final String[][] FIELDS = {
      // controller and command object data binding (Grails 4 to 7)
      {"org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_WHITE_LIST_PROPERTY_NAMES"},
      {"org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_EXPLICITLY_BINDABLE_SPECIAL_PROPERTY_NAMES"},
      // GORM (Grails 4 to 7): a @Memoized method, and properties by class name
      {"org.grails.datastore.mapping.reflect.AstUtils", "memoizedMethodClosure$isDomainClassClassNode"},
      {"org.grails.datastore.gorm.transform.AstPropertyResolveUtils", "cachedClassProperties"},
  };

  private final ClassLoader loader;
  private List<Field> fields;

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
