package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.Visitor;

import java.util.List;

/**
 * Validates the canonical 7-tier Semantic Category Order of metadata facets.
 * <p>
 * Evaluates facet sequence in all metadata blocks:
 * <ul>
 *   <li><b>Tier 1 (Flags & Intrinsic Modes):</b> #unsigned &rarr; #exact &rarr; #invertible &rarr; #preserveIndent &rarr; #offset &rarr; #zoned &rarr; #audited &rarr; #equatable &rarr; #comparable</li>
 *   <li><b>Tier 2 (Temporal Scale):</b> #s &rarr; #ms &rarr; #us &rarr; #ns</li>
 *   <li><b>Tier 3 (Dimensions):</b> #size &rarr; #minSize &rarr; #maxSize</li>
 *   <li><b>Tier 4 (Intervals):</b> #minIncl &rarr; #minExcl &rarr; #maxExcl &rarr; #maxIncl</li>
 *   <li><b>Tier 5 (Patterns):</b> #regex</li>
 *   <li><b>Tier 6 (Subsets):</b> #filterIncl &rarr; #filterExcl</li>
 *   <li><b>Tier 7 (Directives):</b> #strip</li>
 * </ul>
 * </p>
 */
@NullMarked
public final class StvnMetadataOrderInspection extends LocalInspectionTool {

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
                            "Metadata facet '" + keyword + "' is out of canonical 7-tier order.",
                            ProblemHighlightType.WEAK_WARNING,
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
     * Determines whether the entries in a metadata map violate the canonical 7-tier order.
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
     * Computes the numerical sorting rank of a facet keyword according to the
     * canonical 7-tier Semantic Category Order hierarchy.
     *
     * @param keyword the facet keyword string
     * @return an integer representing the relative canonical position
     */
    public static int getFacetRank(String keyword) {
        return switch (keyword) {
            // Tier 1: Flags & Intrinsic Modes (101-109)
            case "#unsigned" -> 101;
            case "#exact" -> 102;
            case "#invertible" -> 103;
            case "#preserveIndent" -> 104;
            case "#offset" -> 105;
            case "#zoned" -> 106;
            case "#audited" -> 107;
            case "#equatable" -> 108;
            case "#comparable" -> 109;

            // Tier 2: Temporal Scale (201-204)
            case "#s" -> 201;
            case "#ms" -> 202;
            case "#us" -> 203;
            case "#ns" -> 204;

            // Tier 3: Dimensions (301-303)
            case "#size" -> 301;
            case "#minSize" -> 302;
            case "#maxSize" -> 303;

            // Tier 4: Intervals (401-404)
            case "#minIncl" -> 401;
            case "#minExcl" -> 402;
            case "#maxExcl" -> 403;
            case "#maxIncl" -> 404;

            // Tier 5: Patterns (501)
            case "#regex" -> 501;

            // Tier 6: Subsets (601-602)
            case "#filterIncl" -> 601;
            case "#filterExcl" -> 602;

            // Tier 7: Directives (701)
            case "#strip" -> 701;

            default -> 999;
        };
    }
}
