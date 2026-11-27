package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.Value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure Java 21 CPU-bias-free PSI literal evaluator for standalone editor inspections,
 * hover cards, and completion contributors.
 */
@NullMarked
public final class StvnPsiLiteralEvaluator {

    /** Maximum bit-width shifted eagerly into decimal range bounds during live editor analysis. */
    public static final int MAX_EAGER_SHIFT_BIT_WIDTH = 16_384;

    private static final int MAX_CONST_RESOLUTION_DEPTH = 16;
    private static final Pattern VALID_FENCE_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

    private StvnPsiLiteralEvaluator() {
    }

    /**
     * Parses an STVN integer token (decimal, {@code 0x}, {@code 0b}, or {@code 0o}) into a {@link BigInteger}.
     *
     * @param rawText raw PSI token text
     * @return parsed {@link BigInteger}, or empty if syntactically incomplete or invalid
     */
    public static Optional<BigInteger> parseBigInteger(@Nullable String rawText) {
        if (rawText == null) {
            return Optional.empty();
        }
        String s = rawText.trim().replace("_", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        boolean neg = false;
        if (s.startsWith("-")) {
            neg = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            BigInteger mag;
            if (s.startsWith("0x") || s.startsWith("0X")) {
                mag = new BigInteger(s.substring(2), 16);
            } else if (s.startsWith("0b") || s.startsWith("0B")) {
                mag = new BigInteger(s.substring(2), 2);
            } else if (s.startsWith("0o") || s.startsWith("0O")) {
                mag = new BigInteger(s.substring(2), 8);
            } else {
                mag = new BigInteger(s, 10);
            }
            return Optional.of(neg ? mag.negate() : mag);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Parses an STVN floating-point or exact decimal token into a {@link BigDecimal}.
     *
     * @param rawText raw PSI token text
     * @return parsed {@link BigDecimal}, or empty if non-finite or malformed
     */
    public static Optional<BigDecimal> parseBigDecimal(@Nullable String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(rawText.trim().replace("_", "")));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Resolves an STVN integer literal or a {@code #CONST} reference chain to a {@link BigInteger}.
     *
     * @param context PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText raw literal or {@code #CONST} token text
     * @return resolved {@link BigInteger}, or empty if unresolvable or cyclic
     */
    public static Optional<BigInteger> resolveBigIntegerOrConst(@Nullable PsiElement context, @Nullable String rawText) {
        return resolveBigIntegerOrConstInternal(context, rawText, new HashSet<>());
    }

    private static Optional<BigInteger> resolveBigIntegerOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        Optional<BigInteger> direct = parseBigInteger(trimmed);
        if (direct.isPresent()) {
            return direct;
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveBigIntegerOrConstInternal(val, val.getText(), visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves an STVN decimal/floating-point literal or a {@code #CONST} reference chain to a {@link BigDecimal}.
     *
     * @param context PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText raw literal or {@code #CONST} token text
     * @return resolved {@link BigDecimal}, or empty if unresolvable or cyclic
     */
    public static Optional<BigDecimal> resolveBigDecimalOrConst(@Nullable PsiElement context, @Nullable String rawText) {
        return resolveBigDecimalOrConstInternal(context, rawText, new HashSet<>());
    }

    private static Optional<BigDecimal> resolveBigDecimalOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        Optional<BigDecimal> direct = parseBigDecimal(trimmed);
        if (direct.isPresent()) {
            return direct;
        }
        Optional<BigInteger> intVal = parseBigInteger(trimmed);
        if (intVal.isPresent()) {
            return Optional.of(new BigDecimal(intVal.get()));
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveBigDecimalOrConstInternal(val, val.getText(), visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves an STVN string literal (simple or fenced) or a {@code #CONST} reference chain to its decoded UTF-8 string.
     *
     * @param context        PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText        raw string literal or {@code #CONST} token text
     * @param preserveIndent {@code true} when {@code #preserveIndent #TRUE} retains common leading spaces
     * @return decoded string value, or empty if unresolvable or cyclic
     */
    public static Optional<String> resolveStringOrConst(
        @Nullable PsiElement context,
        @Nullable String rawText,
        boolean preserveIndent
    ) {
        return resolveStringOrConstInternal(context, rawText, preserveIndent, new HashSet<>());
    }

    private static Optional<String> resolveStringOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        boolean preserveIndent,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        Optional<String> decoded = decodeStringLiteral(trimmed, preserveIndent);
        if (decoded.isPresent()) {
            return decoded;
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                boolean constPreserveIndent = extractPreserveIndentFromSchema(constDef.getSchemaType());
                return resolveStringOrConstInternal(val, val.getText(), constPreserveIndent, visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the effective {@code #preserveIndent} flag declared on a PSI {@link SchemaType} chain.
     *
     * @param schemaType governing PSI schema type, or {@code null}
     * @return {@code true} when {@code #preserveIndent} is enabled; {@code false} otherwise
     */
    public static boolean extractPreserveIndentFromSchema(@Nullable SchemaType schemaType) {
        return extractPreserveIndentFromSchema(schemaType, new HashSet<>());
    }

    private static boolean extractPreserveIndentFromSchema(@Nullable SchemaType schemaType, Set<PsiElement> visited) {
        if (schemaType == null || !visited.add(schemaType)) {
            return false;
        }
        MetadataMap metaMap = schemaType.getMetadataMap();
        if (metaMap == null && schemaType.getParent() instanceof TypeDefinition td) {
            metaMap = td.getMetadataMap();
        }
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                String text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                    return true;
                }
                if (text.startsWith(StvnVocabulary.FACET_KW_PRESERVE_INDENT)
                        && Character.isWhitespace(text.charAt(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()))) {
                    String arg = text.substring(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()).trim();
                    return resolveBooleanOrConst(entry, arg).orElse(false);
                }
            }
        }
        if (schemaType.getTypeKeyword() != null && schemaType.getContainingFile() != null) {
            String alias = schemaType.getTypeKeyword().getText().trim();
            var parentDef = StvnTypeReference.resolveTypeDefinitionAtContext(schemaType.getTypeKeyword(), alias);
            if (parentDef != null && visited.add(parentDef)) {
                if (parentDef.getMetadataMap() != null) {
                    for (var entry : parentDef.getMetadataMap().getMetadataEntryList()) {
                        String text = entry.getText().trim();
                        if (text.equals(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                            return true;
                        }
                        if (text.startsWith(StvnVocabulary.FACET_KW_PRESERVE_INDENT)
                                && Character.isWhitespace(text.charAt(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()))) {
                            String arg = text.substring(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()).trim();
                            return resolveBooleanOrConst(entry, arg).orElse(false);
                        }
                    }
                }
                return extractPreserveIndentFromSchema(parentDef.getSchemaType(), visited);
            }
        }
        return false;
    }

    /**
     * Resolves the effective {@code #preserveIndent} flag governing a PSI string literal or leaf element.
     * <p>
     * Per STVN 2.0.0 Specification Section 5.3 item 6, inline string metadata facets
     * ({@code #regex}, {@code #minIncl}, {@code #minExcl}, {@code #maxIncl}, {@code #maxExcl})
     * always strip common leading ASCII-space indentation ({@code false}), even when the owning
     * {@code :String} declaration specifies {@code #preserveIndent #TRUE}.
     *
     * @param element PSI {@link StringLiteral} or enclosed leaf token
     * @return {@code true} if {@code #preserveIndent #TRUE} governs this literal; {@code false} otherwise
     */
    public static boolean resolvePreserveIndentForStringLiteral(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        StringLiteral strLit = element instanceof StringLiteral sl
            ? sl
            : PsiTreeUtil.getParentOfType(element, StringLiteral.class, false);
        PsiElement anchor = strLit != null ? strLit : element;

        if (PsiTreeUtil.getParentOfType(anchor, MetadataEntry.class, false) != null
                || PsiTreeUtil.getParentOfType(anchor, MetaEntry.class, false) != null
                || PsiTreeUtil.getParentOfType(anchor, IncludeStmt.class, false) != null) {
            return false;
        }

        Value enclosingValue = PsiTreeUtil.getParentOfType(anchor, Value.class, false);
        if (enclosingValue == null) {
            return false;
        }
        ConstantDefinition constDef = PsiTreeUtil.getParentOfType(enclosingValue, ConstantDefinition.class, false);
        boolean inBody = PsiTreeUtil.getParentOfType(enclosingValue, BodyEntry.class, false) != null;
        if (constDef == null && !inBody) {
            return false;
        }
        if (constDef != null && (constDef.getValue() == null || !PsiTreeUtil.isAncestor(constDef.getValue(), enclosingValue, false))) {
            return false;
        }

        StvnValue coreVal = unwrapSumIrValue(StvnTypeResolver.resolveCoreValue(enclosingValue));
        if (coreVal instanceof StvnValue.StvnString stvnStr
                && unwrapPoisonedIrSchema(stvnStr.schema()) instanceof StvnSchema.StringSchema ss) {
            return ss.preserveIndent();
        }
        if (coreVal instanceof StvnValue.StvnErrorValue errVal
                && unwrapPoisonedIrSchema(errVal.schema()) instanceof StvnSchema.StringSchema ss) {
            return ss.preserveIndent();
        }

        SchemaType govSchema = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(enclosingValue);
        if (govSchema == null) {
            return false;
        }
        Boolean fromIr = resolveStringPreserveIndentFromIr(resolveEffectiveIrSchema(govSchema));
        if (fromIr != null) {
            if (!fromIr && govSchema.getMetadataMap() != null && extractPreserveIndentFromSchema(govSchema)) {
                return true;
            }
            return fromIr;
        }
        return resolvePreserveIndentFromPsiWithImplicitSums(govSchema, new HashSet<>());
    }

    /**
     * Determines whether a PSI string literal or leaf element is governed by a {@code :DateTime}
     * schema (including {@code MetadataEntry} bounds, multi-hop nominal aliases, {@code :use}
     * {@code UseMapAlias} imports, composite slots, and explicit or implicit sum branches).
     *
     * @param element PSI {@link StringLiteral} or enclosed leaf token
     * @return {@code true} when {@code :DateTime} governs this literal and fenced strings are prohibited
     */
    public static boolean isDateTimeGovernedStringLiteral(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        StringLiteral strLit = element instanceof StringLiteral sl
            ? sl
            : PsiTreeUtil.getParentOfType(element, StringLiteral.class, false);
        PsiElement anchor = strLit != null ? strLit : element;

        MetadataEntry metaEntry = PsiTreeUtil.getParentOfType(anchor, MetadataEntry.class, false);
        if (metaEntry != null) {
            SchemaType enclosingSchema = PsiTreeUtil.getParentOfType(metaEntry, SchemaType.class, false);
            return isDateTimeSchemaType(enclosingSchema);
        }

        Value enclosingValue = PsiTreeUtil.getParentOfType(anchor, Value.class, false);
        if (enclosingValue == null) {
            return false;
        }
        StvnValue coreVal = unwrapSumIrValue(StvnTypeResolver.resolveCoreValue(enclosingValue));
        if (coreVal instanceof StvnValue.StvnDateTime) {
            return true;
        }
        if (coreVal instanceof StvnValue.StvnString) {
            return false;
        }
        if (coreVal instanceof StvnValue.StvnErrorValue errVal) {
            StvnSchema errSchema = unwrapPoisonedIrSchema(errVal.schema());
            if (errSchema instanceof StvnSchema.DateTimeSchema) {
                return true;
            }
            if (errSchema instanceof StvnSchema.StringSchema) {
                return false;
            }
        }

        SchemaType govSchema = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(enclosingValue);
        return isDateTimeSchemaOrImplicitSumTarget(govSchema);
    }

    private static @Nullable StvnValue unwrapSumIrValue(@Nullable StvnValue value) {
        StvnValue cur = value;
        while (cur != null) {
            if (cur instanceof StvnValue.StvnOption opt && opt.value().isPresent()) {
                cur = opt.value().get();
            } else if (cur instanceof StvnValue.StvnEither eith) {
                cur = eith.value();
            } else if (cur instanceof StvnValue.StvnUnion un) {
                cur = un.value();
            } else {
                break;
            }
        }
        return cur;
    }

    private static @Nullable StvnSchema unwrapPoisonedIrSchema(@Nullable StvnSchema schema) {
        StvnSchema cur = schema;
        while (cur instanceof StvnSchema.PoisonedSchema p) {
            cur = p.bestEffortFallback().orElse(null);
        }
        return cur;
    }

    private static @Nullable StvnSchema resolveEffectiveIrSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return null;
        }
        StvnSchema direct = unwrapPoisonedIrSchema(StvnTypeResolver.resolveIrSchema(schemaType));
        if (direct != null) {
            return direct;
        }
        if (schemaType.getTypeKeyword() != null && schemaType.getContainingFile() != null) {
            return unwrapPoisonedIrSchema(
                StvnTypeResolver.resolveIrSchemaAtContext(
                    schemaType.getTypeKeyword(),
                    schemaType.getTypeKeyword().getText().trim()
                )
            );
        }
        return null;
    }

    private static @Nullable Boolean resolveStringPreserveIndentFromIr(@Nullable StvnSchema rawSchema) {
        StvnSchema schema = unwrapPoisonedIrSchema(rawSchema);
        if (schema instanceof StvnSchema.StringSchema ss) {
            return ss.preserveIndent();
        }
        if (schema instanceof StvnSchema.DateTimeSchema) {
            return false;
        }
        if (schema instanceof StvnSchema.OptionSchema opt) {
            return resolveStringPreserveIndentFromIr(opt.valueSchema());
        }
        if (schema instanceof StvnSchema.EitherSchema eith) {
            Boolean right = resolveStringPreserveIndentFromIr(eith.rightSchema());
            return right != null ? right : resolveStringPreserveIndentFromIr(eith.leftSchema());
        }
        if (schema instanceof StvnSchema.UnionSchema un) {
            Boolean uniqueMatch = null;
            int stringBranches = 0;
            for (StvnSchema branch : un.branchSchemas()) {
                Boolean cand = resolveStringPreserveIndentFromIr(branch);
                if (cand != null && !containsDateTimeIrBranch(branch)) {
                    uniqueMatch = cand;
                    stringBranches++;
                }
            }
            return stringBranches == 1 ? uniqueMatch : null;
        }
        return null;
    }

    private static boolean containsDateTimeIrBranch(@Nullable StvnSchema rawSchema) {
        StvnSchema s = unwrapPoisonedIrSchema(rawSchema);
        if (s instanceof StvnSchema.DateTimeSchema) {
            return true;
        }
        if (s instanceof StvnSchema.OptionSchema opt) {
            return containsDateTimeIrBranch(opt.valueSchema());
        }
        if (s instanceof StvnSchema.EitherSchema eith) {
            return containsDateTimeIrBranch(eith.rightSchema());
        }
        if (s instanceof StvnSchema.UnionSchema un) {
            return un.branchSchemas().stream().anyMatch(StvnPsiLiteralEvaluator::containsDateTimeIrBranch);
        }
        return false;
    }

    private static boolean isDateTimeSchemaType(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return false;
        }
        StvnSchema ir = resolveEffectiveIrSchema(schemaType);
        if (ir instanceof StvnSchema.DateTimeSchema) {
            return true;
        }
        if (ir instanceof StvnSchema.StringSchema) {
            return false;
        }
        SchemaType terminal = StvnTypeResolver.unwrapNominalSchemaChain(schemaType);
        SchemaType check = terminal != null ? terminal : schemaType;
        SchemaConstructor ctor = check.getSchemaConstructor();
        AtomicType atom = ctor != null ? ctor.getAtomicType() : null;
        return atom != null && StvnVocabulary.TYPE_DATE_TIME.equals(atom.getText().trim());
    }

    private static boolean isDateTimeSchemaOrImplicitSumTarget(@Nullable SchemaType govSchema) {
        if (govSchema == null) {
            return false;
        }
        StvnSchema ir = resolveEffectiveIrSchema(govSchema);
        if (ir != null) {
            return isIrUntaggedStringTargetingDateTime(ir);
        }
        return isPsiUntaggedStringTargetingDateTime(govSchema, new HashSet<>());
    }

    private static boolean isIrUntaggedStringTargetingDateTime(@Nullable StvnSchema rawSchema) {
        StvnSchema s = unwrapPoisonedIrSchema(rawSchema);
        if (s instanceof StvnSchema.DateTimeSchema) {
            return true;
        }
        if (s instanceof StvnSchema.OptionSchema opt) {
            return isIrUntaggedStringTargetingDateTime(opt.valueSchema());
        }
        if (s instanceof StvnSchema.EitherSchema eith) {
            return isIrUntaggedStringTargetingDateTime(eith.rightSchema());
        }
        if (s instanceof StvnSchema.UnionSchema un) {
            return un.branchSchemas().stream().anyMatch(StvnPsiLiteralEvaluator::isIrUntaggedStringTargetingDateTime);
        }
        return false;
    }

    private static boolean isPsiUntaggedStringTargetingDateTime(SchemaType schema, Set<PsiElement> visited) {
        if (!visited.add(schema)) {
            return false;
        }
        if (isDateTimeSchemaType(schema)) {
            return true;
        }
        SchemaType terminal = StvnTypeResolver.unwrapNominalSchemaChain(schema);
        SchemaConstructor ctor = terminal != null ? terminal.getSchemaConstructor() : schema.getSchemaConstructor();
        SumType sum = ctor != null ? ctor.getSumType() : null;
        if (sum == null) {
            return false;
        }
        var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
        String sumText = sum.getText().trim();
        if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && !branches.isEmpty()) {
            return isPsiUntaggedStringTargetingDateTime(branches.get(0), visited);
        }
        if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() >= 2) {
            return isPsiUntaggedStringTargetingDateTime(branches.get(1), visited);
        }
        if (sumText.startsWith(StvnVocabulary.TYPE_UNION)) {
            for (SchemaType b : branches) {
                if (isPsiUntaggedStringTargetingDateTime(b, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean resolvePreserveIndentFromPsiWithImplicitSums(SchemaType schema, Set<PsiElement> visited) {
        if (!visited.add(schema)) {
            return false;
        }
        if (extractPreserveIndentFromSchema(schema)) {
            return true;
        }
        SchemaType terminal = StvnTypeResolver.unwrapNominalSchemaChain(schema);
        SchemaConstructor ctor = terminal != null ? terminal.getSchemaConstructor() : schema.getSchemaConstructor();
        SumType sum = ctor != null ? ctor.getSumType() : null;
        if (sum == null) {
            return false;
        }
        var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
        String sumText = sum.getText().trim();
        if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && !branches.isEmpty()) {
            return resolvePreserveIndentFromPsiWithImplicitSums(branches.get(0), visited);
        }
        if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() >= 2) {
            return resolvePreserveIndentFromPsiWithImplicitSums(branches.get(1), visited);
        }
        if (sumText.startsWith(StvnVocabulary.TYPE_UNION)) {
            Boolean uniqueStringPreserve = null;
            int stringCount = 0;
            for (SchemaType b : branches) {
                if (isPsiStringBranch(b)) {
                    uniqueStringPreserve = resolvePreserveIndentFromPsiWithImplicitSums(b, visited);
                    stringCount++;
                }
            }
            return stringCount == 1 && Boolean.TRUE.equals(uniqueStringPreserve);
        }
        return false;
    }

    private static boolean isPsiStringBranch(SchemaType schema) {
        SchemaType terminal = StvnTypeResolver.unwrapNominalSchemaChain(schema);
        SchemaType check = terminal != null ? terminal : schema;
        SchemaConstructor ctor = check.getSchemaConstructor();
        AtomicType atom = ctor != null ? ctor.getAtomicType() : null;
        return atom != null && StvnVocabulary.TYPE_STRING.equals(atom.getText().trim());
    }

    /**
     * Resolves an STVN boolean literal ({@code #TRUE}, {@code #T}, {@code #FALSE}, {@code #F}) or a
     * {@code #CONST} reference chain to its boolean value.
     *
     * @param context PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText raw boolean token or {@code #CONST} reference text
     * @return resolved boolean value, or empty if unresolvable or cyclic
     */
    public static Optional<Boolean> resolveBooleanOrConst(@Nullable PsiElement context, @Nullable String rawText) {
        return resolveBooleanOrConstInternal(context, rawText, new HashSet<>());
    }

    private static Optional<Boolean> resolveBooleanOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        if (StvnVocabulary.VAL_TRUE.equals(trimmed) || StvnVocabulary.VAL_TRUE_SHORT.equals(trimmed)) {
            return Optional.of(true);
        }
        if (StvnVocabulary.VAL_FALSE.equals(trimmed) || StvnVocabulary.VAL_FALSE_SHORT.equals(trimmed)) {
            return Optional.of(false);
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveBooleanOrConstInternal(val, val.getText(), visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a {@link ConstantDefinition} across local {@code :defs}, {@code :package}, {@code :use},
     * {@code :include}, and Standard Library Prelude scopes.
     *
     * @param context   originating PSI context element
     * @param constName constant token starting with {@code #}
     * @return resolved {@link ConstantDefinition}, or {@code null} if not found
     */
    public static @Nullable ConstantDefinition resolveConstantDefinition(@Nullable PsiElement context, String constName) {
        if (context == null || !constName.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return null;
        }
        return StvnConstantReference.resolveConstantDefinitionAtContext(context, constName);
    }

    /**
     * Resolves a 1-based union branch prefix (such as {@code #1} or {@code #2}) to a 0-based branch
     * list index without 32-bit integer truncation.
     *
     * @param tagText     raw union tag token text starting with {@code #}
     * @param branchCount number of branches declared on the governing {@code :Union}
     * @return 0-based branch index when {@code 1 <= index <= branchCount}, or empty otherwise
     */
    public static OptionalInt resolveZeroBasedUnionBranchIndex(@Nullable String tagText, int branchCount) {
        if (tagText == null || !tagText.startsWith(StvnVocabulary.SIGIL_VALUE) || branchCount <= 0) {
            return OptionalInt.empty();
        }
        String digits = tagText.substring(1);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }
        BigInteger oneBased = new BigInteger(digits);
        if (oneBased.compareTo(BigInteger.ONE) < 0
            || oneBased.compareTo(BigInteger.valueOf(branchCount)) > 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(oneBased.intValueExact() - 1);
    }

    /**
     * Evaluates the decoded character count of a PSI {@link StringLiteral} using default
     * common-indentation stripping for fenced block strings.
     *
     * @param literal PSI string literal node, or {@code null}
     * @return decoded character length, or {@code 0} when {@code literal} is {@code null}
     */
    public static int evaluateStringContentLength(@Nullable StringLiteral literal) {
        return evaluateStringContentLength(literal, false);
    }

    /**
     * Evaluates the decoded character count of a PSI {@link StringLiteral}.
     *
     * @param literal        PSI string literal node, or {@code null}
     * @param preserveIndent {@code true} when {@code #preserveIndent #TRUE} retains common leading spaces
     * @return decoded character length, or {@code 0} when {@code literal} is {@code null} or lexically malformed
     */
    public static int evaluateStringContentLength(@Nullable StringLiteral literal, boolean preserveIndent) {
        if (literal == null) {
            return 0;
        }
        return decodeStringLiteral(literal.getText(), preserveIndent)
            .map(s -> s.codePointCount(0, s.length()))
            .orElse(0);
    }

    /**
     * Decodes a simple {@code "..."} or fenced {@code """[TAG]...[TAG]"""} string token.
     *
     * @param rawLiteral     raw string literal PSI text
     * @param preserveIndent {@code true} when {@code #preserveIndent #TRUE} retains common leading spaces
     * @return decoded UTF-8 string value, or empty when unclosed or malformed in-flight
     */
    public static Optional<String> decodeStringLiteral(@Nullable String rawLiteral, boolean preserveIndent) {
        return StvnStringLiteralCodec.decodeStringLiteral(rawLiteral, preserveIndent);
    }

    /**
     * Strips common leading ASCII space ({@code 0x20}) indentation across non-empty lines while
     * preserving verbatim horizontal tabs ({@code 0x09}) per STVN 2.0.0 Rule {@code STR-04}.
     *
     * @param text LF-normalized fenced string payload
     * @return dedented string payload per STVN 2.0.0 Rule {@code STR-04}
     */
    public static String trimCommonIndent(String text) {
        return StvnStringLiteralCodec.trimCommonIndent(text);
    }

    /**
     * Evaluates whether a decoded simple string satisfies the STVN 2.0.0 Fenced-String Bias trigger
     * and 100% lossless round-trip soundness guard ({@code decode(print(s)) == s}).
     *
     * @param decoded        decoded UTF-16 string value
     * @param preserveIndent effective {@code #preserveIndent} flag for the target slot
     * @return {@code true} when fenced string emission is triggered and round-trip lossless
     */
    public static boolean shouldEmitFencedPretty(String decoded, boolean preserveIndent) {
        return StvnStringLiteralCodec.shouldEmitFencedPretty(decoded, preserveIndent);
    }

    /**
     * Checks whether {@code text} contains any non-empty line consisting exclusively of ASCII spaces ({@code 0x20}).
     *
     * @param text LF-normalized string content
     * @return {@code true} if any non-empty line contains only ASCII spaces
     */
    public static boolean hasNonEmptyAsciiSpaceOnlyLine(String text) {
        int lineStart = 0;
        int len = text.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n') {
                if (i > lineStart) {
                    boolean allSpaces = true;
                    for (int j = lineStart; j < i; j++) {
                        if (text.charAt(j) != ' ') {
                            allSpaces = false;
                            break;
                        }
                    }
                    if (allSpaces) {
                        return true;
                    }
                }
                lineStart = i + 1;
            }
        }
        return false;
    }

    /**
     * Selects a collision-free Rule {@code STR-04} fence tag ({@code baseTag}, {@code baseTag_1}, ...)
     * whose closing delimiter {@code [TAG]"""} does not appear in {@code decoded}.
     *
     * @param decoded decoded string body
     * @param baseTag preferred base tag ({@code RAW} or {@code REGEX})
     * @return collision-free fence tag
     */
    public static String chooseSafeFenceTag(String decoded, String baseTag) {
        String candidate = baseTag;
        int suffix = 0;
        while (decoded.contains("[" + candidate + "]\"\"\"")) {
            suffix++;
            candidate = baseTag + "_" + suffix;
        }
        return candidate;
    }

    /**
     * Determines whether a constant identifier ends with {@code REGEX} or {@code _REGEX}.
     *
     * @param rawConstIdentifier constant identifier text (local or FQNI)
     * @return {@code true} if the constant identifier denotes a regex pattern constant
     */
    public static boolean isRegexConstantIdentifier(String rawConstIdentifier) {
        int slashIdx = rawConstIdentifier.lastIndexOf('/');
        String localName = slashIdx >= 0
            ? rawConstIdentifier.substring(slashIdx + 1)
            : (rawConstIdentifier.startsWith(StvnVocabulary.SIGIL_VALUE)
                ? rawConstIdentifier.substring(StvnVocabulary.SIGIL_VALUE.length())
                : rawConstIdentifier);
        return localName.endsWith("_" + StvnVocabulary.FENCE_TAG_REGEX)
            || localName.endsWith(StvnVocabulary.FENCE_TAG_REGEX);
    }

    /**
     * Formats a decoded UTF-8 string as a canonical single-line STVN simple string literal {@code "..."}.
     *
     * @param decoded decoded string content
     * @return canonically escaped STVN quoted string literal
     */
    public static String formatCanonicalQuotedString(String decoded) {
        return StvnStringLiteralCodec.formatCanonicalQuotedString(decoded);
    }
}
