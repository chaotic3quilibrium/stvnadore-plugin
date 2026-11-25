package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.VariantList;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility for extracting sanitized, comment-free, canonically formatted schema representations
 * from IntelliJ PSI trees and STVN 2.0.0 pure-IR {@link StvnSchema} records.
 */
@NullMarked
public final class StvnSchemaFormatter {

    private static final BigInteger THIRTY_TWO = BigInteger.valueOf(32L);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private StvnSchemaFormatter() {
    }

    /**
     * Extracts a clean, canonically formatted schema type string from a PSI element,
     * preserving child metadata maps in canonical § 6.1 5-tier decimal order and enforcing standard delimiter padding.
     *
     * @param element the PSI element (SchemaType, SchemaConstructor, TypeDefinition, etc.)
     * @return the cleanly formatted canonical schema signature, or an empty string if null
     */
    public static @NotNull String formatSchema(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        if (element instanceof TypeDefinition typeDef) {
            var topMeta = typeDef.getMetadataMap();
            var schemaType = typeDef.getSchemaType();
            if (schemaType == null) {
                return topMeta != null ? formatMetadataMap(topMeta) : "";
            }
            String formattedRhs = formatPsiSchemaType(schemaType);
            if (topMeta != null && schemaType.getMetadataMap() == null) {
                return (formatMetadataMap(topMeta) + " " + formattedRhs).trim();
            }
            return formattedRhs;
        }
        if (element instanceof ConstantDefinition constDef) {
            var topMeta = constDef.getMetadataMap();
            var schemaType = constDef.getSchemaType();
            if (schemaType == null) {
                return topMeta != null ? formatMetadataMap(topMeta) : "";
            }
            String formattedRhs = formatPsiSchemaType(schemaType);
            if (topMeta != null && schemaType.getMetadataMap() == null) {
                return (formatMetadataMap(topMeta) + " " + formattedRhs).trim();
            }
            return formattedRhs;
        }
        if (element instanceof SchemaType schemaType) {
            return formatPsiSchemaType(schemaType);
        }
        if (element instanceof SchemaConstructor ctor) {
            return formatPsiSchemaConstructor(ctor);
        }
        if (element instanceof MetadataMap metaMap) {
            return formatMetadataMap(metaMap);
        }
        if (element instanceof MetadataEntry entry) {
            return formatSanitizedMetadataEntry(entry, false);
        }
        return formatFallbackPsi(element);
    }

    /**
     * Extracts a clean, canonically formatted schema type string from a PSI element,
     * enforcing standard whitespace padding around delimiters and stripping comments.
     *
     * @param element the PSI element (typically SchemaType, SchemaConstructor, etc.)
     * @return the cleanly formatted canonical schema signature, or an empty string if null
     */
    public static @NotNull String formatCleanSchema(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        if (element instanceof TypeDefinition typeDef) {
            return formatCleanSchema(typeDef.getSchemaType());
        }
        if (element instanceof ConstantDefinition constDef) {
            return formatCleanSchema(constDef.getSchemaType());
        }
        if (element instanceof SchemaType schemaType) {
            var keyword = schemaType.getTypeKeyword();
            if (keyword != null) {
                return keyword.getText().trim();
            }
            var ctor = schemaType.getSchemaConstructor();
            if (ctor != null) {
                return formatPsiSchemaConstructor(ctor);
            }
            return formatFallbackPsi(schemaType);
        }
        if (element instanceof SchemaConstructor ctor) {
            return formatPsiSchemaConstructor(ctor);
        }
        return formatFallbackPsi(element);
    }

    /**
     * Formats a pure-IR {@link StvnSchema} preserving nominal alias identity when present.
     *
     * @param schema pure-IR schema record, or {@code null}
     * @return nominal alias name when branded, or canonical structural signature
     */
    public static @NotNull String formatIrSchema(@Nullable StvnSchema schema) {
        if (schema == null) {
            return "";
        }
        if (schema.aliasName().isPresent()) {
            return schema.aliasName().get();
        }
        return formatCleanIrSchema(schema);
    }

    /**
     * Formats the underlying structural shape of a pure-IR {@link StvnSchema} without its outer alias name.
     *
     * @param schema pure-IR schema record, or {@code null}
     * @return canonical structural schema signature
     */
    public static @NotNull String formatCleanIrSchema(@Nullable StvnSchema schema) {
        if (schema == null) {
            return "";
        }
        return switch (schema) {
            case StvnSchema.BooleanSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
            case StvnSchema.IntSchema ignored -> StvnVocabulary.TYPE_INT;
            case StvnSchema.FloatSchema ignored -> StvnVocabulary.TYPE_FLOAT;
            case StvnSchema.StringSchema ignored -> StvnVocabulary.TYPE_STRING;
            case StvnSchema.TimeEpochSchema ignored -> StvnVocabulary.TYPE_TIME_EPOCH;
            case StvnSchema.DateTimeSchema ignored -> StvnVocabulary.TYPE_DATE_TIME;
            case StvnSchema.EnumSchema e -> formatEnumVariants(e.rootVariants());
            case StvnSchema.EnumSubsetSchema es -> formatEnumVariants(es.activeVariants());
            case StvnSchema.SeqSchema s ->
                StvnVocabulary.TYPE_SEQ + "( " + formatIrSchema(s.elementSchema()) + " )";
            case StvnSchema.SetSchema s ->
                StvnVocabulary.TYPE_SET + "( " + formatIrSchema(s.elementSchema()) + " )";
            case StvnSchema.MapSchema m ->
                StvnVocabulary.TYPE_MAP + "( " + formatIrSchema(m.keySchema()) + " " + formatIrSchema(m.valueSchema()) + " )";
            case StvnSchema.TupleSchema t ->
                t.fieldSchemas().isEmpty()
                    ? StvnVocabulary.TYPE_TUPLE + "()"
                    : StvnVocabulary.TYPE_TUPLE + "( " + t.fieldSchemas().stream()
                        .map(StvnSchemaFormatter::formatIrSchema)
                        .collect(Collectors.joining(" ")) + " )";
            case StvnSchema.OptionSchema o ->
                StvnVocabulary.TYPE_OPTION + "( " + formatIrSchema(o.valueSchema()) + " )";
            case StvnSchema.EitherSchema e ->
                StvnVocabulary.TYPE_EITHER + "( " + formatIrSchema(e.leftSchema()) + " " + formatIrSchema(e.rightSchema()) + " )";
            case StvnSchema.UnionSchema u ->
                u.branchSchemas().isEmpty()
                    ? StvnVocabulary.TYPE_UNION + "()"
                    : StvnVocabulary.TYPE_UNION + "( " + u.branchSchemas().stream()
                        .map(StvnSchemaFormatter::formatIrSchema)
                        .collect(Collectors.joining(" ")) + " )";
            case StvnSchema.PoisonedSchema p ->
                p.bestEffortFallback()
                    .map(StvnSchemaFormatter::formatCleanIrSchema)
                    .orElseGet(() -> p.aliasName().orElse("<poisoned>"));
        };
    }

    /**
     * Returns the canonical base constructor keyword for a pure-IR {@link StvnSchema}.
     *
     * @param schema pure-IR schema record
     * @return canonical base constructor keyword (for example, {@code ":Int"} or {@code ":Tuple"})
     */
    public static @NotNull String getPrimitiveBaseType(StvnSchema schema) {
        return switch (schema) {
            case StvnSchema.BooleanSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
            case StvnSchema.IntSchema ignored -> StvnVocabulary.TYPE_INT;
            case StvnSchema.FloatSchema ignored -> StvnVocabulary.TYPE_FLOAT;
            case StvnSchema.StringSchema ignored -> StvnVocabulary.TYPE_STRING;
            case StvnSchema.TimeEpochSchema ignored -> StvnVocabulary.TYPE_TIME_EPOCH;
            case StvnSchema.DateTimeSchema ignored -> StvnVocabulary.TYPE_DATE_TIME;
            case StvnSchema.EnumSchema ignored -> StvnVocabulary.TYPE_ENUM;
            case StvnSchema.EnumSubsetSchema ignored -> StvnVocabulary.TYPE_ENUM;
            case StvnSchema.SeqSchema ignored -> StvnVocabulary.TYPE_SEQ;
            case StvnSchema.SetSchema ignored -> StvnVocabulary.TYPE_SET;
            case StvnSchema.MapSchema ignored -> StvnVocabulary.TYPE_MAP;
            case StvnSchema.TupleSchema ignored -> StvnVocabulary.TYPE_TUPLE;
            case StvnSchema.OptionSchema ignored -> StvnVocabulary.TYPE_OPTION;
            case StvnSchema.EitherSchema ignored -> StvnVocabulary.TYPE_EITHER;
            case StvnSchema.UnionSchema ignored -> StvnVocabulary.TYPE_UNION;
            case StvnSchema.PoisonedSchema p ->
                p.bestEffortFallback()
                    .map(StvnSchemaFormatter::getPrimitiveBaseType)
                    .orElseGet(() -> p.aliasName().orElse(""));
        };
    }

    private static String formatPsiSchemaType(SchemaType schemaType) {
        var meta = schemaType.getMetadataMap();
        var metaPrefix = meta != null ? formatMetadataMap(meta) + " " : "";

        String body;
        var keyword = schemaType.getTypeKeyword();
        if (keyword != null) {
            body = keyword.getText().trim();
        } else {
            var ctor = schemaType.getSchemaConstructor();
            if (ctor != null) {
                body = formatPsiSchemaConstructor(ctor);
            } else {
                body = formatFallbackPsi(schemaType);
            }
        }
        return (metaPrefix + body).trim();
    }

    private static String formatPsiSchemaConstructor(SchemaConstructor ctor) {
        var atomic = ctor.getAtomicType();
        if (atomic != null) {
            return atomic.getText().trim();
        }
        var sum = ctor.getSumType();
        if (sum != null) {
            if (sum.getNode().findChildByType(StvnTypes.KW_OPTION) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return formatParenthesizedCtor(StvnVocabulary.TYPE_OPTION, children);
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_EITHER) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return formatParenthesizedCtor(StvnVocabulary.TYPE_EITHER, children);
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_UNION) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return formatParenthesizedCtor(StvnVocabulary.TYPE_UNION, children);
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_ENUM) != null) {
                var enumDef = sum.getEnumDef();
                if (enumDef != null) {
                    var kws = enumDef.getValueKeywordList();
                    var list = new ArrayList<String>();
                    for (var kw : kws) {
                        list.add(kw.getText().trim());
                    }
                    return formatEnumVariants(list);
                }
            }
        }
        var prod = ctor.getProductType();
        if (prod != null) {
            return formatParenthesizedCtor(StvnVocabulary.TYPE_TUPLE, prod.getSchemaTypeList());
        }
        var coll = ctor.getCollectionType();
        if (coll != null) {
            var first = coll.getFirstChild();
            var collName = first != null ? first.getText().trim() : StvnVocabulary.TYPE_SEQ;
            return formatParenthesizedCtor(collName, coll.getSchemaTypeList());
        }
        return formatFallbackPsi(ctor);
    }

    private static String formatParenthesizedCtor(String ctorName, List<SchemaType> children) {
        if (children.isEmpty()) {
            return ctorName + "()";
        }
        return ctorName + "( " + formatPsiChildren(children) + " )";
    }

    private static String formatPsiChildren(List<SchemaType> children) {
        var list = new ArrayList<String>();
        for (var child : children) {
            list.add(formatPsiSchemaType(child));
        }
        return String.join(" ", list);
    }

    /**
     * Computes the canonical § 6.1 5-tier decimal rank ({@code 1.1.1} through {@code 5.0})
     * for a schema {@link MetadataEntry} by delegating keyword extraction and ranking to
     * {@link StvnMetadataOrderInspection}.
     *
     * @param entry schema metadata entry PSI node
     * @return canonical integer rank ({@code 1110..5000}, or {@code 99999} when unrecognized)
     */
    public static int resolveEntryFacetRank(MetadataEntry entry) {
        String keyword = StvnMetadataOrderInspection.extractFacetKeyword(entry);
        return StvnMetadataOrderInspection.getFacetRank(keyword);
    }

    /**
     * Formats a single {@link MetadataEntry} into a comment-free single-line string, preserving
     * {@code //} sequences inside quoted string literals and normalizing Rule {@code STR-04}
     * fenced facet strings to canonical quoted literals.
     *
     * @param entry           schema metadata entry PSI node
     * @param compactBrackets {@code true} to emit zero-bracket-padded variant lists ({@code [#A #B]})
     * @return sanitized single-line metadata facet string
     */
    public static @NotNull String formatSanitizedMetadataEntry(MetadataEntry entry, boolean compactBrackets) {
        var sb = new StringBuilder();
        appendSanitizedPsiTokens(entry, sb, compactBrackets);
        return sb.toString().trim();
    }

    private static String formatMetadataMap(MetadataMap metaMap) {
        var entries = new ArrayList<>(metaMap.getMetadataEntryList());
        if (entries.isEmpty()) {
            return StvnVocabulary.DELIM_OPEN_BRACE + StvnVocabulary.DELIM_CLOSE_BRACE;
        }
        entries.sort(Comparator.comparingInt(StvnSchemaFormatter::resolveEntryFacetRank));
        var list = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            var clean = formatSanitizedMetadataEntry(entry, false);
            if (!clean.isEmpty()) {
                list.add(clean);
            }
        }
        return StvnVocabulary.DELIM_OPEN_BRACE + " " + String.join(" ", list) + " " + StvnVocabulary.DELIM_CLOSE_BRACE;
    }

    private static String formatFallbackPsi(PsiElement element) {
        var sb = new StringBuilder();
        appendSanitizedPsiTokens(element, sb, false);
        return sb.toString().trim();
    }

    private static void appendSanitizedPsiTokens(
        PsiElement node,
        StringBuilder sb,
        boolean compactVariantBrackets
    ) {
        if (node instanceof PsiComment) {
            return;
        }
        if (node instanceof PsiWhiteSpace) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            return;
        }
        if (node instanceof StringLiteral strLit) {
            String raw = strLit.getText();
            if (raw.startsWith("\"\"\"")) {
                String normalized = StvnPsiLiteralEvaluator.decodeStringLiteral(raw, false)
                    .map(StvnPsiLiteralEvaluator::formatCanonicalQuotedString)
                    .orElseGet(() -> WHITESPACE_PATTERN.matcher(raw).replaceAll(" ").trim());
                sb.append(normalized);
            } else {
                sb.append(raw);
            }
            return;
        }
        if (compactVariantBrackets && node instanceof VariantList variantList) {
            List<String> items = new ArrayList<>();
            for (PsiElement child = variantList.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof PsiComment || child instanceof PsiWhiteSpace) {
                    continue;
                }
                String txt = child.getText().trim();
                if (!txt.isEmpty()
                        && !StvnVocabulary.DELIM_OPEN_BRACKET.equals(txt)
                        && !StvnVocabulary.DELIM_CLOSE_BRACKET.equals(txt)) {
                    items.add(txt);
                }
            }
            sb.append(StvnVocabulary.DELIM_OPEN_BRACKET)
              .append(String.join(" ", items))
              .append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return;
        }
        PsiElement firstChild = node.getFirstChild();
        if (firstChild == null) {
            sb.append(node.getText());
            return;
        }
        for (PsiElement child = firstChild; child != null; child = child.getNextSibling()) {
            appendSanitizedPsiTokens(child, sb, compactVariantBrackets);
        }
    }

    /**
     * Formats the underlying base constructor for degraded schema diagnostics and hover banners,
     * collapsing enum definitions and enum subsets to {@code :Enum} without inline variant lists.
     *
     * @param schema pure-IR schema record, or {@code null}
     * @return concise underlying base type string, or an empty string if {@code null}
     */
    public static @NotNull String formatDegradedUnderlyingBaseIr(@Nullable StvnSchema schema) {
        if (schema == null) {
            return "";
        }
        if (schema instanceof StvnSchema.EnumSchema || schema instanceof StvnSchema.EnumSubsetSchema) {
            return StvnVocabulary.TYPE_ENUM;
        }
        if (schema instanceof StvnSchema.PoisonedSchema p) {
            return p.bestEffortFallback()
                .map(StvnSchemaFormatter::formatDegradedUnderlyingBaseIr)
                .orElseGet(() -> p.aliasName().orElse("<poisoned>"));
        }
        return formatCleanIrSchema(schema);
    }

    /**
     * Formats the underlying base constructor from PSI for degraded schema diagnostics and hover banners,
     * collapsing enum definitions to {@code :Enum} without inline variant lists.
     *
     * @param element PSI schema element, or {@code null}
     * @return concise underlying base type string, or an empty string if {@code null}
     */
    public static @NotNull String formatDegradedUnderlyingBasePsi(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        if (element instanceof TypeDefinition typeDef) {
            return formatDegradedUnderlyingBasePsi(typeDef.getSchemaType());
        }
        if (element instanceof ConstantDefinition constDef) {
            return formatDegradedUnderlyingBasePsi(constDef.getSchemaType());
        }
        if (element instanceof SchemaType schemaType) {
            var ctor = schemaType.getSchemaConstructor();
            if (ctor != null && ctor.getSumType() != null
                    && ctor.getSumType().getNode().findChildByType(StvnTypes.KW_ENUM) != null) {
                return StvnVocabulary.TYPE_ENUM;
            }
        } else if (element instanceof SchemaConstructor ctor) {
            if (ctor.getSumType() != null
                    && ctor.getSumType().getNode().findChildByType(StvnTypes.KW_ENUM) != null) {
                return StvnVocabulary.TYPE_ENUM;
            }
        }
        return formatCleanSchema(element);
    }

    /**
     * Formats an enum structure from an explicit list of variant keywords.
     *
     * @param variants the list of variant keywords (for example, {@code ["#KNIGHT", "#BISHOP"]})
     * @return canonical formatted enum string, e.g. {@code ":Enum [ #KNIGHT #BISHOP ]"} or {@code ":Enum []"} if empty
     */
    public static @NotNull String formatEnumVariants(List<String> variants) {
        if (variants.isEmpty()) {
            return StvnVocabulary.TYPE_ENUM + " []";
        }
        return StvnVocabulary.TYPE_ENUM + " [ " + String.join(" ", variants) + " ]";
    }

    /**
     * Formats a PSI schema element into canonical STVN 2.0.0 zero-bracket-padded notation
     * (for example, {@code ":Tuple(:ValveMode :LoopTempC)"} and {@code ":Enum[#CLOSED #OPEN]"}),
     * preserving prefix metadata constraint blocks while stripping comments and newlines.
     *
     * @param element PSI element ({@link SchemaType}, {@link SchemaConstructor}, {@link TypeDefinition}, or {@link ConstantDefinition})
     * @return zero-bracket-padded canonical STVN 2.0.0 schema string, or empty string if {@code null}
     */
    public static @NotNull String formatCompactCanonicalPsiSchema(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        if (element instanceof TypeDefinition typeDef) {
            var topMeta = typeDef.getMetadataMap();
            var schemaType = typeDef.getSchemaType();
            if (schemaType == null) {
                return topMeta != null ? formatCompactMetadataMap(topMeta) : "";
            }
            String formattedRhs = formatCompactPsiSchemaType(schemaType);
            if (topMeta != null && schemaType.getMetadataMap() == null) {
                return (formatCompactMetadataMap(topMeta) + " " + formattedRhs).trim();
            }
            return formattedRhs;
        }
        if (element instanceof ConstantDefinition constDef) {
            var topMeta = constDef.getMetadataMap();
            var schemaType = constDef.getSchemaType();
            if (schemaType == null) {
                return topMeta != null ? formatCompactMetadataMap(topMeta) : "";
            }
            String formattedRhs = formatCompactPsiSchemaType(schemaType);
            if (topMeta != null && schemaType.getMetadataMap() == null) {
                return (formatCompactMetadataMap(topMeta) + " " + formattedRhs).trim();
            }
            return formattedRhs;
        }
        if (element instanceof SchemaType schemaType) {
            return formatCompactPsiSchemaType(schemaType);
        }
        if (element instanceof SchemaConstructor ctor) {
            return formatCompactPsiConstructor(ctor);
        }
        if (element instanceof MetadataMap metaMap) {
            return formatCompactMetadataMap(metaMap);
        }
        if (element instanceof MetadataEntry entry) {
            return formatSanitizedMetadataEntry(entry, true);
        }
        return formatFallbackPsi(element);
    }

    /**
     * Formats a pure-IR {@link StvnSchema} into canonical STVN 2.0.0 zero-bracket-padded structural notation
     * without outer nominal alias names (for example, {@code ":Tuple(:Int :String)"} and {@code ":Enum[#A #B]"}),
     * emitting all non-default metadata facets in canonical § 6.1 5-tier decimal order ({@code 1.1.1} through {@code 4.4}).
     *
     * @param schema pure-IR {@link StvnSchema}, or {@code null}
     * @return zero-bracket-padded canonical STVN 2.0.0 schema string, or empty string if {@code null}
     */
    public static @NotNull String formatCompactCanonicalIrSchema(@Nullable StvnSchema schema) {
        if (schema == null) {
            return "";
        }
        if (schema instanceof StvnSchema.PoisonedSchema p) {
            return p.bestEffortFallback()
                .map(StvnSchemaFormatter::formatCompactCanonicalIrSchema)
                .orElseGet(() -> p.aliasName().orElse("<poisoned>"));
        }
        if (schema instanceof StvnSchema.EnumSubsetSchema es) {
            Optional<String> parentAlias = es.parentEnum().aliasName().or(() -> es.rootEnum().aliasName());
            if (parentAlias.isEmpty()) {
                List<String> traitOnlyFacets = new ArrayList<>(2);
                appendIrTraitFacets(es.traits(), traitOnlyFacets);
                String activeEnum = formatCompactEnumVariants(es.activeVariants());
                return traitOnlyFacets.isEmpty()
                    ? activeEnum
                    : formatFacetListBlock(traitOnlyFacets) + " " + activeEnum;
            }
            List<String> subsetFacets = collectCompactIrFacets(es);
            return subsetFacets.isEmpty()
                ? parentAlias.get()
                : formatFacetListBlock(subsetFacets) + " " + parentAlias.get();
        }
        List<String> facets = collectCompactIrFacets(schema);
        String ctorBody = formatCompactIrConstructorOnly(schema);
        if (facets.isEmpty()) {
            return ctorBody;
        }
        return formatFacetListBlock(facets) + " " + ctorBody;
    }

    private static String formatFacetListBlock(List<String> facets) {
        return StvnVocabulary.DELIM_OPEN_BRACE + " " + String.join(" ", facets) + " " + StvnVocabulary.DELIM_CLOSE_BRACE;
    }

    private static String formatCompactIrConstructorOnly(StvnSchema schema) {
        return switch (schema) {
            case StvnSchema.BooleanSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
            case StvnSchema.IntSchema ignored -> StvnVocabulary.TYPE_INT;
            case StvnSchema.FloatSchema ignored -> StvnVocabulary.TYPE_FLOAT;
            case StvnSchema.StringSchema ignored -> StvnVocabulary.TYPE_STRING;
            case StvnSchema.TimeEpochSchema ignored -> StvnVocabulary.TYPE_TIME_EPOCH;
            case StvnSchema.DateTimeSchema ignored -> StvnVocabulary.TYPE_DATE_TIME;
            case StvnSchema.EnumSchema e -> formatCompactEnumVariants(e.rootVariants());
            case StvnSchema.EnumSubsetSchema es -> formatCompactEnumVariants(es.activeVariants());
            case StvnSchema.SeqSchema s ->
                StvnVocabulary.TYPE_SEQ + StvnVocabulary.DELIM_OPEN_PAREN
                    + formatCompactIrChild(s.elementSchema()) + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.SetSchema s ->
                StvnVocabulary.TYPE_SET + StvnVocabulary.DELIM_OPEN_PAREN
                    + formatCompactIrChild(s.elementSchema()) + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.MapSchema m ->
                StvnVocabulary.TYPE_MAP + StvnVocabulary.DELIM_OPEN_PAREN
                    + formatCompactIrChild(m.keySchema()) + " " + formatCompactIrChild(m.valueSchema())
                    + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.TupleSchema t ->
                StvnVocabulary.TYPE_TUPLE + StvnVocabulary.DELIM_OPEN_PAREN
                    + t.fieldSchemas().stream()
                        .map(StvnSchemaFormatter::formatCompactIrChild)
                        .collect(Collectors.joining(" "))
                    + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.OptionSchema o ->
                StvnVocabulary.TYPE_OPTION + StvnVocabulary.DELIM_OPEN_PAREN
                    + formatCompactIrChild(o.valueSchema()) + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.EitherSchema e ->
                StvnVocabulary.TYPE_EITHER + StvnVocabulary.DELIM_OPEN_PAREN
                    + formatCompactIrChild(e.leftSchema()) + " " + formatCompactIrChild(e.rightSchema())
                    + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.UnionSchema u ->
                StvnVocabulary.TYPE_UNION + StvnVocabulary.DELIM_OPEN_PAREN
                    + u.branchSchemas().stream()
                        .map(StvnSchemaFormatter::formatCompactIrChild)
                        .collect(Collectors.joining(" "))
                    + StvnVocabulary.DELIM_CLOSE_PAREN;
            case StvnSchema.PoisonedSchema p ->
                p.bestEffortFallback()
                    .map(StvnSchemaFormatter::formatCompactCanonicalIrSchema)
                    .orElseGet(() -> p.aliasName().orElse("<poisoned>"));
        };
    }

    private static List<String> collectCompactIrFacets(StvnSchema schema) {
        List<String> facets = new ArrayList<>();

        // Tier 1: Definition (1.1.1 .. 1.5.2)
        switch (schema) {
            case StvnSchema.IntSchema i -> {
                if (i.unsigned()) {
                    facets.add(StvnVocabulary.FACET_KW_UNSIGNED);
                }
                if (!i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
                    facets.add(StvnVocabulary.FACET_KW_SIZE + " " + i.bitWidth());
                }
            }
            case StvnSchema.FloatSchema f -> {
                if (f.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
                    facets.add(StvnVocabulary.FACET_KW_EXACT);
                }
                if (f.representation() == StvnSchema.FloatRepresentation.IEEE_32) {
                    facets.add(StvnVocabulary.FACET_KW_SIZE + " " + THIRTY_TWO);
                }
                if (f.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL
                        && !f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                    facets.add(StvnVocabulary.FACET_KW_MAX_SIZE + " " + f.exactMaxDigits());
                }
            }
            case StvnSchema.MapSchema map -> {
                if (map.invertible()) {
                    facets.add(StvnVocabulary.FACET_KW_INVERTIBLE);
                }
                if (map.minSize().signum() > 0) {
                    facets.add(StvnVocabulary.FACET_KW_MIN_SIZE + " " + map.minSize());
                }
                if (!map.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                    facets.add(StvnVocabulary.FACET_KW_MAX_SIZE + " " + map.maxSize());
                }
            }
            case StvnSchema.TimeEpochSchema te -> {
                String scale = switch (te.scale()) {
                    case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
                    case US -> StvnVocabulary.FACET_KW_SCALE_US;
                    case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
                    case S -> StvnVocabulary.FACET_KW_SCALE_S;
                };
                facets.add(scale);
            }
            case StvnSchema.DateTimeSchema dt -> {
                String mode = switch (dt.mode()) {
                    case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
                    case ZONED -> StvnVocabulary.FACET_KW_ZONED;
                    case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
                };
                facets.add(mode);
            }
            case StvnSchema.StringSchema s -> {
                if (s.minSize().signum() > 0) {
                    facets.add(StvnVocabulary.FACET_KW_MIN_SIZE + " " + s.minSize());
                }
                if (!s.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                    facets.add(StvnVocabulary.FACET_KW_MAX_SIZE + " " + s.maxSize());
                }
            }
            case StvnSchema.SeqSchema seq -> {
                if (seq.minSize().signum() > 0) {
                    facets.add(StvnVocabulary.FACET_KW_MIN_SIZE + " " + seq.minSize());
                }
                if (!seq.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                    facets.add(StvnVocabulary.FACET_KW_MAX_SIZE + " " + seq.maxSize());
                }
            }
            case StvnSchema.SetSchema set -> {
                if (set.minSize().signum() > 0) {
                    facets.add(StvnVocabulary.FACET_KW_MIN_SIZE + " " + set.minSize());
                }
                if (!set.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                    facets.add(StvnVocabulary.FACET_KW_MAX_SIZE + " " + set.maxSize());
                }
            }
            default -> {
            }
        }

        // Tier 2: Trait (2.1 .. 2.2)
        appendIrTraitFacets(schema.traits(), facets);

        // Tier 3: Bounds (3.1 .. 3.4)
        switch (schema) {
            case StvnSchema.IntSchema i -> {
                i.explicitMinIncl().ifPresent(min -> facets.add(StvnVocabulary.FACET_KW_MIN_INCL + " " + min));
                i.explicitMaxExcl().ifPresent(max -> facets.add(StvnVocabulary.FACET_KW_MAX_EXCL + " " + max));
            }
            case StvnSchema.FloatSchema f -> {
                f.lowerBound().ifPresent(lb -> {
                    String kw = lb.kind() == StvnSchema.BoundKind.INCLUSIVE
                        ? StvnVocabulary.FACET_KW_MIN_INCL
                        : StvnVocabulary.FACET_KW_MIN_EXCL;
                    facets.add(kw + " " + formatCanonicalDecimalBound(lb.value()));
                });
                f.upperBound().ifPresent(ub -> {
                    String kw = ub.kind() == StvnSchema.BoundKind.INCLUSIVE
                        ? StvnVocabulary.FACET_KW_MAX_INCL
                        : StvnVocabulary.FACET_KW_MAX_EXCL;
                    facets.add(kw + " " + formatCanonicalDecimalBound(ub.value()));
                });
            }
            case StvnSchema.StringSchema s -> {
                s.lowerBound().ifPresent(lb -> {
                    String kw = lb.kind() == StvnSchema.BoundKind.INCLUSIVE
                        ? StvnVocabulary.FACET_KW_MIN_INCL
                        : StvnVocabulary.FACET_KW_MIN_EXCL;
                    facets.add(kw + " " + StvnPsiLiteralEvaluator.formatCanonicalQuotedString(lb.value()));
                });
                s.upperBound().ifPresent(ub -> {
                    String kw = ub.kind() == StvnSchema.BoundKind.INCLUSIVE
                        ? StvnVocabulary.FACET_KW_MAX_INCL
                        : StvnVocabulary.FACET_KW_MAX_EXCL;
                    facets.add(kw + " " + StvnPsiLiteralEvaluator.formatCanonicalQuotedString(ub.value()));
                });
            }
            case StvnSchema.TimeEpochSchema te -> {
                te.explicitMinIncl().ifPresent(min -> facets.add(StvnVocabulary.FACET_KW_MIN_INCL + " " + min));
                te.explicitMaxExcl().ifPresent(max -> facets.add(StvnVocabulary.FACET_KW_MAX_EXCL + " " + max));
            }
            case StvnSchema.DateTimeSchema dt -> {
                dt.explicitMinInclIso().ifPresent(minIso ->
                    facets.add(StvnVocabulary.FACET_KW_MIN_INCL + " " + StvnPsiLiteralEvaluator.formatCanonicalQuotedString(minIso)));
                dt.explicitMaxExclIso().ifPresent(maxIso ->
                    facets.add(StvnVocabulary.FACET_KW_MAX_EXCL + " " + StvnPsiLiteralEvaluator.formatCanonicalQuotedString(maxIso)));
            }
            default -> {
            }
        }

        // Tier 4: Constraint (4.1 #filterIncl -> 4.2 #filterExcl -> 4.3 #preserveIndent -> 4.4 #regex)
        switch (schema) {
            case StvnSchema.EnumSubsetSchema sub -> {
                String filterKw = sub.inclusiveFilter()
                    ? StvnVocabulary.FACET_KW_FILTER_INCL
                    : StvnVocabulary.FACET_KW_FILTER_EXCL;
                facets.add(
                    filterKw + " "
                        + StvnVocabulary.DELIM_OPEN_BRACKET
                        + String.join(" ", sub.declaredFilterList())
                        + StvnVocabulary.DELIM_CLOSE_BRACKET
                );
            }
            case StvnSchema.StringSchema s -> {
                if (s.preserveIndent()) {
                    facets.add(StvnVocabulary.FACET_KW_PRESERVE_INDENT + " " + StvnVocabulary.VAL_TRUE);
                }
                s.rawRegexPattern().ifPresent(rx ->
                    facets.add(StvnVocabulary.FACET_KW_REGEX + " " + StvnPsiLiteralEvaluator.formatCanonicalQuotedString(rx)));
            }
            default -> {
            }
        }

        return facets;
    }

    private static void appendIrTraitFacets(StvnSchema.TraitProfile traits, List<String> facets) {
        traits.explicitEquatableOverride().ifPresent(eq ->
            facets.add(StvnVocabulary.FACET_KW_EQUATABLE + " " + (eq ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE)));
        traits.explicitComparableOverride().ifPresent(cmp ->
            facets.add(StvnVocabulary.FACET_KW_COMPARABLE + " " + (cmp ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE)));
    }

    private static String formatCanonicalDecimalBound(BigDecimal value) {
        String plain = value.toPlainString();
        return plain.indexOf('.') >= 0 ? plain : plain + ".0";
    }

    private static String formatCompactIrChild(StvnSchema child) {
        return child.aliasName().orElseGet(() -> formatCompactCanonicalIrSchema(child));
    }

    private static String formatCompactEnumVariants(List<String> variants) {
        return StvnVocabulary.TYPE_ENUM
            + StvnVocabulary.DELIM_OPEN_BRACKET
            + String.join(" ", variants)
            + StvnVocabulary.DELIM_CLOSE_BRACKET;
    }

    private static String formatCompactPsiSchemaType(SchemaType schemaType) {
        var meta = schemaType.getMetadataMap();
        var metaPrefix = meta != null ? formatCompactMetadataMap(meta) + " " : "";
        String body;
        var keyword = schemaType.getTypeKeyword();
        if (keyword != null) {
            body = keyword.getText().trim();
        } else {
            var ctor = schemaType.getSchemaConstructor();
            body = ctor != null ? formatCompactPsiConstructor(ctor) : formatFallbackPsi(schemaType);
        }
        return (metaPrefix + body).trim();
    }

    private static String formatCompactMetadataMap(MetadataMap metaMap) {
        var entries = new ArrayList<>(metaMap.getMetadataEntryList());
        if (entries.isEmpty()) {
            return StvnVocabulary.DELIM_OPEN_BRACE + StvnVocabulary.DELIM_CLOSE_BRACE;
        }
        entries.sort(Comparator.comparingInt(StvnSchemaFormatter::resolveEntryFacetRank));
        var list = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            var clean = formatSanitizedMetadataEntry(entry, true);
            if (!clean.isEmpty()) {
                list.add(clean);
            }
        }
        return StvnVocabulary.DELIM_OPEN_BRACE + " " + String.join(" ", list) + " " + StvnVocabulary.DELIM_CLOSE_BRACE;
    }

    private static String formatCompactPsiConstructor(SchemaConstructor ctor) {
        var atomic = ctor.getAtomicType();
        if (atomic != null) {
            return atomic.getText().trim();
        }
        var sum = ctor.getSumType();
        if (sum != null) {
            if (sum.getNode().findChildByType(StvnTypes.KW_OPTION) != null) {
                return formatCompactParenthesizedCtor(
                    StvnVocabulary.TYPE_OPTION,
                    PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class)
                );
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_EITHER) != null) {
                return formatCompactParenthesizedCtor(
                    StvnVocabulary.TYPE_EITHER,
                    PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class)
                );
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_UNION) != null) {
                return formatCompactParenthesizedCtor(
                    StvnVocabulary.TYPE_UNION,
                    PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class)
                );
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_ENUM) != null) {
                var enumDef = sum.getEnumDef();
                if (enumDef != null) {
                    var list = new ArrayList<String>();
                    for (var kw : enumDef.getValueKeywordList()) {
                        list.add(kw.getText().trim());
                    }
                    return formatCompactEnumVariants(list);
                }
                return StvnVocabulary.TYPE_ENUM;
            }
        }
        var prod = ctor.getProductType();
        if (prod != null) {
            return formatCompactParenthesizedCtor(StvnVocabulary.TYPE_TUPLE, prod.getSchemaTypeList());
        }
        var coll = ctor.getCollectionType();
        if (coll != null) {
            var first = coll.getFirstChild();
            var collName = first != null ? first.getText().trim() : StvnVocabulary.TYPE_SEQ;
            return formatCompactParenthesizedCtor(collName, coll.getSchemaTypeList());
        }
        return formatFallbackPsi(ctor);
    }

    private static String formatCompactParenthesizedCtor(String ctorName, List<SchemaType> children) {
        if (children.isEmpty()) {
            return ctorName + StvnVocabulary.DELIM_OPEN_PAREN + StvnVocabulary.DELIM_CLOSE_PAREN;
        }
        var formattedChildren = new ArrayList<String>(children.size());
        for (var child : children) {
            formattedChildren.add(formatCompactPsiSchemaType(child));
        }
        return ctorName
            + StvnVocabulary.DELIM_OPEN_PAREN
            + String.join(" ", formattedChildren)
            + StvnVocabulary.DELIM_CLOSE_PAREN;
    }
}
