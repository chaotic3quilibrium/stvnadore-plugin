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
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.actions.StvnCanonicalizationGuard;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Post-format processor delegating whole-file {@code Reformat Code} ({@code Ctrl+Alt+L})
 * on STVN documents to {@code ij_stvnadore_core}'s Single Formatting Authority
 * ({@link StvnSourceFormatter} for Operation 1 and {@link AstPrettyPrinter} for Operation 2)
 * according to {@link StvnSettings.ReformatDelegationMode}.
 * <p>
 * In {@link StvnSettings.ReformatDelegationMode#CORE_SOURCE_FORMATTER} (default) mode, whole-file
 * formatting delegates to {@link StvnSourceFormatter#tryFormat(String)}, preserving {@code //}
 * comments, {@code :package}, {@code :use}, {@code :include}, {@code #CONST}, unreferenced
 * {@code :defs}, and Pass 2–4 semantic errors while enforcing canonical Core formatting.
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

        String prePsiText = source.getUserData(StvnMetadataOrderPreFormatProcessor.PRE_PSI_FORMAT_TEXT_KEY);
        source.putUserData(StvnMetadataOrderPreFormatProcessor.PRE_PSI_FORMAT_TEXT_KEY, null);

        TextRange fileRange = source.getTextRange();
        if (fileRange == null || !rangeToReformat.contains(fileRange)) {
            return rangeToReformat;
        }

        Project project = source.getProject();
        if (project.isDisposed()) {
            return rangeToReformat;
        }

        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        Document document = docManager.getDocument(source);
        if (document == null) {
            return rangeToReformat;
        }
        docManager.doPostponedOperationsAndUnblockDocument(document);
        docManager.commitDocument(document);

        String currentText = document.getText();
        boolean hadTrailingNewline = currentText.endsWith("\n");

        StvnSettings.ReformatDelegationMode mode = StvnSettings.getInstance(project).getState().reformatDelegationMode;
        if (mode == StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING) {
            if (prePsiText == null) {
                return rangeToReformat;
            }
            String reanchoredPsi = reanchorAlignedTrailingComments(prePsiText, currentText);
            return applyFormattedText(docManager, document, currentText, false,
                    Optional.of(reanchoredPsi), rangeToReformat);
        }

        if (mode == StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER) {
            return applyFormattedText(docManager, document, currentText, hadTrailingNewline,
                    formatWithAlignedTrailingComments(currentText, prePsiText), rangeToReformat);
        }

        if (mode == StvnSettings.ReformatDelegationMode.CORE_PRETTY_WHEN_LOSSLESS
                && StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(source)) {
            return applyFormattedText(docManager, document, currentText, hadTrailingNewline,
                    formatWithAlignedTrailingComments(currentText, prePsiText), rangeToReformat);
        }

        Optional<String> sourceFormattedOpt = formatWithAlignedTrailingComments(currentText, prePsiText);
        String normalizedText = sourceFormattedOpt.orElse(currentText);

        VirtualFile vf = source.getVirtualFile();
        String fileName = vf != null ? vf.getName() : source.getName();
        String docPath = vf != null
            ? StvnCompiledDocumentCache.resolvePhysicalPath(vf, project, normalizedText)
            : fileName;
        StvnParserConfig config = StvnCompiledDocumentCache.isSystemSubstrateFileName(fileName)
            ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
            : StvnParserConfig.DEFAULT;

        StvnCompilationResult<StvnDocument> result;
        try {
            result = StvnCompiler.compileToResult(normalizedText, docPath, config);
        } catch (RuntimeException ex) {
            return applyFormattedText(docManager, document, currentText, hadTrailingNewline,
                    sourceFormattedOpt, rangeToReformat);
        }

        if (result.hasErrors() || result.document().isEmpty()) {
            return applyFormattedText(docManager, document, currentText, hadTrailingNewline,
                    sourceFormattedOpt, rangeToReformat);
        }

        String pretty = AstPrettyPrinter.print(result.document().get());
        return applyFormattedText(docManager, document, currentText, hadTrailingNewline,
                Optional.of(pretty), rangeToReformat);
    }

    private static Optional<String> formatWithAlignedTrailingComments(
            String currentText,
            @Nullable String prePsiText
    ) {
        Optional<String> formattedOpt = StvnSourceFormatter.tryFormat(currentText);
        if (formattedOpt.isEmpty() && prePsiText != null && !prePsiText.equals(currentText)) {
            formattedOpt = StvnSourceFormatter.tryFormat(prePsiText);
        }
        return formattedOpt.map(formatted -> reanchorAlignedTrailingComments(prePsiText, formatted));
    }

    private record AlignedCommentSpec(int authoredSpaces, int targetCol) {
    }

    /**
     * Re-anchors same-line trailing {@code //} comments that had {@code authoredSpaces >= 2}
     * in {@code prePsiText} so their target column is preserved via
     * {@code Math.max(authoredSpaces, targetCol - currentLineCol)} (§ 3.3.1).
     */
    static String reanchorAlignedTrailingComments(@Nullable String prePsiText, String formattedText) {
        if (prePsiText == null || prePsiText.isEmpty() || formattedText.isEmpty()) {
            return formattedText;
        }
        Map<String, Deque<AlignedCommentSpec>> specsByKey = new HashMap<>();
        String[] preLines = prePsiText.split("\n", -1);
        for (String rawLine : preLines) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            int commentIdx = findTrailingCommentIndex(line);
            if (commentIdx <= 0) {
                continue;
            }
            int codeEnd = commentIdx;
            while (codeEnd > 0 && line.charAt(codeEnd - 1) == ' ') {
                codeEnd--;
            }
            if (codeEnd == 0) {
                continue;
            }
            int authoredSpaces = commentIdx - codeEnd;
            if (authoredSpaces < 2) {
                continue;
            }
            String codePrefix = line.substring(0, codeEnd);
            String commentText = line.substring(commentIdx).stripTrailing();
            String key = normalizeCodePrefixKey(codePrefix) + "\u0000" + commentText;
            specsByKey.computeIfAbsent(key, k -> new ArrayDeque<>())
                    .addLast(new AlignedCommentSpec(authoredSpaces, commentIdx));
        }
        if (specsByKey.isEmpty()) {
            return formattedText;
        }
        String[] outLines = formattedText.split("\n", -1);
        boolean modified = false;
        for (int i = 0; i < outLines.length; i++) {
            String rawLine = outLines[i];
            boolean hasCr = rawLine.endsWith("\r");
            String line = hasCr ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            int commentIdx = findTrailingCommentIndex(line);
            if (commentIdx <= 0) {
                continue;
            }
            int codeEnd = commentIdx;
            while (codeEnd > 0 && line.charAt(codeEnd - 1) == ' ') {
                codeEnd--;
            }
            if (codeEnd == 0) {
                continue;
            }
            String codePrefix = line.substring(0, codeEnd);
            String commentText = line.substring(commentIdx).stripTrailing();
            String key = normalizeCodePrefixKey(codePrefix) + "\u0000" + commentText;
            Deque<AlignedCommentSpec> queue = specsByKey.get(key);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            AlignedCommentSpec spec = queue.removeFirst();
            int requiredSpaces = Math.max(spec.authoredSpaces(), spec.targetCol() - codeEnd);
            int existingSpaces = commentIdx - codeEnd;
            if (requiredSpaces != existingSpaces) {
                outLines[i] = codePrefix + " ".repeat(requiredSpaces) + commentText + (hasCr ? "\r" : "");
                modified = true;
            }
        }
        return modified ? String.join("\n", outLines) : formattedText;
    }

    private static int findTrailingCommentIndex(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '/' && line.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeCodePrefixKey(String codePrefix) {
        StringBuilder sb = new StringBuilder(codePrefix.length());
        boolean inString = false;
        boolean lastWasSpace = true;
        for (int i = 0; i < codePrefix.length(); i++) {
            char c = codePrefix.charAt(i);
            if (c == '"' && (i == 0 || codePrefix.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
                lastWasSpace = false;
            } else if (!inString && (c == ' ' || c == '\t')) {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString().trim();
    }

    private static @NotNull TextRange applyFormattedText(
            PsiDocumentManager docManager,
            Document document,
            String currentText,
            boolean hadTrailingNewline,
            Optional<String> formattedOpt,
            TextRange fallbackRange
    ) {
        if (formattedOpt.isEmpty()) {
            return fallbackRange;
        }
        String formatted = formattedOpt.get();
        String replacement = hadTrailingNewline ? formatted + "\n" : formatted;
        if (replacement.equals(currentText)) {
            return TextRange.from(0, currentText.length());
        }
        document.replaceString(0, document.getTextLength(), replacement);
        docManager.commitDocument(document);
        return TextRange.from(0, document.getTextLength());
    }
}
