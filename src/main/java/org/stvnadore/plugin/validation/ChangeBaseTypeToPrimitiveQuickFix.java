package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

import java.util.HashSet;
import java.util.Set;

/**
 * Primary quick-fix ({@link PriorityAction.Priority#TOP}) for {@code E040} ({@code ERR_NON_OVERRIDABLE_FACET})
 * and cross-hop {@code E008} ({@code ERR_MUTUALLY_EXCLUSIVE} with {@code "ancestor"}) on a nominal type reference.
 *
 * <p>Replaces the nominal base type reference (for example, {@code :Int32}, {@code :Float64}, {@code :PlainMap})
 * with its underlying root primitive or collection constructor (for example, {@code :Int}, {@code :Float},
 * {@code :Map( :String :Int )}) when doing so makes the local {@link MetadataMap} valid.
 */
public final class ChangeBaseTypeToPrimitiveQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String nominalAlias;
    private final String rootTypeText;
    private final String replacementConstructorText;

    private ChangeBaseTypeToPrimitiveQuickFix(
            @NotNull MetadataEntry offendingEntry,
            @NotNull String nominalAlias,
            @NotNull String rootTypeText,
            @NotNull String replacementConstructorText
    ) {
        super(offendingEntry);
        this.nominalAlias = nominalAlias;
        this.rootTypeText = rootTypeText;
        this.replacementConstructorText = replacementConstructorText;
    }

    /**
     * Evaluates whether changing the enclosing {@link SchemaType}'s nominal reference to its underlying
     * root constructor is applicable and makes the local {@link MetadataMap} valid.
     *
     * @param file           the containing PSI file
     * @param offendingEntry the offending {@link MetadataEntry} flagged with {@code E040} or cross-hop {@code E008}
     * @return a configured {@link ChangeBaseTypeToPrimitiveQuickFix} instance, or {@code null} if inapplicable
     */
    public static @Nullable ChangeBaseTypeToPrimitiveQuickFix createIfApplicable(
            @NotNull PsiFile file,
            @NotNull MetadataEntry offendingEntry
    ) {
        var metaMap = PsiTreeUtil.getParentOfType(offendingEntry, MetadataMap.class);
        if (metaMap == null) {
            return null;
        }

        SchemaType targetSchemaType = resolveGoverningSchemaType(metaMap);
        if (targetSchemaType == null) {
            return null;
        }

        TypeKeyword targetTypeKeyword = targetSchemaType.getTypeKeyword();
        if (targetTypeKeyword == null || targetSchemaType.getSchemaConstructor() != null) {
            return null;
        }

        String nominalAlias = targetTypeKeyword.getText().trim();
        if (nominalAlias.isEmpty()
                || StvnVocabulary.isBaseScalarType(nominalAlias)
                || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(nominalAlias)) {
            return null;
        }

        SchemaConstructor rootConstructor = resolveRootSchemaConstructor(file, nominalAlias);
        if (rootConstructor == null) {
            return null;
        }

        String rootTypeText = extractRootConstructorKeyword(rootConstructor);
        if (rootTypeText == null || !isSupportedPrimitiveOrCollectionRoot(rootTypeText)) {
            return null;
        }

        String replacementConstructorText = StvnSchemaFormatter.formatCleanSchema(rootConstructor);
        if (replacementConstructorText.isEmpty()) {
            replacementConstructorText = rootConstructor.getText().trim();
        }
        if (replacementConstructorText.isEmpty() || replacementConstructorText.equals(nominalAlias)) {
            return null;
        }

        if (!isLocalMetadataMapValidAfterReplacement(file, metaMap, targetTypeKeyword, replacementConstructorText)) {
            return null;
        }

        return new ChangeBaseTypeToPrimitiveQuickFix(
                offendingEntry,
                nominalAlias,
                rootTypeText,
                replacementConstructorText
        );
    }

    private static @Nullable SchemaType resolveGoverningSchemaType(@NotNull MetadataMap metaMap) {
        var parent = metaMap.getParent();
        if (parent instanceof SchemaType st) {
            return st;
        }
        if (parent instanceof TypeDefinition td) {
            return td.getSchemaType();
        }
        if (parent instanceof ConstantDefinition cd) {
            return cd.getSchemaType();
        }
        return PsiTreeUtil.getParentOfType(metaMap, SchemaType.class);
    }

    private static @Nullable SchemaConstructor resolveRootSchemaConstructor(
            @NotNull PsiFile startFile,
            @NotNull String startAlias
    ) {
        Set<PsiElement> visitedDefs = new HashSet<>();
        PsiFile currentFile = startFile;
        String currentAlias = startAlias;

        for (int hop = 0; hop < 32; hop++) {
            var resolved = StvnTypeReference.resolveTypeInFile(currentFile, currentAlias, new HashSet<>());
            var typeDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
            if (typeDef == null || !visitedDefs.add(typeDef)) {
                return null;
            }

            var schemaType = typeDef.getSchemaType();
            if (schemaType == null) {
                return null;
            }

            if (schemaType.getSchemaConstructor() != null) {
                return schemaType.getSchemaConstructor();
            }

            var nextKw = schemaType.getTypeKeyword();
            if (nextKw == null) {
                return null;
            }

            String nextAlias = nextKw.getText().trim();
            if (nextAlias.isEmpty() || nextAlias.equals(currentAlias)) {
                return null;
            }

            if (StvnVocabulary.isBaseScalarType(nextAlias)) {
                var dummySchema = StvnElementFactory.createSchemaType(startFile.getProject(), nextAlias);
                return dummySchema.getSchemaConstructor();
            }

            currentFile = typeDef.getContainingFile() != null ? typeDef.getContainingFile() : currentFile;
            currentAlias = nextAlias;
        }
        return null;
    }

    private static @Nullable String extractRootConstructorKeyword(@NotNull SchemaConstructor constructor) {
        if (constructor.getAtomicType() != null) {
            return constructor.getAtomicType().getText().trim();
        }
        if (constructor.getCollectionType() != null) {
            var first = constructor.getCollectionType().getFirstChild();
            return first != null ? first.getText().trim() : null;
        }
        if (constructor.getProductType() != null) {
            var first = constructor.getProductType().getFirstChild();
            return first != null ? first.getText().trim() : null;
        }
        if (constructor.getSumType() != null) {
            var first = constructor.getSumType().getFirstChild();
            return first != null ? first.getText().trim() : null;
        }
        return null;
    }

    private static boolean isSupportedPrimitiveOrCollectionRoot(@NotNull String rootTypeText) {
        return StvnVocabulary.isBaseScalarType(rootTypeText)
                || StvnVocabulary.TYPE_MAP.equals(rootTypeText)
                || StvnVocabulary.TYPE_SEQ.equals(rootTypeText)
                || StvnVocabulary.TYPE_SET.equals(rootTypeText);
    }

    private static boolean isLocalMetadataMapValidAfterReplacement(
            @NotNull PsiFile file,
            @NotNull MetadataMap metaMap,
            @NotNull TypeKeyword targetTypeKeyword,
            @NotNull String replacementConstructorText
    ) {
        String sourceText = file.getText();
        TextRange kwRange = targetTypeKeyword.getTextRange();
        TextRange metaRange = metaMap.getTextRange();
        if (kwRange == null || metaRange == null || kwRange.getEndOffset() > sourceText.length()) {
            return false;
        }

        String candidateText = sourceText.substring(0, kwRange.getStartOffset())
                + replacementConstructorText
                + sourceText.substring(kwRange.getEndOffset());

        int delta = replacementConstructorText.length() - kwRange.getLength();
        int shiftedMetaStart = metaRange.getStartOffset() <= kwRange.getStartOffset()
                ? metaRange.getStartOffset()
                : metaRange.getStartOffset() + delta;
        int shiftedMetaEnd = metaRange.getEndOffset() <= kwRange.getStartOffset()
                ? metaRange.getEndOffset()
                : metaRange.getEndOffset() + delta;

        var virtualFile = file.getOriginalFile().getVirtualFile();
        String resolvedPath = virtualFile != null
                ? StvnCompiledDocumentCache.resolvePhysicalPath(virtualFile, file.getProject(), candidateText)
                : file.getName();

        var result = StvnCompiler.compileToResult(candidateText, resolvedPath, StvnParserConfig.DEFAULT);
        for (StvnDiagnostic d : result.diagnostics()) {
            if (d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR) {
                if (d.startOffset() < shiftedMetaEnd && d.endOffset() > shiftedMetaStart) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @NotNull String getText() {
        return "Change base type from '" + nominalAlias + "' to primitive '" + rootTypeText + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Change base type to primitive";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var metaMap = PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
        var schemaType = metaMap != null ? resolveGoverningSchemaType(metaMap) : null;
        TypeKeyword targetKw = schemaType != null ? schemaType.getTypeKeyword() : null;
        if (targetKw == null || !targetKw.isValid()) {
            return;
        }

        var psiDocMgr = PsiDocumentManager.getInstance(project);
        var doc = psiDocMgr.getDocument(file);
        if (doc != null) {
            psiDocMgr.doPostponedOperationsAndUnblockDocument(doc);
            TextRange range = targetKw.getTextRange();
            if (range != null) {
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacementConstructorText);
                psiDocMgr.commitDocument(doc);
                return;
            }
        }

        var dummySchema = StvnElementFactory.createSchemaType(project, replacementConstructorText);
        var newConstructor = dummySchema.getSchemaConstructor();
        if (newConstructor != null) {
            targetKw.replace(newConstructor);
        }
    }
}
