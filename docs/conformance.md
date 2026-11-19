# Conformance checklist

Every feature of the [Grails VS Code extension](https://github.com/Johan070119/Grails-VsCode-Extension) (its README, v0.7.0) and of the [IntelliJ Grails plugin](https://github.com/apache/grails-intellij-plugin) that the design names, and where this server stands on each.

- **covered**: works today, with tests.
- **planned**: part of the server's LSP surface, not built yet.
- **not covered**: not built and not planned yet.
- **out of scope**: belongs in an editor client or another tool, not in the server. Rows marked *candidate* could be added later.

## Completion (Grails VS Code extension)

| Feature | Status | Notes |
|---|---|---|
| Domain properties after `instance.` | covered | From the compiled class, including properties a trait gives |
| Dynamic finders (`findBy`, `findAllBy`, `countBy`, ...) | covered | Read against the domain's real properties |
| Finder chaining (`findByTitleAnd` → `AndAuthor`, ...) | covered | Step by step, per property and comparator |
| Static GORM methods (`list`, `get`, `getAll`, `read`, `load`, `withCriteria`, `where`, `whereAny`) | covered | Members GORM's transformations add |
| Instance GORM methods (persistence, validation, dirty checking, locking, session) | covered | Same |
| `hasMany`/`belongsTo` relations | covered | The properties Grails adds for them |
| `addTo*` / `removeFrom*` helpers | not covered | GORM resolves them at run time; only `addTo(String, Object)` exists in the class |
| Completion inside `constraints`, `mapping`, criteria and `where` | covered | Constraint names, criteria methods, properties in `where`; `mapping` keys are not offered |
| Service methods after `fooService.` (typed or `def`) | covered | `def fooService` is typed as `FooService` from `grails-app/services` |
| Actions after `FooController.`, methods after `FooService.` (by class name) | covered | Members of the compiled class |
| Unimported class names (`Swagger` → `SwaggerController`) | covered | With an import edit |
| Controller scope variables (`params`, `request`, `response`, `session`, `flash`, `grailsApplication`, `servletContext`, `controllerName`, `actionName`) | covered | Members Grails's controller traits add |
| `render(` / `redirect(` named arguments | covered | |
| View names after `view: "` (with folders) | covered | |
| Controller names after `controller: "` | covered | |
| Action names after `action: "` | covered | Of the named controller, or the current one |
| Project packages and classes after `import pkg.` | covered | Packages and classes of the project, its jars and the JDK; static members after `import static pkg.Class.` |

## Navigation (Grails VS Code extension)

| From | On | To | Status |
|---|---|---|---|
| Controller | a domain class name | the domain class | covered |
| Controller | `book.title`, `book?.title` | the property's line | covered (`id` and `version`, added by Grails, have no line) |
| Controller | `Area.findByName(` | the property's line | covered |
| Controller | `render(view: 'show')`, `render(view: '/layouts/main')` | the view | covered |
| Controller | `render(template: 'row')` | `_row.gsp` | covered |
| Controller | `redirect(action: 'logIn')` | the action in the same controller | covered |
| Controller | `redirect(controller: 'book', action: 'show')` | the action in `BookController` | covered |
| Controller | `fooService.method(` | the method | covered |
| Controller | `SomeController.method(`, `SomeService.method(` | the method | covered |
| Controller | a local method call | the method | covered |
| Any file | `BookController` | its file | covered |
| Any file | `def bookService` | `BookService.groovy` | covered |
| GSP | `<g:render template="row">` | `_row.gsp` | covered |
| GSP | `controller="book" action="show"` | the action | covered |
| GSP | `<app:badge>` | the tag's closure in the project's taglib | covered |
| GSP | `<asset:javascript src="application.js">` | the asset | covered (assets inside plugin jars are not looked up) |
| GSP | `<meta name="layout" content="main">` | the layout | covered |

## Other features (Grails VS Code extension)

| Feature | Status | Notes |
|---|---|---|
| Find all references | planned | |
| Rename, renaming the file of a class, view or template | planned | |
| Call hierarchy | out of scope, candidate | |
| Project view (side panel of artefacts) | out of scope | Editor client |
| Creating artefacts from the project view | out of scope | Editor client |
| Grails CLI commands (`run-app`, `test-app`, `clean`, `compile`) | out of scope | Editor client |
| Status bar with the Grails version | out of scope | Editor client; the server reports its state through `grails/status` |
| CodeLens: action → its view | out of scope, candidate | Definition on the `render` string reaches the view |

## IntelliJ Grails plugin features named in the design

| Feature (IntelliJ source) | Status | Notes |
|---|---|---|
| Domain members (`DomainMembersProvider`, `GrailsArtifactNonCodeMemberProcessor`) | covered | From Grails's own transformations, not from a table |
| Domain descriptor: persistent properties, `hasMany`, `belongsTo`, `transients` (`DomainDescriptor`, `DomainClassUtils`) | covered | |
| Dynamic finder grammar and completion (`GormDynamicFinderCompletionProvider`) | covered | |
| Finder diagnostics: unknown property, mixed `And`/`Or`, argument count | covered | Warnings |
| GORM static API and named arguments (`DomainStaticMemberCompletionProvider`, `Gorm*NamedArgumentProvider`) | covered | Query settings (`max`, `sort`, ...) as argument keys |
| Criteria blocks (`references/domain/criteria/*`) | covered | Root class per block, switching at association blocks |
| `namedQueries` | not covered | |
| Controller members, `params`, `request` (`ControllerMembersProvider`, `HttpRequestMemberContributor`, `GrailsStandardDSL.gdsl`) | covered | From Grails's controller traits |
| `static allowedMethods` keys (`ControllerAllowedMethod*Provider`) | covered | |
| GSP tags: completion, attributes, hover, definition (`lang/gsp/resolve/taglib/*`, `TaglibMembersProvider`) | covered | Built-in, plugin and project taglibs, with `@attr` docs from sources jars |
| `g.tag(...)` / `ns.tag(...)` call form | covered | In GSP code and in Groovy |
| GSP expressions typed, with diagnostics | covered | The page is translated by Grails's own `GroovyPageParser` and compiled |
| GSP implicit objects | covered | `request`, `response`, `session`, `flash`, `application`, `params`, `out`, `pageScope`, ... |
| Model variables from the rendering action | covered | Literal model maps; also template models, `g:set var`, `g:each var/status` |
| GSP inspections: unresolved template or view, unknown tag, missing required attribute (`GspAndGroovyInspection`) | covered | Hints |
| `UrlMappings.groovy` controller and action references (`references/urlMappings/`) | covered | View references in `UrlMappings` are not read |
| i18n: `g:message code` → `grails-app/i18n/*.properties` (`pluginModules/i18n/`) | not covered | |
| Interceptor `match(controller:, action:)` | not covered | |
| `application.yml` Grails keys | not covered | |
| Code actions ("Create view", "Create template") | not covered | |

## LSP features beyond the design

| Feature | Status |
|---|---|
| Type definition, document highlight | planned |
| Inlay hints for `def`, semantic tokens, code lens reference counts, type hierarchy, unused-import diagnostics, organize imports | out of scope, candidates |
| Formatting | out of scope |
