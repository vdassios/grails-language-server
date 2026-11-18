package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lookups through the jar index ({@link JarIndex}) find what the URL class path and the class output have. */
class ModuleClassLoaderTest {
  @TempDir
  Path tmp;

  /** A class with no dependencies, defined again from its class file by the loaders under test. */
  static final class Sample {}

  private static final String SAMPLE = Sample.class.getName();
  private static final String SAMPLE_FILE = SAMPLE.replace('.', '/') + ".class";

  private static byte[] sampleBytes() throws IOException {
    try (InputStream in = Sample.class.getClassLoader().getResourceAsStream(SAMPLE_FILE)) {
      return in.readAllBytes();
    }
  }

  private Path jar(String name, Map<String, byte[]> entries) throws IOException {
    Path file = tmp.resolve(name);
    try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        jar.putNextEntry(new JarEntry(entry.getKey()));
        jar.write(entry.getValue());
        jar.closeEntry();
      }
    }
    return file;
  }

  private static ModuleClassLoader loader(Map<String, Path> javaClasses, Path... urls) throws IOException {
    URL[] list = new URL[urls.length];
    for (int i = 0; i < urls.length; i++) {
      list[i] = urls[i].toUri().toURL();
    }
    return new ModuleClassLoader("test", List.of(list), javaClasses, Map.of(), null);
  }

  @Test
  void aClassAndAResourceInAJarAreFound() throws Exception {
    Path jar = jar("a.jar", Map.of(SAMPLE_FILE, sampleBytes(), "a/b.properties", new byte[0]));
    try (ModuleClassLoader loader = loader(Map.of(), jar)) {
      assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.Absent"));
      assertNull(loader.getResource("a/Absent.class"));
      assertSame(loader, loader.loadClass(SAMPLE).getClassLoader());
      assertNotNull(loader.getResource("a/b.properties"));
    }
  }

  @Test
  void aClassOfTheClassOutputIsServedThoughNoJarHasIt() throws Exception {
    Path jar = jar("a.jar", Map.of("a/Other.class", new byte[0]));
    Path classFile = tmp.resolve("Sample.class");
    Files.write(classFile, sampleBytes());
    try (ModuleClassLoader loader = loader(Map.of(SAMPLE_FILE, classFile), jar)) {
      assertNotNull(loader.getResource(SAMPLE_FILE));
      assertSame(loader, loader.loadClass(SAMPLE).getClassLoader());
    }
  }

  @Test
  void aClassWrittenToAClasspathDirectoryAfterTheFirstLookupIsFound() throws Exception {
    Path classes = Files.createDirectories(tmp.resolve("classes"));
    try (ModuleClassLoader loader = loader(Map.of(), jar("a.jar", Map.of("a/Other.class", new byte[0])), classes)) {
      assertThrows(ClassNotFoundException.class, () -> loader.loadClass(SAMPLE));
      Path file = classes.resolve(SAMPLE_FILE);
      Files.createDirectories(file.getParent());
      Files.write(file, sampleBytes());
      assertNotNull(loader.getResource(SAMPLE_FILE));
      assertSame(loader, loader.loadClass(SAMPLE).getClassLoader());
    }
  }
}
