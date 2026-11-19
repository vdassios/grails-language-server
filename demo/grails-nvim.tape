# Records grails.nvim's demo GIF (its docs/media/grails-nvim.gif) with your own Neovim config (the GIF uses the
# author's), which must enable grails.nvim. From the repository root, on JDK 17 or later:
#   GRAILS_LS_JAR=build/libs/grails-language-server-all.jar vhs demo/grails-nvim.tape
#   cp build/demo/grails-nvim.gif <grails.nvim>/docs/media/
Output build/demo/grails-nvim.gif

Set FontFamily "MesloLGS NF"
Set FontSize 16
Set Width 1200
Set Height 640
Set Padding 16
Set TypingSpeed 40ms

Hide
Type `nvim -n --cmd "lua vim.g.grails_ls = os.getenv('GRAILS_LS_JAR') and { jar = vim.fn.fnamemodify(os.getenv('GRAILS_LS_JAR'), ':p') } or nil" demo/bookstore/grails-app/controllers/bookstore/BookController.groovy` Enter
Wait+Screen@60s /package bookstore/
Type ":lua vim.wait(180000, function() return require('grails.ls').ready(0) end, 200); vim.wait(3000, function() return false end); print('DEMO' .. '-GO')" Enter
Wait+Screen@200s /DEMO-GO/
Type ":echo ''" Enter
Type "8G^w"
Show

# From a controller action to its view: the GSP grammar, with HTML and Groovy injected.
Sleep 1.5s
Type ":GrailsAlternate" Sleep 500ms Enter
Sleep 3s

# A template name goes to its file.
Type "/row" Enter Sleep 1s
Type "gd" Sleep 3s

# Back to the page, and from the page to its action.
Ctrl+O Sleep 1.5s
Type ":GrailsAlternate" Sleep 500ms Enter
Sleep 3s
