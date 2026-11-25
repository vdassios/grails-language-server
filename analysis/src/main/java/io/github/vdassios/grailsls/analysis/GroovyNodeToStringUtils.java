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

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;

public class GroovyNodeToStringUtils {
	private static final String JAVA_OBJECT = "java.lang.Object";

	public static String classToString(ClassNode classNode, ASTNodeVisitor ast) {
		StringBuilder builder = new StringBuilder();
		String packageName = classNode.getPackageName();
		if (packageName != null && packageName.length() > 0) {
			builder.append("package ");
			builder.append(packageName);
			builder.append("\n");
		}
		if (!classNode.isSyntheticPublic()) {
			builder.append("public ");
		}
		if (classNode.isAbstract()) {
			builder.append("abstract ");
		}
		if (classNode.isInterface()) {
			builder.append("interface ");
		} else if (classNode.isEnum()) {
			builder.append("enum ");
		} else {
			builder.append("class ");
		}
		builder.append(sourceName(classNode.getNameWithoutPackage()));
		appendGenerics(builder, classNode.redirect().getGenericsTypes(), true);

		ClassNode superClass = null;
		try {
			// unlike getSuperClass(), keeps the type arguments it is extended with
			superClass = classNode.redirect().getUnresolvedSuperClass();
		} catch (NoClassDefFoundError e) {
			// this is fine, we'll just treat it as null
		}
		if (superClass != null && !superClass.getName().equals(JAVA_OBJECT)) {
			builder.append(" extends ");
			builder.append(typeToString(superClass));
		}
		return builder.toString();
	}

	public static String constructorToString(ConstructorNode constructorNode, ASTNodeVisitor ast) {
		StringBuilder builder = new StringBuilder();
		builder.append(sourceName(constructorNode.getDeclaringClass().getName()));
		builder.append("(");
		builder.append(parametersToString(constructorNode.getParameters(), ast));
		builder.append(")");
		return builder.toString();
	}

	public static String methodToString(MethodNode methodNode, ASTNodeVisitor ast) {
		if (methodNode instanceof ConstructorNode) {
			return constructorToString((ConstructorNode) methodNode, ast);
		}
		StringBuilder builder = new StringBuilder();
		if (methodNode.isPublic()) {
			if (!methodNode.isSyntheticPublic()) {
				builder.append("public ");
			}
		} else if (methodNode.isProtected()) {
			builder.append("protected ");
		} else if (methodNode.isPrivate()) {
			builder.append("private ");
		}

		if (methodNode.isStatic()) {
			builder.append("static ");
		}

		if (methodNode.isFinal()) {
			builder.append("final ");
		}
		if (methodNode.getGenericsTypes() != null && methodNode.getGenericsTypes().length > 0) {
			appendGenerics(builder, methodNode.getGenericsTypes(), true);
			builder.append(" ");
		}
		builder.append(typeToString(methodNode.getReturnType()));
		builder.append(" ");
		builder.append(methodNode.getName());
		builder.append("(");
		builder.append(parametersToString(methodNode.getParameters(), ast));
		builder.append(")");
		return builder.toString();
	}

	public static String parametersToString(Parameter[] params, ASTNodeVisitor ast) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) {
				builder.append(", ");
			}
			Parameter paramNode = params[i];
			builder.append(variableToString(paramNode, ast));
		}
		return builder.toString();
	}

	public static String variableToString(Variable variable, ASTNodeVisitor ast) {
		StringBuilder builder = new StringBuilder();
		if (variable instanceof FieldNode) {
			FieldNode fieldNode = (FieldNode) variable;
			if (fieldNode.isPublic()) {
				builder.append("public ");
			}
			if (fieldNode.isProtected()) {
				builder.append("protected ");
			}
			if (fieldNode.isPrivate()) {
				builder.append("private ");
			}

			if (fieldNode.isFinal()) {
				builder.append("final ");
			}

			if (fieldNode.isStatic()) {
				builder.append("static ");
			}
		}
		ClassNode varType = null;
		if (variable instanceof ASTNode) {
			varType = GroovyASTUtils.getTypeOfNode((ASTNode) variable, ast);
		} else {
			varType = variable.getType();
		}
		builder.append(typeToString(varType));
		builder.append(" ");
		builder.append(variable.getName());
		return builder.toString();
	}

	/**
	 * A type without its package, as source writes it, with its type arguments, such as
	 * {@code Map.Entry<String, List<? extends Number>>}.
	 */
	static String typeToString(ClassNode type) {
		if (type.isArray()) {
			return typeToString(type.getComponentType()) + "[]";
		}
		if (type.isGenericsPlaceHolder()) {
			return type.getUnresolvedName();
		}
		StringBuilder builder = new StringBuilder(sourceName(type.getNameWithoutPackage()));
		appendGenerics(builder, type.getGenericsTypes(), false);
		return builder.toString();
	}

	/**
	 * A binary class name as source writes it: the {@code $} before a nested class's name becomes a dot, so
	 * {@code Map$Entry} is {@code Map.Entry}. A class file's node does not know its outer class, so this goes by the
	 * name. A {@code $} that no letter follows stays: an anonymous or local class has no name to write
	 * ({@code Sampler$1}), nor has a closure's class ({@code Sampler$_split_closure1}).
	 */
	static String sourceName(String binary) {
		StringBuilder name = new StringBuilder(binary);
		for (int i = 1; i < name.length() - 1; i++) {
			if (name.charAt(i) == '$' && name.charAt(i - 1) != '.' && Character.isLetter(name.charAt(i + 1))) {
				name.setCharAt(i, '.');
			}
		}
		return name.toString();
	}

	/**
	 * Appends type arguments in angle brackets, or nothing when there are none. Where type parameters are declared (a
	 * class, a generic method), their bounds are shown too: {@code <T extends Number>}.
	 */
	private static void appendGenerics(StringBuilder builder, GenericsType[] generics, boolean declared) {
		if (generics == null || generics.length == 0) {
			return;
		}
		builder.append("<");
		for (int i = 0; i < generics.length; i++) {
			if (i > 0) {
				builder.append(", ");
			}
			GenericsType generic = generics[i];
			if (generic.isPlaceholder() || generic.isWildcard()) {
				builder.append(generic.isWildcard() ? "?" : generic.getName());
				if (generic.isWildcard() || declared) {
					appendBounds(builder, generic);
				}
			} else {
				builder.append(typeToString(generic.getType()));
			}
		}
		builder.append(">");
	}

	private static void appendBounds(StringBuilder builder, GenericsType generic) {
		ClassNode[] upper = generic.getUpperBounds();
		// a class file gives an unbounded type parameter the bound Object
		if (upper != null && upper.length > 0 && !(upper.length == 1 && upper[0].getName().equals(JAVA_OBJECT))) {
			builder.append(" extends ");
			for (int i = 0; i < upper.length; i++) {
				builder.append(i > 0 ? " & " : "").append(typeToString(upper[i]));
			}
		} else if (generic.getLowerBound() != null) {
			builder.append(" super ").append(typeToString(generic.getLowerBound()));
		}
	}
}