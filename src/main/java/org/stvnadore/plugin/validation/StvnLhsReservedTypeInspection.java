package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

/**
 * Validates that fundamental types and reserved keywords are not declared on the
 * left-hand side (LHS) of type definitions.
 */
@NullMarked
public final class StvnLhsReservedTypeInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition def) {
                var target = def.getTypeDefTarget();
                if (target == null) {
                    return;
                }
                var rawText = target.getText().trim();
                if (StvnTypeResolver.isReservedFundamentalType(rawText)
                        || target.getAtomicType() != null
                        || target.getCollectionType() != null
                        || target.getStructuralType() != null
                        || target.getReservedKeyword() != null) {
                    holder.registerProblem(
                        target,
                        "Fundamental type '" + rawText + "' cannot be aliased",
                        ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
