# Roadmap

This page lists the work that is left on the server, and the routes it could take later. Nothing on it has a date. For what works today, see [Feature status](conformance.md).

## Planned

### Language features

These LSP features will be added:

- Find all references
- Rename, including the file of a class, view, or template
- Type definition
- Document highlight

### Editor packages

- **VS Code.** Today you set up VS Code with a small local extension that you write yourself. See [VS Code](editors.md#vs-code). A published extension, on the VS Code Marketplace and Open VSX, will replace it.
- **nvim-lspconfig and Mason.** A `grails_ls` config in nvim-lspconfig and a Mason package would let you install the server without grails.nvim. Both projects accept a server only once it shows that people use it, for example with 100 GitHub stars. The proposals wait until the server meets their criteria.

## Gaps in Grails support

None of these is planned yet. Each could be added later.

### GORM

- Boolean finders, such as `findPaperbackByAuthor` and `findAllNotPaperbackByAuthor`
- `static namedQueries`
- `addTo*` and `removeFrom*`, which GORM resolves at run time
- Keys inside `mapping` blocks

### Controllers and conventions

- Actions inherited from a superclass in a jar, such as `RestfulController`
- Views of namespaced controllers
- `grailsApplication.getXxxClasses()`
- Interceptor `match(controller:, action:)`

### GSP pages

- Assets inside plugin jars (`META-INF/assets`)
- The `plugin` attribute of `g:render`
- A hint for a missing template in `<tmpl:row/>`, and in a `g:render` with a `contextPath`
- The `contextPath` in completion of attribute values

### Other Grails files

- i18n: going from `g:message code` to `grails-app/i18n/*.properties`
- Grails keys in `application.yml`
- Code actions, such as "Create view" and "Create template"

## Possible routes

These are candidates. None is planned yet.

### Editor features

- Call hierarchy
- Type hierarchy
- CodeLens, from an action to its view, and with reference counts
- Inlay hints for `def`
- Semantic tokens
- Diagnostics for unused imports, and organize imports

### More editors

- **Zed.** Zed starts language servers only through extensions, and none exists for this server. See [Zed](editors.md#zed).

### Platforms and versions

- **Windows.** The server runs on Linux and macOS. It has not been tested on Windows.
- **Grails 5, 6, and 8 fixtures.** The tests build fixture projects for Grails 4 and 7 only. Grails 5 and 6 were checked by hand on public projects, and Grails 8 by a unit test. Fixture projects for them would run in CI.

### Memory

- **A default heap limit.** The server uses the JVM's default, which is at most a quarter of your RAM. A launcher could set a limit that fits the project.
- **A fallback for the parser cache.** If a future JDK denies the change to a final field that sets up the shared parser cache, the server loses its memory saving. See [Known limits](how-it-works.md#known-limits).
