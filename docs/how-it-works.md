# How it works

- **Project model.** The server runs the project's Gradle wrapper with an init script and records, per module: source roots, compile classpath, class directories, build directory, sibling modules and the Groovy version. The `.java` files compiled together with Groovy are found in the source roots each time the model is applied, not recorded. The build directory is the only part of a source root that is never source, so a package may be named `build`, `bin` or `out`. Results are cached on disk, keyed by a hash of the build files (dependency lock files included), the JDK and the init script; a cached model is used only while every jar it names is on disk, so a cleaned Gradle cache resolves again. Model directories and analysis jars no server has used for 30 days are deleted when a server starts. Resolution runs in the background with a timeout; `grails/reloadProject` runs it again.
- **Two layers.** `server` (LSP endpoints, project model, scheduling) has no Groovy dependency. `analysis` is compiled against the Groovy 2.5 API and runs inside a class loader per module source set, next to the project's own jars, so the project's own Groovy and AST transformations compile its code. Only the plain types of `api` cross between them.
- **Per-file compile.** Each open file is compiled alone, to canonicalization; a file with `@CompileStatic` or `@GrailsCompileStatic` code goes on to instruction selection, so the static type checker's errors are reported and its inferred types (closure parameters, flow typing) feed completion and hover. An edit recompiles the file after 300 ms and cancels a compile still running. Classes it uses are pulled in from source through a declaration index of the source roots (exact names, nested types included) and added under their file paths, which is how Grails recognizes artefacts. Jars, sibling modules' class directories and the compiled classes of the module's own `.java` files come from the loader; the rest of the module's build output never does. A sibling's class directory is read at each lookup, so classes Gradle builds there after the server started (`gradle classes`) are seen by the next compile. The module's own `.java` classes are picked up when the project model is applied, so those built later (the status counts them as not built) need `grails/reloadProject`. Extension modules of the main source set (for tests) and of sibling modules are served too, as Gradle's classpath would: their descriptors from the resource roots, their classes from the build output.
- **Symbol index.** In the background, each source file is parsed alone (no class resolved, no transformation run), in batches between compiles, for workspace symbols and the completion of types not imported yet. It follows saves and file changes.
- **Loaders are released.** A new project model closes every module loader; the server stops Groovy and the JDK's bean introspection from keeping them alive.

## Trust

Resolving the project runs its Gradle build scripts, and compiling a Grails project runs its AST transformations. Both execute project code inside the server. The server does neither until the client sends `initializationOptions.trusted = true`. When compiling, `@Grab` is disabled (`groovy.grape.enable=false`) and `@ASTTest` annotations, also under an import alias, are removed before they run. The server runs a Gradle wrapper only from the workspace folder itself, never from a parent directory.

## Protocol

| Message | Direction | Payload |
|---|---|---|
| `grails/status` | server to client | `{ "state": "resolving" \| "indexing" \| "ready" \| "failed", "message"?: string }` |
| `grails/reloadProject` | client to server (request) | none; re-resolves the project, ignoring the cache |
| `grails/downloadSources` | client to server (request) | none; result `{ "available": number, "unavailable": number, "failed": number, "message"?: string }`: libraries with a sources jar on disk now, libraries that publish none, libraries whose sources could not be fetched, and the reason when something failed or nothing ran (untrusted workspace, Gradle error or timeout) |

The server logs each startup stage once with `window/logMessage`, as `startup: <stage> after <ms> ms` (stages: model resolved, declarations indexed, symbols indexed, first diagnostics).

A failure that turns a feature off is shown with `window/showMessage` (type Error), once per module source set, so a `ready` status is never the only sign: a compile that throws (for example, the analysis layer did not load; later failures in that source set are only logged), Grails's GSP page parser being there but unusable (pages then get tag checks only), and taglib class files that cannot be read (their tags are unknown, so no tag is reported as unknown). A failure that only turns off a workaround, while the features still work, is logged once with `window/logMessage` (type Warning): a field the server empties between compiles, or one of Groovy's that it resets, missing from the project's version of its class. An error while indexing ends in `failed`, never a stuck `indexing`.

`initialize` answers with `serverInfo`: `name` is `grails-language-server`, `version` the server's version (from the jar's manifest).

Initialization options: `trusted` (boolean, required to do anything), `gradle.timeoutSeconds` (default 300), `cacheDir` (default `$XDG_CACHE_HOME/grails-language-server`, else `~/.cache/grails-language-server`).

The server exits when its client goes away, even if the client never sends `exit`: when its standard input ends, or when the process named by `processId` in `initialize` ends. It kills a running Gradle first. The exit code is 0 if `shutdown` came first, else 1. So an editor need not wait for the server when it quits.

## Known limits

- **JDK 17 with Groovy 4:** after the project model changes, a closed module loader can stay in memory when the server runs on JDK 17: JDK 17 keeps method-handle type caches that refer to the loader's classes (seen on a Groovy 4 project). JDK 19 and later do not. A Grails 7 project on a recent Groovy 4 can run the server on JDK 21 (see the table under [Install](editors.md#install)).
- **Heap:** the JVM's defaults apply (a quarter of the RAM at most). Give `-Xmx` before `-jar` to cap it; a large multi-module project can use 2 GB or more after compiling many files.
