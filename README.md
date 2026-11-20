# grails-language-server

[![CI](https://github.com/vdassios/grails-language-server/actions/workflows/ci.yml/badge.svg)](https://github.com/vdassios/grails-language-server/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/vdassios/grails-language-server?include_prereleases)](https://github.com/vdassios/grails-language-server/releases)
[![License](https://img.shields.io/github/license/vdassios/grails-language-server)](LICENSE)

A [language server](https://microsoft.github.io/language-server-protocol/) for Groovy and Grails that compiles your code with your project's own Groovy and Grails. The members Grails adds at compile time (`render`, `params`, `save`, `get`, dynamic finders, ...) are visible to completion, hover and navigation, in any LSP editor.

![Neovim with grails-language-server: a GORM finder completed step by step and read on hover, a finder warning, a view name followed to its page, and a model variable completed in the page](docs/media/server.gif)

Recorded in Neovim on [`demo/bookstore`](demo/), a small Grails 7 app you can try it on.

## Features

- **Grails artefacts**: controllers, domain classes, services and taglibs carry the members Grails adds; `def fooService` is typed as that service. [More](docs/features.md#grails-artefacts)
- **GORM**: dynamic finders complete one step at a time, are typed, read in words on hover and warned about when GORM would reject them; criteria and `where` blocks, `constraints` and `mapping`. [More](docs/features.md#gorm)
- **Conventions**: strings that name views, templates, layouts, controllers and actions complete, go to their target and get a hint when it does not exist. [More](docs/features.md#conventions)
- **GSP pages**: tags and attributes from the project's taglibs and jars; page code compiled as Grails translates it, with errors, completion, hover and definition in `${ }`, and model variables typed from the controllers. [More](docs/features.md#gsp-pages)
- **Library sources**: definition and hover reach the sources of jars and the JDK; missing sources jars are downloaded on request. [More](docs/features.md#library-sources)
- **The usual LSP features**: diagnostics, completion (with imports added), hover, signature help, definition, document and workspace symbols. [Checklist](docs/conformance.md)
- **Groovy 2.5 to 5, Grails 4 to 8**: each module source set compiles with its own jars, on the project's JDK. [JDK table](docs/editors.md#install)
- **Any LSP editor**: setups checked in Neovim, Helix, Emacs and VS Code. [Editors](docs/editors.md)

## Quick start

1. Download `grails-language-server-all.jar` from the [releases](https://github.com/vdassios/grails-language-server/releases).
2. Run it on the project's JDK, the one its Gradle build uses: Groovy 2.5 (Grails 4) needs JDK 15 or older ([JDK table](docs/editors.md#install)).
3. Start it from your editor with the Gradle root as the workspace root. Neovim 0.11 or later:

```lua
vim.lsp.config('grails_ls', {
  cmd = { 'java', '-jar', '/path/to/grails-language-server-all.jar' },
  filetypes = { 'groovy', 'gsp' },
  root_markers = { 'settings.gradle', 'settings.gradle.kts', 'gradlew' },
  init_options = { trusted = true },
})
vim.lsp.enable 'grails_ls'
```

`trusted = true` lets the server run the project's Gradle build and AST transformations; send it only for projects you trust ([Trust](docs/how-it-works.md#trust)). In Neovim, [grails.nvim](https://github.com/vdassios/grails.nvim) does all of this for you: it downloads the jar, finds the JDK and adds a GSP grammar. Helix, Emacs, VS Code and Zed: [docs/editors.md](docs/editors.md).

## Status

Pre-alpha, milestone M6 (distribution). Linux and macOS only: nothing is tested on Windows. Tested on Grails 4 and Grails 7 fixtures, and measured on five public Grails projects, Grails 4 to 8 ([benchmarks/results.md](benchmarks/results.md)). What is covered, planned or out of scope, feature by feature: [docs/conformance.md](docs/conformance.md).

## Documentation

- [Features](docs/features.md): Grails artefacts, GORM, conventions, GSP pages, library sources
- [Install and editor setup](docs/editors.md): the JDK table, Neovim, Helix, Emacs, VS Code, Zed
- [How it works](docs/how-it-works.md): design, trust, protocol extensions, known limits
- [Conformance checklist](docs/conformance.md)
- [Benchmarks](benchmarks/results.md) on a [public corpus](benchmarks/corpus.md)
- [Development](docs/development.md): build, tests, performance tests, releases

## Origin

A fork of [groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server) by Prominic.NET, Inc. Its providers and AST visitor are the starting point of `analysis`. See `NOTICE`.

## License

[Apache License 2.0](LICENSE).
