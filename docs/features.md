# Features

What the server understands beyond plain Groovy, area by area. Feature-by-feature status: [conformance.md](conformance.md).

## Grails artefacts

Grails adds most artefact members through AST transformations and traits. Compiled with the project's own jars and real file paths, the classes already carry them: `render`, `redirect`, `params`, `request` in controllers; `save`, `get`, `list`, `errors` on domain classes; the transaction manager on `@Transactional` services; `out` and `throwTagError` in taglibs; `log` with `grails-logging`. The server adds three things:

- A getter also completes as its property (`params`, `errors`, `out`), as Groovy code uses it.
- A name the transformations add after Groovy binds variables (`log`, a trait's `params`) is resolved to that member, so `params.` and `log.` complete and hover with the member's type.
- **Untyped injection.** A property `def fooService` in any class is typed as the service class `FooService` (declared under a `grails-app/services` source root, in any package, the only one of that name); the compile pulls that class in from source. Services from plugin jars are not looked up.

## GORM

A domain class is any class the compile gave GORM's entity trait, from source or from a jar. Its persistent properties are read from the compiled class: the declared ones that are neither static nor transient, `id`, `version`, one per `hasMany`, `belongsTo` and `hasOne` entry, and the properties its traits give it.

- **Dynamic finders** (`Book.findByTitleLikeAndAuthorIsNotNull(...)`) exist in no class; GORM resolves them at run time. The server reads the name against the properties (`findBy`, `findAllBy`, `countBy`, `findOrCreateBy`, `findOrSaveBy`, `listOrderBy`; every comparator; `And` or `Or`) and answers as if the method were declared: hover and signature help show its parameters and the query in words, the result is typed (the class, a list of it, a count), and definition goes from each condition to its property. Completion builds the name one step at a time: the prefixes, then the properties, then the comparators and connectors. The list is marked incomplete, so editors ask again as the name grows rather than filtering the step they have.
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
