package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFlatPayloadFile;
import org.stvnadore.plugin.StvnInclfFile;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.Visitor;

/**
 * Validates that hermetic flat documents (.stvn_f) and flat leaf modules (.stvn_inclf)
 * do not contain include directives.
 */
@NullMarked
public final class StvnFlatDocumentIncludeInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitIncludeStmt(@NotNull IncludeStmt stmt) {
                var file = stmt.getContainingFile();
                if (file instanceof StvnFlatPayloadFile || file instanceof StvnInclfFile
                        || (file != null && (file.getName().endsWith(".stvn_f") || file.getName().endsWith(".stvn_inclf")))) {
                    holder.registerProblem(
                        stmt,
                        "Flat document or leaf module (.stvn_f / .stvn_inclf) cannot contain include statements",
                        ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
