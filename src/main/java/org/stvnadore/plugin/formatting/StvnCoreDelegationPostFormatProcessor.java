package org.stvnadore.plugin.formatting;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.actions.StvnCanonicalizationGuard;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.settings.StvnSettings;

/**
 * Post-format processor optionally delegating whole-file {@code Reformat Code} ({@code Ctrl+Alt+L})
 * on valid STVN documents to {@link AstPrettyPrinter} according to {@link StvnSettings.ReformatDelegationMode}.
 * <p>
 * When comments, authoring sugar ({@code :package} / {@code :use}), partial text selections, or compilation
 * errors are present in {@link StvnSettings.ReformatDelegationMode#CORE_PRETTY_WHEN_LOSSLESS} mode,
 * this processor preserves the structure-preserving PSI formatter output without data loss.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnCoreDelegationPostFormatProcessor implements PostFormatProcessor {

    /**
     * Constructs a new {@code StvnCoreDelegationPostFormatProcessor}.
     */
    public StvnCoreDelegationPostFormatProcessor() {
    }

    @Override
    public @NotNull PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
        if (source instanceof StvnFile stvnFile) {
            TextRange fullRange = stvnFile.getTextRange();
            if (fullRange != null) {
                processText(stvnFile, fullRange, settings);
            }
        }
        return source;
    }

    @Override
    public @NotNull TextRange processText(
            @NotNull PsiFile source,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings
    ) {
        if (!(source instanceof StvnFile) || !source.isValid()) {
            return rangeToReformat;
        }

        TextRange fileRange = source.getTextRange();
        if (fileRange == null || !rangeToReformat.contains(fileRange)) {
            return rangeToReformat;
        }

        Project project = source.getProject();
        if (project.isDisposed()) {
            return rangeToReformat;
        }

        StvnSettings.ReformatDelegationMode mode = StvnSettings.getInstance(project).getState().reformatDelegationMode;
        if (mode == StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING) {
            return rangeToReformat;
        }

        if (mode == StvnSettings.ReformatDelegationMode.CORE_PRETTY_WHEN_LOSSLESS
                && StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(source)) {
            return rangeToReformat;
        }

        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        Document document = docManager.getDocument(source);
        if (document == null) {
            return rangeToReformat;
        }
        docManager.commitDocument(document);

        String currentText = document.getText();
        boolean hadTrailingNewline = currentText.endsWith("\n");

        VirtualFile vf = source.getVirtualFile();
        String fileName = vf != null ? vf.getName() : source.getName();
        String docPath = vf != null
            ? StvnCompiledDocumentCache.resolvePhysicalPath(vf, project, currentText)
            : fileName;
        StvnParserConfig config = StvnCompiledDocumentCache.isSystemSubstrateFileName(fileName)
            ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
            : StvnParserConfig.DEFAULT;

        StvnCompilationResult<StvnDocument> result;
        try {
            result = StvnCompiler.compileToResult(currentText, docPath, config);
        } catch (RuntimeException ex) {
            return rangeToReformat;
        }

        if (result.hasErrors() || result.document().isEmpty()) {
            return rangeToReformat;
        }

        String pretty = AstPrettyPrinter.print(result.document().get());
        String replacement = hadTrailingNewline ? pretty + "\n" : pretty;
        if (replacement.equals(currentText)) {
            return TextRange.from(0, currentText.length());
        }

        document.replaceString(0, document.getTextLength(), replacement);
        docManager.commitDocument(document);
        return TextRange.from(0, document.getTextLength());
    }
}
