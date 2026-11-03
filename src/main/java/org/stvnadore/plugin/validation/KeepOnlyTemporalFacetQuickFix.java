package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dedicated quick-fix and intention action that resolves mutually exclusive temporal mode
 * ({@code #offset}, {@code #zoned}, {@code #audited}) or scale ({@code #s}, {@code #ms}, {@code #us}, {@code #ns})
 * conflicts by keeping only the selected facet and removing all other conflicting facets in the same exclusivity
 * group from the enclosing {@link MetadataMap}.
 */
@NullMarked
public final class KeepOnlyTemporalFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    private static final Set<String> MODE_FACETS = Set.of(
        StvnVocabulary.FACET_KW_OFFSET,
        StvnVocabulary.FACET_KW_ZONED,
        StvnVocabulary.FACET_KW_AUDITED
    );

    private static final Set<String> SCALE_FACETS = Set.of(
        StvnVocabulary.FACET_KW_SCALE_S,
        StvnVocabulary.FACET_KW_SCALE_MS,
        StvnVocabulary.FACET_KW_SCALE_US,
        StvnVocabulary.FACET_KW_SCALE_NS
    );

    private final String keptFacet;
    private final List<String> removedFacets;
    private final boolean modeGroup;

    /**
     * Constructs a new {@code KeepOnlyTemporalFacetQuickFix}.
     *
     * @param element the PSI element representing the clicked temporal metadata entry
     * @param keptFacet the facet keyword to retain (e.g., {@code "#offset"})
     * @param removedFacets the conflicting facet keywords to remove (e.g., {@code ["#zoned", "#audited"]})
     * @param modeGroup {@code true} if resolving a {@code :DateTime} mode conflict; {@code false} if resolving a {@code :TimeEpoch} scale conflict
     */
    public KeepOnlyTemporalFacetQuickFix(
            @NotNull PsiElement element,
            @NotNull String keptFacet,
            @NotNull List<String> removedFacets,
            boolean modeGroup
    ) {
        super(element);
        this.keptFacet = keptFacet;
        this.removedFacets = List.copyOf(removedFacets);
        this.modeGroup = modeGroup;
    }

    @Override
    public @Nls @NotNull String getText() {
        if (removedFacets.isEmpty()) {
            return "Keep only '" + keptFacet + "'";
        }
        String removedList = removedFacets.stream()
                .map(f -> "'" + f + "'")
                .collect(Collectors.joining(", "));
        return "Keep only '" + keptFacet + "' (remove " + removedList + ")";
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Keep only selected temporal facet";
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
        return RemoveRedundantFacetQuickFix.resolveMetadataEntry(startElement) != null;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var clickedEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(startElement);
        if (clickedEntry == null || !clickedEntry.isValid()) {
            return;
        }
        var metaMap = PsiTreeUtil.getParentOfType(clickedEntry, MetadataMap.class);
        if (metaMap == null || !metaMap.isValid()) {
            return;
        }

        Set<String> targetGroup = modeGroup ? MODE_FACETS : SCALE_FACETS;
        List<MetadataEntry> toRemove = new ArrayList<>();
        boolean keptSeen = false;

        for (var entry : metaMap.getMetadataEntryList()) {
            String kw = extractTemporalKeyword(entry);
            if (targetGroup.contains(kw)) {
                if (kw.equals(keptFacet) && !keptSeen) {
                    keptSeen = true;
                } else {
                    toRemove.add(entry);
                }
            }
        }

        for (var entry : toRemove) {
            if (entry.isValid()) {
                RemoveRedundantFacetQuickFix.executeRemoval(entry);
            }
        }
    }

    /**
     * Checks whether the given {@link MetadataEntry} declares a {@code :DateTime} mode facet.
     *
     * @param entry the metadata entry to inspect
     * @return {@code true} if the entry is {@code #offset}, {@code #zoned}, or {@code #audited}
     */
    public static boolean isModeEntry(@NotNull MetadataEntry entry) {
        return MODE_FACETS.contains(extractTemporalKeyword(entry));
    }

    /**
     * Checks whether the given {@link MetadataEntry} declares a {@code :TimeEpoch} scale facet.
     *
     * @param entry the metadata entry to inspect
     * @return {@code true} if the entry is {@code #s}, {@code #ms}, {@code #us}, or {@code #ns}
     */
    public static boolean isScaleEntry(@NotNull MetadataEntry entry) {
        return SCALE_FACETS.contains(extractTemporalKeyword(entry));
    }

    /**
     * Extracts the canonical temporal mode or scale keyword from a {@link MetadataEntry}.
     *
     * @param entry the target metadata entry
     * @return the canonical keyword string (e.g., {@code "#offset"} or {@code "#ms"})
     */
    public static @NotNull String extractTemporalKeyword(@NotNull MetadataEntry entry) {
        var node = entry.getNode();
        var bare = entry.getMetadataBareFlag();
        var bareNode = bare != null ? bare.getNode() : null;
        if (node.findChildByType(StvnTypes.KW_OFFSET) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_OFFSET) != null)) {
            return StvnVocabulary.FACET_KW_OFFSET;
        }
        if (node.findChildByType(StvnTypes.KW_ZONED) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_ZONED) != null)) {
            return StvnVocabulary.FACET_KW_ZONED;
        }
        if (node.findChildByType(StvnTypes.KW_AUDITED) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_AUDITED) != null)) {
            return StvnVocabulary.FACET_KW_AUDITED;
        }
        if (node.findChildByType(StvnTypes.KW_SCALE_S) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_SCALE_S) != null)) {
            return StvnVocabulary.FACET_KW_SCALE_S;
        }
        if (node.findChildByType(StvnTypes.KW_SCALE_MS) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_SCALE_MS) != null)) {
            return StvnVocabulary.FACET_KW_SCALE_MS;
        }
        if (node.findChildByType(StvnTypes.KW_SCALE_US) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_SCALE_US) != null)) {
            return StvnVocabulary.FACET_KW_SCALE_US;
        }
        if (node.findChildByType(StvnTypes.KW_SCALE_NS) != null
                || (bareNode != null && bareNode.findChildByType(StvnTypes.KW_SCALE_NS) != null)) {
            return StvnVocabulary.FACET_KW_SCALE_NS;
        }
        String text = entry.getText().trim();
        int spaceIdx = text.indexOf(' ');
        return spaceIdx > 0 ? text.substring(0, spaceIdx) : text;
    }
}
