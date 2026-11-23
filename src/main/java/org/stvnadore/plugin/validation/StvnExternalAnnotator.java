package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.problems.ProblemImpl;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.Problem;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.formatting.StvnRootSectionReorderer;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.OpenIncludedFileQuickFix;
import org.stvnadore.plugin.validation.quickfix.RemoveIllegalStructuralSectionQuickFix;
import org.stvnadore.plugin.validation.quickfix.RenameFileExtensionToKindQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnChangeReservedDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnDeclareConstantQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnFileRenameExecutor;
import org.stvnadore.plugin.validation.quickfix.StvnFloatFacetQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnInteractiveRenameQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnMatchMetaFileRenameQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnOmitDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnRenameDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameReservedPackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnTraitViolationQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedTypeQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedUsePackageQuickFixProvider;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Executes background STVN 2.0.0 compilation and pure-IR semantic validation,
 * routing compiler diagnostics to exact half-open coordinates in the active editor
 * buffer or to originating {@code :include} statements via {@link StvnDiagnostic#sourcePath()}.
 */
@NullMarked
public final class StvnExternalAnnotator
    extends ExternalAnnotator<StvnExternalAnnotator.CollectedInfo, StvnExternalAnnotator.AnnotationResult> {

    private static final Set<String> CANONICAL_STVN_EXTENSIONS = Set.of(
        ".stvn", ".stvn_i", ".stvn_d", ".stvn_di", ".stvn_b", ".stvn_bd"
    );

    private static final Pattern TUPLE_ARITY_PATTERN =
        Pattern.compile("Expected\\s+(\\d+)\\s+elements?,\\s+got\\s+(\\d+)");

    /**
     * Constructs a new {@link StvnExternalAnnotator} instance.
     */
    public StvnExternalAnnotator() {
    }

    /**
     * Document context information collected on the EDT for background compilation.
     *
     * @param text        document text content
     * @param path        physical or virtual file path
     * @param virtualFile underlying virtual file
     * @param project     active project context
     * @param systemMode  {@code true} when system substrate compilation rules apply
     */
    public record CollectedInfo(
        String text,
        String path,
        VirtualFile virtualFile,
        @Nullable Project project,
        boolean systemMode
    ) {
        /**
         * Constructs a {@link CollectedInfo} instance with {@code systemMode} set to {@code false}.
         *
         * @param text        document text content
         * @param path        physical or virtual file path
         * @param virtualFile underlying virtual file
         * @param project     active project context
         */
        public CollectedInfo(String text, String path, VirtualFile virtualFile, @Nullable Project project) {
            this(text, path, virtualFile, project, false);
        }

        /**
         * Constructs a {@link CollectedInfo} instance without an explicit project context.
         *
         * @param text        document text content
         * @param path        physical or virtual file path
         * @param virtualFile underlying virtual file
         */
        public CollectedInfo(String text, String path, VirtualFile virtualFile) {
            this(text, path, virtualFile, null, false);
        }
    }

    /**
     * Immutable background compilation output containing diagnostics and the POSIX-normalized
     * active compilation unit path.
     *
     * @param diagnostics          compiler diagnostics accumulated for the compilation unit
     * @param activeNormalizedPath POSIX-normalized path passed to the compiler for the active unit
     */
    public record AnnotationResult(List<StvnDiagnostic> diagnostics, String activeNormalizedPath) {
        /**
         * Constructs an {@link AnnotationResult} without an explicit normalized unit path.
         *
         * @param diagnostics compiler diagnostics accumulated for the compilation unit
         */
        public AnnotationResult(List<StvnDiagnostic> diagnostics) {
            this(diagnostics, "");
        }
    }

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file) {
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        var app = ApplicationManager.getApplication();
        if (app != null && app.isDispatchThread()) {
            FileDocumentManager.getInstance().saveAllDocuments();
        }
        boolean isSystemPrelude = isGenuineSyntheticPrelude(file, virtualFile, file.getProject());
        return new CollectedInfo(file.getText(), virtualFile.getPath(), virtualFile, file.getProject(), isSystemPrelude);
    }

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file, Editor editor, boolean hasErrors) {
        return collectInformation(file);
    }

    @Override
    public @Nullable AnnotationResult doAnnotate(CollectedInfo info) {
        synchronized (info.path().intern()) {
            String fileName = info.virtualFile() != null
                ? info.virtualFile().getName()
                : extractFileName(info.path());

            var layer1Diag = validateLayer1Identity(fileName, info.systemMode());
            if (info.text().isBlank()) {
                return layer1Diag != null
                    ? new AnnotationResult(List.of(layer1Diag), "")
                    : new AnnotationResult(List.of(), "");
            }

            var resolvedPath = StvnCompiledDocumentCache.resolvePhysicalPath(
                info.virtualFile(),
                info.project(),
                info.text()
            );
            var activeNormalizedPath = StvnSchemaFlattener.normalizePath(resolvedPath);
            try {
                var config = info.systemMode()
                    ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
                    : StvnParserConfig.DEFAULT;
                var compilationResult = StvnCompiler.compileToResult(info.text(), resolvedPath, config);
                var diags = new ArrayList<>(compilationResult.diagnostics());
                if (layer1Diag != null) {
                    boolean alreadyEmitted = diags.stream().anyMatch(d ->
                        (d.symbolicId().isPresent() && d.symbolicId().equals(layer1Diag.symbolicId()))
                            || (d.errorCode().isPresent() && d.errorCode().equals(layer1Diag.errorCode())));
                    if (!alreadyEmitted) {
                        diags.add(0, layer1Diag.withSourcePath(activeNormalizedPath));
                    }
                }
                return new AnnotationResult(diags, activeNormalizedPath);
            } catch (Exception e) {
                return new AnnotationResult(
                    List.of(new StvnDiagnostic(
                        "Internal Compilation Exception: " + e.getMessage(),
                        DiagnosticSeverity.ERROR,
                        -1,
                        -1,
                        0,
                        info.text().length(),
                        e
                    ).withSourcePath(activeNormalizedPath)),
                    activeNormalizedPath
                );
            }
        }
    }

    private static String extractFileName(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static @Nullable StvnDiagnostic validateLayer1Identity(String fileName, boolean systemMode) {
        if (fileName.startsWith(".")) {
            return new StvnDiagnostic(
                "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
                DiagnosticSeverity.ERROR,
                1,
                0,
                0,
                0,
                null,
                Optional.of(DiagnosticBag.ERR_INVALID_FILENAME_STEM)
            );
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || fileName.substring(0, lastDot).isEmpty()) {
            return new StvnDiagnostic(
                "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
                DiagnosticSeverity.ERROR,
                1,
                0,
                0,
                0,
                null,
                Optional.of(DiagnosticBag.ERR_INVALID_FILENAME_STEM)
            );
        }
        String ext = fileName.substring(lastDot);
        if (!CANONICAL_STVN_EXTENSIONS.contains(ext)) {
            return new StvnDiagnostic(
                "Unrecognized STVN file extension '" + ext + "'; permitted extensions are [.stvn, .stvn_i, .stvn_d, .stvn_di, .stvn_b, .stvn_bd]",
                DiagnosticSeverity.ERROR,
                1,
                0,
                0,
                0,
                null,
                Optional.of(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED)
            );
        }
        if (!systemMode) {
            String stem = fileName.substring(0, lastDot);
            String[] dotParts = stem.split("\\.", -1);
            if (dotParts.length >= 3) {
                return new StvnDiagnostic(
                    "Invalid filename stem '" + fileName + "'; filename must contain exactly one or two dot-separated stem segments (<name>.<ext> or <name>.<domain>.<ext>)",
                    DiagnosticSeverity.ERROR,
                    1,
                    0,
                    0,
                    0,
                    null,
                    Optional.of(DiagnosticBag.ERR_INVALID_FILENAME_STEM)
                );
            }
            if (dotParts.length > 1 && !dotParts[1].isEmpty()) {
                String slot1 = dotParts[1];
                if (slot1.startsWith("stvn_")) {
                    return new StvnDiagnostic(
                        "Reserved system domain prohibited: '" + slot1 + "' is reserved for internal compiler substrates and CAS envelopes",
                        DiagnosticSeverity.ERROR,
                        1,
                        0,
                        0,
                        0,
                        null,
                        Optional.of(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED)
                    );
                }
            }
        }
        return null;
    }

    private static boolean isGenuineSyntheticPrelude(PsiFile file, VirtualFile virtualFile, @Nullable Project project) {
        if (project != null && !project.isDisposed()) {
            try {
                if (ProjectFileIndex.getInstance(project).isInContent(virtualFile)) {
                    return false;
                }
            } catch (Exception ignored) {
            }
        }
        if (file.isPhysical() || virtualFile.isWritable()) {
            return false;
        }
        if (project != null && file == StvnPreludeBridge.getPreludeFile(project)) {
            return true;
        }
        return StvnPreludeBridge.isPreludeFile(file);
    }

    @Override
    public void apply(PsiFile file, AnnotationResult result, AnnotationHolder holder) {
        var virtualFile = file.getVirtualFile();
        var project = file.getProject();
        var activeNormalizedPath = result.activeNormalizedPath();

        var rawDiagnostics = result.diagnostics();
        var activeDiagnostics = rawDiagnostics.stream()
            .filter(d -> !isDiagnosticSuppressed(file, d, rawDiagnostics, activeNormalizedPath))
            .toList();

        var activeErrorDiagnostics = activeDiagnostics.stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
            .toList();

        var textLength = file.getTextLength();
        if (textLength == 0 && activeErrorDiagnostics.isEmpty()) {
            if (virtualFile != null && virtualFile.isValid() && !project.isDisposed()) {
                WolfTheProblemSolver.getInstance(project).clearProblems(virtualFile);
            }
            return;
        }

        var problems = new ArrayList<Problem>();
        WolfTheProblemSolver wolf = null;
        if (virtualFile != null && virtualFile.isValid() && !project.isDisposed()) {
            wolf = WolfTheProblemSolver.getInstance(project);
        }

        renderPinnedIncludeDiagnostics(file, rawDiagnostics, activeNormalizedPath, holder, problems, wolf, virtualFile);

        if (wolf != null && virtualFile != null) {
            var wolfErrors = new ArrayList<>(activeErrorDiagnostics);
            for (var diag : rawDiagnostics) {
                if (diag.severity() == DiagnosticSeverity.ERROR
                        && !isDiagnosticFromIncludedFile(file, diag, activeNormalizedPath)
                        && !wolfErrors.contains(diag)) {
                    wolfErrors.add(diag);
                }
            }
            for (var diag : wolfErrors) {
                var line = Math.max(0, diag.line() - 1);
                var col = Math.max(0, diag.column());
                var diagnosticId = resolvePrimaryDiagnosticId(diag);
                var diagMsg = StvnDiagnosticFormatter.formatCore(
                    diag.message(),
                    diag.canonicalCode().orElse(null),
                    diagnosticId
                );
                var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{diagMsg});
                if (problem == null) {
                    var highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                        .range(new TextRange(0, 0))
                        .descriptionAndTooltip(diagMsg)
                        .create();
                    if (highlightInfo != null) {
                        problem = new ProblemImpl(virtualFile, highlightInfo, false);
                    }
                }
                if (problem != null) {
                    problems.add(problem);
                }
            }

            if (!problems.isEmpty()) {
                wolf.reportProblems(virtualFile, problems);
            } else if (!activeErrorDiagnostics.isEmpty()) {
                var firstDiag = activeErrorDiagnostics.get(0);
                var diagnosticId = resolvePrimaryDiagnosticId(firstDiag);
                var diagMsg = StvnDiagnosticFormatter.formatCore(
                    firstDiag.message(),
                    firstDiag.canonicalCode().orElse(null),
                    diagnosticId
                );
                var highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                    .range(new TextRange(0, 0))
                    .descriptionAndTooltip(diagMsg)
                    .create();
                if (highlightInfo != null) {
                    wolf.reportProblems(virtualFile, List.of(new ProblemImpl(virtualFile, highlightInfo, false)));
                }
            } else {
                wolf.clearProblems(virtualFile);
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed() && virtualFile.isValid()) {
                    FileStatusManager.getInstance(project).fileStatusChanged(virtualFile);
                    var projectView = ProjectView.getInstance(project);
                    if (projectView != null) {
                        var pane = projectView.getCurrentProjectViewPane();
                        if (pane != null) {
                            pane.updateFromRoot(false);
                        }
                        projectView.refresh();
                    }
                }
            }, ModalityState.defaultModalityState());
        }

        var registeredExactAnnotations = new HashSet<String>();
        var registeredSyntaxStartOffsets = new HashSet<Integer>();

        for (var diag : activeDiagnostics) {
            var rawMessage = diag.message()
                .replace("<schema type>", "schema type")
                .replace("<value keyword>", "variant keyword");
            var diagnosticId = resolvePrimaryDiagnosticId(diag);
            var canonicalCode = diag.canonicalCode().orElse(null);
            var message = StvnDiagnosticFormatter.formatCore(rawMessage, canonicalCode, diagnosticId);
            var severity = mapSeverity(diag.severity());
            var start = diag.startOffset();
            var end = diag.endOffset();

            boolean isBareSigil = DiagnosticBag.ERR_BARE_COLON_PROHIBITED.equals(diagnosticId)
                || DiagnosticBag.ERR_BARE_HASH_PROHIBITED.equals(diagnosticId)
                || message.contains("ERR_BARE_COLON_PROHIBITED")
                || message.contains("ERR_BARE_HASH_PROHIBITED")
                || message.contains("trap7")
                || message.contains("trap6");

            if (isBareSigil && start >= 0 && start < textLength) {
                end = start + 1;
            }

            boolean isLayer1Identity = isLayer1IdentityDiagnostic(diagnosticId, rawMessage);
            if (textLength == 0 || (isLayer1Identity && start == 0 && end == 0)) {
                var builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .fileLevel();
                boolean isReservedDomain = DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
                    || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED)
                    || rawMessage.contains("Reserved system domain");

                if (isReservedDomain) {
                    var omitFix = StvnOmitDomainQuickFix.create(file);
                    if (omitFix != null) {
                        builder = builder.withFix(omitFix);
                    }
                    var renameFix = StvnRenameDomainQuickFix.create(file);
                    if (renameFix != null) {
                        builder = builder.withFix(renameFix);
                    }
                    builder = builder.withFix(new StvnInteractiveRenameQuickFix());
                } else if (isLayer1Identity) {
                    builder = builder.withFix(new StvnInteractiveRenameQuickFix());
                }
                builder.create();
                continue;
            }

            if (DiagnosticBag.ERR_DUPLICATE_MAP_KEY.equals(diagnosticId)
                    && start >= 0
                    && end > start
                    && end <= textLength) {
                var rawKey = file.getText().substring(start, end).trim();
                if (rawKey.startsWith("\"") && rawKey.endsWith("\"") && rawKey.length() >= 2) {
                    rawKey = rawKey.substring(1, rawKey.length() - 1);
                }
                message = StvnDiagnosticFormatter.formatCore(
                    "Duplicate map key detected: '" + rawKey + "'",
                    canonicalCode,
                    DiagnosticBag.ERR_DUPLICATE_MAP_KEY
                );
            }

            if (DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH.equals(diagnosticId)
                    || rawMessage.startsWith("Tuple arity mismatch")) {
                if (!rawMessage.contains("missing")) {
                    var matcher = TUPLE_ARITY_PATTERN.matcher(rawMessage);
                    if (matcher.find()) {
                        int exp = Integer.parseInt(matcher.group(1));
                        int got = Integer.parseInt(matcher.group(2));
                        if (exp > got) {
                            int missing = exp - got;
                            message = StvnDiagnosticFormatter.formatCore(
                                "Tuple arity mismatch: Expected " + exp + " elements, got " + got + " (" + missing + " missing)",
                                canonicalCode,
                                DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH
                            );
                        }
                    }
                }
            }

            // Unlocated cyclic dependency trace fallback (rotates trace to start at active file name)
            if ((start < 0 || end < start) && rawMessage.startsWith("Cycle detected: ")) {
                renderCyclicIncludeDiagnostic(file, holder, severity, rawMessage, canonicalCode, diagnosticId);
                continue;
            }

            var s = start >= 0 ? Math.max(0, Math.min(start, textLength)) : 0;
            var e = end >= start && start >= 0 ? Math.max(s, Math.min(end, textLength)) : Math.min(1, textLength);

            if (s == e) {
                if (s < textLength) {
                    e = s + 1;
                } else if (s > 0) {
                    s = s - 1;
                }
            }

            if (s < e) {
                var range = new TextRange(s, e);
                if (DiagnosticBag.ERR_INVALID_REGEX.equals(diagnosticId)
                        || rawMessage.contains("Invalid regex pattern")) {
                    var elemAtStart = file.findElementAt(range.getStartOffset());
                    var metaEntry = PsiTreeUtil.getParentOfType(elemAtStart, org.stvnadore.psi.MetadataEntry.class, false);
                    if (metaEntry != null) {
                        var regexLit = PsiTreeUtil.findChildOfType(metaEntry, org.stvnadore.psi.StringLiteral.class);
                        if (regexLit != null) {
                            range = regexLit.getTextRange();
                        }
                    }
                }
                if (DiagnosticBag.ERR_EMPTY_METADATA_BLOCK.equals(diagnosticId)
                        || DiagnosticBag.ERR_EMPTY_META_BLOCK.equals(diagnosticId)
                        || DiagnosticBag.ERR_EMPTY_DEFS_BLOCK.equals(diagnosticId)
                        || rawMessage.contains("must not be empty")
                        || rawMessage.contains("requires at least one type definition inside ':defs { ... }'")
                        || rawMessage.contains("Empty definition block ':defs {}' is redundant")) {
                    if (s >= 0 && s + 2 <= textLength && "{}".equals(file.getText().substring(s, s + 2))) {
                        range = new TextRange(s, s + 2);
                    }
                }

                // Prevent IntelliJ HighlightInfoUpdater from dropping warnings whose range is identical to or inside an error range
                if (severity.equals(HighlightSeverity.WARNING) || severity.equals(HighlightSeverity.WEAK_WARNING)) {
                    final TextRange candidateWarnRange = range;
                    boolean coveredByError = activeErrorDiagnostics.stream().anyMatch(ed ->
                        ed.startOffset() >= 0
                            && ed.startOffset() <= candidateWarnRange.getStartOffset()
                            && candidateWarnRange.getEndOffset() <= ed.endOffset()
                    );
                    if (coveredByError) {
                        var elemAtWarn = file.findElementAt(range.getStartOffset());
                        PsiElement enclosing = PsiTreeUtil.getParentOfType(
                            elemAtWarn,
                            org.stvnadore.psi.MetaDomainFacet.class,
                            org.stvnadore.psi.MetaNameFacet.class,
                            org.stvnadore.psi.MetadataEntry.class
                        );
                        boolean escaped = false;
                        while (enclosing != null
                                && !(enclosing instanceof PsiFile)
                                && !(enclosing instanceof org.stvnadore.psi.MetaBlock)
                                && !(enclosing instanceof MetaEntry)
                                && !(enclosing instanceof org.stvnadore.psi.MetadataMap)) {
                            TextRange enclosingRange = enclosing.getTextRange();
                            if (enclosingRange != null && enclosingRange.getLength() > range.getLength()) {
                                range = enclosingRange;
                                boolean stillCovered = activeErrorDiagnostics.stream().anyMatch(ed ->
                                    ed.startOffset() >= 0
                                        && ed.startOffset() <= enclosingRange.getStartOffset()
                                        && enclosingRange.getEndOffset() <= ed.endOffset()
                                );
                                if (!stillCovered) {
                                    escaped = true;
                                    break;
                                }
                            }
                            enclosing = enclosing.getParent();
                        }
                        if (!escaped) {
                            CharSequence chars = file.getViewProvider().getContents();
                            int wsStart = range.getStartOffset();
                            int wsEnd = range.getEndOffset();
                            if (wsStart > 0 && (chars.charAt(wsStart - 1) == ' ' || chars.charAt(wsStart - 1) == '\t')) {
                                range = new TextRange(wsStart - 1, wsEnd);
                            } else if (wsEnd < textLength && (chars.charAt(wsEnd) == ' ' || chars.charAt(wsEnd) == '\t')) {
                                range = new TextRange(wsStart, wsEnd + 1);
                            }
                        }
                    }
                }

                boolean isSyntaxError = rawMessage.startsWith("STVN Syntax Error");
                String exactKey = "%d:%d:%s".formatted(range.getStartOffset(), range.getEndOffset(), message);
                if (registeredExactAnnotations.contains(exactKey)
                        || (isSyntaxError && registeredSyntaxStartOffsets.contains(range.getStartOffset()))) {
                    continue;
                }
                registeredExactAnnotations.add(exactKey);
                if (isSyntaxError) {
                    registeredSyntaxStartOffsets.add(range.getStartOffset());
                }

                var annotationBuilder = holder.newAnnotation(severity, message)
                    .range(range);

                var listLit = findMapTargetListLiteral(file, range);
                if (listLit != null) {
                    annotationBuilder = annotationBuilder.withFix(new StvnMapAutoHealerQuickFix(listLit));
                }

                // Illegal structural sections (:type or :body inside .stvn_d / .stvn_di)
                if (rawMessage.contains("cannot declare ':type'")
                        || rawMessage.contains("cannot declare ':body'")
                        || rawMessage.contains("Structural violation:")) {
                    if (rawMessage.contains("cannot declare ':type'")) {
                        var typeEntry = PsiTreeUtil.findElementOfClassAtRange(file, s, e, TypeEntry.class);
                        if (typeEntry == null) {
                            var elem = file.findElementAt(s);
                            if (elem != null) {
                                typeEntry = PsiTreeUtil.getParentOfType(elem, TypeEntry.class, false);
                            }
                        }
                        if (typeEntry == null) {
                            typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
                        }
                        if (typeEntry != null) {
                            annotationBuilder = annotationBuilder.withFix(
                                new RemoveIllegalStructuralSectionQuickFix(typeEntry, StvnVocabulary.KEYWORD_TYPE)
                            );
                        }
                    } else if (rawMessage.contains("cannot declare ':body'")) {
                        var bodyEntry = PsiTreeUtil.findElementOfClassAtRange(file, s, e, BodyEntry.class);
                        if (bodyEntry == null) {
                            var elem = file.findElementAt(s);
                            if (elem != null) {
                                bodyEntry = PsiTreeUtil.getParentOfType(elem, BodyEntry.class, false);
                            }
                        }
                        if (bodyEntry == null) {
                            bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                        }
                        if (bodyEntry != null) {
                            annotationBuilder = annotationBuilder.withFix(
                                new RemoveIllegalStructuralSectionQuickFix(bodyEntry, StvnVocabulary.KEYWORD_BODY)
                            );
                        }
                    }
                }

                // Unresolved :use package quick-fixes (modular E030 + Pass-A hermetic E035 parity)
                boolean isModularUnresolvedUsePkg = rawMessage.startsWith(
                    "Unresolved package in " + StvnVocabulary.KEYWORD_USE + " directive:"
                );
                boolean isHermeticUnresolvedUsePkg =
                    DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(
                            diagnosticId,
                            StvnDiagnosticCodes.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT
                        )
                        || "E035".equals(canonicalCode)
                        || rawMessage.startsWith("Scoped " + StvnVocabulary.KEYWORD_USE + " in hermetic document");
                if (isModularUnresolvedUsePkg || isHermeticUnresolvedUsePkg) {
                    annotationBuilder = StvnUnresolvedUsePackageQuickFixProvider.registerAnnotatorFixes(
                        annotationBuilder,
                        file,
                        range,
                        isHermeticUnresolvedUsePkg
                    );
                }

                // Undefined type or constant quick-fixes
                if (rawMessage.contains("Undefined type: ")
                        || rawMessage.contains("Unresolved type alias: ")
                        || rawMessage.contains("Undefined constant: ")
                        || rawMessage.contains("Unresolved constant: ")
                        || rawMessage.contains("Undeclared value keyword or constant: ")) {
                    var offendingTypeKw = PsiTreeUtil.findElementOfClassAtRange(file, s, e, TypeKeyword.class);
                    if (offendingTypeKw == null) {
                        var elemAtRange = file.findElementAt(s);
                        if (elemAtRange != null) {
                            offendingTypeKw = PsiTreeUtil.getParentOfType(elemAtRange, TypeKeyword.class, false);
                        }
                    }
                    if (offendingTypeKw != null) {
                        var kwText = offendingTypeKw.getText().trim();
                        var isLocalPkg = StvnPsiUtils.isTypeDeclaredInLocalPackage(file, kwText);
                        var isPrelude = !isLocalPkg
                            && StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), kwText) != null;
                        if (!isPrelude) {
                            annotationBuilder = StvnUnresolvedTypeQuickFixProvider.registerFixes(
                                annotationBuilder,
                                offendingTypeKw
                            );
                        }
                    } else {
                        var offendingValKw = PsiTreeUtil.findElementOfClassAtRange(file, s, e, ValueKeyword.class);
                        if (offendingValKw == null) {
                            var elemAtRange = file.findElementAt(s);
                            if (elemAtRange != null) {
                                offendingValKw = PsiTreeUtil.getParentOfType(elemAtRange, ValueKeyword.class, false);
                            }
                        }
                        if (offendingValKw != null && offendingValKw.getText().startsWith(StvnVocabulary.SIGIL_VALUE)) {
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnDeclareConstantQuickFix(offendingValKw, StvnVocabulary.TYPE_INT, "0")
                            );
                        }
                    }
                }

                // Bare '#' token in sum-type value position
                var elemAtStart = file.findElementAt(s);
                var tokenText = elemAtStart != null ? elemAtStart.getText().trim() : "";
                if (StvnVocabulary.SIGIL_VALUE.equals(tokenText)
                        || StvnVocabulary.SIGIL_VALUE.equals(file.getText().substring(s, e).trim())) {
                    if (elemAtStart != null) {
                        annotationBuilder = attachCompleteSumVariantQuickFixes(annotationBuilder, elemAtStart);
                    }
                }

                // Incomplete boolean trait quick-fixes (#preserveIndent, #equatable, #comparable)
                if (DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_INCOMPLETE_TRAIT_FACET)
                        || rawMessage.contains("requires an explicit boolean value")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    if (targetElem != null) {
                        var trait = targetElem instanceof org.stvnadore.psi.MetadataTrait mt
                            ? mt
                            : PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.MetadataTrait.class, false);
                        var boolFacet = targetElem instanceof org.stvnadore.psi.MetadataBool mb
                            ? mb
                            : PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.MetadataBool.class, false);
                        if ((trait != null && trait.getMetadataValue() == null)
                                || (boolFacet != null && boolFacet.getMetadataValue() == null)) {
                            for (var fix : CompleteTraitFacetQuickFix.createFixes(targetElem)) {
                                annotationBuilder = annotationBuilder.withFix(fix);
                            }
                        }
                    }
                }

                // Redundant or non-overridable metadata facet quick-fix (W003 / E040 / cross-hop E008)
                boolean isNonOverridable = DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(diagnosticId)
                        || "NON_OVERRIDABLE_FACET".equals(diagnosticId)
                        || "E040".equals(diagnosticId)
                        || rawMessage.contains("non-overridable");
                boolean isCrossHopAncestorMutex = (DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE)
                        || "E008".equals(diagnosticId)
                        || rawMessage.contains("mutually exclusive"))
                        && rawMessage.contains("ancestor");
                boolean isRedundant = DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.WARN_REDUNDANT_METADATA_FACET)
                        || rawMessage.contains("is redundant");
                if (isRedundant || isNonOverridable || isCrossHopAncestorMutex) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                    if (entry == null && range.getStartOffset() + 1 < textLength) {
                        entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(
                            file.findElementAt(range.getStartOffset() + 1)
                        );
                    }
                    if (entry != null) {
                        if (isNonOverridable || isCrossHopAncestorMutex) {
                            var changeBaseFix = ChangeBaseTypeToPrimitiveQuickFix.createIfApplicable(file, entry);
                            if (changeBaseFix != null) {
                                annotationBuilder = annotationBuilder.withFix(changeBaseFix);
                            }
                            annotationBuilder = annotationBuilder.withFix(
                                new RemoveRedundantFacetQuickFix(
                                    entry,
                                    RemoveRedundantFacetQuickFix.extractFacetKeyword(entry),
                                    "Remove non-overridable facet",
                                    PriorityAction.Priority.HIGH
                                )
                            );
                        } else {
                            annotationBuilder = annotationBuilder.withFix(new RemoveRedundantFacetQuickFix(entry));
                        }
                    }
                }

                // Inapplicable metadata facet quick-fix (ERR_INVALID_METADATA_FACET)
                if ((DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET)
                        || rawMessage.contains("is not permitted on"))
                        && !rawMessage.contains(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                    if (entry == null && range.getStartOffset() + 1 < textLength) {
                        entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(
                            file.findElementAt(range.getStartOffset() + 1)
                        );
                    }
                    if (entry != null) {
                        String facetName = StvnMetadataFacetInspection.extractFacetName(entry);
                        String kwTag = facetName != null ? StvnVocabulary.SIGIL_VALUE + facetName : entry.getText().trim();
                        annotationBuilder = annotationBuilder
                            .withFix(new RemoveRedundantFacetQuickFix(entry, kwTag, "Remove inapplicable facet"));
                    }
                }

                // Invalid #regex pattern quick-fix (E013 / INVALID_REGEX_PATTERN)
                if (DiagnosticBag.ERR_INVALID_REGEX.equals(diagnosticId)
                        || "INVALID_REGEX_PATTERN".equals(diagnosticId)
                        || rawMessage.contains("Invalid regex pattern:")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var regexEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                    if (regexEntry == null && range.getStartOffset() + 1 < textLength) {
                        regexEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(
                            file.findElementAt(range.getStartOffset() + 1)
                        );
                    }
                    if (regexEntry != null) {
                        annotationBuilder = annotationBuilder.withFix(
                            RemoveRedundantFacetQuickFix.createRemoveInvalidFacetFix(
                                regexEntry,
                                StvnVocabulary.FACET_KW_REGEX
                            )
                        );
                    }
                }

                // Invalid continuous :Float #size bit-width quick-fixes (E010 / FLOAT_INVALID_SIZE)
                if (DiagnosticBag.ERR_FLOAT_INVALID_SIZE.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_FLOAT_INVALID_SIZE)
                        || rawMessage.contains("continuous " + StvnVocabulary.TYPE_FLOAT + " " + StvnVocabulary.FACET_KW_SIZE + " must be 32 or 64")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var sizeEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                    if (sizeEntry == null && range.getStartOffset() + 1 < textLength) {
                        sizeEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(
                            file.findElementAt(range.getStartOffset() + 1)
                        );
                    }
                    var metaMap = sizeEntry != null ? PsiTreeUtil.getParentOfType(sizeEntry, MetadataMap.class, false) : null;
                    if (metaMap != null && sizeEntry != null) {
                        var exactEntry = StvnFloatFacetQuickFixProvider.findFirstExactEntry(metaMap);
                        var floatFixes = exactEntry != null
                            ? StvnFloatFacetQuickFixProvider.buildExactAndSizeMutexFixes(metaMap, exactEntry, sizeEntry)
                            : StvnFloatFacetQuickFixProvider.buildStandaloneInvalidFloatSizeFixes(metaMap, sizeEntry);
                        for (var floatFix : floatFixes) {
                            if (floatFix instanceof IntentionAction ia) {
                                annotationBuilder = annotationBuilder.withFix(ia);
                            }
                        }
                    }
                }

                TextRange deferredFirstFloatMutexRange = null;
                List<IntentionAction> deferredFirstFloatMutexFixes = null;

                // Mutually exclusive temporal mode/scale and :Float #exact/#size quick-fixes (ERR_MUTUALLY_EXCLUSIVE)
                if ((DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE)
                        || rawMessage.contains("Temporal scale facets")
                        || rawMessage.contains("Temporal mode facets")
                        || rawMessage.contains("Mutually exclusive temporal"))
                        && !isCrossHopAncestorMutex) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                    if (entry == null && range.getStartOffset() + 1 < textLength) {
                        entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(
                            file.findElementAt(range.getStartOffset() + 1)
                        );
                    }
                    if (entry != null) {
                        var metaMap = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
                        if (metaMap != null) {
                            boolean isMode = KeepOnlyTemporalFacetQuickFix.isModeEntry(entry);
                            boolean isScale = KeepOnlyTemporalFacetQuickFix.isScaleEntry(entry);
                            if (isMode || isScale) {
                                String clickedKw = KeepOnlyTemporalFacetQuickFix.extractTemporalKeyword(entry);
                                var others = new java.util.ArrayList<String>();
                                for (var sibling : metaMap.getMetadataEntryList()) {
                                    if (sibling != entry
                                            && ((isMode && KeepOnlyTemporalFacetQuickFix.isModeEntry(sibling))
                                                || (isScale && KeepOnlyTemporalFacetQuickFix.isScaleEntry(sibling)))) {
                                        String sibKw = KeepOnlyTemporalFacetQuickFix.extractTemporalKeyword(sibling);
                                        if (!sibKw.equals(clickedKw) && !others.contains(sibKw)) {
                                            others.add(sibKw);
                                        }
                                    }
                                }
                                if (!others.isEmpty()) {
                                    annotationBuilder = annotationBuilder
                                        .withFix(new KeepOnlyTemporalFacetQuickFix(entry, clickedKw, others, isMode));
                                }
                                annotationBuilder = annotationBuilder
                                    .withFix(new RemoveRedundantFacetQuickFix(entry, clickedKw, "Remove conflicting facet"));
                            } else {
                                String clickedKw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
                                var exactEntry = StvnFloatFacetQuickFixProvider.findFirstExactEntry(metaMap);
                                var sizeEntry = StvnFloatFacetQuickFixProvider.findFirstSizeEntry(metaMap);
                                if ((StvnVocabulary.FACET_KW_EXACT.equals(clickedKw)
                                        || StvnVocabulary.FACET_KW_SIZE.equals(clickedKw))
                                        && exactEntry != null && sizeEntry != null) {
                                    var mutexFixes = StvnFloatFacetQuickFixProvider.buildExactAndSizeMutexFixes(
                                        metaMap,
                                        exactEntry,
                                        sizeEntry
                                    );
                                    List<IntentionAction> mutexActions = new ArrayList<>(mutexFixes.length + 1);
                                    for (var mf : mutexFixes) {
                                        if (mf instanceof IntentionAction ia) {
                                            annotationBuilder = annotationBuilder.withFix(ia);
                                            mutexActions.add(ia);
                                        }
                                    }
                                    MetadataEntry firstMutexEntry = (entry == sizeEntry) ? exactEntry : sizeEntry;
                                    if (!StvnDiagnosticAnnotatorMapping.isInspectionActive(file, "StvnMetadataFacet")) {
                                        TextRange firstRange = firstMutexEntry.getTextRange();
                                        boolean firstHasFloatSizeError = activeErrorDiagnostics.stream().anyMatch(ed ->
                                            ed.startOffset() <= firstRange.getStartOffset()
                                                && firstRange.getEndOffset() <= ed.endOffset()
                                                && (DiagnosticBag.ERR_FLOAT_INVALID_SIZE.equals(resolvePrimaryDiagnosticId(ed))
                                                    || ed.message().contains("must be 32 or 64"))
                                        );
                                        if (!firstHasFloatSizeError && !mutexActions.isEmpty()) {
                                            deferredFirstFloatMutexRange = firstRange;
                                            deferredFirstFloatMutexFixes = mutexActions;
                                        }
                                    }
                                } else {
                                    annotationBuilder = annotationBuilder
                                        .withFix(new RemoveRedundantFacetQuickFix(entry, clickedKw, "Remove conflicting facet"));
                                }
                            }
                        }
                    }
                }

                // Duplicate or misplaced root-section quick-fixes (ERR_DUPLICATE_ROOT_SECTION, ERR_SECTION_ORDER_VIOLATION, E033)
                if (DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_DUPLICATE_ROOT_SECTION)
                        || DiagnosticBag.ERR_SECTION_ORDER_VIOLATION.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_SECTION_ORDER_VIOLATION)
                        || DiagnosticBag.ERR_META_POSITION_INVALID.equals(diagnosticId)
                        || "E033".equals(canonicalCode)) {
                    var secLeaf = file.findElementAt(range.getStartOffset());
                    var rootSec = StvnMetaTemplateUtil.findEnclosingRootSection(secLeaf);
                    if (rootSec != null && StvnMetaTemplateUtil.isInSecondaryRootSection(rootSec)) {
                        String kw = StvnMetaTemplateUtil.resolveRootSectionKeyword(rootSec);
                        if (kw != null) {
                            annotationBuilder = annotationBuilder.withFix(
                                new RemoveDuplicateSectionQuickFix(rootSec, kw)
                            );
                        }
                    } else if (StvnRootSectionReorderer.canReorderRootSections(file)) {
                        annotationBuilder = annotationBuilder.withFix(new ReorderRootSectionsQuickFix());
                    }
                }

                // Missing temporal mode/scale quick-fixes (ERR_MISSING_TEMPORAL_FACET)
                if (DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_DATETIME_MODE_MISSING)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MISSING)
                        || rawMessage.contains("requires explicit mode facet")
                        || rawMessage.contains("requires a scale facet")
                        || rawMessage.contains("requires exactly one mode facet")
                        || rawMessage.contains("requires exactly one scale facet")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var schemaType = PsiTreeUtil.getParentOfType(targetElem, SchemaType.class, false);
                    if (schemaType != null) {
                        var typeText = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
                        if (StvnVocabulary.TYPE_DATE_TIME.equals(typeText)) {
                            for (var fix : StvnTemporalModeInspection.createDateTimeModeQuickFixes(schemaType)) {
                                annotationBuilder = annotationBuilder.withFix(fix);
                            }
                        } else if (StvnVocabulary.TYPE_TIME_EPOCH.equals(typeText)) {
                            for (var fix : StvnTemporalModeInspection.createTimeEpochScaleQuickFixes(schemaType)) {
                                annotationBuilder = annotationBuilder.withFix(fix);
                            }
                        }
                    }
                }

                // Metadata facet and ':meta' entry order quick-fixes (ERR_FACET_ORDER_VIOLATION)
                if (DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_FACET_ORDER_VIOLATION)
                        || rawMessage.contains("5-tier hierarchy")
                        || rawMessage.contains("canonical order (#name, #domain, #kind)")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var metaBlock = PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.MetaBlock.class, false);
                    if (metaBlock != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnReorderMetaEntriesQuickFix(metaBlock));
                    } else {
                        var metaMap = PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.MetadataMap.class, false);
                        if (metaMap != null) {
                            annotationBuilder = annotationBuilder.withFix(new StvnReorderMetadataFacetsQuickFix(metaMap));
                        }
                    }
                }

                // Reserved package namespace quick-fix (E037)
                if (DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_RESERVED_PACKAGE_PROHIBITED)
                        || rawMessage.contains("Reserved package namespace prohibited")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    if (targetElem != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnRenameReservedPackageQuickFix(targetElem));
                    }
                }

                // Trailing slash quick-fix (ERR_TRAILING_SLASH_PROHIBITED)
                if (DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED)
                        || rawMessage.contains("Trailing slash prohibited")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    PsiElement slashElem = PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.UseTarget.class, false);
                    if (slashElem == null) {
                        slashElem = PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.PackagePath.class, false);
                    }
                    if (slashElem != null) {
                        String cleaned = StvnTrailingSlashInspection.stripTrailingSlashes(slashElem.getText().trim());
                        annotationBuilder = annotationBuilder.withFix(
                            new StvnRemoveTrailingSlashIntentionAction(slashElem, cleaned)
                        );
                    }
                }

                TextRange deferredE038KeywordRange = null;
                List<IntentionAction> deferredE038KeywordFixes = null;

                // Reserved domain quick-fixes (E038)
                if (DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED)
                        || rawMessage.contains("Reserved system domain")) {
                    String fName = file.getName();
                    int lastDot = fName.lastIndexOf('.');
                    String[] dotParts = lastDot > 0 ? fName.substring(0, lastDot).split("\\.") : new String[0];
                    boolean filenameHasReservedDomain = dotParts.length >= 2 && dotParts[1].startsWith("stvn_");
                    var targetElem = file.findElementAt(range.getStartOffset());
                    if (filenameHasReservedDomain) {
                        if (targetElem != null) {
                            annotationBuilder = annotationBuilder.withFix(new StvnChangeReservedDomainQuickFix(targetElem));
                        }
                        var omitFix = StvnOmitDomainQuickFix.create(file);
                        if (omitFix != null) {
                            annotationBuilder = annotationBuilder.withFix(omitFix);
                        }
                        var renameFix = StvnRenameDomainQuickFix.create(file);
                        if (renameFix != null) {
                            annotationBuilder = annotationBuilder.withFix(renameFix);
                        }
                        annotationBuilder = annotationBuilder.withFix(new StvnInteractiveRenameQuickFix());
                    } else if (targetElem != null) {
                        StringLiteral stringLit = targetElem instanceof StringLiteral sl
                            ? sl
                            : PsiTreeUtil.getParentOfType(targetElem, StringLiteral.class, false);
                        PsiElement fixTarget = stringLit != null ? stringLit : targetElem;
                        var domainFacet = PsiTreeUtil.getParentOfType(fixTarget, MetaDomainFacet.class, false);
                        List<IntentionAction> e038Actions = new ArrayList<>(4);
                        if (dotParts.length == 2
                                && !dotParts[1].isEmpty()
                                && !dotParts[1].startsWith("stvn_")
                                && POSIX_META_ID_PATTERN.matcher(dotParts[1]).matches()) {
                            e038Actions.add(
                                new StvnStemMismatchInspection.AlignDomainWithStemQuickFix(fixTarget, dotParts[1])
                            );
                        }
                        e038Actions.add(
                            new StvnStemMismatchInspection.StvnChangeReservedDomainQuickFix(fixTarget)
                        );
                        if (dotParts.length == 1 && domainFacet != null) {
                            e038Actions.add(
                                new StvnStemMismatchInspection.RemoveMetaDomainQuickFix(domainFacet)
                            );
                        }
                        for (IntentionAction e038Fix : e038Actions) {
                            annotationBuilder = annotationBuilder.withFix(e038Fix);
                        }
                        if (domainFacet != null && stringLit != null) {
                            int fStart = domainFacet.getTextRange().getStartOffset();
                            int sStart = stringLit.getTextRange().getStartOffset();
                            if (sStart > fStart) {
                                deferredE038KeywordRange = new TextRange(fStart, Math.max(fStart + 1, sStart - 1));
                                deferredE038KeywordFixes = e038Actions;
                            }
                        }
                    }
                }

                annotationBuilder = attachMetaIdentityQuickFixes(
                    annotationBuilder,
                    file,
                    diagnosticId,
                    rawMessage,
                    s,
                    range
                );

                // Untagged triple-quoted block or inline string quick-fixes (Rule STR-04, E025)
                var fenceLeaf = file.findElementAt(range.getStartOffset());
                String fenceLeafText = fenceLeaf != null ? fenceLeaf.getText() : "";
                boolean isUntaggedTripleQuote = fenceLeafText.startsWith("\"\"\"")
                    && !fenceLeafText.startsWith("\"\"\"[")
                    && !fenceLeafText.startsWith("\"\"\"->");
                if (rawMessage.contains("Unfenced block strings are prohibited") || isUntaggedTripleQuote) {
                    String rawInner = (fenceLeafText.endsWith("\"\"\"") && fenceLeafText.length() >= 6)
                        ? fenceLeafText.substring(3, fenceLeafText.length() - 3)
                        : fenceLeafText;
                    String safeTag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(rawInner, "FENCE");
                    annotationBuilder = annotationBuilder.withFix(
                        new ConvertToFencedStringIntentionAction(range, safeTag)
                    );
                    var simpleFix = ConvertToSimpleStringIntentionAction.createIfSingleLineUntagged(file, range);
                    if (simpleFix != null) {
                        annotationBuilder = annotationBuilder.withFix(simpleFix);
                    }
                }

                // Tab character quick-fix (Rule STR-01 / E001)
                if (DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN)
                        || "TAB_DISCIPLINE_VIOLATION".equals(diagnosticId)
                        || rawMessage.contains("Tab character")) {
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnTabCharacterInspection.ReplaceTabWithSpacesQuickFix()
                    );
                }

                TextRange deferredEarlierSwapRange = null;
                StvnDiscreteIntervalInspection.SwapInvertedBoundsQuickFix deferredEarlierSwapFix = null;

                // Inverted interval bounds quick-fix (ERR_INVERTED_RANGE / INVALID_NUMERIC_RANGE)
                if (DiagnosticBag.ERR_INVERTED_RANGE.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_INVERTED_RANGE)
                        || "INVALID_NUMERIC_RANGE".equals(diagnosticId)
                        || rawMessage.contains("effective range is invalid")
                        || rawMessage.contains("cannot exceed " + StvnVocabulary.FACET_KW_MAX_SIZE)) {
                    boolean cardinalityBounds = rawMessage.contains(StvnVocabulary.FACET_KW_MIN_SIZE);
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var metaMap = PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.MetadataMap.class, false);
                    if (metaMap == null && range.getStartOffset() + 1 < textLength) {
                        metaMap = PsiTreeUtil.getParentOfType(
                            file.findElementAt(range.getStartOffset() + 1),
                            org.stvnadore.psi.MetadataMap.class,
                            false
                        );
                    }
                    var swapFix = StvnDiscreteIntervalInspection.createSwapFixForMetadataMap(metaMap, cardinalityBounds);
                    if (swapFix != null) {
                        annotationBuilder = annotationBuilder.withFix(swapFix);
                        var earlierNonAdj = StvnDiscreteIntervalInspection.findNonAdjacentEarlierBoundEntry(
                            metaMap,
                            cardinalityBounds
                        );
                        if (earlierNonAdj != null && !earlierNonAdj.getTextRange().intersectsStrict(range)) {
                            deferredEarlierSwapRange = earlierNonAdj.getTextRange();
                            deferredEarlierSwapFix = swapFix;
                        }
                    }
                }

                // Pass A (0-inspection) mismatched fenced delimiter balance quick-fixes (Rule STR-04 / E025)
                if (("FENCE_DELIMITER_MISMATCH".equals(diagnosticId)
                        || DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH.equals(diagnosticId))
                        && rawMessage.contains("found mismatched closing tag")) {
                    for (var fenceFix : StvnFencedStringInspection.createMismatchedFenceQuickFixes(file, range)) {
                        annotationBuilder = annotationBuilder.withFix(fenceFix);
                    }
                }

                // Duplicate enum filter variant quick-fix (ERR_FILTER_DUPLICATE_VARIANT)
                if (DiagnosticBag.ERR_FILTER_DUPLICATE_VARIANT.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_FILTER_DUPLICATE_VARIANT)
                        || rawMessage.contains("in filter list")
                        || rawMessage.contains("duplicate variant in filter")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var vkw = PsiTreeUtil.getParentOfType(targetElem, ValueKeyword.class, false);
                    if (vkw != null) {
                        annotationBuilder = annotationBuilder.withFix(
                            new StvnRemoveDuplicateFilterVariantQuickFix(vkw, vkw.getText())
                        );
                    }
                }

                // Interactive bootstrapping intention actions for incomplete clauses
                if (DiagnosticBag.ERR_MALFORMED_SECTION_STRUCTURE.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_MALFORMED_SECTION_STRUCTURE)) {
                    var incLeaf = file.findElementAt(range.getStartOffset());
                    String leafKw = incLeaf != null ? incLeaf.getText().trim() : null;
                    String incKw;
                    if (StvnVocabulary.KEYWORD_META.equals(leafKw)
                            || StvnVocabulary.KEYWORD_DEFS.equals(leafKw)
                            || StvnVocabulary.KEYWORD_TYPE.equals(leafKw)
                            || StvnVocabulary.KEYWORD_BODY.equals(leafKw)) {
                        incKw = leafKw;
                    } else {
                        var incSec = StvnMetaTemplateUtil.findEnclosingRootSection(incLeaf);
                        incKw = incSec != null ? StvnMetaTemplateUtil.resolveRootSectionKeyword(incSec) : leafKw;
                    }
                    if (StvnVocabulary.KEYWORD_META.equals(incKw)) {
                        annotationBuilder = annotationBuilder.withFix(new InsertMetaBlockIntentionAction(range));
                    } else if (StvnVocabulary.KEYWORD_DEFS.equals(incKw)) {
                        annotationBuilder = annotationBuilder.withFix(new InsertDefinitionBlockIntentionAction(range));
                    } else if (StvnVocabulary.KEYWORD_TYPE.equals(incKw)) {
                        String suggestedType = InsertSchemaTypeIntentionAction.inferSchemaTypeForRange(file, range);
                        annotationBuilder = annotationBuilder.withFix(
                            new InsertSchemaTypeIntentionAction(range, suggestedType)
                        );
                    } else if (StvnVocabulary.KEYWORD_BODY.equals(incKw)) {
                        annotationBuilder = annotationBuilder.withFix(new InsertDefaultBodyValueIntentionAction(range));
                    }
                }
                if (DiagnosticBag.ERR_EMPTY_META_BLOCK.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_EMPTY_META_BLOCK)) {
                    String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(file.getName());
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnMissingMetaInspection.InsertMissingMetaBlockQuickFix(expectedKind)
                    );
                }
                if (DiagnosticBag.ERR_EMPTY_DEFS_BLOCK.equals(diagnosticId)
                        || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_EMPTY_DEFS_BLOCK)) {
                    var fileName = file.getName();
                    if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd") || fileName.endsWith(".stvn_di")) {
                        annotationBuilder = annotationBuilder.withFix(new InsertTypeDefinitionIntentionAction(range));
                    } else {
                        annotationBuilder = annotationBuilder.withFix(new RemoveRedundantDefsBlockIntentionAction(range));
                    }
                }

                annotationBuilder = attachWrapSumVariantQuickFixes(annotationBuilder, file, range, diagnosticId, rawMessage);

                if (DiagnosticBag.ERR_MALFORMED_SCHEMA.equals(diagnosticId)
                        || "ERR_MALFORMED_SCHEMA".equals(diagnosticId)
                        || rawMessage.startsWith("Union schema requires at least 2 branch types")) {
                    var unwrapFix = StvnDegenerateCompositeInspection.createSingleBranchUnionUnwrapFix(
                        file.findElementAt(range.getStartOffset())
                    );
                    if (unwrapFix != null) {
                        annotationBuilder = annotationBuilder.withFix(unwrapFix);
                    }
                }

                // Trait violation navigation and paired healing quick-fixes (ERR_TRAIT_VIOLATION)
                if (DiagnosticBag.ERR_TRAIT_VIOLATION.equals(diagnosticId)
                        || "ERR_TRAIT_VIOLATION".equals(diagnosticId)
                        || rawMessage.contains("require types to be #equatable")
                        || rawMessage.contains("requires equatable")
                        || rawMessage.contains("requires comparable")) {
                    annotationBuilder = StvnTraitViolationQuickFixProvider.registerActions(
                        annotationBuilder,
                        file,
                        range,
                        message
                    );
                }

                annotationBuilder = StvnPayloadValueQuickFixProvider.registerPayloadValueQuickFixes(
                    annotationBuilder,
                    file,
                    diagnosticId,
                    rawMessage,
                    range.getStartOffset(),
                    range.getEndOffset()
                );

                // Attach reorder quick-fix to co-located ERROR annotations inside disordered :meta or MetadataMap
                if (severity.equals(HighlightSeverity.ERROR)
                        && !DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(diagnosticId)
                        && !StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_FACET_ORDER_VIOLATION)) {
                    var reorderElem = file.findElementAt(range.getStartOffset());
                    if (reorderElem != null && !StvnMetaTemplateUtil.isInSecondaryRootSection(reorderElem)) {
                        var enclosingMetaEntry = reorderElem instanceof MetaEntry me
                            ? me
                            : PsiTreeUtil.getParentOfType(reorderElem, MetaEntry.class, false);
                        if (enclosingMetaEntry != null && StvnMetaTemplateUtil.isPrimaryMetaEntry(enclosingMetaEntry)) {
                            var enclosingMetaBlock = enclosingMetaEntry.getMetaBlock();
                            if (enclosingMetaBlock != null
                                    && !PsiTreeUtil.hasErrorElements(enclosingMetaBlock)
                                    && StvnMetadataOrderInspection.isMetaBlockDisordered(enclosingMetaBlock)) {
                                var reorderMetaFix = new StvnReorderMetaEntriesQuickFix(enclosingMetaBlock);
                                annotationBuilder = annotationBuilder.withFix(reorderMetaFix);
                                if (deferredE038KeywordFixes != null) {
                                    deferredE038KeywordFixes.add(reorderMetaFix);
                                }
                            }
                        } else {
                            var enclosingMetaMap = reorderElem instanceof MetadataMap mm
                                ? mm
                                : PsiTreeUtil.getParentOfType(reorderElem, MetadataMap.class, false);
                            if (enclosingMetaMap != null
                                    && StvnMetadataOrderInspection.isMetadataMapDisordered(enclosingMetaMap)) {
                                var reorderMapFix = new StvnReorderMetadataFacetsQuickFix(enclosingMetaMap);
                                annotationBuilder = annotationBuilder.withFix(reorderMapFix);
                                if (deferredFirstFloatMutexFixes != null) {
                                    deferredFirstFloatMutexFixes.add(reorderMapFix);
                                }
                            }
                        }
                    }
                }

                annotationBuilder.create();
                if (deferredEarlierSwapRange != null && deferredEarlierSwapFix != null) {
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(deferredEarlierSwapRange)
                        .withFix(deferredEarlierSwapFix)
                        .create();
                }
                if (deferredE038KeywordRange != null && deferredE038KeywordFixes != null && !deferredE038KeywordFixes.isEmpty()) {
                    var infoBuilder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(deferredE038KeywordRange);
                    for (IntentionAction fix : deferredE038KeywordFixes) {
                        infoBuilder = infoBuilder.withFix(fix);
                    }
                    infoBuilder.create();
                }
                if (deferredFirstFloatMutexRange != null && deferredFirstFloatMutexFixes != null && !deferredFirstFloatMutexFixes.isEmpty()) {
                    var infoBuilder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(deferredFirstFloatMutexRange);
                    for (IntentionAction fix : deferredFirstFloatMutexFixes) {
                        infoBuilder = infoBuilder.withFix(fix);
                    }
                    infoBuilder.create();
                }
            }
        }

        // Pass A (0-inspection) silent INFORMATION reorder anchors on non-error tokens when StvnMetadataOrder is inactive
        if (!StvnDiagnosticAnnotatorMapping.isInspectionActive(file, "StvnMetadataOrder")) {
            emitPassADisorderedBlockAnchors(file, holder, activeErrorDiagnostics);
        }

        // Validate DateTime literals in healthy :body slots inside structurally recovered containers (skipping degraded schemas per VOP Option D)
        var bodyClause = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (bodyClause != null) {
            for (var strLit : PsiTreeUtil.findChildrenOfType(bodyClause, StringLiteral.class)) {
                if (strLit.getParent() instanceof Value valNode) {
                    var govSchema = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(valNode);
                    if (govSchema != null) {
                        if (StvnTypeResolver.isDegradedOrUngroundedSchema(file, govSchema)
                                || StvnTypeResolver.hasDegradedEnclosingValueAncestor(valNode)) {
                            continue;
                        }
                        var termSchema = StvnTypeResolver.resolveNominalSchema(govSchema);
                        var inspectSchema = termSchema != null ? termSchema : govSchema;
                        var ctor = inspectSchema.getSchemaConstructor();
                        if (ctor != null && ctor.getAtomicType() != null
                                && StvnVocabulary.TYPE_DATE_TIME.equals(ctor.getAtomicType().getText().trim())) {
                            var modeFlag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(govSchema);
                            if (modeFlag == null) {
                                modeFlag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(inspectSchema);
                            }
                            StvnSchema.DateTimeMode mode = StvnVocabulary.FACET_KW_OFFSET.equals(modeFlag)
                                ? StvnSchema.DateTimeMode.OFFSET
                                : StvnVocabulary.FACET_KW_ZONED.equals(modeFlag)
                                    ? StvnSchema.DateTimeMode.ZONED
                                    : StvnVocabulary.FACET_KW_AUDITED.equals(modeFlag)
                                        ? StvnSchema.DateTimeMode.AUDITED
                                        : null;
                            if (mode != null) {
                                String rawDtText = strLit.getText().trim();
                                if (!rawDtText.startsWith("\"\"\"")
                                        && StvnPsiLiteralEvaluator.decodeStringLiteral(rawDtText, false).isEmpty()) {
                                    continue;
                                }
                                var valRange = valNode.getTextRange();
                                boolean rangeContainsActiveError = activeErrorDiagnostics.stream().anyMatch(ed ->
                                    ed.startOffset() >= valRange.getStartOffset()
                                        && ed.endOffset() <= valRange.getEndOffset()
                                );
                                if (rangeContainsActiveError) {
                                    continue;
                                }
                                var failureOpt = StvnCompiler.validateDateTimeLiteral(rawDtText, mode);
                                if (failureOpt.isPresent()) {
                                    var failure = failureOpt.get();
                                    var formattedMsg = StvnDiagnosticFormatter.formatCore(
                                        failure.message(),
                                        failure.canonicalCode(),
                                        failure.symbolicId()
                                    );
                                    var exactKey = "%d:%d:%s".formatted(valRange.getStartOffset(), valRange.getEndOffset(), formattedMsg);
                                    boolean rangeAlreadyHasError = registeredExactAnnotations.stream()
                                        .anyMatch(k -> k.startsWith("%d:%d:".formatted(valRange.getStartOffset(), valRange.getEndOffset())));
                                    if (!registeredExactAnnotations.contains(exactKey) && !rangeAlreadyHasError) {
                                        registeredExactAnnotations.add(exactKey);
                                        var dtBuilder = holder.newAnnotation(HighlightSeverity.ERROR, formattedMsg)
                                            .range(valRange);
                                        dtBuilder = StvnPayloadValueQuickFixProvider.registerPayloadValueQuickFixes(
                                            dtBuilder,
                                            file,
                                            failure.symbolicId(),
                                            failure.message(),
                                            valRange.getStartOffset(),
                                            valRange.getEndOffset()
                                        );
                                        dtBuilder.create();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Anchor warning on :type when a referenced nominal schema is poisoned or degraded
        boolean hasCrossFileInvertibleError = rawDiagnostics.stream()
            .anyMatch(d -> isDiagnosticFromIncludedFile(file, d, activeNormalizedPath)
                && d.message().contains("Inverted map values require types to be #equatable #TRUE"));
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry != null) {
            var typeKeywords = PsiTreeUtil.findChildrenOfType(typeEntry, TypeKeyword.class);
            for (var typeKw : typeKeywords) {
                var aliasName = typeKw.getText();
                var irSchema = StvnTypeResolver.resolveIrSchemaByAlias(file, aliasName);
                boolean isTraitPoisoned = irSchema instanceof StvnSchema.PoisonedSchema p
                    && DiagnosticBag.ERR_TRAIT_VIOLATION.equals(p.primaryDiagnosticCode());
                boolean isDegraded = StvnTypeResolver.isDegradedNominalAlias(file, aliasName);
                if (isDegraded || hasCrossFileInvertibleError || isTraitPoisoned) {
                    @Nullable String fallbackBase = null;
                    if (irSchema instanceof StvnSchema.PoisonedSchema poisoned && poisoned.isGrounded()) {
                        fallbackBase = StvnSchemaFormatter.formatDegradedUnderlyingBaseIr(poisoned.bestEffortFallback().get());
                    } else if (!(irSchema instanceof StvnSchema.PoisonedSchema)) {
                        var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new HashSet<>());
                        var td = StvnPsiUtils.resolveTargetTypeDefinition(targetDef);
                        if (td != null && td.getSchemaType() != null) {
                            var resolved = StvnTypeResolver.resolveNominalSchema(td.getSchemaType());
                            if (resolved != null && resolved.getSchemaConstructor() != null) {
                                fallbackBase = StvnSchemaFormatter.formatDegradedUnderlyingBasePsi(resolved);
                            }
                        }
                    }
                    var degradedMsg = fallbackBase != null
                        ? "Schema '" + aliasName
                            + "' has constraint errors at definition site in "
                            + StvnVocabulary.KEYWORD_DEFS
                            + " (underlying base '" + fallbackBase + "' \u2014 payload evaluation is suspended)"
                        : "Schema '" + aliasName
                            + "' does not resolve to a base type in "
                            + StvnVocabulary.KEYWORD_DEFS
                            + " (circular or unresolved lineage). Payload evaluation is suspended";
                    var formattedDegradedMsg = StvnDiagnosticFormatter.formatPlugin(
                        degradedMsg,
                        StvnDiagnosticCodes.WARN_DEGRADED_SCHEMA_USAGE
                    );
                    var warnBuilder = holder.newAnnotation(HighlightSeverity.WARNING, formattedDegradedMsg)
                        .range(typeKw.getTextRange());
                    if (hasCrossFileInvertibleError || isTraitPoisoned) {
                        warnBuilder = StvnTraitViolationQuickFixProvider.registerActions(
                            warnBuilder,
                            file,
                            typeKw.getTextRange(),
                            "Inverted map values require types to be #equatable #TRUE"
                        );
                    }
                    warnBuilder.create();
                }
            }
        }
    }

    private static String resolvePrimaryDiagnosticId(StvnDiagnostic diag) {
        return diag.symbolicId()
            .or(diag::canonicalCode)
            .or(diag::errorCode)
            .orElse("ERR_COMPILATION_ERROR");
    }

    private static boolean isLayer1IdentityDiagnostic(String diagnosticId, String rawMessage) {
        return DiagnosticBag.ERR_INVALID_FILENAME_STEM.equals(diagnosticId)
            || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_INVALID_FILENAME_STEM)
            || DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED.equals(diagnosticId)
            || DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
            || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED)
            || (DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH.equals(diagnosticId)
                && rawMessage.startsWith("Unrecognized STVN file extension"))
            || rawMessage.contains("Reserved system domain prohibited")
            || rawMessage.contains("bare dotfiles are prohibited")
            || rawMessage.contains("Invalid filename stem")
            || rawMessage.startsWith("Unrecognized STVN file extension");
    }

    private static void renderCyclicIncludeDiagnostic(
        PsiFile file,
        AnnotationHolder holder,
        HighlightSeverity severity,
        String rawMessage,
        @Nullable String canonicalCode,
        String diagnosticId
    ) {
        String rotatedRaw = rotateCycleTrace(rawMessage, file.getName());
        String formatted = StvnDiagnosticFormatter.formatCore(rotatedRaw, canonicalCode, diagnosticId);
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        boolean matched = false;
        for (var elem : includes) {
            var stringLit = elem.getStringLiteral();
            if (stringLit != null) {
                var decoded = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true).orElse("");
                if (!decoded.isEmpty()) {
                    var baseName = extractFileName(decoded);
                    if (rotatedRaw.contains(decoded) || rotatedRaw.contains(baseName)) {
                        holder.newAnnotation(severity, formatted)
                            .range(elem.getTextRange())
                            .create();
                        matched = true;
                    }
                }
            }
        }
        if (!matched) {
            holder.newAnnotation(severity, formatted)
                .fileLevel()
                .create();
        }
    }

    private static String rotateCycleTrace(String rawMessage, String mainFileName) {
        String prefix = "Cycle detected: ";
        if (!rawMessage.startsWith(prefix)) {
            return rawMessage;
        }
        String traceStr = rawMessage.substring(prefix.length());
        String[] parts = traceStr.split(" -> ");
        int startIdx = -1;
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(mainFileName)) {
                startIdx = i;
                break;
            }
        }
        if (startIdx == -1) {
            return rawMessage;
        }
        var rotated = new ArrayList<String>();
        for (int i = startIdx; i < parts.length - 1; i++) {
            rotated.add(parts[i]);
        }
        for (int i = 0; i < startIdx; i++) {
            rotated.add(parts[i]);
        }
        rotated.add(parts[startIdx]);
        return prefix + String.join(" -> ", rotated);
    }

    boolean isDiagnosticSuppressed(PsiFile file, StvnDiagnostic diag) {
        return isDiagnosticSuppressed(file, diag, List.of(), "");
    }

    boolean isDiagnosticSuppressed(PsiFile file, StvnDiagnostic diag, List<StvnDiagnostic> allDiagnostics) {
        return isDiagnosticSuppressed(file, diag, allDiagnostics, "");
    }

    private boolean isDiagnosticSuppressed(
        PsiFile file,
        StvnDiagnostic diag,
        List<StvnDiagnostic> allDiagnostics,
        String activeNormalizedPath
    ) {
        // 1. Cross-File Diagnostic Filtering via authoritative ESC-CORE-01 diag.sourcePath()
        if (isDiagnosticFromIncludedFile(file, diag, activeNormalizedPath)) {
            return true;
        }

        // 2. Deconfliction Gate: suppress compiler diagnostics governed by an active local PSI inspection
        if (StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(file, diag)) {
            return true;
        }

        var message = diag.message();
        var diagnosticId = resolvePrimaryDiagnosticId(diag);

        // 3. StvnSemanticAnnotator owns in-flight and committed :Union tag overflow checks when PSI resolves the union
        if ((DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW.equals(diagnosticId)
                || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_UNION_BRANCH_OVERFLOW)
                || (message.startsWith("Union variant tag '") && message.contains("exceeds branch count")))
                && StvnSemanticAnnotator.handlesUnionBranchOverflowAtOffset(file, diag.startOffset())) {
            return true;
        }
        if ((DiagnosticBag.ERR_TYPE_MISMATCH.equals(diagnosticId)
                || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_TYPE_MISMATCH))
                && message.contains("matches neither Left nor Right branch")
                && StvnSemanticAnnotator.hasActiveUnionBranchOverflowAtOffset(file, diag.startOffset())) {
            return true;
        }

        // 4. Universal Comment & Whitespace Locus Protection (except forbidden tab characters)
        boolean isTabError = DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN.equals(diagnosticId)
            || message.contains("Tab character");
        var text = file.getText();
        var textLength = file.getTextLength();
        var start = diag.startOffset();
        var end = diag.endOffset();

        if (!isTabError && start >= 0 && start <= textLength) {
            var s = Math.max(0, Math.min(start, textLength));
            var e = Math.max(s, Math.min(end, textLength));
            if (s < e && text.substring(s, e).isBlank()) {
                return true;
            }
            int checkOffset = s;
            int limitOffset = Math.max(s + 1, e);
            while (checkOffset < limitOffset && checkOffset < textLength) {
                var leaf = file.findElementAt(checkOffset);
                if (leaf == null) {
                    break;
                }
                if (leaf instanceof PsiComment || PsiTreeUtil.getParentOfType(leaf, PsiComment.class, false) != null) {
                    return true;
                }
                var nextOffset = leaf.getTextRange().getEndOffset();
                if (nextOffset <= checkOffset) {
                    break;
                }
                checkOffset = nextOffset;
            }
        }

        return false;
    }

    private static boolean isDiagnosticFromIncludedFile(
        PsiFile file,
        StvnDiagnostic diag,
        String activeNormalizedPath
    ) {
        if (diag.sourcePath().isEmpty()) {
            return false;
        }
        String diagPath = diag.sourcePath().get();
        if (!activeNormalizedPath.isEmpty()) {
            return !diagPath.equals(activeNormalizedPath);
        }
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return false;
        }
        String vfPath = StvnSchemaFlattener.normalizePath(virtualFile.getPath());
        String strippedVf = vfPath.replaceFirst("^/+", "");
        return !diagPath.equals(vfPath)
            && (strippedVf.isEmpty() || !diagPath.endsWith("/" + strippedVf));
    }

    private record IncludeNode(
        IncludeElement directInclude,
        String directPath,
        PsiFile directFile,
        List<String> pathChain,
        String targetPath,
        PsiFile targetFile,
        String expectedCompilerPath,
        String targetVfPath
    ) {}

    private static void renderPinnedIncludeDiagnostics(
        PsiFile file,
        List<StvnDiagnostic> diagnostics,
        String activeNormalizedPath,
        AnnotationHolder holder,
        @Nullable List<Problem> problems,
        @Nullable WolfTheProblemSolver wolf,
        @Nullable VirtualFile virtualFile
    ) {
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (includes.isEmpty()) {
            return;
        }

        var document = file.getViewProvider().getDocument();
        String baseCompilerPath = !activeNormalizedPath.isEmpty()
            ? activeNormalizedPath
            : (file.getVirtualFile() != null
                ? StvnSchemaFlattener.normalizePath(file.getVirtualFile().getPath())
                : "");

        var includeNodes = new ArrayList<IncludeNode>();
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit == null) {
                continue;
            }
            var directPath = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true).orElse(null);
            if (directPath == null || directPath.isEmpty()) {
                continue;
            }
            var resolvedDirect = StvnTypeReference.resolveIncludeFile(stringLit);
            if (resolvedDirect == null) {
                continue;
            }
            String expectedChildPath = resolveRelativeNormalizedPath(baseCompilerPath, directPath);
            String targetVfPath = resolvedDirect.getVirtualFile() != null
                ? StvnSchemaFlattener.normalizePath(resolvedDirect.getVirtualFile().getPath())
                : "";

            var directChain = new ArrayList<String>();
            directChain.add(directPath);
            includeNodes.add(new IncludeNode(
                incl,
                directPath,
                resolvedDirect,
                directChain,
                directPath,
                resolvedDirect,
                expectedChildPath,
                targetVfPath
            ));
            collectTransitiveIncludes(
                incl,
                directPath,
                resolvedDirect,
                directChain,
                resolvedDirect,
                expectedChildPath,
                includeNodes,
                new HashSet<>()
            );
        }

        if (includeNodes.isEmpty()) {
            return;
        }

        record GroupedIncludeDiag(
            String rawMessage,
            String formattedCoreMessage,
            String attributedMessage,
            String directPath,
            @Nullable VirtualFile directVirtualFile,
            String targetPath,
            @Nullable VirtualFile targetVirtualFile,
            String chainStr,
            int depth,
            boolean isRootCause
        ) {}

        var groupedMap = new LinkedHashMap<PsiElement, List<GroupedIncludeDiag>>();
        var seenMessageKeys = new HashSet<String>();

        var sortedNodes = new ArrayList<>(includeNodes);
        sortedNodes.sort((a, b) -> Integer.compare(b.pathChain().size(), a.pathChain().size()));

        for (var diag : diagnostics) {
            if (diag.severity() != DiagnosticSeverity.ERROR) {
                continue;
            }
            if (!isDiagnosticFromIncludedFile(file, diag, activeNormalizedPath)) {
                continue;
            }
            String diagSourcePath = diag.sourcePath().orElse("");
            if (diagSourcePath.isEmpty()) {
                continue;
            }

            IncludeNode matchedNode = findMatchingIncludeNode(sortedNodes, diagSourcePath);
            if (matchedNode == null) {
                continue;
            }

            var msg = diag.message();
            var formattedDiagMsg = StvnDiagnosticFormatter.formatCore(diag);
            var chainStr = matchedNode.pathChain().size() > 1
                ? String.join(" -> ", matchedNode.pathChain())
                : matchedNode.directPath();
            String attributedMsg = matchedNode.pathChain().size() > 1
                ? "In included module '" + chainStr + "': " + msg
                : "Included file '" + matchedNode.directPath() + "' contains compilation errors: " + msg;

            boolean isRootCause = !msg.contains("Undefined type:")
                && !msg.contains("Unresolved type alias:")
                && !msg.contains("Unknown or undefined type:");
            var parentStmt = PsiTreeUtil.getParentOfType(matchedNode.directInclude(), IncludeStmt.class, false);
            var targetRangeElement = parentStmt != null ? parentStmt : matchedNode.directInclude();
            var dedupKey = "%d:%s".formatted(targetRangeElement.getTextOffset(), attributedMsg);
            if (seenMessageKeys.add(dedupKey)) {
                var directVf = resolveIncludeVirtualFile(file, matchedNode.directFile(), matchedNode.directPath());
                var targetVf = resolveIncludeVirtualFile(file, matchedNode.targetFile(), matchedNode.targetPath());
                groupedMap.computeIfAbsent(targetRangeElement, k -> new ArrayList<>())
                    .add(new GroupedIncludeDiag(
                        msg,
                        formattedDiagMsg,
                        attributedMsg,
                        matchedNode.directPath(),
                        directVf,
                        matchedNode.targetPath(),
                        targetVf,
                        chainStr,
                        matchedNode.pathChain().size(),
                        isRootCause
                    ));
            }
        }

        String moduleImportSuffixHtml = "<br/><code>["
            + StvnDiagnosticFormatter.ENCLAVE_CORE + ':' + StvnDiagnosticCodes.ERR_MODULE_IMPORT + "]</code>";

        for (var entry : groupedMap.entrySet()) {
            var element = entry.getKey();
            var diagList = entry.getValue();
            var targetRange = element.getTextRange();

            diagList.sort((a, b) -> {
                if (a.isRootCause() != b.isRootCause()) {
                    return a.isRootCause() ? -1 : 1;
                }
                return Integer.compare(b.depth(), a.depth());
            });

            var rootCauses = diagList.stream().filter(GroupedIncludeDiag::isRootCause).toList();
            var cascadingErrors = diagList.stream().filter(d -> !d.isRootCause()).toList();
            boolean collapseNoise = !rootCauses.isEmpty() && !cascadingErrors.isEmpty();

            boolean isHierarchy = diagList.stream().anyMatch(d -> d.depth() > 1 || !d.targetPath().equals(d.directPath()));
            String directPath = diagList.get(0).directPath();

            String finalMessage;
            String htmlTooltip;
            if (diagList.size() == 1) {
                var single = diagList.get(0);
                finalMessage = StvnDiagnosticFormatter.formatCore(
                    single.attributedMessage(),
                    "E005",
                    DiagnosticBag.ERR_MODULE_IMPORT
                );
                if (single.depth() > 1) {
                    htmlTooltip = "<html>In included module '" + escapeHtml(single.chainStr()) + "':<br/>"
                        + "&nbsp;&nbsp;&bull; " + escapeHtml(single.formattedCoreMessage())
                        + moduleImportSuffixHtml + "</html>";
                } else {
                    htmlTooltip = "<html>Included file '" + escapeHtml(single.directPath())
                        + "' contains compilation errors:<br/>"
                        + "&nbsp;&nbsp;&bull; " + escapeHtml(single.formattedCoreMessage())
                        + moduleImportSuffixHtml + "</html>";
                }
            } else if (collapseNoise) {
                var primaryRoot = rootCauses.get(0);
                var count = cascadingErrors.size();
                var summarySuffix = "\n(+" + count + " secondary cascading undefined type errors suppressed)";

                if (rootCauses.size() == 1) {
                    String plainMsg = (primaryRoot.depth() > 1
                        ? ("In included module '" + primaryRoot.chainStr() + "': ")
                        : ("Included file '" + primaryRoot.directPath() + "' contains compilation errors: "))
                        + primaryRoot.rawMessage() + summarySuffix;
                    finalMessage = StvnDiagnosticFormatter.formatCore(
                        plainMsg,
                        "E005",
                        DiagnosticBag.ERR_MODULE_IMPORT
                    );

                    var htmlSb = new StringBuilder();
                    if (primaryRoot.depth() > 1) {
                        htmlSb.append("<html>In included module '").append(escapeHtml(primaryRoot.chainStr())).append("':<br/>");
                    } else {
                        htmlSb.append("<html>Included file '").append(escapeHtml(primaryRoot.directPath()))
                            .append("' contains compilation errors:<br/>");
                    }
                    htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(primaryRoot.formattedCoreMessage())).append("<br/><br/>");
                    htmlSb.append("<font color=\"#888888\"><i>(+").append(count)
                        .append(" secondary cascading undefined type errors suppressed)</i></font>")
                        .append(moduleImportSuffixHtml).append("</html>");
                    htmlTooltip = htmlSb.toString();
                } else {
                    var sb = new StringBuilder();
                    if (!isHierarchy) {
                        sb.append("Included file '").append(directPath).append("' contains compilation errors:\n");
                        for (var r : rootCauses) {
                            sb.append("• ").append(r.rawMessage()).append("\n");
                        }
                    } else {
                        sb.append("Included module hierarchy contains compilation errors:\n");
                        for (var r : rootCauses) {
                            sb.append("• [").append(r.depth() > 1 ? r.chainStr() : r.directPath()).append("] ")
                                .append(r.rawMessage()).append("\n");
                        }
                    }
                    sb.append("(+").append(count).append(" secondary cascading undefined type errors suppressed)");
                    finalMessage = StvnDiagnosticFormatter.formatCore(
                        sb.toString().trim(),
                        "E005",
                        DiagnosticBag.ERR_MODULE_IMPORT
                    );

                    var htmlSb = new StringBuilder();
                    if (!isHierarchy) {
                        htmlSb.append("<html>Included file '").append(escapeHtml(directPath))
                            .append("' contains compilation errors:<br/>");
                        for (var r : rootCauses) {
                            htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(r.formattedCoreMessage())).append("<br/>");
                        }
                    } else {
                        htmlSb.append("<html>Included module hierarchy contains compilation errors:<br/>");
                        for (var r : rootCauses) {
                            htmlSb.append("&nbsp;&nbsp;&bull; <i>[")
                                .append(escapeHtml(r.depth() > 1 ? r.chainStr() : r.directPath()))
                                .append("]</i> ").append(escapeHtml(r.formattedCoreMessage())).append("<br/>");
                        }
                    }
                    htmlSb.append("<br/><font color=\"#888888\"><i>(+").append(count)
                        .append(" secondary cascading undefined type errors suppressed)</i></font>")
                        .append(moduleImportSuffixHtml).append("</html>");
                    htmlTooltip = htmlSb.toString();
                }
            } else if (!isHierarchy) {
                var sb = new StringBuilder();
                sb.append("Included file '").append(directPath).append("' contains compilation errors:\n");
                for (var d : diagList) {
                    sb.append("• ").append(d.rawMessage()).append("\n");
                }
                finalMessage = StvnDiagnosticFormatter.formatCore(
                    sb.toString().trim(),
                    "E005",
                    DiagnosticBag.ERR_MODULE_IMPORT
                );

                var htmlSb = new StringBuilder();
                htmlSb.append("<html>Included file '").append(escapeHtml(directPath))
                    .append("' contains compilation errors:<br/>");
                for (var d : diagList) {
                    htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(d.formattedCoreMessage())).append("<br/>");
                }
                htmlSb.append(moduleImportSuffixHtml).append("</html>");
                htmlTooltip = htmlSb.toString();
            } else {
                var sb = new StringBuilder();
                sb.append("Included module hierarchy contains compilation errors:\n");
                for (var d : diagList) {
                    sb.append("• [").append(d.depth() > 1 ? d.chainStr() : d.directPath()).append("] ")
                        .append(d.rawMessage()).append("\n");
                }
                finalMessage = StvnDiagnosticFormatter.formatCore(
                    sb.toString().trim(),
                    "E005",
                    DiagnosticBag.ERR_MODULE_IMPORT
                );

                var htmlSb = new StringBuilder();
                htmlSb.append("<html>Included module hierarchy contains compilation errors:<br/>");
                for (var d : diagList) {
                    htmlSb.append("&nbsp;&nbsp;&bull; <i>[")
                        .append(escapeHtml(d.depth() > 1 ? d.chainStr() : d.directPath()))
                        .append("]</i> ").append(escapeHtml(d.formattedCoreMessage())).append("<br/>");
                }
                htmlSb.append(moduleImportSuffixHtml).append("</html>");
                htmlTooltip = htmlSb.toString();
            }

            var builder = holder.newAnnotation(HighlightSeverity.ERROR, finalMessage)
                .tooltip(htmlTooltip)
                .range(targetRange);
            var attachedFixPaths = new HashSet<String>();
            for (var d : diagList) {
                if (d.directVirtualFile() != null && d.directVirtualFile().isValid() && attachedFixPaths.add(d.directPath())) {
                    builder = builder.withFix(new OpenIncludedFileQuickFix(d.directPath(), d.directVirtualFile()));
                }
                if (d.targetVirtualFile() != null && d.targetVirtualFile().isValid() && attachedFixPaths.add(d.targetPath())) {
                    builder = builder.withFix(new OpenIncludedFileQuickFix(d.targetPath(), d.targetVirtualFile()));
                }
            }
            builder.create();

            if (problems != null && wolf != null && virtualFile != null) {
                int line = 0;
                int col = 0;
                if (document != null
                        && targetRange.getStartOffset() >= 0
                        && targetRange.getStartOffset() <= document.getTextLength()) {
                    line = document.getLineNumber(targetRange.getStartOffset());
                    col = targetRange.getStartOffset() - document.getLineStartOffset(line);
                }
                var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{finalMessage});
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
    }

    private static @Nullable IncludeNode findMatchingIncludeNode(
        List<IncludeNode> sortedNodes,
        String diagSourcePath
    ) {
        // Pass 1: exact normalized compiler path or exact VFS path match
        for (var node : sortedNodes) {
            if ((!node.expectedCompilerPath().isEmpty() && diagSourcePath.equals(node.expectedCompilerPath()))
                    || (!node.targetVfPath().isEmpty() && diagSourcePath.equals(node.targetVfPath()))) {
                return node;
            }
        }
        // Pass 2: staged VFS relative suffix match
        for (var node : sortedNodes) {
            String strippedVf = node.targetVfPath().replaceFirst("^/+", "");
            if (!strippedVf.isEmpty() && diagSourcePath.endsWith("/" + strippedVf)) {
                return node;
            }
            String normTarget = StvnSchemaFlattener.normalizePath(node.targetPath()).replaceFirst("^\\./", "");
            if (!normTarget.isEmpty() && (diagSourcePath.equals(normTarget) || diagSourcePath.endsWith("/" + normTarget))) {
                return node;
            }
        }
        return null;
    }

    private static @Nullable VirtualFile resolveIncludeVirtualFile(
        PsiFile activeFile,
        PsiFile resolvedTarget,
        String relativePath
    ) {
        var vf = resolvedTarget.getVirtualFile();
        if (vf != null) {
            return vf;
        }
        var activeVf = activeFile.getVirtualFile();
        if (activeVf != null && activeVf.getParent() != null) {
            return activeVf.getParent().findFileByRelativePath(relativePath);
        }
        return null;
    }

    private static String resolveRelativeNormalizedPath(String baseFilePath, String relativeIncludePath) {
        if (baseFilePath.isEmpty()) {
            return StvnSchemaFlattener.normalizePath(relativeIncludePath);
        }
        try {
            Path base = Path.of(baseFilePath);
            Path parent = base.getParent();
            Path resolved = parent != null
                ? parent.resolve(relativeIncludePath).normalize()
                : Path.of(relativeIncludePath).normalize();
            return StvnSchemaFlattener.normalizePath(resolved.toString());
        } catch (InvalidPathException ex) {
            int slash = baseFilePath.lastIndexOf('/');
            String dir = slash >= 0 ? baseFilePath.substring(0, slash + 1) : "";
            return StvnSchemaFlattener.normalizePath(dir + relativeIncludePath);
        }
    }

    private static void collectTransitiveIncludes(
        IncludeElement directInclude,
        String directPath,
        PsiFile directFile,
        List<String> currentChain,
        PsiFile currentFile,
        String currentExpectedCompilerPath,
        List<IncludeNode> accumulator,
        Set<String> visited
    ) {
        if (currentChain.size() > 10) {
            return;
        }
        var childIncludes = PsiTreeUtil.findChildrenOfType(currentFile, IncludeElement.class);
        for (var child : childIncludes) {
            var strLit = child.getStringLiteral();
            if (strLit == null) {
                continue;
            }
            var pathText = StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), true).orElse(null);
            if (pathText == null || pathText.isEmpty()) {
                continue;
            }
            var resolved = StvnTypeReference.resolveIncludeFile(strLit);
            if (resolved == null) {
                continue;
            }
            var resolvedVfPath = resolved.getVirtualFile() != null
                ? StvnSchemaFlattener.normalizePath(resolved.getVirtualFile().getPath())
                : resolved.getName();
            if (!visited.add(resolvedVfPath)) {
                continue;
            }

            String nextCompilerPath = resolveRelativeNormalizedPath(currentExpectedCompilerPath, pathText);
            var nextChain = new ArrayList<>(currentChain);
            nextChain.add(pathText);
            accumulator.add(new IncludeNode(
                directInclude,
                directPath,
                directFile,
                nextChain,
                pathText,
                resolved,
                nextCompilerPath,
                resolved.getVirtualFile() != null ? resolvedVfPath : ""
            ));
            collectTransitiveIncludes(
                directInclude,
                directPath,
                directFile,
                nextChain,
                resolved,
                nextCompilerPath,
                accumulator,
                visited
            );
        }
    }

    private static String escapeHtml(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static HighlightSeverity mapSeverity(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> HighlightSeverity.ERROR;
            case WARNING -> HighlightSeverity.WARNING;
            case INFO -> HighlightSeverity.WEAK_WARNING;
            case HINT -> HighlightSeverity.INFORMATION;
        };
    }

    private static void emitPassADisorderedBlockAnchors(
        PsiFile file,
        AnnotationHolder holder,
        List<StvnDiagnostic> activeErrorDiagnostics
    ) {
        var primaryMetaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
        if (primaryMetaEntry != null && StvnMetaTemplateUtil.isPrimaryMetaEntry(primaryMetaEntry)) {
            MetaBlock metaBlock = primaryMetaEntry.getMetaBlock();
            if (metaBlock != null
                    && !PsiTreeUtil.hasErrorElements(metaBlock)
                    && StvnMetadataOrderInspection.isMetaBlockDisordered(metaBlock)) {
                var reorderMetaFix = new StvnReorderMetaEntriesQuickFix(metaBlock);
                var kwMeta = primaryMetaEntry.getNode().findChildByType(StvnTypes.KW_META);
                if (kwMeta != null && kwMeta.getPsi() != null) {
                    emitSilentReorderAnchorIfNoErrorOverlap(holder, kwMeta.getPsi().getTextRange(), activeErrorDiagnostics, reorderMetaFix);
                }
                var lbrace = metaBlock.getNode().findChildByType(StvnTypes.LBRACE);
                if (lbrace != null && lbrace.getPsi() != null) {
                    emitSilentReorderAnchorIfNoErrorOverlap(holder, lbrace.getPsi().getTextRange(), activeErrorDiagnostics, reorderMetaFix);
                }
                var rbrace = metaBlock.getNode().findChildByType(StvnTypes.RBRACE);
                if (rbrace != null && rbrace.getPsi() != null) {
                    emitSilentReorderAnchorIfNoErrorOverlap(holder, rbrace.getPsi().getTextRange(), activeErrorDiagnostics, reorderMetaFix);
                }
                for (MetaElement element : metaBlock.getMetaElementList()) {
                    emitSilentReorderAnchorIfNoErrorOverlap(holder, element.getTextRange(), activeErrorDiagnostics, reorderMetaFix);
                }
            }
        }

        for (MetadataMap metaMap : PsiTreeUtil.findChildrenOfType(file, MetadataMap.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaMap)
                    || !StvnMetadataOrderInspection.isMetadataMapDisordered(metaMap)) {
                continue;
            }
            var reorderMapFix = new StvnReorderMetadataFacetsQuickFix(metaMap);
            PsiElement ownerAnchor = StvnMetadataOrderInspection.resolveMetadataMapOwnerTarget(metaMap);
            if (ownerAnchor != null) {
                emitSilentReorderAnchorIfNoErrorOverlap(holder, ownerAnchor.getTextRange(), activeErrorDiagnostics, reorderMapFix);
            }
            var lbrace = metaMap.getNode().findChildByType(StvnTypes.LBRACE);
            if (lbrace != null && lbrace.getPsi() != null) {
                emitSilentReorderAnchorIfNoErrorOverlap(holder, lbrace.getPsi().getTextRange(), activeErrorDiagnostics, reorderMapFix);
            }
            var rbrace = metaMap.getNode().findChildByType(StvnTypes.RBRACE);
            if (rbrace != null && rbrace.getPsi() != null) {
                emitSilentReorderAnchorIfNoErrorOverlap(holder, rbrace.getPsi().getTextRange(), activeErrorDiagnostics, reorderMapFix);
            }
            for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
                emitSilentReorderAnchorIfNoErrorOverlap(holder, entry.getTextRange(), activeErrorDiagnostics, reorderMapFix);
            }
        }
    }

    private static void emitSilentReorderAnchorIfNoErrorOverlap(
        AnnotationHolder holder,
        @Nullable TextRange anchorRange,
        List<StvnDiagnostic> activeErrorDiagnostics,
        IntentionAction reorderFix
    ) {
        if (anchorRange == null || anchorRange.isEmpty()) {
            return;
        }
        boolean overlapsActiveError = activeErrorDiagnostics.stream().anyMatch(ed ->
            ed.startOffset() < anchorRange.getEndOffset()
                && ed.endOffset() > anchorRange.getStartOffset()
        );
        if (!overlapsActiveError) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(anchorRange)
                .withFix(reorderFix)
                .create();
        }
    }

    private static final Pattern POSIX_META_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private static AnnotationBuilder attachMetaIdentityQuickFixes(
        AnnotationBuilder annotationBuilder,
        PsiFile file,
        String diagnosticId,
        String rawMessage,
        int rawStartOffset,
        TextRange range
    ) {
        String fileName = file.getName();
        int lastDot = fileName.lastIndexOf('.');
        String ext = lastDot > 0 ? fileName.substring(lastDot) : "";
        String[] dotParts = lastDot > 0 ? fileName.substring(0, lastDot).split("\\.", -1) : new String[0];

        // Pass A (0-inspection) E031 (:meta / #kind identity) quick-fixes
        if (DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH.equals(diagnosticId)
                || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH)
                || "E031".equals(diagnosticId)) {
            String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
            if (rawMessage.contains("requires root " + StvnVocabulary.KEYWORD_META + " block")
                    || rawMessage.contains("Mandatory " + StvnVocabulary.KEYWORD_META + " block missing")) {
                annotationBuilder = annotationBuilder.withFix(
                    new StvnMissingMetaInspection.InsertMissingMetaBlockQuickFix(expectedKind)
                );
            } else if (rawMessage.contains("requires " + StvnVocabulary.FACET_KW_META_KIND + " facet")) {
                PsiElement elem = file.findElementAt(rawStartOffset);
                MetaEntry metaEntry = elem instanceof MetaEntry me
                    ? me
                    : PsiTreeUtil.getParentOfType(elem, MetaEntry.class, false);
                if (metaEntry == null) {
                    metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
                }
                if (metaEntry != null) {
                    var kwNode = metaEntry.getNode().findChildByType(StvnTypes.KW_META);
                    PsiElement anchor = kwNode != null ? kwNode.getPsi() : metaEntry;
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnMissingMetaInspection.InsertMissingMetaKindFacetQuickFix(anchor, expectedKind)
                    );
                }
            } else if (rawMessage.contains("does not match file extension")) {
                PsiElement elem = file.findElementAt(rawStartOffset);
                MetaKindFacet kindFacet = elem instanceof MetaKindFacet mkf
                    ? mkf
                    : PsiTreeUtil.getParentOfType(elem, MetaKindFacet.class, false);
                if (kindFacet == null) {
                    kindFacet = PsiTreeUtil.findChildOfType(file, MetaKindFacet.class);
                }
                if (kindFacet != null) {
                    var metaSummary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
                    if (metaSummary != null && metaSummary.mismatchedComponentCount() >= 2) {
                        annotationBuilder = annotationBuilder.withFix(
                            new StvnMatchMetaFileRenameQuickFix(kindFacet, metaSummary.targetFileName())
                        );
                    }
                    var extFix = RenameFileExtensionToKindQuickFix.createIfApplicable(file, kindFacet);
                    if (extFix != null) {
                        annotationBuilder = annotationBuilder.withFix(extFix);
                    }
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnMissingMetaInspection.UpdateMetaKindQuickFix(kindFacet, expectedKind)
                    );
                }
            }
            return annotationBuilder;
        }

        // Pass A (0-inspection) W002 (filename declares domain omitted in :meta) quick-fixes
        if (DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META.equals(diagnosticId)
                || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META)
                || rawMessage.contains("omits " + StvnVocabulary.FACET_KW_META_DOMAIN)) {
            if (dotParts.length == 2 && !dotParts[0].isEmpty() && !dotParts[1].isEmpty() && !dotParts[1].startsWith("stvn_")) {
                PsiElement elem = file.findElementAt(rawStartOffset);
                MetaEntry metaEntry = elem instanceof MetaEntry me
                    ? me
                    : PsiTreeUtil.getParentOfType(elem, MetaEntry.class, false);
                if (metaEntry == null) {
                    metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
                }
                if (metaEntry != null) {
                    var kwNode = metaEntry.getNode().findChildByType(StvnTypes.KW_META);
                    PsiElement anchor = kwNode != null ? kwNode.getPsi() : metaEntry;
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnStemMismatchInspection.InsertMetaDomainQuickFix(anchor, dotParts[1])
                    );
                }
                var omitFix = StvnOmitDomainQuickFix.create(file);
                if (omitFix != null) {
                    annotationBuilder = annotationBuilder.withFix(omitFix);
                }
            }
            return annotationBuilder;
        }

        // Pass A (0-inspection) W001 / W002 (#name or #domain mismatch) quick-fixes
        if (DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH.equals(diagnosticId)
                || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH)
                || DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME.equals(diagnosticId)
                || StvnDiagnosticCodes.matchesSymbolicOrPrefixed(diagnosticId, StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME)) {
            if (dotParts.length == 0 || dotParts[0].isEmpty()) {
                return annotationBuilder;
            }
            PsiElement elem = file.findElementAt(rawStartOffset);
            if (elem == null) {
                elem = file.findElementAt(range.getStartOffset());
            }
            MetaNameFacet nameFacet = elem instanceof MetaNameFacet mnf
                ? mnf
                : PsiTreeUtil.getParentOfType(elem, MetaNameFacet.class, false);
            MetaDomainFacet domainFacet = elem instanceof MetaDomainFacet mdf
                ? mdf
                : PsiTreeUtil.getParentOfType(elem, MetaDomainFacet.class, false);

            var metaSummary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
            if (nameFacet != null && nameFacet.getStringLiteral() != null) {
                StringLiteral stringLit = nameFacet.getStringLiteral();
                var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true);
                if (decodedOpt.isPresent()) {
                    String declared = decodedOpt.get();
                    boolean isValidPosixName = POSIX_META_ID_PATTERN.matcher(declared).matches();
                    boolean hasReservedSlot1 = dotParts.length >= 2 && dotParts[1].startsWith("stvn_");
                    boolean hasAtomicMetaFix = metaSummary != null && metaSummary.mismatchedComponentCount() >= 2;
                    if (hasAtomicMetaFix) {
                        annotationBuilder = annotationBuilder.withFix(
                            new StvnMatchMetaFileRenameQuickFix(nameFacet, metaSummary.targetFileName())
                        );
                    }
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnStemMismatchInspection.AlignStemNameQuickFix(
                            stringLit,
                            dotParts[0],
                            hasAtomicMetaFix ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP
                        )
                    );
                    if (isValidPosixName && !hasReservedSlot1) {
                        StringBuilder targetSb = new StringBuilder(declared);
                        for (int i = 1; i < dotParts.length; i++) {
                            targetSb.append('.').append(dotParts[i]);
                        }
                        targetSb.append(ext);
                        annotationBuilder = annotationBuilder.withFix(
                            new StvnStemMismatchInspection.RenameFileToDeclaredNameQuickFix(
                                stringLit,
                                targetSb.toString(),
                                hasAtomicMetaFix ? PriorityAction.Priority.NORMAL : PriorityAction.Priority.HIGH
                            )
                        );
                    }
                }
            } else if (domainFacet != null && domainFacet.getStringLiteral() != null) {
                StringLiteral stringLit = domainFacet.getStringLiteral();
                var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true);
                if (decodedOpt.isPresent()) {
                    String declared = decodedOpt.get();
                    boolean isSystemFile = StvnStemMismatchInspection.isSystemPreludeOrSubstrateFile(file);
                    boolean isReservedDomain = declared.startsWith("stvn_");
                    boolean isValidPosixDomain = POSIX_META_ID_PATTERN.matcher(declared).matches();
                    boolean emitReservedDomainError = isReservedDomain && !isSystemFile;
                    boolean hasAtomicMetaFix = !emitReservedDomainError
                        && metaSummary != null
                        && metaSummary.mismatchedComponentCount() >= 2;

                    if (dotParts.length == 1) {
                        if (!emitReservedDomainError) {
                            if (hasAtomicMetaFix) {
                                annotationBuilder = annotationBuilder.withFix(
                                    new StvnMatchMetaFileRenameQuickFix(domainFacet, metaSummary.targetFileName())
                                );
                            }
                            if (!isReservedDomain && isValidPosixDomain) {
                                annotationBuilder = annotationBuilder.withFix(
                                    new StvnStemMismatchInspection.IncludeDomainInFilenameQuickFix(
                                        stringLit,
                                        dotParts[0] + "." + declared + ext,
                                        hasAtomicMetaFix ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP
                                    )
                                );
                            }
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnStemMismatchInspection.RemoveMetaDomainQuickFix(domainFacet)
                            );
                        } else {
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnStemMismatchInspection.RemoveMetaDomainQuickFix(domainFacet)
                            );
                        }
                    } else {
                        String stemDomain = dotParts[1];
                        if (hasAtomicMetaFix) {
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnMatchMetaFileRenameQuickFix(domainFacet, metaSummary.targetFileName())
                            );
                        }
                        if (!stemDomain.isEmpty()
                                && !stemDomain.startsWith("stvn_")
                                && POSIX_META_ID_PATTERN.matcher(stemDomain).matches()) {
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnStemMismatchInspection.AlignDomainWithStemQuickFix(
                                    stringLit,
                                    stemDomain,
                                    hasAtomicMetaFix ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP
                                )
                            );
                        }
                        if (!isReservedDomain && isValidPosixDomain) {
                            StringBuilder targetSb = new StringBuilder(dotParts[0]).append('.').append(declared);
                            for (int i = 2; i < dotParts.length; i++) {
                                targetSb.append('.').append(dotParts[i]);
                            }
                            targetSb.append(ext);
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnStemMismatchInspection.IncludeDomainInFilenameQuickFix(
                                    stringLit,
                                    targetSb.toString(),
                                    hasAtomicMetaFix ? PriorityAction.Priority.NORMAL : PriorityAction.Priority.HIGH
                                )
                            );
                        }
                    }
                }
            }
        }
        return annotationBuilder;
    }

    private static @Nullable ListLiteral findMapTargetListLiteral(PsiFile file, TextRange range) {
        var lists = PsiTreeUtil.findChildrenOfType(file, ListLiteral.class);
        for (var listLit : lists) {
            if (listLit.getTextRange().intersects(range)) {
                var valueParent = PsiTreeUtil.getParentOfType(listLit, Value.class);
                if (valueParent != null
                        && !(valueParent.getParent() instanceof ListLiteral)
                        && !(valueParent.getParent() instanceof org.stvnadore.psi.MapLiteral)) {
                    var typeInfo = StvnTypeResolver.resolveBaseTypeInfo(valueParent);
                    if (typeInfo != null && StvnMapStructuralInspection.isMapSchema(typeInfo.getSchema())) {
                        return listLit;
                    }
                }
            }
        }
        return null;
    }

    private static AnnotationBuilder attachCompleteSumVariantQuickFixes(
        AnnotationBuilder annotationBuilder,
        PsiElement targetElem
    ) {
        var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(targetElem);
        if (expectedSchema == null) {
            return annotationBuilder;
        }
        var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
        var schemaToInspect = resolvedNominal != null ? resolvedNominal : expectedSchema;
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor == null || constructor.getSumType() == null) {
            return annotationBuilder;
        }
        var sumType = constructor.getSumType();
        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
        if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER) && innerSchemas.size() >= 2) {
            var rightLabel = StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(1));
            var leftLabel = StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(0));
            annotationBuilder = annotationBuilder.withFix(
                new CompleteSumVariantQuickFix(targetElem, StvnVocabulary.VAL_RIGHT, rightLabel, PriorityAction.Priority.HIGH)
            );
            annotationBuilder = annotationBuilder.withFix(
                new CompleteSumVariantQuickFix(targetElem, StvnVocabulary.VAL_LEFT, leftLabel, PriorityAction.Priority.NORMAL)
            );
        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
            for (int i = 0; i < innerSchemas.size(); i++) {
                var branchLabel = StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(i));
                annotationBuilder = annotationBuilder.withFix(
                    new CompleteSumVariantQuickFix(targetElem, StvnVocabulary.SIGIL_VALUE + (i + 1), branchLabel, PriorityAction.Priority.NORMAL)
                );
            }
        }
        return annotationBuilder;
    }

    private static AnnotationBuilder attachWrapSumVariantQuickFixes(
        AnnotationBuilder annotationBuilder,
        PsiFile file,
        TextRange range,
        String diagnosticId,
        String rawMessage
    ) {
        boolean isAmbiguousSum = DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE.equals(diagnosticId)
            || rawMessage.contains("Ambiguous implicit")
            || rawMessage.contains("matches multiple candidate branches");
        boolean isEitherLeftMissingTag = DiagnosticBag.ERR_EITHER_LEFT_MISSING_TAG.equals(diagnosticId)
            || "EITHER_LEFT_MISSING_TAG".equals(diagnosticId)
            || "E023".equals(diagnosticId)
            || rawMessage.contains("Rule E Violation")
            || rawMessage.contains("explicit #Left tag is required");
        if (!isAmbiguousSum && !isEitherLeftMissingTag) {
            return annotationBuilder;
        }
        var elem = file.findElementAt(range.getStartOffset());
        if (elem == null) {
            return annotationBuilder;
        }
        var valueElement = PsiTreeUtil.getParentOfType(elem, Value.class, false);
        if (valueElement == null) {
            valueElement = PsiTreeUtil.getNonStrictParentOfType(elem, Value.class);
        }
        var targetElement = valueElement != null ? valueElement : elem;
        var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(elem);
        if (expectedSchema == null) {
            return annotationBuilder;
        }
        var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
        var schemaToInspect = resolvedNominal != null ? resolvedNominal : expectedSchema;
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor == null || constructor.getSumType() == null) {
            return annotationBuilder;
        }
        var sumType = constructor.getSumType();
        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
        if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER) && innerSchemas.size() >= 2) {
            var rightLabel = StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(1));
            var leftLabel = StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(0));
            if (isEitherLeftMissingTag) {
                annotationBuilder = annotationBuilder.withFix(
                    new WrapSumVariantQuickFix(targetElement, StvnVocabulary.VAL_LEFT, leftLabel, PriorityAction.Priority.TOP)
                );
            } else {
                annotationBuilder = annotationBuilder.withFix(
                    new WrapSumVariantQuickFix(targetElement, StvnVocabulary.VAL_RIGHT, rightLabel, PriorityAction.Priority.HIGH)
                );
                annotationBuilder = annotationBuilder.withFix(
                    new WrapSumVariantQuickFix(targetElement, StvnVocabulary.VAL_LEFT, leftLabel, PriorityAction.Priority.NORMAL)
                );
            }
        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
            for (int i = 0; i < innerSchemas.size(); i++) {
                var branch = innerSchemas.get(i);
                if (valueElement != null && StvnTypeResolver.matchesSchemaPattern(valueElement, branch)) {
                    var branchLabel = StvnSchemaFormatter.formatCleanSchema(branch);
                    annotationBuilder = annotationBuilder.withFix(
                        new WrapSumVariantQuickFix(targetElement, StvnVocabulary.SIGIL_VALUE + (i + 1), branchLabel)
                    );
                }
            }
        }
        return annotationBuilder;
    }

    private static final class StvnRemoveTrailingSlashIntentionAction
            extends com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement {

        @SafeFieldForPreview
        private final String cleanedTarget;

        StvnRemoveTrailingSlashIntentionAction(@Nullable PsiElement element, String cleanedTarget) {
            super(element);
            this.cleanedTarget = cleanedTarget;
        }

        @Override
        public String getText() {
            return "Remove trailing slash '" + cleanedTarget + "'";
        }

        @Override
        public String getFamilyName() {
            return "Remove trailing slash";
        }

        @Override
        public void invoke(
            Project project,
            PsiFile file,
            @Nullable Editor editor,
            PsiElement startElement,
            PsiElement endElement
        ) {
            var doc = file.getViewProvider().getDocument();
            if (doc != null) {
                var range = startElement.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), cleanedTarget);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    private static final class RemoveDuplicateSectionQuickFix
            extends com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement {

        @SafeFieldForPreview
        private final String sectionKeyword;

        RemoveDuplicateSectionQuickFix(@Nullable PsiElement element, String sectionKeyword) {
            super(element);
            this.sectionKeyword = sectionKeyword;
        }

        @Override
        public String getText() {
            return "Remove duplicate '" + sectionKeyword + "' section";
        }

        @Override
        public String getFamilyName() {
            return "Remove duplicate '" + sectionKeyword + "' section";
        }

        @Override
        public void invoke(
            Project project,
            PsiFile file,
            @Nullable Editor editor,
            PsiElement startElement,
            PsiElement endElement
        ) {
            var doc = file.getViewProvider().getDocument();
            if (doc != null) {
                var range = startElement.getTextRange();
                int deleteStart = range.getStartOffset();
                int deleteEnd = range.getEndOffset();
                CharSequence chars = doc.getCharsSequence();
                while (deleteStart > 0 && (chars.charAt(deleteStart - 1) == ' ' || chars.charAt(deleteStart - 1) == '\t')) {
                    deleteStart--;
                }
                if (deleteEnd < chars.length() && chars.charAt(deleteEnd) == '\r') {
                    deleteEnd++;
                }
                if (deleteEnd < chars.length() && chars.charAt(deleteEnd) == '\n') {
                    deleteEnd++;
                }
                doc.deleteString(deleteStart, deleteEnd);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }
}
