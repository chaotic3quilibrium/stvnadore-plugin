package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.StvnSyntaxHighlighterColors;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.math.BigInteger;
import java.util.HashSet;

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

        // 1. Validate in-flight Rule G Union tag bounds without 32-bit integer truncation
        if (element instanceof ExplicitUnionValue explicitUnion
                && !StvnMetaTemplateUtil.isInSecondaryRootSection(explicitUnion)) {
            var prefixElem = explicitUnion.getUnionTagPrefix();
            var prefixText = prefixElem.getText().trim();
            if (prefixText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                var parsedTag = org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator.parseBigInteger(prefixText.substring(1));
                if (parsedTag.isPresent()) {
                    var k = parsedTag.get();
                    var sumType = resolveGoverningUnionSumType(explicitUnion);
                    if (sumType != null) {
                        var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                        int n = branches.size();
                        if (n >= 1 && k.compareTo(BigInteger.valueOf(n)) > 0) {
                            holder.newAnnotation(
                                HighlightSeverity.ERROR,
                                StvnDiagnosticFormatter.formatCore(
                                    "Union variant tag '" + StvnVocabulary.SIGIL_VALUE + k
                                        + "' exceeds branch count (" + n + ")",
                                    "E028",
                                    DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW
                                )
                            )
                                .range(prefixElem.getTextRange())
                                .create();
                        }
                    }
                }
            }
        }

        // 2. Highlight standard syntax categories
        if (type.equals(StvnTypes.TYPE_KEYWORD)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE)
                  .create();
            return;
        }
        if (type.equals(StvnTypes.VALUE_KEYWORD)) {
            if (PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.MetadataUnknownEntry.class, false) != null
                    && PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.MetadataMap.class, false) != null) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                      .textAttributes(StvnSyntaxHighlighterColors.STVN_METADATA_TARGET)
                      .create();
                return;
            }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD)
                  .create();
            return;
        }
        PsiElement parent = element.getParent();
        if (parent instanceof TypeKeyword || parent instanceof ValueKeyword) {
            return;
        }
        if (type.equals(StvnTypes.TYPE_KEYWORD_BASE)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .textAttributes(StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE)
                  .create();
        } else if (type.equals(StvnTypes.VALUE_KEYWORD_BASE)) {
            if (PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.MetadataUnknownEntry.class, false) != null
                    && PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.MetadataMap.class, false) != null) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                      .textAttributes(StvnSyntaxHighlighterColors.STVN_METADATA_TARGET)
                      .create();
                return;
            }
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

    static boolean handlesUnionBranchOverflowAtOffset(PsiFile file, int startOffset) {
        if (startOffset < 0 || startOffset >= file.getTextLength()) {
            return false;
        }
        var leaf = file.findElementAt(startOffset);
        if (leaf == null) {
            return false;
        }
        var explicitUnion = PsiTreeUtil.getParentOfType(leaf, ExplicitUnionValue.class, false);
        if (explicitUnion == null || StvnMetaTemplateUtil.isInSecondaryRootSection(explicitUnion)) {
            return false;
        }
        var sumType = resolveGoverningUnionSumType(explicitUnion);
        if (sumType == null) {
            return false;
        }
        return !PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class).isEmpty();
    }

    static boolean hasActiveUnionBranchOverflowAtOffset(PsiFile file, int startOffset) {
        if (startOffset < 0 || startOffset >= file.getTextLength()) {
            return false;
        }
        var leaf = file.findElementAt(startOffset);
        if (leaf == null) {
            return false;
        }
        var explicitUnion = PsiTreeUtil.getParentOfType(leaf, ExplicitUnionValue.class, false);
        if (explicitUnion == null
                || explicitUnion.getTextRange().getStartOffset() != startOffset
                || StvnMetaTemplateUtil.isInSecondaryRootSection(explicitUnion)) {
            return false;
        }
        var prefixText = explicitUnion.getUnionTagPrefix().getText().trim();
        if (!prefixText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return false;
        }
        var parsedTag = org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator.parseBigInteger(prefixText.substring(1));
        if (parsedTag.isEmpty()) {
            return false;
        }
        var sumType = resolveGoverningUnionSumType(explicitUnion);
        if (sumType == null) {
            return false;
        }
        int n = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class).size();
        return n >= 1 && parsedTag.get().compareTo(BigInteger.valueOf(n)) > 0;
    }

    private static @Nullable SumType resolveGoverningUnionSumType(ExplicitUnionValue explicitUnion) {
        Value enclosingValue = PsiTreeUtil.getParentOfType(explicitUnion, Value.class, false);
        SchemaType candidateSchema = null;
        if (enclosingValue != null) {
            var baseInfo = StvnTypeResolver.resolveBaseTypeInfo(enclosingValue);
            if (baseInfo != null) {
                candidateSchema = baseInfo.getSchema();
            }
        }
        if (candidateSchema == null) {
            candidateSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(explicitUnion);
        }
        var visited = new HashSet<PsiElement>();
        SchemaType curr = candidateSchema;
        for (int depth = 0; depth < 16 && curr != null; depth++) {
            while (curr != null && curr.getSchemaConstructor() == null && visited.add(curr)) {
                var next = StvnTypeResolver.resolveNominalSchema(curr);
                if (next == null || next == curr) {
                    break;
                }
                curr = next;
            }
            if (curr == null || !visited.add(curr)) {
                if (curr == null || curr.getSchemaConstructor() == null) {
                    return null;
                }
            }
            var constructor = curr.getSchemaConstructor();
            var sumType = constructor != null ? constructor.getSumType() : null;
            if (sumType == null || sumType.getEnumDef() != null) {
                return null;
            }
            if (isUnionSumType(sumType)) {
                return sumType;
            }
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
            var sumText = sumType.getText().trim();
            if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && !branches.isEmpty()) {
                curr = branches.get(0);
            } else if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() >= 2) {
                curr = branches.get(1);
            } else {
                return null;
            }
        }
        return null;
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
        return firstChild != null && firstChild.getText().equals(StvnVocabulary.TYPE_UNION);
    }
}