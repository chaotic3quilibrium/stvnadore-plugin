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
import org.stvnadore.psi.PackagePath;
import org.stvnadore.psi.TypeDefTarget;
import org.stvnadore.psi.ValueKeyword;

/**
 * Quick-fix and intention action repairing ERR_RESERVED_PACKAGE_PROHIBITED (E037)
 * by renaming reserved ':org/stvnadore' namespaces to a compliant project namespace.
 */
@NullMarked
public final class StvnRenameReservedPackageQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    public StvnRenameReservedPackageQuickFix(PsiElement element) {
        super(element);
    }

    @Override
    public @NotNull String getText() {
        return "Rename reserved namespace to project namespace ':com/example'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Package Governance";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var pkgPath = PsiTreeUtil.getParentOfType(startElement, PackagePath.class, false);
        if (pkgPath != null && pkgPath.getTypeKeyword() != null) {
            var current = pkgPath.getTypeKeyword().getText();
            var replaced = replaceReservedPrefix(current, ":com/example");
            var newKw = StvnElementFactory.createTypeKeyword(project, replaced);
            pkgPath.getTypeKeyword().replace(newKw);
            return;
        }

        var typeTarget = PsiTreeUtil.getParentOfType(startElement, TypeDefTarget.class, false);
        if (typeTarget != null && typeTarget.getTypeKeyword() != null) {
            var current = typeTarget.getTypeKeyword().getText();
            var replaced = replaceReservedPrefix(current, ":com/example");
            var newKw = StvnElementFactory.createTypeKeyword(project, replaced);
            typeTarget.getTypeKeyword().replace(newKw);
            return;
        }

        var valKw = PsiTreeUtil.getParentOfType(startElement, ValueKeyword.class, false);
        if (valKw != null) {
            var current = valKw.getText();
            var replaced = replaceReservedConstantPrefix(current, "#com/example");
            var newValKw = StvnElementFactory.createValueKeyword(project, replaced);
            valKw.replace(newValKw);
        }
    }

    private static String replaceReservedPrefix(String current, String replacement) {
        if (current.equals(":org/stvnadore")) {
            return replacement;
        }
        if (current.startsWith(":org/stvnadore/")) {
            return replacement + current.substring(":org/stvnadore".length());
        }
        return current;
    }

    private static String replaceReservedConstantPrefix(String current, String replacement) {
        if (current.equals("#org/stvnadore")) {
            return replacement;
        }
        if (current.startsWith("#org/stvnadore/")) {
            return replacement + current.substring("#org/stvnadore".length());
        }
        return current;
    }
}
