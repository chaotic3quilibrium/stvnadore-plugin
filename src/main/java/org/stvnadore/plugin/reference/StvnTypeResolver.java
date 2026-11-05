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
import org.stvnadore.psi.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
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
        "E010",
        DiagnosticBag.ERR_CAPACITY_OVERFLOW,
        "E011",
        DiagnosticBag.ERR_INCOMPATIBLE_TYPE,
        DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE,
        "E013",
        DiagnosticBag.ERR_NON_OVERRIDABLE_FACET,
        "E035",
        DiagnosticBag.ERR_INVALID_METADATA_FACET,
        "E036",
        DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED,
        DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED,
        DiagnosticBag.ERR_ENUM_SUBSET_EMPTY,
        DiagnosticBag.ERR_UNDEFINED_TYPE,
        "E014",
        DiagnosticBag.ERR_CIRCULAR_TYPE,
        "E015"
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
            var pkgText = enclosingPkg.getPackagePath().getText().trim();
            var localPart = typeName.startsWith(":") ? typeName.substring(1) : typeName;
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
        String localSuffix = "/" + (typeName.startsWith(":") ? typeName.substring(1) : typeName);
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
        if (aliasName.startsWith(":") && !aliasName.substring(1).contains("/")) {
            var preludeFqni = ":org/stvnadore/prelude/" + aliasName.substring(1);
            var preludeShort = preludeCatalog.findType(preludeFqni);
            if (preludeShort.isPresent()) {
                return preludeShort.get();
            }
        }
        return null;
    }

    private static @Nullable TypeDefinition resolveUnderlyingTypeDefinition(@Nullable PsiElement targetElem) {
        if (targetElem == null) {
            return null;
        }
        TypeDefinition typeDef = StvnPsiUtils.getParentTypeDefinition(targetElem);
        if (typeDef != null) {
            return typeDef;
        }
        var inclAlias = targetElem instanceof IncludeMapAlias ima ? ima
            : targetElem.getParent() instanceof IncludeMapAlias ima ? ima : null;
        if (inclAlias != null) {
            var list = inclAlias.getTypeKeywordList();
            var remoteKw = !list.isEmpty() ? list.get(0) : null;
            if (remoteKw != null) {
                return StvnPsiUtils.getParentTypeDefinition(new StvnTypeReference(remoteKw).resolve());
            }
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

        // 1. Authoritative pure-IR PoisonedSchema check (exclude non-constraint structural warnings such as 1-branch :Union)
        var irSchema = resolveIrSchemaByAlias(file, aliasName);
        if (irSchema instanceof StvnSchema.PoisonedSchema poisoned
                && FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(poisoned.primaryDiagnosticCode())) {
            return true;
        }

        // 2. Direct PSI check on the TypeDefinition metadata map for in-flight buffers
        var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new HashSet<>());
        var typeDef = resolveUnderlyingTypeDefinition(targetDef);
        if (typeDef != null) {
            var defIr = resolveNominalIrSchema(typeDef);
            if (defIr instanceof StvnSchema.PoisonedSchema poisonedDef
                    && FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(poisonedDef.primaryDiagnosticCode())) {
                return true;
            }
            var metaMap = typeDef.getMetadataMap();
            if (metaMap != null) {
                var entries = metaMap.getMetadataEntryList();
                var hasMinIncl = false;
                var hasMinExcl = false;
                var hasMaxIncl = false;
                var hasMaxExcl = false;
                BigDecimal minVal = null;
                BigDecimal maxVal = null;

                for (var entry : entries) {
                    var entryText = entry.getText();
                    if (entryText.startsWith(StvnVocabulary.FACET_KW_REGEX)) {
                        var strLit = PsiTreeUtil.findChildOfType(entry, StringLiteral.class);
                        if (strLit != null) {
                            var decoded = StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), false);
                            var content = decoded.orElseGet(() -> {
                                var raw = strLit.getText();
                                return raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2
                                    ? raw.substring(1, raw.length() - 1) : raw;
                            });
                            try {
                                Pattern.compile(content);
                            } catch (PatternSyntaxException e) {
                                return true;
                            }
                        }
                    } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)) {
                        hasMinIncl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) minVal = num;
                    } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)) {
                        hasMinExcl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) minVal = num;
                    } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)) {
                        hasMaxIncl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) maxVal = num;
                    } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)) {
                        hasMaxExcl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) maxVal = num;
                    } else if (entryText.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                        var schemaType = typeDef.getSchemaType();
                        var schemaText = schemaType != null ? StvnSchemaFormatter.formatSchema(schemaType).trim() : "";
                        if (schemaText.endsWith(StvnVocabulary.TYPE_STRING)) {
                            return true;
                        }
                    }
                }

                var schemaType = typeDef.getSchemaType();
                var schemaText = schemaType != null ? StvnSchemaFormatter.formatSchema(schemaType).trim() : "";
                if ((hasMaxIncl || hasMinExcl) && schemaText.endsWith(StvnVocabulary.TYPE_INT)) {
                    return true;
                }

                var hasOffset = false;
                var hasZoned = false;
                var hasAudited = false;
                for (var entry : entries) {
                    var entryText = entry.getText();
                    if (entryText.startsWith(StvnVocabulary.FACET_KW_OFFSET)) hasOffset = true;
                    if (entryText.startsWith(StvnVocabulary.FACET_KW_ZONED)) hasZoned = true;
                    if (entryText.startsWith(StvnVocabulary.FACET_KW_AUDITED)) hasAudited = true;
                }
                int modeCount = (hasOffset ? 1 : 0) + (hasZoned ? 1 : 0) + (hasAudited ? 1 : 0);
                if (modeCount > 1) {
                    return true;
                }

                if ((hasMinIncl && hasMinExcl) || (hasMaxIncl && hasMaxExcl)) {
                    return true;
                }
                if (minVal != null && maxVal != null && minVal.compareTo(maxVal) > 0) {
                    return true;
                }
            }
        }

        // 3. Check compiler diagnostics accumulated for the defining file (strictly within :defs, never :body)
        PsiFile diagFile = typeDef != null && typeDef.getContainingFile() != null
            ? typeDef.getContainingFile()
            : file;
        var bodyEntry = PsiTreeUtil.findChildOfType(diagFile, BodyEntry.class);
        int bodyStartOffset = bodyEntry != null ? bodyEntry.getTextRange().getStartOffset() : Integer.MAX_VALUE;
        var snapshot = StvnCompiledDocumentCache.getSnapshot(diagFile);
        for (var diag : snapshot.diagnostics()) {
            if (diag.severity() != StvnDiagnostic.DiagnosticSeverity.ERROR) {
                continue;
            }
            if (diag.sourcePath().isPresent()
                && !diag.sourcePath().get().equals(snapshot.normalizedDocPath())) {
                continue;
            }
            if (diag.startOffset() >= bodyStartOffset) {
                continue;
            }
            String symbolic = diag.symbolicId().orElse("");
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
        if (target.endsWith("/" + (aliasName.startsWith(":") ? aliasName.substring(1) : aliasName))) {
            return true;
        }
        return aliasName.endsWith("/" + (target.startsWith(":") ? target.substring(1) : target));
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
            var keywordElement = StvnTypeReference.resolveTypeInFile(containingFile, alias, new HashSet<>());
            var kw = keywordElement instanceof TypeDefinition td ? td.getTypeKeyword()
                : keywordElement instanceof TypeKeyword k ? k : null;
            if (kw != null) {
                var trace = StvnTypeReference.extractResolutionTrace(kw);
                return formatTrace(trace);
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

            if (resolvedNode instanceof StvnValue.StvnUnion union) {
                var trajectory = StvnSumTrajectory.evaluate(value, union, catalog);
                BigInteger explicitOneBased = extractExplicitUnionOneBasedIndex(value);
                BigInteger activeOneBased = explicitOneBased != null
                    ? explicitOneBased
                    : union.oneBasedBranchIndex();
                var branchTag = "#" + activeOneBased;
                boolean isExplicit = trajectory.stoppedAtConstantReference()
                    || (!trajectory.steps().isEmpty() && !trajectory.steps().get(0).inferred())
                    || explicitOneBased != null
                    || value.getExplicitUnionValue() != null
                    || (value.getParent() instanceof ExplicitUnionValue);
                var tagText = isExplicit ? branchTag : "[" + branchTag + "]";

                int activeTagIndex = toSafeZeroBasedIndex(activeOneBased);
                var branchPsi = getNominalUnionBranchPsi(file, rootAlias, activeTagIndex);
                var innerVal = value.getExplicitUnionValue() != null ? value.getExplicitUnionValue().getValue() : value;
                var branchTrace = branchPsi != null ? resolvePsiBranchTrace(branchPsi, innerVal, project) : "";
                if (branchTrace.isEmpty()) {
                    var childVal = union.value();
                    branchTrace = unspoolBranchTrajectory(childVal.schema(), childVal, file, project, useLong);
                }
                return prefix + (!branchTrace.isEmpty() ? rootAlias + " " + tagText + " (-> " + branchTrace + ")" : rootAlias + " " + tagText);
            }

            if (resolvedNode instanceof StvnValue.StvnOption opt) {
                var trajectory = StvnSumTrajectory.evaluate(value, opt, catalog);
                var isExplicit = trajectory.stoppedAtConstantReference()
                    || (!trajectory.steps().isEmpty() && !trajectory.steps().get(0).inferred())
                    || value.getExplicitOptionValue() != null
                    || isExplicitOptionText(value.getText().trim());

                if (opt.value().isEmpty()) {
                    var tag = useLong ? "#None" : "#N";
                    return prefix + rootAlias + " " + tag;
                }

                var tag = useLong ? "#Some" : "#S";
                var tagText = isExplicit ? tag : "[" + tag + "]";

                var branchPsi = getNominalOptionBranchPsi(file, rootAlias);
                var innerVal = value.getExplicitOptionValue() != null ? value.getExplicitOptionValue().getValue() : value;
                var branchTrace = branchPsi != null ? resolvePsiBranchTrace(branchPsi, innerVal, project) : "";
                if (branchTrace.isEmpty()) {
                    var childVal = opt.value().orElse(null);
                    if (childVal != null) {
                        branchTrace = unspoolBranchTrajectory(childVal.schema(), childVal, file, project, useLong);
                    }
                }
                return prefix + (!branchTrace.isEmpty() ? rootAlias + " " + tagText + " (-> " + branchTrace + ")" : rootAlias + " " + tagText);
            }

            if (resolvedNode instanceof StvnValue.StvnEither either) {
                var trajectory = StvnSumTrajectory.evaluate(value, either, catalog);
                var isExplicit = trajectory.stoppedAtConstantReference()
                    || (!trajectory.steps().isEmpty() && !trajectory.steps().get(0).inferred())
                    || value.getExplicitEitherValue() != null
                    || isExplicitEitherText(value.getText().trim());
                var isRight = either.branch() == StvnValue.EitherBranch.RIGHT;
                var tag = isRight
                    ? (useLong ? "#Right" : "#R")
                    : (useLong ? "#Left" : "#L");
                var tagText = isExplicit ? tag : "[" + tag + "]";

                var branchPsi = getNominalEitherBranchPsi(file, rootAlias, isRight);
                var innerVal = value.getExplicitEitherValue() != null ? value.getExplicitEitherValue().getValue() : value;
                var branchTrace = branchPsi != null ? resolvePsiBranchTrace(branchPsi, innerVal, project) : "";
                if (branchTrace.isEmpty()) {
                    var childVal = either.value();
                    branchTrace = unspoolBranchTrajectory(childVal.schema(), childVal, file, project, useLong);
                }
                return prefix + (!branchTrace.isEmpty() ? rootAlias + " " + tagText + " (-> " + branchTrace + ")" : rootAlias + " " + tagText);
            }

            var alias = rootAlias;
            var shortAlias = alias.startsWith(":org/stvnadore/prelude/")
                ? ":" + alias.substring(":org/stvnadore/prelude/".length())
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
                    return prefix + trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
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
            if (firstChild != null && firstChild.getText().startsWith("#")) {
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
            if (token.startsWith("#")) {
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
        if (tagText == null || !tagText.startsWith("#")) {
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
                    var tag = useLong ? "#None" : "#N";
                    levels.add(new SumLevelDescriptor(SumKind.OPTION, tag, Set.of("#None", "#N"), true));
                    break;
                } else {
                    var tag = useLong ? "#Some" : "#S";
                    levels.add(new SumLevelDescriptor(SumKind.OPTION, tag, Set.of("#Some", "#S"), false));
                    curr = opt.value().orElse(null);
                }
            } else if (curr instanceof StvnValue.StvnEither either) {
                var isRight = either.branch() == StvnValue.EitherBranch.RIGHT;
                var tag = isRight ? (useLong ? "#Right" : "#R") : (useLong ? "#Left" : "#L");
                var tokens = isRight ? Set.of("#Right", "#R") : Set.of("#Left", "#L");
                levels.add(new SumLevelDescriptor(SumKind.EITHER, tag, tokens, false));
                curr = either.value();
            } else if (curr instanceof StvnValue.StvnUnion union) {
                var tag = "#" + union.oneBasedBranchIndex();
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
        return token.equals("#Some") || token.equals("#S") || token.equals("#None") || token.equals("#N")
            || token.equals("#Right") || token.equals("#R") || token.equals("#Left") || token.equals("#L")
            || EXPLICIT_UNION_TAG_PATTERN.matcher(token).matches();
    }

    private static boolean isExplicitOptionText(String text) {
        return text.startsWith("#Some ") || text.equals("#Some")
            || text.startsWith("#S ") || text.equals("#S")
            || text.startsWith("#None") || text.equals("#N");
    }

    private static boolean isExplicitEitherText(String text) {
        return text.startsWith("#Left ") || text.equals("#Left")
            || text.startsWith("#L ") || text.equals("#L")
            || text.startsWith("#Right ") || text.equals("#Right")
            || text.startsWith("#R ") || text.equals("#R");
    }

    private static String unspoolBranchTrajectory(
        StvnSchema branchSchema,
        @Nullable StvnValue branchValue,
        PsiFile file,
        Project project,
        boolean useLong
    ) {
        if (StvnSumTrajectory.isSumValue(branchValue) && branchValue != null) {
            var innerBase = branchValue.schema();
            var innerAlias = innerBase.aliasName().orElse(null);
            if (branchValue instanceof StvnValue.StvnUnion innerUnion) {
                var innerTag = "#" + innerUnion.oneBasedBranchIndex();
                var innerChild = innerUnion.value();
                var innerChildTrace = unspoolBranchTrajectory(innerChild.schema(), innerChild, file, project, useLong);
                if (innerAlias != null) {
                    return innerAlias + " " + innerTag + (!innerChildTrace.isEmpty() ? " (-> " + innerChildTrace + ")" : "");
                } else {
                    return !innerChildTrace.isEmpty() ? innerChildTrace + " " + innerTag : innerTag;
                }
            } else if (branchValue instanceof StvnValue.StvnOption innerOpt) {
                if (innerOpt.value().isEmpty()) {
                    var tag = useLong ? "#None" : "#N";
                    return innerAlias != null ? innerAlias + " " + tag : tag;
                }
                var innerTag = useLong ? "#Some" : "#S";
                var innerChild = innerOpt.value().orElse(null);
                if (innerChild != null) {
                    var innerChildTrace = unspoolBranchTrajectory(innerChild.schema(), innerChild, file, project, useLong);
                    if (innerAlias != null) {
                        return innerAlias + " " + innerTag + (!innerChildTrace.isEmpty() ? " (-> " + innerChildTrace + ")" : "");
                    } else {
                        return !innerChildTrace.isEmpty() ? innerChildTrace + " " + innerTag : innerTag;
                    }
                }
                return innerAlias != null ? innerAlias + " " + innerTag : innerTag;
            } else if (branchValue instanceof StvnValue.StvnEither innerEither) {
                var innerTag = innerEither.branch() == StvnValue.EitherBranch.RIGHT
                    ? (useLong ? "#Right" : "#R")
                    : (useLong ? "#Left" : "#L");
                var innerChild = innerEither.value();
                var innerChildTrace = unspoolBranchTrajectory(innerChild.schema(), innerChild, file, project, useLong);
                if (innerAlias != null) {
                    return innerAlias + " " + innerTag + (!innerChildTrace.isEmpty() ? " (-> " + innerChildTrace + ")" : "");
                } else {
                    return !innerChildTrace.isEmpty() ? innerChildTrace + " " + innerTag : innerTag;
                }
            }
        }

        if (branchValue instanceof StvnValue.StvnEnum && branchSchema.aliasName().isEmpty()) {
            return StvnVocabulary.TYPE_ENUM;
        }

        if (branchSchema.aliasName().isPresent()) {
            var alias = branchSchema.aliasName().get();
            var shortAlias = alias.startsWith(":org/stvnadore/prelude/")
                ? ":" + alias.substring(":org/stvnadore/prelude/".length())
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
                    return String.join(" -> ", trace);
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
            var bare = ":" + alias.substring(alias.lastIndexOf('/') + 1);
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
            var bare = ":" + alias.substring(alias.lastIndexOf('/') + 1);
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
            var bare = ":" + alias.substring(alias.lastIndexOf('/') + 1);
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

        if (!StvnTypeInferenceHelper.isStructurallyCompatible(value, info.getSchema())) {
            return null;
        }

        var resolvedSchema = resolveNominalSchema(info.getSchema());
        var schemaToInspect = resolvedSchema != null ? resolvedSchema : info.getSchema();
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor != null) {
            var sumType = constructor.getSumType();
            if (sumType != null) {
                var baseLabel = info.getLabel();
                var useLong = useLongFormSumTypes(value.getProject());
                if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                    var eitherVal = value.getExplicitEitherValue();
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (eitherVal != null) {
                        var txt = eitherVal.getText().trim().split("\\s+")[0];
                        var isLeft = txt.equals("#Left") || txt.equals("#L");
                        var tagText = isLeft ? (useLong ? "#Left" : "#L") : (useLong ? "#Right" : "#R");
                        var branchSchema = (isLeft && !innerSchemas.isEmpty()) ? innerSchemas.get(0) : (innerSchemas.size() > 1 ? innerSchemas.get(1) : null);
                        var innerVal = eitherVal.getValue();
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, innerVal, value.getProject()) : "";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace + " " + tagText : baseLabel + " " + tagText;
                        }
                    } else {
                        var tagText = useLong ? "[#Right]" : "[#R]";
                        var branchSchema = innerSchemas.size() > 1 ? innerSchemas.get(1) : null;
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, value, value.getProject()) : "";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace + " " + tagText : baseLabel + " " + tagText;
                        }
                    }
                } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_OPTION)) {
                    var optVal = value.getExplicitOptionValue();
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var payloadSchema = !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
                    var innerVal = optVal != null ? optVal.getValue() : null;
                    var payloadTrace = payloadSchema != null ? resolvePsiBranchTrace(payloadSchema, innerVal, value.getProject()) : "";

                    if (optVal != null) {
                        var txt = optVal.getText().trim().split("\\s+")[0];
                        var isNone = txt.equals("#None") || txt.equals("#N");
                        var tagText = isNone ? (useLong ? "#None" : "#N") : (useLong ? "#Some" : "#S");
                        if (isNone) {
                            return baseLabel + " " + tagText;
                        }
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!payloadTrace.isEmpty() ? " (-> " + payloadTrace + ")" : "");
                        } else {
                            return baseLabel + " " + tagText;
                        }
                    } else {
                        var txt = value.getText().trim().split("\\s+")[0];
                        if (txt.equals("#None") || txt.equals("#N")) {
                            var tagText = useLong ? "#None" : "#N";
                            return baseLabel + " " + tagText;
                        } else {
                            var tagText = useLong ? "[#Some]" : "[#S]";
                            payloadTrace = payloadSchema != null ? resolvePsiBranchTrace(payloadSchema, value, value.getProject()) : "";
                            if (info.getSchema().getTypeKeyword() != null) {
                                return baseLabel + " " + tagText + (!payloadTrace.isEmpty() ? " (-> " + payloadTrace + ")" : "");
                            } else {
                                return !payloadTrace.isEmpty() ? payloadTrace + " " + tagText : baseLabel + " " + tagText;
                            }
                        }
                    }
                } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                    var unionVal = value.getExplicitUnionValue();
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (unionVal != null) {
                        var firstChild = unionVal.getFirstChild();
                        var tagText = firstChild != null ? firstChild.getText() : "#1";
                        var resolvedIdx = StvnPsiLiteralEvaluator.resolveZeroBasedUnionBranchIndex(tagText, innerSchemas.size());
                        var branchSchema = resolvedIdx.isPresent() ? innerSchemas.get(resolvedIdx.getAsInt()) : null;
                        var innerVal = unionVal.getValue();
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, innerVal, value.getProject()) : "";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace + " " + tagText : baseLabel + " " + tagText;
                        }
                    } else {
                        var tagIndex = 0;
                        for (int i = 0; i < innerSchemas.size(); i++) {
                            var schema = innerSchemas.get(i);
                            if (matchesSchemaPattern(value, schema)) {
                                tagIndex = i;
                                break;
                            }
                        }
                        var branchSchema = (tagIndex >= 0 && tagIndex < innerSchemas.size()) ? innerSchemas.get(tagIndex) : null;
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, value, value.getProject()) : "";
                        var tagText = "[#" + (tagIndex + 1) + "]";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace : baseLabel;
                        }
                    }
                } else if (sumType.getEnumDef() != null) {
                    if (matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
                        if (info.getSchema().getTypeKeyword() != null) {
                            var trace = StvnTypeReference.extractResolutionTrace(info.getSchema().getTypeKeyword());
                            if (trace.size() > 1) {
                                return trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                            }
                            return info.getLabel();
                        }
                        return StvnVocabulary.TYPE_ENUM;
                    }
                }
            }
        }

        var keyword = info.getSchema().getTypeKeyword();
        if (keyword != null) {
            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            if (trace.size() > 1 && matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
                if (!StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(keyword.getText())) {
                    var isDegraded = isDegradedNominalAlias(value.getContainingFile(), keyword.getText());
                    var prefix = isDegraded ? "\u26A0 " : "";
                    return prefix + trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                }
                return info.getLabel();
            }
        }
        if (matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
            return info.getLabel();
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
        var keyword = branchSchema.getTypeKeyword();
        if (keyword != null) {
            var shortKw = keyword.getText().startsWith(":org/stvnadore/prelude/")
                ? ":" + keyword.getText().substring(":org/stvnadore/prelude/".length())
                : keyword.getText();
            if (StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(shortKw)) {
                return shortKw;
            }
            var file = keyword.getContainingFile();
            var resolvedTarget = StvnTypeReference.resolveTypeInFile(file, keyword.getText(), new HashSet<>());
            var targetDef = resolveUnderlyingTypeDefinition(resolvedTarget);
            if (targetDef != null) {
                var targetSchema = targetDef.getSchemaType();
                if (targetSchema != null && targetSchema.getSchemaConstructor() != null) {
                    var targetConstructor = targetSchema.getSchemaConstructor();
                    var targetSum = targetConstructor.getSumType();
                    if (targetSum != null) {
                        var useLong = useLongFormSumTypes(project);
                        var targetAlias = keyword.getText();
                        if (targetSum.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(targetSum, SchemaType.class);
                            int activeTagIndex = 0;
                            String branchTag = null;
                            if (innerValue != null && innerValue.getExplicitUnionValue() != null) {
                                var firstChild = innerValue.getExplicitUnionValue().getFirstChild();
                                if (firstChild != null && firstChild.getText().startsWith("#")) {
                                    branchTag = firstChild.getText().trim();
                                    var resolvedIdx = StvnPsiLiteralEvaluator
                                        .resolveZeroBasedUnionBranchIndex(branchTag, innerSchemas.size());
                                    activeTagIndex = resolvedIdx.isPresent() ? resolvedIdx.getAsInt() : -1;
                                }
                            } else if (innerValue != null) {
                                for (int i = 0; i < innerSchemas.size(); i++) {
                                    if (matchesSchemaPattern(innerValue, innerSchemas.get(i))) {
                                        activeTagIndex = i;
                                        break;
                                    }
                                }
                            }
                            if (branchTag == null) {
                                branchTag = "#" + (activeTagIndex + 1);
                            }
                            var isExplicit = innerValue != null && (innerValue.getExplicitUnionValue() != null || (innerValue.getParent() instanceof ExplicitUnionValue));
                            var tagText = isExplicit ? branchTag : "[" + branchTag + "]";
                            var nestedBranch = (activeTagIndex >= 0 && activeTagIndex < innerSchemas.size()) ? innerSchemas.get(activeTagIndex) : null;
                            var nestedVal = innerValue != null && innerValue.getExplicitUnionValue() != null ? innerValue.getExplicitUnionValue().getValue() : innerValue;
                            var nestedTrace = nestedBranch != null ? resolvePsiBranchTrace(nestedBranch, nestedVal, project) : "";
                            return targetAlias + " " + tagText + (!nestedTrace.isEmpty() ? " (-> " + nestedTrace + ")" : "");
                        } else if (targetSum.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(targetSum, SchemaType.class);
                            var eitherVal = innerValue != null ? innerValue.getExplicitEitherValue() : null;
                            var isLeft = false;
                            if (eitherVal != null) {
                                var txt = eitherVal.getText().trim().split("\\s+")[0];
                                isLeft = txt.equals("#Left") || txt.equals("#L");
                            } else if (innerValue != null && !innerSchemas.isEmpty()) {
                                isLeft = matchesSchemaPattern(innerValue, innerSchemas.get(0));
                            }
                            var isExplicit = eitherVal != null;
                            var tag = isLeft ? (useLong ? "#Left" : "#L") : (useLong ? "#Right" : "#R");
                            var tagText = isExplicit ? tag : "[" + tag + "]";
                            var nestedBranch = (isLeft && !innerSchemas.isEmpty()) ? innerSchemas.get(0) : (innerSchemas.size() > 1 ? innerSchemas.get(1) : null);
                            var nestedVal = eitherVal != null ? eitherVal.getValue() : innerValue;
                            var nestedTrace = nestedBranch != null ? resolvePsiBranchTrace(nestedBranch, nestedVal, project) : "";
                            return targetAlias + " " + tagText + (!nestedTrace.isEmpty() ? " (-> " + nestedTrace + ")" : "");
                        } else if (targetSum.getText().startsWith(StvnVocabulary.TYPE_OPTION)) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(targetSum, SchemaType.class);
                            var optVal = innerValue != null ? innerValue.getExplicitOptionValue() : null;
                            var isNone = false;
                            if (optVal != null) {
                                var txt = optVal.getText().trim().split("\\s+")[0];
                                isNone = txt.equals("#None") || txt.equals("#N");
                            } else if (innerValue != null) {
                                var txt = innerValue.getText().trim().split("\\s+")[0];
                                isNone = txt.equals("#None") || txt.equals("#N");
                            }
                            if (isNone) {
                                var tag = useLong ? "#None" : "#N";
                                return targetAlias + " " + tag;
                            }
                            var isExplicit = optVal != null;
                            var tag = useLong ? "#Some" : "#S";
                            var tagText = isExplicit ? tag : "[" + tag + "]";
                            var nestedBranch = !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
                            var nestedVal = optVal != null ? optVal.getValue() : innerValue;
                            var nestedTrace = nestedBranch != null ? resolvePsiBranchTrace(nestedBranch, nestedVal, project) : "";
                            return targetAlias + " " + tagText + (!nestedTrace.isEmpty() ? " (-> " + nestedTrace + ")" : "");
                        }
                    }
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
        if (value == null) {
            return false;
        }
        var resolved = resolveNominalSchema(schema);
        var schemaToInspect = (resolved != null) ? resolved : schema;
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
                    return matchesSchemaPattern(value, innerSchemas.get(0));
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
                    return matchesSchemaPattern(value, innerSchemas.get(1));
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
                        return innerVal == null || matchesSchemaPattern(innerVal, innerSchemas.get(resolvedIdx.getAsInt()));
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
                return innerSchemas.stream().anyMatch(s -> matchesSchemaPattern(value, s));
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
        if (bodyEntry == null) {
            return null;
        }

        var path = new Stack<PathStep>();
        var curr = (PsiElement) element;
        while (curr != null) {
            var parent = curr.getParent();
            if (parent instanceof BodyEntry) {
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
                if (idx != -1) {
                    var isKey = (idx % 2 == 0);
                    path.push(new PathStep(isKey ? StepType.MAP_KEY : StepType.MAP_VALUE, isKey ? 0 : 1));
                    path.push(new PathStep(StepType.MAP, -1));
                }
            } else if (parent instanceof ExplicitOptionValue) {
                path.push(new PathStep(StepType.OPTION, -1));
            } else if (parent instanceof ExplicitEitherValue either) {
                var text = either.getText().trim();
                var isLeft = text.startsWith("#Left") || text.startsWith("#L");
                path.push(new PathStep(StepType.EITHER, isLeft ? 0 : 1));
            } else if (parent instanceof ExplicitUnionValue union) {
                var firstChild = union.getUnionTagPrefix();
                int tagIndex = parseUnionTagZeroBasedIndex(firstChild.getText());
                path.push(new PathStep(StepType.UNION, tagIndex));
            }

            curr = parent;
        }

        var containingFile = bodyEntry.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        var typeEntry = PsiTreeUtil.findChildOfType(containingFile, TypeEntry.class);
        if (typeEntry == null) {
            return null;
        }

        var rootSchemaType = typeEntry.getSchemaType();
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

    private static @Nullable SchemaType descendSchemaPath(SchemaType rootSchema, Stack<PathStep> path) {
        var currentSchema = rootSchema;
        while (!path.isEmpty() && currentSchema != null) {
            var step = path.pop();
            var resolvedNominal = resolveNominalSchema(currentSchema);
            var schemaToInspect = resolvedNominal != null ? resolvedNominal : currentSchema;

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
                } else if (step.type != StepType.UNION && sum.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                    var unionBranches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (unionBranches.size() == 1) {
                        innerSchema = unionBranches.get(0);
                    }
                }
                if (innerSchema == null) {
                    break;
                }
                var res = resolveNominalSchema(innerSchema);
                schemaToInspect = (res != null) ? res : innerSchema;
                var c = schemaToInspect.getSchemaConstructor();
                if (c == null) {
                    break;
                }
                constructor = c;
                sum = c.getSumType();
            }

            if (step.type == StepType.LIST) {
                var collection = constructor.getCollectionType();
                if (collection != null && !collection.getSchemaTypeList().isEmpty()) {
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
                } else if (step.index == 0 && constructor.getSumType() != null) {
                    currentSchema = schemaToInspect;
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
                    currentSchema = (step.index >= 0 && step.index < innerSchemas.size())
                        ? innerSchemas.get(step.index)
                        : null;
                } else {
                    currentSchema = null;
                }
            }
        }
        return currentSchema;
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
        var resolved = StvnTypeReference.resolveTypeInFile(keyword.getContainingFile(), typeName, new HashSet<>());
        if (resolved != null) {
            var alias = resolved instanceof IncludeMapAlias ima ? ima
                : resolved.getParent() instanceof IncludeMapAlias ima ? ima : null;
            if (alias != null) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2) {
                    var remoteKw = list.get(0);
                    var includeElement = PsiTreeUtil.getParentOfType(alias, IncludeElement.class);
                    if (includeElement != null) {
                        var stringLit = includeElement.getStringLiteral();
                        var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                        if (targetFile != null && remoteKw != null) {
                            var remTypeDef = findTypeDefinition(targetFile, remoteKw.getText(), null);
                            if (remTypeDef != null) {
                                return remTypeDef.getSchemaType();
                            }
                        }
                    }
                }
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
        if (schemaType == null) return null;
        var kw = schemaType.getTypeKeyword();
        if (kw == null) return null;

        var visited = new HashSet<String>();
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

        var visited = new HashSet<String>();
        return resolveEnumSubsetInternal(kw.getContainingFile(), kw.getText(), visited);
    }

    /**
     * Resolves the enum subset declaration from a type keyword.
     *
     * @param typeKeyword the type keyword element
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable TypeKeyword typeKeyword) {
        if (typeKeyword == null) return null;

        var visited = new HashSet<String>();
        return resolveEnumSubsetInternal(typeKeyword.getContainingFile(), typeKeyword.getText(), visited);
    }

    /**
     * Resolves the enum subset declaration referenced by an include map alias.
     *
     * @param alias the include map alias element
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubset(@Nullable IncludeMapAlias alias) {
        if (alias == null) return null;
        var list = alias.getTypeKeywordList();
        var localKw = list.size() >= 2 ? list.get(1) : (!list.isEmpty() ? list.get(0) : null);
        if (localKw == null) return null;

        var visited = new HashSet<String>();
        return resolveEnumSubsetInternal(localKw.getContainingFile(), localKw.getText(), visited);
    }

    /**
     * Resolves an enum subset model from an arbitrary supported PSI element.
     *
     * @param element the PSI element ({@link SchemaType}, {@link TypeDefinition}, {@link TypeKeyword}, or {@link IncludeMapAlias})
     * @return the resolved {@link StvnEnumSubsetInfo}, or {@code null} if unresolvable
     */
    public static @Nullable StvnEnumSubsetInfo resolveEnumSubsetFromElement(@Nullable PsiElement element) {
        if (element == null) return null;
        if (element instanceof SchemaType st) return resolveEnumSubset(st);
        if (element instanceof TypeDefinition td) return resolveEnumSubset(td);
        if (element instanceof TypeKeyword tk) return resolveEnumSubset(tk);
        if (element instanceof IncludeMapAlias ima) return resolveEnumSubset(ima);
        return null;
    }

    private static @Nullable StvnEnumSubsetInfo resolveEnumSubsetInternal(
        PsiFile file,
        String typeName,
        Set<String> visited
    ) {
        if (!visited.add(typeName)) return null;

        var targetDef = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
        if (targetDef == null) return null;

        TypeDefinition typeDef = resolveUnderlyingTypeDefinition(targetDef);
        if (typeDef == null) {
            return null;
        }

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
                var parentSubset = resolveEnumSubset(parentSchema);
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
        var parentSubset = resolveEnumSubset(parentSchema);

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
                        var isLeft = eitherVal.getText().startsWith("#Left") || eitherVal.getText().startsWith("#L");
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
            ? ":" + first.substring(":org/stvnadore/prelude/".length())
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
        if (coreNode == null) {
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
            if (tagText.startsWith("#")) {
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
                var isLeft = text.startsWith("#Left") || text.startsWith("#L");
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
            if (enclosingPkg != null && (!typeName.startsWith(":") || !typeName.substring(1).contains("/"))) {
                for (var elem : enclosingPkg.getPackageElementList()) {
                    var typeDef = elem.getTypeDefinition();
                    if (typeDef != null) {
                        var kw = typeDef.getTypeKeyword();
                        if (kw != null && kw.getText().equals(typeName)) {
                            return typeDef;
                        }
                    }
                }
            }
        }
        var resolved = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
        return StvnPsiUtils.getParentTypeDefinition(resolved);
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
        if (text.startsWith("#")) {
            var constDef = findConstantDefinition(file, text);
            if (constDef != null) {
                return true;
            }
        }
        var info = resolveBaseTypeInfo(value);
        if (info != null) {
            var resolved = resolveNominalSchema(info.getSchema());
            var toInspect = resolved != null ? resolved : info.getSchema();
            if (StvnTypeInferenceHelper.isStructurallyCompatible(value, info.getSchema())) {
                if (err.schema() instanceof StvnSchema.PoisonedSchema) {
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
