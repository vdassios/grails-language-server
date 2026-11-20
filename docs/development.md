# Development

## Build

Building needs JDK 17 or later (Gradle 9); the server itself targets Java 11 and runs on the project's JDK.

```sh
./gradlew build      # compiles api, analysis and server, runs the tests
./gradlew shadowJar  # build/libs/grails-language-server-all.jar
java -jar build/libs/grails-language-server-all.jar   # LSP over stdio
```

The demo GIFs in `docs/media/` are recorded from the tapes in `demo/` on the `demo/bookstore` app; see [demo/README.md](../demo/README.md).

Releases: the version is `version` in `gradle.properties`; the jar's manifest carries it and the server reports it as `serverInfo.version`. Pushing a tag `v*` runs CI, and once the build passes it publishes the tested jar as a GitHub release (a tag with a hyphen, such as `v0.6.0-alpha.1`, as a pre-release). The tag must be `v` and that version, or the release step fails.

The project-model tests run the fixture builds under `fixtures/` (see [its README](../fixtures/README.md)) with their own Gradle wrappers. They need a JDK 11 (Grails 4 fixture) and a JDK 17 (Grails 7 and plain Groovy fixtures), given as `GLS_TEST_JDK11` and `GLS_TEST_JDK17`; a test whose JDK is not set is skipped. `-PtestJavaHome=<jdk>` runs the tests on another JDK; CI runs them on JDK 11, the oldest the server supports, with both fixture JDKs set.

`analysis` is tested on every Groovy the server supports: `test` on Groovy 2.5, `testGroovy3` on Groovy 3, `testGroovy4` on Groovy 4 and `testGroovy5` on Groovy 5 (versions in `analysis/build.gradle`); `check` runs all four. The server tests that compile the Grails 7 fixture in-process (tagged `jdk17`) run in `:server:testJdk17` on the JDK in `GLS_TEST_JDK17`, since Grails 7 needs Java 17.

`./gradlew :server:perfTest` measures compile and query latency, outside the default build: on the Grails 4 fixture, or on the built project in `GLS_PERF_PROJECT` (resolved with the JDK in `GLS_PERF_JDK`). It writes counts and timings only, under `server/build/perf/`. `LatencyTest.artefactSweep` compiles every Groovy file of the project as the open file and reports time, errors and whether Grails's members are present, per artefact kind and kind of module (`sweep.txt`).
