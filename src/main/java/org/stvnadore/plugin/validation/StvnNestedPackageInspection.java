package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.NestedPackageIllegal;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

/**
 * Validates that package enclosures are not illegally nested inside other package enclosures.
 */
@NullMarked
public final class StvnNestedPackageInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnNestedPackageInspection instance.
     */
    public StvnNestedPackageInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitNestedPackageIllegal(@NotNull NestedPackageIllegal element) {
                holder.registerProblem(
                    element,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Nested packages are prohibited",
                        StvnDiagnosticCodes.ERR_NESTED_PACKAGE_PROHIBITED
                    ),
                    ProblemHighlightType.ERROR
                );
            }

            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition def) {
                var target = def.getTypeDefTarget();
                if (target != null && ":package".equals(target.getText().trim())
                        && PsiTreeUtil.getParentOfType(def, PackageEnclosure.class) != null) {
                    holder.registerProblem(
                        def,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Nested packages are prohibited",
                            StvnDiagnosticCodes.ERR_NESTED_PACKAGE_PROHIBITED
                        ),
                        ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
