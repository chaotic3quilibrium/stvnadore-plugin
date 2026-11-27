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
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.ValueKeyword;

import java.util.HashSet;
import java.util.Set;

/**
 * Context-aware reference resolver for value keywords, sum literals, and boolean keywords.
 * Disambiguates between Enum variant definitions (Dimension 1) and constant declarations (Dimension 2/3/4).
 */
@NullMarked
public final class StvnValueKeywordReference extends PsiReferenceBase<PsiElement> {

    /**
     * Constructs a reference for a value keyword or variant element.
     *
     * @param element the PSI element containing the value keyword
     */
    public StvnValueKeywordReference(PsiElement element) {
        super(element, extractHeadTokenRange(element));
    }

    private static TextRange extractHeadTokenRange(PsiElement element) {
        var text = element.getText().trim();
        if (text.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            var firstWord = text.split("\\s+")[0];
            return new TextRange(0, firstWord.length());
        }
        return new TextRange(0, element.getTextLength());
    }

    /**
     * Handles renaming of the referenced value element by replacing the ValueKeyword PSI element.
     * Preserves modular path prefixes when renaming constant identifiers.
     *
     * @param newElementName the new name string to assign to the value element
     * @return the newly created ValueKeyword PSI element
     * @throws IncorrectOperationException if the element replacement fails
     */
    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
        var element = getElement();
        if (element instanceof ValueKeyword valKw) {
            if (newElementName.startsWith(StvnVocabulary.SIGIL_TYPIC) || newElementName.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                throw new IncorrectOperationException("Identifier must be a bare name without ':' or '#' prefix: " + newElementName);
            }
            var currentText = valKw.getText();
            String replacementText;
            if (currentText.contains("/")) {
                var lastSlashIndex = currentText.lastIndexOf('/');
                var prefix = currentText.substring(0, lastSlashIndex + 1);
                replacementText = prefix + newElementName;
            } else {
                replacementText = StvnVocabulary.SIGIL_VALUE + newElementName;
            }
            var newKeyword = org.stvnadore.plugin.psi.StvnElementFactory.createValueKeyword(element.getProject(), replacementText);
            return valKw.replace(newKeyword);
        }
        return super.handleElementRename(newElementName);
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

        var tokenText = element.getText().trim().split("\\s+")[0];
        if (!tokenText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return null;
        }

        // 0. Declaration-site guard
        if (element instanceof ValueKeyword valKw) {
            if (valKw.getParent() instanceof ConstantDefinition cDef && cDef.getValueKeyword() == valKw) {
                return valKw;
            }
            if (valKw.getParent() instanceof org.stvnadore.psi.EnumDef) {
                return valKw;
            }
        }

        // 1. Dimension 1 (:Enum) check: if the enclosing Value is governed by an :Enum in the compiled IR,
        // resolve to the :Enum variant declaration before checking constant definitions.
        var valueParent = PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.Value.class, false);
        if (valueParent != null) {
            var coreVal = StvnTypeResolver.resolveCoreValue(valueParent);
            if (coreVal instanceof org.stvnadore.core.ir.StvnValue.StvnEnum) {
                PsiElement enumVariant = element instanceof ValueKeyword valKw
                    ? StvnTypeResolver.resolveGoverningEnumVariant(valKw)
                    : null;
                if (enumVariant == null) {
                    enumVariant = findEnumDeclarationAtContext(element, tokenText);
                }
                if (enumVariant != null) {
                    return enumVariant;
                }
            }
        }

        // 2. Scoped ConstantDefinition / UseMapAlias resolution
        if (element instanceof ValueKeyword valKw) {
            var constResolved = StvnConstantReference.resolveConstantWithContext(valKw);
            if (constResolved != null) {
                return constResolved;
            }
            var contextualEnumVariant = StvnTypeResolver.resolveGoverningEnumVariant(valKw);
            if (contextualEnumVariant != null) {
                return contextualEnumVariant;
            }
        } else {
            var constDef = StvnConstantReference.resolveConstantAtContext(element, tokenText);
            if (constDef != null) {
                return constDef;
            }
        }

        // Fallback to lexically visible Enum Declaration if constant or contextual enum was not found
        return findEnumDeclarationAtContext(element, tokenText);
    }

    /**
     * Finds an {@code :Enum} variant declaration matching {@code tokenText} that is lexically visible
     * from {@code contextSite} (co-located {@code :package} enums, merged same-package enums, and
     * package-local {@code :use} imports before root {@code :defs} and root {@code :use} imports,
     * without leaking un-imported sibling packages).
     *
     * @param contextSite originating PSI element
     * @param tokenText   variant token text starting with {@code #}
     * @return matching variant {@link ValueKeyword}, or {@code null} when not visible in scope
     */
    public static @Nullable PsiElement findEnumDeclarationAtContext(@Nullable PsiElement contextSite, String tokenText) {
        if (contextSite == null) {
            return null;
        }
        var file = contextSite instanceof PsiFile pf ? pf : contextSite.getContainingFile();
        if (file == null) {
            return null;
        }
        var enclosingPkg = contextSite instanceof PsiFile
            ? null
            : PsiTreeUtil.getParentOfType(contextSite, org.stvnadore.psi.PackageEnclosure.class, false);
        if (enclosingPkg != null) {
            var localTypeNames = new HashSet<String>();
            for (var pkgElem : enclosingPkg.getPackageElementList()) {
                var td = pkgElem.getTypeDefinition();
                if (td != null && td.getTypeKeyword() != null) {
                    localTypeNames.add(td.getTypeKeyword().getText().trim());
                }
                var match = matchEnumVariantInTypeDef(td, tokenText);
                if (match != null) {
                    return match;
                }
            }
            if (!org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(enclosingPkg)
                    && enclosingPkg.getPackagePath() != null) {
                var pkgPathText = enclosingPkg.getPackagePath().getText().trim();
                var samePkgMatch = findMergedPackageEnumVariant(file, pkgPathText, tokenText, localTypeNames, new HashSet<>());
                if (samePkgMatch != null) {
                    return samePkgMatch;
                }
            }
            for (var pkgElem : enclosingPkg.getPackageElementList()) {
                if (pkgElem.getUseStmt() != null) {
                    var match = findEnumVariantViaUseStmt(file, pkgElem.getUseStmt(), tokenText, localTypeNames);
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        return findRootVisibleEnumDeclaration(file, tokenText, new HashSet<>());
    }

    private static @Nullable PsiElement findMergedPackageEnumVariant(
            PsiFile file,
            String pkgPathText,
            String tokenText,
            Set<String> localTypeNames,
            Set<PsiFile> visited
    ) {
        if (!visited.add(file)) {
            return null;
        }
        for (var pkg : PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class)) {
            if (org.stvnadore.plugin.util.StvnMetaTemplateUtil.isInSecondaryRootSection(pkg)
                    || org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(pkg)
                    || pkg.getPackagePath() == null
                    || !pkgPathText.equals(pkg.getPackagePath().getText().trim())) {
                continue;
            }
            for (var elem : pkg.getPackageElementList()) {
                var td = elem.getTypeDefinition();
                if (td != null && td.getTypeKeyword() != null) {
                    localTypeNames.add(td.getTypeKeyword().getText().trim());
                }
                var match = matchEnumVariantInTypeDef(td, tokenText);
                if (match != null) {
                    return match;
                }
            }
        }
        for (var incl : PsiTreeUtil.findChildrenOfType(file, IncludeElement.class)) {
            var stringLit = incl.getStringLiteral();
            if (stringLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null) {
                    var match = findMergedPackageEnumVariant(targetFile, pkgPathText, tokenText, localTypeNames, visited);
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable PsiElement findRootVisibleEnumDeclaration(
            PsiFile file,
            String tokenText,
            Set<PsiFile> visited
    ) {
        if (!visited.add(file)) {
            return null;
        }
        var rootDeclaredNames = new HashSet<String>();
        for (var def : PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class)) {
            if (org.stvnadore.plugin.util.StvnMetaTemplateUtil.isInSecondaryRootSection(def)
                    || PsiTreeUtil.getParentOfType(def, org.stvnadore.psi.PackageEnclosure.class) != null) {
                continue;
            }
            if (def.getTypeKeyword() != null) {
                rootDeclaredNames.add(def.getTypeKeyword().getText().trim());
            }
            var match = matchEnumVariantInTypeDef(def, tokenText);
            if (match != null) {
                return match;
            }
        }
        for (var useStmt : PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class)) {
            if (org.stvnadore.plugin.util.StvnMetaTemplateUtil.isInSecondaryRootSection(useStmt)
                    || PsiTreeUtil.getParentOfType(useStmt, org.stvnadore.psi.PackageEnclosure.class) != null) {
                continue;
            }
            var match = findEnumVariantViaUseStmt(file, useStmt, tokenText, rootDeclaredNames);
            if (match != null) {
                return match;
            }
        }
        for (var incl : PsiTreeUtil.findChildrenOfType(file, IncludeElement.class)) {
            if (org.stvnadore.plugin.util.StvnMetaTemplateUtil.isInSecondaryRootSection(incl)) {
                continue;
            }
            var stringLit = incl.getStringLiteral();
            if (stringLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null) {
                    var resolved = findRootVisibleEnumDeclaration(targetFile, tokenText, visited);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable PsiElement findEnumVariantViaUseStmt(
            PsiFile file,
            org.stvnadore.psi.UseStmt useStmt,
            String tokenText,
            Set<String> localEvictingTypeNames
    ) {
        for (var useElem : useStmt.getUseElementList()) {
            var target = useElem.getUseTarget();
            if (target == null || target.getUseTargetIllegal() != null) {
                continue;
            }
            String pkgPrefix = target.getText().trim();
            var renamedLhs = new HashSet<String>();
            var aliasBlock = useElem.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var typeKws = alias.getTypeKeywordList();
                    if (typeKws.size() >= 2 && typeKws.get(0) != null && typeKws.get(1) != null) {
                        String remoteText = typeKws.get(0).getText().trim();
                        String localText = typeKws.get(1).getText().trim();
                        renamedLhs.add(remoteText);
                        if (!localEvictingTypeNames.contains(localText)) {
                            String bare = remoteText.startsWith(StvnVocabulary.SIGIL_TYPIC)
                                ? remoteText.substring(1)
                                : remoteText;
                            var resolved = StvnTypeReference.resolveTypeInFile(file, pkgPrefix + "/" + bare, new HashSet<>());
                            var td = org.stvnadore.plugin.psi.StvnPsiUtils.resolveTargetTypeDefinition(resolved);
                            var match = matchEnumVariantInTypeDef(td, tokenText);
                            if (match != null) {
                                return match;
                            }
                        }
                    }
                }
            }
            if (org.stvnadore.plugin.psi.StvnPsiUtils.hasValidStripOption(useElem.getUseOptionsBlock())) {
                var match = findStrippedEnumVariantInPackage(
                    file,
                    pkgPrefix,
                    tokenText,
                    localEvictingTypeNames,
                    renamedLhs,
                    new HashSet<>()
                );
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static @Nullable PsiElement findStrippedEnumVariantInPackage(
            PsiFile file,
            String pkgPrefix,
            String tokenText,
            Set<String> localEvictingTypeNames,
            Set<String> renamedLhs,
            Set<PsiFile> visited
    ) {
        if (!visited.add(file)) {
            return null;
        }
        for (var pkg : PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class)) {
            if (org.stvnadore.plugin.util.StvnMetaTemplateUtil.isInSecondaryRootSection(pkg)
                    || org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(pkg)
                    || pkg.getPackagePath() == null
                    || !pkgPrefix.equals(pkg.getPackagePath().getText().trim())) {
                continue;
            }
            for (var elem : pkg.getPackageElementList()) {
                var td = elem.getTypeDefinition();
                if (td != null && td.getTypeKeyword() != null) {
                    String bareName = td.getTypeKeyword().getText().trim();
                    if (localEvictingTypeNames.contains(bareName) || renamedLhs.contains(bareName)) {
                        continue;
                    }
                    var match = matchEnumVariantInTypeDef(td, tokenText);
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        for (var incl : PsiTreeUtil.findChildrenOfType(file, IncludeElement.class)) {
            var stringLit = incl.getStringLiteral();
            if (stringLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null) {
                    var match = findStrippedEnumVariantInPackage(
                        targetFile,
                        pkgPrefix,
                        tokenText,
                        localEvictingTypeNames,
                        renamedLhs,
                        visited
                    );
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable PsiElement matchEnumVariantInTypeDef(
            @Nullable TypeDefinition def,
            String tokenText
    ) {
        if (def == null) {
            return null;
        }
        var schema = def.getSchemaType();
        if (schema != null && schema.getSchemaConstructor() != null) {
            var sum = schema.getSchemaConstructor().getSumType();
            if (sum != null && sum.getEnumDef() != null) {
                for (var kw : sum.getEnumDef().getValueKeywordList()) {
                    if (kw.getText().equals(tokenText)) {
                        return kw;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds an enum variant declaration matching the specified token text across files.
     *
     * @param file the starting PSI file
     * @param tokenText the enum token text
     * @param visited the set of visited files to prevent circular traversals
     * @return the matching PSI element, or null if not found
     */
    public static @Nullable PsiElement findEnumDeclaration(PsiFile file, String tokenText, Set<PsiFile> visited) {
        if (!visited.add(file)) {
            return null;
        }

        // 1. Scan local type definitions for EnumDef
        var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : typeDefs) {
            var schema = def.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null && sum.getEnumDef() != null) {
                    for (var kw : sum.getEnumDef().getValueKeywordList()) {
                        if (kw.getText().equals(tokenText)) {
                            return kw;
                        }
                    }
                }
            }
        }

        // 2. Scan imported modules
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null) {
                    var resolved = findEnumDeclaration(targetFile, tokenText, visited);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }

        return null;
    }
}
