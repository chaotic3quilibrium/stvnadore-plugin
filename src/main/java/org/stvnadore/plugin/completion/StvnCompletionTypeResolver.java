package org.stvnadore.plugin.completion;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.SchemaType;

import java.util.List;

/**
 * Resolves expected types at caret positions with support for algebraic branch narrowing.
 */
@NullMarked
public final class StvnCompletionTypeResolver {

    private StvnCompletionTypeResolver() {}

    /**
     * Resolves the narrowed expected type for completion at the caret position,
     * accounting for preceding sum constructor tags.
     *
     * @param position The leaf PSI element at the caret.
     * @param rootExpectedType The un-narrowed root schema type for the container/body.
     * @return The narrowed {@link SchemaType}, or {@code null} if narrowing leads to unit/invalid payload.
     */
    public static @Nullable SchemaType resolveExpectedTypeAtCaret(
            PsiElement position,
            SchemaType rootExpectedType
    ) {
        var precedingTags = StvnPsiUtils.findPrecedingTagsInExpression(position);
        var currentSchema = rootExpectedType;

        for (var tag : precedingTags) {
            currentSchema = narrowTypeByTag(currentSchema, tag);
            if (currentSchema == null) {
                return null;
            }
        }

        return currentSchema;
    }

    /**
     * Narrow a sum type schema by a single discriminator tag.
     */
    public static @Nullable SchemaType narrowTypeByTag(SchemaType schema, String tagText) {
        var resolvedNominal = StvnTypeResolver.resolveNominalSchema(schema);
        var schemaToInspect = resolvedNominal != null ? resolvedNominal : schema;
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor == null || constructor.getSumType() == null) {
            return null;
        }

        var sumType = constructor.getSumType();
        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);

        if (sumType.getText().startsWith(":Option")) {
            if ("#Some".equals(tagText) || "#S".equals(tagText)) {
                return !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
            }
            if ("#None".equals(tagText) || "#N".equals(tagText)) {
                return null; // #None takes no payload
            }
            // Rule A Implied Option Unwrapping: If tagText is not #Some or #None,
            // the outer :Option implies #Some. Recursively narrow the inner payload!
            if (!innerSchemas.isEmpty()) {
                return narrowTypeByTag(innerSchemas.get(0), tagText);
            }
            return null;
        } else if (sumType.getText().startsWith(":Either")) {
            if ("#Left".equals(tagText) || "#L".equals(tagText)) {
                return !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
            } else if ("#Right".equals(tagText) || "#R".equals(tagText)) {
                return innerSchemas.size() > 1 ? innerSchemas.get(1) : null;
            }
            return null;
        } else if (sumType.getText().startsWith(":Union")) {
            if (tagText.startsWith("#")) {
                var branchSpec = tagText.substring(1);
                if (branchSpec.matches("\\d+")) {
                    var index = Integer.parseInt(branchSpec) - 1;
                    if (index >= 0 && index < innerSchemas.size()) {
                        return innerSchemas.get(index);
                    }
                }
                for (var branch : innerSchemas) {
                    var branchName = StvnSchemaFormatter.formatCleanSchema(branch);
                    if (branchName.equals(":" + branchSpec) || branchName.equals(branchSpec)) {
                        return branch;
                    }
                }
            }
            return null;
        }

        return null;
    }
}
