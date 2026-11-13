package io.github.vdassios.grailsls.project;

import java.util.ArrayList;
import java.util.List;

/** One Gradle project, as written by {@code model.init.gradle}. Field names match its JSON. */
public final class ModuleRecord {
  public String path;
  public String name;
  public String projectDir;
  /** Version of the {@code groovy} or {@code groovy-all} jar on a compile classpath; null without Groovy. */
  public String groovyVersion;
  /** Paths of sibling projects on a compile classpath, e.g. {@code :support}. */
  public List<String> dependsOn = new ArrayList<>();
  public List<SourceSetRecord> sourceSets = new ArrayList<>();
  /** Dependencies that failed to resolve; the rest of the classpath is still listed. */
  public List<String> errors = new ArrayList<>();

  public SourceSetRecord sourceSet(String name) {
    return sourceSets.stream().filter(s -> s.name.equals(name)).findFirst().orElse(null);
  }

  public static final class SourceSetRecord {
    public String name;
    /** Groovy and Java source directories (Grails adds {@code grails-app/*} to {@code main}). */
    public List<String> sourceRoots = new ArrayList<>();
    public List<String> resourceRoots = new ArrayList<>();
    /** Files of the compile classpath configuration; sibling projects appear as their jars. */
    public List<String> compileClasspath = new ArrayList<>();
    /** Class output dirs; the classes of {@link #javaFiles} are among them once the project is built. */
    public List<String> classesDirs = new ArrayList<>();
    /** Every {@code .java} file of the source set, including those in Groovy source dirs (joint compilation). */
    public List<String> javaFiles = new ArrayList<>();
  }
}
