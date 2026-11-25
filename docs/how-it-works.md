# How it works

This page explains how the server is built and why it behaves as it does. To set the server up, see [Install and editor setup](editors.md).

## Two layers

The server is split into two layers, so that your project's own Groovy can compile your code:

- **`server`** contains the LSP endpoints, the project model, and scheduling. It does not depend on Groovy.
- **`analysis`** is compiled against the Groovy 2.5 API. It runs inside a separate class loader for each module source set, next to your project's own jars. So your project's Groovy and AST transformations compile your code.

Only the plain types of the `api` module cross between the two layers.

## The project model

When it starts, the server runs your project's Gradle wrapper with an init script. The script records these for each module:

- the source roots
- the compile class path
- the class directories
- the build directory
- the sibling modules
- the Groovy version
- the compiler configuration script of each source set, if its Groovy compile task sets `groovyOptions.configurationScript`

The server does not record the `.java` files that are compiled together with Groovy. Instead, it finds them in the source roots each time it applies the model.

The build directory is the only part of a source root that the server never treats as source. So a package can be named `build`, `bin`, or `out`.

The server caches the model on disk. The cache key is a hash of the build files (including dependency lock files), the JDK, and the init script. The server only uses a cached model while every jar that it names is still on disk. So if you clean Gradle's cache, the project is resolved again.

When a server starts, it deletes the model directories and analysis jars that no server has used for 30 days.

Resolution runs in the background, with a timeout. `grails/reloadProject` runs it again.

## Compiling a file

The server compiles each open file on its own, up to Groovy's canonicalization phase.

If the file has code under `@CompileStatic`, `@TypeChecked`, or their Grails variants, the compile goes on to the instruction selection phase. This applies when the annotation is on a class, a method, or a constructor, and also when it is imported under an alias. The static type checker then reports its errors, and its inferred types feed completion and hover. Examples of inferred types are the types of closure parameters and flow typing.

If the source set has a compiler configuration script, the server runs it on every compile, as `groovyc --configscript` does. A script such as `withConfig(configuration) { ast(CompileStatic) }` makes the whole file static, so the compile goes on to instruction selection too. The script's imports apply to every class in the compile, including the classes pulled in from source. Its customizers that run after canonicalization, such as `ast(CompileStatic)`, apply only to the open file, so the type checker reports only on that file. When the script file changes, the server reads it again. If the script fails to compile or run, the server shows the error once and compiles without it. If the file does not exist, for example the script that the Grails 7 Gradle plugin writes when the build runs, the server only logs it.

After an edit, the server waits 300 ms, then compiles the file again. It cancels any compile that is still running.

### Cancellation

If your editor cancels a request with `$/cancelRequest` while the request is still waiting for its module's compile thread, the request never runs. If the request has already started a compile, the compile stops at its next phase. This applies to completion and signature help, which compile a copy of the unfinished text.

### Completion after a dot

Right after you type a dot, the text usually does not parse, so completion compiles a repaired copy of it. The copy gets a name after the dot. It also closes a string that the line leaves open, and the blocks, brackets, and parentheses that the text leaves open, such as a method you are writing before its closing brace. Braces in strings and comments do not count. If the copy still does not parse, the list is empty and marked as incomplete, so your editor asks again as you type.

The server skips this compile when the file's last compile that parsed is known to contain the receiver. That is the case when both of these are true:

- The two texts differ only after the receiver, on the receiver's line, by at most a member access.
- The sources that the last compile read have not changed. As for any kept compile, classes from the class loader are not checked.

### Classes from source

The classes that a file uses are compiled from source as well. The server finds them through a declaration index of the source roots, which matches exact names, including nested types. It adds each class under its real file path, which is how Grails recognizes artefacts.

The next compile of the file starts from the classes that its last compile pulled in from source, instead of parsing and resolving them again. In a module that uses a sibling module's sources, these classes are most of the work. Completion's compile of a repaired copy starts from them too. The server compiles them again when any of these is true:

- A file that the last compile read has changed, on disk or in your editor, or a class name that it looked up now resolves to another file.
- The compiler configuration script has changed, because its imports apply to these classes.
- The new compile ends in another phase, for example after you add `@CompileStatic` to a file that had no static code.
- One of the files names a class that the open file declares, in its new text or its last one. Its references would still point to the open file's classes from the last compile.
- The last compile failed, or Groovy reported more than syntax errors.

Errors in the reused files are reported again with each compile that reuses them.

Until the [symbol index](#the-symbol-index) has parsed a file, the declaration index reads the file's names from its text, skipping strings and comments. So, until then, a dollar-slashy string that contains `/*` can hide a class from this scan.

### Classes from the class loader

These classes come from the module's class loader:

- the jars
- the class directories of sibling modules
- the compiled classes of the module's own `.java` files

The rest of the module's build output never comes from the loader.

A sibling's class directory is read at every lookup. So if Gradle builds classes there after the server has started, for example with `gradle classes`, the next compile sees them.

The module's own Java classes are read from the build output when the project model is applied, and again on each rescan (see [Following file changes](#following-file-changes)). So Java classes that are built later are used after `grails/reindex`. Until then, the status counts them as not built. A class that is already loaded keeps its first version until the next project model.

Extension modules of the main source set (for tests) and of sibling modules are also used, as they would be on Gradle's class path. Their descriptors come from the resource roots, and their classes from the build output.

## The symbol index

In the background, the server parses each source file on its own, without resolving classes or running transformations. It does this in batches, between compiles.

Parsing needs no class path. So the index only runs in the first source set of each Groovy jar, and does not start the class loader of any other source set.

The symbol index is used for:

- **Workspace symbols.** An empty query returns at most 1,000 symbols, classes first.
- **Completion of types that are not imported yet.**
- **The declaration index.** When a parse changes the type names in a file, the open documents are compiled again.

The index follows saves and file changes.

## Following file changes

Some of the server's state must follow changes made outside the editor, such as a checkout, a code generator, or a file created in a terminal. This state is:

- the declaration and symbol indexes
- the GSP pages that view and template hints check
- the module's Java classes

If your editor can register `workspace/didChangeWatchedFiles`, the server asks it to watch:

- `**/*.groovy`, `**/*.java`, and `**/*.gsp`
- the build files: `**/*.gradle`, `**/*.gradle.kts`, `**/gradle.properties`, `**/gradle-wrapper.properties`, `**/*.versions.toml`, and `**/*.lockfile`

If your editor cannot, as with Neovim on Linux, the server uses its own watcher, the JDK's `WatchService`. It watches:

- every directory below the source roots, and each `grails-app` directory. It skips build directories, and adds new directories as they appear.
- the directories that contain build files

The server logs a message and rescans when events are lost because the watch queue overflowed, or when a directory cannot be watched because of Linux's inotify limit (`fs.inotify.max_user_watches`). After a directory could not be watched, the server also rescans every time you save.

### Rescans

A rescan walks the source roots and the pages. It reads again only the files that were created, deleted, or modified since the server last read them, judged by modification time. It also reads the Java class output again. A rescan never runs Gradle.

`grails/reindex` runs a rescan on request. When a Java source changes, the server rescans after 300 ms, because its classes only appear once Gradle builds them.

When a type name or a page appears or disappears, the open documents are compiled again. If two files declare the same class for a moment, as when a rename is seen as a create followed by a delete, the class stays resolvable.

### Build file changes

When a build file changes, the server computes the model's cache key again. If the key no longer matches, the server shows a warning with `window/showMessage`, once per model. The warning says that the model is out of date and that `grails/reloadProject` reads it again. The server does not run Gradle by itself.

Each new project model, at startup or after `grails/reloadProject`, scans everything from scratch.

## Releasing class loaders

A new project model closes every module's class loader. The server stops Groovy and the JDK's bean introspection from holding on to the old loaders, so that they can be garbage collected.

## Trust

Resolving your project runs its Gradle build scripts, and compiling runs its AST transformations and compiler configuration scripts. All of them run your project's code inside the server. So the server does none of this until your editor sends `initializationOptions.trusted = true`.

The server also takes these precautions:

- It disables `@Grab` when it compiles (`groovy.grape.enable=false`).
- It removes `@ASTTest` annotations before they can run, including annotations imported under an alias.
- It only runs a Gradle wrapper from the workspace folder itself, never from a parent directory.

## Protocol

### Custom messages

| Message | Direction | Payload |
|---|---|---|
| `grails/status` | Notification from the server | `{ "state": "resolving" \| "indexing" \| "ready" \| "failed", "message"?: string }` |
| `grails/reloadProject` | Request from the client | No parameters. Resolves the project again, ignoring the cache. |
| `grails/reindex` | Request from the client | No parameters. Reads the source roots again (see [Rescans](#rescans)) and returns `null` once the indexes match the disk. Never runs Gradle. Returns at once if no project is resolved. |
| `grails/downloadSources` | Request from the client | No parameters. Returns `{ "available": number, "unavailable": number, "failed": number, "message"?: string }`: the libraries that now have a sources jar on disk, the libraries that publish none, and the libraries whose sources could not be fetched. `message` gives the reason when something failed or nothing ran, for example an untrusted workspace, a Gradle error, or a timeout. |

For the messages that `grails/status` carries, see [Status messages](editors.md#status-messages).

### Initialization options

| Option | Type | Default | Description |
|---|---|---|---|
| `trusted` | boolean | none | Must be `true`, or the server does nothing. See [Trust](#trust). |
| `gradle.timeoutSeconds` | number | `300` | How long a Gradle run may take. A positive whole number. A string such as `"600"` is also accepted. Any other value is logged, and the default is used. |
| `cacheDir` | string | `$XDG_CACHE_HOME/grails-language-server`, or else `~/.cache/grails-language-server` | Where the server keeps its caches. A value that is not a non-blank path is logged, and the default is used. |

### Server info

The response to `initialize` includes `serverInfo`. Its `name` is `grails-language-server`, and its `version` is the server's version, read from the jar's manifest.

### Cancelled requests

When your editor cancels a request with `$/cancelRequest`, the server answers with LSP's `RequestCancelled` error. See [Cancellation](#cancellation).

If a `grails/downloadSources` request is still running or queued at `shutdown`, the server answers it with `{ "available": 0, "unavailable": 0, "failed": 0, "message": "cancelled" }`.

### Logs and errors

The server logs each startup stage once with `window/logMessage`, as `startup: <stage> after <ms> ms`. The stages are:

- `model resolved`
- `declarations indexed`
- `symbols indexed`
- `first diagnostics`: the first diagnostics that a compile publishes, not the empty ones for a closed document

When a failure turns a feature off, the server shows it with `window/showMessage` (type Error), once for each module source set. So a `ready` status is never the only sign. These failures are:

- **A compile throws**, for example because the analysis layer did not load. Later failures in the same source set are only logged.
- **Grails's GSP page parser is present but cannot be used.** Pages then only get tag checks.
- **Taglib class files cannot be read.** Their tags are unknown, so no tag is reported as unknown.

When a failure only turns off a workaround and the features still work, the server logs it once with `window/logMessage` (type Warning). This happens when a field that the server clears between compiles, or a Groovy field that it resets, is missing from your project's version of the class.

An error while indexing ends in `failed`. The status never stays stuck at `indexing`.

### Shutdown

The server exits when its editor goes away, even if the editor never sends `exit`. It notices this when its standard input ends, or when the process named by `processId` in `initialize` ends.

On `shutdown`, and on exit, the server cancels its compiles and kills the Gradle processes it started. It waits for nothing else, so it exits at once, even in the middle of a compile. If one of those Gradle processes started a Gradle daemon, the daemon is killed too. A daemon reused from an earlier build keeps running: Gradle sees that its client has gone, cancels the build, and keeps the daemon for the next one.

The exit code is 0 if `shutdown` came first, and 1 otherwise. So your editor does not need to wait for the server when it quits.

## Known limits

- **JDK 17 with Groovy 4.** When the server runs on JDK 17, a closed module class loader can stay in memory after the project model changes. JDK 17 keeps method handle type caches that refer to the loader's classes. This was seen on a Groovy 4 project. JDK 19 and later don't have this problem. A Grails 7 project on a recent Groovy 4 can run the server on JDK 21 (see the [JDK table](editors.md#choose-the-jdk)).
- **Characters outside the Basic Multilingual Plane**, such as emoji. LSP counts positions in UTF-16 code units. To match this on every Groovy version, the parser reads each of these characters as two characters: `__` in a name, and two replacement characters elsewhere. So string values and names in the compiled code contain these stand-ins. Hover falls back to the source text, but a class whose name contains such a character does not match its class file.
- **The shared parser cache relies on changing a final field.** On Groovy 3 and 4, the module class loaders that use the same Groovy jar share one parser prediction cache. On a large project, this cuts the heap by about a third once every file has been compiled ([results](../benchmarks/results.md#parser-cache-and-other-decisions)). To set up the cache, the server changes a final field of Groovy's parser through reflection, which [JEP 500](https://openjdk.org/jeps/500) restricts. The server jar's manifest allows it with `Enable-Final-Field-Mutation: ALL-UNNAMED`. Without that entry, JDK 26 and later print a warning. If a future JDK denies the change by default, the server catches the error and each module class loader keeps its own cache. Compiles still work, but the memory saving is lost. The cache would then need a fallback or a flag.
- **Heap.** The JVM's default heap limit applies, which is at most a quarter of your RAM. To set a limit, pass `-Xmx` before `-jar`. A large multi-module project can use 2 GB or more after many files have been compiled.
