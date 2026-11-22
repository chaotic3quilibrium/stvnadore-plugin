package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.TestOnly;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.actions.StvnWorkspaceNotificationHelper;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaNameFacet;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared executor and live {@code :meta} identity resolver for STVN file-renaming quick-fixes.
 * <p>
 * Enforces atomic {@link WriteCommandAction} execution, read-only Intention Preview safety,
 * pre-flight sibling filename collision detection before any buffer mutation, and explicit
 * Simplified Technical English (STE) error feedback via editor hint and workspace notification balloon.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnFileRenameExecutor {

    private static final String RESERVED_DOMAIN_PREFIX = "stvn_";
    private static final Pattern POSIX_META_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static volatile @Nullable String lastReportedRenameError;

    private StvnFileRenameExecutor() {
    }

    /**
     * Resolved snapshot of root {@code :meta} document identity components compared against the physical filename.
     *
     * @param targetFileName           canonical target filename synthesized from {@code :meta} ({@code <name>[.<domain>].<ext>})
     * @param kindOnlyTargetFileName   target filename preserving current stem and updating only the extension to match {@code #kind}
     * @param targetExtension          canonical file extension corresponding to {@code #kind}
     * @param nameMismatch             {@code true} when valid declared {@code #name} differs from filename stem slot 0
     * @param domainMismatch           {@code true} when declared {@code #domain} differs from filename stem slot 1
     * @param kindMismatch             {@code true} when declared {@code #kind} differs from the current file extension
     * @param mismatchedComponentCount total count of mismatching {@code :meta} identity components ({@code 0..3})
     */
    public record MetaIdentitySummary(
        String targetFileName,
        @Nullable String kindOnlyTargetFileName,
        @Nullable String targetExtension,
        boolean nameMismatch,
        boolean domainMismatch,
        boolean kindMismatch,
        int mismatchedComponentCount
    ) {
    }

    /**
     * Maps a declared {@code #kind} keyword to its canonical textual STVN file extension.
     *
     * @param kindKeyword declared {@code #kind} token (for example, {@code #DEFS})
     * @return canonical file extension (for example, {@code ".stvn_d"}), or {@code null} if unrecognized
     */
    public static @Nullable String mapKindKeywordToExtension(String kindKeyword) {
        if (StvnVocabulary.KIND_KW_BODY.equals(kindKeyword)) {
            return ".stvn";
        }
        if (StvnVocabulary.KIND_KW_BODY_INCLUDE.equals(kindKeyword)) {
            return ".stvn_i";
        }
        if (StvnVocabulary.KIND_KW_DEFS.equals(kindKeyword)) {
            return ".stvn_d";
        }
        if (StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindKeyword)) {
            return ".stvn_di";
        }
        return null;
    }

    /**
     * Evaluates the primary {@code :meta} block of {@code file} against its current physical filename.
     * Returns {@code null} if any declared {@code #name} or {@code #domain} is syntactically invalid or
     * uses a prohibited {@code stvn_*} system domain on a user file.
     *
     * @param file active STVN PSI file
     * @return resolved {@link MetaIdentitySummary}, or {@code null} when atomic {@code :meta} file rename is inapplicable
     */
    public static @Nullable MetaIdentitySummary resolveMetaIdentitySummary(@Nullable PsiFile file) {
        if (file == null || !file.isValid()) {
            return null;
        }
        String fileName = file.getName();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String currentExt = fileName.substring(lastDot);
        String stemPart = fileName.substring(0, lastDot);
        String[] dotParts = stemPart.split("\\.", -1);
        if (dotParts.length == 0 || dotParts[0].isEmpty() || dotParts.length > 2) {
            return null;
        }

        MetaEntry primaryMeta = null;
        for (MetaEntry candidate : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isPrimaryMetaEntry(candidate)) {
                primaryMeta = candidate;
                break;
            }
        }
        if (primaryMeta == null || primaryMeta.getMetaBlock() == null) {
            return null;
        }
        MetaBlock metaBlock = primaryMeta.getMetaBlock();
        boolean isSystemFile = isSystemPreludeOrSubstrateFile(file);
        if (!isSystemFile && dotParts.length >= 2 && dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX)) {
            return null;
        }

        @Nullable String declaredName = null;
        @Nullable String declaredDomain = null;
        @Nullable String declaredKind = null;
        boolean sawDomainFacet = false;

        for (MetaElement el : metaBlock.getMetaElementList()) {
            MetaNameFacet nf = el.getMetaNameFacet();
            if (nf != null && declaredName == null) {
                if (nf.getStringLiteral() == null) {
                    return null;
                }
                var dec = StvnPsiLiteralEvaluator.decodeStringLiteral(nf.getStringLiteral().getText(), true);
                if (dec.isEmpty() || !POSIX_META_ID_PATTERN.matcher(dec.get()).matches()) {
                    return null;
                }
                declaredName = dec.get();
            }
            MetaDomainFacet df = el.getMetaDomainFacet();
            if (df != null && !sawDomainFacet) {
                sawDomainFacet = true;
                if (df.getStringLiteral() == null) {
                    return null;
                }
                var dec = StvnPsiLiteralEvaluator.decodeStringLiteral(df.getStringLiteral().getText(), true);
                if (dec.isEmpty() || !POSIX_META_ID_PATTERN.matcher(dec.get()).matches()) {
                    return null;
                }
                if (!isSystemFile && dec.get().startsWith(RESERVED_DOMAIN_PREFIX)) {
                    return null;
                }
                declaredDomain = dec.get();
            }
            MetaKindFacet kf = el.getMetaKindFacet();
            if (kf != null && declaredKind == null && kf.getMetaKindValue() != null) {
                declaredKind = kf.getMetaKindValue().getText().trim();
            }
        }

        String effectiveName = declaredName != null ? declaredName : dotParts[0];
        boolean nameMismatch = declaredName != null && !declaredName.equals(dotParts[0]);

        String currentDomain = dotParts.length == 2 ? dotParts[1] : null;
        boolean domainMismatch;
        @Nullable String effectiveDomain;
        if (sawDomainFacet) {
            effectiveDomain = declaredDomain;
            domainMismatch = !Objects.equals(declaredDomain, currentDomain);
        } else {
            effectiveDomain = null;
            domainMismatch = currentDomain != null && !currentDomain.isEmpty();
        }

        @Nullable String mappedExt = declaredKind != null ? mapKindKeywordToExtension(declaredKind) : null;
        String expectedCurrentKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
        boolean kindMismatch = declaredKind != null
            && mappedExt != null
            && !declaredKind.equals(expectedCurrentKind);
        String effectiveExt = kindMismatch ? mappedExt : currentExt;

        String targetStem = effectiveDomain != null && !effectiveDomain.isEmpty()
            ? effectiveName + "." + effectiveDomain
            : effectiveName;
        String fullTargetFileName = targetStem + effectiveExt;
        @Nullable String kindOnlyTarget = mappedExt != null ? (stemPart + mappedExt) : null;

        int mismatches = (nameMismatch ? 1 : 0) + (domainMismatch ? 1 : 0) + (kindMismatch ? 1 : 0);
        return new MetaIdentitySummary(
            fullTargetFileName,
            kindOnlyTarget,
            mappedExt,
            nameMismatch,
            domainMismatch,
            kindMismatch,
            mismatches
        );
    }

    /**
     * Prevents cross-facet intention bleed on single-line {@code :meta} blocks while preserving
     * mouse-hover tooltip quick-fixes and {@code getAllQuickFixes()} when the caret rests on another line.
     * <p>
     * Enforces {@code ownMetaElement.getTextRange().containsOffset(caretOffset)} strictly when the editor
     * caret is on the same document line as {@code facetElement} and at least one sibling {@link MetaElement}
     * in the enclosing {@link MetaBlock} also resides on that same line.
     *
     * @param editor       active editor, or {@code null} in batch mode
     * @param facetElement owning facet or literal PSI element
     * @return {@code true} if the action should be available at the current caret/hover context
     */
    public static boolean isCaretWithinFacet(@Nullable Editor editor, @Nullable PsiElement facetElement) {
        if (editor == null || facetElement == null || !facetElement.isValid()) {
            return true;
        }
        MetaElement ownMetaElement = facetElement instanceof MetaElement me
            ? me
            : PsiTreeUtil.getParentOfType(facetElement, MetaElement.class, false);
        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facetElement, MetaBlock.class, false);
        if (ownMetaElement == null || metaBlock == null) {
            return true;
        }
        TextRange ownRange = ownMetaElement.getTextRange();
        if (ownRange == null) {
            return true;
        }
        Document doc = editor.getDocument();
        int docLength = doc.getTextLength();
        if (docLength == 0) {
            return true;
        }
        int caretOffset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), docLength));
        int startOffset = Math.max(0, Math.min(ownRange.getStartOffset(), docLength));
        int endOffset = Math.max(startOffset, Math.min(ownRange.getEndOffset(), docLength));
        int caretLine = doc.getLineNumber(caretOffset);
        int facetStartLine = doc.getLineNumber(startOffset);
        int facetEndLine = doc.getLineNumber(endOffset);
        if (caretLine < facetStartLine || caretLine > facetEndLine) {
            return true;
        }
        boolean hasSameLineSiblingMetaElement = false;
        for (MetaElement sibling : metaBlock.getMetaElementList()) {
            if (sibling == ownMetaElement) {
                continue;
            }
            TextRange sibRange = sibling.getTextRange();
            if (sibRange != null) {
                int sibStart = Math.max(0, Math.min(sibRange.getStartOffset(), docLength));
                int sibEnd = Math.max(sibStart, Math.min(sibRange.getEndOffset(), docLength));
                int sibStartLine = doc.getLineNumber(sibStart);
                int sibEndLine = doc.getLineNumber(sibEnd);
                if (sibStartLine <= caretLine && caretLine <= sibEndLine) {
                    hasSameLineSiblingMetaElement = true;
                    break;
                }
            }
        }
        if (!hasSameLineSiblingMetaElement) {
            return true;
        }
        return ownRange.containsOffset(caretOffset);
    }

    /**
     * Builds a read-only Intention Preview card for file-renaming quick-fixes without mutating VFS.
     *
     * @param file           source or preview PSI file
     * @param targetFileName target filename to preview
     * @return {@link IntentionPreviewInfo} rename card, or {@link IntentionPreviewInfo#EMPTY} when non-physical
     */
    public static IntentionPreviewInfo buildRenamePreview(@Nullable PsiFile file, String targetFileName) {
        if (file == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        VirtualFile vFile = file.getOriginalFile().getVirtualFile();
        if (vFile == null) {
            vFile = file.getVirtualFile();
        }
        return vFile != null ? IntentionPreviewInfo.rename(vFile, targetFileName) : IntentionPreviewInfo.EMPTY;
    }

    /**
     * Executes a physical VFS file rename inside a single undoable {@link WriteCommandAction},
     * checking for sibling filename collisions before mutating any buffer text and surfacing
     * explicit STE error feedback on collision or VFS I/O failure.
     *
     * @param project                 active IntelliJ project
     * @param editor                  active editor for hint display, or {@code null}
     * @param file                    source STVN PSI file
     * @param targetFileName          live target filename
     * @param commandTitle            Undo command title
     * @param preRenameBufferMutation optional PSI/Document mutation executed atomically only when no collision exists
     * @return {@code true} if the rename succeeded; {@code false} if aborted due to collision, read-only status, or I/O error
     */
    public static boolean executeRenameWithCollisionFeedback(
            Project project,
            @Nullable Editor editor,
            @Nullable PsiFile file,
            String targetFileName,
            String commandTitle,
            @Nullable Runnable preRenameBufferMutation
    ) {
        if (file == null || !file.isValid() || !file.isPhysical()) {
            return false;
        }
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null || !vFile.isValid()) {
            return false;
        }
        String sourceFileName = vFile.getName();
        if (sourceFileName.equals(targetFileName)) {
            return false;
        }

        VirtualFile parent = vFile.getParent();
        if (parent != null) {
            VirtualFile existing = parent.findChild(targetFileName);
            if (existing != null && !existing.equals(vFile)) {
                String msg = "Cannot rename '" + sourceFileName + "' to '" + targetFileName
                    + "': a file with that name already exists in '" + parent.getName() + "'.";
                reportRenameError(project, editor, msg);
                return false;
            }
        }

        var readonlyStatus = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(List.of(vFile));
        if (readonlyStatus.hasReadonlyFiles()) {
            String msg = "Cannot rename '" + sourceFileName + "' to '" + targetFileName
                + "': file is read-only.";
            reportRenameError(project, editor, msg);
            return false;
        }

        final boolean[] succeeded = {false};
        final String[] failureMessage = {null};
        WriteCommandAction.runWriteCommandAction(project, commandTitle, null, () -> {
            try {
                if (preRenameBufferMutation != null) {
                    preRenameBufferMutation.run();
                }
                var doc = PsiDocumentManager.getInstance(project).getDocument(file);
                if (doc != null) {
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                }
                vFile.rename(StvnFileRenameExecutor.class, targetFileName);
                succeeded[0] = true;
            } catch (IOException | IncorrectOperationException ex) {
                String detail = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
                failureMessage[0] = "Cannot rename '" + sourceFileName + "' to '" + targetFileName + "': " + detail;
            }
        });

        if (!succeeded[0] && failureMessage[0] != null) {
            reportRenameError(project, editor, failureMessage[0]);
        }
        return succeeded[0];
    }

    private static void reportRenameError(Project project, @Nullable Editor editor, String message) {
        lastReportedRenameError = message;
        StvnWorkspaceNotificationHelper.showFileRenameWarning(project, message);
        var app = ApplicationManager.getApplication();
        if (editor != null && app != null && !app.isHeadlessEnvironment() && !app.isUnitTestMode()) {
            CommonRefactoringUtil.showErrorHint(project, editor, message, "STVN File Rename Failed", null);
        }
    }

    /**
     * Returns and clears the most recent rename error message recorded during testing.
     *
     * @return the last rename error message, or {@code null} if none occurred
     */
    @TestOnly
    public static @Nullable String getAndClearLastReportedRenameError() {
        String err = lastReportedRenameError;
        lastReportedRenameError = null;
        return err;
    }

    private static boolean isSystemPreludeOrSubstrateFile(PsiFile file) {
        String name = file.getName();
        if (name.contains(".stvn_core.") || name.contains(".stvn_ir.") || name.contains(".stvn_cas.")) {
            return true;
        }
        VirtualFile vf = file.getVirtualFile();
        if (vf != null) {
            String path = vf.getPath().replace('\\', '/');
            return path.contains("/prelude/") || path.contains("/org/stvnadore/prelude/");
        }
        return false;
    }
}
