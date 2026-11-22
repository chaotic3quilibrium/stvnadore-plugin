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

    private static @Nullable PsiElement resolveTypeKeywordWithContext(
            TypeKeyword keyword,
            @Nullable List<String> trace,
            boolean allowImplicitPreludeFallback
    ) {
        var containingFile = keyword.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        var targetTypeName = keyword.getText().trim();
        var enclosingPkg = PsiTreeUtil.getParentOfType(keyword, PackageEnclosure.class);
        if (enclosingPkg != null
                && (!targetTypeName.startsWith(StvnVocabulary.SIGIL_TYPIC) || !targetTypeName.substring(1).contains("/"))) {
            for (var elem : enclosingPkg.getPackageElementList()) {
                var typeDef = elem.getTypeDefinition();
                if (typeDef != null) {
                    var defKw = typeDef.getTypeKeyword();
                    if (defKw != null && defKw != keyword && defKw.getText().equals(targetTypeName)) {
                        return defKw;
                    }
                }
            }
            var pkgPath = enclosingPkg.getPackagePath();
            if (!StvnPsiUtils.hasIllegalTrailingSlash(enclosingPkg) && pkgPath != null) {
                var bare = targetTypeName.startsWith(StvnVocabulary.SIGIL_TYPIC)
                    ? targetTypeName.substring(1)
                    : targetTypeName;
                var fqni = pkgPath.getText().trim() + "/" + bare;
                var resolvedSamePkg = resolveTypeInFile(containingFile, fqni, new HashSet<>(), null, false, allowImplicitPreludeFallback);
                if (resolvedSamePkg != null && resolvedSamePkg != keyword) {
                    return resolvedSamePkg;
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
                return null;
            }
            if (pkgUseResult.resolved() != null) {
                return pkgUseResult.resolved();
            }
        }
        return resolveTypeInFile(containingFile, targetTypeName, new HashSet<>(), trace, false, allowImplicitPreludeFallback);
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

        for (var use : useStmts) {
            for (var useElem : use.getUseElementList()) {
                var useTarget = useElem.getUseTarget();
                if (useTarget == null || useTarget.getUseTargetIllegal() != null) {
                    continue;
                }
                var prefix = useTarget.getText().trim();
                var fqni = prefix + "/" + targetName.substring(1);

                boolean lhsRenamedInThisUse = false;
                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var list = alias.getTypeKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            var localKw = list.get(1);
                            if (remoteKw != null) {
                                var remoteText = remoteKw.getText().trim();
                                if (remoteText.equals(targetName) || remoteText.equals(fqni)) {
                                    lhsRenamedInThisUse = true;
                                }
                            }
                            if (localKw != null && localKw.getText().trim().equals(targetName)) {
                                rhsClaims.add(new RhsClaim(localKw, remoteKw != null ? remoteKw.getText().trim() : null));
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

    static boolean areSameDeclarationTarget(PsiElement a, PsiElement b) {
        if (a == b) {
            return true;
        }
        var manager = a.getManager();
        return manager != null && manager.areElementsEquivalent(a, b);
    }

    /**
     * Extracts the sequential type resolution trace starting from the given keyword.
     *
     * @param startKeyword the starting TypeKeyword element
     * @return list of type names traversed during resolution
     */
    public static java.util.List<String> extractResolutionTrace(TypeKeyword startKeyword) {
        var trace = new java.util.ArrayList<String>();
        trace.add(startKeyword.getText());
        if (EXACT_TERMINAL_TYPE_NAMES.contains(startKeyword.getText())) {
            return trace;
        }
        PsiElement currentElement;
        if (org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(startKeyword)) {
            currentElement = startKeyword;
        } else if (startKeyword.getParent() instanceof org.stvnadore.psi.UseMapAlias useAlias
                && useAlias.getTypeKeywordList().size() >= 2
                && useAlias.getTypeKeywordList().get(1) == startKeyword) {
            var remoteKw = useAlias.getTypeKeywordList().get(0);
            if (remoteKw != null) {
                trace.add(remoteKw.getText());
            }
            currentElement = useAlias;
        } else {
            currentElement = resolveTypeKeywordWithContext(startKeyword, trace, true);
        }

        var visitedElements = new java.util.HashSet<PsiElement>();
        if (currentElement != null) {
            visitedElements.add(currentElement);
        }

        while (currentElement != null) {
            var parent = currentElement.getParent();
            var typeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(currentElement);
            if (typeDef != null) {
                var schemaType = typeDef.getSchemaType();
                if (schemaType != null) {
                    var nextKeyword = schemaType.getTypeKeyword();
                    var nextTypeName = "";
                    if (nextKeyword != null) {
                        nextTypeName = nextKeyword.getText().trim();
                    } else if (schemaType.getSchemaConstructor() != null) {
                        var ctor = schemaType.getSchemaConstructor();
                        if (ctor.getAtomicType() != null) {
                            nextTypeName = ctor.getAtomicType().getText().trim();
                        } else if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
                            nextTypeName = StvnVocabulary.TYPE_ENUM;
                        }
                    }

                    if (!nextTypeName.isEmpty()) {
                        if (isPrimitiveTypeName(nextTypeName) || nextTypeName.equals(StvnVocabulary.TYPE_ENUM)) {
                            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(nextTypeName)) {
                                var scale = StvnTypeResolver.extractScaleFlagFromSchemaType(schemaType);
                                trace.add(scale != null ? "{ " + scale + " } " + StvnVocabulary.TYPE_TIME_EPOCH : StvnVocabulary.TYPE_TIME_EPOCH);
                            } else if (StvnVocabulary.TYPE_DATE_TIME.equals(nextTypeName)) {
                                var mode = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(schemaType);
                                trace.add(mode != null ? "{ " + mode + " } " + StvnVocabulary.TYPE_DATE_TIME : StvnVocabulary.TYPE_DATE_TIME);
                            } else {
                                trace.add(nextTypeName);
                            }
                            break;
                        }

                        if (nextKeyword != null) {
                            trace.add(nextTypeName);
                            var resolved = resolveTypeKeywordWithContext(nextKeyword, trace, true);
                            if (resolved != null && visitedElements.add(resolved)) {
                                currentElement = resolved;
                                continue;
                            }
                        }
                    }
                }
            } else if (currentElement instanceof org.stvnadore.psi.UseMapAlias || parent instanceof org.stvnadore.psi.UseMapAlias) {
                var useAlias = currentElement instanceof org.stvnadore.psi.UseMapAlias a ? a : (org.stvnadore.psi.UseMapAlias) parent;
                var list = useAlias.getTypeKeywordList();
                if (list.size() >= 2) {
                    var remoteKw = list.get(0);
                    if (remoteKw != null) {
                        var resolved = new StvnTypeReference(remoteKw).resolve();
                        if (resolved != null && visitedElements.add(resolved)) {
                            currentElement = resolved;
                            continue;
                        }
                    }
                }
            }
            break;
        }
        return trace;
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
