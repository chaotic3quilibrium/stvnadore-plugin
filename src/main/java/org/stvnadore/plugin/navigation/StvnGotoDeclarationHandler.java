package org.stvnadore.plugin.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

/**
 * Custom Goto Declaration handler enabling direct navigation from STVN root metadata facets
 * ({@code #name}, {@code #domain}, {@code #kind}, and {@code #DEFS}), scoped constant references
 * ({@code #CONST} and {@code #pkg/CONST}), and {@code :body} / {@link ConstantDefinition} payload values
 * to their corresponding canonical declarations in {@code :defs}, included modules, or the standard library prelude.
 */
@NullMarked
public final class StvnGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null || sourceElement instanceof PsiWhiteSpace || sourceElement instanceof PsiComment) {
            return null;
        }

        var file = sourceElement.getContainingFile();
        if (file == null) return null;

        // 1. MetaKindValue (#DEFS, #BODY, etc.)
        var kindVal = PsiTreeUtil.getParentOfType(sourceElement, MetaKindValue.class, false);
        if (kindVal != null) {
            var variantName = kindVal.getText();
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeEnumVariant(file, ":org/stvnadore/prelude/meta/StvnDocumentKind", variantName)
                : StvnPreludeBridge.resolvePreludeEnumVariant(file.getProject(), ":org/stvnadore/prelude/meta/StvnDocumentKind", variantName);
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 2. MetaKindFacet (#kind)
        var kindFacet = PsiTreeUtil.getParentOfType(sourceElement, MetaKindFacet.class, false);
        if (kindFacet != null && sourceElement.getText().equals(StvnVocabulary.FACET_KW_META_KIND)) {
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeTypeDefinition(file, StvnVocabulary.META_TYPE_DOCUMENT_KIND)
                : StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), StvnVocabulary.META_TYPE_DOCUMENT_KIND);
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 3. MetaNameFacet (#name)
        var nameFacet = PsiTreeUtil.getParentOfType(sourceElement, MetaNameFacet.class, false);
        if (nameFacet != null && sourceElement.getText().equals(StvnVocabulary.FACET_KW_META_NAME)) {
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeTypeDefinition(file, StvnVocabulary.META_TYPE_POSIX_ID_64)
                : StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), StvnVocabulary.META_TYPE_POSIX_ID_64);
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 4. MetaDomainFacet (#domain)
        var domainFacet = PsiTreeUtil.getParentOfType(sourceElement, MetaDomainFacet.class, false);
        if (domainFacet != null && sourceElement.getText().equals(StvnVocabulary.FACET_KW_META_DOMAIN)) {
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeTypeDefinition(file, StvnVocabulary.META_TYPE_POSIX_ID_64)
                : StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), StvnVocabulary.META_TYPE_POSIX_ID_64);
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 5. ValueKeyword (#CONST reference, UseMapAlias remote constant, or :Enum variant)
        var valKw = PsiTreeUtil.getParentOfType(sourceElement, ValueKeyword.class, false);
        if (valKw != null) {
            if (StvnPsiUtils.isConstantDefinitionTarget(valKw) || valKw.getParent() instanceof EnumDef) {
                return null;
            }

            // Rule 1: ConstantDefinition reference takes precedence over governing nominal type jump
            var constResolved = StvnConstantReference.resolveConstantWithContext(valKw);
            if (constResolved != null) {
                var targetConstDef = StvnPsiUtils.resolveTargetConstantDefinition(constResolved);
                return new PsiElement[]{ targetConstDef != null ? targetConstDef : constResolved };
            }

            // Rule 2: :Enum variant in :body or ConstantDefinition jumps to governing nominal TypeDefinition
            // (or falls through to inline EnumDef variant if governed by an anonymous :Enum)
            var enclosingVal = PsiTreeUtil.getParentOfType(valKw, Value.class, false);
            if (enclosingVal != null && isInsideBodyOrConstantValue(enclosingVal)) {
                var governingTypeDef = StvnTypeResolver.resolveGoverningNominalTypeDefinition(enclosingVal);
                if (governingTypeDef != null) {
                    return new PsiElement[]{ governingTypeDef };
                }
            }

            // Variant in #filterIncl / #filterExcl or anonymous inline :Enum jumps to EnumDef variant keyword
            var enumVariant = StvnTypeResolver.resolveGoverningEnumVariant(valKw);
            if (enumVariant != null) {
                return new PsiElement[]{ enumVariant };
            }
            var ref = valKw.getReference();
            var fallbackResolved = ref != null ? ref.resolve() : new StvnConstantReference(valKw).resolve();
            if (fallbackResolved != null && fallbackResolved != valKw) {
                var target = fallbackResolved.getParent() instanceof ConstantDefinition cDef ? cDef : fallbackResolved;
                return new PsiElement[]{ target };
            }
        }

        // 6. TypeKeyword inside UseMapAlias at index 0 (remote type symbol)
        var typeKw = PsiTreeUtil.getParentOfType(sourceElement, TypeKeyword.class, false);
        if (typeKw != null) {
            if (typeKw.getParent() instanceof UseMapAlias alias) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2 && list.get(0) == typeKw) {
                    var ref = typeKw.getReference();
                    var resolved = ref != null ? ref.resolve() : new StvnTypeReference(typeKw).resolve();
                    if (resolved != null) {
                        var target = resolved.getParent() instanceof TypeDefinition tDef ? tDef : resolved;
                        return new PsiElement[]{ target };
                    }
                }
            }
        }

        // 7. Rule 3: Scalar literals, sum constructor tags, and container delimiters in :body or ConstantDefinition
        // jump to their governing nominal alias TypeDefinition or inline member SchemaType in :defs
        var targetValue = findNavigablePayloadValue(sourceElement);
        if (targetValue != null) {
            var governingTarget = StvnTypeResolver.resolveGoverningDeclarationTarget(targetValue);
            if (governingTarget != null) {
                return new PsiElement[]{ governingTarget };
            }
        }

        return null;
    }

    private static boolean isInsideBodyOrConstantValue(Value value) {
        return StvnPsiUtils.isInsideBody(value)
            || PsiTreeUtil.getParentOfType(value, ConstantDefinition.class, false) != null;
    }

    private static @Nullable Value findNavigablePayloadValue(PsiElement sourceElement) {
        var value = PsiTreeUtil.getParentOfType(sourceElement, Value.class, false);
        if (value == null || !isInsideBodyOrConstantValue(value)) {
            return null;
        }
        if (value.getCollectionValue() != null) {
            if (!isContainerDelimiterToken(sourceElement)) {
                return null;
            }
        }
        return value;
    }

    private static boolean isContainerDelimiterToken(PsiElement token) {
        var parent = token.getParent();
        if (!(parent instanceof TupleLiteral || parent instanceof ListLiteral || parent instanceof MapLiteral)) {
            return false;
        }
        var type = token.getNode() != null ? token.getNode().getElementType() : null;
        return type == StvnTypes.LPAREN || type == StvnTypes.RPAREN
            || type == StvnTypes.LBRACK || type == StvnTypes.RBRACK
            || type == StvnTypes.LBRACE || type == StvnTypes.RBRACE;
    }

    /**
     * Resolves the highlight text range for a declaration navigation candidate at the given editor offset.
     *
     * @param editor the active editor
     * @param offset the caret offset in the document
     * @return the text range of the navigable source token, or {@code null} if none
     */
    public @Nullable TextRange getGotoDeclarationRange(@NotNull Editor editor, int offset) {
        var project = editor.getProject();
        if (project == null) return null;
        var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (file == null) return null;
        var sourceElement = file.findElementAt(offset);
        return getGotoDeclarationRange(sourceElement, offset, editor);
    }

    /**
     * Resolves the highlight text range for a declaration navigation candidate from a source element.
     *
     * @param sourceElement the leaf PSI element under the cursor
     * @param offset        the caret offset in the document
     * @param editor        the active editor
     * @return the text range of the navigable source token, or {@code null} if none
     */
    public @Nullable TextRange getGotoDeclarationRange(@Nullable PsiElement sourceElement, int offset, @Nullable Editor editor) {
        if (sourceElement == null || sourceElement instanceof PsiWhiteSpace || sourceElement instanceof PsiComment) {
            return null;
        }
        var kindVal = PsiTreeUtil.getParentOfType(sourceElement, MetaKindValue.class, false);
        if (kindVal != null) {
            return kindVal.getTextRange();
        }
        var text = sourceElement.getText();
        if (text.equals(StvnVocabulary.FACET_KW_META_KIND)
                || text.equals(StvnVocabulary.FACET_KW_META_NAME)
                || text.equals(StvnVocabulary.FACET_KW_META_DOMAIN)) {
            return sourceElement.getTextRange();
        }
        var valKw = PsiTreeUtil.getParentOfType(sourceElement, ValueKeyword.class, false);
        if (valKw != null) {
            return valKw.getTextRange();
        }
        var typeKw = PsiTreeUtil.getParentOfType(sourceElement, TypeKeyword.class, false);
        if (typeKw != null) {
            return typeKw.getTextRange();
        }
        return sourceElement.getTextRange();
    }
}
