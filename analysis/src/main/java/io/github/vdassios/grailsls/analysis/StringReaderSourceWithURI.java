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

import java.io.IOException;
import java.io.Reader;
import java.net.URI;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.io.StringReaderSource;

/**
 * A source of in-memory text, identified by a URI. The parser reads {@link #parsable} of the text: positions then
 * count UTF-16 units, as LSP and the text do, on every Groovy.
 */
public class StringReaderSourceWithURI extends StringReaderSource {
	private final String text;
	private URI uri;

	public StringReaderSourceWithURI(String string, URI uri, CompilerConfiguration configuration) {
		super(parsable(string), configuration);
		this.text = string;
		this.uri = uri;
	}

	public URI getURI() {
		return uri;
	}

	/**
	 * The text a source unit was compiled from, as given: with any character outside the BMP (an emoji) that
	 * {@link #parsable} replaced, which string values and Groovydoc in the AST carry. Null when it cannot be read.
	 */
	static String text(SourceUnit unit) {
		if (unit == null || unit.getSource() == null) {
			return null;
		}
		if (unit.getSource() instanceof StringReaderSourceWithURI) {
			return ((StringReaderSourceWithURI) unit.getSource()).text;
		}
		try (Reader reader = unit.getSource().getReader()) {
			StringBuilder text = new StringBuilder();
			char[] buffer = new char[8192];
			for (int read; (read = reader.read(buffer)) >= 0;) {
				text.append(buffer, 0, read);
			}
			return text.toString();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** What {@link #parsable} puts for a character outside the BMP that is not part of a name. */
	static final String REPLACEMENT = "\uFFFD\uFFFD";

	/**
	 * The text with each character outside the BMP replaced by two of the BMP: {@code __} in a name, else
	 * {@link #REPLACEMENT}. Groovy 3 and later count columns in code points, 2.5 in UTF-16 units; with no such
	 * character left the two agree, so every position the AST holds is an LSP position.
	 */
	static String parsable(String text) {
		StringBuilder out = null;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (!Character.isHighSurrogate(c) || i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
				if (out != null) {
					out.append(c);
				}
				continue;
			}
			if (out == null) {
				out = new StringBuilder(text.length()).append(text, 0, i);
			}
			out.append(Character.isJavaIdentifierPart(text.codePointAt(i)) ? "__" : REPLACEMENT);
			i++;
		}
		return out == null ? text : out.toString();
	}
}
