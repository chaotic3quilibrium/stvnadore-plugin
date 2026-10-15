package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.MetaDomainFacet;

/**
 * Quick-fix and intention action repairing ERR_RESERVED_DOMAIN_PROHIBITED (E038)
 * by replacing reserved system domains ('stvn_core', 'stvn_ir', 'stvn_cas') with
 * a compliant project domain identifier derived from the document filename.
 */
@NullMarked
public final class StvnChangeReservedDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    public StvnChangeReservedDomainQuickFix(PsiElement element) {
        super(element);
    }

    @Override
    public @NotNull String getText() {
        return "Change reserved domain to compliant user domain";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Document Identity";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var domainFacet = PsiTreeUtil.getParentOfType(startElement, MetaDomainFacet.class, false);
        if (domainFacet == null) return;

        var stringLit = domainFacet.getStringLiteral();
        if (stringLit == null) return;

        var fileName = file.getName();
        var parts = fileName.split("\\.");
        String newDomain = "custom_domain";
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[0].equals("prelude")) {
            newDomain = parts[0];
        } else if (parts.length >= 3 && !parts[1].isEmpty() && !parts[1].startsWith("stvn_")) {
            newDomain = parts[1];
        }

        var replacement = StvnElementFactory.createStringLiteral(project, "\"" + newDomain + "\"");
        stringLit.replace(replacement);
    }
}
