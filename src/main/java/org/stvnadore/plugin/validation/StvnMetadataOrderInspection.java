package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.Visitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Validates the canonical 5-Tier Decimal Hierarchy of metadata facets (§ 6.1).
 * <p>
 * Evaluates facet sequence in all metadata blocks:
 * <ul>
 *   <li><b>Tier 1 (Definition):</b> #unsigned (1.1.1) &rarr; #exact (1.1.2) &rarr; #invertible (1.1.3) &rarr; #ns..#s (1.2.1..1.2.4) &rarr; #offset..#audited (1.2.5..1.2.7) &rarr; #size (1.3.1) &rarr; #minSize (1.5.1) &rarr; #maxSize (1.5.2)</li>
 *   <li><b>Tier 2 (Trait):</b> #equatable (2.1) &rarr; #comparable (2.2)</li>
 *   <li><b>Tier 3 (Bounds):</b> #minIncl (3.1) &rarr; #minExcl (3.2) &rarr; #maxIncl (3.3) &rarr; #maxExcl (3.4)</li>
 *   <li><b>Tier 4 (Constraint):</b> #filterIncl (4.1) &rarr; #filterExcl (4.2) &rarr; #preserveIndent (4.3) &rarr; #regex (4.4)</li>
 *   <li><b>Tier 5 (Directive):</b> #strip (5.0)</li>
 * </ul>
 */
@NullMarked
public final class StvnMetadataOrderInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnMetadataOrderInspection instance.
     */
    public StvnMetadataOrderInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnMetadataOrder";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Metadata facet canonical ordering inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitMetaEntry(@NotNull MetaEntry metaEntry) {
                super.visitMetaEntry(metaEntry);
                if (!StvnMetaTemplateUtil.isPrimaryMetaEntry(metaEntry)) {
                    return;
                }
                MetaBlock metaBlock = metaEntry.getMetaBlock();
                if (metaBlock == null) {
                    return;
                }
                List<MetaElement> elements = metaBlock.getMetaElementList();
                if (elements.size() <= 1) {
                    return;
                }
                int maxRankSeen = -1;
                Set<Integer> seenTiers = new HashSet<>();
                Set<MetaElement> flaggedMetaElements = new HashSet<>();
                var reorderMetaFix = new StvnReorderMetaEntriesQuickFix(metaBlock);
                for (MetaElement element : elements) {
                    int rank = getMetaElementRank(element);
                    if (rank > 3) {
                        continue;
                    }
                    if (!seenTiers.add(rank)) {
                        continue;
                    }
                    if (rank < maxRankSeen) {
                        flaggedMetaElements.add(element);
                        String keyword = extractMetaElementKeyword(element);
                        holder.registerProblem(
                            element,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Metadata facet '" + keyword + "' violates 3-tier monotonic progression ("
                                    + StvnVocabulary.FACET_KW_META_NAME + " -> "
                                    + StvnVocabulary.FACET_KW_META_DOMAIN + " -> "
                                    + StvnVocabulary.FACET_KW_META_KIND + ")",
                                StvnDiagnosticCodes.ERR_FACET_ORDER_VIOLATION
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            reorderMetaFix
                        );
                    } else {
                        maxRankSeen = rank;
                    }
                }
                if (!flaggedMetaElements.isEmpty() && isOnTheFly) {
                    String anchorMsg = reorderMetaFix.getFamilyName();
                    registerChildTokenAnchor(holder, metaEntry.getNode(), StvnTypes.KW_META, anchorMsg, reorderMetaFix);
                    registerChildTokenAnchor(holder, metaBlock.getNode(), StvnTypes.LBRACE, anchorMsg, reorderMetaFix);
                    registerChildTokenAnchor(holder, metaBlock.getNode(), StvnTypes.RBRACE, anchorMsg, reorderMetaFix);
                    for (MetaElement element : elements) {
                        if (!flaggedMetaElements.contains(element)) {
                            holder.registerProblem(element, anchorMsg, ProblemHighlightType.INFORMATION, reorderMetaFix);
                        }
                    }
                }
            }

            @Override
            public void visitMetadataMap(@NotNull MetadataMap metadataMap) {
                super.visitMetadataMap(metadataMap);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(metadataMap)) {
                    return;
                }
                List<MetadataEntry> entries = metadataMap.getMetadataEntryList();
                if (entries.size() <= 1) {
                    return;
                }

                boolean canReorder = !PsiTreeUtil.hasErrorElements(metadataMap)
                    && !hasIncompleteTraitEntry(metadataMap);

                int currentMaxRank = -1;
                Set<Integer> seenMutexGroups = new HashSet<>();
                Set<MetadataEntry> flaggedEntries = new HashSet<>();
                var reorderFacetFix = canReorder ? new StvnReorderMetadataFacetsQuickFix(metadataMap) : null;
                var fixes = reorderFacetFix == null
                    ? LocalQuickFix.EMPTY_ARRAY
                    : new LocalQuickFix[]{reorderFacetFix};
                for (MetadataEntry entry : entries) {
                    if (!StvnMetadataFacetAllowlist.isFacetPermittedForOrdering(entry, metadataMap)) {
                        continue;
                    }
                    String keyword = extractFacetKeyword(entry);
                    int rank = getFacetRank(keyword);
                    int mutexGroup = getMutexGroupId(keyword);
                    if (mutexGroup > 0 && !seenMutexGroups.add(mutexGroup)) {
                        continue;
                    }
                    if (rank < currentMaxRank) {
                        flaggedEntries.add(entry);
                        PsiElement target = entry.getFirstChild() != null ? entry.getFirstChild() : entry;
                        holder.registerProblem(
                            target,
                            StvnDiagnosticFormatter.formatPlugin("Metadata facet '" + keyword + "' violates canonical 5-tier order", StvnDiagnosticCodes.ERR_FACET_ORDER_VIOLATION),
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                    } else {
                        currentMaxRank = rank;
                    }
                }
                if (!flaggedEntries.isEmpty() && isOnTheFly && reorderFacetFix != null) {
                    String anchorMsg = reorderFacetFix.getFamilyName();
                    PsiElement ownerAnchor = resolveMetadataMapOwnerTarget(metadataMap);
                    if (ownerAnchor != null) {
                        holder.registerProblem(ownerAnchor, anchorMsg, ProblemHighlightType.INFORMATION, reorderFacetFix);
                    }
                    registerChildTokenAnchor(holder, metadataMap.getNode(), StvnTypes.LBRACE, anchorMsg, reorderFacetFix);
                    registerChildTokenAnchor(holder, metadataMap.getNode(), StvnTypes.RBRACE, anchorMsg, reorderFacetFix);
                    for (MetadataEntry entry : entries) {
                        if (!flaggedEntries.contains(entry)) {
                            holder.registerProblem(entry, anchorMsg, ProblemHighlightType.INFORMATION, reorderFacetFix);
                        }
                    }
                }
            }
        };
    }

    private static void registerChildTokenAnchor(
            ProblemsHolder holder,
            ASTNode parentNode,
            com.intellij.psi.tree.IElementType tokenType,
            String description,
            LocalQuickFix fix
    ) {
        ASTNode childNode = parentNode.findChildByType(tokenType);
        if (childNode != null && childNode.getPsi() != null) {
            holder.registerProblem(childNode.getPsi(), description, ProblemHighlightType.INFORMATION, fix);
        }
    }

    /**
     * Resolves the owning declaration target token ({@code :TypeName}, {@code #CONST}, or {@code :type})
     * for a schema {@link MetadataMap}.
     *
     * @param metadataMap target metadata map
     * @return owning declaration keyword PSI element, or {@code null} for anonymous nested constructors
     */
    public static @Nullable PsiElement resolveMetadataMapOwnerTarget(MetadataMap metadataMap) {
        PsiElement parent = metadataMap.getParent();
        if (parent instanceof TypeDefinition typeDef) {
            return typeDef.getTypeDefTarget();
        }
        if (parent instanceof ConstantDefinition constDef) {
            return constDef.getValueKeyword();
        }
        if (parent instanceof SchemaType schemaType) {
            PsiElement grandParent = schemaType.getParent();
            if (grandParent instanceof TypeDefinition typeDef) {
                return typeDef.getTypeDefTarget();
            }
            if (grandParent instanceof ConstantDefinition constDef) {
                return constDef.getValueKeyword();
            }
            if (grandParent instanceof TypeEntry typeEntry) {
                ASTNode kwNode = typeEntry.getNode().findChildByType(StvnTypes.KW_TYPE);
                return kwNode != null ? kwNode.getPsi() : null;
            }
        } else if (parent instanceof TypeEntry typeEntry) {
            ASTNode kwNode = typeEntry.getNode().findChildByType(StvnTypes.KW_TYPE);
            return kwNode != null ? kwNode.getPsi() : null;
        }
        return null;
    }

    /**
     * Checks whether any entry inside {@code map} is a bare incomplete boolean or trait facet
     * missing its required boolean argument.
     *
     * @param map metadata map to inspect
     * @return {@code true} if an incomplete trait or boolean facet is present; {@code false} otherwise
     */
    public static boolean hasIncompleteTraitEntry(MetadataMap map) {
        for (MetadataEntry entry : map.getMetadataEntryList()) {
            if ((entry.getMetadataBool() != null && entry.getMetadataBool().getMetadataValue() == null)
                    || (entry.getMetadataTrait() != null && entry.getMetadataTrait().getMetadataValue() == null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends the enclosing {@link StvnReorderMetaEntriesQuickFix} or {@link StvnReorderMetadataFacetsQuickFix}
     * to {@code baseFixes} whenever {@code anchor} resides inside a disordered primary {@link MetaEntry}
     * or disordered {@link MetadataMap}, ensuring co-located {@code ERROR} descriptors never hide the reorder action.
     *
     * @param anchor    target PSI element receiving an {@code ERROR} descriptor
     * @param baseFixes base quick-fixes for the diagnostic
     * @return merged quick-fix array including the deduplicated reorder quick-fix when disordered
     */
    public static LocalQuickFix @NotNull [] appendReorderFixIfDisordered(
            @NotNull PsiElement anchor,
            LocalQuickFix @NotNull ... baseFixes
    ) {
        if (StvnMetaTemplateUtil.isInSecondaryRootSection(anchor)) {
            return baseFixes;
        }
        MetaEntry metaEntry = anchor instanceof MetaEntry me
            ? me
            : PsiTreeUtil.getParentOfType(anchor, MetaEntry.class, false);
        if (metaEntry != null && StvnMetaTemplateUtil.isPrimaryMetaEntry(metaEntry)) {
            MetaBlock metaBlock = metaEntry.getMetaBlock();
            if (metaBlock != null
                    && !PsiTreeUtil.hasErrorElements(metaBlock)
                    && isMetaBlockDisordered(metaBlock)) {
                for (LocalQuickFix existing : baseFixes) {
                    if (existing instanceof StvnReorderMetaEntriesQuickFix) {
                        return baseFixes;
                    }
                }
                List<LocalQuickFix> merged = new ArrayList<>(baseFixes.length + 1);
                merged.addAll(Arrays.asList(baseFixes));
                merged.add(new StvnReorderMetaEntriesQuickFix(metaBlock));
                return merged.toArray(LocalQuickFix.EMPTY_ARRAY);
            }
        }
        MetadataMap metaMap = anchor instanceof MetadataMap mm
            ? mm
            : PsiTreeUtil.getParentOfType(anchor, MetadataMap.class, false);
        if (metaMap != null && isMetadataMapDisordered(metaMap)) {
            for (LocalQuickFix existing : baseFixes) {
                if (existing instanceof StvnReorderMetadataFacetsQuickFix) {
                    return baseFixes;
                }
            }
            List<LocalQuickFix> merged = new ArrayList<>(baseFixes.length + 1);
            merged.addAll(Arrays.asList(baseFixes));
            merged.add(new StvnReorderMetadataFacetsQuickFix(metaMap));
            return merged.toArray(LocalQuickFix.EMPTY_ARRAY);
        }
        return baseFixes;
    }

    /**
     * Determines whether the entries in a {@code :meta} block violate the canonical
     * {@code #name -> #domain -> #kind} order.
     *
     * @param metaBlock the {@code :meta} block PSI node
     * @return {@code true} if any entry is out of canonical order, {@code false} otherwise
     */
    public static boolean isMetaBlockDisordered(MetaBlock metaBlock) {
        List<MetaElement> elements = metaBlock.getMetaElementList();
        if (elements.size() <= 1) {
            return false;
        }
        int maxRank = -1;
        for (MetaElement element : elements) {
            int rank = getMetaElementRank(element);
            if (rank > 3) {
                continue;
            }
            if (rank < maxRank) {
                return true;
            }
            maxRank = rank;
        }
        return false;
    }

    /**
     * Computes the canonical rank of a {@code :meta} element:
     * {@code #name} = 1, {@code #domain} = 2, {@code #kind} = 3.
     *
     * @param element the {@code :meta} element PSI node
     * @return the canonical integer rank (1..4)
     */
    public static int getMetaElementRank(MetaElement element) {
        if (element.getMetaNameFacet() != null) {
            return 1;
        }
        if (element.getMetaDomainFacet() != null) {
            return 2;
        }
        if (element.getMetaKindFacet() != null) {
            return 3;
        }
        return 4;
    }

    /**
     * Extracts the leading keyword from a {@code :meta} element.
     *
     * @param element the {@code :meta} element PSI node
     * @return {@code "#name"}, {@code "#domain"}, or {@code "#kind"}
     */
    public static String extractMetaElementKeyword(MetaElement element) {
        if (element.getMetaNameFacet() != null) {
            return StvnVocabulary.FACET_KW_META_NAME;
        }
        if (element.getMetaDomainFacet() != null) {
            return StvnVocabulary.FACET_KW_META_DOMAIN;
        }
        if (element.getMetaKindFacet() != null) {
            return StvnVocabulary.FACET_KW_META_KIND;
        }
        String text = element.getText().trim();
        int spaceIdx = text.indexOf(' ');
        return spaceIdx > 0 ? text.substring(0, spaceIdx) : text;
    }

    /**
     * Determines whether the entries in a metadata map violate the canonical 5-tier order.
     *
     * @param map the metadata map PSI node
     * @return {@code true} if any facet is out of order, {@code false} otherwise
     */
    public static boolean isDisordered(MetadataMap map) {
        return isMetadataMapDisordered(map);
    }

    /**
     * Determines whether a metadata map has at least one 5-tier order violation and is eligible
     * for comment-preserving 5-tier facet reordering.
     *
     * @param map the metadata map PSI node
     * @return {@code true} if the map is syntactically complete and contains a 5-tier order violation
     */
    public static boolean isMetadataMapDisordered(MetadataMap map) {
        List<MetadataEntry> entries = map.getMetadataEntryList();
        if (entries.size() <= 1 || PsiTreeUtil.hasErrorElements(map) || hasIncompleteTraitEntry(map)) {
            return false;
        }
        int maxRank = -1;
        Set<Integer> seenMutexGroups = new HashSet<>();
        for (MetadataEntry entry : entries) {
            if (!StvnMetadataFacetAllowlist.isFacetPermittedForOrdering(entry, map)) {
                continue;
            }
            String keyword = extractFacetKeyword(entry);
            int mutexGroup = getMutexGroupId(keyword);
            if (mutexGroup > 0 && !seenMutexGroups.add(mutexGroup)) {
                continue;
            }
            int rank = getFacetRank(keyword);
            if (rank < maxRank) {
                return true;
            }
            maxRank = rank;
        }
        return false;
    }

    /**
     * Returns the mutual-exclusivity group identifier (1..6) for the given facet keyword,
     * or {@code 0} when the facet does not belong to a same-block mutual-exclusivity group.
     *
     * @param keyword the facet keyword string
     * @return mutex group identifier in {@code 1..6}, or {@code 0}
     */
    public static int getMutexGroupId(String keyword) {
        if (StvnVocabulary.FACET_KW_SCALE_NS.equals(keyword)
                || StvnVocabulary.FACET_KW_SCALE_US.equals(keyword)
                || StvnVocabulary.FACET_KW_SCALE_MS.equals(keyword)
                || StvnVocabulary.FACET_KW_SCALE_S.equals(keyword)) {
            return 1;
        }
        if (StvnVocabulary.FACET_KW_OFFSET.equals(keyword)
                || StvnVocabulary.FACET_KW_ZONED.equals(keyword)
                || StvnVocabulary.FACET_KW_AUDITED.equals(keyword)) {
            return 2;
        }
        if (StvnVocabulary.FACET_KW_SIZE.equals(keyword)
                || StvnVocabulary.FACET_KW_EXACT.equals(keyword)) {
            return 3;
        }
        if (StvnVocabulary.FACET_KW_MIN_INCL.equals(keyword)
                || StvnVocabulary.FACET_KW_MIN_EXCL.equals(keyword)) {
            return 4;
        }
        if (StvnVocabulary.FACET_KW_MAX_INCL.equals(keyword)
                || StvnVocabulary.FACET_KW_MAX_EXCL.equals(keyword)) {
            return 5;
        }
        if (StvnVocabulary.FACET_KW_FILTER_INCL.equals(keyword)
                || StvnVocabulary.FACET_KW_FILTER_EXCL.equals(keyword)) {
            return 6;
        }
        return 0;
    }

    /**
     * Creates a stateful AST-node ranker that pins 2nd+ members of the same mutual-exclusivity group
     * to the first member's rank so reverse-subtier conflicts preserve relative source order.
     *
     * @return a stateful rank function over {@link ASTNode} children of a metadata map
     */
    public static ToIntFunction<ASTNode> createMutexStabilizedEntryRanker() {
        Map<Integer, Integer> firstRankByGroup = new HashMap<>();
        return node -> {
            if (node.getPsi() instanceof MetadataEntry entry) {
                String keyword = extractFacetKeyword(entry);
                int rawRank = getFacetRank(keyword);
                int group = getMutexGroupId(keyword);
                if (group == 0) {
                    return rawRank;
                }
                Integer existing = firstRankByGroup.get(group);
                if (existing != null) {
                    return existing;
                }
                firstRankByGroup.put(group, rawRank);
                return rawRank;
            }
            return 500;
        };
    }

    /**
     * Extracts the leading facet keyword from a metadata entry.
     *
     * @param entry the metadata entry
     * @return the facet keyword (e.g. "#unsigned", "#size", "#minIncl")
     */
    public static String extractFacetKeyword(MetadataEntry entry) {
        String text = entry.getText().trim();
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '[' || c == '{' || c == '(') {
                end = i;
                break;
            }
        }
        return text.substring(0, end);
    }

    /**
     * Computes the numerical sorting rank of a facet keyword according to
     * the canonical 5-Tier Decimal Hierarchy (§ 6.1).
     *
     * @param keyword the facet keyword string
     * @return a scaled integer representing the relative canonical decimal position
     */
    public static int getFacetRank(String keyword) {
        return switch (keyword) {
            // Tier 1: Definition (1.1.1 - 1.5.2)
            case StvnVocabulary.FACET_KW_UNSIGNED -> 1110;       // 1.1.1
            case StvnVocabulary.FACET_KW_EXACT -> 1120;          // 1.1.2
            case StvnVocabulary.FACET_KW_INVERTIBLE -> 1130;     // 1.1.3
            case StvnVocabulary.FACET_KW_SCALE_NS -> 1210;       // 1.2.1
            case StvnVocabulary.FACET_KW_SCALE_US -> 1220;       // 1.2.2
            case StvnVocabulary.FACET_KW_SCALE_MS -> 1230;       // 1.2.3
            case StvnVocabulary.FACET_KW_SCALE_S -> 1240;        // 1.2.4
            case StvnVocabulary.FACET_KW_OFFSET -> 1250;         // 1.2.5
            case StvnVocabulary.FACET_KW_ZONED -> 1260;          // 1.2.6
            case StvnVocabulary.FACET_KW_AUDITED -> 1270;        // 1.2.7
            case StvnVocabulary.FACET_KW_SIZE -> 1310;           // 1.3.1
            case StvnVocabulary.FACET_KW_MIN_SIZE -> 1510;       // 1.5.1
            case StvnVocabulary.FACET_KW_MAX_SIZE -> 1520;       // 1.5.2

            // Tier 2: Trait (2.1 - 2.2)
            case StvnVocabulary.FACET_KW_EQUATABLE -> 2100;      // 2.1
            case StvnVocabulary.FACET_KW_COMPARABLE -> 2200;     // 2.2

            // Tier 3: Bounds (3.1 - 3.4)
            case StvnVocabulary.FACET_KW_MIN_INCL -> 3100;       // 3.1
            case StvnVocabulary.FACET_KW_MIN_EXCL -> 3200;       // 3.2
            case StvnVocabulary.FACET_KW_MAX_INCL -> 3300;       // 3.3
            case StvnVocabulary.FACET_KW_MAX_EXCL -> 3400;       // 3.4

            // Tier 4: Constraint (4.1 - 4.4)
            case StvnVocabulary.FACET_KW_FILTER_INCL -> 4100;    // 4.1
            case StvnVocabulary.FACET_KW_FILTER_EXCL -> 4200;    // 4.2
            case StvnVocabulary.FACET_KW_PRESERVE_INDENT -> 4300;// 4.3
            case StvnVocabulary.FACET_KW_REGEX -> 4400;          // 4.4

            // Tier 5: Directive (5.0)
            case StvnVocabulary.FACET_KW_STRIP -> 5000;          // 5.0

            default -> 99999;
        };
    }
}
