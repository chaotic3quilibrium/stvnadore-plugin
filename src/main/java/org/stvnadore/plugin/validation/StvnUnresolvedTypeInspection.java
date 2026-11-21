package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnBrandNominalTypeQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnImportUseQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnQualifyTypeQuickFix;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.PackagePath;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.Visitor;

/**
 * Local inspection identifying unresolved Standard Library Prelude type references
 * and offering atomic in-place qualification and batch fix-all actions.
 */
@NullMarked
public final class StvnUnresolvedTypeInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnUnresolvedTypeInspection instance.
     */
    public StvnUnresolvedTypeInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeKeyword(@NotNull TypeKeyword typeKw) {
                super.visitTypeKeyword(typeKw);
                inspectTypeKeyword(typeKw, holder);
            }
        };
    }

    private static void inspectTypeKeyword(TypeKeyword typeKw, ProblemsHolder holder) {
        if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeKw)) {
            return;
        }
        // Skip LHS declaration targets in :defs (they declare a new type, not reference an existing one)
        if (StvnPsiUtils.isTypeDefinitionTarget(typeKw)
                || PsiTreeUtil.getParentOfType(typeKw, UseTarget.class, PackagePath.class) != null
                || typeKw.getParent() instanceof IncludeMapAlias) {
            return;
        }

        var text = typeKw.getText().trim();
        // Skip already qualified FQNIs
        if (text.startsWith(":org/stvnadore/prelude/")) {
            return;
        }
        // Must be a bare colon identifier
        if (!text.startsWith(StvnVocabulary.SIGIL_TYPIC) || text.substring(1).contains("/")) {
            return;
        }

        var project = typeKw.getProject();
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(project, text);
        if (preludeDef == null) {
            return;
        }

        var file = typeKw.getContainingFile();
        if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
            return;
        }

        if (typeKw.getParent() instanceof UseMapAlias useAlias) {
            var list = useAlias.getTypeKeywordList();
            if (list.size() >= 2 && list.get(1) == typeKw) {
                return;
            }
            if (!list.isEmpty() && list.get(0) == typeKw) {
                var enclosingUse = PsiTreeUtil.getParentOfType(useAlias, UseElement.class);
                if (enclosingUse != null && enclosingUse.getUseTarget() != null) {
                    var targetPkg = enclosingUse.getUseTarget().getText().trim();
                    var expectedPkg = StvnPreludeBridge.getPreludePackagePath(preludeDef);
                    if (targetPkg.equals(expectedPkg) || !targetPkg.startsWith(":org/stvnadore/prelude")) {
                        return;
                    }
                }
            }
        }

        // Skip when already resolved via local :defs, enclosing :package, :use, or :include module
        if (StvnTypeReference.resolveTypeKeywordWithoutImplicitPrelude(typeKw) != null) {
            return;
        }

        // Check whether the type is locally shadowed or defined in :defs
        var localDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : localDefs) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(def)) {
                continue;
            }
            var kw = def.getTypeKeyword();
            if (kw != null && text.equals(kw.getText()) && def != typeKw.getParent()) {
                return;
            }
        }

        // Check whether the type is imported via :use
        var useStmts = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : useStmts) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(use)) {
                continue;
            }
            for (var useElem : use.getUseElementList()) {
                var aliasBlock = useElem.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (var alias : aliasBlock.getUseMapAliasList()) {
                        var list = alias.getTypeKeywordList();
                        if (list.size() >= 2 && list.get(1) != null && text.equals(list.get(1).getText())) {
                            return;
                        }
                        if (list.size() == 1 && list.get(0) != null && text.equals(list.get(0).getText())) {
                            return;
                        }
                    }
                }
                if (StvnPsiUtils.hasValidStripOption(useElem.getUseOptionsBlock())) {
                    var useTarget = useElem.getUseTarget();
                    var pkgPath = StvnPreludeBridge.getPreludePackagePath(preludeDef);
                    if (useTarget != null && (useTarget.getText().equals(":org/stvnadore/prelude") || useTarget.getText().equals(pkgPath))) {
                        return;
                    }
                }
            }
        }

        var bareName = text;
        var pkgPath = StvnPreludeBridge.getPreludePackagePath(preludeDef);
        var fqni = pkgPath + "/" + text.substring(1);
        var message = StvnDiagnosticFormatter.formatPlugin("Unresolved Prelude type reference '" + text + "'. Available as '" + fqni + "'", StvnDiagnosticCodes.ERR_UNRESOLVED_TYPE);

        holder.registerProblem(
            typeKw,
            message,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new StvnQualifyTypeQuickFix(typeKw, fqni),
            new StvnImportUseQuickFix(typeKw, pkgPath, bareName),
            new StvnBrandNominalTypeQuickFix(typeKw, fqni)
        );
    }
}
