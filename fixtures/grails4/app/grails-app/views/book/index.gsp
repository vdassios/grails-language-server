<html>
<head>
<meta name="layout" content="main"/>
<asset:stylesheet src="main.css"/>
<asset:javascript src="app"/>
<asset:link rel="stylesheet" href="main.css"/>
</head>
<body>
<g:each in="${books}" var="book">
  <g:render template="row" model="[book: book]"/>
</g:each>
<g:render template="/book/row"/>
<tmpl:row/>
<tmpl:/book/row/>
<g:include view="summary"/>
<g:link controller="book" action="show" id="1">First</g:link>
<g:form action="save"></g:form>
<g:applyLayout name="main">Inside</g:applyLayout>
<sec:link controller="book" action="index">Books</sec:link>
</body>
</html>
