package org.stvnadore.plugin.validation.quickfix;

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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.SchemaType;
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
                BigInteger actual = StvnPsiLiteralEvaluator.parseBigInteger(
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
     * @param value      the offending PSI {@link Value} node
     * @param code       the diagnostic code
     * @param message    the diagnostic message
     * @param clock      the clock used for live temporal exemplars
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

        String baseType = terminalSchema != null ? extractBaseType(terminalSchema, irSchema) : null;
        if (baseType == null && value.getFloatLiteral() != null) {
            baseType = StvnVocabulary.TYPE_FLOAT;
        }
        if (baseType == null) {
            return List.of();
        }

        List<PayloadValueQuickFix> fixes = new ArrayList<>();
        String rawValueText = value.getText().trim();

        switch (baseType) {
            case StvnVocabulary.TYPE_DATE_TIME -> {
                DateTimeMode mode = resolveDateTimeMode(meta, irSchema);
                String modeFlag = switch (mode) {
                    case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
                    case ZONED -> StvnVocabulary.FACET_KW_ZONED;
                    case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
                };
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
                String minIncl = (irSchema instanceof StvnSchema.DateTimeSchema dt && dt.explicitMinInclIso().isPresent())
                    ? dt.explicitMinInclIso().get()
                    : meta.get(StvnVocabulary.FACET_KW_MIN_INCL);
                String maxExcl = (irSchema instanceof StvnSchema.DateTimeSchema dt && dt.explicitMaxExclIso().isPresent())
                    ? dt.explicitMaxExclIso().get()
                    : meta.get(StvnVocabulary.FACET_KW_MAX_EXCL);
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
                    meta.get(StvnVocabulary.FACET_KW_SIZE),
                    scale.bitWidth().intValue()
                );
                BigInteger minIncl = (irSchema instanceof StvnSchema.TimeEpochSchema ep && ep.explicitMinIncl().isPresent())
                    ? ep.explicitMinIncl().get()
                    : parseBigIntOrNull(meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                BigInteger maxExcl = (irSchema instanceof StvnSchema.TimeEpochSchema ep && ep.explicitMaxExcl().isPresent())
                    ? ep.explicitMaxExcl().get()
                    : parseBigIntOrNull(meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));

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

                addIntegerClampFixIfOutOfBounds(fixes, value, rawValueText, unsigned, sizeBits, minIncl, maxExcl);

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
                int sizeBits = parseIntOrDefault(meta.get(StvnVocabulary.FACET_KW_SIZE), defaultBits);
                BigInteger minIncl = (irSchema instanceof StvnSchema.IntSchema is && is.explicitMinIncl().isPresent())
                    ? is.explicitMinIncl().get()
                    : parseBigIntOrNull(meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                BigInteger maxExcl = (irSchema instanceof StvnSchema.IntSchema is && is.explicitMaxExcl().isPresent())
                    ? is.explicitMaxExcl().get()
                    : parseBigIntOrNull(meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));

                addIntegerClampFixIfOutOfBounds(fixes, value, rawValueText, unsigned, sizeBits, minIncl, maxExcl);

                String exemplar = StvnExemplarValueGenerator.generateIntExemplar(unsigned, sizeBits, minIncl, maxExcl);
                if (!exemplar.equals(rawValueText)) {
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
                Double minIncl = parseDoubleOrNull(meta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                Double minExcl = parseDoubleOrNull(meta.get(StvnVocabulary.FACET_KW_MIN_EXCL));
                Double maxIncl = parseDoubleOrNull(meta.get(StvnVocabulary.FACET_KW_MAX_INCL));
                Double maxExcl = parseDoubleOrNull(meta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
                if (irSchema instanceof StvnSchema.FloatSchema fs) {
                    if (fs.lowerBound().isPresent()) {
                        var lb = fs.lowerBound().get();
                        if (lb.kind() == StvnSchema.BoundKind.INCLUSIVE) {
                            minIncl = lb.value().doubleValue();
                        } else {
                            minExcl = lb.value().doubleValue();
                        }
                    }
                    if (fs.upperBound().isPresent()) {
                        var ub = fs.upperBound().get();
                        if (ub.kind() == StvnSchema.BoundKind.INCLUSIVE) {
                            maxIncl = ub.value().doubleValue();
                        } else {
                            maxExcl = ub.value().doubleValue();
                        }
                    }
                }
                if (value.getIntegerLiteral() != null) {
                    String rawIntText = value.getIntegerLiteral().getText().trim();
                    String appended = rawIntText + ".0";
                    if (isFloatWithinBounds(appended, minIncl, minExcl, maxIncl, maxExcl)) {
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
                if (value.getFloatLiteral() != null) {
                    int maxDigits = -1;
                    Matcher sigMatcher = SIGNIFICANT_DIGITS_MAX_PATTERN.matcher(message);
                    if (sigMatcher.find()) {
                        maxDigits = parseIntOrDefault(sigMatcher.group(1), -1);
                    }
                    if (maxDigits <= 0 && irSchema instanceof StvnSchema.FloatSchema fs
                            && fs.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
                        maxDigits = fs.exactMaxDigits().intValue();
                    }
                    if (maxDigits <= 0 && (meta.containsKey(StvnVocabulary.FACET_KW_EXACT)
                            || meta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE))) {
                        maxDigits = parseIntOrDefault(meta.get(StvnVocabulary.FACET_KW_MAX_SIZE), -1);
                    }
                    if (maxDigits > 0) {
                        BigDecimal bd = StvnPsiLiteralEvaluator.parseBigDecimal(rawValueText).orElse(null);
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
                                    PriorityAction.Priority.HIGH
                                )
                            );
                        }
                    }
                }
                addFloatClampFixIfOutOfBounds(fixes, value, rawValueText, minIncl, minExcl, maxIncl, maxExcl);
                String exemplar = StvnExemplarValueGenerator.generateFloatExemplar(minIncl, minExcl, maxIncl, maxExcl);
                if (!exemplar.equals(rawValueText)) {
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
            case StvnVocabulary.TYPE_STRING, StvnVocabulary.TYPE_ENUM -> {
                String exemplar = StvnExemplarValueGenerator.generateExemplar(rawSchema, irSchema, clock);
                if (exemplar != null && !exemplar.equals(rawValueText)) {
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
            default -> {
            }
        }
        return fixes;
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
            var target = StvnTypeReference.resolveTypeInFile(
                schemaType.getContainingFile(),
                kw.getText().trim(),
                new HashSet<>()
            );
            var td = StvnPsiUtils.getParentTypeDefinition(target);
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

    private static void addIntegerClampFixIfOutOfBounds(
        List<PayloadValueQuickFix> fixes,
        Value value,
        String rawValueText,
        boolean unsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl
    ) {
        BigInteger actual = StvnPsiLiteralEvaluator.parseBigInteger(rawValueText).orElse(null);
        if (actual == null) {
            return;
        }
        BigInteger[] bounds = StvnExemplarValueGenerator.computeEffectiveIntegerBounds(
            unsigned,
            sizeBits,
            minIncl,
            maxExcl
        );
        BigInteger effMin = bounds[0];
        BigInteger effMaxIncl = bounds[1];

        if (actual.compareTo(effMin) < 0) {
            String clamped = effMin.toString();
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + clamped + "'",
                    "STVN Scalar Bound Clamping",
                    clamped,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        } else if (actual.compareTo(effMaxIncl) > 0) {
            String clamped = effMaxIncl.toString();
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + clamped + "'",
                    "STVN Scalar Bound Clamping",
                    clamped,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        }
    }

    private static boolean isFloatWithinBounds(
        String rawValueText,
        @Nullable Double minIncl,
        @Nullable Double minExcl,
        @Nullable Double maxIncl,
        @Nullable Double maxExcl
    ) {
        Double actual = parseDoubleOrNull(rawValueText);
        if (actual == null || Double.isNaN(actual)) {
            return false;
        }
        if (minIncl != null && actual < minIncl) {
            return false;
        }
        if (minExcl != null && actual <= minExcl) {
            return false;
        }
        if (maxIncl != null && actual > maxIncl) {
            return false;
        }
        if (maxExcl != null && actual >= maxExcl) {
            return false;
        }
        return true;
    }

    private static void addFloatClampFixIfOutOfBounds(
        List<PayloadValueQuickFix> fixes,
        Value value,
        String rawValueText,
        @Nullable Double minIncl,
        @Nullable Double minExcl,
        @Nullable Double maxIncl,
        @Nullable Double maxExcl
    ) {
        Double actual = parseDoubleOrNull(rawValueText);
        if (actual == null || Double.isNaN(actual)) {
            return;
        }
        if (minIncl != null && actual < minIncl) {
            String clamped = formatFloatBound(minIncl);
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + clamped + "'",
                    "STVN Scalar Bound Clamping",
                    clamped,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        } else if (minExcl != null && actual <= minExcl) {
            String clamped = StvnExemplarValueGenerator.generateFloatExemplar(minIncl, minExcl, maxIncl, maxExcl);
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to minimum bound '" + clamped + "'",
                    "STVN Scalar Bound Clamping",
                    clamped,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        } else if (maxIncl != null && actual > maxIncl) {
            String clamped = formatFloatBound(maxIncl);
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + clamped + "'",
                    "STVN Scalar Bound Clamping",
                    clamped,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        } else if (maxExcl != null && actual >= maxExcl) {
            String clamped = StvnExemplarValueGenerator.generateFloatExemplar(minIncl, minExcl, maxIncl, maxExcl);
            fixes.add(
                new PayloadValueQuickFix(
                    value,
                    "Clamp value to maximum bound '" + clamped + "'",
                    "STVN Scalar Bound Clamping",
                    clamped,
                    null,
                    PriorityAction.Priority.TOP
                )
            );
        }
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
                || code.equals("ERR_ENUM_VARIANT_NOT_IN_SUBSET")) {
            return true;
        }
        return message.contains("Type mismatch")
            || message.contains("Constraint violation")
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
        if (bd.precision() < maxDigits) {
            return plain + ".0";
        }
        String sci = bd.toString();
        if (sci.contains(".") || sci.contains("E") || sci.contains("e")) {
            return sci;
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

    private static @Nullable String stripQuotesOrFences(String raw) {
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

    private static String formatFloatBound(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.format(java.util.Locale.ROOT, "%.1f", value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static int parseIntOrDefault(@Nullable String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static @Nullable BigInteger parseBigIntOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.parseBigInteger(raw.trim()).orElse(null);
    }

    private static @Nullable Double parseDoubleOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Unified {@link IntentionAction} and {@link LocalQuickFix} that replaces an invalid STVN
     * {@link Value} node with a converted, clamped, or exemplar literal.
     */
    public static final class PayloadValueQuickFix implements IntentionAction, LocalQuickFix, PriorityAction {

        private final SmartPsiElementPointer<Value> targetPointer;
        private final String text;
        private final String familyName;
        private final String fallbackReplacement;
        private final @Nullable Supplier<String> dynamicReplacementSupplier;
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
