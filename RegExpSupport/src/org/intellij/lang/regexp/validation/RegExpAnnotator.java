/*
 * Copyright 2006 Sascha Weinreuter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.intellij.lang.regexp.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.*;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.lang.regexp.RegExpLanguageHost;
import org.intellij.lang.regexp.RegExpLanguageHosts;
import org.intellij.lang.regexp.RegExpTT;
import org.intellij.lang.regexp.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RegExpAnnotator extends RegExpElementVisitor implements Annotator {
  private static final Set<String> POSIX_CHARACTER_CLASSES = ContainerUtil.newHashSet(
    "alnum", "alpha", "ascii", "blank", "cntrl", "digit", "graph", "lower", "print", "punct", "space", "upper", "word", "xdigit");
  private AnnotationHolder myHolder;
  private final RegExpLanguageHosts myLanguageHosts;
  private final Key<Map<String, RegExpGroup>> NAMED_GROUP_MAP = new Key<>("REG_EXP_NAMED_GROUP_MAP");

  public RegExpAnnotator() {
    myLanguageHosts = RegExpLanguageHosts.getInstance();
  }

  @Override
  public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder holder) {
    assert myHolder == null : "unsupported concurrent annotator invocation";
    try {
      myHolder = holder;
      psiElement.accept(this);
    }
    finally {
      myHolder = null;
    }
  }

  @Override
  public void visitRegExpSetOptions(RegExpSetOptions options) {
    checkValidFlag(options.getOnOptions(), false);
    checkValidFlag(options.getOffOptions(), true);
  }

  private void checkValidFlag(@Nullable RegExpOptions options, boolean skipMinus) {
    if (options == null) {
      return;
    }
    final String text = options.getText();
    final int start = skipMinus ? 1 : 0; // skip '-' if necessary
    for (int i = start, length = text.length(); i < length; i++) {
      final int c = text.codePointAt(i);
      if (!Character.isBmpCodePoint(c) || !myLanguageHosts.supportsInlineOptionFlag((char)c, options)) {
        final int offset = options.getTextOffset() + i;
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unknown inline option flag").range(new TextRange(offset, offset + 1)).create();
      }
    }
  }

  @Override
  public void visitRegExpCharRange(RegExpCharRange range) {
    final RegExpChar from = range.getFrom();
    final RegExpChar to = range.getTo();
    if (to == null) {
      return;
    }
    int fromCodePoint = from.getValue();
    int toCodePoint = to.getValue();
    if (fromCodePoint == -1 || toCodePoint == -1) {
      return;
    }
    int errorStart = range.getTextOffset();
    int errorEnd = errorStart + range.getTextLength();
    // \ud800\udc00-\udbff\udfff
    if (!Character.isSupplementaryCodePoint(fromCodePoint) && Character.isLowSurrogate((char)fromCodePoint)) {
      final PsiElement prevSibling = range.getPrevSibling();
      if (prevSibling instanceof RegExpChar) {
        final int prevSiblingValue = ((RegExpChar)prevSibling).getValue();
        if (!Character.isSupplementaryCodePoint(prevSiblingValue) && Character.isHighSurrogate((char)prevSiblingValue)) {
          fromCodePoint = Character.toCodePoint((char)prevSiblingValue, (char)fromCodePoint);
          errorStart -= prevSibling.getTextLength();
        }
      }
    }
    if (!Character.isSupplementaryCodePoint(toCodePoint) && Character.isHighSurrogate((char)toCodePoint)) {
      final PsiElement nextSibling = range.getNextSibling();
      if (nextSibling instanceof RegExpChar) {
        final int nextSiblingValue = ((RegExpChar)nextSibling).getValue();
        if (!Character.isSupplementaryCodePoint(nextSiblingValue) && Character.isLowSurrogate((char)nextSiblingValue)) {
          toCodePoint = Character.toCodePoint((char)toCodePoint, (char)nextSiblingValue);
          errorEnd += nextSibling.getTextLength();
        }
      }
    }
    if (toCodePoint < fromCodePoint) {
      myHolder.createErrorAnnotation(new TextRange(errorStart, errorEnd), "Illegal character range (to < from)");
    }
    else if (toCodePoint == fromCodePoint) {
      myHolder.createWarningAnnotation(new TextRange(errorStart, errorEnd), "Redundant character range");
    }
  }

  @Override
  public void visitRegExpBoundary(RegExpBoundary boundary) {
    if (!myLanguageHosts.supportsBoundary(boundary)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "This boundary is not supported in this regex dialect").create();
    }
  }

  @Override
  public void visitSimpleClass(RegExpSimpleClass simpleClass) {
    if (!myLanguageHosts.supportsSimpleClass(simpleClass)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal/unsupported escape sequence").create();
    }
  }

  @Override
  public void visitRegExpClass(RegExpClass regExpClass) {
    if (!(regExpClass.getParent() instanceof RegExpClass)) {
      checkForDuplicates(regExpClass, new HashSet<>());
    }
  }

  private void checkForDuplicates(RegExpClassElement element, Set<Object> seen) {
    if (element instanceof RegExpChar) {
      final RegExpChar regExpChar = (RegExpChar)element;
      final int value = regExpChar.getValue();
      if (value != -1 && !seen.add(value)) {
        myHolder.newAnnotation(HighlightSeverity.WARNING, "Duplicate character '" + regExpChar.getText() + "' inside character class").range(regExpChar).create();
      }
    }
    else if (element instanceof RegExpSimpleClass) {
      final RegExpSimpleClass regExpSimpleClass = (RegExpSimpleClass)element;
      final RegExpSimpleClass.Kind kind = regExpSimpleClass.getKind();
      if (!seen.add(kind)) {
        myHolder.newAnnotation(HighlightSeverity.WARNING, "Duplicate predefined character class '" + regExpSimpleClass.getText() +
                                                          "' inside character class").range(regExpSimpleClass).create();
      }
    }
    else if (element instanceof RegExpClass) {
      final RegExpClass regExpClass = (RegExpClass)element;
      for (RegExpClassElement classElement : regExpClass.getElements()) {
        checkForDuplicates(classElement, seen);
      }
    }
  }

  @Override
  public void visitRegExpChar(final RegExpChar ch) {
    final PsiElement child = ch.getFirstChild();
    final IElementType type = child.getNode().getElementType();
    if (type == StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal/unsupported escape sequence").create();
      return;
    }
    else if (type == RegExpTT.BAD_HEX_VALUE) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal hexadecimal escape sequence").create();
      return;
    }
    else if (type == RegExpTT.BAD_OCT_VALUE) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal octal escape sequence").create();
      return;
    }
    else if (type == StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal unicode escape sequence").create();
      return;
    }
    final String text = ch.getUnescapedText();
    if (type == RegExpTT.ESC_CTRL_CHARACTER && text.equals("\\b") && !myLanguageHosts.supportsLiteralBackspace(ch)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal/unsupported escape sequence").create();
    }
    final RegExpChar.Type charType = ch.getType();
    if (charType == RegExpChar.Type.HEX || charType == RegExpChar.Type.UNICODE) {
      if (ch.getValue() == -1) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal unicode escape sequence").create();
        return;
      }
      if (text.charAt(text.length() - 1) == '}') {
        if (!myLanguageHosts.supportsExtendedHexCharacter(ch)) {
          myHolder.newAnnotation(HighlightSeverity.ERROR, "This hex character syntax is not supported in this regex dialect").create();
        }
      }
    }
  }

  @Override
  public void visitRegExpProperty(RegExpProperty property) {
    final ASTNode category = property.getCategoryNode();
    if (category == null) {
      return;
    }
    if (!myLanguageHosts.supportsPropertySyntax(property)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Property escape sequences are not supported in this regex dialect").create();
      return;
    }
    final String propertyName = category.getText();
    final ASTNode next = category.getTreeNext();
    if (next == null || next.getElementType() != RegExpTT.EQ) {
      if(!myLanguageHosts.isValidCategory(category.getPsi(), propertyName)) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unknown character category").range(category)
        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
      }
    }
    else {
      if(!myLanguageHosts.isValidPropertyName(category.getPsi(), propertyName)) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unknown property name").range(category)
        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
        return;
      }
      final ASTNode valueNode = property.getValueNode();
      if (valueNode != null && !myLanguageHosts.isValidPropertyValue(category.getPsi(), propertyName, valueNode.getText())) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unknown property value").range(valueNode)
        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
      }
    }
  }

  @Override
  public void visitRegExpNamedCharacter(RegExpNamedCharacter namedCharacter) {
    if (!myLanguageHosts.supportsNamedCharacters(namedCharacter)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Named Unicode characters are not allowed in this regex dialect").create();
    }
    else if (!myLanguageHosts.isValidNamedCharacter(namedCharacter)) {
      final ASTNode node = namedCharacter.getNameNode();
      if (node != null) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unknown character name").range(node)
        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
      }
    }
  }

  @Override
  public void visitRegExpBackref(final RegExpBackref backref) {
    final RegExpGroup group = backref.resolve();
    if (group == null) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Unresolved back reference")
      .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
    }
    else if (PsiTreeUtil.isAncestor(group, backref, true)) {
      myHolder.newAnnotation(HighlightSeverity.WARNING, "Back reference is nested into the capturing group it refers to").create();
    }
  }

  @Override
  public void visitRegExpGroup(RegExpGroup group) {
    final RegExpPattern pattern = group.getPattern();
    if (pattern != null) {
      final RegExpBranch[] branches = pattern.getBranches();
      if (isEmpty(branches)) {
        // catches "()" as well as "(|)"
        myHolder.newAnnotation(HighlightSeverity.WARNING, "Empty group").create();
      }
      else if (branches.length == 1) {
        final RegExpAtom[] atoms = branches[0].getAtoms();
        if (atoms.length == 1 && atoms[0] instanceof RegExpGroup) {
          final RegExpGroup.Type type = group.getType();
          if (type == RegExpGroup.Type.CAPTURING_GROUP || type == RegExpGroup.Type.ATOMIC || type == RegExpGroup.Type.NON_CAPTURING) {
            final RegExpGroup innerGroup = (RegExpGroup)atoms[0];
            if (group.isCapturing() == innerGroup.isCapturing()) {
              myHolder.newAnnotation(HighlightSeverity.WARNING, "Redundant group nesting").create();
            }
          }
        }
      }
    }
    if (group.isAnyNamedGroup()) {
      if (!myLanguageHosts.supportsNamedGroupSyntax(group)) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "This named group syntax is not supported in this regex dialect").create();
      }
    }
    if (group.getType() == RegExpGroup.Type.ATOMIC && !myLanguageHosts.supportsPossessiveQuantifiers(group)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Atomic groups are not supported in this regex dialect").create();
    }
    final String name = group.getName();
    if (name != null && !myLanguageHosts.isValidGroupName(name, group)) {
      final ASTNode node = group.getNode().findChildByType(RegExpTT.NAME);
      if (node != null) myHolder.newAnnotation(HighlightSeverity.ERROR, "Invalid group name").range(node).create();
    }
    final AnnotationSession session = myHolder.getCurrentAnnotationSession();
    final Map<String, RegExpGroup> namedGroups = NAMED_GROUP_MAP.get(session, new HashMap<>());
    if (namedGroups.isEmpty()) session.putUserData(NAMED_GROUP_MAP, namedGroups);
    if (namedGroups.put(name, group) != null) {
      final ASTNode node = group.getNode().findChildByType(RegExpTT.NAME);
      if (node != null) myHolder.newAnnotation(HighlightSeverity.ERROR, "Group with name '" + name + "' already defined").range(node).create();
    }
    final RegExpGroup.Type groupType = group.getType();
    if (groupType == RegExpGroup.Type.POSITIVE_LOOKBEHIND || groupType == RegExpGroup.Type.NEGATIVE_LOOKBEHIND) {
      final RegExpLanguageHost.Lookbehind support = myLanguageHosts.supportsLookbehind(group);
      if (support == RegExpLanguageHost.Lookbehind.NOT_SUPPORTED) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Look-behind groups are not supported in this regex dialect").create();
      }
      else {
        group.accept(new LookbehindVisitor(support, myHolder));
      }
    }
  }

  @Override
  public void visitRegExpNamedGroupRef(RegExpNamedGroupRef groupRef) {
    if (!myLanguageHosts.supportsNamedGroupRefSyntax(groupRef)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "This named group reference syntax is not supported in this regex dialect").create();
      return;
    }
    if (groupRef.getGroupName() == null) {
      return;
    }
    final RegExpGroup group = groupRef.resolve();
    if (group == null) {
      final ASTNode node = groupRef.getNode().findChildByType(RegExpTT.NAME);
      if (node != null) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unresolved named group reference").range(node)
        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
      }
    }
    else if (PsiTreeUtil.isAncestor(group, groupRef, true)) {
      myHolder.newAnnotation(HighlightSeverity.WARNING, "Group reference is nested into the named group it refers to").create();
    }
  }

  @Override
  public void visitComment(@NotNull PsiComment comment) {
    if (comment.getText().startsWith("(?#")) {
      if (!myLanguageHosts.supportsPerl5EmbeddedComments(comment)) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Embedded comments are not supported in this regex dialect").create();
      }
    }
  }

  @Override
  public void visitRegExpPyCondRef(RegExpPyCondRef condRef) {
    if (!myLanguageHosts.supportsPythonConditionalRefs(condRef)) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Conditional references are not supported in this regex dialect").create();
    }
  }

  private static boolean isEmpty(RegExpBranch[] branches) {
    for (RegExpBranch branch : branches) {
      if (branch.getAtoms().length > 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void visitRegExpClosure(RegExpClosure closure) {
    if (closure.getAtom() instanceof RegExpSetOptions) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Dangling metacharacter").range(closure.getQuantifier()).create();
    }
  }

  @Override
  public void visitRegExpQuantifier(RegExpQuantifier quantifier) {
    if (quantifier.isCounted()) {
      final RegExpNumber minElement = quantifier.getMin();
      final String min = minElement == null ? "" : minElement.getText();
      final RegExpNumber maxElement = quantifier.getMax();
      final String max = maxElement == null ? "" : maxElement.getText();
      if (!max.isEmpty() && max.equals(min)) {
        if ("1".equals(max)) {
          myHolder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Single repetition")
          .withFix(new SimplifyQuantifierAction(quantifier, null)).create();
        }
        else {
          final ASTNode node = quantifier.getNode();
          if (node.findChildByType(RegExpTT.COMMA) != null) {
            myHolder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Fixed repetition range")
            .withFix(new SimplifyQuantifierAction(quantifier, "{" + max + "}")).create();
          }
        }
      }
      else if (("0".equals(min) || min.isEmpty()) && "1".equals(max)) {
        myHolder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Repetition range replaceable by '?'")
        .withFix(new SimplifyQuantifierAction(quantifier, "?")).create();
      }
      else if (("0".equals(min) || min.isEmpty()) && max.isEmpty()) {
        myHolder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Repetition range replaceable by '*'")
        .withFix(new SimplifyQuantifierAction(quantifier, "*")).create();
      }
      else if ("1".equals(min) && max.isEmpty()) {
        myHolder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Repetition range replaceable by '+'")
        .withFix(new SimplifyQuantifierAction(quantifier, "+")).create();
      }
      Number minValue = null;
      if (minElement != null) {
        minValue = myLanguageHosts.getQuantifierValue(minElement);
        if (minValue == null) myHolder.newAnnotation(HighlightSeverity.ERROR, "Repetition value too large").range(minElement).create();
      }
      Number maxValue = null;
      if (maxElement != null) {
        maxValue= myLanguageHosts.getQuantifierValue(maxElement);
        if (maxValue == null) myHolder.newAnnotation(HighlightSeverity.ERROR, "Repetition value too large").range(maxElement).create();
      }
      if (minValue != null && maxValue != null) {
        if (minValue.longValue() > maxValue.longValue() || minValue.doubleValue() > maxValue.doubleValue()) {
          final TextRange range = new TextRange(minElement.getTextOffset(), maxElement.getTextOffset() + maxElement.getTextLength());
          myHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal repetition range (min > max)").range(range).create();
        }
      }
    }
    if (quantifier.isPossessive() && !myLanguageHosts.supportsPossessiveQuantifiers(quantifier)) {
      final ASTNode modifier = quantifier.getModifier();
      assert modifier != null;
      myHolder.newAnnotation(HighlightSeverity.ERROR, "Nested quantifier in regexp").range(modifier).create();
    }
  }

  @Override
  public void visitPosixBracketExpression(RegExpPosixBracketExpression posixBracketExpression) {
    final String className = posixBracketExpression.getClassName();
    if (!POSIX_CHARACTER_CLASSES.contains(className) && !"<".equals(className) && !">".equals(className)) {
      final ASTNode node = posixBracketExpression.getNode().findChildByType(RegExpTT.NAME);
      if (node != null) {
        myHolder.newAnnotation(HighlightSeverity.ERROR, "Unknown POSIX character class").range(node)
        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL).create();
      }
    }
  }

  private static void registerFix(Annotation a, IntentionAction action) {
    if (a != null) {
      // IDEA-9381
      a.registerFix(action);
    }
  }


  private static class LookbehindVisitor extends RegExpRecursiveElementVisitor {

    private final RegExpLanguageHost.Lookbehind mySupport;
    private final AnnotationHolder myHolder;
    private int myLength = 0;
    private boolean myStop = false;

    LookbehindVisitor(RegExpLanguageHost.Lookbehind support, AnnotationHolder holder) {
      mySupport = support;
      myHolder = holder;
    }

    @Override
    public void visitRegExpElement(RegExpElement element) {
      if (myStop) {
        return;
      }
      super.visitRegExpElement(element);
    }

    @Override
    public void visitRegExpChar(RegExpChar ch) {
      super.visitRegExpChar(ch);
      myLength++;
    }

    @Override
    public void visitSimpleClass(RegExpSimpleClass simpleClass) {
      super.visitSimpleClass(simpleClass);
      myLength++;
    }

    @Override
    public void visitRegExpClass(RegExpClass regExpClass) {
      super.visitRegExpClass(regExpClass);
      myLength++;
    }

    @Override
    public void visitRegExpProperty(RegExpProperty property) {
      super.visitRegExpProperty(property);
      myLength++;
    }

    @Override
    public void visitRegExpBackref(RegExpBackref backref) {
      super.visitRegExpBackref(backref);
      if (mySupport != RegExpLanguageHost.Lookbehind.FULL) {
        stopAndReportError(backref, "Group reference not allowed inside lookbehind");
      }
    }

    @Override
    public void visitRegExpPattern(RegExpPattern pattern) {
      if (mySupport != RegExpLanguageHost.Lookbehind.FIXED_LENGTH_ALTERNATION) {
        super.visitRegExpPattern(pattern);
        return;
      }
      final int length = myLength;
      int branchLength = -1;
      final RegExpBranch[] branches = pattern.getBranches();
      for (RegExpBranch branch : branches) {
        myLength = 0;
        super.visitRegExpBranch(branch);
        if (branchLength == -1) {
          branchLength = myLength;
        } else if (branchLength != myLength) {
          stopAndReportError(pattern, "Alternation alternatives needs to have the same length inside lookbehind");
          return;
        }
      }
      myLength = length;
    }

    @Override
    public void visitRegExpQuantifier(RegExpQuantifier quantifier) {
      super.visitRegExpQuantifier(quantifier);
      if (mySupport == RegExpLanguageHost.Lookbehind.FULL) {
        return;
      }
      if (quantifier.isCounted()) {
        final RegExpNumber minElement = quantifier.getMin();
        final RegExpNumber maxElement = quantifier.getMax();
        if (minElement != null && maxElement != null) {
          try {
            final long min = Long.parseLong(minElement.getText());
            final long max = Long.parseLong(maxElement.getText());
            if (min == max) {
              myLength += min;
              return;
            }
          } catch (NumberFormatException ignore) {}
        }
        if (mySupport != RegExpLanguageHost.Lookbehind.FINITE_REPETITION) {
          stopAndReportError(quantifier, "Unequal min and max in counted quantifier not allowed inside lookbehind");
        }
      }
      else {
        final ASTNode token = quantifier.getToken();
        assert token != null;
        final String tokenText = token.getText();
        if ("?".equals(tokenText) && mySupport == RegExpLanguageHost.Lookbehind.FINITE_REPETITION) {
          return;
        }
        stopAndReportError(quantifier, tokenText + " repetition not allowed inside lookbehind");
      }
    }

    @Override
    public void visitRegExpNamedGroupRef(RegExpNamedGroupRef groupRef) {
      super.visitRegExpNamedGroupRef(groupRef);
      if (mySupport != RegExpLanguageHost.Lookbehind.FULL) {
        stopAndReportError(groupRef, "Named group reference not allowed inside lookbehind");
      }
    }

    @Override
    public void visitRegExpPyCondRef(RegExpPyCondRef condRef) {
      super.visitRegExpPyCondRef(condRef);
      if (mySupport != RegExpLanguageHost.Lookbehind.FULL) {
        stopAndReportError(condRef, "Conditional group reference not allowed inside lookbehind");
      }
    }

    @Override
    public void visitPosixBracketExpression(RegExpPosixBracketExpression posixBracketExpression) {
      super.visitPosixBracketExpression(posixBracketExpression);
      myLength++;
    }

    public void stopAndReportError(RegExpElement element, @NotNull String message) {
      myHolder.newAnnotation(HighlightSeverity.ERROR, message).range(element).create();
      myStop = true;
    }
  }
}
