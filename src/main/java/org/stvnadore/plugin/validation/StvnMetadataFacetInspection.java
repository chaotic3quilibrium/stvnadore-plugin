package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Validates STVN metadata facet compatibility and detects empty blocks in directives and metadata.
 */
@NullMarked
public final class StvnMetadataFacetInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnMetadataFacetInspection instance.
     */
    public StvnMetadataFacetInspection() {
    }

    private static final TokenSet NUMERIC_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_SIZE, StvnTypes.KW_UNSIGNED, StvnTypes.KW_EXACT
    );
    private static final TokenSet INTERVAL_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_MIN_INCL, StvnTypes.KW_MIN_EXCL, StvnTypes.KW_MAX_INCL, StvnTypes.KW_MAX_EXCL
    );
    private static final TokenSet STRING_ONLY_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_REGEX
    );
    private static final TokenSet DIMENSION_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_MIN_SIZE, StvnTypes.KW_MAX_SIZE
    );
    private static final TokenSet TEMPORAL_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_SCALE_S, StvnTypes.KW_SCALE_MS, StvnTypes.KW_SCALE_US, StvnTypes.KW_SCALE_NS,
        StvnTypes.KW_OFFSET, StvnTypes.KW_ZONED, StvnTypes.KW_AUDITED
    );
    private static final TokenSet MAP_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_INVERTIBLE
    );

    @Override
    public @NotNull String getShortName() {
        return "StvnMetadataFacet";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Metadata facet and block governance inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitMetadataMap(@NotNull MetadataMap metaMap) {
                super.visitMetadataMap(metaMap);
                Set<String> seenFacets = new HashSet<>();
                for (var entry : metaMap.getMetadataEntryList()) {
                    String facetName = extractFacetName(entry);
                    if (facetName == null || facetName.isEmpty()) continue;
                    if (!seenFacets.add(facetName)) {
                        holder.registerProblem(
                            entry,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Duplicate facet '#" + facetName + "' is prohibited",
                                StvnDiagnosticCodes.ERR_DUPLICATE_METADATA_FACET
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove duplicate facet")
                        );
                    }
                }
            }

            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition def) {
                super.visitTypeDefinition(def);
                var metaMap = def.getMetadataMap();
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        var trait = entry.getMetadataTrait();
                        if (trait != null && trait.getBooleanValue() == null) {
                            var fixes = CompleteTraitFacetQuickFix.createFixes(trait);
                            holder.registerProblem(
                                trait,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Metadata facet #preserveIndent requires an explicit boolean value (#TRUE or #FALSE)",
                                    StvnDiagnosticCodes.ERR_INCOMPLETE_TRAIT_FACET
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                            );
                        }
                    }
                }
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (schemaType.getParent() instanceof ConstantDefinition) {
                    return; // Handled by visitConstantDefinition
                }
                var metaMap = schemaType.getMetadataMap();
                if (metaMap != null) {
                    if (isGenuinelyEmptyConstraintBlock(metaMap)) {
                        holder.registerProblem(
                            metaMap,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Empty type constraint block is invalid; remove '{}' or specify valid constraint facets",
                                StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK
                            ),
                            ProblemHighlightType.ERROR,
                            new RemoveElementQuickFix("Remove empty type constraint block")
                        );
                    } else {
                        validateMetadataEntries(metaMap, schemaType, holder);
                    }
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition def) {
                var metaMap = def.getMetadataMap();
                if (metaMap != null) {
                    if (isGenuinelyEmptyConstraintBlock(metaMap)) {
                        holder.registerProblem(
                            metaMap,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Empty type constraint block is invalid; remove '{}' or specify valid constraint facets",
                                StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK
                            ),
                            ProblemHighlightType.ERROR,
                            new RemoveElementQuickFix("Remove empty type constraint block")
                        );
                    } else {
                        for (var entry : metaMap.getMetadataEntryList()) {
                            holder.registerProblem(
                                entry,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Metadata facets are not permitted on constants; permitted facets: []",
                                    StvnDiagnosticCodes.ERR_METADATA_FACET_ON_CONSTANT
                                ),
                                ProblemHighlightType.ERROR,
                                new RemoveElementQuickFix("Remove invalid facet")
                            );
                        }
                    }
                }
            }

            @Override
            public void visitUseStmt(@NotNull UseStmt stmt) {
                var optBlock = stmt.getUseOptionsBlock();
                if (optBlock != null && optBlock.getText().replaceAll("\\s+", "").equals("{}")) {
                    holder.registerProblem(
                        optBlock,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty directive block in :use statement is invalid; specify {#strip} or remove '{}'",
                            StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK
                        ),
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
                var aliasBlock = stmt.getUseAliasBlock();
                if (aliasBlock != null && aliasBlock.getUseMapAliasList().isEmpty()) {
                    holder.registerProblem(
                        aliasBlock,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty directive block in :use statement is invalid; specify alias mappings or remove '{}'",
                            StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK
                        ),
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
            }

            @Override
            public void visitIncludeElement(@NotNull IncludeElement element) {
                var optBlock = element.getIncludeOptionsBlock();
                if (optBlock != null && optBlock.getIncludeOptionList().isEmpty()) {
                    holder.registerProblem(
                        optBlock,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty directive block in :include statement is invalid; specify {#strip} or remove '{}'",
                            StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK
                        ),
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
                var aliasBlock = element.getIncludeAliasBlock();
                if (aliasBlock != null && aliasBlock.getIncludeMapAliasList().isEmpty()) {
                    holder.registerProblem(
                        aliasBlock,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty directive block in :include statement is invalid; specify alias mappings or remove '{}'",
                            StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK
                        ),
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
            }
        };
    }

    private void validateMetadataEntries(MetadataMap metaMap, SchemaType schemaType, ProblemsHolder holder) {
        var baseType = schemaType != null ? resolveBaseTypeString(schemaType, new HashSet<>()) : null;
        boolean isNumeric = baseType != null && isNumericType(baseType);
        boolean isString = baseType != null && isStringType(baseType);
        boolean isTemporal = baseType != null && isTemporalType(baseType);
        boolean isMap = baseType != null && isMapType(baseType);
        boolean isCollection = baseType != null && isCollectionType(baseType);
        boolean isBoolean = baseType != null && isBooleanType(baseType);
        boolean isEnum = baseType != null && isEnumConstructor(baseType);

        for (var entry : metaMap.getMetadataEntryList()) {
            if (entry.getMetadataUnknownEntry() != null) {
                continue;
            }
            if (entry.getMetadataDirective() != null) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Directive facet '#strip' is not permitted on type declarations; directive facets are valid strictly in :use and :include blocks",
                        StvnDiagnosticCodes.ERR_STRIP_ON_TYPE_DEF
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
                continue;
            }
            if (baseType == null) {
                continue;
            }
            if (hasToken(entry, INTERVAL_FACET_TOKENS) && !isNumeric && !isTemporal && !isString) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for numeric, temporal, and string types: [#minIncl, #maxIncl, #minExcl, #maxExcl]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, NUMERIC_FACET_TOKENS) && !isNumeric) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for numeric types: [#equatable, #comparable, #size, #unsigned, #exact]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, STRING_ONLY_FACET_TOKENS) && !isString) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for string types: [#equatable, #comparable, #regex, #minSize, #maxSize, #preserveIndent]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, DIMENSION_FACET_TOKENS) && !isString && !isCollection) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for collections and string types: [#minSize, #maxSize]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, TEMPORAL_FACET_TOKENS) && !isTemporal) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for temporal types: [#s, #ms, #us, #ns, #offset, #zoned, #audited]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, MAP_FACET_TOKENS) && !isMap) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for map types: [#invertible]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getMetadataFilter() != null && !isEnum) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Filter facets are permitted strictly on nominal aliases of :Enum and enum subsets",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            }
        }
    }

    private static boolean hasToken(MetadataEntry entry, TokenSet tokenSet) {
        if (entry.getNode().findChildByType(tokenSet) != null) {
            return true;
        }
        var bare = entry.getMetadataBareFlag();
        if (bare != null && bare.getNode().findChildByType(tokenSet) != null) {
            return true;
        }
        var trait = entry.getMetadataTrait();
        return trait != null && trait.getNode().findChildByType(tokenSet) != null;
    }

    private static @Nullable String resolveBaseTypeString(SchemaType schemaType, Set<String> visitedTypeNames) {
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            var atomic = constructor.getAtomicType();
            if (atomic != null) {
                return atomic.getText().trim();
            }
            var collection = constructor.getCollectionType();
            if (collection != null) {
                return collection.getText().trim();
            }
            var product = constructor.getProductType();
            if (product != null) {
                return product.getText().trim();
            }
            var sum = constructor.getSumType();
            if (sum != null) {
                return sum.getText().trim();
            }
            return constructor.getText().trim();
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (StvnVocabulary.TYPE_ENUM.equals(text)) {
                return StvnVocabulary.TYPE_ENUM;
            }
            if (StvnVocabulary.isBaseScalarType(text) || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(text)) {
                return text;
            }
            if (!visitedTypeNames.add(text)) {
                return null;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveBaseTypeString(parentDef.getSchemaType(), visitedTypeNames);
                    }
                    if (resolved instanceof IncludeMapAlias || resolved.getParent() instanceof IncludeMapAlias) {
                        var mapAlias = resolved instanceof IncludeMapAlias ima ? ima : (IncludeMapAlias) resolved.getParent();
                        var list = mapAlias.getTypeKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            var includeElement = PsiTreeUtil.getParentOfType(mapAlias, IncludeElement.class);
                            if (includeElement != null && includeElement.getStringLiteral() != null && remoteKw != null) {
                                var targetFile = org.stvnadore.plugin.reference.StvnTypeReference.resolveIncludeFile(includeElement.getStringLiteral());
                                if (targetFile != null) {
                                    var remoteResolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(targetFile, remoteKw.getText(), new HashSet<>());
                                    if (remoteResolved != null) {
                                        var remTypeDef = PsiTreeUtil.getParentOfType(remoteResolved, TypeDefinition.class);
                                        if (remTypeDef != null && remTypeDef.getSchemaType() != null) {
                                            return resolveBaseTypeString(remTypeDef.getSchemaType(), visitedTypeNames);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (resolved instanceof UseMapAlias || resolved.getParent() instanceof UseMapAlias) {
                        var mapAlias = resolved instanceof UseMapAlias uma ? uma : (UseMapAlias) resolved.getParent();
                        var list = mapAlias.getTypeKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            if (remoteKw != null) {
                                var remoteResolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, remoteKw.getText(), new HashSet<>());
                                if (remoteResolved != null) {
                                    var remTypeDef = PsiTreeUtil.getParentOfType(remoteResolved, TypeDefinition.class);
                                    if (remTypeDef != null && remTypeDef.getSchemaType() != null) {
                                        return resolveBaseTypeString(remTypeDef.getSchemaType(), visitedTypeNames);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }
        var rawText = schemaType.getText().trim();
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            var metaText = metaMap.getText().trim();
            if (rawText.startsWith(metaText)) {
                rawText = rawText.substring(metaText.length()).trim();
            }
        } else if (rawText.startsWith("{")) {
            int closeIdx = rawText.indexOf('}');
            if (closeIdx >= 0 && closeIdx + 1 < rawText.length()) {
                rawText = rawText.substring(closeIdx + 1).trim();
            }
        }
        if (rawText.isEmpty()) {
            return null;
        }
        if (StvnVocabulary.isBaseScalarType(rawText) || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(rawText)
            || isConstructorMatch(rawText, StvnVocabulary.TYPE_SEQ) || isConstructorMatch(rawText, StvnVocabulary.TYPE_SET)
            || isConstructorMatch(rawText, StvnVocabulary.TYPE_MAP) || isEnumConstructor(rawText)) {
            return rawText;
        }
        if (rawText.startsWith(":")) {
            if (!visitedTypeNames.add(rawText)) {
                return null;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, rawText, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveBaseTypeString(parentDef.getSchemaType(), visitedTypeNames);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isNumericType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_INT) || baseType.equals(StvnVocabulary.TYPE_FLOAT) || baseType.equals(StvnVocabulary.TYPE_TIME_EPOCH);
    }

    private static boolean isStringType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_STRING)
            || isCanonicalPreludeStringType(baseType);
    }

    private static boolean isCanonicalPreludeStringType(String baseType) {
        return baseType.equals(":org/stvnadore/prelude/Uuid")
            || baseType.equals(":org/stvnadore/prelude/Ulid")
            || baseType.equals(":org/stvnadore/prelude/Sha256")
            || baseType.equals(":org/stvnadore/prelude/SemVer")
            || baseType.equals(":org/stvnadore/prelude/Email")
            || baseType.equals(":org/stvnadore/prelude/IPv4")
            || baseType.equals(":Uuid")
            || baseType.equals(":Ulid")
            || baseType.equals(":Sha256")
            || baseType.equals(":SemVer")
            || baseType.equals(":Email")
            || baseType.equals(":IPv4");
    }

    private static boolean isBooleanType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_BOOLEAN);
    }

    private static boolean isCollectionType(String baseType) {
        return isConstructorMatch(baseType, StvnVocabulary.TYPE_SEQ) || isConstructorMatch(baseType, StvnVocabulary.TYPE_SET) || isConstructorMatch(baseType, StvnVocabulary.TYPE_MAP);
    }

    private static boolean isTemporalType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_TIME_EPOCH) || baseType.equals(StvnVocabulary.TYPE_DATE_TIME);
    }

    private static boolean isMapType(String baseType) {
        return isConstructorMatch(baseType, StvnVocabulary.TYPE_MAP);
    }

    private static boolean isEnumConstructor(String baseType) {
        baseType = stripLeadingMetadata(baseType);
        if (baseType.equals(StvnVocabulary.TYPE_ENUM)) {
            return true;
        }
        if (baseType.startsWith(StvnVocabulary.TYPE_ENUM)) {
            char next = baseType.charAt(StvnVocabulary.TYPE_ENUM.length());
            return next == '[' || Character.isWhitespace(next);
        }
        return false;
    }

    private static boolean isConstructorMatch(String type, String constructor) {
        type = stripLeadingMetadata(type);
        if (type.equals(constructor)) {
            return true;
        }
        if (type.startsWith(constructor)) {
            char next = type.charAt(constructor.length());
            return next == '(' || Character.isWhitespace(next);
        }
        return false;
    }

    private static String stripLeadingMetadata(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        String trimmed = type.trim();
        if (trimmed.startsWith("{")) {
            int closeIdx = trimmed.indexOf('}');
            if (closeIdx >= 0 && closeIdx + 1 < trimmed.length()) {
                return trimmed.substring(closeIdx + 1).trim();
            }
        }
        return trimmed;
    }

    public static @Nullable String extractFacetName(MetadataEntry entry) {
        String text = entry.getText().trim();
        if (text.isEmpty()) return null;
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '[' || c == '{' || c == '(') {
                end = i;
                break;
            }
        }
        String token = text.substring(0, end);
        return token.startsWith("#") ? token.substring(1) : token;
    }

    private static boolean isGenuinelyEmptyConstraintBlock(@NotNull MetadataMap metaMap) {
        if (!metaMap.getMetadataEntryList().isEmpty()) {
            return false;
        }
        if (hasUnknownMetadataEntries(metaMap)) {
            return false;
        }
        return !hasNonWhitespaceNonCommentTokens(metaMap);
    }

    private static boolean hasUnknownMetadataEntries(@NotNull MetadataMap metaMap) {
        if (PsiTreeUtil.findChildOfType(metaMap, MetadataUnknownEntry.class) != null) {
            return true;
        }
        for (var entry : metaMap.getMetadataEntryList()) {
            if (entry.getMetadataUnknownEntry() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonWhitespaceNonCommentTokens(@NotNull MetadataMap metaMap) {
        String text = metaMap.getText();
        if (text == null) {
            return false;
        }
        int openBrace = text.indexOf('{');
        if (openBrace == -1) {
            return false;
        }
        int closeBrace = text.lastIndexOf('}');
        String inner;
        if (closeBrace > openBrace) {
            inner = text.substring(openBrace + 1, closeBrace);
        } else {
            inner = text.substring(openBrace + 1);
        }
        String stripped = inner.replaceAll("(?m)//.*$", "");
        return !stripped.trim().isEmpty();
    }

    private static final class RemoveElementQuickFix implements LocalQuickFix {
        private final String familyName;

        RemoveElementQuickFix(String familyName) {
            this.familyName = familyName;
        }

        @Override
        public @Nls @NotNull String getName() {
            return familyName;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return familyName;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element != null && element.isValid()) {
                var prev = element.getPrevSibling();
                if (prev instanceof PsiWhiteSpace) {
                    if (!(element instanceof MetadataEntry) || prev.getPrevSibling() instanceof MetadataEntry) {
                        prev.delete();
                    }
                }
                element.delete();
            }
        }
    }
}
