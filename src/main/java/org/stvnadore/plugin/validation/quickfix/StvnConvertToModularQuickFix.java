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
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.TypeKeyword;

import java.io.IOException;

/**
 * Quick-fix converting a flat document (.stvn / .stvn_d) to modular format (.stvn_i / .stvn_di).
 */
@NullMarked
public final class StvnConvertToModularQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final boolean isDefsSchema;

    /**
     * Constructs a modular format conversion quick-fix.
     *
     * @param element offending TypeKeyword element
     * @param isDefsSchema true for .stvn_d -> .stvn_di; false for .stvn -> .stvn_i
     */
    public StvnConvertToModularQuickFix(TypeKeyword element, boolean isDefsSchema) {
        super(element);
        this.isDefsSchema = isDefsSchema;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.LOW;
    }

    @Override
    public @NotNull String getText() {
        return isDefsSchema
            ? "Convert schema to modular format (.stvn_di)"
            : "Convert document to modular format (.stvn_i)";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Convert to Modular Format";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var vf = file.getVirtualFile();
        if (vf == null) return;
        var oldName = file.getName();
        String newName;
        if (isDefsSchema && oldName.endsWith(".stvn_d")) {
            newName = oldName.substring(0, oldName.length() - 7) + ".stvn_di";
        } else if (!isDefsSchema && oldName.endsWith(".stvn")) {
            newName = oldName.substring(0, oldName.length() - 5) + ".stvn_i";
        } else {
            return;
        }

        try {
            vf.rename(this, newName);
        } catch (IOException e) {
            return;
        }

        var doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        var metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
        if (metaEntry != null) {
            var kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null) {
                String targetKind = isDefsSchema ? "#DEFS_INCLUDE" : "#BODY_INCLUDE";
                doc.replaceString(kindFacet.getTextRange().getStartOffset(), kindFacet.getTextRange().getEndOffset(), "#kind " + targetKind);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }
}
