<html>
<head><meta name="layout" content="main"/></head>
<body>
<g:each in="${items}" var="item">
  <g:render template="row" model="[item: item]"/>
</g:each>
</body>
</html>
