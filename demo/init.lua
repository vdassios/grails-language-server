-- A minimal Neovim config for trying the server on demo/bookstore: grails.nvim and Neovim's built-in LSP completion,
-- nothing else. See demo/README.md.
local plugin = os.getenv 'GRAILS_NVIM' or vim.fn.stdpath 'data' .. '/lazy/grails.nvim'
vim.opt.runtimepath:prepend(plugin)
vim.opt.runtimepath:append(plugin .. '/after')
if os.getenv 'GRAILS_LS_JAR' then
  vim.g.grails_ls = { jar = os.getenv 'GRAILS_LS_JAR' }
end

vim.o.termguicolors = true
vim.o.number = true
vim.o.signcolumn = 'yes'
vim.o.swapfile = false
vim.o.shortmess = vim.o.shortmess .. 'I'
vim.o.winborder = 'rounded'
vim.o.completeopt = 'menuone,noinsert,popup'
vim.o.pumheight = 8
vim.diagnostic.config { virtual_text = true }

-- The server's state (resolving, indexing, ready) in the status line.
function _G.grails_status()
  local ok, ls = pcall(require, 'grails.ls')
  if not ok or #vim.lsp.get_clients { bufnr = 0, name = 'grails_ls' } == 0 then
    return ''
  end
  return ls.ready(0) and 'grails_ls ready' or 'grails_ls starting'
end
vim.o.statusline = " %{fnamemodify(bufname(), ':.')} %m%=%{v:lua.grails_status()} "

vim.api.nvim_create_autocmd('FileType', {
  pattern = { 'groovy', 'gsp' },
  callback = function()
    pcall(vim.treesitter.start)
  end,
})

vim.api.nvim_create_autocmd('LspAttach', {
  callback = function(args)
    local client = assert(vim.lsp.get_client_by_id(args.data.client_id))
    if client:supports_method 'textDocument/completion' then
      vim.lsp.completion.enable(true, client.id, args.buf, { autotrigger = true })
    end
    vim.keymap.set('n', 'gd', vim.lsp.buf.definition, { buffer = args.buf })
  end,
})

vim.lsp.enable { 'grails', 'grails_ls' }
