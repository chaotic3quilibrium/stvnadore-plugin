package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Document;
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
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.validation.RemoveRedundantFacetQuickFix;
import org.stvnadore.plugin.validation.StvnMetadataFacetInsertionUtil;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared quick-fix provider for {@code :Float} {@code #size} bit-width violations
 * ({@code E010-FLOAT_INVALID_SIZE}) and {@code #exact} versus {@code #size} mutual-exclusivity
 * conflicts ({@code E008-MUTUALLY_EXCLUSIVE_BOUNDS}) across {@code StvnMetadataFacetInspection}
 * ({@code Pass B}) and {@code StvnExternalAnnotator} ({@code Pass A}).
 * <p>
 * Every contributed quick-fix is {@code IntentionPreviewInfo.DIFF}-safe, places inserted facets at
 * their canonical § 6.1 5-tier rank without secondary order errors, excises comment-free empty
 * {@code {}} blocks to prevent {@code E003}, and avoids introducing {@code W003} redundant
 * {@code #size 64} warnings or incompatible bound/cardinality errors.
 */
@NullMarked
public final class StvnFloatFacetQuickFixProvider {

    private static final BigInteger SIZE_32 = BigInteger.valueOf(32);
    private static final BigInteger SIZE_64 = BigInteger.valueOf(64);

    private StvnFloatFacetQuickFixProvider() {
    }

    /**
     * Checks whether {@code schemaType} directly declares the primitive {@code :Float} atomic constructor
     * (excluding derived nominal aliases such as {@code :Child { #size 16 } :ParentFloat}, which are
     * governed by {@code E040-ERR_NON_OVERRIDABLE_FACET}).
     *
     * @param schemaType schema type node to inspect, or {@code null}
     * @return {@code true} if {@code schemaType} is a direct {@code :Float} constructor
     */
    public static boolean isDirectPrimitiveFloatSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return false;
        }
        var ctor = schemaType.getSchemaConstructor();
        if (ctor == null || ctor.getAtomicType() == null) {
            return false;
        }
        return StvnVocabulary.TYPE_FLOAT.equals(ctor.getAtomicType().getText().trim());
    }

    /**
     * Resolves an integer literal or {@code #CONST} argument supplied to a {@code #size} facet entry.
     *
     * @param sizeEntry target {@code #size} metadata entry
     * @return resolved {@link BigInteger} bit-width, or {@code null} when absent or unresolvable
     */
    public static @Nullable BigInteger resolveFloatSizeArgument(@NotNull MetadataEntry sizeEntry) {
        var mv = sizeEntry.getMetadataValue();
        if (mv == null) {
            return null;
        }
        String raw = mv.getText().trim();
        if (mv.getIntegerLiteral() == null && !raw.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(sizeEntry, raw).orElse(null);
    }

    /**
     * Determines whether a resolved {@code :Float} {@code #size} argument violates IEEE-754
     * continuous bit-width constraints ({@code 32} or {@code 64} only).
     *
     * @param parsedSize resolved integer bit-width, or {@code null}
     * @return {@code true} when {@code parsedSize} is non-null and neither {@code 32} nor {@code 64}
     */
    public static boolean isInvalidContinuousFloatSize(@Nullable BigInteger parsedSize) {
        return parsedSize != null && !parsedSize.equals(SIZE_32) && !parsedSize.equals(SIZE_64);
    }

    /**
     * Finds the first {@code #exact} {@link MetadataEntry} in {@code metaMap}, or {@code null} if none exists.
     *
     * @param metaMap metadata map to inspect
     * @return first {@code #exact} entry, or {@code null}
     */
    public static @Nullable MetadataEntry findFirstExactEntry(@NotNull MetadataMap metaMap) {
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            if (isExactEntry(entry)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Finds the first {@code #size} {@link MetadataEntry} in {@code metaMap}, or {@code null} if none exists.
     *
     * @param metaMap metadata map to inspect
     * @return first {@code #size} entry, or {@code null}
     */
    public static @Nullable MetadataEntry findFirstSizeEntry(@NotNull MetadataMap metaMap) {
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            if (isSizeEntry(entry)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Checks whether {@code entry} declares the {@code #exact} facet.
     *
     * @param entry metadata entry to inspect
     * @return {@code true} if {@code entry} is {@code #exact}
     */
    public static boolean isExactEntry(@NotNull MetadataEntry entry) {
        if (StvnVocabulary.FACET_KW_EXACT.equals(StvnMetadataOrderInspection.extractFacetKeyword(entry))) {
            return true;
        }
        var bare = entry.getMetadataBareFlag();
        return bare != null && bare.getNode().findChildByType(StvnTypes.KW_EXACT) != null;
    }

    /**
     * Checks whether {@code entry} declares the {@code #size} facet.
     *
     * @param entry metadata entry to inspect
     * @return {@code true} if {@code entry} is {@code #size}
     */
    public static boolean isSizeEntry(@NotNull MetadataEntry entry) {
        return StvnVocabulary.FACET_KW_SIZE.equals(StvnMetadataOrderInspection.extractFacetKeyword(entry))
            || entry.getNode().findChildByType(StvnTypes.KW_SIZE) != null;
    }

    /**
     * Checks whether {@code metaMap} declares continuous-only float bounds ({@code #minExcl} or {@code #maxIncl})
     * that would trigger {@code E011-ERR_DISCRETE_BOUND_KIND_PROHIBITED} under {@code #exact}.
     *
     * @param metaMap metadata map to inspect
     * @return {@code true} if {@code #minExcl} or {@code #maxIncl} is present
     */
    public static boolean hasContinuousOnlyFloatBound(@NotNull MetadataMap metaMap) {
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
            if (StvnVocabulary.FACET_KW_MIN_EXCL.equals(kw)
                    || StvnVocabulary.FACET_KW_MAX_INCL.equals(kw)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether {@code metaMap} declares {@code #maxSize}, which is permitted on {@code :Float}
     * strictly when {@code #exact} is active.
     *
     * @param metaMap metadata map to inspect
     * @return {@code true} if {@code #maxSize} is present
     */
    public static boolean hasExactOnlyMaxSize(@NotNull MetadataMap metaMap) {
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
            if (StvnVocabulary.FACET_KW_MAX_SIZE.equals(kw)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_SIZE) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the three-option remediation suite for standalone invalid {@code :Float} {@code #size <N>}
     * ({@code E010-FLOAT_INVALID_SIZE}):
     * <ol>
     *   <li>{@code Change '#size <N>' to '#size 32'} ({@link PriorityAction.Priority#HIGH})</li>
     *   <li>{@code Use default 64-bit ':Float' (remove '#size <N>')} ({@link PriorityAction.Priority#HIGH})</li>
     *   <li>{@code Replace '#size <N>' with '#exact'} ({@link PriorityAction.Priority#NORMAL}, omitted when
     *       {@code #minExcl} or {@code #maxIncl} is present)</li>
     * </ol>
     *
     * @param metaMap   enclosing metadata map
     * @param sizeEntry offending {@code #size <N>} entry
     * @return ordered array of {@code IntentionPreviewInfo.DIFF}-safe quick-fixes
     */
    public static LocalQuickFix @NotNull [] buildStandaloneInvalidFloatSizeFixes(
            @NotNull MetadataMap metaMap,
            @NotNull MetadataEntry sizeEntry
    ) {
        String sizeSnippet = sizeEntry.getText().trim();
        List<LocalQuickFix> fixes = new ArrayList<>(3);
        fixes.add(new ChangeFloatSizeTo32QuickFix(sizeEntry, sizeSnippet));
        fixes.add(new UseDefault64BitFloatQuickFix(sizeEntry, sizeSnippet));
        if (!hasContinuousOnlyFloatBound(metaMap)) {
            fixes.add(new ReplaceFloatSizeWithExactQuickFix(sizeEntry, sizeSnippet));
        }
        return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    }

    /**
     * Builds the complete bidirectional remediation suite when {@code #exact} and {@code #size}
     * co-occur on {@code :Float} ({@code E008-MUTUALLY_EXCLUSIVE_BOUNDS}), ensuring identical
     * zero-warning options across both {@code #exact} and {@code #size} caret positions.
     *
     * @param metaMap    enclosing metadata map
     * @param exactEntry first {@code #exact} entry in {@code metaMap}
     * @param sizeEntry  first {@code #size} entry in {@code metaMap}
     * @return ordered array of {@code IntentionPreviewInfo.DIFF}-safe quick-fixes
     */
    public static LocalQuickFix @NotNull [] buildExactAndSizeMutexFixes(
            @NotNull MetadataMap metaMap,
            @NotNull MetadataEntry exactEntry,
            @NotNull MetadataEntry sizeEntry
    ) {
        boolean hasContinuousOnlyBound = hasContinuousOnlyFloatBound(metaMap);
        boolean hasExactOnlyMax = hasExactOnlyMaxSize(metaMap);
        BigInteger parsedSize = resolveFloatSizeArgument(sizeEntry);
        String sizeSnippet = sizeEntry.getText().trim();

        List<LocalQuickFix> fixes = new ArrayList<>(3);
        if (!hasContinuousOnlyBound) {
            fixes.add(new RemoveConflictingFloatFacetQuickFix(
                sizeEntry,
                StvnVocabulary.FACET_KW_SIZE,
                PriorityAction.Priority.HIGH
            ));
        }
        if (!hasExactOnlyMax) {
            if (parsedSize != null && parsedSize.equals(SIZE_32)) {
                fixes.add(new RemoveConflictingFloatFacetQuickFix(
                    exactEntry,
                    StvnVocabulary.FACET_KW_EXACT,
                    PriorityAction.Priority.HIGH
                ));
            } else if (parsedSize != null && parsedSize.equals(SIZE_64)) {
                fixes.add(new UseDefault64BitFloatFromMutexQuickFix(
                    metaMap,
                    sizeSnippet,
                    PriorityAction.Priority.HIGH
                ));
                fixes.add(new ReplaceMutexWithSize32QuickFix(
                    metaMap,
                    PriorityAction.Priority.NORMAL
                ));
            } else {
                fixes.add(new ReplaceMutexWithSize32QuickFix(
                    metaMap,
                    PriorityAction.Priority.HIGH
                ));
                fixes.add(new UseDefault64BitFloatFromMutexQuickFix(
                    metaMap,
                    sizeSnippet,
                    PriorityAction.Priority.NORMAL
                ));
            }
        }
        return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    }

    private static final class ChangeFloatSizeTo32QuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String sizeSnippet;

        ChangeFloatSizeTo32QuickFix(@NotNull MetadataEntry sizeEntry, String sizeSnippet) {
            super(sizeEntry);
            this.sizeSnippet = sizeSnippet;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Change '" + sizeSnippet + "' to '" + StvnVocabulary.FACET_KW_SIZE + " 32'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Change continuous " + StvnVocabulary.TYPE_FLOAT + " " + StvnVocabulary.FACET_KW_SIZE + " to 32";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap map = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            Document doc = file.getViewProvider().getDocument();
            if (map == null || doc == null) {
                return;
            }
            StvnMetadataFacetInsertionUtil.insertOrReplaceSingleFacet(
                doc,
                map,
                StvnVocabulary.FACET_KW_SIZE,
                StvnVocabulary.FACET_KW_SIZE + " 32"
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ChangeFloatSizeTo32QuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(sizeSnippet, other.sizeSnippet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), sizeSnippet);
        }
    }

    private static final class UseDefault64BitFloatQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String sizeSnippet;

        UseDefault64BitFloatQuickFix(@NotNull MetadataEntry sizeEntry, String sizeSnippet) {
            super(sizeEntry);
            this.sizeSnippet = sizeSnippet;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Use default 64-bit '" + StvnVocabulary.TYPE_FLOAT + "' (remove '" + sizeSnippet + "')";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Use default 64-bit " + StvnVocabulary.TYPE_FLOAT;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            RemoveRedundantFacetQuickFix.executeRemoval(startElement);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof UseDefault64BitFloatQuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(sizeSnippet, other.sizeSnippet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), sizeSnippet);
        }
    }

    private static final class ReplaceFloatSizeWithExactQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String sizeSnippet;

        ReplaceFloatSizeWithExactQuickFix(@NotNull MetadataEntry sizeEntry, String sizeSnippet) {
            super(sizeEntry);
            this.sizeSnippet = sizeSnippet;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Replace '" + sizeSnippet + "' with '" + StvnVocabulary.FACET_KW_EXACT + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Replace invalid " + StvnVocabulary.TYPE_FLOAT + " " + StvnVocabulary.FACET_KW_SIZE
                + " with " + StvnVocabulary.FACET_KW_EXACT;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.NORMAL;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap map = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            Document doc = file.getViewProvider().getDocument();
            if (map == null || doc == null) {
                return;
            }
            StvnMetadataFacetInsertionUtil.insertOrReplaceSingleFacetRemoving(
                doc,
                map,
                StvnVocabulary.FACET_KW_EXACT,
                StvnVocabulary.FACET_KW_EXACT,
                Set.of(StvnVocabulary.FACET_KW_SIZE)
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ReplaceFloatSizeWithExactQuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(sizeSnippet, other.sizeSnippet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), sizeSnippet);
        }
    }

    private static final class RemoveConflictingFloatFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String facetKeyword;
        @SafeFieldForPreview
        private final Priority priority;

        RemoveConflictingFloatFacetQuickFix(
                @NotNull MetadataEntry targetEntry,
                String facetKeyword,
                Priority priority
        ) {
            super(targetEntry);
            this.facetKeyword = facetKeyword;
            this.priority = priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Remove conflicting facet '" + facetKeyword + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Remove conflicting facet";
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            RemoveRedundantFacetQuickFix.executeRemoval(startElement);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RemoveConflictingFloatFacetQuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(facetKeyword, other.facetKeyword);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), facetKeyword);
        }
    }

    private static final class UseDefault64BitFloatFromMutexQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String sizeSnippet;
        @SafeFieldForPreview
        private final Priority priority;

        UseDefault64BitFloatFromMutexQuickFix(
                @NotNull MetadataMap metaMap,
                String sizeSnippet,
                Priority priority
        ) {
            super(metaMap);
            this.sizeSnippet = sizeSnippet;
            this.priority = priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Use default 64-bit '" + StvnVocabulary.TYPE_FLOAT + "' (remove '"
                + StvnVocabulary.FACET_KW_EXACT + "' and '" + sizeSnippet + "')";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Use default 64-bit " + StvnVocabulary.TYPE_FLOAT;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap map = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            if (map == null || !map.isValid()) {
                return;
            }
            List<MetadataEntry> toDelete = new ArrayList<>();
            for (MetadataEntry entry : map.getMetadataEntryList()) {
                if (isExactEntry(entry) || isSizeEntry(entry)) {
                    toDelete.add(entry);
                }
            }
            toDelete.sort(Comparator.comparingInt((MetadataEntry e) -> e.getTextRange().getStartOffset()).reversed());
            for (MetadataEntry entry : toDelete) {
                if (entry.isValid()) {
                    RemoveRedundantFacetQuickFix.executeRemoval(entry);
                }
            }
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof UseDefault64BitFloatFromMutexQuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(sizeSnippet, other.sizeSnippet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), sizeSnippet);
        }
    }

    private static final class ReplaceMutexWithSize32QuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final Priority priority;

        ReplaceMutexWithSize32QuickFix(
                @NotNull MetadataMap metaMap,
                Priority priority
        ) {
            super(metaMap);
            this.priority = priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Replace with '" + StvnVocabulary.FACET_KW_SIZE + " 32' (remove '"
                + StvnVocabulary.FACET_KW_EXACT + "')";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Replace conflicting float facets with " + StvnVocabulary.FACET_KW_SIZE + " 32";
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap map = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            Document doc = file.getViewProvider().getDocument();
            if (map == null || doc == null) {
                return;
            }
            StvnMetadataFacetInsertionUtil.insertOrReplaceSingleFacetRemoving(
                doc,
                map,
                StvnVocabulary.FACET_KW_SIZE,
                StvnVocabulary.FACET_KW_SIZE + " 32",
                Set.of(StvnVocabulary.FACET_KW_EXACT)
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ReplaceMutexWithSize32QuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getStartElement());
        }
    }
}
