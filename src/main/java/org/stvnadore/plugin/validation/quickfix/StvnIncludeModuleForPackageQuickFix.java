package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.StvnFlatDocumentIncludeInspection;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.UseTarget;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Top-priority modular quick-fix ({@link Priority#TOP}) that appends or inserts a workspace
 * {@code .stvn_d} or {@code .stvn_di} module path into the primary {@code :include [...]} block
 * when an unresolved {@link UseTarget} matches a {@code :package} declared in that module.
 */
@NullMarked
public final class StvnIncludeModuleForPackageQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    @SafeFieldForPreview
    private final String relPath;

    @SafeFieldForPreview
    private final String targetPackage;

    @SafeFieldForPreview
    private final String targetNormalizedVfPath;

    /**
     * Constructs an auto-{@code :include} quick-fix for an unresolved {@code :use} package target.
     *
     * @param useTarget     unresolved {@link UseTarget} PSI element
     * @param relPath       relative POSIX path from the host file to the declaring module
     * @param targetPackage unresolved package path (for example, {@code ":rov/telemetry"})
     */
    public StvnIncludeModuleForPackageQuickFix(
            UseTarget useTarget,
            String relPath,
            String targetPackage
    ) {
        this(useTarget, relPath, targetPackage, "");
    }

    /**
     * Constructs an auto-{@code :include} quick-fix with explicit normalized {@link VirtualFile} identity.
     *
     * @param useTarget              unresolved {@link UseTarget} PSI element
     * @param relPath                relative POSIX path from the host file to the declaring module
     * @param targetPackage          unresolved package path (for example, {@code ":rov/telemetry"})
     * @param targetNormalizedVfPath normalized virtual file path of the target module, or empty string
     */
    public StvnIncludeModuleForPackageQuickFix(
            UseTarget useTarget,
            String relPath,
            String targetPackage,
            String targetNormalizedVfPath
    ) {
        super(useTarget);
        this.relPath = relPath;
        this.targetPackage = targetPackage;
        this.targetNormalizedVfPath = targetNormalizedVfPath;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @NotNull String getText() {
        return "Include '" + relPath + "' for package '" + targetPackage + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Include workspace module for unresolved package";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid()) {
            return false;
        }
        PsiFile originFile = file.getOriginalFile();
        if (StvnFlatDocumentIncludeInspection.isHermeticCompilationUnit(file)
                || (!StvnPsiUtils.isModularDocument(file) && !StvnPsiUtils.isModularDocument(originFile))) {
            return false;
        }
        return !hasDirectIncludePath(file, relPath, targetNormalizedVfPath);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        insertOrAppendIncludePath(project, file, relPath, targetNormalizedVfPath);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement elem = previewDescriptor.getPsiElement();
        PsiFile previewFile = elem != null ? elem.getContainingFile() : null;
        if (previewFile == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        insertOrAppendIncludePath(project, previewFile, relPath, targetNormalizedVfPath);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        insertOrAppendIncludePath(project, file, relPath, targetNormalizedVfPath);
    }

    /**
     * Strips any leading {@code "./"} segments from a normalized POSIX path.
     *
     * @param path raw or normalized path string
     * @return path without leading {@code "./"} prefixes
     */
    public static String stripLeadingDotSlash(String path) {
        String norm = StvnSchemaFlattener.normalizePath(path.trim());
        while (norm.startsWith("./")) {
            norm = norm.substring(2);
        }
        return norm;
    }

    /**
     * Formats {@code rawRelPath} to preserve a leading {@code "./"} prefix when existing same-directory
     * {@code :include} entries in {@code hostFile} use {@code "./<file>"} notation, while keeping
     * canonical POSIX relative paths for cross-directory references.
     *
     * @param hostFile   host STVN PSI file
     * @param rawRelPath raw relative POSIX path
     * @return formatted relative path matching host file style
     */
    public static String formatIncludeRelativePath(PsiFile hostFile, String rawRelPath) {
        String stripped = stripLeadingDotSlash(rawRelPath);
        if (stripped.isEmpty() || stripped.contains("/") || stripped.startsWith(".")) {
            return rawRelPath.startsWith("./") && stripped.contains("/") ? stripped : rawRelPath;
        }
        boolean existingUsesDotSlashForSibling = false;
        for (IncludeStmt inc : PsiTreeUtil.findChildrenOfType(hostFile, IncludeStmt.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(inc)) {
                continue;
            }
            for (IncludeElement elem : inc.getIncludeElementList()) {
                StringLiteral strLit = elem.getStringLiteral();
                if (strLit == null) {
                    continue;
                }
                String decoded = StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), true).orElse("");
                if (decoded.startsWith("./") && !decoded.substring(2).contains("/")) {
                    existingUsesDotSlashForSibling = true;
                    break;
                }
            }
            if (existingUsesDotSlashForSibling) {
                break;
            }
        }
        return existingUsesDotSlashForSibling ? "./" + stripped : stripped;
    }

    /**
     * Collects the normalized {@link VirtualFile} paths of all modules directly referenced inside
     * {@code file}'s primary {@code :include [...]} statements via {@link StvnTypeReference#resolveIncludeFile}.
     *
     * @param file host STVN file
     * @return set of normalized virtual file paths already directly included
     */
    public static Set<String> collectDirectIncludedNormalizedVfPaths(PsiFile file) {
        Set<String> vfPaths = new HashSet<>();
        for (IncludeStmt inc : PsiTreeUtil.findChildrenOfType(file, IncludeStmt.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(inc)) {
                continue;
            }
            for (IncludeElement elem : inc.getIncludeElementList()) {
                StringLiteral strLit = elem.getStringLiteral();
                if (strLit == null) {
                    continue;
                }
                PsiFile resolved = StvnTypeReference.resolveIncludeFile(strLit);
                if (resolved != null) {
                    VirtualFile vf = resolved.getOriginalFile().getVirtualFile();
                    if (vf == null) {
                        vf = resolved.getVirtualFile();
                    }
                    if (vf != null) {
                        vfPaths.add(StvnSchemaFlattener.normalizePath(vf.getPath()));
                    }
                }
            }
        }
        return vfPaths;
    }

    /**
     * Checks whether {@code file}'s primary {@code :defs} already declares {@code candidateRelPath}
     * inside an {@link IncludeStmt}, comparing both resolved {@link VirtualFile} identities and
     * {@code "./"}-normalized relative paths.
     *
     * @param file             host STVN file
     * @param candidateRelPath relative path to test
     * @return {@code true} when already present
     */
    public static boolean hasDirectIncludePath(PsiFile file, String candidateRelPath) {
        return hasDirectIncludePath(file, candidateRelPath, "");
    }

    /**
     * Checks whether {@code file}'s primary {@code :defs} already declares {@code candidateRelPath}
     * or resolves to {@code candidateNormalizedVfPath}.
     *
     * @param file                      host STVN file
     * @param candidateRelPath          relative path to test
     * @param candidateNormalizedVfPath normalized virtual file path of the candidate module, or empty string
     * @return {@code true} when already included
     */
    public static boolean hasDirectIncludePath(
            PsiFile file,
            String candidateRelPath,
            String candidateNormalizedVfPath
    ) {
        String normalizedCandidateRel = stripLeadingDotSlash(candidateRelPath);
        String resolvedCandidateVfPath = candidateNormalizedVfPath;
        if (resolvedCandidateVfPath.isEmpty()) {
            VirtualFile hostVf = file.getOriginalFile().getVirtualFile();
            if (hostVf == null) {
                hostVf = file.getVirtualFile();
            }
            if (hostVf != null && hostVf.getParent() != null) {
                VirtualFile targetVf = hostVf.getParent().findFileByRelativePath(normalizedCandidateRel);
                if (targetVf != null) {
                    resolvedCandidateVfPath = StvnSchemaFlattener.normalizePath(targetVf.getPath());
                }
            }
        }

        for (IncludeStmt inc : PsiTreeUtil.findChildrenOfType(file, IncludeStmt.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(inc)) {
                continue;
            }
            for (IncludeElement elem : inc.getIncludeElementList()) {
                StringLiteral strLit = elem.getStringLiteral();
                if (strLit == null) {
                    continue;
                }
                String decoded = StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), true).orElse("");
                if (!decoded.isEmpty() && stripLeadingDotSlash(decoded).equals(normalizedCandidateRel)) {
                    return true;
                }
                if (!resolvedCandidateVfPath.isEmpty()) {
                    PsiFile resolvedInclude = StvnTypeReference.resolveIncludeFile(strLit);
                    if (resolvedInclude != null) {
                        VirtualFile incVf = resolvedInclude.getOriginalFile().getVirtualFile();
                        if (incVf == null) {
                            incVf = resolvedInclude.getVirtualFile();
                        }
                        if (incVf != null
                                && resolvedCandidateVfPath.equals(StvnSchemaFlattener.normalizePath(incVf.getPath()))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Appends {@code "\"" + formattedRelPath + "\""} into the first primary {@link IncludeStmt} in {@code file},
     * or inserts a canonical {@code :include [ "<formattedRelPath>" ]} directive at the top of primary {@code :defs}
     * when none exists, preserving surrounding comments, sibling indentation, and {@code "./"} path conventions.
     *
     * @param project active project
     * @param file    target STVN PSI file (physical or preview copy)
     * @param relPath relative module path to include
     */
    public static void insertOrAppendIncludePath(Project project, PsiFile file, String relPath) {
        insertOrAppendIncludePath(project, file, relPath, "");
    }

    /**
     * Appends or inserts a module include path into {@code file} if not already directly included.
     *
     * @param project                active project
     * @param file                   target STVN PSI file (physical or preview copy)
     * @param relPath                relative module path to include
     * @param targetNormalizedVfPath normalized virtual file path of the target module, or empty string
     */
    public static void insertOrAppendIncludePath(
            Project project,
            PsiFile file,
            String relPath,
            String targetNormalizedVfPath
    ) {
        if (hasDirectIncludePath(file, relPath, targetNormalizedVfPath)) {
            return;
        }
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = PsiDocumentManager.getInstance(project).getDocument(file);
        }
        if (doc == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        if (file.isPhysical()) {
            docManager.doPostponedOperationsAndUnblockDocument(doc);
        }
        docManager.commitDocument(doc);

        String effectiveRelPath = relPath.startsWith("./")
            ? relPath
            : formatIncludeRelativePath(file, relPath);

        IncludeStmt primaryInclude = null;
        for (IncludeStmt inc : PsiTreeUtil.findChildrenOfType(file, IncludeStmt.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(inc)) {
                primaryInclude = inc;
                break;
            }
        }

        String quoted = "\"" + effectiveRelPath + "\"";
        CharSequence chars = doc.getCharsSequence();

        if (primaryInclude != null) {
            ASTNode rBrack = primaryInclude.getNode().findChildByType(StvnTypes.RBRACK);
            if (rBrack != null) {
                int rBrackOffset = rBrack.getStartOffset();
                if (primaryInclude.getText().indexOf('\n') >= 0) {
                    List<IncludeElement> existing = primaryInclude.getIncludeElementList();
                    String incIndent = detectLineIndent(chars, primaryInclude.getTextRange().getStartOffset(), "    ");
                    String elemIndent = existing.isEmpty()
                        ? incIndent + "  "
                        : detectLineIndent(chars, existing.get(0).getTextRange().getStartOffset(), incIndent + "  ");
                    int rBrackLineStart = findLineStartSkippingSpaces(chars, rBrackOffset);
                    if (rBrackLineStart == 0 || chars.charAt(rBrackLineStart - 1) == '\n') {
                        doc.insertString(rBrackLineStart, elemIndent + quoted + "\n");
                    } else {
                        doc.insertString(rBrackOffset, "\n" + elemIndent + quoted + "\n" + incIndent);
                    }
                } else {
                    if (rBrackOffset > 0 && chars.charAt(rBrackOffset - 1) == ' ') {
                        doc.insertString(rBrackOffset - 1, " " + quoted);
                    } else {
                        doc.insertString(rBrackOffset, " " + quoted + " ");
                    }
                }
                docManager.commitDocument(doc);
                return;
            }
        }

        DefsEntry defs = StvnPsiUtils.getOrCreateDefsBlock(file);
        if (defs == null) {
            return;
        }
        chars = doc.getCharsSequence();
        ASTNode openBrace = defs.getNode().findChildByType(StvnTypes.LBRACE);
        if (openBrace == null) {
            return;
        }
        String defsIndent = detectLineIndent(chars, defs.getTextRange().getStartOffset(), "  ");
        String childIndent = defsIndent + "  ";
        String directive = StvnVocabulary.KEYWORD_INCLUDE + " "
            + StvnVocabulary.DELIM_OPEN_BRACKET + " " + quoted + " " + StvnVocabulary.DELIM_CLOSE_BRACKET;

        int afterBrace = openBrace.getStartOffset() + 1;
        int scan = afterBrace;
        while (scan < chars.length() && (chars.charAt(scan) == ' ' || chars.charAt(scan) == '\t')) {
            scan++;
        }
        if (scan + 1 < chars.length() && chars.charAt(scan) == '/' && chars.charAt(scan + 1) == '/') {
            while (scan < chars.length() && chars.charAt(scan) != '\n' && chars.charAt(scan) != '\r') {
                scan++;
            }
        }
        if (scan < chars.length() && (chars.charAt(scan) == '\n' || chars.charAt(scan) == '\r')) {
            int nextLine = (chars.charAt(scan) == '\r' && scan + 1 < chars.length() && chars.charAt(scan + 1) == '\n')
                ? scan + 2
                : scan + 1;
            doc.insertString(nextLine, childIndent + directive + "\n");
        } else {
            doc.insertString(afterBrace, "\n" + childIndent + directive);
        }
        docManager.commitDocument(doc);
    }

    private static int findLineStartSkippingSpaces(CharSequence chars, int offset) {
        int pos = offset;
        while (pos > 0 && (chars.charAt(pos - 1) == ' ' || chars.charAt(pos - 1) == '\t')) {
            pos--;
        }
        return pos;
    }

    private static String detectLineIndent(CharSequence chars, int offset, String fallback) {
        int lineStart = findLineStartSkippingSpaces(chars, offset);
        if (lineStart == 0 || chars.charAt(lineStart - 1) == '\n') {
            return chars.subSequence(lineStart, offset).toString();
        }
        return fallback;
    }
}
