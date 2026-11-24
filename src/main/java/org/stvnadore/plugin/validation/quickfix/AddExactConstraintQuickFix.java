package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.validation.StvnMetadataFacetInsertionUtil;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;

import java.util.Objects;

/**
 * Quick-fix attaching the {@code #exact} constraint to continuous {@code :Float} or a nominal
 * {@code :Float} definition in {@code :defs}.
 * Elevates continuous machine float to discrete arbitrary-precision decimal representation,
 * restoring {@code #equatable #TRUE} under STVN Spec § 6.2.
 */
@NullMarked
public final class AddExactConstraintQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    @SafeFieldForPreview
    private final @Nullable String nominalTypeName;
    @SafeFieldForPreview
    private final @Nullable String inlineSlotQualifier;
    @SafeFieldForPreview
    private final Priority priority;

    /**
     * Constructs a quick-fix targeting an inline continuous {@code :Float} {@link SchemaType}.
     *
     * @param element the {@link SchemaType} representing continuous {@code :Float}
     */
    public AddExactConstraintQuickFix(SchemaType element) {
        this(element, null, Priority.HIGH);
    }

    /**
     * Constructs a quick-fix targeting an inline continuous {@code :Float} {@link SchemaType}
     * with an optional slot qualifier and explicit intention priority.
     *
     * @param element             the {@link SchemaType} representing continuous {@code :Float}
     * @param inlineSlotQualifier optional slot qualifier when multiple inline {@code :Float} leaves exist
     * @param priority            the intention priority ordering
     */
    public AddExactConstraintQuickFix(
            SchemaType element,
            @Nullable String inlineSlotQualifier,
            Priority priority
    ) {
        super(element);
        this.nominalTypeName = null;
        this.inlineSlotQualifier = inlineSlotQualifier;
        this.priority = priority;
    }

    /**
     * Constructs a quick-fix targeting a nominal continuous {@code :Float} {@link TypeDefinition} in {@code :defs}.
     *
     * @param nominalFloatDef the {@link TypeDefinition} declaring continuous {@code :Float}
     * @param nominalTypeName display name of the nominal type (for example {@code :SampleReading})
     */
    public AddExactConstraintQuickFix(TypeDefinition nominalFloatDef, String nominalTypeName) {
        this(nominalFloatDef, nominalTypeName, Priority.HIGH);
    }

    /**
     * Constructs a quick-fix targeting a nominal continuous {@code :Float} {@link TypeDefinition} in {@code :defs}
     * with explicit intention priority.
     *
     * @param nominalFloatDef the {@link TypeDefinition} declaring continuous {@code :Float}
     * @param nominalTypeName display name of the nominal type (for example {@code :SampleReading})
     * @param priority        the intention priority ordering
     */
    public AddExactConstraintQuickFix(
            TypeDefinition nominalFloatDef,
            String nominalTypeName,
            Priority priority
    ) {
        super(nominalFloatDef);
        this.nominalTypeName = nominalTypeName;
        this.inlineSlotQualifier = null;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @Nls @NotNull String getText() {
        if (nominalTypeName != null) {
            return "Add '" + StvnVocabulary.FACET_KW_EXACT + "' to " + nominalTypeName
                + " in " + StvnVocabulary.KEYWORD_DEFS;
        }
        if (inlineSlotQualifier != null && !inlineSlotQualifier.isEmpty()) {
            return "Add '{ " + StvnVocabulary.FACET_KW_EXACT + " }' constraint to "
                + StvnVocabulary.TYPE_FLOAT + " (" + inlineSlotQualifier + ")";
        }
        return "Add '{ " + StvnVocabulary.FACET_KW_EXACT + " }' constraint to " + StvnVocabulary.TYPE_FLOAT;
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Add '{ " + StvnVocabulary.FACET_KW_EXACT + " }' constraint";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid()) {
            return false;
        }
        if (startElement instanceof TypeDefinition td) {
            var st = td.getSchemaType();
            if (st == null || !st.isValid()) {
                return false;
            }
            return st.getText().contains(StvnVocabulary.TYPE_FLOAT)
                && !containsExact(td.getMetadataMap())
                && !containsExact(st.getMetadataMap());
        }
        var schemaType = (startElement instanceof SchemaType st) ? st
                : PsiTreeUtil.getParentOfType(startElement, SchemaType.class, false);
        if (schemaType == null || !schemaType.isValid()) {
            return false;
        }
        var text = schemaType.getText();
        return text.contains(StvnVocabulary.TYPE_FLOAT) && !text.contains(StvnVocabulary.FACET_KW_EXACT);
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        SchemaType schemaType;
        MetadataMap metaMap;
        PsiFile targetFile;
        if (startElement instanceof TypeDefinition td) {
            if (!td.isValid() || td.getSchemaType() == null) {
                return;
            }
            schemaType = td.getSchemaType();
            metaMap = td.getMetadataMap() != null ? td.getMetadataMap() : schemaType.getMetadataMap();
            targetFile = td.getContainingFile();
        } else {
            schemaType = (startElement instanceof SchemaType st) ? st
                    : PsiTreeUtil.getParentOfType(startElement, SchemaType.class, false);
            if (schemaType == null || !schemaType.isValid()) {
                return;
            }
            metaMap = schemaType.getMetadataMap();
            targetFile = schemaType.getContainingFile();
        }

        if (metaMap != null && metaMap.isValid()) {
            if (containsExact(metaMap)) {
                return;
            }
            boolean hasSizeFacet = metaMap.getMetadataEntryList().stream()
                .anyMatch(e -> e.getText().trim().startsWith(StvnVocabulary.FACET_KW_SIZE));
            if (!hasSizeFacet && targetFile != null) {
                var psiDocManager = PsiDocumentManager.getInstance(project);
                var doc = targetFile.getViewProvider().getDocument();
                if (doc == null) {
                    doc = psiDocManager.getDocument(targetFile);
                }
                if (doc != null) {
                    if (targetFile.isPhysical()) {
                        psiDocManager.doPostponedOperationsAndUnblockDocument(doc);
                    }
                    StvnMetadataFacetInsertionUtil.insertOrReplaceSingleFacet(
                        doc,
                        metaMap,
                        StvnVocabulary.FACET_KW_EXACT,
                        StvnVocabulary.FACET_KW_EXACT
                    );
                    psiDocManager.commitDocument(doc);
                    StvnTraitViolationQuickFixProvider.postProcessFileMutation(project, file, targetFile);
                    return;
                }
            }

            var newEntriesText = new StringBuilder();
            newEntriesText.append(StvnVocabulary.FACET_KW_EXACT);
            for (var entry : metaMap.getMetadataEntryList()) {
                var entryText = entry.getText().trim();
                // Purge conflicting #size facet per STVN Spec § 6.1 (mutually exclusive)
                if (!entryText.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                    newEntriesText.append(" ").append(entryText);
                }
            }
            var newMap = StvnElementFactory.createMetadataMap(project, "{ " + newEntriesText + " }");
            metaMap.replace(newMap);
        } else {
            var newSchema = StvnElementFactory.createSchemaType(
                    project, "{ " + StvnVocabulary.FACET_KW_EXACT + " } " + schemaType.getText());
            schemaType.replace(newSchema);
        }

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
        if (!(obj instanceof AddExactConstraintQuickFix other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement())
            && Objects.equals(nominalTypeName, other.nominalTypeName)
            && Objects.equals(inlineSlotQualifier, other.inlineSlotQualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getStartElement(), nominalTypeName, inlineSlotQualifier);
    }

    private static boolean containsExact(@Nullable MetadataMap map) {
        if (map == null) {
            return false;
        }
        for (var entry : map.getMetadataEntryList()) {
            if (entry.getText().trim().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
                return true;
            }
        }
        return false;
    }
}
