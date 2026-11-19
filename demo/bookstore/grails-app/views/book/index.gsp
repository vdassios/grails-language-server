<html>
<head>
  <meta name="layout" content="main"/>
  <title>Books</title>
</head>
<body>
  <table>
    <g:each in="${books}" var="book">
      <g:render template="row" model="[book: book]"/>
    </g:each>
  </table>
</body>
</html>
