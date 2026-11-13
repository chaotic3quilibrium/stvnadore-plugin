package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility for extracting sanitized, comment-free, canonically formatted schema representations
 * from IntelliJ PSI trees and STVN 2.0.0 pure-IR {@link StvnSchema} records.
 */
@NullMarked
public final class StvnSchemaFormatter {

    private static final Pattern COMMENT_PATTERN = Pattern.compile("//[^\\r\\n]*");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private StvnSchemaFormatter() {
    }

    /**
     * Extracts a clean, canonically formatted schema type string from a PSI element,
     * preserving child metadata maps and enforcing standard delimiter padding.
     *
     * @param element the PSI element (SchemaType, SchemaConstructor, TypeDefinition, etc.)
     * @return the cleanly formatted canonical schema signature, or an empty string if null
     */
    public static @NotNull String formatSchema(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        if (element instanceof TypeDefinition typeDef) {
            return formatSchema(typeDef.getSchemaType());
        }
        if (element instanceof ConstantDefinition constDef) {
            return formatSchema(constDef.getSchemaType());
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

    private static String formatMetadataMap(MetadataMap metaMap) {
        var entries = metaMap.getMetadataEntryList();
        if (entries.isEmpty()) {
            return "{}";
        }
        var list = new ArrayList<String>();
        for (var entry : entries) {
            var raw = entry.getText();
            var stripped = COMMENT_PATTERN.matcher(raw).replaceAll("");
            var clean = WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
            if (!clean.isEmpty()) {
                list.add(clean);
            }
        }
        return "{ " + String.join(" ", list) + " }";
    }

    private static String formatFallbackPsi(PsiElement element) {
        var raw = element.getText();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        var stripped = COMMENT_PATTERN.matcher(raw).replaceAll("");
        return WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
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
}
