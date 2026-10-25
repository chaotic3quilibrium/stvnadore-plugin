package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.UseTargetIllegal;
import org.stvnadore.psi.Visitor;

/**
 * Validates that :use target paths do not contain trailing slash characters.
 */
@NullMarked
public final class StvnTrailingSlashInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnTrailingSlashInspection instance.
     */
    public StvnTrailingSlashInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitUseTargetIllegal(@NotNull UseTargetIllegal element) {
                holder.registerProblem(
                    element,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Trailing slash prohibited in :use target",
                        StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED
                    ),
                    ProblemHighlightType.ERROR
                );
            }

            @Override
            public void visitUseTarget(@NotNull UseTarget element) {
                if (element.getText().endsWith("/")) {
                    holder.registerProblem(
                        element,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Trailing slash prohibited in :use target",
                            StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED
                        ),
                        ProblemHighlightType.ERROR
                    );
                }
            }

            @Override
            public void visitUseStmt(@NotNull UseStmt element) {
                var text = element.getText();
                var hasSlash = text.contains("/") && (text.endsWith("/") || text.contains("/ ") || text.contains("/]") || text.contains("/ ]"));
                if (!hasSlash) {
                    var next = element.getNextSibling();
                    while (next != null && !(next instanceof UseStmt) && !next.getText().contains("}")) {
                        if (next.getText().contains("/") && next.getText().contains("]")) {
                            hasSlash = true;
                            break;
                        }
                        if (next.getText().equals("/")) {
                            hasSlash = true;
                            break;
                        }
                        next = next.getNextSibling();
                    }
                }
                if (hasSlash) {
                    holder.registerProblem(
                        element,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Trailing slash prohibited in :use target",
                            StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED
                        ),
                        ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
