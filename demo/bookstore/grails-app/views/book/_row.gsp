<tr>
  <td><g:link action="show" id="${book.id}">${book.title}</g:link></td>
  <td>${book.author.name}</td>
  <td><g:formatNumber number="${book.price}" type="currency"/></td>
</tr>
