package org.stvnadore.plugin.reference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.hints.StvnTypeInferenceHelper;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.ir.StvnEnumSubsetInfo;
import org.stvnadore.plugin.ir.StvnSumTrajectory;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Resolves static and semantic types, nominal type aliases, sum-type trajectories,
 * enum subsets, and lowered pure-IR values for STVN PSI elements.
 */
@NullMarked
public final class StvnTypeResolver {

    private StvnTypeResolver() {
    }

    private static final Key<CachedValue<Map<Integer, StvnValue>>> CORE_VALUES_KEY =
        Key.create("org.stvnadore.plugin.CORE_VALUES");

    private static final Pattern EXPLICIT_UNION_TAG_PATTERN =
        Pattern.compile("^#[1-9][0-9]*(\\s+|$)");

    private static final Pattern CONSTRAINT_VIOLATION_TARGET_PATTERN =
        Pattern.compile("^Constraint violation \\(([^)]+)\\):");

    /** Diagnostic symbolic and canonical codes indicating a degraded or poisoned schema definition. */
    public static final Set<String> FATAL_SCHEMA_DIAGNOSTIC_CODES = Set.of(
        DiagnosticBag.ERR_INTEGER_OVERFLOW,
        "E009",
        DiagnosticBag.ERR_INVALID_REGEX,
        "E012",
        DiagnosticBag.ERR_INVERTED_RANGE,
        DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN,
        DiagnosticBag.ERR_FLOAT_INVALID_SIZE,
        "E010",
        DiagnosticBag.ERR_CAPACITY_OVERFLOW,
        "E011",
        DiagnosticBag.ERR_INCOMPATIBLE_TYPE,
        DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE,
        "E013",
        DiagnosticBag.ERR_NON_OVERRIDABLE_FACET,
        "E035",
        "E040",
        DiagnosticBag.ERR_INVALID_METADATA_FACET,
        DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET,
        "E036",
        DiagnosticBag.ERR_DUPLICATE_METADATA_FACET,
        DiagnosticBag.ERR_FILTER_DUPLICATE_VARIANT,
        DiagnosticBag.ERR_TRAIT_VIOLATION,
        DiagnosticBag.ERR_MISSING_TEMPORAL_FACET,
        DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING,
        DiagnosticBag.ERR_DATETIME_MODE_INVALID,
        DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED,
        DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED,
        DiagnosticBag.ERR_ENUM_SUBSET_NOT_SUBSET,
        DiagnosticBag.ERR_ENUM_SUBSET_ORDER_VIOLATION,
        DiagnosticBag.ERR_ENUM_SUBSET_EMPTY,
        "E016",
        DiagnosticBag.ERR_UNDEFINED_TYPE,
        "E014",
        DiagnosticBag.ERR_CIRCULAR_TYPE,
        "E007",
        "E015",
        DiagnosticBag.ERR_MALFORMED_SCHEMA,
        "ERR_MALFORMED_SCHEMA",
        DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED,
        "E019",
        DiagnosticBag.ERR_TEMPORAL_ZONE_IN_EPOCH,
        "E020",
        DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH,
        "E021",
        DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL,
        "E022",
        DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET,
        DiagnosticBag.ERR_DATETIME_FENCED_STRING_PROHIBITED,
        DiagnosticBag.ERR_DATETIME_MISSING_OFFSET,
        DiagnosticBag.ERR_DATETIME_MISSING_ZONE,
        DiagnosticBag.ERR_DATETIME_INVALID_FORMAT,
        DiagnosticBag.ERR_DATETIME_INVALID_CALENDAR,
        DiagnosticBag.ERR_DATETIME_INVALID_ZONE
    );

    /**
     * Returns cached compiler diagnostics for the given STVN PSI file.
     *
     * @param file the PSI file to compile and inspect
     * @return immutable list of diagnostics, or {@code null} if {@code file} is {@code null}
     */
    public static @Nullable List<StvnDiagnostic> getCompilationDiagnostics(@Nullable PsiFile file) {
        if (file == null) {
            return null;
        }
        return StvnCompiledDocumentCache.getSnapshot(file).diagnostics();
    }

    /**
     * Resolves the pure-IR {@link StvnSchema} record for the given nominal type definition,
     * including folded constraints and algebraic traits across its nominal alias chain.
     *
     * @param typeDef the type definition PSI element
     * @return the lowered pure-IR {@link StvnSchema}, or {@code null} if unresolvable
     */
    public static @Nullable StvnSchema resolveNominalIrSchema(@Nullable TypeDefinition typeDef) {
        if (typeDef == null) {
            return null;
        }
        var file = typeDef.getContainingFile();
        if (file == null) {
            return null;
        }
        var keyword = typeDef.getTypeKeyword();
        if (keyword == null) {
            return null;
        }
        var typeName = keyword.getText().trim();
        var enclosingPkg = PsiTreeUtil.getParentOfType(typeDef, PackageEnclosure.class);
        String fqniKey = typeName;
        if (enclosingPkg != null && enclosingPkg.getPackagePath() != null) {
            var pkgText = org.stvnadore.plugin.validation.StvnTrailingSlashInspection.stripTrailingSlashes(
                enclosingPkg.getPackagePath().getText()
            );
            var localPart = typeName.startsWith(StvnVocabulary.SIGIL_TYPIC) ? typeName.substring(1) : typeName;
            fqniKey = pkgText + "/" + localPart;
        }

        StvnDefsCatalog catalog = StvnPreludeBridge.isPreludeFile(file)
            ? StvnPreludeBridge.getPreludeCatalog()
            : StvnCompiledDocumentCache.getSnapshot(file).catalog();

        var found = catalog.findType(fqniKey);
        if (found.isPresent()) {
            return found.get();
        }
        var byRaw = catalog.findType(typeName);
        if (byRaw.isPresent()) {
            return byRaw.get();
        }
        String localSuffix = "/" + (typeName.startsWith(StvnVocabulary.SIGIL_TYPIC) ? typeName.substring(1) : typeName);
        StvnSchema singleMatch = null;
        for (var entry : catalog.nominalTypes().entrySet()) {
            if (entry.getKey().endsWith(localSuffix)) {
                if (singleMatch != null) {
                    return null;
                }
                singleMatch = entry.getValue();
            }
        }
        return singleMatch;
    }

    /**
     * Resolves the effective Tier-2 algebraic {@link StvnSchema.TraitProfile} for a nominal type definition.
     *
     * @param typeDef the type definition PSI element
     * @return effective trait profile, or {@code null} if unresolvable
     */
    public static StvnSchema.@Nullable TraitProfile resolveNominalTraitConstraints(@Nullable TypeDefinition typeDef) {
        var schema = resolveNominalIrSchema(typeDef);
        return schema != null ? schema.traits() : null;
    }

    /**
     * Resolves a nominal pure-IR {@link StvnSchema} by alias identifier within the context of {@code file}.
     *
     * @param file      containing PSI file
     * @param aliasName local, imported, or fully qualified nominal alias identifier
     * @return resolved pure-IR {@link StvnSchema}, or {@code null} if unresolvable
     */
    public static @Nullable StvnSchema resolveIrSchemaByAlias(@Nullable PsiFile file, @Nullable String aliasName) {
        if (file == null || aliasName == null || aliasName.isEmpty()) {
            return null;
        }
        var catalog = StvnCompiledDocumentCache.getSnapshot(file).catalog();
        var direct = catalog.findType(aliasName);
        if (direct.isPresent()) {
            return direct.get();
        }
        var targetElem = StvnTypeReference.resolveTypeInFile(file, aliasName, new HashSet<>());
        var typeDef = resolveUnderlyingTypeDefinition(targetElem);
        if (typeDef != null) {
            var resolved = resolveNominalIrSchema(typeDef);
            if (resolved != null) {
                return resolved;
            }
        }
        var preludeCatalog = StvnPreludeBridge.getPreludeCatalog();
        var preludeDirect = preludeCatalog.findType(aliasName);
        if (preludeDirect.isPresent()) {
            return preludeDirect.get();
        }
        if (aliasName.startsWith(StvnVocabulary.SIGIL_TYPIC) && !aliasName.substring(1).contains("/")) {
            var preludeFqni = ":org/stvnadore/prelude/" + aliasName.substring(1);
            var preludeShort = preludeCatalog.findType(preludeFqni);
            if (preludeShort.isPresent()) {
                return preludeShort.get();
            }
        }
        return null;
    }

    /**
     * Resolves the lowered pure-IR {@link StvnSchema} corresponding to a {@link SchemaType} PSI node
     * across nominal {@code :defs} declarations, root {@code :type} clauses, inline composite children,
     * and nominal alias references.
     *
     * @param schemaType target schema PSI node
     * @return resolved pure-IR {@link StvnSchema}, or {@code null} if unresolvable
     */
    public static @Nullable StvnSchema resolveIrSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return null;
        }
        var file = schemaType.getContainingFile();
        if (file == null) {
            return null;
        }
        var parentDef = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
        if (parentDef != null) {
            if (parentDef.getSchemaType() == schemaType) {
                return resolveNominalIrSchema(parentDef);
            }
            var rootIr = resolveNominalIrSchema(parentDef);
            var rootPsi = parentDef.getSchemaType();
            if (rootIr != null && rootPsi != null) {
                var descended = descendIrSchemaChild(rootPsi, rootIr, schemaType);
                if (descended != null) {
                    return descended;
                }
            }
        }
        var typeEntry = PsiTreeUtil.getParentOfType(schemaType, TypeEntry.class);
        if (typeEntry != null && typeEntry.getSchemaType() != null) {
            var rootTypeOpt = StvnCompiledDocumentCache.getSnapshot(file).rootType();
            if (rootTypeOpt.isPresent()) {
                var rootIr = rootTypeOpt.get();
                if (typeEntry.getSchemaType() == schemaType) {
                    return rootIr;
                }
                var descended = descendIrSchemaChild(typeEntry.getSchemaType(), rootIr, schemaType);
                if (descended != null) {
                    return descended;
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null && schemaType.getMetadataMap() == null) {
            return resolveIrSchemaByAlias(file, kw.getText().trim());
        }
        return null;
    }

    private static @Nullable StvnSchema descendIrSchemaChild(
        SchemaType rootPsi,
        StvnSchema rootIr,
        SchemaType targetChildPsi
    ) {
        var steps = new ArrayDeque<Integer>();
        PsiElement curr = targetChildPsi;
        while (curr != null && curr != rootPsi) {
            var parent = curr.getParent();
            if (parent instanceof ProductType pt && curr instanceof SchemaType st) {
                int idx = pt.getSchemaTypeList().indexOf(st);
                if (idx < 0) {
                    return null;
                }
                steps.push(idx);
                curr = PsiTreeUtil.getParentOfType(pt, SchemaType.class);
            } else if (parent instanceof CollectionType ct && curr instanceof SchemaType st) {
                int idx = ct.getSchemaTypeList().indexOf(st);
                if (idx < 0) {
                    return null;
                }
                steps.push(idx);
                curr = PsiTreeUtil.getParentOfType(ct, SchemaType.class);
            } else if (parent instanceof SumType stSum && curr instanceof SchemaType st) {
                var branches = PsiTreeUtil.getChildrenOfTypeAsList(stSum, SchemaType.class);
                int idx = branches.indexOf(st);
                if (idx < 0) {
                    return null;
                }
                steps.push(idx);
                curr = PsiTreeUtil.getParentOfType(stSum, SchemaType.class);
            } else {
                curr = parent;
            }
        }
        if (curr != rootPsi) {
            return null;
        }
        StvnSchema active = rootIr;
        while (!steps.isEmpty() && active != null) {
            int idx = steps.pop();
            if (active instanceof StvnSchema.PoisonedSchema p) {
                active = p.bestEffortFallback().orElse(null);
            }
            active = switch (active) {
                case StvnSchema.TupleSchema ts -> (idx < ts.fieldSchemas().size()) ? ts.fieldSchemas().get(idx) : null;
                case StvnSchema.SeqSchema sq -> (idx == 0) ? sq.elementSchema() : null;
                case StvnSchema.SetSchema st -> (idx == 0) ? st.elementSchema() : null;
                case StvnSchema.MapSchema mp -> (idx == 0) ? mp.keySchema() : (idx == 1 ? mp.valueSchema() : null);
                case StvnSchema.OptionSchema op -> (idx == 0) ? op.valueSchema() : null;
                case StvnSchema.EitherSchema ei -> (idx == 0) ? ei.leftSchema() : (idx == 1 ? ei.rightSchema() : null);
                case StvnSchema.UnionSchema un -> (idx < un.branchSchemas().size()) ? un.branchSchemas().get(idx) : null;
                case null, default -> null;
            };
        }
        return active;
    }

    private static @Nullable TypeDefinition resolveUnderlyingTypeDefinition(@Nullable PsiElement targetElem) {
        if (targetElem == null) {
            return null;
        }
        TypeDefinition typeDef = StvnPsiUtils.resolveTargetTypeDefinition(targetElem);
        if (typeDef != null) {
            return typeDef;
        }
        var useAlias = targetElem instanceof UseMapAlias uma ? uma
            : targetElem.getParent() instanceof UseMapAlias uma ? uma : null;
        if (useAlias != null) {
            var list = useAlias.getTypeKeywordList();
            var remoteKw = !list.isEmpty() ? list.get(0) : null;
            if (remoteKw != null) {
                return StvnPsiUtils.getParentTypeDefinition(new StvnTypeReference(remoteKw).resolve());
            }
        }
        return null;
    }

    /**
     * Determines whether the specified nominal alias name refers to a degraded or poisoned schema definition.
     *
     * @param file      the containing PSI file
     * @param aliasName the nominal alias identifier to test
     * @return {@code true} if degraded, {@code false} otherwise
     */
    public static boolean isDegradedNominalAlias(@Nullable PsiFile file, @Nullable String aliasName) {
        if (file == null || aliasName == null || aliasName.isEmpty()) {
            return false;
        }

        // 1. Authoritative pure-IR PoisonedSchema check
        var irSchema = resolveIrSchemaByAlias(file, aliasName);
        if (irSchema instanceof StvnSchema.PoisonedSchema poisoned
                && ((!"RECURSIVE_BACK_EDGE".equals(poisoned.primaryDiagnosticCode())
                        && !"INTERNAL_RECURSIVE_NOMINAL_REF".equals(poisoned.primaryDiagnosticCode()))
                    || FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(poisoned.primaryDiagnosticCode()))) {
            return true;
        }

        // 2. Direct PSI check on the TypeDefinition metadata map for in-flight buffers
        var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new HashSet<>());
        var typeDef = resolveUnderlyingTypeDefinition(targetDef);
        if (typeDef != null) {
            var defIr = resolveNominalIrSchema(typeDef);
            if (defIr instanceof StvnSchema.PoisonedSchema poisonedDef
                    && ((!"RECURSIVE_BACK_EDGE".equals(poisonedDef.primaryDiagnosticCode())
                            && !"INTERNAL_RECURSIVE_NOMINAL_REF".equals(poisonedDef.primaryDiagnosticCode()))
                        || FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(poisonedDef.primaryDiagnosticCode()))) {
                return true;
            }
            var metaMap = typeDef.getMetadataMap() != null
                ? typeDef.getMetadataMap()
                : (typeDef.getSchemaType() != null ? typeDef.getSchemaType().getMetadataMap() : null);
            if (metaMap != null && hasDegradedMetadataConstraints(file, typeDef.getSchemaType(), metaMap)) {
                return true;
            }
        }

        // 3. Check compiler diagnostics accumulated for the defining file (strictly within :defs, never :body)
        PsiFile diagFile = typeDef != null && typeDef.getContainingFile() != null
            ? typeDef.getContainingFile()
            : file;
        var bodyEntry = PsiTreeUtil.findChildOfType(diagFile, BodyEntry.class);
        var bodyRange = bodyEntry != null ? bodyEntry.getTextRange() : null;
        var snapshot = StvnCompiledDocumentCache.getSnapshot(diagFile);
        for (var diag : snapshot.diagnostics()) {
            if (diag.severity() != StvnDiagnostic.DiagnosticSeverity.ERROR) {
                continue;
            }
            if (diag.sourcePath().isPresent()
                && !diag.sourcePath().get().equals(snapshot.normalizedDocPath())) {
                continue;
            }
            if (bodyRange != null && bodyRange.containsRange(diag.startOffset(), diag.endOffset())) {
                continue;
            }
            String symbolic = diag.symbolicId().orElse("");
            if (DiagnosticBag.ERR_SECTION_ORDER_VIOLATION.equals(symbolic)
                    || DiagnosticBag.ERR_META_POSITION_INVALID.equals(symbolic)
                    || DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION.equals(symbolic)
                    || DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(symbolic)) {
                continue;
            }
            String canonical = diag.canonicalCode().orElse("");
            String fallback = diag.errorCode().orElse("");
            boolean isFatal = (!symbolic.isEmpty() && FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(symbolic))
                || (symbolic.isEmpty() && FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(canonical))
                || (symbolic.isEmpty() && FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(fallback));
            if (isFatal) {
                var msg = diag.message();
                var matcher = CONSTRAINT_VIOLATION_TARGET_PATTERN.matcher(msg);
                if (matcher.find()) {
                    var target = matcher.group(1).trim();
                    if (matchesTargetTypeName(target, aliasName)) {
                        if (typeDef == null || (diag.startOffset() >= typeDef.getTextRange().getStartOffset()
                                && diag.endOffset() <= typeDef.getTextRange().getEndOffset())) {
                            return true;
                        }
                    }
                    continue;
                }

                if (typeDef != null) {
                    var range = typeDef.getTextRange();
                    if (diag.startOffset() >= range.getStartOffset()
                            && diag.endOffset() <= range.getEndOffset()
                            && !isInsideCompositeChildSchema(typeDef, diag.startOffset(), diag.endOffset())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Determines whether the given {@link SchemaType} (nominal alias, adorned nominal alias, or inline schema)
     * is degraded or ungrounded due to schema or metadata constraint errors.
     *
     * @param file       the containing PSI file
     * @param schemaType the governing schema PSI node to inspect
     * @return {@code true} if degraded or ungrounded, {@code false} otherwise
     */
    public static boolean isDegradedOrUngroundedSchema(@Nullable PsiFile file, @Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return false;
        }
        PsiFile ctxFile = schemaType.getContainingFile() != null ? schemaType.getContainingFile() : file;
        if (ctxFile == null) {
            return false;
        }
        var kw = schemaType.getTypeKeyword();
        var inlineMap = schemaType.getMetadataMap();
        if (kw != null) {
            if (isDegradedNominalAlias(ctxFile, kw.getText().trim())) {
                return true;
            }
            if (inlineMap == null) {
                return false;
            }
        }
        var ir = resolveIrSchema(schemaType);
        if (ir instanceof StvnSchema.PoisonedSchema poisoned
                && !DiagnosticBag.ERR_UNDEFINED_TYPE.equals(poisoned.primaryDiagnosticCode())
                && ((!"RECURSIVE_BACK_EDGE".equals(poisoned.primaryDiagnosticCode())
                        && !"INTERNAL_RECURSIVE_NOMINAL_REF".equals(poisoned.primaryDiagnosticCode()))
                    || FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(poisoned.primaryDiagnosticCode()))) {
            return true;
        }
        if (inlineMap != null && hasDegradedMetadataConstraints(ctxFile, schemaType, inlineMap)) {
            return true;
        }
        var ctor = schemaType.getSchemaConstructor();
        if (ctor != null && ctor.getAtomicType() != null && inlineMap == null) {
            String atomText = ctor.getAtomicType().getText().trim();
            if (StvnVocabulary.TYPE_DATE_TIME.equals(atomText)
                    || StvnVocabulary.TYPE_TIME_EPOCH.equals(atomText)) {
                var parentDef = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
                if (parentDef == null || parentDef.getSchemaType() != schemaType || parentDef.getMetadataMap() == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determines whether any enclosing composite {@link Value} ancestor inside {@code :body} is governed
     * by a degraded or ungrounded schema.
     *
     * @param value the payload {@link Value} PSI node
     * @return {@code true} if an enclosing ancestor inside {@code :body} is degraded or ungrounded
     */
    public static boolean hasDegradedEnclosingValueAncestor(@Nullable Value value) {
        if (value == null || PsiTreeUtil.getParentOfType(value, BodyEntry.class) == null) {
            return false;
        }
        Value curr = PsiTreeUtil.getParentOfType(value, Value.class, true);
        while (curr != null && PsiTreeUtil.getParentOfType(curr, BodyEntry.class) != null) {
            var info = resolveBaseTypeInfo(curr);
            if (info != null && info.getSchema() != null
                    && isDegradedOrUngroundedSchema(curr.getContainingFile(), info.getSchema())) {
                return true;
            }
            curr = PsiTreeUtil.getParentOfType(curr, Value.class, true);
        }
        return false;
    }

    private static boolean hasDegradedMetadataConstraints(
            PsiFile file,
            @Nullable SchemaType schemaType,
            MetadataMap metaMap
    ) {
        var entries = metaMap.getMetadataEntryList();
        var hasMinIncl = false;
        var hasMinExcl = false;
        var hasMaxIncl = false;
        var hasMaxExcl = false;
        var hasFilterIncl = false;
        var hasFilterExcl = false;
        var hasSize = false;
        var hasExact = false;
        BigDecimal minVal = null;
        BigDecimal maxVal = null;
        BigDecimal minSizeVal = null;
        BigDecimal maxSizeVal = null;
        String minStrVal = null;
        String maxStrVal = null;
        MetadataEntry localMinEntry = null;
        MetadataEntry localMaxEntry = null;

        var resolvedSchema = resolveNominalSchema(schemaType);
        var inspectSchema = resolvedSchema != null ? resolvedSchema : schemaType;
        var schemaText = inspectSchema != null ? StvnSchemaFormatter.formatCleanSchema(inspectSchema).trim() : "";
        boolean isDateTimeBase = StvnVocabulary.TYPE_DATE_TIME.equals(schemaText)
            || schemaText.endsWith(StvnVocabulary.TYPE_DATE_TIME);
        boolean isTimeEpochBase = StvnVocabulary.TYPE_TIME_EPOCH.equals(schemaText)
            || schemaText.endsWith(StvnVocabulary.TYPE_TIME_EPOCH);
        boolean isIntBase = StvnVocabulary.TYPE_INT.equals(schemaText)
            || schemaText.endsWith(StvnVocabulary.TYPE_INT);
        boolean isStringBase = StvnVocabulary.TYPE_STRING.equals(schemaText)
            || schemaText.endsWith(StvnVocabulary.TYPE_STRING);

        for (var entry : entries) {
            var trait = entry.getMetadataTrait();
            if (trait != null) {
                var mv = trait.getMetadataValue();
                if (mv == null || (mv.getBooleanValue() == null && mv.getValueKeyword() == null)) {
                    return true;
                }
            }
            var boolFacet = entry.getMetadataBool();
            if (boolFacet != null) {
                var mv = boolFacet.getMetadataValue();
                if (mv == null || (mv.getBooleanValue() == null && mv.getValueKeyword() == null)) {
                    return true;
                }
            }
            var entryText = entry.getText();
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_REGEX)) {
                var mv = entry.getMetadataValue();
                if (mv != null) {
                    var resolvedRegex = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, mv.getText().trim(), false);
                    if (mv.getStringLiteral() != null && resolvedRegex.isEmpty()) {
                        return true;
                    }
                    if (resolvedRegex.isPresent()) {
                        try {
                            Pattern.compile(resolvedRegex.get());
                        } catch (PatternSyntaxException e) {
                            return true;
                        }
                    }
                }
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_MIN_SIZE)) {
                minSizeVal = extractNumericValue(entry);
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_MAX_SIZE)) {
                maxSizeVal = extractNumericValue(entry);
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_MIN_INCL)) {
                hasMinIncl = true;
                localMinEntry = entry;
                var num = extractNumericValue(entry);
                if (num != null) {
                    minVal = num;
                } else if (entry.getMetadataValue() != null) {
                    var mv = entry.getMetadataValue();
                    String rawMv = mv.getText().trim();
                    if (isDateTimeBase && rawMv.startsWith("\"\"\"")) {
                        return true;
                    }
                    var resolved = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, rawMv, false);
                    if (mv.getStringLiteral() != null && resolved.isEmpty()) {
                        return true;
                    }
                    minStrVal = resolved.orElse(null);
                }
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_MIN_EXCL)) {
                hasMinExcl = true;
                localMinEntry = entry;
                var num = extractNumericValue(entry);
                if (num != null) {
                    minVal = num;
                } else if (entry.getMetadataValue() != null) {
                    var mv = entry.getMetadataValue();
                    String rawMv = mv.getText().trim();
                    if (isDateTimeBase && rawMv.startsWith("\"\"\"")) {
                        return true;
                    }
                    var resolved = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, rawMv, false);
                    if (mv.getStringLiteral() != null && resolved.isEmpty()) {
                        return true;
                    }
                    minStrVal = resolved.orElse(null);
                }
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_MAX_INCL)) {
                hasMaxIncl = true;
                localMaxEntry = entry;
                var num = extractNumericValue(entry);
                if (num != null) {
                    maxVal = num;
                } else if (entry.getMetadataValue() != null) {
                    var mv = entry.getMetadataValue();
                    String rawMv = mv.getText().trim();
                    if (isDateTimeBase && rawMv.startsWith("\"\"\"")) {
                        return true;
                    }
                    var resolved = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, rawMv, false);
                    if (mv.getStringLiteral() != null && resolved.isEmpty()) {
                        return true;
                    }
                    maxStrVal = resolved.orElse(null);
                }
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_MAX_EXCL)) {
                hasMaxExcl = true;
                localMaxEntry = entry;
                var num = extractNumericValue(entry);
                if (num != null) {
                    maxVal = num;
                } else if (entry.getMetadataValue() != null) {
                    var mv = entry.getMetadataValue();
                    String rawMv = mv.getText().trim();
                    if (isDateTimeBase && rawMv.startsWith("\"\"\"")) {
                        return true;
                    }
                    var resolved = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, rawMv, false);
                    if (mv.getStringLiteral() != null && resolved.isEmpty()) {
                        return true;
                    }
                    maxStrVal = resolved.orElse(null);
                }
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_FILTER_INCL)) {
                hasFilterIncl = true;
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_FILTER_EXCL)) {
                hasFilterExcl = true;
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_EXACT)) {
                hasExact = true;
            } else if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_SIZE)) {
                hasSize = true;
                if (isStringBase) {
                    return true;
                }
            }
        }

        if ((hasFilterIncl && hasFilterExcl) || (hasSize && hasExact)) {
            return true;
        }
        if (minSizeVal != null && maxSizeVal != null && minSizeVal.compareTo(maxSizeVal) > 0) {
            return true;
        }
        if ((hasMaxIncl || hasMinExcl) && (isIntBase || isTimeEpochBase || isDateTimeBase)) {
            return true;
        }

        var hasOffset = false;
        var hasZoned = false;
        var hasAudited = false;
        var hasScaleNs = false;
        var hasScaleUs = false;
        var hasScaleMs = false;
        var hasScaleS = false;
        for (var entry : entries) {
            var entryText = entry.getText();
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_OFFSET)) hasOffset = true;
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_ZONED)) hasZoned = true;
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_AUDITED)) hasAudited = true;
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_SCALE_NS)) hasScaleNs = true;
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_SCALE_US)) hasScaleUs = true;
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_SCALE_MS)) hasScaleMs = true;
            if (hasExactFacetTag(entryText, StvnVocabulary.FACET_KW_SCALE_S)) hasScaleS = true;
        }
        int modeCount = (hasOffset ? 1 : 0) + (hasZoned ? 1 : 0) + (hasAudited ? 1 : 0);
        int scaleCount = (hasScaleNs ? 1 : 0) + (hasScaleUs ? 1 : 0) + (hasScaleMs ? 1 : 0) + (hasScaleS ? 1 : 0);
        if (modeCount > 1 || scaleCount > 1) {
            return true;
        }

        if ((hasMinIncl && hasMinExcl) || (hasMaxIncl && hasMaxExcl)) {
            return true;
        }

        if (isDateTimeBase) {
            StvnSchema.DateTimeMode dtMode = hasOffset
                ? StvnSchema.DateTimeMode.OFFSET
                : hasZoned
                    ? StvnSchema.DateTimeMode.ZONED
                    : hasAudited
                        ? StvnSchema.DateTimeMode.AUDITED
                        : resolveInheritedDateTimeMode(file, schemaType);
            if (dtMode == null) {
                return true;
            }
            Instant minInst = null;
            Instant maxInst = null;
            if (localMinEntry != null) {
                minInst = parseValidatedDateTimeBoundInstant(localMinEntry, minStrVal, dtMode);
                if (minInst == null) {
                    return true;
                }
            }
            if (localMaxEntry != null) {
                maxInst = parseValidatedDateTimeBoundInstant(localMaxEntry, maxStrVal, dtMode);
                if (maxInst == null) {
                    return true;
                }
            }
            if (minInst != null && maxInst != null) {
                int dtCmp = minInst.compareTo(maxInst);
                if (dtCmp > 0 || (dtCmp == 0 && (hasMinExcl || hasMaxExcl))) {
                    return true;
                }
            }
        }

        if ((minVal != null && maxVal == null) || (minVal == null && maxVal != null)) {
            var visitedAncestors = new HashSet<String>();
            String parentAlias = (schemaType != null && schemaType.getTypeKeyword() != null)
                ? schemaType.getTypeKeyword().getText().trim()
                : null;
            while (parentAlias != null && parentAlias.startsWith(StvnVocabulary.SIGIL_TYPIC)
                    && visitedAncestors.add(parentAlias)) {
                var parentTarget = StvnTypeReference.resolveTypeInFile(file, parentAlias, new HashSet<>());
                var parentDef = resolveUnderlyingTypeDefinition(parentTarget);
                if (parentDef == null) {
                    break;
                }
                var parentMeta = parentDef.getMetadataMap();
                if (parentMeta != null) {
                    for (var pEntry : parentMeta.getMetadataEntryList()) {
                        var pText = pEntry.getText();
                        if (minVal == null && (hasExactFacetTag(pText, StvnVocabulary.FACET_KW_MIN_INCL)
                                || hasExactFacetTag(pText, StvnVocabulary.FACET_KW_MIN_EXCL))) {
                            minVal = extractNumericValue(pEntry);
                            if (hasExactFacetTag(pText, StvnVocabulary.FACET_KW_MIN_EXCL)) hasMinExcl = true;
                            else hasMinIncl = true;
                        } else if (maxVal == null && (hasExactFacetTag(pText, StvnVocabulary.FACET_KW_MAX_INCL)
                                || hasExactFacetTag(pText, StvnVocabulary.FACET_KW_MAX_EXCL))) {
                            maxVal = extractNumericValue(pEntry);
                            if (hasExactFacetTag(pText, StvnVocabulary.FACET_KW_MAX_EXCL)) hasMaxExcl = true;
                            else hasMaxIncl = true;
                        }
                    }
                }
                if (minVal != null && maxVal != null) {
                    break;
                }
                var nextSt = parentDef.getSchemaType();
                parentAlias = (nextSt != null && nextSt.getTypeKeyword() != null)
                    ? nextSt.getTypeKeyword().getText().trim()
                    : null;
            }
        }
        if (minVal != null && maxVal != null) {
            int cmp = minVal.compareTo(maxVal);
            if (cmp > 0 || (cmp == 0 && (hasMinExcl || hasMaxExcl))) {
                return true;
            }
        }
        if (minStrVal != null && maxStrVal != null && isStringBase) {
            int strCmp = Arrays.compareUnsigned(
                minStrVal.getBytes(StandardCharsets.UTF_8),
                maxStrVal.getBytes(StandardCharsets.UTF_8));
            if (strCmp > 0 || (strCmp == 0 && (hasMinExcl || hasMaxExcl))) {
                return true;
            }
        }
        return false;
    }

    private static StvnSchema.@Nullable DateTimeMode resolveInheritedDateTimeMode(
            PsiFile file,
            @Nullable SchemaType schemaType
    ) {
        if (schemaType == null) {
            return null;
        }
        var visited = new HashSet<String>();
        String alias = schemaType.getTypeKeyword() != null ? schemaType.getTypeKeyword().getText().trim() : null;
        while (alias != null && alias.startsWith(StvnVocabulary.SIGIL_TYPIC) && visited.add(alias)) {
            var target = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
            var parentDef = resolveUnderlyingTypeDefinition(target);
            if (parentDef == null) {
                break;
            }
            var parentMeta = parentDef.getMetadataMap() != null
                ? parentDef.getMetadataMap()
                : (parentDef.getSchemaType() != null ? parentDef.getSchemaType().getMetadataMap() : null);
            if (parentMeta != null) {
                for (var entry : parentMeta.getMetadataEntryList()) {
                    var text = entry.getText().trim();
                    if (StvnVocabulary.FACET_KW_OFFSET.equals(text)) return StvnSchema.DateTimeMode.OFFSET;
                    if (StvnVocabulary.FACET_KW_ZONED.equals(text)) return StvnSchema.DateTimeMode.ZONED;
                    if (StvnVocabulary.FACET_KW_AUDITED.equals(text)) return StvnSchema.DateTimeMode.AUDITED;
                }
            }
            var nextSt = parentDef.getSchemaType();
            alias = (nextSt != null && nextSt.getTypeKeyword() != null)
                ? nextSt.getTypeKeyword().getText().trim()
                : null;
        }
        return null;
    }

    private static @Nullable Instant parseValidatedDateTimeBoundInstant(
            MetadataEntry entry,
            @Nullable String decoded,
            StvnSchema.DateTimeMode mode
    ) {
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        var mv = entry.getMetadataValue();
        if (mv == null) {
            return null;
        }
        String rawText = mv.getText().trim();
        if (rawText.startsWith("\"\"\"")) {
            return null;
        }
        String validationToken = mv.getStringLiteral() != null
            ? rawText
            : StvnPsiLiteralEvaluator.formatCanonicalQuotedString(decoded);
        if (StvnCompiler.validateDateTimeLiteral(validationToken, mode).isPresent()) {
            return null;
        }
        try {
            return switch (mode) {
                case OFFSET -> OffsetDateTime.parse(decoded).toInstant();
                case AUDITED -> {
                    int bracketIdx = decoded.indexOf('[');
                    yield OffsetDateTime.parse(decoded.substring(0, bracketIdx)).toInstant();
                }
                case ZONED -> {
                    int openBracket = decoded.indexOf('[');
                    int closeBracket = decoded.lastIndexOf(']');
                    var ldt = LocalDateTime.parse(decoded.substring(0, openBracket));
                    var zone = ZoneId.of(decoded.substring(openBracket + 1, closeBracket));
                    yield ldt.atZone(zone).toInstant();
                }
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isInsideCompositeChildSchema(TypeDefinition typeDef, int startOffset, int endOffset) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null || schemaType.getSchemaConstructor() == null) {
            return false;
        }
        var ctor = schemaType.getSchemaConstructor();
        List<SchemaType> childSchemas = List.of();
        if (ctor.getCollectionType() != null) {
            childSchemas = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getCollectionType(), SchemaType.class);
        } else if (ctor.getProductType() != null) {
            childSchemas = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getProductType(), SchemaType.class);
        } else if (ctor.getSumType() != null) {
            childSchemas = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        }
        for (var child : childSchemas) {
            var childRange = child.getTextRange();
            if (startOffset >= childRange.getStartOffset() && endOffset <= childRange.getEndOffset()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTargetTypeName(String target, String aliasName) {
        if (target.equals(aliasName)) {
            return true;
        }
        if (target.endsWith("/" + (aliasName.startsWith(StvnVocabulary.SIGIL_TYPIC) ? aliasName.substring(1) : aliasName))) {
            return true;
        }
        return aliasName.endsWith("/" + (target.startsWith(StvnVocabulary.SIGIL_TYPIC) ? target.substring(1) : target));
    }

    private static @Nullable BigDecimal extractNumericValue(MetadataEntry entry) {
        var intLit = PsiTreeUtil.findChildOfType(entry, IntegerLiteral.class);
        if (intLit != null) {
            var parsed = StvnPsiLiteralEvaluator.parseBigInteger(intLit.getText());
            if (parsed.isPresent()) {
                return new BigDecimal(parsed.get());
            }
        }
        var floatLit = PsiTreeUtil.findChildOfType(entry, FloatLiteral.class);
        if (floatLit != null) {
            return StvnPsiLiteralEvaluator.parseBigDecimal(floatLit.getText()).orElse(null);
        }
        return null;
    }

    /**
     * Resolves the corresponding lowered pure-IR {@link StvnValue} for the specified PSI element.
     *
     * @param value the PSI element to resolve
     * @return the resolved {@link StvnValue} IR node, or {@code null} if unresolvable
     */
    public static @Nullable StvnValue resolveCoreValue(PsiElement value) {
        var file = value.getContainingFile();
        if (file == null) {
            return null;
        }

        var manager = CachedValuesManager.getManager(file.getProject());
        var valuesMap = manager.getCachedValue(file, CORE_VALUES_KEY, () -> {
            Map<Integer, StvnValue> map = new HashMap<>();
            var snapshot = StvnCompiledDocumentCache.getSnapshot(file);
            if (snapshot.payload().isPresent()) {
                var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                if (bodyEntry != null && bodyEntry.getValue() != null) {
                    mapValues(bodyEntry.getValue(), snapshot.payload().get(), map);
                }
            }
            for (var constDef : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)
                        || constDef.getValue() == null
                        || constDef.getValueKeyword() == null) {
                    continue;
                }
                var enclosingPkg = PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class);
                if (enclosingPkg != null && StvnPsiUtils.hasIllegalTrailingSlash(enclosingPkg)) {
                    continue;
                }
                String pkgPath = (enclosingPkg != null && enclosingPkg.getPackagePath() != null)
                    ? enclosingPkg.getPackagePath().getText().trim()
                    : null;
                String constName = constDef.getValueKeyword().getText().trim();
                var constOpt = snapshot.catalog().findConstant(pkgPath, constName);
                if (constOpt.isPresent()) {
                    mapValues(constDef.getValue(), constOpt.get().value(), map);
                }
            }
            return CachedValueProvider.Result.create(
                map,
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            );
        }, false);

        int offset = value.getTextRange().getStartOffset();
        return valuesMap != null ? valuesMap.get(offset) : null;
    }

    private enum StepType {
        LIST,
        MAP,
        MAP_KEY,
        MAP_VALUE,
        TUPLE,
        OPTION,
        EITHER,
        UNION
    }

    private static final class PathStep {
        final StepType type;
        final int index;

        PathStep(StepType type, int index) {
            this.type = type;
            this.index = index;
        }
    }

    /**
     * Encapsulates a resolved schema type alongside its formatted human-readable label.
     */
    public static final class ResolvedTypeInfo {
        private final SchemaType schema;
        private final String label;

        /**
         * Constructs a {@link ResolvedTypeInfo} instance.
         *
         * @param schema the underlying schema type PSI element
         * @param label  the formatted type label
         */
        public ResolvedTypeInfo(SchemaType schema, String label) {
            this.schema = schema;
            this.label = label;
        }

        /**
         * Returns the underlying schema type PSI element.
         *
         * @return the schema type
         */
        public SchemaType getSchema() {
            return schema;
        }

        /**
         * Returns the formatted human-readable label.
         *
         * @return the type label
         */
        public String getLabel() {
            return label;
        }
    }

    private static String getLabelForSchema(StvnSchema schema, PsiFile containingFile) {
        if (schema.aliasName().isPresent()) {
            var alias = schema.aliasName().get();
            var preludePrefix = StvnVocabulary.SIGIL_TYPIC + "org/stvnadore/prelude/";
            var shortAlias = alias.startsWith(preludePrefix)
                ? StvnVocabulary.SIGIL_TYPIC + alias.substring(preludePrefix.length())
                : alias;
            if (StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(shortAlias)) {
                return shortAlias;
            }
            var keywordElement = StvnTypeReference.resolveTypeInFile(containingFile, alias, new HashSet<>());
            var kw = keywordElement instanceof TypeDefinition td ? td.getTypeKeyword()
                : keywordElement instanceof TypeKeyword k ? k : null;
            if (kw != null) {
                var trace = StvnTypeReference.extractResolutionTrace(kw);
                if (trace.size() > 1) {
                    var head = alias.contains("/") ? alias : trace.get(0);
                    return head + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                }
                return alias.contains("/") ? alias : formatTrace(trace);
            }
            return alias;
        }

        if (schema instanceof StvnSchema.TimeEpochSchema te) {
            String scaleFlag = switch (te.scale()) {
                case S -> StvnVocabulary.FACET_KW_SCALE_S;
                case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
                case US -> StvnVocabulary.FACET_KW_SCALE_US;
                case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
            };
            return "{ " + scaleFlag + " } " + StvnVocabulary.TYPE_TIME_EPOCH;
        }

        if (schema instanceof StvnSchema.DateTimeSchema dt) {
            String modeFlag = switch (dt.mode()) {
                case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
                case ZONED -> StvnVocabulary.FACET_KW_ZONED;
                case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
            };
            return "{ " + modeFlag + " } " + StvnVocabulary.TYPE_DATE_TIME;
        }

        return StvnSchemaFormatter.formatCleanIrSchema(schema);
    }

    /**
     * Extracts the qualified type label for a schema type, ensuring temporal types
     * include their mandatory scale or mode qualifiers.
     *
     * @param schemaType the schema type to format
     * @return the qualified type string, or empty string if {@code null}
     */
    public static String getTypeLabel(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return "";
        }
        var keyword = schemaType.getTypeKeyword();
        if (keyword != null) {
            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            return formatTrace(trace);
        }
        return formatSchemaType(schemaType);
    }

    /**
     * Formats a concrete schema type, extracting qualifying metadata facets for temporal primitives.
     *
     * @param schemaType the schema type to format
     * @return the formatted schema type string with qualifying facets
     */
    public static String formatSchemaType(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return "";
        }
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null && constructor.getAtomicType() != null) {
            var atomicText = constructor.getAtomicType().getText().trim();
            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(atomicText)) {
                var scale = extractScaleFlagFromSchemaType(schemaType);
                return scale != null ? "{ " + scale + " } " + StvnVocabulary.TYPE_TIME_EPOCH : StvnVocabulary.TYPE_TIME_EPOCH;
            }
            if (StvnVocabulary.TYPE_DATE_TIME.equals(atomicText)) {
                var mode = extractDateTimeModeFlagFromSchemaType(schemaType);
                return mode != null ? "{ " + mode + " } " + StvnVocabulary.TYPE_DATE_TIME : StvnVocabulary.TYPE_DATE_TIME;
            }
        }
        return StvnSchemaFormatter.formatCleanSchema(schemaType);
    }

    /**
     * Extracts the declared scale flag ({@code #s}, {@code #ms}, {@code #us}, {@code #ns}) from a {@link SchemaType}.
     *
     * @param schemaType the schema type to inspect
     * @return the extracted scale flag or {@code null}
     */
    public static @Nullable String extractScaleFlagFromSchemaType(SchemaType schemaType) {
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_SCALE_S) || text.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                    || text.equals(StvnVocabulary.FACET_KW_SCALE_US) || text.equals(StvnVocabulary.FACET_KW_SCALE_NS)) {
                    return text;
                }
            }
        }
        var parentDef = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
        if (parentDef != null && parentDef.getSchemaType() == schemaType && parentDef.getMetadataMap() != null) {
            for (var entry : parentDef.getMetadataMap().getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_SCALE_S) || text.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                    || text.equals(StvnVocabulary.FACET_KW_SCALE_US) || text.equals(StvnVocabulary.FACET_KW_SCALE_NS)) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the declared mode flag ({@code #offset}, {@code #zoned}, {@code #audited}) from a {@link SchemaType}.
     *
     * @param schemaType the schema type to inspect
     * @return the extracted datetime mode flag or {@code null}
     */
    public static @Nullable String extractDateTimeModeFlagFromSchemaType(SchemaType schemaType) {
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_OFFSET) || text.equals(StvnVocabulary.FACET_KW_ZONED)
                    || text.equals(StvnVocabulary.FACET_KW_AUDITED)) {
                    return text;
                }
            }
        }
        var parentDef = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
        if (parentDef != null && parentDef.getSchemaType() == schemaType && parentDef.getMetadataMap() != null) {
            for (var entry : parentDef.getMetadataMap().getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_OFFSET) || text.equals(StvnVocabulary.FACET_KW_ZONED)
                    || text.equals(StvnVocabulary.FACET_KW_AUDITED)) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the string type representation of a value element.
     *
     * @param value the PSI value element
     * @return the resolved type representation, or {@code null} if resolution fails
     */
    public static @Nullable String resolveValueType(Value value) {
        var resolvedNode = resolveCoreValue(value);
        if (resolvedNode instanceof StvnValue.StvnErrorValue err) {
            if (!isRecoverableCoreError(err, value)) {
                return null;
            }
            if (err.schema() instanceof StvnSchema.PoisonedSchema poisoned
                    && poisoned.isUngrounded()
                    && poisoned.aliasName().isPresent()) {
                return "\u26A0 " + poisoned.aliasName().get();
            }
        }
        if (resolvedNode != null && !(resolvedNode instanceof StvnValue.StvnErrorValue)) {
            var file = value.getContainingFile();
            if (file != null) {
                var project = value.getProject();
                var useLong = useLongFormSumTypes(project);
                var formatted = formatResolvedNodeTrajectory(resolvedNode, value, file, project, useLong);
                if (formatted != null && !formatted.isEmpty()) {
                    return formatted;
                }
            }
        }

        return resolvePsiFallbackValueType(value);
    }

    private static @Nullable String formatResolvedNodeTrajectory(
        StvnValue resolvedNode,
        Value value,
        PsiFile file,
        Project project,
        boolean useLong
    ) {
        var baseSchema = resolvedNode.schema();
        var rootAliasOpt = baseSchema.aliasName();
        var catalog = StvnCompiledDocumentCache.getSnapshot(file).catalog();

        // 1. Nominal Sum Type Handling
        if (rootAliasOpt.isPresent()) {
            var rootAlias = rootAliasOpt.get();
            var isDegraded = isDegradedNominalAlias(file, rootAlias);
            var prefix = isDegraded ? "\u26A0 " : "";

            if (StvnSumTrajectory.isSumValue(resolvedNode)) {
                var rootDef = findTypeDefinition(file, rootAlias, value);
                var rootRhsPsi = rootDef != null ? rootDef.getSchemaType() : null;
                String formattedNominalSum = formatNominalSumMultiHopTrajectory(
                    rootAlias,
                    rootRhsPsi,
                    value,
                    resolvedNode,
                    catalog,
                    file,
                    project,
                    useLong,
                    new HashSet<>()
                );
                return prefix + formattedNominalSum;
            }

            var alias = rootAlias;
            var shortAlias = alias.startsWith(":org/stvnadore/prelude/")
                ? StvnVocabulary.SIGIL_TYPIC + alias.substring(":org/stvnadore/prelude/".length())
                : alias;
            if (StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(shortAlias)) {
                return prefix + shortAlias;
            }
            var kw = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
            var typeKw = kw instanceof TypeDefinition td ? td.getTypeKeyword()
                : kw instanceof TypeKeyword k ? k : null;
            if (typeKw != null) {
                var trace = StvnTypeReference.extractResolutionTrace(typeKw);
                if (trace.size() > 1) {
                    var head = alias.contains("/") ? alias : trace.get(0);
                    return prefix + head + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                }
            }
            return prefix + alias;
        }

        // 2. Anonymous Enum Handling
        if (resolvedNode instanceof StvnValue.StvnEnum) {
            return StvnVocabulary.TYPE_ENUM;
        }

        // 3. Anonymous Deep Sum Trajectory Handling
        if (StvnSumTrajectory.isSumValue(resolvedNode)) {
            var rootLabel = getLabelForSchema(baseSchema, file);
            var levels = collectSumLevels(resolvedNode, useLong);
            if (levels.isEmpty()) {
                return rootLabel;
            }

            var tokens = extractLeadingTagTokens(value);
            var sb = new StringBuilder(rootLabel);
            int tokenIdx = 0;

            for (var level : levels) {
                sb.append(" ");
                boolean isExplicit = (tokenIdx < tokens.size()) && level.validExplicitTokens().contains(tokens.get(tokenIdx));
                if (isExplicit) {
                    sb.append(level.canonicalTag());
                    tokenIdx++;
                } else {
                    sb.append("[").append(level.canonicalTag()).append("]");
                }
                if (level.isTerminalNone()) {
                    break;
                }
            }

            return sb.toString();
        }

        // 4. Default Schema Formatting
        return getLabelForSchema(baseSchema, file);
    }

    private static @Nullable BigInteger extractExplicitUnionOneBasedIndex(Value value) {
        if (value.getExplicitUnionValue() != null) {
            var firstChild = value.getExplicitUnionValue().getFirstChild();
            if (firstChild != null && firstChild.getText().startsWith(StvnVocabulary.SIGIL_VALUE)) {
                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(firstChild.getText().substring(1));
                if (parsed.isPresent() && parsed.get().compareTo(BigInteger.ONE) >= 0) {
                    return parsed.get();
                }
            }
        }
        var text = value.getText().trim();
        var matcher = EXPLICIT_UNION_TAG_PATTERN.matcher(text);
        if (matcher.find()) {
            var token = matcher.group().trim();
            if (token.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(token.substring(1));
                if (parsed.isPresent() && parsed.get().compareTo(BigInteger.ONE) >= 0) {
                    return parsed.get();
                }
            }
        }
        return null;
    }

    private static int toSafeZeroBasedIndex(@Nullable BigInteger oneBased) {
        if (oneBased == null
            || oneBased.compareTo(BigInteger.ONE) < 0
            || oneBased.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return -1;
        }
        return oneBased.intValue() - 1;
    }

    private static int parseUnionTagZeroBasedIndex(@Nullable String tagText) {
        if (tagText == null || !tagText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return -1;
        }
        var parsed = StvnPsiLiteralEvaluator.parseBigInteger(tagText.trim().substring(1));
        return toSafeZeroBasedIndex(parsed.orElse(null));
    }

    /**
     * Categorizes algebraic sum type constructors.
     */
    public enum SumKind {
        /** Option type constructor ({@code #Some} / {@code #None}). */
        OPTION,
        /** Either type constructor ({@code #Left} / {@code #Right}). */
        EITHER,
        /** Indexed union constructor ({@code #1}, {@code #2}, and higher indices). */
        UNION
    }

    /**
     * Describes an individual nesting level within an algebraic sum hierarchy.
     *
     * @param kind                the algebraic sum kind
     * @param canonicalTag        the canonical tag string for the variant
     * @param validExplicitTokens the set of accepted explicit tokens for this variant
     * @param isTerminalNone      whether this level represents a terminal none or empty branch
     */
    public record SumLevelDescriptor(
        SumKind kind,
        String canonicalTag,
        Set<String> validExplicitTokens,
        boolean isTerminalNone
    ) {
    }

    private static List<SumLevelDescriptor> collectSumLevels(StvnValue rootNode, boolean useLong) {
        var levels = new ArrayList<SumLevelDescriptor>();
        StvnValue curr = rootNode;

        while (StvnSumTrajectory.isSumValue(curr)) {
            if (curr instanceof StvnValue.StvnOption opt) {
                if (opt.value().isEmpty()) {
                    var tag = useLong ? StvnVocabulary.VAL_NONE : StvnVocabulary.VAL_NONE_SHORT;
                    levels.add(new SumLevelDescriptor(SumKind.OPTION, tag, Set.of(StvnVocabulary.VAL_NONE, StvnVocabulary.VAL_NONE_SHORT), true));
                    break;
                } else {
                    var tag = useLong ? StvnVocabulary.VAL_SOME : StvnVocabulary.VAL_SOME_SHORT;
                    levels.add(new SumLevelDescriptor(SumKind.OPTION, tag, Set.of(StvnVocabulary.VAL_SOME, StvnVocabulary.VAL_SOME_SHORT), false));
                    curr = opt.value().orElse(null);
                }
            } else if (curr instanceof StvnValue.StvnEither either) {
                var isRight = either.branch() == StvnValue.EitherBranch.RIGHT;
                var tag = isRight ? (useLong ? StvnVocabulary.VAL_RIGHT : StvnVocabulary.VAL_RIGHT_SHORT) : (useLong ? StvnVocabulary.VAL_LEFT : StvnVocabulary.VAL_LEFT_SHORT);
                var tokens = isRight ? Set.of(StvnVocabulary.VAL_RIGHT, StvnVocabulary.VAL_RIGHT_SHORT) : Set.of(StvnVocabulary.VAL_LEFT, StvnVocabulary.VAL_LEFT_SHORT);
                levels.add(new SumLevelDescriptor(SumKind.EITHER, tag, tokens, false));
                curr = either.value();
            } else if (curr instanceof StvnValue.StvnUnion union) {
                var tag = StvnVocabulary.SIGIL_VALUE + union.oneBasedBranchIndex();
                levels.add(new SumLevelDescriptor(SumKind.UNION, tag, Set.of(tag), false));
                curr = union.value();
            } else {
                break;
            }
        }

        return levels;
    }

    private static List<String> extractLeadingTagTokens(Value value) {
        List<String> tokens = new ArrayList<>();
        var curr = (PsiElement) value;

        while (curr != null) {
            if (curr instanceof Value val) {
                if (val.getExplicitOptionValue() != null) {
                    curr = val.getExplicitOptionValue();
                } else if (val.getExplicitEitherValue() != null) {
                    curr = val.getExplicitEitherValue();
                } else if (val.getExplicitUnionValue() != null) {
                    curr = val.getExplicitUnionValue();
                } else {
                    if (tokens.isEmpty()) {
                        var text = val.getText().trim();
                        var parts = text.split("\\s+");
                        for (var part : parts) {
                            if (isSumTagToken(part)) {
                                tokens.add(part);
                            } else {
                                break;
                            }
                        }
                    }
                    break;
                }
            } else if (curr instanceof ExplicitOptionValue opt) {
                var someLit = opt.getSomeLiteral();
                if (someLit != null) tokens.add(someLit.getText().trim());
                var someShortLit = opt.getSomeShortLiteral();
                if (someShortLit != null) tokens.add(someShortLit.getText().trim());
                var noneLit = opt.getNoneLiteral();
                if (noneLit != null) tokens.add(noneLit.getText().trim());
                var noneShortLit = opt.getNoneShortLiteral();
                if (noneShortLit != null) tokens.add(noneShortLit.getText().trim());
                curr = opt.getValue();
            } else if (curr instanceof ExplicitEitherValue either) {
                var leftLit = either.getLeftLiteral();
                if (leftLit != null) tokens.add(leftLit.getText().trim());
                var leftShortLit = either.getLeftShortLiteral();
                if (leftShortLit != null) tokens.add(leftShortLit.getText().trim());
                var rightLit = either.getRightLiteral();
                if (rightLit != null) tokens.add(rightLit.getText().trim());
                var rightShortLit = either.getRightShortLiteral();
                if (rightShortLit != null) tokens.add(rightShortLit.getText().trim());
                curr = either.getValue();
            } else if (curr instanceof ExplicitUnionValue union) {
                var tagPrefix = union.getUnionTagPrefix();
                tokens.add(tagPrefix.getText().trim());
                curr = union.getValue();
            } else {
                break;
            }
        }

        return tokens;
    }

    private static boolean isSumTagToken(String token) {
        return token.equals(StvnVocabulary.VAL_SOME) || token.equals(StvnVocabulary.VAL_SOME_SHORT) || token.equals(StvnVocabulary.VAL_NONE) || token.equals(StvnVocabulary.VAL_NONE_SHORT)
            || token.equals(StvnVocabulary.VAL_RIGHT) || token.equals(StvnVocabulary.VAL_RIGHT_SHORT) || token.equals(StvnVocabulary.VAL_LEFT) || token.equals(StvnVocabulary.VAL_LEFT_SHORT)
            || EXPLICIT_UNION_TAG_PATTERN.matcher(token).matches();
    }

    private static boolean isExplicitOptionText(String text) {
        return text.startsWith("#Some ") || text.equals(StvnVocabulary.VAL_SOME)
            || text.startsWith("#S ") || text.equals(StvnVocabulary.VAL_SOME_SHORT)
            || text.startsWith(StvnVocabulary.VAL_NONE) || text.equals(StvnVocabulary.VAL_NONE_SHORT);
    }

    private static boolean isExplicitEitherText(String text) {
        return text.startsWith("#Left ") || text.equals(StvnVocabulary.VAL_LEFT)
            || text.startsWith("#L ") || text.equals(StvnVocabulary.VAL_LEFT_SHORT)
            || text.startsWith("#Right ") || text.equals(StvnVocabulary.VAL_RIGHT)
            || text.startsWith("#R ") || text.equals(StvnVocabulary.VAL_RIGHT_SHORT);
    }

    /**
     * Immutable descriptor for an explicit or Happy-Path inferred sum-constructor step on a payload {@link Value}.
     *
     * @param inferred          {@code true} when inferred via Happy-Path Rules A-C; {@code false} when explicit in source
     * @param displayTag        rendered badge token ({@code "[#Some]"}, {@code "#Some"}, {@code "[#2]"}, {@code "#2"}, ...)
     * @param insertToken       canonical STVN tag token inserted upon clicking an inferred badge ({@code "#Some"}, {@code "#S"}, {@code "#2"}, ...)
     * @param anchorStartOffset document character offset of the PSI {@link Value} before which {@code insertToken} is inserted
     */
    public record SumTrajectoryStep(
        boolean inferred,
        String displayTag,
        String insertToken,
        int anchorStartOffset
    ) {
    }

    /**
     * Evaluates the ordered list of explicit and inferred sum-constructor steps for a {@code :body} {@link Value},
     * preferring lowered Pure-IR {@link StvnSumTrajectory} provenance and falling back to PSI sum-branch resolution
     * when a leaf constraint diagnostic prevents full payload lowering.
     *
     * @param value the payload {@link Value} element
     * @return immutable ordered list of sum trajectory steps (empty when {@code value} is not governed by a sum schema)
     */
    public static List<SumTrajectoryStep> resolveSumTrajectorySteps(Value value) {
        var file = value.getContainingFile();
        if (file == null) {
            return List.of();
        }
        boolean useLong = useLongFormSumTypes(value.getProject());
        int defaultAnchor = value.getTextRange().getStartOffset();
        var coreNode = resolveCoreValue(value);
        if (coreNode != null && !(coreNode instanceof StvnValue.StvnErrorValue) && StvnSumTrajectory.isSumValue(coreNode)) {
            var catalog = StvnCompiledDocumentCache.getSnapshot(file).catalog();
            var trajectory = StvnSumTrajectory.evaluate(value, coreNode, catalog);
            if (trajectory.steps().isEmpty()) {
                return List.of();
            }
            var result = new ArrayList<SumTrajectoryStep>(trajectory.steps().size());
            for (var step : trajectory.steps()) {
                String token = step.formatTag(useLong);
                String display = step.inferred()
                    ? StvnVocabulary.DELIM_OPEN_BRACKET + token + StvnVocabulary.DELIM_CLOSE_BRACKET
                    : token;
                int anchor = step.anchorPsiValue() != null
                    ? step.anchorPsiValue().getTextRange().getStartOffset()
                    : defaultAnchor;
                result.add(new SumTrajectoryStep(step.inferred(), display, token, anchor));
            }
            return List.copyOf(result);
        }

        var info = resolveBaseTypeInfo(value);
        if (info == null || info.getSchema() == null) {
            return List.of();
        }
        var steps = new ArrayList<SumTrajectoryStep>();
        collectPsiFallbackSumSteps(info.getSchema(), value, useLong, steps, new HashSet<>());
        return List.copyOf(steps);
    }

    /**
     * Formats the full multi-hop sum trajectory for hover {@code Governing Schema:} and {@code Slot Schema:} rows,
     * appending {@code (-> <nominalLeafTrace>)} even when the outer slot constructor is an anonymous sum wrapping
     * a nominal leaf alias (such as {@code :Option(:Either(:CommandFaultCode :AckToken))}).
     *
     * @param slotRootValue the outer slot {@link Value} inside {@code :body} or {@code ConstantDefinition}
     * @return formatted multi-hop trajectory string, or {@code null} if unresolvable
     */
    public static @Nullable String formatHoverSlotTrajectory(Value slotRootValue) {
        String baseTrajectory = resolveValueType(slotRootValue);
        if (baseTrajectory == null || baseTrajectory.isEmpty()) {
            return null;
        }
        var info = resolveBaseTypeInfo(slotRootValue);
        if (info == null || info.getSchema() == null || info.getSchema().getTypeKeyword() != null) {
            return baseTrajectory;
        }
        var ctor = info.getSchema().getSchemaConstructor();
        if (ctor == null || ctor.getSumType() == null || ctor.getSumType().getEnumDef() != null) {
            return baseTrajectory;
        }
        if (baseTrajectory.contains("(-> ")) {
            return baseTrajectory;
        }
        SchemaType leafSchema = resolveTerminalPayloadSchema(slotRootValue, unwrapExplicitPsiValue(slotRootValue));
        if (leafSchema != null && leafSchema.getTypeKeyword() != null) {
            String kwText = leafSchema.getTypeKeyword().getText().trim();
            if (!StvnNamespaceRules.isReservedFundamentalType(kwText)) {
                var trace = StvnTypeReference.extractResolutionTrace(leafSchema.getTypeKeyword());
                if (!trace.isEmpty()) {
                    return baseTrajectory + " (-> " + String.join(" -> ", trace) + ")";
                }
            }
        }
        return baseTrajectory;
    }

    /**
     * Unwraps all explicit and implicit {@code :Option}, {@code :Either}, and {@code :Union} sum layers
     * between {@code slotRootValue} and {@code targetLeafValue} to locate the terminal leaf {@link SchemaType}.
     *
     * @param slotRootValue   the outer slot {@link Value}
     * @param targetLeafValue the inner leaf {@link Value} being evaluated for compliance or exemplar synthesis
     * @return the unwrapped terminal {@link SchemaType}, or the base schema of {@code targetLeafValue} if not a sum
     */
    public static @Nullable SchemaType resolveTerminalPayloadSchema(Value slotRootValue, Value targetLeafValue) {
        var leafInfo = resolveBaseTypeInfo(targetLeafValue);
        SchemaType startSchema = leafInfo != null ? leafInfo.getSchema() : null;
        if (startSchema == null) {
            var rootInfo = resolveBaseTypeInfo(slotRootValue);
            startSchema = rootInfo != null ? rootInfo.getSchema() : null;
        }
        if (startSchema == null) {
            return null;
        }
        var coreNode = resolveCoreValue(targetLeafValue);
        if (coreNode == null) {
            coreNode = resolveCoreValue(slotRootValue);
        }
        return unwrapAllSumLayersToLeafSchema(startSchema, targetLeafValue, coreNode, new HashSet<>());
    }

    /**
     * Resolves the root {@link EnumDef} PSI node declared for an {@code :Enum} or {@code :EnumSubset}
     * {@link SchemaType}, walking through nominal derivation parents.
     *
     * @param schemaType the candidate {@link SchemaType}
     * @return the root {@link EnumDef} PSI node, or {@code null} if {@code schemaType} does not resolve to an {@code :Enum}
     */
    public static @Nullable EnumDef resolveRootEnumDef(SchemaType schemaType) {
        var target = unwrapNominalSchemaChain(schemaType);
        if (target == null) {
            return null;
        }
        var ctor = target.getSchemaConstructor();
        return (ctor != null && ctor.getSumType() != null)
            ? ctor.getSumType().getEnumDef()
            : null;
    }

    private static Value unwrapExplicitPsiValue(Value start) {
        Value curr = start;
        for (int depth = 0; depth < 16 && curr != null; depth++) {
            if (curr.getExplicitOptionValue() != null && curr.getExplicitOptionValue().getValue() != null) {
                curr = curr.getExplicitOptionValue().getValue();
            } else if (curr.getExplicitEitherValue() != null && curr.getExplicitEitherValue().getValue() != null) {
                curr = curr.getExplicitEitherValue().getValue();
            } else if (curr.getExplicitUnionValue() != null && curr.getExplicitUnionValue().getValue() != null) {
                curr = curr.getExplicitUnionValue().getValue();
            } else {
                break;
            }
        }
        return curr != null ? curr : start;
    }

    private static String formatNominalSumMultiHopTrajectory(
        String nominalAlias,
        @Nullable SchemaType sumRhsPsi,
        @Nullable Value psiCursor,
        @Nullable StvnValue irCursor,
        StvnDefsCatalog catalog,
        PsiFile file,
        Project project,
        boolean useLong,
        Set<String> visitedAliases
    ) {
        if (!visitedAliases.add(nominalAlias)) {
            return nominalAlias;
        }
        List<String> renderedTags = new ArrayList<>();
        SchemaType currSchemaPsi = sumRhsPsi != null ? unwrapNominalSchemaChain(sumRhsPsi) : null;
        Value currPsi = psiCursor;
        StvnValue currIr = irCursor;
        boolean terminatedAtNone = false;
        boolean hitConstantBarrier = false;

        for (int hop = 0; hop < 16; hop++) {
            if (hop > 0 && currSchemaPsi != null && currSchemaPsi.getTypeKeyword() != null) {
                break;
            }
            SumType sumPsi = (currSchemaPsi != null && currSchemaPsi.getSchemaConstructor() != null)
                ? currSchemaPsi.getSchemaConstructor().getSumType()
                : null;
            if (sumPsi != null && sumPsi.getEnumDef() != null) {
                break;
            }
            boolean irIsSum = StvnSumTrajectory.isSumValue(currIr);
            if (sumPsi == null && !irIsSum) {
                break;
            }
            if (!hitConstantBarrier && currPsi != null && currIr != null) {
                var probeTraj = StvnSumTrajectory.evaluate(currPsi, currIr, catalog);
                if (probeTraj.steps().isEmpty() && probeTraj.stoppedAtConstantReference()) {
                    hitConstantBarrier = true;
                }
            }

            String sumKindText = sumPsi != null ? sumPsi.getText().trim() : "";
            List<SchemaType> branches = sumPsi != null
                ? PsiTreeUtil.getChildrenOfTypeAsList(sumPsi, SchemaType.class)
                : List.of();

            if ((currIr instanceof StvnValue.StvnOption) || sumKindText.startsWith(StvnVocabulary.TYPE_OPTION)) {
                var explicitOpt = currPsi != null ? currPsi.getExplicitOptionValue() : null;
                boolean isNone = false;
                if (currIr instanceof StvnValue.StvnOption optIr) {
                    isNone = optIr.value().isEmpty();
                } else if (explicitOpt != null) {
                    String head = explicitOpt.getText().trim().split("\\s+")[0];
                    isNone = StvnVocabulary.VAL_NONE.equals(head) || StvnVocabulary.VAL_NONE_SHORT.equals(head);
                } else if (currPsi != null) {
                    String head = currPsi.getText().trim().split("\\s+")[0];
                    isNone = StvnVocabulary.VAL_NONE.equals(head) || StvnVocabulary.VAL_NONE_SHORT.equals(head);
                }
                if (isNone) {
                    renderedTags.add(useLong ? StvnVocabulary.VAL_NONE : StvnVocabulary.VAL_NONE_SHORT);
                    terminatedAtNone = true;
                    break;
                }
                boolean isExplicit = hitConstantBarrier
                    || explicitOpt != null
                    || (currPsi != null && isExplicitOptionText(currPsi.getText().trim()));
                String tag = useLong ? StvnVocabulary.VAL_SOME : StvnVocabulary.VAL_SOME_SHORT;
                renderedTags.add(isExplicit
                    ? tag
                    : StvnVocabulary.DELIM_OPEN_BRACKET + tag + StvnVocabulary.DELIM_CLOSE_BRACKET);
                if (explicitOpt != null) {
                    currPsi = explicitOpt.getValue();
                }
                if (currIr instanceof StvnValue.StvnOption optIr) {
                    currIr = optIr.value().orElse(null);
                }
                currSchemaPsi = !branches.isEmpty() ? branches.get(0) : null;
            } else if ((currIr instanceof StvnValue.StvnEither) || sumKindText.startsWith(StvnVocabulary.TYPE_EITHER)) {
                var explicitEither = currPsi != null ? currPsi.getExplicitEitherValue() : null;
                boolean isRight;
                if (currIr instanceof StvnValue.StvnEither eitherIr) {
                    isRight = eitherIr.branch() == StvnValue.EitherBranch.RIGHT;
                } else if (explicitEither != null) {
                    String head = explicitEither.getText().trim().split("\\s+")[0];
                    isRight = !(StvnVocabulary.VAL_LEFT.equals(head) || StvnVocabulary.VAL_LEFT_SHORT.equals(head));
                } else if (currPsi != null && !branches.isEmpty()) {
                    isRight = branches.size() > 1 || !matchesSchemaPattern(currPsi, branches.get(0));
                } else {
                    isRight = true;
                }
                boolean isExplicit = hitConstantBarrier
                    || explicitEither != null
                    || (currPsi != null && isExplicitEitherText(currPsi.getText().trim()));
                String tag = isRight
                    ? (useLong ? StvnVocabulary.VAL_RIGHT : StvnVocabulary.VAL_RIGHT_SHORT)
                    : (useLong ? StvnVocabulary.VAL_LEFT : StvnVocabulary.VAL_LEFT_SHORT);
                renderedTags.add(isExplicit
                    ? tag
                    : StvnVocabulary.DELIM_OPEN_BRACKET + tag + StvnVocabulary.DELIM_CLOSE_BRACKET);
                if (explicitEither != null) {
                    currPsi = explicitEither.getValue();
                }
                if (currIr instanceof StvnValue.StvnEither eitherIr) {
                    currIr = eitherIr.value();
                }
                currSchemaPsi = isRight
                    ? (branches.size() > 1 ? branches.get(1) : null)
                    : (!branches.isEmpty() ? branches.get(0) : null);
            } else if ((currIr instanceof StvnValue.StvnUnion) || sumKindText.startsWith(StvnVocabulary.TYPE_UNION)) {
                var explicitUnion = currPsi != null ? currPsi.getExplicitUnionValue() : null;
                BigInteger explicitOneBased = (!hitConstantBarrier && currPsi != null)
                    ? extractExplicitUnionOneBasedIndex(currPsi)
                    : null;
                int zeroIdx;
                String rawTag;
                if (explicitOneBased != null) {
                    rawTag = StvnVocabulary.SIGIL_VALUE + explicitOneBased;
                    zeroIdx = toSafeZeroBasedIndex(explicitOneBased);
                } else if (currIr instanceof StvnValue.StvnUnion unionIr) {
                    rawTag = StvnVocabulary.SIGIL_VALUE + unionIr.oneBasedBranchIndex();
                    zeroIdx = toSafeZeroBasedIndex(unionIr.oneBasedBranchIndex());
                } else {
                    zeroIdx = selectBestMatchingPsiUnionBranch(currPsi, branches);
                    rawTag = StvnVocabulary.SIGIL_VALUE + (zeroIdx + 1);
                }
                boolean isExplicit = hitConstantBarrier
                    || explicitUnion != null
                    || explicitOneBased != null
                    || (currPsi != null && currPsi.getParent() instanceof ExplicitUnionValue);
                renderedTags.add(isExplicit
                    ? rawTag
                    : StvnVocabulary.DELIM_OPEN_BRACKET + rawTag + StvnVocabulary.DELIM_CLOSE_BRACKET);
                if (explicitUnion != null) {
                    currPsi = explicitUnion.getValue();
                }
                if (currIr instanceof StvnValue.StvnUnion unionIr) {
                    currIr = unionIr.value();
                }
                currSchemaPsi = (zeroIdx >= 0 && zeroIdx < branches.size()) ? branches.get(zeroIdx) : null;
            } else {
                break;
            }
        }

        String headWithTags = renderedTags.isEmpty()
            ? nominalAlias
            : nominalAlias + " " + String.join(" ", renderedTags);
        if (terminatedAtNone) {
            return headWithTags;
        }

        String leafTrace = "";
        if (currSchemaPsi != null) {
            leafTrace = resolvePsiBranchTraceWithIr(
                currSchemaPsi,
                hitConstantBarrier ? null : currPsi,
                currIr,
                catalog,
                file,
                project,
                useLong,
                new HashSet<>(),
                visitedAliases
            );
        }
        if (leafTrace.isEmpty() && currIr != null) {
            leafTrace = unspoolBranchTrajectory(currIr.schema(), currIr, file, project, useLong);
        }
        return !leafTrace.isEmpty() ? headWithTags + " (-> " + leafTrace + ")" : headWithTags;
    }

    private static int selectBestMatchingPsiUnionBranch(@Nullable Value value, List<SchemaType> branches) {
        if (value == null || branches.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < branches.size(); i++) {
            if (matchesSchemaPattern(value, branches.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private static String unspoolBranchTrajectory(
        StvnSchema branchSchema,
        @Nullable StvnValue branchValue,
        PsiFile file,
        Project project,
        boolean useLong
    ) {
        if (StvnSumTrajectory.isSumValue(branchValue) && branchValue != null) {
            StvnValue curr = branchValue;
            while (StvnSumTrajectory.isSumValue(curr)) {
                String stepAlias = curr.schema().aliasName().orElse(null);
                if (stepAlias != null) {
                    var catalog = StvnCompiledDocumentCache.getSnapshot(file).catalog();
                    var td = findTypeDefinition(file, stepAlias);
                    return formatNominalSumMultiHopTrajectory(
                        stepAlias,
                        td != null ? td.getSchemaType() : null,
                        null,
                        curr,
                        catalog,
                        file,
                        project,
                        useLong,
                        new HashSet<>()
                    );
                }
                if (curr instanceof StvnValue.StvnOption opt) {
                    if (opt.value().isEmpty()) {
                        return useLong ? StvnVocabulary.VAL_NONE : StvnVocabulary.VAL_NONE_SHORT;
                    }
                    curr = opt.value().get();
                } else if (curr instanceof StvnValue.StvnEither either) {
                    curr = either.value();
                } else if (curr instanceof StvnValue.StvnUnion union) {
                    curr = union.value();
                } else {
                    break;
                }
            }
            return curr != null ? unspoolBranchTrajectory(curr.schema(), curr, file, project, useLong) : "";
        }

        if (branchValue instanceof StvnValue.StvnEnum && branchSchema.aliasName().isEmpty()) {
            return StvnVocabulary.TYPE_ENUM;
        }

        if (branchSchema.aliasName().isPresent()) {
            var alias = branchSchema.aliasName().get();
            var shortAlias = alias.startsWith(":org/stvnadore/prelude/")
                ? StvnVocabulary.SIGIL_TYPIC + alias.substring(":org/stvnadore/prelude/".length())
                : alias;
            if (StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(shortAlias)) {
                return shortAlias;
            }
            var keywordElement = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
            var kw = keywordElement instanceof TypeDefinition td ? td.getTypeKeyword()
                : keywordElement instanceof TypeKeyword k ? k : null;
            if (kw != null) {
                var trace = StvnTypeReference.extractResolutionTrace(kw);
                if (!trace.isEmpty()) {
                    var head = alias.contains("/") ? alias : trace.get(0);
                    return trace.size() > 1
                        ? head + " -> " + String.join(" -> ", trace.subList(1, trace.size()))
                        : head;
                }
            }
            return alias;
        }

        return getLabelForSchema(branchSchema, file);
    }

    /**
     * Resolves the PSI {@link SchemaType} for a specific tagged branch of a nominal union alias.
     *
     * @param file     the containing PSI file
     * @param alias    the nominal union alias identifier
     * @param tagIndex the 0-based index of the union branch
     * @return the resolved branch {@link SchemaType}, or {@code null} if out of bounds or unresolvable
     */
    public static @Nullable SchemaType getNominalUnionBranchPsi(PsiFile file, String alias, int tagIndex) {
        var typeDef = findTypeDefinition(file, alias);
        if (typeDef == null && alias.contains("/")) {
            var bare = StvnVocabulary.SIGIL_TYPIC + alias.substring(alias.lastIndexOf('/') + 1);
            typeDef = findTypeDefinition(file, bare);
        }
        if (typeDef != null) {
            var schema = typeDef.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null) {
                    var list = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (tagIndex >= 0 && tagIndex < list.size()) {
                        return list.get(tagIndex);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves the inner value {@link SchemaType} for a nominal option alias.
     *
     * @param file  the containing PSI file
     * @param alias the nominal option alias identifier
     * @return the resolved inner {@link SchemaType}, or {@code null} if unresolvable
     */
    public static @Nullable SchemaType getNominalOptionBranchPsi(PsiFile file, String alias) {
        var typeDef = findTypeDefinition(file, alias);
        if (typeDef == null && alias.contains("/")) {
            var bare = StvnVocabulary.SIGIL_TYPIC + alias.substring(alias.lastIndexOf('/') + 1);
            typeDef = findTypeDefinition(file, bare);
        }
        if (typeDef != null) {
            var schema = typeDef.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null) {
                    var list = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (!list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves the left or right branch {@link SchemaType} for a nominal either alias.
     *
     * @param file    the containing PSI file
     * @param alias   the nominal either alias identifier
     * @param isRight {@code true} for the right branch, {@code false} for the left branch
     * @return the resolved branch {@link SchemaType}, or {@code null} if unresolvable
     */
    public static @Nullable SchemaType getNominalEitherBranchPsi(PsiFile file, String alias, boolean isRight) {
        var typeDef = findTypeDefinition(file, alias);
        if (typeDef == null && alias.contains("/")) {
            var bare = StvnVocabulary.SIGIL_TYPIC + alias.substring(alias.lastIndexOf('/') + 1);
            typeDef = findTypeDefinition(file, bare);
        }
        if (typeDef != null) {
            var schema = typeDef.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null) {
                    var list = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (isRight && list.size() > 1) {
                        return list.get(1);
                    } else if (!isRight && !list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String resolvePsiFallbackValueType(Value value) {
        var info = resolveBaseTypeInfo(value);
        if (info == null) {
            return null;
        }

        if (info.getSchema() != null && info.getSchema().getTypeKeyword() != null) {
            var governingAlias = info.getSchema().getTypeKeyword().getText().trim();
            var governingIr = resolveIrSchemaByAlias(value.getContainingFile(), governingAlias);
            if (governingIr instanceof StvnSchema.PoisonedSchema poisoned && poisoned.isUngrounded()) {
                return "\u26A0 " + governingAlias;
            }
        }

        if (!StvnTypeInferenceHelper.isStructurallyCompatible(value, info.getSchema())) {
            return null;
        }

        var resolvedSchema = resolveNominalSchema(info.getSchema());
        var schemaToInspect = resolvedSchema != null ? resolvedSchema : info.getSchema();
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor != null) {
            var sumType = constructor.getSumType();
            if (sumType != null) {
                var degradedSum = info.getSchema() != null
                    && info.getSchema().getTypeKeyword() != null
                    && isDegradedNominalAlias(value.getContainingFile(), info.getSchema().getTypeKeyword().getText().trim());
                var baseLabel = (degradedSum ? "\u26A0 " : "") + info.getLabel();
                var useLong = useLongFormSumTypes(value.getProject());
                if (sumType.getEnumDef() == null) {
                    var file = value.getContainingFile();
                    if (info.getSchema().getTypeKeyword() != null && file != null) {
                        String rootAlias = info.getSchema().getTypeKeyword().getText().trim();
                        var catalog = StvnCompiledDocumentCache.getSnapshot(file).catalog();
                        String formatted = formatNominalSumMultiHopTrajectory(
                            rootAlias,
                            schemaToInspect,
                            value,
                            null,
                            catalog,
                            file,
                            value.getProject(),
                            useLong,
                            new HashSet<>()
                        );
                        return (degradedSum ? "\u26A0 " : "") + formatted;
                    }
                    var anonSteps = new ArrayList<SumTrajectoryStep>();
                    collectPsiFallbackSumSteps(info.getSchema(), value, useLong, anonSteps, new HashSet<>());
                    if (!anonSteps.isEmpty()) {
                        var tagsJoined = String.join(" ", anonSteps.stream().map(SumTrajectoryStep::displayTag).toList());
                        return baseLabel + " " + tagsJoined;
                    }
                    return baseLabel;
                } else {
                    if (degradedSum || matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
                        if (info.getSchema().getTypeKeyword() != null) {
                            var prefix = degradedSum ? "\u26A0 " : "";
                            var trace = StvnTypeReference.extractResolutionTrace(info.getSchema().getTypeKeyword());
                            if (trace.size() > 1) {
                                return prefix + trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                            }
                            return baseLabel;
                        }
                        return StvnVocabulary.TYPE_ENUM;
                    }
                }
            }
        }

        var keyword = info.getSchema().getTypeKeyword();
        var isDegraded = keyword != null && isDegradedNominalAlias(value.getContainingFile(), keyword.getText());
        if (keyword != null) {
            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            if (trace.size() > 1 && (isDegraded || matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema()))) {
                if (!StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(keyword.getText())) {
                    var prefix = isDegraded ? "\u26A0 " : "";
                    return prefix + trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                }
                return info.getLabel();
            }
        }
        if (isDegraded || matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
            var prefix = isDegraded ? "\u26A0 " : "";
            return prefix + info.getLabel();
        }
        if (value.getCollectionValue() != null) {
            return info.getLabel();
        }
        return null;
    }

    private static String resolvePsiBranchTrace(
        SchemaType branchSchema,
        @Nullable Value innerValue,
        Project project
    ) {
        var file = branchSchema.getContainingFile();
        var catalog = file != null ? StvnCompiledDocumentCache.getSnapshot(file).catalog() : StvnDefsCatalog.EMPTY;
        return resolvePsiBranchTraceWithIr(
            branchSchema,
            innerValue,
            null,
            catalog,
            file,
            project,
            useLongFormSumTypes(project),
            new HashSet<>(),
            new HashSet<>()
        );
    }

    private static String resolvePsiBranchTraceWithIr(
        SchemaType branchSchema,
        @Nullable Value innerValue,
        @Nullable StvnValue innerIr,
        StvnDefsCatalog catalog,
        @Nullable PsiFile file,
        Project project,
        boolean useLong,
        Set<SchemaType> visitedSchemas,
        Set<String> visitedAliases
    ) {
        if (!visitedSchemas.add(branchSchema)) {
            return "";
        }
        var keyword = branchSchema.getTypeKeyword();
        if (keyword != null) {
            var shortKw = keyword.getText().startsWith(":org/stvnadore/prelude/")
                ? StvnVocabulary.SIGIL_TYPIC + keyword.getText().substring(":org/stvnadore/prelude/".length())
                : keyword.getText();
            if (StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(shortKw)) {
                return shortKw;
            }
            var lookupFile = file != null ? file : keyword.getContainingFile();
            var resolvedTarget = lookupFile != null
                ? StvnTypeReference.resolveTypeInFile(lookupFile, keyword.getText(), new HashSet<>())
                : null;
            var targetDef = resolveUnderlyingTypeDefinition(resolvedTarget);
            if (targetDef != null) {
                var targetSchema = targetDef.getSchemaType();
                var unwrappedTarget = targetSchema != null ? unwrapNominalSchemaChain(targetSchema) : null;
                if (unwrappedTarget != null
                        && unwrappedTarget.getSchemaConstructor() != null
                        && unwrappedTarget.getSchemaConstructor().getSumType() != null
                        && unwrappedTarget.getSchemaConstructor().getSumType().getEnumDef() == null
                        && lookupFile != null) {
                    return formatNominalSumMultiHopTrajectory(
                        keyword.getText().trim(),
                        targetSchema,
                        innerValue,
                        innerIr,
                        catalog,
                        lookupFile,
                        project,
                        useLong,
                        visitedAliases
                    );
                }
            }

            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            if (!trace.isEmpty()) {
                return String.join(" -> ", trace);
            }
        }
        if (branchSchema.getSchemaConstructor() != null && branchSchema.getSchemaConstructor().getSumType() != null
            && branchSchema.getSchemaConstructor().getSumType().getEnumDef() != null) {
            return StvnVocabulary.TYPE_ENUM;
        }
        return StvnSchemaFormatter.formatSchema(branchSchema);
    }

    private static void collectPsiFallbackSumSteps(
        SchemaType startSchema,
        @Nullable Value startValue,
        boolean useLong,
        List<SumTrajectoryStep> outSteps,
        Set<SchemaType> visited
    ) {
        SchemaType currSchema = startSchema;
        Value currVal = startValue;
        for (int depth = 0; depth < 16 && currSchema != null; depth++) {
            SchemaType inspected = unwrapNominalSchemaChain(currSchema);
            if (inspected == null || !visited.add(inspected)) {
                break;
            }
            var ctor = inspected.getSchemaConstructor();
            var sum = ctor != null ? ctor.getSumType() : null;
            if (sum == null || sum.getEnumDef() != null) {
                break;
            }
            int anchorOffset = currVal != null ? currVal.getTextRange().getStartOffset() : 0;
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            String sumText = sum.getText().trim();
            if (sumText.startsWith(StvnVocabulary.TYPE_OPTION)) {
                var optVal = currVal != null ? currVal.getExplicitOptionValue() : null;
                String firstToken = currVal != null ? currVal.getText().trim().split("\\s+")[0] : "";
                boolean isNone = StvnVocabulary.VAL_NONE.equals(firstToken) || StvnVocabulary.VAL_NONE_SHORT.equals(firstToken);
                if (isNone) {
                    String noneTag = useLong ? StvnVocabulary.VAL_NONE : StvnVocabulary.VAL_NONE_SHORT;
                    outSteps.add(new SumTrajectoryStep(false, noneTag, noneTag, anchorOffset));
                    break;
                }
                boolean explicit = optVal != null || (currVal != null && isExplicitOptionText(currVal.getText().trim()));
                String someTag = useLong ? StvnVocabulary.VAL_SOME : StvnVocabulary.VAL_SOME_SHORT;
                String display = explicit
                    ? someTag
                    : StvnVocabulary.DELIM_OPEN_BRACKET + someTag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                outSteps.add(new SumTrajectoryStep(!explicit, display, someTag, anchorOffset));
                if (optVal != null) {
                    currVal = optVal.getValue();
                }
                currSchema = !branches.isEmpty() ? branches.get(0) : null;
            } else if (sumText.startsWith(StvnVocabulary.TYPE_EITHER)) {
                var eitherVal = currVal != null ? currVal.getExplicitEitherValue() : null;
                boolean isLeft = false;
                if (eitherVal != null) {
                    String head = eitherVal.getText().trim().split("\\s+")[0];
                    isLeft = StvnVocabulary.VAL_LEFT.equals(head) || StvnVocabulary.VAL_LEFT_SHORT.equals(head);
                } else if (currVal != null && !branches.isEmpty() && branches.size() == 1) {
                    isLeft = matchesSchemaPattern(currVal, branches.get(0));
                }
                boolean explicit = eitherVal != null || (currVal != null && isExplicitEitherText(currVal.getText().trim()));
                String tag = isLeft
                    ? (useLong ? StvnVocabulary.VAL_LEFT : StvnVocabulary.VAL_LEFT_SHORT)
                    : (useLong ? StvnVocabulary.VAL_RIGHT : StvnVocabulary.VAL_RIGHT_SHORT);
                String display = explicit
                    ? tag
                    : StvnVocabulary.DELIM_OPEN_BRACKET + tag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                outSteps.add(new SumTrajectoryStep(!explicit, display, tag, anchorOffset));
                if (eitherVal != null) {
                    currVal = eitherVal.getValue();
                }
                currSchema = isLeft
                    ? (!branches.isEmpty() ? branches.get(0) : null)
                    : (branches.size() > 1 ? branches.get(1) : null);
            } else if (sumText.startsWith(StvnVocabulary.TYPE_UNION)) {
                var unionVal = currVal != null ? currVal.getExplicitUnionValue() : null;
                BigInteger explicitIdx = currVal != null ? extractExplicitUnionOneBasedIndex(currVal) : null;
                int zeroIdx = explicitIdx != null
                    ? toSafeZeroBasedIndex(explicitIdx)
                    : selectBestMatchingPsiUnionBranch(currVal, branches);
                String tag = explicitIdx != null
                    ? StvnVocabulary.SIGIL_VALUE + explicitIdx
                    : StvnVocabulary.SIGIL_VALUE + (zeroIdx + 1);
                boolean explicit = unionVal != null || explicitIdx != null;
                String display = explicit
                    ? tag
                    : StvnVocabulary.DELIM_OPEN_BRACKET + tag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                outSteps.add(new SumTrajectoryStep(!explicit, display, tag, anchorOffset));
                if (unionVal != null) {
                    currVal = unionVal.getValue();
                }
                currSchema = (zeroIdx >= 0 && zeroIdx < branches.size()) ? branches.get(zeroIdx) : null;
            } else {
                break;
            }
        }
    }

    private static @Nullable SchemaType unwrapAllSumLayersToLeafSchema(
        SchemaType startSchema,
        @Nullable Value psiCursor,
        @Nullable StvnValue irCursor,
        Set<SchemaType> visited
    ) {
        SchemaType current = startSchema;
        Value currPsi = psiCursor;
        StvnValue currIr = irCursor;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            if (isUngroundedSchemaType(current)) {
                break;
            }
            SchemaType inspected = unwrapNominalSchemaChain(current);
            if (inspected == null || !visited.add(inspected)) {
                break;
            }
            var ctor = inspected.getSchemaConstructor();
            var sum = ctor != null ? ctor.getSumType() : null;
            if (sum == null || sum.getEnumDef() != null) {
                break;
            }
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            if (branches.isEmpty()) {
                break;
            }
            String sumText = sum.getText().trim();
            if (sumText.startsWith(StvnVocabulary.TYPE_OPTION)) {
                String head = currPsi != null ? currPsi.getText().trim().split("\\s+")[0] : "";
                if (StvnVocabulary.VAL_NONE.equals(head) || StvnVocabulary.VAL_NONE_SHORT.equals(head)
                        || (currIr instanceof StvnValue.StvnOption optIr && optIr.value().isEmpty())) {
                    return current;
                }
                if (currPsi != null && currPsi.getExplicitOptionValue() != null) {
                    currPsi = currPsi.getExplicitOptionValue().getValue();
                }
                if (currIr instanceof StvnValue.StvnOption optIr) {
                    currIr = optIr.value().orElse(null);
                }
                current = branches.get(0);
            } else if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() >= 2) {
                boolean isLeft = false;
                if (currIr instanceof StvnValue.StvnEither eitherIr) {
                    isLeft = eitherIr.branch() == StvnValue.EitherBranch.LEFT;
                    currIr = eitherIr.value();
                } else if (currPsi != null && currPsi.getExplicitEitherValue() != null) {
                    String head = currPsi.getExplicitEitherValue().getText().trim().split("\\s+")[0];
                    isLeft = StvnVocabulary.VAL_LEFT.equals(head) || StvnVocabulary.VAL_LEFT_SHORT.equals(head);
                } else if (currPsi != null) {
                    isLeft = !matchesSchemaPattern(currPsi, branches.get(1)) && matchesSchemaPattern(currPsi, branches.get(0));
                }
                if (currPsi != null && currPsi.getExplicitEitherValue() != null) {
                    currPsi = currPsi.getExplicitEitherValue().getValue();
                }
                current = isLeft ? branches.get(0) : branches.get(1);
            } else if (sumText.startsWith(StvnVocabulary.TYPE_UNION)) {
                int idx = -1;
                if (currIr instanceof StvnValue.StvnUnion unionIr) {
                    idx = toSafeZeroBasedIndex(unionIr.oneBasedBranchIndex());
                    currIr = unionIr.value();
                } else if (currPsi != null) {
                    BigInteger expIdx = extractExplicitUnionOneBasedIndex(currPsi);
                    idx = expIdx != null ? toSafeZeroBasedIndex(expIdx) : selectBestMatchingPsiUnionBranch(currPsi, branches);
                }
                if (currPsi != null && currPsi.getExplicitUnionValue() != null) {
                    currPsi = currPsi.getExplicitUnionValue().getValue();
                }
                current = (idx >= 0 && idx < branches.size()) ? branches.get(idx) : null;
            } else {
                break;
            }
        }
        return current;
    }

    /**
     * Checks whether long-form sum type syntax is enabled in the project settings.
     *
     * @param project the IntelliJ project
     * @return {@code true} if long-form sum type formatting is enabled, {@code false} otherwise
     */
    public static boolean useLongFormSumTypes(Project project) {
        var settings = StvnSettings.getInstance(project);
        return settings != null && settings.getState().useLongFormSumTypes;
    }

    /**
     * Determines whether a PSI value element conforms to the structure of the specified schema type.
     *
     * @param value  the PSI value element to test
     * @param schema the expected schema type
     * @return {@code true} if the value matches the schema pattern, {@code false} otherwise
     */
    public static boolean matchesSchemaPattern(@Nullable Value value, SchemaType schema) {
        return matchesSchemaPattern(value, schema, new HashSet<>(), new HashSet<>());
    }

    private static boolean matchesSchemaPattern(
        @Nullable Value value,
        SchemaType schema,
        Set<SchemaType> visitedSchemas,
        Set<String> visitedConstants
    ) {
        if (value == null) {
            return false;
        }
        var valKwForConst = value.getValueKeyword();
        if (valKwForConst != null && value.getContainingFile() != null) {
            String constName = valKwForConst.getText().trim();
            if (!visitedConstants.add(constName)) {
                return false;
            }
            var constDef = StvnPsiLiteralEvaluator.resolveConstantDefinition(value, constName);
            if (constDef != null && constDef.getValue() != null) {
                return matchesSchemaPattern(constDef.getValue(), schema, visitedSchemas, visitedConstants);
            }
        }
        if (!visitedSchemas.add(schema)) {
            return false;
        }
        var resolved = resolveNominalSchema(schema);
        var schemaToInspect = (resolved != null) ? resolved : schema;
        if (schemaToInspect != schema && !visitedSchemas.add(schemaToInspect)) {
            return false;
        }
        var text = StvnSchemaFormatter.formatSchema(schemaToInspect);
        var stripped = (text.startsWith("{") && text.contains("}"))
            ? text.substring(text.lastIndexOf('}') + 1).trim()
            : text;
        if (stripped.equals(StvnVocabulary.TYPE_INT) || stripped.equals(StvnVocabulary.TYPE_TIME_EPOCH)) {
            return value.getIntegerLiteral() != null;
        }
        if (stripped.equals(StvnVocabulary.TYPE_FLOAT)) {
            return value.getFloatLiteral() != null;
        }
        if (stripped.equals(StvnVocabulary.TYPE_STRING)
            || stripped.equals(StvnVocabulary.TYPE_DATE_TIME)) {
            return value.getStringLiteral() != null;
        }
        if (stripped.equals(StvnVocabulary.TYPE_BOOLEAN)) {
            return value.getBooleanValue() != null || StvnVocabulary.BOOLEAN_KEYWORDS.contains(value.getText());
        }
        if (isConstructorMatch(stripped, StvnVocabulary.TYPE_TUPLE)) {
            var collVal = value.getCollectionValue();
            return collVal != null && collVal.getTupleLiteral() != null;
        }
        if (isConstructorMatch(stripped, StvnVocabulary.TYPE_SEQ) || isConstructorMatch(stripped, StvnVocabulary.TYPE_SET)) {
            var collVal = value.getCollectionValue();
            return collVal != null && collVal.getListLiteral() != null;
        }
        if (isConstructorMatch(stripped, StvnVocabulary.TYPE_MAP)) {
            var collVal = value.getCollectionValue();
            return collVal != null && collVal.getMapLiteral() != null;
        }
        if (isConstructorMatch(stripped, StvnVocabulary.TYPE_OPTION)) {
            if (value.getExplicitOptionValue() != null
                || value.getText().equals(StvnVocabulary.VAL_NONE)
                || value.getText().equals(StvnVocabulary.VAL_NONE_SHORT)) {
                return true;
            }
            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor != null && constructor.getSumType() != null) {
                var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(constructor.getSumType(), SchemaType.class);
                if (!innerSchemas.isEmpty()) {
                    return matchesSchemaPattern(value, innerSchemas.get(0), visitedSchemas, new HashSet<>(visitedConstants));
                }
            }
            return false;
        }
        if (isConstructorMatch(stripped, StvnVocabulary.TYPE_EITHER)) {
            if (value.getExplicitEitherValue() != null) {
                return true;
            }
            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor != null && constructor.getSumType() != null) {
                var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(constructor.getSumType(), SchemaType.class);
                if (innerSchemas.size() >= 2) {
                    return matchesSchemaPattern(value, innerSchemas.get(1), visitedSchemas, new HashSet<>(visitedConstants));
                }
            }
            return false;
        }
        if (isConstructorMatch(stripped, StvnVocabulary.TYPE_UNION)) {
            if (value.getExplicitUnionValue() != null) {
                var expUnion = value.getExplicitUnionValue();
                var tagElem = expUnion.getUnionTagPrefix();
                var tagText = tagElem.getText().trim();
                var constructor = schemaToInspect.getSchemaConstructor();
                if (constructor != null && constructor.getSumType() != null) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(constructor.getSumType(), SchemaType.class);
                    var resolvedIdx = StvnPsiLiteralEvaluator.resolveZeroBasedUnionBranchIndex(tagText, innerSchemas.size());
                    if (resolvedIdx.isPresent()) {
                        var innerVal = expUnion.getValue();
                        return innerVal == null
                            || matchesSchemaPattern(innerVal, innerSchemas.get(resolvedIdx.getAsInt()), visitedSchemas, new HashSet<>(visitedConstants));
                    }
                }
                return false;
            }
        }
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor != null) {
            var sum = constructor.getSumType();
            if (sum != null) {
                if (sum.getEnumDef() != null) {
                    var valKw = value.getValueKeyword();
                    if (valKw != null) {
                        for (var kw : sum.getEnumDef().getValueKeywordList()) {
                            if (kw.getText().equals(valKw.getText())) {
                                return true;
                            }
                        }
                    }
                    var txt = value.getText().trim();
                    for (var kw : sum.getEnumDef().getValueKeywordList()) {
                        if (kw.getText().equals(txt)) {
                            return true;
                        }
                    }
                }
                var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return innerSchemas.stream().anyMatch(s -> matchesSchemaPattern(value, s, visitedSchemas, new HashSet<>(visitedConstants)));
            }
        }
        return false;
    }

    /**
     * Resolves the base schema type info and human-readable label for a value element inside a body entry.
     *
     * @param element the PSI value element to resolve
     * @return the resolved type info, or {@code null} if unresolvable
     */
    public static @Nullable ResolvedTypeInfo resolveBaseTypeInfo(Value element) {
        var bodyEntry = findBodyEntryParent(element);
        var constDef = bodyEntry == null ? PsiTreeUtil.getParentOfType(element, ConstantDefinition.class, false) : null;
        if (bodyEntry == null && constDef == null) {
            return null;
        }

        var path = new Stack<PathStep>();
        var curr = (PsiElement) element;
        while (curr != null) {
            var parent = curr.getParent();
            if (parent instanceof BodyEntry || parent instanceof ConstantDefinition) {
                break;
            }

            if (parent instanceof TupleLiteral tuple) {
                var values = tuple.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx != -1) {
                    path.push(new PathStep(StepType.TUPLE, idx));
                }
            } else if (parent instanceof ListLiteral) {
                path.push(new PathStep(StepType.LIST, -1));
            } else if (parent instanceof MapLiteral map) {
                var values = map.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx != -1) {
                    var isKey = (idx % 2 == 0);
                    path.push(new PathStep(isKey ? StepType.MAP_KEY : StepType.MAP_VALUE, isKey ? 0 : 1));
                    path.push(new PathStep(StepType.MAP, -1));
                }
            } else if (parent instanceof ExplicitOptionValue) {
                path.push(new PathStep(StepType.OPTION, -1));
            } else if (parent instanceof ExplicitEitherValue either) {
                var text = either.getText().trim();
                var isLeft = text.startsWith(StvnVocabulary.VAL_LEFT) || text.startsWith(StvnVocabulary.VAL_LEFT_SHORT);
                path.push(new PathStep(StepType.EITHER, isLeft ? 0 : 1));
            } else if (parent instanceof ExplicitUnionValue union) {
                var firstChild = union.getUnionTagPrefix();
                int tagIndex = parseUnionTagZeroBasedIndex(firstChild.getText());
                path.push(new PathStep(StepType.UNION, tagIndex));
            }

            curr = parent;
        }

        SchemaType rootSchemaType;
        if (bodyEntry != null) {
            var containingFile = bodyEntry.getContainingFile();
            if (containingFile == null) {
                return null;
            }
            var typeEntry = PsiTreeUtil.findChildOfType(containingFile, TypeEntry.class);
            if (typeEntry == null) {
                return null;
            }
            rootSchemaType = typeEntry.getSchemaType();
        } else {
            rootSchemaType = constDef.getSchemaType();
        }
        if (rootSchemaType == null) {
            return null;
        }

        var currentSchema = descendSchemaPath(rootSchemaType, path);
        if (currentSchema == null) {
            return null;
        }

        String finalLabel;
        var keyword = currentSchema.getTypeKeyword();
        if (keyword != null) {
            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            finalLabel = formatTrace(trace);
        } else {
            finalLabel = formatSchemaType(currentSchema);
        }

        if (finalLabel.isEmpty()) {
            return null;
        }

        return new ResolvedTypeInfo(currentSchema, finalLabel);
    }

    /**
     * Checks whether a PSI {@link SchemaType} refers to an ungrounded {@link StvnSchema.PoisonedSchema}
     * in the lowered IR or forms a pure PSI nominal alias cycle.
     *
     * @param schemaType the PSI schema type to evaluate
     * @return {@code true} if the schema is ungrounded in IR or circular in PSI; {@code false} otherwise
     */
    public static boolean isUngroundedSchemaType(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return false;
        }
        if (schemaType.getTypeKeyword() != null && schemaType.getContainingFile() != null) {
            var aliasName = schemaType.getTypeKeyword().getText().trim();
            var irSchema = resolveIrSchemaByAlias(schemaType.getContainingFile(), aliasName);
            if (irSchema instanceof StvnSchema.PoisonedSchema poisoned) {
                return poisoned.isUngrounded();
            }
            if (irSchema != null) {
                return false;
            }
        }
        var unwrapped = unwrapNominalSchemaChain(schemaType);
        return unwrapped == null || unwrapped.getSchemaConstructor() == null;
    }

    private static @Nullable SchemaType unwrapNominalSchemaChain(SchemaType schema) {
        var current = schema;
        var visited = new HashSet<PsiElement>();
        while (current.getSchemaConstructor() == null) {
            if (!visited.add(current)) {
                return null;
            }
            var next = resolveNominalSchema(current);
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static @Nullable SchemaType descendSchemaPath(SchemaType rootSchema, Stack<PathStep> path) {
        var currentSchema = rootSchema;
        while (!path.isEmpty() && currentSchema != null) {
            if (isUngroundedSchemaType(currentSchema)) {
                return null;
            }
            var step = path.pop();
            var schemaToInspect = unwrapNominalSchemaChain(currentSchema);
            if (schemaToInspect == null) {
                return null;
            }

            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor == null) {
                return null;
            }

            var sum = constructor.getSumType();
            while (sum != null) {
                SchemaType innerSchema = null;
                if (step.type != StepType.OPTION && sum.getText().startsWith(StvnVocabulary.TYPE_OPTION)) {
                    var optBranches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (!optBranches.isEmpty()) {
                        innerSchema = optBranches.get(0);
                    }
                } else if (step.type != StepType.EITHER && sum.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                    var eitherBranches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (eitherBranches.size() >= 2) {
                        innerSchema = eitherBranches.get(1);
                    }
                }
                if (innerSchema == null) {
                    break;
                }
                if (isUngroundedSchemaType(innerSchema)) {
                    return null;
                }
                schemaToInspect = unwrapNominalSchemaChain(innerSchema);
                if (schemaToInspect == null) {
                    return null;
                }
                var c = schemaToInspect.getSchemaConstructor();
                if (c == null) {
                    break;
                }
                constructor = c;
                sum = c.getSumType();
            }

            if (step.type == StepType.LIST) {
                var collection = constructor.getCollectionType();
                var firstChild = collection != null ? collection.getFirstChild() : null;
                var collType = firstChild != null ? firstChild.getText() : "";
                if (collection != null
                        && !collection.getSchemaTypeList().isEmpty()
                        && (collType.equals(StvnVocabulary.TYPE_SEQ) || collType.equals(StvnVocabulary.TYPE_SET))) {
                    currentSchema = collection.getSchemaTypeList().get(0);
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.MAP) {
                var collection = constructor.getCollectionType();
                var firstChild = collection != null ? collection.getFirstChild() : null;
                if (firstChild == null || !firstChild.getText().equals(StvnVocabulary.TYPE_MAP)) {
                    currentSchema = null;
                }
            } else if (step.type == StepType.MAP_KEY || step.type == StepType.MAP_VALUE) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var innerSchemas = collection.getSchemaTypeList();
                    var targetIndex = (step.type == StepType.MAP_KEY) ? 0 : 1;
                    currentSchema = (targetIndex >= 0 && targetIndex < innerSchemas.size())
                        ? innerSchemas.get(targetIndex)
                        : null;
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.TUPLE) {
                var product = constructor.getProductType();
                if (product != null) {
                    var innerSchemas = product.getSchemaTypeList();
                    currentSchema = (step.index >= 0 && step.index < innerSchemas.size())
                        ? innerSchemas.get(step.index)
                        : null;
                } else if (step.index == 0 && (constructor.getSumType() != null || constructor.getAtomicType() != null)) {
                    currentSchema = (constructor.getSumType() != null) ? schemaToInspect : currentSchema;
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.OPTION) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(StvnVocabulary.TYPE_OPTION)) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    currentSchema = !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.EITHER) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    currentSchema = (step.index >= 0 && step.index < innerSchemas.size())
                        ? innerSchemas.get(step.index)
                        : null;
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.UNION) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    currentSchema = (innerSchemas.size() >= 2 && step.index >= 0 && step.index < innerSchemas.size())
                        ? innerSchemas.get(step.index)
                        : null;
                } else {
                    currentSchema = null;
                }
            }
        }
        return currentSchema;
    }

    /**
     * Resolves the governing nominal {@link TypeDefinition} in {@code :defs} (or {@code :package},
     * {@code :include}, or Standard Library Prelude) for a payload {@link Value} in {@code :body}
     * or {@link ConstantDefinition}. Returns {@code null} if the value is governed by an unaliased
     * primitive/base type or anonymous composite constructor.
     *
     * @param value the payload value element
     * @return the governing nominal {@link TypeDefinition}, or {@code null} if unaliased
     */
    public static @Nullable TypeDefinition resolveGoverningNominalTypeDefinition(Value value) {
        var info = resolveBaseTypeInfo(value);
        if (info != null) {
            var schema = info.getSchema();

            // 1. Check if the slot schema itself carries a nominal TypeKeyword (e.g. :Port, :GatewayTuple, :Stage9V3Envelope)
            var directTypeDef = resolveNominalTypeDefinitionFromSchema(schema);
            if (directTypeDef != null) {
                return directTypeDef;
            }

            // 2. If the slot schema is an anonymous sum constructor (:Option(:Port), :Either(:Port :HostName), :Union(...))
            // and the value is an implicitly inferred branch payload, unwrap the inferred branch and check its TypeKeyword.
            if (value.getExplicitOptionValue() == null
                    && value.getExplicitEitherValue() == null
                    && value.getExplicitUnionValue() == null) {
                var unwrappedBranch = unwrapImplicitSumBranchSchema(schema, value);
                if (unwrappedBranch != null) {
                    var branchTypeDef = resolveNominalTypeDefinitionFromSchema(unwrappedBranch);
                    if (branchTypeDef != null) {
                        return branchTypeDef;
                    }
                }
            }
        }

        // 3. Fallback to core IR node alias when value resides inside a multi-branch implicit union container
        var coreNode = resolveCoreValue(value);
        if (coreNode != null && !(coreNode instanceof StvnValue.StvnErrorValue)) {
            var aliasOpt = coreNode.schema().aliasName();
            if (aliasOpt.isPresent()) {
                var aliasName = aliasOpt.get();
                if (!StvnNamespaceRules.isReservedFundamentalType(aliasName)) {
                    return findTypeDefinition(value.getContainingFile(), aliasName, value);
                }
            }
        }
        return null;
    }

    /**
     * Resolves the governing declaration target PSI element for a payload {@link Value} in {@code :body}
     * or {@link ConstantDefinition}.
     * <p>
     * Resolution priority:
     * <ol>
     *   <li>If the value is governed by a nominal {@link TypeDefinition} (via {@link #resolveGoverningNominalTypeDefinition(Value)}),
     *       returns that {@link TypeDefinition}.</li>
     *   <li>If the value is governed by an inline composite, collection, or sum {@link SchemaConstructor}
     *       ({@code :Seq(...)}, {@code :Set(...)}, {@code :Map(...)}, {@code :Tuple(...)}, {@code :Option(...)},
     *       {@code :Either(...)}, {@code :Union(...)}, or inline {@code :Enum [...]}) that is declared inside
     *       a {@link TypeDefinition} in {@code :defs}, returns that inline {@link SchemaType} PSI element
     *       (or the enclosing {@link TypeDefinition} if the constructor is the top-level RHS of that definition).</li>
     *   <li>Otherwise (unaliased scalar primitives or anonymous constructors declared in root {@code :type} outside {@code :defs}),
     *       returns {@code null}.</li>
     * </ol>
     *
     * @param value the payload value element to resolve
     * @return the governing {@link TypeDefinition} or inline member {@link SchemaType} in {@code :defs}, or {@code null}
     */
    public static @Nullable PsiElement resolveGoverningDeclarationTarget(@Nullable Value value) {
        if (value == null) {
            return null;
        }
        var nominalTypeDef = resolveGoverningNominalTypeDefinition(value);
        if (nominalTypeDef != null) {
            return nominalTypeDef;
        }

        var info = resolveBaseTypeInfo(value);
        if (info == null) {
            return null;
        }

        var candidateSchema = info.getSchema();
        if (value.getExplicitOptionValue() == null
                && value.getExplicitEitherValue() == null
                && value.getExplicitUnionValue() == null) {
            var unwrapped = unwrapImplicitSumBranchSchema(candidateSchema, value);
            if (unwrapped != null) {
                candidateSchema = unwrapped;
            }
        }

        if (candidateSchema.getTypeKeyword() == null && candidateSchema.getSchemaConstructor() != null) {
            var ctor = candidateSchema.getSchemaConstructor();
            if (ctor.getCollectionType() != null || ctor.getProductType() != null || ctor.getSumType() != null) {
                var enclosingTypeDef = PsiTreeUtil.getParentOfType(candidateSchema, TypeDefinition.class);
                if (enclosingTypeDef != null) {
                    return enclosingTypeDef.getSchemaType() == candidateSchema ? enclosingTypeDef : candidateSchema;
                }
            }
        }
        return null;
    }

    private static @Nullable TypeDefinition resolveNominalTypeDefinitionFromSchema(@Nullable SchemaType schema) {
        if (schema == null) {
            return null;
        }
        var typeKw = schema.getTypeKeyword();
        if (typeKw == null) {
            return null;
        }
        var kwText = typeKw.getText().trim();
        if (StvnNamespaceRules.isReservedFundamentalType(kwText)) {
            return null;
        }
        var resolved = new StvnTypeReference(typeKw).resolve();
        if (resolved != null) {
            var targetDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
            if (targetDef != null) {
                return targetDef;
            }
        }
        return findTypeDefinition(typeKw.getContainingFile(), kwText, typeKw);
    }

    private static @Nullable SchemaType unwrapImplicitSumBranchSchema(SchemaType schema, Value value) {
        SchemaType current = schema;
        var coreNode = resolveCoreValue(value);
        for (int depth = 0; depth < 16 && current != null; depth++) {
            var inspected = unwrapNominalSchemaChain(current);
            var ctor = inspected != null ? inspected.getSchemaConstructor() : null;
            var sum = ctor != null ? ctor.getSumType() : null;
            if (sum == null || sum.getEnumDef() != null) {
                break;
            }
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            if (branches.isEmpty()) {
                break;
            }
            var sumText = sum.getText().trim();
            if (sumText.startsWith(StvnVocabulary.TYPE_OPTION)) {
                current = branches.get(0);
                if (coreNode instanceof StvnValue.StvnOption optVal && optVal.value().isPresent()) {
                    coreNode = optVal.value().get();
                }
            } else if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() >= 2) {
                if (coreNode instanceof StvnValue.StvnEither eitherVal) {
                    boolean isLeft = eitherVal.branch() == StvnValue.EitherBranch.LEFT;
                    current = isLeft ? branches.get(0) : branches.get(1);
                    coreNode = eitherVal.value();
                } else {
                    current = matchesSchemaPattern(value, branches.get(1)) ? branches.get(1) : branches.get(0);
                }
            } else if (sumText.startsWith(StvnVocabulary.TYPE_UNION)) {
                if (coreNode instanceof StvnValue.StvnUnion unionVal) {
                    int idx = toSafeZeroBasedIndex(unionVal.oneBasedBranchIndex());
                    if (idx >= 0 && idx < branches.size()) {
                        current = branches.get(idx);
                        coreNode = unionVal.value();
                    } else {
                        break;
                    }
                } else {
                    SchemaType matched = null;
                    for (var b : branches) {
                        if (matchesSchemaPattern(value, b)) {
                            matched = b;
                            break;
                        }
                    }
                    current = matched;
                }
            } else {
                break;
            }
            if (current != null && current.getTypeKeyword() != null) {
                var nextInspected = unwrapNominalSchemaChain(current);
                var nextCtor = nextInspected != null ? nextInspected.getSchemaConstructor() : null;
                if (nextCtor == null || nextCtor.getSumType() == null || nextCtor.getSumType().getEnumDef() != null) {
                    return current;
                }
            }
        }
        return current;
    }

    /**
     * Resolves a {@link ValueKeyword} representing an {@code :Enum} variant (either in a
     * {@code #filterIncl}/{@code #filterExcl} {@link VariantList} or in a {@code :body} /
     * {@link ConstantDefinition} payload {@link Value}) to its declaration {@link ValueKeyword}
     * inside the root {@link EnumDef}.
     *
     * @param valKw the enum variant value keyword
     * @return the declaration {@link ValueKeyword} inside the root {@link EnumDef}, or {@code null}
     */
    public static @Nullable ValueKeyword resolveGoverningEnumVariant(ValueKeyword valKw) {
        var variantName = valKw.getText().trim();
        SchemaType startSchema = null;
        if (PsiTreeUtil.getParentOfType(valKw, VariantList.class) != null) {
            startSchema = PsiTreeUtil.getParentOfType(valKw, SchemaType.class);
            if (startSchema == null) {
                var enclosingTypeDef = PsiTreeUtil.getParentOfType(valKw, TypeDefinition.class);
                if (enclosingTypeDef != null) {
                    startSchema = enclosingTypeDef.getSchemaType();
                }
            }
        } else {
            var enclosingValue = PsiTreeUtil.getParentOfType(valKw, Value.class);
            if (enclosingValue != null) {
                var info = resolveBaseTypeInfo(enclosingValue);
                if (info != null) {
                    startSchema = info.getSchema();
                    if (startSchema.getTypeKeyword() == null
                            && enclosingValue.getExplicitOptionValue() == null
                            && enclosingValue.getExplicitEitherValue() == null
                            && enclosingValue.getExplicitUnionValue() == null) {
                        var unwrapped = unwrapImplicitSumBranchSchema(startSchema, enclosingValue);
                        if (unwrapped != null) {
                            startSchema = unwrapped;
                        }
                    }
                }
            }
        }
        if (startSchema == null) {
            return null;
        }
        var targetSchema = unwrapNominalSchemaChain(startSchema);
        var ctor = targetSchema.getSchemaConstructor();
        if (ctor != null && ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
            for (var kw : ctor.getSumType().getEnumDef().getValueKeywordList()) {
                if (variantName.equals(kw.getText().trim())) {
                    return kw;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the {@code Ctrl+Click} navigation target for an {@code :Enum} variant
     * {@link ValueKeyword} in {@code :body}, {@link ConstantDefinition}, or
     * {@code #filterIncl}/{@code #filterExcl} {@link VariantList}:
     * <ul>
     *   <li>Root {@code :Enum[...]} or unconstrained nominal alias / {@code #strip} projection:
     *       returns the declaration {@link ValueKeyword} inside {@link EnumDef}.</li>
     *   <li>Positive {@code #filterIncl [...]} restriction (without {@code #filterExcl}):
     *       Hop 1 returns the explicit {@link ValueKeyword} inside {@code #filterIncl};
     *       Hop 2 from {@code #filterIncl} returns the ancestor {@code #filterIncl} or root
     *       {@link EnumDef} {@link ValueKeyword}.</li>
     *   <li>Negative {@code #filterExcl [...]} restriction (or Safeguard 4 mutex-conflicted filter):
     *       stops at the restricting schema and returns the governing {@link TypeDefinition}
     *       (or inline {@link SchemaType}).</li>
     * </ul>
     *
     * @param valKw the enum variant value keyword
     * @return the target {@link ValueKeyword}, {@link TypeDefinition}, or {@link SchemaType}, or {@code null}
     */
    public static @Nullable PsiElement resolveEnumVariantNavigationTarget(ValueKeyword valKw) {
        var variantName = valKw.getText().trim();
        if (PsiTreeUtil.getParentOfType(valKw, VariantList.class) != null) {
            var enclosingSchema = PsiTreeUtil.getParentOfType(valKw, SchemaType.class);
            if (enclosingSchema == null) {
                var enclosingTypeDef = PsiTreeUtil.getParentOfType(valKw, TypeDefinition.class);
                enclosingSchema = enclosingTypeDef != null ? enclosingTypeDef.getSchemaType() : null;
            }
            if (enclosingSchema == null) {
                return null;
            }
            var inlineCtor = enclosingSchema.getSchemaConstructor();
            if (inlineCtor != null && inlineCtor.getSumType() != null && inlineCtor.getSumType().getEnumDef() != null) {
                for (var kw : inlineCtor.getSumType().getEnumDef().getValueKeywordList()) {
                    if (variantName.equals(kw.getText().trim())) {
                        return kw;
                    }
                }
            }
            var currentDef = resolveNominalTypeDefinitionFromSchema(enclosingSchema);
            var visited = new HashSet<PsiElement>();
            while (currentDef != null && visited.size() < 32 && visited.add(currentDef)) {
                var defSchema = currentDef.getSchemaType();
                if (defSchema == null) {
                    break;
                }
                var target = evaluateSchemaVariantTarget(defSchema, variantName, currentDef, true);
                if (target != null) {
                    return target;
                }
                currentDef = resolveNominalTypeDefinitionFromSchema(defSchema);
            }
            return null;
        }

        var enclosingValue = PsiTreeUtil.getParentOfType(valKw, Value.class, false);
        if (enclosingValue == null) {
            return null;
        }
        var info = resolveBaseTypeInfo(enclosingValue);
        if (info == null) {
            return null;
        }
        var slotSchema = info.getSchema();
        if (slotSchema.getTypeKeyword() == null
                && enclosingValue.getExplicitOptionValue() == null
                && enclosingValue.getExplicitEitherValue() == null
                && enclosingValue.getExplicitUnionValue() == null) {
            var unwrapped = unwrapImplicitSumBranchSchema(slotSchema, enclosingValue);
            if (unwrapped != null) {
                slotSchema = unwrapped;
            }
        }

        var inlineOwnerTypeDef = PsiTreeUtil.getParentOfType(slotSchema, TypeDefinition.class);
        PsiElement inlineFallbackOwner = (inlineOwnerTypeDef != null && inlineOwnerTypeDef.getSchemaType() == slotSchema)
            ? inlineOwnerTypeDef
            : slotSchema;
        var inlineTarget = evaluateSchemaVariantTarget(slotSchema, variantName, inlineFallbackOwner, false);
        if (inlineTarget != null) {
            return inlineTarget;
        }

        var currentDef = resolveNominalTypeDefinitionFromSchema(slotSchema);
        var visitedDefs = new HashSet<PsiElement>();
        while (currentDef != null && visitedDefs.size() < 32 && visitedDefs.add(currentDef)) {
            var defSchema = currentDef.getSchemaType();
            if (defSchema == null) {
                break;
            }
            if (defSchema.getTypeKeyword() == null
                    && enclosingValue.getExplicitOptionValue() == null
                    && enclosingValue.getExplicitEitherValue() == null
                    && enclosingValue.getExplicitUnionValue() == null) {
                var unwrappedBranch = unwrapImplicitSumBranchSchema(defSchema, enclosingValue);
                if (unwrappedBranch != null && unwrappedBranch != defSchema) {
                    defSchema = unwrappedBranch;
                }
            }
            var target = evaluateSchemaVariantTarget(defSchema, variantName, currentDef, false);
            if (target != null) {
                return target;
            }
            currentDef = resolveNominalTypeDefinitionFromSchema(defSchema);
        }
        return null;
    }

    private static @Nullable PsiElement evaluateSchemaVariantTarget(
            SchemaType schema,
            String variantName,
            @Nullable PsiElement governingOwner,
            boolean inVariantList
    ) {
        var metaMap = schema.getMetadataMap();
        if (metaMap != null) {
            org.stvnadore.psi.MetadataFilter filterInclFilter = null;
            boolean hasFilterExcl = false;
            for (var entry : metaMap.getMetadataEntryList()) {
                var filter = entry.getMetadataFilter();
                if (filter == null || filter.getFirstChild() == null) {
                    continue;
                }
                var facetText = filter.getFirstChild().getText().trim();
                if (StvnVocabulary.FACET_KW_FILTER_INCL.equals(facetText)) {
                    filterInclFilter = filter;
                } else if (StvnVocabulary.FACET_KW_FILTER_EXCL.equals(facetText)) {
                    hasFilterExcl = true;
                }
            }
            if (!inVariantList && hasFilterExcl) {
                return governingOwner;
            }
            if (filterInclFilter != null && (!hasFilterExcl || !inVariantList)) {
                var variantList = filterInclFilter.getVariantList();
                if (variantList != null) {
                    for (var kw : variantList.getValueKeywordList()) {
                        if (variantName.equals(kw.getText().trim())) {
                            return kw;
                        }
                    }
                }
                if (!inVariantList) {
                    return governingOwner;
                }
            }
        }
        var ctor = schema.getSchemaConstructor();
        if (ctor != null && ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
            for (var kw : ctor.getSumType().getEnumDef().getValueKeywordList()) {
                if (variantName.equals(kw.getText().trim())) {
                    return kw;
                }
            }
        }
        return null;
    }

    private static @Nullable BodyEntry findBodyEntryParent(PsiElement element) {
        var curr = element;
        while (curr != null) {
            if (curr instanceof BodyEntry bodyEntry) {
                return bodyEntry;
            }
            curr = curr.getParent();
        }
        return null;
    }

    /**
     * Resolves the immediate nominal schema without recursively collapsing aliases into underlying primitives.
     *
     * @param schemaType the schema type to inspect
     * @return the resolved nominal schema type, or {@code null} if {@code schemaType} is {@code null}
     */
    public static @Nullable SchemaType resolveNominalSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return null;
        }
        var keyword = schemaType.getTypeKeyword();
        if (keyword == null) {
            return schemaType;
        }
        var typeName = keyword.getText();
        var typeDef = findTypeDefinition(keyword.getContainingFile(), typeName, keyword);
        if (typeDef != null) {
            return typeDef.getSchemaType();
        }
        var resolved = new StvnTypeReference(keyword).resolve();
        if (resolved != null) {
            var targetDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
            if (targetDef != null && targetDef.getSchemaType() != null) {
                return targetDef.getSchemaType();
            }
        }
        return schemaType;
    }

    /**
     * Resolves the {@link StvnEnumSubsetInfo} model for a given schema type if it represents an enum subset,
     * computing allowed variants transitively across arbitrary chain depths.
     *
     * @param schemaType the schema type to inspect
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if not a subset
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable SchemaType schemaType) {
        return resolveEnumSubset(schemaType, new HashSet<>());
    }

    private static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable SchemaType schemaType, Set<String> visited) {
        if (schemaType == null) return null;
        var kw = schemaType.getTypeKeyword();
        if (kw == null) return null;

        var resolvedTarget = new StvnTypeReference(kw).resolve();
        var targetDef = resolveUnderlyingTypeDefinition(resolvedTarget);
        if (targetDef != null) {
            return resolveEnumSubsetFromTypeDef(targetDef, kw.getText(), visited);
        }
        return resolveEnumSubsetInternal(kw.getContainingFile(), kw.getText(), visited);
    }

    /**
     * Resolves the enum subset declaration from a type definition.
     *
     * @param typeDef the type definition element
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable TypeDefinition typeDef) {
        if (typeDef == null) return null;
        var kw = typeDef.getTypeKeyword();
        if (kw == null) return null;

        return resolveEnumSubsetFromTypeDef(typeDef, kw.getText(), new HashSet<>());
    }

    /**
     * Resolves the enum subset declaration from a type keyword.
     *
     * @param typeKeyword the type keyword element
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable TypeKeyword typeKeyword) {
        if (typeKeyword == null) return null;
        if (StvnPsiUtils.isTypeDefinitionTarget(typeKeyword)) {
            return resolveEnumSubset(StvnPsiUtils.getParentTypeDefinition(typeKeyword));
        }
        if (typeKeyword.getParent() instanceof UseMapAlias uma) {
            return resolveEnumSubset(uma);
        }
        var resolvedTarget = new StvnTypeReference(typeKeyword).resolve();
        var targetDef = resolveUnderlyingTypeDefinition(resolvedTarget);
        if (targetDef != null) {
            return resolveEnumSubsetFromTypeDef(targetDef, typeKeyword.getText(), new HashSet<>());
        }
        return resolveEnumSubsetInternal(typeKeyword.getContainingFile(), typeKeyword.getText(), new HashSet<>());
    }

    /**
     * Resolves the enum subset declaration referenced by a {@code :use} map alias.
     *
     * @param alias the {@code :use} map alias element
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable UseMapAlias alias) {
        if (alias == null) return null;
        var list = alias.getTypeKeywordList();
        var localKw = list.size() >= 2 ? list.get(1) : (!list.isEmpty() ? list.get(0) : null);
        if (localKw == null) return null;

        var targetDef = resolveUnderlyingTypeDefinition(alias);
        if (targetDef != null) {
            return resolveEnumSubsetFromTypeDef(targetDef, localKw.getText(), new HashSet<>());
        }
        return resolveEnumSubsetInternal(localKw.getContainingFile(), localKw.getText(), new HashSet<>());
    }

    /**
     * Resolves an enum subset model from an arbitrary supported PSI element.
     *
     * @param element the PSI element ({@link SchemaType}, {@link TypeDefinition}, {@link TypeKeyword}, or {@link UseMapAlias})
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubsetFromElement(@Nullable PsiElement element) {
        if (element == null) return null;
        if (element instanceof SchemaType st) return resolveEnumSubset(st);
        if (element instanceof TypeDefinition td) return resolveEnumSubset(td);
        if (element instanceof TypeKeyword tk) return resolveEnumSubset(tk);
        if (element instanceof UseMapAlias uma) return resolveEnumSubset(uma);
        return null;
    }

    private static @Nullable StvnEnumSubsetInfo resolveEnumSubsetInternal(
        PsiFile file,
        String typeName,
        Set<String> visited
    ) {
        var targetDef = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
        if (targetDef == null) return null;

        TypeDefinition typeDef = resolveUnderlyingTypeDefinition(targetDef);
        if (typeDef == null) {
            return null;
        }
        return resolveEnumSubsetFromTypeDef(typeDef, typeName, visited);
    }

    private static @Nullable StvnEnumSubsetInfo resolveEnumSubsetFromTypeDef(
        TypeDefinition typeDef,
        String typeName,
        Set<String> visited
    ) {
        String visitKey = (typeDef.getContainingFile() != null ? typeDef.getContainingFile().getName() + "@" : "")
            + typeDef.getTextOffset() + "|" + typeName;
        if (!visited.add(visitKey)) return null;

        var metaMap = typeDef.getMetadataMap();
        MetadataFilter filter = null;
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                if (entry.getMetadataFilter() != null) {
                    filter = entry.getMetadataFilter();
                    break;
                }
            }
        }

        var parentSchema = typeDef.getSchemaType();
        if (filter == null) {
            if (parentSchema != null) {
                var parentSubset = resolveEnumSubset(parentSchema, visited);
                if (parentSubset != null) {
                    return new StvnEnumSubsetInfo(
                        typeName,
                        parentSubset.name(),
                        parentSubset.rootEnum(),
                        parentSubset.allowedVariants(),
                        parentSubset.rootVariants(),
                        parentSubset.isInclusive(),
                        parentSubset.declaredFilterList()
                    );
                }
            }
            return null;
        }

        if (parentSchema == null) return null;
        var parentSubset = resolveEnumSubset(parentSchema, visited);

        List<String> parentAllowed;
        String parentName;
        String rootEnumName;
        List<String> rootVariants;

        if (parentSubset != null) {
            parentAllowed = parentSubset.allowedVariants();
            parentName = parentSubset.name();
            rootEnumName = parentSubset.rootEnum();
            rootVariants = parentSubset.rootVariants();
        } else {
            var resolvedParent = resolveNominalSchema(parentSchema);
            if (resolvedParent == null || resolvedParent.getSchemaConstructor() == null
                || resolvedParent.getSchemaConstructor().getSumType() == null
                || resolvedParent.getSchemaConstructor().getSumType().getEnumDef() == null) {
                return null;
            }
            var enumDef = resolvedParent.getSchemaConstructor().getSumType().getEnumDef();
            rootVariants = enumDef.getValueKeywordList().stream().map(ValueKeyword::getText).toList();
            parentAllowed = rootVariants;
            parentName = parentSchema.getTypeKeyword() != null ? parentSchema.getTypeKeyword().getText() : StvnVocabulary.TYPE_ENUM;
            rootEnumName = parentName;
        }

        var isIncl = filter.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null;
        var variantList = filter.getVariantList();
        var facetVariants = variantList != null
            ? variantList.getValueKeywordList().stream().map(ValueKeyword::getText).toList()
            : List.<String>of();

        List<String> computedAllowed = isIncl
            ? facetVariants.stream().filter(parentAllowed::contains).toList()
            : parentAllowed.stream().filter(v -> !facetVariants.contains(v)).toList();

        return new StvnEnumSubsetInfo(
            typeName,
            parentName,
            rootEnumName,
            computedAllowed,
            rootVariants,
            isIncl,
            facetVariants
        );
    }

    /**
     * Determines whether the target leaf type context of the given value slot resolves to a boolean primitive,
     * traversing any surrounding explicit or implicit sum-type envelopes.
     *
     * @param value the PSI value element to test
     * @return {@code true} if the target type context resolves to a boolean, {@code false} otherwise
     */
    public static boolean resolvesToBoolean(Value value) {
        var info = resolveBaseTypeInfo(value);
        if (info != null) {
            var schema = info.getSchema();
            var resolved = resolveNominalSchema(schema);
            var toInspect = resolved != null ? resolved : schema;
            var ctor = toInspect.getSchemaConstructor();
            if (ctor != null && ctor.getSumType() != null) {
                var sumType = ctor.getSumType();
                if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var unionVal = value.getExplicitUnionValue();
                    if (unionVal != null) {
                        var firstChild = unionVal.getFirstChild();
                        String tagText = firstChild != null ? firstChild.getText() : "";
                        var resolvedIdx = StvnPsiLiteralEvaluator.resolveZeroBasedUnionBranchIndex(tagText, innerSchemas.size());
                        return resolvedIdx.isPresent() && isBooleanSchema(innerSchemas.get(resolvedIdx.getAsInt()));
                    }
                    for (var branch : innerSchemas) {
                        if (matchesSchemaPattern(value, branch)) {
                            return isBooleanSchema(branch);
                        }
                    }
                    return false;
                } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var eitherVal = value.getExplicitEitherValue();
                    if (eitherVal != null) {
                        var isLeft = eitherVal.getText().startsWith(StvnVocabulary.VAL_LEFT) || eitherVal.getText().startsWith(StvnVocabulary.VAL_LEFT_SHORT);
                        var branch = isLeft ? (!innerSchemas.isEmpty() ? innerSchemas.get(0) : null) : (innerSchemas.size() > 1 ? innerSchemas.get(1) : null);
                        return isBooleanSchema(branch);
                    } else if (innerSchemas.size() > 1) {
                        return isBooleanSchema(innerSchemas.get(1));
                    }
                    return false;
                } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_OPTION)) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (!innerSchemas.isEmpty()) {
                        return isBooleanSchema(innerSchemas.get(0));
                    }
                    return false;
                }
            }
            if (isBooleanSchema(info.getSchema())) {
                return true;
            }
        }

        var resolvedNode = resolveCoreValue(value);
        if (resolvedNode == null || resolvedNode instanceof StvnValue.StvnErrorValue) {
            return false;
        }

        var file = value.getContainingFile();
        var catalog = file != null
            ? StvnCompiledDocumentCache.getSnapshot(file).catalog()
            : StvnDefsCatalog.EMPTY;
        var trajectory = StvnSumTrajectory.evaluate(value, resolvedNode, catalog);
        var deepest = StvnSumTrajectory.unwrapTerminalIrValue(resolvedNode);

        return (deepest instanceof StvnValue.StvnBoolean)
            || (trajectory.terminalIrValue() instanceof StvnValue.StvnBoolean);
    }

    private static String formatTrace(List<String> trace) {
        if (trace.isEmpty()) {
            return "";
        }
        var first = trace.get(0);
        var shortFirst = first.startsWith(":org/stvnadore/prelude/")
            ? StvnVocabulary.SIGIL_TYPIC + first.substring(":org/stvnadore/prelude/".length())
            : first;
        if (StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(shortFirst)) {
            return shortFirst;
        }
        if (trace.size() == 1) {
            return trace.get(0);
        }
        var sb = new StringBuilder();
        sb.append(trace.get(0));
        sb.append(" (-> ");
        for (var i = 1; i < trace.size(); i++) {
            if (i > 1) {
                sb.append(" -> ");
            }
            sb.append(trace.get(i));
        }
        sb.append(")");
        return sb.toString();
    }

    private static void mapValues(@Nullable Value psiVal, @Nullable StvnValue irVal, Map<Integer, StvnValue> map) {
        if (psiVal == null || irVal == null) {
            return;
        }

        var offset = psiVal.getTextRange().getStartOffset();
        if (!map.containsKey(offset)) {
            map.put(offset, irVal);
        }

        if (irVal instanceof StvnValue.StvnOption opt) {
            var innerIr = opt.value().orElse(null);
            if (innerIr != null) {
                var optPsi = psiVal.getExplicitOptionValue();
                if (optPsi != null) {
                    mapValues(optPsi.getValue(), innerIr, map);
                } else {
                    mapValues(psiVal, innerIr, map);
                }
            }
        } else if (irVal instanceof StvnValue.StvnEither either) {
            var innerIr = either.value();
            var eitherPsi = psiVal.getExplicitEitherValue();
            if (eitherPsi != null) {
                mapValues(eitherPsi.getValue(), innerIr, map);
            } else {
                mapValues(psiVal, innerIr, map);
            }
        } else if (irVal instanceof StvnValue.StvnUnion union) {
            var innerIr = union.value();
            var explicitUnion = psiVal.getExplicitUnionValue();
            if (explicitUnion != null) {
                mapValues(explicitUnion.getValue(), innerIr, map);
            } else {
                mapValues(psiVal, innerIr, map);
            }
        } else if (irVal instanceof StvnValue.StvnTuple tuple) {
            var collPsi = psiVal.getCollectionValue();
            var tuplePsi = collPsi != null ? collPsi.getTupleLiteral() : null;
            if (tuplePsi != null) {
                var psiChildren = PsiTreeUtil.getChildrenOfTypeAsList(tuplePsi, Value.class);
                mapCollectionElementsWithUnspooling(psiChildren, tuple.elements(), map);
            }
        } else if (irVal instanceof StvnValue.StvnSeq seq) {
            var collPsi = psiVal.getCollectionValue();
            var listPsi = collPsi != null ? collPsi.getListLiteral() : null;
            if (listPsi != null) {
                var psiChildren = PsiTreeUtil.getChildrenOfTypeAsList(listPsi, Value.class);
                mapCollectionElementsWithUnspooling(psiChildren, seq.elements(), map);
            }
        } else if (irVal instanceof StvnValue.StvnSet set) {
            var collPsi = psiVal.getCollectionValue();
            var listPsi = collPsi != null ? collPsi.getListLiteral() : null;
            if (listPsi != null) {
                var psiChildren = PsiTreeUtil.getChildrenOfTypeAsList(listPsi, Value.class);
                mapCollectionElementsWithUnspooling(psiChildren, new ArrayList<>(set.elements()), map);
            }
        } else if (irVal instanceof StvnValue.StvnMap mapVal) {
            var collPsi = psiVal.getCollectionValue();
            var mapPsi = collPsi != null ? collPsi.getMapLiteral() : null;
            if (mapPsi != null) {
                var psiChildren = mapPsi.getValueList();
                var entries = mapVal.entries();
                for (int i = 0; i < psiChildren.size() / 2 && i < entries.size(); i++) {
                    var entry = entries.get(i);
                    var keyPsi = psiChildren.get(i * 2);
                    var valPsi = psiChildren.get(i * 2 + 1);
                    mapValues(keyPsi, entry.key(), map);
                    mapValues(valPsi, entry.value(), map);
                }
            }
        }
    }

    private static void mapCollectionElementsWithUnspooling(
        List<Value> psiValues,
        List<StvnValue> irElements,
        Map<Integer, StvnValue> map
    ) {
        int[] irIdx = new int[]{0};
        for (var psiVal : psiValues) {
            if (irIdx[0] >= irElements.size()) {
                break;
            }
            unspoolAndMap(psiVal, irElements, irIdx, map);
        }
    }

    private static void unspoolAndMap(
        Value psiVal,
        List<StvnValue> irElements,
        int[] irIdx,
        Map<Integer, StvnValue> map
    ) {
        if (irIdx[0] >= irElements.size()) {
            return;
        }

        var optPsi = psiVal.getExplicitOptionValue();
        var eitherPsi = psiVal.getExplicitEitherValue();
        var unionPsi = psiVal.getExplicitUnionValue();

        if (optPsi != null) {
            var targetIr = irElements.get(irIdx[0]);
            StvnValue currentIr = targetIr;
            while (StvnSumTrajectory.isSumValue(currentIr) && !(currentIr instanceof StvnValue.StvnOption)) {
                if (currentIr instanceof StvnValue.StvnEither either) {
                    currentIr = either.value();
                } else if (currentIr instanceof StvnValue.StvnUnion union) {
                    currentIr = union.value();
                } else {
                    break;
                }
            }
            if (currentIr instanceof StvnValue.StvnOption opt) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                var innerIr = opt.value().orElse(null);
                if (innerIr != null && optPsi.getValue() != null) {
                    mapValues(optPsi.getValue(), innerIr, map);
                }
                irIdx[0]++;
            } else if (targetIr instanceof StvnValue.StvnErrorValue) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                map.put(optPsi.getTextRange().getStartOffset(), targetIr);
                var innerVal = optPsi.getValue();
                if (innerVal != null) {
                    map.put(innerVal.getTextRange().getStartOffset(), targetIr);
                }
                irIdx[0]++;
            } else {
                var headTokenOffset = psiVal.getTextRange().getStartOffset();
                map.put(headTokenOffset, targetIr);
                irIdx[0]++;

                var innerVal = optPsi.getValue();
                if (innerVal != null && irIdx[0] < irElements.size()) {
                    unspoolAndMap(innerVal, irElements, irIdx, map);
                }
            }
            return;
        }

        if (eitherPsi != null) {
            var targetIr = irElements.get(irIdx[0]);
            StvnValue currentIr = targetIr;
            while (StvnSumTrajectory.isSumValue(currentIr) && !(currentIr instanceof StvnValue.StvnEither)) {
                if (currentIr instanceof StvnValue.StvnOption opt) {
                    currentIr = opt.value().orElse(null);
                } else if (currentIr instanceof StvnValue.StvnUnion union) {
                    currentIr = union.value();
                } else {
                    break;
                }
            }
            if (currentIr instanceof StvnValue.StvnEither either) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                var innerIr = either.value();
                if (eitherPsi.getValue() != null) {
                    mapValues(eitherPsi.getValue(), innerIr, map);
                }
                irIdx[0]++;
            } else if (targetIr instanceof StvnValue.StvnErrorValue) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                map.put(eitherPsi.getTextRange().getStartOffset(), targetIr);
                var innerVal = eitherPsi.getValue();
                if (innerVal != null) {
                    map.put(innerVal.getTextRange().getStartOffset(), targetIr);
                }
                irIdx[0]++;
            } else {
                var headTokenOffset = psiVal.getTextRange().getStartOffset();
                map.put(headTokenOffset, targetIr);
                irIdx[0]++;

                var innerVal = eitherPsi.getValue();
                if (innerVal != null && irIdx[0] < irElements.size()) {
                    unspoolAndMap(innerVal, irElements, irIdx, map);
                }
            }
            return;
        }

        if (unionPsi != null) {
            var targetIr = irElements.get(irIdx[0]);
            StvnValue currentIr = targetIr;
            while (StvnSumTrajectory.isSumValue(currentIr) && !(currentIr instanceof StvnValue.StvnUnion)) {
                if (currentIr instanceof StvnValue.StvnOption opt) {
                    currentIr = opt.value().orElse(null);
                } else if (currentIr instanceof StvnValue.StvnEither either) {
                    currentIr = either.value();
                } else {
                    break;
                }
            }
            if (currentIr instanceof StvnValue.StvnUnion union) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                var innerIr = union.value();
                if (unionPsi.getValue() != null) {
                    mapValues(unionPsi.getValue(), innerIr, map);
                }
                irIdx[0]++;
            } else if (targetIr instanceof StvnValue.StvnErrorValue) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                map.put(unionPsi.getTextRange().getStartOffset(), targetIr);
                var innerVal = unionPsi.getValue();
                if (innerVal != null) {
                    map.put(innerVal.getTextRange().getStartOffset(), targetIr);
                }
                irIdx[0]++;
            } else {
                var headTokenOffset = psiVal.getTextRange().getStartOffset();
                map.put(headTokenOffset, targetIr);
                irIdx[0]++;

                var innerVal = unionPsi.getValue();
                if (innerVal != null && irIdx[0] < irElements.size()) {
                    unspoolAndMap(innerVal, irElements, irIdx, map);
                }
            }
            return;
        }

        var targetIr = irElements.get(irIdx[0]);
        mapValues(psiVal, targetIr, map);
        irIdx[0]++;
    }

    private static boolean hasSumType(@Nullable StvnValue node, Class<?> sumClass) {
        var curr = node;
        while (StvnSumTrajectory.isSumValue(curr)) {
            if (sumClass.isInstance(curr)) {
                return true;
            }
            if (curr instanceof StvnValue.StvnOption opt) {
                curr = opt.value().orElse(null);
            } else if (curr instanceof StvnValue.StvnEither either) {
                curr = either.value();
            } else if (curr instanceof StvnValue.StvnUnion union) {
                curr = union.value();
            } else {
                break;
            }
        }
        return false;
    }

    /**
     * Determines whether an explicit algebraic container is unspooled relative to its core IR node.
     *
     * @param container the container PSI element
     * @param coreNode  the resolved core value representation
     * @return {@code true} if unspooled, {@code false} otherwise
     */
    public static boolean isUnspooledContainer(PsiElement container, @Nullable StvnValue coreNode) {
        if (coreNode == null || coreNode instanceof StvnValue.StvnErrorValue) {
            return false;
        }
        if (container instanceof ExplicitOptionValue) {
            return !hasSumType(coreNode, StvnValue.StvnOption.class);
        }
        if (container instanceof ExplicitEitherValue) {
            return !hasSumType(coreNode, StvnValue.StvnEither.class);
        }
        if (container instanceof ExplicitUnionValue) {
            return !hasSumType(coreNode, StvnValue.StvnUnion.class);
        }
        return false;
    }

    private static boolean isBooleanSchema(@Nullable SchemaType schema) {
        if (schema == null) {
            return false;
        }
        var resolved = resolveNominalSchema(schema);
        var toInspect = (resolved != null) ? resolved : schema;
        var text = StvnSchemaFormatter.formatSchema(toInspect);
        var stripped = (text.startsWith("{") && text.contains("}"))
            ? text.substring(text.lastIndexOf('}') + 1).trim()
            : text;
        return stripped.equals(StvnVocabulary.TYPE_BOOLEAN);
    }

    /**
     * Resolves the expected schema type at the specified caret position.
     *
     * @param position the PSI element at the caret position
     * @return the expected schema type, or {@code null} if no schema context can be determined
     */
    public static @Nullable SchemaType resolveExpectedSchemaAtCaret(@Nullable PsiElement position) {
        if (position == null) {
            return null;
        }

        // Unpack explicit union constructor when evaluating an immediate union tag or direct union value
        var explicitUnion = PsiTreeUtil.getParentOfType(position, ExplicitUnionValue.class);
        if (explicitUnion != null && (explicitUnion.getValue() == null || position.getParent() == explicitUnion)) {
            var tagElem = explicitUnion.getUnionTagPrefix();
            var tagText = tagElem.getText().trim();
            if (tagText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                var parentExpected = resolveExpectedSchemaAtCaret(explicitUnion);
                if (parentExpected != null) {
                    var resolvedNominal = resolveNominalSchema(parentExpected);
                    var schemaToInspect = resolvedNominal != null ? resolvedNominal : parentExpected;
                    var constructor = schemaToInspect.getSchemaConstructor();
                    if (constructor != null && constructor.getSumType() != null) {
                        var sumType = constructor.getSumType();
                        if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                            var resolvedIdx = StvnPsiLiteralEvaluator.resolveZeroBasedUnionBranchIndex(tagText, innerSchemas.size());
                            if (resolvedIdx.isPresent()) {
                                return innerSchemas.get(resolvedIdx.getAsInt());
                            }
                        }
                    }
                }
            }
        }

        // 1. Check if position is inside a ConstantDefinition in :defs
        var constDef = PsiTreeUtil.getParentOfType(position, ConstantDefinition.class);
        SchemaType rootSchema = null;
        if (constDef != null) {
            rootSchema = constDef.getSchemaType();
        } else {
            var bodyEntry = PsiTreeUtil.getParentOfType(position, BodyEntry.class);
            if (bodyEntry == null) {
                var file = position.getContainingFile();
                if (file != null) {
                    bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                }
            }
            if (bodyEntry != null) {
                var containingFile = bodyEntry.getContainingFile();
                if (containingFile != null) {
                    var typeEntry = PsiTreeUtil.findChildOfType(containingFile, TypeEntry.class);
                    if (typeEntry != null) {
                        rootSchema = typeEntry.getSchemaType();
                    }
                }
            }
        }

        if (rootSchema == null) {
            return null;
        }

        // 2. Ascend PSI hierarchy from position
        var path = new Stack<PathStep>();
        var curr = position;
        while (curr != null) {
            var parent = curr.getParent();
            if (parent == null || parent instanceof BodyEntry || parent instanceof ConstantDefinition) {
                break;
            }

            if (parent instanceof TupleLiteral tuple) {
                var values = tuple.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (PsiTreeUtil.isAncestor(values.get(i), curr, false) || values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx == -1) {
                    int count = 0;
                    for (var child = tuple.getFirstChild(); child != null && child != curr; child = child.getNextSibling()) {
                        if (child instanceof Value) {
                            count++;
                        }
                    }
                    idx = count;
                }
                path.push(new PathStep(StepType.TUPLE, idx));
            } else if (parent instanceof ListLiteral) {
                path.push(new PathStep(StepType.LIST, -1));
            } else if (parent instanceof MapLiteral map) {
                var values = map.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (PsiTreeUtil.isAncestor(values.get(i), curr, false) || values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx == -1) {
                    int count = 0;
                    for (var child = map.getFirstChild(); child != null && child != curr; child = child.getNextSibling()) {
                        if (child instanceof Value) {
                            count++;
                        }
                    }
                    idx = count;
                }
                var isKey = (idx % 2 == 0);
                path.push(new PathStep(isKey ? StepType.MAP_KEY : StepType.MAP_VALUE, isKey ? 0 : 1));
                path.push(new PathStep(StepType.MAP, -1));
            } else if (parent instanceof ExplicitOptionValue) {
                path.push(new PathStep(StepType.OPTION, -1));
            } else if (parent instanceof ExplicitEitherValue either) {
                var text = either.getText().trim();
                var isLeft = text.startsWith(StvnVocabulary.VAL_LEFT) || text.startsWith(StvnVocabulary.VAL_LEFT_SHORT);
                path.push(new PathStep(StepType.EITHER, isLeft ? 0 : 1));
            } else if (parent instanceof ExplicitUnionValue union) {
                var firstChild = union.getUnionTagPrefix();
                int tagIndex = parseUnionTagZeroBasedIndex(firstChild.getText());
                path.push(new PathStep(StepType.UNION, tagIndex));
            }

            curr = parent;
        }

        // 3. Descend schema AST using recorded path steps
        return descendSchemaPath(rootSchema, path);
    }

    /**
     * Determines whether a source schema type is assignable to a target schema type.
     *
     * @param targetSchema the expected destination schema type
     * @param sourceSchema the candidate source schema type
     * @return {@code true} if assignable, {@code false} otherwise
     */
    public static boolean isSchemaAssignable(@Nullable SchemaType targetSchema, @Nullable SchemaType sourceSchema) {
        if (targetSchema == null || sourceSchema == null) {
            return false;
        }
        var targetText = StvnSchemaFormatter.formatSchema(targetSchema);
        var sourceText = StvnSchemaFormatter.formatSchema(sourceSchema);
        return targetText.equals(sourceText);
    }

    /**
     * Finds all constants defined in the file or its includes that are assignable to the target schema type.
     *
     * @param file         the PSI file to search within
     * @param targetSchema the expected target schema type
     * @return list of assignable constant definitions
     */
    public static List<ConstantDefinition> findAssignableConstants(PsiFile file, SchemaType targetSchema) {
        var list = new ArrayList<ConstantDefinition>();
        var visitedFiles = new HashSet<PsiFile>();
        collectConstantsRecursive(file, targetSchema, list, visitedFiles);
        return list;
    }

    private static void collectConstantsRecursive(
        PsiFile file,
        SchemaType targetSchema,
        List<ConstantDefinition> list,
        Set<PsiFile> visited
    ) {
        if (!visited.add(file)) {
            return;
        }

        var localConstants = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var constDef : localConstants) {
            var constSchema = constDef.getSchemaType();
            if (constSchema != null && isSchemaAssignable(targetSchema, constSchema)) {
                list.add(constDef);
            }
        }

        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var strLit = incl.getStringLiteral();
            if (strLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(strLit);
                if (targetFile != null) {
                    collectConstantsRecursive(targetFile, targetSchema, list, visited);
                }
            }
        }
    }

    /**
     * Resolves a {@link TypeDefinition} by name within a file or context scope,
     * traversing nested package scopes, root definitions, and include hierarchies.
     *
     * @param file     the containing PSI file
     * @param typeName the type name or alias to locate
     * @param context  the optional context element determining the active lexical scope
     * @return the resolved {@link TypeDefinition}, or {@code null} if unresolvable
     */
    public static @Nullable TypeDefinition findTypeDefinition(
        @Nullable PsiFile file,
        @Nullable String typeName,
        @Nullable PsiElement context
    ) {
        if (file == null || typeName == null || typeName.isEmpty()) {
            return null;
        }
        if (context != null) {
            var enclosingPkg = PsiTreeUtil.getParentOfType(context, PackageEnclosure.class);
            if (enclosingPkg != null && (!typeName.startsWith(StvnVocabulary.SIGIL_TYPIC) || !typeName.substring(1).contains("/"))) {
                var pkgUseStmts = new ArrayList<UseStmt>();
                for (var elem : enclosingPkg.getPackageElementList()) {
                    var typeDef = elem.getTypeDefinition();
                    if (typeDef != null) {
                        var kw = typeDef.getTypeKeyword();
                        if (kw != null && kw.getText().equals(typeName)) {
                            return typeDef;
                        }
                    }
                    var useStmt = elem.getUseStmt();
                    if (useStmt != null) {
                        pkgUseStmts.add(useStmt);
                    }
                }
                var pkgUseOutcome = StvnTypeReference.resolveFromUseStmts(file, pkgUseStmts, typeName, null);
                if (pkgUseOutcome.collisionOrEvicted()) {
                    return null;
                }
                if (pkgUseOutcome.resolved() != null) {
                    return StvnPsiUtils.resolveTargetTypeDefinition(pkgUseOutcome.resolved());
                }
            }
        }
        var resolved = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
        return StvnPsiUtils.resolveTargetTypeDefinition(resolved);
    }

    /**
     * Resolves a {@link TypeDefinition} by name within a file without context element.
     *
     * @param file     the containing PSI file
     * @param typeName the type name or alias to locate
     * @return the resolved {@link TypeDefinition}, or {@code null} if unresolvable
     */
    public static @Nullable TypeDefinition findTypeDefinition(@Nullable PsiFile file, @Nullable String typeName) {
        return findTypeDefinition(file, typeName, null);
    }

    /**
     * Determines whether the given identifier matches a reserved fundamental type name.
     *
     * @param name the type name to check
     * @return {@code true} if reserved fundamental type, {@code false} otherwise
     */
    public static boolean isReservedFundamentalType(String name) {
        return StvnNamespaceRules.isReservedFundamentalType(name);
    }

    /**
     * Resolves a physical filesystem path for a virtual file, staging to a temporary directory if in-memory.
     *
     * @param virtualFile the virtual file being compiled
     * @param project     the active IntelliJ project
     * @param text        the document content string
     * @return the resolved physical path string
     */
    public static String resolvePhysicalPath(
        @Nullable VirtualFile virtualFile,
        @Nullable Project project,
        String text
    ) {
        return StvnCompiledDocumentCache.resolvePhysicalPath(virtualFile, project, text);
    }

    /**
     * Resolves a named constant definition within the specified PSI file.
     *
     * @param file the PSI file containing definitions
     * @param name the constant name including leading sigil {@code #}
     * @return the {@link ConstantDefinition} PSI node, or {@code null} if not found
     */
    public static @Nullable ConstantDefinition findConstantDefinition(@Nullable PsiFile file, String name) {
        if (file == null) {
            return null;
        }
        var resolved = StvnConstantReference.resolveConstantInFile(file, name, new HashSet<>());
        if (resolved != null) {
            var target = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
            if (target != null) {
                return target;
            }
        }
        var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var def : constDefs) {
            var kw = def.getValueKeyword();
            if (kw != null && name.equals(kw.getText())) {
                return def;
            }
        }
        return null;
    }

    /**
     * Determines whether a core IR {@link StvnValue.StvnErrorValue} can be recovered via PSI fallback,
     * specifically for untagged union discrimination and keyword constant references.
     *
     * @param err   the core IR {@link StvnValue.StvnErrorValue}
     * @param value the PSI value element
     * @return {@code true} if recoverable via PSI fallback, {@code false} otherwise
     */
    public static boolean isRecoverableCoreError(StvnValue.StvnErrorValue err, Value value) {
        var file = value.getContainingFile();
        if (file == null) {
            return false;
        }
        var text = value.getText().trim();
        if (text.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            var constDef = findConstantDefinition(file, text);
            if (constDef != null) {
                return true;
            }
        }
        var info = resolveBaseTypeInfo(value);
        if (info != null) {
            if (err.schema() instanceof StvnSchema.PoisonedSchema poisoned && poisoned.isUngrounded()) {
                return true;
            }
            var resolved = resolveNominalSchema(info.getSchema());
            var toInspect = resolved != null ? resolved : info.getSchema();
            if (StvnTypeInferenceHelper.isStructurallyCompatible(value, info.getSchema())) {
                if (err.schema() instanceof StvnSchema.PoisonedSchema) {
                    return true;
                }
                if (matchesSchemaPattern(value, toInspect)) {
                    return true;
                }
                var ctor = toInspect.getSchemaConstructor();
                if (ctor != null && (ctor.getProductType() != null || ctor.getCollectionType() != null)) {
                    return true;
                }
            }
            var ctor = toInspect.getSchemaConstructor();
            if (ctor != null && ctor.getSumType() != null) {
                var sumType = ctor.getSumType();
                if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                    var inner = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    int matchCount = 0;
                    for (var branch : inner) {
                        if (matchesSchemaPattern(value, branch)) {
                            matchCount++;
                        }
                    }
                    if (matchCount == 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasExactFacetTag(String entryText, String facetTag) {
        var trimmed = entryText.trim();
        if (!trimmed.startsWith(facetTag)) {
            return false;
        }
        return trimmed.length() == facetTag.length()
            || Character.isWhitespace(trimmed.charAt(facetTag.length()));
    }

    private static boolean isConstructorMatch(String type, String constructor) {
        if (type.equals(constructor)) {
            return true;
        }
        if (type.startsWith(constructor)) {
            char next = type.charAt(constructor.length());
            return next == '(' || Character.isWhitespace(next);
        }
        return false;
    }
}
