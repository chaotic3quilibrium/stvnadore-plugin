package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

/**
 * Real-time local inspection identifying flat ListLiterals supplied to Map collection slots,
 * attaching StvnMapAutoHealerQuickFix to restructure them into canonical MapLiterals.
 */
@NullMarked
public final class StvnMapStructuralInspection extends LocalInspectionTool {

    /** Constructs an StvnMapStructuralInspection instance. */
    public StvnMapStructuralInspection() {}

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitListLiteral(@NotNull ListLiteral listLiteral) {
                super.visitListLiteral(listLiteral);
                var valueParent = PsiTreeUtil.getParentOfType(listLiteral, Value.class);
                if (valueParent == null) {
                    return;
                }

                var typeInfo = StvnTypeResolver.resolveBaseTypeInfo(valueParent);
                if (typeInfo != null && isMapSchema(typeInfo.getSchema())) {
                    holder.registerProblem(
                        listLiteral,
                        "Flat list supplied to map slot; map collections require canonical '{ [ key value ] }' literal envelope (STVN Spec §5.4)",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new StvnMapAutoHealerQuickFix(listLiteral)
                    );
                }
            }
        };
    }

    /**
     * Checks if the given SchemaType resolves to a Map collection type.
     *
     * @param schema the schema type element
     * @return true if schema is a Map collection
     */
    public static boolean isMapSchema(SchemaType schema) {
        var resolved = StvnTypeResolver.resolveNominalSchema(schema);
        if (resolved == null) {
            return false;
        }
        var constructor = resolved.getSchemaConstructor();
        if (constructor != null) {
            var coll = constructor.getCollectionType();
            if (coll != null) {
                var firstChild = coll.getFirstChild();
                if (firstChild != null) {
                    var text = firstChild.getText();
                    return text.equals(":Map") || text.equals(":MapNonEmpty")
                        || text.equals(":MapInv") || text.equals(":MapInvNonEmpty");
                }
            }
        }
        return false;
    }
}