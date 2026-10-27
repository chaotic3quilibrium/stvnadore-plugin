package org.stvnadore.plugin.metadata;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;

import java.util.*;

/**
 * Authoritative registry of supported STVN 2.0.0 metadata facets, target type mappings,
 * overridability rules, and authoring templates.
 */
@NullMarked
public final class StvnMetadataRegistry {

    private static final Map<String, StvnFacetMetadata> ALL_FACETS = new LinkedHashMap<>();
    private static final Map<String, List<StvnFacetMetadata>> DOMAIN_FACETS = new HashMap<>();

    static {
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_UNSIGNED, 1, false,
            "Designates an integer representation as unsigned (non-negative)",
            Set.of(StvnVocabulary.TYPE_INT), "#unsigned"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_SIZE, 1, false,
            "Enforces explicit storage bit-width (e.g. 8, 16, 32, 64)",
            Set.of(StvnVocabulary.TYPE_INT, StvnVocabulary.TYPE_FLOAT), "#size 32"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_EXACT, 1, false,
            "Enforces arbitrary-precision exact decimal representation",
            Set.of(StvnVocabulary.TYPE_FLOAT), "#exact"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_INVERTIBLE, 1, false,
            "Enforces bidirectional unique value-to-key mapping",
            Set.of(StvnVocabulary.TYPE_MAP), "#invertible"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_SCALE_S, 2, false,
            "Physical epoch resolution in seconds (64-bit integer ticks)",
            Set.of(StvnVocabulary.TYPE_TIME_EPOCH), "#s"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_SCALE_MS, 2, false,
            "Physical epoch resolution in milliseconds (64-bit integer ticks)",
            Set.of(StvnVocabulary.TYPE_TIME_EPOCH), "#ms"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_SCALE_US, 2, false,
            "Physical epoch resolution in microseconds (64-bit integer ticks)",
            Set.of(StvnVocabulary.TYPE_TIME_EPOCH), "#us"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_SCALE_NS, 2, false,
            "Physical epoch resolution in nanoseconds (128-bit integer ticks)",
            Set.of(StvnVocabulary.TYPE_TIME_EPOCH), "#ns"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_OFFSET, 1, false,
            "Physical instant ISO-8601 timestamp with numerical UTC offset",
            Set.of(StvnVocabulary.TYPE_DATE_TIME), "#offset"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_ZONED, 1, false,
            "Civil wall-clock ISO-8601 timestamp with IANA jurisdiction name",
            Set.of(StvnVocabulary.TYPE_DATE_TIME), "#zoned"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_AUDITED, 1, false,
            "Dual-verified audit timestamp with numerical offset and IANA jurisdiction",
            Set.of(StvnVocabulary.TYPE_DATE_TIME), "#audited"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_EQUATABLE, 2, true,
            "Overrides mathematical equivalence comparison capability",
            Set.of(
                StvnVocabulary.TYPE_BOOLEAN, StvnVocabulary.TYPE_INT, StvnVocabulary.TYPE_FLOAT,
                StvnVocabulary.TYPE_STRING, StvnVocabulary.TYPE_TIME_EPOCH, StvnVocabulary.TYPE_DATE_TIME,
                StvnVocabulary.TYPE_SEQ, StvnVocabulary.TYPE_SET, StvnVocabulary.TYPE_MAP,
                StvnVocabulary.TYPE_TUPLE, StvnVocabulary.TYPE_UNION, StvnVocabulary.TYPE_ENUM,
                StvnVocabulary.TYPE_EITHER, StvnVocabulary.TYPE_OPTION
            ),
            "#equatable #TRUE"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_COMPARABLE, 2, true,
            "Overrides total ordering comparison capability",
            Set.of(
                StvnVocabulary.TYPE_BOOLEAN, StvnVocabulary.TYPE_INT, StvnVocabulary.TYPE_FLOAT,
                StvnVocabulary.TYPE_STRING, StvnVocabulary.TYPE_TIME_EPOCH, StvnVocabulary.TYPE_DATE_TIME,
                StvnVocabulary.TYPE_SEQ, StvnVocabulary.TYPE_SET, StvnVocabulary.TYPE_MAP,
                StvnVocabulary.TYPE_TUPLE, StvnVocabulary.TYPE_UNION, StvnVocabulary.TYPE_ENUM,
                StvnVocabulary.TYPE_EITHER, StvnVocabulary.TYPE_OPTION
            ),
            "#comparable #TRUE"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_MIN_INCL, 3, true,
            "Inclusive lower bound value",
            Set.of(StvnVocabulary.TYPE_INT, StvnVocabulary.TYPE_FLOAT, StvnVocabulary.TYPE_STRING, StvnVocabulary.TYPE_TIME_EPOCH),
            "#minIncl 0"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_MAX_EXCL, 3, true,
            "Exclusive upper bound value (discrete half-open domain [minIncl, maxExcl))",
            Set.of(StvnVocabulary.TYPE_INT, StvnVocabulary.TYPE_FLOAT, StvnVocabulary.TYPE_TIME_EPOCH),
            "#maxExcl 100"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_MIN_EXCL, 3, true,
            "Exclusive lower bound value (continuous types only)",
            Set.of(StvnVocabulary.TYPE_FLOAT, StvnVocabulary.TYPE_STRING),
            "#minExcl 0.0"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_MAX_INCL, 3, true,
            "Inclusive upper bound value (continuous types only)",
            Set.of(StvnVocabulary.TYPE_FLOAT, StvnVocabulary.TYPE_STRING),
            "#maxIncl 1.0"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_MIN_SIZE, 3, true,
            "Minimum cardinality or character length bound",
            Set.of(StvnVocabulary.TYPE_STRING, StvnVocabulary.TYPE_SEQ, StvnVocabulary.TYPE_SET, StvnVocabulary.TYPE_MAP),
            "#minSize 1"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_MAX_SIZE, 3, true,
            "Maximum cardinality or character length bound",
            Set.of(StvnVocabulary.TYPE_STRING, StvnVocabulary.TYPE_SEQ, StvnVocabulary.TYPE_SET, StvnVocabulary.TYPE_MAP),
            "#maxSize 256"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_PRESERVE_INDENT, 4, true,
            "Preserves leading multi-line block string indentation verbatim",
            Set.of(StvnVocabulary.TYPE_STRING), "#preserveIndent #TRUE"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_REGEX, 4, true,
            "Regular expression validation pattern for string literals",
            Set.of(StvnVocabulary.TYPE_STRING), "#regex \"^[A-Za-z0-9]+$\""
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_FILTER_INCL, 4, true,
            "Inclusive subset filtering for nominal enumeration derivations",
            Set.of(StvnVocabulary.TYPE_ENUM), "#filterIncl [ #VARIANT_A #VARIANT_B ]"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_FILTER_EXCL, 4, true,
            "Exclusive subset filtering for nominal enumeration derivations",
            Set.of(StvnVocabulary.TYPE_ENUM), "#filterExcl [ #EXCLUDED_VARIANT ]"
        ));
        register(new StvnFacetMetadata(
            StvnVocabulary.FACET_KW_STRIP, 5, false,
            "Ingestion option stripping comments or whitespace",
            Set.of(StvnVocabulary.KEYWORD_USE, StvnVocabulary.KEYWORD_INCLUDE), "#strip"
        ));
    }

    private static void register(StvnFacetMetadata facet) {
        ALL_FACETS.put(facet.facetTag(), facet);
        for (var domain : facet.targetDomains()) {
            DOMAIN_FACETS.computeIfAbsent(domain, k -> new ArrayList<>()).add(facet);
        }
    }

    private StvnMetadataRegistry() {}

    /**
     * Returns the full list of metadata facets supported by the specified base type.
     */
    public static List<StvnFacetMetadata> getSupportedFacets(String baseType) {
        var cleanBase = baseType.startsWith(":") ? baseType : ":" + baseType;
        if (cleanBase.startsWith(":Tuple")) cleanBase = StvnVocabulary.TYPE_TUPLE;
        else if (cleanBase.startsWith(":Seq")) cleanBase = StvnVocabulary.TYPE_SEQ;
        else if (cleanBase.startsWith(":Set")) cleanBase = StvnVocabulary.TYPE_SET;
        else if (cleanBase.startsWith(":Map")) cleanBase = StvnVocabulary.TYPE_MAP;
        else if (cleanBase.startsWith(":Union")) cleanBase = StvnVocabulary.TYPE_UNION;
        else if (cleanBase.startsWith(":Either")) cleanBase = StvnVocabulary.TYPE_EITHER;
        else if (cleanBase.startsWith(":Option")) cleanBase = StvnVocabulary.TYPE_OPTION;
        else if (cleanBase.startsWith(":Enum")) cleanBase = StvnVocabulary.TYPE_ENUM;

        return DOMAIN_FACETS.getOrDefault(cleanBase, Collections.emptyList());
    }

    /**
     * Filters supported facets against active facet tags, returning unconfigured options.
     */
    public static List<StvnFacetMetadata> getUnsetFacets(String baseType, Set<String> activeFacetTags) {
        var supported = getSupportedFacets(baseType);
        var unset = new ArrayList<StvnFacetMetadata>();
        for (var facet : supported) {
            if (!activeFacetTags.contains(facet.facetTag())) {
                unset.add(facet);
            }
        }
        return unset;
    }

    /**
     * Returns metadata descriptor for a specific facet keyword tag.
     */
    public static @Nullable StvnFacetMetadata getFacetMetadata(String facetTag) {
        return ALL_FACETS.get(facetTag);
    }
}
