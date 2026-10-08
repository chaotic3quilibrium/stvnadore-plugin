package org.stvnadore.plugin.validation.quickfix;

import com.intellij.lang.annotation.AnnotationBuilder;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.reference.StvnNamespaceIndexHelper;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.TypeKeyword;

/**
 * Factory and registry attaching identity-preserving and nominal branding quick-fixes
 * to unresolved type annotations.
 */
@NullMarked
public final class StvnUnresolvedTypeQuickFixProvider {

    private StvnUnresolvedTypeQuickFixProvider() {}

    /**
     * Inspects an unresolved TypeKeyword and attaches the appropriate Quick-Fix actions.
     *
     * @param builder the annotation builder
     * @param typeKw the offending unresolved TypeKeyword
     * @return the enhanced annotation builder
     */
    public static AnnotationBuilder registerFixes(AnnotationBuilder builder, TypeKeyword typeKw) {
        var tokenText = typeKw.getText().trim();
        if (!tokenText.startsWith(":") || tokenText.substring(1).contains("/")) {
            return builder;
        }

        var bareName = tokenText;
        var project = typeKw.getProject();

        // Case A: Standard Library Prelude Symbols
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(project, bareName);
        if (preludeDef != null) {
            var fqni = ":org/stvnadore/prelude/" + bareName.substring(1);
            builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni));
            builder = builder.withFix(new StvnImportUseQuickFix(typeKw, ":org/stvnadore/prelude", bareName));
            builder = builder.withFix(new StvnBrandNominalTypeQuickFix(typeKw, fqni));
            return builder;
        }

        // Case B: Non-Prelude Project & Namespace Dependency Symbols
        var matchingNamespaces = StvnNamespaceIndexHelper.findExportingNamespaces(project, bareName);
        if (matchingNamespaces.size() == 1) {
            var targetNs = matchingNamespaces.get(0);
            var fqni = targetNs + "/" + bareName.substring(1);
            builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni));
            builder = builder.withFix(new StvnImportUseQuickFix(typeKw, targetNs, bareName));
            builder = builder.withFix(new StvnBrandNominalTypeQuickFix(typeKw, fqni));
        } else if (matchingNamespaces.size() > 1) {
            for (var targetNs : matchingNamespaces) {
                var fqni = targetNs + "/" + bareName.substring(1);
                builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni, "Qualify as '" + fqni + "'"));
                builder = builder.withFix(new StvnImportUseQuickFix(typeKw, targetNs, bareName, "Import '" + bareName + "' from " + targetNs + " via :use"));
            }
        } else {
            // Case C: Unresolved Brand-New Type (Not in Prelude or Sibling Modules)
            builder = builder.withFix(new StvnCreateNominalTypeQuickFix(typeKw, StvnVocabulary.TYPE_STRING));
            builder = builder.withFix(new StvnAddIncludeQuickFix(typeKw, "common_types.stvn_incl"));
        }

        return builder;
    }
}
