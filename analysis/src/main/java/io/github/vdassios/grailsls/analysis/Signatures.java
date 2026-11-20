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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.TupleExpression;

import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Signature help over a visited compile. Ported from groovy-language-server's {@code SignatureHelpProvider}, returning
 * {@code api} types. Change: the call is found from any node inside its arguments, not only from the argument list, and
 * named arguments ({@code render(view: 'x')}, one map) count as the first argument.
 */
public final class Signatures {
  private Signatures() {}

  /** The overloads of the call whose arguments hold the position, or null when there is none or no node there. */
  public static SignatureHelp at(ASTNodeVisitor ast, URI document, SourceLookup lookup, int line, int column) {
    ASTNode offsetNode = ast.getNodeAtLineAndColumn(document, line, column);
    TupleExpression arguments = null;
    MethodCall call = null;
    // the innermost argument list holding the position; a closure in between starts code of its own
    for (ASTNode current = offsetNode; current != null && !(current instanceof ClosureExpression); current = ast.getParent(current)) {
      ASTNode parent = ast.getParent(current);
      if (current instanceof TupleExpression && parent instanceof MethodCall && ((MethodCall) parent).getArguments() == current) {
        arguments = (TupleExpression) current;
        call = (MethodCall) parent;
        break;
      }
    }
    if (call == null) {
      return null;
    }
    List<MethodNode> methods = GroovyASTUtils.getMethodOverloadsFromCallExpression(call, ast);
    if (methods.isEmpty()) {
      return null;
    }
    List<SignatureHelp.Signature> signatures = new ArrayList<>();
    for (MethodNode method : methods) {
      List<String> parameters = new ArrayList<>();
      for (Parameter parameter : method.getParameters()) {
        parameters.add(GroovyNodeToStringUtils.variableToString(parameter, ast));
      }
      signatures.add(new SignatureHelp.Signature(GroovyNodeToStringUtils.methodToString(method, ast), parameters, Navigation.documentation(method, lookup)));
    }
    int activeParameter = arguments instanceof ArgumentListExpression ? activeParameter(arguments.getExpressions(), line, column) : 0;
    MethodNode best = GroovyASTUtils.getMethodFromCallExpression(call, ast, activeParameter);
    return new SignatureHelp(signatures, methods.indexOf(best), activeParameter);
  }

  /** The argument that ends at or after the position; past the last one, the next. */
  private static int activeParameter(List<Expression> expressions, int line, int column) {
    for (int i = 0; i < expressions.size(); i++) {
      Range range = NodeRanges.of(expressions.get(i));
      if (range == null) {
        continue;
      }
      if (line < range.endLine || (line == range.endLine && column <= range.endColumn)) {
        return i;
      }
    }
    return expressions.size();
  }
}
