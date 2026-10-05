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
                            "Duplicate facet '#" + facetName + "' is prohibited",
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove duplicate facet")
                        );
                    }
                }
            }

            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition def) {
                // Top-level validation handled uniformly via visitSchemaType
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (schemaType.getParent() instanceof ConstantDefinition) {
                    return; // Handled by visitConstantDefinition
                }
                var metaMap = schemaType.getMetadataMap();
                if (metaMap != null) {
                    if (metaMap.getMetadataEntryList().isEmpty()) {
                        holder.registerProblem(
                            metaMap,
                            "Empty metadata block is invalid; remove '{}' or specify valid facets",
                            ProblemHighlightType.ERROR,
                            new RemoveElementQuickFix("Remove empty metadata block")
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
                    if (metaMap.getMetadataEntryList().isEmpty()) {
                        holder.registerProblem(
                            metaMap,
                            "Empty metadata block is invalid; remove '{}' or specify valid facets",
                            ProblemHighlightType.ERROR,
                            new RemoveElementQuickFix("Remove empty metadata block")
                        );
                    } else {
                        for (var entry : metaMap.getMetadataEntryList()) {
                            holder.registerProblem(
                                entry,
                                "Metadata facets are not permitted on constants; permitted facets: []",
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
                        "Empty directive block in :use statement is invalid; specify {#strip} or remove '{}'",
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
                var aliasBlock = stmt.getUseAliasBlock();
                if (aliasBlock != null && aliasBlock.getUseMapAliasList().isEmpty()) {
                    holder.registerProblem(
                        aliasBlock,
                        "Empty directive block in :use statement is invalid; specify alias mappings or remove '{}'",
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
                        "Empty directive block in :include statement is invalid; specify {#strip} or remove '{}'",
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
                var aliasBlock = element.getIncludeAliasBlock();
                if (aliasBlock != null && aliasBlock.getIncludeMapAliasList().isEmpty()) {
                    holder.registerProblem(
                        aliasBlock,
                        "Empty directive block in :include statement is invalid; specify alias mappings or remove '{}'",
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
            if (entry.getMetadataDirective() != null) {
                holder.registerProblem(
                    entry,
                    "Directive facet '#strip' is not permitted on type declarations; directive facets are valid strictly in :use and :include blocks",
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
                    "Facet is not permitted on " + baseType + "; permitted facets for numeric, temporal, and string types: [#minIncl, #maxIncl, #minExcl, #maxExcl]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, NUMERIC_FACET_TOKENS) && !isNumeric) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for numeric types: [#equatable, #comparable, #size, #unsigned, #exact]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, STRING_ONLY_FACET_TOKENS) && !isString) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for string types: [#equatable, #comparable, #regex, #minSize, #maxSize, #preserveIndent]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, DIMENSION_FACET_TOKENS) && !isString && !isCollection) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for collections and string types: [#minSize, #maxSize]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, TEMPORAL_FACET_TOKENS) && !isTemporal) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for temporal types: [#s, #ms, #us, #ns, #offset, #zoned, #audited]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (hasToken(entry, MAP_FACET_TOKENS) && !isMap) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for map types: [#invertible]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getMetadataFilter() != null && !isEnum) {
                holder.registerProblem(
                    entry,
                    "Filter facets are permitted strictly on nominal aliases of :Enum and enum subsets",
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

    private static final class RemoveElementQuickFix implements LocalQuickFix {
        private final String familyName;

        RemoveElementQuickFix(String familyName) {
            this.familyName = familyName;
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
