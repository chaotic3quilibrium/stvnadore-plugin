package org.stvnadore.plugin.browser;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnDefinitionsFile;
import org.stvnadore.plugin.StvnDefsIncludeFile;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.CollectionValue;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.ExplicitEitherValue;
import org.stvnadore.psi.ExplicitOptionValue;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects 5-column namespace dependency closures across {@code :defs}, {@code :type}, and {@code :body} scopes.
 */
@NullMarked
public final class StvnNamespaceSymbolCollector {

    /**
     * Immutable precomputed snapshot of all three namespace dependency scopes, standard library prelude
     * entries, and root section availability flags for instant EDT switching.
     *
     * @param entriesByScope map from {@link StvnNamespaceScope} to ordered symbol entries
     * @param preludeEntries canonical standard library prelude type and constant entries
     * @param hasTypeSection {@code true} when the document declares a primary root {@code :type} section
     * @param hasBodySection {@code true} when the document declares a primary root {@code :body} section
     */
    public record StvnNamespaceSnapshot(
        Map<StvnNamespaceScope, List<StvnNamespaceSymbolEntry>> entriesByScope,
        List<StvnNamespaceSymbolEntry> preludeEntries,
        boolean hasTypeSection,
        boolean hasBodySection
    ) {
        /**
         * Resolves the effective initial scope, falling back cleanly when {@code :type} or {@code :body} is absent.
         *
         * @param requestedScope initial scope requested by caret position or gutter icon
         * @return valid available {@link StvnNamespaceScope}
         */
        public StvnNamespaceScope resolveAvailableScope(StvnNamespaceScope requestedScope) {
            if (requestedScope == StvnNamespaceScope.BODY && !hasBodySection) {
                return hasTypeSection ? StvnNamespaceScope.TYPE : StvnNamespaceScope.DEFS;
            }
            if (requestedScope == StvnNamespaceScope.TYPE && !hasTypeSection) {
                return StvnNamespaceScope.DEFS;
            }
            return requestedScope;
        }
    }

    private record ProjectedBinding(
        String localName,
        String sourcePackage,
        String upstreamTargetFqni,
        PsiElement localBindingElement
    ) {
    }

    private StvnNamespaceSymbolCollector() {
    }

    /**
     * Precomputes all three scopes ({@code :defs}, {@code :type}, {@code :body}), standard library prelude
     * symbols, and section availability flags inside a single read action.
     *
     * @param project active IntelliJ project
     * @param file    source STVN file
     * @return immutable {@link StvnNamespaceSnapshot}
     */
    public static StvnNamespaceSnapshot collectSnapshot(Project project, PsiFile file) {
        ProgressManager.checkCanceled();
        return ReadAction.compute(() -> {
            boolean defsOnlyModule = isDefsModule(file);
            boolean hasType = !defsOnlyModule && findPrimaryTypeEntry(file) != null;
            boolean hasBody = !defsOnlyModule && findPrimaryBodyEntry(file) != null;
            Map<StvnNamespaceScope, List<StvnNamespaceSymbolEntry>> byScope = new EnumMap<>(StvnNamespaceScope.class);
            byScope.put(StvnNamespaceScope.DEFS, List.copyOf(collectDefsSymbols(file)));
            ProgressManager.checkCanceled();
            byScope.put(StvnNamespaceScope.TYPE, hasType ? List.copyOf(collectTypeSymbols(file)) : List.of());
            ProgressManager.checkCanceled();
            byScope.put(StvnNamespaceScope.BODY, hasBody ? List.copyOf(collectBodySymbols(file)) : List.of());
            ProgressManager.checkCanceled();
            List<StvnNamespaceSymbolEntry> prelude = collectPreludeSymbolsInternal(project);
            return new StvnNamespaceSnapshot(Map.copyOf(byScope), prelude, hasType, hasBody);
        });
    }

    /**
     * Collects all symbols matching the specified section scope from the STVN file.
     *
     * @param file  the source STVN file
     * @param scope the target namespace scope
     * @return ordered list of 5-column symbol entries
     */
    public static List<StvnNamespaceSymbolEntry> collectSymbols(PsiFile file, StvnNamespaceScope scope) {
        ProgressManager.checkCanceled();
        return ReadAction.compute(() -> switch (scope) {
            case DEFS -> List.copyOf(collectDefsSymbols(file));
            case TYPE -> List.copyOf(collectTypeSymbols(file));
            case BODY -> List.copyOf(collectBodySymbols(file));
        });
    }

    /**
     * Collects all canonical standard library prelude symbols from {@code stvnadore-core}.
     *
     * @param project the active project
     * @return unmodifiable list of prelude symbol entries
     */
    public static List<StvnNamespaceSymbolEntry> collectPreludeSymbols(Project project) {
        ProgressManager.checkCanceled();
        return ReadAction.compute(() -> collectPreludeSymbolsInternal(project));
    }

    private static List<StvnNamespaceSymbolEntry> collectPreludeSymbolsInternal(Project project) {
        var preludeFile = StvnPreludeBridge.getPreludeFile(project);
        var results = new ArrayList<StvnNamespaceSymbolEntry>();
        Map<PsiElement, Integer> preludeGroundingMemo = new IdentityHashMap<>();
        for (var def : PsiTreeUtil.findChildrenOfType(preludeFile, TypeDefinition.class)) {
            ProgressManager.checkCanceled();
            var kw = def.getTypeKeyword();
            if (kw != null) {
                var fullText = kw.getText().trim();
                var source = StvnPreludeBridge.getPreludePackagePath(def);
                var fqni = fullText.contains("/") ? fullText : source + "/" + stripColon(fullText);
                String resolvedType = resolveFormattedTypeStructure(def, new HashSet<>());
                int depth = computeVopGroundingDepthForDeclaration(def, preludeGroundingMemo);
                results.add(new StvnNamespaceSymbolEntry(
                    fqni,
                    source,
                    StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                    resolvedType,
                    depth,
                    kw,
                    true,
                    kw.getTextOffset(),
                    StvnNamespaceScope.DEFS
                ));
            }
        }
        for (var cDef : PsiTreeUtil.findChildrenOfType(preludeFile, ConstantDefinition.class)) {
            ProgressManager.checkCanceled();
            var kw = cDef.getValueKeyword();
            if (kw != null) {
                var fullText = kw.getText().trim();
                var source = StvnPreludeBridge.getPreludePackagePath(cDef);
                var fqni = buildConstantFqni(source, fullText);
                String resolvedType = formatConstantResolvedType(cDef, preludeFile);
                int depth = computeVopGroundingDepthForDeclaration(cDef, preludeGroundingMemo);
                results.add(new StvnNamespaceSymbolEntry(
                    fqni,
                    source,
                    StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                    resolvedType,
                    depth,
                    kw,
                    true,
                    kw.getTextOffset(),
                    StvnNamespaceScope.DEFS
                ));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Maps a canonical packaged type FQNI to its active root-scope {@code :use} local name,
     * honoring {@link UseMapAlias} eviction ahead of {@code #strip}.
     *
     * @param file the active PSI file
     * @param fqni the candidate type identifier
     * @return the local {@code :use} alias, stripped bare name, or original {@code fqni}
     */
    static String resolveLocalImportedTypeName(PsiFile file, String fqni) {
        var binding = findRootUseBindingForTypeFqni(file, fqni);
        return binding != null ? binding.localName() : fqni;
    }

    private static @Nullable ProjectedBinding findRootUseBindingForTypeFqni(PsiFile file, String fqni) {
        if (!fqni.contains("/")) {
            return null;
        }
        int lastSlash = fqni.lastIndexOf('/');
        String pkgPath = fqni.substring(0, lastSlash);
        String rawShort = StvnVocabulary.SIGIL_TYPIC + fqni.substring(lastSlash + 1);

        ProjectedBinding strippedCandidate = null;
        for (var use : PsiTreeUtil.findChildrenOfType(file, UseStmt.class)) {
            if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null
                    || StvnMetaTemplateUtil.isInSecondaryRootSection(use)) {
                continue;
            }
            for (var useElem : use.getUseElementList()) {
                var target = useElem.getUseTarget();
                if (target == null || target.getUseTargetIllegal() != null || !target.getText().trim().equals(pkgPath)) {
                    continue;
                }
                boolean evictedInThisElem = false;
                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var list = alias.getTypeKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            var localKw = list.get(1);
                            if (remoteKw != null) {
                                String remoteText = remoteKw.getText().trim();
                                if (remoteText.equals(rawShort) || remoteText.equals(fqni)) {
                                    evictedInThisElem = true;
                                    if (localKw != null) {
                                        return new ProjectedBinding(
                                            localKw.getText().trim(),
                                            pkgPath,
                                            fqni,
                                            localKw
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
                if (!evictedInThisElem && StvnPsiUtils.hasValidStripOption(useElem.getUseOptionsBlock())) {
                    strippedCandidate = new ProjectedBinding(rawShort, pkgPath, fqni, useElem);
                }
            }
        }
        return strippedCandidate;
    }

    private static @Nullable ProjectedBinding findRootUseBindingForConstantFqni(PsiFile file, String constFqni) {
        if (!constFqni.contains("/")) {
            return null;
        }
        int lastSlash = constFqni.lastIndexOf('/');
        String pkgBody = stripHash(constFqni.substring(0, lastSlash));
        String pkgPath = StvnVocabulary.SIGIL_TYPIC + pkgBody;
        String rawShort = StvnVocabulary.SIGIL_VALUE + constFqni.substring(lastSlash + 1);

        ProjectedBinding strippedCandidate = null;
        for (var use : PsiTreeUtil.findChildrenOfType(file, UseStmt.class)) {
            if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null
                    || StvnMetaTemplateUtil.isInSecondaryRootSection(use)) {
                continue;
            }
            for (var useElem : use.getUseElementList()) {
                var target = useElem.getUseTarget();
                if (target == null || target.getUseTargetIllegal() != null || !target.getText().trim().equals(pkgPath)) {
                    continue;
                }
                boolean evictedInThisElem = false;
                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var valList = alias.getValueKeywordList();
                        if (valList.size() >= 2) {
                            var remoteKw = valList.get(0);
                            var localKw = valList.get(1);
                            if (remoteKw != null) {
                                String remoteText = remoteKw.getText().trim();
                                if (remoteText.equals(rawShort) || remoteText.equals(constFqni)) {
                                    evictedInThisElem = true;
                                    if (localKw != null) {
                                        return new ProjectedBinding(
                                            localKw.getText().trim(),
                                            pkgPath,
                                            constFqni,
                                            localKw
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
                if (!evictedInThisElem && StvnPsiUtils.hasValidStripOption(useElem.getUseOptionsBlock())) {
                    strippedCandidate = new ProjectedBinding(rawShort, pkgPath, constFqni, useElem);
                }
            }
        }
        return strippedCandidate;
    }

    private static List<StvnNamespaceSymbolEntry> collectDefsSymbols(PsiFile file) {
        var results = new ArrayList<StvnNamespaceSymbolEntry>();
        var fileName = file.getName();
        var processedNames = new HashSet<String>();
        Map<PsiElement, Integer> groundingMemo = new IdentityHashMap<>();

        boolean hasRootDeclsOrDirectives = false;

        // 1. Direct root-level TypeDefinitions in :defs (outside PackageEnclosure)
        for (var def : PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class)) {
            if (PsiTreeUtil.getParentOfType(def, PackageEnclosure.class) != null
                    || StvnMetaTemplateUtil.isInSecondaryRootSection(def)) {
                continue;
            }
            hasRootDeclsOrDirectives = true;
            var kw = def.getTypeKeyword();
            if (kw != null) {
                String name = kw.getText().trim();
                if (processedNames.add(name)) {
                    String resolvedType = resolveFormattedTypeStructure(def, new HashSet<>());
                    int depth = computeVopGroundingDepthForDeclaration(def, groundingMemo);
                    results.add(new StvnNamespaceSymbolEntry(
                        name,
                        fileName,
                        StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                        resolvedType,
                        depth,
                        kw,
                        false,
                        kw.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
        }

        // 2. Direct root-level ConstantDefinitions in :defs (outside PackageEnclosure)
        for (var def : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
            if (PsiTreeUtil.getParentOfType(def, PackageEnclosure.class) != null
                    || StvnMetaTemplateUtil.isInSecondaryRootSection(def)) {
                continue;
            }
            hasRootDeclsOrDirectives = true;
            var kw = def.getValueKeyword();
            if (kw != null) {
                String name = kw.getText().trim();
                if (processedNames.add(name)) {
                    String resolvedType = formatConstantResolvedType(def, file);
                    int depth = computeVopGroundingDepthForDeclaration(def, groundingMemo);
                    results.add(new StvnNamespaceSymbolEntry(
                        name,
                        fileName,
                        StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                        resolvedType,
                        depth,
                        kw,
                        false,
                        kw.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
        }

        // 3. Root-level :use projections (enforcing STVN § 4.2 eviction; un-imported :package internals excluded)
        for (var use : PsiTreeUtil.findChildrenOfType(file, UseStmt.class)) {
            if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null
                    || StvnMetaTemplateUtil.isInSecondaryRootSection(use)) {
                continue;
            }
            hasRootDeclsOrDirectives = true;
            for (var useElem : use.getUseElementList()) {
                var target = useElem.getUseTarget();
                if (target == null || target.getUseTargetIllegal() != null) {
                    continue;
                }
                String targetPkg = target.getText().trim();
                var evictedRawNames = new HashSet<String>();
                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var typeList = alias.getTypeKeywordList();
                        if (typeList.size() >= 2) {
                            var remoteKw = typeList.get(0);
                            var localKw = typeList.get(1);
                            if (remoteKw != null) {
                                evictedRawNames.add(remoteKw.getText().trim());
                            }
                            if (localKw != null && remoteKw != null) {
                                String localName = localKw.getText().trim();
                                String upstreamFqni = qualifyTypeWithPackage(targetPkg, remoteKw.getText().trim());
                                if (processedNames.add(localName)) {
                                    String resolvedType = resolveImportedTypeStructure(file, upstreamFqni);
                                    TypeDefinition upstreamTd = resolveUpstreamTypeForUseAlias(file, remoteKw, upstreamFqni);
                                    int aliasDepth = upstreamTd != null
                                        ? computeVopGroundingDepthForDeclaration(upstreamTd, groundingMemo)
                                        : 0;
                                    results.add(new StvnNamespaceSymbolEntry(
                                        localName,
                                        targetPkg,
                                        upstreamFqni,
                                        resolvedType,
                                        aliasDepth,
                                        localKw,
                                        false,
                                        localKw.getTextOffset(),
                                        StvnNamespaceScope.DEFS
                                    ));
                                }
                            }
                        }
                        var valList = alias.getValueKeywordList();
                        if (valList.size() >= 2) {
                            var remoteKw = valList.get(0);
                            var localKw = valList.get(1);
                            if (remoteKw != null) {
                                evictedRawNames.add(remoteKw.getText().trim());
                            }
                            if (localKw != null && remoteKw != null) {
                                String localName = localKw.getText().trim();
                                String upstreamConstFqni = buildConstantFqni(targetPkg, remoteKw.getText().trim());
                                if (processedNames.add(localName)) {
                                    String resolvedType = resolveImportedConstantType(file, targetPkg, remoteKw.getText().trim());
                                    ConstantDefinition upstreamCd = resolveUpstreamConstForUseAlias(
                                        file,
                                        remoteKw,
                                        upstreamConstFqni
                                    );
                                    int constDepth = upstreamCd != null
                                        ? computeVopGroundingDepthForDeclaration(upstreamCd, groundingMemo)
                                        : 0;
                                    results.add(new StvnNamespaceSymbolEntry(
                                        localName,
                                        targetPkg,
                                        upstreamConstFqni,
                                        resolvedType,
                                        constDepth,
                                        localKw,
                                        false,
                                        localKw.getTextOffset(),
                                        StvnNamespaceScope.DEFS
                                    ));
                                }
                            }
                        }
                    }
                }
                if (StvnPsiUtils.hasValidStripOption(useElem.getUseOptionsBlock())) {
                    var scopedProcessed = new HashSet<>(processedNames);
                    scopedProcessed.addAll(evictedRawNames);
                    int before = results.size();
                    collectStrippedPackageSymbols(file, targetPkg, scopedProcessed, results, useElem, groundingMemo);
                    for (int i = before; i < results.size(); i++) {
                        processedNames.add(results.get(i).name());
                    }
                }
            }
        }

        // 4. Transitive unpackaged root declarations from :include modules
        var includedFiles = collectTransitiveIncludedFiles(file);
        if (!includedFiles.isEmpty()) {
            hasRootDeclsOrDirectives = true;
        }
        for (var targetFile : includedFiles) {
            var includeSource = targetFile.getName();
            for (var rDef : PsiTreeUtil.findChildrenOfType(targetFile, TypeDefinition.class)) {
                if (PsiTreeUtil.getParentOfType(rDef, PackageEnclosure.class) != null
                        || StvnMetaTemplateUtil.isInSecondaryRootSection(rDef)) {
                    continue;
                }
                var kw = rDef.getTypeKeyword();
                if (kw != null && processedNames.add(kw.getText().trim())) {
                    results.add(new StvnNamespaceSymbolEntry(
                        kw.getText().trim(),
                        includeSource,
                        StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                        resolveFormattedTypeStructure(rDef, new HashSet<>()),
                        computeVopGroundingDepthForDeclaration(rDef, groundingMemo),
                        kw,
                        false,
                        kw.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
            for (var rConst : PsiTreeUtil.findChildrenOfType(targetFile, ConstantDefinition.class)) {
                if (PsiTreeUtil.getParentOfType(rConst, PackageEnclosure.class) != null
                        || StvnMetaTemplateUtil.isInSecondaryRootSection(rConst)) {
                    continue;
                }
                var kw = rConst.getValueKeyword();
                if (kw != null && processedNames.add(kw.getText().trim())) {
                    results.add(new StvnNamespaceSymbolEntry(
                        kw.getText().trim(),
                        includeSource,
                        StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                        formatConstantResolvedType(rConst, targetFile),
                        computeVopGroundingDepthForDeclaration(rConst, groundingMemo),
                        kw,
                        false,
                        kw.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
        }

        // 5. Pure #DEFS / #DEFS_INCLUDE library schemas declaring only exported :package blocks
        boolean isPureDefsCatalog = isDefsModule(file)
            || (findPrimaryTypeEntry(file) == null && findPrimaryBodyEntry(file) == null);
        if (isPureDefsCatalog && !hasRootDeclsOrDirectives) {
            for (var pkg : PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class)) {
                if (StvnPsiUtils.hasIllegalTrailingSlash(pkg)
                        || StvnMetaTemplateUtil.isInSecondaryRootSection(pkg)) {
                    continue;
                }
                var path = pkg.getPackagePath();
                String pathText = path != null ? path.getText().trim() : StvnVocabulary.KEYWORD_PACKAGE;
                for (var elem : pkg.getPackageElementList()) {
                    var pkgTypeDef = elem.getTypeDefinition();
                    if (pkgTypeDef != null && pkgTypeDef.getTypeKeyword() != null) {
                        var kw = pkgTypeDef.getTypeKeyword();
                        String fqni = pathText + "/" + stripColon(kw.getText().trim());
                        if (processedNames.add(fqni)) {
                            results.add(new StvnNamespaceSymbolEntry(
                                fqni,
                                pathText,
                                StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                                resolveFormattedTypeStructure(pkgTypeDef, new HashSet<>()),
                                computeVopGroundingDepthForDeclaration(pkgTypeDef, groundingMemo),
                                kw,
                                false,
                                kw.getTextOffset(),
                                StvnNamespaceScope.DEFS
                            ));
                        }
                    }
                    var pkgConstDef = elem.getConstantDefinition();
                    if (pkgConstDef != null && pkgConstDef.getValueKeyword() != null) {
                        var kw = pkgConstDef.getValueKeyword();
                        String fqni = buildConstantFqni(pathText, kw.getText().trim());
                        if (processedNames.add(fqni)) {
                            results.add(new StvnNamespaceSymbolEntry(
                                fqni,
                                pathText,
                                StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                                formatConstantResolvedType(pkgConstDef, file),
                                computeVopGroundingDepthForDeclaration(pkgConstDef, groundingMemo),
                                kw,
                                false,
                                kw.getTextOffset(),
                                StvnNamespaceScope.DEFS
                            ));
                        }
                    }
                }
            }
        }

        return results;
    }

    private sealed interface ClosureTask {
        int nextSymbolDepth();

        long seq();

        record SchemaTask(SchemaType schema, int nextSymbolDepth, PsiFile contextFile, long seq)
            implements ClosureTask {
        }

        record ConstantTask(ValueKeyword constRef, int nextSymbolDepth, PsiFile contextFile, long seq)
            implements ClosureTask {
        }
    }

    private record UnwrappedSchemaConstructor(
        SchemaConstructor ctor,
        PsiFile contextFile,
        int nextSymbolDepth
    ) {
    }

    private static final Comparator<ClosureTask> CLOSURE_TASK_ORDER =
        Comparator.comparingInt(ClosureTask::nextSymbolDepth)
            .thenComparingLong(ClosureTask::seq);

    private static boolean isDefsModule(PsiFile file) {
        if (file instanceof StvnDefinitionsFile || file instanceof StvnDefsIncludeFile) {
            return true;
        }
        String fileName = file.getName();
        if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_di") || fileName.endsWith(".stvn_bd")) {
            return true;
        }
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                continue;
            }
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                return StvnVocabulary.KIND_KW_DEFS.equals(kindText)
                    || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindText);
            }
            break;
        }
        return false;
    }

    private static int resolveInitialTypeSeedDepth(SchemaType rootTypeSchema) {
        var kw = rootTypeSchema.getTypeKeyword();
        if (kw != null && !StvnVocabulary.isBaseScalarType(kw.getText().trim())) {
            return 0;
        }
        return 1;
    }

    private static List<StvnNamespaceSymbolEntry> collectTypeSymbols(PsiFile rootFile) {
        var typeEntry = findPrimaryTypeEntry(rootFile);
        if (typeEntry == null || typeEntry.getSchemaType() == null) {
            return List.of();
        }
        SchemaType rootSchema = typeEntry.getSchemaType();
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey = new LinkedHashMap<>();
        PriorityQueue<ClosureTask> queue = new PriorityQueue<>(CLOSURE_TASK_ORDER);
        AtomicLong seqGen = new AtomicLong();
        queue.add(new ClosureTask.SchemaTask(
            rootSchema,
            resolveInitialTypeSeedDepth(rootSchema),
            rootFile,
            seqGen.getAndIncrement()
        ));
        runReachabilityClosure(rootFile, StvnNamespaceScope.TYPE, queue, seqGen, byIdentityKey);
        return new ArrayList<>(byIdentityKey.values());
    }

    private static List<StvnNamespaceSymbolEntry> collectBodySymbols(PsiFile rootFile) {
        var bodyEntry = findPrimaryBodyEntry(rootFile);
        if (bodyEntry == null || bodyEntry.getValue() == null) {
            return List.of();
        }
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey = new LinkedHashMap<>();
        PriorityQueue<ClosureTask> queue = new PriorityQueue<>(CLOSURE_TASK_ORDER);
        AtomicLong seqGen = new AtomicLong();

        var typeEntry = findPrimaryTypeEntry(rootFile);
        SchemaType rootTypeSchema = typeEntry != null ? typeEntry.getSchemaType() : null;
        if (rootTypeSchema != null) {
            queue.add(new ClosureTask.SchemaTask(
                rootTypeSchema,
                resolveInitialTypeSeedDepth(rootTypeSchema),
                rootFile,
                seqGen.getAndIncrement()
            ));
        }

        Value rootBodyVal = bodyEntry.getValue();
        Map<ValueKeyword, Integer> seededConstants = new LinkedHashMap<>();
        int initialBodySlotDepth = rootTypeSchema != null ? resolveInitialTypeSeedDepth(rootTypeSchema) : 1;
        collectBodyConstantSeeds(
            rootFile,
            rootFile,
            rootBodyVal,
            rootTypeSchema,
            initialBodySlotDepth,
            seededConstants
        );
        for (ValueKeyword valKw : PsiTreeUtil.findChildrenOfType(rootBodyVal, ValueKeyword.class)) {
            if (!isNonConstantValueKeyword(valKw)) {
                seededConstants.putIfAbsent(valKw, 1);
            }
        }
        for (var constSeed : seededConstants.entrySet()) {
            queue.add(new ClosureTask.ConstantTask(
                constSeed.getKey(),
                constSeed.getValue(),
                rootFile,
                seqGen.getAndIncrement()
            ));
        }

        runReachabilityClosure(rootFile, StvnNamespaceScope.BODY, queue, seqGen, byIdentityKey);
        return new ArrayList<>(byIdentityKey.values());
    }

    private static void runReachabilityClosure(
        PsiFile rootFile,
        StvnNamespaceScope targetScope,
        PriorityQueue<ClosureTask> queue,
        AtomicLong seqGen,
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey
    ) {
        Map<PsiElement, Integer> bestSchemaDepth = new HashMap<>();
        Map<PsiElement, Integer> bestDeclDepth = new HashMap<>();

        while (!queue.isEmpty()) {
            ProgressManager.checkCanceled();
            ClosureTask task = queue.poll();
            if (task instanceof ClosureTask.SchemaTask schemaTask) {
                processSchemaClosureTask(
                    rootFile,
                    targetScope,
                    schemaTask.schema(),
                    schemaTask.nextSymbolDepth(),
                    schemaTask.contextFile(),
                    queue,
                    seqGen,
                    byIdentityKey,
                    bestSchemaDepth,
                    bestDeclDepth
                );
            } else if (task instanceof ClosureTask.ConstantTask constTask) {
                processConstantClosureTask(
                    rootFile,
                    targetScope,
                    constTask.constRef(),
                    constTask.nextSymbolDepth(),
                    queue,
                    seqGen,
                    byIdentityKey,
                    bestDeclDepth
                );
            }
        }
    }

    private static boolean isBetterDepth(
        Map<PsiElement, Integer> bestDepthMap,
        PsiElement element,
        int candidateDepth
    ) {
        Integer prev = bestDepthMap.get(element);
        if (prev == null || candidateDepth < prev) {
            bestDepthMap.put(element, candidateDepth);
            return true;
        }
        return false;
    }

    private static void processSchemaClosureTask(
        PsiFile rootFile,
        StvnNamespaceScope targetScope,
        SchemaType currentSchema,
        int currentDepth,
        PsiFile contextFile,
        PriorityQueue<ClosureTask> queue,
        AtomicLong seqGen,
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey,
        Map<PsiElement, Integer> bestSchemaDepth,
        Map<PsiElement, Integer> bestDeclDepth
    ) {
        if (!isBetterDepth(bestSchemaDepth, currentSchema, currentDepth)) {
            return;
        }
        enqueueMetadataFacetConstants(
            currentSchema.getMetadataMap(),
            Math.max(1, currentDepth),
            contextFile,
            queue,
            seqGen
        );

        var kw = currentSchema.getTypeKeyword();
        if (kw != null) {
            String rawRef = kw.getText().trim();
            if (StvnVocabulary.isBaseScalarType(rawRef)) {
                return;
            }
            ResolvedTypeSymbol resolvedSym = resolveTypeSymbolForClosure(rootFile, contextFile, kw);
            if (resolvedSym == null) {
                return;
            }
            recordEntryWithMinDepth(
                byIdentityKey,
                resolvedSym.identityKey(),
                new StvnNamespaceSymbolEntry(
                    resolvedSym.displayName(),
                    resolvedSym.source(),
                    resolvedSym.upstreamTarget(),
                    resolvedSym.resolvedType(),
                    currentDepth,
                    resolvedSym.navigationTarget(),
                    resolvedSym.isPrelude(),
                    resolvedSym.navigationTarget().getTextOffset(),
                    targetScope
                )
            );

            TypeDefinition targetTypeDef = resolvedSym.underlyingTypeDef();
            if (targetTypeDef != null && !resolvedSym.isPrelude()
                    && isBetterDepth(bestDeclDepth, targetTypeDef, currentDepth)) {
                PsiFile defFile = targetTypeDef.getContainingFile() != null
                    ? targetTypeDef.getContainingFile()
                    : contextFile;
                int childSymbolDepth = currentDepth + 1;
                enqueueMetadataFacetConstants(
                    targetTypeDef.getMetadataMap(),
                    childSymbolDepth,
                    defFile,
                    queue,
                    seqGen
                );
                SchemaType rhsSchema = targetTypeDef.getSchemaType();
                if (rhsSchema != null) {
                    queue.add(new ClosureTask.SchemaTask(
                        rhsSchema,
                        childSymbolDepth,
                        defFile,
                        seqGen.getAndIncrement()
                    ));
                }
            }
            return;
        }

        var ctor = currentSchema.getSchemaConstructor();
        if (ctor != null) {
            // Inline anonymous constructors (:Tuple, :Seq, :Set, :Map, :Option, :Either, :Union) add +0 hop cost
            for (SchemaType child : collectDirectChildSchemas(ctor)) {
                queue.add(new ClosureTask.SchemaTask(
                    child,
                    currentDepth,
                    contextFile,
                    seqGen.getAndIncrement()
                ));
            }
        }
    }

    private static void processConstantClosureTask(
        PsiFile rootFile,
        StvnNamespaceScope targetScope,
        ValueKeyword constRef,
        int currentDepth,
        PriorityQueue<ClosureTask> queue,
        AtomicLong seqGen,
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey,
        Map<PsiElement, Integer> bestDeclDepth
    ) {
        var resolvedElem = StvnConstantReference.resolveConstantWithContext(constRef);
        ConstantDefinition constDef = resolvedElem != null
            ? StvnPsiUtils.resolveTargetConstantDefinition(resolvedElem)
            : StvnTypeResolver.resolveContextAwareConstantDefinition(constRef);
        if (constDef == null || constDef.getValueKeyword() == null) {
            return;
        }
        ResolvedConstantSymbol resolvedConst = resolveConstantSymbolForClosure(rootFile, constDef, resolvedElem);
        recordEntryWithMinDepth(
            byIdentityKey,
            resolvedConst.identityKey(),
            new StvnNamespaceSymbolEntry(
                resolvedConst.displayName(),
                resolvedConst.source(),
                resolvedConst.upstreamTarget(),
                resolvedConst.resolvedType(),
                currentDepth,
                resolvedConst.navigationTarget(),
                resolvedConst.isPrelude(),
                resolvedConst.navigationTarget().getTextOffset(),
                targetScope
            )
        );

        if (!resolvedConst.isPrelude() && isBetterDepth(bestDeclDepth, constDef, currentDepth)) {
            PsiFile defFile = constDef.getContainingFile() != null ? constDef.getContainingFile() : rootFile;
            int childSymbolDepth = currentDepth + 1;
            enqueueMetadataFacetConstants(constDef.getMetadataMap(), childSymbolDepth, defFile, queue, seqGen);
            if (constDef.getSchemaType() != null) {
                queue.add(new ClosureTask.SchemaTask(
                    constDef.getSchemaType(),
                    childSymbolDepth,
                    defFile,
                    seqGen.getAndIncrement()
                ));
            }
            if (constDef.getValue() != null) {
                for (ValueKeyword nestedKw : PsiTreeUtil.findChildrenOfType(constDef.getValue(), ValueKeyword.class)) {
                    if (!isNonConstantValueKeyword(nestedKw)) {
                        queue.add(new ClosureTask.ConstantTask(
                            nestedKw,
                            childSymbolDepth,
                            defFile,
                            seqGen.getAndIncrement()
                        ));
                    }
                }
            }
        }
    }

    private static void enqueueMetadataFacetConstants(
        @Nullable MetadataMap metadataMap,
        int depth,
        PsiFile contextFile,
        PriorityQueue<ClosureTask> queue,
        AtomicLong seqGen
    ) {
        if (metadataMap == null) {
            return;
        }
        for (var entry : metadataMap.getMetadataEntryList()) {
            var mv = entry.getMetadataValue();
            if (mv != null && mv.getValueKeyword() != null && !isNonConstantValueKeyword(mv.getValueKeyword())) {
                queue.add(new ClosureTask.ConstantTask(
                    mv.getValueKeyword(),
                    depth,
                    contextFile,
                    seqGen.getAndIncrement()
                ));
            }
        }
    }

    private static boolean isNonConstantValueKeyword(ValueKeyword valKw) {
        String text = valKw.getText().trim();
        if (StvnVocabulary.BOOLEAN_KEYWORDS.contains(text)
                || StvnVocabulary.CONTROL_KEYWORDS.contains(text)) {
            return true;
        }
        if (text.length() > 1 && Character.isDigit(text.charAt(1))) {
            return true;
        }
        return StvnTypeResolver.resolveGoverningEnumVariant(valKw) != null;
    }

    private static void collectBodyConstantSeeds(
        PsiFile rootFile,
        PsiFile contextFile,
        @Nullable Value currentVal,
        @Nullable SchemaType governingSchema,
        int slotNextSymbolDepth,
        Map<ValueKeyword, Integer> seededConstants
    ) {
        if (currentVal == null) {
            return;
        }
        ValueKeyword valKw = currentVal.getValueKeyword();
        if (valKw != null) {
            if (!isNonConstantValueKeyword(valKw)) {
                int constDepth = Math.max(1, slotNextSymbolDepth);
                seededConstants.merge(valKw, constDepth, Math::min);
            }
            return;
        }

        ExplicitOptionValue optVal = currentVal.getExplicitOptionValue();
        if (optVal != null) {
            Value payload = optVal.getValue();
            if (payload != null) {
                UnwrappedSchemaConstructor unwrapped = unwrapToSumSchemaConstructor(
                    rootFile, contextFile, governingSchema, slotNextSymbolDepth, new HashSet<>()
                );
                if (unwrapped != null) {
                    List<SchemaType> args = collectDirectChildSchemas(unwrapped.ctor());
                    SchemaType inner = !args.isEmpty() ? args.get(0) : null;
                    collectBodyConstantSeeds(
                        rootFile,
                        unwrapped.contextFile(),
                        payload,
                        inner,
                        Math.max(1, unwrapped.nextSymbolDepth()),
                        seededConstants
                    );
                } else {
                    collectBodyConstantSeeds(
                        rootFile,
                        contextFile,
                        payload,
                        null,
                        Math.max(1, slotNextSymbolDepth),
                        seededConstants
                    );
                }
            }
            return;
        }

        ExplicitEitherValue eitherVal = currentVal.getExplicitEitherValue();
        if (eitherVal != null) {
            Value payload = eitherVal.getValue();
            if (payload != null) {
                int branchIdx = (eitherVal.getLeftLiteral() != null || eitherVal.getLeftShortLiteral() != null) ? 0 : 1;
                UnwrappedSchemaConstructor unwrapped = unwrapToSumSchemaConstructor(
                    rootFile, contextFile, governingSchema, slotNextSymbolDepth, new HashSet<>()
                );
                if (unwrapped != null) {
                    List<SchemaType> args = collectDirectChildSchemas(unwrapped.ctor());
                    SchemaType branchSchema = branchIdx < args.size() ? args.get(branchIdx) : null;
                    collectBodyConstantSeeds(
                        rootFile,
                        unwrapped.contextFile(),
                        payload,
                        branchSchema,
                        Math.max(1, unwrapped.nextSymbolDepth()),
                        seededConstants
                    );
                } else {
                    collectBodyConstantSeeds(
                        rootFile,
                        contextFile,
                        payload,
                        null,
                        Math.max(1, slotNextSymbolDepth),
                        seededConstants
                    );
                }
            }
            return;
        }

        ExplicitUnionValue unionVal = currentVal.getExplicitUnionValue();
        if (unionVal != null) {
            Value payload = unionVal.getValue();
            if (payload != null) {
                int branchIdx = parseExplicitUnionZeroBasedBranchIndex(unionVal);
                UnwrappedSchemaConstructor unwrapped = unwrapToSumSchemaConstructor(
                    rootFile, contextFile, governingSchema, slotNextSymbolDepth, new HashSet<>()
                );
                if (unwrapped != null) {
                    List<SchemaType> args = collectDirectChildSchemas(unwrapped.ctor());
                    SchemaType branchSchema = (branchIdx >= 0 && branchIdx < args.size()) ? args.get(branchIdx) : null;
                    collectBodyConstantSeeds(
                        rootFile,
                        unwrapped.contextFile(),
                        payload,
                        branchSchema,
                        Math.max(1, unwrapped.nextSymbolDepth()),
                        seededConstants
                    );
                } else {
                    collectBodyConstantSeeds(
                        rootFile,
                        contextFile,
                        payload,
                        null,
                        Math.max(1, slotNextSymbolDepth),
                        seededConstants
                    );
                }
            }
            return;
        }

        CollectionValue collVal = currentVal.getCollectionValue();
        if (collVal != null) {
            List<UnwrappedSchemaConstructor> matches = resolveMatchingContainerSchemas(
                rootFile,
                contextFile,
                governingSchema,
                slotNextSymbolDepth,
                collVal,
                new HashSet<>()
            );
            TupleLiteral tupleLit = collVal.getTupleLiteral();
            if (tupleLit != null) {
                List<Value> elems = tupleLit.getValueList();
                if (matches.isEmpty()) {
                    for (Value elem : elems) {
                        collectBodyConstantSeeds(
                            rootFile, contextFile, elem, null, Math.max(1, slotNextSymbolDepth), seededConstants
                        );
                    }
                } else {
                    for (UnwrappedSchemaConstructor match : matches) {
                        List<SchemaType> slotSchemas = collectDirectChildSchemas(match.ctor());
                        int nextDepth = Math.max(1, match.nextSymbolDepth());
                        for (int i = 0; i < elems.size(); i++) {
                            SchemaType slotSchema = i < slotSchemas.size() ? slotSchemas.get(i) : null;
                            collectBodyConstantSeeds(
                                rootFile, match.contextFile(), elems.get(i), slotSchema, nextDepth, seededConstants
                            );
                        }
                    }
                }
                return;
            }

            ListLiteral listLit = collVal.getListLiteral();
            if (listLit != null) {
                List<Value> elems = listLit.getValueList();
                if (matches.isEmpty()) {
                    for (Value elem : elems) {
                        collectBodyConstantSeeds(
                            rootFile, contextFile, elem, null, Math.max(1, slotNextSymbolDepth), seededConstants
                        );
                    }
                } else {
                    for (UnwrappedSchemaConstructor match : matches) {
                        List<SchemaType> slotSchemas = collectDirectChildSchemas(match.ctor());
                        SchemaType elemSchema = !slotSchemas.isEmpty() ? slotSchemas.get(0) : null;
                        int nextDepth = Math.max(1, match.nextSymbolDepth());
                        for (Value elem : elems) {
                            collectBodyConstantSeeds(
                                rootFile, match.contextFile(), elem, elemSchema, nextDepth, seededConstants
                            );
                        }
                    }
                }
                return;
            }

            MapLiteral mapLit = collVal.getMapLiteral();
            if (mapLit != null) {
                List<Value> kvValues = mapLit.getValueList();
                if (matches.isEmpty()) {
                    for (Value kv : kvValues) {
                        collectBodyConstantSeeds(
                            rootFile, contextFile, kv, null, Math.max(1, slotNextSymbolDepth), seededConstants
                        );
                    }
                } else {
                    for (UnwrappedSchemaConstructor match : matches) {
                        List<SchemaType> slotSchemas = collectDirectChildSchemas(match.ctor());
                        SchemaType keySchema = slotSchemas.size() >= 1 ? slotSchemas.get(0) : null;
                        SchemaType valSchema = slotSchemas.size() >= 2 ? slotSchemas.get(1) : null;
                        int nextDepth = Math.max(1, match.nextSymbolDepth());
                        for (int i = 0; i < kvValues.size(); i++) {
                            SchemaType entrySchema = (i % 2 == 0) ? keySchema : valSchema;
                            collectBodyConstantSeeds(
                                rootFile, match.contextFile(), kvValues.get(i), entrySchema, nextDepth, seededConstants
                            );
                        }
                    }
                }
            }
        }
    }

    private static @Nullable UnwrappedSchemaConstructor unwrapToSumSchemaConstructor(
        PsiFile rootFile,
        PsiFile contextFile,
        @Nullable SchemaType schema,
        int currentDepth,
        Set<PsiElement> visited
    ) {
        if (schema == null || !visited.add(schema)) {
            return null;
        }
        if (schema.getTypeKeyword() != null) {
            String raw = schema.getTypeKeyword().getText().trim();
            if (StvnVocabulary.isBaseScalarType(raw)) {
                return null;
            }
            ResolvedTypeSymbol sym = resolveTypeSymbolForClosure(rootFile, contextFile, schema.getTypeKeyword());
            if (sym == null || sym.isPrelude() || sym.underlyingTypeDef() == null) {
                return null;
            }
            PsiFile nextFile = sym.underlyingTypeDef().getContainingFile() != null
                ? sym.underlyingTypeDef().getContainingFile()
                : contextFile;
            return unwrapToSumSchemaConstructor(
                rootFile,
                nextFile,
                sym.underlyingTypeDef().getSchemaType(),
                currentDepth + 1,
                visited
            );
        }
        SchemaConstructor ctor = schema.getSchemaConstructor();
        if (ctor != null && ctor.getSumType() != null && ctor.getSumType().getEnumDef() == null) {
            return new UnwrappedSchemaConstructor(ctor, contextFile, currentDepth);
        }
        return null;
    }

    private static List<UnwrappedSchemaConstructor> resolveMatchingContainerSchemas(
        PsiFile rootFile,
        PsiFile contextFile,
        @Nullable SchemaType schema,
        int currentDepth,
        CollectionValue collVal,
        Set<PsiElement> visited
    ) {
        if (schema == null || !visited.add(schema)) {
            return List.of();
        }
        if (schema.getTypeKeyword() != null) {
            String raw = schema.getTypeKeyword().getText().trim();
            if (StvnVocabulary.isBaseScalarType(raw)) {
                return List.of();
            }
            ResolvedTypeSymbol sym = resolveTypeSymbolForClosure(rootFile, contextFile, schema.getTypeKeyword());
            if (sym == null || sym.isPrelude() || sym.underlyingTypeDef() == null) {
                return List.of();
            }
            PsiFile nextFile = sym.underlyingTypeDef().getContainingFile() != null
                ? sym.underlyingTypeDef().getContainingFile()
                : contextFile;
            return resolveMatchingContainerSchemas(
                rootFile,
                nextFile,
                sym.underlyingTypeDef().getSchemaType(),
                currentDepth + 1,
                collVal,
                visited
            );
        }
        SchemaConstructor ctor = schema.getSchemaConstructor();
        if (ctor == null) {
            return List.of();
        }
        if (ctor.getProductType() != null && collVal.getTupleLiteral() != null) {
            return List.of(new UnwrappedSchemaConstructor(ctor, contextFile, currentDepth));
        }
        if (ctor.getCollectionType() != null) {
            boolean isMapSchema = ctor.getCollectionType().getNode().findChildByType(StvnTypes.COLL_MAP) != null;
            if (isMapSchema && collVal.getMapLiteral() != null) {
                return List.of(new UnwrappedSchemaConstructor(ctor, contextFile, currentDepth));
            }
            if (!isMapSchema && collVal.getListLiteral() != null) {
                return List.of(new UnwrappedSchemaConstructor(ctor, contextFile, currentDepth));
            }
            return List.of();
        }
        if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() == null) {
            List<UnwrappedSchemaConstructor> matches = new ArrayList<>();
            for (SchemaType branch : collectDirectChildSchemas(ctor)) {
                matches.addAll(resolveMatchingContainerSchemas(
                    rootFile,
                    contextFile,
                    branch,
                    currentDepth,
                    collVal,
                    new HashSet<>(visited)
                ));
            }
            return matches;
        }
        return List.of();
    }

    private static int parseExplicitUnionZeroBasedBranchIndex(ExplicitUnionValue unionVal) {
        PsiElement first = unionVal.getFirstChild();
        if (first == null) {
            return -1;
        }
        String text = first.getText().trim();
        if (!text.startsWith(StvnVocabulary.SIGIL_VALUE) || text.length() <= 1) {
            return -1;
        }
        try {
            int oneBased = Integer.parseInt(text.substring(1));
            return oneBased >= 1 ? oneBased - 1 : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static List<SchemaType> collectDirectChildSchemas(SchemaConstructor ctor) {
        if (ctor.getProductType() != null) {
            return ctor.getProductType().getSchemaTypeList();
        }
        if (ctor.getCollectionType() != null) {
            return ctor.getCollectionType().getSchemaTypeList();
        }
        if (ctor.getSumType() != null) {
            return PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        }
        return List.of();
    }

    private static void recordEntryWithMinDepth(
        Map<String, StvnNamespaceSymbolEntry> byKey,
        String key,
        StvnNamespaceSymbolEntry candidate
    ) {
        var existing = byKey.get(key);
        if (existing == null || candidate.useDepth() < existing.useDepth()) {
            byKey.put(key, candidate);
        }
    }

    private record ResolvedTypeSymbol(
        String identityKey,
        String displayName,
        String source,
        String upstreamTarget,
        String resolvedType,
        PsiElement navigationTarget,
        @Nullable TypeDefinition underlyingTypeDef,
        boolean isPrelude
    ) {
    }

    private record ResolvedConstantSymbol(
        String identityKey,
        String displayName,
        String source,
        String upstreamTarget,
        String resolvedType,
        PsiElement navigationTarget,
        boolean isPrelude
    ) {
    }

    private static @Nullable ResolvedTypeSymbol resolveTypeSymbolForClosure(
        PsiFile rootFile,
        PsiFile contextFile,
        TypeKeyword referenceKw
    ) {
        PsiElement resolved = new StvnTypeReference(referenceKw).resolve();
        if (resolved == null) {
            resolved = StvnTypeReference.resolveTypeAtContext(referenceKw, referenceKw.getText().trim());
        }
        if (resolved == null) {
            var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(rootFile.getProject(), referenceKw.getText().trim());
            if (preludeDef != null && preludeDef.getTypeKeyword() != null) {
                resolved = preludeDef.getTypeKeyword();
            }
        }
        if (resolved == null) {
            return null;
        }

        // Unwrap UseMapAlias if referenceKw resolved to a local or package-local :use alias
        UseMapAlias directUseAlias = null;
        if (resolved instanceof TypeKeyword rKw && rKw.getParent() instanceof UseMapAlias ua) {
            directUseAlias = ua;
            var list = ua.getTypeKeywordList();
            if (!list.isEmpty() && list.get(0) != null) {
                PsiElement remoteResolved = new StvnTypeReference(list.get(0)).resolve();
                if (remoteResolved != null) {
                    resolved = remoteResolved;
                }
            }
        }

        TypeDefinition targetDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
        if (targetDef == null || targetDef.getTypeKeyword() == null) {
            if (directUseAlias != null && directUseAlias.getTypeKeywordList().size() >= 2) {
                var remoteKw = directUseAlias.getTypeKeywordList().get(0);
                var localKw = directUseAlias.getTypeKeywordList().get(1);
                var useElem = PsiTreeUtil.getParentOfType(directUseAlias, UseElement.class);
                String pkg = (useElem != null && useElem.getUseTarget() != null)
                    ? useElem.getUseTarget().getText().trim()
                    : contextFile.getName();
                String upFqni = remoteKw != null ? qualifyTypeWithPackage(pkg, remoteKw.getText().trim()) : referenceKw.getText().trim();
                PsiElement nav = localKw != null ? localKw : referenceKw;
                String name = localKw != null ? localKw.getText().trim() : referenceKw.getText().trim();
                return new ResolvedTypeSymbol("USE_UNRESOLVED:" + upFqni, name, pkg, upFqni, upFqni, nav, null, false);
            }
            return null;
        }

        var defKw = targetDef.getTypeKeyword();
        PsiFile defFile = targetDef.getContainingFile();
        boolean isPrelude = defFile != null && StvnPreludeBridge.isPreludeFile(defFile);
        String canonicalFqni = buildCanonicalTypeDefFqni(targetDef);
        String resolvedSchemaText = resolveFormattedTypeStructure(targetDef, new HashSet<>());
        String identityKey = (defFile != null ? defFile.getName() : "") + "::" + canonicalFqni;

        ProjectedBinding rootBinding = findRootUseBindingForTypeFqni(rootFile, canonicalFqni);
        if (rootBinding != null) {
            PsiElement navTarget = rootBinding.localBindingElement() instanceof TypeKeyword
                ? rootBinding.localBindingElement()
                : defKw;
            return new ResolvedTypeSymbol(
                identityKey,
                rootBinding.localName(),
                rootBinding.sourcePackage(),
                canonicalFqni,
                resolvedSchemaText,
                navTarget,
                targetDef,
                false
            );
        }

        if (isPrelude) {
            String preludePkg = StvnPreludeBridge.getPreludePackagePath(targetDef);
            return new ResolvedTypeSymbol(
                identityKey,
                canonicalFqni,
                preludePkg,
                StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                resolvedSchemaText,
                defKw,
                targetDef,
                true
            );
        }

        var pkg = PsiTreeUtil.getParentOfType(targetDef, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            String pkgPath = pkg.getPackagePath().getText().trim();
            return new ResolvedTypeSymbol(
                identityKey,
                canonicalFqni,
                pkgPath,
                StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                resolvedSchemaText,
                defKw,
                targetDef,
                false
            );
        }

        String sourceName = defFile != null ? defFile.getName() : rootFile.getName();
        return new ResolvedTypeSymbol(
            identityKey,
            defKw.getText().trim(),
            sourceName,
            StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
            resolvedSchemaText,
            defKw,
            targetDef,
            false
        );
    }

    private static ResolvedConstantSymbol resolveConstantSymbolForClosure(
        PsiFile rootFile,
        ConstantDefinition constDef,
        @Nullable PsiElement directReferenceTarget
    ) {
        var defKw = constDef.getValueKeyword();
        PsiFile defFile = constDef.getContainingFile();
        boolean isPrelude = defFile != null && StvnPreludeBridge.isPreludeFile(defFile);
        String canonicalConstFqni = buildCanonicalConstantDefFqni(constDef);
        ProjectedBinding rootBinding = findRootUseBindingForConstantFqni(rootFile, canonicalConstFqni);
        boolean applyRootProjection = rootBinding != null
            || (!isPrelude && PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class) == null);
        String resolvedConstType = formatConstantResolvedType(constDef, rootFile, applyRootProjection);
        String identityKey = (defFile != null ? defFile.getName() : "") + "::" + canonicalConstFqni;

        if (rootBinding != null) {
            PsiElement navTarget = rootBinding.localBindingElement() instanceof ValueKeyword
                ? rootBinding.localBindingElement()
                : (directReferenceTarget != null ? directReferenceTarget : defKw);
            return new ResolvedConstantSymbol(
                identityKey,
                rootBinding.localName(),
                rootBinding.sourcePackage(),
                canonicalConstFqni,
                resolvedConstType,
                navTarget,
                false
            );
        }

        if (isPrelude) {
            String preludePkg = StvnPreludeBridge.getPreludePackagePath(constDef);
            return new ResolvedConstantSymbol(
                identityKey,
                canonicalConstFqni,
                preludePkg,
                StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                resolvedConstType,
                defKw,
                true
            );
        }

        var pkg = PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            String pkgPath = pkg.getPackagePath().getText().trim();
            return new ResolvedConstantSymbol(
                identityKey,
                canonicalConstFqni,
                pkgPath,
                StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                resolvedConstType,
                defKw,
                false
            );
        }

        String sourceName = defFile != null ? defFile.getName() : rootFile.getName();
        return new ResolvedConstantSymbol(
            identityKey,
            defKw.getText().trim(),
            sourceName,
            StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
            resolvedConstType,
            defKw,
            false
        );
    }

    private static String formatConstantResolvedType(ConstantDefinition constDef, PsiFile activeRootFile) {
        boolean applyRootUseProjection = !StvnPreludeBridge.isPreludeFile(constDef.getContainingFile())
            && PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class) == null;
        return formatConstantResolvedType(constDef, activeRootFile, applyRootUseProjection);
    }

    private static String formatConstantResolvedType(
        ConstantDefinition constDef,
        PsiFile activeRootFile,
        boolean applyRootUseProjection
    ) {
        var schemaType = constDef.getSchemaType();
        if (schemaType == null) {
            return "";
        }
        var typeKw = schemaType.getTypeKeyword();
        if (typeKw != null) {
            var hops = StvnTypeReference.extractClickableResolutionHops(typeKw, true);
            if (!hops.isEmpty()) {
                List<String> labels = new ArrayList<>(hops.stream().map(StvnTypeReference.TraceHop::displayLabel).toList());
                if (applyRootUseProjection) {
                    String firstHop = labels.get(0);
                    ProjectedBinding rootBinding = findRootUseBindingForTypeFqni(activeRootFile, firstHop);
                    if (rootBinding != null && !rootBinding.localName().equals(firstHop)) {
                        String localSimple = StvnTypeReference.extractSimpleTypeName(rootBinding.localName());
                        String remoteSimple = StvnTypeReference.extractSimpleTypeName(firstHop);
                        if (!localSimple.equals(remoteSimple)) {
                            labels.add(0, rootBinding.localName());
                        }
                    }
                }
                if (labels.size() == 1) {
                    return labels.get(0);
                }
                return labels.get(0) + " (-> " + String.join(" -> ", labels.subList(1, labels.size())) + ")";
            }
        }
        return StvnSchemaFormatter.formatCompactCanonicalPsiSchema(constDef);
    }

    private static String resolveImportedTypeStructure(PsiFile file, String upstreamFqni) {
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), upstreamFqni);
        if (preludeDef != null) {
            return resolveFormattedTypeStructure(preludeDef, new HashSet<>());
        }
        var resolved = StvnTypeReference.resolveTypeInFile(file, upstreamFqni, new HashSet<>());
        var targetDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
        if (targetDef != null) {
            return resolveFormattedTypeStructure(targetDef, new HashSet<>());
        }
        return upstreamFqni;
    }

    private static String resolveImportedConstantType(PsiFile file, String targetPkg, String remoteConstText) {
        String constFqni = buildConstantFqni(targetPkg, remoteConstText);
        ConstantDefinition found = findPackagedConstantDefinition(file, targetPkg, remoteConstText);
        if (found != null) {
            return formatConstantResolvedType(found, file, true);
        }
        return constFqni;
    }

    private static @Nullable ConstantDefinition findPackagedConstantDefinition(
        PsiFile file,
        String targetPkg,
        String remoteConstText
    ) {
        String bareConst = StvnVocabulary.SIGIL_VALUE + stripHash(
            remoteConstText.contains("/")
                ? remoteConstText.substring(remoteConstText.lastIndexOf('/') + 1)
                : remoteConstText
        );
        List<PsiFile> searchFiles = new ArrayList<>();
        searchFiles.add(file);
        searchFiles.addAll(collectTransitiveIncludedFiles(file));
        searchFiles.add(StvnPreludeBridge.getPreludeFile(file.getProject()));

        for (PsiFile candidateFile : searchFiles) {
            for (var pkg : PsiTreeUtil.findChildrenOfType(candidateFile, PackageEnclosure.class)) {
                if (StvnPsiUtils.hasIllegalTrailingSlash(pkg) || pkg.getPackagePath() == null) {
                    continue;
                }
                if (!targetPkg.equals(pkg.getPackagePath().getText().trim())) {
                    continue;
                }
                for (var elem : pkg.getPackageElementList()) {
                    var cDef = elem.getConstantDefinition();
                    if (cDef != null && cDef.getValueKeyword() != null
                            && bareConst.equals(cDef.getValueKeyword().getText().trim())) {
                        return cDef;
                    }
                }
            }
        }
        return null;
    }

    private static String resolveFormattedTypeStructure(TypeDefinition def, Set<PsiElement> visited) {
        if (!visited.add(def)) {
            return def.getTypeKeyword() != null ? def.getTypeKeyword().getText().trim() : "";
        }
        var schemaType = def.getSchemaType();
        if (schemaType == null) {
            return def.getTypeKeyword() != null ? def.getTypeKeyword().getText().trim() : "";
        }
        TypeKeyword parentKw = schemaType.getTypeKeyword();
        if (parentKw != null) {
            String nextRaw = parentKw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(nextRaw)
                    && !StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(nextRaw)) {
                TypeDefinition parentDef = StvnTypeReference.resolveTypeDefinitionAtContext(parentKw, nextRaw);
                if (parentDef != null && parentDef != def) {
                    return formatDerivedNominalTypeStructure(def, schemaType, parentKw, parentDef);
                }
            }
        }
        return StvnSchemaFormatter.formatCompactCanonicalPsiSchema(def);
    }

    private static String formatDerivedNominalTypeStructure(
        TypeDefinition def,
        SchemaType schemaType,
        TypeKeyword parentKw,
        TypeDefinition firstParentDef
    ) {
        List<MetadataMap> ancestorMapsChildToRoot = new ArrayList<>();
        Set<PsiElement> chainVisited = new HashSet<>();
        chainVisited.add(def);
        TypeDefinition terminalDef = firstParentDef;
        TypeDefinition curr = firstParentDef;
        while (curr != null && chainVisited.add(curr)) {
            terminalDef = curr;
            appendTypeDefinitionMetadataMaps(curr, ancestorMapsChildToRoot);
            SchemaType currSt = curr.getSchemaType();
            TypeKeyword nextKw = currSt != null ? currSt.getTypeKeyword() : null;
            if (nextKw == null || StvnVocabulary.isBaseScalarType(nextKw.getText().trim())) {
                break;
            }
            curr = StvnTypeReference.resolveTypeDefinitionAtContext(nextKw, nextKw.getText().trim());
        }

        boolean isSubset = hasEnumSubsetFacet(def.getMetadataMap()) || hasEnumSubsetFacet(schemaType.getMetadataMap());
        String facetBlock;
        if (isSubset) {
            List<MetadataMap> rootToParentMaps = new ArrayList<>(ancestorMapsChildToRoot.size());
            for (int i = ancestorMapsChildToRoot.size() - 1; i >= 0; i--) {
                rootToParentMaps.add(ancestorMapsChildToRoot.get(i));
            }
            MetadataMap ownMeta = def.getMetadataMap() != null ? def.getMetadataMap() : schemaType.getMetadataMap();
            facetBlock = StvnSchemaFormatter.formatMergedEnumSubsetPsiMetadataMaps(rootToParentMaps, ownMeta);
        } else {
            List<MetadataMap> childToRootMaps = new ArrayList<>();
            appendTypeDefinitionMetadataMaps(def, childToRootMaps);
            childToRootMaps.addAll(ancestorMapsChildToRoot);
            List<MetadataMap> rootToChildMaps = new ArrayList<>(childToRootMaps.size());
            for (int i = childToRootMaps.size() - 1; i >= 0; i--) {
                rootToChildMaps.add(childToRootMaps.get(i));
            }
            facetBlock = StvnSchemaFormatter.formatMergedPsiMetadataMaps(rootToChildMaps);
        }

        List<String> lineageLabels = extractNominalTypeLineageLabels(parentKw, terminalDef, !facetBlock.isEmpty());
        String directParent = lineageLabels.isEmpty() ? parentKw.getText().trim() : lineageLabels.get(0);
        String prefix = facetBlock.isEmpty() ? "" : facetBlock + " ";
        String suffix = lineageLabels.size() > 1
            ? " (-> " + String.join(" -> ", lineageLabels.subList(1, lineageLabels.size())) + ")"
            : "";
        return prefix + directParent + suffix;
    }

    private static void appendTypeDefinitionMetadataMaps(TypeDefinition td, List<MetadataMap> childToRootTarget) {
        if (td.getMetadataMap() != null) {
            childToRootTarget.add(td.getMetadataMap());
        }
        if (td.getSchemaType() != null && td.getSchemaType().getMetadataMap() != null) {
            childToRootTarget.add(td.getSchemaType().getMetadataMap());
        }
    }

    private static List<String> extractNominalTypeLineageLabels(
        TypeKeyword parentKw,
        TypeDefinition terminalDef,
        boolean normalizeTemporalTerminal
    ) {
        var rawHops = StvnTypeReference.extractClickableResolutionHops(parentKw, true);
        List<String> labels = new ArrayList<>(rawHops.size() + 1);
        for (var hop : rawHops) {
            String label = hop.displayLabel();
            if (labels.isEmpty() || !labels.get(labels.size() - 1).equals(label)) {
                labels.add(label);
            }
        }
        if (labels.isEmpty()) {
            labels.add(parentKw.getText().trim());
        }

        String lastLabel = labels.get(labels.size() - 1);
        if (!isConstructorOrScalarTerminalLabel(lastLabel)) {
            String terminalCtor = extractTerminalConstructorKeyword(terminalDef);
            if (terminalCtor != null && !terminalCtor.equals(lastLabel)) {
                labels.add(terminalCtor);
            }
        }

        if (normalizeTemporalTerminal && labels.size() > 1) {
            int lastIdx = labels.size() - 1;
            labels.set(lastIdx, stripInlineTemporalFlagPrefix(labels.get(lastIdx)));
        }
        return labels;
    }

    private static boolean isConstructorOrScalarTerminalLabel(String label) {
        if (StvnVocabulary.isBaseScalarType(label) || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(label)) {
            return true;
        }
        return label.startsWith(StvnVocabulary.DELIM_OPEN_BRACE)
            && (label.endsWith(" " + StvnVocabulary.TYPE_TIME_EPOCH)
                || label.endsWith(" " + StvnVocabulary.TYPE_DATE_TIME));
    }

    private static String stripInlineTemporalFlagPrefix(String label) {
        if (label.startsWith(StvnVocabulary.DELIM_OPEN_BRACE)) {
            if (label.endsWith(" " + StvnVocabulary.TYPE_TIME_EPOCH)) {
                return StvnVocabulary.TYPE_TIME_EPOCH;
            }
            if (label.endsWith(" " + StvnVocabulary.TYPE_DATE_TIME)) {
                return StvnVocabulary.TYPE_DATE_TIME;
            }
        }
        return label;
    }

    private static @Nullable String extractTerminalConstructorKeyword(TypeDefinition terminalDef) {
        SchemaType st = terminalDef.getSchemaType();
        if (st == null) {
            return null;
        }
        if (st.getTypeKeyword() != null) {
            String kwText = st.getTypeKeyword().getText().trim();
            if (StvnVocabulary.isBaseScalarType(kwText) || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(kwText)) {
                return kwText;
            }
        }
        SchemaConstructor ctor = st.getSchemaConstructor();
        if (ctor == null) {
            return null;
        }
        if (ctor.getAtomicType() != null) {
            return ctor.getAtomicType().getText().trim();
        }
        if (ctor.getProductType() != null) {
            return StvnVocabulary.TYPE_TUPLE;
        }
        if (ctor.getCollectionType() != null && ctor.getCollectionType().getFirstChild() != null) {
            return ctor.getCollectionType().getFirstChild().getText().trim();
        }
        if (ctor.getSumType() != null) {
            SumType sum = ctor.getSumType();
            if (sum.getEnumDef() != null) {
                return StvnVocabulary.TYPE_ENUM;
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_OPTION) != null) {
                return StvnVocabulary.TYPE_OPTION;
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_EITHER) != null) {
                return StvnVocabulary.TYPE_EITHER;
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_UNION) != null) {
                return StvnVocabulary.TYPE_UNION;
            }
        }
        return null;
    }

    private static boolean hasEnumSubsetFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return false;
        }
        String text = metaMap.getText();
        return text.contains(StvnVocabulary.FACET_KW_FILTER_INCL)
            || text.contains(StvnVocabulary.FACET_KW_FILTER_EXCL);
    }

    private static @Nullable TypeDefinition resolveUpstreamTypeForUseAlias(
        PsiFile file,
        TypeKeyword remoteKw,
        String upstreamFqni
    ) {
        PsiElement resolved = new StvnTypeReference(remoteKw).resolve();
        TypeDefinition td = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
        if (td != null) {
            return td;
        }
        PsiElement byFqni = StvnTypeReference.resolveTypeInFile(file, upstreamFqni, new HashSet<>());
        td = StvnPsiUtils.resolveTargetTypeDefinition(byFqni);
        if (td != null) {
            return td;
        }
        if (!file.getProject().isDisposed()) {
            return StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), upstreamFqni);
        }
        return null;
    }

    private static @Nullable ConstantDefinition resolveUpstreamConstForUseAlias(
        PsiFile file,
        ValueKeyword remoteKw,
        String upstreamConstFqni
    ) {
        PsiElement resolved = StvnConstantReference.resolveConstantWithContext(remoteKw);
        ConstantDefinition cd = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
        if (cd != null) {
            return cd;
        }
        PsiElement byFqni = StvnConstantReference.resolveConstantInFile(file, upstreamConstFqni, new HashSet<>());
        cd = StvnPsiUtils.resolveTargetConstantDefinition(byFqni);
        if (cd != null) {
            return cd;
        }
        if (!file.getProject().isDisposed()) {
            return StvnPreludeBridge.resolvePreludeConstantDefinition(file.getProject(), upstreamConstFqni);
        }
        return null;
    }

    private static int computeVopGroundingDepthForDeclaration(
        PsiElement rootDecl,
        Map<PsiElement, Integer> sharedDepthMemo
    ) {
        Integer cached = sharedDepthMemo.get(rootDecl);
        if (cached != null) {
            return cached;
        }

        Map<PsiElement, List<PsiElement>> adj = new IdentityHashMap<>();
        Deque<PsiElement> worklist = new ArrayDeque<>();
        worklist.add(rootDecl);
        while (!worklist.isEmpty()) {
            PsiElement u = worklist.poll();
            if (adj.containsKey(u) || sharedDepthMemo.containsKey(u)) {
                continue;
            }
            List<PsiElement> deps = collectDirectDeclarationDependencies(u);
            adj.put(u, deps);
            for (PsiElement v : deps) {
                if (!adj.containsKey(v) && !sharedDepthMemo.containsKey(v)) {
                    worklist.add(v);
                }
            }
        }

        Map<PsiElement, Integer> dfn = new IdentityHashMap<>();
        Map<PsiElement, Integer> low = new IdentityHashMap<>();
        Set<PsiElement> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<PsiElement> tarjanStack = new ArrayDeque<>();
        Map<PsiElement, Integer> sccIdOf = new IdentityHashMap<>();
        List<List<PsiElement>> sccs = new ArrayList<>();
        int[] timer = {0};

        for (PsiElement node : adj.keySet()) {
            if (!dfn.containsKey(node)) {
                runTarjanDfs(node, adj, sharedDepthMemo, dfn, low, onStack, tarjanStack, sccIdOf, sccs, timer);
            }
        }

        for (int sccIdx = 0; sccIdx < sccs.size(); sccIdx++) {
            List<PsiElement> component = sccs.get(sccIdx);
            int depth = 0;
            for (PsiElement u : component) {
                for (PsiElement v : adj.getOrDefault(u, List.of())) {
                    Integer precomputed = sharedDepthMemo.get(v);
                    if (precomputed != null) {
                        Integer vScc = sccIdOf.get(v);
                        if (vScc == null || vScc != sccIdx) {
                            depth = Math.max(depth, 1 + precomputed);
                        }
                    }
                }
            }
            for (PsiElement u : component) {
                sharedDepthMemo.put(u, depth);
            }
        }

        return sharedDepthMemo.getOrDefault(rootDecl, 0);
    }

    private static void runTarjanDfs(
        PsiElement u,
        Map<PsiElement, List<PsiElement>> adj,
        Map<PsiElement, Integer> sharedDepthMemo,
        Map<PsiElement, Integer> dfn,
        Map<PsiElement, Integer> low,
        Set<PsiElement> onStack,
        Deque<PsiElement> tarjanStack,
        Map<PsiElement, Integer> sccIdOf,
        List<List<PsiElement>> sccs,
        int[] timer
    ) {
        int id = timer[0]++;
        dfn.put(u, id);
        low.put(u, id);
        tarjanStack.push(u);
        onStack.add(u);

        for (PsiElement v : adj.getOrDefault(u, List.of())) {
            if (sharedDepthMemo.containsKey(v)) {
                continue;
            }
            Integer vDfn = dfn.get(v);
            if (vDfn == null) {
                runTarjanDfs(v, adj, sharedDepthMemo, dfn, low, onStack, tarjanStack, sccIdOf, sccs, timer);
                low.put(u, Math.min(low.get(u), low.get(v)));
            } else if (onStack.contains(v)) {
                low.put(u, Math.min(low.get(u), vDfn));
            }
        }

        if (low.get(u).equals(dfn.get(u))) {
            int sccIndex = sccs.size();
            List<PsiElement> component = new ArrayList<>();
            while (true) {
                PsiElement w = tarjanStack.pop();
                onStack.remove(w);
                sccIdOf.put(w, sccIndex);
                component.add(w);
                if (w == u) {
                    break;
                }
            }
            sccs.add(component);
        }
    }

    private static List<PsiElement> collectDirectDeclarationDependencies(PsiElement decl) {
        Set<PsiElement> deps = Collections.newSetFromMap(new IdentityHashMap<>());
        if (decl instanceof TypeDefinition td) {
            collectMetadataMapSymbolDeps(td.getMetadataMap(), deps);
            collectSchemaTypeSymbolDeps(td.getSchemaType(), deps);
        } else if (decl instanceof ConstantDefinition cd) {
            collectSchemaTypeSymbolDeps(cd.getSchemaType(), deps);
            collectValueConstantDeps(cd.getValue(), deps);
        }
        deps.remove(decl);
        return new ArrayList<>(deps);
    }

    private static void collectSchemaTypeSymbolDeps(@Nullable SchemaType schemaType, Set<PsiElement> deps) {
        if (schemaType == null) {
            return;
        }
        collectMetadataMapSymbolDeps(schemaType.getMetadataMap(), deps);
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            TypeDefinition targetTd = resolveReferencedTypeDefinitionForGrounding(kw);
            if (targetTd != null) {
                deps.add(targetTd);
            }
        }
        SchemaConstructor ctor = schemaType.getSchemaConstructor();
        if (ctor != null) {
            ProductType prod = ctor.getProductType();
            if (prod != null) {
                for (SchemaType child : prod.getSchemaTypeList()) {
                    collectSchemaTypeSymbolDeps(child, deps);
                }
            }
            CollectionType coll = ctor.getCollectionType();
            if (coll != null) {
                for (SchemaType child : coll.getSchemaTypeList()) {
                    collectSchemaTypeSymbolDeps(child, deps);
                }
            }
            SumType sum = ctor.getSumType();
            if (sum != null && sum.getEnumDef() == null) {
                for (SchemaType child : PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class)) {
                    collectSchemaTypeSymbolDeps(child, deps);
                }
            }
        }
    }

    private static void collectMetadataMapSymbolDeps(@Nullable MetadataMap metaMap, Set<PsiElement> deps) {
        if (metaMap == null) {
            return;
        }
        for (var entry : metaMap.getMetadataEntryList()) {
            if (entry.getMetadataFilter() != null) {
                continue;
            }
            for (var vkw : PsiTreeUtil.findChildrenOfType(entry, ValueKeyword.class)) {
                ConstantDefinition cd = resolveReferencedConstantDefinitionForGrounding(vkw);
                if (cd != null) {
                    deps.add(cd);
                }
            }
        }
    }

    private static void collectValueConstantDeps(@Nullable Value value, Set<PsiElement> deps) {
        if (value == null) {
            return;
        }
        for (var vkw : PsiTreeUtil.findChildrenOfType(value, ValueKeyword.class)) {
            String text = vkw.getText().trim();
            if (StvnPsiUtils.isConstructorTag(text)
                    || StvnVocabulary.VAL_TRUE.equals(text)
                    || StvnVocabulary.VAL_TRUE_SHORT.equals(text)
                    || StvnVocabulary.VAL_FALSE.equals(text)
                    || StvnVocabulary.VAL_FALSE_SHORT.equals(text)) {
                continue;
            }
            var ref = vkw.getReference();
            if (ref != null) {
                PsiElement resolved = ref.resolve();
                if (resolved != null && PsiTreeUtil.getParentOfType(resolved, EnumDef.class, false) != null) {
                    continue;
                }
                ConstantDefinition viaRef = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
                if (viaRef != null) {
                    deps.add(viaRef);
                    continue;
                }
            }
            ConstantDefinition cd = resolveReferencedConstantDefinitionForGrounding(vkw);
            if (cd != null) {
                deps.add(cd);
            }
        }
    }

    private static @Nullable TypeDefinition resolveReferencedTypeDefinitionForGrounding(TypeKeyword kw) {
        PsiFile file = kw.getContainingFile();
        String name = kw.getText().trim();
        if (file == null
                || !name.startsWith(StvnVocabulary.SIGIL_TYPIC)
                || StvnVocabulary.isBaseScalarType(name)) {
            return null;
        }
        return StvnTypeReference.resolveTypeDefinitionAtContext(kw, name);
    }

    private static @Nullable ConstantDefinition resolveReferencedConstantDefinitionForGrounding(ValueKeyword vkw) {
        String name = vkw.getText().trim();
        if (!name.startsWith(StvnVocabulary.SIGIL_VALUE)
                || StvnVocabulary.VAL_TRUE.equals(name)
                || StvnVocabulary.VAL_TRUE_SHORT.equals(name)
                || StvnVocabulary.VAL_FALSE.equals(name)
                || StvnVocabulary.VAL_FALSE_SHORT.equals(name)
                || StvnPsiUtils.isConstructorTag(name)) {
            return null;
        }
        PsiElement withCtx = StvnConstantReference.resolveConstantWithContext(vkw);
        if (withCtx != null && PsiTreeUtil.getParentOfType(withCtx, EnumDef.class, false) == null) {
            ConstantDefinition cd = StvnPsiUtils.resolveTargetConstantDefinition(withCtx);
            if (cd != null) {
                return cd;
            }
        }
        PackageEnclosure pkg = PsiTreeUtil.getParentOfType(vkw, PackageEnclosure.class);
        if (pkg != null && pkg.getPackagePath() != null && !name.substring(1).contains("/")) {
            for (var elem : pkg.getPackageElementList()) {
                var cd = elem.getConstantDefinition();
                if (cd != null && cd.getValueKeyword() != null
                        && name.equals(cd.getValueKeyword().getText().trim())) {
                    return cd;
                }
            }
        }
        return StvnPsiLiteralEvaluator.resolveConstantDefinition(vkw, name);
    }

    private static void collectStrippedPackageSymbols(
        PsiFile file,
        String targetPackagePath,
        Set<String> processedNames,
        List<StvnNamespaceSymbolEntry> results,
        @Nullable PsiElement originElement,
        Map<PsiElement, Integer> groundingMemo
    ) {
        List<PsiFile> candidateFiles = new ArrayList<>();
        candidateFiles.add(file);
        candidateFiles.addAll(collectTransitiveIncludedFiles(file));
        candidateFiles.add(StvnPreludeBridge.getPreludeFile(file.getProject()));

        for (PsiFile candidateFile : candidateFiles) {
            for (var pkg : PsiTreeUtil.findChildrenOfType(candidateFile, PackageEnclosure.class)) {
                var path = pkg.getPackagePath();
                if (!StvnPsiUtils.hasIllegalTrailingSlash(pkg)
                        && path != null
                        && targetPackagePath.equals(path.getText().trim())) {
                    collectStrippedElements(file, pkg, targetPackagePath, processedNames, results, originElement, groundingMemo);
                }
            }
        }
    }

    private static void collectStrippedElements(
        PsiFile activeRootFile,
        PackageEnclosure pkg,
        String targetPackagePath,
        Set<String> processedNames,
        List<StvnNamespaceSymbolEntry> results,
        @Nullable PsiElement originElement,
        Map<PsiElement, Integer> groundingMemo
    ) {
        for (var elem : pkg.getPackageElementList()) {
            var typeDef = elem.getTypeDefinition();
            if (typeDef != null && typeDef.getTypeKeyword() != null) {
                var kw = typeDef.getTypeKeyword();
                String bareName = kw.getText().trim();
                String fqni = targetPackagePath + "/" + stripColon(bareName);
                PsiElement navElement = originElement != null ? originElement : kw;
                if (processedNames.add(bareName)) {
                    results.add(new StvnNamespaceSymbolEntry(
                        bareName,
                        targetPackagePath,
                        fqni,
                        resolveFormattedTypeStructure(typeDef, new HashSet<>()),
                        computeVopGroundingDepthForDeclaration(typeDef, groundingMemo),
                        navElement,
                        false,
                        navElement.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
            var constDef = elem.getConstantDefinition();
            if (constDef != null && constDef.getValueKeyword() != null) {
                var kw = constDef.getValueKeyword();
                String bareName = kw.getText().trim();
                String fqni = buildConstantFqni(targetPackagePath, bareName);
                PsiElement navElement = originElement != null ? originElement : kw;
                if (processedNames.add(bareName)) {
                    results.add(new StvnNamespaceSymbolEntry(
                        bareName,
                        targetPackagePath,
                        fqni,
                        formatConstantResolvedType(constDef, activeRootFile, true),
                        computeVopGroundingDepthForDeclaration(constDef, groundingMemo),
                        navElement,
                        false,
                        navElement.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
        }
    }

    private static List<PsiFile> collectTransitiveIncludedFiles(PsiFile rootFile) {
        var result = new ArrayList<PsiFile>();
        var visited = new HashSet<PsiFile>();
        visited.add(rootFile);
        Queue<PsiFile> queue = new ArrayDeque<>();
        queue.add(rootFile);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var incl : PsiTreeUtil.findChildrenOfType(current, IncludeElement.class)) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(incl)) {
                    continue;
                }
                var stringLit = incl.getStringLiteral();
                if (stringLit == null) {
                    continue;
                }
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null && visited.add(targetFile)) {
                    result.add(targetFile);
                    queue.add(targetFile);
                }
            }
        }
        return result;
    }

    private static @Nullable TypeEntry findPrimaryTypeEntry(PsiFile file) {
        for (var entry : PsiTreeUtil.findChildrenOfType(file, TypeEntry.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(entry)) {
                return entry;
            }
        }
        return null;
    }

    private static @Nullable BodyEntry findPrimaryBodyEntry(PsiFile file) {
        for (var entry : PsiTreeUtil.findChildrenOfType(file, BodyEntry.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(entry)) {
                return entry;
            }
        }
        return null;
    }

    private static String buildCanonicalTypeDefFqni(TypeDefinition typeDef) {
        var kw = typeDef.getTypeKeyword();
        String raw = kw != null ? kw.getText().trim() : "";
        if (raw.contains("/")) {
            return raw;
        }
        var pkg = PsiTreeUtil.getParentOfType(typeDef, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            return pkg.getPackagePath().getText().trim() + "/" + stripColon(raw);
        }
        if (typeDef.getContainingFile() != null && StvnPreludeBridge.isPreludeFile(typeDef.getContainingFile())) {
            return StvnPreludeBridge.getPreludePackagePath(typeDef) + "/" + stripColon(raw);
        }
        return raw;
    }

    private static String buildCanonicalConstantDefFqni(ConstantDefinition constDef) {
        var kw = constDef.getValueKeyword();
        String raw = kw != null ? kw.getText().trim() : "";
        if (raw.contains("/")) {
            return raw;
        }
        var pkg = PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            return buildConstantFqni(pkg.getPackagePath().getText().trim(), raw);
        }
        if (constDef.getContainingFile() != null && StvnPreludeBridge.isPreludeFile(constDef.getContainingFile())) {
            return buildConstantFqni(StvnPreludeBridge.getPreludePackagePath(constDef), raw);
        }
        return raw;
    }

    private static String qualifyTypeWithPackage(String packagePath, String remoteText) {
        if (remoteText.contains("/")) {
            return remoteText;
        }
        return packagePath + "/" + stripColon(remoteText);
    }

    private static String stripColon(String text) {
        return text.startsWith(StvnVocabulary.SIGIL_TYPIC) ? text.substring(1) : text;
    }

    private static String stripHash(String text) {
        return text.startsWith(StvnVocabulary.SIGIL_VALUE) ? text.substring(1) : text;
    }

    static String buildConstantFqni(String packagePath, String constantName) {
        if (constantName.contains("/")) {
            return constantName.startsWith(StvnVocabulary.SIGIL_TYPIC)
                ? StvnVocabulary.SIGIL_VALUE + constantName.substring(1)
                : constantName;
        }
        return StvnVocabulary.SIGIL_VALUE + stripColon(packagePath) + "/" + stripHash(constantName);
    }
}
