package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.PsiTreeUtil;
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

        // 2. Scan imported modules
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
