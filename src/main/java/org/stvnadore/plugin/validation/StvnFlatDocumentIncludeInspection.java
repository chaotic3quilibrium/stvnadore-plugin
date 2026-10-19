package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.Visitor;

/**
 * Validates that hermetic flat documents (.stvn) and hermetic definitions schemas (.stvn_d)
 * do not contain include directives under STVN 2.0.0 Specification § 1.2.
 */
@NullMarked
public final class StvnFlatDocumentIncludeInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnFlatDocumentIncludeInspection instance.
     */
    public StvnFlatDocumentIncludeInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitIncludeStmt(@NotNull IncludeStmt stmt) {
                var file = stmt.getContainingFile();
                if (StvnPsiUtils.isHermeticDocument(file)) {
                    holder.registerProblem(
                        stmt,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Hermetic document or schema (.stvn / .stvn_d) cannot contain include statements",
                            StvnDiagnosticCodes.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT
                        ),
                        ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
