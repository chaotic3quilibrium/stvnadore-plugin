package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.PackageElement;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseAliasBlock;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseOptionsBlock;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stateless PSI layout synthesis utility backing {@link StvnExpandContainerIntention},
 * {@link StvnCollapseContainerIntention}, and {@link StvnCollapseChildElementsIntention}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnContainerLayoutUtil {

    private static final String INDENT_STEP = "  ";

    private StvnContainerLayoutUtil() {
    }

    /**
     * Pure text-replacement operation computed over an STVN {@link PsiFile}.
     *
     * @param range       half-open document character range to replace
     * @param replacement canonical single-line or multi-line STVN replacement text
     * @param priority    caret-proximity intention priority ({@link PriorityAction.Priority#NORMAL} on container
     *                    delimiters/whitespace, {@link PriorityAction.Priority#LOW} inside inner leaf tokens)
     */
    public record LayoutOperation(
        TextRange range,
        String replacement,
        PriorityAction.Priority priority
    ) {
        /**
         * Constructs a {@link LayoutOperation} with default {@link PriorityAction.Priority#NORMAL} priority.
         *
         * @param range       half-open document character range to replace
         * @param replacement canonical single-line or multi-line STVN replacement text
         */
        public LayoutOperation(TextRange range, String replacement) {
            this(range, replacement, PriorityAction.Priority.NORMAL);
        }
    }

    private record MapEntrySlice(
        ASTNode lbrack,
        Value keyValue,
        Value entryValue,
        ASTNode rbrack
    ) {
        TextRange textRange() {
            return new TextRange(lbrack.getStartOffset(), rbrack.getTextRange().getEndOffset());
        }
    }

    /**
     * Applies {@code op} directly to {@code file}'s underlying {@link Document} and commits PSI,
     * operating cleanly on both physical editor buffers and non-physical {@code IntentionPreviewInfo.DIFF} copies.
     *
     * @param project active IntelliJ project
     * @param file    target physical or preview STVN file
     * @param op      precomputed layout replacement operation
     */
    public static void applyOperation(Project project, PsiFile file, LayoutOperation op) {
        Document document = file.getViewProvider().getDocument();
        if (document == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file);
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(document);
        }
        document.replaceString(op.range().getStartOffset(), op.range().getEndOffset(), op.replacement());
        if (commitPhysical) {
            docManager.commitDocument(document);
        }
    }

    /**
     * Resolves an expand-to-multiple-lines operation for the innermost eligible non-empty single-line
     * container enclosing {@code caretOffset}.
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findExpandOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        for (PsiElement cur = leaf; cur != null && !(cur instanceof PsiFile); cur = cur.getParent()) {
            PsiElement candidate = normalizeEnumCandidate(cur);
            if (!isExpandableContainerKind(candidate)) {
                continue;
            }
            if (candidate instanceof UseAliasBlock aliasBlock) {
                UseStmt parentUse = PsiTreeUtil.getParentOfType(aliasBlock, UseStmt.class);
                if (parentUse != null && isSingleLineSpan(chars, findOpenDelim(parentUse), findCloseDelim(parentUse))) {
                    continue;
                }
            }
            if (PsiTreeUtil.hasErrorElements(candidate)) {
                return null;
            }
            ASTNode open = findOpenDelim(candidate);
            ASTNode close = findCloseDelim(candidate);
            if (open == null || close == null) {
                return null;
            }
            if (!isSingleLineSpan(chars, open, close)) {
                return null;
            }
            if (isEmptyContainer(candidate)) {
                return null;
            }
            String baseIndent = lineIndentOfOffset(chars, candidate.getTextRange().getStartOffset());
            String expanded = renderExpandedContainer(candidate, chars, baseIndent);
            if (expanded == null) {
                return null;
            }
            TextRange targetRange = replacementRangeForContainer(candidate);
            return new LayoutOperation(
                targetRange,
                expanded,
                computeCaretProximityPriority(file, caretOffset, candidate, open, close)
            );
        }
        return null;
    }

    /**
     * Resolves a collapse-to-single-line operation for the innermost eligible comment-free,
     * fenced-string-free multi-line container enclosing {@code caretOffset}.
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findCollapseOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        for (PsiElement cur = leaf; cur != null && !(cur instanceof PsiFile); cur = cur.getParent()) {
            PsiElement candidate = normalizeEnumCandidate(cur);
            if (!isExpandableContainerKind(candidate)) {
                continue;
            }
            ASTNode open = findOpenDelim(candidate);
            ASTNode close = findCloseDelim(candidate);
            if (open == null || close == null) {
                return null;
            }
            if (isSingleLineSpan(chars, open, close)) {
                continue;
            }
            if (PsiTreeUtil.hasErrorElements(candidate) || !isRangeSafeToCollapse(candidate.getNode(), open, close)) {
                return null;
            }
            String collapsed = renderCollapsedContainer(candidate);
            if (collapsed == null) {
                return null;
            }
            return new LayoutOperation(
                replacementRangeForContainer(candidate),
                collapsed,
                computeCaretProximityPriority(file, caretOffset, candidate, open, close)
            );
        }
        return null;
    }

    /**
     * Expands the single-line {@code [key value]} map entry enclosing {@code caretOffset} into
     * canonical 4-slot multi-line symmetry ({@code [\n  key\n  value\n]}).
     * <p>
     * When the enclosing {@link MapLiteral} is itself single-line, expands the outer {@link MapLiteral}
     * onto multiple lines while expanding only the targeted entry into 4-slot symmetry and keeping
     * sibling entries as canonical single-line {@code [k v]} rows.
     * </p>
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findExpandMapEntryOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        MapLiteral mapLit = PsiTreeUtil.getParentOfType(leaf, MapLiteral.class, false);
        if (mapLit == null) {
            return null;
        }
        ASTNode mapOpen = findOpenDelim(mapLit);
        ASTNode mapClose = findCloseDelim(mapLit);
        if (mapOpen == null || mapClose == null) {
            return null;
        }
        MapEntrySlice targetSlice = findEnclosingMapEntrySlice(mapLit, caretOffset);
        if (targetSlice == null
                || !isSingleLineSpan(chars, targetSlice.lbrack(), targetSlice.rbrack())
                || PsiTreeUtil.hasErrorElements(targetSlice.keyValue())
                || PsiTreeUtil.hasErrorElements(targetSlice.entryValue())
                || !isRangeSafeToCollapse(mapLit.getNode(), targetSlice.lbrack(), targetSlice.rbrack())) {
            return null;
        }
        PriorityAction.Priority priority = computeSingleMapEntryPriority(file, caretOffset, targetSlice);
        if (isSingleLineSpan(chars, mapOpen, mapClose)) {
            if (PsiTreeUtil.hasErrorElements(mapLit) || !isRangeSafeToCollapse(mapLit.getNode(), mapOpen, mapClose)) {
                return null;
            }
            List<MapEntrySlice> slices = listMapEntrySlices(mapLit);
            String baseIndent = lineIndentOfOffset(chars, mapLit.getTextRange().getStartOffset());
            String childIndent = baseIndent + INDENT_STEP;
            StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (MapEntrySlice slice : slices) {
                sb.append('\n').append(childIndent);
                if (slice.lbrack() == targetSlice.lbrack()) {
                    sb.append(renderExpandedMapEntrySlice(slice, childIndent));
                } else {
                    sb.append(renderCollapsedMapEntry(slice));
                }
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return new LayoutOperation(mapLit.getTextRange(), sb.toString(), priority);
        }

        boolean precededOnLine = hasNonWhitespaceBeforeOnSameLine(chars, targetSlice.lbrack().getStartOffset());
        boolean followedOnLine = hasNonWhitespaceAfterOnSameLine(
            chars,
            targetSlice.rbrack().getTextRange().getEndOffset(),
            mapClose.getStartOffset()
        );
        if (!precededOnLine && !followedOnLine) {
            String sliceIndent = lineIndentOfOffset(chars, targetSlice.lbrack().getStartOffset());
            return new LayoutOperation(
                targetSlice.textRange(),
                renderExpandedMapEntrySlice(targetSlice, sliceIndent),
                priority
            );
        }
        String entryIndent = lineIndentOfOffset(chars, mapLit.getTextRange().getStartOffset()) + INDENT_STEP;
        int replaceStart = precededOnLine
            ? retreatHorizontalSpaces(chars, targetSlice.lbrack().getStartOffset())
            : targetSlice.lbrack().getStartOffset();
        int replaceEnd = followedOnLine
            ? advanceHorizontalSpaces(chars, targetSlice.rbrack().getTextRange().getEndOffset())
            : targetSlice.rbrack().getTextRange().getEndOffset();
        String expandedSlice = (precededOnLine ? "\n" + entryIndent : "")
            + renderExpandedMapEntrySlice(targetSlice, entryIndent)
            + (followedOnLine ? "\n" + entryIndent : "");
        return new LayoutOperation(new TextRange(replaceStart, replaceEnd), expandedSlice, priority);
    }

    /**
     * Collapses the comment-free, fenced-string-free multi-line {@code [key value]} map entry
     * enclosing {@code caretOffset} onto a single canonical line ({@code [key value]}).
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findCollapseMapEntryOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        MapLiteral mapLit = PsiTreeUtil.getParentOfType(leaf, MapLiteral.class, false);
        if (mapLit == null) {
            return null;
        }
        MapEntrySlice slice = findEnclosingMapEntrySlice(mapLit, caretOffset);
        if (slice == null
                || isSingleLineSpan(chars, slice.lbrack(), slice.rbrack())
                || PsiTreeUtil.hasErrorElements(slice.keyValue())
                || PsiTreeUtil.hasErrorElements(slice.entryValue())
                || !isRangeSafeToCollapse(mapLit.getNode(), slice.lbrack(), slice.rbrack())) {
            return null;
        }
        return new LayoutOperation(
            slice.textRange(),
            renderCollapsedMapEntry(slice),
            computeSingleMapEntryPriority(file, caretOffset, slice)
        );
    }

    /**
     * Expands every single-line {@code [key value]} entry inside the innermost eligible {@link MapLiteral}
     * enclosing {@code caretOffset} into canonical 4-slot multi-line symmetry, preserving all comments
     * and already-multi-line entries.
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findExpandAllMapEntriesOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        for (PsiElement cur = leaf; cur != null && !(cur instanceof PsiFile); cur = cur.getParent()) {
            if (!(cur instanceof MapLiteral mapLit)) {
                continue;
            }
            if (PsiTreeUtil.hasErrorElements(mapLit)) {
                return null;
            }
            ASTNode open = findOpenDelim(mapLit);
            ASTNode close = findCloseDelim(mapLit);
            if (open == null || close == null) {
                return null;
            }
            List<MapEntrySlice> slices = listMapEntrySlices(mapLit);
            if (slices.isEmpty()) {
                continue;
            }
            boolean hasExpandableSingleLineSlice = slices.stream()
                .anyMatch(s -> isSingleLineSpan(chars, s.lbrack(), s.rbrack())
                    && isRangeSafeToCollapse(mapLit.getNode(), s.lbrack(), s.rbrack()));
            if (!hasExpandableSingleLineSlice) {
                continue;
            }
            PriorityAction.Priority priority = computeWholeMapEntriesPriority(file, caretOffset, mapLit, open, close);
            String baseIndent = lineIndentOfOffset(chars, mapLit.getTextRange().getStartOffset());
            String childIndent = baseIndent + INDENT_STEP;

            if (isSingleLineSpan(chars, open, close)) {
                if (!isRangeSafeToCollapse(mapLit.getNode(), open, close)) {
                    return null;
                }
                StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
                for (MapEntrySlice slice : slices) {
                    sb.append('\n').append(childIndent).append(renderExpandedMapEntrySlice(slice, childIndent));
                }
                sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
                return new LayoutOperation(mapLit.getTextRange(), sb.toString(), priority);
            }

            StringBuilder rebuilt = new StringBuilder(mapLit.getText());
            int mapStart = mapLit.getTextRange().getStartOffset();
            for (int i = slices.size() - 1; i >= 0; i--) {
                MapEntrySlice slice = slices.get(i);
                if (!isSingleLineSpan(chars, slice.lbrack(), slice.rbrack())
                        || !isRangeSafeToCollapse(mapLit.getNode(), slice.lbrack(), slice.rbrack())) {
                    continue;
                }
                boolean splitBefore = hasNonWhitespaceBeforeOnSameLine(chars, slice.lbrack().getStartOffset());
                String sliceIndent = splitBefore
                    ? childIndent
                    : lineIndentOfOffset(chars, slice.lbrack().getStartOffset());
                String replacement = (splitBefore ? "\n" + sliceIndent : "")
                    + renderExpandedMapEntrySlice(slice, sliceIndent);
                int absStart = splitBefore
                    ? retreatHorizontalSpaces(chars, slice.textRange().getStartOffset())
                    : slice.textRange().getStartOffset();
                int relStart = absStart - mapStart;
                int relEnd = slice.textRange().getEndOffset() - mapStart;
                rebuilt.replace(relStart, relEnd, replacement);
            }
            return new LayoutOperation(mapLit.getTextRange(), rebuilt.toString(), priority);
        }
        return null;
    }

    /**
     * Collapses every multi-line {@code [key value]} entry inside the innermost eligible multi-line
     * {@link MapLiteral} enclosing {@code caretOffset} onto single lines while preserving outer
     * multi-line {@code { ... }} structure and any {@code //} comments between entries.
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findCollapseAllMapEntriesOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        for (PsiElement cur = leaf; cur != null && !(cur instanceof PsiFile); cur = cur.getParent()) {
            if (!(cur instanceof MapLiteral mapLit)) {
                continue;
            }
            if (PsiTreeUtil.hasErrorElements(mapLit)) {
                return null;
            }
            ASTNode open = findOpenDelim(mapLit);
            ASTNode close = findCloseDelim(mapLit);
            if (open == null || close == null || isSingleLineSpan(chars, open, close)) {
                continue;
            }
            if (!areAllMapEntriesCollapsible(mapLit, chars)) {
                continue;
            }
            List<MapEntrySlice> slices = listMapEntrySlices(mapLit);
            StringBuilder rebuilt = new StringBuilder(mapLit.getText());
            int mapStart = mapLit.getTextRange().getStartOffset();
            for (int i = slices.size() - 1; i >= 0; i--) {
                MapEntrySlice slice = slices.get(i);
                if (isSingleLineSpan(chars, slice.lbrack(), slice.rbrack())) {
                    continue;
                }
                int relStart = slice.textRange().getStartOffset() - mapStart;
                int relEnd = slice.textRange().getEndOffset() - mapStart;
                rebuilt.replace(relStart, relEnd, renderCollapsedMapEntry(slice));
            }
            PriorityAction.Priority priority = computeWholeMapEntriesPriority(file, caretOffset, mapLit, open, close);
            return new LayoutOperation(mapLit.getTextRange(), rebuilt.toString(), priority);
        }
        return null;
    }

    private static boolean areAllMapEntriesCollapsible(MapLiteral mapLit, CharSequence chars) {
        List<MapEntrySlice> slices = listMapEntrySlices(mapLit);
        if (slices.isEmpty()) {
            return false;
        }
        boolean hasMultiLine = false;
        for (MapEntrySlice slice : slices) {
            if (!isSingleLineSpan(chars, slice.lbrack(), slice.rbrack())) {
                hasMultiLine = true;
                if (PsiTreeUtil.hasErrorElements(slice.keyValue())
                        || PsiTreeUtil.hasErrorElements(slice.entryValue())
                        || !isRangeSafeToCollapse(mapLit.getNode(), slice.lbrack(), slice.rbrack())) {
                    return false;
                }
            }
        }
        return hasMultiLine;
    }

    /**
     * Resolves a collapse-child-elements operation on the innermost eligible multi-line parent container
     * enclosing {@code caretOffset} that owns at least one collapsible multi-line direct child.
     *
     * @param file        target STVN file
     * @param caretOffset 0-based editor caret offset
     * @return {@link LayoutOperation} if eligible, or {@code null}
     */
    public static @Nullable LayoutOperation findCollapseChildrenOperation(PsiFile file, int caretOffset) {
        PsiElement leaf = findCaretLeaf(file, caretOffset);
        if (leaf == null) {
            return null;
        }
        CharSequence chars = file.getViewProvider().getContents();
        for (PsiElement cur = leaf; cur != null && !(cur instanceof PsiFile); cur = cur.getParent()) {
            if (!isParentEligibleForChildCollapse(cur)) {
                continue;
            }
            ASTNode open = findOpenDelim(cur);
            ASTNode close = findCloseDelim(cur);
            if (open == null || close == null || isSingleLineSpan(chars, open, close)) {
                continue;
            }
            if (cur instanceof MapLiteral mapLit && areAllMapEntriesCollapsible(mapLit, chars)) {
                // Deconflict with StvnCollapseAllMapEntriesIntention when every map entry is collapsible
                continue;
            }
            List<LayoutOperation> childOps = collectCollapsibleDirectChildOps(cur, chars);
            if (childOps.isEmpty()) {
                continue;
            }
            TextRange parentRange = cur.getTextRange();
            StringBuilder rebuilt = new StringBuilder(cur.getText());
            childOps.sort(Comparator.comparingInt((LayoutOperation op) -> op.range().getStartOffset()).reversed());
            int baseStart = parentRange.getStartOffset();
            for (LayoutOperation childOp : childOps) {
                int relStart = childOp.range().getStartOffset() - baseStart;
                int relEnd = childOp.range().getEndOffset() - baseStart;
                rebuilt.replace(relStart, relEnd, childOp.replacement());
            }
            return new LayoutOperation(
                parentRange,
                rebuilt.toString(),
                computeCaretProximityPriority(file, caretOffset, cur, open, close)
            );
        }
        return null;
    }

    private static PriorityAction.Priority computeSingleMapEntryPriority(
            PsiFile file,
            int caretOffset,
            MapEntrySlice slice
    ) {
        if (isCaretInsideOrImmediatelyAfterStringLeaf(file, caretOffset)) {
            return PriorityAction.Priority.LOW;
        }
        int len = file.getTextLength();
        int clamped = Math.max(0, Math.min(caretOffset, len));
        PsiElement at = clamped < len ? file.findElementAt(clamped) : null;
        PsiElement prev = clamped > 0 ? file.findElementAt(clamped - 1) : null;
        if (at != null && at.getNode() != null) {
            ASTNode atNode = at.getNode();
            if (atNode == slice.lbrack() || atNode == slice.rbrack()) {
                return PriorityAction.Priority.HIGH;
            }
            if (atNode.getElementType() == TokenType.WHITE_SPACE && at.getParent() instanceof MapLiteral) {
                return PriorityAction.Priority.HIGH;
            }
        }
        if (prev != null && prev.getNode() == slice.rbrack()) {
            return PriorityAction.Priority.HIGH;
        }
        return PriorityAction.Priority.LOW;
    }

    private static PriorityAction.Priority computeWholeMapEntriesPriority(
            PsiFile file,
            int caretOffset,
            MapLiteral mapLit,
            ASTNode openBrace,
            ASTNode closeBrace
    ) {
        if (isCaretInsideOrImmediatelyAfterStringLeaf(file, caretOffset)) {
            return PriorityAction.Priority.LOW;
        }
        MapEntrySlice enclosingSlice = findEnclosingMapEntrySlice(mapLit, caretOffset);
        int len = file.getTextLength();
        int clamped = Math.max(0, Math.min(caretOffset, len));
        PsiElement at = clamped < len ? file.findElementAt(clamped) : null;
        PsiElement prev = clamped > 0 ? file.findElementAt(clamped - 1) : null;
        if (enclosingSlice != null) {
            if (at != null && at.getNode() != null) {
                ASTNode atNode = at.getNode();
                if (atNode == enclosingSlice.lbrack()
                        || atNode == enclosingSlice.rbrack()
                        || (atNode.getElementType() == TokenType.WHITE_SPACE && at.getParent() == mapLit)) {
                    return PriorityAction.Priority.NORMAL;
                }
            }
            if (prev != null && prev.getNode() == enclosingSlice.rbrack()) {
                return PriorityAction.Priority.NORMAL;
            }
            return PriorityAction.Priority.LOW;
        }
        if (at != null && at.getNode() != null) {
            ASTNode atNode = at.getNode();
            if (atNode == openBrace || atNode == closeBrace
                    || (atNode.getElementType() == TokenType.WHITE_SPACE && at.getParent() == mapLit)) {
                return PriorityAction.Priority.HIGH;
            }
        }
        if (prev != null && prev.getNode() == closeBrace) {
            return PriorityAction.Priority.HIGH;
        }
        return PriorityAction.Priority.NORMAL;
    }

    private static boolean isCaretInsideOrImmediatelyAfterStringLeaf(PsiFile file, int caretOffset) {
        int len = file.getTextLength();
        int clamped = Math.max(0, Math.min(caretOffset, len));
        PsiElement at = clamped < len ? file.findElementAt(clamped) : null;
        if (at != null && at.getNode() != null) {
            IElementType atType = at.getNode().getElementType();
            if (atType == StvnTypes.LITERAL_STRING_SIMPLE || atType == StvnTypes.LITERAL_STRING_FENCED) {
                return true;
            }
            if (atType == TokenType.WHITE_SPACE && clamped > 0) {
                PsiElement prev = file.findElementAt(clamped - 1);
                if (prev != null && prev.getNode() != null) {
                    IElementType prevType = prev.getNode().getElementType();
                    if (prevType == StvnTypes.LITERAL_STRING_SIMPLE || prevType == StvnTypes.LITERAL_STRING_FENCED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static PriorityAction.Priority computeCaretProximityPriority(
            PsiFile file,
            int caretOffset,
            PsiElement targetContainer,
            ASTNode openDelim,
            ASTNode closeDelim
    ) {
        if (targetContainer instanceof MapLiteral mapLit) {
            return computeWholeMapEntriesPriority(file, caretOffset, mapLit, openDelim, closeDelim);
        }
        int len = file.getTextLength();
        int clamped = Math.max(0, Math.min(caretOffset, len));
        PsiElement at = clamped < len ? file.findElementAt(clamped) : null;
        PsiElement prev = clamped > 0 ? file.findElementAt(clamped - 1) : null;

        if (at != null && at.getNode() != null) {
            ASTNode atNode = at.getNode();
            IElementType atType = atNode.getElementType();
            if (atNode == openDelim || atNode == closeDelim) {
                return PriorityAction.Priority.NORMAL;
            }
            if (atType == TokenType.WHITE_SPACE) {
                if (prev != null && prev.getNode() != null) {
                    IElementType prevType = prev.getNode().getElementType();
                    if (prevType == StvnTypes.LITERAL_STRING_SIMPLE
                            || prevType == StvnTypes.LITERAL_STRING_FENCED) {
                        return PriorityAction.Priority.LOW;
                    }
                    if (prev.getNode() == openDelim || prev.getNode() == closeDelim) {
                        return PriorityAction.Priority.NORMAL;
                    }
                }
                return at.getParent() == targetContainer
                    ? PriorityAction.Priority.NORMAL
                    : PriorityAction.Priority.LOW;
            }
            if (prev != null && prev.getNode() == closeDelim) {
                return PriorityAction.Priority.NORMAL;
            }
            return PriorityAction.Priority.LOW;
        }
        if (prev != null && prev.getNode() == closeDelim) {
            return PriorityAction.Priority.NORMAL;
        }
        return PriorityAction.Priority.LOW;
    }

    private static List<LayoutOperation> collectCollapsibleDirectChildOps(PsiElement parent, CharSequence chars) {
        List<LayoutOperation> ops = new ArrayList<>();
        if (parent instanceof TupleLiteral tup) {
            for (Value childVal : tup.getValueList()) {
                addValueChildCollapseOp(childVal, chars, ops);
            }
        } else if (parent instanceof ListLiteral list) {
            for (Value childVal : list.getValueList()) {
                addValueChildCollapseOp(childVal, chars, ops);
            }
        } else if (parent instanceof MapLiteral map) {
            for (MapEntrySlice slice : listMapEntrySlices(map)) {
                if (!isSingleLineSpan(chars, slice.lbrack(), slice.rbrack())
                        && !PsiTreeUtil.hasErrorElements(slice.keyValue())
                        && !PsiTreeUtil.hasErrorElements(slice.entryValue())
                        && isRangeSafeToCollapse(map.getNode(), slice.lbrack(), slice.rbrack())) {
                    ops.add(new LayoutOperation(slice.textRange(), renderCollapsedMapEntry(slice)));
                }
            }
        } else if (parent instanceof ProductType prod) {
            for (SchemaType childSchema : prod.getSchemaTypeList()) {
                addSchemaTypeChildCollapseOp(childSchema, chars, ops);
            }
        } else if (parent instanceof SumType sum && sum.getEnumDef() == null) {
            for (SchemaType childSchema : sum.getSchemaTypeList()) {
                addSchemaTypeChildCollapseOp(childSchema, chars, ops);
            }
        } else if (parent instanceof CollectionType coll) {
            for (SchemaType childSchema : coll.getSchemaTypeList()) {
                addSchemaTypeChildCollapseOp(childSchema, chars, ops);
            }
        } else if (parent instanceof DefsEntry defs) {
            for (DefsElement el : defs.getDefsElementList()) {
                if (el.getPackageEnclosure() != null) {
                    continue;
                }
                addDeclarationChildCollapseOp(el, chars, ops);
            }
        } else if (parent instanceof PackageEnclosure pkg) {
            for (PackageElement el : pkg.getPackageElementList()) {
                if (el.getNestedPackageIllegal() != null) {
                    continue;
                }
                addDeclarationChildCollapseOp(el, chars, ops);
            }
        }
        return ops;
    }

    private static void addValueChildCollapseOp(Value val, CharSequence chars, List<LayoutOperation> ops) {
        TextRange r = val.getTextRange();
        if (lineOf(chars, r.getStartOffset()) >= lineOf(chars, Math.max(0, r.getEndOffset() - 1))) {
            return;
        }
        if (PsiTreeUtil.hasErrorElements(val) || !isSubtreeSafeToCollapse(val.getNode())) {
            return;
        }
        ops.add(new LayoutOperation(r, renderCollapsedValue(val)));
    }

    private static void addSchemaTypeChildCollapseOp(SchemaType schema, CharSequence chars, List<LayoutOperation> ops) {
        TextRange r = schema.getTextRange();
        if (lineOf(chars, r.getStartOffset()) >= lineOf(chars, Math.max(0, r.getEndOffset() - 1))) {
            return;
        }
        if (PsiTreeUtil.hasErrorElements(schema) || !isSubtreeSafeToCollapse(schema.getNode())) {
            return;
        }
        ops.add(new LayoutOperation(r, renderCollapsedSchemaType(schema)));
    }

    private static void addDeclarationChildCollapseOp(PsiElement declWrapper, CharSequence chars, List<LayoutOperation> ops) {
        PsiElement inner = firstNonWsPsiChild(declWrapper);
        if (inner == null) {
            return;
        }
        TextRange r = inner.getTextRange();
        if (lineOf(chars, r.getStartOffset()) >= lineOf(chars, Math.max(0, r.getEndOffset() - 1))) {
            return;
        }
        if (PsiTreeUtil.hasErrorElements(inner) || !isSubtreeSafeToCollapse(inner.getNode())) {
            return;
        }
        String collapsed = null;
        if (inner instanceof TypeDefinition td && td.getTypeDefTarget() != null && td.getSchemaType() != null) {
            collapsed = td.getTypeDefTarget().getText().trim() + " " + renderCollapsedSchemaType(td.getSchemaType());
        } else if (inner instanceof ConstantDefinition cd
                && cd.getValueKeyword() != null && cd.getSchemaType() != null && cd.getValue() != null) {
            collapsed = cd.getValueKeyword().getText().trim()
                + " " + renderCollapsedSchemaType(cd.getSchemaType())
                + " " + renderCollapsedValue(cd.getValue());
        } else if (inner instanceof UseStmt useStmt) {
            collapsed = renderCollapsedContainer(useStmt);
        } else if (inner instanceof IncludeStmt incStmt) {
            collapsed = renderCollapsedContainer(incStmt);
        }
        if (collapsed != null) {
            ops.add(new LayoutOperation(r, collapsed));
        }
    }

    private static @Nullable String renderExpandedContainer(PsiElement container, CharSequence chars, String baseIndent) {
        String childIndent = baseIndent + INDENT_STEP;
        if (container instanceof ProductType prod) {
            return renderExpandedSchemaParen(StvnVocabulary.TYPE_TUPLE, prod.getSchemaTypeList(), baseIndent, childIndent);
        }
        if (container instanceof CollectionType coll) {
            String head = resolveCollectionHead(coll);
            return head == null ? null : renderExpandedSchemaParen(head, coll.getSchemaTypeList(), baseIndent, childIndent);
        }
        if (container instanceof SumType sum) {
            String head = resolveParenSumHead(sum);
            return head == null ? null : renderExpandedSchemaParen(head, sum.getSchemaTypeList(), baseIndent, childIndent);
        }
        if (container instanceof EnumDef enumDef) {
            List<ValueKeyword> variants = enumDef.getValueKeywordList();
            if (variants.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.TYPE_ENUM)
                .append(StvnVocabulary.DELIM_OPEN_BRACKET);
            for (ValueKeyword vk : variants) {
                sb.append('\n').append(childIndent).append(vk.getText().trim());
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return sb.toString();
        }
        if (container instanceof MetadataMap metaMap) {
            List<MetadataEntry> entries = metaMap.getMetadataEntryList();
            if (entries.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (MetadataEntry entry : entries) {
                sb.append('\n').append(childIndent).append(renderCollapsedMetadataEntry(entry));
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return sb.toString();
        }
        if (container instanceof UseAliasBlock aliasBlock) {
            List<UseMapAlias> aliases = aliasBlock.getUseMapAliasList();
            if (aliases.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (UseMapAlias alias : aliases) {
                sb.append('\n').append(childIndent).append(renderCollapsedUseMapAlias(alias));
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return sb.toString();
        }
        if (container instanceof UseStmt useStmt) {
            List<UseElement> elems = useStmt.getUseElementList();
            if (elems.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.KEYWORD_USE)
                .append(' ')
                .append(StvnVocabulary.DELIM_OPEN_BRACKET);
            for (UseElement elem : elems) {
                sb.append('\n').append(childIndent).append(renderExpandedUseElement(elem, childIndent));
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return sb.toString();
        }
        if (container instanceof IncludeStmt incStmt) {
            List<IncludeElement> elems = incStmt.getIncludeElementList();
            if (elems.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.KEYWORD_INCLUDE)
                .append(' ')
                .append(StvnVocabulary.DELIM_OPEN_BRACKET);
            for (IncludeElement elem : elems) {
                sb.append('\n').append(childIndent).append(elem.getText().trim());
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return sb.toString();
        }
        if (container instanceof TupleLiteral tup) {
            return renderExpandedValueList(
                StvnVocabulary.DELIM_OPEN_PAREN,
                StvnVocabulary.DELIM_CLOSE_PAREN,
                tup.getValueList(),
                baseIndent,
                childIndent
            );
        }
        if (container instanceof ListLiteral list) {
            return renderExpandedValueList(
                StvnVocabulary.DELIM_OPEN_BRACKET,
                StvnVocabulary.DELIM_CLOSE_BRACKET,
                list.getValueList(),
                baseIndent,
                childIndent
            );
        }
        if (container instanceof MapLiteral map) {
            List<MapEntrySlice> slices = listMapEntrySlices(map);
            if (slices.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (MapEntrySlice slice : slices) {
                boolean sliceCollapsible = isRangeSafeToCollapse(map.getNode(), slice.lbrack(), slice.rbrack())
                    && isSingleLineSpan(chars, slice.keyValue().getNode(), slice.keyValue().getNode())
                    && isSingleLineSpan(chars, slice.entryValue().getNode(), slice.entryValue().getNode());
                sb.append('\n').append(childIndent);
                if (sliceCollapsible) {
                    sb.append(renderCollapsedMapEntry(slice));
                } else {
                    sb.append(renderExpandedMapEntrySlice(slice, childIndent));
                }
            }
            sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return sb.toString();
        }
        return null;
    }

    private static @Nullable String renderExpandedSchemaParen(
            String head,
            List<SchemaType> args,
            String baseIndent,
            String childIndent
    ) {
        if (args.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(head).append(StvnVocabulary.DELIM_OPEN_PAREN);
        for (SchemaType arg : args) {
            sb.append('\n').append(childIndent).append(renderCollapsedSchemaType(arg));
        }
        sb.append('\n').append(baseIndent).append(StvnVocabulary.DELIM_CLOSE_PAREN);
        return sb.toString();
    }

    private static String renderExpandedUseElement(UseElement elem, String elemIndent) {
        StringBuilder sb = new StringBuilder();
        if (elem.getUseTarget() != null) {
            sb.append(elem.getUseTarget().getText().trim());
        }
        UseOptionsBlock opt = elem.getUseOptionsBlock();
        if (opt != null) {
            sb.append(' ').append(renderCollapsedUseOptionsBlock(opt));
        }
        UseAliasBlock aliasBlock = elem.getUseAliasBlock();
        if (aliasBlock != null) {
            List<UseMapAlias> aliases = aliasBlock.getUseMapAliasList();
            if (aliases.isEmpty()) {
                sb.append(' ').append(StvnVocabulary.DELIM_OPEN_BRACE).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            } else {
                String aliasIndent = elemIndent + INDENT_STEP;
                sb.append(' ').append(StvnVocabulary.DELIM_OPEN_BRACE);
                for (UseMapAlias alias : aliases) {
                    sb.append('\n').append(aliasIndent).append(renderCollapsedUseMapAlias(alias));
                }
                sb.append('\n').append(elemIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            }
        }
        return sb.toString();
    }

    private static @Nullable String renderExpandedValueList(
            String openDelim,
            String closeDelim,
            List<Value> values,
            String baseIndent,
            String childIndent
    ) {
        if (values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(openDelim);
        for (Value v : values) {
            sb.append('\n').append(childIndent).append(renderCollapsedValue(v));
        }
        sb.append('\n').append(baseIndent).append(closeDelim);
        return sb.toString();
    }

    private static @Nullable String renderCollapsedContainer(PsiElement container) {
        if (container instanceof ProductType prod) {
            return renderCollapsedSchemaParen(StvnVocabulary.TYPE_TUPLE, prod.getSchemaTypeList());
        }
        if (container instanceof CollectionType coll) {
            String head = resolveCollectionHead(coll);
            return head == null ? null : renderCollapsedSchemaParen(head, coll.getSchemaTypeList());
        }
        if (container instanceof SumType sum) {
            String head = resolveParenSumHead(sum);
            return head == null ? null : renderCollapsedSchemaParen(head, sum.getSchemaTypeList());
        }
        if (container instanceof EnumDef enumDef) {
            StringBuilder sb = new StringBuilder(StvnVocabulary.TYPE_ENUM)
                .append(StvnVocabulary.DELIM_OPEN_BRACKET);
            List<ValueKeyword> variants = enumDef.getValueKeywordList();
            for (int i = 0; i < variants.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(variants.get(i).getText().trim());
            }
            sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return sb.toString();
        }
        if (container instanceof MetadataMap metaMap) {
            return renderCollapsedMetadataMap(metaMap);
        }
        if (container instanceof UseAliasBlock aliasBlock) {
            return renderCollapsedUseAliasBlock(aliasBlock);
        }
        if (container instanceof UseStmt useStmt) {
            List<UseElement> elems = useStmt.getUseElementList();
            StringBuilder sb = new StringBuilder(StvnVocabulary.KEYWORD_USE)
                .append(' ')
                .append(StvnVocabulary.DELIM_OPEN_BRACKET);
            for (UseElement elem : elems) {
                sb.append(' ').append(renderCollapsedUseElement(elem));
            }
            sb.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return sb.toString();
        }
        if (container instanceof IncludeStmt incStmt) {
            List<IncludeElement> elems = incStmt.getIncludeElementList();
            StringBuilder sb = new StringBuilder(StvnVocabulary.KEYWORD_INCLUDE)
                .append(' ')
                .append(StvnVocabulary.DELIM_OPEN_BRACKET);
            for (IncludeElement elem : elems) {
                sb.append(' ').append(elem.getText().trim());
            }
            sb.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return sb.toString();
        }
        if (container instanceof TupleLiteral tup) {
            return renderCollapsedValueList(
                StvnVocabulary.DELIM_OPEN_PAREN,
                StvnVocabulary.DELIM_CLOSE_PAREN,
                tup.getValueList()
            );
        }
        if (container instanceof ListLiteral list) {
            return renderCollapsedValueList(
                StvnVocabulary.DELIM_OPEN_BRACKET,
                StvnVocabulary.DELIM_CLOSE_BRACKET,
                list.getValueList()
            );
        }
        if (container instanceof MapLiteral map) {
            List<MapEntrySlice> slices = listMapEntrySlices(map);
            StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (int i = 0; i < slices.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(renderCollapsedMapEntry(slices.get(i)));
            }
            sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return sb.toString();
        }
        return null;
    }

    private static String renderCollapsedSchemaType(SchemaType schema) {
        StringBuilder sb = new StringBuilder();
        if (schema.getMetadataMap() != null) {
            sb.append(renderCollapsedMetadataMap(schema.getMetadataMap())).append(' ');
        }
        if (schema.getTypeKeyword() != null) {
            sb.append(schema.getTypeKeyword().getText().trim());
        } else if (schema.getSchemaConstructor() != null) {
            SchemaConstructor ctor = schema.getSchemaConstructor();
            if (ctor.getAtomicType() != null) {
                sb.append(ctor.getAtomicType().getText().trim());
            } else if (ctor.getProductType() != null) {
                sb.append(renderCollapsedSchemaParen(StvnVocabulary.TYPE_TUPLE, ctor.getProductType().getSchemaTypeList()));
            } else if (ctor.getCollectionType() != null) {
                String head = resolveCollectionHead(ctor.getCollectionType());
                sb.append(head != null
                    ? renderCollapsedSchemaParen(head, ctor.getCollectionType().getSchemaTypeList())
                    : ctor.getCollectionType().getText().trim());
            } else if (ctor.getSumType() != null) {
                SumType sum = ctor.getSumType();
                if (sum.getEnumDef() != null) {
                    String collapsedEnum = renderCollapsedContainer(sum.getEnumDef());
                    sb.append(collapsedEnum != null ? collapsedEnum : sum.getText().trim());
                } else {
                    String head = resolveParenSumHead(sum);
                    sb.append(head != null
                        ? renderCollapsedSchemaParen(head, sum.getSchemaTypeList())
                        : sum.getText().trim());
                }
            }
        }
        return sb.toString();
    }

    private static String renderCollapsedSchemaParen(String head, List<SchemaType> args) {
        StringBuilder sb = new StringBuilder(head).append(StvnVocabulary.DELIM_OPEN_PAREN);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(renderCollapsedSchemaType(args.get(i)));
        }
        sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
        return sb.toString();
    }

    private static String renderCollapsedMetadataMap(MetadataMap metaMap) {
        List<MetadataEntry> entries = metaMap.getMetadataEntryList();
        if (entries.isEmpty()) {
            return StvnVocabulary.DELIM_OPEN_BRACE + StvnVocabulary.DELIM_CLOSE_BRACE;
        }
        StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
        for (MetadataEntry entry : entries) {
            sb.append(' ').append(renderCollapsedMetadataEntry(entry));
        }
        sb.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACE);
        return sb.toString();
    }

    private static String renderCollapsedMetadataEntry(MetadataEntry entry) {
        if (entry.getMetadataFilter() != null) {
            ASTNode kw = firstNonWsAstChild(entry.getMetadataFilter().getNode());
            String kwText = kw != null ? kw.getText().trim() : "";
            StringBuilder variants = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACKET);
            var list = entry.getMetadataFilter().getVariantList().getValueKeywordList();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    variants.append(' ');
                }
                variants.append(list.get(i).getText().trim());
            }
            variants.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return kwText + " " + variants;
        }
        ASTNode node = entry.getNode();
        ASTNode first = firstNonWsAstChild(node);
        if (first == null) {
            return entry.getText().trim();
        }
        if (first.getElementType() == StvnTypes.METADATA_BARE_FLAG
                || first.getElementType() == StvnTypes.METADATA_DIRECTIVE) {
            return first.getText().trim();
        }
        ASTNode key = (first.getElementType() == StvnTypes.METADATA_BOOL
                || first.getElementType() == StvnTypes.METADATA_TRAIT
                || first.getElementType() == StvnTypes.METADATA_UNKNOWN_ENTRY)
            ? firstNonWsAstChild(first)
            : first;
        if (key == null) {
            return entry.getText().trim();
        }
        ASTNode arg = nextNonWsAstSibling(key);
        if (arg == null && key.getTreeParent() != node) {
            arg = nextNonWsAstSibling(key.getTreeParent());
        }
        if (arg == null) {
            return key.getText().trim();
        }
        if (arg.getPsi() instanceof Value valPsi) {
            return key.getText().trim() + " " + renderCollapsedValue(valPsi);
        }
        if (arg.getPsi() instanceof SchemaType stPsi) {
            return key.getText().trim() + " " + renderCollapsedSchemaType(stPsi);
        }
        return key.getText().trim() + " " + arg.getText().trim();
    }

    private static String renderCollapsedUseElement(UseElement elem) {
        StringBuilder sb = new StringBuilder();
        if (elem.getUseTarget() != null) {
            sb.append(elem.getUseTarget().getText().trim());
        }
        if (elem.getUseOptionsBlock() != null) {
            sb.append(' ').append(renderCollapsedUseOptionsBlock(elem.getUseOptionsBlock()));
        }
        if (elem.getUseAliasBlock() != null) {
            sb.append(' ').append(renderCollapsedUseAliasBlock(elem.getUseAliasBlock()));
        }
        return sb.toString();
    }

    private static String renderCollapsedUseOptionsBlock(UseOptionsBlock optBlock) {
        var opts = optBlock.getUseOptionList();
        if (opts.isEmpty()) {
            return StvnVocabulary.DELIM_OPEN_BRACE + StvnVocabulary.DELIM_CLOSE_BRACE;
        }
        StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
        for (var opt : opts) {
            sb.append(' ').append(opt.getText().trim().replaceAll("\\s+", " "));
        }
        sb.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACE);
        return sb.toString();
    }

    private static String renderCollapsedUseAliasBlock(UseAliasBlock aliasBlock) {
        List<UseMapAlias> aliases = aliasBlock.getUseMapAliasList();
        if (aliases.isEmpty()) {
            return StvnVocabulary.DELIM_OPEN_BRACE + StvnVocabulary.DELIM_CLOSE_BRACE;
        }
        StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
        for (UseMapAlias alias : aliases) {
            sb.append(' ').append(renderCollapsedUseMapAlias(alias));
        }
        sb.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACE);
        return sb.toString();
    }

    private static String renderCollapsedUseMapAlias(UseMapAlias alias) {
        var typeKws = alias.getTypeKeywordList();
        if (typeKws.size() >= 2) {
            return typeKws.get(0).getText().trim() + " " + typeKws.get(1).getText().trim();
        }
        var valKws = alias.getValueKeywordList();
        if (valKws.size() >= 2) {
            return valKws.get(0).getText().trim() + " " + valKws.get(1).getText().trim();
        }
        return alias.getText().trim();
    }

    private static String renderCollapsedValue(Value val) {
        if (val.getCollectionValue() != null) {
            var coll = val.getCollectionValue();
            if (coll.getTupleLiteral() != null) {
                return renderCollapsedValueList(
                    StvnVocabulary.DELIM_OPEN_PAREN,
                    StvnVocabulary.DELIM_CLOSE_PAREN,
                    coll.getTupleLiteral().getValueList()
                );
            }
            if (coll.getListLiteral() != null) {
                return renderCollapsedValueList(
                    StvnVocabulary.DELIM_OPEN_BRACKET,
                    StvnVocabulary.DELIM_CLOSE_BRACKET,
                    coll.getListLiteral().getValueList()
                );
            }
            if (coll.getMapLiteral() != null) {
                String collapsed = renderCollapsedContainer(coll.getMapLiteral());
                return collapsed != null ? collapsed : coll.getMapLiteral().getText().trim();
            }
        }
        if (val.getExplicitOptionValue() != null) {
            var opt = val.getExplicitOptionValue();
            ASTNode tag = firstNonWsAstChild(opt.getNode());
            String tagText = tag != null ? tag.getText().trim() : "";
            return opt.getValue() != null ? tagText + " " + renderCollapsedValue(opt.getValue()) : tagText;
        }
        if (val.getExplicitEitherValue() != null) {
            var eith = val.getExplicitEitherValue();
            ASTNode tag = firstNonWsAstChild(eith.getNode());
            String tagText = tag != null ? tag.getText().trim() : "";
            return eith.getValue() != null ? tagText + " " + renderCollapsedValue(eith.getValue()) : tagText;
        }
        if (val.getExplicitUnionValue() != null) {
            var un = val.getExplicitUnionValue();
            ASTNode tag = firstNonWsAstChild(un.getNode());
            String tagText = tag != null ? tag.getText().trim() : "";
            return un.getValue() != null ? tagText + " " + renderCollapsedValue(un.getValue()) : tagText;
        }
        return val.getText().trim();
    }

    private static String renderCollapsedValueList(String openDelim, String closeDelim, List<Value> values) {
        StringBuilder sb = new StringBuilder(openDelim);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(renderCollapsedValue(values.get(i)));
        }
        sb.append(closeDelim);
        return sb.toString();
    }

    /**
     * Renders {@code val} onto a single canonical line without interior delimiter padding when
     * {@code val} is error-free and contains neither comments nor fenced strings; otherwise returns
     * {@code val.getText().trim()}.
     *
     * @param val STVN value PSI node
     * @return canonical single-line value text, or trimmed raw text when non-collapsible
     */
    public static String renderCollapsedOrTrimmedValue(Value val) {
        if (!PsiTreeUtil.hasErrorElements(val) && isSubtreeSafeToCollapse(val.getNode())) {
            return renderCollapsedValue(val);
        }
        return val.getText().trim();
    }

    private static String renderCollapsedMapEntry(MapEntrySlice slice) {
        return StvnVocabulary.DELIM_OPEN_BRACKET
            + renderCollapsedValue(slice.keyValue())
            + " "
            + renderCollapsedValue(slice.entryValue())
            + StvnVocabulary.DELIM_CLOSE_BRACKET;
    }

    private static String renderExpandedMapEntrySlice(MapEntrySlice slice, String sliceIndent) {
        String slotIndent = sliceIndent + INDENT_STEP;
        String keyRendered = isSubtreeSafeToCollapse(slice.keyValue().getNode())
            ? renderCollapsedValue(slice.keyValue())
            : reindentMultiLineValueText(slice.keyValue(), slotIndent);
        String valRendered = isSubtreeSafeToCollapse(slice.entryValue().getNode())
            ? renderCollapsedValue(slice.entryValue())
            : reindentMultiLineValueText(slice.entryValue(), slotIndent);
        return StvnVocabulary.DELIM_OPEN_BRACKET
            + "\n" + slotIndent + keyRendered
            + "\n" + slotIndent + valRendered
            + "\n" + sliceIndent + StvnVocabulary.DELIM_CLOSE_BRACKET;
    }

    private static String reindentMultiLineValueText(Value val, String targetSlotIndent) {
        PsiElement strChild = val.getStringLiteral();
        if (strChild != null && strChild.getNode().findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null) {
            boolean preserve = org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator.resolvePreserveIndentForStringLiteral(strChild);
            var decodedOpt = org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator.decodeStringLiteral(strChild.getText(), preserve);
            String raw = strChild.getText();
            int closeBrack = raw.indexOf(StvnVocabulary.DELIM_CLOSE_BRACKET, 4);
            if (decodedOpt.isPresent() && raw.startsWith("\"\"\"" + StvnVocabulary.DELIM_OPEN_BRACKET) && closeBrack > 4) {
                String tag = raw.substring(4, closeBrack);
                return org.stvnadore.plugin.intentions.StvnConvertEscapedStringToFencedIntention.renderFencedLiteral(
                    decodedOpt.get(),
                    tag,
                    preserve,
                    targetSlotIndent.length()
                );
            }
        }
        return val.getText().trim();
    }

    private static TextRange replacementRangeForContainer(PsiElement container) {
        if (container instanceof EnumDef enumDef && enumDef.getParent() instanceof SumType sumType) {
            return sumType.getTextRange();
        }
        return container.getTextRange();
    }

    private static PsiElement normalizeEnumCandidate(PsiElement element) {
        if (element instanceof SumType sum && sum.getEnumDef() != null) {
            return sum.getEnumDef();
        }
        return element;
    }

    private static boolean isExpandableContainerKind(PsiElement el) {
        if (el instanceof ProductType
                || el instanceof CollectionType
                || el instanceof EnumDef
                || el instanceof MetadataMap
                || el instanceof UseAliasBlock
                || el instanceof UseStmt
                || el instanceof IncludeStmt
                || el instanceof TupleLiteral
                || el instanceof ListLiteral
                || el instanceof MapLiteral) {
            return true;
        }
        return el instanceof SumType sum && sum.getEnumDef() == null;
    }

    private static boolean isParentEligibleForChildCollapse(PsiElement el) {
        if (el instanceof TupleLiteral
                || el instanceof ListLiteral
                || el instanceof MapLiteral
                || el instanceof ProductType
                || el instanceof CollectionType
                || el instanceof DefsEntry
                || el instanceof PackageEnclosure) {
            return true;
        }
        return el instanceof SumType sum && sum.getEnumDef() == null;
    }

    private static boolean isEmptyContainer(PsiElement container) {
        if (container instanceof ProductType prod) {
            return prod.getSchemaTypeList().isEmpty();
        }
        if (container instanceof CollectionType coll) {
            return coll.getSchemaTypeList().isEmpty();
        }
        if (container instanceof SumType sum) {
            return sum.getSchemaTypeList().isEmpty();
        }
        if (container instanceof EnumDef enumDef) {
            return enumDef.getValueKeywordList().isEmpty();
        }
        if (container instanceof MetadataMap metaMap) {
            return metaMap.getMetadataEntryList().isEmpty();
        }
        if (container instanceof UseAliasBlock aliasBlock) {
            return aliasBlock.getUseMapAliasList().isEmpty();
        }
        if (container instanceof UseStmt useStmt) {
            return useStmt.getUseElementList().isEmpty();
        }
        if (container instanceof IncludeStmt incStmt) {
            return incStmt.getIncludeElementList().isEmpty();
        }
        if (container instanceof TupleLiteral tup) {
            return tup.getValueList().isEmpty();
        }
        if (container instanceof ListLiteral list) {
            return list.getValueList().isEmpty();
        }
        if (container instanceof MapLiteral map) {
            return listMapEntrySlices(map).isEmpty();
        }
        return true;
    }

    private static @Nullable ASTNode findOpenDelim(PsiElement container) {
        ASTNode node = container.getNode();
        IElementType t = node.getElementType();
        if (t == StvnTypes.PRODUCT_TYPE || t == StvnTypes.COLLECTION_TYPE || t == StvnTypes.SUM_TYPE
                || t == StvnTypes.TUPLE_LITERAL) {
            return node.findChildByType(StvnTypes.LPAREN);
        }
        if (t == StvnTypes.ENUM_DEF || t == StvnTypes.USE_STMT || t == StvnTypes.INCLUDE_STMT
                || t == StvnTypes.LIST_LITERAL) {
            return node.findChildByType(StvnTypes.LBRACK);
        }
        if (t == StvnTypes.METADATA_MAP || t == StvnTypes.USE_ALIAS_BLOCK || t == StvnTypes.MAP_LITERAL
                || t == StvnTypes.DEFS_ENTRY || t == StvnTypes.PACKAGE_ENCLOSURE) {
            return node.findChildByType(StvnTypes.LBRACE);
        }
        return null;
    }

    private static @Nullable ASTNode findCloseDelim(PsiElement container) {
        ASTNode node = container.getNode();
        IElementType t = node.getElementType();
        if (t == StvnTypes.PRODUCT_TYPE || t == StvnTypes.COLLECTION_TYPE || t == StvnTypes.SUM_TYPE
                || t == StvnTypes.TUPLE_LITERAL) {
            return findLastDirectAstChild(node, StvnTypes.RPAREN);
        }
        if (t == StvnTypes.ENUM_DEF || t == StvnTypes.USE_STMT || t == StvnTypes.INCLUDE_STMT
                || t == StvnTypes.LIST_LITERAL) {
            return findLastDirectAstChild(node, StvnTypes.RBRACK);
        }
        if (t == StvnTypes.METADATA_MAP || t == StvnTypes.USE_ALIAS_BLOCK || t == StvnTypes.MAP_LITERAL
                || t == StvnTypes.DEFS_ENTRY || t == StvnTypes.PACKAGE_ENCLOSURE) {
            return findLastDirectAstChild(node, StvnTypes.RBRACE);
        }
        return null;
    }

    private static @Nullable String resolveCollectionHead(CollectionType coll) {
        ASTNode node = coll.getNode();
        if (node.findChildByType(StvnTypes.COLL_MAP) != null) {
            return StvnVocabulary.TYPE_MAP;
        }
        if (node.findChildByType(StvnTypes.COLL_SEQ) != null) {
            return StvnVocabulary.TYPE_SEQ;
        }
        if (node.findChildByType(StvnTypes.COLL_SET) != null) {
            return StvnVocabulary.TYPE_SET;
        }
        return null;
    }

    private static @Nullable String resolveParenSumHead(SumType sum) {
        ASTNode node = sum.getNode();
        if (node.findChildByType(StvnTypes.KW_OPTION) != null) {
            return StvnVocabulary.TYPE_OPTION;
        }
        if (node.findChildByType(StvnTypes.KW_EITHER) != null) {
            return StvnVocabulary.TYPE_EITHER;
        }
        if (node.findChildByType(StvnTypes.KW_UNION) != null) {
            return StvnVocabulary.TYPE_UNION;
        }
        return null;
    }

    private static boolean isRangeSafeToCollapse(ASTNode ownerNode, ASTNode openDelim, ASTNode closeDelim) {
        int start = openDelim.getTextRange().getEndOffset();
        int end = closeDelim.getStartOffset();
        return isWindowSafeToCollapse(ownerNode, start, end);
    }

    private static boolean isSubtreeSafeToCollapse(ASTNode root) {
        return isWindowSafeToCollapse(root, root.getStartOffset(), root.getTextRange().getEndOffset());
    }

    private static boolean isWindowSafeToCollapse(ASTNode node, int startOffset, int endOffset) {
        TextRange r = node.getTextRange();
        if (r.getEndOffset() <= startOffset || r.getStartOffset() >= endOffset) {
            return true;
        }
        IElementType t = node.getElementType();
        if (t == StvnTypes.COMMENT
                || t == StvnTypes.LITERAL_STRING_FENCED
                || t == StvnTypes.MALFORMED_FENCE
                || t == TokenType.ERROR_ELEMENT
                || node.getPsi() instanceof PsiComment) {
            return false;
        }
        for (ASTNode c = node.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (!isWindowSafeToCollapse(c, startOffset, endOffset)) {
                return false;
            }
        }
        return true;
    }

    private static List<MapEntrySlice> listMapEntrySlices(MapLiteral map) {
        List<MapEntrySlice> result = new ArrayList<>();
        ASTNode open = map.getNode().findChildByType(StvnTypes.LBRACE);
        ASTNode close = findLastDirectAstChild(map.getNode(), StvnTypes.RBRACE);
        if (open == null || close == null) {
            return result;
        }
        ASTNode cur = open.getTreeNext();
        while (cur != null && cur != close) {
            if (cur.getElementType() == StvnTypes.LBRACK) {
                ASTNode lbrack = cur;
                ASTNode keyNode = nextNonWsAstSibling(lbrack);
                ASTNode valNode = keyNode != null ? nextNonWsAstSibling(keyNode) : null;
                ASTNode rbrack = valNode != null ? nextNonWsAstSibling(valNode) : null;
                if (keyNode != null && valNode != null && rbrack != null
                        && rbrack.getElementType() == StvnTypes.RBRACK
                        && keyNode.getPsi() instanceof Value keyPsi
                        && valNode.getPsi() instanceof Value valPsi) {
                    result.add(new MapEntrySlice(lbrack, keyPsi, valPsi, rbrack));
                    cur = rbrack.getTreeNext();
                    continue;
                }
            }
            cur = cur.getTreeNext();
        }
        return result;
    }

    private static @Nullable MapEntrySlice findEnclosingMapEntrySlice(MapLiteral map, int caretOffset) {
        for (MapEntrySlice slice : listMapEntrySlices(map)) {
            TextRange r = slice.textRange();
            if (caretOffset >= r.getStartOffset() && caretOffset <= r.getEndOffset()) {
                return slice;
            }
        }
        return null;
    }

    private static @Nullable PsiElement findCaretLeaf(PsiFile file, int caretOffset) {
        int len = file.getTextLength();
        if (len == 0) {
            return null;
        }
        int clamped = Math.max(0, Math.min(caretOffset, len));
        if (clamped < len) {
            PsiElement at = file.findElementAt(clamped);
            if (at != null && at.getNode().getElementType() != TokenType.WHITE_SPACE) {
                return at;
            }
        }
        if (clamped > 0) {
            PsiElement prev = file.findElementAt(clamped - 1);
            if (prev != null) {
                IElementType pt = prev.getNode().getElementType();
                if (pt == StvnTypes.RPAREN || pt == StvnTypes.RBRACK || pt == StvnTypes.RBRACE || clamped == len) {
                    return prev;
                }
            }
        }
        return clamped < len ? file.findElementAt(clamped) : file.findElementAt(len - 1);
    }

    private static boolean isSingleLineSpan(CharSequence chars, @Nullable ASTNode open, @Nullable ASTNode close) {
        if (open == null || close == null) {
            return false;
        }
        return lineOf(chars, open.getStartOffset()) == lineOf(chars, close.getStartOffset());
    }

    private static int lineOf(CharSequence chars, int offset) {
        int clamped = Math.max(0, Math.min(offset, chars.length()));
        int line = 0;
        for (int i = 0; i < clamped; i++) {
            if (chars.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String lineIndentOfOffset(CharSequence chars, int offset) {
        int clamped = Math.max(0, Math.min(offset, chars.length()));
        int lineStart = clamped;
        while (lineStart > 0 && chars.charAt(lineStart - 1) != '\n' && chars.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int wsEnd = lineStart;
        while (wsEnd < clamped && chars.charAt(wsEnd) == ' ') {
            wsEnd++;
        }
        return chars.subSequence(lineStart, wsEnd).toString();
    }

    private static boolean hasNonWhitespaceBeforeOnSameLine(CharSequence chars, int offset) {
        for (int i = offset - 1; i >= 0; i--) {
            char c = chars.charAt(i);
            if (c == '\n' || c == '\r') {
                return false;
            }
            if (c != ' ' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonWhitespaceAfterOnSameLine(CharSequence chars, int startOffset, int limitOffset) {
        int end = Math.min(chars.length(), limitOffset);
        for (int i = Math.max(0, startOffset); i < end; i++) {
            char c = chars.charAt(i);
            if (c == '\n' || c == '\r') {
                return false;
            }
            if (c != ' ' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    private static int retreatHorizontalSpaces(CharSequence chars, int offset) {
        int cur = Math.max(0, Math.min(offset, chars.length()));
        while (cur > 0 && (chars.charAt(cur - 1) == ' ' || chars.charAt(cur - 1) == '\t')) {
            cur--;
        }
        return cur;
    }

    private static int advanceHorizontalSpaces(CharSequence chars, int offset) {
        int cur = Math.max(0, Math.min(offset, chars.length()));
        while (cur < chars.length() && (chars.charAt(cur) == ' ' || chars.charAt(cur) == '\t')) {
            cur++;
        }
        return cur;
    }

    private static @Nullable ASTNode findLastDirectAstChild(ASTNode parent, IElementType type) {
        ASTNode found = null;
        for (ASTNode c = parent.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() == type) {
                found = c;
            }
        }
        return found;
    }

    private static @Nullable ASTNode firstNonWsAstChild(ASTNode parent) {
        for (ASTNode c = parent.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() != TokenType.WHITE_SPACE && c.getElementType() != StvnTypes.COMMENT) {
                return c;
            }
        }
        return null;
    }

    private static @Nullable ASTNode nextNonWsAstSibling(ASTNode node) {
        for (ASTNode c = node.getTreeNext(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() != TokenType.WHITE_SPACE && c.getElementType() != StvnTypes.COMMENT) {
                return c;
            }
        }
        return null;
    }

    private static @Nullable PsiElement firstNonWsPsiChild(PsiElement parent) {
        ASTNode first = firstNonWsAstChild(parent.getNode());
        return first != null ? first.getPsi() : null;
    }
}
