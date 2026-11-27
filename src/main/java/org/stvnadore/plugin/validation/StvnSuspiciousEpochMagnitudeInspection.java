package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.documentation.StvnTimeEpochFormatter;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IntegerLiteral;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.Visitor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates {@code :TimeEpoch} tick magnitudes and civil-window bounds across {@code :defs}
 * {@link ConstantDefinition} declarations and {@code :body} literals or {@code #CONST} references.
 * <p>
 * Shares {@link StvnTimeEpochFormatter#format} with {@code StvnDocumentationProvider} to guarantee
 * 100% 4-locus parity between editor warning squigglies and hover warning banners.
 */
@NullMarked
public final class StvnSuspiciousEpochMagnitudeInspection extends LocalInspectionTool {

    private static final int MAX_CONST_HOP_DEPTH = 16;

    /**
     * Constructs a new {@link StvnSuspiciousEpochMagnitudeInspection}.
     */
    public StvnSuspiciousEpochMagnitudeInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnSuspiciousEpochMagnitude";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Suspicious epoch magnitude inspection";
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
        if (!StvnSettings.getInstance(holder.getProject()).getState().showEpochMagnitudeWarnings) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new Visitor() {
            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)) {
                    return;
                }
                ValueKeyword lhsKw = constDef.getValueKeyword();
                SchemaType schemaType = constDef.getSchemaType();
                if (lhsKw == null || schemaType == null) {
                    return;
                }
                String scaleFlag = resolveTimeEpochScaleFlag(schemaType);
                if (scaleFlag == null) {
                    return;
                }
                IntegerLiteral terminalLit = resolveTerminalIntegerLiteral(constDef);
                if (terminalLit == null) {
                    return;
                }
                String rawTicks = terminalLit.getText().trim();
                var fmtResult = StvnTimeEpochFormatter.format(
                    rawTicks,
                    scaleFlag,
                    StvnSettings.TimeEpochHourFormat.HOUR_24
                );
                if (!fmtResult.isMagnitudeMismatch() && !fmtResult.isCivilWindowViolation()) {
                    return;
                }
                String msg = buildSuspiciousEpochMessage(lhsKw.getText().trim(), rawTicks, scaleFlag, fmtResult);
                String formatted = StvnDiagnosticFormatter.formatPlugin(
                    msg,
                    StvnDiagnosticCodes.WARN_SUSPICIOUS_EPOCH_MAGNITUDE
                );
                Value directRhs = constDef.getValue();
                IntegerLiteral directIntLit = directRhs != null ? directRhs.getIntegerLiteral() : null;
                boolean outOfSchemaBounds = directIntLit != null
                    && isLiteralOutOfTimeEpochBounds(schemaType, directIntLit, rawTicks);
                List<LocalQuickFix> fixes = buildQuickFixes(
                    schemaType,
                    directIntLit,
                    rawTicks,
                    scaleFlag,
                    fmtResult.recommendedScale(),
                    outOfSchemaBounds
                );
                holder.registerProblem(
                    lhsKw,
                    formatted,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
                if (isOnTheFly && directIntLit != null && !fixes.isEmpty()) {
                    holder.registerProblem(
                        directIntLit,
                        formatted,
                        ProblemHighlightType.INFORMATION,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                }
            }

            @Override
            public void visitValueKeyword(@NotNull ValueKeyword valueKeyword) {
                super.visitValueKeyword(valueKeyword);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(valueKeyword)) {
                    return;
                }
                if (!(valueKeyword.getParent() instanceof Value value)
                        || PsiTreeUtil.getParentOfType(value, BodyEntry.class) == null) {
                    return;
                }
                var info = StvnTypeResolver.resolveBaseTypeInfo(value);
                if (info == null || info.getSchema() == null) {
                    return;
                }
                String scaleFlag = resolveTimeEpochScaleFlag(info.getSchema());
                if (scaleFlag == null) {
                    return;
                }
                ConstantDefinition targetDef = resolveReferencedConstant(valueKeyword);
                if (targetDef == null) {
                    return;
                }
                IntegerLiteral terminalLit = resolveTerminalIntegerLiteral(targetDef);
                if (terminalLit == null) {
                    return;
                }
                String rawTicks = terminalLit.getText().trim();
                var fmtResult = StvnTimeEpochFormatter.format(
                    rawTicks,
                    scaleFlag,
                    StvnSettings.TimeEpochHourFormat.HOUR_24
                );
                if (!fmtResult.isMagnitudeMismatch() && !fmtResult.isCivilWindowViolation()) {
                    return;
                }
                String msg = buildSuspiciousEpochMessage(valueKeyword.getText().trim(), rawTicks, scaleFlag, fmtResult);
                String formatted = StvnDiagnosticFormatter.formatPlugin(
                    msg,
                    StvnDiagnosticCodes.WARN_SUSPICIOUS_EPOCH_MAGNITUDE
                );
                List<LocalQuickFix> fixes = buildQuickFixes(
                    info.getSchema(),
                    null,
                    rawTicks,
                    scaleFlag,
                    fmtResult.recommendedScale(),
                    false
                );
                holder.registerProblem(
                    valueKeyword,
                    formatted,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }

            @Override
            public void visitIntegerLiteral(@NotNull IntegerLiteral literal) {
                super.visitIntegerLiteral(literal);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(literal)) {
                    return;
                }
                var bodyEntry = PsiTreeUtil.getParentOfType(literal, BodyEntry.class);
                if (bodyEntry == null) {
                    return;
                }
                var value = PsiTreeUtil.getParentOfType(literal, Value.class);
                if (value == null) {
                    return;
                }
                var info = StvnTypeResolver.resolveBaseTypeInfo(value);
                if (info == null || info.getSchema() == null) {
                    return;
                }
                String scaleFlag = resolveTimeEpochScaleFlag(info.getSchema());
                if (scaleFlag == null) {
                    return;
                }
                var text = literal.getText().trim();
                var fmtResult = StvnTimeEpochFormatter.format(
                    text,
                    scaleFlag,
                    StvnSettings.TimeEpochHourFormat.HOUR_24
                );
                if (!fmtResult.isMagnitudeMismatch() && !fmtResult.isCivilWindowViolation()) {
                    return;
                }
                String msg = buildSuspiciousEpochMessage(null, text, scaleFlag, fmtResult);
                var formatted = StvnDiagnosticFormatter.formatPlugin(
                    msg,
                    StvnDiagnosticCodes.WARN_SUSPICIOUS_EPOCH_MAGNITUDE
                );
                boolean outOfSchemaBounds = isLiteralOutOfTimeEpochBounds(info.getSchema(), literal, text);
                List<LocalQuickFix> quickFixes = buildQuickFixes(
                    info.getSchema(),
                    literal,
                    text,
                    scaleFlag,
                    fmtResult.recommendedScale(),
                    outOfSchemaBounds
                );
                holder.registerProblem(
                    literal,
                    formatted,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    quickFixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }
        };
    }

    private static String buildSuspiciousEpochMessage(
            @Nullable String constLabel,
            String rawLiteral,
            String scaleFlag,
            StvnTimeEpochFormatter.EpochFormatResult fmtResult
    ) {
        String subject = constLabel != null
            ? "constant '" + constLabel + "' ('" + rawLiteral + "')"
            : "literal '" + rawLiteral + "'";
        if (fmtResult.isMagnitudeMismatch()) {
            String detectedBand = fmtResult.detectedBandDescription();
            if (fmtResult.recommendedScale() != null && detectedBand != null) {
                int paren = detectedBand.indexOf(" (suggesting ");
                String countPart = paren > 0 ? detectedBand.substring(0, paren) : detectedBand;
                return "Suspicious epoch magnitude: " + subject + " has " + countPart
                    + " (suggesting '" + fmtResult.recommendedScale() + "'), but declared scale is '" + scaleFlag + "'.";
            }
            String bandText = detectedBand != null ? detectedBand : "unexpected digit length";
            return "Suspicious epoch magnitude: " + subject + " has " + bandText
                + ", but declared scale is '" + scaleFlag + "' (expected " + fmtResult.expectedBandDescription() + ").";
        }
        if (fmtResult.year() != null) {
            return "Suspicious epoch magnitude: " + subject + " resolves to year " + fmtResult.year()
                + " outside civil window [1900, 2100] for scale '" + scaleFlag + "'.";
        }
        return "Suspicious epoch magnitude: " + subject + " exceeds civil window [1900, 2100] for scale '" + scaleFlag + "'.";
    }

    private static List<LocalQuickFix> buildQuickFixes(
            SchemaType schemaType,
            @Nullable IntegerLiteral targetLiteral,
            String rawText,
            String declaredScale,
            @Nullable String detectedScale,
            boolean suppressRescaleForBounds
    ) {
        if (detectedScale == null || detectedScale.equals(declaredScale)) {
            return List.of();
        }
        int declaredOrder = scaleToOrder(declaredScale);
        int detectedOrder = scaleToOrder(detectedScale);
        if (Math.abs(detectedOrder - declaredOrder) < 3) {
            return List.of();
        }
        List<LocalQuickFix> fixes = new ArrayList<>(2);
        if (targetLiteral != null && !suppressRescaleForBounds) {
            LocalQuickFix rescale = createRescaleFix(targetLiteral, rawText, declaredOrder, detectedOrder, declaredScale);
            if (rescale != null) {
                fixes.add(rescale);
            }
        }
        LocalQuickFix updateSchema = createUpdateSchemaFix(schemaType, detectedScale, new HashSet<>());
        if (updateSchema != null) {
            fixes.add(updateSchema);
        }
        return fixes;
    }

    private static @Nullable ConstantDefinition resolveReferencedConstant(ValueKeyword valueKeyword) {
        var ref = valueKeyword.getReference();
        var resolved = ref != null ? ref.resolve() : new StvnConstantReference(valueKeyword).resolve();
        ConstantDefinition cd = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
        if (cd != null) {
            return cd;
        }
        return StvnPsiLiteralEvaluator.resolveConstantDefinition(valueKeyword, valueKeyword.getText().trim());
    }

    private static @Nullable IntegerLiteral resolveTerminalIntegerLiteral(ConstantDefinition startDef) {
        Set<String> visited = new HashSet<>();
        ConstantDefinition current = startDef;
        int depth = 0;
        while (current != null && depth < MAX_CONST_HOP_DEPTH) {
            depth++;
            ValueKeyword lhs = current.getValueKeyword();
            if (lhs != null) {
                String name = lhs.getText().trim();
                if (!name.isEmpty() && !visited.add(name)) {
                    return null;
                }
            }
            Value val = current.getValue();
            if (val == null) {
                return null;
            }
            if (val.getIntegerLiteral() != null) {
                return val.getIntegerLiteral();
            }
            ValueKeyword nextKw = val.getValueKeyword();
            if (nextKw == null) {
                return null;
            }
            current = resolveReferencedConstant(nextKw);
        }
        return null;
    }

    private static @Nullable String resolveTimeEpochScaleFlag(SchemaType schemaType) {
        StvnSchema irSchema = StvnTypeResolver.resolveIrSchema(schemaType);
        if (irSchema == null && schemaType.getTypeKeyword() != null) {
            var kw = schemaType.getTypeKeyword();
            String alias = kw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(alias)) {
                irSchema = StvnTypeResolver.resolveIrSchemaAtContext(kw, alias);
            }
        }
        if (irSchema instanceof StvnSchema.PoisonedSchema) {
            return null;
        }
        if (irSchema instanceof StvnSchema.TimeEpochSchema tes) {
            return mapTemporalScaleToFacet(tes.scale());
        }
        if (irSchema != null) {
            return null;
        }
        if (schemaType.getTypeKeyword() != null) {
            var kw = schemaType.getTypeKeyword();
            String alias = kw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(alias)
                    && StvnTypeResolver.isDegradedNominalAlias(kw, alias)) {
                return null;
            }
        }
        String baseType = resolveBaseTypeString(schemaType, new HashSet<>());
        if (!StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) {
            return null;
        }
        return extractScaleFlag(schemaType, new HashSet<>());
    }

    private static String mapTemporalScaleToFacet(TemporalScale scale) {
        return switch (scale) {
            case S -> StvnVocabulary.FACET_KW_SCALE_S;
            case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
            case US -> StvnVocabulary.FACET_KW_SCALE_US;
            case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
        };
    }

    private static boolean isLiteralOutOfTimeEpochBounds(
            SchemaType schemaType,
            PsiElement context,
            String rawText
    ) {
        BigInteger actual = StvnPsiLiteralEvaluator.parseBigInteger(rawText).orElse(null);
        if (actual == null) {
            return false;
        }
        Map<String, String> meta = StvnExemplarValueGenerator.extractLineageMetadata(schemaType);
        TemporalScale scale = StvnExemplarValueGenerator.resolveEpochScaleFromMeta(meta);
        boolean unsigned = meta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
        int sizeBits = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                context,
                meta.get(StvnVocabulary.FACET_KW_SIZE)
            )
            .map(BigInteger::intValue)
            .orElse(scale.bitWidth().intValue());
        BigInteger minIncl = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                context,
                meta.get(StvnVocabulary.FACET_KW_MIN_INCL)
            )
            .orElse(null);
        BigInteger maxExcl = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                context,
                meta.get(StvnVocabulary.FACET_KW_MAX_EXCL)
            )
            .orElse(null);
        BigInteger[] bounds = StvnExemplarValueGenerator.computeEffectiveIntegerBounds(
            unsigned,
            sizeBits,
            minIncl,
            maxExcl
        );
        return actual.compareTo(bounds[0]) < 0 || actual.compareTo(bounds[1]) > 0;
    }

    /**
     * Converts a canonical temporal scale facet flag to its decimal exponent order relative to seconds.
     *
     * @param scale scale facet flag
     * @return decimal order (0, 3, 6, or 9)
     */
    public static int scaleToOrder(String scale) {
        return switch (scale) {
            case StvnVocabulary.FACET_KW_SCALE_NS -> 9;
            case StvnVocabulary.FACET_KW_SCALE_US -> 6;
            case StvnVocabulary.FACET_KW_SCALE_MS -> 3;
            default -> 0;
        };
    }

    private static @Nullable LocalQuickFix createRescaleFix(
            IntegerLiteral targetLiteral,
            String rawText,
            int declaredOrder,
            int detectedOrder,
            String targetScale
    ) {
        BigInteger original = StvnPsiLiteralEvaluator.parseBigInteger(rawText).orElse(null);
        if (original == null) {
            return null;
        }
        int diff = detectedOrder - declaredOrder;
        BigInteger factor = BigInteger.TEN.pow(Math.abs(diff));
        BigInteger rescaled = diff > 0 ? original.divide(factor) : original.multiply(factor);
        String label = "Rescale literal to '" + rescaled + "' (match declared '" + targetScale + "' scale)";

        return new RescaleEpochLiteralQuickFix(targetLiteral, rescaled.toString(), label);
    }

    private static final class RescaleEpochLiteralQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        @SafeFieldForPreview
        private final String rescaledText;
        @SafeFieldForPreview
        private final String label;

        RescaleEpochLiteralQuickFix(IntegerLiteral targetLiteral, String rescaledText, String label) {
            super(targetLiteral);
            this.rescaledText = rescaledText;
            this.label = label;
        }

        @Override
        public @Nls @NotNull String getText() {
            return label;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Rescale TimeEpoch literal";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            IntegerLiteral intLit = startElement instanceof IntegerLiteral il
                ? il
                : PsiTreeUtil.getParentOfType(startElement, IntegerLiteral.class, false);
            if (intLit == null || !intLit.isValid()) {
                return;
            }
            Document doc = file.getViewProvider().getDocument();
            if (doc == null) {
                doc = PsiDocumentManager.getInstance(project).getDocument(file);
            }
            if (doc == null) {
                return;
            }
            TextRange range = intLit.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), rescaledText);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }

    private static @Nullable LocalQuickFix createUpdateSchemaFix(
            SchemaType schemaType,
            String detectedScale,
            Set<PsiElement> visited
    ) {
        if (!visited.add(schemaType)) {
            return null;
        }
        PsiElement targetScaleElem = findScaleFacetEntry(schemaType.getMetadataMap());
        if (targetScaleElem == null
                && schemaType.getParent() instanceof TypeDefinition parentTd
                && parentTd.getSchemaType() == schemaType) {
            targetScaleElem = findScaleFacetEntry(parentTd.getMetadataMap());
        }
        if (targetScaleElem != null) {
            PsiFile targetFile = targetScaleElem.getContainingFile();
            if (targetFile == null
                    || !targetFile.isWritable()
                    || !targetScaleElem.isWritable()
                    || StvnPreludeBridge.isPreludeFile(targetFile)) {
                return null;
            }
            return new UpdateSchemaScaleQuickFix(targetScaleElem, detectedScale);
        }

        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            TypeDefinition td = resolveTargetTypeDef(schemaType, kw.getText().trim());
            if (td != null
                    && td.isWritable()
                    && td.getContainingFile() != null
                    && td.getContainingFile().isWritable()
                    && !StvnPreludeBridge.isPreludeFile(td.getContainingFile())) {
                PsiElement tdScale = findScaleFacetEntry(td.getMetadataMap());
                if (tdScale != null) {
                    return new UpdateSchemaScaleQuickFix(tdScale, detectedScale);
                }
                if (td.getSchemaType() != null) {
                    return createUpdateSchemaFix(td.getSchemaType(), detectedScale, visited);
                }
            }
        }
        return null;
    }

    private static final class UpdateSchemaScaleQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement {

        @SafeFieldForPreview
        private final String detectedScale;

        UpdateSchemaScaleQuickFix(PsiElement scaleNode, String detectedScale) {
            super(scaleNode);
            this.detectedScale = detectedScale;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Update schema scale flag to '" + detectedScale + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Update TimeEpoch scale flag";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            if (!startElement.isValid()) {
                return;
            }
            PsiFile targetFile = startElement.getContainingFile();
            if (targetFile == null || StvnPreludeBridge.isPreludeFile(targetFile)) {
                return;
            }
            Document doc = targetFile.getViewProvider().getDocument();
            if (doc == null) {
                doc = PsiDocumentManager.getInstance(project).getDocument(targetFile);
            }
            if (doc == null) {
                return;
            }
            TextRange range = startElement.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), detectedScale);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            PsiElement start = getStartElement();
            PsiElement descElem = previewDescriptor.getPsiElement();
            if (start != null && descElem != null) {
                PsiFile scaleFile = start.getContainingFile();
                PsiFile activeOriginal = descElem.getContainingFile().getOriginalFile();
                if (scaleFile != null && scaleFile.getOriginalFile() != activeOriginal) {
                    return buildCrossFileCustomDiff(scaleFile, start.getTextRange());
                }
            }
            return super.generatePreview(project, previewDescriptor);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            PsiElement start = getStartElement();
            if (start != null) {
                PsiFile scaleFile = start.getContainingFile();
                if (scaleFile != null && scaleFile.getOriginalFile() != file.getOriginalFile()) {
                    return buildCrossFileCustomDiff(scaleFile, start.getTextRange());
                }
            }
            return super.generatePreview(project, editor, file);
        }

        private IntentionPreviewInfo buildCrossFileCustomDiff(PsiFile scaleFile, TextRange range) {
            String originalText = scaleFile.getText();
            if (range.getEndOffset() > originalText.length()) {
                return IntentionPreviewInfo.EMPTY;
            }
            String modifiedText = originalText.substring(0, range.getStartOffset())
                + detectedScale
                + originalText.substring(range.getEndOffset());
            return new IntentionPreviewInfo.CustomDiff(
                scaleFile.getFileType(),
                scaleFile.getName(),
                originalText,
                modifiedText
            );
        }
    }

    private static @Nullable MetadataEntry findScaleFacetEntry(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return null;
        }
        for (var entry : metaMap.getMetadataEntryList()) {
            var text = entry.getText().trim();
            if (isScaleFacetToken(text)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isScaleFacetToken(String text) {
        return text.equals(StvnVocabulary.FACET_KW_SCALE_S)
            || text.equals(StvnVocabulary.FACET_KW_SCALE_MS)
            || text.equals(StvnVocabulary.FACET_KW_SCALE_US)
            || text.equals(StvnVocabulary.FACET_KW_SCALE_NS);
    }

    private static @Nullable String extractScaleFlag(SchemaType schemaType, Set<PsiElement> visited) {
        if (!visited.add(schemaType)) {
            return null;
        }
        MetadataEntry ownScale = findScaleFacetEntry(schemaType.getMetadataMap());
        if (ownScale != null) {
            return ownScale.getText().trim();
        }
        if (schemaType.getParent() instanceof TypeDefinition parentTd && parentTd.getSchemaType() == schemaType) {
            MetadataEntry tdScale = findScaleFacetEntry(parentTd.getMetadataMap());
            if (tdScale != null) {
                return tdScale.getText().trim();
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            TypeDefinition td = resolveTargetTypeDef(schemaType, kw.getText().trim());
            if (td != null) {
                MetadataEntry tdScale = findScaleFacetEntry(td.getMetadataMap());
                if (tdScale != null) {
                    return tdScale.getText().trim();
                }
                if (td.getSchemaType() != null) {
                    return extractScaleFlag(td.getSchemaType(), visited);
                }
            }
        }
        return null;
    }

    private static @Nullable String resolveBaseTypeString(SchemaType schemaType, Set<String> visited) {
        var ctor = schemaType.getSchemaConstructor();
        if (ctor != null && ctor.getAtomicType() != null) {
            return ctor.getAtomicType().getText().trim();
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(text) || StvnVocabulary.isBaseScalarType(text)) {
                return text;
            }
            if (!visited.add(text)) {
                return null;
            }
            TypeDefinition td = resolveTargetTypeDef(schemaType, text);
            if (td != null && td.getSchemaType() != null) {
                return resolveBaseTypeString(td.getSchemaType(), visited);
            }
        }
        return null;
    }

    private static @Nullable TypeDefinition resolveTargetTypeDef(SchemaType context, String aliasText) {
        var kw = context.getTypeKeyword();
        return StvnTypeReference.resolveTypeDefinitionAtContext(kw != null ? kw : context, aliasText);
    }
}
