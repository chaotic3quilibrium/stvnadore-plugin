package org.stvnadore.plugin.hints;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.*;

/**
 * Provides bounded-depth payload type inference and badge coordinate calculations
 * for STVN inlay hint rendering.
 */
@NullMarked
public final class StvnTypeInferenceHelper {

    private StvnTypeInferenceHelper() {}

    /**
     * Resolves display type label with a bounded recursion depth counter.
     *
     * @param value the PSI value to evaluate
     * @param maxDepth maximum recursion depth for resolving nested recursive structures
     * @return formatted type label, or null if unresolved
     */
    public static @Nullable String resolveValueTypeWithDepth(Value value, int maxDepth) {
        if (maxDepth <= 0) {
            return null;
        }
        return StvnTypeResolver.resolveValueType(value);
    }

    /**
     * Asserts whether a PSI value element matches the structural category of an expected schema.
     *
     * @param valueElement the AST value element
     * @param expectedSchema the expected schema type
     * @return true if structurally compatible, false otherwise
     */
    public static boolean isStructurallyCompatible(@Nullable Value valueElement, @Nullable SchemaType expectedSchema) {
        if (valueElement == null || expectedSchema == null) {
            return false;
        }
        var resolved = StvnTypeResolver.resolveNominalSchema(expectedSchema);
        var toInspect = (resolved != null) ? resolved : expectedSchema;
        var constructor = toInspect.getSchemaConstructor();
        if (constructor == null) {
            return true;
        }

        var collection = constructor.getCollectionType();
        if (collection != null) {
            var collVal = valueElement.getCollectionValue();
            if (collVal == null) {
                return false;
            }
            var firstChild = collection.getFirstChild();
            var tokenText = (firstChild != null) ? firstChild.getText() : "";
            if (org.stvnadore.core.StvnVocabulary.TYPE_MAP.equals(tokenText)) {
                return collVal.getMapLiteral() != null;
            } else if (org.stvnadore.core.StvnVocabulary.TYPE_SET.equals(tokenText) || org.stvnadore.core.StvnVocabulary.TYPE_SEQ.equals(tokenText)) {
                return collVal.getListLiteral() != null;
            }
            return true;
        }

        var product = constructor.getProductType();
        if (product != null) {
            var collVal = valueElement.getCollectionValue();
            return collVal != null && collVal.getTupleLiteral() != null;
        }

        var sum = constructor.getSumType();
        if (sum != null) {
            if (sum.getEnumDef() != null) {
                return valueElement.getValueKeyword() != null || (valueElement.getText() != null && valueElement.getText().startsWith("#"));
            }
            if (valueElement.getExplicitOptionValue() != null || valueElement.getExplicitEitherValue() != null || valueElement.getExplicitUnionValue() != null) {
                return true;
            }
            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            for (var branch : innerSchemas) {
                if (isStructurallyCompatible(valueElement, branch)) {
                    return true;
                }
            }
            return false;
        }

        var atomic = constructor.getAtomicType();
        if (atomic != null) {
            return valueElement.getCollectionValue() == null;
        }

        return true;
    }

    /**
     * Calculates the document offset for positioning an inlay badge relative to algebraic containers.
     *
     * @param valueElement the PSI value element being badged
     * @param coreNode the resolved core value representation
     * @return the document offset where the inlay badge should be anchored
     */
    public static int calculateInlayBadgeOffset(Value valueElement, @Nullable StvnValue coreNode) {
        var optPsi = valueElement.getExplicitOptionValue();
        if (optPsi != null && coreNode != null && StvnTypeResolver.isUnspooledContainer(optPsi, coreNode)) {
            var someLit = optPsi.getSomeLiteral();
            if (someLit != null) return someLit.getTextRange().getEndOffset();
            var someShortLit = optPsi.getSomeShortLiteral();
            if (someShortLit != null) return someShortLit.getTextRange().getEndOffset();
        }

        var eitherPsi = valueElement.getExplicitEitherValue();
        if (eitherPsi != null && coreNode != null && StvnTypeResolver.isUnspooledContainer(eitherPsi, coreNode)) {
            var leftLit = eitherPsi.getLeftLiteral();
            if (leftLit != null) return leftLit.getTextRange().getEndOffset();
            var leftShortLit = eitherPsi.getLeftShortLiteral();
            if (leftShortLit != null) return leftShortLit.getTextRange().getEndOffset();
            var rightLit = eitherPsi.getRightLiteral();
            if (rightLit != null) return rightLit.getTextRange().getEndOffset();
            var rightShortLit = eitherPsi.getRightShortLiteral();
            if (rightShortLit != null) return rightShortLit.getTextRange().getEndOffset();
        }

        var unionPsi = valueElement.getExplicitUnionValue();
        if (unionPsi != null && coreNode != null && StvnTypeResolver.isUnspooledContainer(unionPsi, coreNode)) {
            var tagPrefix = unionPsi.getUnionTagPrefix();
            if (tagPrefix != null) return tagPrefix.getTextRange().getEndOffset();
            var firstChild = unionPsi.getFirstChild();
            if (firstChild != null) return firstChild.getTextRange().getEndOffset();
        }

        return valueElement.getTextRange().getEndOffset();
    }

    /**
     * Determines whether a PSI element is an inner child of an unspooled algebraic container.
     *
     * @param element the PSI element to test
     * @return {@code true} if element is an inner child of an algebraic container, {@code false} otherwise
     */
    public static boolean isInnerChildOfAlgebraicContainer(PsiElement element) {
        var curr = element.getParent();
        while (curr != null && !(curr instanceof PsiFile) && !(curr instanceof BodyEntry)) {
            if (curr instanceof ExplicitOptionValue || curr instanceof ExplicitEitherValue || curr instanceof ExplicitUnionValue) {
                var containerVal = (curr instanceof Value) ? (Value) curr : PsiTreeUtil.getParentOfType(curr, Value.class);
                if (containerVal != null) {
                    var coreNode = StvnTypeResolver.resolveCoreValue(containerVal);
                    if (coreNode != null && StvnTypeResolver.isUnspooledContainer(curr, coreNode)) {
                        curr = curr.getParent();
                        continue;
                    }
                }
                return true;
            }
            curr = curr.getParent();
        }
        return false;
    }
}
