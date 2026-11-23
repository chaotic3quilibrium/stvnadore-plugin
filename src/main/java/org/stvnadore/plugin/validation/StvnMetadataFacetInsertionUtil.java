package org.stvnadore.plugin.validation;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiComment;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.StvnTypes;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Canonical § 6.1 5-tier metadata facet insertion helper for capacity and string length quick-fixes.
 * <p>
 * Ensures {@code #minSize} (Tier {@code 1.5.1}, rank {@code 1510}) and {@code #maxSize}
 * (Tier {@code 1.5.2}, rank {@code 1520}) are placed strictly after Tier {@code 1.1..1.3}
 * representation/scale/mode flags and before Tier {@code 2..5} traits, value bounds,
 * subset filters, {@code #preserveIndent}, {@code #regex}, and {@code #strip}.
 */
@NullMarked
public final class StvnMetadataFacetInsertionUtil {

    private static final int MAX_SIZE_RANK = 1520;

    private StvnMetadataFacetInsertionUtil() {
    }

    /**
     * Replaces existing capacity facets ({@code #size}, {@code #minSize}, {@code #maxSize}) in
     * {@code metaMap} and inserts {@code minSizeVal} (when non-null) and {@code maxSizeVal}
     * at their canonical § 6.1 5-tier decimal rank without disturbing unrelated facets or comments.
     *
     * @param doc              mutable document backing {@code metaMap}
     * @param metaMap          target metadata map PSI node
     * @param minSizeVal       optional {@code #minSize} value token (or {@code null} when omitted)
     * @param maxSizeVal       mandatory {@code #maxSize} value token
     * @param preserveValidMin when {@code true} and {@code minSizeVal == null}, retains an existing
     *                         positive {@code #minSize <= maxSizeVal} entry
     */
    public static void insertOrReplaceCapacityFacets(
            Document doc,
            MetadataMap metaMap,
            @Nullable String minSizeVal,
            String maxSizeVal,
            boolean preserveValidMin
    ) {
        List<MetadataEntry> entries = metaMap.getMetadataEntryList();
        String effectiveMinVal = minSizeVal;
        BigInteger targetMaxBi = StvnPsiLiteralEvaluator.parseBigInteger(maxSizeVal).orElse(null);

        if (effectiveMinVal == null && preserveValidMin && targetMaxBi != null) {
            for (MetadataEntry entry : entries) {
                String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
                if (StvnVocabulary.FACET_KW_MIN_SIZE.equals(kw) && entry.getMetadataValue() != null) {
                    String rawMin = entry.getMetadataValue().getText().trim();
                    BigInteger existingMin = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, rawMin).orElse(null);
                    if (existingMin != null
                            && existingMin.signum() > 0
                            && existingMin.compareTo(targetMaxBi) <= 0) {
                        effectiveMinVal = rawMin;
                        break;
                    }
                }
            }
        }

        List<String> newFacetTokens = new ArrayList<>(2);
        if (effectiveMinVal != null) {
            newFacetTokens.add(StvnVocabulary.FACET_KW_MIN_SIZE + " " + effectiveMinVal);
        }
        newFacetTokens.add(StvnVocabulary.FACET_KW_MAX_SIZE + " " + maxSizeVal);
        String inlineJoined = String.join(" ", newFacetTokens);

        boolean allCapacity = entries.isEmpty() || entries.stream().allMatch(StvnMetadataFacetInsertionUtil::isCapacityEntry);
        boolean hasInteriorComment = PsiTreeUtil.findChildOfType(metaMap, PsiComment.class) != null;
        int mapStart = metaMap.getTextRange().getStartOffset();
        int mapEnd = metaMap.getTextRange().getEndOffset();
        boolean multiLine = doc.getLineNumber(mapStart) < doc.getLineNumber(Math.max(mapStart, mapEnd - 1));

        if (allCapacity && !hasInteriorComment && !multiLine) {
            doc.replaceString(mapStart, mapEnd, "{ " + inlineJoined + " }");
            return;
        }

        ASTNode rbraceNode = metaMap.getNode().findChildByType(StvnTypes.RBRACE);
        int closeOffset = rbraceNode != null ? rbraceNode.getStartOffset() : -1;

        List<MetadataEntry> nonCapacitySurviving = new ArrayList<>();
        List<MetadataEntry> toRemove = new ArrayList<>();
        for (MetadataEntry entry : entries) {
            if (isCapacityEntry(entry)) {
                toRemove.add(entry);
            } else {
                nonCapacitySurviving.add(entry);
            }
        }

        MetadataEntry insertBeforeEntry = null;
        for (MetadataEntry surviving : nonCapacitySurviving) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(surviving);
            if (StvnMetadataOrderInspection.getFacetRank(kw) > MAX_SIZE_RANK) {
                insertBeforeEntry = surviving;
                break;
            }
        }

        int anchorOffset = insertBeforeEntry != null ? insertBeforeEntry.getTextRange().getStartOffset() : -1;
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";

        // Delete existing capacity entries from highest offset to lowest, tracking anchorOffset and closeOffset shifts
        toRemove.sort(Comparator.comparingInt((MetadataEntry e) -> e.getTextRange().getStartOffset()).reversed());
        for (MetadataEntry capEntry : toRemove) {
            int delStart = capEntry.getTextRange().getStartOffset();
            int delEnd = capEntry.getTextRange().getEndOffset();
            CharSequence seq = doc.getCharsSequence();
            if (multiLine) {
                int lineNum = doc.getLineNumber(delStart);
                int lineStart = doc.getLineStartOffset(lineNum);
                int lineEnd = doc.getLineEndOffset(lineNum);
                String prefix = seq.subSequence(lineStart, delStart).toString();
                String suffix = seq.subSequence(delEnd, lineEnd).toString();
                if (prefix.isBlank() && suffix.isBlank()) {
                    if (lineEnd + 1 < seq.length() && seq.charAt(lineEnd) == '\r' && seq.charAt(lineEnd + 1) == '\n') {
                        delStart = lineStart;
                        delEnd = lineEnd + 2;
                    } else if (lineEnd < seq.length() && seq.charAt(lineEnd) == '\n') {
                        delStart = lineStart;
                        delEnd = lineEnd + 1;
                    } else if (delStart > 0 && seq.charAt(delStart - 1) == ' ') {
                        delStart--;
                    }
                } else if (delStart > 0 && seq.charAt(delStart - 1) == ' ') {
                    delStart--;
                }
            } else if (delStart > 0 && seq.charAt(delStart - 1) == ' ') {
                delStart--;
            } else if (delEnd < seq.length() && seq.charAt(delEnd) == ' ') {
                delEnd++;
            }
            int deletedChars = delEnd - delStart;
            doc.deleteString(delStart, delEnd);
            if (anchorOffset >= delEnd) {
                anchorOffset -= deletedChars;
            }
            if (closeOffset >= delEnd) {
                closeOffset -= deletedChars;
            }
        }

        CharSequence chars = doc.getCharsSequence();
        if (multiLine) {
            if (anchorOffset >= 0) {
                int anchorLine = doc.getLineNumber(anchorOffset);
                int lineStart = doc.getLineStartOffset(anchorLine);
                String indent = extractLeadingSpaces(chars, lineStart, anchorOffset);
                String block = formatMultiLineFacets(newFacetTokens, indent, lineSep);
                doc.insertString(lineStart, block);
            } else if (closeOffset >= 0) {
                int closeLine = doc.getLineNumber(closeOffset);
                int closeLineStart = doc.getLineStartOffset(closeLine);
                String closeIndent = extractLeadingSpaces(chars, closeLineStart, closeOffset);
                String entryIndent = closeIndent + "  ";
                String block = formatMultiLineFacets(newFacetTokens, entryIndent, lineSep);
                doc.insertString(closeLineStart, block);
            }
            return;
        }

        if (anchorOffset >= 0) {
            doc.insertString(anchorOffset, inlineJoined + " ");
        } else if (closeOffset >= 0) {
            if (closeOffset > 0 && chars.charAt(closeOffset - 1) == ' ') {
                doc.insertString(closeOffset - 1, " " + inlineJoined);
            } else {
                doc.insertString(closeOffset, " " + inlineJoined + " ");
            }
        }
    }

    /**
     * Inserts or replaces a single metadata facet entry (for example {@code #exact} or
     * {@code #equatable #TRUE}) in {@code metaMap} at its canonical § 6.1 5-tier rank while
     * preserving existing single-line or multi-line layout and interior comments.
     *
     * @param doc           mutable document backing {@code metaMap}
     * @param metaMap       target metadata map PSI node
     * @param facetKeyword  canonical facet keyword (for example {@code #exact} or {@code #equatable})
     * @param fullEntryText full facet entry text to insert (for example {@code #exact} or {@code #equatable #TRUE})
     */
    public static void insertOrReplaceSingleFacet(
            Document doc,
            MetadataMap metaMap,
            String facetKeyword,
            String fullEntryText
    ) {
        insertOrReplaceSingleFacetRemoving(doc, metaMap, facetKeyword, fullEntryText, Set.of());
    }

    /**
     * Inserts or replaces {@code fullEntryText} at {@code facetKeyword}'s canonical § 6.1 5-tier rank
     * while simultaneously removing any conflicting facet entries whose keywords appear in
     * {@code extraKeywordsToRemove}.
     *
     * @param doc                   mutable document backing {@code metaMap}
     * @param metaMap               target metadata map PSI node
     * @param facetKeyword          canonical facet keyword being inserted or updated
     * @param fullEntryText         full facet entry text to insert (for example {@code #exact} or {@code #size 32})
     * @param extraKeywordsToRemove additional facet keywords (for example {@code #size} or {@code #exact}) to remove atomically
     */
    public static void insertOrReplaceSingleFacetRemoving(
            Document doc,
            MetadataMap metaMap,
            String facetKeyword,
            String fullEntryText,
            Set<String> extraKeywordsToRemove
    ) {
        List<MetadataEntry> entries = metaMap.getMetadataEntryList();
        int targetRank = StvnMetadataOrderInspection.getFacetRank(facetKeyword);

        List<MetadataEntry> surviving = new ArrayList<>();
        List<MetadataEntry> toRemove = new ArrayList<>();
        for (MetadataEntry entry : entries) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
            if (facetKeyword.equals(kw) || extraKeywordsToRemove.contains(kw)) {
                toRemove.add(entry);
            } else {
                surviving.add(entry);
            }
        }

        boolean hasInteriorComment = PsiTreeUtil.findChildOfType(metaMap, PsiComment.class) != null;
        int mapStart = metaMap.getTextRange().getStartOffset();
        int mapEnd = metaMap.getTextRange().getEndOffset();
        boolean multiLine = doc.getLineNumber(mapStart) < doc.getLineNumber(Math.max(mapStart, mapEnd - 1));

        if (surviving.isEmpty() && !hasInteriorComment && !multiLine) {
            doc.replaceString(mapStart, mapEnd, "{ " + fullEntryText + " }");
            return;
        }

        ASTNode rbraceNode = metaMap.getNode().findChildByType(StvnTypes.RBRACE);
        int closeOffset = rbraceNode != null ? rbraceNode.getStartOffset() : -1;

        MetadataEntry insertBeforeEntry = null;
        for (MetadataEntry candidate : surviving) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(candidate);
            if (StvnMetadataOrderInspection.getFacetRank(kw) > targetRank) {
                insertBeforeEntry = candidate;
                break;
            }
        }

        int anchorOffset = insertBeforeEntry != null ? insertBeforeEntry.getTextRange().getStartOffset() : -1;
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";

        toRemove.sort(Comparator.comparingInt((MetadataEntry e) -> e.getTextRange().getStartOffset()).reversed());
        for (MetadataEntry oldEntry : toRemove) {
            int delStart = oldEntry.getTextRange().getStartOffset();
            int delEnd = oldEntry.getTextRange().getEndOffset();
            CharSequence seq = doc.getCharsSequence();
            if (multiLine) {
                int lineNum = doc.getLineNumber(delStart);
                int lineStart = doc.getLineStartOffset(lineNum);
                int lineEnd = doc.getLineEndOffset(lineNum);
                String prefix = seq.subSequence(lineStart, delStart).toString();
                String suffix = seq.subSequence(delEnd, lineEnd).toString();
                if (prefix.isBlank() && suffix.isBlank()) {
                    if (lineEnd + 1 < seq.length() && seq.charAt(lineEnd) == '\r' && seq.charAt(lineEnd + 1) == '\n') {
                        delStart = lineStart;
                        delEnd = lineEnd + 2;
                    } else if (lineEnd < seq.length() && seq.charAt(lineEnd) == '\n') {
                        delStart = lineStart;
                        delEnd = lineEnd + 1;
                    } else if (delStart > 0 && seq.charAt(delStart - 1) == ' ') {
                        delStart--;
                    }
                } else if (delStart > 0 && seq.charAt(delStart - 1) == ' ') {
                    delStart--;
                }
            } else if (delStart > 0 && seq.charAt(delStart - 1) == ' ') {
                delStart--;
            } else if (delEnd < seq.length() && seq.charAt(delEnd) == ' ') {
                delEnd++;
            }
            int deletedChars = delEnd - delStart;
            doc.deleteString(delStart, delEnd);
            if (anchorOffset >= delEnd) {
                anchorOffset -= deletedChars;
            }
            if (closeOffset >= delEnd) {
                closeOffset -= deletedChars;
            }
        }

        CharSequence chars = doc.getCharsSequence();
        List<String> singleToken = List.of(fullEntryText);
        if (multiLine) {
            if (anchorOffset >= 0) {
                int anchorLine = doc.getLineNumber(anchorOffset);
                int lineStart = doc.getLineStartOffset(anchorLine);
                String indent = extractLeadingSpaces(chars, lineStart, anchorOffset);
                doc.insertString(lineStart, formatMultiLineFacets(singleToken, indent, lineSep));
            } else if (closeOffset >= 0) {
                int closeLine = doc.getLineNumber(closeOffset);
                int closeLineStart = doc.getLineStartOffset(closeLine);
                String closeIndent = extractLeadingSpaces(chars, closeLineStart, closeOffset);
                doc.insertString(closeLineStart, formatMultiLineFacets(singleToken, closeIndent + "  ", lineSep));
            }
            return;
        }

        if (anchorOffset >= 0) {
            doc.insertString(anchorOffset, fullEntryText + " ");
        } else if (closeOffset >= 0) {
            if (closeOffset > 0 && chars.charAt(closeOffset - 1) == ' ') {
                doc.insertString(closeOffset - 1, " " + fullEntryText);
            } else {
                doc.insertString(closeOffset, " " + fullEntryText + " ");
            }
        }
    }

    private static boolean isCapacityEntry(MetadataEntry entry) {
        String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
        return StvnVocabulary.FACET_KW_SIZE.equals(kw)
            || StvnVocabulary.FACET_KW_MIN_SIZE.equals(kw)
            || StvnVocabulary.FACET_KW_MAX_SIZE.equals(kw);
    }

    private static String extractLeadingSpaces(CharSequence chars, int lineStart, int limit) {
        int i = lineStart;
        while (i < limit && chars.charAt(i) == ' ') {
            i++;
        }
        return " ".repeat(Math.max(0, i - lineStart));
    }

    private static String formatMultiLineFacets(List<String> facetTokens, String indent, String lineSep) {
        StringBuilder sb = new StringBuilder();
        for (String facet : facetTokens) {
            sb.append(indent).append(facet).append(lineSep);
        }
        return sb.toString();
    }
}
