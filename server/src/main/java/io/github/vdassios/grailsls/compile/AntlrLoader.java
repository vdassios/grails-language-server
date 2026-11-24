package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import io.github.vdassios.grailsls.api.SharedParser;

/**
 * The ANTLR runtime shaded into one Groovy 3 or later jar ({@code groovyjarjarantlr4}), loaded once for every module
 * loader whose Groovy comes from that jar, so that their parsers can share one prediction cache ({@link SharedParser}).
 * The runtime refers to no Groovy class. This loader defines nothing else and refers to no module loader, so it keeps
 * no closed module alive; the workspace that created it closes it.
 */
final class AntlrLoader extends URLClassLoader implements SharedParser {
  static final String PACKAGE = "groovyjarjarantlr4.";
  private static final String ATN_CLASS = "groovyjarjarantlr4/v4/runtime/atn/ATN.class";
  private static final Pattern GROOVY_JAR = Pattern.compile("groovy-\\d[^/]*\\.jar");

  static {
    registerAsParallelCapable();
  }

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private Object atn;

  private AntlrLoader(Path groovyJar) throws MalformedURLException {
    super("grails-ls-antlr " + groovyJar.getFileName(), new URL[] {groovyJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
  }

  /** A loader for the ANTLR 4 runtime in this Groovy jar, or null when the file is no Groovy jar or has none (Groovy 2). */
  static AntlrLoader of(Path groovyJar) {
    if (!GROOVY_JAR.matcher(groovyJar.getFileName().toString()).matches()) {
      return null;
    }
    try (JarFile jar = new JarFile(groovyJar.toFile())) {
      return jar.getEntry(ATN_CLASS) == null ? null : new AntlrLoader(groovyJar);
    } catch (IOException e) {
      return null; // not readable: the module loader reports it
    }
  }

  /** The first Groovy jar on a classpath: the one a module loader takes Groovy's classes from. */
  static Path groovyJar(Iterable<Path> classpath) {
    for (Path entry : classpath) {
      if (entry.getFileName() != null && GROOVY_JAR.matcher(entry.getFileName().toString()).matches()) {
        return entry;
      }
    }
    return null;
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    if (!name.startsWith(PACKAGE)) {
      throw new ClassNotFoundException(name);
    }
    return super.findClass(name);
  }

  @Override
  public synchronized Object atn(Object offered) {
    if (atn == null) {
      atn = offered;
    }
    return atn;
  }

  @Override
  public ReadWriteLock lock() {
    return lock;
  }

  @Override
  public void close() {
    try {
      super.close();
    } catch (IOException e) {
      // nothing left to release
    }
  }
}
