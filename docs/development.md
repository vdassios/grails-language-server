# Development

## Build

To build the server, you need JDK 17 or later, because the build uses Gradle 9. The server itself targets Java 11, and runs on your project's JDK.

```sh
./gradlew build      # compile api, analysis and server, and run the tests
./gradlew shadowJar  # build build/libs/grails-language-server-all.jar
java -jar build/libs/grails-language-server-all.jar   # start the server (LSP over stdio)
```

## Tests

### Fixture projects

The project model tests build the fixture projects under `fixtures/` with their own Gradle wrappers (see [fixtures/README.md](../fixtures/README.md)). They need two JDKs, which you give as environment variables:

- `GLS_TEST_JDK11`: a JDK 11, for the Grails 4 fixture
- `GLS_TEST_JDK17`: a JDK 17, for the Grails 7 and plain Groovy fixtures

A test whose JDK is not set is skipped.

To run the tests on a different JDK, pass `-PtestJavaHome=<jdk>`. CI runs them on JDK 11, the oldest JDK that the server supports, with both fixture JDKs set.

### Groovy versions

`analysis` is tested on every Groovy version that the server supports. The versions are set in `analysis/build.gradle`.

| Task | Groovy |
|---|---|
| `test` | 2.5 |
| `testGroovy3` | 3 |
| `testGroovy4` | 4 |
| `testGroovy5` | 5 |
| `testGroovy6` | 6 |

`check` runs all five. Groovy 6 needs Java 17, so `testGroovy6` runs on the JDK in `GLS_TEST_JDK17`, or else on Gradle's JDK. It never uses `-PtestJavaHome`.

### Tests that need Java 17

Some server tests need Java 17. They are tagged `jdk17`, and include the tests that compile the Grails 7 fixture in the test process, and the test where Groovy 2.5.14 cannot read the JDK's class files. They run in `:server:testJdk17`, on the JDK in `GLS_TEST_JDK17`.

## Performance tests

`./gradlew :server:perfTest` runs `LatencyTest`, which measures compile and query latency. It lives in its own source set, `server/src/perf`. The default build only compiles it.

By default, it measures the Grails 4 fixture. To measure another project, build that project and set:

- `GLS_PERF_PROJECT`: the path to the built project
- `GLS_PERF_JDK`: the JDK to resolve it with

The test writes counts and timings only, under `server/build/perf/`.

`LatencyTest.artefactSweep` compiles every Groovy file of the project as the open file. For each kind of artefact and each kind of module, it reports the time, the errors, and whether Grails's members are present. It writes the results to `sweep.txt`.

For the full measurement procedure, see [Rerunning](../benchmarks/results.md#rerunning) in the benchmark results.

## Demo recordings

The GIFs in `docs/media/` are recorded from the tapes in `demo/`, on the `demo/bookstore` app. See [demo/README.md](../demo/README.md).

## Releases

The version is the `version` property in `gradle.properties`. The jar's manifest includes it, and the server reports it as `serverInfo.version`.

To release:

1. Set `version` in `gradle.properties`.
2. Push a tag named `v` followed by that version, such as `v0.6.0`.

CI builds and tests the tag. If the build passes, CI publishes the tested jar as a GitHub release. A tag with a hyphen, such as `v0.6.0-alpha.1`, is published as a pre-release. If the tag does not match the version, the release step fails.
