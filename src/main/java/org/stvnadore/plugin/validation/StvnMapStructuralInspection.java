package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.*;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Real-time local inspection identifying flat ListLiterals supplied to Map collection slots,
 * attaching StvnMapAutoHealerQuickFix to restructure them into canonical MapLiterals,
 * and enforcing bidirectional uniqueness on #invertible map payloads.
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

            @Override
            public void visitMapLiteral(@NotNull MapLiteral mapLiteral) {
                super.visitMapLiteral(mapLiteral);
                var valueParent = PsiTreeUtil.getParentOfType(mapLiteral, Value.class);
                if (valueParent == null) return;
                var typeInfo = StvnTypeResolver.resolveBaseTypeInfo(valueParent);
                if (typeInfo != null && isInvertibleMapSchema(typeInfo.getSchema())) {
                    var seenKeys = new HashMap<String, PsiElement>();
                    var seenValues = new HashMap<String, PsiElement>();
                    var values = mapLiteral.getValueList();
                    for (int i = 0; i + 1 < values.size(); i += 2) {
                        var keyElem = values.get(i);
                        var valElem = values.get(i + 1);
                        var keyText = keyElem.getText();
                        var valText = valElem.getText();

                        if (seenKeys.containsKey(keyText)) {
                            holder.registerProblem(
                                keyElem,
                                "Duplicate map key '" + keyText + "' is forbidden in map collection",
                                ProblemHighlightType.GENERIC_ERROR
                            );
                        } else {
                            seenKeys.put(keyText, keyElem);
                        }

                        if (seenValues.containsKey(valText)) {
                            holder.registerProblem(
                                valElem,
                                "Invertible map violates bidirectional uniqueness invariant: duplicate value '" + valText + "'",
                                ProblemHighlightType.GENERIC_ERROR
                            );
                        } else {
                            seenValues.put(valText, valElem);
                        }
                    }
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
    public static boolean isMapSchema(@Nullable SchemaType schema) {
        if (schema == null) return false;
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
                    return text.equals(":Map");
                }
            }
        }
        return false;
    }

    /**
     * Checks if the given SchemaType resolves to an invertible Map collection type.
     *
     * @param schema the schema type element
     * @return true if schema is an invertible Map collection
     */
    public static boolean isInvertibleMapSchema(@Nullable SchemaType schema) {
        if (schema == null) return false;
        var kw = schema.getTypeKeyword();
        if (kw != null) {
            var file = schema.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, kw.getText(), new HashSet<>());
                var typeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
                if (typeDef != null && typeDef.getMetadataMap() != null) {
                    for (var entry : typeDef.getMetadataMap().getMetadataEntryList()) {
                        if (entry.getText().startsWith("#invertible") || entry.getNode().findChildByType(StvnTypes.KW_INVERTIBLE) != null) {
                            return true;
                        }
                    }
                }
            }
        }
        var parentDef = PsiTreeUtil.getParentOfType(schema, TypeDefinition.class);
        if (parentDef != null && parentDef.getMetadataMap() != null) {
            for (var entry : parentDef.getMetadataMap().getMetadataEntryList()) {
                if (entry.getText().startsWith("#invertible") || entry.getNode().findChildByType(StvnTypes.KW_INVERTIBLE) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}