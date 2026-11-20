////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Prominic.NET, Inc.
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package io.github.vdassios.grailsls.analysis;

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.AnnotatedNode;

public class GroovydocUtils {
	/**
	 * The Groovydoc on a node as markdown ({@link Javadoc#markdown}), or null. Groovydoc is attached to AST nodes from
	 * Groovy 3 on ({@code AnnotatedNode.getGroovydoc()}). This layer is compiled against Groovy 2.5 and runs on the
	 * project's Groovy, so it is read reflectively: on Groovy 2.5 the result is always null.
	 */
	public static String markdown(AnnotatedNode node) {
		String content = groovydocContent(node);
		return content == null ? null : Javadoc.markdown(content);
	}

	private static String groovydocContent(AnnotatedNode node) {
		if (node == null) {
			return null;
		}
		try {
			Method getGroovydoc = node.getClass().getMethod("getGroovydoc");
			Object groovydoc = getGroovydoc.invoke(node);
			if (groovydoc == null || !(Boolean) groovydoc.getClass().getMethod("isPresent").invoke(groovydoc)) {
				return null;
			}
			return (String) groovydoc.getClass().getMethod("getContent").invoke(groovydoc);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** Markdown for the HTML of one line of a documentation comment. */
	public static String reformatLine(String line) {
		// remove all attributes (including namespaced)
		line = line.replaceAll("<(\\w+)(?:\\s+\\w+(?::\\w+)?=(\"|\')[^\"\']*\\2)*\\s*(\\/{0,1})>", "<$1$3>");
		line = line.replaceAll("<pre>", "\n\n```\n");
		line = line.replaceAll("</pre>", "\n```\n");
		line = line.replaceAll("</?(em|i)>", "_");
		line = line.replaceAll("</?(strong|b)>", "**");
		line = line.replaceAll("</?code>", "`");
		line = line.replaceAll("<hr ?\\/>", "\n\n---\n\n");
		line = line.replaceAll("<(p|ul|ol|dl|li|dt|table|tr|div|blockquote)>", "\n\n");

		// to add a line break to markdown, there needs to be at least two
		// spaces at the end of the line
		line = line.replaceAll("<br\\s*/?>\\s*", "  \n");
		line = line.replaceAll("<\\/{0,1}\\w+\\/{0,1}>", "");
		return line;
	}
}
