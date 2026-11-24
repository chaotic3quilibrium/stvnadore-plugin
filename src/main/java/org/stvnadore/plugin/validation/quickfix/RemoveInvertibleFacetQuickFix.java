package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;

import java.util.Objects;

/**
 * Quick-fix removing the '#invertible' facet from an inverted map schema definition or anonymous container.
 * When '#invertible' is the sole comment-free facet in the metadata block, this fix deletes the entire block
 * and surrounding whitespace to prevent ERR_EMPTY_METADATA_BLOCK per STVN Spec § 3.5.1.
 */
@NullMarked
public final class RemoveInvertibleFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    @SafeFieldForPreview
    private final String containerName;
    @SafeFieldForPreview
    private final Priority priority;

    /**
     * Constructs a quick-fix targeting the specified '#invertible' metadata entry.
     *
     * @param element       the '#invertible' MetadataEntry PSI node
     * @param containerName display name of the container (for example ':InvFloatMap' or ':Map')
     */
    public RemoveInvertibleFacetQuickFix(MetadataEntry element, String containerName) {
        this(element, containerName, Priority.NORMAL);
    }

    /**
     * Constructs a quick-fix targeting the specified '#invertible' metadata entry with explicit intention priority.
     *
     * @param element       the '#invertible' MetadataEntry PSI node
     * @param containerName display name of the container (for example ':InvFloatMap' or ':Map')
     * @param priority      the intention priority ordering
     */
    public RemoveInvertibleFacetQuickFix(MetadataEntry element, String containerName, Priority priority) {
        super(element);
        this.containerName = containerName;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @Nls @NotNull String getText() {
        return "Remove '" + StvnVocabulary.FACET_KW_INVERTIBLE + "' facet from " + containerName;
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Remove '" + StvnVocabulary.FACET_KW_INVERTIBLE + "' facet";
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
            if (map.getMetadataEntryList().size() <= 1
                    && PsiTreeUtil.findChildOfType(map, PsiComment.class) == null) {
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

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        var cross = StvnTraitViolationQuickFixProvider.tryGenerateCrossFilePreview(
            project, file, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem)
        );
        return cross != null ? cross : super.generatePreview(project, editor, file);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement descElem = previewDescriptor.getPsiElement();
        PsiFile activeFile = descElem != null ? descElem.getContainingFile() : null;
        var cross = activeFile != null
            ? StvnTraitViolationQuickFixProvider.tryGenerateCrossFilePreview(
                project, activeFile, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem))
            : null;
        return cross != null ? cross : super.generatePreview(project, previewDescriptor);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RemoveInvertibleFacetQuickFix other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement())
            && Objects.equals(containerName, other.containerName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getStartElement(), containerName);
    }
}
