package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;

/**
 * Resolves root metadata facets (#name, #domain, #kind) and document kind variants
 * (#BODY, #DEFS, #BODY_INCLUDE, #DEFS_INCLUDE) to the synthetic standard library prelude AST
 * (:org/stvnadore/prelude/meta) per STVN Specification § 10.
 */
@NullMarked
public final class StvnMetaReference extends PsiReferenceBase<PsiElement> {

    private final String targetFqniOrVariant;
    private final boolean isVariantReference;

    /**
     * Constructs a reference resolving a metadata facet or kind value to the standard prelude.
     *
     * @param element the enclosing PSI element
     * @param rangeInElement the text range of the symbol within the element
     * @param targetFqniOrVariant the target prelude FQNI or enum variant identifier
     * @param isVariantReference {@code true} if resolving an enum variant inside :StvnDocumentKind
     */
    public StvnMetaReference(PsiElement element, TextRange rangeInElement, String targetFqniOrVariant, boolean isVariantReference) {
        super(element, rangeInElement);
        this.targetFqniOrVariant = targetFqniOrVariant;
        this.isVariantReference = isVariantReference;
    }

    /**
     * Creates a reference for #name or #domain resolving to :org/stvnadore/prelude/meta/StvnPosixId64.
     *
     * @param facet the MetaNameFacet or MetaDomainFacet element
     * @param length the text length of the facet keyword (5 for #name, 7 for #domain)
     * @return the configured StvnMetaReference
     */
    public static StvnMetaReference forPosixIdFacet(PsiElement facet, int length) {
        return new StvnMetaReference(facet, new TextRange(0, length), ":org/stvnadore/prelude/meta/StvnPosixId64", false);
    }

    /**
     * Creates a reference for #kind resolving to :org/stvnadore/prelude/meta/StvnDocumentKind.
     *
     * @param facet the MetaKindFacet element
     * @return the configured StvnMetaReference
     */
    public static StvnMetaReference forKindFacet(MetaKindFacet facet) {
        return new StvnMetaReference(facet, new TextRange(0, 5), ":org/stvnadore/prelude/meta/StvnDocumentKind", false);
    }

    /**
     * Creates a reference for a MetaKindValue resolving to the variant inside :StvnDocumentKind.
     *
     * @param value the MetaKindValue element
     * @return the configured StvnMetaReference
     */
    public static StvnMetaReference forKindValue(MetaKindValue value) {
        var text = value.getText().trim();
        return new StvnMetaReference(value, new TextRange(0, value.getTextLength()), text, true);
    }

    @Override
    public @Nullable PsiElement resolve() {
        var project = getElement().getProject();
        if (isVariantReference) {
            return StvnPreludeBridge.resolvePreludeEnumVariant(
                project,
                ":org/stvnadore/prelude/meta/StvnDocumentKind",
                targetFqniOrVariant
            );
        }
        return StvnPreludeBridge.resolvePreludeType(project, targetFqniOrVariant);
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        var resolved = resolve();
        if (resolved == null) return false;
        var manager = getElement().getManager();
        if (manager.areElementsEquivalent(resolved, element)) return true;
        var parent = resolved.getParent();
        return parent != null && manager.areElementsEquivalent(parent, element);
    }
}
