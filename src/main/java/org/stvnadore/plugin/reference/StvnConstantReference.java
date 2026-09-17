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
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.HashSet;
import java.util.Set;

@NullMarked
public final class StvnConstantReference extends PsiReferenceBase<ValueKeyword> {

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
        if (newElementName.startsWith(":") || newElementName.startsWith("#")) {
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
            replacementText = "#" + newElementName;
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
        var valueParent = PsiTreeUtil.getParentOfType(element, Value.class, false);
        if (valueParent != null) {
            var coreVal = StvnTypeResolver.resolveCoreValue(valueParent);
            if (coreVal instanceof org.stvnadore.core.ir.StvnValue.StvnEnum) {
                var enumVariant = StvnValueKeywordReference.findEnumDeclaration(file, tokenText, new HashSet<>());
                if (enumVariant != null) {
                    return enumVariant;
                }
            }
        }
        var constDef = resolveConstantInFile(file, tokenText, new HashSet<>());
        if (constDef != null) {
            return constDef;
        }
        return StvnValueKeywordReference.findEnumDeclaration(file, tokenText, new HashSet<>());
    }

    public static @Nullable PsiElement resolveConstantInFile(PsiFile file, String constName, Set<PsiFile> visited) {
        if (!visited.add(file)) {
            return null;
        }

        // 1. Scan local constant definitions
        var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var def : constDefs) {
            var kw = def.getValueKeyword();
            if (kw != null && kw.getText().equals(constName)) {
                return kw;
            }
        }

        // 2. Scan package enclosures (FQNI expansion and in-scope relative names)
        var packages = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class);
        for (var pkg : packages) {
            var pkgPath = pkg.getPackagePath();
            if (pkgPath == null) continue;
            var pathText = pkgPath.getText();
            for (var elem : pkg.getPackageElementList()) {
                var cDef = elem.getConstantDefinition();
                if (cDef != null) {
                    var kw = cDef.getValueKeyword();
                    if (kw != null) {
                        var kwText = kw.getText();
                        var fqni = pathText + "/" + kwText;
                        if (fqni.equals(constName) || kwText.equals(constName)) {
                            return kw;
                        }
                    }
                }
            }
        }

        // 3. Scan :use statements
        var useStmts = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class);
        for (var use : useStmts) {
            var aliasBlock = use.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var list = alias.getValueKeywordList();
                    if (list.size() >= 2) {
                        var localKw = list.get(1);
                        if (localKw != null && localKw.getText().equals(constName)) {
                            return localKw;
                        }
                    }
                }
            }
            var optBlock = use.getUseOptionsBlock();
            if (optBlock != null && optBlock.getText().contains("#strip")) {
                var target = use.getUseTarget();
                if (target != null) {
                    var prefix = target.getText();
                    var fqni = prefix + "/" + constName;
                    var resolved = resolveConstantInFile(file, fqni, visited);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }

        // 4. Scan imported modules
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                if (targetFile != null) {
                    var resolved = resolveConstantInFile(targetFile, constName, visited);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }

        return null;
    }
}
