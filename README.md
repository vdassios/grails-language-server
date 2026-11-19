# grails-language-server

A [language server](https://microsoft.github.io/language-server-protocol/) for Groovy with Grails support, usable from any LSP editor. It compiles each open file with the **project's own** Groovy and Grails jars, so the members Grails adds at compile time (`render`, `params`, `save`, `get`, finders, ...) are visible to completion, hover and navigation.

**Status: pre-alpha, milestone M6 (distribution).** The server resolves the Gradle project model in the background, reports progress through `grails/status`, compiles each open Groovy file and publishes its diagnostics. It answers completion (with resolve, including types not imported yet), hover, signature help, definition (into library and JDK sources too), document symbols and workspace symbols. Controllers, domain classes, services and taglibs get the members Grails adds (tested on Grails 4 and Grails 7), and `def fooService` is typed as the service of that name. GORM dynamic finders, criteria, `where`, `constraints` and `mapping` blocks are understood, and so are the strings that name views, templates, layouts, controllers and actions. In GSP pages the server knows the tags and the attribute values that name files and controllers, and compiles the Groovy inside `${ }` and scriptlets as Grails translates it, with errors, completion, hover and definition there.

What is covered, planned or out of scope, feature by feature: [docs/conformance.md](docs/conformance.md).

## Grails artefacts

Grails adds most artefact members through AST transformations and traits. Compiled with the project's own jars and real file paths, the classes already carry them: `render`, `redirect`, `params`, `request` in controllers; `save`, `get`, `list`, `errors` on domain classes; the transaction manager on `@Transactional` services; `out` and `throwTagError` in taglibs; `log` with `grails-logging`. The server adds three things:

- A getter also completes as its property (`params`, `errors`, `out`), as Groovy code uses it.
- A name the transformations add after Groovy binds variables (`log`, a trait's `params`) is resolved to that member, so `params.` and `log.` complete and hover with the member's type.
- **Untyped injection.** A property `def fooService` in any class is typed as the service class `FooService` (declared under a `grails-app/services` source root, in any package, the only one of that name); the compile pulls that class in from source. Services from plugin jars are not looked up.

## GORM

A domain class is any class the compile gave GORM's entity trait, from source or from a jar. Its persistent properties are read from the compiled class: the declared ones that are neither static nor transient, `id`, `version`, one per `hasMany`, `belongsTo` and `hasOne` entry, and the properties its traits give it.

- **Dynamic finders** (`Book.findByTitleLikeAndAuthorIsNotNull(...)`) exist in no class; GORM resolves them at run time. The server reads the name against the properties (`findBy`, `findAllBy`, `countBy`, `findOrCreateBy`, `findOrSaveBy`, `listOrderBy`; every comparator; `And` or `Or`) and answers as if the method were declared: hover and signature help show its parameters and the query in words, the result is typed (the class, a list of it, a count), and definition goes from each condition to its property. Completion builds the name one step at a time: the prefixes, then the properties, then the comparators and connectors.
- **Warnings** for a finder GORM would reject: a condition on no property of the class, `And` mixed with `Or`, the wrong number of arguments. A trailing map of query settings and a closure of further criteria are allowed.
- **Criteria blocks** (`Book.withCriteria { }`, `Book.createCriteria().list { }`): calls in the block resolve against the closure's `@DelegatesTo` type, so `eq`, `like` and `order` complete, hover and show signatures. The same holds for any method whose closure parameter declares `@DelegatesTo` with a class. A string that names a property (`eq('title', x)`) completes and goes to the property, through association blocks too (`books { like('title', x) }`).
- **`where` blocks** (`Book.where { title == wanted }`, `find` and `findAll` with a closure): GORM rewrites these closures at compile time and leaves no node of the original code, so the names are read from the text. Property names complete, hover and go to their declaration, along association paths (`author.name`).
- **`constraints` and `mapping` blocks**: an entry (`title blank: false`) goes to its property, and the constraint names complete after it.
- **Query settings**: `max`, `offset`, `sort`, `order` and the others complete as argument keys of a query, and the value of `sort:` completes and resolves as a property name.

Named queries (`static namedQueries`) are not covered.

## Conventions

Strings that name a view, template, layout, controller or action go to that file or declaration and complete from what exists. Files are looked up under `grails-app/views` and `grails-app/controllers` of every module of the project, the current module first.

| Where | Names |
|---|---|
| `render(view: 'show')`, `respond x, view: 'show'` in a controller | `views/<controller>/show.gsp` (then `.jsp`, `.gson`); `'/a/b'` is `views/a/b.gsp` from anywhere |
| `render(template: 'row')` | `views/<controller>/_row.gsp` (then `_row.gson`); `'/a/row'` is `views/a/_row.gsp` |
| `static layout = 'main'` in a controller | `views/layouts/main.gsp` |
| `controller:` in `redirect`, `chain`, `forward`, `createLink`, `link` and in `UrlMappings` | the controller class (`'book'` or, as Grails's URL converter reads it, `'Book'`) |
| `action:` in the same calls; keys of `static allowedMethods` | the action in the named controller, else in the current one: a public method that is not static, or a property assigned a closure, declared in the controller or in a superclass from the project's sources |
| `view:` in `UrlMappings` | an absolute view, or one of the `controller:` given beside it |

- **Completion** of these strings offers the views and templates of the controller first, then every one by absolute path, the layouts, the controllers by logical name and the actions of the controller. The keys of `render` and `redirect` complete as named arguments.
- **Hints** for a `render(view:)` or `render(template:)` whose file does not exist.

Not covered: actions inherited from a superclass in a jar (such as `RestfulController`), views of namespaced controllers, `grailsApplication.getXxxClasses()`. For GSP pages, see below.

## GSP pages

The server reads a page's tags and attributes, then translates the page to Groovy with Grails's own GSP compiler (`org.grails.gsp.compiler.GroovyPageParser`, from the project's class path, so the translation matches the project's Grails version) and compiles that in the page's module, as it compiles a Groovy file.

- **Code** in `${ }` (in text and in attribute values), `<% %>`, `<%= %>` and `%{ }%`: errors land on the page (a column inside the code, else the page line the compiler names), and completion, signature help, hover and definition work as in Groovy code, also while an expression is still unfinished (`${book.`). Classes come from `<%@ page import="..." %>` or their full names; completion inserts no imports on a page. A page the GSP compiler rejects (a tag not closed, `g:each` without `in`) gets that one error.
- **Implicit objects** are typed as Grails binds them for a request, from the project's own classes: `params`, `request`, `response`, `session`, `flash`, `application`, `webRequest`, `actionName`, `controllerName`, `applicationContext`, `grailsApplication`, `pageScope` and `out`.
- **Model variables**: the keys of the literal model maps of every action that renders the view (`render(view: 'show', model: [book: book])`, `respond x, model: [...]`, a map the action returns), from all modules' controllers; for a template, the keys of `render(template: 'row', model: [...])` in actions and of `<g:render template="row" model="[...]"/>` in every module's pages. They complete in page code, and definition goes to the map entry. A key is typed when the controller states its value's type (a typed variable or parameter, `new`, a cast or a literal), else by a compile of the controller when the page's module sees its classes (once per controller text; a large controller takes seconds). A template's key is typed by the compile of the page that renders it, once that page has been compiled with its current text (opened, or reached from it). Keys that renders give different types stay untyped. Layouts get none.
- **Page variables**: a `g:set var` is typed by its `value`, and definition goes to the `var` attribute. A `g:each var` is typed by the elements of its `in` when the collection's type says them (`List<Book>`, an array), and so is `it` in a `g:each` without `var`; `status` is an `int`.
- **Tags called as methods**: `g.createLink(action: 'show')` and `ns.tag(...)` for any namespace of the taglib index, in page code and in Groovy files, and in page code a call with no receiver that names a `g:` tag (`${message(code: 'x')}`), as Grails runs it. Tag names complete after `g.`, attribute names inside the call's parentheses; hover and definition work as for `<g:createLink>`.

- **Tags** come from the project's taglibs (`*TagLib.groovy` below `grails-app/*/taglib/`), the taglib classes in its jars (Grails's own `g:` tags, plugins such as `asset:` or `f:`) and the GSP compiler's built-in tags (`g:if`, `g:each`, `g:set`, ...). Tag names complete after `<ns:`, attribute names inside a tag (required ones first). Hover shows the tag's doc comment and the attributes it documents with `@attr`, read from the project's source or a jar's sources jar; definition goes to the tag's closure. Hints: a tag no taglib of a known namespace declares, a `REQUIRED` attribute not given.
- **Attribute values** that name something go there and complete from what exists, as in Groovy code:

| Where | Names |
|---|---|
| `<g:render template="row">` | `views/<controller>/_row.gsp`; `"/a/row"` is `views/a/_row.gsp` |
| `<g:include view="show">` | `views/<controller>/show.gsp` (then `.jsp`, `.gson`), or a view of the tag's `controller` |
| `<meta name="layout" content="main">`, `<g:applyLayout name="main">` | `views/layouts/main.gsp` |
| `src` or `href` of `asset:stylesheet`, `javascript`, `image`, `assetPath`, `link` | the file below any `grails-app/assets/*/` directory; a stylesheet or script name also matches the extensions asset-pipeline compiles (`.scss`, `.less`, `.ts`, ...) |
| `controller` and `action` of any `g:` tag | the controller class, and the action in the tag's controller or the page's |

  A page's controller is the first directory below `views/` (`views/book/show.gsp` and `views/book/part/x.gsp` belong to `book`), which is how Grails resolves relative names. A value holding `${ }` or a URL names nothing. Hints: a template, view, layout or asset that does not exist.

Not covered: assets that come from plugin jars (`META-INF/assets`), `contextPath` and `plugin` attributes of `g:render`.

## Library sources

Definition and hover reach classes that have no source in the project: jars, the JDK and the project's own compiled Java.

- A jar's sources are the `-sources.jar` already on disk for it: next to the jar (a Maven repository) or in Gradle's module cache, where an IDE or a `sources` download put it. A library without one has no definition. JDK classes come from `lib/src.zip` of the JDK the server runs on.
- **Nothing is downloaded unless the client asks.** The request `grails/downloadSources` (trusted workspaces only) runs the project's Gradle wrapper once more and resolves the sources artifacts of every library on the compile classpaths, so they land in Gradle's own cache, where the server looks. It answers with counts: libraries with sources now, libraries that publish none, and failures (offline, repository errors) with the first reason. Definition and hover find the new jars at once; no reload is needed.
- The file is extracted once, read-only, under `<cacheDir>/sources/` and returned as an ordinary `file:` location, so every editor opens it with no client support.
- A method a class gets from a trait goes to the trait's source: Grails's `render`, `redirect`, `save`, `list` to `ResponseRenderer.groovy` or `GormEntity.groovy`, and a method or property of a trait of the project to that trait's file.
- Hover, completion resolve and signature help show the declaration's documentation comment. For the project's own sources on Groovy 2.5, which keeps no comments on its nodes, it is read from the text each source was compiled from, the same way.
- The declaration is found by scanning the source text for its names, since the project's Groovy parses no Java: among overloads, the one with the same number of parameters and the most matching type names. A getter or setter with no method of that name in the text goes to the property it reads or writes. A member the scan misses (a record component) goes to its class.

## Install

Download `grails-language-server-all.jar` from the [releases](https://github.com/vdassios/grails-language-server/releases), or build it (`./gradlew shadowJar`, see "Build"). It speaks LSP over stdio:

```sh
java -jar grails-language-server-all.jar
```

**Run it on the project's JDK** (the one its Gradle build uses): the server compiles with the project's own Groovy, which reads the JDK's class files with its bundled ASM, and it runs the project's Gradle wrapper on the same JDK. The newest JDK a Groovy release can run on depends on its ASM; measured for some releases:

| Project's Groovy | Grails | Newest JDK for the server |
|---|---|---|
| 2.5.14 | 4 | 15 (the JDK's class files up to version 59) |
| 3.0.14 | 5, 6 | 19 (version 63) |
| 3.0.23 | 5, 6 | 23 (version 67) |
| 4.0.4 | 7 | 19 (version 63) |
| 4.0.33 | 7 | 27 (version 71) |
| 5.1.3 | 8 | 27 (version 71) |

The server itself needs Java 11 or later, Grails 7 and 8 need 17.

The server does nothing until the client sends `initializationOptions.trusted = true` (see "Trust"). The workspace root must be the Gradle root, the directory with `settings.gradle` (or `gradlew`); the server reads the project model from there.

## Editor setup

Each setup below starts `java` from `PATH`; give the full path of the project's JDK's `java` if `PATH` points at another. Replace `/path/to/grails-language-server-all.jar`. Each was checked by opening a Grails 4 controller and asking for hover on `render`, except Zed.

### Neovim (0.11 or later)

```lua
vim.lsp.config('grails_ls', {
  cmd = { 'java', '-jar', '/path/to/grails-language-server-all.jar' },
  filetypes = { 'groovy', 'gsp' },
  root_markers = { 'settings.gradle', 'settings.gradle.kts', 'gradlew' },
  init_options = { trusted = true },
})
vim.lsp.enable 'grails_ls'
```

Neovim detects `*.gsp` as `gsp`; [grails.nvim](https://github.com/vdassios/grails.nvim) adds a GSP tree-sitter grammar.

### Helix

In `~/.config/helix/languages.toml`:

```toml
[language-server.grails-language-server]
command = "java"
args = ["-jar", "/path/to/grails-language-server-all.jar"]
config = { trusted = true }

[[language]]
name = "groovy"
language-servers = ["grails-language-server"]
roots = ["settings.gradle", "settings.gradle.kts"]
```

Helix sends `config` as the initialization options. It takes the topmost of the `roots` markers below the workspace (the version-control root), which is the Gradle root unless the repository holds several Gradle builds. Helix has no GSP language.

### Emacs (eglot, Emacs 29 or later)

With a `groovy-mode` (for example `groovy-emacs-modes` from MELPA):

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(groovy-mode . ("java" "-jar" "/path/to/grails-language-server-all.jar"
                                :initializationOptions (:trusted t)))))
;; Without this, the project root is the version-control root.
(setq project-vc-extra-root-markers '("settings.gradle" "settings.gradle.kts"))
```

Then `M-x eglot` in a Groovy buffer, or `(add-hook 'groovy-mode-hook #'eglot-ensure)`.

### VS Code

VS Code starts language servers only from extensions. A local extension of two files is enough: put them in a folder, run `npm install` there, and link the folder into `~/.vscode/extensions/` (or open it and press F5 to try it). Set `grailsLanguageServer.jar` (and `grailsLanguageServer.java` if `java` on `PATH` is not the project's JDK) in the settings.

`package.json`:

```json
{
  "name": "grails-language-server-local",
  "version": "0.0.1",
  "engines": { "vscode": "^1.91.0" },
  "main": "extension.js",
  "activationEvents": ["onLanguage:groovy"],
  "contributes": {
    "configuration": {
      "properties": {
        "grailsLanguageServer.jar": { "type": "string", "description": "Path to grails-language-server-all.jar" },
        "grailsLanguageServer.java": { "type": "string", "default": "java", "description": "java to run it with (the project's JDK)" }
      }
    }
  },
  "dependencies": { "vscode-languageclient": "^11.0.0" }
}
```

`extension.js`:

```js
const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

let client;

exports.activate = async () => {
  const config = vscode.workspace.getConfiguration('grailsLanguageServer');
  client = new LanguageClient(
    'grails-language-server',
    'Grails',
    { command: config.get('java'), args: ['-jar', config.get('jar')] },
    {
      documentSelector: [
        { scheme: 'file', language: 'groovy' },
        { scheme: 'file', pattern: '**/*.gsp' },
      ],
      initializationOptions: { trusted: true },
    },
  );
  await client.start();
};

exports.deactivate = () => client?.stop();
```

The workspace folder is the root, so open the Gradle root.

### Zed

Not set up yet. Zed starts language servers only through its extensions (compiled to WebAssembly), and the Groovy extension in its registry downloads and starts its own server; its source reads no `binary` setting that could point it here. Zed needs a small extension of its own for this server; none exists yet.

## Known limits

- **JDK 17 with Groovy 4:** after the project model changes, a closed module loader can stay in memory when the server runs on JDK 17: JDK 17 keeps method-handle type caches that refer to the loader's classes (seen on a Groovy 4 project). JDK 19 and later do not. A Grails 7 project on a recent Groovy 4 can run the server on JDK 21 (see the table under "Install").
- **Heap:** the JVM's defaults apply (a quarter of the RAM at most). Give `-Xmx` before `-jar` to cap it; a large multi-module project can use 2 GB or more after compiling many files.

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
| `grails/downloadSources` | client to server (request) | none; result `{ "available": number, "unavailable": number, "failed": number, "message"?: string }`: libraries with a sources jar on disk now, libraries that publish none, libraries whose sources could not be fetched, and the reason when something failed or nothing ran (untrusted workspace, Gradle error or timeout) |

The server logs each startup stage once with `window/logMessage`, as `startup: <stage> after <ms> ms` (stages: model resolved, declarations indexed, symbols indexed, first diagnostics).

Initialization options: `trusted` (boolean, required to do anything), `gradle.timeoutSeconds` (default 300), `cacheDir` (default `$XDG_CACHE_HOME/grails-language-server`, else `~/.cache/grails-language-server`).

## Build

Building needs JDK 17 or later (Gradle 9); the server itself targets Java 11 and runs on the project's JDK.

```sh
./gradlew build      # compiles api, analysis and server, runs the tests
./gradlew shadowJar  # build/libs/grails-language-server-all.jar
java -jar build/libs/grails-language-server-all.jar   # LSP over stdio
```

Releases: pushing a tag `v*` runs CI, and once the build passes it publishes the tested jar as a GitHub release (a tag with a hyphen, such as `v0.6.0-alpha.1`, as a pre-release).

The project-model tests run the fixture builds under `fixtures/` (see its README) with their own Gradle wrappers. They need a JDK 11 (Grails 4 fixture) and a JDK 17 (Grails 7 and plain Groovy fixtures), given as `GLS_TEST_JDK11` and `GLS_TEST_JDK17`; a test whose JDK is not set is skipped. `-PtestJavaHome=<jdk>` runs the tests on another JDK; CI runs them on JDK 11, the oldest the server supports, with both fixture JDKs set.

`analysis` is tested twice: `test` on Groovy 2.5, `testGroovy4` on Groovy 4. The server tests that compile the Grails 7 fixture in-process (tagged `jdk17`) run in `:server:testJdk17` on the JDK in `GLS_TEST_JDK17`, since Grails 7 needs Java 17.

`./gradlew :server:perfTest` measures compile and query latency, outside the default build: on the Grails 4 fixture, or on the built project in `GLS_PERF_PROJECT` (resolved with the JDK in `GLS_PERF_JDK`). It writes counts and timings only, under `server/build/perf/`. `LatencyTest.artefactSweep` compiles every Groovy file of the project as the open file and reports time, errors and whether Grails's members are present, per artefact kind and kind of module (`sweep.txt`).

## Origin

A fork of [groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server) by Prominic.NET, Inc. Its providers and AST visitor are the starting point of `analysis`. See `NOTICE`.

## License

[Apache License 2.0](LICENSE).
