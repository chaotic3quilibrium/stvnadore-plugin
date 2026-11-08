package org.stvnadore.plugin.completion;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.SchemaType;

/**
 * Resolves expected types at caret positions with support for algebraic branch narrowing.
 */
@NullMarked
public final class StvnCompletionTypeResolver {

    private StvnCompletionTypeResolver() {
    }

    /**
     * Resolves the narrowed expected type for completion at the caret position,
     * accounting for preceding sum constructor tags.
     *
     * @param position         the leaf PSI element at the caret
     * @param rootExpectedType the un-narrowed root schema type for the container or body
     * @return the narrowed {@link SchemaType}, or {@code null} if narrowing leads to unit or invalid payload
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
     * Narrows a sum type schema by a single discriminator tag.
     *
     * @param schema  the sum type schema to inspect
     * @param tagText the constructor tag literal (for example {@code #Some}, {@code #Right}, or {@code #1})
     * @return the narrowed schema type for the branch, or {@code null} if unit or invalid
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

        if (sumType.getText().startsWith(StvnVocabulary.TYPE_OPTION)) {
            if (StvnVocabulary.VAL_SOME.equals(tagText) || StvnVocabulary.VAL_SOME_SHORT.equals(tagText)) {
                return !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
            }
            if (StvnVocabulary.VAL_NONE.equals(tagText) || StvnVocabulary.VAL_NONE_SHORT.equals(tagText)) {
                return null;
            }
            if (!innerSchemas.isEmpty()) {
                return narrowTypeByTag(innerSchemas.get(0), tagText);
            }
            return null;
        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
            if (StvnVocabulary.VAL_LEFT.equals(tagText) || StvnVocabulary.VAL_LEFT_SHORT.equals(tagText)) {
                return !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
            } else if (StvnVocabulary.VAL_RIGHT.equals(tagText) || StvnVocabulary.VAL_RIGHT_SHORT.equals(tagText)) {
                return innerSchemas.size() > 1 ? innerSchemas.get(1) : null;
            }
            return null;
        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
            if (tagText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                var branchSpec = tagText.substring(1);
                if (branchSpec.matches("\\d+")) {
                    var resolvedIdx = StvnPsiLiteralEvaluator
                        .resolveZeroBasedUnionBranchIndex(tagText, innerSchemas.size());
                    return resolvedIdx.isPresent() ? innerSchemas.get(resolvedIdx.getAsInt()) : null;
                }
                for (var branch : innerSchemas) {
                    var branchName = StvnSchemaFormatter.formatCleanSchema(branch);
                    if (branchName.equals(StvnVocabulary.SIGIL_TYPIC + branchSpec) || branchName.equals(branchSpec)) {
                        return branch;
                    }
                }
            }
            return null;
        }

        return null;
    }
}
