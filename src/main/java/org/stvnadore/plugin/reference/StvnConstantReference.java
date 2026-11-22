package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.ValueKeyword;

import java.util.HashSet;
import java.util.Set;

/**
 * Resolves references to declared constant definitions and enum variants from value keywords.
 */
@NullMarked
public final class StvnConstantReference extends PsiReferenceBase<ValueKeyword> {

    /**
     * Constructs a reference for the specified value keyword element.
     *
     * @param element the value keyword element
     */
    public StvnConstantReference(ValueKeyword element) {
        super(element, new TextRange(0, element.getTextLength()));
    }

    /**
     * Handles renaming of the referenced constant element by replacing the ValueKeyword PSI element.
     * Preserves modular path prefixes when renaming constant identifiers.
     *
     * @param newElementName the new name string to assign to the constant
     * @return the newly created ValueKeyword PSI element
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
            replacementText = StvnVocabulary.SIGIL_VALUE + newElementName;
        }
        var newKeyword = org.stvnadore.plugin.psi.StvnElementFactory.createValueKeyword(element.getProject(), replacementText);
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
        if (element instanceof ConstantDefinition constDef) {
            return manager.areElementsEquivalent(resolved, constDef.getValueKeyword())
                || manager.areElementsEquivalent(resolved, constDef.getNameIdentifier());
        }
        if (element instanceof org.stvnadore.psi.UseMapAlias alias) {
            return manager.areElementsEquivalent(resolved, alias.getNameIdentifier());
        }
        if (resolved.getContainingFile() != null && element.getContainingFile() != null
            && StvnPreludeBridge.isPreludeFile(resolved.getContainingFile())
            && StvnPreludeBridge.isPreludeFile(element.getContainingFile())) {
            String resolvedText = (resolved instanceof ValueKeyword vk) ? vk.getText()
                : (resolved instanceof ConstantDefinition cd && cd.getValueKeyword() != null ? cd.getValueKeyword().getText() : null);
            String elemText = (element instanceof ValueKeyword vk) ? vk.getText()
                : (element instanceof ConstantDefinition cd && cd.getValueKeyword() != null ? cd.getValueKeyword().getText() : null);
            if (resolvedText != null && resolvedText.equals(elemText)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable PsiElement resolve() {
        var element = getElement();
        var file = element.getContainingFile();
        if (file == null) {
            return null;
        }
        var tokenText = element.getText().trim();
        var valueParent = PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.Value.class, false);
        if (valueParent != null) {
            var coreVal = StvnTypeResolver.resolveCoreValue(valueParent);
            if (coreVal instanceof org.stvnadore.core.ir.StvnValue.StvnEnum) {
                PsiElement enumVariant = StvnTypeResolver.resolveGoverningEnumVariant(element);
                if (enumVariant == null) {
                    enumVariant = StvnValueKeywordReference.findEnumDeclaration(file, tokenText, new HashSet<>());
                }
                if (enumVariant != null) {
                    return enumVariant;
                }
            }
        }
        var constDef = resolveConstantWithContext(element);
        if (constDef != null) {
            return constDef;
        }
        var enumVariant = StvnTypeResolver.resolveGoverningEnumVariant(element);
        if (enumVariant != null) {
            return enumVariant;
        }
        return StvnValueKeywordReference.findEnumDeclaration(file, tokenText, new HashSet<>());
    }

    /**
     * Builds a canonical STVN constant FQNI ({@code #prefix/NAME}) from a package or {@code :use} target
     * ({@code :prefix}) and a constant keyword ({@code #NAME}).
     *
     * @param packagePrefix the package or use target path
     * @param constKeyword  the constant keyword identifier
     * @return the canonical constant FQNI starting with {@code #}
     */
    public static @NotNull String buildPackageConstantFqni(@NotNull String packagePrefix, @NotNull String constKeyword) {
        var cleanPrefix = packagePrefix.trim();
        if (cleanPrefix.startsWith(StvnVocabulary.SIGIL_TYPIC) || cleanPrefix.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            cleanPrefix = cleanPrefix.substring(1);
        }
        while (cleanPrefix.endsWith("/")) {
            cleanPrefix = cleanPrefix.substring(0, cleanPrefix.length() - 1);
        }
        var cleanConst = constKeyword.trim();
        if (cleanConst.startsWith(StvnVocabulary.SIGIL_VALUE) || cleanConst.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
            cleanConst = cleanConst.substring(1);
        }
        if (cleanPrefix.isEmpty()) {
            return StvnVocabulary.SIGIL_VALUE + cleanConst;
        }
        return StvnVocabulary.SIGIL_VALUE + cleanPrefix + "/" + cleanConst;
    }

    /**
     * Canonicalizes any constant query (such as legacy {@code :pkg/#NAME} or {@code :pkg/NAME})
     * into canonical {@code #pkg/NAME} form.
     *
     * @param constName the raw constant query string
     * @return the canonicalized constant query string starting with {@code #}
     */
    public static @NotNull String canonicalizeConstantQuery(@NotNull String constName) {
        var trimmed = constName.trim();
        if (trimmed.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
            trimmed = StvnVocabulary.SIGIL_VALUE + trimmed.substring(1);
        } else if (!trimmed.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            trimmed = StvnVocabulary.SIGIL_VALUE + trimmed;
        }
        return trimmed.replace("/#", "/");
    }

    /**
     * Resolves a {@link ValueKeyword} constant reference with full awareness of its lexical context
     * ({@code UseMapAlias} index 0, enclosing {@code PackageEnclosure} local definitions and package-local
     * {@code :use} directives, root {@code :defs}, {@code :include} modules, and Standard Library Prelude).
     *
     * @param element the value keyword reference site
     * @return the resolved declaration keyword element, or {@code null} if unresolved
     */
    public static @Nullable PsiElement resolveConstantWithContext(@NotNull ValueKeyword element) {
        var file = element.getContainingFile();
        if (file == null) {
            return null;
        }
        var tokenText = element.getText().trim();

        // 1. If element is index 0 (#REMOTE) inside UseMapAlias, qualify with enclosing UseElement target
        if (element.getParent() instanceof org.stvnadore.psi.UseMapAlias alias) {
            var kws = alias.getValueKeywordList();
            if (!kws.isEmpty() && kws.get(0) == element) {
                var useElem = PsiTreeUtil.getParentOfType(alias, org.stvnadore.psi.UseElement.class);
                if (useElem != null && useElem.getUseTarget() != null) {
                    var fqni = buildPackageConstantFqni(useElem.getUseTarget().getText(), tokenText);
                    return resolveConstantInFile(file, fqni, new HashSet<>(), false);
                }
            }
        }

        // 2. If element resides inside a PackageEnclosure and is unqualified, check package-local scope first
        var enclosingPkg = PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.PackageEnclosure.class);
        if (enclosingPkg != null && !tokenText.contains("/")) {
            var pkgUseStmts = new java.util.ArrayList<org.stvnadore.psi.UseStmt>();
            for (var pkgElem : enclosingPkg.getPackageElementList()) {
                var cDef = pkgElem.getConstantDefinition();
                if (cDef != null) {
                    var kw = cDef.getValueKeyword();
                    if (kw != null && kw != element && tokenText.equals(kw.getText().trim())) {
                        return kw;
                    }
                }
                var useStmt = pkgElem.getUseStmt();
                if (useStmt != null) {
                    pkgUseStmts.add(useStmt);
                }
            }
            if (!org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(enclosingPkg) && enclosingPkg.getPackagePath() != null) {
                var pkgFqni = buildPackageConstantFqni(enclosingPkg.getPackagePath().getText(), tokenText);
                var samePkgResolved = resolveConstantInFile(file, pkgFqni, new HashSet<>(), false);
                if (samePkgResolved != null && samePkgResolved != element) {
                    return samePkgResolved;
                }
            }
            var pkgUseResolved = resolveFromUseStmts(file, pkgUseStmts, canonicalizeConstantQuery(tokenText));
            if (pkgUseResolved.collision()) {
                return null;
            }
            if (pkgUseResolved.resolved() != null) {
                return pkgUseResolved.resolved();
            }
        }

        return resolveConstantInFile(file, tokenText, new HashSet<>(), false);
    }

    /**
     * Recursively resolves a constant identifier within the file and its imported packages.
     *
     * @param file the root PSI file to search within
     * @param constName the constant name or path to resolve
     * @param visited set of already visited files to prevent infinite cycles
     * @return the resolved constant definition element, or {@code null} if unresolved
     */
    public static @Nullable PsiElement resolveConstantInFile(PsiFile file, String constName, Set<String> visited) {
        return resolveConstantInFile(file, constName, visited, false);
    }

    /**
     * Recursively resolves a constant identifier within the file and its imported packages,
     * with explicit control over whether unqualified names match packaged declarations.
     *
     * @param file                     the root PSI file to search within
     * @param constName                the constant name or path to resolve
     * @param visited                  set of already visited file keys to prevent infinite cycles
     * @param allowUnqualifiedPackaged whether bare constant names may match declarations inside package enclosures
     * @return the resolved constant definition element, or {@code null} if unresolved
     */
    public static @Nullable PsiElement resolveConstantInFile(
            PsiFile file,
            String constName,
            Set<String> visited,
            boolean allowUnqualifiedPackaged) {
        var canonicalName = canonicalizeConstantQuery(constName);
        String cycleKey = (file.getVirtualFile() != null ? file.getVirtualFile().getPath() : file.getName())
            + StvnVocabulary.SIGIL_VALUE + canonicalName + StvnVocabulary.SIGIL_VALUE + allowUnqualifiedPackaged;
        if (!visited.add(cycleKey)) {
            return null;
        }

        // Physical prelude file self-resolution guard: check local declarations before synthetic bridge
        if (StvnPreludeBridge.isPreludeFile(file)) {
            for (var def : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
                var kw = def.getValueKeyword();
                if (kw != null) {
                    var kwText = kw.getText().trim();
                    var pkg = PsiTreeUtil.getParentOfType(def, org.stvnadore.psi.PackageEnclosure.class);
                    if (pkg != null && !org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
                        var fqni = buildPackageConstantFqni(pkg.getPackagePath().getText(), kwText);
                        if (fqni.equals(canonicalName) || kwText.equals(canonicalName)) {
                            return kw;
                        }
                    } else if (kwText.equals(canonicalName)) {
                        return kw;
                    }
                }
            }
        }

        // 1. Scan top-level (unpackaged) constant definitions in :defs
        var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var def : constDefs) {
            if (PsiTreeUtil.getParentOfType(def, org.stvnadore.psi.PackageEnclosure.class) != null && !allowUnqualifiedPackaged) {
                continue;
            }
            var kw = def.getValueKeyword();
            if (kw != null && kw.getText().trim().equals(canonicalName)) {
                return kw;
            }
        }

        // 2. Scan package enclosures (FQNI expansion and stripped short names)
        var packages = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class);
        for (var pkg : packages) {
            if (org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(pkg)) continue;
            var pkgPath = pkg.getPackagePath();
            if (pkgPath == null) continue;
            var pathText = pkgPath.getText();
            for (var elem : pkg.getPackageElementList()) {
                var cDef = elem.getConstantDefinition();
                if (cDef != null) {
                    var kw = cDef.getValueKeyword();
                    if (kw != null) {
                        var kwText = kw.getText().trim();
                        var fqni = buildPackageConstantFqni(pathText, kwText);
                        if (fqni.equals(canonicalName) || (allowUnqualifiedPackaged && kwText.equals(canonicalName))) {
                            return kw;
                        }
                    }
                }
            }
        }

        // 3. Scan root :use statements
        var rootUseStmts = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class).stream()
            .filter(use -> PsiTreeUtil.getParentOfType(use, org.stvnadore.psi.PackageEnclosure.class) == null)
            .toList();
        var rootUseResolved = resolveFromUseStmts(file, rootUseStmts, canonicalName);
        if (rootUseResolved.collision()) {
            return null;
        }
        if (rootUseResolved.resolved() != null) {
            return rootUseResolved.resolved();
        }

        // 3b. Explicit Standard Library Prelude constant FQNI (#org/stvnadore/prelude/*)
        if (canonicalName.startsWith(StvnVocabulary.SIGIL_VALUE + "org/stvnadore/prelude/")) {
            var project = file.getProject();
            if (project != null && !project.isDisposed()) {
                var explicitPreludeConst = StvnPreludeBridge.resolvePreludeConstant(project, canonicalName);
                if (explicitPreludeConst != null) {
                    return explicitPreludeConst;
                }
            }
        }

        // 4. Scan imported modules (:include) with per-branch cycle sets and diamond deduplication
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        var uniqueIncludeMatches = new java.util.ArrayList<PsiElement>();
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null) {
                    var resolved = resolveConstantInFile(
                        targetFile, canonicalName, new HashSet<>(visited), allowUnqualifiedPackaged);
                    if (resolved != null
                            && uniqueIncludeMatches.stream().noneMatch(e -> StvnTypeReference.areSameDeclarationTarget(e, resolved))) {
                        uniqueIncludeMatches.add(resolved);
                    }
                }
            }
        }
        if (uniqueIncludeMatches.size() > 1) {
            return null;
        }
        if (uniqueIncludeMatches.size() == 1) {
            return uniqueIncludeMatches.get(0);
        }

        // 5. Standard library prelude fallback
        if (StvnPreludeBridge.isPreludeFile(file)) {
            return null;
        }
        var project = file.getProject();
        if (project != null && !project.isDisposed()) {
            var preludeFile = StvnPreludeBridge.getPreludeFile(project);
            if (preludeFile != null && !preludeFile.equals(file)) {
                var resolved = resolveConstantInFile(preludeFile, canonicalName, visited, allowUnqualifiedPackaged);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        return null;
    }

    private record UseConstResolution(@Nullable PsiElement resolved, boolean collision) {}

    private static UseConstResolution resolveFromUseStmts(
            PsiFile file,
            java.util.List<org.stvnadore.psi.UseStmt> useStmts,
            String canonicalName
    ) {
        if (canonicalName.contains("/")) {
            return new UseConstResolution(null, false);
        }
        var rhsClaims = new java.util.ArrayList<ValueKeyword>();
        var rawClaims = new java.util.ArrayList<PsiElement>();
        for (var use : useStmts) {
            for (var useElem : use.getUseElementList()) {
                var target = useElem.getUseTarget();
                if (target == null || target.getUseTargetIllegal() != null) {
                    continue;
                }
                var prefix = target.getText().trim();
                var fqni = buildPackageConstantFqni(prefix, canonicalName);
                boolean lhsRenamedInThisUse = false;

                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var list = alias.getValueKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            var localKw = list.get(1);
                            if (remoteKw != null) {
                                var remoteNorm = canonicalizeConstantQuery(remoteKw.getText().trim());
                                if (remoteNorm.equals(canonicalName) || remoteNorm.equals(fqni)) {
                                    lhsRenamedInThisUse = true;
                                }
                            }
                            if (localKw != null && localKw.getText().trim().equals(canonicalName)) {
                                rhsClaims.add(localKw);
                            }
                        }
                    }
                }

                var optBlock = useElem.getUseOptionsBlock();
                if (!lhsRenamedInThisUse && StvnPsiUtils.hasValidStripOption(optBlock)) {
                    var resolved = resolveConstantInFile(file, fqni, new HashSet<>(), false);
                    if (resolved != null
                            && rawClaims.stream().noneMatch(e -> StvnTypeReference.areSameDeclarationTarget(e, resolved))) {
                        rawClaims.add(resolved);
                    }
                }
            }
        }
        int total = rhsClaims.size() + rawClaims.size();
        if (total > 1) {
            return new UseConstResolution(null, true);
        }
        if (rhsClaims.size() == 1) {
            return new UseConstResolution(rhsClaims.get(0), false);
        }
        if (rawClaims.size() == 1) {
            return new UseConstResolution(rawClaims.get(0), false);
        }
        return new UseConstResolution(null, false);
    }
}
