package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnPsiUtils;
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
    public boolean isAvailable(@NotNull Project project, @NotNull PsiFile file, @NotNull PsiElement startElement, @NotNull PsiElement endElement) {
        // In hermetic flat documents (.stvn, .stvn_d), :use must strictly target prelude or local packages (Spec Invariant 7)
        if (StvnPsiUtils.isHermeticDocument(file)) {
            boolean isPrelude = targetNamespace.startsWith(":org/stvnadore/prelude");
            boolean isLocal = StvnPsiUtils.isPackageDeclaredLocally(file, targetNamespace);
            if (!isPrelude && !isLocal) {
                return false;
            }
        }
        return super.isAvailable(project, file, startElement, endElement);
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
            var existingUse = findExistingUse(enclosingPkg);
            if (existingUse != null) {
                appendSymbolToExistingUse(doc, existingUse);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
            var openBrace = enclosingPkg.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : enclosingPkg.getTextOffset();
            importStatement = "\n    :use [ " + targetNamespace + " { " + symbolName + " " + symbolName + " } ]";
        } else {
            var defsEntry = org.stvnadore.plugin.psi.StvnPsiUtils.getOrCreateDefsBlock(file);
            if (defsEntry == null) {
                return;
            }
            var existingUse = findExistingUse(defsEntry);
            if (existingUse != null) {
                appendSymbolToExistingUse(doc, existingUse);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
            var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : defsEntry.getTextOffset();
            importStatement = "\n    :use [ " + targetNamespace + " { " + symbolName + " " + symbolName + " } ]";
        }

        doc.insertString(insertOffset, importStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    private @Nullable org.stvnadore.psi.UseStmt findExistingUse(PsiElement container) {
        var useStmts = PsiTreeUtil.findChildrenOfType(container, org.stvnadore.psi.UseStmt.class);
        for (var use : useStmts) {
            if (container instanceof PackageEnclosure pkg) {
                if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != pkg) {
                    continue;
                }
            } else if (container instanceof DefsEntry defs) {
                if (PsiTreeUtil.getParentOfType(use, DefsEntry.class) != defs || PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null) {
                    continue;
                }
            }
            var target = use.getUseTarget();
            if (target != null && target.getText().equals(targetNamespace)) {
                return use;
            }
        }
        return null;
    }

    private void appendSymbolToExistingUse(com.intellij.openapi.editor.Document doc, org.stvnadore.psi.UseStmt existingUse) {
        var opts = existingUse.getUseOptionsBlock();
        if (opts != null && opts.getText().contains("#strip")) {
            return;
        }
        if (existingUse.getText().contains("#strip")) {
            return;
        }
        var aliasBlock = existingUse.getUseAliasBlock();
        var chars = doc.getCharsSequence();
        if (aliasBlock != null) {
            for (var alias : aliasBlock.getUseMapAliasList()) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2 && list.get(0) != null && list.get(0).getText().equals(symbolName)) {
                    return; // Already present
                }
                var vList = alias.getValueKeywordList();
                if (vList.size() >= 2 && vList.get(0) != null && vList.get(0).getText().equals(symbolName)) {
                    return; // Already present
                }
            }
            var rbrace = aliasBlock.getNode().findChildByType(org.stvnadore.psi.StvnTypes.RBRACE);
            int offset = rbrace != null ? rbrace.getStartOffset() : aliasBlock.getTextRange().getEndOffset() - 1;
            if (offset > 0 && chars.charAt(offset - 1) == ' ') {
                doc.insertString(offset - 1, " " + symbolName + " " + symbolName);
            } else {
                doc.insertString(offset, " " + symbolName + " " + symbolName + " ");
            }
        } else {
            var rbrack = existingUse.getNode().findChildByType(org.stvnadore.psi.StvnTypes.RBRACK);
            int offset = rbrack != null ? rbrack.getStartOffset() : existingUse.getTextRange().getEndOffset() - 1;
            if (offset > 0 && chars.charAt(offset - 1) == ' ') {
                doc.insertString(offset - 1, " { " + symbolName + " " + symbolName + " }");
            } else {
                doc.insertString(offset, " { " + symbolName + " " + symbolName + " } ");
            }
        }
    }
}
