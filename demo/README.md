# Demo

`bookstore/` is a small Grails 7 app (two domain classes, a controller, a service, three pages and a layout) to try the server on, and the project the demo GIFs are recorded on. It compiles (`./gradlew classes`) but is not set up to run.

## Try it in Neovim

You need Neovim 0.11 or later, a JDK 17 or later as `JAVA_HOME`, [grails.nvim](https://github.com/vdassios/grails.nvim) and the `gsp`, `groovy` and `html` tree-sitter parsers. `init.lua` is a minimal config: grails.nvim and Neovim's built-in LSP completion, nothing else. From the repository root:

```sh
GRAILS_NVIM=/path/to/grails.nvim nvim -u demo/init.lua demo/bookstore/grails-app/services/bookstore/BookService.groovy
```

`GRAILS_NVIM` defaults to where lazy.nvim installs grails.nvim. Run `:GrailsInstallServer` once, or point `GRAILS_LS_JAR` at a jar you built. The first start resolves the Gradle project, which takes a while; the status line says `grails_ls ready` when it is done.

Things to try: `Book.findBy` then `<C-x><C-o>` for the next step of the finder name, `K` on a finder, `gd` on `'show'` in `BookController`, `${book.` in `views/book/show.gsp`.

## Recording the GIFs

The tapes are scripts for [vhs](https://github.com/charmbracelet/vhs). They start `nvim` with your own config, which must enable grails.nvim (the GIFs use the author's: catppuccin-macchiato, lualine, blink.cmp, nvim-autopairs; the keys assume a completion menu that opens as you type). From the repository root, on a JDK 17 or later:

```sh
./gradlew shadowJar
GRAILS_LS_JAR=build/libs/grails-language-server-all.jar vhs demo/server.tape        # docs/media/server.gif
GRAILS_LS_JAR=build/libs/grails-language-server-all.jar vhs demo/grails-nvim.tape   # build/demo/grails-nvim.gif, for grails.nvim's docs/media/
```
