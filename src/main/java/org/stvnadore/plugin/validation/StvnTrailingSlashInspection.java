package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.UseTargetIllegal;
import org.stvnadore.psi.Visitor;

/**
 * Validates that :use target paths do not contain trailing slash characters.
 */
@NullMarked
public final class StvnTrailingSlashInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitUseTargetIllegal(@NotNull UseTargetIllegal element) {
                holder.registerProblem(
                    element,
                    "Trailing slash prohibited in :use target",
                    ProblemHighlightType.ERROR
                );
            }
        };
    }
}
