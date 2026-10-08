package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.validation.quickfix.StvnBrandNominalTypeQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnImportUseQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnQualifyTypeQuickFix;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseStmt;
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
        // Skip LHS declaration targets in :defs (they declare a new type, not reference an existing one)
        if (StvnPsiUtils.isTypeDefinitionTarget(typeKw)) {
            return;
        }

        var text = typeKw.getText().trim();
        // Skip already qualified FQNIs
        if (text.startsWith(":org/stvnadore/prelude/")) {
            return;
        }
        // Must be a bare colon identifier
        if (!text.startsWith(":") || text.substring(1).contains("/")) {
            return;
        }

        var project = typeKw.getProject();
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(project, text);
        if (preludeDef == null) {
            return;
        }

        var file = typeKw.getContainingFile();
        if (file == null || "prelude.stvn_inclf".equals(file.getName()) || "org_stvnadore_prelude.stvn_inclf".equals(file.getName())) {
            return;
        }

        // Check whether the type is locally shadowed or defined in :defs
        var localDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : localDefs) {
            var kw = def.getTypeKeyword();
            if (kw != null && text.equals(kw.getText()) && def != typeKw.getParent()) {
                return;
            }
        }

        // Check whether the type is imported via :use
        var useStmts = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : useStmts) {
            var aliasBlock = use.getUseAliasBlock();
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
            var opts = use.getUseOptionsBlock();
            if (opts != null && opts.getText().contains("#strip")) {
                var useTarget = use.getUseTarget();
                if (useTarget != null && useTarget.getText().equals(":org/stvnadore/prelude")) {
                    return;
                }
            }
        }

        var bareName = text;
        var fqni = ":org/stvnadore/prelude/" + text.substring(1);
        var message = "Unresolved Prelude type reference '" + text + "'. Available as '" + fqni + "'.";

        holder.registerProblem(
            typeKw,
            message,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new StvnQualifyTypeQuickFix(typeKw, fqni),
            new StvnImportUseQuickFix(typeKw, ":org/stvnadore/prelude", bareName),
            new StvnBrandNominalTypeQuickFix(typeKw, fqni)
        );
    }
}
