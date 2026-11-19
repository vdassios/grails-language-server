<html>
<head>
  <meta name="layout" content="main"/>
  <title>${book.title}</title>
</head>
<body>
  <h1>${book.title}</h1>
  <p>by ${book.author.name}</p>
  <% if (book.price > 20) { %>
    <p class="deal">Free shipping</p>
  <% } %>
  <g:link action="index" params="[author: book.author.name]">More by this author</g:link>
</body>
</html>
