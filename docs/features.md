# Features

This page describes what the server understands beyond plain Groovy. For the status of each feature, see [Feature status](conformance.md).

## Grails artefacts

Grails adds most of an artefact's members through AST transformations and traits. The server compiles your classes with your project's jars and with their real file paths, so after the compile these members are already there. For example:

- Controllers have `render`, `redirect`, `params`, and `request`.
- Domain classes have `save`, `get`, `list`, and `errors`.
- Services annotated with `@Transactional` have the transaction manager.
- Taglibs have `out` and `throwTagError`.
- With `grails-logging`, classes have `log`.

The server adds three things on top of the compile:

- **Getters as properties.** A getter also completes as a property (`params`, `errors`, `out`), because that is how Groovy code usually uses it.
- **Late members.** Some transformations add a member after Groovy has already resolved the names in your code. Examples are `log`, and `params` when it comes from a trait. The server resolves these names to the member, so `params.` and `log.` complete, and hover shows the member's type.
- **Untyped service injection.** A property such as `def fooService`, in any class, is typed as the service class `FooService`. That class must be declared under a `grails-app/services` source root, in any package, and must be the only class with that name. The compile loads it from source. Services from plugin jars are not looked up.

## GORM

A domain class is any class that the compile gave GORM's entity trait, from source or from a jar. The server reads its persistent properties from the compiled class. They are:

- the declared properties that are neither static nor transient
- `id` and `version`
- one property for each `hasMany`, `belongsTo`, and `hasOne` entry
- the properties that its traits add
- each getter and setter pair without a field, which GORM also maps

### Dynamic finders

No class declares a dynamic finder such as `Book.findByTitleLikeAndAuthorIsNotNull(...)`. GORM resolves it at run time from the method name. The server reads the name the same way as GORM's own `DynamicFinder`, and checks it against the class's properties. It knows:

- the prefixes `findBy`, `findAllBy`, `countBy`, `findOrCreateBy`, `findOrSaveBy`, and `listOrderBy`
- every comparator
- the connectors `And` and `Or`

The server then treats the finder as if it were a declared method:

- Hover and signature help show its parameters and describe the query in words.
- The result has a type: the domain class, a list of it, or a count.
- Go to definition takes you from each condition to its property.
- Completion builds the name one step at a time: first the prefixes, then the properties, then the comparators and connectors. It works after the class name, and without a receiver inside the domain class. The server marks the list as incomplete, so your editor asks again as the name grows instead of filtering the previous step.

The server warns you about a finder that GORM would reject:

- A condition names no property of the class. A property declared as `def` or `Object` counts as no property, because GORM does not map properties of type `Object`.
- The name mixes `And` and `Or`.
- A property's name contains the connector where GORM splits the name, as in `findByOrderNoteOrTitle`.
- The call has too few arguments.

Extra arguments after the ones the conditions need are allowed, such as a map of query settings or a closure of further criteria.

### Criteria blocks

In a criteria block such as `Book.withCriteria { }` or `Book.createCriteria().list { }`, calls resolve against the closure's `@DelegatesTo` type. So `eq`, `like`, and `order` complete, and show hover and signature help. This works for any method whose closure parameter declares `@DelegatesTo` with a class.

A string that names a property, as in `eq('title', x)`, completes and takes you to the property. This also works inside association blocks, as in `books { like('title', x) }`.

### `where` blocks

`where` blocks include `Book.where { title == wanted }`, and `find` and `findAll` with a closure. GORM rewrites these closures at compile time and keeps nothing of the original code, so the server reads the names from the text instead. Property names complete, show hover, and take you to their declaration. This includes association paths such as `author.name`.

### `constraints` and `mapping` blocks

An entry such as `title blank: false` takes you to its property. After the property name, the constraint names complete. The scaffolding constraints, such as `display`, `widget`, and `password`, are included.

### Query settings

`max`, `offset`, `sort`, `order`, and the other query settings complete as argument keys of:

- `list`, `listDistinct`, and `findAll`
- a finder, after the values of its conditions

They do not complete for `get`. The value of `sort:` completes and resolves as a property name.

### Limitations

- Named queries (`static namedQueries`) are not supported.
- GORM's boolean finder forms are not supported. For a boolean property `paperback`, `Book.findPaperbackByAuthor(a)` and `findAllNotPaperbackByAuthor` get no completion, type, hover, or warning.

## Conventions

Grails finds views, templates, layouts, controllers, and actions by name. When a string in your code names one of them, the server takes you to it, and completes the name from what exists. It looks for files under `grails-app/views` and `grails-app/controllers` in every module of the project, starting with the current module.

| Where the string is | What it names |
|---|---|
| `render(view: 'show')` or `respond x, view: 'show'` in a controller | `views/<controller>/show.gsp`, or else `.jsp`, or else `.gson`. From anywhere, `'/a/b'` names `views/a/b.gsp`. |
| `render(template: 'row')` | `views/<controller>/_row.gsp`, or else `_row.gson`. `'/a/row'` names `views/a/_row.gsp`. |
| `static layout = 'main'` in a controller | `views/layouts/main.gsp` |
| `controller:` in `redirect`, `chain`, `forward`, `createLink`, `link`, and `UrlMappings`. `resources:` and `resource:` in `UrlMappings`. | The controller class. Both `'book'` and `'Book'` work, because Grails's URL converter reads both. |
| `action:` in the same calls. Each value of an `action: [GET: 'show']` map in `UrlMappings`. The keys of `static allowedMethods`. | The action in the named controller, or else in the current one. An action is a public method that is not static, or a property assigned a closure. It can be declared in the controller or in a superclass from your sources. |
| `view:` in `UrlMappings` | An absolute view, or a view of the `controller:` given next to it. |

**Completion** offers, in this order: the views and templates of the current controller, every view and template by absolute path, the layouts, the controllers by logical name, and the actions of the controller. Views, templates, layouts, and controllers are listed from the files on disk. So a file that you have created in the editor but not saved yet is not offered. The keys of `render` and `redirect` also complete as named arguments.

**Hints** appear for a `render(view:)` or `render(template:)` whose file does not exist. There is no hint when the file might come from somewhere the server does not model:

- a `render` with a `plugin` or `contextPath` argument
- a relative name in a controller that declares `static namespace`, or in an abstract controller
- a view that a plugin jar on the class path lists as precompiled, in `gsp/views.properties`

### Limitations

- Actions inherited from a superclass in a jar, such as `RestfulController`, are not found.
- Views of namespaced controllers are not resolved.
- `grailsApplication.getXxxClasses()` is not supported.

For names in GSP pages, see [Attribute values](#attribute-values).

## GSP pages

The server first reads the tags and attributes of a page. Then it translates the page to Groovy with Grails's own GSP compiler, `org.grails.gsp.compiler.GroovyPageParser`. It loads that compiler from your class path, so the translation matches your Grails version. Finally, it compiles the result in the page's module, the same way as a Groovy file.

### Page code

These parts of a page are code:

- `${ }`, in text and in attribute values
- `<% %>`, `<%= %>`, and `%{ }%`
- an attribute value of a GSP tag that is a whole `[...]`, such as `model="[book: book]"` or `params="[id: book.id]"`. Grails writes these out as Groovy.

The server follows the same rules as Grails:

- Inside a GSP tag, only `${ }` is code.
- `<%! %>` declarations contain no code. Grails rejects them.
- `\${` is text on Grails 8. Up to Grails 7, it is an expression, and the `\` is printed.

Errors appear on the page, at their column in the code. If the compiler places an error outside the page's code, it appears on the nearest line of page code.

Completion, signature help, hover, and go to definition work as they do in Groovy code. They also work while you are still typing an expression, such as `${book.`.

Grails compiles `%{ }%` code with each run of white space collapsed into one space. It also turns `&amp;`, `&lt;`, `&gt;`, and `&semi;` into their characters, and `<br>` into a line break. So statements in `%{ }%` must be separated by `;`, as they must be when Grails compiles the page. Positions in this code still map back to the page.

Classes come from `<%@ page import="..." %>` or from their fully qualified names. Completion does not add imports to a page.

If the GSP compiler rejects a page, for example because a tag is not closed or a `g:each` has no `in`, the page gets that one error.

### Implicit objects

The implicit objects are typed the way Grails binds them during a request, from your project's own classes: `params`, `request`, `response`, `session`, `flash`, `application`, `webRequest`, `actionName`, `controllerName`, `applicationContext`, `grailsApplication`, `pageScope`, and `out`.

### Model variables

A model variable is a key of the model map that a controller passes to a page. The server collects the keys from the literal model maps of every action that renders the view, in the controllers of every module:

- `render(view: 'show', model: [book: book])`
- `respond x, model: [...]`
- a map that the action returns

For a template, it collects the keys of `render(template: 'row', model: [...])` in actions, and of `<g:render template="row" model="[...]"/>` in the pages of every module.

The keys complete in page code, and go to definition takes you to the map entry.

A key gets its type in one of these ways:

- **From the controller's code**, when the code states the type of the value: a typed variable or parameter, `new`, a cast, or a literal.
- **By compiling the controller**, when the page's module can see the controller's classes. The server does this once for each version of the controller's text. For a large controller, this takes a few seconds.
- **For a template**, by compiling the page that renders it. That page must have been compiled with its current text, either because it is open or because it was reached from an open page.

If different renders give a key different types, the key stays untyped. Layouts get no model variables.

### Page variables

- A variable from `g:set var` is typed by its `value`. Go to definition takes you to the `var` attribute.
- A variable from `g:each var` is typed by the elements of its `in` collection, when the collection's type names them, as `List<Book>` or an array does. The same applies to `it` in a `g:each` without `var`. `status` is an `int`.

### Tags called as methods

You can call a tag as a method: `g.createLink(action: 'show')`, or `ns.tag(...)` for any namespace in the taglib index. This works in page code and in Groovy files. In page code, a call without a receiver that names a `g:` tag, such as `${message(code: 'x')}`, also works, because Grails runs it as a tag.

Tag names complete after `g.`, and attribute names complete inside the parentheses. Hover and go to definition work as they do on `<g:createLink>`.

### Tags

The server knows the tags from:

- your project's taglibs (`*TagLib.groovy` below `grails-app/*/taglib/`)
- the taglib classes in your jars, such as Grails's own `g:` tags and plugin tags like `asset:` and `f:`
- the built-in tags of the GSP compiler, such as `g:if`, `g:each`, and `g:set`

A taglib's tags are the ones that Grails registers:

- its closure properties, including those inherited from a superclass or a trait
- on Grails 8, also the methods that Grails 8 accepts as tags, declared in the taglib or in one of its traits. Such a method is public, and either can be called as `(Map attrs)`, `(Closure body)`, or `(Map attrs, Closure body)`, or is annotated with `@Tag`.

Tag names complete after `<ns:`. The `:` is a trigger character, so editors that only complete on trigger characters open the list as you type the colon. Attribute names complete inside a tag, with the required attributes first. Attributes have no trigger character, so in those editors you have to ask for completion of attribute names and values yourself.

Hover shows the tag's doc comment and the attributes that it documents with `@attr`. The server reads these from your project's source, or from a jar's sources jar. Go to definition takes you to the tag's closure or method.

Hints appear for:

- a tag that no taglib of a known namespace declares. There is no hint when the namespace's taglib inherits from a class or trait that the server cannot read.
- a missing `REQUIRED` attribute

### Attribute values

When an attribute value names something, it takes you there and completes from what exists, as in Groovy code:

| Attribute | What it names |
|---|---|
| `<g:render template="row">`, `<tmpl:row/>` | `views/<controller>/_row.gsp`. `"/a/row"` and `<tmpl:/a/row/>` name `views/a/_row.gsp`. A `contextPath` goes before the name: `contextPath="/a" template="row"` names `/a/row`. |
| `<g:include view="show">` | `views/<controller>/show.gsp`, or else `.jsp`, or else `.gson`. With a `controller` attribute, a view of that controller. |
| `<meta name="layout" content="main">`, `<g:applyLayout name="main">` | `views/layouts/main.gsp` |
| `src` or `href` of `asset:stylesheet`, `asset:javascript`, `asset:image`, `asset:assetPath`, and `asset:link` | The file below any `grails-app/assets/*/` directory. A stylesheet or script name also matches the extensions that asset-pipeline compiles, such as `.scss`, `.less`, and `.ts`. |
| `controller` and `action` of any `g:` tag | The controller class, and the action in the tag's controller or else the page's controller. |

A page belongs to the controller named by the first directory below `views/`. For example, `views/book/show.gsp` and `views/book/part/x.gsp` both belong to `book`. Grails resolves relative names the same way.

A value that contains `${ }` or a URL does not name anything.

Hints appear for a template, view, layout, or asset that does not exist. There is no hint:

- on a tag with a `plugin` or `contextPath` attribute
- for a view, template, or layout that a plugin jar lists as precompiled, in `gsp/views.properties`
- for an asset below a jar's `META-INF/assets/`

### Limitations

- Assets from plugin jars (`META-INF/assets`) are not looked up.
- The `plugin` attribute of `g:render` is not supported. It gets no definition and no hint.
- `<tmpl:row/>` and a `g:render` with a `contextPath` take you to their template, but get no hint when the template does not exist.
- Completion of attribute values ignores the `contextPath`.

## Library sources

Go to definition and hover also work on classes that have no source in your project: classes from jars, from the JDK, and from your project's own compiled Java.

### Where sources come from

- **Jars.** The server uses the `-sources.jar` that is already on disk for the jar. It looks next to the jar, as in a Maven repository, and in Gradle's module cache, where your IDE or a Gradle `sources` download puts it. A library without a sources jar has no definition.
- **The JDK.** The server uses `lib/src.zip` from the JDK that it runs on.

### Downloading sources

The server never downloads anything unless your editor asks. The `grails/downloadSources` request runs your project's Gradle wrapper once more, and resolves the sources of every library on the compile class paths. Gradle puts them in its own cache, where the server looks for them. The request only works in a trusted workspace.

The response gives three counts:

- libraries that now have sources
- libraries that publish no sources
- libraries whose sources could not be fetched, for example because you are offline or a repository failed, with the first reason

Go to definition and hover find the new sources immediately. You don't need to reload.

### How a source file opens

The server extracts the source file once, as a read-only file under `<cacheDir>/sources/`, and returns it as an ordinary `file:` location. So any editor can open it without special support.

### Members from traits

A method that a class gets from a trait takes you to the trait's source. For example, Grails's `render` goes to `ResponseRenderer.groovy`, and `save` and `list` go to `GormEntity.groovy`. A method or property of one of your project's traits takes you to that trait's file.

### Groovy extension methods

You call a Groovy extension method, such as `take` on a `List`, without its first parameter. Signature help and hover show it that way. Go to definition takes you to the static method that declares it, and its doc comment comes from there. That method is in `DefaultGroovyMethods` or another class in Groovy's sources jar, or in your project's own extension class.

### Doc comments

Hover, completion item details, and signature help show the declaration's doc comment. Groovy 2.5 does not keep comments on its syntax tree. So for your project's own sources on Groovy 2.5, the server reads the comment from the text that the file was compiled from.

### How a declaration is found

Your project's Groovy cannot parse Java, so the server finds a declaration by scanning the source text for its name:

- Among overloads, it picks the one with the same number of parameters and the most matching type names.
- A getter or setter that has no method of that name in the text takes you to the property that it reads or writes.
- If the scan misses a member, such as a record component, you go to its class instead.
