package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.IntentionAction;
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
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
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
        if (rawSchema == null) {
            return List.of();
        }
        SchemaType terminalSchema = StvnExemplarValueGenerator.resolveTerminalSchemaType(rawSchema);
        Map<String, String> meta = StvnExemplarValueGenerator.extractLineageMetadata(rawSchema);
        StvnSchema irSchema = resolveLeafIrSchema(rawSchema);

        String baseType = extractBaseType(terminalSchema, irSchema);
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
                            null
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
                        () -> StvnExemplarValueGenerator.generateDateTimeExemplar(mode, minIncl, maxExcl, clock)
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
                            null
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
                        )
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
                            null
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
                                null
                            )
                        );
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
                            null
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
                            null
                        )
                    );
                } else if (clean.equalsIgnoreCase("false") || clean.equals("0")) {
                    fixes.add(
                        new PayloadValueQuickFix(
                            value,
                            "Convert to boolean literal '" + StvnVocabulary.VAL_FALSE + "'",
                            "STVN Scalar Conversion",
                            StvnVocabulary.VAL_FALSE,
                            null
                        )
                    );
                }
                fixes.add(
                    new PayloadValueQuickFix(
                        value,
                        "Replace with exemplar value '" + StvnVocabulary.VAL_TRUE + "'",
                        "STVN Exemplar Value Replacement",
                        StvnVocabulary.VAL_TRUE,
                        null
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
                            null
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
                    null
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
                    null
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
                    null
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
                    null
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
                    null
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
                    null
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
        return PsiTreeUtil.getParentOfType(elem, Value.class, false);
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
    public static final class PayloadValueQuickFix implements IntentionAction, LocalQuickFix {

        private final SmartPsiElementPointer<Value> targetPointer;
        private final String text;
        private final String familyName;
        private final String fallbackReplacement;
        private final @Nullable Supplier<String> dynamicReplacementSupplier;

        /**
         * Creates a {@link PayloadValueQuickFix}.
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
            this.targetPointer = SmartPointerManager.getInstance(target.getProject()).createSmartPsiElementPointer(target);
            this.text = text;
            this.familyName = familyName;
            this.fallbackReplacement = fallbackReplacement;
            this.dynamicReplacementSupplier = dynamicReplacementSupplier;
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
}
