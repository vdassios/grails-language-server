package io.github.vdassios.grailsls.analysis;

import java.util.List;

import io.github.vdassios.grailsls.api.Completion;

/** Classes and packages of the classpath that completion offers besides the classes of the compile. */
public interface ClasspathTypes {
  List<Type> types();

  List<String> packages();

  /** A class of the classpath. */
  final class Type {
    public final String name;
    public final String simpleName;
    public final String packageName;
    public final Completion.Kind kind;

    public Type(String name, String simpleName, String packageName, Completion.Kind kind) {
      this.name = name;
      this.simpleName = simpleName;
      this.packageName = packageName;
      this.kind = kind;
    }
  }
}
