package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.navigation.StvnNavigationUtil;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides diagnostic navigation intentions and paired healing quick-fixes
 * for STVN trait violation errors (ERR_TRAIT_VIOLATION).
 */
@NullMarked
public final class StvnTraitViolationQuickFixProvider {

    private StvnTraitViolationQuickFixProvider() {
    }

    /**
     * Inspects a trait violation diagnostic, resolves root causes in :defs,
     * and attaches appropriate navigation intentions and paired quick-fixes.
     */
    public static AnnotationBuilder registerActions(
            AnnotationBuilder builder,
            PsiFile file,
            TextRange range,
            String message
    ) {
        var elemAtRange = file.findElementAt(range.getStartOffset());
        if (elemAtRange == null) {
            return builder;
        }

        var schemaType = (elemAtRange instanceof SchemaType st) ? st
                : PsiTreeUtil.getParentOfType(elemAtRange, SchemaType.class, false);
        if (schemaType == null) {
            var coll = PsiTreeUtil.getParentOfType(elemAtRange, CollectionType.class, false);
            if (coll != null && !coll.getSchemaTypeList().isEmpty()) {
                schemaType = coll.getSchemaTypeList().get(0);
            }
        }
        if (schemaType == null) {
            return builder;
        }

        var typeKw = schemaType.getTypeKeyword();
        if (typeKw == null) {
            typeKw = PsiTreeUtil.findChildOfType(schemaType, TypeKeyword.class);
        }

        if (typeKw != null) {
            var typeName = typeKw.getText().trim();
            var resolvedDefElem = StvnTypeReference.resolveTypeInFile(file, typeName, new java.util.HashSet<>());
            var targetTypeDef = StvnPsiUtils.getParentTypeDefinition(resolvedDefElem);
            if (targetTypeDef != null) {
                return registerActionsForNominalType(builder, file, targetTypeDef, typeName, message);
            }
        }

        return builder;
    }

    private static AnnotationBuilder registerActionsForNominalType(
            AnnotationBuilder builder,
            PsiFile file,
            TypeDefinition typeDef,
            String typeName,
            String message
    ) {
        var explicitOverrideEntry = findExplicitTraitEntry(typeDef, StvnVocabulary.FACET_KW_EQUATABLE);
        if (explicitOverrideEntry != null && isExplicitFalse(explicitOverrideEntry)) {
            // Case 1: Explicit Override in :defs
            builder = builder.withFix(new NavigateToExplicitOverrideIntention(explicitOverrideEntry, typeName));
            if (file.isWritable() && typeDef.getContainingFile().isWritable()) {
                builder = builder.withFix(new RemoveIncompatibleOverrideQuickFix(explicitOverrideEntry, typeName));
                boolean intrinsicallyEquatable = isUnderlyingIntrinsicallyEquatable(typeDef);
                if (!intrinsicallyEquatable) {
                    builder = builder.withFix(new ChangeNominalOverrideQuickFix(typeDef, typeName));
                }
            }
            return builder;
        }

        // Case 2: Inductive Constituent Breakdown
        var nonConforming = extractNonConformingConstituents(typeDef);
        for (var constituent : nonConforming) {
            builder = builder.withFix(new JumpToNonConformingConstituentIntention(
                    constituent.schema(), typeName, constituent.index(), constituent.label()));
        }

        if (file.isWritable() && typeDef.getContainingFile().isWritable()) {
            builder = builder.withFix(new ChangeNominalOverrideQuickFix(typeDef, typeName));
        }

        return builder;
    }

    public record ConstituentInfo(int index, String label, SchemaType schema) {}

    public static List<ConstituentInfo> extractNonConformingConstituents(TypeDefinition typeDef) {
        var result = new ArrayList<ConstituentInfo>();
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            return result;
        }

        var file = typeDef.getContainingFile();
        if (schemaType.getSchemaConstructor() == null && schemaType.getTypeKeyword() != null && file != null) {
            var aliasTarget = StvnTypeReference.resolveTypeInFile(file, schemaType.getTypeKeyword().getText().trim(), new java.util.HashSet<>());
            var aliasDef = StvnPsiUtils.getParentTypeDefinition(aliasTarget);
            if (aliasDef != null && aliasDef != typeDef) {
                return extractNonConformingConstituents(aliasDef);
            }
        }

        if (schemaType.getSchemaConstructor() == null) {
            return result;
        }

        var ctor = schemaType.getSchemaConstructor();
        List<SchemaType> constituents = List.of();
        if (ctor.getProductType() != null) {
            constituents = ctor.getProductType().getSchemaTypeList();
        } else if (ctor.getCollectionType() != null) {
            constituents = ctor.getCollectionType().getSchemaTypeList();
        } else if (ctor.getSumType() != null) {
            constituents = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        }

        int slotIndex = 1;
        for (var constituent : constituents) {
            var kw = constituent.getTypeKeyword();
            String fieldLabel = (constituent.getMetadataMap() != null)
                    ? constituent.getText().trim()
                    : (kw != null ? kw.getText().trim() : constituent.getText().trim());

            if (kw != null) {
                var name = kw.getText().trim();
                if (StvnVocabulary.TYPE_FLOAT.equals(name)) {
                    boolean exact = hasExactFacet(constituent.getMetadataMap());
                    if (!exact) {
                        result.add(new ConstituentInfo(slotIndex, fieldLabel, constituent));
                    }
                    slotIndex++;
                    continue;
                } else if (StvnVocabulary.TYPE_INT.equals(name) || StvnVocabulary.TYPE_STRING.equals(name) || StvnVocabulary.TYPE_BOOLEAN.equals(name)
                        || StvnVocabulary.TYPE_TIME_EPOCH.equals(name) || StvnVocabulary.TYPE_DATE_TIME.equals(name)) {
                    slotIndex++;
                    continue;
                }

                if (file != null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, name, new java.util.HashSet<>());
                    var targetDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                    if (targetDef != null) {
                        var rs = StvnTypeResolver.resolveNominalResolvedSchema(targetDef);
                        if (rs != null && !rs.constraints().equatable().orElse(false)) {
                            result.add(new ConstituentInfo(slotIndex, fieldLabel, constituent));
                        }
                    }
                }
            } else if (constituent.getSchemaConstructor() != null) {
                var txt = constituent.getText();
                if (txt.contains(StvnVocabulary.TYPE_FLOAT) && !txt.contains("#exact")) {
                    result.add(new ConstituentInfo(slotIndex, fieldLabel, constituent));
                }
            }
            slotIndex++;
        }
        return result;
    }

    public static @Nullable ConstituentInfo findFirstNonEquatableConstituent(TypeDefinition typeDef) {
        var nonConforming = extractNonConformingConstituents(typeDef);
        return nonConforming.isEmpty() ? null : nonConforming.get(0);
    }

    public static boolean isUnderlyingIntrinsicallyEquatable(TypeDefinition typeDef) {
        return isUnderlyingIntrinsicallyEquatable(typeDef, new java.util.HashSet<>());
    }

    public static boolean isUnderlyingIntrinsicallyEquatable(TypeDefinition typeDef, java.util.Set<String> visited) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) return false;

        var kw = typeDef.getTypeKeyword();
        if (kw != null && !visited.add(kw.getText().trim())) {
            return true; // Break recursion on cycle
        }

        var schemaKw = schemaType.getTypeKeyword();
        if (schemaKw != null) {
            var name = schemaKw.getText().trim();
            if (StvnVocabulary.TYPE_FLOAT.equals(name)) {
                return hasExactFacet(schemaType.getMetadataMap());
            }
            if (StvnVocabulary.TYPE_INT.equals(name) || StvnVocabulary.TYPE_STRING.equals(name)
                    || StvnVocabulary.TYPE_BOOLEAN.equals(name) || StvnVocabulary.TYPE_TIME_EPOCH.equals(name)
                    || StvnVocabulary.TYPE_DATE_TIME.equals(name) || StvnVocabulary.TYPE_ENUM.equals(name)) {
                return true;
            }
            var file = typeDef.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, name, new java.util.HashSet<>());
                var aliasDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                if (aliasDef != null && aliasDef != typeDef) {
                    return isUnderlyingIntrinsicallyEquatable(aliasDef, visited);
                }
            }
        }
        return findFirstNonEquatableConstituent(typeDef) == null;
    }

    private static boolean hasExactFacet(@Nullable MetadataMap map) {
        if (map == null) return false;
        for (var entry : map.getMetadataEntryList()) {
            if (entry.getText().trim().startsWith("#exact")) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable MetadataEntry findExplicitTraitEntry(TypeDefinition typeDef, String facetKw) {
        var map = typeDef.getMetadataMap();
        if (map == null && typeDef.getSchemaType() != null) {
            map = typeDef.getSchemaType().getMetadataMap();
        }
        if (map == null) return null;
        for (var entry : map.getMetadataEntryList()) {
            if (entry.getText().trim().startsWith(facetKw)) {
                return entry;
            }
        }
        return null;
    }

    public static boolean isExplicitFalse(MetadataEntry entry) {
        var txt = entry.getText().trim();
        var parts = txt.split("\\s+");
        if (parts.length > 1) {
            var val = parts[1].trim();
            return "#FALSE".equalsIgnoreCase(val) || "#F".equalsIgnoreCase(val);
        }
        return false;
    }

    // --- INTENTION: Navigate to Explicit Override ---
    public static final class NavigateToExplicitOverrideIntention extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        private final String typeName;

        public NavigateToExplicitOverrideIntention(PsiElement element, String typeName) {
            super(element);
            this.typeName = typeName;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Navigate to explicit '#equatable #FALSE' declaration on " + typeName;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Navigate to trait declaration";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            var vFile = StvnNavigationUtil.resolveVirtualFile(startElement);
            if (vFile == null) return;
            var descriptor = new OpenFileDescriptor(project, vFile, startElement.getTextOffset());
            var ed = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
            if (ed != null) {
                var range = startElement.getTextRange();
                ed.getCaretModel().moveToOffset(range.getStartOffset());
                ed.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset());
                ed.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            }
        }
    }

    // --- INTENTION: Jump to Non-Conforming Constituent ---
    public static final class JumpToNonConformingConstituentIntention extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        private final String typeName;
        private final int slotIndex;
        private final String fieldLabel;

        public JumpToNonConformingConstituentIntention(
                PsiElement element,
                String typeName,
                int slotIndex,
                String fieldLabel
        ) {
            super(element);
            this.typeName = typeName;
            this.slotIndex = slotIndex;
            this.fieldLabel = fieldLabel;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Jump to non-equatable constituent in " + typeName + " (Field #" + slotIndex + ": " + fieldLabel + ")";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Navigate to non-conforming constituent";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            var vFile = StvnNavigationUtil.resolveVirtualFile(startElement);
            if (vFile == null) return;
            var descriptor = new OpenFileDescriptor(project, vFile, startElement.getTextOffset());
            var ed = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
            if (ed != null) {
                ed.getCaretModel().moveToOffset(startElement.getTextOffset());
                ed.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            }
        }
    }

    // --- QUICK-FIX 1: Remove Incompatible Override ---
    public static final class RemoveIncompatibleOverrideQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
        private final String typeName;

        public RemoveIncompatibleOverrideQuickFix(PsiElement element, String typeName) {
            super(element);
            this.typeName = typeName;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Remove explicit '#equatable #FALSE' override from " + typeName;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Remove trait override";
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
            if (entry == null || !entry.isValid()) return;
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
                    postProcessFileMutation(project, file, targetFile);
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
            postProcessFileMutation(project, file, targetFile);
        }
    }

    // --- QUICK-FIX 2: Change Nominal Override in :defs ---
    public static final class ChangeNominalOverrideQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
        private final String typeName;

        public ChangeNominalOverrideQuickFix(TypeDefinition typeDef, String typeName) {
            super(typeDef);
            this.typeName = typeName;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Override '#equatable #TRUE' on " + typeName + " in :defs";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Override trait in definition";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            if (!(startElement instanceof TypeDefinition typeDef) || !typeDef.isValid()) return;
            var targetFile = typeDef.getContainingFile();

            var entry = findExplicitTraitEntry(typeDef, StvnVocabulary.FACET_KW_EQUATABLE);
            if (entry != null && entry.isValid()) {
                var newEntry = StvnElementFactory.createMetadataEntry(project, "#equatable #TRUE");
                entry.replace(newEntry);
                postProcessFileMutation(project, file, targetFile);
                return;
            }

            var map = typeDef.getMetadataMap();
            if (map == null && typeDef.getSchemaType() != null) {
                map = typeDef.getSchemaType().getMetadataMap();
            }

            if (map != null && map.isValid()) {
                var inner = map.getText().trim();
                if (inner.startsWith("{") && inner.endsWith("}")) {
                    inner = inner.substring(1, inner.length() - 1).trim();
                }
                var newMap = StvnElementFactory.createMetadataMap(project, "#equatable #TRUE " + inner);
                map.replace(newMap);
            } else if (typeDef.getSchemaType() != null) {
                var newSchema = StvnElementFactory.createSchemaType(
                        project, "{ #equatable #TRUE } " + typeDef.getSchemaType().getText());
                typeDef.getSchemaType().replace(newSchema);
            }
            postProcessFileMutation(project, file, targetFile);
        }
    }

    @SuppressWarnings("deprecation")
    public static void postProcessFileMutation(Project project, PsiFile consumerFile, @Nullable PsiFile targetFile) {
        if (IntentionPreviewUtils.isIntentionPreviewActive() || !consumerFile.isPhysical()) {
            return;
        }
        if (targetFile != null && targetFile != consumerFile && targetFile.isPhysical()) {
            var psiDocManager = PsiDocumentManager.getInstance(project);
            var targetDoc = psiDocManager.getDocument(targetFile);
            if (targetDoc != null) {
                psiDocManager.doPostponedOperationsAndUnblockDocument(targetDoc);
                FileDocumentManager.getInstance().saveDocument(targetDoc);
            }
            DaemonCodeAnalyzer.getInstance(project).restart(consumerFile);
        }
    }
}
