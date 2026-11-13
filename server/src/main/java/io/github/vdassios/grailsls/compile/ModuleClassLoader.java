package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * One module's classes: its compile classpath and the analysis jar as URLs, plus the compiled classes of the module's
 * own {@code .java} sources, served one by one from its class output ({@link #javaClasses}). The rest of that output is
 * left out on purpose: project Groovy classes must resolve from source, not from stale class files.
 */
final class ModuleClassLoader extends URLClassLoader {
  static {
    registerAsParallelCapable();
  }

  /** Resource name ({@code a/b/C.class}, {@code a/b/C$1.class}) to class file. */
  private final Map<String, Path> javaClasses;

  ModuleClassLoader(String name, List<URL> urls, Map<String, Path> javaClasses) {
    super(name, urls.toArray(new URL[0]), BoundaryLoader.INSTANCE);
    this.javaClasses = Map.copyOf(javaClasses);
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    try {
      return super.findClass(name);
    } catch (ClassNotFoundException e) {
      Path file = javaClasses.get(name.replace('.', '/') + ".class");
      if (file == null) {
        throw e;
      }
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (IOException io) {
        throw new ClassNotFoundException(name, io);
      }
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  /** Groovy's resolver reads class files as resources (ASM), so they must be found here too. */
  @Override
  public URL findResource(String name) {
    URL url = super.findResource(name);
    return url != null ? url : javaClassUrl(name);
  }

  @Override
  public Enumeration<URL> findResources(String name) throws IOException {
    URL extra = javaClassUrl(name);
    if (extra == null) {
      return super.findResources(name);
    }
    List<URL> urls = new ArrayList<>(Collections.list(super.findResources(name)));
    urls.add(extra);
    return Collections.enumeration(urls);
  }

  private URL javaClassUrl(String name) {
    Path file = javaClasses.get(name);
    try {
      return file == null ? null : file.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }
}
