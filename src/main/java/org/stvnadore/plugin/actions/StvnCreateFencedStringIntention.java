package org.stvnadore.plugin.actions;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.intentions.StvnContainerLayoutUtil;
import org.stvnadore.plugin.intentions.StvnConvertEscapedStringToFencedIntention;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Intention action that converts an escape-free double-quoted simple string literal (or an incomplete
 * opening triple-quote recovery token) into a canonical STVN 2.0.0 Rule {@code STR-04} fenced string block.
 */
@NullMarked
public final class StvnCreateFencedStringIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    private static final String DEFAULT_RECOVERY_TAG = "FENCE";
    private static final String CARE_SENTINEL = "\u0000CARET\u0000";

    /**
     * Constructs a new {@code StvnCreateFencedStringIntention} instance.
     */
    public StvnCreateFencedStringIntention() {
        setText(StvnBundle.message("stvn.intention.convert.string.to.fenced.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.convert.string.to.fenced.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.convert.string.to.fenced.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }
        var file = element.getContainingFile();
        if (file == null
                || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)
                || StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
            return false;
        }
        if (StvnConvertEscapedStringToFencedIntention.canConvertUnescapedSimpleStringToFenced(element, editor)) {
            return true;
        }
        if (isSimpleOrFencedStringElement(element, editor, file)) {
            return false;
        }
        if (editor == null) {
            return false;
        }

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        if (offset < 0 || offset > doc.getTextLength()) {
            return false;
        }

        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) {
            return false;
        }

        int tripleStart = lineStart + tripleIdx;
        int tripleEnd = tripleStart + 3;
        if (offset < tripleStart || offset > tripleEnd + 1) {
            return false;
        }

        String afterTriple = lineText.substring(tripleIdx + 3).trim();
        if (afterTriple.startsWith(StvnVocabulary.DELIM_OPEN_BRACKET)) {
            return false;
        }

        String beforeTriple = lineText.substring(0, tripleIdx).trim();
        return !beforeTriple.endsWith(StvnVocabulary.DELIM_CLOSE_BRACKET);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        PsiElement element = StvnConvertEscapedStringToFencedIntention.findSimpleStringElementAtOrBeforeCaret(
            file, editor.getCaretModel().getOffset()
        );
        if (element == null) {
            element = getElement(editor, file);
        }
        if (element == null || !isAvailable(project, editor, element)) {
            return IntentionPreviewInfo.EMPTY;
        }
        invoke(project, editor, element);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        if (editor == null) {
            return;
        }
        var file = element.getContainingFile();
        if (file == null) {
            return;
        }

        if (StvnConvertEscapedStringToFencedIntention.invokeUnescapedSimpleStringToFenced(project, editor, element)) {
            return;
        }
        if (isSimpleOrFencedStringElement(element, editor, file)) {
            return;
        }

        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = editor.getDocument();
        }
        int offset = editor.getCaretModel().getOffset();
        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) {
            return;
        }

        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
        String tagToken = StvnVocabulary.DELIM_OPEN_BRACKET + DEFAULT_RECOVERY_TAG + StvnVocabulary.DELIM_CLOSE_BRACKET;
        boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file);

        if (tryExpandIncompleteFenceInsideMap(project, file, doc, editor, lineStart, lineText, tripleIdx, lineSep, tagToken, commitPhysical)) {
            return;
        }

        StringBuilder baseIndentSb = new StringBuilder();
        for (int i = lineStart; i < lineEnd; i++) {
            char c = doc.getCharsSequence().charAt(i);
            if (c == ' ' || c == '\t') {
                baseIndentSb.append(c);
            } else {
                break;
            }
        }
        String baseIndent = baseIndentSb.toString();
        String innerIndent = baseIndent + "  ";

        int insertOffset = lineStart + tripleIdx + 3;
        String insertion = tagToken + lineSep + innerIndent + lineSep + baseIndent + tagToken + "\"\"\"";
        doc.insertString(insertOffset, insertion);
        if (commitPhysical) {
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        int caretPos = insertOffset + tagToken.length() + lineSep.length() + innerIndent.length();
        editor.getCaretModel().moveToOffset(caretPos);
    }

    private static boolean tryExpandIncompleteFenceInsideMap(
            Project project,
            PsiFile file,
            Document doc,
            Editor editor,
            int lineStart,
            String lineText,
            int tripleIdx,
            String lineSep,
            String tagToken,
            boolean commitPhysical
    ) {
        int entryBrackOpen = findEnclosingBracketOnLine(lineText, tripleIdx);
        if (entryBrackOpen < 0) {
            return false;
        }
        int entryBrackClose = findMatchingBracketOnLine(lineText, entryBrackOpen, tripleIdx);
        if (entryBrackClose < 0) {
            return false;
        }

        int outerBraceOpen = findEnclosingBraceOnLine(lineText, entryBrackOpen);
        int outerBraceClose = outerBraceOpen >= 0
            ? findMatchingBraceOnLine(lineText, outerBraceOpen, entryBrackClose)
            : -1;

        if (outerBraceOpen >= 0 && outerBraceClose > entryBrackClose) {
            PsiElement braceLeaf = file.findElementAt(lineStart + outerBraceOpen);
            if (braceLeaf == null || PsiTreeUtil.getParentOfType(braceLeaf, MapLiteral.class, false) == null) {
                return false;
            }
            String mapIndent = leadingSpacesOf(lineText);
            String entryIndent = mapIndent + "  ";
            String innerBody = lineText.substring(outerBraceOpen + 1, outerBraceClose);
            int relTriple = tripleIdx - (outerBraceOpen + 1);
            List<TextRange> bracketSlices = splitTopLevelBracketSlices(innerBody, relTriple);
            if (bracketSlices.isEmpty()) {
                return false;
            }
            StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (TextRange slice : bracketSlices) {
                sb.append(lineSep).append(entryIndent);
                if (relTriple >= slice.getStartOffset() && relTriple < slice.getEndOffset()) {
                    String expandedEntry = buildFourSlotIncompleteEntry(
                        project,
                        innerBody.substring(slice.getStartOffset() + 1, relTriple),
                        innerBody.substring(relTriple + 3, slice.getEndOffset() - 1),
                        entryIndent,
                        lineSep,
                        tagToken
                    );
                    if (expandedEntry == null) {
                        return false;
                    }
                    sb.append(expandedEntry);
                } else {
                    String siblingSlice = innerBody.substring(slice.getStartOffset(), slice.getEndOffset());
                    sb.append(normalizeSingleLineMapEntrySnippet(project, siblingSlice));
                }
            }
            sb.append(lineSep).append(mapIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);

            String rendered = sb.toString();
            int sentinelIdx = rendered.indexOf(CARE_SENTINEL);
            String cleanRendered = rendered.replace(CARE_SENTINEL, "");
            int replaceStart = lineStart + outerBraceOpen;
            int replaceEnd = lineStart + outerBraceClose + 1;
            doc.replaceString(replaceStart, replaceEnd, cleanRendered);
            if (commitPhysical) {
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
            if (sentinelIdx >= 0) {
                editor.getCaretModel().moveToOffset(replaceStart + sentinelIdx);
            }
            return true;
        }

        PsiElement lbrackLeaf = file.findElementAt(lineStart + entryBrackOpen);
        MapLiteral enclosingMap = lbrackLeaf != null
            ? PsiTreeUtil.getParentOfType(lbrackLeaf, MapLiteral.class, false)
            : null;
        if (enclosingMap == null) {
            return false;
        }

        boolean precededOnLine = !lineText.substring(0, entryBrackOpen).isBlank();
        boolean followedOnLine = !lineText.substring(entryBrackClose + 1).isBlank();
        String entryIndent = precededOnLine
            ? leadingSpacesOfOffset(doc.getCharsSequence(), enclosingMap.getTextRange().getStartOffset()) + "  "
            : leadingSpacesOf(lineText);

        String expandedEntry = buildFourSlotIncompleteEntry(
            project,
            lineText.substring(entryBrackOpen + 1, tripleIdx),
            lineText.substring(tripleIdx + 3, entryBrackClose),
            entryIndent,
            lineSep,
            tagToken
        );
        if (expandedEntry == null) {
            return false;
        }
        String fullReplacement = (precededOnLine ? lineSep + entryIndent : "")
            + expandedEntry
            + (followedOnLine ? lineSep + entryIndent : "");
        int sentinelIdx = fullReplacement.indexOf(CARE_SENTINEL);
        String cleanReplacement = fullReplacement.replace(CARE_SENTINEL, "");

        int startCol = precededOnLine ? retreatSpacesInLine(lineText, entryBrackOpen) : entryBrackOpen;
        int endCol = followedOnLine ? advanceSpacesInLine(lineText, entryBrackClose + 1) : entryBrackClose + 1;
        doc.replaceString(lineStart + startCol, lineStart + endCol, cleanReplacement);
        if (commitPhysical) {
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
        if (sentinelIdx >= 0) {
            editor.getCaretModel().moveToOffset(lineStart + startCol + sentinelIdx);
        }
        return true;
    }

    private static @Nullable String buildFourSlotIncompleteEntry(
            Project project,
            String beforeTripleInEntry,
            String afterTripleInEntry,
            String entryIndent,
            String lineSep,
            String tagToken
    ) {
        String keyCand = beforeTripleInEntry.trim();
        String valCand = afterTripleInEntry.trim();
        boolean tripleIsValue = !keyCand.isEmpty() && valCand.isEmpty();
        boolean tripleIsKey = keyCand.isEmpty() && !valCand.isEmpty();
        if (!tripleIsValue && !tripleIsKey) {
            return null;
        }
        String slotIndent = entryIndent + "  ";
        String fenceBodyIndent = slotIndent + "  ";
        String fenceSlot = "\"\"\"" + tagToken
            + lineSep + fenceBodyIndent + CARE_SENTINEL
            + lineSep + slotIndent + tagToken + "\"\"\"";
        String keySlot = tripleIsKey ? fenceSlot : normalizeStandaloneValueSnippet(project, keyCand);
        String valSlot = tripleIsValue ? fenceSlot : normalizeStandaloneValueSnippet(project, valCand);
        return StvnVocabulary.DELIM_OPEN_BRACKET
            + lineSep + slotIndent + keySlot
            + lineSep + slotIndent + valSlot
            + lineSep + entryIndent + StvnVocabulary.DELIM_CLOSE_BRACKET;
    }

    private static String normalizeStandaloneValueSnippet(Project project, String rawValue) {
        String wrapper = StvnVocabulary.DELIM_OPEN_BRACE + " "
            + StvnVocabulary.KEYWORD_BODY + " " + rawValue + " "
            + StvnVocabulary.DELIM_CLOSE_BRACE;
        PsiFile scratch = PsiFileFactory.getInstance(project)
            .createFileFromText("val_norm.stvn", StvnFileType.Payload.INSTANCE, wrapper);
        Value val = PsiTreeUtil.findChildOfType(scratch, Value.class);
        if (val != null && !PsiTreeUtil.hasErrorElements(val)) {
            return StvnContainerLayoutUtil.renderCollapsedOrTrimmedValue(val);
        }
        return rawValue.trim();
    }

    private static String normalizeSingleLineMapEntrySnippet(Project project, String rawBracketEntry) {
        String wrapper = StvnVocabulary.DELIM_OPEN_BRACE + " "
            + StvnVocabulary.KEYWORD_BODY + " "
            + StvnVocabulary.DELIM_OPEN_BRACE + rawBracketEntry + StvnVocabulary.DELIM_CLOSE_BRACE + " "
            + StvnVocabulary.DELIM_CLOSE_BRACE;
        PsiFile scratch = PsiFileFactory.getInstance(project)
            .createFileFromText("entry_norm.stvn", StvnFileType.Payload.INSTANCE, wrapper);
        MapLiteral map = PsiTreeUtil.findChildOfType(scratch, MapLiteral.class);
        if (map != null && !PsiTreeUtil.hasErrorElements(map)) {
            List<Value> vals = PsiTreeUtil.getChildrenOfTypeAsList(map, Value.class);
            if (vals.size() == 2) {
                return StvnVocabulary.DELIM_OPEN_BRACKET
                    + StvnContainerLayoutUtil.renderCollapsedOrTrimmedValue(vals.get(0))
                    + " "
                    + StvnContainerLayoutUtil.renderCollapsedOrTrimmedValue(vals.get(1))
                    + StvnVocabulary.DELIM_CLOSE_BRACKET;
            }
        }
        return rawBracketEntry.trim();
    }

    private static int findEnclosingBracketOnLine(String lineText, int tripleIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = tripleIdx - 1; i >= 0; i--) {
            char c = lineText.charAt(i);
            if (c == '"' && (i == 0 || lineText.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == ']') {
                    depth++;
                } else if (c == '[') {
                    if (depth == 0) {
                        return i;
                    }
                    depth--;
                }
            }
        }
        return -1;
    }

    private static int findMatchingBracketOnLine(String lineText, int openIdx, int tripleIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx + 1; i < lineText.length(); i++) {
            if (i >= tripleIdx && i < tripleIdx + 3) {
                continue;
            }
            char c = lineText.charAt(i);
            if (c == '"' && (i == 0 || lineText.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    if (depth == 0) {
                        return i;
                    }
                    depth--;
                }
            }
        }
        return -1;
    }

    private static int findEnclosingBraceOnLine(String lineText, int beforeIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = beforeIdx - 1; i >= 0; i--) {
            char c = lineText.charAt(i);
            if (c == '"' && (i == 0 || lineText.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '}') {
                    depth++;
                } else if (c == '{') {
                    if (depth == 0) {
                        return i;
                    }
                    depth--;
                }
            }
        }
        return -1;
    }

    private static int findMatchingBraceOnLine(String lineText, int openBraceIdx, int afterIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = afterIdx + 1; i < lineText.length(); i++) {
            char c = lineText.charAt(i);
            if (c == '"' && (i == 0 || lineText.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    if (depth == 0) {
                        return i;
                    }
                    depth--;
                }
            }
        }
        return -1;
    }

    private static List<TextRange> splitTopLevelBracketSlices(String body, int relTriple) {
        List<TextRange> slices = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inQuote = false;
        for (int i = 0; i < body.length(); i++) {
            if (i >= relTriple && i < relTriple + 3) {
                continue;
            }
            char c = body.charAt(i);
            if (c == '"' && (i == 0 || body.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '[') {
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        slices.add(new TextRange(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return depth == 0 ? slices : List.of();
    }

    private static String leadingSpacesOf(String lineText) {
        int idx = 0;
        while (idx < lineText.length() && lineText.charAt(idx) == ' ') {
            idx++;
        }
        return " ".repeat(idx);
    }

    private static String leadingSpacesOfOffset(CharSequence chars, int offset) {
        int lineStart = Math.max(0, Math.min(offset, chars.length()));
        while (lineStart > 0 && chars.charAt(lineStart - 1) != '\n' && chars.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int ws = lineStart;
        while (ws < chars.length() && chars.charAt(ws) == ' ') {
            ws++;
        }
        return chars.subSequence(lineStart, ws).toString();
    }

    private static int retreatSpacesInLine(String line, int idx) {
        int cur = idx;
        while (cur > 0 && (line.charAt(cur - 1) == ' ' || line.charAt(cur - 1) == '\t')) {
            cur--;
        }
        return cur;
    }

    private static int advanceSpacesInLine(String line, int idx) {
        int cur = idx;
        while (cur < line.length() && (line.charAt(cur) == ' ' || line.charAt(cur) == '\t')) {
            cur++;
        }
        return cur;
    }

    private static boolean isSimpleOrFencedStringElement(
            PsiElement element,
            @Nullable Editor editor,
            PsiFile file
    ) {
        if (isSimpleOrFencedNode(element)) {
            return true;
        }
        if (editor != null) {
            int offset = editor.getCaretModel().getOffset();
            if (offset > 0 && offset <= file.getTextLength()) {
                PsiElement prev = file.findElementAt(offset - 1);
                if (prev != null && prev.getTextRange().getEndOffset() == offset && isSimpleOrFencedNode(prev)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSimpleOrFencedNode(PsiElement element) {
        var node = element.getNode();
        if (node != null
                && (node.getElementType() == StvnTypes.LITERAL_STRING_SIMPLE
                    || node.getElementType() == StvnTypes.LITERAL_STRING_FENCED)) {
            return true;
        }
        StringLiteral sl = element instanceof StringLiteral s
            ? s
            : PsiTreeUtil.getParentOfType(element, StringLiteral.class, false);
        return sl != null
            && sl.getNode() != null
            && (sl.getNode().findChildByType(StvnTypes.LITERAL_STRING_SIMPLE) != null
                || sl.getNode().findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null);
    }
}
