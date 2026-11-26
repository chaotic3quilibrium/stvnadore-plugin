package org.stvnadore.plugin.intentions;

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
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Intention action that converts an escaped simple string literal into a canonical Rule {@code STR-04}
 * fenced string literal when lossless round-trip soundness holds.
 */
@NullMarked
public final class StvnConvertEscapedStringToFencedIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    private static final Set<String> ALLOWED_STRING_FACETS = Set.of(
        StvnVocabulary.FACET_KW_REGEX,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MIN_EXCL,
        StvnVocabulary.FACET_KW_MAX_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private enum EscapeFilter {
        ESCAPED_ONLY,
        UNESCAPED_ONLY
    }

    /**
     * Constructs a new {@code StvnConvertEscapedStringToFencedIntention}.
     */
    public StvnConvertEscapedStringToFencedIntention() {
        setText(StvnBundle.message("stvn.intention.convert.escaped.to.fenced.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.convert.escaped.to.fenced.text");
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.convert.escaped.to.fenced.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return false;
        }
        return resolveConversionSpec(element, editor, EscapeFilter.ESCAPED_ONLY) != null;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        if (editor == null) {
            return;
        }
        ConversionSpec spec = resolveConversionSpec(element, editor, EscapeFilter.ESCAPED_ONLY);
        if (spec == null) {
            return;
        }
        applyConversionSpec(project, editor, spec);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        PsiElement element = findSimpleStringElementAtOrBeforeCaret(file, editor.getCaretModel().getOffset());
        if (element == null) {
            element = getElement(editor, file);
        }
        if (element == null || !isAvailable(project, editor, element)) {
            return IntentionPreviewInfo.EMPTY;
        }
        invoke(project, editor, element);
        return IntentionPreviewInfo.DIFF;
    }

    /**
     * Checks whether {@code element} (or the simple string ending at the editor caret) is an
     * escape-free double-quoted STVN string literal that converts losslessly into a Rule {@code STR-04}
     * fenced string block.
     *
     * @param element PSI element under the editor caret
     * @param editor  optional active editor for trailing-quote boundary resolution
     * @return {@code true} when lossless unescaped simple-to-fenced conversion is available
     */
    public static boolean canConvertUnescapedSimpleStringToFenced(PsiElement element, @Nullable Editor editor) {
        if (!element.isValid()) {
            return false;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return false;
        }
        return resolveConversionSpec(element, editor, EscapeFilter.UNESCAPED_ONLY) != null;
    }

    /**
     * Converts the escape-free simple double-quoted STVN string literal enclosing {@code element}
     * into a canonical Rule {@code STR-04} fenced string block when lossless round-trip soundness holds.
     *
     * @param project active IntelliJ project
     * @param editor  active text editor
     * @param element PSI element under the editor caret
     * @return {@code true} if an escape-free simple string literal was converted, or {@code false} otherwise
     */
    public static boolean invokeUnescapedSimpleStringToFenced(Project project, Editor editor, PsiElement element) {
        ConversionSpec spec = resolveConversionSpec(element, editor, EscapeFilter.UNESCAPED_ONLY);
        if (spec == null) {
            return false;
        }
        applyConversionSpec(project, editor, spec);
        return true;
    }

    /**
     * Finds a {@code LITERAL_STRING_SIMPLE} leaf token at {@code caretOffset} or ending immediately at
     * {@code caretOffset}.
     *
     * @param file        enclosing STVN PSI file
     * @param caretOffset 0-based editor caret offset
     * @return matching simple string leaf token, or {@code null}
     */
    public static @Nullable PsiElement findSimpleStringElementAtOrBeforeCaret(PsiFile file, int caretOffset) {
        int len = file.getTextLength();
        if (caretOffset >= 0 && caretOffset < len) {
            PsiElement at = file.findElementAt(caretOffset);
            if (at != null && at.getNode() != null && at.getNode().getElementType() == StvnTypes.LITERAL_STRING_SIMPLE) {
                return at;
            }
        }
        if (caretOffset > 0 && caretOffset <= len) {
            PsiElement prev = file.findElementAt(caretOffset - 1);
            if (prev != null
                    && prev.getNode() != null
                    && prev.getNode().getElementType() == StvnTypes.LITERAL_STRING_SIMPLE
                    && prev.getTextRange().getEndOffset() == caretOffset) {
                return prev;
            }
        }
        return null;
    }

    private static void applyConversionSpec(Project project, Editor editor, ConversionSpec spec) {
        PsiFile file = spec.stringLiteral().getContainingFile();
        Document document = file != null ? file.getViewProvider().getDocument() : null;
        if (document == null) {
            document = editor.getDocument();
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file != null
            && file.isPhysical()
            && !IntentionPreviewUtils.isPreviewElement(spec.stringLiteral());
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(document);
        }

        MetadataEntry metaEntry = PsiTreeUtil.getParentOfType(spec.stringLiteral(), MetadataEntry.class);
        if (metaEntry != null) {
            MetadataMap metaMap = PsiTreeUtil.getParentOfType(metaEntry, MetadataMap.class);
            if (metaMap != null && !metaMap.textContains('\n')) {
                replaceSingleLineMetadataMap(document, metaMap, metaEntry, spec);
            } else {
                int ownerIndent = computeLineLeadingSpaces(document, metaEntry.getTextRange().getStartOffset());
                String fenced = renderFencedLiteral(spec.decoded(), spec.tag(), spec.preserveIndent(), ownerIndent);
                TextRange litRange = spec.stringLiteral().getTextRange();
                document.replaceString(litRange.getStartOffset(), litRange.getEndOffset(), fenced);
            }
        } else {
            replacePayloadOrConstantString(document, spec);
        }

        if (commitPhysical) {
            docManager.commitDocument(document);
        }
    }

    private record ConversionSpec(
        StringLiteral stringLiteral,
        String decoded,
        boolean preserveIndent,
        String tag
    ) {}

    private static @Nullable ConversionSpec resolveConversionSpec(
            PsiElement element,
            @Nullable Editor editor,
            EscapeFilter escapeFilter
    ) {
        StringLiteral strLit = resolveSimpleStringLiteral(element, editor);
        if (strLit == null || StvnMetaTemplateUtil.isInSecondaryRootSection(strLit)) {
            return null;
        }
        if (PsiTreeUtil.getParentOfType(strLit, MetaEntry.class) != null
                || PsiTreeUtil.getParentOfType(strLit, IncludeStmt.class) != null) {
            return null;
        }

        String rawText = strLit.getText();
        boolean hasEscapeSequence = rawText.indexOf('\\') >= 0;
        if (escapeFilter == EscapeFilter.ESCAPED_ONLY && !hasEscapeSequence) {
            return null;
        }
        if (escapeFilter == EscapeFilter.UNESCAPED_ONLY && hasEscapeSequence) {
            return null;
        }

        Optional<String> decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(rawText, false);
        if (decodedOpt.isEmpty()) {
            return null;
        }
        String decoded = decodedOpt.get();

        // Case 1: MetadataEntry string facet (#regex, #minIncl, #minExcl, #maxIncl, #maxExcl)
        if (strLit.getParent() instanceof MetadataValue metaVal
                && metaVal.getParent() instanceof MetadataEntry metaEntry) {
            PsiElement facetToken = metaEntry.getFirstChild();
            if (facetToken == null) {
                return null;
            }
            String facetName = facetToken.getText().trim();
            if (!ALLOWED_STRING_FACETS.contains(facetName)
                    || StvnPsiLiteralEvaluator.isDateTimeGovernedStringLiteral(strLit)) {
                return null;
            }
            String baseTag = StvnVocabulary.FACET_KW_REGEX.equals(facetName)
                ? StvnVocabulary.FENCE_TAG_REGEX
                : StvnVocabulary.FENCE_TAG_RAW;
            String safeTag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(decoded, baseTag);
            if (!isRoundTripLosslessFencedString(decoded, safeTag, false)) {
                return null;
            }
            return new ConversionSpec(strLit, decoded, false, safeTag);
        }

        // Case 2: ConstantDefinition RHS Value or :body Value
        Value enclosingValue = PsiTreeUtil.getParentOfType(strLit, Value.class, false);
        if (enclosingValue == null) {
            return null;
        }
        ConstantDefinition constDef = PsiTreeUtil.getParentOfType(enclosingValue, ConstantDefinition.class);
        boolean inBody = StvnPsiUtils.isInsideBody(enclosingValue);
        if (constDef == null && !inBody) {
            return null;
        }
        if (constDef != null && (constDef.getValue() == null || !PsiTreeUtil.isAncestor(constDef.getValue(), enclosingValue, false))) {
            return null;
        }
        if (StvnPsiLiteralEvaluator.isDateTimeGovernedStringLiteral(strLit)) {
            return null;
        }

        boolean preserveIndent = StvnPsiLiteralEvaluator.resolvePreserveIndentForStringLiteral(strLit);
        String baseTag = (constDef != null
                && constDef.getValueKeyword() != null
                && StvnPsiLiteralEvaluator.isRegexConstantIdentifier(constDef.getValueKeyword().getText().trim()))
            ? StvnVocabulary.FENCE_TAG_REGEX
            : StvnVocabulary.FENCE_TAG_RAW;
        String safeTag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(decoded, baseTag);
        if (!isRoundTripLosslessFencedString(decoded, safeTag, preserveIndent)) {
            return null;
        }
        return new ConversionSpec(strLit, decoded, preserveIndent, safeTag);
    }

    private static @Nullable StringLiteral resolveSimpleStringLiteral(PsiElement element, @Nullable Editor editor) {
        StringLiteral direct = extractSimpleStringLiteral(element);
        if (direct != null) {
            return direct;
        }
        PsiFile file = element.getContainingFile();
        if (editor != null && file != null) {
            PsiElement atOrBefore = findSimpleStringElementAtOrBeforeCaret(file, editor.getCaretModel().getOffset());
            return atOrBefore != null ? extractSimpleStringLiteral(atOrBefore) : null;
        }
        return null;
    }

    private static @Nullable StringLiteral extractSimpleStringLiteral(PsiElement candidate) {
        StringLiteral strLit = candidate instanceof StringLiteral sl
            ? sl
            : PsiTreeUtil.getParentOfType(candidate, StringLiteral.class, false);
        return (strLit != null
                && strLit.getNode() != null
                && strLit.getNode().findChildByType(StvnTypes.LITERAL_STRING_SIMPLE) != null)
            ? strLit
            : null;
    }

    private static boolean isRoundTripLosslessFencedString(String decoded, String safeTag, boolean preserveIndent) {
        if (!StvnStringCharacterPolicy.canRepresentVerbatimInFencedString(decoded, preserveIndent)) {
            return false;
        }
        String sampleRendered = renderFencedLiteral(decoded, safeTag, preserveIndent, 2);
        return StvnPsiLiteralEvaluator.decodeStringLiteral(sampleRendered, preserveIndent)
            .filter(decoded::equals)
            .isPresent();
    }

    private static void replaceSingleLineMetadataMap(
            Document document,
            MetadataMap metaMap,
            MetadataEntry targetEntry,
            ConversionSpec spec
    ) {
        int mapDeclIndent = computeLineLeadingSpaces(document, metaMap.getTextRange().getStartOffset());
        int entryIndent = mapDeclIndent + 2;
        String entryPrefix = " ".repeat(entryIndent);
        StringBuilder sb = new StringBuilder();
        sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            sb.append('\n').append(entryPrefix);
            if (entry == targetEntry) {
                PsiElement facetKw = entry.getFirstChild();
                String facetText = facetKw != null ? facetKw.getText().trim() : "";
                sb.append(facetText).append(' ');
                sb.append(renderFencedLiteral(spec.decoded(), spec.tag(), false, entryIndent));
            } else {
                sb.append(entry.getText().trim());
            }
        }
        sb.append('\n').append(" ".repeat(mapDeclIndent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
        TextRange mapRange = metaMap.getTextRange();
        document.replaceString(mapRange.getStartOffset(), mapRange.getEndOffset(), sb.toString());
    }

    private static void replacePayloadOrConstantString(Document document, ConversionSpec spec) {
        StringLiteral strLit = spec.stringLiteral();
        PsiElement outermostSingleLineContainer = findOutermostSingleLinePayloadContainer(strLit);
        if (outermostSingleLineContainer != null) {
            int baseIndent = computeLineLeadingSpaces(document, outermostSingleLineContainer.getTextRange().getStartOffset());
            String expanded = expandPayloadContainer(outermostSingleLineContainer, strLit, spec, baseIndent);
            TextRange seqRange = outermostSingleLineContainer.getTextRange();
            document.replaceString(seqRange.getStartOffset(), seqRange.getEndOffset(), expanded);
            return;
        }
        int ownerIndent = computeLineLeadingSpaces(document, strLit.getTextRange().getStartOffset());
        String fenced = renderFencedLiteral(spec.decoded(), spec.tag(), spec.preserveIndent(), ownerIndent);
        TextRange litRange = strLit.getTextRange();
        document.replaceString(litRange.getStartOffset(), litRange.getEndOffset(), fenced);
    }

    private static @Nullable PsiElement findOutermostSingleLinePayloadContainer(PsiElement start) {
        PsiElement outermost = null;
        PsiElement cur = start.getParent();
        while (cur != null) {
            if ((cur instanceof TupleLiteral || cur instanceof ListLiteral || cur instanceof MapLiteral)
                    && !cur.textContains('\n')) {
                outermost = cur;
            }
            cur = cur.getParent();
        }
        return outermost;
    }

    private static String expandPayloadContainer(
            PsiElement container,
            StringLiteral targetLiteral,
            ConversionSpec spec,
            int containerIndent
    ) {
        List<Value> elements = new ArrayList<>(PsiTreeUtil.getChildrenOfTypeAsList(container, Value.class));
        int childIndent = containerIndent + 2;
        String childPrefix = " ".repeat(childIndent);

        if (container instanceof MapLiteral && elements.size() % 2 == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
            for (int i = 0; i < elements.size(); i += 2) {
                Value keyVal = elements.get(i);
                Value entryVal = elements.get(i + 1);
                sb.append('\n').append(childPrefix).append(StvnVocabulary.DELIM_OPEN_BRACKET).append(' ');
                sb.append(PsiTreeUtil.isAncestor(keyVal, targetLiteral, false)
                    ? renderExpandedValue(keyVal, targetLiteral, spec, childIndent)
                    : keyVal.getText().trim());
                sb.append(' ');
                sb.append(PsiTreeUtil.isAncestor(entryVal, targetLiteral, false)
                    ? renderExpandedValue(entryVal, targetLiteral, spec, childIndent)
                    : entryVal.getText().trim());
                sb.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            }
            sb.append('\n').append(" ".repeat(containerIndent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return sb.toString();
        }

        boolean isTuple = container instanceof TupleLiteral;
        String openDelim = isTuple ? StvnVocabulary.DELIM_OPEN_PAREN : StvnVocabulary.DELIM_OPEN_BRACKET;
        String closeDelim = isTuple ? StvnVocabulary.DELIM_CLOSE_PAREN : StvnVocabulary.DELIM_CLOSE_BRACKET;
        StringBuilder sb = new StringBuilder();
        sb.append(openDelim);
        for (Value val : elements) {
            sb.append('\n').append(childPrefix);
            if (PsiTreeUtil.isAncestor(val, targetLiteral, false)) {
                sb.append(renderExpandedValue(val, targetLiteral, spec, childIndent));
            } else {
                sb.append(val.getText().trim());
            }
        }
        sb.append('\n').append(" ".repeat(containerIndent)).append(closeDelim);
        return sb.toString();
    }

    private static String renderExpandedValue(
            Value val,
            StringLiteral targetLiteral,
            ConversionSpec spec,
            int valueIndent
    ) {
        if (val.getStringLiteral() == targetLiteral) {
            return renderFencedLiteral(spec.decoded(), spec.tag(), spec.preserveIndent(), valueIndent);
        }
        if (val.getCollectionValue() != null) {
            TupleLiteral nestedTuple = val.getCollectionValue().getTupleLiteral();
            if (nestedTuple != null && !nestedTuple.textContains('\n')) {
                return expandPayloadContainer(nestedTuple, targetLiteral, spec, valueIndent);
            }
            ListLiteral nestedList = val.getCollectionValue().getListLiteral();
            if (nestedList != null && !nestedList.textContains('\n')) {
                return expandPayloadContainer(nestedList, targetLiteral, spec, valueIndent);
            }
            MapLiteral nestedMap = val.getCollectionValue().getMapLiteral();
            if (nestedMap != null && !nestedMap.textContains('\n')) {
                return expandPayloadContainer(nestedMap, targetLiteral, spec, valueIndent);
            }
        }
        String rawVal = val.getText();
        int relStart = targetLiteral.getTextRange().getStartOffset() - val.getTextRange().getStartOffset();
        int relEnd = targetLiteral.getTextRange().getEndOffset() - val.getTextRange().getStartOffset();
        String fenced = renderFencedLiteral(spec.decoded(), spec.tag(), spec.preserveIndent(), valueIndent);
        return rawVal.substring(0, relStart) + fenced + rawVal.substring(relEnd);
    }

    /**
     * Formats {@code decoded} as a canonical Rule {@code STR-04} fenced string at {@code ownerIndentSpaces}.
     *
     * @param decoded           decoded UTF-16 string body
     * @param tag               collision-safe fence tag
     * @param preserveIndent    effective {@code #preserveIndent} flag
     * @param ownerIndentSpaces leading ASCII space count of the owning declaration or element line
     * @return formatted fenced string literal text
     */
    public static String renderFencedLiteral(
            String decoded,
            String tag,
            boolean preserveIndent,
            int ownerIndentSpaces
    ) {
        String openFence = "\"\"\"" + StvnVocabulary.DELIM_OPEN_BRACKET + tag + StvnVocabulary.DELIM_CLOSE_BRACKET + "\n";
        String closeFence = StvnVocabulary.DELIM_OPEN_BRACKET + tag + StvnVocabulary.DELIM_CLOSE_BRACKET + "\"\"\"";
        if (preserveIndent) {
            return openFence + decoded + closeFence;
        }

        String bodyIndent = " ".repeat(Math.max(0, ownerIndentSpaces + 2));
        String[] lines = decoded.split("\n", -1);
        StringBuilder sb = new StringBuilder(openFence.length() + decoded.length() + closeFence.length() + 16);
        sb.append(openFence);

        boolean trailingNewline = decoded.endsWith("\n");
        int contentLineCount = trailingNewline ? lines.length - 1 : lines.length;
        for (int i = 0; i < contentLineCount; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (!line.isEmpty()) {
                sb.append(bodyIndent).append(line);
            }
        }
        if (trailingNewline) {
            sb.append('\n').append(" ".repeat(Math.max(0, ownerIndentSpaces))).append(closeFence);
        } else {
            sb.append(closeFence);
        }
        return sb.toString();
    }

    private static int computeLineLeadingSpaces(Document document, int offset) {
        int lineNum = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(lineNum);
        int lineEnd = document.getLineEndOffset(lineNum);
        CharSequence seq = document.getCharsSequence();
        int spaces = 0;
        while (lineStart + spaces < lineEnd && seq.charAt(lineStart + spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }
}
