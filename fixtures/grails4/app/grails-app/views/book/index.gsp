<html>
<head><meta name="layout" content="main"/></head>
<body>
<g:each in="${books}" var="book">
  <g:render template="row" model="[book: book]"/>
</g:each>
</body>
</html>
