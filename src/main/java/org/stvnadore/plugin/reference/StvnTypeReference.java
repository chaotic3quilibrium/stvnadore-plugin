package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves STVN TypeKeyword PSI elements to their target TypeDefinition declarations.
 */
@NullMarked
public final class StvnTypeReference extends PsiReferenceBase<TypeKeyword> implements PsiPolyVariantReference {

    /**
     * Constructs an StvnTypeReference for the given TypeKeyword element.
     *
     * @param element the TypeKeyword element
     */
    public StvnTypeReference(TypeKeyword element) {
        super(element, new TextRange(0, element.getTextLength()));
    }

    /**
     * Handles renaming of the referenced element by replacing the TypeKeyword PSI element.
     * Preserves modular path prefixes when renaming leaf type symbols.
     *
     * @param newElementName the new name string to assign to the type
     * @return the newly created TypeKeyword PSI element
     * @throws IncorrectOperationException if the element replacement fails
     */
    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
        if (newElementName.startsWith(StvnVocabulary.SIGIL_TYPIC) || newElementName.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            throw new IncorrectOperationException("Identifier must be a bare name without ':' or '#' prefix: " + newElementName);
        }
        var element = getElement();
        var currentText = element.getText();
        String replacementText;
        if (currentText.contains("/")) {
            var lastSlashIndex = currentText.lastIndexOf('/');
            var prefix = currentText.substring(0, lastSlashIndex + 1);
            replacementText = prefix + newElementName;
        } else {
            replacementText = StvnVocabulary.SIGIL_TYPIC + newElementName;
        }
        var newKeyword = org.stvnadore.plugin.psi.StvnElementFactory.createTypeKeyword(element.getProject(), replacementText);
        return element.replace(newKeyword);
    }

    /**
     * Evaluates whether this reference points to the specified target element,
     * matching both direct keyword declarations and enclosing definition containers.
     *
     * @param element the potential target declaration element
     * @return {@code true} if this reference resolves to the element or its declared name token
     */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        var resolved = resolve();
        if (resolved == null) {
            return false;
        }
        var manager = getElement().getManager();
        if (manager.areElementsEquivalent(resolved, element)) {
            return true;
        }
        if (resolved instanceof TypeDefinition typeDef) {
            if (manager.areElementsEquivalent(typeDef.getTypeKeyword(), element)
                || manager.areElementsEquivalent(typeDef.getNameIdentifier(), element)) {
                return true;
            }
        }
        if (element instanceof TypeDefinition typeDef) {
            return manager.areElementsEquivalent(resolved, typeDef.getTypeKeyword())
                || manager.areElementsEquivalent(resolved, typeDef.getNameIdentifier());
        }
        if (element instanceof org.stvnadore.psi.UseMapAlias alias) {
            return manager.areElementsEquivalent(resolved, alias.getNameIdentifier());
        }
        if (StvnPreludeBridge.isPreludeFile(resolved.getContainingFile())
            && StvnPreludeBridge.isPreludeFile(element.getContainingFile())) {
            String resolvedText = (resolved instanceof TypeKeyword tk) ? tk.getText()
                : (resolved instanceof TypeDefinition td && td.getTypeKeyword() != null ? td.getTypeKeyword().getText() : null);
            String elemText = (element instanceof TypeKeyword tk) ? tk.getText()
                : (element instanceof TypeDefinition td && td.getTypeKeyword() != null ? td.getTypeKeyword().getText() : null);
            if (resolvedText != null && resolvedText.equals(elemText)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable PsiElement resolve() {
        var results = multiResolve(false);
        return results.length == 1 ? results[0].getElement() : null;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        var keyword = getElement();
        var parent = keyword.getParent();

        // 1. Declarations should not resolve to anything else
        if (org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(keyword)) {
            return ResolveResult.EMPTY_ARRAY;
        }
        if (PsiTreeUtil.getParentOfType(keyword, org.stvnadore.psi.PackagePath.class, org.stvnadore.psi.UseTargetIllegal.class) != null) {
            return ResolveResult.EMPTY_ARRAY;
        }

        if (parent instanceof org.stvnadore.psi.UseMapAlias alias) {
            var list = alias.getTypeKeywordList();
            if (list.size() >= 2) {
                if (list.get(0) == keyword) {
                    var useElem = PsiTreeUtil.getParentOfType(alias, org.stvnadore.psi.UseElement.class);
                    if (useElem != null && useElem.getUseTarget() != null) {
                        var targetPackage = useElem.getUseTarget().getText();
                        var targetTypeName = keyword.getText();
                        var bare = targetTypeName.startsWith(StvnVocabulary.SIGIL_TYPIC) ? targetTypeName.substring(1) : targetTypeName;
                        var fqni = targetPackage + "/" + bare;
                        if (":org/stvnadore/prelude".equals(targetPackage) || targetPackage.startsWith(":org/stvnadore/prelude/")) {
                            var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(keyword.getProject(), fqni);
                            if (preludeDef == null) {
                                preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(keyword.getProject(), targetTypeName);
                            }
                            if (preludeDef != null && preludeDef.getTypeKeyword() != null) {
                                return new ResolveResult[]{new PsiElementResolveResult(preludeDef.getTypeKeyword())};
                            }
                        }
                        var containingFile = keyword.getContainingFile();
                        if (containingFile != null) {
                            var resolved = StvnTypeReference.resolveTypeInFile(containingFile, fqni, new HashSet<>());
                            if (resolved == null && targetTypeName.substring(1).contains("/")) {
                                resolved = StvnTypeReference.resolveTypeInFile(containingFile, targetTypeName, new HashSet<>());
                            }
                            if (resolved != null) {
                                return new ResolveResult[]{new PsiElementResolveResult(resolved)};
                            }
                        }
                    }
                    return ResolveResult.EMPTY_ARRAY;
                } else {
                    return ResolveResult.EMPTY_ARRAY;
                }
            } else {
                return ResolveResult.EMPTY_ARRAY;
            }
        }

        var containingFile = keyword.getContainingFile();
        if (containingFile == null) {
            return ResolveResult.EMPTY_ARRAY;
        }

        // 2. Lexical Package Scope Priority: check sibling definitions in enclosing package
        var resolved = resolveTypeKeywordWithContext(keyword, null, true);
        return resolved != null ? new ResolveResult[]{new PsiElementResolveResult(resolved)} : ResolveResult.EMPTY_ARRAY;
    }

    /**
     * Resolves a {@link TypeKeyword} reference without consulting the synthetic bare-name Prelude fallback,
     * returning {@code null} when a bare Prelude type is neither locally shadowed nor imported via {@code :use} or {@code :include}.
     *
     * @param keyword candidate usage-site {@link TypeKeyword}
     * @return resolved declaration element, or {@code null} when unresolved without implicit Prelude fallback
     */
    public static @Nullable PsiElement resolveTypeKeywordWithoutImplicitPrelude(TypeKeyword keyword) {
        return resolveTypeKeywordWithContext(keyword, null, false);
    }

    private record ScopedTypeLookup(@Nullable PsiElement resolved, boolean blockedByCollision) {}

    /**
     * Resolves a raw {@code :Type} identifier from an arbitrary PSI {@code contextSite},
     * enforcing enclosing {@link PackageEnclosure} lexical isolation, package-local {@code :use}
     * directives, and root {@code :defs} {@code #strip} eviction rules.
     *
     * @param contextSite originating PSI element (or {@link PsiFile} for root-scope queries)
     * @param rawName     candidate raw or qualified type token
     * @return resolved declaration PSI element, or {@code null} when unresolved
     */
    public static @Nullable PsiElement resolveTypeAtContext(
            @Nullable PsiElement contextSite,
            @Nullable String rawName
    ) {
        return resolveTypeAtContextInternal(contextSite, rawName, null, true).resolved();
    }

    /**
     * Resolves the underlying {@link TypeDefinition} for {@code rawName} at {@code contextSite},
     * unwrapping {@link org.stvnadore.psi.UseMapAlias} hops and Standard Library Prelude fallbacks
     * while failing closed when a {@code :use} collision blocks resolution.
     *
     * @param contextSite originating PSI element (or {@link PsiFile} for root-scope queries)
     * @param rawName     candidate raw or qualified type token
     * @return resolved {@link TypeDefinition}, or {@code null} when unresolvable
     */
    public static @Nullable TypeDefinition resolveTypeDefinitionAtContext(
            @Nullable PsiElement contextSite,
            @Nullable String rawName
    ) {
        if (contextSite == null || rawName == null || rawName.isBlank()) {
            return null;
        }
        String trimmed = rawName.trim();
        var outcome = resolveTypeAtContextInternal(contextSite, trimmed, null, true);
        if (outcome.blockedByCollision()) {
            return null;
        }
        var td = StvnPsiUtils.resolveTargetTypeDefinition(outcome.resolved());
        if (td != null) {
            return td;
        }
        if (outcome.resolved() == null) {
            var project = contextSite.getProject();
            if (!project.isDisposed()) {
                return StvnPreludeBridge.resolvePreludeTypeDefinition(project, trimmed);
            }
        }
        return null;
    }

    private static ScopedTypeLookup resolveTypeAtContextInternal(
            @Nullable PsiElement contextSite,
            @Nullable String rawName,
            @Nullable List<String> trace,
            boolean allowImplicitPreludeFallback
    ) {
        if (contextSite == null || rawName == null || rawName.isBlank()) {
            return new ScopedTypeLookup(null, false);
        }
        PsiFile containingFile = contextSite instanceof PsiFile pf ? pf : contextSite.getContainingFile();
        if (containingFile == null) {
            return new ScopedTypeLookup(null, false);
        }
        String targetTypeName = rawName.trim();
        if (contextSite instanceof TypeKeyword kw
                && kw.getParent() instanceof org.stvnadore.psi.UseMapAlias alias) {
            var kws = alias.getTypeKeywordList();
            if (!kws.isEmpty() && kws.get(0) == kw) {
                var useElem = PsiTreeUtil.getParentOfType(alias, org.stvnadore.psi.UseElement.class);
                if (useElem != null && useElem.getUseTarget() != null && useElem.getUseTarget().getUseTargetIllegal() == null) {
                    var bare = targetTypeName.startsWith(StvnVocabulary.SIGIL_TYPIC)
                        ? targetTypeName.substring(1)
                        : targetTypeName;
                    var fqni = useElem.getUseTarget().getText().trim() + "/" + bare;
                    return new ScopedTypeLookup(
                        resolveTypeInFile(containingFile, fqni, new HashSet<>(), trace, false, allowImplicitPreludeFallback),
                        false
                    );
                }
            }
        }

        var enclosingPkg = contextSite instanceof PsiFile
            ? null
            : PsiTreeUtil.getParentOfType(contextSite, PackageEnclosure.class, false);
        if (enclosingPkg != null
                && (!targetTypeName.startsWith(StvnVocabulary.SIGIL_TYPIC) || !targetTypeName.substring(1).contains("/"))) {
            for (var elem : enclosingPkg.getPackageElementList()) {
                var typeDef = elem.getTypeDefinition();
                if (typeDef != null) {
                    var defKw = typeDef.getTypeKeyword();
                    if (defKw != null && defKw != contextSite && defKw.getText().trim().equals(targetTypeName)) {
                        return new ScopedTypeLookup(defKw, false);
                    }
                }
            }
            var pkgPath = enclosingPkg.getPackagePath();
            if (!StvnPsiUtils.hasIllegalTrailingSlash(enclosingPkg) && pkgPath != null) {
                var bare = targetTypeName.startsWith(StvnVocabulary.SIGIL_TYPIC)
                    ? targetTypeName.substring(1)
                    : targetTypeName;
                var fqni = pkgPath.getText().trim() + "/" + bare;
                var resolvedSamePkg = resolveTypeInFile(containingFile, fqni, new HashSet<>(), null, false, false);
                if (resolvedSamePkg != null && resolvedSamePkg != contextSite) {
                    return new ScopedTypeLookup(resolvedSamePkg, false);
                }
            }
            var pkgUseStmts = new ArrayList<org.stvnadore.psi.UseStmt>();
            for (var elem : enclosingPkg.getPackageElementList()) {
                if (elem.getUseStmt() != null) {
                    pkgUseStmts.add(elem.getUseStmt());
                }
            }
            var pkgUseResult = resolveFromUseStmts(containingFile, pkgUseStmts, targetTypeName, trace);
            if (pkgUseResult.collisionOrEvicted()) {
                return new ScopedTypeLookup(null, true);
            }
            if (pkgUseResult.resolved() != null) {
                return new ScopedTypeLookup(pkgUseResult.resolved(), false);
            }
        }

        var rootResolved = resolveTypeInFile(
            containingFile,
            targetTypeName,
            new HashSet<>(),
            trace,
            false,
            allowImplicitPreludeFallback
        );
        if (rootResolved == null && hasRootUseCollision(containingFile, targetTypeName)) {
            return new ScopedTypeLookup(null, true);
        }
        return new ScopedTypeLookup(rootResolved, false);
    }

    private static boolean hasRootUseCollision(PsiFile file, String targetName) {
        if (!targetName.startsWith(StvnVocabulary.SIGIL_TYPIC) || targetName.substring(1).contains("/")) {
            return false;
        }
        for (var def : PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(def)
                    || PsiTreeUtil.getParentOfType(def, PackageEnclosure.class) != null) {
                continue;
            }
            var kw = def.getTypeKeyword();
            if (kw != null && kw.getText().trim().equals(targetName)) {
                return false;
            }
        }
        var useStmts = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class).stream()
            .filter(use -> PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) == null)
            .filter(use -> !StvnMetaTemplateUtil.isInSecondaryRootSection(use))
            .toList();
        return resolveFromUseStmts(file, useStmts, targetName, null).collisionOrEvicted();
    }

    private static @Nullable PsiElement resolveTypeKeywordWithContext(
            TypeKeyword keyword,
            @Nullable List<String> trace,
            boolean allowImplicitPreludeFallback
    ) {
        return resolveTypeAtContextInternal(keyword, keyword.getText().trim(), trace, allowImplicitPreludeFallback).resolved();
    }

    /**
     * Resolves a type name in the given file.
     *
     * @param file the PSI file to search
     * @param targetName the target type name to resolve
     * @param visited set of already visited files to prevent cycles
     * @return the resolved target PSI element, or null if not found
     */
    public static @Nullable PsiElement resolveTypeInFile(PsiFile file, String targetName, Set<PsiFile> visited) {
        return resolveTypeInFile(file, targetName, visited, null);
    }

    /**
     * Resolves a type name in the given file while recording resolution trace.
     *
     * @param file the PSI file to search
     * @param targetName the target type name to resolve
     * @param visited set of already visited files to prevent cycles
     * @param trace list to accumulate resolution trace steps
     * @return the resolved target PSI element, or null if not found
     */
    public static @Nullable PsiElement resolveTypeInFile(PsiFile file, String targetName, Set<PsiFile> visited, @Nullable List<String> trace) {
        return resolveTypeInFile(file, targetName, visited, trace, false);
    }

    /**
     * Resolves a type name in the given file with qualification control.
     *
     * @param file the PSI file to search
     * @param targetName the target type name to resolve
     * @param visited set of already visited files to prevent cycles
     * @param trace list to accumulate resolution trace steps
     * @param allowUnqualifiedPackaged whether to match unqualified names in packages
     * @return the resolved target PSI element, or null if not found
     */
    public static @Nullable PsiElement resolveTypeInFile(PsiFile file, String targetName, Set<PsiFile> visited, @Nullable List<String> trace, boolean allowUnqualifiedPackaged) {
        return resolveTypeInFile(file, targetName, visited, trace, allowUnqualifiedPackaged, true);
    }

    private static @Nullable PsiElement resolveTypeInFile(
            PsiFile file,
            String targetName,
            Set<PsiFile> visited,
            @Nullable List<String> trace,
            boolean allowUnqualifiedPackaged,
            boolean allowImplicitPreludeFallback
    ) {
        if (!visited.add(file)) {
            return null;
        }

        // Local prelude file short-circuit: resolve directly within local file AST
        if (StvnPreludeBridge.isPreludeFile(file)) {
            var localDef = StvnPreludeBridge.resolvePreludeTypeDefinition(file, targetName);
            if (localDef != null && localDef.getTypeKeyword() != null) {
                return localDef.getTypeKeyword();
            }
        }

        // 1. Local definitions scanning
        var definitions = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class).stream()
            .filter(def -> !StvnMetaTemplateUtil.isInSecondaryRootSection(def))
            .toList();
        for (var def : definitions) {
            // Ignore definitions inside package enclosures when searching for bare root names
            if (PsiTreeUtil.getParentOfType(def, org.stvnadore.psi.PackageEnclosure.class) != null && !allowUnqualifiedPackaged) {
                continue;
            }
            var keyword = def.getTypeKeyword();
            if (keyword != null && keyword.getText().equals(targetName)) {
                return keyword;
            }
        }

        // 1b. Package enclosure scanning (FQNI expansion and in-scope relative names)
        var packages = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class).stream()
            .filter(pkg -> !StvnMetaTemplateUtil.isInSecondaryRootSection(pkg))
            .toList();
        for (var pkg : packages) {
            if (StvnPsiUtils.hasIllegalTrailingSlash(pkg)) continue;
            var pkgPath = pkg.getPackagePath();
            if (pkgPath == null) continue;
            var pathText = pkgPath.getText();
            for (var elem : pkg.getPackageElementList()) {
                var typeDef = elem.getTypeDefinition();
                if (typeDef != null) {
                    var kw = typeDef.getTypeKeyword();
                    if (kw != null) {
                        var kwText = kw.getText();
                        var fqni = pathText + "/" + (kwText.startsWith(StvnVocabulary.SIGIL_TYPIC) ? kwText.substring(1) : kwText);
                        if (fqni.equals(targetName) || (allowUnqualifiedPackaged && kwText.equals(targetName))) {
                            return kw;
                        }
                    }
                }
            }
        }

        // 1c. Scoped :use statement scanning (root-level only; package-local :use stays isolated inside its package)
        var useStmts = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class).stream()
            .filter(use -> PsiTreeUtil.getParentOfType(use, org.stvnadore.psi.PackageEnclosure.class) == null)
            .filter(use -> !StvnMetaTemplateUtil.isInSecondaryRootSection(use))
            .toList();
        var rootUseResult = resolveFromUseStmts(file, useStmts, targetName, trace);
        if (rootUseResult.collisionOrEvicted()) {
            return null;
        }
        if (rootUseResult.resolved() != null) {
            return rootUseResult.resolved();
        }

        // 1d. Explicit Standard Library Prelude FQNI resolution (:org/stvnadore/prelude/*)
        if (targetName.startsWith(StvnVocabulary.SIGIL_TYPIC + "org/stvnadore/prelude/")) {
            var explicitPrelude = StvnPreludeBridge.resolvePreludeType(file.getProject(), targetName);
            if (explicitPrelude != null) {
                return explicitPrelude;
            }
        }

        // 2. Cycle-safe diamond :include resolution with origin deduplication
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class).stream()
            .filter(incl -> !StvnMetaTemplateUtil.isInSecondaryRootSection(incl))
            .toList();
        record IncludeCandidate(PsiElement element, List<String> branchTrace) {}
        var uniqueCandidates = new ArrayList<IncludeCandidate>();
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit == null) {
                continue;
            }
            var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
            if (targetFile == null) {
                continue;
            }

            var branchVisited = new HashSet<>(visited);
            var branchTrace = trace != null ? new ArrayList<String>() : null;

            var resolved = resolveTypeInFile(targetFile, targetName, branchVisited, branchTrace, false, false);
            if (resolved != null
                    && uniqueCandidates.stream().noneMatch(c -> areSameDeclarationTarget(c.element(), resolved))) {
                uniqueCandidates.add(new IncludeCandidate(resolved, branchTrace != null ? branchTrace : List.of()));
            }
        }

        if (uniqueCandidates.size() > 1) {
            return null;
        }
        if (uniqueCandidates.size() == 1) {
            var winner = uniqueCandidates.get(0);
            if (trace != null) {
                trace.addAll(winner.branchTrace());
            }
            return winner.element();
        }

        // 4. Implicit bare-name Standard Library Prelude fallback (after :include modules)
        if (allowImplicitPreludeFallback && !targetName.contains("/")) {
            return StvnPreludeBridge.resolvePreludeType(file.getProject(), targetName);
        }
        return null;
    }

    /**
     * Result of evaluating a scope's {@code :use} directives under the § 4.2 Namespace Eviction Cascade.
     *
     * @param resolved           unique resolved PSI target, or {@code null}
     * @param collisionOrEvicted {@code true} when multiple distinct targets collide in {@code :use}
     */
    public record UseScopeResolution(@Nullable PsiElement resolved, boolean collisionOrEvicted) {}

    /**
     * Evaluates a list of lexical-scope {@code :use} statements using the exact § 4.2 Namespace Eviction Cascade
     * ({@code RENAMED_IMPORT_RHS} priority, per-statement LHS alias eviction under combined {@code { #strip }},
     * and diamond raw-origin deduplication).
     *
     * @param file       containing PSI file
     * @param useStmts   in-scope {@code :use} statements
     * @param targetName queried bare type identifier
     * @param trace      optional resolution trace accumulator
     * @return {@link UseScopeResolution} outcome
     */
    public static UseScopeResolution resolveFromUseStmts(
            PsiFile file,
            List<org.stvnadore.psi.UseStmt> useStmts,
            String targetName,
            @Nullable List<String> trace
    ) {
        if (!targetName.startsWith(StvnVocabulary.SIGIL_TYPIC) || targetName.substring(1).contains("/")) {
            return new UseScopeResolution(null, false);
        }
        record RhsClaim(TypeKeyword localKw, @Nullable String remoteText) {}
        var rhsClaims = new ArrayList<RhsClaim>();
        var rawClaims = new ArrayList<PsiElement>();
        var seenValidTargets = new HashSet<String>();

        for (var use : useStmts) {
            for (var useElem : use.getUseElementList()) {
                var useTarget = useElem.getUseTarget();
                if (useTarget == null || useTarget.getUseTargetIllegal() != null) {
                    continue;
                }
                var prefix = useTarget.getText().trim();
                if (prefix.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(prefix)) {
                    continue;
                }
                if (isStructurallyValidUseElementForDedup(useElem) && !seenValidTargets.add(prefix)) {
                    continue;
                }
                var fqni = prefix + "/" + targetName.substring(1);

                boolean lhsRenamedInThisUse = false;
                var seenCanonicalPairs = new HashSet<String>();
                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var list = alias.getTypeKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            var localKw = list.get(1);
                            if (remoteKw != null && localKw != null) {
                                var remoteText = remoteKw.getText().trim();
                                var localText = localKw.getText().trim();
                                var canonicalLhs = remoteText.indexOf('/') >= 0
                                    ? remoteText
                                    : (prefix + "/" + remoteText.substring(1));
                                if (!seenCanonicalPairs.add(canonicalLhs + "=>" + localText)) {
                                    continue;
                                }
                                int slash = remoteText.lastIndexOf('/');
                                String bareRemote = slash >= 0
                                    ? (StvnVocabulary.SIGIL_TYPIC + remoteText.substring(slash + 1))
                                    : remoteText;
                                if (bareRemote.equals(targetName) || remoteText.equals(fqni)) {
                                    lhsRenamedInThisUse = true;
                                }
                                if (localText.equals(targetName)) {
                                    rhsClaims.add(new RhsClaim(localKw, remoteText));
                                }
                            }
                        }
                    }
                }

                var optBlock = useElem.getUseOptionsBlock();
                if (!lhsRenamedInThisUse && StvnPsiUtils.hasValidStripOption(optBlock)) {
                    var resolved = resolveTypeInFile(file, fqni, new HashSet<>(), null, false, false);
                    if (resolved != null && rawClaims.stream().noneMatch(existing -> areSameDeclarationTarget(existing, resolved))) {
                        rawClaims.add(resolved);
                    }
                }
            }
        }

        int totalActive = rhsClaims.size() + rawClaims.size();
        if (totalActive > 1) {
            return new UseScopeResolution(null, true);
        }
        if (rhsClaims.size() == 1) {
            var winner = rhsClaims.get(0);
            if (trace != null && winner.remoteText() != null) {
                trace.add(winner.remoteText());
            }
            return new UseScopeResolution(winner.localKw(), false);
        }
        if (rawClaims.size() == 1) {
            return new UseScopeResolution(rawClaims.get(0), false);
        }
        return new UseScopeResolution(null, false);
    }

    /**
     * Determines whether {@code useElem} is structurally well-formed so duplicate same-scope
     * {@code :use} entries ({@code E005}) can be deduplicated without counting fault-isolated
     * empty ({@code E004}) or malformed ({@code E008} / {@code E002}) directive blocks.
     *
     * @param useElem candidate {@code :use} element
     * @return {@code true} when structurally valid
     */
    public static boolean isStructurallyValidUseElementForDedup(org.stvnadore.psi.UseElement useElem) {
        var useTarget = useElem.getUseTarget();
        if (useTarget == null
                || useTarget.getUseTargetIllegal() != null
                || org.stvnadore.plugin.validation.StvnTrailingSlashInspection.hasTrailingSlash(useTarget)
                || PsiTreeUtil.hasErrorElements(useElem)) {
            return false;
        }
        String pkg = useTarget.getText().trim();
        if (pkg.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(pkg)) {
            return false;
        }
        var optBlock = useElem.getUseOptionsBlock();
        var aliasBlock = useElem.getUseAliasBlock();
        if (optBlock == null && aliasBlock == null) {
            return false;
        }
        if (optBlock != null && !StvnPsiUtils.hasValidStripOption(optBlock)) {
            return false;
        }
        return aliasBlock == null || !aliasBlock.getUseMapAliasList().isEmpty();
    }

    static boolean areSameDeclarationTarget(PsiElement a, PsiElement b) {
        if (a == b) {
            return true;
        }
        var manager = a.getManager();
        return manager != null && manager.areElementsEquivalent(a, b);
    }

    /**
     * Represents a single display segment and its optional declaration PSI target along a nominal type chain.
     *
     * @param displayLabel  rendered type or constructor label
     * @param targetElement navigable PSI declaration target, or {@code null} for primitive or constructor terminals
     */
    public record TraceHop(String displayLabel, @Nullable PsiElement targetElement) {
    }

    private static final String PRELUDE_NAMESPACE_PREFIX =
        StvnVocabulary.PRELUDE_TYPE_POSIX_ID.substring(0, StvnVocabulary.PRELUDE_TYPE_POSIX_ID.lastIndexOf('/') + 1);
    private static final String PRELUDE_META_NAMESPACE_PREFIX =
        StvnVocabulary.META_TYPE_POSIX_ID_64.substring(0, StvnVocabulary.META_TYPE_POSIX_ID_64.lastIndexOf('/') + 1);

    /**
     * Extracts ordered, clickable resolution hops starting from {@code startKeyword}, qualifying
     * {@code :use} targets to canonical package FQNIs, prepending local aliases strictly when
     * {@code localSimpleName != targetSimpleName}, and optionally appending composite or collection
     * constructor terminals.
     *
     * @param startKeyword            starting {@link TypeKeyword} PSI node
     * @param appendCompositeTerminal {@code true} to append composite or collection constructor terminals
     * @return ordered list of {@link TraceHop} entries
     */
    public static List<TraceHop> extractClickableResolutionHops(
        TypeKeyword startKeyword,
        boolean appendCompositeTerminal
    ) {
        var hops = new ArrayList<TraceHop>();
        String startText = startKeyword.getText().trim();
        if (EXACT_TERMINAL_TYPE_NAMES.contains(startText)) {
            hops.add(new TraceHop(startText, resolvePreludeKeywordTarget(startKeyword.getProject(), startText)));
            return List.copyOf(hops);
        }

        var visitedUseAliases = new HashSet<PsiElement>();
        PsiElement currentElement;
        if (StvnPsiUtils.isTypeDefinitionTarget(startKeyword)) {
            String qualifiedStart = qualifyTypeDefinitionKeyword(startKeyword);
            String preludeShort = toShortPreludeTerminal(qualifiedStart);
            if (preludeShort != null) {
                hops.add(new TraceHop(preludeShort, startKeyword));
                return List.copyOf(hops);
            }
            hops.add(new TraceHop(qualifiedStart, startKeyword));
            currentElement = startKeyword;
        } else if (startKeyword.getParent() instanceof org.stvnadore.psi.UseMapAlias directAlias
                && directAlias.getTypeKeywordList().size() >= 2
                && directAlias.getTypeKeywordList().get(1) == startKeyword) {
            currentElement = appendUseMapAliasHeadHops(hops, startText, startKeyword, directAlias, visitedUseAliases);
        } else {
            currentElement = resolveTypeKeywordWithContext(startKeyword, null, true);
            if (currentElement instanceof TypeKeyword resolvedKw
                && resolvedKw.getParent() instanceof org.stvnadore.psi.UseMapAlias useAlias
                && useAlias.getTypeKeywordList().size() >= 2
                && useAlias.getTypeKeywordList().get(1) == resolvedKw) {
                currentElement = appendUseMapAliasHeadHops(hops, startText, resolvedKw, useAlias, visitedUseAliases);
            } else if (currentElement instanceof TypeKeyword targetDefKw) {
                String qualified = qualifyTypeDefinitionKeyword(targetDefKw);
                String preludeShort = toShortPreludeTerminal(qualified);
                if (preludeShort != null) {
                    hops.add(new TraceHop(preludeShort, targetDefKw));
                    return List.copyOf(hops);
                }
                hops.add(new TraceHop(qualified, targetDefKw));
            } else {
                String preludeShort = toShortPreludeTerminal(startText);
                if (preludeShort != null) {
                    hops.add(new TraceHop(
                        preludeShort,
                        resolvePreludeKeywordTarget(startKeyword.getProject(), preludeShort)
                    ));
                    return List.copyOf(hops);
                }
                hops.add(new TraceHop(startText, currentElement));
            }
        }

        var visited = new HashSet<PsiElement>();
        if (currentElement != null) {
            visited.add(currentElement);
        }

        while (currentElement != null) {
            var typeDef = StvnPsiUtils.getParentTypeDefinition(currentElement);
            if (typeDef == null) {
                break;
            }
            var schemaType = typeDef.getSchemaType();
            if (schemaType == null) {
                break;
            }
            var nextKw = schemaType.getTypeKeyword();
            if (nextKw != null) {
                String rawNext = nextKw.getText().trim();
                String shortRawNext = toShortPreludeTerminal(rawNext);
                if (shortRawNext != null) {
                    hops.add(new TraceHop(shortRawNext, resolvePreludeKeywordTarget(nextKw.getProject(), shortRawNext)));
                    break;
                }
                if (isPrimitiveTypeName(rawNext)) {
                    hops.add(new TraceHop(formatAtomicTerminal(rawNext, schemaType), null));
                    break;
                }
                var resolvedNext = resolveTypeKeywordWithContext(nextKw, null, true);
                if (resolvedNext instanceof TypeKeyword resolvedNextKw
                    && resolvedNextKw.getParent() instanceof org.stvnadore.psi.UseMapAlias nextUseAlias
                    && nextUseAlias.getTypeKeywordList().size() >= 2
                    && nextUseAlias.getTypeKeywordList().get(1) == resolvedNextKw) {
                    var nextTarget = appendUseMapAliasHeadHops(
                        hops,
                        rawNext,
                        resolvedNextKw,
                        nextUseAlias,
                        visitedUseAliases
                    );
                    if (nextTarget != null && visited.add(nextTarget)) {
                        currentElement = nextTarget;
                        continue;
                    }
                    break;
                }
                if (resolvedNext instanceof TypeKeyword resolvedDefKw && visited.add(resolvedDefKw)) {
                    String qualifiedNext = qualifyTypeDefinitionKeyword(resolvedDefKw);
                    String preludeShortNext = toShortPreludeTerminal(qualifiedNext);
                    if (preludeShortNext != null) {
                        hops.add(new TraceHop(preludeShortNext, resolvedDefKw));
                        break;
                    }
                    hops.add(new TraceHop(qualifiedNext, resolvedDefKw));
                    currentElement = resolvedDefKw;
                    continue;
                }
                hops.add(new TraceHop(rawNext, resolvedNext));
                break;
            }

            var ctor = schemaType.getSchemaConstructor();
            if (ctor != null) {
                if (ctor.getAtomicType() != null) {
                    String atomicName = ctor.getAtomicType().getText().trim();
                    hops.add(new TraceHop(formatAtomicTerminal(atomicName, schemaType), null));
                } else if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
                    hops.add(new TraceHop(StvnVocabulary.TYPE_ENUM, null));
                } else if (appendCompositeTerminal) {
                    if (ctor.getProductType() != null) {
                        hops.add(new TraceHop(StvnVocabulary.TYPE_TUPLE, null));
                    } else if (ctor.getCollectionType() != null && ctor.getCollectionType().getFirstChild() != null) {
                        hops.add(new TraceHop(ctor.getCollectionType().getFirstChild().getText().trim(), null));
                    }
                }
            }
            break;
        }
        return List.copyOf(hops);
    }

    /**
     * Extracts the unqualified simple type identifier without leading {@code :} sigil or package path.
     *
     * @param typeToken candidate STVN type token
     * @return simple type identifier (for example, {@code "CoolantValveMode"} from {@code ":cooling/manifold/CoolantValveMode"})
     */
    public static String extractSimpleTypeName(String typeToken) {
        String trimmed = typeToken.trim();
        String withoutSigil = trimmed.startsWith(StvnVocabulary.SIGIL_TYPIC)
            ? trimmed.substring(StvnVocabulary.SIGIL_TYPIC.length())
            : trimmed;
        int slashIdx = withoutSigil.lastIndexOf('/');
        return slashIdx >= 0 ? withoutSigil.substring(slashIdx + 1) : withoutSigil;
    }

    /**
     * Normalizes a canonical Prelude or Meta-Substrate FQNI to its short terminal identifier when
     * registered in {@link #EXACT_TERMINAL_TYPE_NAMES}.
     *
     * @param qualifiedName candidate qualified or short type token
     * @return short Prelude terminal name (for example, {@code ":Port"}), or {@code null} if not a terminal
     */
    public static @Nullable String toShortPreludeTerminal(String qualifiedName) {
        String trimmed = qualifiedName.trim();
        if (trimmed.startsWith(PRELUDE_META_NAMESPACE_PREFIX)) {
            String candidate = StvnVocabulary.SIGIL_TYPIC + trimmed.substring(PRELUDE_META_NAMESPACE_PREFIX.length());
            return EXACT_TERMINAL_TYPE_NAMES.contains(candidate) ? candidate : null;
        }
        if (trimmed.startsWith(PRELUDE_NAMESPACE_PREFIX)) {
            String candidate = StvnVocabulary.SIGIL_TYPIC + trimmed.substring(PRELUDE_NAMESPACE_PREFIX.length());
            return EXACT_TERMINAL_TYPE_NAMES.contains(candidate) ? candidate : null;
        }
        return EXACT_TERMINAL_TYPE_NAMES.contains(trimmed) ? trimmed : null;
    }

    private static @Nullable PsiElement resolvePreludeKeywordTarget(
        com.intellij.openapi.project.Project project,
        String shortPreludeName
    ) {
        if (project.isDisposed() || !shortPreludeName.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
            return null;
        }
        var preludeTarget = StvnPreludeBridge.resolvePreludeTypeDefinition(project, shortPreludeName);
        return preludeTarget != null ? preludeTarget.getTypeKeyword() : null;
    }

    private static @Nullable PsiElement appendUseMapAliasHeadHops(
        List<TraceHop> hops,
        String localAliasText,
        TypeKeyword useAliasRhsKw,
        org.stvnadore.psi.UseMapAlias useAlias,
        Set<PsiElement> visitedUseAliases
    ) {
        if (!visitedUseAliases.add(useAlias)) {
            hops.add(new TraceHop(localAliasText, useAliasRhsKw));
            return null;
        }
        var remoteKw = useAlias.getTypeKeywordList().get(0);
        if (remoteKw == null) {
            hops.add(new TraceHop(localAliasText, useAliasRhsKw));
            return null;
        }
        String localSimple = extractSimpleTypeName(localAliasText);
        String remoteSimple = extractSimpleTypeName(remoteKw.getText());
        boolean isRenamed = !localSimple.equals(remoteSimple);
        if (isRenamed) {
            hops.add(new TraceHop(localAliasText, useAliasRhsKw));
        }

        var remoteTarget = new StvnTypeReference(remoteKw).resolve();
        if (remoteTarget instanceof TypeKeyword remoteAliasKw
                && remoteAliasKw.getParent() instanceof org.stvnadore.psi.UseMapAlias upstreamUseAlias
                && upstreamUseAlias.getTypeKeywordList().size() >= 2
                && upstreamUseAlias.getTypeKeywordList().get(1) == remoteAliasKw) {
            return appendUseMapAliasHeadHops(
                hops,
                qualifyUseRemoteKeyword(useAlias, remoteKw),
                remoteAliasKw,
                upstreamUseAlias,
                visitedUseAliases
            );
        }
        if (remoteTarget instanceof TypeKeyword targetDefKw) {
            String qualified = qualifyTypeDefinitionKeyword(targetDefKw);
            String preludeShort = toShortPreludeTerminal(qualified);
            if (preludeShort != null) {
                hops.add(new TraceHop(preludeShort, targetDefKw));
                return null;
            }
            hops.add(new TraceHop(qualified, targetDefKw));
            return targetDefKw;
        }
        String qualifiedFallback = qualifyUseRemoteKeyword(useAlias, remoteKw);
        String preludeShort = toShortPreludeTerminal(qualifiedFallback);
        if (preludeShort != null) {
            hops.add(new TraceHop(
                preludeShort,
                remoteTarget != null ? remoteTarget : resolvePreludeKeywordTarget(useAlias.getProject(), preludeShort)
            ));
            return null;
        }
        hops.add(new TraceHop(qualifiedFallback, remoteTarget != null ? remoteTarget : useAliasRhsKw));
        return remoteTarget;
    }

    private static String qualifyTypeDefinitionKeyword(TypeKeyword defKw) {
        String text = defKw.getText().trim();
        if (text.length() > 1 && text.substring(1).contains("/")) {
            return text;
        }
        var pkg = PsiTreeUtil.getParentOfType(defKw, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            String bare = text.startsWith(StvnVocabulary.SIGIL_TYPIC) ? text.substring(1) : text;
            return pkg.getPackagePath().getText().trim() + "/" + bare;
        }
        if (defKw.getContainingFile() != null && StvnPreludeBridge.isPreludeFile(defKw.getContainingFile())) {
            var typeDef = PsiTreeUtil.getParentOfType(defKw, TypeDefinition.class);
            if (typeDef != null) {
                String preludePkg = StvnPreludeBridge.getPreludePackagePath(typeDef);
                String bare = text.startsWith(StvnVocabulary.SIGIL_TYPIC) ? text.substring(1) : text;
                return preludePkg + "/" + bare;
            }
        }
        return text;
    }

    private static String qualifyUseRemoteKeyword(org.stvnadore.psi.UseMapAlias useAlias, TypeKeyword remoteKw) {
        String remoteText = remoteKw.getText().trim();
        if (remoteText.length() > 1 && remoteText.substring(1).contains("/")) {
            return remoteText;
        }
        var useElem = PsiTreeUtil.getParentOfType(useAlias, org.stvnadore.psi.UseElement.class);
        if (useElem != null && useElem.getUseTarget() != null && useElem.getUseTarget().getUseTargetIllegal() == null) {
            String bare = remoteText.startsWith(StvnVocabulary.SIGIL_TYPIC) ? remoteText.substring(1) : remoteText;
            return useElem.getUseTarget().getText().trim() + "/" + bare;
        }
        return remoteText;
    }

    private static String formatAtomicTerminal(String atomicName, org.stvnadore.psi.SchemaType schemaType) {
        if (StvnVocabulary.TYPE_TIME_EPOCH.equals(atomicName)) {
            var scale = StvnTypeResolver.extractScaleFlagFromSchemaType(schemaType);
            return scale != null
                ? StvnVocabulary.DELIM_OPEN_BRACE + " " + scale + " " + StvnVocabulary.DELIM_CLOSE_BRACE + " " + StvnVocabulary.TYPE_TIME_EPOCH
                : StvnVocabulary.TYPE_TIME_EPOCH;
        }
        if (StvnVocabulary.TYPE_DATE_TIME.equals(atomicName)) {
            var mode = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(schemaType);
            return mode != null
                ? StvnVocabulary.DELIM_OPEN_BRACE + " " + mode + " " + StvnVocabulary.DELIM_CLOSE_BRACE + " " + StvnVocabulary.TYPE_DATE_TIME
                : StvnVocabulary.TYPE_DATE_TIME;
        }
        return atomicName;
    }

    /**
     * Extracts the sequential type resolution trace starting from the given keyword by delegating
     * to {@link #extractClickableResolutionHops(TypeKeyword, boolean)}.
     *
     * @param startKeyword the starting {@link TypeKeyword} element
     * @return ordered list of type names traversed during resolution
     */
    public static java.util.List<String> extractResolutionTrace(TypeKeyword startKeyword) {
        return extractClickableResolutionHops(startKeyword, false)
            .stream()
            .map(TraceHop::displayLabel)
            .toList();
    }

    /**
     * Exact canonical and standard prelude terminal type names.
     * <p>
     * Terminal type names halt sequential alias resolution traces because they represent either:
     * <ol>
     *   <li><b>Normative Language Base Primitives (§ 2.1):</b> Fundamental scalar types
     *       ({@code :Boolean}, {@code :Int}, {@code :Float}, {@code :String}, {@code :TimeEpoch},
     *       {@code :DateTime}) and composite constructors ({@code :Seq}, {@code :Set}, {@code :Map},
     *       {@code :Tuple}, {@code :Option}, {@code :Either}, {@code :Union}, {@code :Enum}).</li>
     *   <li><b>Canonical Standard Prelude Domain Types:</b> Nominal types pre-registered under
     *       {@code :org/stvnadore/prelude/} (e.g. {@code :UuidLowerCase}, {@code :UuidMixedCase},
     *       {@code :Ulid}, {@code :Sha256LowerCase}, {@code :Sha256MixedCase}, {@code :SemVer},
     *       {@code :EmailLowerCase}, {@code :EmailMixedCase}, {@code :IPv4}, {@code :Port},
     *       {@code :Percentage}, {@code :Probability}, {@code :Currency}, {@code :Latitude},
     *       {@code :Longitude}, {@code :PosixId}, {@code :PolyglotCodeId}).</li>
     *   <li><b>Meta-Circular Substrate Types:</b> Substrate types pre-registered under
     *       {@code :org/stvnadore/prelude/meta/} ({@code :StvnPosixId64}, {@code :StvnDocumentKind},
     *       {@code :StvnDocumentMeta}, {@code :StvnCatalogMeta}).</li>
     * </ol>
     * <p>
     * Bare un-cased types ({@code :Uuid}, {@code :Sha256}, {@code :Email}) and numbered fixed-width
     * primitives ({@code :Int32}, {@code :Int64}, {@code :Float64}, {@code :String32}) are permanently
     * prohibited in STVN 2.0.0. Widths, precision, and bounds must be declared via metadata facets
     * (e.g. {@code { #size 16 } :Int}, {@code { #maxSize 32 } :String}, {@code { #exact } :Float}).
     */
    public static final Set<String> EXACT_TERMINAL_TYPE_NAMES = Set.of(
        // Group 1: Normative Language Base Primitives (§ 2.1)
        StvnVocabulary.TYPE_BOOLEAN,
        StvnVocabulary.TYPE_ENUM,
        StvnVocabulary.TYPE_INT,
        StvnVocabulary.TYPE_FLOAT,
        StvnVocabulary.TYPE_STRING,
        StvnVocabulary.TYPE_TIME_EPOCH,
        StvnVocabulary.TYPE_DATE_TIME,
        StvnVocabulary.TYPE_SEQ,
        StvnVocabulary.TYPE_SET,
        StvnVocabulary.TYPE_MAP,
        StvnVocabulary.TYPE_TUPLE,
        StvnVocabulary.TYPE_OPTION,
        StvnVocabulary.TYPE_EITHER,
        StvnVocabulary.TYPE_UNION,

        // Group 2: Canonical Standard Prelude Domain Types (:org/stvnadore/prelude/)
        ":UuidLowerCase",
        ":UuidMixedCase",
        ":Ulid",
        ":Sha256LowerCase",
        ":Sha256MixedCase",
        ":SemVer",
        ":EmailLowerCase",
        ":EmailMixedCase",
        ":IPv4",
        ":Port",
        ":Percentage",
        ":Probability",
        ":Currency",
        ":Latitude",
        ":Longitude",
        ":PosixId",
        ":PolyglotCodeId",

        // Group 3: Meta-Circular Substrate Types (:org/stvnadore/prelude/meta/)
        ":StvnPosixId64",
        ":StvnDocumentKind",
        ":StvnDocumentMeta",
        ":StvnCatalogMeta"
    );

    private static boolean isPrimitiveTypeName(String name) {
        return EXACT_TERMINAL_TYPE_NAMES.contains(name) || StvnVocabulary.isBaseScalarType(name);
    }

    /**
     * Resolves the target PSI file referenced by an include string literal.
     *
     * @param stringLit the include StringLiteral element
     * @return the resolved target PsiFile, or null if resolution fails
     */
    public static @Nullable PsiFile resolveIncludeFile(StringLiteral stringLit) {
        var containingFile = stringLit.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        var virtualFile = containingFile.getOriginalFile().getVirtualFile();
        if (virtualFile == null) {
            virtualFile = containingFile.getVirtualFile();
        }
        if (virtualFile == null) {
            return null;
        }
        var parentDir = virtualFile.getParent();
        if (parentDir == null) {
            return null;
        }
        // Extract unquoted relative path
        var relativePath = StvnTypeReference.getUnquotedPath(stringLit);
        if (relativePath.isEmpty()) {
            return null;
        }
        var targetVirtualFile = parentDir.findFileByRelativePath(relativePath);
        if (targetVirtualFile == null) {
            var project = containingFile.getProject();
            var projectDir = project != null ? com.intellij.openapi.project.ProjectUtil.guessProjectDir(project) : null;
            if (projectDir != null) {
                targetVirtualFile = projectDir.findFileByRelativePath(relativePath);
            }
        }
        if (targetVirtualFile == null) {
            return null;
        }
        var psiManager = stringLit.getManager();
        if (psiManager == null) {
            return null;
        }
        return psiManager.findFile(targetVirtualFile);
    }

    private static String getUnquotedPath(StringLiteral element) {
        return StvnPsiLiteralEvaluator.decodeStringLiteral(element.getText(), true).orElse("");
    }
}
