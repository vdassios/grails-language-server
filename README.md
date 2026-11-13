# grails-language-server

A [language server](https://microsoft.github.io/language-server-protocol/) for Groovy with Grails support, usable from any LSP editor. It compiles each open file with the **project's own** Groovy and Grails jars, so the members Grails adds at compile time (`render`, `params`, `save`, `get`, finders, ...) are visible to completion, hover and navigation.

**Status: pre-alpha (milestone M1 in progress).** The server resolves the Gradle project model in the background, reports progress through `grails/status`, compiles each open Groovy file and publishes its diagnostics. Completion, hover, navigation and symbols arrive with the rest of M1.

## How it works

- **Project model.** The server runs the project's Gradle wrapper with an init script and records, per module: source roots, compile classpath, class directories, sibling modules, the Groovy version and the `.java` files that are compiled together with Groovy. Results are cached on disk, keyed by a hash of the build files. Resolution runs in the background with a timeout; `grails/reloadProject` runs it again.
- **Two layers.** `server` (LSP endpoints, project model, scheduling) has no Groovy dependency. `analysis` is compiled against the Groovy 2.5 API and runs inside a class loader per module source set, next to the project's own jars, so the project's own Groovy and AST transformations compile its code. Only the plain types of `api` cross between them.
- **Per-file compile.** Each open file is compiled alone, to canonicalization; an edit recompiles it after 300 ms and cancels a compile still running. Classes it uses are pulled in from source through a declaration index of the source roots (exact names, nested types included) and added under their file paths, which is how Grails recognizes artefacts. Jars, sibling modules' class directories and the compiled classes of the module's own `.java` files come from the loader; the rest of the module's build output never does.
- **Loaders are released.** A new project model closes every module loader; the server stops Groovy and the JDK's bean introspection from keeping them alive.

## Trust

Resolving the project runs its Gradle build scripts, and compiling a Grails project runs its AST transformations. Both execute project code inside the server. The server does neither until the client sends `initializationOptions.trusted = true`. When compiling, `@Grab` is disabled (`groovy.grape.enable=false`) and `@ASTTest` annotations are removed before they run.

## Protocol

| Message | Direction | Payload |
|---|---|---|
| `grails/status` | server to client | `{ "state": "resolving" \| "indexing" \| "ready" \| "failed", "message"?: string }` |
| `grails/reloadProject` | client to server (request) | none; re-resolves the project, ignoring the cache |

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

## Origin

A fork of [groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server) by Prominic.NET, Inc. Its providers and AST visitor are the starting point of `analysis`. See `NOTICE`.

## License

[Apache License 2.0](LICENSE).
