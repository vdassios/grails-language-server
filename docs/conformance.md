# Feature status

This page lists the features that a Grails editor can offer, and which of them the server supports. The list starts from two other Grails tools, so that you can compare the server with them:

- the [Grails VS Code extension](https://github.com/Johan070119/Grails-VsCode-Extension): every feature in its README, as of v0.7.0
- the [IntelliJ Grails plugin](https://github.com/apache/grails-intellij-plugin): the features that the server was designed to match

For details of how each feature works, see [Features](features.md). For what comes next, see [Roadmap](roadmap.md).

| Status | Meaning |
|---|---|
| Supported | Works today, and has tests. |
| Planned | Not built yet, but will be. |
| Not supported | Not built, and not planned yet. |
| Candidate | Not planned, but could be added later. |
| Out of scope | Belongs in an editor plugin or another tool, not in the server. |

## Completion

From the Grails VS Code extension. Domain, GORM, and controller members are read from the compiled classes and from Grails's own transformations and traits, not from a hand-written list.

| Feature | Status | Notes |
|---|---|---|
| Domain properties after `book.` | Supported | Includes properties that a trait adds. |
| Dynamic finders: `findBy`, `findAllBy`, `countBy`, ... | Supported | Checked against the domain class's properties. |
| Chained finders: `findByTitleAnd` → `findByTitleAndAuthor` | Supported | One step at a time: property, comparator, connector. |
| Static GORM methods: `list`, `get`, `getAll`, `read`, `load`, `withCriteria`, `where`, `whereAny` | Supported | |
| Instance GORM methods: persistence, validation, dirty checking, locking, session | Supported | |
| `hasMany` and `belongsTo` properties | Supported | |
| Inside `constraints`, criteria, and `where` blocks | Supported | Constraint names, criteria methods, and properties. |
| Service methods after `bookService.` | Supported | Also when declared as `def bookService`. |
| Members after `BookController.` and `BookService.` | Supported | |
| Controller variables: `params`, `request`, `response`, `session`, `flash`, `grailsApplication`, `servletContext`, `controllerName`, `actionName` | Supported | |
| Named arguments of `render` and `redirect` | Supported | |
| Values of `view: "`, `controller: "`, and `action: "` | Supported | Views include folders. Actions come from the named controller, or else the current one. |
| Classes that are not imported yet | Supported | Project classes only. Adds the import: see [Imports](#imports). |
| `import` and `import static` statements | Supported | Packages, classes, and static members from the project, its jars, and the JDK. |
| Keys inside `mapping` blocks | Not supported | |
| `addTo*` and `removeFrom*` | Not supported | GORM resolves them at run time. |

### Imports

Completing a class that is not imported yet also adds its import, unless the class is:

- in the file's own package
- covered by a star import
- covered by Groovy's default imports
- in the default package

The import goes after the existing imports. If there are none, it goes after the `package` line. If there is no `package` line, it goes after any shebang line and header comment.

## Navigation

From the Grails VS Code extension. All of these are supported.

| In | On | Goes to |
|---|---|---|
| Controller | a domain class name | the domain class |
| Controller | `book.title`, `book?.title` | the property, or the class for `id` and `version` |
| Controller | `Book.findByTitle(title)` | the property |
| Controller | `render(view: 'show')`, `render(view: '/layouts/main')` | the view |
| Controller | `render(template: 'row')` | `_row.gsp` |
| Controller | `redirect(action: 'show')` | the action in the same controller |
| Controller | `redirect(controller: 'book', action: 'show')` | the action in `BookController` |
| Controller | `bookService.save(book)` | the method |
| Controller | `BookController.show()`, `BookService.save(book)` | the method |
| Controller | a call to a local method | the method |
| Any file | `BookController` | its file |
| Any file | `def bookService` | `BookService.groovy` |
| GSP | `<g:render template="row">` | `_row.gsp` |
| GSP | `controller="book" action="show"` | the action |
| GSP | `<app:badge>` | the tag's closure in your taglib |
| GSP | `<asset:javascript src="application.js">` | the asset, unless it is in a plugin jar |
| GSP | `<meta name="layout" content="main">` | the layout |

## Navigation shared with grails.nvim

[grails.nvim](https://github.com/vdassios/grails.nvim) includes a small Lua server that answers go to definition until this server reports `ready`, then goes quiet. Every jump the Lua server offers must keep working after the handover.

The tests check this with one list of cases, `tests/definition_cases.lua` in grails.nvim, run against `fixtures/grails4`: first on the Lua server alone, then on this server once it is ready. A "Both" case must give the same target from each. The list is the Lua server's full scope, and will not grow.

| Case | Answered by |
|---|---|
| `<g:render template="row">` and `template="/book/row"` from a page in a subfolder, such as `views/book/part/` | Both |
| `<tmpl:row/>` and `<tmpl:/book/row/>` | Both |
| `<g:render contextPath="/book" template="row">` | Both |
| `<g:include view="summary">` to a `.gson` view | Both |
| `<meta name="layout" content="main">` and `<g:applyLayout name="main">` | Both |
| `<asset:stylesheet src>` and `<asset:javascript src>` without an extension, and `<asset:link href>` | Both |
| `controller` and `action` of a `g:` tag | Both |
| `render(view:)` relative or to a `.jsp` view, `render(template:)`, and `static layout` | Both |
| `controller:` and `action:` in `redirect`, `chain`, `forward`, `createLink`, and `g.link` | Both |
| `controller` of a tag outside `g:`, such as `<sec:link controller="book">` | Lua server only |
| `respond(..., view:)` | This server only |
| Keys of `static allowedMethods` | This server only |
| `controller:`, `resources:`, `resource:`, and `action: [GET: 'show']` in `UrlMappings` | This server only |

Where the two servers differ:

- **`controller` outside `g:`.** This server reads `controller` and `action` only on `g:` tags, because other namespaces use these names for their own purposes.
- **`template` and `view`.** The Lua server reads them on any `g:` tag. This server reads them only on `g:render` and `g:include`.
- **`<g:include controller="author" view="show">`.** The Lua server ignores `controller` and looks among the views of the page's own controller. This server looks among the views of `author`. This case is not on the list.

## Other features of the Grails VS Code extension

| Feature | Status | Notes |
|---|---|---|
| Find all references | Planned | |
| Rename, including the file of a class, view, or template | Planned | |
| Call hierarchy | Candidate | |
| CodeLens from an action to its view | Candidate | Go to definition on the `render` string already does this. |
| Project view, and creating artefacts from it | Out of scope | |
| Grails CLI commands: `run-app`, `test-app`, `clean`, `compile` | Out of scope | |
| Status bar with the Grails version | Out of scope | The server reports its state through `grails/status`. |

## Features of the IntelliJ Grails plugin

| Feature | Status | Notes |
|---|---|---|
| Domain members | Supported | |
| Domain descriptor: persistent properties, `hasMany`, `belongsTo`, `transients` | Supported | |
| Dynamic finder grammar and completion | Supported | |
| Finder diagnostics: unknown property, mixed `And` and `Or`, wrong argument count | Supported | Warnings. Tested against GORM's own `DynamicFinder`. |
| GORM static methods and their named arguments | Supported | Includes query settings such as `max` and `sort`. |
| Criteria blocks | Supported | Association blocks switch to the associated class. |
| Controller members, `params`, `request` | Supported | |
| `static allowedMethods` keys | Supported | |
| GSP tags: completion, attributes, hover, go to definition | Supported | Built-in, plugin, and project taglibs. `@attr` docs come from sources or sources jars. |
| Tags called as methods: `g.tag(...)`, `ns.tag(...)` | Supported | In GSP code and in Groovy files. |
| Typed GSP expressions, with diagnostics | Supported | Pages are translated by Grails's own `GroovyPageParser`, then compiled. |
| GSP implicit objects: `request`, `params`, `out`, `pageScope`, ... | Supported | |
| GSP model variables | Supported | From the action's literal model map, template models, `g:set var`, and `g:each var` with its `status`. |
| GSP inspections: unresolved template or view, unknown tag, missing required attribute | Supported | Hints. |
| Controller, action, and view references in `UrlMappings.groovy` | Supported | A view is absolute, or relative to the `controller:` next to it. |
| `namedQueries` | Not supported | |
| i18n: from `g:message code` to `grails-app/i18n/*.properties` | Not supported | |
| Interceptor `match(controller:, action:)` | Not supported | |
| Grails keys in `application.yml` | Not supported | |
| Code actions, such as "Create view" and "Create template" | Not supported | |

## Other LSP features

| Feature | Status |
|---|---|
| Type definition | Planned |
| Document highlight | Planned |
| Inlay hints for `def` | Candidate |
| Semantic tokens | Candidate |
| CodeLens with reference counts | Candidate |
| Type hierarchy | Candidate |
| Diagnostics for unused imports | Candidate |
| Organize imports | Candidate |
| Formatting | Out of scope |
