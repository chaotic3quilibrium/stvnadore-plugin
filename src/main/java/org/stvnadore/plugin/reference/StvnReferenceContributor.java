package org.stvnadore.plugin.reference;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.ValueKeyword;

/**
 * Contributes PsiReference bindings for STVN type keywords, value keywords,
 * and include string paths across the entire AST.
 */
@NullMarked
public final class StvnReferenceContributor extends PsiReferenceContributor {

    /** Constructs an StvnReferenceContributor instance. */
    public StvnReferenceContributor() {}

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        // 1. TypeKeyword Reference Provider: matches all TypeKeyword elements
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(TypeKeyword.class),
            new PsiReferenceProvider() {
                @Override
                public PsiReference @NotNull [] getReferencesByElement(
                        @NotNull PsiElement element,
                        @NotNull ProcessingContext context) {
                    if (!(element instanceof TypeKeyword typeKw)) {
                        return PsiReference.EMPTY_ARRAY;
                    }

                    var parent = typeKw.getParent();

                    // Declaration Isolation: LHS of TypeDefinition is a declaration point and returns no reference
                    if (parent instanceof TypeDefinition typeDef && typeDef.getTypeKeyword() == typeKw) {
                        return PsiReference.EMPTY_ARRAY;
                    }

                    // Declaration Isolation: RHS (second keyword) of IncludeMapAlias is a local declaration point
                    if (parent instanceof IncludeMapAlias alias) {
                        var list = alias.getTypeKeywordList();
                        if (list.size() >= 2 && list.get(1) == typeKw) {
                            return PsiReference.EMPTY_ARRAY;
                        }
                    }

                    return new PsiReference[]{new StvnTypeReference(typeKw)};
                }
            }
        );

        // 2. ValueKeyword & Sum/Bool Literal Reference Provider: matches constant and enum references
        var valueKeywordProvider = new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(
                    @NotNull PsiElement element,
                    @NotNull ProcessingContext context) {
                var text = element.getText();
                if (text.startsWith("#")) {
                    var parent = element.getParent();
                    // Declaration Isolation: LHS of ConstantDefinition is a declaration site
                    if (parent instanceof ConstantDefinition constDef && constDef.getValueKeyword() == element) {
                        return PsiReference.EMPTY_ARRAY;
                    }
                    // Declaration Isolation: Enum variant inside EnumDef is a declaration site
                    if (parent instanceof org.stvnadore.psi.EnumDef) {
                        return PsiReference.EMPTY_ARRAY;
                    }

                    return new PsiReference[]{new StvnValueKeywordReference(element)};
                }
                return PsiReference.EMPTY_ARRAY;
            }
        };

        registrar.registerReferenceProvider(PlatformPatterns.psiElement(ValueKeyword.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.SomeLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.SomeShortLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.NoneLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.NoneShortLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.LeftLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.LeftShortLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.RightLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.RightShortLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.TrueLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.TrueShortLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.FalseLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.FalseShortLiteral.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.BooleanValue.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.ExplicitOptionValue.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.ExplicitEitherValue.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(org.stvnadore.psi.ExplicitUnionValue.class), valueKeywordProvider);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), valueKeywordProvider);

        // 3. StringLiteral Reference Provider: matches include file paths
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(StringLiteral.class).withParent(IncludeElement.class),
            new PsiReferenceProvider() {
                @Override
                public PsiReference @NotNull [] getReferencesByElement(
                        @NotNull PsiElement element,
                        @NotNull ProcessingContext context) {
                    if (!(element instanceof StringLiteral stringLit)) {
                        return PsiReference.EMPTY_ARRAY;
                    }
                    return new PsiReference[]{new StvnIncludeReference(stringLit)};
                }
            }
        );
    }
}
