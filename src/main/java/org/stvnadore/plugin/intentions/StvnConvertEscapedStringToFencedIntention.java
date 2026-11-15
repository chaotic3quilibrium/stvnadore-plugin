package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
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
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Intention action that converts an escaped simple string literal into a canonical Rule STR-04
 * fenced string literal when lossless round-trip soundness holds.
 */
@NullMarked
public final class StvnConvertEscapedStringToFencedIntention extends PsiElementBaseIntentionAction {

    private static final String INTENTION_TEXT = "Convert escaped string to fenced string";
    private static final String FAMILY_NAME = "STVN";

    private static final Set<String> ALLOWED_STRING_FACETS = Set.of(
        StvnVocabulary.FACET_KW_REGEX,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MIN_EXCL,
        StvnVocabulary.FACET_KW_MAX_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    /**
     * Constructs a new {@code StvnConvertEscapedStringToFencedIntention}.
     */
    public StvnConvertEscapedStringToFencedIntention() {
        setText(INTENTION_TEXT);
    }

    @Override
    public @NotNull String getFamilyName() {
        return FAMILY_NAME;
    }

    @Override
    public @NotNull String getText() {
        return INTENTION_TEXT;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return false;
        }
        return resolveConversionSpec(element) != null;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        if (editor == null) {
            return;
        }
        ConversionSpec spec = resolveConversionSpec(element);
        if (spec == null) {
            return;
        }

        Document document = editor.getDocument();
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        docManager.doPostponedOperationsAndUnblockDocument(document);

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

        docManager.commitDocument(document);
    }

    private record ConversionSpec(
        StringLiteral stringLiteral,
        String decoded,
        boolean preserveIndent,
        String tag
    ) {}

    private static @Nullable ConversionSpec resolveConversionSpec(PsiElement element) {
        StringLiteral strLit = element instanceof StringLiteral sl
            ? sl
            : PsiTreeUtil.getParentOfType(element, StringLiteral.class, false);
        if (strLit == null || strLit.getNode().findChildByType(StvnTypes.LITERAL_STRING_SIMPLE) == null) {
            return null;
        }

        if (PsiTreeUtil.getParentOfType(strLit, MetaEntry.class) != null
                || PsiTreeUtil.getParentOfType(strLit, IncludeStmt.class) != null) {
            return null;
        }

        Optional<String> decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), false);
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
            if (!ALLOWED_STRING_FACETS.contains(facetName)) {
                return null;
            }
            SchemaType enclosingSchema = PsiTreeUtil.getParentOfType(metaEntry, SchemaType.class);
            if (!StvnVocabulary.FACET_KW_REGEX.equals(facetName) && isDateTimeSchema(enclosingSchema)) {
                return null;
            }
            if (!StvnPsiLiteralEvaluator.shouldEmitFencedPretty(decoded, false)) {
                return null;
            }
            String baseTag = StvnVocabulary.FACET_KW_REGEX.equals(facetName)
                ? StvnVocabulary.FENCE_TAG_REGEX
                : StvnVocabulary.FENCE_TAG_RAW;
            String safeTag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(decoded, baseTag);
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
        if (constDef != null && !PsiTreeUtil.isAncestor(constDef.getValue(), enclosingValue, false)) {
            return null;
        }

        SchemaResolution schemaRes = resolveEffectiveStringSchema(enclosingValue, constDef);
        if (schemaRes.isDateTime()) {
            return null;
        }
        boolean preserveIndent = schemaRes.preserveIndent();
        if (!StvnPsiLiteralEvaluator.shouldEmitFencedPretty(decoded, preserveIndent)) {
            return null;
        }

        String baseTag = (constDef != null
                && constDef.getValueKeyword() != null
                && StvnPsiLiteralEvaluator.isRegexConstantIdentifier(constDef.getValueKeyword().getText().trim()))
            ? StvnVocabulary.FENCE_TAG_REGEX
            : StvnVocabulary.FENCE_TAG_RAW;
        String safeTag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(decoded, baseTag);
        return new ConversionSpec(strLit, decoded, preserveIndent, safeTag);
    }

    private record SchemaResolution(boolean isDateTime, boolean preserveIndent) {}

    private static SchemaResolution resolveEffectiveStringSchema(
            Value enclosingValue,
            @Nullable ConstantDefinition constDef
    ) {
        if (constDef != null && constDef.getValue() == enclosingValue && constDef.getSchemaType() != null) {
            SchemaType declaredSchema = constDef.getSchemaType();
            StvnSchema irSchema = StvnTypeResolver.resolveIrSchema(declaredSchema);
            if (irSchema instanceof StvnSchema.DateTimeSchema) {
                return new SchemaResolution(true, false);
            }
            if (irSchema instanceof StvnSchema.StringSchema ss) {
                return new SchemaResolution(false, ss.preserveIndent());
            }
            if (isDateTimeSchema(declaredSchema)) {
                return new SchemaResolution(true, false);
            }
            return new SchemaResolution(false, StvnPsiLiteralEvaluator.extractPreserveIndentFromSchema(declaredSchema));
        }

        var baseInfo = StvnTypeResolver.resolveBaseTypeInfo(enclosingValue);
        if (baseInfo != null) {
            SchemaType slotSchema = baseInfo.getSchema();
            if (baseInfo.getLabel().endsWith(StvnVocabulary.TYPE_DATE_TIME) || isDateTimeSchema(slotSchema)) {
                return new SchemaResolution(true, false);
            }
            StvnSchema irSchema = StvnTypeResolver.resolveIrSchema(slotSchema);
            if (irSchema instanceof StvnSchema.DateTimeSchema) {
                return new SchemaResolution(true, false);
            }
            if (irSchema instanceof StvnSchema.StringSchema ss) {
                return new SchemaResolution(false, ss.preserveIndent());
            }
            return new SchemaResolution(false, StvnPsiLiteralEvaluator.extractPreserveIndentFromSchema(slotSchema));
        }
        return new SchemaResolution(false, false);
    }

    private static boolean isDateTimeSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return false;
        }
        StvnSchema ir = StvnTypeResolver.resolveIrSchema(schemaType);
        if (ir instanceof StvnSchema.DateTimeSchema) {
            return true;
        }
        SchemaType unwrapped = StvnTypeResolver.resolveNominalSchema(schemaType);
        SchemaType toCheck = unwrapped != null ? unwrapped : schemaType;
        return toCheck.getText().endsWith(StvnVocabulary.TYPE_DATE_TIME);
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
        String openFence = "\"\"\"[" + tag + "]\n";
        String closeFence = "[" + tag + "]\"\"\"";
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
