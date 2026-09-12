package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.NestedPackageIllegal;
import org.stvnadore.psi.Visitor;

/**
 * Validates that package enclosures are not illegally nested inside other package enclosures.
 */
@NullMarked
public final class StvnNestedPackageInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitNestedPackageIllegal(@NotNull NestedPackageIllegal element) {
                holder.registerProblem(
                    element,
                    "Nested packages are prohibited",
                    ProblemHighlightType.ERROR
                );
            }
        };
    }
}
