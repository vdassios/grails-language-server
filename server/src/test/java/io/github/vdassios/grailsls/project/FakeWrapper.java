package io.github.vdassios.grailsls.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** A {@code gradlew} shell script that logs each run to {@code runs.txt} and runs the given body. */
final class FakeWrapper {
  private FakeWrapper() {}

  /** Body that writes one root record into the {@code -Pgls.out} directory. */
  static final String WRITES_MODEL = "printf '%s' '{\"path\":\":\",\"name\":\"fake\",\"projectDir\":\"'\"$PWD\"'\",\"sourceSets\":[]}' > \"$out/_root.json\"\n";

  static Path write(Path dir, String body) throws IOException {
    Files.createDirectories(dir);
    Path script = dir.resolve("gradlew");
    String text = "#!/bin/sh\n"
        + "for a in \"$@\"; do case \"$a\" in -Pgls.out=*) out=\"${a#-Pgls.out=}\";; esac; done\n"
        + "echo \"JAVA_HOME=$JAVA_HOME\" >> \"$(dirname \"$0\")/runs.txt\"\n"
        + body;
    Files.writeString(script, text);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  static long runs(Path dir) throws IOException {
    Path runs = dir.resolve("runs.txt");
    return Files.exists(runs) ? Files.readAllLines(runs).size() : 0;
  }
}
