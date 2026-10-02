package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.TypeKeyword;

/**
 * Quick-fix injecting a scoped :use import statement into the nearest enclosing :defs or :package block.
 */
@NullMarked
public final class StvnImportUseQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String targetNamespace;
    private final String symbolName;
    private final String customLabel;
    private final Priority priority;

    /**
     * Constructs an import quick-fix with standard label and NORMAL priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param targetNamespace the target namespace to import from
     * @param symbolName the leaf type symbol name to import
     */
    public StvnImportUseQuickFix(TypeKeyword element, String targetNamespace, String symbolName) {
        this(element, targetNamespace, symbolName,
            targetNamespace.equals(":org/stvnadore/prelude")
                ? "Import '" + symbolName + "' from Prelude via :use (preserves type identity)"
                : "Import '" + symbolName + "' from " + targetNamespace + " via :use (preserves type identity)",
            Priority.NORMAL);
    }

    /**
     * Constructs an import quick-fix with custom label and NORMAL priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param targetNamespace the target namespace to import from
     * @param symbolName the leaf type symbol name to import
     * @param customLabel user-facing intention action text
     */
    public StvnImportUseQuickFix(TypeKeyword element, String targetNamespace, String symbolName, String customLabel) {
        this(element, targetNamespace, symbolName, customLabel, Priority.NORMAL);
    }

    /**
     * Constructs an import quick-fix with custom label and explicit priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param targetNamespace the target namespace to import from
     * @param symbolName the leaf type symbol name to import
     * @param customLabel user-facing intention action text
     * @param priority intention action display priority
     */
    public StvnImportUseQuickFix(TypeKeyword element, String targetNamespace, String symbolName, String customLabel, Priority priority) {
        super(element);
        this.targetNamespace = targetNamespace;
        this.symbolName = symbolName;
        this.customLabel = customLabel;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @NotNull String getText() {
        return customLabel;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Import Scoped Reference (:use)";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        var enclosingPkg = PsiTreeUtil.getParentOfType(startElement, PackageEnclosure.class);
        int insertOffset;
        String importStatement;

        if (enclosingPkg != null) {
            var openBrace = enclosingPkg.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : enclosingPkg.getTextOffset();
            importStatement = "\n    :use [ " + targetNamespace + " { " + symbolName + " } ]";
        } else {
            var defsEntry = PsiTreeUtil.getParentOfType(startElement, DefsEntry.class);
            if (defsEntry != null) {
                var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
                insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : defsEntry.getTextOffset();
                importStatement = "\n    :use [ " + targetNamespace + " { " + symbolName + " } ]";
            } else {
                return;
            }
        }

        doc.insertString(insertOffset, importStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);
    }
}
