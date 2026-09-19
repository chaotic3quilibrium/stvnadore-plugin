package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnSyntaxHighlighterColors;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;

/**
 * Validates structural tags and paints semantic types in the editor.
 */
@NullMarked
public final class StvnSemanticAnnotator implements Annotator {

    /** Constructs an StvnSemanticAnnotator instance. */
    public StvnSemanticAnnotator() {}

    @Override
    public void annotate(PsiElement element, AnnotationHolder holder) {
        var node = element.getNode();
        if (node == null) {
            return;
        }

        var type = node.getElementType();

        // 1. Validate leaf module (.stvn_inclf) include constraint
        if (type.equals(StvnTypes.KW_INCLUDE)) {
            var file = element.getContainingFile();
            if (file != null && file.getName().endsWith(".stvn_inclf")) {
                holder.newAnnotation(HighlightSeverity.ERROR, "Leaf module (.stvn_inclf) cannot contain include statements")
                      .range(element.getTextRange())
                      .create();
            }
        }

        // 1b. Validate in-flight Rule G Union tag bounds
        if (element instanceof ExplicitUnionValue explicitUnion) {
            var prefixElem = explicitUnion.getUnionTagPrefix();
            var prefixText = prefixElem.getText().trim();
            if (prefixText.startsWith("#")) {
                try {
                    int k = Integer.parseInt(prefixText.substring(1));
                    var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(explicitUnion);
                    if (expectedSchema != null) {
                        var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
                        var schemaToInspect = (resolvedNominal != null) ? resolvedNominal : expectedSchema;
                        var constructor = schemaToInspect.getSchemaConstructor();
                        if (constructor != null && constructor.getSumType() != null) {
                            var sumType = constructor.getSumType();
                            if (isUnionSumType(sumType)) {
                                var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                int n = branches.size();
                                if (k > n) {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "Union variant tag '#" + k + "' exceeds branch count (" + n + ")")
                                          .range(prefixElem.getTextRange())
                                          .create();
                                }
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 2. Highlight standard syntax categories
        if (type.equals(StvnTypes.TYPE_KEYWORD_BASE)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE)
                  .create();
        } else if (type.equals(StvnTypes.VALUE_KEYWORD_BASE)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD)
                  .create();
        } else if (type.equals(StvnTypes.KW_TRUE) || type.equals(StvnTypes.KW_FALSE) ||
                   type.equals(StvnTypes.KW_TRUE_SHORT) || type.equals(StvnTypes.KW_FALSE_SHORT) ||
                   type.equals(StvnTypes.KW_SOME) || type.equals(StvnTypes.KW_SOME_SHORT) ||
                   type.equals(StvnTypes.KW_NONE) || type.equals(StvnTypes.KW_NONE_SHORT) ||
                   type.equals(StvnTypes.KW_LEFT) || type.equals(StvnTypes.KW_LEFT_SHORT) ||
                   type.equals(StvnTypes.KW_RIGHT) || type.equals(StvnTypes.KW_RIGHT_SHORT)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD)
                  .create();
        } else if (isMetadataTarget(type)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_METADATA_TARGET)
                  .create();
        }
    }

    private static boolean isMetadataTarget(IElementType type) {
        return type.equals(StvnTypes.KW_EQUATABLE) ||
               type.equals(StvnTypes.KW_COMPARABLE) ||
               type.equals(StvnTypes.KW_PRESERVE_INDENT) ||
               type.equals(StvnTypes.KW_MIN_INCL) ||
               type.equals(StvnTypes.KW_MIN_EXCL) ||
               type.equals(StvnTypes.KW_MAX_INCL) ||
               type.equals(StvnTypes.KW_MAX_EXCL) ||
               type.equals(StvnTypes.KW_REGEX);
    }

    private static boolean isUnionSumType(SumType sumType) {
        if (sumType.getEnumDef() != null) {
            return false;
        }
        var node = sumType.getNode();
        if (node != null && node.findChildByType(StvnTypes.KW_UNION) != null) {
            return true;
        }
        var firstChild = sumType.getFirstChild();
        return firstChild != null && firstChild.getText().equals(":Union");
    }
}