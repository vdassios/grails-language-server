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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;

public class GroovyASTUtils {
    /**
     * Node metadata of a dynamically typed variable: the expression whose type it takes (a GSP page variable, the value
     * of its {@code g:set}).
     */
    public static final String VALUE_OF = "grailsls.valueOf";
    /** Node metadata of a dynamically typed variable: the expression whose elements it takes (a {@code g:each} variable). */
    public static final String ELEMENT_OF = "grailsls.elementOf";
    /** Variables whose {@link #VALUE_OF} or {@link #ELEMENT_OF} is being typed: {@code value="${x.next}"} names its own variable. */
    private static final ThreadLocal<Set<ASTNode>> TYPING = ThreadLocal.withInitial(HashSet::new);

    /**
     * Reads members of a class node, or {@code none}. A class from a jar or class directory loads its members on first
     * use, and Groovy throws when the type of one of them is missing from the classpath (e.g. stale build output).
     */
    public static <T> T members(Supplier<T> read, T none) {
        try {
            return read.get();
        } catch (LinkageError e) {
            return none;
        }
    }

    /**
     * The call whose argument list a node is, or null. For the call a node is an argument of, pass the node's parent:
     * {@code callWithArguments(ast.getParent(argument), ast)}.
     */
    public static MethodCall callWithArguments(ASTNode arguments, ASTNodeVisitor astVisitor) {
        ASTNode call = arguments instanceof TupleExpression ? astVisitor.getParent(arguments) : null;
        return call instanceof MethodCall && ((MethodCall) call).getArguments() == arguments ? (MethodCall) call : null;
    }

    /** The top-level class a class is nested in, or the class itself; null for null. */
    public static ClassNode topLevel(ClassNode type) {
        while (type != null && type.getOuterClass() != null) {
            type = type.getOuterClass();
        }
        return type;
    }

    public static ASTNode getEnclosingNodeOfType(ASTNode offsetNode, Class<? extends ASTNode> nodeType,
            ASTNodeVisitor astVisitor) {
        ASTNode current = offsetNode;
        while (current != null) {
            if (nodeType.isInstance(current)) {
                return current;
            }
            current = astVisitor.getParent(current);
        }
        return null;
    }

    public static ASTNode getDefinition(ASTNode node, boolean strict, ASTNodeVisitor astVisitor) {
        if (node == null) {
            return null;
        }
        ASTNode parentNode = astVisitor.getParent(node);
        if (node instanceof ExpressionStatement) {
            ExpressionStatement statement = (ExpressionStatement) node;
            node = statement.getExpression();
        }
        if (node instanceof ClassNode) {
            return tryToResolveOriginalClassNode((ClassNode) node, strict, astVisitor);
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression callExpression = (ConstructorCallExpression) node;
            MethodNode constructor = GroovyASTUtils.getMethodFromCallExpression(callExpression, astVisitor);
            if (constructor != null && constructor.getLineNumber() != -1) {
                return constructor;
            }
            // a default or generated constructor has no source: the class instead
            return tryToResolveOriginalClassNode(callExpression.getType(), strict, astVisitor);
        } else if (node instanceof DeclarationExpression) {
            DeclarationExpression declExpression = (DeclarationExpression) node;
            if (!declExpression.isMultipleAssignmentDeclaration()) {
                ClassNode originType = declExpression.getVariableExpression().getOriginType();
                return tryToResolveOriginalClassNode(originType, strict, astVisitor);
            }
        } else if (node instanceof ClassExpression) {
            ClassExpression classExpression = (ClassExpression) node;
            return tryToResolveOriginalClassNode(classExpression.getType(), strict, astVisitor);
        } else if (node instanceof ImportNode) {
            ImportNode importNode = (ImportNode) node;
            return tryToResolveOriginalClassNode(importNode.getType(), strict, astVisitor);
        } else if (node instanceof MethodNode) {
            return node;
        } else if (node instanceof StaticMethodCallExpression) {
            // a call without a receiver to a static method of the class, in dynamic code: it has no node for its name
            return GroovyASTUtils.getMethodFromCallExpression((StaticMethodCallExpression) node, astVisitor);
        } else if (node instanceof ConstantExpression && parentNode != null) {
            if (parentNode instanceof MethodCallExpression) {
                MethodCallExpression methodCallExpression = (MethodCallExpression) parentNode;
                return GroovyASTUtils.getMethodFromCallExpression(methodCallExpression, astVisitor);
            } else if (parentNode instanceof PropertyExpression) {
                PropertyExpression propertyExpression = (PropertyExpression) parentNode;
                PropertyNode propNode = GroovyASTUtils.getPropertyFromExpression(propertyExpression, astVisitor);
                if (propNode != null) {
                    return propNode;
                }
                return GroovyASTUtils.getFieldFromExpression(propertyExpression, astVisitor);
            }
        } else if (node instanceof VariableExpression) {
            VariableExpression variableExpression = (VariableExpression) node;
            Variable accessedVariable = variableExpression.getAccessedVariable();
            if (accessedVariable instanceof ASTNode) {
                return (ASTNode) accessedVariable;
            }
            return getMemberOfDynamicVariable(variableExpression, astVisitor);
        } else if (node instanceof Variable) {
            return node;
        }
        return null;
    }

    /**
     * A name the scope visitor left unbound (a DynamicVariable): a member of the enclosing class added after it ran, by
     * a transformation (Grails's {@code log}) or a trait ({@code params}, {@code out}): a field or property of that
     * name, else its getter. Null when the class has neither.
     */
    private static ASTNode getMemberOfDynamicVariable(VariableExpression variable, ASTNodeVisitor astVisitor) {
        String name = variable.getName();
        ClassNode owner = (ClassNode) getEnclosingNodeOfType(variable, ClassNode.class, astVisitor);
        if (owner == null || name.isEmpty() || variable.isThisExpression() || variable.isSuperExpression()) {
            return null;
        }
        return members(() -> {
            PropertyNode property = owner.getProperty(name);
            if (property != null) {
                return property;
            }
            FieldNode field = owner.getField(name);
            if (field != null) {
                return field;
            }
            String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            MethodNode getter = owner.getGetterMethod("get" + capitalized);
            if (getter == null) {
                getter = owner.getGetterMethod("is" + capitalized);
            }
            return getter;
        }, null);
    }

    private static ClassNode tryToResolveOriginalClassNode(ClassNode node, boolean strict, ASTNodeVisitor ast) {
        for (ClassNode originalNode : ast.getClassNodes()) {
            if (originalNode.equals(node)) {
                return originalNode;
            }
        }
        if (strict) {
            return null;
        }
        return node;
    }

    public static PropertyNode getPropertyFromExpression(PropertyExpression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfReceiver(node.getObjectExpression(), node, astVisitor);
        if (classNode != null) {
            return members(() -> classNode.getProperty(node.getProperty().getText()), null);
        }
        return null;
    }

    public static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfReceiver(node.getObjectExpression(), node, astVisitor);
        if (classNode != null) {
            return members(() -> classNode.getField(node.getProperty().getText()), null);
        }
        return null;
    }

    public static ClassNode getTypeOfReceiver(Expression receiver, ASTNode expression, ASTNodeVisitor astVisitor) {
        if (receiver instanceof VariableExpression && ((VariableExpression) receiver).isThisExpression()) {
            ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(expression, ClassNode.class, astVisitor);
            if (enclosingClass != null) {
                return enclosingClass;
            }
        }
        // a class name, Book.: the class itself; the static type checker infers Class<Book> for it
        if (receiver instanceof ClassExpression) {
            return receiver.getType();
        }
        return getTypeOfNode(receiver, astVisitor);
    }

    public static ClassNode getTypeOfNode(ASTNode node, ASTNodeVisitor astVisitor) {
        // statically compiled code: the type the static type checker inferred
        Object inferred = node.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
        if (inferred instanceof ClassNode) {
            return (ClassNode) inferred;
        }
        if (node instanceof BinaryExpression) {
            BinaryExpression binaryExpr = (BinaryExpression) node;
            Expression leftExpr = binaryExpr.getLeftExpression();
            if (binaryExpr.getOperation().getText().equals("[") && leftExpr.getType().isArray()) {
                return leftExpr.getType().getComponentType();
            }
        } else if (node instanceof ClassExpression) {
            ClassExpression expression = (ClassExpression) node;
            // This means it's an expression like this: SomeClass.someProp
            return expression.getType();
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression expression = (ConstructorCallExpression) node;
            return expression.getType();
        } else if (node instanceof MethodCallExpression || node instanceof StaticMethodCallExpression) {
            MethodNode methodNode = GroovyASTUtils.getMethodFromCallExpression((MethodCall) node, astVisitor);
            if (methodNode != null) {
                return methodNode.getReturnType();
            }
            return ((Expression) node).getType();
        } else if (node instanceof PropertyExpression) {
            PropertyExpression expression = (PropertyExpression) node;
            PropertyNode propNode = GroovyASTUtils.getPropertyFromExpression(expression, astVisitor);
            if (propNode != null) {
                return getTypeOfNode(propNode, astVisitor);
            }
            FieldNode fieldNode = GroovyASTUtils.getFieldFromExpression(expression, astVisitor);
            if (fieldNode != null) {
                return getTypeOfNode(fieldNode, astVisitor);
            }
            return expression.getType();
        } else if (node instanceof Variable) {
            Variable var = (Variable) node;
            if (var.getName().equals("this")) {
                ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(node, ClassNode.class, astVisitor);
                if (enclosingClass != null) {
                    return enclosingClass;
                }
            } else if (var.isDynamicTyped()) {
                // it in a GSP g:each without var: linked itself (PageVariables)
                ClassNode own = linkedType(node, astVisitor);
                if (own != null) {
                    return own;
                }
                ASTNode defNode = GroovyASTUtils.getDefinition(node, false, astVisitor);
                // def fooService in an artefact: the service Grails injects by name
                ClassNode injected = Injections.typeOf(defNode);
                if (injected != null) {
                    return injected;
                }
                ClassNode linked = linkedType(defNode, astVisitor);
                if (linked != null) {
                    return linked;
                }
                // a member added by a transformation: its getter, or a field or property with a declared type
                if (defNode instanceof MethodNode) {
                    return ((MethodNode) defNode).getReturnType();
                }
                if ((defNode instanceof FieldNode || defNode instanceof PropertyNode) && !((Variable) defNode).isDynamicTyped()) {
                    return ((Variable) defNode).getOriginType();
                }
                if (defNode instanceof Variable) {
                    Variable defVar = (Variable) defNode;
                    if (defVar.hasInitialExpression()) {
                        return getTypeOfNode(defVar.getInitialExpression(), astVisitor);
                    } else {
                        ASTNode declNode = astVisitor.getParent(defNode);
                        if (declNode instanceof DeclarationExpression) {
                            DeclarationExpression decl = (DeclarationExpression) declNode;
                            return getTypeOfNode(decl.getRightExpression(), astVisitor);
                        }
                    }
                }
            }
            if (var.getOriginType() != null) {
                return var.getOriginType();
            }
        }
        if (node instanceof Expression) {
            Expression expression = (Expression) node;
            return expression.getType();
        }
        return null;
    }

    /** The type a variable takes from its {@link #VALUE_OF} or {@link #ELEMENT_OF} expression; null without one. */
    private static ClassNode linkedType(ASTNode variable, ASTNodeVisitor astVisitor) {
        Object value = variable == null ? null : variable.getNodeMetaData(VALUE_OF);
        Object items = variable == null ? null : variable.getNodeMetaData(ELEMENT_OF);
        if (!(value instanceof Expression || items instanceof Expression) || !TYPING.get().add(variable)) {
            return null;
        }
        try {
            if (value instanceof Expression) {
                return getTypeOfNode((Expression) value, astVisitor);
            }
            ClassNode collection = getTypeOfNode((Expression) items, astVisitor);
            return collection == null ? null : elementType(collection);
        } finally {
            TYPING.get().remove(variable);
        }
    }

    /** The element type of an array or of an {@code Iterable} with its type argument; null when unknown. */
    static ClassNode elementType(ClassNode collection) {
        if (collection.isArray()) {
            return collection.getComponentType();
        }
        GenericsType[] generics = collection.getGenericsTypes();
        if (generics == null || generics.length != 1 || generics[0].isPlaceholder() || generics[0].isWildcard()) {
            return null;
        }
        return members(() -> collection.implementsInterface(ClassHelper.make(Iterable.class)) || collection.equals(ClassHelper.make(Iterable.class)), false) ? generics[0].getType() : null;
    }

    public static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor) {
        if (node instanceof MethodCallExpression) {
            MethodCallExpression methodCallExpr = (MethodCallExpression) node;
            ClassNode leftType = getTypeOfReceiver(methodCallExpr.getObjectExpression(), methodCallExpr, astVisitor);
            String name = methodCallExpr.getMethod().getText();
            List<MethodNode> methods = leftType == null ? Collections.emptyList() : members(() -> leftType.getMethods(name), Collections.emptyList());
            if (!methods.isEmpty()) {
                return methods;
            }
            // a GORM dynamic finder: no class declares it
            Gorm.Finder finder = Gorm.finder(methodCallExpr, astVisitor);
            if (finder != null) {
                return Collections.singletonList(finder.method());
            }
            // a call without a receiver in a closure goes to the closure's delegate: Book.withCriteria { eq(...) }
            if (methodCallExpr.isImplicitThis()) {
                for (ClassNode delegate : delegates(methodCallExpr, astVisitor)) {
                    List<MethodNode> delegated = members(() -> delegate.getMethods(name), Collections.emptyList());
                    if (!delegated.isEmpty()) {
                        return delegated;
                    }
                }
            }
        } else if (node instanceof StaticMethodCallExpression) {
            StaticMethodCallExpression call = (StaticMethodCallExpression) node;
            return members(() -> call.getOwnerType().getMethods(call.getMethod()).stream().filter(MethodNode::isStatic)
                    .collect(Collectors.toList()), Collections.emptyList());
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression constructorCallExpr = (ConstructorCallExpression) node;
            ClassNode constructorType = constructorCallExpr.getType();
            if (constructorType != null) {
                return members(() -> constructorType.getDeclaredConstructors().stream().map(constructor -> (MethodNode) constructor)
                        .collect(Collectors.toList()), Collections.emptyList());
            }
        }
        return Collections.emptyList();
    }

    /** The {@code @DelegatesTo} types of the closures around a node, innermost first. */
    public static List<ClassNode> delegates(ASTNode node, ASTNodeVisitor astVisitor) {
        List<ClassNode> delegates = new ArrayList<>();
        for (ASTNode current = astVisitor.getParent(node); current != null; current = astVisitor.getParent(current)) {
            if (current instanceof ClosureExpression) {
                ClassNode delegate = Gorm.delegateOf((ClosureExpression) current, astVisitor);
                if (delegate != null) {
                    delegates.add(delegate);
                }
            }
        }
        return delegates;
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor) {
        // statically compiled code: the method the static type checker chose (a Groovy extension method shows as
        // static on Groovy 2.5)
        Object target = ((ASTNode) node).getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
        if (target instanceof MethodNode) {
            return (MethodNode) target;
        }
        return getMethodFromCallExpression(node, astVisitor, -1);
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor, int argIndex) {
        List<MethodNode> possibleMethods = getMethodOverloadsFromCallExpression(node, astVisitor);
        // named arguments, foo(a: 1), are a TupleExpression holding one map; an ArgumentListExpression is a tuple too
        if (!possibleMethods.isEmpty() && node.getArguments() instanceof TupleExpression) {
            TupleExpression actualArguments = (TupleExpression) node.getArguments();
            MethodNode foundMethod = possibleMethods.stream().max(new Comparator<MethodNode>() {
                public int compare(MethodNode m1, MethodNode m2) {
                    Parameter[] p1 = m1.getParameters();
                    Parameter[] p2 = m2.getParameters();
                    int m1Value = calculateArgumentsScore(p1, actualArguments, argIndex);
                    int m2Value = calculateArgumentsScore(p2, actualArguments, argIndex);
                    if (m1Value > m2Value) {
                        return 1;
                    } else if (m1Value < m2Value) {
                        return -1;
                    }
                    return 0;
                }
            }).orElse(null);
            return foundMethod;
        }
        return null;
    }

    /** A map literal, named arguments included, is typed Object in the AST: count it as a Map. */
    private static ClassNode argumentType(Expression argument) {
        return argument instanceof MapExpression ? ClassHelper.MAP_TYPE : argument.getType();
    }

    private static int calculateArgumentsScore(Parameter[] parameters, TupleExpression arguments, int argIndex) {
        int score = 0;
        int paramCount = parameters.length;
        int expressionsCount = arguments.getExpressions().size();
        int argsCount = expressionsCount;
        if (argIndex >= argsCount) {
            argsCount = argIndex + 1;
        }
        int minCount = Math.min(paramCount, argsCount);
        if (paramCount == argsCount) {
            // among overloads that match as well, the one with as many parameters as there are arguments
            score++;
        }
        for (int i = 0; i < minCount; i++) {
            ClassNode argType = (i < expressionsCount) ? argumentType(arguments.getExpression(i)) : null;
            ClassNode paramType = (i < paramCount) ? parameters[i].getType() : null;
            if (argType != null && paramType != null) {
                if (argType.equals(paramType)) {
                    // equal types are preferred
                    score += 1000;
                } else if (argType.isDerivedFrom(paramType)) {
                    // subtypes are nice, but less important
                    score += 100;
                } else {
                    // if a type doesn't match at all, it's not worth much
                    score++;
                }
            } else if (paramType != null) {
                // extra parameters are like a type not matching
                score++;
            }
        }
        return score;
    }
}
