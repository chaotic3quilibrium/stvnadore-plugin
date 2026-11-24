package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
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
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.navigation.StvnNavigationUtil;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.RemoveRedundantFacetQuickFix;
import org.stvnadore.plugin.validation.StvnMetadataFacetInsertionUtil;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provides diagnostic navigation intentions and paired healing quick-fixes
 * for STVN trait violation errors (ERR_TRAIT_VIOLATION).
 */
@NullMarked
public final class StvnTraitViolationQuickFixProvider {

    private StvnTraitViolationQuickFixProvider() {
    }

    /**
     * Disjoint silent {@code INFORMATION} anchor descriptor exposing local remediation actions
     * on a conflicting {@code #equatable #FALSE} metadata entry.
     *
     * @param partnerRange text range of the partner {@code #equatable #FALSE} entry
     * @param actions      ordered local quick-fixes to attach onto the partner range
     */
    public record PartnerTraitAnchor(TextRange partnerRange, List<IntentionAction> actions) {
    }

    /**
     * Result of registering trait-violation quick-fixes onto an {@link AnnotationBuilder},
     * optionally carrying a disjoint {@link PartnerTraitAnchor}.
     *
     * @param builder       updated annotation builder
     * @param partnerAnchor optional disjoint partner anchor descriptor, or {@code null}
     */
    public record TraitQuickFixRegistration(
            AnnotationBuilder builder,
            @Nullable PartnerTraitAnchor partnerAnchor
    ) {
    }

    /**
     * Callback invoked on a cloned remote {@link PsiFile} to render cross-file intention previews.
     */
    @FunctionalInterface
    public interface CrossFileCopyMutator {
        /**
         * Applies a quick-fix mutation onto {@code copyElement} inside {@code remoteCopy}.
         *
         * @param project     active project
         * @param remoteCopy  non-physical clone of the remote file
         * @param copyElement corresponding PSI element inside {@code remoteCopy}
         */
        void mutate(Project project, PsiFile remoteCopy, PsiElement copyElement);
    }

    /**
     * Generates a safe {@link IntentionPreviewInfo.CustomDiff} preview when {@code startElement}
     * resides in an included module distinct from {@code activeFile}, returning {@code null}
     * for same-file edits so standard {@code DIFF} preview executes.
     *
     * @param project      active project
     * @param activeFile   file open in the active editor or preview session
     * @param startElement target PSI element held by the quick-fix
     * @param mutator      mutation callback executed on a cloned copy of the remote file
     * @return cross-file {@link IntentionPreviewInfo.CustomDiff}, or {@code null} when same-file
     */
    public static @Nullable IntentionPreviewInfo tryGenerateCrossFilePreview(
            Project project,
            PsiFile activeFile,
            @Nullable PsiElement startElement,
            CrossFileCopyMutator mutator
    ) {
        if (startElement == null || !startElement.isValid()) {
            return null;
        }
        PsiFile remoteFile = startElement.getContainingFile();
        if (remoteFile == null || remoteFile.getOriginalFile() == activeFile.getOriginalFile()) {
            return null;
        }
        String beforeText = remoteFile.getText();
        PsiFile remoteCopy = (PsiFile) remoteFile.copy();
        PsiElement copyElem = PsiTreeUtil.findSameElementInCopy(startElement, remoteCopy);
        mutator.mutate(project, remoteCopy, copyElem);
        return new IntentionPreviewInfo.CustomDiff(
            remoteFile.getFileType(),
            remoteFile.getName(),
            beforeText,
            remoteCopy.getText()
        );
    }

    /**
     * Inspects a trait violation diagnostic, resolves root causes in {@code :defs},
     * and attaches appropriate navigation intentions and paired quick-fixes.
     *
     * @param builder annotation builder receiving quick-fixes
     * @param file    active PSI file
     * @param range   diagnostic text range
     * @param message formatted or raw diagnostic message
     * @return updated annotation builder
     */
    public static AnnotationBuilder registerActions(
            AnnotationBuilder builder,
            PsiFile file,
            TextRange range,
            String message
    ) {
        return registerActionsWithPartner(builder, file, range, message).builder();
    }

    /**
     * Inspects a trait violation diagnostic, attaches local and constituent quick-fixes onto
     * {@code builder}, and returns an optional disjoint {@link PartnerTraitAnchor} when a
     * conflicting {@code #equatable #FALSE} entry co-exists in the same {@link MetadataMap}.
     *
     * @param builder annotation builder receiving quick-fixes
     * @param file    active PSI file
     * @param range   diagnostic text range
     * @param message formatted or raw diagnostic message
     * @return registration result containing the updated builder and optional partner anchor
     */
    public static TraitQuickFixRegistration registerActionsWithPartner(
            AnnotationBuilder builder,
            PsiFile file,
            TextRange range,
            String message
    ) {
        var elemAtRange = file.findElementAt(range.getStartOffset());
        if (elemAtRange == null && range.getStartOffset() > 0) {
            elemAtRange = file.findElementAt(range.getStartOffset() - 1);
        }
        if (elemAtRange == null) {
            return new TraitQuickFixRegistration(builder, null);
        }

        // Target 2: Explicit '#comparable #TRUE' without '#equatable #TRUE'
        if (isComparableRequiresEquatableMessage(message)) {
            return registerComparableRequiresEquatableActions(builder, file, range, elemAtRange, message);
        }

        // Target 1A: Inverted Map Values Require Types to be #equatable #TRUE
        String invMapPrefix = "Inverted map values require types to be "
            + StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE;
        if (message.contains(invMapPrefix)) {
            return new TraitQuickFixRegistration(
                registerInvertibleMapActions(builder, file, range, elemAtRange, message),
                null
            );
        }

        // Target 1B: Set Elements or Map Keys (or Nominal Overrides) Require #equatable #TRUE
        SchemaType offendingSlotSchema = resolveExactSchemaTypeForRange(file, range, elemAtRange);
        if (offendingSlotSchema != null) {
            return new TraitQuickFixRegistration(
                registerContainerSlotEquatableActions(
                    builder,
                    file,
                    offendingSlotSchema,
                    message,
                    PriorityAction.Priority.HIGH,
                    PriorityAction.Priority.NORMAL
                ),
                null
            );
        }

        return new TraitQuickFixRegistration(builder, null);
    }

    private static boolean isComparableRequiresEquatableMessage(String message) {
        return message.contains("requires '" + StvnVocabulary.FACET_KW_EQUATABLE)
            || (message.contains(StvnVocabulary.FACET_KW_COMPARABLE)
                && message.contains("requires")
                && message.contains(StvnVocabulary.FACET_KW_EQUATABLE));
    }

    private static @Nullable SchemaType resolveExactSchemaTypeForRange(
            PsiFile file,
            TextRange range,
            PsiElement elemAtRange
    ) {
        SchemaType exactMatch = null;
        SchemaType nearestSchema = null;
        PsiElement curr = elemAtRange;
        while (curr != null && !(curr instanceof PsiFile)) {
            if (curr instanceof SchemaType st) {
                if (nearestSchema == null) {
                    nearestSchema = st;
                }
                if (st.getTextRange().equals(range)) {
                    exactMatch = st;
                }
            }
            curr = curr.getParent();
        }
        if (exactMatch != null) {
            return exactMatch;
        }
        if (nearestSchema != null) {
            return nearestSchema;
        }
        var coll = PsiTreeUtil.getParentOfType(elemAtRange, CollectionType.class, false);
        if (coll != null && !coll.getSchemaTypeList().isEmpty()) {
            return coll.getSchemaTypeList().get(0);
        }
        return null;
    }

    private static AnnotationBuilder registerInvertibleMapActions(
            AnnotationBuilder builder,
            PsiFile file,
            TextRange range,
            PsiElement elemAtRange,
            String message
    ) {
        var invertibleEntry = findInvertibleEntry(elemAtRange);
        var containerName = (invertibleEntry != null)
                ? resolveContainerName(invertibleEntry)
                : StvnVocabulary.TYPE_MAP;

        // Subcase 1: Diagnostic or caret is directly inside #invertible or its enclosing MetadataMap
        if (invertibleEntry != null && isCaretInsideInvertibleMetadata(elemAtRange, invertibleEntry)) {
            if (file.isWritable()
                    && invertibleEntry.getContainingFile().isWritable()
                    && !StvnPreludeBridge.isPreludeFile(invertibleEntry.getContainingFile())) {
                builder = builder.withFix(new RemoveInvertibleFacetQuickFix(
                    invertibleEntry, containerName, PriorityAction.Priority.HIGH));
            }
            return builder;
        }

        // Subcase 2: Offending value SchemaType inside { #invertible } :Map(K V) or :type :SharedInvMap reference
        SchemaType offendingSlotSchema = resolveExactSchemaTypeForRange(file, range, elemAtRange);
        if (offendingSlotSchema != null) {
            var directKw = offendingSlotSchema.getTypeKeyword();
            if (directKw != null) {
                var typeName = directKw.getText().trim();
                var targetTypeDef = resolveTargetTypeDefForKeyword(directKw, file, typeName);
                var ownerMapDef = invertibleEntry != null
                    ? resolveDirectOwnerTypeDef(PsiTreeUtil.getParentOfType(invertibleEntry, MetadataMap.class, false))
                    : null;
                if (targetTypeDef != null && targetTypeDef == ownerMapDef) {
                    var nonConforming = extractNonConformingConstituents(targetTypeDef);
                    for (var constituent : nonConforming) {
                        builder = builder.withFix(new JumpToNonConformingConstituentIntention(
                            constituent.schema(), typeName, constituent.slotRoleLabel(), constituent.label()));
                    }
                    if (invertibleEntry != null
                            && file.isWritable()
                            && invertibleEntry.getContainingFile().isWritable()
                            && !StvnPreludeBridge.isPreludeFile(invertibleEntry.getContainingFile())) {
                        builder = builder.withFix(new RemoveInvertibleFacetQuickFix(
                            invertibleEntry, containerName, PriorityAction.Priority.NORMAL));
                    }
                    return builder;
                }
            }
            builder = registerContainerSlotEquatableActions(
                builder,
                file,
                offendingSlotSchema,
                message,
                PriorityAction.Priority.HIGH,
                PriorityAction.Priority.NORMAL
            );
        }

        if (invertibleEntry != null
                && file.isWritable()
                && invertibleEntry.getContainingFile().isWritable()
                && !StvnPreludeBridge.isPreludeFile(invertibleEntry.getContainingFile())) {
            builder = builder.withFix(new RemoveInvertibleFacetQuickFix(
                invertibleEntry, containerName, PriorityAction.Priority.NORMAL));
        }

        return builder;
    }

    private static AnnotationBuilder registerContainerSlotEquatableActions(
            AnnotationBuilder builder,
            PsiFile file,
            SchemaType slotSchema,
            String message,
            PriorityAction.Priority primaryPriority,
            PriorityAction.Priority overridePriority
    ) {
        // 1. Direct inline continuous :Float (e.g., :Set(:Float) or :Map(:Float :Int))
        if (isDirectContinuousFloatSchema(slotSchema)) {
            boolean incompatibleWithExact = hasDiscreteIncompatibleFloatBound(slotSchema.getMetadataMap());
            if (file.isWritable() && slotSchema.getContainingFile().isWritable()) {
                if (!incompatibleWithExact) {
                    builder = builder.withFix(new AddExactConstraintQuickFix(slotSchema, null, primaryPriority));
                }
                builder = builder.withFix(new RestoreInlineSchemaEquatableQuickFix(
                    slotSchema,
                    StvnVocabulary.TYPE_FLOAT,
                    incompatibleWithExact ? primaryPriority : overridePriority
                ));
            }
            return builder;
        }

        // 2. Direct inline explicit '#equatable #FALSE' override on slotSchema
        var localEqEntry = findExplicitTraitEntryInMap(slotSchema.getMetadataMap(), StvnVocabulary.FACET_KW_EQUATABLE);
        if (localEqEntry != null && isExplicitFalse(localEqEntry)) {
            if (file.isWritable() && slotSchema.getContainingFile().isWritable()) {
                String snippet = extractAuthoredFacetSnippet(localEqEntry);
                boolean underlyingEquatable = isSchemaTypeEquatableInternal(
                    slotSchema,
                    hasExactFacet(slotSchema.getMetadataMap()),
                    hasInvertibleFacet(slotSchema.getMetadataMap()),
                    new HashSet<>(),
                    true
                );
                if (underlyingEquatable) {
                    builder = builder.withFix(RemoveRedundantFacetQuickFix.createRemoveConflictingFacetFix(
                        localEqEntry, snippet, primaryPriority));
                } else if (slotSchema.getMetadataMap() != null) {
                    builder = builder.withFix(new AddOrChangeLocalTraitFacetQuickFix(
                        slotSchema.getMetadataMap(),
                        null,
                        StvnVocabulary.FACET_KW_EQUATABLE,
                        StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE,
                        snippet,
                        primaryPriority
                    ));
                }
            }
            return builder;
        }

        // 3. Direct nominal TypeKeyword reference (e.g., :Set(:RawLidarSlope) or :Set(:Values))
        var directKw = slotSchema.getTypeKeyword();
        if (directKw != null) {
            if (isSchemaTypeEquatable(slotSchema, new HashSet<>())) {
                return builder;
            }
            String rawName = directKw.getText().trim();
            if (StvnVocabulary.TYPE_FLOAT.equals(rawName)) {
                if (file.isWritable() && slotSchema.getContainingFile().isWritable()) {
                    if (!hasDiscreteIncompatibleFloatBound(slotSchema.getMetadataMap())) {
                        builder = builder.withFix(new AddExactConstraintQuickFix(slotSchema, null, primaryPriority));
                    }
                    builder = builder.withFix(new RestoreInlineSchemaEquatableQuickFix(
                        slotSchema, StvnVocabulary.TYPE_FLOAT, overridePriority));
                }
                return builder;
            }
            var targetTypeDef = resolveTargetTypeDefForKeyword(directKw, file, rawName);
            if (targetTypeDef != null) {
                return registerActionsForNominalType(
                    builder, file, targetTypeDef, rawName, message, primaryPriority, overridePriority);
            }
            return builder;
        }

        // 4. Anonymous inline composite/coproduct constructor (e.g., :Set(:Union(:AltitudeCm :RawLidarSlope)))
        if (slotSchema.getSchemaConstructor() != null) {
            if (file.isWritable()
                    && slotSchema.getContainingFile().isWritable()
                    && canRestoreInlineWrapperEquatable(slotSchema)) {
                String wrapperHead = resolveInlineWrapperHead(slotSchema);
                if (wrapperHead != null) {
                    builder = builder.withFix(new RestoreInlineSchemaEquatableQuickFix(
                        slotSchema, wrapperHead, primaryPriority));
                }
            }
            var targets = new NonEquatableTargetCollector();
            collectNonEquatableConstituentTargets(slotSchema, null, new HashSet<>(), 0, targets);
            builder = attachCollectedConstituentFixes(
                builder, file, message, targets, primaryPriority, overridePriority);
        }

        return builder;
    }

    private static TraitQuickFixRegistration registerComparableRequiresEquatableActions(
            AnnotationBuilder builder,
            PsiFile file,
            TextRange range,
            PsiElement elemAtRange,
            String message
    ) {
        MetadataEntry compEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(elemAtRange);
        MetadataMap metaMap = compEntry != null
            ? PsiTreeUtil.getParentOfType(compEntry, MetadataMap.class, false)
            : PsiTreeUtil.getParentOfType(elemAtRange, MetadataMap.class, false);
        if (metaMap == null) {
            return new TraitQuickFixRegistration(builder, null);
        }
        if (compEntry == null) {
            compEntry = findExplicitTraitEntryInMap(metaMap, StvnVocabulary.FACET_KW_COMPARABLE);
        }

        TypeDefinition ownerTypeDef = resolveDirectOwnerTypeDef(metaMap);
        String ownerName = ownerTypeDef != null && ownerTypeDef.getTypeKeyword() != null
            ? ownerTypeDef.getTypeKeyword().getText().trim()
            : null;
        SchemaType governedSchema = resolveGovernedSchemaType(metaMap, ownerTypeDef);
        MetadataEntry localEqEntry = findExplicitTraitEntryInMap(metaMap, StvnVocabulary.FACET_KW_EQUATABLE);
        if (localEqEntry == null && ownerTypeDef != null) {
            localEqEntry = findExplicitTraitEntry(ownerTypeDef, StvnVocabulary.FACET_KW_EQUATABLE);
        }

        List<IntentionAction> localActions = new ArrayList<>();
        if (file.isWritable()
                && metaMap.getContainingFile().isWritable()
                && !StvnPreludeBridge.isPreludeFile(metaMap.getContainingFile())) {
            // 1. Primary Local Fix (Priority.TOP)
            if (localEqEntry == null) {
                localActions.add(new AddOrChangeLocalTraitFacetQuickFix(
                    metaMap,
                    ownerName,
                    StvnVocabulary.FACET_KW_EQUATABLE,
                    StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE,
                    null,
                    PriorityAction.Priority.TOP
                ));
            } else if (isExplicitFalse(localEqEntry)) {
                String eqSnippet = extractAuthoredFacetSnippet(localEqEntry);
                boolean underlyingEquatable = isUnderlyingEquatableIgnoringLocalMap(
                    ownerTypeDef, governedSchema, metaMap);
                if (underlyingEquatable) {
                    localActions.add(RemoveRedundantFacetQuickFix.createRemoveConflictingFacetFix(
                        localEqEntry, eqSnippet, PriorityAction.Priority.TOP));
                } else {
                    localActions.add(new AddOrChangeLocalTraitFacetQuickFix(
                        metaMap,
                        ownerName,
                        StvnVocabulary.FACET_KW_EQUATABLE,
                        StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE,
                        eqSnippet,
                        PriorityAction.Priority.TOP
                    ));
                }
            }

            // 2. Secondary Local Fix (Priority.HIGH)
            if (compEntry != null) {
                String compSnippet = extractAuthoredFacetSnippet(compEntry);
                localActions.add(RemoveRedundantFacetQuickFix.createRemoveFacetFix(
                    compEntry, compSnippet, PriorityAction.Priority.HIGH));
            }
        }

        for (IntentionAction action : localActions) {
            builder = builder.withFix(action);
        }

        // 3. Constituent Fixes (Priority.NORMAL / Priority.LOW) when local #equatable #FALSE is NOT locking it off
        if (localEqEntry == null && governedSchema != null) {
            if (governedSchema.getTypeKeyword() != null) {
                var kw = governedSchema.getTypeKeyword();
                String rawName = kw.getText().trim();
                var targetDef = resolveTargetTypeDefForKeyword(kw, file, rawName);
                if (targetDef != null && !isSchemaTypeEquatable(governedSchema, new HashSet<>())) {
                    builder = registerActionsForNominalType(
                        builder,
                        file,
                        targetDef,
                        rawName,
                        message,
                        PriorityAction.Priority.NORMAL,
                        PriorityAction.Priority.LOW
                    );
                }
            } else if (governedSchema.getSchemaConstructor() != null) {
                var targets = new NonEquatableTargetCollector();
                collectNonEquatableConstituentTargetsFromConstructor(
                    governedSchema.getSchemaConstructor(),
                    hasInvertibleFacet(metaMap)
                        || (ownerTypeDef != null && hasInvertibleInLineage(ownerTypeDef, governedSchema)),
                    new HashSet<>(),
                    0,
                    targets
                );
                builder = attachCollectedConstituentFixes(
                    builder,
                    file,
                    message,
                    targets,
                    PriorityAction.Priority.NORMAL,
                    PriorityAction.Priority.LOW
                );
            }
        }

        PartnerTraitAnchor partnerAnchor = null;
        if (localEqEntry != null
                && !localActions.isEmpty()
                && !localEqEntry.getTextRange().intersects(range)) {
            partnerAnchor = new PartnerTraitAnchor(
                localEqEntry.getTextRange(),
                List.copyOf(localActions)
            );
        }
        return new TraitQuickFixRegistration(builder, partnerAnchor);
    }

    private static final class NonEquatableTargetCollector {
        final Map<TypeDefinition, String> nominalTargets = new LinkedHashMap<>();
        final Map<SchemaType, String> inlineFloatLeaves = new LinkedHashMap<>();
        final Map<SchemaType, MetadataEntry> nestedExplicitFalseSchemas = new LinkedHashMap<>();
    }

    private static void collectNonEquatableConstituentTargets(
            SchemaType schema,
            @Nullable String slotRoleLabel,
            Set<String> visited,
            int depth,
            NonEquatableTargetCollector sink
    ) {
        if (depth > MAX_CONSTITUENT_DESCENT_DEPTH || isSchemaTypeEquatable(schema, new HashSet<>(visited))) {
            return;
        }
        if (isDirectContinuousFloatSchema(schema)) {
            sink.inlineFloatLeaves.putIfAbsent(schema, slotRoleLabel != null ? slotRoleLabel : "");
            return;
        }
        var localEq = findExplicitTraitEntryInMap(schema.getMetadataMap(), StvnVocabulary.FACET_KW_EQUATABLE);
        if (localEq != null && isExplicitFalse(localEq)) {
            sink.nestedExplicitFalseSchemas.putIfAbsent(schema, localEq);
            return;
        }
        var kw = schema.getTypeKeyword();
        if (kw != null) {
            String rawName = kw.getText().trim();
            if (StvnVocabulary.TYPE_FLOAT.equals(rawName)) {
                sink.inlineFloatLeaves.putIfAbsent(schema, slotRoleLabel != null ? slotRoleLabel : "");
                return;
            }
            PsiFile kwFile = schema.getContainingFile();
            if (kwFile != null) {
                TypeDefinition targetDef = resolveTargetTypeDefForKeyword(kw, kwFile, rawName);
                if (targetDef != null) {
                    sink.nominalTargets.putIfAbsent(targetDef, rawName);
                }
            }
            return;
        }
        var ctor = schema.getSchemaConstructor();
        if (ctor != null) {
            collectNonEquatableConstituentTargetsFromConstructor(
                ctor,
                hasInvertibleFacet(schema.getMetadataMap()),
                visited,
                depth + 1,
                sink
            );
        }
    }

    private static void collectNonEquatableConstituentTargetsFromConstructor(
            SchemaConstructor ctor,
            boolean invertibleActive,
            Set<String> visited,
            int depth,
            NonEquatableTargetCollector sink
    ) {
        if (depth > MAX_CONSTITUENT_DESCENT_DEPTH) {
            return;
        }
        if (ctor.getCollectionType() != null) {
            var coll = ctor.getCollectionType();
            var first = coll.getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            var inner = coll.getSchemaTypeList();
            if (StvnVocabulary.TYPE_MAP.equals(collKind)) {
                if (!inner.isEmpty() && !isSchemaTypeEquatable(inner.get(0), new HashSet<>(visited))) {
                    collectNonEquatableConstituentTargets(inner.get(0), "Key", visited, depth, sink);
                }
                if (inner.size() >= 2
                        && invertibleActive
                        && !isSchemaTypeEquatable(inner.get(1), new HashSet<>(visited))) {
                    collectNonEquatableConstituentTargets(inner.get(1), "Value", visited, depth, sink);
                }
                return;
            }
            if (inner.size() == 1 && !isSchemaTypeEquatable(inner.get(0), new HashSet<>(visited))) {
                collectNonEquatableConstituentTargets(inner.get(0), "Element", visited, depth, sink);
            }
            return;
        }
        List<SchemaType> children = collectConstructorConstituents(ctor);
        int idx = 1;
        for (SchemaType child : children) {
            if (!isSchemaTypeEquatable(child, new HashSet<>(visited))) {
                String role = resolveConstituentSlotRoleLabel(ctor, idx);
                collectNonEquatableConstituentTargets(child, role, visited, depth, sink);
            }
            idx++;
        }
    }

    private static AnnotationBuilder attachCollectedConstituentFixes(
            AnnotationBuilder builder,
            PsiFile file,
            String message,
            NonEquatableTargetCollector targets,
            PriorityAction.Priority primaryPriority,
            PriorityAction.Priority overridePriority
    ) {
        for (var entry : targets.nominalTargets.entrySet()) {
            builder = registerActionsForNominalType(
                builder,
                file,
                entry.getKey(),
                entry.getValue(),
                message,
                primaryPriority,
                overridePriority
            );
        }
        boolean disambiguateFloats = targets.inlineFloatLeaves.size() > 1;
        for (var entry : targets.inlineFloatLeaves.entrySet()) {
            SchemaType floatLeaf = entry.getKey();
            String qualifier = disambiguateFloats ? entry.getValue() : null;
            boolean incompatibleWithExact = hasDiscreteIncompatibleFloatBound(floatLeaf.getMetadataMap());
            if (file.isWritable() && floatLeaf.getContainingFile().isWritable()) {
                if (!incompatibleWithExact) {
                    builder = builder.withFix(new AddExactConstraintQuickFix(floatLeaf, qualifier, primaryPriority));
                } else {
                    builder = builder.withFix(new RestoreInlineSchemaEquatableQuickFix(
                        floatLeaf, StvnVocabulary.TYPE_FLOAT, primaryPriority));
                }
            }
        }
        for (var entry : targets.nestedExplicitFalseSchemas.entrySet()) {
            SchemaType nestedSchema = entry.getKey();
            MetadataEntry eqEntry = entry.getValue();
            if (file.isWritable() && nestedSchema.getContainingFile().isWritable()) {
                String snippet = extractAuthoredFacetSnippet(eqEntry);
                boolean underlyingEquatable = isSchemaTypeEquatableInternal(
                    nestedSchema,
                    hasExactFacet(nestedSchema.getMetadataMap()),
                    hasInvertibleFacet(nestedSchema.getMetadataMap()),
                    new HashSet<>(),
                    true
                );
                if (underlyingEquatable) {
                    builder = builder.withFix(RemoveRedundantFacetQuickFix.createRemoveConflictingFacetFix(
                        eqEntry, snippet, primaryPriority));
                } else if (nestedSchema.getMetadataMap() != null) {
                    builder = builder.withFix(new AddOrChangeLocalTraitFacetQuickFix(
                        nestedSchema.getMetadataMap(),
                        null,
                        StvnVocabulary.FACET_KW_EQUATABLE,
                        StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE,
                        snippet,
                        primaryPriority
                    ));
                }
            }
        }
        return builder;
    }

    private static boolean canRestoreInlineWrapperEquatable(SchemaType slotSchema) {
        if (slotSchema.getTypeKeyword() != null) {
            return false;
        }
        if (resolveExplicitBooleanTrait(slotSchema.getMetadataMap(), StvnVocabulary.FACET_KW_EQUATABLE).isPresent()) {
            return false;
        }
        var ctor = slotSchema.getSchemaConstructor();
        if (ctor == null) {
            return false;
        }
        if (ctor.getProductType() != null) {
            return !hasNestedIntrinsicContainerViolation(slotSchema, 0);
        }
        if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() == null) {
            return !hasNestedIntrinsicContainerViolation(slotSchema, 0);
        }
        if (ctor.getCollectionType() != null) {
            var first = ctor.getCollectionType().getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            if (StvnVocabulary.TYPE_SEQ.equals(collKind)) {
                return !hasNestedIntrinsicContainerViolation(slotSchema, 0);
            }
        }
        return false;
    }

    private static boolean hasNestedIntrinsicContainerViolation(SchemaType schema, int depth) {
        if (depth > MAX_CONSTITUENT_DESCENT_DEPTH || isSchemaTypeEquatable(schema, new HashSet<>())) {
            return false;
        }
        if (schema.getTypeKeyword() != null) {
            PsiFile file = schema.getContainingFile();
            if (file == null) {
                return false;
            }
            String rawName = schema.getTypeKeyword().getText().trim();
            TypeDefinition td = resolveTargetTypeDefForKeyword(schema.getTypeKeyword(), file, rawName);
            if (td != null) {
                StvnSchema ir = StvnTypeResolver.resolveNominalIrSchema(td);
                return ir instanceof StvnSchema.PoisonedSchema;
            }
            return false;
        }
        var ctor = schema.getSchemaConstructor();
        if (ctor == null || ctor.getAtomicType() != null) {
            return false;
        }
        if (ctor.getCollectionType() != null) {
            var coll = ctor.getCollectionType();
            var first = coll.getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            var inner = coll.getSchemaTypeList();
            if (StvnVocabulary.TYPE_SET.equals(collKind)) {
                return inner.isEmpty() || !isSchemaTypeEquatable(inner.get(0), new HashSet<>());
            }
            if (StvnVocabulary.TYPE_MAP.equals(collKind)) {
                if (inner.size() < 2 || !isSchemaTypeEquatable(inner.get(0), new HashSet<>())) {
                    return true;
                }
                if (hasInvertibleFacet(schema.getMetadataMap())
                        && !isSchemaTypeEquatable(inner.get(1), new HashSet<>())) {
                    return true;
                }
                return false;
            }
            return inner.size() == 1 && hasNestedIntrinsicContainerViolation(inner.get(0), depth + 1);
        }
        for (SchemaType child : collectConstructorConstituents(ctor)) {
            if (!isSchemaTypeEquatable(child, new HashSet<>())
                    && hasNestedIntrinsicContainerViolation(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String resolveInlineWrapperHead(SchemaType slotSchema) {
        var ctor = slotSchema.getSchemaConstructor();
        if (ctor == null) {
            return null;
        }
        if (ctor.getProductType() != null) {
            return StvnVocabulary.TYPE_TUPLE;
        }
        if (ctor.getSumType() != null && ctor.getSumType().getFirstChild() != null) {
            return ctor.getSumType().getFirstChild().getText().trim();
        }
        if (ctor.getCollectionType() != null && ctor.getCollectionType().getFirstChild() != null) {
            return ctor.getCollectionType().getFirstChild().getText().trim();
        }
        return null;
    }

    private static @Nullable TypeDefinition resolveDirectOwnerTypeDef(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return null;
        }
        PsiElement parent = metaMap.getParent();
        if (parent instanceof TypeDefinition td) {
            return td;
        }
        if (parent instanceof SchemaType st && st.getParent() instanceof TypeDefinition td) {
            return td;
        }
        return null;
    }

    private static @Nullable SchemaType resolveGovernedSchemaType(
            MetadataMap metaMap,
            @Nullable TypeDefinition ownerTypeDef
    ) {
        if (ownerTypeDef != null && ownerTypeDef.getSchemaType() != null) {
            return ownerTypeDef.getSchemaType();
        }
        PsiElement parent = metaMap.getParent();
        if (parent instanceof SchemaType st) {
            return st;
        }
        if (parent instanceof TypeEntry te) {
            return te.getSchemaType();
        }
        return null;
    }

    private static boolean isUnderlyingEquatableIgnoringLocalMap(
            @Nullable TypeDefinition ownerTypeDef,
            @Nullable SchemaType governedSchema,
            MetadataMap localMap
    ) {
        if (ownerTypeDef != null) {
            return isUnderlyingIntrinsicallyEquatable(ownerTypeDef);
        }
        if (governedSchema != null) {
            return isSchemaTypeEquatableInternal(
                governedSchema,
                hasExactFacet(localMap),
                hasInvertibleFacet(localMap),
                new HashSet<>(),
                true
            );
        }
        return false;
    }

    private static String extractAuthoredFacetSnippet(MetadataEntry entry) {
        return entry.getText().trim().replaceAll("\\s+", " ");
    }

    private static boolean isDirectContinuousFloatSchema(SchemaType schemaType) {
        if (schemaType.getTypeKeyword() != null) {
            return false;
        }
        var ctor = schemaType.getSchemaConstructor();
        if (ctor == null || ctor.getAtomicType() == null) {
            return false;
        }
        if (!StvnVocabulary.TYPE_FLOAT.equals(ctor.getAtomicType().getText().trim())) {
            return false;
        }
        if (hasExactFacet(schemaType.getMetadataMap())) {
            return false;
        }
        return resolveExplicitBooleanTrait(schemaType.getMetadataMap(), StvnVocabulary.FACET_KW_EQUATABLE).isEmpty();
    }

    private static boolean hasDiscreteIncompatibleFloatBound(@Nullable MetadataMap map) {
        if (map == null) {
            return false;
        }
        for (var entry : map.getMetadataEntryList()) {
            String txt = entry.getText().trim();
            if (txt.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || txt.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCaretInsideInvertibleMetadata(PsiElement elemAtRange, MetadataEntry invertibleEntry) {
        if (PsiTreeUtil.isAncestor(invertibleEntry, elemAtRange, false)) {
            return true;
        }
        var enclosingMap = PsiTreeUtil.getParentOfType(invertibleEntry, MetadataMap.class, false);
        return enclosingMap != null && PsiTreeUtil.isAncestor(enclosingMap, elemAtRange, false);
    }

    /**
     * Resolves an inline continuous {@code :Float} {@link SchemaType} at or immediately enclosing {@code element}.
     *
     * @param element candidate PSI element
     * @return direct continuous {@code :Float} {@link SchemaType}, or {@code null}
     */
    public static @Nullable SchemaType findFloatSchemaType(PsiElement element) {
        var schemaType = (element instanceof SchemaType st) ? st
                : PsiTreeUtil.getParentOfType(element, SchemaType.class, false);
        if (schemaType != null && isDirectContinuousFloatSchema(schemaType)) {
            return schemaType;
        }
        return null;
    }

    /**
     * Checks whether {@code schemaType} is a direct continuous {@code :Float} lacking {@code #exact}.
     *
     * @param schemaType candidate schema PSI node
     * @return {@code true} if direct continuous {@code :Float}
     */
    public static boolean isContinuousFloat(SchemaType schemaType) {
        return isDirectContinuousFloatSchema(schemaType);
    }

    public static @Nullable MetadataEntry findInvertibleEntry(PsiElement element) {
        var entry = (element instanceof MetadataEntry me) ? me
                : PsiTreeUtil.getParentOfType(element, MetadataEntry.class, false);
        if (entry != null && entry.getText().contains(StvnVocabulary.FACET_KW_INVERTIBLE)) {
            return entry;
        }

        var map = (element instanceof MetadataMap mm) ? mm
                : PsiTreeUtil.getParentOfType(element, MetadataMap.class, false);
        if (map != null) {
            for (var e : map.getMetadataEntryList()) {
                if (e.getText().contains(StvnVocabulary.FACET_KW_INVERTIBLE)) {
                    return e;
                }
            }
        }

        var coll = PsiTreeUtil.getParentOfType(element, CollectionType.class, false);
        if (coll != null) {
            var outerSchema = PsiTreeUtil.getParentOfType(coll, SchemaType.class, false);
            if (outerSchema != null && outerSchema.getMetadataMap() != null) {
                for (var e : outerSchema.getMetadataMap().getMetadataEntryList()) {
                    if (e.getText().contains(StvnVocabulary.FACET_KW_INVERTIBLE)) {
                        return e;
                    }
                }
            }
            var typeDef = PsiTreeUtil.getParentOfType(coll, TypeDefinition.class, false);
            if (typeDef != null) {
                var e = findExplicitTraitEntry(typeDef, StvnVocabulary.FACET_KW_INVERTIBLE);
                if (e != null) return e;
            }
        }

        var typeDef = PsiTreeUtil.getParentOfType(element, TypeDefinition.class, false);
        if (typeDef != null) {
            var e = findExplicitTraitEntry(typeDef, StvnVocabulary.FACET_KW_INVERTIBLE);
            if (e != null) return e;
        }

        var schemaType = (element instanceof SchemaType st) ? st
                : PsiTreeUtil.getParentOfType(element, SchemaType.class, false);
        if (schemaType != null) {
            var typeKw = schemaType.getTypeKeyword();
            if (typeKw != null) {
                var typeName = typeKw.getText().trim();
                var resolved = StvnTypeReference.resolveTypeInFile(element.getContainingFile(), typeName, new java.util.HashSet<>());
                var resolvedTypeDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                if (resolvedTypeDef != null) {
                    var e = findExplicitTraitEntry(resolvedTypeDef, StvnVocabulary.FACET_KW_INVERTIBLE);
                    if (e != null) return e;
                }
            }
        }

        return null;
    }

    public static String resolveContainerName(MetadataEntry entry) {
        var typeDef = PsiTreeUtil.getParentOfType(entry, TypeDefinition.class);
        if (typeDef != null && typeDef.getTypeKeyword() != null) {
            return typeDef.getTypeKeyword().getText().trim();
        }
        return StvnVocabulary.TYPE_MAP;
    }

    private static AnnotationBuilder registerActionsForNominalType(
            AnnotationBuilder builder,
            PsiFile file,
            TypeDefinition typeDef,
            String typeName,
            String message,
            PriorityAction.Priority primaryPriority,
            PriorityAction.Priority overridePriority
    ) {
        var explicitOverrideEntry = findExplicitTraitEntry(typeDef, StvnVocabulary.FACET_KW_EQUATABLE);
        if (explicitOverrideEntry != null && isExplicitFalse(explicitOverrideEntry)) {
            // Case 1: Explicit Override in :defs
            builder = builder.withFix(new NavigateToExplicitOverrideIntention(
                explicitOverrideEntry, typeName, primaryPriority));
            if (file.isWritable()
                    && typeDef.getContainingFile().isWritable()
                    && !StvnPreludeBridge.isPreludeFile(typeDef.getContainingFile())) {
                builder = builder.withFix(new RemoveIncompatibleOverrideQuickFix(
                    explicitOverrideEntry, typeName, primaryPriority));
                boolean intrinsicallyEquatable = isUnderlyingIntrinsicallyEquatable(typeDef);
                if (!intrinsicallyEquatable) {
                    builder = builder.withFix(new ChangeNominalOverrideQuickFix(
                        typeDef, typeName, overridePriority));
                }
            }
            return builder;
        }

        // Case 2: Inductive Constituent Breakdown or Nominal Continuous :Float Lineage
        var nonConforming = extractNonConformingConstituents(typeDef);
        for (var constituent : nonConforming) {
            builder = builder.withFix(new JumpToNonConformingConstituentIntention(
                constituent.schema(),
                typeName,
                constituent.slotRoleLabel(),
                constituent.label(),
                primaryPriority
            ));
        }

        if (nonConforming.isEmpty()) {
            var immediateNavElem = typeDef.getTypeKeyword() != null ? typeDef.getTypeKeyword() : typeDef;
            var immediateLabel = typeDef.getTypeKeyword() != null
                ? typeDef.getTypeKeyword().getText().trim()
                : typeName;
            builder = builder.withFix(new JumpToNominalTraitDeclarationIntention(
                immediateNavElem, immediateLabel, primaryPriority));

            var floatLineage = resolveContinuousFloatNominalLineage(typeDef);
            if (!floatLineage.isEmpty()) {
                var rootFloatDef = floatLineage.get(floatLineage.size() - 1);
                var rootFloatName = rootFloatDef.getTypeKeyword() != null
                    ? rootFloatDef.getTypeKeyword().getText().trim()
                    : immediateLabel;
                if (rootFloatDef != typeDef) {
                    var rootNavElem = rootFloatDef.getTypeKeyword() != null
                        ? rootFloatDef.getTypeKeyword()
                        : rootFloatDef;
                    builder = builder.withFix(new JumpToNominalTraitDeclarationIntention(
                        rootNavElem, rootFloatName, primaryPriority));
                }
                if (file.isWritable()
                        && rootFloatDef.getContainingFile().isWritable()
                        && !StvnPreludeBridge.isPreludeFile(rootFloatDef.getContainingFile())
                        && canSafelyAddExactToLineage(floatLineage)) {
                    builder = builder.withFix(new AddExactConstraintQuickFix(
                        rootFloatDef, rootFloatName, primaryPriority));
                }
            }
        }

        if (file.isWritable()
                && typeDef.getContainingFile().isWritable()
                && !StvnPreludeBridge.isPreludeFile(typeDef.getContainingFile())) {
            var overrideLabel = typeDef.getTypeKeyword() != null
                ? typeDef.getTypeKeyword().getText().trim()
                : typeName;
            builder = builder.withFix(new ChangeNominalOverrideQuickFix(
                typeDef, overrideLabel, overridePriority));
        }

        return builder;
    }

    private static List<TypeDefinition> resolveContinuousFloatNominalLineage(TypeDefinition startDef) {
        var lineage = new ArrayList<TypeDefinition>();
        var visited = new HashSet<TypeDefinition>();
        TypeDefinition curr = startDef;
        while (curr != null && visited.add(curr)) {
            lineage.add(curr);
            if (resolveExplicitBooleanTraitOnDef(curr, StvnVocabulary.FACET_KW_EQUATABLE).isPresent()) {
                return List.of();
            }
            if (hasExactFacet(curr.getMetadataMap())) {
                return List.of();
            }
            var schema = curr.getSchemaType();
            if (schema == null || hasExactFacet(schema.getMetadataMap())) {
                return List.of();
            }
            var ctor = schema.getSchemaConstructor();
            if (ctor != null && ctor.getAtomicType() != null) {
                return StvnVocabulary.TYPE_FLOAT.equals(ctor.getAtomicType().getText().trim())
                    ? lineage
                    : List.of();
            }
            var aliasKw = schema.getTypeKeyword();
            if (aliasKw == null) {
                return List.of();
            }
            var aliasName = aliasKw.getText().trim();
            if (StvnVocabulary.TYPE_FLOAT.equals(aliasName)) {
                return lineage;
            }
            var containingFile = curr.getContainingFile();
            if (containingFile == null) {
                return List.of();
            }
            curr = resolveTargetTypeDefForKeyword(aliasKw, containingFile, aliasName);
        }
        return List.of();
    }

    private static boolean canSafelyAddExactToLineage(List<TypeDefinition> lineage) {
        for (var def : lineage) {
            if (hasExactIncompatibleFacet(def.getMetadataMap())) {
                return false;
            }
            if (def.getSchemaType() != null && hasExactIncompatibleFacet(def.getSchemaType().getMetadataMap())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasExactIncompatibleFacet(@Nullable MetadataMap map) {
        if (map == null) {
            return false;
        }
        for (var entry : map.getMetadataEntryList()) {
            var txt = entry.getText().trim();
            if (txt.startsWith(StvnVocabulary.FACET_KW_EXACT)
                    || txt.startsWith(StvnVocabulary.FACET_KW_SIZE)
                    || txt.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || txt.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)) {
                return true;
            }
        }
        return false;
    }

    private static final int MAX_CONSTITUENT_DESCENT_DEPTH = 16;

    /**
     * Describes a constituent schema slot that lacks a required or derived capability trait.
     *
     * @param index         1-based constituent slot index within the enclosing constructor
     * @param slotRoleLabel constructor-accurate STE slot label ({@code "Element"}, {@code "Key"},
     *                      {@code "Value"}, {@code "Left branch"}, {@code "Right branch"},
     *                      {@code "Branch #k"}, or {@code "Field #k"})
     * @param label         concise nominal alias, atomic type, or attributed leaf label
     * @param schema        navigable PSI {@link SchemaType} of the constituent or attributed leaf
     */
    public record ConstituentInfo(int index, String slotRoleLabel, String label, SchemaType schema) {
        /**
         * Constructs a positional field constituent descriptor defaulting to {@code "Field #" + index}.
         *
         * @param index  1-based positional index
         * @param label  constituent schema label
         * @param schema navigable PSI {@link SchemaType}
         */
        public ConstituentInfo(int index, String label, SchemaType schema) {
            this(index, "Field " + StvnVocabulary.SIGIL_VALUE + index, label, schema);
        }
    }

    /**
     * Resolves the constructor-accurate STE slot role label for a 1-based constituent index.
     *
     * @param ctor      enclosing {@link SchemaConstructor}
     * @param slotIndex 1-based constituent index
     * @return STE slot role label
     */
    public static String resolveConstituentSlotRoleLabel(SchemaConstructor ctor, int slotIndex) {
        if (ctor.getProductType() != null) {
            return "Field " + StvnVocabulary.SIGIL_VALUE + slotIndex;
        }
        if (ctor.getCollectionType() != null) {
            var first = ctor.getCollectionType().getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            if (StvnVocabulary.TYPE_MAP.equals(collKind)) {
                return slotIndex == 1 ? "Key" : "Value";
            }
            return "Element";
        }
        if (ctor.getSumType() != null) {
            var first = ctor.getSumType().getFirstChild();
            String sumKind = first != null ? first.getText().trim() : "";
            if (StvnVocabulary.TYPE_OPTION.equals(sumKind)) {
                return "Element";
            }
            if (StvnVocabulary.TYPE_EITHER.equals(sumKind)) {
                return slotIndex == 1 ? "Left branch" : "Right branch";
            }
            if (StvnVocabulary.TYPE_UNION.equals(sumKind)) {
                return "Branch " + StvnVocabulary.SIGIL_VALUE + slotIndex;
            }
        }
        return "Field " + StvnVocabulary.SIGIL_VALUE + slotIndex;
    }

    public static List<ConstituentInfo> extractNonConformingConstituents(TypeDefinition typeDef) {
        return extractNonConformingConstituents(typeDef, false, new HashSet<>());
    }

    private static List<ConstituentInfo> extractNonConformingConstituents(
        TypeDefinition typeDef,
        boolean inheritedInvertible,
        Set<String> visited
    ) {
        var result = new ArrayList<ConstituentInfo>();
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            return result;
        }

        var defKw = typeDef.getTypeKeyword();
        if (defKw != null && !visited.add(defKw.getText().trim())) {
            return result;
        }

        boolean invertibleActive = inheritedInvertible || hasInvertibleInLineage(typeDef, schemaType);
        var file = typeDef.getContainingFile();
        if (schemaType.getSchemaConstructor() == null && schemaType.getTypeKeyword() != null && file != null) {
            var aliasKw = schemaType.getTypeKeyword();
            var aliasDef = resolveTargetTypeDefForKeyword(aliasKw, file, aliasKw.getText().trim());
            if (aliasDef != null && aliasDef != typeDef) {
                return extractNonConformingConstituents(aliasDef, invertibleActive, visited);
            }
        }

        var ctor = schemaType.getSchemaConstructor();
        if (ctor == null) {
            return result;
        }

        return extractNonConformingConstituentsFromConstructor(ctor, invertibleActive, visited);
    }

    /**
     * Extracts non-equatable constituents directly from a {@link SchemaConstructor} with STE slot role labels
     * and named or atomic leaf attribution across anonymous inline wrappers.
     *
     * @param ctor             constructor PSI node
     * @param invertibleActive {@code true} when {@code #invertible} is active on a {@code :Map} constructor
     * @param visited          visited nominal aliases for cycle termination
     * @return non-equatable constituent descriptors in declaration order
     */
    public static List<ConstituentInfo> extractNonConformingConstituentsFromConstructor(
        SchemaConstructor ctor,
        boolean invertibleActive,
        Set<String> visited
    ) {
        var result = new ArrayList<ConstituentInfo>();
        if (ctor.getCollectionType() != null) {
            var coll = ctor.getCollectionType();
            var inner = coll.getSchemaTypeList();
            var first = coll.getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            if (StvnVocabulary.TYPE_MAP.equals(collKind)) {
                if (!inner.isEmpty() && !isSchemaTypeEquatable(inner.get(0), new HashSet<>(visited))) {
                    collectAttributedNonEquatableLeaves(inner.get(0), 1, "Key", visited, 0, result);
                }
                if (inner.size() >= 2
                        && invertibleActive
                        && !isSchemaTypeEquatable(inner.get(1), new HashSet<>(visited))) {
                    collectAttributedNonEquatableLeaves(inner.get(1), 2, "Value", visited, 0, result);
                }
                return result;
            }
        }

        List<SchemaType> constituents = collectConstructorConstituents(ctor);
        int slotIndex = 1;
        for (var constituent : constituents) {
            if (!isSchemaTypeEquatable(constituent, new HashSet<>(visited))) {
                String roleLabel = resolveConstituentSlotRoleLabel(ctor, slotIndex);
                collectAttributedNonEquatableLeaves(constituent, slotIndex, roleLabel, visited, 0, result);
            }
            slotIndex++;
        }
        return result;
    }

    public static @Nullable ConstituentInfo findFirstNonEquatableConstituent(TypeDefinition typeDef) {
        var nonConforming = extractNonConformingConstituents(typeDef);
        return nonConforming.isEmpty() ? null : nonConforming.get(0);
    }

    public static @Nullable ConstituentInfo findFirstNonComparableConstituent(TypeDefinition typeDef) {
        var nonConforming = extractNonComparableConstituents(typeDef);
        return nonConforming.isEmpty() ? null : nonConforming.get(0);
    }

    public static List<ConstituentInfo> extractNonComparableConstituents(TypeDefinition typeDef) {
        return extractNonComparableConstituents(typeDef, new HashSet<>());
    }

    private static List<ConstituentInfo> extractNonComparableConstituents(TypeDefinition typeDef, Set<String> visited) {
        var result = new ArrayList<ConstituentInfo>();
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            return result;
        }

        var defKw = typeDef.getTypeKeyword();
        if (defKw != null && !visited.add(defKw.getText().trim())) {
            return result;
        }

        var file = typeDef.getContainingFile();
        if (schemaType.getSchemaConstructor() == null && schemaType.getTypeKeyword() != null && file != null) {
            var aliasKw = schemaType.getTypeKeyword();
            var aliasDef = resolveTargetTypeDefForKeyword(aliasKw, file, aliasKw.getText().trim());
            if (aliasDef != null && aliasDef != typeDef) {
                return extractNonComparableConstituents(aliasDef, visited);
            }
        }

        var ctor = schemaType.getSchemaConstructor();
        if (ctor == null) {
            return result;
        }

        if (ctor.getCollectionType() != null) {
            var coll = ctor.getCollectionType();
            var first = coll.getFirstChild();
            var collKind = first != null ? first.getText().trim() : "";
            if (StvnVocabulary.TYPE_SET.equals(collKind) || StvnVocabulary.TYPE_MAP.equals(collKind)) {
                result.add(new ConstituentInfo(1, "Element", collKind, schemaType));
                return result;
            }
        }
        if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
            return result;
        }

        List<SchemaType> constituents = collectConstructorConstituents(ctor);
        int slotIndex = 1;
        for (var constituent : constituents) {
            if (!isSchemaTypeComparable(constituent, new HashSet<>(visited))) {
                String roleLabel = resolveConstituentSlotRoleLabel(ctor, slotIndex);
                collectAttributedNonComparableLeaves(constituent, slotIndex, roleLabel, visited, 0, result);
            }
            slotIndex++;
        }
        return result;
    }

    private static void collectAttributedNonEquatableLeaves(
        SchemaType slotSchema,
        int outerIndex,
        String outerRoleLabel,
        Set<String> visited,
        int depth,
        List<ConstituentInfo> sink
    ) {
        if (slotSchema.getTypeKeyword() != null) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatLeafConstituentLabel(slotSchema), slotSchema));
            return;
        }
        var childCtor = slotSchema.getSchemaConstructor();
        if (childCtor == null || childCtor.getAtomicType() != null || depth >= MAX_CONSTITUENT_DESCENT_DEPTH) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatLeafConstituentLabel(slotSchema), slotSchema));
            return;
        }
        var explicitEq = resolveExplicitBooleanTrait(slotSchema.getMetadataMap(), StvnVocabulary.FACET_KW_EQUATABLE);
        if (explicitEq.isPresent() && !explicitEq.get()) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatInlineConstructorHeadLabel(slotSchema, childCtor), slotSchema));
            return;
        }
        int beforeSize = sink.size();
        if (childCtor.getCollectionType() != null) {
            var coll = childCtor.getCollectionType();
            var first = coll.getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            var inner = coll.getSchemaTypeList();
            if (StvnVocabulary.TYPE_MAP.equals(collKind)) {
                if (!inner.isEmpty() && !isSchemaTypeEquatable(inner.get(0), new HashSet<>(visited))) {
                    collectAttributedNonEquatableLeaves(inner.get(0), outerIndex, outerRoleLabel, visited, depth + 1, sink);
                }
                if (inner.size() >= 2
                        && hasInvertibleFacet(slotSchema.getMetadataMap())
                        && !isSchemaTypeEquatable(inner.get(1), new HashSet<>(visited))) {
                    collectAttributedNonEquatableLeaves(inner.get(1), outerIndex, outerRoleLabel, visited, depth + 1, sink);
                }
            } else if (inner.size() == 1 && !isSchemaTypeEquatable(inner.get(0), new HashSet<>(visited))) {
                collectAttributedNonEquatableLeaves(inner.get(0), outerIndex, outerRoleLabel, visited, depth + 1, sink);
            }
        } else {
            for (var child : collectConstructorConstituents(childCtor)) {
                if (!isSchemaTypeEquatable(child, new HashSet<>(visited))) {
                    collectAttributedNonEquatableLeaves(child, outerIndex, outerRoleLabel, visited, depth + 1, sink);
                }
            }
        }
        if (sink.size() == beforeSize) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatInlineConstructorHeadLabel(slotSchema, childCtor), slotSchema));
        }
    }

    private static void collectAttributedNonComparableLeaves(
        SchemaType slotSchema,
        int outerIndex,
        String outerRoleLabel,
        Set<String> visited,
        int depth,
        List<ConstituentInfo> sink
    ) {
        if (slotSchema.getTypeKeyword() != null) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatLeafConstituentLabel(slotSchema), slotSchema));
            return;
        }
        var childCtor = slotSchema.getSchemaConstructor();
        if (childCtor == null || childCtor.getAtomicType() != null || depth >= MAX_CONSTITUENT_DESCENT_DEPTH) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatLeafConstituentLabel(slotSchema), slotSchema));
            return;
        }
        if (childCtor.getCollectionType() != null) {
            var first = childCtor.getCollectionType().getFirstChild();
            String collKind = first != null ? first.getText().trim() : "";
            if (StvnVocabulary.TYPE_SET.equals(collKind) || StvnVocabulary.TYPE_MAP.equals(collKind)) {
                sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, collKind, slotSchema));
                return;
            }
        }
        var explicitComp = resolveExplicitBooleanTrait(slotSchema.getMetadataMap(), StvnVocabulary.FACET_KW_COMPARABLE);
        if (explicitComp.isPresent() && !explicitComp.get()) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatInlineConstructorHeadLabel(slotSchema, childCtor), slotSchema));
            return;
        }
        int beforeSize = sink.size();
        for (var child : collectConstructorConstituents(childCtor)) {
            if (!isSchemaTypeComparable(child, new HashSet<>(visited))) {
                collectAttributedNonComparableLeaves(child, outerIndex, outerRoleLabel, visited, depth + 1, sink);
            }
        }
        if (sink.size() == beforeSize) {
            sink.add(new ConstituentInfo(outerIndex, outerRoleLabel, formatInlineConstructorHeadLabel(slotSchema, childCtor), slotSchema));
        }
    }

    private static String formatLeafConstituentLabel(SchemaType constituent) {
        if (constituent.getMetadataMap() != null) {
            return StvnSchemaFormatter.formatSchema(constituent);
        }
        if (constituent.getTypeKeyword() != null) {
            return constituent.getTypeKeyword().getText().trim();
        }
        var ctor = constituent.getSchemaConstructor();
        if (ctor != null && ctor.getAtomicType() != null) {
            return ctor.getAtomicType().getText().trim();
        }
        return StvnSchemaFormatter.formatCleanSchema(constituent);
    }

    private static String formatInlineConstructorHeadLabel(SchemaType slotSchema, SchemaConstructor ctor) {
        String head;
        if (ctor.getProductType() != null) {
            head = StvnVocabulary.TYPE_TUPLE;
        } else if (ctor.getCollectionType() != null && ctor.getCollectionType().getFirstChild() != null) {
            head = ctor.getCollectionType().getFirstChild().getText().trim();
        } else if (ctor.getSumType() != null && ctor.getSumType().getFirstChild() != null) {
            head = ctor.getSumType().getFirstChild().getText().trim();
        } else {
            head = StvnSchemaFormatter.formatCleanSchema(slotSchema);
        }
        if (slotSchema.getMetadataMap() != null) {
            return slotSchema.getMetadataMap().getText().trim().replaceAll("\\s+", " ") + " " + head;
        }
        return head;
    }

    public static boolean isUnderlyingIntrinsicallyEquatable(TypeDefinition typeDef) {
        return isUnderlyingIntrinsicallyEquatable(typeDef, new HashSet<>());
    }

    public static boolean isUnderlyingIntrinsicallyEquatable(TypeDefinition typeDef, Set<String> visited) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            return false;
        }
        boolean inheritedExact = hasExactFacet(typeDef.getMetadataMap()) || hasExactFacet(schemaType.getMetadataMap());
        boolean inheritedInv = hasInvertibleFacet(typeDef.getMetadataMap()) || hasInvertibleFacet(schemaType.getMetadataMap());
        var kw = typeDef.getTypeKeyword();
        Set<String> nextVisited = new HashSet<>(visited);
        if (kw != null && !nextVisited.add(kw.getText().trim())) {
            return true;
        }
        return isSchemaTypeEquatableInternal(schemaType, inheritedExact, inheritedInv, nextVisited, true);
    }

    public static boolean isUnderlyingIntrinsicallyComparable(TypeDefinition typeDef) {
        return isUnderlyingIntrinsicallyComparable(typeDef, new HashSet<>());
    }

    public static boolean isUnderlyingIntrinsicallyComparable(TypeDefinition typeDef, Set<String> visited) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            return false;
        }
        var kw = typeDef.getTypeKeyword();
        Set<String> nextVisited = new HashSet<>(visited);
        if (kw != null && !nextVisited.add(kw.getText().trim())) {
            return true;
        }
        return isSchemaTypeComparableInternal(schemaType, nextVisited, true);
    }

    /**
     * Evaluates whether a {@link SchemaType} satisfies {@code #equatable #TRUE}, accounting for
     * explicit trait overrides, nominal lineages, {@code :Map} {@code #invertible} values, and nested
     * inline composite constructors even when compiler lowering produces an ungrounded {@code PoisonedSchema}.
     *
     * @param schemaType schema PSI node to inspect
     * @param visited    visited nominal type names for cycle termination
     * @return {@code true} when equatable
     */
    public static boolean isSchemaTypeEquatable(@Nullable SchemaType schemaType, Set<String> visited) {
        if (schemaType == null) {
            return false;
        }
        return isSchemaTypeEquatableInternal(schemaType, false, false, visited, false);
    }

    /**
     * Evaluates whether a {@link SchemaType} satisfies {@code #comparable #TRUE} inductively over PSI and IR.
     *
     * @param schemaType schema PSI node to inspect
     * @param visited    visited nominal type names for cycle termination
     * @return {@code true} when comparable
     */
    public static boolean isSchemaTypeComparable(@Nullable SchemaType schemaType, Set<String> visited) {
        if (schemaType == null) {
            return false;
        }
        return isSchemaTypeComparableInternal(schemaType, visited, false);
    }

    private static boolean isSchemaTypeEquatableInternal(
        SchemaType schemaType,
        boolean inheritedExact,
        boolean inheritedInvertible,
        Set<String> visited,
        boolean ignoreSelfExplicitOverride
    ) {
        if (!ignoreSelfExplicitOverride) {
            var explicit = resolveExplicitBooleanTrait(schemaType.getMetadataMap(), StvnVocabulary.FACET_KW_EQUATABLE);
            if (explicit.isPresent()) {
                return explicit.get();
            }
        }
        boolean exactActive = inheritedExact || hasExactFacet(schemaType.getMetadataMap());
        boolean invertibleActive = inheritedInvertible || hasInvertibleFacet(schemaType.getMetadataMap());

        var schemaKw = schemaType.getTypeKeyword();
        if (schemaKw != null) {
            String name = schemaKw.getText().trim();
            if (StvnVocabulary.TYPE_FLOAT.equals(name)) {
                return exactActive;
            }
            if (StvnVocabulary.TYPE_INT.equals(name)
                    || StvnVocabulary.TYPE_STRING.equals(name)
                    || StvnVocabulary.TYPE_BOOLEAN.equals(name)
                    || StvnVocabulary.TYPE_TIME_EPOCH.equals(name)
                    || StvnVocabulary.TYPE_DATE_TIME.equals(name)
                    || StvnVocabulary.TYPE_ENUM.equals(name)) {
                return true;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var targetDef = resolveTargetTypeDefForKeyword(schemaKw, file, name);
                if (targetDef != null) {
                    var explicitTarget = resolveExplicitBooleanTraitOnDef(targetDef, StvnVocabulary.FACET_KW_EQUATABLE);
                    if (explicitTarget.isPresent()) {
                        return explicitTarget.get();
                    }
                    var targetIr = StvnTypeResolver.resolveNominalIrSchema(targetDef);
                    if (targetIr != null && !(targetIr instanceof StvnSchema.PoisonedSchema)) {
                        if (exactActive && targetIr instanceof StvnSchema.FloatSchema) {
                            return true;
                        }
                        if (invertibleActive && targetIr instanceof StvnSchema.MapSchema mapIr && !mapIr.invertible()) {
                            return mapIr.keySchema().traits().equatable() && mapIr.valueSchema().traits().equatable();
                        }
                        return targetIr.traits().equatable();
                    }
                    var targetDefKw = targetDef.getTypeKeyword();
                    String cycleKey = targetDefKw != null ? targetDefKw.getText().trim() : name;
                    if (!visited.add(cycleKey)) {
                        return true;
                    }
                    var nextSchema = targetDef.getSchemaType();
                    if (nextSchema == null) {
                        return false;
                    }
                    boolean nextExact = exactActive || hasExactFacet(targetDef.getMetadataMap());
                    boolean nextInv = invertibleActive || hasInvertibleFacet(targetDef.getMetadataMap());
                    return isSchemaTypeEquatableInternal(nextSchema, nextExact, nextInv, visited, false);
                }
            }
            return true;
        }

        var ctor = schemaType.getSchemaConstructor();
        if (ctor == null) {
            return false;
        }
        if (ctor.getAtomicType() != null) {
            String atom = ctor.getAtomicType().getText().trim();
            if (StvnVocabulary.TYPE_FLOAT.equals(atom)) {
                return exactActive;
            }
            return true;
        }
        if (ctor.getProductType() != null) {
            var slots = ctor.getProductType().getSchemaTypeList();
            if (slots.isEmpty()) {
                return false;
            }
            for (var slot : slots) {
                if (!isSchemaTypeEquatable(slot, new HashSet<>(visited))) {
                    return false;
                }
            }
            return true;
        }
        if (ctor.getCollectionType() != null) {
            var coll = ctor.getCollectionType();
            var inners = coll.getSchemaTypeList();
            String collText = coll.getText().trim();
            if (collText.startsWith(StvnVocabulary.TYPE_MAP)) {
                if (inners.size() != 2) {
                    return false;
                }
                boolean keyEq = isSchemaTypeEquatable(inners.get(0), new HashSet<>(visited));
                if (!keyEq) {
                    return false;
                }
                return !invertibleActive || isSchemaTypeEquatable(inners.get(1), new HashSet<>(visited));
            }
            return inners.size() == 1 && isSchemaTypeEquatable(inners.get(0), new HashSet<>(visited));
        }
        if (ctor.getSumType() != null) {
            var sum = ctor.getSumType();
            if (sum.getEnumDef() != null) {
                return !sum.getEnumDef().getValueKeywordList().isEmpty();
            }
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            String sumText = sum.getText().trim();
            if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && branches.size() != 1) {
                return false;
            }
            if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() != 2) {
                return false;
            }
            if (sumText.startsWith(StvnVocabulary.TYPE_UNION) && branches.size() < 2) {
                return false;
            }
            if (branches.isEmpty()) {
                return false;
            }
            for (var b : branches) {
                if (!isSchemaTypeEquatable(b, new HashSet<>(visited))) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private static boolean isSchemaTypeComparableInternal(
        SchemaType schemaType,
        Set<String> visited,
        boolean ignoreSelfExplicitOverride
    ) {
        if (!ignoreSelfExplicitOverride) {
            var explicit = resolveExplicitBooleanTrait(schemaType.getMetadataMap(), StvnVocabulary.FACET_KW_COMPARABLE);
            if (explicit.isPresent()) {
                return explicit.get();
            }
        }
        var schemaKw = schemaType.getTypeKeyword();
        if (schemaKw != null) {
            String name = schemaKw.getText().trim();
            if (StvnVocabulary.TYPE_SET.equals(name) || StvnVocabulary.TYPE_MAP.equals(name)) {
                return false;
            }
            if (StvnVocabulary.TYPE_INT.equals(name)
                    || StvnVocabulary.TYPE_STRING.equals(name)
                    || StvnVocabulary.TYPE_BOOLEAN.equals(name)
                    || StvnVocabulary.TYPE_FLOAT.equals(name)
                    || StvnVocabulary.TYPE_TIME_EPOCH.equals(name)
                    || StvnVocabulary.TYPE_DATE_TIME.equals(name)
                    || StvnVocabulary.TYPE_ENUM.equals(name)) {
                return true;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var targetDef = resolveTargetTypeDefForKeyword(schemaKw, file, name);
                if (targetDef != null) {
                    var explicitTarget = resolveExplicitBooleanTraitOnDef(targetDef, StvnVocabulary.FACET_KW_COMPARABLE);
                    if (explicitTarget.isPresent()) {
                        return explicitTarget.get();
                    }
                    var targetIr = StvnTypeResolver.resolveNominalIrSchema(targetDef);
                    if (targetIr != null && !(targetIr instanceof StvnSchema.PoisonedSchema)) {
                        return targetIr.traits().comparable();
                    }
                    var targetDefKw = targetDef.getTypeKeyword();
                    String cycleKey = targetDefKw != null ? targetDefKw.getText().trim() : name;
                    if (!visited.add(cycleKey)) {
                        return true;
                    }
                    var nextSchema = targetDef.getSchemaType();
                    return nextSchema != null && isSchemaTypeComparableInternal(nextSchema, visited, false);
                }
            }
            return true;
        }

        var ctor = schemaType.getSchemaConstructor();
        if (ctor == null) {
            return false;
        }
        if (ctor.getAtomicType() != null) {
            return true;
        }
        if (ctor.getProductType() != null) {
            var slots = ctor.getProductType().getSchemaTypeList();
            if (slots.isEmpty()) {
                return false;
            }
            for (var slot : slots) {
                if (!isSchemaTypeComparable(slot, new HashSet<>(visited))) {
                    return false;
                }
            }
            return true;
        }
        if (ctor.getCollectionType() != null) {
            var coll = ctor.getCollectionType();
            String collText = coll.getText().trim();
            if (collText.startsWith(StvnVocabulary.TYPE_SET) || collText.startsWith(StvnVocabulary.TYPE_MAP)) {
                return false;
            }
            var inners = coll.getSchemaTypeList();
            return inners.size() == 1 && isSchemaTypeComparable(inners.get(0), new HashSet<>(visited));
        }
        if (ctor.getSumType() != null) {
            var sum = ctor.getSumType();
            if (sum.getEnumDef() != null) {
                return !sum.getEnumDef().getValueKeywordList().isEmpty();
            }
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            String sumText = sum.getText().trim();
            if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && branches.size() != 1) {
                return false;
            }
            if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() != 2) {
                return false;
            }
            if (sumText.startsWith(StvnVocabulary.TYPE_UNION) && branches.size() < 2) {
                return false;
            }
            if (branches.isEmpty()) {
                return false;
            }
            for (var b : branches) {
                if (!isSchemaTypeComparable(b, new HashSet<>(visited))) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private static List<SchemaType> collectConstructorConstituents(SchemaConstructor ctor) {
        if (ctor.getProductType() != null) {
            return ctor.getProductType().getSchemaTypeList();
        }
        if (ctor.getCollectionType() != null) {
            return ctor.getCollectionType().getSchemaTypeList();
        }
        if (ctor.getSumType() != null) {
            return PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        }
        return List.of();
    }

    private static @Nullable TypeDefinition resolveTargetTypeDefForKeyword(
        @Nullable TypeKeyword schemaKw,
        PsiFile file,
        String typeName
    ) {
        if (schemaKw != null) {
            var refResolved = new StvnTypeReference(schemaKw).resolve();
            var refTargetDef = StvnPsiUtils.resolveTargetTypeDefinition(refResolved);
            if (refTargetDef != null) {
                return refTargetDef;
            }
        }
        var resolved = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
        var targetDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
        if (targetDef != null) {
            return targetDef;
        }
        if (!file.getProject().isDisposed()) {
            return StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), typeName);
        }
        return null;
    }

    private static boolean hasInvertibleInLineage(TypeDefinition rootDef, SchemaType schemaType) {
        if (hasInvertibleFacet(rootDef.getMetadataMap()) || hasInvertibleFacet(schemaType.getMetadataMap())) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        SchemaType curr = schemaType;
        while (curr != null && curr.getTypeKeyword() != null) {
            var currKw = curr.getTypeKeyword();
            String alias = currKw.getText().trim();
            if (!seen.add(alias) || curr.getContainingFile() == null) {
                break;
            }
            TypeDefinition nextDef = resolveTargetTypeDefForKeyword(currKw, curr.getContainingFile(), alias);
            if (nextDef == null) {
                break;
            }
            if (hasInvertibleFacet(nextDef.getMetadataMap())
                    || (nextDef.getSchemaType() != null && hasInvertibleFacet(nextDef.getSchemaType().getMetadataMap()))) {
                return true;
            }
            curr = nextDef.getSchemaType();
        }
        return false;
    }

    private static Optional<Boolean> resolveExplicitBooleanTraitOnDef(TypeDefinition def, String facetKw) {
        var onSchema = def.getSchemaType() != null
            ? resolveExplicitBooleanTrait(def.getSchemaType().getMetadataMap(), facetKw)
            : Optional.<Boolean>empty();
        if (onSchema.isPresent()) {
            return onSchema;
        }
        return resolveExplicitBooleanTrait(def.getMetadataMap(), facetKw);
    }

    private static Optional<Boolean> resolveExplicitBooleanTrait(@Nullable MetadataMap map, String facetKw) {
        if (map == null) {
            return Optional.empty();
        }
        for (var entry : map.getMetadataEntryList()) {
            String txt = entry.getText().trim();
            String[] parts = txt.split("\\s+", 2);
            if (parts.length >= 2 && facetKw.equals(parts[0])) {
                return StvnPsiLiteralEvaluator.resolveBooleanOrConst(entry, parts[1].trim());
            }
        }
        return Optional.empty();
    }

    private static boolean hasInvertibleFacet(@Nullable MetadataMap map) {
        if (map == null) {
            return false;
        }
        for (var entry : map.getMetadataEntryList()) {
            if (entry.getText().trim().startsWith(StvnVocabulary.FACET_KW_INVERTIBLE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExactFacet(@Nullable MetadataMap map) {
        if (map == null) return false;
        for (var entry : map.getMetadataEntryList()) {
            if (entry.getText().trim().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
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
        return findExplicitTraitEntryInMap(map, facetKw);
    }

    private static @Nullable MetadataEntry findExplicitTraitEntryInMap(@Nullable MetadataMap map, String facetKw) {
        if (map == null) {
            return null;
        }
        for (var entry : map.getMetadataEntryList()) {
            String txt = entry.getText().trim();
            String[] parts = txt.split("\\s+", 2);
            if (parts.length >= 1 && facetKw.equals(parts[0])) {
                return entry;
            }
        }
        return null;
    }

    public static boolean isExplicitFalse(MetadataEntry entry) {
        var txt = entry.getText().trim();
        var parts = txt.split("\\s+", 2);
        if (parts.length > 1) {
            var val = parts[1].trim();
            if (StvnVocabulary.VAL_FALSE.equalsIgnoreCase(val) || StvnVocabulary.VAL_FALSE_SHORT.equalsIgnoreCase(val)) {
                return true;
            }
            return StvnPsiLiteralEvaluator.resolveBooleanOrConst(entry, val).map(b -> !b).orElse(false);
        }
        return false;
    }

    // --- INTENTION: Navigate to Explicit Override ---
    public static final class NavigateToExplicitOverrideIntention extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final String typeName;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a navigation intention to an explicit {@code #equatable #FALSE} declaration.
         *
         * @param element  explicit trait entry PSI node
         * @param typeName nominal type name
         */
        public NavigateToExplicitOverrideIntention(PsiElement element, String typeName) {
            this(element, typeName, Priority.HIGH);
        }

        /**
         * Constructs a navigation intention to an explicit {@code #equatable #FALSE} declaration with priority.
         *
         * @param element  explicit trait entry PSI node
         * @param typeName nominal type name
         * @param priority intention priority ordering
         */
        public NavigateToExplicitOverrideIntention(PsiElement element, String typeName, Priority priority) {
            super(element);
            this.typeName = typeName;
            this.priority = priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Navigate to explicit '" + StvnVocabulary.FACET_KW_EQUATABLE + " "
                + StvnVocabulary.VAL_FALSE + "' declaration on " + typeName;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Navigate to trait declaration";
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
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
                ed.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                ed.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset());
            }
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof NavigateToExplicitOverrideIntention other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(typeName, other.typeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), typeName);
        }
    }

    // --- INTENTION: Jump to Nominal Declaration in :defs ---
    public static final class JumpToNominalTraitDeclarationIntention extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final String typeName;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a navigation intention to a nominal declaration in {@code :defs}.
         *
         * @param element  nominal declaration PSI element
         * @param typeName display name of the nominal type
         */
        public JumpToNominalTraitDeclarationIntention(PsiElement element, String typeName) {
            this(element, typeName, Priority.HIGH);
        }

        /**
         * Constructs a navigation intention to a nominal declaration in {@code :defs} with priority.
         *
         * @param element  nominal declaration PSI element
         * @param typeName display name of the nominal type
         * @param priority intention priority ordering
         */
        public JumpToNominalTraitDeclarationIntention(PsiElement element, String typeName, Priority priority) {
            super(element);
            this.typeName = typeName;
            this.priority = priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Jump to '" + typeName + "' declaration in " + StvnVocabulary.KEYWORD_DEFS;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Jump to nominal type declaration";
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
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
                ed.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                ed.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset());
            }
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof JumpToNominalTraitDeclarationIntention other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(typeName, other.typeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), typeName);
        }
    }

    // --- INTENTION: Jump to Non-Conforming Constituent ---
    public static final class JumpToNonConformingConstituentIntention extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final String typeName;
        @SafeFieldForPreview
        private final String slotRoleLabel;
        @SafeFieldForPreview
        private final String fieldLabel;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a navigation intention to a non-equatable constituent inside a nominal type.
         *
         * @param element       constituent PSI element
         * @param typeName      enclosing nominal type name
         * @param slotRoleLabel STE slot role label
         * @param fieldLabel    attributed constituent label
         */
        public JumpToNonConformingConstituentIntention(
                PsiElement element,
                String typeName,
                String slotRoleLabel,
                String fieldLabel
        ) {
            this(element, typeName, slotRoleLabel, fieldLabel, Priority.HIGH);
        }

        /**
         * Constructs a navigation intention to a non-equatable constituent with explicit priority.
         *
         * @param element       constituent PSI element
         * @param typeName      enclosing nominal type name
         * @param slotRoleLabel STE slot role label
         * @param fieldLabel    attributed constituent label
         * @param priority      intention priority ordering
         */
        public JumpToNonConformingConstituentIntention(
                PsiElement element,
                String typeName,
                String slotRoleLabel,
                String fieldLabel,
                Priority priority
        ) {
            super(element);
            this.typeName = typeName;
            this.slotRoleLabel = slotRoleLabel;
            this.fieldLabel = fieldLabel;
            this.priority = priority;
        }

        /**
         * Constructs a positional field constituent navigation intention.
         *
         * @param element    constituent PSI element
         * @param typeName   enclosing nominal type name
         * @param slotIndex  1-based positional field index
         * @param fieldLabel attributed constituent label
         */
        public JumpToNonConformingConstituentIntention(
                PsiElement element,
                String typeName,
                int slotIndex,
                String fieldLabel
        ) {
            this(element, typeName, "Field " + StvnVocabulary.SIGIL_VALUE + slotIndex, fieldLabel, Priority.HIGH);
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Jump to non-equatable constituent in " + typeName + " (" + slotRoleLabel + ": " + fieldLabel + ")";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Navigate to non-conforming constituent";
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
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
                ed.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                ed.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset());
            }
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof JumpToNonConformingConstituentIntention other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(typeName, other.typeName)
                && Objects.equals(slotRoleLabel, other.slotRoleLabel)
                && Objects.equals(fieldLabel, other.fieldLabel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), typeName, slotRoleLabel, fieldLabel);
        }
    }

    // --- QUICK-FIX 1: Remove Incompatible Override ---
    public static final class RemoveIncompatibleOverrideQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final String typeName;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a quick-fix removing an explicit {@code #equatable #FALSE} override from a nominal declaration.
         *
         * @param element  explicit trait entry PSI node
         * @param typeName nominal type name
         */
        public RemoveIncompatibleOverrideQuickFix(PsiElement element, String typeName) {
            this(element, typeName, Priority.HIGH);
        }

        /**
         * Constructs a quick-fix removing an explicit {@code #equatable #FALSE} override with explicit priority.
         *
         * @param element  explicit trait entry PSI node
         * @param typeName nominal type name
         * @param priority intention priority ordering
         */
        public RemoveIncompatibleOverrideQuickFix(PsiElement element, String typeName, Priority priority) {
            super(element);
            this.typeName = typeName;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Remove explicit '" + StvnVocabulary.FACET_KW_EQUATABLE + " "
                + StvnVocabulary.VAL_FALSE + "' override from " + typeName;
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

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            var cross = tryGenerateCrossFilePreview(
                project, file, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem));
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
                ? tryGenerateCrossFilePreview(
                    project, activeFile, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem))
                : null;
            return cross != null ? cross : super.generatePreview(project, previewDescriptor);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RemoveIncompatibleOverrideQuickFix other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(typeName, other.typeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), typeName);
        }
    }

    // --- QUICK-FIX 2: Change Nominal Override in :defs ---
    public static final class ChangeNominalOverrideQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final String typeName;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a quick-fix overriding {@code #equatable #TRUE} on a nominal declaration in {@code :defs}.
         *
         * @param typeDef  target {@link TypeDefinition}
         * @param typeName display name of the nominal type
         */
        public ChangeNominalOverrideQuickFix(TypeDefinition typeDef, String typeName) {
            this(typeDef, typeName, Priority.NORMAL);
        }

        /**
         * Constructs a quick-fix overriding {@code #equatable #TRUE} on a nominal declaration with explicit priority.
         *
         * @param typeDef  target {@link TypeDefinition}
         * @param typeName display name of the nominal type
         * @param priority intention priority ordering
         */
        public ChangeNominalOverrideQuickFix(TypeDefinition typeDef, String typeName, Priority priority) {
            super(typeDef);
            this.typeName = typeName;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Override '" + StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE
                + "' on " + typeName + " in " + StvnVocabulary.KEYWORD_DEFS;
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
            var equatableTrueEntry = StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE;

            var map = typeDef.getMetadataMap();
            if (map == null && typeDef.getSchemaType() != null) {
                map = typeDef.getSchemaType().getMetadataMap();
            }

            if (map != null && map.isValid() && targetFile != null) {
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
                        map,
                        StvnVocabulary.FACET_KW_EQUATABLE,
                        equatableTrueEntry
                    );
                    psiDocManager.commitDocument(doc);
                    postProcessFileMutation(project, file, targetFile);
                    return;
                }
            }
            var entry = findExplicitTraitEntry(typeDef, StvnVocabulary.FACET_KW_EQUATABLE);
            if (entry != null && entry.isValid()) {
                var newEntry = StvnElementFactory.createMetadataEntry(project, equatableTrueEntry);
                entry.replace(newEntry);
                postProcessFileMutation(project, file, targetFile);
                return;
            }
            if (typeDef.getSchemaType() != null) {
                var newSchema = StvnElementFactory.createSchemaType(
                        project,
                        StvnVocabulary.DELIM_OPEN_BRACE + " " + equatableTrueEntry + " "
                            + StvnVocabulary.DELIM_CLOSE_BRACE + " " + typeDef.getSchemaType().getText());
                typeDef.getSchemaType().replace(newSchema);
            }
            postProcessFileMutation(project, file, targetFile);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            var cross = tryGenerateCrossFilePreview(
                project, file, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem));
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
                ? tryGenerateCrossFilePreview(
                    project, activeFile, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem))
                : null;
            return cross != null ? cross : super.generatePreview(project, previewDescriptor);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ChangeNominalOverrideQuickFix other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(typeName, other.typeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), typeName);
        }
    }

    // --- QUICK-FIX 3: Restore Inline Wrapper / Leaf '#equatable #TRUE' ---
    public static final class RestoreInlineSchemaEquatableQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final String wrapperHead;
        @SafeFieldForPreview
        private final boolean hasExistingMetadataMap;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a quick-fix attaching {@code #equatable #TRUE} onto an inline schema constructor or leaf.
         *
         * @param schemaType  target inline {@link SchemaType}
         * @param wrapperHead display constructor or atomic type keyword (for example {@code :Union} or {@code :Float})
         * @param priority    intention priority ordering
         */
        public RestoreInlineSchemaEquatableQuickFix(
                SchemaType schemaType,
                String wrapperHead,
                Priority priority
        ) {
            super(schemaType);
            this.wrapperHead = wrapperHead;
            this.hasExistingMetadataMap = schemaType.getMetadataMap() != null;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            String eqTrue = StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE;
            if (hasExistingMetadataMap) {
                return "Add '" + eqTrue + "' to inline " + wrapperHead;
            }
            return "Add '" + StvnVocabulary.DELIM_OPEN_BRACE + " " + eqTrue + " "
                + StvnVocabulary.DELIM_CLOSE_BRACE + "' to inline " + wrapperHead;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Add inline '" + StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE + "' override";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            SchemaType schemaType = startElement instanceof SchemaType st
                ? st
                : PsiTreeUtil.getParentOfType(startElement, SchemaType.class, false);
            if (schemaType == null || !schemaType.isValid()) {
                return;
            }
            PsiFile targetFile = schemaType.getContainingFile();
            String eqTrue = StvnVocabulary.FACET_KW_EQUATABLE + " " + StvnVocabulary.VAL_TRUE;
            MetadataMap metaMap = schemaType.getMetadataMap();
            if (metaMap != null && metaMap.isValid() && targetFile != null) {
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
                        doc, metaMap, StvnVocabulary.FACET_KW_EQUATABLE, eqTrue);
                    psiDocManager.commitDocument(doc);
                    postProcessFileMutation(project, file, targetFile);
                    return;
                }
            }
            SchemaType newSchema = StvnElementFactory.createSchemaType(
                project,
                StvnVocabulary.DELIM_OPEN_BRACE + " " + eqTrue + " "
                    + StvnVocabulary.DELIM_CLOSE_BRACE + " " + schemaType.getText()
            );
            schemaType.replace(newSchema);
            postProcessFileMutation(project, file, targetFile);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            var cross = tryGenerateCrossFilePreview(
                project, file, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem));
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
                ? tryGenerateCrossFilePreview(
                    project, activeFile, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem))
                : null;
            return cross != null ? cross : super.generatePreview(project, previewDescriptor);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RestoreInlineSchemaEquatableQuickFix other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(wrapperHead, other.wrapperHead)
                && hasExistingMetadataMap == other.hasExistingMetadataMap;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), wrapperHead, hasExistingMetadataMap);
        }
    }

    // --- QUICK-FIX 4: Add or Change Local Trait Facet at Canonical Tier-2 Rank ---
    public static final class AddOrChangeLocalTraitFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {
        @SafeFieldForPreview
        private final @Nullable String ownerName;
        @SafeFieldForPreview
        private final String facetKeyword;
        @SafeFieldForPreview
        private final String fullEntryText;
        @SafeFieldForPreview
        private final @Nullable String existingSnippet;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a local metadata-map quick-fix that inserts or replaces a Tier-2 trait facet
         * at its canonical § 6.1 decimal rank.
         *
         * @param metaMap         target {@link MetadataMap}
         * @param ownerName       owning nominal declaration name (or {@code null} when anonymous inline)
         * @param facetKeyword    canonical facet keyword (for example {@code #equatable})
         * @param fullEntryText   replacement facet snippet (for example {@code "#equatable #TRUE"})
         * @param existingSnippet authored conflicting snippet when replacing in-place, or {@code null} when adding
         * @param priority        intention priority ordering
         */
        public AddOrChangeLocalTraitFacetQuickFix(
                MetadataMap metaMap,
                @Nullable String ownerName,
                String facetKeyword,
                String fullEntryText,
                @Nullable String existingSnippet,
                Priority priority
        ) {
            super(metaMap);
            this.ownerName = ownerName;
            this.facetKeyword = facetKeyword;
            this.fullEntryText = fullEntryText;
            this.existingSnippet = existingSnippet;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            if (existingSnippet != null && !existingSnippet.isEmpty()) {
                return "Change '" + existingSnippet + "' to '" + fullEntryText + "'";
            }
            if (ownerName != null && !ownerName.isEmpty()) {
                return "Add '" + fullEntryText + "' to '" + ownerName + "'";
            }
            return "Add '" + fullEntryText + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return existingSnippet != null
                ? "Change conflicting trait facet"
                : "Add missing prerequisite trait facet";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap metaMap = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            if (metaMap == null || !metaMap.isValid()) {
                return;
            }
            PsiFile targetFile = metaMap.getContainingFile();
            if (targetFile == null) {
                return;
            }
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
                    doc, metaMap, facetKeyword, fullEntryText);
                psiDocManager.commitDocument(doc);
                postProcessFileMutation(project, file, targetFile);
            }
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            var cross = tryGenerateCrossFilePreview(
                project, file, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem));
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
                ? tryGenerateCrossFilePreview(
                    project, activeFile, getStartElement(), (p, copyFile, copyElem) -> invoke(p, copyFile, null, copyElem, copyElem))
                : null;
            return cross != null ? cross : super.generatePreview(project, previewDescriptor);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AddOrChangeLocalTraitFacetQuickFix other)) return false;
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(ownerName, other.ownerName)
                && Objects.equals(facetKeyword, other.facetKeyword)
                && Objects.equals(fullEntryText, other.fullEntryText)
                && Objects.equals(existingSnippet, other.existingSnippet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), ownerName, facetKeyword, fullEntryText, existingSnippet);
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
