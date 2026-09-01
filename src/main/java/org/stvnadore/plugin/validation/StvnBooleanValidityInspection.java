package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.*;

import java.util.Set;

@NullMarked
public final class StvnBooleanValidityInspection extends LocalInspectionTool {

    private static final Set<String> BOOLEAN_WHITELIST = Set.of("#TRUE", "#T", "#FALSE", "#F");

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitValueKeyword(@NotNull ValueKeyword element) {
                checkBooleanValidity(element, holder);
            }

            @Override
            public void visitBooleanValue(@NotNull BooleanValue element) {
                checkBooleanValidity(element, holder);
            }

            @Override
            public void visitTrueShortLiteral(@NotNull TrueShortLiteral element) {
                checkBooleanValidity(element, holder);
            }

            @Override
            public void visitFalseShortLiteral(@NotNull FalseShortLiteral element) {
                checkBooleanValidity(element, holder);
            }
        };
    }

    private static void checkBooleanValidity(PsiElement element, ProblemsHolder holder) {
        if (element.getParent() instanceof BooleanValue) {
            return;
        }

        var text = element.getText();
        if (BOOLEAN_WHITELIST.contains(text)) {
            return;
        }

        var valueParent = PsiTreeUtil.getParentOfType(element, Value.class);
        if (valueParent == null) {
            return;
        }

        if (!StvnTypeResolver.resolvesToBoolean(valueParent)) {
            return;
        }

        var useLong = StvnTypeResolver.useLongFormSumTypes(holder.getProject());
        var lower = text.toLowerCase();
        var isNearMiss = lower.equals("#true") || lower.equals("#false") || lower.equals("#t") || lower.equals("#f");

        if (isNearMiss) {
            var isTrue = lower.equals("#true") || lower.equals("#t");
            var targetTag = isTrue 
                ? (useLong ? "#TRUE" : "#T") 
                : (useLong ? "#FALSE" : "#F");

            var message = "Invalid boolean literal casing: '" + text + "'. Valid forms are case-sensitive: #TRUE, #T, #FALSE, #F.";
            holder.registerProblem(
                element,
                message,
                ProblemHighlightType.ERROR,
                new StvnVariantStyleInspection.ChangeVariantTagFormQuickFix(targetTag)
            );
        } else {
            var message = "Value '" + text + "' is not a valid boolean literal. Expected exactly #TRUE, #T, #FALSE, or #F.";
            if (useLong) {
                holder.registerProblem(
                    element,
                    message,
                    ProblemHighlightType.ERROR,
                    new ChangeToBooleanLiteralQuickFix("#TRUE"),
                    new ChangeToBooleanLiteralQuickFix("#FALSE")
                );
            } else {
                holder.registerProblem(
                    element,
                    message,
                    ProblemHighlightType.ERROR,
                    new ChangeToBooleanLiteralQuickFix("#T"),
                    new ChangeToBooleanLiteralQuickFix("#F")
                );
            }
        }
    }

    private static final class ChangeToBooleanLiteralQuickFix implements LocalQuickFix {
        private final String targetTag;

        public ChangeToBooleanLiteralQuickFix(String targetTag) {
            this.targetTag = targetTag;
        }

        @Override
        public @NotNull String getName() {
            return "Change to " + targetTag;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Change to boolean literal";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            var valueParent = PsiTreeUtil.getParentOfType(element, Value.class);
            if (valueParent != null) {
                if (!StvnTypeResolver.resolvesToBoolean(valueParent)) {
                    return;
                }
                var dummy = StvnElementFactory.createValue(project, targetTag);
                valueParent.replace(dummy);
            }
        }
    }
}
