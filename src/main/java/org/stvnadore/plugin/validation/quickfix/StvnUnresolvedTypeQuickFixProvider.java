package org.stvnadore.plugin.validation.quickfix;

import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnNamespaceIndexHelper;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnSchemaIndexHelper;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.TypeKeyword;

import java.util.ArrayList;

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
        var file = typeKw.getContainingFile();
        if (file == null) {
            return builder;
        }

        boolean isModular = StvnPsiUtils.isModularDocument(file);
        boolean isHermetic = StvnPsiUtils.isHermeticDocument(file);

        // Case A: Standard Library Prelude Symbols (unless shadowed by local co-located package)
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(project, bareName);
        if (preludeDef != null && !StvnPsiUtils.isTypeDeclaredInLocalPackage(file, bareName)) {
            var pkgPath = StvnPreludeBridge.getPreludePackagePath(preludeDef);
            var fqni = pkgPath + "/" + bareName.substring(1);
            builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni));
            builder = builder.withFix(new StvnImportUseQuickFix(typeKw, pkgPath, bareName));
            builder = builder.withFix(new StvnBrandNominalTypeQuickFix(typeKw, fqni));
            return builder;
        }

        // Case B: Non-Prelude Project & Namespace Dependency Symbols
        var matchingNamespaces = new ArrayList<>(StvnNamespaceIndexHelper.findExportingNamespaces(project, bareName));
        var localPackages = PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class);
        for (var pkg : localPackages) {
            var path = pkg.getPackagePath();
            if (path == null) continue;
            for (var elem : pkg.getPackageElementList()) {
                var def = elem.getTypeDefinition();
                if (def != null && def.getTypeKeyword() != null) {
                    var kwText = def.getTypeKeyword().getText();
                    if (kwText.equals(bareName) || kwText.equals(":" + bareName)) {
                        var pkgText = path.getText();
                        if (!matchingNamespaces.contains(pkgText)) {
                            matchingNamespaces.add(pkgText);
                        }
                    }
                }
            }
        }

        if (!matchingNamespaces.isEmpty()) {
            var localNamespaces = matchingNamespaces.stream()
                .filter(ns -> StvnPsiUtils.isPackageDeclaredLocally(file, ns))
                .toList();

            if (isHermetic) {
                // Spec Invariant 7: Hermetic files cannot import external packages via :use
                if (!localNamespaces.isEmpty()) {
                    for (var localNs : localNamespaces) {
                        var fqni = localNs + "/" + bareName.substring(1);
                        builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni));
                        builder = builder.withFix(new StvnImportUseQuickFix(typeKw, localNs, bareName, "Import '" + bareName + "' from " + localNs + " via :use"));
                        builder = builder.withFix(new StvnBrandNominalTypeQuickFix(typeKw, fqni));
                    }
                } else {
                    builder = builder.withFix(new StvnCreateNominalTypeQuickFix(typeKw, StvnVocabulary.TYPE_STRING));
                    boolean isSchema = file.getName().endsWith(".stvn_d");
                    builder = builder.withFix(new StvnConvertToModularQuickFix(typeKw, isSchema));
                }
                return builder;
            }

            if (matchingNamespaces.size() == 1) {
                var targetNs = matchingNamespaces.get(0);
                var fqni = targetNs + "/" + bareName.substring(1);
                builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni));
                builder = builder.withFix(new StvnImportUseQuickFix(typeKw, targetNs, bareName));
                builder = builder.withFix(new StvnBrandNominalTypeQuickFix(typeKw, fqni));
            } else {
                for (var targetNs : matchingNamespaces) {
                    var fqni = targetNs + "/" + bareName.substring(1);
                    builder = builder.withFix(new StvnQualifyTypeQuickFix(typeKw, fqni, "Qualify as '" + fqni + "'"));
                    builder = builder.withFix(new StvnImportUseQuickFix(typeKw, targetNs, bareName, "Import '" + bareName + "' from " + targetNs + " via :use"));
                }
            }
            return builder;
        }

        // Case C: Unresolved Type (Catalog-Grounded Candidate Discovery)
        var candidateSchemas = StvnSchemaIndexHelper.findDeclaringSchemaFiles(project, bareName);
        var otherSchemas = candidateSchemas.stream().filter(cand -> !cand.equals(file)).toList();

        if (isModular) {
            for (var schemaFile : otherSchemas) {
                var relPath = StvnSchemaIndexHelper.computeRelativeIncludePath(file, schemaFile);
                if (relPath != null && !relPath.isEmpty()) {
                    builder = builder.withFix(new StvnAddIncludeQuickFix(typeKw, relPath));
                }
            }
            builder = builder.withFix(new StvnCreateNominalTypeQuickFix(typeKw, StvnVocabulary.TYPE_STRING));
        } else {
            builder = builder.withFix(new StvnCreateNominalTypeQuickFix(typeKw, StvnVocabulary.TYPE_STRING));
            boolean isSchema = file.getName().endsWith(".stvn_d");
            builder = builder.withFix(new StvnConvertToModularQuickFix(typeKw, isSchema));
        }

        return builder;
    }
}
