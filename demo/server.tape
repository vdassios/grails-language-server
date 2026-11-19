# Records docs/media/server.gif with your own Neovim config (the GIF uses the author's: catppuccin-macchiato, lualine,
# blink.cmp, nvim-autopairs), which must enable grails.nvim's grails_ls. From the repository root, on JDK 17 or later:
#   GRAILS_LS_JAR=build/libs/grails-language-server-all.jar vhs demo/server.tape
# Without GRAILS_LS_JAR, grails.nvim starts the jar :GrailsInstallServer downloaded.
Output docs/media/server.gif

Set FontFamily "MesloLGS NF"
Set FontSize 16
Set Width 1200
Set Height 640
Set Padding 16
Set TypingSpeed 70ms

Hide
Type `nvim -n --cmd "lua vim.g.grails_ls = os.getenv('GRAILS_LS_JAR') and { jar = vim.fn.fnamemodify(os.getenv('GRAILS_LS_JAR'), ':p') } or nil" demo/bookstore/grails-app/services/bookstore/BookService.groovy` Enter
Wait+Screen@60s /package bookstore/
Type ":lua vim.wait(180000, function() return require('grails.ls').ready(0) end, 200); vim.wait(6000, function() return false end); print('DEMO' .. '-GO')" Enter
Wait+Screen@200s /DEMO-GO/
Type ":setlocal expandtab shiftwidth=4 softtabstop=4 | echo ''" Enter
Type "12G"
Show

# A dynamic finder: completion offers each next step of the name as it is typed; hover reads it in words.
Sleep 1s
Type "o" Sleep 300ms
Type "Book." Sleep 1.2s
Type "findBy" Sleep 1.5s
Type "Title" Sleep 1.5s
Type "Like" Sleep 1.2s
Type "And" Sleep 1.2s
Type "Price" Sleep 1.2s
Type "LessThan" Sleep 1s
Type "('%Groovy%', 30)" Sleep 1s
Escape
Sleep 500ms
Type "^fd" Sleep 300ms
Type "K" Sleep 3.5s
Escape

# A finder GORM would reject.
Type "o" Sleep 300ms
Type "Book.findByTitel('Groovy')" Sleep 300ms
Escape
Sleep 4s

# A view name goes to the page.
Type@15ms ":e! demo/bookstore/grails-app/controllers/bookstore/BookController.groovy" Enter
Sleep 1.5s
Type "/'show'" Enter Sleep 1s
Type "gd" Sleep 2.5s

# Model variables are typed from the action that renders the page.
Hide
Type ":setlocal expandtab indentexpr= noautoindent nosmartindent | echo ''" Enter
Show
Type "8G" Sleep 300ms
Type "o" Sleep 300ms
Type "  <p>${book." Sleep 2.5s
Type "pri" Sleep 800ms
Ctrl+Y Sleep 300ms
Type "}</p>" Sleep 1.5s
Escape
Sleep 3s
