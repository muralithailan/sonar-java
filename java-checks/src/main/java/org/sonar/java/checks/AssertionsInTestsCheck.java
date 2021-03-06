/*
 * SonarQube Java
 * Copyright (C) 2012-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.checks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.check.Rule;
import org.sonar.check.RuleProperty;
import org.sonar.java.matcher.MethodMatcher;
import org.sonar.java.matcher.MethodMatcherCollection;
import org.sonar.java.matcher.NameCriteria;
import org.sonar.java.matcher.TypeCriteria;
import org.sonar.java.model.ModifiersUtils;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.SymbolMetadata;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodReferenceTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Modifier;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.Tree;

import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.sonar.java.model.ExpressionUtils.methodName;

@Rule(key = "S2699")
public class AssertionsInTestsCheck extends BaseTreeVisitor implements JavaFileScanner {

  private static final Logger LOG = Loggers.get(AssertionsInTestsCheck.class);

  private static final TypeCriteria IO_RESTASSURED = TypeCriteria.is("io.restassured.response.ValidatableResponseOptions");
  private static final TypeCriteria ANY_TYPE = TypeCriteria.anyType();
  private static final NameCriteria ANY_NAME = NameCriteria.any();

  private static final Pattern ASSERTION_METHODS_PATTERN = Pattern.compile("(assert|verify|fail|should|check|expect).*");

  private static final MethodMatcherCollection ASSERTION_INVOCATION_MATCHERS = MethodMatcherCollection.create(
    // fest 1.x / 2.X
    method(TypeCriteria.subtypeOf("org.fest.assertions.GenericAssert"), ANY_NAME).withAnyParameters(),
    method(TypeCriteria.subtypeOf("org.fest.assertions.api.AbstractAssert"), ANY_NAME).withAnyParameters(),
    // rest assured 2.0
    method(IO_RESTASSURED, NameCriteria.is("body")).withAnyParameters(),
    method(IO_RESTASSURED, NameCriteria.is("time")).withAnyParameters(),
    method(IO_RESTASSURED, NameCriteria.startsWith("content")).withAnyParameters(),
    method(IO_RESTASSURED, NameCriteria.startsWith("status")).withAnyParameters(),
    method(IO_RESTASSURED, NameCriteria.startsWith("header")).withAnyParameters(),
    method(IO_RESTASSURED, NameCriteria.startsWith("cookie")).withAnyParameters(),
    method(IO_RESTASSURED, NameCriteria.startsWith("spec")).withAnyParameters(),
    // assertJ
    method(TypeCriteria.subtypeOf("org.assertj.core.api.AbstractAssert"), ANY_NAME).withAnyParameters(),
    // spring
    method("org.springframework.test.web.servlet.ResultActions", "andExpect").addParameter(ANY_TYPE),
    // JMockit
    method("mockit.Verifications", "<init>").withAnyParameters());

  @RuleProperty(
    key = "customAssertionMethods",
    description = "Comma-separated list of fully qualified method symbols that should be considered as assertion methods. " +
      "The wildcard character can be used at the end of the method name.",
    defaultValue = "")
  public String customAssertionMethods = "";
  private MethodMatcherCollection customAssertionMethodsMatcher = null;

  private final Deque<Boolean> methodContainsAssertion = new ArrayDeque<>();
  private final Deque<Boolean> inUnitTest = new ArrayDeque<>();
  private final Map<Symbol, Boolean> assertionInMethod = new HashMap<>();
  private JavaFileScannerContext context;

  @Override
  public void scanFile(final JavaFileScannerContext context) {
    this.context = context;
    assertionInMethod.clear();
    inUnitTest.push(false);
    methodContainsAssertion.push(false);
    scan(context.getTree());
    methodContainsAssertion.pop();
    inUnitTest.pop();
    assertionInMethod.clear();
  }

  @Override
  public void visitMethod(MethodTree methodTree) {
    if (ModifiersUtils.hasModifier(methodTree.modifiers(), Modifier.ABSTRACT)) {
      return;
    }
    boolean isUnitTest = isUnitTest(methodTree);
    inUnitTest.push(isUnitTest);
    methodContainsAssertion.push(false);
    super.visitMethod(methodTree);
    Boolean containsAssertion = methodContainsAssertion.pop();
    inUnitTest.pop();
    if (isUnitTest && !expectAssertion(methodTree) && !containsAssertion) {
      context.reportIssue(this, methodTree.simpleName(), "Add at least one assertion to this test case.");
    }
  }

  @Override
  public void visitMethodInvocation(MethodInvocationTree mit) {
    super.visitMethodInvocation(mit);
    if (shouldCheckForAssertion() && isAssertion(methodName(mit), mit.symbol())) {
      methodContainsAssertion.pop();
      methodContainsAssertion.push(true);
    }
  }

  @Override
  public void visitMethodReference(MethodReferenceTree methodReferenceTree) {
    super.visitMethodReference(methodReferenceTree);
    if (shouldCheckForAssertion() && isAssertion(methodReferenceTree.method(), methodReferenceTree.method().symbol())) {
      methodContainsAssertion.pop();
      methodContainsAssertion.push(true);
    }
  }

  @Override
  public void visitNewClass(NewClassTree tree) {
    super.visitNewClass(tree);
    if (shouldCheckForAssertion() && isAssertion(null, tree.constructorSymbol())) {
      methodContainsAssertion.pop();
      methodContainsAssertion.push(true);
    }
  }

  private boolean shouldCheckForAssertion() {
    return !methodContainsAssertion.peek() && inUnitTest.peek();
  }

  private boolean isAssertion(@Nullable IdentifierTree method, Symbol methodSymbol) {
    boolean matchesMethodPattern = method != null && ASSERTION_METHODS_PATTERN.matcher(method.name()).matches();
    return matchesMethodPattern
      || ASSERTION_INVOCATION_MATCHERS.anyMatch(methodSymbol)
      || getCustomAssertionMethodsMatcher().anyMatch(methodSymbol)
      || isLocalMethodWithAssertion(methodSymbol);
  }

  private boolean isLocalMethodWithAssertion(Symbol symbol) {
    return assertionInMethod.computeIfAbsent(symbol, key -> {
      Tree declaration = key.declaration();
      if (declaration == null) {
        return false;
      } else {
        AssertionVisitor assertionVisitor = new AssertionVisitor(getCustomAssertionMethodsMatcher());
        declaration.accept(assertionVisitor);
        return assertionVisitor.hasAssertion;
      }
    });
  }

  private MethodMatcherCollection getCustomAssertionMethodsMatcher() {
    if (customAssertionMethodsMatcher == null) {
      String[] fullyQualifiedMethodSymbols = customAssertionMethods.split(",");
      List<MethodMatcher> customMethodMatchers = new ArrayList<>(fullyQualifiedMethodSymbols.length);
      for (String fullyQualifiedMethodSymbol : fullyQualifiedMethodSymbols) {
        String[] methodMatcherParts = fullyQualifiedMethodSymbol.split("#");
        if (methodMatcherParts.length == 2 && !isEmpty(methodMatcherParts[0].trim()) && !isEmpty(methodMatcherParts[1].trim())) {
          String methodName = methodMatcherParts[1].trim();
          NameCriteria nameCriteria;
          if (methodName.endsWith("*")) {
            nameCriteria = NameCriteria.startsWith(methodName.substring(0, methodName.length() - 1));
          } else {
            nameCriteria = NameCriteria.is(methodName);
          }
          customMethodMatchers.add(method(methodMatcherParts[0].trim(), nameCriteria).withAnyParameters());
        } else {
          LOG.warn("Unable to create a corresponding matcher for custom assertion method, please check the format of the following symbol: '{}'", fullyQualifiedMethodSymbol);
        }
      }

      customAssertionMethodsMatcher = MethodMatcherCollection.create(customMethodMatchers.toArray(new MethodMatcher[0]));
    }

    return customAssertionMethodsMatcher;
  }

  private static boolean expectAssertion(MethodTree methodTree) {
    List<SymbolMetadata.AnnotationValue> annotationValues = methodTree.symbol().metadata().valuesForAnnotation("org.junit.Test");
    if (annotationValues != null) {
      for (SymbolMetadata.AnnotationValue annotationValue : annotationValues) {
        if ("expected".equals(annotationValue.name())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isUnitTest(MethodTree methodTree) {
    Symbol.MethodSymbol symbol = methodTree.symbol();
    while (symbol != null) {
      if (symbol.metadata().isAnnotatedWith("org.junit.Test")) {
        return true;
      }
      symbol = symbol.overriddenSymbol();
    }
    Symbol.TypeSymbol enclosingClass = methodTree.symbol().enclosingClass();
    return enclosingClass != null && enclosingClass.type().isSubtypeOf("junit.framework.TestCase") && methodTree.simpleName().name().startsWith("test");
  }

  private static MethodMatcher method(String typeDefinition, String methodName) {
    return method(TypeCriteria.is(typeDefinition), NameCriteria.is(methodName));
  }

  private static MethodMatcher method(String typeDefinition, NameCriteria nameCriteria) {
    return MethodMatcher.create().typeDefinition(TypeCriteria.is(typeDefinition)).name(nameCriteria);
  }

  private static MethodMatcher method(TypeCriteria typeDefinitionCriteria, NameCriteria nameCriteria) {
    return MethodMatcher.create().typeDefinition(typeDefinitionCriteria).name(nameCriteria);
  }

  private static class AssertionVisitor extends BaseTreeVisitor {
    boolean hasAssertion = false;
    private MethodMatcherCollection customAssertionMethodsMatcher;

    public AssertionVisitor(MethodMatcherCollection customAssertionMethodsMatcher) {
      this.customAssertionMethodsMatcher = customAssertionMethodsMatcher;
    }

    @Override
    public void visitMethodInvocation(MethodInvocationTree mit) {
      super.visitMethodInvocation(mit);
      if (!hasAssertion && isLocalAssertion(methodName(mit), mit.symbol())) {
        hasAssertion = true;
      }
    }

    @Override
    public void visitMethodReference(MethodReferenceTree methodReferenceTree) {
      super.visitMethodReference(methodReferenceTree);
      if (!hasAssertion && isLocalAssertion(methodReferenceTree.method(), methodReferenceTree.method().symbol())) {
        hasAssertion = true;
      }
    }

    @Override
    public void visitNewClass(NewClassTree tree) {
      super.visitNewClass(tree);
      if (!hasAssertion && isLocalAssertion(null, tree.constructorSymbol())) {
        hasAssertion = true;
      }
    }

    private boolean isLocalAssertion(@Nullable IdentifierTree method, Symbol methodSymbol) {
      boolean matchesMethodPattern = method != null && ASSERTION_METHODS_PATTERN.matcher(method.name()).matches();
      return matchesMethodPattern || ASSERTION_INVOCATION_MATCHERS.anyMatch(methodSymbol) || customAssertionMethodsMatcher.anyMatch(methodSymbol);
    }
  }

}
