# grails-language-server

A [language server](https://microsoft.github.io/language-server-protocol/) for Groovy with Grails support, usable from any LSP editor. It compiles each open file with the **project's own** Groovy and Grails jars, so the members Grails adds at compile time (`render`, `params`, `save`, `get`, finders, ...) are visible to completion, hover and navigation.

**Status: pre-alpha, milestone M1 (Groovy core).** The server resolves the Gradle project model in the background, reports progress through `grails/status`, compiles each open Groovy file and publishes its diagnostics. It answers completion (with resolve, including types not imported yet), hover, signature help, definition, document symbols and workspace symbols. Grails-specific features (artefact checks, untyped injection, GORM finders, conventions, GSP) come in later milestones.

## How it works

- **Project model.** The server runs the project's Gradle wrapper with an init script and records, per module: source roots, compile classpath, class directories, sibling modules, the Groovy version and the `.java` files that are compiled together with Groovy. Results are cached on disk, keyed by a hash of the build files. Resolution runs in the background with a timeout; `grails/reloadProject` runs it again.
- **Two layers.** `server` (LSP endpoints, project model, scheduling) has no Groovy dependency. `analysis` is compiled against the Groovy 2.5 API and runs inside a class loader per module source set, next to the project's own jars, so the project's own Groovy and AST transformations compile its code. Only the plain types of `api` cross between them.
- **Per-file compile.** Each open file is compiled alone, to canonicalization; a file with `@CompileStatic` or `@GrailsCompileStatic` code goes on to instruction selection, so the static type checker's errors are reported and its inferred types (closure parameters, flow typing) feed completion and hover. An edit recompiles the file after 300 ms and cancels a compile still running. Classes it uses are pulled in from source through a declaration index of the source roots (exact names, nested types included) and added under their file paths, which is how Grails recognizes artefacts. Jars, sibling modules' class directories and the compiled classes of the module's own `.java` files come from the loader; the rest of the module's build output never does. Extension modules of the main source set (for tests) and of sibling modules are served too, as Gradle's classpath would: their descriptors from the resource roots, their classes from the build output.
- **Symbol index.** In the background, each source file is parsed alone (no class resolved, no transformation run), in batches between compiles, for workspace symbols and the completion of types not imported yet. It follows saves and file changes.
- **Loaders are released.** A new project model closes every module loader; the server stops Groovy and the JDK's bean introspection from keeping them alive.

## Trust

Resolving the project runs its Gradle build scripts, and compiling a Grails project runs its AST transformations. Both execute project code inside the server. The server does neither until the client sends `initializationOptions.trusted = true`. When compiling, `@Grab` is disabled (`groovy.grape.enable=false`) and `@ASTTest` annotations are removed before they run.

## Protocol

| Message | Direction | Payload |
|---|---|---|
| `grails/status` | server to client | `{ "state": "resolving" \| "indexing" \| "ready" \| "failed", "message"?: string }` |
| `grails/reloadProject` | client to server (request) | none; re-resolves the project, ignoring the cache |

The server logs each startup stage once with `window/logMessage`, as `startup: <stage> after <ms> ms` (stages: model resolved, declarations indexed, symbols indexed, first diagnostics).

Initialization options: `trusted` (boolean, required to do anything), `gradle.timeoutSeconds` (default 300), `cacheDir` (default `$XDG_CACHE_HOME/grails-language-server`, else `~/.cache/grails-language-server`).

## Build

Building needs JDK 17 or later (Gradle 9); the server itself targets Java 11 and runs on the project's JDK.

```sh
./gradlew build      # compiles api, analysis and server, runs the tests
./gradlew shadowJar  # build/libs/grails-language-server-all.jar
java -jar build/libs/grails-language-server-all.jar   # LSP over stdio
```

The project-model tests run the fixture builds under `fixtures/` (see its README) with their own Gradle wrappers. They need a JDK 11 (Grails 4 fixture) and a JDK 17 (Grails 7 and plain Groovy fixtures), given as `GLS_TEST_JDK11` and `GLS_TEST_JDK17`; a test whose JDK is not set is skipped. `-PtestJavaHome=<jdk>` runs the tests on another JDK; CI runs them on JDK 11, the oldest the server supports, with both fixture JDKs set.

`analysis` is tested twice: `test` on Groovy 2.5, `testGroovy4` on Groovy 4.

`./gradlew :server:perfTest` measures compile and query latency, outside the default build: on the Grails 4 fixture, or on the built project in `GLS_PERF_PROJECT` (resolved with the JDK in `GLS_PERF_JDK`). It writes counts and timings only, under `server/build/perf/`.

## Origin

A fork of [groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server) by Prominic.NET, Inc. Its providers and AST visitor are the starting point of `analysis`. See `NOTICE`.

## License

[Apache License 2.0](LICENSE).
