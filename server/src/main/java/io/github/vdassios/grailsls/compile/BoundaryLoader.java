package io.github.vdassios.grailsls.compile;

import io.github.vdassios.grailsls.api.Analyzer;

/**
 * Parent of every module loader: the JDK's platform classes, plus the {@code api} package from the server's own
 * loader, so that an {@link Analyzer} loaded in a module loader is one the server can call. Nothing else of the
 * server (lsp4j, Gson) is visible to project code.
 */
final class BoundaryLoader extends ClassLoader {
  static final String API_PACKAGE = Analyzer.class.getPackageName() + ".";
  static final BoundaryLoader INSTANCE = new BoundaryLoader();

  static {
    registerAsParallelCapable();
  }

  private BoundaryLoader() {
    super("grails-ls-boundary", ClassLoader.getPlatformClassLoader());
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.startsWith(API_PACKAGE)) {
      return Analyzer.class.getClassLoader().loadClass(name);
    }
    return super.loadClass(name, resolve);
  }
}
