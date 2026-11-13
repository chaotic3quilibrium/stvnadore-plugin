package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.documentation.StvnTimeEpochFormatter;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.StvnSuspiciousEpochMagnitudeInspection;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.Value;

/**
 * Factory and provider of smart in-place value conversion, bound-clamping, and schema exemplar
 * replacement quick-fixes for {@code :body} and {@code #CONST} payload diagnostics.
 */
@NullMarked
public final class StvnPayloadValueQuickFixProvider {

    private static final Pattern TEMPORAL_COMPONENTS_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)"
            + "(Z|[+-]\\d{2}:\\d{2})?"
            + "(?:\\[([^\\]]+)\\])?$"
    );

    private static final Pattern VALID_METADATA_RANGE_PATTERN = Pattern.compile(
        "valid\\s+(#\\w+)\\s+range\\s+is\\s+\\[(-?\\d+),\\s*(-?\\d+)\\]"
    );

    private static final Pattern ALLOWABLE_RANGE_MAX_PATTERN = Pattern.compile(
        "outside allowable range \\[\\d+,\\s*(\\d+)\\]"
    );

    private static final Pattern SIGNIFICANT_DIGITS_MAX_PATTERN = Pattern.compile(
        "significant digits \\((\\d+)\\)"
    );

    private static final Pattern INT_MIN_BOUND_DIAG_PATTERN = Pattern.compile(
        "(?:below minimum(?: bound)?|must be >=)\\s+(-?\\d+)"
    );

    private static final Pattern INT_MAX_BOUND_DIAG_PATTERN = Pattern.compile(
        "(?:exceeds maximum(?: bound)?|meets or exceeds maximum(?: bound)?|must be <)\\s+(-?\\d+)"
    );

    private static final Pattern INT_RANGE_DIAG_PATTERN = Pattern.compile(
        "\\[(-?\\d+),\\s*(-?\\d+)([\\])])"
    );

    private StvnPayloadValueQuickFixProvider() {}

    /**
     * Attaches applicable payload value conversion, clamping, and exemplar quick-fixes to an
     * {@link AnnotationBuilder} in {@code StvnExternalAnnotator}.
     *
     * @param builder      the active annotation builder
     * @param file         the PSI file being annotated
     * @param diagnosticId the symbolic or canonical diagnostic identifier
     * @param rawMessage   the raw diagnostic message
     * @param startOffset  the annotation start offset
     * @param endOffset    the annotation end offset
     * @return the updated {@link AnnotationBuilder}
     */
    public static AnnotationBuilder registerPayloadValueQuickFixes(
        AnnotationBuilder builder,
        PsiFile file,
        @Nullable String diagnosticId,
        String rawMessage,
        int startOffset,
        int endOffset
    ) {
        String code = diagnosticId != null ? diagnosticId : "";
        int textLength = file.getTextLength();

        // 1. Prohibited numeric underscore separator removal (E002 / ERR_INVALID_NUMERIC_LITERAL)
        if ((DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL.equals(code)
                || rawMessage.contains("Underscore separators are prohibited in numeric literals"))
                && startOffset >= 0
                && endOffset > startOffset
                && endOffset <= textLength) {
            String rawLiteral = file.getText().substring(startOffset, endOffset).trim();
            if (rawLiteral.contains("_")) {
                String cleanLiteral = rawLiteral.replace("_", "");
                return builder.withFix(
                    new TextRangeReplacementQuickFix(
                        file,
                        startOffset,
                        endOffset,
                        cleanLiteral,
                        "Remove prohibited underscores from numeric literal '" + cleanLiteral + "'"
                    )
                );
            }
        }

        // 2. MetadataEntry integer bound overflow (E009) and discrete bound kind (E011) in :defs / :type
        PsiElement elemAtStart = (startOffset >= 0 && startOffset < textLength)
            ? file.findElementAt(startOffset)
            : null;
        MetadataEntry metaEntry = elemAtStart != null
            ? PsiTreeUtil.getParentOfType(elemAtStart, MetadataEntry.class, false)
            : null;
        if (metaEntry != null) {
            Matcher rangeMatcher = VALID_METADATA_RANGE_PATTERN.matcher(rawMessage);
            if (rangeMatcher.find() && metaEntry.getMetadataValue() != null) {
                String facetKw = rangeMatcher.group(1);
                BigInteger minAllowed = new BigInteger(rangeMatcher.group(2));
                BigInteger maxAllowed = new BigInteger(rangeMatcher.group(3));
                BigInteger actual = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                    metaEntry,
                    metaEntry.getMetadataValue().getText().trim()
                ).orElse(null);
                AnnotationBuilder current = builder;
                if (actual != null) {
                    if (actual.compareTo(minAllowed) < 0) {
                        current = current.withFix(
                            new MetadataEntryReplacementQuickFix(
                                metaEntry,
                                facetKw + " " + minAllowed,
                                "Clamp '" + facetKw + "' to minimum physical bound '" + minAllowed + "'"
                            )
                        );
                    } else if (actual.compareTo(maxAllowed) > 0) {
                        current = current.withFix(
                            new MetadataEntryReplacementQuickFix(
                                metaEntry,
                                facetKw + " " + maxAllowed,
                                "Clamp '" + facetKw + "' to maximum physical bound '" + maxAllowed + "'"
                            )
                        );
                    }
                }
                String exemplarBound = StvnVocabulary.FACET_KW_MIN_INCL.equals(facetKw)
                    ? (BigInteger.ZERO.compareTo(minAllowed) >= 0 && BigInteger.ZERO.compareTo(maxAllowed) <= 0
                        ? "0"
                        : minAllowed.toString())
                    : maxAllowed.toString();
                current = current.withFix(
                    new MetadataEntryReplacementQuickFix(
                        metaEntry,
                        facetKw + " " + exemplarBound,
                        "Replace '" + facetKw + "' with exemplar bound '" + exemplarBound + "'",
                        PriorityAction.Priority.LOW
                    )
                );
                return current;
            }
            if (DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED.equals(code)
                    || "E011".equals(code)
                    || rawMessage.contains("Discrete types reject")
                    || rawMessage.contains("prohibited on discrete")) {
                String entryText = metaEntry.getText().trim();
                String valText = metaEntry.getMetadataValue() != null
                    ? metaEntry.getMetadataValue().getText().trim()
                    : "0";
                if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)) {
                    return builder.withFix(
                        new MetadataEntryReplacementQuickFix(
                            metaEntry,
                            StvnVocabulary.FACET_KW_MAX_EXCL + " " + valText,
                            "Replace '" + StvnVocabulary.FACET_KW_MAX_INCL
                                + "' with half-open bound '" + StvnVocabulary.FACET_KW_MAX_EXCL + "'"
                        )
                    );
                } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)) {
                    return builder.withFix(
                        new MetadataEntryReplacementQuickFix(
                            metaEntry,
                            StvnVocabulary.FACET_KW_MIN_INCL + " " + valText,
                            "Replace '" + StvnVocabulary.FACET_KW_MIN_EXCL
                                + "' with closed bound '" + StvnVocabulary.FACET_KW_MIN_INCL + "'"
                        )
                    );
                }
            }
        }

        // 3. Duplicate set element removal (DUPLICATE_SET_ELEMENT / E017 / E029)
        if (DiagnosticBag.ERR_DUPLICATE_SET_ELEMENT.equals(code)
                || rawMessage.contains("Duplicate set element")) {
            Value dupElem = findTargetValue(file, startOffset, endOffset);
            if (dupElem != null) {
                String elemText = dupElem.getText().trim();
                return builder.withFix(
                    new RemoveContainerElementQuickFix(
                        dupElem,
                        "Remove duplicate set element '" + elemText + "'"
                    )
                );
            }
        }

        // 4. Duplicate map key / duplicate inverted map value entry removal (E017 / E018)
        if (DiagnosticBag.ERR_DUPLICATE_MAP_KEY.equals(code)
                || DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE.equals(code)
                || rawMessage.contains("Duplicate map key")
                || rawMessage.contains("Duplicate inverted map value")) {
            PsiElement mapEntryElem = findEnclosingMapEntry(elemAtStart);
            if (mapEntryElem != null) {
                String entryText = mapEntryElem.getText().trim();
                return builder.withFix(
                    new RemoveContainerElementQuickFix(
                        mapEntryElem,
                        "Remove duplicate map entry '" + entryText + "'"
                    )
                );
            }
        }

        // 5. Container #maxSize excess element / entry removal (CAPACITY_OVERFLOW / E012)
        if ((DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(code) || "E012".equals(code))
                && rawMessage.contains("outside allowable range [")
                && !rawMessage.contains("String ")) {
            Matcher maxMatcher = ALLOWABLE_RANGE_MAX_PATTERN.matcher(rawMessage);
            String maxSizeStr = maxMatcher.find() ? maxMatcher.group(1) : "";
            String suffix = !maxSizeStr.isEmpty()
                ? " (exceeds " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + maxSizeStr + ")"
                : "";
            PsiElement mapEntryElem = findEnclosingMapEntry(elemAtStart);
            if (mapEntryElem != null && mapEntryElem.getTextRange().getStartOffset() == startOffset) {
                String entryText = mapEntryElem.getText().trim();
                return builder.withFix(
                    new RemoveContainerElementQuickFix(
                        mapEntryElem,
                        "Remove excess map entry '" + entryText + "'" + suffix
                    )
                );
            }
            Value excessElem = findTargetValue(file, startOffset, endOffset);
            if (excessElem != null && excessElem.getParent() instanceof ListLiteral) {
                String elemText = excessElem.getText().trim();
                return builder.withFix(
                    new RemoveContainerElementQuickFix(
                        excessElem,
                        "Remove excess element '" + elemText + "'" + suffix
                    )
                );
            }
        }

        if (!isPayloadValueDiagnostic(code, rawMessage)) {
            return builder;
        }
        Value targetValue = findTargetValue(file, startOffset, endOffset);
        if (targetValue == null) {
            return builder;
        }
        if (PsiTreeUtil.getParentOfType(targetValue, BodyEntry.class) == null
                && PsiTreeUtil.getParentOfType(targetValue, ConstantDefinition.class) == null) {
            return builder;
        }
        List<PayloadValueQuickFix> fixes = computeFixesForValue(
            targetValue,
            code,
            rawMessage,
            Clock.systemDefaultZone()
        );
        AnnotationBuilder current = builder;
        for (PayloadValueQuickFix fix : fixes) {
            current = current.withFix(fix);
        }
        return current;
    }

    /**
     * Computes the ordered list of smart conversion, bound-clamping, and exemplar replacement
     * quick-fixes for an invalid {@link Value} element.
     *
     * @param value   the offending PSI {@link Value} node
     * @param code    the diagnostic code
     * @param message the diagnostic message
     * @param clock   the clock used for live temporal exemplars
     * @return the ordered list of quick-fixes
     */
    public static List<PayloadValueQuickFix> computeFixesForValue(
        Value value,
        String code,
        String message,
        Clock clock
    ) {
        SchemaType rawSchema = resolveGoverningSchemaForValue(value);
        SchemaType terminalSchema = rawSchema != null
            ? StvnExemplarValueGenerator.resolveTerminalSchemaType(rawSchema)
            : null;
        Map<String, String> meta = rawSchema != null
            ? StvnExemplarValueGenerator.extractLineageMetadata(rawSchema)
            : Map.of();
        StvnSchema irSchema = rawSchema != null ? resolveLeafIrSchema(rawSchema) : null;
        if (irSchema == null) {
            var coreVal = StvnTypeResolver.resolveCoreValue(value);
            irSchema = coreVal != null ? coreVal.schema() : null;
            while (irSchema instanceof StvnSchema.PoisonedSchema p) {
                irSchema = p.bestEffortFallback().orElse(null);
            }
        }

        String rawValueText = value.getText().trim();
        String baseType = terminalSchema != null ? extractBaseType(terminalSchema, irSchema) : null;
        if (baseType == null && value.getFloatLiteral() != null) {
            baseType = StvnVocabulary.TYPE_FLOAT;
        }
        if (baseType == null
                && value.getIntegerLiteral() != null
                && (terminalSchema == null || terminalSchema.getSchemaConstructor() == null)) {
            baseType = StvnVocabulary.TYPE_INT;
        }
        if (baseType == null) {
            if (rawSchema != null) {
                String exemplar = StvnExemplarValueGenerator.generateExemplar(rawSchema, irSchema, clock);
                if (exemplar != null && !exemplar.equals(rawValueText)) {
                    return List.of(
                        new PayloadValueQuickFix(
                            value,
                            "Replace with exemplar value '" + exemplar + "'",
                            "STVN Exemplar Value Replacement",
                            exemplar,
                            null,
                            PriorityAction.Priority.LOW
                        )
                    );
                }
            }
            return List.of();
        }

        List<PayloadValueQuickFix> fixes = new ArrayList<>();

        if (value.getCollectionValue() != null
                && value.getCollectionValue().getTupleLiteral() != null
                && value.getCollectionValue().getTupleLiteral().getValueList().size() == 1) {
            Value innerVal = value.getCollectionValue().getTupleLiteral().getValueList().get(0);
            String innerText = innerVal.getText().trim();
            if (!innerText.isEmpty()) {
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Unwrap parentheses around scalar value '" + innerText + "'",
                        "STVN Scalar Unwrap",
                        innerText,
                        null,
                        PriorityAction.Priority.TOP
                    )
                );
            }
        } else if (value.getParent() instanceof TupleLiteral parentTuple
                && parentTuple.getValueList().size() == 1
                && parentTuple.getParent() != null
                && parentTuple.getParent().getParent() instanceof Value outerTupleVal
                && !rawValueText.isEmpty()) {
            fixes.add(
                new PayloadValueQuickFix(
                    outerTupleVal,
                    "Unwrap parentheses around scalar value '" + rawValueText + "'",
                    "STVN Scalar Unwrap",
                    rawValueText,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        }

        switch (baseType) {
            case StvnVocabulary.TYPE_DATE_TIME -> {
                DateTimeMode mode = resolveDateTimeMode(meta, irSchema);
                String modeFlag = switch (mode) {
                    case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
                    case ZONED -> StvnVocabulary.FACET_KW_ZONED;
                    case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
                };
                String minIncl = (irSchema instanceof StvnSchema.DateTimeSchema dt && dt.explicitMinInclIso().isPresent())
                    ? dt.explicitMinInclIso().get()
                    : resolveStringOrConstOrNull(value, meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                String maxExcl = (irSchema instanceof StvnSchema.DateTimeSchema dt && dt.explicitMaxExclIso().isPresent())
                    ? dt.explicitMaxExclIso().get()
                    : resolveStringOrConstOrNull(value, meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));

                String converted = tryConvertDateTimeLiteral(rawValueText, mode, clock);
                if (converted != null && !converted.equals(rawValueText)) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Convert to " + modeFlag + " " + StvnVocabulary.TYPE_DATE_TIME + " '" + converted + "'",
                            "STVN Temporal Mode Conversion",
                            converted,
                            null,
                            PriorityAction.Priority.TOP
                        )
                    );
                }

                addDateTimeClampFixesIfOutOfBounds(fixes, value, rawValueText, mode, minIncl, maxExcl, clock);

                String initialExemplar = StvnExemplarValueGenerator.generateDateTimeExemplar(mode, minIncl, maxExcl, clock);
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Replace with current " + modeFlag + " " + StvnVocabulary.TYPE_DATE_TIME,
                        "STVN Exemplar Value Replacement",
                        initialExemplar,
                        () -> StvnExemplarValueGenerator.generateDateTimeExemplar(mode, minIncl, maxExcl, clock),
                        PriorityAction.Priority.LOW
                    )
                );
            }
            case StvnVocabulary.TYPE_TIME_EPOCH -> {
                TemporalScale scale = resolveEpochScale(meta, irSchema);
                String scaleFlag = switch (scale) {
                    case S -> StvnVocabulary.FACET_KW_SCALE_S;
                    case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
                    case US -> StvnVocabulary.FACET_KW_SCALE_US;
                    case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
                };
                boolean unsigned = meta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                int sizeBits = parseIntOrDefault(
                    value,
                    meta.get(StvnVocabulary.FACET_KW_SIZE),
                    scale.bitWidth().intValue()
                );
                BigInteger minIncl = (irSchema instanceof StvnSchema.TimeEpochSchema ep && ep.explicitMinIncl().isPresent())
                    ? ep.explicitMinIncl().get()
                    : parseBigIntOrNull(value, meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                BigInteger maxExcl = (irSchema instanceof StvnSchema.TimeEpochSchema ep && ep.explicitMaxExcl().isPresent())
                    ? ep.explicitMaxExcl().get()
                    : parseBigIntOrNull(value, meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));

                String convertedTicks = tryConvertIsoStringToEpochTicks(rawValueText, scale, clock);
                if (convertedTicks != null) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Convert timestamp to " + scaleFlag + " " + StvnVocabulary.TYPE_TIME_EPOCH + " '" + convertedTicks + "'",
                            "STVN Temporal Epoch Conversion",
                            convertedTicks,
                            null,
                            PriorityAction.Priority.TOP
                        )
                    );
                }

                boolean rescaledAdded = addTimeEpochRescaleFixIfApplicable(
                    fixes,
                    value,
                    rawValueText,
                    scaleFlag,
                    unsigned,
                    sizeBits,
                    minIncl,
                    maxExcl
                );

                addIntegerClampFixIfOutOfBounds(
                    fixes,
                    value,
                    rawValueText,
                    unsigned,
                    sizeBits,
                    minIncl,
                    maxExcl,
                    rescaledAdded ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP,
                    PriorityAction.Priority.HIGH
                );

                String initialExemplar = StvnExemplarValueGenerator.generateTimeEpochExemplar(
                    scale,
                    unsigned,
                    sizeBits,
                    minIncl,
                    maxExcl,
                    clock
                );
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Replace with current " + scaleFlag + " " + StvnVocabulary.TYPE_TIME_EPOCH,
                        "STVN Exemplar Value Replacement",
                        initialExemplar,
                        () -> StvnExemplarValueGenerator.generateTimeEpochExemplar(
                            scale,
                            unsigned,
                            sizeBits,
                            minIncl,
                            maxExcl,
                            clock
                        ),
                        PriorityAction.Priority.LOW
                    )
                );
            }
            case StvnVocabulary.TYPE_INT -> {
                boolean unsigned = (irSchema instanceof StvnSchema.IntSchema is && is.unsigned())
                    || meta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                int defaultBits = (irSchema instanceof StvnSchema.IntSchema is)
                    ? is.bitWidth().intValue()
                    : StvnVocabulary.DEFAULT_INT_BIT_WIDTH.intValue();
                int sizeBits = parseIntOrDefault(value, meta.get(StvnVocabulary.FACET_KW_SIZE), defaultBits);
                BigInteger minIncl = (irSchema instanceof StvnSchema.IntSchema is && is.explicitMinIncl().isPresent())
                    ? is.explicitMinIncl().get()
                    : parseBigIntOrNull(value, meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                BigInteger maxExcl = (irSchema instanceof StvnSchema.IntSchema is && is.explicitMaxExcl().isPresent())
                    ? is.explicitMaxExcl().get()
                    : parseBigIntOrNull(value, meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));

                if (minIncl == null) {
                    Matcher minM = INT_MIN_BOUND_DIAG_PATTERN.matcher(message);
                    if (minM.find()) {
                        minIncl = parseBigIntOrNull(value, minM.group(1));
                    } else {
                        Matcher rangeM = INT_RANGE_DIAG_PATTERN.matcher(message);
                        if (rangeM.find()) {
                            minIncl = parseBigIntOrNull(value, rangeM.group(1));
                        }
                    }
                }
                if (maxExcl == null) {
                    Matcher maxM = INT_MAX_BOUND_DIAG_PATTERN.matcher(message);
                    if (maxM.find()) {
                        maxExcl = parseBigIntOrNull(value, maxM.group(1));
                    } else {
                        Matcher rangeM = INT_RANGE_DIAG_PATTERN.matcher(message);
                        if (rangeM.find()) {
                            BigInteger rawUpper = parseBigIntOrNull(value, rangeM.group(2));
                            if (rawUpper != null) {
                                maxExcl = "]".equals(rangeM.group(3)) ? rawUpper.add(BigInteger.ONE) : rawUpper;
                            }
                        }
                    }
                }

                Set<String> offeredIntClamps = addIntegerClampFixIfOutOfBounds(
                    fixes,
                    value,
                    rawValueText,
                    unsigned,
                    sizeBits,
                    minIncl,
                    maxExcl,
                    PriorityAction.Priority.TOP,
                    PriorityAction.Priority.HIGH
                );

                String exemplar = StvnExemplarValueGenerator.generateIntExemplar(unsigned, sizeBits, minIncl, maxExcl);
                if (!exemplar.equals(rawValueText) && !offeredIntClamps.contains(exemplar)) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Replace with exemplar value '" + exemplar + "'",
                            "STVN Exemplar Value Replacement",
                            exemplar,
                            null,
                            PriorityAction.Priority.LOW
                        )
                    );
                }
            }
            case StvnVocabulary.TYPE_FLOAT -> {
                FloatConstraintProfile floatProfile = resolveFloatConstraintProfile(value, meta, irSchema, message);
                if (value.getIntegerLiteral() != null) {
                    String rawIntText = value.getIntegerLiteral().getText().trim();
                    String appended = rawIntText + ".0";
                    BigDecimal appendedBd = StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(value, appended).orElse(null);
                    if (appendedBd != null && isValidFloatCandidate(appendedBd, floatProfile)) {
                        fixes.add(
                            new PayloadValueQuickFix(
                                value,
                                "Append '.0' to integer value '" + appended + "'",
                                "STVN Float Literal Conversion",
                                appended,
                                null,
                                PriorityAction.Priority.TOP
                            )
                        );
                    }
                }
                boolean emittedPrecisionFixes = false;
                if (value.getFloatLiteral() != null) {
                    int maxDigits = floatProfile.exactMaxDigits();
                    if (maxDigits > 0) {
                        BigDecimal bd = StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(value, rawValueText).orElse(null);
                        if (bd != null && bd.precision() > maxDigits) {
                            BigDecimal roundedBd = bd.round(new MathContext(maxDigits, RoundingMode.HALF_UP));
                            BigDecimal truncatedBd = bd.round(new MathContext(maxDigits, RoundingMode.DOWN));
                            String roundedStr = formatExactDecimalLiteral(roundedBd, maxDigits);
                            String truncatedStr = formatExactDecimalLiteral(truncatedBd, maxDigits);
                            fixes.add(
                                new PayloadValueQuickFix(
                                    value,
                                    "Round value to " + StvnVocabulary.FACET_KW_MAX_SIZE + " "
                                        + maxDigits + " significant digits '" + roundedStr + "'",
                                    "STVN Exact Float Precision Rounding",
                                    roundedStr,
                                    null,
                                    PriorityAction.Priority.TOP
                                )
                            );
                            fixes.add(
                                new PayloadValueQuickFix(
                                    value,
                                    "Truncate value to " + StvnVocabulary.FACET_KW_MAX_SIZE + " "
                                        + maxDigits + " significant digits '" + truncatedStr + "'",
                                    "STVN Exact Float Precision Truncation",
                                    truncatedStr,
                                    null,
                                    PriorityAction.Priority.TOP
                                )
                            );
                            emittedPrecisionFixes = true;
                        }
                    }
                }
                PriorityAction.Priority nearestClampPriority = emittedPrecisionFixes
                    ? PriorityAction.Priority.HIGH
                    : PriorityAction.Priority.TOP;
                PriorityAction.Priority oppositeClampPriority = emittedPrecisionFixes
                    ? PriorityAction.Priority.NORMAL
                    : PriorityAction.Priority.HIGH;
                Set<String> offeredFloatClamps = addFloatClampFixesIfOutOfBounds(
                    fixes,
                    value,
                    rawValueText,
                    floatProfile,
                    nearestClampPriority,
                    oppositeClampPriority
                );
                String exemplar = computeSchemaValidFloatExemplar(floatProfile);
                if (exemplar != null && !exemplar.equals(rawValueText) && !offeredFloatClamps.contains(exemplar)) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Replace with exemplar value '" + exemplar + "'",
                            "STVN Exemplar Value Replacement",
                            exemplar,
                            null,
                            PriorityAction.Priority.LOW
                        )
                    );
                }
            }
            case StvnVocabulary.TYPE_BOOLEAN -> {
                String clean = rawValueText.replace("\"", "").trim();
                if (clean.equalsIgnoreCase("true") || clean.equals("1")) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Convert to boolean literal '" + StvnVocabulary.VAL_TRUE + "'",
                            "STVN Scalar Conversion",
                            StvnVocabulary.VAL_TRUE,
                            null,
                            PriorityAction.Priority.TOP
                        )
                    );
                } else if (clean.equalsIgnoreCase("false") || clean.equals("0")) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Convert to boolean literal '" + StvnVocabulary.VAL_FALSE + "'",
                            "STVN Scalar Conversion",
                            StvnVocabulary.VAL_FALSE,
                            null,
                            PriorityAction.Priority.TOP
                        )
                    );
                }
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Replace with exemplar value '" + StvnVocabulary.VAL_TRUE + "'",
                        "STVN Exemplar Value Replacement",
                        StvnVocabulary.VAL_TRUE,
                        null,
                        PriorityAction.Priority.LOW
                    )
                );
            }
            case StvnVocabulary.TYPE_STRING -> {
                String rawMin = null;
                String rawMax = null;
                String regexPattern = null;
                long minBytes = 0L;
                long maxBytes = StvnVocabulary.DEFAULT_MAX_SIZE.longValue();
                if (irSchema instanceof StvnSchema.StringSchema ss) {
                    if (ss.lowerBound().isPresent()) {
                        rawMin = ss.lowerBound().get().value();
                    }
                    if (ss.upperBound().isPresent()) {
                        rawMax = ss.upperBound().get().value();
                    }
                    if (ss.rawRegexPattern().isPresent()) {
                        regexPattern = ss.rawRegexPattern().get();
                    }
                    minBytes = ss.minSize().longValue();
                    maxBytes = ss.maxSize().longValue();
                }
                if (meta.containsKey(StvnVocabulary.FACET_KW_SIZE)) {
                    long fixedSize = parseIntOrDefault(value, meta.get(StvnVocabulary.FACET_KW_SIZE), (int) minBytes);
                    minBytes = fixedSize;
                    maxBytes = fixedSize;
                } else {
                    if (meta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                        minBytes = parseIntOrDefault(value, meta.get(StvnVocabulary.FACET_KW_MIN_SIZE), (int) minBytes);
                    }
                    if (meta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE)) {
                        maxBytes = parseIntOrDefault(value, meta.get(StvnVocabulary.FACET_KW_MAX_SIZE), (int) maxBytes);
                    }
                }
                if (rawMin == null) {
                    String metaMin = meta.get(StvnVocabulary.FACET_KW_MIN_INCL);
                    if (metaMin == null) {
                        metaMin = meta.get(StvnVocabulary.FACET_KW_MIN_EXCL);
                    }
                    rawMin = resolveStringOrConstOrNull(value, metaMin);
                }
                if (rawMax == null) {
                    String metaMax = meta.get(StvnVocabulary.FACET_KW_MAX_INCL);
                    if (metaMax == null) {
                        metaMax = meta.get(StvnVocabulary.FACET_KW_MAX_EXCL);
                    }
                    rawMax = resolveStringOrConstOrNull(value, metaMax);
                }
                if (regexPattern == null) {
                    regexPattern = resolveStringOrConstOrNull(value, meta.get(StvnVocabulary.FACET_KW_REGEX));
                }

                String authored = value.getStringLiteral() != null ? stripQuotesOrFences(rawValueText) : null;
                if (authored != null && regexPattern != null && !regexPattern.isBlank()) {
                    try {
                        Pattern compiledRegex = Pattern.compile(regexPattern);
                        if (!compiledRegex.matcher(authored).matches()) {
                            String lower = authored.toLowerCase(Locale.ROOT);
                            if (!lower.equals(authored)
                                    && compiledRegex.matcher(lower).matches()
                                    && isStringWithinSizeAndLexBounds(lower, minBytes, maxBytes, rawMin, rawMax)) {
                                String quotedLower = "\"" + escapeStvnString(lower) + "\"";
                                fixes.add(
                                    new PayloadValueQuickFix(
                                        value,
                                        "Convert string to lowercase '" + quotedLower + "'",
                                        "STVN String Case Conversion",
                                        quotedLower,
                                        null,
                                        PriorityAction.Priority.TOP
                                    )
                                );
                            }
                            String upper = authored.toUpperCase(Locale.ROOT);
                            if (!upper.equals(authored)
                                    && compiledRegex.matcher(upper).matches()
                                    && isStringWithinSizeAndLexBounds(upper, minBytes, maxBytes, rawMin, rawMax)) {
                                String quotedUpper = "\"" + escapeStvnString(upper) + "\"";
                                fixes.add(
                                    new PayloadValueQuickFix(
                                        value,
                                        "Convert string to uppercase '" + quotedUpper + "'",
                                        "STVN String Case Conversion",
                                        quotedUpper,
                                        null,
                                        PriorityAction.Priority.TOP
                                    )
                                );
                            }
                        }
                    } catch (PatternSyntaxException ignored) {
                    }
                }

                if (authored != null && (rawMin != null || rawMax != null)) {
                    String quotedMin = rawMin != null ? "\"" + rawMin + "\"" : null;
                    String quotedMax = rawMax != null ? "\"" + rawMax + "\"" : null;
                    if (rawMax != null && quotedMax != null && authored.compareTo(rawMax) > 0) {
                        fixes.add(
                            new PayloadValueQuickFix(
                                value,
                                "Clamp value to maximum bound '" + quotedMax + "'",
                                "STVN Scalar Bound Clamping",
                                quotedMax,
                                null,
                                PriorityAction.Priority.TOP
                            )
                        );
                        if (quotedMin != null) {
                            fixes.add(
                                new PayloadValueQuickFix(
                                    value,
                                    "Clamp value to minimum bound '" + quotedMin + "'",
                                    "STVN Scalar Bound Clamping",
                                    quotedMin,
                                    null,
                                    PriorityAction.Priority.HIGH
                                )
                            );
                        }
                    } else if (rawMin != null && quotedMin != null && authored.compareTo(rawMin) < 0) {
                        fixes.add(
                            new PayloadValueQuickFix(
                                value,
                                "Clamp value to minimum bound '" + quotedMin + "'",
                                "STVN Scalar Bound Clamping",
                                quotedMin,
                                null,
                                PriorityAction.Priority.TOP
                            )
                        );
                        if (quotedMax != null) {
                            fixes.add(
                                new PayloadValueQuickFix(
                                    value,
                                    "Clamp value to maximum bound '" + quotedMax + "'",
                                    "STVN Scalar Bound Clamping",
                                    quotedMax,
                                    null,
                                    PriorityAction.Priority.HIGH
                                )
                            );
                        }
                    }
                }

                if (isUuidSchemaOrRegex(rawSchema, regexPattern)) {
                    String randomUuid = "\"" + UUID.randomUUID().toString().toLowerCase(Locale.ROOT) + "\"";
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Replace with random lowercase UUID '" + randomUuid + "'",
                            "STVN Random UUID Generation",
                            randomUuid,
                            null,
                            PriorityAction.Priority.LOW
                        )
                    );
                }

                String exemplar = StvnExemplarValueGenerator.generateExemplar(rawSchema, irSchema, clock);
                if (exemplar != null
                        && !exemplar.equals(rawValueText)
                        && fixes.stream().noneMatch(f -> f.getText().startsWith("Clamp value to ") && f.getText().endsWith("'" + exemplar + "'"))) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Replace with exemplar value '" + exemplar + "'",
                            "STVN Exemplar Value Replacement",
                            exemplar,
                            null,
                            PriorityAction.Priority.LOW
                        )
                    );
                }
            }
            case StvnVocabulary.TYPE_ENUM -> {
                List<String> allowedVariants = resolveActiveEnumVariants(irSchema, rawSchema, terminalSchema);
                boolean first = true;
                for (String variant : allowedVariants) {
                    if (variant.equals(rawValueText)) {
                        continue;
                    }
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Replace with '" + variant + "'",
                            "STVN Enum Variant Replacement",
                            variant,
                            null,
                            first ? PriorityAction.Priority.HIGH : PriorityAction.Priority.NORMAL
                        )
                    );
                    first = false;
                }
            }
            default -> {
            }
        }
        return fixes;
    }

    private static List<String> resolveActiveEnumVariants(
        @Nullable StvnSchema irSchema,
        @Nullable SchemaType rawSchema,
        @Nullable SchemaType terminalSchema
    ) {
        if (irSchema instanceof StvnSchema.EnumSubsetSchema subsetIr) {
            return subsetIr.activeVariants();
        }
        if (irSchema instanceof StvnSchema.EnumSchema enumIr) {
            return enumIr.rootVariants();
        }
        if (rawSchema != null) {
            var subsetInfo = StvnTypeResolver.resolveEnumSubset(rawSchema);
            if (subsetInfo != null) {
                return subsetInfo.allowedVariants();
            }
        }
        SchemaType target = terminalSchema != null ? terminalSchema : rawSchema;
        if (target != null
                && target.getSchemaConstructor() != null
                && target.getSchemaConstructor().getSumType() != null
                && target.getSchemaConstructor().getSumType().getEnumDef() != null) {
            return target.getSchemaConstructor().getSumType().getEnumDef()
                .getValueKeywordList().stream()
                .map(vk -> vk.getText().trim())
                .toList();
        }
        return List.of();
    }

    private static boolean isStringWithinSizeAndLexBounds(
        String candidate,
        long minBytes,
        long maxBytes,
        @Nullable String rawMin,
        @Nullable String rawMax
    ) {
        int byteLen = candidate.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen < minBytes || byteLen > maxBytes) {
            return false;
        }
        if (rawMin != null && candidate.compareTo(rawMin) < 0) {
            return false;
        }
        return rawMax == null || candidate.compareTo(rawMax) <= 0;
    }

    private static boolean isUuidSchemaOrRegex(@Nullable SchemaType rawSchema, @Nullable String regexPattern) {
        if (regexPattern != null && regexPattern.contains("{8}-[0-9a-f") && regexPattern.contains("{12}")) {
            return true;
        }
        if (rawSchema != null && rawSchema.getTypeKeyword() != null) {
            String kw = rawSchema.getTypeKeyword().getText().trim();
            String bare = kw.contains("/") ? StvnVocabulary.SIGIL_TYPIC + kw.substring(kw.lastIndexOf('/') + 1) : kw;
            return StvnVocabulary.TYPE_UUID_LOWER_CASE.equals(bare)
                || StvnVocabulary.TYPE_UUID_MIXED_CASE.equals(bare);
        }
        return false;
    }

    private static String escapeStvnString(String unquoted) {
        return unquoted.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Resolves the governing {@link SchemaType} for a {@link Value} located in either {@code :body}
     * or a {@code #CONST} definition in {@code :defs}.
     *
     * @param value the payload or constant value element
     * @return the governing {@link SchemaType}, or {@code null} if unresolvable
     */
    public static @Nullable SchemaType resolveGoverningSchemaForValue(Value value) {
        ConstantDefinition constDef = PsiTreeUtil.getParentOfType(value, ConstantDefinition.class);
        if (constDef != null && constDef.getSchemaType() != null) {
            if (value.getParent() == constDef) {
                return constDef.getSchemaType();
            }
            SchemaType caretSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(value);
            return caretSchema != null ? caretSchema : constDef.getSchemaType();
        }
        var info = StvnTypeResolver.resolveBaseTypeInfo(value);
        if (info != null && info.getSchema() != null) {
            return info.getSchema();
        }
        return StvnTypeResolver.resolveExpectedSchemaAtCaret(value);
    }

    private static @Nullable StvnSchema resolveLeafIrSchema(SchemaType schemaType) {
        var kw = schemaType.getTypeKeyword();
        if (kw != null && schemaType.getContainingFile() != null) {
            String aliasName = kw.getText().trim();
            var target = StvnTypeReference.resolveTypeInFile(
                schemaType.getContainingFile(),
                aliasName,
                new HashSet<>()
            );
            var td = StvnPsiUtils.resolveTargetTypeDefinition(target);
            if (td == null && !schemaType.getProject().isDisposed()) {
                td = StvnPreludeBridge.resolvePreludeTypeDefinition(schemaType.getProject(), aliasName);
            }
            if (td != null) {
                StvnSchema ir = StvnTypeResolver.resolveNominalIrSchema(td);
                while (ir instanceof StvnSchema.PoisonedSchema p) {
                    ir = p.bestEffortFallback().orElse(null);
                }
                return ir;
            }
        }
        return null;
    }

    private static @Nullable String extractBaseType(SchemaType terminalSchema, @Nullable StvnSchema irSchema) {
        if (irSchema instanceof StvnSchema.DateTimeSchema) {
            return StvnVocabulary.TYPE_DATE_TIME;
        }
        if (irSchema instanceof StvnSchema.TimeEpochSchema) {
            return StvnVocabulary.TYPE_TIME_EPOCH;
        }
        if (irSchema instanceof StvnSchema.IntSchema) {
            return StvnVocabulary.TYPE_INT;
        }
        if (irSchema instanceof StvnSchema.FloatSchema) {
            return StvnVocabulary.TYPE_FLOAT;
        }
        if (irSchema instanceof StvnSchema.StringSchema) {
            return StvnVocabulary.TYPE_STRING;
        }
        if (irSchema instanceof StvnSchema.BooleanSchema) {
            return StvnVocabulary.TYPE_BOOLEAN;
        }
        if (irSchema instanceof StvnSchema.EnumSchema || irSchema instanceof StvnSchema.EnumSubsetSchema) {
            return StvnVocabulary.TYPE_ENUM;
        }
        var ctor = terminalSchema.getSchemaConstructor();
        if (ctor != null) {
            if (ctor.getAtomicType() != null) {
                return ctor.getAtomicType().getText().trim();
            }
            if (ctor.getSumType() != null) {
                var sum = ctor.getSumType();
                if (sum.getEnumDef() != null || sum.getText().trim().startsWith(StvnVocabulary.TYPE_ENUM)) {
                    return StvnVocabulary.TYPE_ENUM;
                }
            }
        }
        return null;
    }

    private static DateTimeMode resolveDateTimeMode(Map<String, String> meta, @Nullable StvnSchema irSchema) {
        if (meta.containsKey(StvnVocabulary.FACET_KW_ZONED)) {
            return DateTimeMode.ZONED;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_AUDITED)) {
            return DateTimeMode.AUDITED;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_OFFSET)) {
            return DateTimeMode.OFFSET;
        }
        if (irSchema instanceof StvnSchema.DateTimeSchema dt) {
            return dt.mode();
        }
        return DateTimeMode.OFFSET;
    }

    private static TemporalScale resolveEpochScale(Map<String, String> meta, @Nullable StvnSchema irSchema) {
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_S)) {
            return TemporalScale.S;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_US)) {
            return TemporalScale.US;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_NS)) {
            return TemporalScale.NS;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_MS)) {
            return TemporalScale.MS;
        }
        if (irSchema instanceof StvnSchema.TimeEpochSchema ep) {
            return ep.scale();
        }
        return TemporalScale.MS;
    }

    /**
     * Converts a raw STVN string or integer literal into a valid {@code :DateTime} literal for the
     * target {@link DateTimeMode}, preserving the authored instant and timezone when possible.
     *
     * @param rawValueText the raw literal text
     * @param targetMode   the target {@link DateTimeMode}
     * @param clock        the fallback clock
     * @return the converted quoted STVN {@code :DateTime} literal, or {@code null} if unparseable
     */
    public static @Nullable String tryConvertDateTimeLiteral(
        String rawValueText,
        DateTimeMode targetMode,
        Clock clock
    ) {
        String unquoted = stripQuotesOrFences(rawValueText);
        if (unquoted == null) {
            OptionalEpochInstant epochInst = tryParseIntegerAsInstant(rawValueText);
            if (epochInst != null) {
                return formatInstantForDateTimeMode(
                    epochInst.instant(),
                    targetMode,
                    StvnExemplarValueGenerator.resolveExemplarZoneId(clock.getZone())
                );
            }
            return null;
        }

        Matcher matcher = TEMPORAL_COMPONENTS_PATTERN.matcher(unquoted.trim());
        if (!matcher.matches()) {
            return null;
        }

        String localPart = matcher.group(1).replace(' ', 'T');
        String offsetPart = matcher.group(2);
        String zonePart = matcher.group(3);

        LocalDateTime ldt;
        try {
            ldt = LocalDateTime.parse(localPart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }

        ZoneId parsedZone = null;
        if (zonePart != null && !zonePart.isBlank()) {
            try {
                ZoneId candidate = ZoneId.of(zonePart.trim());
                if (!(candidate instanceof ZoneOffset)
                        && !candidate.getId().startsWith("+")
                        && !candidate.getId().startsWith("-")) {
                    parsedZone = candidate;
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        ZoneOffset parsedOffset = null;
        if (offsetPart != null && !offsetPart.isBlank()) {
            try {
                parsedOffset = ZoneOffset.of(offsetPart.trim());
            } catch (Exception ignored) {
                return null;
            }
        }

        ZoneId fallbackZone = StvnExemplarValueGenerator.resolveExemplarZoneId(clock.getZone());

        return switch (targetMode) {
            case OFFSET -> {
                if (parsedZone != null) {
                    ZonedDateTime zdt = ZonedDateTime.ofLocal(ldt, parsedZone, parsedOffset);
                    yield "\"" + localPart + formatOffsetForMode(zdt.getOffset()) + "\"";
                }
                if (parsedOffset != null) {
                    yield "\"" + localPart + formatOffsetForMode(parsedOffset) + "\"";
                }
                ZonedDateTime zdt = ldt.atZone(fallbackZone);
                yield "\"" + localPart + formatOffsetForMode(zdt.getOffset()) + "\"";
            }
            case ZONED -> {
                if (parsedZone != null) {
                    ZonedDateTime zdt = ZonedDateTime.ofLocal(ldt, parsedZone, parsedOffset);
                    String resolvedLocal = zdt.toLocalDateTime().equals(ldt)
                        ? localPart
                        : zdt.toLocalDateTime().toString();
                    yield "\"" + resolvedLocal + "[" + parsedZone.getId() + "]\"";
                }
                if (parsedOffset != null) {
                    if (fallbackZone.getRules().getValidOffsets(ldt).contains(parsedOffset)) {
                        yield "\"" + localPart + "[" + fallbackZone.getId() + "]\"";
                    }
                    if (parsedOffset.getTotalSeconds() == 0) {
                        yield "\"" + localPart + "[UTC]\"";
                    }
                    ZonedDateTime converted = OffsetDateTime.of(ldt, parsedOffset).atZoneSameInstant(fallbackZone);
                    yield "\"" + converted.toLocalDateTime() + "[" + fallbackZone.getId() + "]\"";
                }
                ZonedDateTime zdt = ldt.atZone(fallbackZone);
                String resolvedLocal = zdt.toLocalDateTime().equals(ldt)
                    ? localPart
                    : zdt.toLocalDateTime().toString();
                yield "\"" + resolvedLocal + "[" + fallbackZone.getId() + "]\"";
            }
            case AUDITED -> {
                if (parsedZone != null) {
                    ZonedDateTime zdt = ZonedDateTime.ofLocal(ldt, parsedZone, parsedOffset);
                    String resolvedLocal = zdt.toLocalDateTime().equals(ldt)
                        ? localPart
                        : zdt.toLocalDateTime().toString();
                    yield "\""
                        + resolvedLocal
                        + formatOffsetForMode(zdt.getOffset())
                        + "["
                        + parsedZone.getId()
                        + "]\"";
                }
                if (parsedOffset != null) {
                    if (fallbackZone.getRules().getValidOffsets(ldt).contains(parsedOffset)) {
                        yield "\""
                            + localPart
                            + formatOffsetForMode(parsedOffset)
                            + "["
                            + fallbackZone.getId()
                            + "]\"";
                    }
                    if (parsedOffset.getTotalSeconds() == 0) {
                        yield "\"" + localPart + "Z[UTC]\"";
                    }
                    ZonedDateTime converted = OffsetDateTime.of(ldt, parsedOffset).atZoneSameInstant(fallbackZone);
                    yield "\""
                        + converted.toLocalDateTime()
                        + formatOffsetForMode(converted.getOffset())
                        + "["
                        + fallbackZone.getId()
                        + "]\"";
                }
                ZonedDateTime zdt = ldt.atZone(fallbackZone);
                String resolvedLocal = zdt.toLocalDateTime().equals(ldt)
                    ? localPart
                    : zdt.toLocalDateTime().toString();
                yield "\""
                    + resolvedLocal
                    + formatOffsetForMode(zdt.getOffset())
                    + "["
                    + fallbackZone.getId()
                    + "]\"";
            }
        };
    }

    private static void addDateTimeClampFixesIfOutOfBounds(
        List<PayloadValueQuickFix> fixes,
        Value value,
        String rawValueText,
        DateTimeMode mode,
        @Nullable String minIncl,
        @Nullable String maxExcl,
        Clock clock
    ) {
        if (minIncl == null && maxExcl == null) {
            return;
        }
        String unquotedAuthored = stripQuotesOrFences(rawValueText);
        if (unquotedAuthored == null || unquotedAuthored.isBlank()) {
            return;
        }
        ZoneId fallbackZone = StvnExemplarValueGenerator.resolveExemplarZoneId(clock.getZone());
        Instant authoredInstant = parseAnyIsoTimestampToInstant(unquotedAuthored.trim(), fallbackZone);
        if (authoredInstant == null) {
            return;
        }
        String cleanMin = stripQuotesOrFences(minIncl) != null ? stripQuotesOrFences(minIncl) : minIncl;
        String cleanMax = stripQuotesOrFences(maxExcl) != null ? stripQuotesOrFences(maxExcl) : maxExcl;
        Instant minInstant = cleanMin != null ? parseAnyIsoTimestampToInstant(cleanMin.trim(), fallbackZone) : null;
        Instant maxExclInstant = cleanMax != null ? parseAnyIsoTimestampToInstant(cleanMax.trim(), fallbackZone) : null;

        boolean belowMin = minInstant != null && authoredInstant.isBefore(minInstant);
        boolean aboveOrAtMax = maxExclInstant != null && !authoredInstant.isBefore(maxExclInstant);
        if (!belowMin && !aboveOrAtMax) {
            return;
        }

        Instant maxInclInstant = null;
        if (maxExclInstant != null) {
            if (cleanMax != null && cleanMax.contains(".")) {
                maxInclInstant = maxExclInstant.minusMillis(1);
            } else {
                maxInclInstant = maxExclInstant.minusSeconds(1);
            }
            if (minInstant != null && maxInclInstant.isBefore(minInstant)) {
                maxInclInstant = minInstant;
            }
        }

        String minBoundedLit = minInstant != null
            ? formatDateTimeBoundForMode(cleanMin, minInstant, mode, clock, true)
            : null;
        String maxBoundedLit = maxInclInstant != null
            ? formatDateTimeBoundForMode(cleanMax, maxInclInstant, mode, clock, false)
            : null;

        if (minInstant != null && maxInclInstant != null && minBoundedLit != null && maxBoundedLit != null) {
            long distToMin = Math.abs(Duration.between(authoredInstant, minInstant).toMillis());
            long distToMax = Math.abs(Duration.between(authoredInstant, maxInclInstant).toMillis());
            if (distToMin <= distToMax) {
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Clamp value to minimum bound '" + minBoundedLit + "'",
                        "STVN Scalar Bound Clamping",
                        minBoundedLit,
                        null,
                        PriorityAction.Priority.TOP
                    )
                );
                if (!maxBoundedLit.equals(minBoundedLit)) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Clamp value to maximum bound '" + maxBoundedLit + "'",
                            "STVN Scalar Bound Clamping",
                            maxBoundedLit,
                            null,
                            PriorityAction.Priority.HIGH
                        )
                    );
                }
            } else {
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Clamp value to maximum bound '" + maxBoundedLit + "'",
                        "STVN Scalar Bound Clamping",
                        maxBoundedLit,
                        null,
                        PriorityAction.Priority.TOP
                    )
                );
                if (!minBoundedLit.equals(maxBoundedLit)) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Clamp value to minimum bound '" + minBoundedLit + "'",
                            "STVN Scalar Bound Clamping",
                            minBoundedLit,
                            null,
                            PriorityAction.Priority.HIGH
                        )
                    );
                }
            }
        } else if (minBoundedLit != null) {
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + minBoundedLit + "'",
                    "STVN Scalar Bound Clamping",
                    minBoundedLit,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        } else if (maxBoundedLit != null) {
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + maxBoundedLit + "'",
                    "STVN Scalar Bound Clamping",
                    maxBoundedLit,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        }
    }

    private static String formatDateTimeBoundForMode(
        @Nullable String rawCleanBound,
        Instant targetInstant,
        DateTimeMode mode,
        Clock clock,
        boolean exactMatchToBound
    ) {
        if (exactMatchToBound && rawCleanBound != null) {
            String converted = tryConvertDateTimeLiteral("\"" + rawCleanBound.trim() + "\"", mode, clock);
            if (converted != null) {
                return converted;
            }
        }
        Instant truncated = targetInstant.getNano() == 0
            ? targetInstant.truncatedTo(ChronoUnit.SECONDS)
            : targetInstant;
        ZoneId targetZone = resolveBoundZoneId(
            rawCleanBound,
            mode,
            StvnExemplarValueGenerator.resolveExemplarZoneId(clock.getZone())
        );
        return formatInstantForDateTimeMode(truncated, mode, targetZone);
    }

    private static ZoneId resolveBoundZoneId(
        @Nullable String rawCleanBound,
        DateTimeMode mode,
        ZoneId fallbackZone
    ) {
        if (rawCleanBound != null && !rawCleanBound.isBlank()) {
            Matcher matcher = TEMPORAL_COMPONENTS_PATTERN.matcher(rawCleanBound.trim());
            if (matcher.matches()) {
                String offsetPart = matcher.group(2);
                String zonePart = matcher.group(3);
                if (zonePart != null && !zonePart.isBlank()) {
                    try {
                        return ZoneId.of(zonePart.trim());
                    } catch (Exception ignored) {
                    }
                }
                if (mode == DateTimeMode.OFFSET && offsetPart != null && !offsetPart.isBlank()) {
                    try {
                        return ZoneOffset.of(offsetPart.trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return fallbackZone;
    }

    /**
     * Attempts to parse an ISO-8601 string literal and convert it into integer ticks in the given
     * {@link TemporalScale}.
     *
     * @param rawValueText the raw literal text
     * @param scale        the target {@link TemporalScale}
     * @param clock        the fallback clock
     * @return the integer tick string, or {@code null} if not a valid timestamp string
     */
    public static @Nullable String tryConvertIsoStringToEpochTicks(
        String rawValueText,
        TemporalScale scale,
        Clock clock
    ) {
        String unquoted = stripQuotesOrFences(rawValueText);
        if (unquoted == null || unquoted.isBlank()) {
            return null;
        }
        Instant instant = parseAnyIsoTimestampToInstant(
            unquoted.trim(),
            StvnExemplarValueGenerator.resolveExemplarZoneId(clock.getZone())
        );
        if (instant == null) {
            return null;
        }
        return StvnExemplarValueGenerator.instantToEpochTicks(instant, scale).toString();
    }

    private static boolean addTimeEpochRescaleFixIfApplicable(
        List<PayloadValueQuickFix> fixes,
        Value value,
        String rawValueText,
        String scaleFlag,
        boolean unsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl
    ) {
        BigInteger actual = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(value, rawValueText).orElse(null);
        if (actual == null || actual.equals(BigInteger.ZERO)) {
            return false;
        }
        String digits = (rawValueText.startsWith("-") ? rawValueText.substring(1) : rawValueText).replace("_", "");
        String detectedScale = StvnTimeEpochFormatter.detectScaleBand(digits.length());
        if (detectedScale == null || detectedScale.equals(scaleFlag)) {
            return false;
        }
        int declaredOrder = StvnSuspiciousEpochMagnitudeInspection.scaleToOrder(scaleFlag);
        int detectedOrder = StvnSuspiciousEpochMagnitudeInspection.scaleToOrder(detectedScale);
        int diff = detectedOrder - declaredOrder;
        if (Math.abs(diff) < 3) {
            return false;
        }
        BigInteger factor = BigInteger.TEN.pow(Math.abs(diff));
        BigInteger rescaled = diff > 0 ? actual.divide(factor) : actual.multiply(factor);

        BigInteger[] bounds = StvnExemplarValueGenerator.computeEffectiveIntegerBounds(
            unsigned,
            sizeBits,
            minIncl,
            maxExcl
        );
        if (rescaled.compareTo(bounds[0]) >= 0 && rescaled.compareTo(bounds[1]) <= 0) {
            String rescaledStr = rescaled.toString();
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Rescale literal to '" + rescaledStr + "' (match declared '" + scaleFlag + "' scale)",
                    "STVN Temporal Epoch Rescaling",
                    rescaledStr,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
            return true;
        }
        return false;
    }

    private static Set<String> addIntegerClampFixIfOutOfBounds(
        List<PayloadValueQuickFix> fixes,
        Value value,
        String rawValueText,
        boolean unsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl,
        PriorityAction.Priority primaryPriority,
        PriorityAction.Priority secondaryPriority
    ) {
        BigInteger actual = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(value, rawValueText).orElse(null);
        if (actual == null) {
            return Set.of();
        }
        BigInteger[] bounds = StvnExemplarValueGenerator.computeEffectiveIntegerBounds(
            unsigned,
            sizeBits,
            minIncl,
            maxExcl
        );
        BigInteger effMin = bounds[0];
        BigInteger effMaxIncl = bounds[1];

        if (actual.compareTo(effMin) >= 0 && actual.compareTo(effMaxIncl) <= 0) {
            return Set.of();
        }

        Set<String> offered = new LinkedHashSet<>(2);
        BigInteger distToMin = actual.subtract(effMin).abs();
        BigInteger distToMax = actual.subtract(effMaxIncl).abs();
        String minStr = effMin.toString();
        String maxStr = effMaxIncl.toString();

        if (distToMin.compareTo(distToMax) <= 0) {
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + minStr + "'",
                    "STVN Scalar Bound Clamping",
                    minStr,
                    null,
                    primaryPriority
                )
            );
            offered.add(minStr);
            if (!effMaxIncl.equals(effMin)) {
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Clamp value to maximum bound '" + maxStr + "'",
                        "STVN Scalar Bound Clamping",
                        maxStr,
                        null,
                        secondaryPriority
                    )
                );
                offered.add(maxStr);
            }
        } else {
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + maxStr + "'",
                    "STVN Scalar Bound Clamping",
                    maxStr,
                    null,
                    primaryPriority
                )
            );
            offered.add(maxStr);
            if (!effMin.equals(effMaxIncl)) {
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Clamp value to minimum bound '" + minStr + "'",
                        "STVN Scalar Bound Clamping",
                        minStr,
                        null,
                        secondaryPriority
                    )
                );
                offered.add(minStr);
            }
        }
        return offered;
    }

    private record FloatConstraintProfile(
        StvnSchema.FloatRepresentation representation,
        int exactMaxDigits,
        @Nullable BigDecimal lowerBound,
        boolean lowerExclusive,
        @Nullable BigDecimal upperBound,
        boolean upperExclusive
    ) {}

    private record EffectiveFloatClampEndpoints(
        @Nullable BigDecimal minValue,
        @Nullable String minLiteral,
        @Nullable BigDecimal maxValue,
        @Nullable String maxLiteral
    ) {}

    private static FloatConstraintProfile resolveFloatConstraintProfile(
        PsiElement context,
        Map<String, String> meta,
        @Nullable StvnSchema irSchema,
        String message
    ) {
        StvnSchema.FloatRepresentation rep = StvnSchema.FloatRepresentation.IEEE_64;
        int exactMaxDigits = -1;
        BigDecimal lowerBound = null;
        boolean lowerExclusive = false;
        BigDecimal upperBound = null;
        boolean upperExclusive = false;

        if (meta.containsKey(StvnVocabulary.FACET_KW_EXACT)) {
            rep = StvnSchema.FloatRepresentation.EXACT_DECIMAL;
            exactMaxDigits = parseIntOrDefault(
                context,
                meta.get(StvnVocabulary.FACET_KW_MAX_SIZE),
                StvnVocabulary.DEFAULT_MAX_SIZE.intValue()
            );
        } else if ("32".equals(meta.get(StvnVocabulary.FACET_KW_SIZE))) {
            rep = StvnSchema.FloatRepresentation.IEEE_32;
        }

        BigDecimal psiMinIncl = parseBigDecimalOrNull(context, meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
        BigDecimal psiMinExcl = parseBigDecimalOrNull(context, meta.get(StvnVocabulary.FACET_KW_MIN_EXCL));
        BigDecimal psiMaxIncl = parseBigDecimalOrNull(context, meta.get(StvnVocabulary.FACET_KW_MAX_INCL));
        BigDecimal psiMaxExcl = parseBigDecimalOrNull(context, meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
        if (psiMinIncl != null) {
            lowerBound = psiMinIncl;
            lowerExclusive = false;
        } else if (psiMinExcl != null) {
            lowerBound = psiMinExcl;
            lowerExclusive = true;
        }
        if (psiMaxIncl != null) {
            upperBound = psiMaxIncl;
            upperExclusive = false;
        } else if (psiMaxExcl != null) {
            upperBound = psiMaxExcl;
            upperExclusive = true;
        }

        if (irSchema instanceof StvnSchema.FloatSchema fs) {
            rep = fs.representation();
            if (rep == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
                exactMaxDigits = fs.exactMaxDigits().intValue();
            }
            if (fs.lowerBound().isPresent()) {
                var lb = fs.lowerBound().get();
                BigDecimal irLower = lb.value();
                boolean irLowerExcl = lb.kind() == StvnSchema.BoundKind.EXCLUSIVE;
                BigDecimal psiLower = irLowerExcl ? psiMinExcl : psiMinIncl;
                if (psiLower == null && lowerBound != null && lowerBound.compareTo(irLower) == 0) {
                    psiLower = lowerBound;
                }
                lowerBound = (psiLower != null && psiLower.compareTo(irLower) == 0 && psiLower.scale() > irLower.scale())
                    ? psiLower
                    : irLower;
                lowerExclusive = irLowerExcl;
            }
            if (fs.upperBound().isPresent()) {
                var ub = fs.upperBound().get();
                BigDecimal irUpper = ub.value();
                boolean irUpperExcl = ub.kind() == StvnSchema.BoundKind.EXCLUSIVE;
                BigDecimal psiUpper = irUpperExcl ? psiMaxExcl : psiMaxIncl;
                if (psiUpper == null && upperBound != null && upperBound.compareTo(irUpper) == 0) {
                    psiUpper = upperBound;
                }
                upperBound = (psiUpper != null && psiUpper.compareTo(irUpper) == 0 && psiUpper.scale() > irUpper.scale())
                    ? psiUpper
                    : irUpper;
                upperExclusive = irUpperExcl;
            }
        }

        Matcher sigMatcher = SIGNIFICANT_DIGITS_MAX_PATTERN.matcher(message);
        if (sigMatcher.find()) {
            int parsedDigits = parseIntOrDefault(context, sigMatcher.group(1), -1);
            if (parsedDigits > 0) {
                rep = StvnSchema.FloatRepresentation.EXACT_DECIMAL;
                exactMaxDigits = parsedDigits;
            }
        }
        return new FloatConstraintProfile(rep, exactMaxDigits, lowerBound, lowerExclusive, upperBound, upperExclusive);
    }

    private static @Nullable BigDecimal parseBigDecimalOrNull(PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(context, raw.trim()).orElse(null);
    }

    private static boolean isValidFloatCandidate(BigDecimal candidate, FloatConstraintProfile profile) {
        if (profile.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
            if (profile.exactMaxDigits() > 0 && candidate.precision() > profile.exactMaxDigits()) {
                return false;
            }
        } else if (profile.representation() == StvnSchema.FloatRepresentation.IEEE_32) {
            if (candidate.compareTo(StvnVocabulary.FLOAT32_MIN) < 0
                    || candidate.compareTo(StvnVocabulary.FLOAT32_MAX) > 0) {
                return false;
            }
        } else {
            if (candidate.compareTo(StvnVocabulary.FLOAT64_MIN) < 0
                    || candidate.compareTo(StvnVocabulary.FLOAT64_MAX) > 0) {
                return false;
            }
        }
        if (profile.lowerBound() != null) {
            int cmp = candidate.compareTo(profile.lowerBound());
            if (profile.lowerExclusive() ? cmp <= 0 : cmp < 0) {
                return false;
            }
        }
        if (profile.upperBound() != null) {
            int cmp = candidate.compareTo(profile.upperBound());
            if (profile.upperExclusive() ? cmp >= 0 : cmp > 0) {
                return false;
            }
        }
        return true;
    }

    private static EffectiveFloatClampEndpoints computeFloatClampEndpoints(
        FloatConstraintProfile profile,
        boolean includeIeeePhysicalBounds
    ) {
        BigDecimal lower = profile.lowerBound();
        boolean lowerExcl = profile.lowerExclusive();
        BigDecimal upper = profile.upperBound();
        boolean upperExcl = profile.upperExclusive();

        if (includeIeeePhysicalBounds && profile.representation() != StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
            BigDecimal physMin = profile.representation() == StvnSchema.FloatRepresentation.IEEE_32
                ? StvnVocabulary.FLOAT32_MIN
                : StvnVocabulary.FLOAT64_MIN;
            BigDecimal physMax = profile.representation() == StvnSchema.FloatRepresentation.IEEE_32
                ? StvnVocabulary.FLOAT32_MAX
                : StvnVocabulary.FLOAT64_MAX;
            if (lower == null || lower.compareTo(physMin) < 0) {
                lower = physMin;
                lowerExcl = false;
            }
            if (upper == null || upper.compareTo(physMax) > 0) {
                upper = physMax;
                upperExcl = false;
            }
        }

        String minLit = lower != null ? computeSingleFloatEndpointLiteral(lower, lowerExcl, true, profile) : null;
        BigDecimal minVal = minLit != null ? new BigDecimal(minLit) : null;
        String maxLit = upper != null ? computeSingleFloatEndpointLiteral(upper, upperExcl, false, profile) : null;
        BigDecimal maxVal = maxLit != null ? new BigDecimal(maxLit) : null;
        return new EffectiveFloatClampEndpoints(minVal, minLit, maxVal, maxLit);
    }

    private static @Nullable String computeSingleFloatEndpointLiteral(
        BigDecimal bound,
        boolean exclusive,
        boolean isLowerEndpoint,
        FloatConstraintProfile profile
    ) {
        boolean isExact = profile.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL;
        int maxDigits = isExact
            ? profile.exactMaxDigits()
            : (profile.representation() == StvnSchema.FloatRepresentation.IEEE_32
                ? Math.max(bound.precision(), 8)
                : Math.max(bound.precision(), 17));
        RoundingMode directedMode = isLowerEndpoint ? RoundingMode.CEILING : RoundingMode.FLOOR;

        if (!exclusive) {
            BigDecimal normalized = isExact ? trimExcessFractionalZeros(bound, maxDigits) : bound;
            if (isExact && maxDigits > 0 && normalized.precision() > maxDigits) {
                normalized = normalized.round(new MathContext(maxDigits, directedMode));
            }
            String formatted = formatFloatCandidateLiteral(normalized, profile);
            BigDecimal reparsed = new BigDecimal(formatted);
            return isValidFloatCandidate(reparsed, profile) ? formatted : null;
        }

        int baseScale = bound.scale() < 0 ? bound.scale() + 1 : Math.max(bound.scale(), 1);
        for (int scale = baseScale; scale <= baseScale + 16; scale++) {
            BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(-scale);
            BigDecimal stepped = isLowerEndpoint ? bound.add(step) : bound.subtract(step);
            if (isExact) {
                stepped = trimExcessFractionalZeros(stepped, maxDigits);
            }
            if (maxDigits > 0 && stepped.precision() > maxDigits) {
                stepped = stepped.round(new MathContext(maxDigits, directedMode));
            }
            String formatted = formatFloatCandidateLiteral(stepped, profile);
            BigDecimal reparsed = new BigDecimal(formatted);
            if (isValidFloatCandidate(reparsed, profile)) {
                return formatted;
            }
        }
        return null;
    }

    private static BigDecimal trimExcessFractionalZeros(BigDecimal bd, int maxDigits) {
        if (maxDigits <= 0 || bd.precision() <= maxDigits || bd.scale() <= 1) {
            return bd;
        }
        BigDecimal current = bd;
        while (current.precision() > maxDigits && current.scale() > 1) {
            BigDecimal stripped = current.stripTrailingZeros();
            if (stripped.scale() == current.scale()) {
                break;
            }
            int nextScale = Math.max(1, current.scale() - (current.precision() - maxDigits));
            nextScale = Math.max(nextScale, stripped.scale());
            if (nextScale >= current.scale()) {
                break;
            }
            current = current.setScale(nextScale, RoundingMode.UNNECESSARY);
        }
        return current;
    }

    private static String formatFloatCandidateLiteral(BigDecimal bd, FloatConstraintProfile profile) {
        if (profile.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
            return formatExactDecimalLiteral(bd, profile.exactMaxDigits());
        }
        String sciOrPlain = bd.toString().replace('E', 'e');
        if (sciOrPlain.contains(".")) {
            return sciOrPlain;
        }
        int eIdx = sciOrPlain.indexOf('e');
        if (eIdx > 0) {
            return sciOrPlain.substring(0, eIdx) + ".0" + sciOrPlain.substring(eIdx);
        }
        return sciOrPlain + ".0";
    }

    private static Set<String> addFloatClampFixesIfOutOfBounds(
        List<PayloadValueQuickFix> fixes,
        Value value,
        String rawValueText,
        FloatConstraintProfile profile,
        PriorityAction.Priority nearestPriority,
        PriorityAction.Priority oppositePriority
    ) {
        BigDecimal actual = StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(value, rawValueText).orElse(null);
        if (actual == null) {
            return Set.of();
        }
        boolean ieeePhysicalOverflow = profile.representation() != StvnSchema.FloatRepresentation.EXACT_DECIMAL
            && (profile.representation() == StvnSchema.FloatRepresentation.IEEE_32
                ? (actual.compareTo(StvnVocabulary.FLOAT32_MIN) < 0 || actual.compareTo(StvnVocabulary.FLOAT32_MAX) > 0)
                : (actual.compareTo(StvnVocabulary.FLOAT64_MIN) < 0 || actual.compareTo(StvnVocabulary.FLOAT64_MAX) > 0));
        boolean belowLower = profile.lowerBound() != null
            && (profile.lowerExclusive()
                ? actual.compareTo(profile.lowerBound()) <= 0
                : actual.compareTo(profile.lowerBound()) < 0);
        boolean aboveUpper = profile.upperBound() != null
            && (profile.upperExclusive()
                ? actual.compareTo(profile.upperBound()) >= 0
                : actual.compareTo(profile.upperBound()) > 0);
        if (!belowLower && !aboveUpper && !ieeePhysicalOverflow) {
            return Set.of();
        }

        EffectiveFloatClampEndpoints endpoints = computeFloatClampEndpoints(profile, ieeePhysicalOverflow);
        String minLit = endpoints.minLiteral();
        String maxLit = endpoints.maxLiteral();
        if (minLit == null && maxLit == null) {
            return Set.of();
        }

        boolean minFirst;
        if (belowLower || (ieeePhysicalOverflow && actual.signum() < 0)) {
            minFirst = true;
        } else if (aboveUpper || (ieeePhysicalOverflow && actual.signum() > 0)) {
            minFirst = false;
        } else {
            minFirst = minLit != null && maxLit == null;
        }

        Set<String> offered = new LinkedHashSet<>(2);
        if (minFirst) {
            if (minLit != null) {
                fixes.add(new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + minLit + "'",
                    "STVN Scalar Bound Clamping",
                    minLit,
                    null,
                    nearestPriority
                ));
                offered.add(minLit);
            }
            if (maxLit != null && !maxLit.equals(minLit)) {
                fixes.add(new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + maxLit + "'",
                    "STVN Scalar Bound Clamping",
                    maxLit,
                    null,
                    minLit != null ? oppositePriority : nearestPriority
                ));
                offered.add(maxLit);
            }
        } else {
            if (maxLit != null) {
                fixes.add(new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + maxLit + "'",
                    "STVN Scalar Bound Clamping",
                    maxLit,
                    null,
                    nearestPriority
                ));
                offered.add(maxLit);
            }
            if (minLit != null && !minLit.equals(maxLit)) {
                fixes.add(new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + minLit + "'",
                    "STVN Scalar Bound Clamping",
                    minLit,
                    null,
                    maxLit != null ? oppositePriority : nearestPriority
                ));
                offered.add(minLit);
            }
        }
        return offered;
    }

    private static @Nullable String computeSchemaValidFloatExemplar(FloatConstraintProfile profile) {
        BigDecimal zero = new BigDecimal("0.0");
        if (isValidFloatCandidate(zero, profile)) {
            return "0.0";
        }
        EffectiveFloatClampEndpoints endpoints = computeFloatClampEndpoints(profile, false);
        if (endpoints.minLiteral() != null) {
            return endpoints.minLiteral();
        }
        return endpoints.maxLiteral();
    }

    private static boolean isPayloadValueDiagnostic(String code, String message) {
        if (code.startsWith("TEMPORAL_")
                || code.startsWith("DATETIME_")
                || code.equals("ERR_TYPE_MISMATCH")
                || code.equals("E028")
                || code.equals("ERR_CONSTRAINT_VIOLATION")
                || code.equals(DiagnosticBag.ERR_CAPACITY_OVERFLOW)
                || code.equals("E012")
                || code.equals("ERR_INTEGER_OVERFLOW")
                || code.equals("E009")
                || code.equals("ERR_FLOAT_OVERFLOW")
                || code.equals("E010")
                || code.equals("ERR_INVALID_DATETIME_LITERAL")
                || code.equals("E011")
                || code.equals("ERR_ENUM_VARIANT_NOT_IN_SUBSET")
                || code.equals(DiagnosticBag.ERR_EITHER_LEFT_MISSING_TAG)
                || code.equals("EITHER_LEFT_MISSING_TAG")
                || code.equals("E023")) {
            return true;
        }
        return message.contains("Type mismatch")
            || message.contains("Constraint violation")
            || message.contains("Rule E Violation")
            || message.contains("explicit #Left tag is required")
            || message.contains("Mantissa precision ")
            || message.contains("significant digits")
            || message.contains("out of bounds")
            || message.contains("out of range")
            || message.contains("overflow")
            || message.contains("below minimum")
            || message.contains("exceeds maximum")
            || message.contains("meets or exceeds")
            || message.contains("IANA time zone")
            || message.contains("UTC offset")
            || message.contains("Invalid enum value")
            || message.contains("Invalid {");
    }

    private static @Nullable PsiElement findEnclosingMapEntry(@Nullable PsiElement elem) {
        PsiElement current = elem;
        while (current != null && !(current instanceof PsiFile)) {
            if (current.getParent() instanceof MapLiteral
                    && (current instanceof Value || current instanceof ListLiteral)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String formatExactDecimalLiteral(BigDecimal bd, int maxDigits) {
        String plain = bd.toPlainString();
        if (plain.contains(".")) {
            return plain;
        }
        if (maxDigits <= 0 || bd.precision() < maxDigits) {
            return plain + ".0";
        }
        boolean negative = bd.signum() < 0;
        String digits = bd.abs().unscaledValue().toString();
        int exp = bd.precision() - 1 - bd.scale();
        if (digits.length() >= 2) {
            return (negative ? "-" : "") + digits.charAt(0) + "." + digits.substring(1)
                + "e" + (exp >= 0 ? "+" : "") + exp;
        }
        if (maxDigits >= 2) {
            return (negative ? "-" : "") + digits + ".0e" + (exp >= 0 ? "+" : "") + exp;
        }
        return plain + ".0";
    }

    private record OptionalEpochInstant(Instant instant) {}

    private static @Nullable OptionalEpochInstant tryParseIntegerAsInstant(String raw) {
        BigInteger bi = StvnPsiLiteralEvaluator.parseBigInteger(raw).orElse(null);
        if (bi == null || bi.compareTo(BigInteger.ZERO) < 0) {
            return null;
        }
        try {
            long val = bi.longValueExact();
            Instant inst;
            if (val >= 100_000_000_000_000_000L) {
                inst = Instant.ofEpochSecond(val / 1_000_000_000L, val % 1_000_000_000L);
            } else if (val >= 100_000_000_000_000L) {
                inst = Instant.ofEpochSecond(val / 1_000_000L, (val % 1_000_000L) * 1_000L);
            } else if (val >= 100_000_000_000L) {
                inst = Instant.ofEpochMilli(val);
            } else {
                inst = Instant.ofEpochSecond(val);
            }
            return new OptionalEpochInstant(inst);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static String formatInstantForDateTimeMode(Instant instant, DateTimeMode mode, ZoneId zone) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, zone);
        String localStr = zdt.toLocalDateTime().toString();
        String offsetStr = formatOffsetForMode(zdt.getOffset());
        return switch (mode) {
            case OFFSET -> "\"" + localStr + offsetStr + "\"";
            case ZONED -> "\"" + localStr + "[" + zone.getId() + "]\"";
            case AUDITED -> "\"" + localStr + offsetStr + "[" + zone.getId() + "]\"";
        };
    }

    private static @Nullable Instant parseAnyIsoTimestampToInstant(String clean, ZoneId fallbackZone) {
        Matcher matcher = TEMPORAL_COMPONENTS_PATTERN.matcher(clean);
        if (!matcher.matches()) {
            return null;
        }
        String localPart = matcher.group(1).replace(' ', 'T');
        String offsetPart = matcher.group(2);
        String zonePart = matcher.group(3);
        try {
            LocalDateTime ldt = LocalDateTime.parse(localPart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (zonePart != null && !zonePart.isBlank()) {
                ZoneId zone = ZoneId.of(zonePart.trim());
                ZoneOffset preferred = (offsetPart != null && !offsetPart.isBlank())
                    ? ZoneOffset.of(offsetPart.trim())
                    : null;
                return ZonedDateTime.ofLocal(ldt, zone, preferred).toInstant();
            }
            if (offsetPart != null && !offsetPart.isBlank()) {
                return OffsetDateTime.of(ldt, ZoneOffset.of(offsetPart.trim())).toInstant();
            }
            return ldt.atZone(fallbackZone).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatOffsetForMode(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "Z" : offset.getId();
    }

    private static @Nullable String stripQuotesOrFences(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"\"\"") && trimmed.endsWith("\"\"\"") && trimmed.length() >= 6) {
            String inner = trimmed.substring(3, trimmed.length() - 3).trim();
            int firstNewline = inner.indexOf('\n');
            if (firstNewline >= 0) {
                inner = inner.substring(firstNewline + 1).trim();
            }
            int lastNewline = inner.lastIndexOf('\n');
            if (lastNewline >= 0 && inner.substring(lastNewline + 1).trim().startsWith("[")) {
                inner = inner.substring(0, lastNewline).trim();
            }
            return inner;
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return null;
    }

    private static @Nullable String resolveStringOrConstOrNull(@Nullable PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveStringOrConst(context, raw, false)
            .orElseGet(() -> stripQuotesOrFences(raw));
    }

    private static @Nullable Value findTargetValue(PsiFile file, int startOffset, int endOffset) {
        PsiElement elem = file.findElementAt(startOffset);
        if (elem == null && startOffset > 0) {
            elem = file.findElementAt(startOffset - 1);
        }
        if (elem == null) {
            return null;
        }
        if (elem instanceof Value v) {
            return v;
        }
        if (PsiTreeUtil.getParentOfType(elem, com.intellij.psi.PsiErrorElement.class, false) != null) {
            return null;
        }
        Value parentVal = PsiTreeUtil.getParentOfType(elem, Value.class, false);
        if (parentVal != null
                && parentVal.getCollectionValue() != null
                && parentVal.getTextRange().getStartOffset() < startOffset) {
            return null;
        }
        return parentVal;
    }

    private static int parseIntOrDefault(@Nullable PsiElement context, @Nullable String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, raw)
            .map(BigInteger::intValue)
            .orElse(defaultValue);
    }

    private static @Nullable BigInteger parseBigIntOrNull(@Nullable PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, raw).orElse(null);
    }

    /**
     * Unified {@link IntentionAction} and {@link LocalQuickFix} that replaces an invalid STVN
     * {@link Value} node with a converted, clamped, or exemplar literal.
     */
    public static final class PayloadValueQuickFix implements IntentionAction, LocalQuickFix, PriorityAction {

        @SafeFieldForPreview
        private final SmartPsiElementPointer<Value> targetPointer;
        @SafeFieldForPreview
        private final String text;
        @SafeFieldForPreview
        private final String familyName;
        @SafeFieldForPreview
        private final String fallbackReplacement;
        @SafeFieldForPreview
        private final @Nullable Supplier<String> dynamicReplacementSupplier;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Creates a {@link PayloadValueQuickFix} with {@link Priority#HIGH}.
         *
         * @param target                     the PSI {@link Value} element to replace
         * @param text                       the user-facing quick-fix label
         * @param familyName                 the intention family name
         * @param fallbackReplacement        the replacement literal text
         * @param dynamicReplacementSupplier optional live supplier for clock-dependent exemplars
         */
        public PayloadValueQuickFix(
            Value target,
            String text,
            String familyName,
            String fallbackReplacement,
            @Nullable Supplier<String> dynamicReplacementSupplier
        ) {
            this(target, text, familyName, fallbackReplacement, dynamicReplacementSupplier, Priority.HIGH);
        }

        /**
         * Creates a {@link PayloadValueQuickFix} with an explicit {@link Priority}.
         *
         * @param target                     the PSI {@link Value} element to replace
         * @param text                       the user-facing quick-fix label
         * @param familyName                 the intention family name
         * @param fallbackReplacement        the replacement literal text
         * @param dynamicReplacementSupplier optional live supplier for clock-dependent exemplars
         * @param priority                   the intention sorting priority
         */
        public PayloadValueQuickFix(
            Value target,
            String text,
            String familyName,
            String fallbackReplacement,
            @Nullable Supplier<String> dynamicReplacementSupplier,
            Priority priority
        ) {
            this.targetPointer = SmartPointerManager.getInstance(target.getProject()).createSmartPsiElementPointer(target);
            this.text = text;
            this.familyName = familyName;
            this.fallbackReplacement = fallbackReplacement;
            this.dynamicReplacementSupplier = dynamicReplacementSupplier;
            this.priority = priority;
        }

        @Override
        public Priority getPriority() {
            return priority;
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public String getName() {
            return text;
        }

        @Override
        public String getFamilyName() {
            return familyName;
        }

        @Override
        public boolean isAvailable(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            Value element = targetPointer.getElement();
            return element != null && element.isValid();
        }

        @Override
        public void invoke(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            Value element = targetPointer.getElement();
            if (element == null || !element.isValid()) {
                return;
            }
            PsiFile targetFile = file != null ? file : element.getContainingFile();
            String replacementText = dynamicReplacementSupplier != null
                ? dynamicReplacementSupplier.get()
                : fallbackReplacement;
            WriteCommandAction.runWriteCommandAction(
                project,
                text,
                null,
                () -> applyReplacement(project, targetFile, element, replacementText),
                targetFile
            );
        }

        @Override
        public void applyFix(Project project, ProblemDescriptor descriptor) {
            PsiElement psiElement = descriptor.getPsiElement();
            Value target = psiElement instanceof Value v
                ? v
                : PsiTreeUtil.getParentOfType(psiElement, Value.class, false);
            if (target == null) {
                target = targetPointer.getElement();
            }
            if (target == null || !target.isValid()) {
                return;
            }
            String replacementText = dynamicReplacementSupplier != null
                ? dynamicReplacementSupplier.get()
                : fallbackReplacement;
            applyReplacement(project, target.getContainingFile(), target, replacementText);
        }

        private static void applyReplacement(
            Project project,
            @Nullable PsiFile file,
            Value target,
            String replacementText
        ) {
            if (file != null) {
                var doc = file.getViewProvider().getDocument();
                if (doc != null) {
                    var range = target.getTextRange();
                    doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacementText);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            }
            Value newValue = StvnElementFactory.createValue(project, replacementText);
            if (target.isValid()) {
                target.replace(newValue);
            }
        }

        @Override
        public IntentionPreviewInfo generatePreview(Project project, Editor editor, PsiFile file) {
            Value orig = targetPointer.getElement();
            if (orig == null) {
                return IntentionPreviewInfo.EMPTY;
            }
            Value copyTarget = PsiTreeUtil.findSameElementInCopy(orig, file);
            if (copyTarget == null) {
                return IntentionPreviewInfo.EMPTY;
            }
            String replacementText = dynamicReplacementSupplier != null
                ? dynamicReplacementSupplier.get()
                : fallbackReplacement;
            Value newValue = StvnElementFactory.createValue(project, replacementText);
            copyTarget.replace(newValue);
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }

    /**
     * Intention and quick-fix that replaces an exact character range in a document.
     */
    private static final class TextRangeReplacementQuickFix implements IntentionAction, LocalQuickFix, PriorityAction {

        private final SmartPsiElementPointer<PsiFile> filePointer;
        private final int startOffset;
        private final int endOffset;
        private final String replacement;
        private final String label;

        TextRangeReplacementQuickFix(
            PsiFile file,
            int startOffset,
            int endOffset,
            String replacement,
            String label
        ) {
            this.filePointer = SmartPointerManager.getInstance(file.getProject()).createSmartPsiElementPointer(file);
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.replacement = replacement;
            this.label = label;
        }

        @Override
        public Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public String getText() {
            return label;
        }

        @Override
        public String getName() {
            return label;
        }

        @Override
        public String getFamilyName() {
            return label;
        }

        @Override
        public boolean isAvailable(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            PsiFile targetFile = file != null ? file : filePointer.getElement();
            return targetFile != null && targetFile.isValid() && endOffset <= targetFile.getTextLength();
        }

        @Override
        public void invoke(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            PsiFile targetFile = file != null ? file : filePointer.getElement();
            if (targetFile == null || !targetFile.isValid()) {
                return;
            }
            WriteCommandAction.runWriteCommandAction(
                project,
                label,
                null,
                () -> applyToDocument(project, targetFile),
                targetFile
            );
        }

        @Override
        public void applyFix(Project project, ProblemDescriptor descriptor) {
            PsiFile targetFile = descriptor.getPsiElement() != null
                ? descriptor.getPsiElement().getContainingFile()
                : filePointer.getElement();
            if (targetFile != null && targetFile.isValid()) {
                applyToDocument(project, targetFile);
            }
        }

        private void applyToDocument(Project project, PsiFile targetFile) {
            var doc = targetFile.getViewProvider().getDocument();
            if (doc != null && startOffset >= 0 && endOffset <= doc.getTextLength() && startOffset <= endOffset) {
                doc.replaceString(startOffset, endOffset, replacement);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }

        @Override
        public IntentionPreviewInfo generatePreview(Project project, Editor editor, PsiFile file) {
            var doc = file.getViewProvider().getDocument();
            if (doc != null && startOffset >= 0 && endOffset <= doc.getTextLength() && startOffset <= endOffset) {
                doc.replaceString(startOffset, endOffset, replacement);
                return IntentionPreviewInfo.DIFF;
            }
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }

    /**
     * Intention and quick-fix that replaces a {@link MetadataEntry} node in {@code :defs} or {@code :type}.
     */
    private static final class MetadataEntryReplacementQuickFix implements IntentionAction, LocalQuickFix, PriorityAction {

        private final SmartPsiElementPointer<MetadataEntry> entryPointer;
        private final String replacementEntryText;
        private final String label;
        private final Priority priority;

        MetadataEntryReplacementQuickFix(
            MetadataEntry entry,
            String replacementEntryText,
            String label
        ) {
            this(entry, replacementEntryText, label, Priority.TOP);
        }

        MetadataEntryReplacementQuickFix(
            MetadataEntry entry,
            String replacementEntryText,
            String label,
            Priority priority
        ) {
            this.entryPointer = SmartPointerManager.getInstance(entry.getProject()).createSmartPsiElementPointer(entry);
            this.replacementEntryText = replacementEntryText;
            this.label = label;
            this.priority = priority;
        }

        @Override
        public Priority getPriority() {
            return priority;
        }

        @Override
        public String getText() {
            return label;
        }

        @Override
        public String getName() {
            return label;
        }

        @Override
        public String getFamilyName() {
            return label;
        }

        @Override
        public boolean isAvailable(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            MetadataEntry entry = entryPointer.getElement();
            return entry != null && entry.isValid();
        }

        @Override
        public void invoke(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            MetadataEntry entry = entryPointer.getElement();
            if (entry == null || !entry.isValid()) {
                return;
            }
            PsiFile targetFile = file != null ? file : entry.getContainingFile();
            WriteCommandAction.runWriteCommandAction(
                project,
                label,
                null,
                () -> {
                    var replacement = StvnElementFactory.createMetadataEntry(project, replacementEntryText);
                    if (entry.isValid()) {
                        entry.replace(replacement);
                    }
                },
                targetFile
            );
        }

        @Override
        public void applyFix(Project project, ProblemDescriptor descriptor) {
            MetadataEntry entry = entryPointer.getElement();
            if (entry != null && entry.isValid()) {
                var replacement = StvnElementFactory.createMetadataEntry(project, replacementEntryText);
                entry.replace(replacement);
            }
        }

        @Override
        public IntentionPreviewInfo generatePreview(Project project, Editor editor, PsiFile file) {
            MetadataEntry orig = entryPointer.getElement();
            if (orig == null) {
                return IntentionPreviewInfo.EMPTY;
            }
            MetadataEntry copy = PsiTreeUtil.findSameElementInCopy(orig, file);
            if (copy == null) {
                return IntentionPreviewInfo.EMPTY;
            }
            copy.replace(StvnElementFactory.createMetadataEntry(project, replacementEntryText));
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }

    /**
     * Intention and quick-fix that removes a duplicate or excess container element or map entry.
     */
    private static final class RemoveContainerElementQuickFix implements IntentionAction, LocalQuickFix, PriorityAction {

        private final SmartPsiElementPointer<PsiElement> elementPointer;
        private final String label;

        RemoveContainerElementQuickFix(PsiElement element, String label) {
            this.elementPointer = SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
            this.label = label;
        }

        @Override
        public Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public String getText() {
            return label;
        }

        @Override
        public String getName() {
            return label;
        }

        @Override
        public String getFamilyName() {
            return label;
        }

        @Override
        public boolean isAvailable(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            PsiElement elem = elementPointer.getElement();
            return elem != null && elem.isValid();
        }

        @Override
        public void invoke(Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            PsiElement elem = elementPointer.getElement();
            if (elem == null || !elem.isValid()) {
                return;
            }
            PsiFile targetFile = file != null ? file : elem.getContainingFile();
            WriteCommandAction.runWriteCommandAction(
                project,
                label,
                null,
                () -> deleteElementCleanly(project, targetFile, elem),
                targetFile
            );
        }

        @Override
        public void applyFix(Project project, ProblemDescriptor descriptor) {
            PsiElement elem = elementPointer.getElement();
            if (elem != null && elem.isValid()) {
                deleteElementCleanly(project, elem.getContainingFile(), elem);
            }
        }

        private static void deleteElementCleanly(
            Project project,
            @Nullable PsiFile file,
            PsiElement elem
        ) {
            if (file != null) {
                var doc = file.getViewProvider().getDocument();
                if (doc != null) {
                    CharSequence chars = doc.getCharsSequence();
                    var range = elem.getTextRange();
                    int delStart = range.getStartOffset();
                    int delEnd = range.getEndOffset();
                    int scanBack = delStart;
                    while (scanBack > 0 && Character.isWhitespace(chars.charAt(scanBack - 1))) {
                        scanBack--;
                    }
                    if (scanBack < delStart && scanBack > 0
                            && chars.charAt(scanBack - 1) != '['
                            && chars.charAt(scanBack - 1) != '{') {
                        delStart = scanBack;
                    } else {
                        while (delEnd < chars.length() && (chars.charAt(delEnd) == ' ' || chars.charAt(delEnd) == '\t')) {
                            delEnd++;
                        }
                    }
                    doc.deleteString(delStart, delEnd);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            }
            if (elem.isValid()) {
                elem.delete();
            }
        }

        @Override
        public IntentionPreviewInfo generatePreview(Project project, Editor editor, PsiFile file) {
            PsiElement orig = elementPointer.getElement();
            if (orig == null) {
                return IntentionPreviewInfo.EMPTY;
            }
            PsiElement copy = PsiTreeUtil.findSameElementInCopy(orig, file);
            if (copy == null) {
                return IntentionPreviewInfo.EMPTY;
            }
            copy.delete();
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }
}
