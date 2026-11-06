package org.stvnadore.plugin.documentation;

import com.intellij.psi.util.PsiTreeUtil;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.ValueKeyword;

/**
 * Generates deterministic or live-clock STVN exemplar literals that satisfy all active metadata
 * facets of a governing {@link SchemaType} or lowered {@link StvnSchema}.
 */
@NullMarked
public final class StvnExemplarValueGenerator {

    private static final DateTimeFormatter LOCAL_SECONDS_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private StvnExemplarValueGenerator() {}

    /**
     * Generates a schema-compliant STVN exemplar literal using the system default clock.
     *
     * @param schemaType the PSI schema type node, if available
     * @param irSchema   the lowered compiler IR schema, if available
     * @return a valid STVN literal string, or {@code null} if no schema information is available
     */
    public static @Nullable String generateExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema
    ) {
        return generateExemplar(schemaType, irSchema, Clock.systemDefaultZone(), 0);
    }

    /**
     * Generates a schema-compliant STVN exemplar literal using the supplied {@link Clock}.
     *
     * @param schemaType the PSI schema type node, if available
     * @param irSchema   the lowered compiler IR schema, if available
     * @param clock      the clock used for {@code :DateTime} and {@code :TimeEpoch} generation
     * @return a valid STVN literal string, or {@code null} if no schema information is available
     */
    public static @Nullable String generateExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        Clock clock
    ) {
        return generateExemplar(schemaType, irSchema, clock, 0);
    }

    private static @Nullable String generateExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        Clock clock,
        int depth
    ) {
        if (depth > 8) {
            return null;
        }

        StvnSchema effectiveIr = irSchema;
        while (effectiveIr instanceof StvnSchema.PoisonedSchema poisoned) {
            effectiveIr = poisoned.bestEffortFallback().orElse(null);
        }

        Map<String, String> psiMeta = schemaType != null ? extractLineageMetadata(schemaType) : Map.of();
        SchemaType terminalPsi = schemaType != null ? resolveTerminalSchemaType(schemaType) : null;

        // 1. Check lowered IR schema first when it matches a leaf scalar or enum
        if (effectiveIr instanceof StvnSchema.DateTimeSchema dtSchema) {
            DateTimeMode mode = psiMeta.containsKey(StvnVocabulary.FACET_KW_ZONED)
                ? DateTimeMode.ZONED
                : psiMeta.containsKey(StvnVocabulary.FACET_KW_AUDITED)
                    ? DateTimeMode.AUDITED
                    : psiMeta.containsKey(StvnVocabulary.FACET_KW_OFFSET)
                        ? DateTimeMode.OFFSET
                        : dtSchema.mode();
            String minIncl = dtSchema.explicitMinInclIso().orElse(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
            String maxExcl = dtSchema.explicitMaxExclIso().orElse(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
            return generateDateTimeExemplar(mode, minIncl, maxExcl, clock);
        }
        if (effectiveIr instanceof StvnSchema.TimeEpochSchema epSchema) {
            TemporalScale scale = hasExplicitScaleInMeta(psiMeta)
                ? resolveEpochScaleFromMeta(psiMeta)
                : epSchema.scale();
            boolean unsigned = psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
            int defaultBits = scale.bitWidth().intValue();
            int sizeBits = parseIntOrDefault(psiMeta.get(StvnVocabulary.FACET_KW_SIZE), defaultBits);
            BigInteger minIncl = epSchema.explicitMinIncl()
                .orElseGet(() -> parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)));
            BigInteger maxExcl = epSchema.explicitMaxExcl()
                .orElseGet(() -> parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)));
            return generateTimeEpochExemplar(scale, unsigned, sizeBits, minIncl, maxExcl, clock);
        }
        if (effectiveIr instanceof StvnSchema.IntSchema intSchema) {
            boolean unsigned = intSchema.unsigned() || psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
            int sizeBits = psiMeta.containsKey(StvnVocabulary.FACET_KW_SIZE)
                ? parseIntOrDefault(psiMeta.get(StvnVocabulary.FACET_KW_SIZE), intSchema.bitWidth().intValue())
                : intSchema.bitWidth().intValue();
            BigInteger minIncl = intSchema.explicitMinIncl()
                .orElseGet(() -> parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)));
            BigInteger maxExcl = intSchema.explicitMaxExcl()
                .orElseGet(() -> parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)));
            return generateIntExemplar(unsigned, sizeBits, minIncl, maxExcl);
        }
        if (effectiveIr instanceof StvnSchema.FloatSchema floatSchema) {
            Double minIncl = null;
            Double minExcl = null;
            Double maxIncl = null;
            Double maxExcl = null;
            if (floatSchema.lowerBound().isPresent()) {
                var lb = floatSchema.lowerBound().get();
                if (lb.kind() == StvnSchema.BoundKind.INCLUSIVE) {
                    minIncl = lb.value().doubleValue();
                } else {
                    minExcl = lb.value().doubleValue();
                }
            }
            if (floatSchema.upperBound().isPresent()) {
                var ub = floatSchema.upperBound().get();
                if (ub.kind() == StvnSchema.BoundKind.INCLUSIVE) {
                    maxIncl = ub.value().doubleValue();
                } else {
                    maxExcl = ub.value().doubleValue();
                }
            }
            if (minIncl == null && minExcl == null) {
                minIncl = parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                minExcl = parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL));
            }
            if (maxIncl == null && maxExcl == null) {
                maxIncl = parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL));
                maxExcl = parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
            }
            return generateFloatExemplar(minIncl, minExcl, maxIncl, maxExcl);
        }
        if (effectiveIr instanceof StvnSchema.StringSchema strSchema) {
            long minSize = psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE)
                ? parseLongOrDefault(psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE), strSchema.minSize().longValue())
                : strSchema.minSize().longValue();
            long maxSize = psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE)
                ? parseLongOrDefault(psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE), strSchema.maxSize().longValue())
                : strSchema.maxSize().longValue();
            String minIncl = strSchema.lowerBound()
                .map(StvnSchema.ContinuousBound::value)
                .orElseGet(() -> unquoteOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)));
            String maxExcl = strSchema.upperBound()
                .map(StvnSchema.ContinuousBound::value)
                .orElseGet(() -> unquoteOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)));
            return generateStringExemplar(minSize, maxSize, minIncl, maxExcl, strSchema.preserveIndent());
        }
        if (effectiveIr instanceof StvnSchema.BooleanSchema) {
            return StvnVocabulary.VAL_TRUE;
        }
        if (effectiveIr instanceof StvnSchema.EnumSubsetSchema subsetSchema) {
            return generateEnumSubsetExemplarFromIr(subsetSchema, psiMeta);
        }
        if (effectiveIr instanceof StvnSchema.EnumSchema enumSchema) {
            return generateEnumExemplarFromIr(enumSchema, psiMeta);
        }

        // 2. Fallback or composite traversal via PSI SchemaType
        if (terminalPsi != null) {
            var ctor = terminalPsi.getSchemaConstructor();
            if (ctor != null) {
                if (ctor.getAtomicType() != null) {
                    String baseType = ctor.getAtomicType().getText().trim();
                    return switch (baseType) {
                        case StvnVocabulary.TYPE_DATE_TIME -> {
                            DateTimeMode mode = resolveDateTimeModeFromMeta(psiMeta);
                            yield generateDateTimeExemplar(
                                mode,
                                psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL),
                                psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL),
                                clock
                            );
                        }
                        case StvnVocabulary.TYPE_TIME_EPOCH -> {
                            TemporalScale scale = resolveEpochScaleFromMeta(psiMeta);
                            boolean unsigned = psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                            int sizeBits = parseIntOrDefault(
                                psiMeta.get(StvnVocabulary.FACET_KW_SIZE),
                                scale.bitWidth().intValue()
                            );
                            BigInteger minIncl = parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                            BigInteger maxExcl = parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
                            yield generateTimeEpochExemplar(scale, unsigned, sizeBits, minIncl, maxExcl, clock);
                        }
                        case StvnVocabulary.TYPE_INT -> {
                            boolean unsigned = psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                            int sizeBits = parseIntOrDefault(
                                psiMeta.get(StvnVocabulary.FACET_KW_SIZE),
                                StvnVocabulary.DEFAULT_INT_BIT_WIDTH.intValue()
                            );
                            BigInteger minIncl = parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                            BigInteger maxExcl = parseBigIntOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
                            yield generateIntExemplar(unsigned, sizeBits, minIncl, maxExcl);
                        }
                        case StvnVocabulary.TYPE_FLOAT -> generateFloatExemplar(
                            parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)),
                            parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL)),
                            parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL)),
                            parseDoubleOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL))
                        );
                        case StvnVocabulary.TYPE_STRING -> generateStringExemplar(
                            parseLongOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE)),
                            parseLongOrNull(psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE)),
                            unquoteOrNull(
                                psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL)
                                    ? psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)
                                    : psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL)
                            ),
                            unquoteOrNull(
                                psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL)
                                    ? psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)
                                    : psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL)
                            ),
                            psiMeta.containsKey(StvnVocabulary.FACET_KW_PRESERVE_INDENT)
                        );
                        case StvnVocabulary.TYPE_BOOLEAN -> StvnVocabulary.VAL_TRUE;
                        default -> null;
                    };
                }
                if (ctor.getProductType() != null) {
                    List<SchemaType> slots = ctor.getProductType().getSchemaTypeList();
                    List<String> slotExemplars = new ArrayList<>(slots.size());
                    for (int i = 0; i < slots.size(); i++) {
                        StvnSchema slotIr = (effectiveIr instanceof StvnSchema.TupleSchema ts
                                && i < ts.fieldSchemas().size())
                            ? ts.fieldSchemas().get(i)
                            : null;
                        String sub = generateExemplar(slots.get(i), slotIr, clock, depth + 1);
                        slotExemplars.add(sub != null ? sub : "0");
                    }
                    return "(" + String.join(" ", slotExemplars) + ")";
                }
                if (ctor.getCollectionType() != null) {
                    var coll = ctor.getCollectionType();
                    String kind = coll.getFirstChild() != null ? coll.getFirstChild().getText().trim() : "";
                    long minSize = parseLongOrDefault(psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE), 0L);
                    var innerTypes = coll.getSchemaTypeList();
                    if (StvnVocabulary.TYPE_MAP.equals(kind)) {
                        if (minSize <= 0 || innerTypes.size() < 2) {
                            return "{}";
                        }
                        StvnSchema keyIr = effectiveIr instanceof StvnSchema.MapSchema ms ? ms.keySchema() : null;
                        StvnSchema valIr = effectiveIr instanceof StvnSchema.MapSchema ms ? ms.valueSchema() : null;
                        String k = generateExemplar(innerTypes.get(0), keyIr, clock, depth + 1);
                        String v = generateExemplar(innerTypes.get(1), valIr, clock, depth + 1);
                        return "{ [" + (k != null ? k : "\"key\"") + " " + (v != null ? v : "0") + "] }";
                    }
                    if (minSize <= 0 || innerTypes.isEmpty()) {
                        return "[]";
                    }
                    StvnSchema elemIr = effectiveIr instanceof StvnSchema.SeqSchema ss
                        ? ss.elementSchema()
                        : effectiveIr instanceof StvnSchema.SetSchema sets ? sets.elementSchema() : null;
                    String elem = generateExemplar(innerTypes.get(0), elemIr, clock, depth + 1);
                    return "[" + (elem != null ? elem : "0") + "]";
                }
                if (ctor.getSumType() != null) {
                    SumType sum = ctor.getSumType();
                    String sumText = sum.getText().trim();
                    if (sum.getEnumDef() != null || sumText.startsWith(StvnVocabulary.TYPE_ENUM)) {
                        return generateEnumExemplarFromPsi(sum, psiMeta);
                    }
                    List<SchemaType> branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (sumText.startsWith(StvnVocabulary.TYPE_OPTION)) {
                        if (branches.isEmpty()) {
                            return StvnVocabulary.VAL_NONE;
                        }
                        StvnSchema innerIr = effectiveIr instanceof StvnSchema.OptionSchema os ? os.valueSchema() : null;
                        String inner = generateExemplar(branches.get(0), innerIr, clock, depth + 1);
                        return inner != null ? StvnVocabulary.VAL_SOME + " " + inner : StvnVocabulary.VAL_NONE;
                    }
                    if (sumText.startsWith(StvnVocabulary.TYPE_EITHER)) {
                        if (branches.size() >= 2) {
                            StvnSchema rightIr = effectiveIr instanceof StvnSchema.EitherSchema es ? es.rightSchema() : null;
                            String right = generateExemplar(branches.get(1), rightIr, clock, depth + 1);
                            if (right != null) {
                                return StvnVocabulary.VAL_RIGHT + " " + right;
                            }
                        }
                        if (!branches.isEmpty()) {
                            StvnSchema leftIr = effectiveIr instanceof StvnSchema.EitherSchema es ? es.leftSchema() : null;
                            String left = generateExemplar(branches.get(0), leftIr, clock, depth + 1);
                            if (left != null) {
                                return StvnVocabulary.VAL_LEFT + " " + left;
                            }
                        }
                        return null;
                    }
                    if (sumText.startsWith(StvnVocabulary.TYPE_UNION) && !branches.isEmpty()) {
                        StvnSchema b0Ir = (effectiveIr instanceof StvnSchema.UnionSchema us && !us.branchSchemas().isEmpty())
                            ? us.branchSchemas().get(0)
                            : null;
                        String branch0 = generateExemplar(branches.get(0), b0Ir, clock, depth + 1);
                        return branch0 != null ? "#1 " + branch0 : null;
                    }
                }
            }
        }

        // 3. Pure-IR composite fallback when PSI SchemaType is null
        if (effectiveIr instanceof StvnSchema.TupleSchema ts) {
            List<String> slotExemplars = new ArrayList<>(ts.fieldSchemas().size());
            for (StvnSchema elem : ts.fieldSchemas()) {
                String sub = generateExemplar(null, elem, clock, depth + 1);
                slotExemplars.add(sub != null ? sub : "0");
            }
            return "(" + String.join(" ", slotExemplars) + ")";
        }
        if (effectiveIr instanceof StvnSchema.SeqSchema ss) {
            if (ss.minSize().compareTo(BigInteger.ZERO) <= 0) {
                return "[]";
            }
            String elem = generateExemplar(null, ss.elementSchema(), clock, depth + 1);
            return "[" + (elem != null ? elem : "0") + "]";
        }
        if (effectiveIr instanceof StvnSchema.SetSchema sets) {
            if (sets.minSize().compareTo(BigInteger.ZERO) <= 0) {
                return "[]";
            }
            String elem = generateExemplar(null, sets.elementSchema(), clock, depth + 1);
            return "[" + (elem != null ? elem : "0") + "]";
        }
        if (effectiveIr instanceof StvnSchema.MapSchema ms) {
            if (ms.minSize().compareTo(BigInteger.ZERO) <= 0) {
                return "{}";
            }
            String k = generateExemplar(null, ms.keySchema(), clock, depth + 1);
            String v = generateExemplar(null, ms.valueSchema(), clock, depth + 1);
            return "{ [" + (k != null ? k : "\"key\"") + " " + (v != null ? v : "0") + "] }";
        }
        if (effectiveIr instanceof StvnSchema.OptionSchema os) {
            String inner = generateExemplar(null, os.valueSchema(), clock, depth + 1);
            return inner != null ? StvnVocabulary.VAL_SOME + " " + inner : StvnVocabulary.VAL_NONE;
        }
        if (effectiveIr instanceof StvnSchema.EitherSchema es) {
            String right = generateExemplar(null, es.rightSchema(), clock, depth + 1);
            return right != null ? StvnVocabulary.VAL_RIGHT + " " + right : null;
        }
        if (effectiveIr instanceof StvnSchema.UnionSchema us && !us.branchSchemas().isEmpty()) {
            String b0 = generateExemplar(null, us.branchSchemas().get(0), clock, depth + 1);
            return b0 != null ? "#1 " + b0 : null;
        }

        return null;
    }

    /**
     * Generates a valid STVN {@code :DateTime} string literal (enclosed in double quotes) for the
     * designated {@link DateTimeMode}, clamped into {@code [#minIncl, #maxExcl)} if bounds are
     * present.
     *
     * @param mode    the target {@link DateTimeMode} ({@code #offset}, {@code #zoned}, or {@code #audited})
     * @param minIncl optional minimum inclusive ISO-8601 bound
     * @param maxExcl optional maximum exclusive ISO-8601 bound
     * @param clock   the clock supplying the current instant and timezone
     * @return the quoted STVN {@code :DateTime} literal
     */
    public static String generateDateTimeExemplar(
        DateTimeMode mode,
        @Nullable String minIncl,
        @Nullable String maxExcl,
        Clock clock
    ) {
        ZoneId zone = resolveExemplarZoneId(clock.getZone());
        Instant targetInstant = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);

        Instant minInstant = tryParseInstantBound(minIncl, zone);
        Instant maxInstant = tryParseInstantBound(maxExcl, zone);
        if (minInstant != null && targetInstant.isBefore(minInstant)) {
            targetInstant = minInstant;
        }
        if (maxInstant != null && !targetInstant.isBefore(maxInstant)) {
            Instant candidate = maxInstant.minusSeconds(1);
            targetInstant = (minInstant != null && candidate.isBefore(minInstant)) ? minInstant : candidate;
        }

        ZonedDateTime zdt = ZonedDateTime.ofInstant(targetInstant, zone).truncatedTo(ChronoUnit.SECONDS);
        return switch (mode) {
            case OFFSET -> {
                OffsetDateTime odt = zdt.toOffsetDateTime();
                String offsetStr = odt.getOffset().getTotalSeconds() == 0 ? "Z" : odt.getOffset().getId();
                String formatted = LOCAL_SECONDS_FORMATTER.format(odt.toLocalDateTime()) + offsetStr;
                yield "\"" + formatted + "\"";
            }
            case ZONED -> {
                String formatted = LOCAL_SECONDS_FORMATTER.format(zdt.toLocalDateTime()) + "[" + zone.getId() + "]";
                yield "\"" + formatted + "\"";
            }
            case AUDITED -> {
                String offsetStr = zdt.getOffset().getTotalSeconds() == 0 ? "Z" : zdt.getOffset().getId();
                String formatted = LOCAL_SECONDS_FORMATTER.format(zdt.toLocalDateTime())
                    + offsetStr
                    + "["
                    + zone.getId()
                    + "]";
                yield "\"" + formatted + "\"";
            }
        };
    }

    /**
     * Ensures that the returned {@link ZoneId} is a valid IANA timezone identifier (or {@code UTC})
     * rather than a bare fixed offset such as {@code +00:00} or {@code GMT+02:00}.
     *
     * @param candidate the candidate timezone
     * @return a region-based IANA {@link ZoneId} or {@code UTC}
     */
    public static ZoneId resolveExemplarZoneId(@Nullable ZoneId candidate) {
        if (candidate == null || candidate instanceof ZoneOffset) {
            return ZoneId.of("UTC");
        }
        String id = candidate.getId();
        if (id.equals("Z")
            || id.startsWith("+")
            || id.startsWith("-")
            || id.startsWith("GMT+")
            || id.startsWith("GMT-")
            || id.startsWith("UTC+")
            || id.startsWith("UTC-")
            || id.startsWith("UT+")
            || id.startsWith("UT-")) {
            return ZoneId.of("UTC");
        }
        return candidate;
    }

    /**
     * Generates a valid STVN {@code :TimeEpoch} integer literal in the designated {@link TemporalScale},
     * clamped into {@code [#minIncl, #maxExcl)}.
     *
     * @param scale      the active {@link TemporalScale}
     * @param isUnsigned whether the epoch integer is unsigned
     * @param sizeBits   the bit width of the epoch integer
     * @param minIncl    optional minimum inclusive bound
     * @param maxExcl    optional maximum exclusive bound
     * @param clock      the clock supplying the current instant
     * @return the unquoted STVN integer literal
     */
    public static String generateTimeEpochExemplar(
        TemporalScale scale,
        boolean isUnsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl,
        Clock clock
    ) {
        Instant now = Instant.now(clock);
        BigInteger ticks = instantToEpochTicks(now, scale);
        BigInteger[] bounds = computeEffectiveIntegerBounds(isUnsigned, sizeBits, minIncl, maxExcl);
        BigInteger effMin = bounds[0];
        BigInteger effMaxIncl = bounds[1];
        if (ticks.compareTo(effMin) < 0) {
            ticks = effMin;
        } else if (ticks.compareTo(effMaxIncl) > 0) {
            ticks = effMaxIncl;
        }
        return ticks.toString();
    }

    /**
     * Converts an {@link Instant} to integer ticks in the given {@link TemporalScale}.
     *
     * @param instant the instant to convert
     * @param scale   the target temporal scale
     * @return the epoch tick count
     */
    public static BigInteger instantToEpochTicks(Instant instant, TemporalScale scale) {
        BigInteger seconds = BigInteger.valueOf(instant.getEpochSecond());
        BigInteger nanos = BigInteger.valueOf(instant.getNano());
        return switch (scale) {
            case S -> seconds;
            case MS -> seconds.multiply(BigInteger.valueOf(1_000L)).add(nanos.divide(BigInteger.valueOf(1_000_000L)));
            case US -> seconds.multiply(BigInteger.valueOf(1_000_000L)).add(nanos.divide(BigInteger.valueOf(1_000L)));
            case NS -> seconds.multiply(BigInteger.valueOf(1_000_000_000L)).add(nanos);
        };
    }

    /**
     * Generates a valid STVN {@code :Int} literal respecting {@code #unsigned}, {@code #size},
     * {@code #minIncl}, and {@code #maxExcl}.
     *
     * @param isUnsigned whether the integer is unsigned
     * @param sizeBits   the bit width of the integer
     * @param minIncl    optional minimum inclusive bound
     * @param maxExcl    optional maximum exclusive bound
     * @return a compliant integer literal string
     */
    public static String generateIntExemplar(
        boolean isUnsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl
    ) {
        BigInteger[] bounds = computeEffectiveIntegerBounds(isUnsigned, sizeBits, minIncl, maxExcl);
        BigInteger effMin = bounds[0];
        BigInteger effMaxIncl = bounds[1];

        if (BigInteger.ZERO.compareTo(effMin) >= 0 && BigInteger.ZERO.compareTo(effMaxIncl) <= 0) {
            return "0";
        }
        if (BigInteger.ZERO.compareTo(effMin) < 0) {
            return effMin.toString();
        }
        return effMaxIncl.toString();
    }

    /**
     * Computes the effective inclusive {@code [minIncl, maxIncl]} bounds for an integer or
     * {@code :TimeEpoch} schema by intersecting bit-width capacity with {@code #minIncl} and
     * {@code #maxExcl}.
     *
     * @param isUnsigned whether the integer is unsigned
     * @param sizeBits   the bit width of the integer
     * @param minIncl    optional explicit minimum inclusive bound
     * @param maxExcl    optional explicit maximum exclusive bound
     * @return a two-element array {@code [effectiveMinIncl, effectiveMaxIncl]}
     */
    public static BigInteger[] computeEffectiveIntegerBounds(
        boolean isUnsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl
    ) {
        int clampedBits = Math.max(1, Math.min(sizeBits, 128));
        BigInteger physMin = isUnsigned
            ? BigInteger.ZERO
            : BigInteger.ONE.shiftLeft(clampedBits - 1).negate();
        BigInteger physMaxIncl = isUnsigned
            ? BigInteger.ONE.shiftLeft(clampedBits).subtract(BigInteger.ONE)
            : BigInteger.ONE.shiftLeft(clampedBits - 1).subtract(BigInteger.ONE);

        BigInteger effMin = (minIncl != null && minIncl.compareTo(physMin) > 0) ? minIncl : physMin;
        BigInteger effMaxIncl = physMaxIncl;
        if (maxExcl != null) {
            BigInteger explicitMaxIncl = maxExcl.subtract(BigInteger.ONE);
            if (explicitMaxIncl.compareTo(effMaxIncl) < 0) {
                effMaxIncl = explicitMaxIncl;
            }
        }
        return new BigInteger[] {effMin, effMaxIncl};
    }

    /**
     * Generates a valid STVN {@code :Float} literal respecting inclusive and exclusive bounds.
     *
     * @param minIncl optional minimum inclusive bound
     * @param minExcl optional minimum exclusive bound
     * @param maxIncl optional maximum inclusive bound
     * @param maxExcl optional maximum exclusive bound
     * @return a compliant floating-point literal string
     */
    public static String generateFloatExemplar(
        @Nullable Double minIncl,
        @Nullable Double minExcl,
        @Nullable Double maxIncl,
        @Nullable Double maxExcl
    ) {
        double lower = -Double.MAX_VALUE;
        boolean lowerInclusive = true;
        if (minIncl != null) {
            lower = minIncl;
            lowerInclusive = true;
        }
        if (minExcl != null && (minIncl == null || minExcl >= minIncl)) {
            lower = minExcl;
            lowerInclusive = false;
        }

        double upper = Double.MAX_VALUE;
        boolean upperInclusive = true;
        if (maxIncl != null) {
            upper = maxIncl;
            upperInclusive = true;
        }
        if (maxExcl != null && (maxIncl == null || maxExcl <= maxIncl)) {
            upper = maxExcl;
            upperInclusive = false;
        }

        boolean zeroValid = (lowerInclusive ? 0.0 >= lower : 0.0 > lower)
            && (upperInclusive ? 0.0 <= upper : 0.0 < upper);
        if (zeroValid) {
            return "0.0";
        }

        if (0.0 <= lower) {
            double candidate = lowerInclusive ? lower : lower + 1.0;
            if (upper < Double.MAX_VALUE && (upperInclusive ? candidate > upper : candidate >= upper)) {
                candidate = (lower + upper) / 2.0;
            }
            return formatFloatLiteral(candidate);
        } else {
            double candidate = upperInclusive ? upper : upper - 1.0;
            if (lower > -Double.MAX_VALUE && (lowerInclusive ? candidate < lower : candidate <= lower)) {
                candidate = (lower + upper) / 2.0;
            }
            return formatFloatLiteral(candidate);
        }
    }

    private static String formatFloatLiteral(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.format(java.util.Locale.ROOT, "%.1f", value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Generates a valid STVN {@code :String} literal respecting UTF-8 byte size and lexicographical
     * bounds.
     *
     * @param minSize        optional minimum UTF-8 byte size
     * @param maxSize        optional maximum UTF-8 byte size
     * @param minIncl        optional minimum inclusive lexicographical bound
     * @param maxExcl        optional maximum exclusive lexicographical bound
     * @param preserveIndent whether {@code #preserveIndent} is active
     * @return a compliant quoted string literal
     */
    public static String generateStringExemplar(
        @Nullable Long minSize,
        @Nullable Long maxSize,
        @Nullable String minIncl,
        @Nullable String maxExcl,
        boolean preserveIndent
    ) {
        long minBytes = minSize != null ? Math.max(0L, minSize) : 0L;
        long maxBytes = maxSize != null ? Math.max(0L, maxSize) : StvnVocabulary.DEFAULT_MAX_SIZE.longValue();

        if (maxBytes == 0L) {
            return "\"\"";
        }

        String base = "example";
        if (minIncl != null && !minIncl.isEmpty() && base.compareTo(minIncl) < 0) {
            base = minIncl;
        }
        if (maxExcl != null && base.compareTo(maxExcl) >= 0) {
            base = minIncl != null ? minIncl : "a";
        }

        byte[] utf8 = base.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxBytes) {
            base = new String(utf8, 0, (int) maxBytes, StandardCharsets.UTF_8);
        } else if (utf8.length < minBytes && minBytes <= 4096) {
            StringBuilder sb = new StringBuilder(base);
            while (sb.toString().getBytes(StandardCharsets.UTF_8).length < minBytes) {
                sb.append('a');
            }
            base = sb.toString();
        }

        String escaped = base.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String generateEnumSubsetExemplarFromIr(
        StvnSchema.EnumSubsetSchema subsetSchema,
        Map<String, String> psiMeta
    ) {
        Set<String> active = new LinkedHashSet<>(subsetSchema.activeVariants());
        applyEnumFiltersFromMeta(active, psiMeta);
        if (!active.isEmpty()) {
            return ensureHashPrefix(active.iterator().next());
        }
        if (!subsetSchema.activeVariants().isEmpty()) {
            return ensureHashPrefix(subsetSchema.activeVariants().get(0));
        }
        return generateEnumExemplarFromIr(subsetSchema.rootEnum(), psiMeta);
    }

    private static String generateEnumExemplarFromIr(
        StvnSchema.EnumSchema enumSchema,
        Map<String, String> psiMeta
    ) {
        Set<String> active = new LinkedHashSet<>(enumSchema.rootVariants());
        applyEnumFiltersFromMeta(active, psiMeta);
        if (!active.isEmpty()) {
            return ensureHashPrefix(active.iterator().next());
        }
        if (!enumSchema.rootVariants().isEmpty()) {
            return ensureHashPrefix(enumSchema.rootVariants().get(0));
        }
        return StvnVocabulary.SIGIL_VALUE + "DEFAULT";
    }

    private static String generateEnumExemplarFromPsi(SumType sumType, Map<String, String> psiMeta) {
        List<String> variants = new ArrayList<>();
        if (sumType.getEnumDef() != null) {
            for (ValueKeyword kw : sumType.getEnumDef().getValueKeywordList()) {
                String text = kw.getText().trim();
                if (!text.isEmpty()) {
                    variants.add(ensureHashPrefix(text));
                }
            }
        }
        Set<String> active = new LinkedHashSet<>(variants);
        applyEnumFiltersFromMeta(active, psiMeta);
        if (!active.isEmpty()) {
            return ensureHashPrefix(active.iterator().next());
        }
        if (!variants.isEmpty()) {
            return ensureHashPrefix(variants.get(0));
        }
        return StvnVocabulary.SIGIL_VALUE + "DEFAULT";
    }

    private static void applyEnumFiltersFromMeta(Set<String> active, Map<String, String> psiMeta) {
        List<String> filterIncl = parseEnumVariantList(psiMeta.get(StvnVocabulary.FACET_KW_FILTER_INCL));
        List<String> filterExcl = parseEnumVariantList(psiMeta.get(StvnVocabulary.FACET_KW_FILTER_EXCL));
        if (!filterIncl.isEmpty()) {
            active.retainAll(filterIncl);
        }
        if (!filterExcl.isEmpty()) {
            active.removeAll(filterExcl);
        }
    }

    private static String ensureHashPrefix(String variant) {
        String trimmed = variant.trim();
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return trimmed;
        }
        if (trimmed.startsWith("$")) {
            return StvnVocabulary.SIGIL_VALUE + trimmed.substring(1);
        }
        return StvnVocabulary.SIGIL_VALUE + trimmed;
    }

    private static List<String> parseEnumVariantList(@Nullable String rawList) {
        if (rawList == null || rawList.isBlank()) {
            return List.of();
        }
        String stripped = rawList.trim();
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }
        if (stripped.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : stripped.split("[\\s,]+")) {
            if (!token.isEmpty()) {
                result.add(ensureHashPrefix(token));
            }
        }
        return result;
    }

    /**
     * Resolves the active {@link DateTimeMode} from extracted PSI metadata entries.
     *
     * @param meta the extracted metadata map
     * @return the resolved {@link DateTimeMode} (defaults to {@link DateTimeMode#OFFSET})
     */
    public static DateTimeMode resolveDateTimeModeFromMeta(Map<String, String> meta) {
        if (meta.containsKey(StvnVocabulary.FACET_KW_ZONED)) {
            return DateTimeMode.ZONED;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_AUDITED)) {
            return DateTimeMode.AUDITED;
        }
        return DateTimeMode.OFFSET;
    }

    /**
     * Resolves the active {@link TemporalScale} from extracted PSI metadata entries.
     *
     * @param meta the extracted metadata map
     * @return the resolved {@link TemporalScale} (defaults to {@link TemporalScale#MS})
     */
    public static TemporalScale resolveEpochScaleFromMeta(Map<String, String> meta) {
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_S)) {
            return TemporalScale.S;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_US)) {
            return TemporalScale.US;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_NS)) {
            return TemporalScale.NS;
        }
        return TemporalScale.MS;
    }

    private static boolean hasExplicitScaleInMeta(Map<String, String> meta) {
        return meta.containsKey(StvnVocabulary.FACET_KW_SCALE_S)
            || meta.containsKey(StvnVocabulary.FACET_KW_SCALE_MS)
            || meta.containsKey(StvnVocabulary.FACET_KW_SCALE_US)
            || meta.containsKey(StvnVocabulary.FACET_KW_SCALE_NS);
    }

    /**
     * Extracts all metadata facets across the nominal alias lineage of {@code schemaType},
     * with closer (child) facets overriding ancestor facets.
     *
     * @param schemaType the starting PSI schema type
     * @return a map of facet keyword to facet argument string (or {@code "#TRUE"} for bare flags)
     */
    public static Map<String, String> extractLineageMetadata(SchemaType schemaType) {
        List<MetadataMap> mapsHighestToLowest = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        SchemaType curr = schemaType;
        while (curr != null) {
            if (curr.getMetadataMap() != null) {
                mapsHighestToLowest.add(curr.getMetadataMap());
            }
            TypeDefinition parentDef = PsiTreeUtil.getParentOfType(curr, TypeDefinition.class);
            if (parentDef != null && parentDef.getSchemaType() == curr && parentDef.getMetadataMap() != null) {
                mapsHighestToLowest.add(parentDef.getMetadataMap());
            }
            var kw = curr.getTypeKeyword();
            if (kw == null) {
                break;
            }
            String alias = kw.getText().trim();
            if (!visited.add(alias)) {
                break;
            }
            var file = curr.getContainingFile();
            if (file == null) {
                break;
            }
            var target = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
            var td = StvnPsiUtils.resolveTargetTypeDefinition(target);
            if (td == null) {
                break;
            }
            if (td.getMetadataMap() != null) {
                mapsHighestToLowest.add(td.getMetadataMap());
            }
            curr = td.getSchemaType();
        }

        Map<String, String> merged = new LinkedHashMap<>();
        for (int i = mapsHighestToLowest.size() - 1; i >= 0; i--) {
            extractMetadataMapInto(mapsHighestToLowest.get(i), merged);
        }
        return merged;
    }

    private static void extractMetadataMapInto(MetadataMap metaMap, Map<String, String> target) {
        for (var entry : metaMap.getMetadataEntryList()) {
            String text = entry.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            String[] parts = text.split("\\s+", 2);
            String key = parts[0];
            String val = parts.length > 1 ? parts[1].trim() : StvnVocabulary.VAL_TRUE;
            target.put(key, val);
        }
    }

    /**
     * Resolves nominal type references on {@code schemaType} to the terminal structural or atomic
     * {@link SchemaType}.
     *
     * @param schemaType the schema type to unwrap
     * @return the terminal {@link SchemaType}
     */
    public static SchemaType resolveTerminalSchemaType(SchemaType schemaType) {
        Set<SchemaType> visited = new HashSet<>();
        SchemaType curr = schemaType;
        while (curr != null && curr.getSchemaConstructor() == null && curr.getTypeKeyword() != null) {
            if (!visited.add(curr)) {
                break;
            }
            SchemaType next = StvnTypeResolver.resolveNominalSchema(curr);
            if (next == null || next == curr) {
                break;
            }
            curr = next;
        }
        return curr != null ? curr : schemaType;
    }

    private static @Nullable Instant tryParseInstantBound(@Nullable String rawBound, ZoneId fallbackZone) {
        if (rawBound == null || rawBound.isBlank()) {
            return null;
        }
        String clean = unquoteOrNull(rawBound);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(clean, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(clean, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            int bracketIdx = clean.indexOf('[');
            if (bracketIdx > 0 && clean.endsWith("]")) {
                String localPart = clean.substring(0, bracketIdx);
                String zonePart = clean.substring(bracketIdx + 1, clean.length() - 1);
                LocalDateTime ldt = LocalDateTime.parse(localPart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(ZoneId.of(zonePart)).toInstant();
            }
            LocalDateTime ldt = LocalDateTime.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(fallbackZone).toInstant();
        } catch (Exception ignored) {
            return null;
        }
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

    private static long parseLongOrDefault(@Nullable String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static @Nullable BigInteger parseBigIntOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StvnPsiLiteralEvaluatorFallback.parseBigInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Long parseLongOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static @Nullable String unquoteOrNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static final class StvnPsiLiteralEvaluatorFallback {
        private static @Nullable BigInteger parseBigInt(String text) {
            String clean = text.replace("_", "");
            if (clean.startsWith("0x") || clean.startsWith("0X")) {
                return new BigInteger(clean.substring(2), 16);
            }
            if (clean.startsWith("0b") || clean.startsWith("0B")) {
                return new BigInteger(clean.substring(2), 2);
            }
            if (clean.startsWith("0o") || clean.startsWith("0O")) {
                return new BigInteger(clean.substring(2), 8);
            }
            return new BigInteger(clean, 10);
        }
    }
}
