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
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.SchemaType;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

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
            boolean hasType = findPrimaryTypeEntry(file) != null;
            boolean hasBody = findPrimaryBodyEntry(file) != null;
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
        for (var def : PsiTreeUtil.findChildrenOfType(preludeFile, TypeDefinition.class)) {
            ProgressManager.checkCanceled();
            var kw = def.getTypeKeyword();
            if (kw != null) {
                var fullText = kw.getText().trim();
                var source = StvnPreludeBridge.getPreludePackagePath(def);
                var fqni = fullText.contains("/") ? fullText : source + "/" + stripColon(fullText);
                String resolvedType = resolveFormattedTypeStructure(def, new HashSet<>());
                results.add(new StvnNamespaceSymbolEntry(
                    fqni,
                    source,
                    StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                    resolvedType,
                    0,
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
                results.add(new StvnNamespaceSymbolEntry(
                    fqni,
                    source,
                    StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                    resolvedType,
                    0,
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
                    int depth = computeAliasHopCount(def, new HashSet<>());
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
                    results.add(new StvnNamespaceSymbolEntry(
                        name,
                        fileName,
                        StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH,
                        resolvedType,
                        0,
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
                                    results.add(new StvnNamespaceSymbolEntry(
                                        localName,
                                        targetPkg,
                                        upstreamFqni,
                                        resolvedType,
                                        1,
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
                                    results.add(new StvnNamespaceSymbolEntry(
                                        localName,
                                        targetPkg,
                                        upstreamConstFqni,
                                        resolvedType,
                                        1,
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
                    collectStrippedPackageSymbols(file, targetPkg, scopedProcessed, results, useElem);
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
                        1,
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
                        1,
                        kw,
                        false,
                        kw.getTextOffset(),
                        StvnNamespaceScope.DEFS
                    ));
                }
            }
        }

        // 5. Pure #DEFS / #DEFS_INCLUDE library schemas declaring only exported :package blocks
        boolean isPureDefsCatalog = findPrimaryTypeEntry(file) == null && findPrimaryBodyEntry(file) == null;
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
                                0,
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
                                0,
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
        record SchemaTask(SchemaType schema, int depth, PsiFile contextFile) implements ClosureTask {
        }

        record ConstantTask(ValueKeyword constRef, int depth, PsiFile contextFile) implements ClosureTask {
        }
    }

    private static List<StvnNamespaceSymbolEntry> collectTypeSymbols(PsiFile rootFile) {
        var typeEntry = findPrimaryTypeEntry(rootFile);
        if (typeEntry == null || typeEntry.getSchemaType() == null) {
            return List.of();
        }
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey = new LinkedHashMap<>();
        Queue<ClosureTask> queue = new ArrayDeque<>();
        queue.add(new ClosureTask.SchemaTask(typeEntry.getSchemaType(), 0, rootFile));
        runReachabilityClosure(rootFile, StvnNamespaceScope.TYPE, queue, byIdentityKey);
        return new ArrayList<>(byIdentityKey.values());
    }

    private static List<StvnNamespaceSymbolEntry> collectBodySymbols(PsiFile rootFile) {
        var bodyEntry = findPrimaryBodyEntry(rootFile);
        if (bodyEntry == null || bodyEntry.getValue() == null) {
            return List.of();
        }
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey = new LinkedHashMap<>();
        Queue<ClosureTask> queue = new ArrayDeque<>();

        var typeEntry = findPrimaryTypeEntry(rootFile);
        if (typeEntry != null && typeEntry.getSchemaType() != null) {
            queue.add(new ClosureTask.SchemaTask(typeEntry.getSchemaType(), 0, rootFile));
        }

        Value rootBodyVal = bodyEntry.getValue();
        for (ValueKeyword valKw : PsiTreeUtil.findChildrenOfType(rootBodyVal, ValueKeyword.class)) {
            if (isNonConstantValueKeyword(valKw)) {
                continue;
            }
            int bodyDepth = calculateBodyConstantDepth(valKw, rootBodyVal);
            queue.add(new ClosureTask.ConstantTask(valKw, bodyDepth, rootFile));
        }

        runReachabilityClosure(rootFile, StvnNamespaceScope.BODY, queue, byIdentityKey);
        return new ArrayList<>(byIdentityKey.values());
    }

    private static void runReachabilityClosure(
        PsiFile rootFile,
        StvnNamespaceScope targetScope,
        Queue<ClosureTask> queue,
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey
    ) {
        Set<PsiElement> visitedSchemas = new HashSet<>();
        Set<PsiElement> visitedDecls = new HashSet<>();

        while (!queue.isEmpty()) {
            ProgressManager.checkCanceled();
            ClosureTask task = queue.poll();
            if (task instanceof ClosureTask.SchemaTask schemaTask) {
                processSchemaClosureTask(
                    rootFile,
                    targetScope,
                    schemaTask.schema(),
                    schemaTask.depth(),
                    schemaTask.contextFile(),
                    queue,
                    byIdentityKey,
                    visitedSchemas,
                    visitedDecls
                );
            } else if (task instanceof ClosureTask.ConstantTask constTask) {
                processConstantClosureTask(
                    rootFile,
                    targetScope,
                    constTask.constRef(),
                    constTask.depth(),
                    queue,
                    byIdentityKey,
                    visitedDecls
                );
            }
        }
    }

    private static void processSchemaClosureTask(
        PsiFile rootFile,
        StvnNamespaceScope targetScope,
        SchemaType currentSchema,
        int currentDepth,
        PsiFile contextFile,
        Queue<ClosureTask> queue,
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey,
        Set<PsiElement> visitedSchemas,
        Set<PsiElement> visitedDecls
    ) {
        if (!visitedSchemas.add(currentSchema)) {
            return;
        }
        enqueueMetadataFacetConstants(currentSchema.getMetadataMap(), currentDepth + 1, contextFile, queue);

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
            if (targetTypeDef != null && !resolvedSym.isPrelude() && visitedDecls.add(targetTypeDef)) {
                PsiFile defFile = targetTypeDef.getContainingFile() != null
                    ? targetTypeDef.getContainingFile()
                    : contextFile;
                enqueueMetadataFacetConstants(targetTypeDef.getMetadataMap(), currentDepth + 1, defFile, queue);
                SchemaType rhsSchema = targetTypeDef.getSchemaType();
                if (rhsSchema != null) {
                    int nextDepth = rhsSchema.getSchemaConstructor() != null ? currentDepth : currentDepth + 1;
                    queue.add(new ClosureTask.SchemaTask(rhsSchema, nextDepth, defFile));
                }
            }
            return;
        }

        var ctor = currentSchema.getSchemaConstructor();
        if (ctor != null) {
            for (SchemaType child : collectDirectChildSchemas(ctor)) {
                queue.add(new ClosureTask.SchemaTask(child, currentDepth + 1, contextFile));
            }
        }
    }

    private static void processConstantClosureTask(
        PsiFile rootFile,
        StvnNamespaceScope targetScope,
        ValueKeyword constRef,
        int currentDepth,
        Queue<ClosureTask> queue,
        Map<String, StvnNamespaceSymbolEntry> byIdentityKey,
        Set<PsiElement> visitedDecls
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

        if (!resolvedConst.isPrelude() && visitedDecls.add(constDef)) {
            PsiFile defFile = constDef.getContainingFile() != null ? constDef.getContainingFile() : rootFile;
            enqueueMetadataFacetConstants(constDef.getMetadataMap(), currentDepth + 1, defFile, queue);
            if (constDef.getSchemaType() != null) {
                queue.add(new ClosureTask.SchemaTask(constDef.getSchemaType(), currentDepth + 1, defFile));
            }
            if (constDef.getValue() != null) {
                for (ValueKeyword nestedKw : PsiTreeUtil.findChildrenOfType(constDef.getValue(), ValueKeyword.class)) {
                    if (!isNonConstantValueKeyword(nestedKw)) {
                        queue.add(new ClosureTask.ConstantTask(nestedKw, currentDepth + 1, defFile));
                    }
                }
            }
        }
    }

    private static void enqueueMetadataFacetConstants(
        @Nullable MetadataMap metadataMap,
        int depth,
        PsiFile contextFile,
        Queue<ClosureTask> queue
    ) {
        if (metadataMap == null) {
            return;
        }
        for (var entry : metadataMap.getMetadataEntryList()) {
            var mv = entry.getMetadataValue();
            if (mv != null && mv.getValueKeyword() != null && !isNonConstantValueKeyword(mv.getValueKeyword())) {
                queue.add(new ClosureTask.ConstantTask(mv.getValueKeyword(), depth, contextFile));
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

    private static int calculateBodyConstantDepth(ValueKeyword valKw, Value rootBodyVal) {
        int depth = 0;
        PsiElement curr = valKw.getParent();
        while (curr != null && curr != rootBodyVal) {
            if (curr instanceof TupleLiteral || curr instanceof ListLiteral || curr instanceof MapLiteral) {
                depth++;
            }
            curr = curr.getParent();
        }
        if (depth == 0 && valKw.getParent() != rootBodyVal) {
            return 1;
        }
        return depth;
    }

    private static List<SchemaType> collectDirectChildSchemas(org.stvnadore.psi.SchemaConstructor ctor) {
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
            resolved = StvnTypeReference.resolveTypeInFile(contextFile, referenceKw.getText().trim(), new HashSet<>());
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
        String resolvedConstType = formatConstantResolvedType(constDef, rootFile);
        String identityKey = (defFile != null ? defFile.getName() : "") + "::" + canonicalConstFqni;

        ProjectedBinding rootBinding = findRootUseBindingForConstantFqni(rootFile, canonicalConstFqni);
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
        var schemaType = constDef.getSchemaType();
        if (schemaType == null) {
            return "";
        }
        var typeKw = schemaType.getTypeKeyword();
        if (typeKw != null) {
            var hops = StvnTypeReference.extractClickableResolutionHops(typeKw, true);
            if (!hops.isEmpty()) {
                List<String> labels = new ArrayList<>(hops.stream().map(StvnTypeReference.TraceHop::displayLabel).toList());
                String firstHop = labels.get(0);
                ProjectedBinding rootBinding = findRootUseBindingForTypeFqni(activeRootFile, firstHop);
                if (rootBinding != null && !rootBinding.localName().equals(firstHop)) {
                    String localSimple = StvnTypeReference.extractSimpleTypeName(rootBinding.localName());
                    String remoteSimple = StvnTypeReference.extractSimpleTypeName(firstHop);
                    if (!localSimple.equals(remoteSimple)) {
                        labels.add(0, rootBinding.localName());
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
            return formatConstantResolvedType(found, file);
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
        boolean hasSubsetFilter = hasEnumSubsetFacet(def.getMetadataMap()) || hasEnumSubsetFacet(schemaType.getMetadataMap());
        if (schemaType.getTypeKeyword() != null && !hasSubsetFilter) {
            String nextRaw = schemaType.getTypeKeyword().getText().trim();
            if (!StvnVocabulary.isBaseScalarType(nextRaw)
                    && StvnTypeReference.toShortPreludeTerminal(nextRaw) == null) {
                var resolved = new StvnTypeReference(schemaType.getTypeKeyword()).resolve();
                var parentDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
                if (parentDef != null) {
                    String parentStruct = resolveFormattedTypeStructure(parentDef, visited);
                    var ownMeta = def.getMetadataMap() != null ? def.getMetadataMap() : schemaType.getMetadataMap();
                    if (ownMeta != null && !parentStruct.startsWith(StvnVocabulary.DELIM_OPEN_BRACE)) {
                        return StvnSchemaFormatter.formatCompactCanonicalPsiSchema(ownMeta) + " " + parentStruct;
                    }
                    return parentStruct;
                }
            }
        }
        return StvnSchemaFormatter.formatCompactCanonicalPsiSchema(def);
    }

    private static boolean hasEnumSubsetFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return false;
        }
        String text = metaMap.getText();
        return text.contains(StvnVocabulary.FACET_KW_FILTER_INCL)
            || text.contains(StvnVocabulary.FACET_KW_FILTER_EXCL);
    }

    private static int computeAliasHopCount(TypeDefinition def, Set<PsiElement> visited) {
        if (!visited.add(def)) {
            return 0;
        }
        var schemaType = def.getSchemaType();
        if (schemaType == null || schemaType.getTypeKeyword() == null) {
            return 0;
        }
        var nextKw = schemaType.getTypeKeyword();
        String nextText = nextKw.getText().trim();
        if (StvnVocabulary.isBaseScalarType(nextText)
                || StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(nextText)) {
            return 0;
        }
        var resolved = new StvnTypeReference(nextKw).resolve();
        if (resolved instanceof TypeKeyword rKw && rKw.getParent() instanceof UseMapAlias ua) {
            var list = ua.getTypeKeywordList();
            if (!list.isEmpty() && list.get(0) != null) {
                var upstream = new StvnTypeReference(list.get(0)).resolve();
                var upstreamDef = StvnPsiUtils.resolveTargetTypeDefinition(upstream);
                if (upstreamDef != null) {
                    return 2 + computeAliasHopCount(upstreamDef, visited);
                }
            }
            return 2;
        }
        var parentDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
        if (parentDef != null) {
            return 1 + computeAliasHopCount(parentDef, visited);
        }
        return 1;
    }

    private static void collectStrippedPackageSymbols(
        PsiFile file,
        String targetPackagePath,
        Set<String> processedNames,
        List<StvnNamespaceSymbolEntry> results,
        @Nullable PsiElement originElement
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
                    collectStrippedElements(file, pkg, targetPackagePath, processedNames, results, originElement);
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
        @Nullable PsiElement originElement
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
                        1,
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
                        formatConstantResolvedType(constDef, activeRootFile),
                        1,
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
