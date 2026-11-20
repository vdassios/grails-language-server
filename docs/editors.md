# Install and editor setup

## Install

Download `grails-language-server-all.jar` from the [releases](https://github.com/vdassios/grails-language-server/releases), or build it (`./gradlew shadowJar`, see [Development](development.md#build)). It speaks LSP over stdio:

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

The server itself needs Java 11 or later, Grails 7 and 8 need 17. It supports Grails 4 or later (Groovy 2.5 or later): for a module on an older Groovy (Grails 3), it reports `failed` and compiles nothing. Before it reports `ready`, the project's Groovy reads one of the JDK's class files, as its compiler does; when that Groovy cannot read them (a JDK newer than the table allows), the server reports `failed`, naming both versions, instead of an error at line 0 of every file. When Gradle fails, the message names the wrapper's Gradle version and the JDK it ran on, and points here when the output shows the JDK does not suit the build.

The server does nothing until the client sends `initializationOptions.trusted = true` (see [Trust](how-it-works.md#trust)). The workspace root must be the Gradle root, the directory with `gradlew`; the server reads the project model from there. It never runs a wrapper from a parent directory: opening a sub-module fails with a message naming the folder to open.

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
