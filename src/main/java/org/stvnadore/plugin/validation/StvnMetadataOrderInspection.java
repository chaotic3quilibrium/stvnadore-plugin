package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.Visitor;

import java.util.List;

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
            public void visitMetadataMap(@NotNull MetadataMap metadataMap) {
                super.visitMetadataMap(metadataMap);
                List<MetadataEntry> entries = metadataMap.getMetadataEntryList();
                if (entries.size() <= 1) {
                    return;
                }

                int currentMaxRank = -1;
                for (MetadataEntry entry : entries) {
                    String keyword = extractFacetKeyword(entry);
                    int rank = getFacetRank(keyword);
                    if (rank < currentMaxRank) {
                        PsiElement target = entry.getFirstChild() != null ? entry.getFirstChild() : entry;
                        holder.registerProblem(
                            target,
                            "Metadata facet '" + keyword + "' violates canonical 5-tier order (ERR_FACET_ORDER_VIOLATION).",
                            ProblemHighlightType.GENERIC_ERROR,
                            new StvnReorderMetadataFacetsQuickFix(metadataMap)
                        );
                    } else {
                        currentMaxRank = rank;
                    }
                }
            }
        };
    }

    /**
     * Determines whether the entries in a metadata map violate the canonical 5-tier order.
     *
     * @param map the metadata map PSI node
     * @return true if any facet is out of order, false otherwise
     */
    public static boolean isDisordered(MetadataMap map) {
        List<MetadataEntry> entries = map.getMetadataEntryList();
        if (entries.size() <= 1) {
            return false;
        }
        int maxRank = -1;
        for (MetadataEntry entry : entries) {
            int rank = getFacetRank(extractFacetKeyword(entry));
            if (rank < maxRank) {
                return true;
            }
            maxRank = rank;
        }
        return false;
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
