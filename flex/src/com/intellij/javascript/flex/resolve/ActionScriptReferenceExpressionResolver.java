package com.intellij.javascript.flex.resolve;

import com.intellij.lang.javascript.JSConditionalCompilationDefinitionsProvider;
import com.intellij.lang.javascript.JSStubElementTypes;
import com.intellij.lang.javascript.JSTokenTypes;
import com.intellij.lang.javascript.flex.XmlBackedJSClassImpl;
import com.intellij.lang.javascript.psi.*;
import com.intellij.lang.javascript.psi.e4x.JSE4XNamespaceReference;
import com.intellij.lang.javascript.psi.ecmal4.JSConditionalCompileVariableReference;
import com.intellij.lang.javascript.psi.ecmal4.JSImportStatement;
import com.intellij.lang.javascript.psi.ecmal4.JSPackageStatement;
import com.intellij.lang.javascript.psi.ecmal4.JSReferenceListMember;
import com.intellij.lang.javascript.psi.impl.JSReferenceExpressionImpl;
import com.intellij.lang.javascript.psi.resolve.*;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

/**
 * @author Konstantin.Ulitin
 */
public class ActionScriptReferenceExpressionResolver
  extends JSReferenceExpressionResolver
  implements ResolveCache.PolyVariantResolver<JSReferenceExpressionImpl> {

  public ActionScriptReferenceExpressionResolver(JSReferenceExpressionImpl expression, boolean ignorePerformanceLimits) {
    super(expression, ignorePerformanceLimits);
  }

  @NotNull
  @Override
  public ResolveResult[] resolve(@NotNull JSReferenceExpressionImpl expression, boolean incompleteCode) {
    if (myReferencedName == null) return ResolveResult.EMPTY_ARRAY;

    PsiElement currentParent = JSResolveUtil.getTopReferenceParent(myParent);
    if (JSResolveUtil.isSelfReference(currentParent, myRef)) {
      if (!(currentParent instanceof JSPackageStatement) || myParent == currentParent) {
        return new ResolveResult[] { new JSResolveResult( currentParent) };
      }
    }

    if (isConditionalVariableReference(currentParent, myRef)) {
      if (ModuleUtilCore.findModuleForPsiElement(myRef) == null) {
        // do not red highlight conditional compiler definitions in 3rd party library/SDK source code
        return new ResolveResult[]{new JSResolveResult(myRef)};
      }
      else {
        return resolveConditionalCompilationVariable(myRef);
      }
    }

    if (myRef.isAttributeReference()) {
      return dummyResult(myRef);
    }

    if(JSCommonTypeNames.ANY_TYPE.equals(myReferencedName)) {
      if (currentParent instanceof JSImportStatement && myQualifier instanceof JSReferenceExpression)
        return ((JSReferenceExpression)myQualifier).multiResolve(false);
      if (myParent instanceof JSE4XNamespaceReference) return dummyResult(myRef);
    }

    // nonqualified items in implements list in mxml
    if (myParent instanceof JSReferenceListMember &&
        myParent.getParent().getNode().getElementType() == JSStubElementTypes.IMPLEMENTS_LIST &&
        myRef.getQualifier() == null &&
        myContainingFile instanceof JSFile &&
        XmlBackedJSClassImpl.isImplementsAttribute((JSFile)myContainingFile)) {
      PsiElement byQName = ActionScriptClassResolver.findClassByQNameStatic(myRef.getText(), myRef);
      // for some reason Flex compiler allows to implement non-public interface in default package, so let's not check access type here
      if (byQName != null) return new ResolveResult[] {new JSResolveResult(byQName)};
      return ResolveResult.EMPTY_ARRAY;
    }

    SinkResolveProcessor<ResolveResultSink> localProcessor;
    if (myUnqualifiedOrLocalResolve) {
      final PsiElement topParent = JSResolveUtil.getTopReferenceParent(myParent);
      localProcessor = new SinkResolveProcessor<ResolveResultSink>(myReferencedName, myRef, new ResolveResultSink(myRef, myReferencedName)) {
        @Override
        public boolean needPackages() {
          if (myParent instanceof JSReferenceExpression && topParent instanceof JSImportStatement) {
            return true;
          }
          return super.needPackages();
        }
      };

      localProcessor.setToProcessHierarchy(true);
      JSReferenceExpressionImpl.doProcessLocalDeclarations(myRef, myQualifier, localProcessor, true, false, null);

      PsiElement jsElement = localProcessor.getResult();
      if (myQualifier instanceof JSThisExpression &&
          localProcessor.processingEncounteredAnyTypeAccess() &&
          jsElement != null  // this is from ecma script closure, proceed it in JavaScript way
        ) {
        localProcessor.getResults().clear();
        jsElement = null;
      }

      if (myQualifier == null) {
        final JSReferenceExpression namespaceReference = JSReferenceExpressionImpl.getNamespaceReference(myRef);
        ResolveResult[] resolveResultsAsConditionalCompilationVariable = null;

        if (namespaceReference != null && (namespaceReference == myRef || namespaceReference.resolve() == namespaceReference)) {
          if (jsElement == null && ModuleUtilCore.findModuleForPsiElement(myRef) == null) {
            // do not red highlight conditional compiler definitions in 3rd party library/SDK source code
            return new ResolveResult[]{new JSResolveResult(myRef)};
          }

          resolveResultsAsConditionalCompilationVariable = resolveConditionalCompilationVariable(myRef);
        }

        if (resolveResultsAsConditionalCompilationVariable != null &&
            resolveResultsAsConditionalCompilationVariable.length > 0 &&
            (jsElement == null || resolveResultsAsConditionalCompilationVariable[0].isValidResult())) {
          return resolveResultsAsConditionalCompilationVariable;
        }
      }

      if (jsElement != null ||
          localProcessor.isEncounteredDynamicClasses() && myQualifier == null ||
          !localProcessor.processingEncounteredAnyTypeAccess() && !localProcessor.isEncounteredDynamicClasses()
        ) {
        return localProcessor.getResultsAsResolveResults();
      }
    } else {
      final QualifiedItemProcessor<ResolveResultSink> processor =
        new QualifiedItemProcessor<>(new ResolveResultSink(myRef, myReferencedName), myContainingFile);
      processor.setTypeContext(JSResolveUtil.isExprInTypeContext(myRef));
      JSTypeEvaluator.evaluateTypes(myQualifier, myContainingFile, processor);

      if (processor.resolved == QualifiedItemProcessor.TypeResolveState.PrefixUnknown) {
        return dummyResult(myRef);
      }

      if (processor.resolved == QualifiedItemProcessor.TypeResolveState.Resolved ||
          processor.resolved == QualifiedItemProcessor.TypeResolveState.Undefined ||
          processor.getResult() != null
        ) {
        return processor.getResultsAsResolveResults();
      } else {
        localProcessor = processor;
      }
    }

    ResolveResult[] results = resolveFromIndices(localProcessor);

    if (results.length == 0 && localProcessor.isEncounteredXmlLiteral()) {
      return dummyResult(myRef);
    }

    return results;
  }

  @Override
  protected void prepareProcessor(WalkUpResolveProcessor processor, @NotNull SinkResolveProcessor<ResolveResultSink> localProcessor) {
    boolean inDefinition = false;
    boolean allowOnlyCompleteMatches = myUnqualifiedOrLocalResolve && localProcessor.isEncounteredDynamicClasses();

    if (myParent instanceof JSDefinitionExpression) {
      inDefinition = true;
      if (myUnqualifiedOrLocalResolve && localProcessor.processingEncounteredAnyTypeAccess()) allowOnlyCompleteMatches = false;
      else allowOnlyCompleteMatches = true;
    } else if (myQualifier instanceof JSThisExpression && localProcessor.processingEncounteredAnyTypeAccess()) {
      processor.allowPartialResults();
    }

    if (inDefinition || allowOnlyCompleteMatches) {
      processor.setAddOnlyCompleteMatches(allowOnlyCompleteMatches);
    }
    processor.setSkipDefinitions(inDefinition);
    processor.addLocalResults(localProcessor);
  }

  @Override
  protected ResolveResult[] getResultsForDefinition() {
    return new ResolveResult[] { new JSResolveResult(myParent) };
  }

  private static boolean isConditionalVariableReference(PsiElement currentParent, JSReferenceExpressionImpl thisElement) {
    if(currentParent instanceof JSConditionalCompileVariableReference) {
      return JSReferenceExpressionImpl.getNamespaceReference(thisElement) != null;
    }
    return false;
  }


  private static ResolveResult[] resolveConditionalCompilationVariable(final JSReferenceExpression jsReferenceExpression) {
    final String namespace;
    final String constantName;

    final PsiElement parent = jsReferenceExpression.getParent();
    if (parent instanceof JSE4XNamespaceReference) {
      final PsiElement namespaceReference = ((JSE4XNamespaceReference)parent).getNamespaceReference();
      final PsiElement parentParent = parent.getParent();
      PsiElement sibling = parent.getNextSibling();
      while (sibling instanceof PsiWhiteSpace) {
        sibling = sibling.getNextSibling();
      }
      if (namespaceReference != null &&
          parentParent instanceof JSReferenceExpression &&
          sibling != null &&
          sibling.getNextSibling() == null &&
          sibling.getNode() != null &&
          sibling.getNode().getElementType() == JSTokenTypes.IDENTIFIER) {
        namespace = namespaceReference.getText();
        constantName = sibling.getText();
      }
      else {
        return new ResolveResult[]{new JSResolveResult(jsReferenceExpression, null, "javascript.unresolved.symbol.message")};
      }
    }
    else {
      final JSE4XNamespaceReference namespaceElement = PsiTreeUtil.getChildOfType(jsReferenceExpression, JSE4XNamespaceReference.class);
      final PsiElement namespaceReference = namespaceElement == null ? null : namespaceElement.getNamespaceReference();
      PsiElement sibling = namespaceElement == null ? null : namespaceElement.getNextSibling();
      while (sibling instanceof PsiWhiteSpace) {
        sibling = sibling.getNextSibling();
      }

      if (namespaceElement != null &&
          sibling != null &&
          sibling.getNextSibling() == null &&
          sibling.getNode() != null &&
          sibling.getNode().getElementType() == JSTokenTypes.IDENTIFIER) {
        namespace = namespaceReference.getText();
        constantName = sibling.getText();
      }
      else {
        return new ResolveResult[]{new JSResolveResult(jsReferenceExpression, null, "javascript.unresolved.symbol.message")};
      }
    }

    for (JSConditionalCompilationDefinitionsProvider provider : JSConditionalCompilationDefinitionsProvider.EP_NAME.getExtensions()) {
      if (provider.containsConstant(ModuleUtilCore.findModuleForPsiElement(jsReferenceExpression), namespace,
                                    constantName)) {
        return new ResolveResult[]{new JSResolveResult(jsReferenceExpression)};
      }
    }

    return new ResolveResult[]{new JSResolveResult(jsReferenceExpression, null, "javascript.unresolved.symbol.message")};
  }
}
