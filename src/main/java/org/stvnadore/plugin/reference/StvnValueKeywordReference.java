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
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.HashSet;
import java.util.Set;

/**
 * Context-aware reference resolver for value keywords, sum literals, and boolean keywords.
 * Disambiguates between Enum variant definitions (Dimension 1) and constant declarations (Dimension 2/3/4).
 */
@NullMarked
public final class StvnValueKeywordReference extends PsiReferenceBase<PsiElement> {

    public StvnValueKeywordReference(PsiElement element) {
        super(element, extractHeadTokenRange(element));
    }

    private static TextRange extractHeadTokenRange(PsiElement element) {
        var text = element.getText().trim();
        if (text.startsWith("#")) {
            var firstWord = text.split("\\s+")[0];
            return new TextRange(0, firstWord.length());
        }
        return new TextRange(0, element.getTextLength());
    }

    @Override
    public @Nullable PsiElement resolve() {
        var element = getElement();
        var file = element.getContainingFile();
        if (file == null) {
            return null;
        }

        var tokenText = element.getText().trim().split("\\s+")[0];
        if (!tokenText.startsWith("#")) {
            return null;
        }

        // 1. Resolve target schema at this coordinate to determine Dimension 1 (:Enum) vs Dimension 2 (:defs constant)
        var valueParent = PsiTreeUtil.getParentOfType(element, Value.class, false);
        if (valueParent != null) {
            var coreVal = StvnTypeResolver.resolveCoreValue(valueParent);
            if (coreVal instanceof org.stvnadore.core.ir.StvnValue.StvnEnum) {
                var enumVariant = findEnumDeclaration(file, tokenText, new HashSet<>());
                if (enumVariant != null) {
                    return enumVariant;
                }
            }
        }

        // 2. Resolve to Constant Definition in :defs (Dimension 2/3/4)
        var constDef = StvnConstantReference.resolveConstantInFile(file, tokenText, new HashSet<>());
        if (constDef != null) {
            return constDef;
        }

        // 3. Fallback to Enum Declaration if constant was not found
        return findEnumDeclaration(file, tokenText, new HashSet<>());
    }

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
