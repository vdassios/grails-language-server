# Install and editor setup

To use the server, you need:

- the server's jar
- the JDK that your project builds with
- an editor set up to start the server, with your Gradle root as the workspace root

## Install

Download `grails-language-server-all.jar` from the [releases](https://github.com/vdassios/grails-language-server/releases) page, or build it yourself with `./gradlew shadowJar` (see [Development](development.md#build)). The server talks LSP over standard input and output:

```sh
java -jar grails-language-server-all.jar
```

The server itself needs Java 11 or later. For Grails 7, Grails 8, and Groovy 6, it needs Java 17 or later.

### Choose the JDK

Run the server on the JDK that your project's Gradle build uses. There are two reasons for this:

- The server compiles your code with your project's Groovy. That Groovy reads the JDK's class files with the ASM library that it bundles, and each ASM version can only read class files up to a certain JDK.
- The server runs your project's Gradle wrapper on the same JDK.

The newest JDK that works depends on your project's Groovy version. These limits were measured for some releases:

| Your project's Groovy | Grails | Newest JDK for the server |
|---|---|---|
| 2.5.14 | 4 | 15 (class file version 59) |
| 3.0.14 | 5, 6 | 19 (class file version 63) |
| 3.0.23 | 5, 6 | 23 (class file version 67) |
| 4.0.4 | 7 | 19 (class file version 63) |
| 4.0.33 | 7 | 27 (class file version 71) |
| 5.1.3 | 8 | 27 (class file version 71) |
| 6.0.0 | none yet | 27 (class file version 71) |

The server supports Grails 4 and later, which means Groovy 2.5 and later. If a module uses an older Groovy, as Grails 3 does, the server reports `failed` and compiles nothing.

Before the server reports `ready`, it checks that your project's Groovy can read the JDK's class files, the same way its compiler does. If the JDK is too new for that Groovy, the server reports `failed` and names both versions. Without this check, you would see an error at line 0 of every file.

If Gradle fails, the message names the wrapper's Gradle version and the JDK that it ran on. If Gradle's output shows that the JDK doesn't suit the build, the message also points to this page.

### Trust and the workspace root

The server does nothing until your editor sends `initializationOptions.trusted = true`. See [Trust](how-it-works.md#trust).

The workspace root must be the Gradle root, which is the directory that contains `gradlew`. The server reads the project model from there. It never runs a Gradle wrapper from a parent directory. If you open a sub-module instead, the server fails with a message that names the folder to open.

## Editor setup

Each setup below starts `java` from your `PATH`. If `java` on your `PATH` is not your project's JDK, use the full path to your project's `java` instead. Replace `/path/to/grails-language-server-all.jar` with the path to the jar.

To check that a setup works, open a controller and hover over `render`. Every setup on this page except Zed was checked this way, on a Grails 4 controller.

### Neovim

You need Neovim 0.11 or later. Add this to your config:

```lua
vim.lsp.config("grails_ls", {
  cmd = { "java", "-jar", "/path/to/grails-language-server-all.jar" },
  filetypes = { "groovy", "gsp" },
  root_markers = { "settings.gradle", "settings.gradle.kts", "gradlew" },
  init_options = { trusted = true },
})
vim.lsp.enable("grails_ls")
```

Neovim detects `*.gsp` files as the `gsp` filetype. [grails.nvim](https://github.com/vdassios/grails.nvim) adds a tree-sitter grammar for GSP, and can also download and start the server for you.

### Helix

Add this to `~/.config/helix/languages.toml`:

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

Helix sends `config` as the initialization options.

Helix's workspace is the version-control root. Below it, Helix uses the topmost directory that contains one of the `roots` markers. That directory is the Gradle root, unless your repository contains several Gradle builds.

Helix has no GSP language, so the server only works on Groovy files there.

### Emacs

You need Emacs 29 or later, which includes eglot, and a `groovy-mode`, such as `groovy-emacs-modes` from MELPA. Add this to your init file:

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(groovy-mode . ("java" "-jar" "/path/to/grails-language-server-all.jar"
                                :initializationOptions (:trusted t)))))
;; Without this, the project root is the version-control root.
(setq project-vc-extra-root-markers '("settings.gradle" "settings.gradle.kts"))
```

Then run `M-x eglot` in a Groovy buffer. To start eglot automatically, also add:

```elisp
(add-hook 'groovy-mode-hook #'eglot-ensure)
```

### VS Code

VS Code only starts language servers from extensions. A local extension made of two files is enough:

1. Create a folder with the two files below.
2. Run `npm install` in that folder.
3. Link the folder into `~/.vscode/extensions/`. To try it without installing it, open the folder in VS Code and press F5.
4. In your settings, set `grailsLanguageServer.jar` to the path to the jar. If `java` on your `PATH` is not your project's JDK, also set `grailsLanguageServer.java`.
5. Open your Gradle root as the workspace folder. VS Code uses the workspace folder as the root.

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
        "grailsLanguageServer.java": { "type": "string", "default": "java", "description": "The java to run it with (the project's JDK)" }
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

### Zed

Zed is not supported yet. Zed only starts language servers through extensions, which are compiled to WebAssembly. The Groovy extension in Zed's registry downloads and starts its own server, and it has no `binary` setting that could point it to this one. Zed needs a small extension for this server, and none exists yet.

## Files changed outside the editor

The server notices files that change outside the editor, for example after a checkout or after running a code generator:

- If your editor can watch files for the server, the server uses that. VS Code and Neovim on macOS can.
- Otherwise, the server watches the files itself. This is the case for Neovim on Linux, where the server uses inotify, with one watch for each source directory.

If the server logs that it cannot watch a directory, raise `fs.inotify.max_user_watches`. Until you do, the server reads the source roots again every time you save.

To make the server read the source roots again at any time, send it `grails/reindex`. This does not run Gradle.

When a build file changes, the server tells you that the project model is out of date. Send `grails/reloadProject` to read the model again.

## Troubleshooting

### Server version

The server's response to `initialize` includes `serverInfo`. Its `name` is `grails-language-server`, and its `version` is the server's version. Use it to check which jar your editor started.

### Startup checks

Before it serves anything, the server checks your project itself, whatever your editor has already checked:

- The workspace is trusted.
- Every module uses Groovy 2.5 or later.
- Gradle resolved every module's Groovy jar.
- Your project's Groovy can read the class files of the JDK that the server runs on.

If a check fails, the server reports `failed` with the reason. You don't get errors on every file.

### Status messages

The server reports its progress with the `grails/status` notification (see [Protocol](how-it-works.md#protocol)). Each status has a state and a message:

| State | Message |
|---|---|
| `resolving` | `reading the Gradle build in <root>` |
| `indexing` | `<n> modules`. ` (cached)` is added when the model came from the cache, and `, <k> unresolved dependencies` is added when Gradle could not resolve some dependencies. |
| `ready` | The same as `indexing`. `, <j> Java files not built (run Gradle <tasks>)` is added when a module has Java sources with no class files yet. |
| `failed` | The reason. See the list below. |

A `failed` status gives one of these reasons:

- `workspace not trusted: ...`
- `no workspace folder`
- `Groovy <version> (module <path>) is not supported: ...`
- `Gradle could not resolve Groovy for module <path> (...)`, with Gradle's reason. For example, Groovy 6 on JDK 11 gives the Java version it needs, and a pointer to this page.
- the JDK that your project's Groovy cannot read, with both versions
- Gradle's error, with the wrapper's Gradle version and the JDK

If a reload fails after the server was `ready`, the server keeps the previous project model and goes on answering from it.
