package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;

/**
 * Quick-fix removing the '#invertible' facet from an inverted map schema definition or anonymous container.
 * When '#invertible' is the sole facet in the metadata block, this fix deletes the entire block
 * and surrounding whitespace to prevent ERR_EMPTY_METADATA_BLOCK per STVN Spec § 3.5.1.
 */
@NullMarked
public final class RemoveInvertibleFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    private final String containerName;

    /**
     * Constructs a quick-fix targeting the specified '#invertible' metadata entry.
     *
     * @param element the '#invertible' MetadataEntry PSI node
     * @param containerName display name of the container (for example ':InvFloatMap' or ':Map')
     */
    public RemoveInvertibleFacetQuickFix(MetadataEntry element, String containerName) {
        super(element);
        this.containerName = containerName;
    }

    @Override
    public @Nls @NotNull String getText() {
        return "Remove '#invertible' facet from " + containerName;
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Remove '#invertible' facet";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        return startElement.isValid();
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var entry = (startElement instanceof MetadataEntry me) ? me
                : PsiTreeUtil.getParentOfType(startElement, MetadataEntry.class, false);
        if (entry == null || !entry.isValid()) {
            return;
        }
        var targetFile = entry.getContainingFile();

        var map = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
        if (map != null && map.isValid()) {
            if (map.getMetadataEntryList().size() <= 1) {
                var prev = map.getPrevSibling();
                var next = map.getNextSibling();
                if (prev instanceof PsiWhiteSpace) {
                    prev.delete();
                } else if (next instanceof PsiWhiteSpace) {
                    next.delete();
                }
                map.delete();
                StvnTraitViolationQuickFixProvider.postProcessFileMutation(project, file, targetFile);
                return;
            }
        }

        var prev = entry.getPrevSibling();
        var next = entry.getNextSibling();
        if (prev instanceof PsiWhiteSpace && prev.getPrevSibling() instanceof MetadataEntry) {
            prev.delete();
        } else if (next instanceof PsiWhiteSpace && next.getNextSibling() instanceof MetadataEntry) {
            next.delete();
        } else if (prev instanceof PsiWhiteSpace) {
            prev.delete();
        } else if (next instanceof PsiWhiteSpace) {
            next.delete();
        }
        entry.delete();
        StvnTraitViolationQuickFixProvider.postProcessFileMutation(project, file, targetFile);
    }
}
