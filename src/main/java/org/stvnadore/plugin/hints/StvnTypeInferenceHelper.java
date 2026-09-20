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
