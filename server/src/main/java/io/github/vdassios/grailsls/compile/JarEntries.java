package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.IntStream;
import java.util.zip.ZipFile;

import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * What the server reads from the entry names of the jars of a workspace, each jar read once for all its users: the
 * module loaders' {@link JarIndex}, import completion ({@link ClasspathNames}) and the taglib and plugin file lookups
 * of pages. Jars are not rebuilt under a running server (the loaders keep no build output), so nothing is read again.
 */
final class JarEntries {
  private final Map<Path, Jar> jars = new ConcurrentHashMap<>();

  /** The jar's entries, read on the first call. */
  Jar of(Path jar) {
    return jars.computeIfAbsent(jar, Jar::read);
  }

  /** One jar's entries, as each user needs them. An unreadable file (a {@code .pom} on the class path) has none. */
  static final class Jar {
    private static final String VIEWS = "gsp/views.properties";
    private static final String ASSETS = "META-INF/assets/";

    /** Hash codes of every entry name, sorted and distinct, as {@link JarIndex} needs them. */
    final int[] names;
    /** The paths the manifest names ({@code Class-Path}); null when one is not a file URL. */
    final List<Path> classPath;
    /** Top-level classes by package, for {@link ClasspathNames}. */
    final Map<String, List<String>> classes;
    /** Binary names of the classes named {@code *TagLib}. */
    final List<String> taglibs;
    /** {@link SourceLookup#pluginFiles()} of this jar. */
    final List<String> pluginFiles;

    private Jar(int[] names, List<Path> classPath, Map<String, List<String>> classes, List<String> taglibs, List<String> pluginFiles) {
      this.names = names;
      this.classPath = classPath;
      this.classes = classes;
      this.taglibs = taglibs;
      this.pluginFiles = pluginFiles;
    }

    static Jar read(Path file) {
      // opened as the URL class path opens it: a multi-release jar's versioned entries count under their own names
      try (JarFile jar = new JarFile(file.toFile(), false, ZipFile.OPEN_READ, JarFile.runtimeVersion())) {
        List<Path> classPath = classPath(file, jar.getManifest());
        Map<String, List<String>> classes = new HashMap<>();
        List<String> taglibs = new ArrayList<>();
        List<String> files = new ArrayList<>();
        IntStream.Builder hashes = IntStream.builder();
        for (JarEntry entry : (Iterable<JarEntry>) jar.stream()::iterator) {
          String name = entry.getName();
          hashes.add(name.hashCode());
          if (name.endsWith("/")) {
            hashes.add(name.substring(0, name.length() - 1).hashCode());
          }
          ClasspathNames.add(classes, name);
          if (name.endsWith("TagLib.class") && !name.contains("$") && !name.endsWith("/TagLib.class") && !name.startsWith("META-INF/")) {
            taglibs.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
          } else if (name.startsWith(ASSETS) && !entry.isDirectory() && name.length() > ASSETS.length()) {
            files.add("assets/" + name.substring(ASSETS.length()));
          } else if (name.equals(VIEWS)) {
            files.addAll(views(jar, entry));
          }
        }
        if (jar.isMultiRelease()) {
          jar.versionedStream().map(JarEntry::getName).forEach(name -> hashes.add(name.hashCode()));
        }
        return new Jar(hashes.build().sorted().distinct().toArray(), classPath, classes, List.copyOf(taglibs), List.copyOf(files));
      } catch (IOException e) {
        return new Jar(new int[0], List.of(), Map.of(), List.of(), List.of()); // not a jar: the URL class path skips it too
      }
    }

    private static List<Path> classPath(Path file, Manifest manifest) throws IOException {
      List<Path> classPath = new ArrayList<>();
      String value = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      if (value == null) {
        return classPath;
      }
      for (String entry : value.trim().split("\\s+")) {
        URL url;
        try {
          url = new URL(file.toUri().toURL(), entry);
        } catch (IOException e) {
          continue; // the URL class path skips it too
        }
        Path path = path(url);
        if (path == null) {
          return null;
        }
        classPath.add(path);
      }
      return classPath;
    }

    /**
     * A plugin's precompiled views: {@code /WEB-INF/grails-app/views/login/auth.gsp=gsp_..._loginauth_gsp} →
     * {@code views/login/auth.gsp}; none when the file cannot be read.
     */
    private static List<String> views(JarFile jar, JarEntry entry) {
      Properties properties = new Properties();
      try (InputStream in = jar.getInputStream(entry)) {
        properties.load(in);
      } catch (IOException | IllegalArgumentException e) {
        return List.of();
      }
      List<String> views = new ArrayList<>();
      for (String key : properties.stringPropertyNames()) {
        int at = key.indexOf("grails-app/");
        if (at >= 0) {
          views.add(key.substring(at + "grails-app/".length()));
        }
      }
      return views;
    }
  }

  /** The file a URL names; null when it is not a file URL. */
  static Path path(URL url) {
    if (!"file".equals(url.getProtocol())) {
      return null;
    }
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }
}
