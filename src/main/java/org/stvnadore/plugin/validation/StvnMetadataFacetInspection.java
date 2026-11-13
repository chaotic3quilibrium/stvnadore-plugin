package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates STVN metadata facet compatibility and detects empty blocks in directives, definitions, and metadata.
 */
@NullMarked
public final class StvnMetadataFacetInspection extends LocalInspectionTool {

    private static final Pattern POSIX_META_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

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
        StvnTypes.KW_REGEX, StvnTypes.KW_PRESERVE_INDENT
    );
    private static final TokenSet MIN_SIZE_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_MIN_SIZE
    );
    private static final TokenSet MAX_SIZE_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_MAX_SIZE
    );
    private static final TokenSet TIME_EPOCH_SCALE_TOKENS = TokenSet.create(
        StvnTypes.KW_SCALE_S, StvnTypes.KW_SCALE_MS, StvnTypes.KW_SCALE_US, StvnTypes.KW_SCALE_NS
    );
    private static final TokenSet DATE_TIME_MODE_TOKENS = TokenSet.create(
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
            public void visitMetaBlock(@NotNull MetaBlock block) {
                super.visitMetaBlock(block);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(block)) {
                    return;
                }
                var file = block.getContainingFile();
                if (file == null) {
                    return;
                }
                List<MetaElement> elements = block.getMetaElementList();
                if (elements.isEmpty()) {
                    if (PsiTreeUtil.hasErrorElements(block)) {
                        return;
                    }
                    String fileName = file.getName();
                    String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
                    boolean metaMandatory = StvnMetaTemplateUtil.isMetaMandatory(fileName);
                    List<LocalQuickFix> fixes = new ArrayList<>();
                    fixes.add(new StvnMissingMetaInspection.InsertMissingMetaBlockQuickFix(expectedKind));
                    if (StvnMetaTemplateUtil.isMetaExtensionMandatory(fileName)) {
                        fixes.add(new StvnMissingMetaInspection.InsertMissingMetaKindFacetQuickFix(expectedKind));
                    }
                    if (!metaMandatory) {
                        fixes.add(new RemoveParentMetaEntryQuickFix());
                    }
                    String facetTuple = "(" + StvnVocabulary.FACET_KW_META_NAME + ", "
                        + StvnVocabulary.FACET_KW_META_DOMAIN + ", " + StvnVocabulary.FACET_KW_META_KIND + ")";
                    String emptyMetaMsg = metaMandatory
                        ? "Empty '" + StvnVocabulary.KEYWORD_META + " {}' section is prohibited; declare document identity facets "
                            + facetTuple
                        : "Empty '" + StvnVocabulary.KEYWORD_META + " {}' section is prohibited; omit '"
                            + StvnVocabulary.KEYWORD_META + "' or declare document identity facets " + facetTuple;
                    holder.registerProblem(
                        block,
                        StvnDiagnosticFormatter.formatPlugin(emptyMetaMsg, StvnDiagnosticCodes.ERR_EMPTY_META_BLOCK),
                        ProblemHighlightType.GENERIC_ERROR,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                    return;
                }

                Set<String> seenMetaFacets = new HashSet<>();
                for (MetaElement el : elements) {
                    MetaUnknownFacet unk = el.getMetaUnknownFacet();
                    if (unk != null) {
                        var vkw = unk.getValueKeyword();
                        String facetTag = vkw != null ? vkw.getText().trim() : unk.getText().trim();
                        holder.registerProblem(
                            unk,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Invalid metadata facet tag '" + facetTag + "' in '" + StvnVocabulary.KEYWORD_META + "' block",
                                StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove invalid metadata facet '" + facetTag + "'", "Remove invalid metadata facet")
                        );
                        continue;
                    }

                    MetaNameFacet nameFacet = el.getMetaNameFacet();
                    if (nameFacet != null) {
                        if (!seenMetaFacets.add(StvnVocabulary.FACET_KW_META_NAME)) {
                            holder.registerProblem(
                                nameFacet,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Duplicate metadata facet tag '" + StvnVocabulary.FACET_KW_META_NAME
                                        + "' declared in '" + StvnVocabulary.KEYWORD_META + "' block",
                                    StvnDiagnosticCodes.ERR_DUPLICATE_METADATA_FACET
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                new RemoveElementQuickFix(
                                    "Remove duplicate metadata facet '" + StvnVocabulary.FACET_KW_META_NAME + "'",
                                    "Remove duplicate facet"
                                )
                            );
                        }
                        validatePosixMetaIdentifier(nameFacet.getStringLiteral(), StvnVocabulary.FACET_KW_META_NAME, holder);
                        continue;
                    }

                    MetaDomainFacet domainFacet = el.getMetaDomainFacet();
                    if (domainFacet != null) {
                        if (!seenMetaFacets.add(StvnVocabulary.FACET_KW_META_DOMAIN)) {
                            holder.registerProblem(
                                domainFacet,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Duplicate metadata facet tag '" + StvnVocabulary.FACET_KW_META_DOMAIN
                                        + "' declared in '" + StvnVocabulary.KEYWORD_META + "' block",
                                    StvnDiagnosticCodes.ERR_DUPLICATE_METADATA_FACET
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                new RemoveElementQuickFix(
                                    "Remove duplicate metadata facet '" + StvnVocabulary.FACET_KW_META_DOMAIN + "'",
                                    "Remove duplicate facet"
                                )
                            );
                        }
                        validatePosixMetaIdentifier(domainFacet.getStringLiteral(), StvnVocabulary.FACET_KW_META_DOMAIN, holder);
                        continue;
                    }

                    MetaKindFacet kindFacet = el.getMetaKindFacet();
                    if (kindFacet != null) {
                        if (!seenMetaFacets.add(StvnVocabulary.FACET_KW_META_KIND)) {
                            holder.registerProblem(
                                kindFacet,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Duplicate metadata facet tag '" + StvnVocabulary.FACET_KW_META_KIND
                                        + "' declared in '" + StvnVocabulary.KEYWORD_META + "' block",
                                    StvnDiagnosticCodes.ERR_DUPLICATE_METADATA_FACET
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                new RemoveElementQuickFix(
                                    "Remove duplicate metadata facet '" + StvnVocabulary.FACET_KW_META_KIND + "'",
                                    "Remove duplicate facet"
                                )
                            );
                        }
                    }
                }
            }

            @Override
            public void visitDefsEntry(@NotNull DefsEntry defsEntry) {
                super.visitDefsEntry(defsEntry);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(defsEntry)) {
                    return;
                }
                if (!defsEntry.getDefsElementList().isEmpty()) {
                    return;
                }
                if (PsiTreeUtil.hasErrorElements(defsEntry)) {
                    return;
                }
                var lbraceNode = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
                var rbraceNode = defsEntry.getNode().findChildByType(StvnTypes.RBRACE);
                if (lbraceNode == null || rbraceNode == null) {
                    return;
                }
                int entryStart = defsEntry.getTextRange().getStartOffset();
                TextRange braceRange = new TextRange(
                    lbraceNode.getStartOffset() - entryStart,
                    rbraceNode.getTextRange().getEndOffset() - entryStart
                );
                var file = defsEntry.getContainingFile();
                boolean isSchemaDoc = isDefsSchemaDocument(file, defsEntry);
                String ext = file != null
                    ? StvnMetaTemplateUtil.resolveFileExtensionOrDefault(file.getName(), ".stvn_d")
                    : ".stvn_d";
                String msg = isSchemaDoc
                    ? "Empty '" + StvnVocabulary.KEYWORD_DEFS + " {}' section is prohibited; definitions schema ("
                        + ext + ") requires at least one type or constant definition"
                    : "Empty '" + StvnVocabulary.KEYWORD_DEFS + " {}' section is prohibited; omit '"
                        + StvnVocabulary.KEYWORD_DEFS + "' or declare at least one type or constant definition";
                LocalQuickFix primaryFix = isSchemaDoc
                    ? new InsertDefaultTypeDefinitionQuickFix()
                    : new RemoveElementQuickFix(
                        "Remove empty '" + StvnVocabulary.KEYWORD_DEFS + " {}' section",
                        "Remove empty '" + StvnVocabulary.KEYWORD_DEFS + " {}' section"
                    );
                holder.registerProblem(
                    defsEntry,
                    braceRange,
                    StvnDiagnosticFormatter.formatPlugin(msg, StvnDiagnosticCodes.ERR_EMPTY_DEFS_BLOCK),
                    primaryFix
                );
            }

            @Override
            public void visitTypeEntry(@NotNull TypeEntry typeEntry) {
                super.visitTypeEntry(typeEntry);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeEntry)) {
                    return;
                }
                if (typeEntry.getSchemaType() != null) {
                    return;
                }
                var metaMap = typeEntry.getMetadataMap();
                if (metaMap == null) {
                    return;
                }
                if (isGenuinelyEmptyConstraintBlock(metaMap)) {
                    holder.registerProblem(
                        metaMap,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty metadata block is invalid; remove '{}' or specify valid facets",
                            StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new RemoveElementQuickFix("Remove empty metadata block")
                    );
                    return;
                }
                for (var entry : metaMap.getMetadataEntryList()) {
                    if (checkIncompleteTraitEntry(entry, holder) || checkInvalidTraitArgument(entry, holder)) {
                        continue;
                    }
                    if (entry.getMetadataDirective() != null) {
                        holder.registerProblem(
                            entry,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Constraint violation (<" + StvnVocabulary.KEYWORD_TYPE + ">): directive facet '"
                                    + StvnVocabulary.FACET_KW_STRIP + "' is not permitted on type declarations; "
                                    + "directive facets are valid strictly in " + StvnVocabulary.KEYWORD_USE
                                    + " and " + StvnVocabulary.KEYWORD_INCLUDE + " blocks",
                                StvnDiagnosticCodes.ERR_STRIP_ON_TYPE_DEF
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove invalid directive facet")
                        );
                    } else if (entry.getMetadataUnknownEntry() != null) {
                        var kw = entry.getMetadataUnknownEntry().getValueKeyword();
                        String facetName = kw.getText().trim();
                        holder.registerProblem(
                            kw,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Metadata violation (<" + StvnVocabulary.KEYWORD_TYPE
                                    + ">): Unrecognized constraint facet '" + facetName + "'",
                                StvnDiagnosticCodes.ERR_UNRECOGNIZED_METADATA_FACET
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove unrecognized facet '" + facetName + "'", "Remove unrecognized facet")
                        );
                    }
                }
            }

            @Override
            public void visitMetadataMap(@NotNull MetadataMap metaMap) {
                super.visitMetadataMap(metaMap);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaMap)) {
                    return;
                }
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
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(def)) {
                    return;
                }
                var metaMap = def.getMetadataMap();
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        checkIncompleteTraitEntry(entry, holder);
                    }
                    if (isGenuinelyEmptyConstraintBlock(metaMap)) {
                        holder.registerProblem(
                            metaMap,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Empty type constraint block is invalid; remove '{}' or specify valid constraint facets",
                                StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove empty type constraint block")
                        );
                    } else if (def.getSchemaType() != null) {
                        validateMetadataEntries(metaMap, def.getSchemaType(), holder);
                    }
                }
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(schemaType)) {
                    return;
                }
                if (schemaType.getParent() instanceof ConstantDefinition
                        || schemaType.getParent() instanceof TypeDefinition) {
                    return; // Handled by visitConstantDefinition / visitTypeDefinition
                }
                var metaMap = schemaType.getMetadataMap();
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        checkIncompleteTraitEntry(entry, holder);
                    }
                    if (isGenuinelyEmptyConstraintBlock(metaMap)) {
                        holder.registerProblem(
                            metaMap,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Empty type constraint block is invalid; remove '{}' or specify valid constraint facets",
                                StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove empty type constraint block")
                        );
                    } else {
                        validateMetadataEntries(metaMap, schemaType, holder);
                    }
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition def) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(def)) {
                    return;
                }
                var metaMap = def.getMetadataMap();
                if (metaMap != null) {
                    if (isGenuinelyEmptyConstraintBlock(metaMap)) {
                        holder.registerProblem(
                            metaMap,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Empty type constraint block is invalid; remove '{}' or specify valid constraint facets",
                                StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveElementQuickFix("Remove empty type constraint block")
                        );
                    } else {
                        for (var entry : metaMap.getMetadataEntryList()) {
                            if (checkIncompleteTraitEntry(entry, holder)) {
                                continue;
                            }
                            String facetName = extractFacetName(entry);
                            if (facetName == null) continue;

                            // Tier 5 Directives: Prohibited on constants
                            if (entry.getMetadataDirective() != null || "strip".equals(facetName)) {
                                holder.registerProblem(
                                    entry,
                                    StvnDiagnosticFormatter.formatPlugin(
                                        "Directive facet '#strip' is not permitted on constant definitions; directive facets are valid strictly in :use and :include blocks",
                                        StvnDiagnosticCodes.ERR_STRIP_ON_TYPE_DEF
                                    ),
                                    ProblemHighlightType.GENERIC_ERROR,
                                    new RemoveElementQuickFix("Remove invalid facet '#strip'", "Remove invalid facet")
                                );
                            }
                            // Tier 2 Trait Overrides: Prohibited on constants
                            else if ("equatable".equals(facetName) || "comparable".equals(facetName)) {
                                holder.registerProblem(
                                    entry,
                                    StvnDiagnosticFormatter.formatPlugin(
                                        "Trait overrides are not permitted on constants; permitted facets: [Definition, Bounds, Constraints]",
                                        StvnDiagnosticCodes.ERR_METADATA_FACET_ON_CONSTANT
                                    ),
                                    ProblemHighlightType.GENERIC_ERROR,
                                    new RemoveElementQuickFix("Remove invalid facet '#" + facetName + "'", "Remove invalid facet")
                                );
                            }
                        }
                        // Validate permitted Definition (Tier 1), Bounds (Tier 3), and Constraint (Tier 4) facets
                        if (def.getSchemaType() != null) {
                            validateMetadataEntries(metaMap, def.getSchemaType(), holder);
                        }
                    }
                }
            }

            @Override
            public void visitUseStmt(@NotNull UseStmt stmt) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(stmt)) {
                    return;
                }
                var optBlock = stmt.getUseOptionsBlock();
                if (optBlock != null && optBlock.getText().replaceAll("\\s+", "").equals("{}")) {
                    holder.registerProblem(
                        optBlock,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty directive block in :use statement is invalid; specify {#strip} or remove '{}'",
                            StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
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
                        ProblemHighlightType.GENERIC_ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
            }

            @Override
            public void visitIncludeElement(@NotNull IncludeElement element) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
                    return;
                }
                var optBlock = element.getIncludeOptionsBlock();
                if (optBlock != null && optBlock.getIncludeOptionList().isEmpty()) {
                    holder.registerProblem(
                        optBlock,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Empty directive block in :include statement is invalid; specify {#strip} or remove '{}'",
                            StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
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
                        ProblemHighlightType.GENERIC_ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
            }
        };
    }

    private void validateMetadataEntries(MetadataMap metaMap, SchemaType schemaType, ProblemsHolder holder) {
        // Validate directive facets (#strip) first, which are strictly prohibited on type declarations regardless of base type (Spec § 4.8.4)
        for (var entry : metaMap.getMetadataEntryList()) {
            if (entry.getMetadataDirective() != null) {
                if (schemaType != null && schemaType.getParent() instanceof ConstantDefinition) {
                    continue; // Handled by visitConstantDefinition
                }
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Directive facet '#strip' is not permitted on type declarations; directive facets are valid strictly in :use and :include blocks",
                        StvnDiagnosticCodes.ERR_STRIP_ON_TYPE_DEF
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    new RemoveElementQuickFix("Remove invalid facet '#strip'", "Remove invalid facet")
                );
            }
        }

        var baseType = schemaType != null ? StvnMetadataFacetAllowlist.resolveUnderlyingBaseType(schemaType, new HashSet<>()) : null;
        if (baseType == null) {
            return;
        }
        boolean isExactActive = StvnMetadataFacetAllowlist.isExactActive(metaMap, schemaType);
        boolean isNumeric = baseType.equals(StvnVocabulary.TYPE_INT) || baseType.equals(StvnVocabulary.TYPE_FLOAT);
        boolean isString = isStringType(baseType);
        boolean isTimeEpoch = StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType);
        boolean isDateTime = StvnVocabulary.TYPE_DATE_TIME.equals(baseType);
        boolean isMap = isMapType(baseType);
        boolean isCollection = isCollectionType(baseType);
        boolean isEnum = isEnumConstructor(baseType);
        String ownerLabel = resolveOwnerLabel(metaMap, baseType);

        // Local Trap 12 enforcement: #exact and #size co-declared on :Float (pinned to 2nd co-occurring facet)
        if (StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
            boolean seenSizeOrExact = false;
            for (var entry : metaMap.getMetadataEntryList()) {
                boolean isExact = hasToken(entry, TokenSet.create(StvnTypes.KW_EXACT));
                boolean isSize = hasToken(entry, TokenSet.create(StvnTypes.KW_SIZE));
                if (!isExact && !isSize) {
                    continue;
                }
                if (seenSizeOrExact) {
                    String curKw = isSize ? StvnVocabulary.FACET_KW_SIZE : StvnVocabulary.FACET_KW_EXACT;
                    String peerKw = isSize ? StvnVocabulary.FACET_KW_EXACT : StvnVocabulary.FACET_KW_SIZE;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + ownerLabel + "): facet '" + curKw
                                + "' is mutually exclusive with '" + peerKw + "'",
                            DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new RemoveRedundantFacetQuickFix(entry, curKw, "Remove conflicting facet")
                    );
                } else {
                    seenSizeOrExact = true;
                }
            }
        }

        for (var entry : metaMap.getMetadataEntryList()) {
            if ((entry.getMetadataTrait() != null && entry.getMetadataTrait().getMetadataValue() == null)
                    || (entry.getMetadataBool() != null && entry.getMetadataBool().getMetadataValue() == null)) {
                continue;
            }
            if (entry.getMetadataDirective() != null) {
                continue;
            }
            if (entry.getMetadataUnknownEntry() != null) {
                var unknownEntry = entry.getMetadataUnknownEntry();
                var kw = unknownEntry.getValueKeyword();
                String facetName = kw.getText().trim();
                holder.registerProblem(
                    kw,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Metadata violation (" + ownerLabel + "): Unrecognized constraint facet '" + facetName + "'",
                        DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    new RemoveElementQuickFix("Remove unrecognized facet '" + facetName + "'", "Remove unrecognized facet")
                );
                continue;
            }

            // Target B Deconfliction: Yield to StvnStringCardinalityInspection on :String with #size
            if (isString && hasToken(entry, TokenSet.create(StvnTypes.KW_SIZE))) {
                if (StvnDiagnosticAnnotatorMapping.isInspectionActive(entry.getContainingFile(), "StvnStringCardinality")) {
                    continue;
                }
            }

            String facetName = extractFacetName(entry);
            String kwTag = facetName != null ? StvnVocabulary.SIGIL_VALUE + facetName : entry.getText().trim();
            var inapplicableFix = new RemoveRedundantFacetQuickFix(entry, kwTag, "Remove inapplicable facet");

            // Temporal scale facets (#ns, #us, #ms, #s) strictly on :TimeEpoch
            if (hasToken(entry, TIME_EPOCH_SCALE_TOKENS) && !isTimeEpoch) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + ": facet '" + kwTag + "' is not permitted on " + baseType + "; temporal scale facets [#ns, #us, #ms, #s] apply only to :TimeEpoch",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
                continue;
            }
            // Temporal mode facets (#offset, #zoned, #audited) strictly on :DateTime
            if (hasToken(entry, DATE_TIME_MODE_TOKENS) && !isDateTime) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + ": facet '" + kwTag + "' is not permitted on " + baseType + "; temporal mode facets [#offset, #zoned, #audited] apply only to :DateTime",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
                continue;
            }
            if (hasToken(entry, INTERVAL_FACET_TOKENS) && !isNumeric && !isTimeEpoch && !isDateTime && !isString) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for numeric, temporal, and string types: [#minIncl, #maxIncl, #minExcl, #maxExcl]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else if (hasToken(entry, NUMERIC_FACET_TOKENS) && !isNumeric) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for numeric types: [#equatable, #comparable, #size, #unsigned, #exact]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else if (hasToken(entry, TokenSet.create(StvnTypes.KW_UNSIGNED)) && !StvnVocabulary.TYPE_INT.equals(baseType)) {
                var fixes = buildInvalidFacetQuickFixes(
                    inapplicableFix,
                    schemaType,
                    StvnVocabulary.TYPE_FLOAT.equals(baseType) ? StvnVocabulary.TYPE_INT : null
                );
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + ": facet '" + kwTag + "' is only applicable to " + StvnVocabulary.TYPE_INT + ", not " + baseType,
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    fixes
                );
            } else if (hasToken(entry, TokenSet.create(StvnTypes.KW_EXACT)) && !StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
                var fixes = buildInvalidFacetQuickFixes(
                    inapplicableFix,
                    schemaType,
                    StvnVocabulary.TYPE_INT.equals(baseType) ? StvnVocabulary.TYPE_FLOAT : null
                );
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + ": facet '" + kwTag + "' is only applicable to " + StvnVocabulary.TYPE_FLOAT + ", not " + baseType,
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    fixes
                );
            } else if (hasToken(entry, STRING_ONLY_FACET_TOKENS) && !isString) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for string types: [#equatable, #comparable, #regex, #minSize, #maxSize, #preserveIndent]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else if (isString && hasToken(entry, TokenSet.create(StvnTypes.KW_REGEX)) && hasLocalPreserveIndentTrue(metaMap)) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet '#regex' cannot be combined with '#preserveIndent #TRUE' on " + baseType,
                        StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else if (hasToken(entry, MIN_SIZE_FACET_TOKENS) && !isString && !isCollection) {
                var cleanBase = StvnMetadataFacetAllowlist.extractCleanBaseType(baseType);
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet '" + kwTag + "' is not permitted on " + baseType + "; '" + kwTag + "' applies only to "
                            + StvnVocabulary.TYPE_STRING + ", " + StvnVocabulary.TYPE_SEQ + ", "
                            + StvnVocabulary.TYPE_SET + ", and " + StvnVocabulary.TYPE_MAP
                            + " (permitted facets on " + cleanBase + ": "
                            + StvnMetadataFacetAllowlist.getPermittedFacetsForType(cleanBase, false) + ")",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else if (hasToken(entry, MAX_SIZE_FACET_TOKENS)) {
                boolean isFloat = StvnVocabulary.TYPE_FLOAT.equals(baseType);
                if (isFloat) {
                    if (isExactActive) {
                        // When #exact is active, #maxSize is permitted (precision narrowing/widening)
                        continue;
                    }
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Facet is not permitted on :Float: facet '#maxSize' is prohibited on continuous ':Float'; declare '#exact' or use value interval bounds",
                            StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        inapplicableFix
                    );
                    continue;
                }
                if (!isString && !isCollection) {
                    var cleanBase = StvnMetadataFacetAllowlist.extractCleanBaseType(baseType);
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Facet '" + kwTag + "' is not permitted on " + baseType + "; '" + kwTag + "' applies only to "
                                + StvnVocabulary.TYPE_STRING + ", " + StvnVocabulary.TYPE_SEQ + ", "
                                + StvnVocabulary.TYPE_SET + ", and " + StvnVocabulary.TYPE_MAP
                                + " (permitted facets on " + cleanBase + ": "
                                + StvnMetadataFacetAllowlist.getPermittedFacetsForType(cleanBase, false) + ")",
                            StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        inapplicableFix
                    );
                }
            } else if (hasToken(entry, MAP_FACET_TOKENS) && !isMap) {
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Facet is not permitted on " + baseType + "; permitted facets for map types: [#invertible]",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else if (entry.getMetadataFilter() != null && !isEnum) {
                if (schemaType != null && schemaType.getParent() instanceof ConstantDefinition) {
                    continue; // Handled by visitConstantDefinition
                }
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Filter facets are permitted strictly on nominal aliases of :Enum and enum subsets",
                        StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    inapplicableFix
                );
            } else {
                checkInvalidTraitArgument(entry, holder);
            }
        }
    }

    private static boolean checkIncompleteTraitEntry(MetadataEntry entry, ProblemsHolder holder) {
        var trait = entry.getMetadataTrait();
        if (trait != null && trait.getMetadataValue() == null) {
            var fixes = CompleteTraitFacetQuickFix.createFixes(trait);
            holder.registerProblem(
                trait,
                StvnDiagnosticFormatter.formatPlugin(
                    "Metadata facet '" + StvnVocabulary.FACET_KW_PRESERVE_INDENT
                        + "' requires an explicit boolean value ("
                        + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
                    DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET
                ),
                ProblemHighlightType.GENERIC_ERROR,
                fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
            );
            return true;
        }

        var boolFacet = entry.getMetadataBool();
        if (boolFacet != null && boolFacet.getMetadataValue() == null) {
            String kw = boolFacet.getNode().findChildByType(StvnTypes.KW_EQUATABLE) != null
                ? StvnVocabulary.FACET_KW_EQUATABLE
                : StvnVocabulary.FACET_KW_COMPARABLE;
            var fixes = CompleteTraitFacetQuickFix.createFixes(boolFacet);
            holder.registerProblem(
                boolFacet,
                StvnDiagnosticFormatter.formatPlugin(
                    "Metadata facet '" + kw + "' requires an explicit boolean value ("
                        + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
                    DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET
                ),
                ProblemHighlightType.GENERIC_ERROR,
                fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
            );
            return true;
        }
        return false;
    }

    private static boolean checkInvalidTraitArgument(MetadataEntry entry, ProblemsHolder holder) {
        var trait = entry.getMetadataTrait();
        if (trait != null && trait.getMetadataValue() != null) {
            var mv = trait.getMetadataValue();
            if (mv.getBooleanValue() == null && mv.getValueKeyword() == null
                    && (mv.getStringLiteral() == null
                        || mv.getStringLiteral().getNode().findChildByType(StvnTypes.MALFORMED_FENCE) == null)) {
                holder.registerProblem(
                    mv,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Metadata facet '" + StvnVocabulary.FACET_KW_PRESERVE_INDENT
                            + "' requires an explicit boolean value ("
                            + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
                        DiagnosticBag.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
                return true;
            }
        }
        var boolFacet = entry.getMetadataBool();
        if (boolFacet != null && boolFacet.getMetadataValue() != null) {
            var mv = boolFacet.getMetadataValue();
            if (mv.getBooleanValue() == null && mv.getValueKeyword() == null
                    && (mv.getStringLiteral() == null
                        || mv.getStringLiteral().getNode().findChildByType(StvnTypes.MALFORMED_FENCE) == null)) {
                String kw = boolFacet.getNode().findChildByType(StvnTypes.KW_EQUATABLE) != null
                    ? StvnVocabulary.FACET_KW_EQUATABLE
                    : StvnVocabulary.FACET_KW_COMPARABLE;
                holder.registerProblem(
                    mv,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Metadata facet '" + kw + "' requires an explicit boolean value ("
                            + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
                        DiagnosticBag.ERR_INVALID_METADATA_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
                return true;
            }
        }
        return false;
    }

    private static String resolveOwnerLabel(MetadataMap metaMap, String fallbackBaseType) {
        var typeDef = PsiTreeUtil.getParentOfType(metaMap, TypeDefinition.class);
        if (typeDef != null && typeDef.getTypeKeyword() != null) {
            return typeDef.getTypeKeyword().getText().trim();
        }
        var constDef = PsiTreeUtil.getParentOfType(metaMap, ConstantDefinition.class);
        if (constDef != null && constDef.getValueKeyword() != null) {
            return constDef.getValueKeyword().getText().trim();
        }
        if (PsiTreeUtil.getParentOfType(metaMap, TypeEntry.class) != null) {
            return "<" + StvnVocabulary.KEYWORD_TYPE + ">";
        }
        return fallbackBaseType;
    }

    private static boolean hasLocalPreserveIndentTrue(@Nullable MetadataMap metaMap) {
        if (metaMap == null) return false;
        for (var entry : metaMap.getMetadataEntryList()) {
            if (hasToken(entry, TokenSet.create(StvnTypes.KW_PRESERVE_INDENT))) {
                String text = entry.getText().trim();
                if (text.endsWith(StvnVocabulary.VAL_TRUE) || text.endsWith(StvnVocabulary.VAL_TRUE_SHORT)) {
                    return true;
                }
            }
        }
        return false;
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
        if (trait != null && trait.getNode().findChildByType(tokenSet) != null) {
            return true;
        }
        var boolFacet = entry.getMetadataBool();
        return boolFacet != null && boolFacet.getNode().findChildByType(tokenSet) != null;
    }

    private static boolean isStringType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_STRING)
            || isCanonicalPreludeStringType(baseType);
    }

    private static boolean isCanonicalPreludeStringType(String baseType) {
        return baseType.equals(":org/stvnadore/prelude/UuidLowerCase")
            || baseType.equals(":org/stvnadore/prelude/UuidMixedCase")
            || baseType.equals(":org/stvnadore/prelude/Uuid")
            || baseType.equals(":org/stvnadore/prelude/Ulid")
            || baseType.equals(":org/stvnadore/prelude/Sha256LowerCase")
            || baseType.equals(":org/stvnadore/prelude/Sha256MixedCase")
            || baseType.equals(":org/stvnadore/prelude/Sha256")
            || baseType.equals(":org/stvnadore/prelude/SemVer")
            || baseType.equals(":org/stvnadore/prelude/EmailLowerCase")
            || baseType.equals(":org/stvnadore/prelude/EmailMixedCase")
            || baseType.equals(":org/stvnadore/prelude/Email")
            || baseType.equals(":org/stvnadore/prelude/IPv4")
            || baseType.equals(":UuidLowerCase")
            || baseType.equals(":UuidMixedCase")
            || baseType.equals(":Uuid")
            || baseType.equals(":Ulid")
            || baseType.equals(":Sha256LowerCase")
            || baseType.equals(":Sha256MixedCase")
            || baseType.equals(":Sha256")
            || baseType.equals(":SemVer")
            || baseType.equals(":EmailLowerCase")
            || baseType.equals(":EmailMixedCase")
            || baseType.equals(":Email")
            || baseType.equals(":IPv4");
    }

    private static boolean isCollectionType(String baseType) {
        return isConstructorMatch(baseType, StvnVocabulary.TYPE_SEQ) || isConstructorMatch(baseType, StvnVocabulary.TYPE_SET) || isConstructorMatch(baseType, StvnVocabulary.TYPE_MAP);
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
        return token.startsWith(StvnVocabulary.SIGIL_VALUE) ? token.substring(1) : token;
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

    private static void validatePosixMetaIdentifier(
            @Nullable StringLiteral stringLit,
            @NotNull String facetKeyword,
            @NotNull ProblemsHolder holder
    ) {
        if (stringLit == null || stringLit.getNode().findChildByType(StvnTypes.MALFORMED_FENCE) != null) {
            return;
        }
        String raw = stringLit.getText();
        String unquoted = (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2)
            ? raw.substring(1, raw.length() - 1)
            : raw;
        if (!POSIX_META_ID_PATTERN.matcher(unquoted).matches()) {
            holder.registerProblem(
                stringLit,
                StvnDiagnosticFormatter.formatPlugin(
                    "Metadata " + facetKeyword + " '" + unquoted
                        + "' does not match required atomic POSIX pattern ^[a-zA-Z0-9_-]{1,64}$",
                    StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET
                ),
                ProblemHighlightType.GENERIC_ERROR
            );
        }
    }

    private static boolean isDefsSchemaDocument(@Nullable PsiFile file, @NotNull DefsEntry defsEntry) {
        if (file != null) {
            MetaEntry primaryMeta = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
            var kindFacet = primaryMeta != null ? PsiTreeUtil.findChildOfType(primaryMeta, MetaKindFacet.class) : null;
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                return StvnVocabulary.KIND_KW_DEFS.equals(kindText)
                    || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindText);
            }
            String name = file.getName();
            if (name.endsWith(".stvn_d") || name.endsWith(".stvn_di") || name.endsWith(".stvn_bd")) {
                return true;
            }
            if (name.endsWith(".stvn") || name.endsWith(".stvn_i") || name.endsWith(".stvn_b")) {
                return false;
            }
            return PsiTreeUtil.findChildOfType(file, BodyEntry.class) == null
                && PsiTreeUtil.findChildOfType(file, TypeEntry.class) == null;
        }
        return false;
    }

    private static final class RemoveParentMetaEntryQuickFix implements LocalQuickFix {
        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Remove empty '" + StvnVocabulary.KEYWORD_META + " {}' section";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null || !element.isValid()) {
                return;
            }
            MetaEntry metaEntry = element instanceof MetaEntry me
                ? me
                : PsiTreeUtil.getParentOfType(element, MetaEntry.class, false);
            PsiElement toDelete = metaEntry != null ? metaEntry : element;
            var prev = toDelete.getPrevSibling();
            if (prev instanceof PsiWhiteSpace) {
                prev.delete();
            }
            toDelete.delete();
        }
    }

    private static final class InsertDefaultTypeDefinitionQuickFix implements LocalQuickFix {
        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Insert sample type definition in " + StvnVocabulary.KEYWORD_DEFS;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null || !element.isValid()) {
                return;
            }
            DefsEntry defsEntry = element instanceof DefsEntry de
                ? de
                : PsiTreeUtil.getParentOfType(element, DefsEntry.class, false);
            if (defsEntry == null) {
                return;
            }
            var lbrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
            if (lbrace == null) {
                return;
            }
            PsiFile file = defsEntry.getContainingFile();
            if (file == null) {
                return;
            }
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) {
                return;
            }
            int insertOffset = lbrace.getStartOffset() + 1;
            doc.insertString(
                insertOffset,
                "\n    " + StvnVocabulary.SIGIL_TYPIC + "SampleType " + StvnVocabulary.TYPE_INT + "\n  "
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
            if (defsEntry.isValid()) {
                com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(defsEntry);
            }
        }
    }

    private static final class RemoveElementQuickFix implements LocalQuickFix {
        private final String name;
        private final String familyName;

        RemoveElementQuickFix(String familyName) {
            this(familyName, familyName);
        }

        RemoveElementQuickFix(String name, String familyName) {
            this.name = name;
            this.familyName = familyName;
        }

        @Override
        public @Nls @NotNull String getName() {
            return name;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return familyName;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element != null && element.isValid()) {
                var metaMap = PsiTreeUtil.getParentOfType(element, MetadataMap.class, false);
                var targetEntry = element instanceof MetadataEntry me ? me : PsiTreeUtil.getParentOfType(element, MetadataEntry.class, false);
                if (metaMap != null && targetEntry != null) {
                    var entries = metaMap.getMetadataEntryList();
                    if (entries.size() <= 1) {
                        var prev = metaMap.getPrevSibling();
                        if (prev instanceof PsiWhiteSpace) {
                            prev.delete();
                        }
                        metaMap.delete();
                        return;
                    }
                }
                var targetMetaElement = element instanceof MetaElement me
                    ? me
                    : PsiTreeUtil.getParentOfType(element, MetaElement.class, false);
                var toDelete = targetEntry != null
                    ? targetEntry
                    : (targetMetaElement != null ? targetMetaElement : element);
                var next = toDelete.getNextSibling();
                var prev = toDelete.getPrevSibling();
                if (next instanceof PsiWhiteSpace && !(prev instanceof PsiWhiteSpace)) {
                    next.delete();
                } else if (prev instanceof PsiWhiteSpace) {
                    if (!(toDelete instanceof MetadataEntry) || prev.getPrevSibling() instanceof MetadataEntry) {
                        prev.delete();
                    }
                }
                toDelete.delete();
            }
        }
    }

    private static LocalQuickFix[] buildInvalidFacetQuickFixes(
            LocalQuickFix removeFix,
            @Nullable SchemaType schemaType,
            @Nullable String suggestedBaseType
    ) {
        boolean canChangeBaseType = suggestedBaseType != null
            && schemaType != null
            && ((schemaType.getSchemaConstructor() != null && schemaType.getSchemaConstructor().getAtomicType() != null)
                || schemaType.getTypeKeyword() != null);
        if (canChangeBaseType) {
            return new LocalQuickFix[]{
                removeFix,
                new ChangeBaseTypeQuickFix(suggestedBaseType)
            };
        }
        return new LocalQuickFix[]{removeFix};
    }

    private static final class ChangeBaseTypeQuickFix implements LocalQuickFix {
        private final String targetBaseType;

        ChangeBaseTypeQuickFix(String targetBaseType) {
            this.targetBaseType = targetBaseType;
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Change base type to '" + targetBaseType + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Change STVN base type";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null || !element.isValid()) {
                return;
            }
            var schemaType = resolveEnclosingSchemaType(element);
            if (schemaType == null) {
                return;
            }
            var replacementSchema = StvnElementFactory.createSchemaType(project, targetBaseType);
            var replacementCtor = replacementSchema.getSchemaConstructor();
            if (schemaType.getSchemaConstructor() != null && replacementCtor != null) {
                schemaType.getSchemaConstructor().replace(replacementCtor);
            } else if (schemaType.getTypeKeyword() != null && replacementCtor != null) {
                schemaType.getTypeKeyword().replace(replacementCtor);
            }
        }

        private static @Nullable SchemaType resolveEnclosingSchemaType(PsiElement element) {
            var schemaType = PsiTreeUtil.getParentOfType(element, SchemaType.class, false);
            if (schemaType != null) {
                return schemaType;
            }
            var typeDef = PsiTreeUtil.getParentOfType(element, TypeDefinition.class, false);
            if (typeDef != null) {
                return typeDef.getSchemaType();
            }
            var constDef = PsiTreeUtil.getParentOfType(element, ConstantDefinition.class, false);
            return constDef != null ? constDef.getSchemaType() : null;
        }
    }
}
