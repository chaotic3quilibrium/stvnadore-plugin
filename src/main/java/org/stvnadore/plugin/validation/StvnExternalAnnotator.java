package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
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
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.quickfix.OpenIncludedFileQuickFix;
import org.stvnadore.plugin.validation.quickfix.RemoveIllegalStructuralSectionQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnChangeReservedDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnDeclareConstantQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnInteractiveRenameQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnOmitDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnRenameDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameReservedPackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnTraitViolationQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedTypeQuickFixProvider;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.SumType;
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
        var expandedEmptyCompositeRanges = new ArrayList<TextRange>();

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
                    || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
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
                if (!isBareSigil) {
                    var expanded = expandEmptyCompositeRange(file, range);
                    if (!expanded.equals(range)) {
                        expandedEmptyCompositeRanges.add(expanded);
                        range = expanded;
                    }
                    if (rawMessage.startsWith("STVN Syntax Error")) {
                        var canonicalEmptyMsg = canonicalEmptyCompositeSyntaxMessage(
                            file.getText().substring(range.getStartOffset(), range.getEndOffset())
                        );
                        if (canonicalEmptyMsg != null) {
                            rawMessage = canonicalEmptyMsg;
                            message = StvnDiagnosticFormatter.formatCore(rawMessage, canonicalCode, diagnosticId);
                        }
                    }
                }
                if (rawMessage.startsWith("STVN Syntax Error")
                        && rawMessage.contains("unexpected token '}'")
                        && !expandedEmptyCompositeRanges.isEmpty()) {
                    var lastEmpty = expandedEmptyCompositeRanges.get(expandedEmptyCompositeRanges.size() - 1);
                    if (lastEmpty.getEndOffset() <= range.getStartOffset()
                            && file.getText().substring(lastEmpty.getEndOffset(), range.getStartOffset()).isBlank()) {
                        continue;
                    }
                }
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
                if (rawMessage.contains("Unfenced block strings are prohibited")) {
                    range = new TextRange(s, Math.min(s + 3, textLength));
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
                        PsiElement enclosingFacet = PsiTreeUtil.getParentOfType(
                            elemAtWarn,
                            org.stvnadore.psi.MetaDomainFacet.class,
                            false
                        );
                        if (enclosingFacet == null) {
                            enclosingFacet = PsiTreeUtil.getParentOfType(
                                elemAtWarn,
                                org.stvnadore.psi.MetaNameFacet.class,
                                false
                            );
                        }
                        if (enclosingFacet == null) {
                            enclosingFacet = PsiTreeUtil.getParentOfType(
                                elemAtWarn,
                                org.stvnadore.psi.MetadataEntry.class,
                                false
                            );
                        }
                        if (enclosingFacet != null && enclosingFacet.getTextRange().getLength() > range.getLength()) {
                            range = enclosingFacet.getTextRange();
                        }
                    }
                }

                boolean isSyntaxError = rawMessage.startsWith("STVN Syntax Error");
                String exactKey = range.getStartOffset() + ":" + range.getEndOffset() + ":" + message;
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
                                new RemoveIllegalStructuralSectionQuickFix(typeEntry, ":type")
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
                                new RemoveIllegalStructuralSectionQuickFix(bodyEntry, ":body")
                            );
                        }
                    }
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
                        if (offendingValKw != null && offendingValKw.getText().startsWith("#")) {
                            annotationBuilder = annotationBuilder.withFix(
                                new StvnDeclareConstantQuickFix(offendingValKw, StvnVocabulary.TYPE_INT, "0")
                            );
                        }
                    }
                }

                // Bare '#' token in sum-type value position
                var elemAtStart = file.findElementAt(s);
                var tokenText = elemAtStart != null ? elemAtStart.getText().trim() : "";
                if ("#".equals(tokenText) || "#".equals(file.getText().substring(s, e).trim())) {
                    if (elemAtStart != null) {
                        annotationBuilder = attachCompleteSumVariantQuickFixes(annotationBuilder, elemAtStart);
                    }
                }

                // Incomplete #preserveIndent trait quick-fixes
                if (rawMessage.contains("Metadata facet #preserveIndent requires an explicit boolean value")
                        || (DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(diagnosticId)
                            && rawMessage.contains("#preserveIndent"))) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    if (targetElem != null) {
                        for (var fix : CompleteTraitFacetQuickFix.createFixes(targetElem)) {
                            annotationBuilder = annotationBuilder.withFix(fix);
                        }
                    }
                }

                // Redundant or non-overridable metadata facet quick-fix (W003 / E040 / cross-hop E008)
                boolean isNonOverridable = DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(diagnosticId)
                        || "NON_OVERRIDABLE_FACET".equals(diagnosticId)
                        || "E040".equals(diagnosticId)
                        || rawMessage.contains("non-overridable");
                boolean isCrossHopAncestorMutex = (DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || "E008".equals(diagnosticId)
                        || rawMessage.contains("mutually exclusive"))
                        && rawMessage.contains("ancestor");
                boolean isRedundant = DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(diagnosticId)
                        || StvnDiagnosticCodes.WARN_REDUNDANT_METADATA_FACET.equals(diagnosticId)
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
                        || StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET.equals(diagnosticId)
                        || rawMessage.contains("is not permitted on"))
                        && !rawMessage.contains("#preserveIndent")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                    if (entry == null && range.getStartOffset() + 1 < textLength) {
                        entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(
                            file.findElementAt(range.getStartOffset() + 1)
                        );
                    }
                    if (entry != null) {
                        String facetName = StvnMetadataFacetInspection.extractFacetName(entry);
                        String kwTag = facetName != null ? "#" + facetName : entry.getText().trim();
                        annotationBuilder = annotationBuilder
                            .withFix(new RemoveRedundantFacetQuickFix(entry, kwTag, "Remove inapplicable facet"));
                    }
                }

                // Mutually exclusive temporal mode/scale quick-fixes (ERR_MUTUALLY_EXCLUSIVE)
                if ((DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE.equals(diagnosticId)
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
                        var metaMap = PsiTreeUtil.getParentOfType(entry, org.stvnadore.psi.MetadataMap.class);
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
                                    .withFix(new RemoveRedundantFacetQuickFix(entry, clickedKw));
                            }
                        }
                    }
                }

                // Missing temporal mode/scale quick-fixes (ERR_MISSING_TEMPORAL_FACET)
                if (DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_DATETIME_MODE_MISSING.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MISSING.equals(diagnosticId)
                        || rawMessage.contains("requires explicit mode facet")
                        || rawMessage.contains("requires a scale facet")) {
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
                        || StvnDiagnosticCodes.ERR_FACET_ORDER_VIOLATION.equals(diagnosticId)
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
                        || StvnDiagnosticCodes.ERR_RESERVED_PACKAGE_PROHIBITED.equals(diagnosticId)
                        || rawMessage.contains("Reserved package namespace prohibited")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    if (targetElem != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnRenameReservedPackageQuickFix(targetElem));
                    }
                }

                // Trailing slash quick-fix (ERR_TRAILING_SLASH_PROHIBITED)
                if (DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED.equals(diagnosticId)
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

                // Reserved domain quick-fixes (E038)
                if (DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
                        || rawMessage.contains("Reserved system domain")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
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
                }

                // Unfenced block string quick-fix (Rule STR-04)
                if (rawMessage.contains("Unfenced block strings are prohibited")) {
                    annotationBuilder = annotationBuilder.withFix(new ConvertToFencedStringIntentionAction(range));
                }

                // Tab character quick-fix (Rule STR-01 / E001)
                if (DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN.equals(diagnosticId)
                        || "TAB_DISCIPLINE_VIOLATION".equals(diagnosticId)
                        || rawMessage.contains("Tab character")) {
                    annotationBuilder = annotationBuilder.withFix(
                        new StvnTabCharacterInspection.ReplaceTabWithSpacesQuickFix()
                    );
                }

                // Inverted interval bounds quick-fix (ERR_INVERTED_RANGE / INVALID_NUMERIC_RANGE)
                if (DiagnosticBag.ERR_INVERTED_RANGE.equals(diagnosticId)
                        || StvnDiagnosticCodes.ERR_INVERTED_RANGE.equals(diagnosticId)
                        || "INVALID_NUMERIC_RANGE".equals(diagnosticId)
                        || rawMessage.contains("effective range is invalid")) {
                    var targetElem = file.findElementAt(range.getStartOffset());
                    var metaMap = PsiTreeUtil.getParentOfType(targetElem, org.stvnadore.psi.MetadataMap.class, false);
                    if (metaMap == null && range.getStartOffset() + 1 < textLength) {
                        metaMap = PsiTreeUtil.getParentOfType(
                            file.findElementAt(range.getStartOffset() + 1),
                            org.stvnadore.psi.MetadataMap.class,
                            false
                        );
                    }
                    var swapFix = StvnDiscreteIntervalInspection.createSwapFixForMetadataMap(metaMap);
                    if (swapFix != null) {
                        annotationBuilder = annotationBuilder.withFix(swapFix);
                    }
                }

                // Interactive bootstrapping intention actions for incomplete clauses
                if (rawMessage.contains("':meta' requires a metadata block")) {
                    annotationBuilder = annotationBuilder.withFix(new InsertMetaBlockIntentionAction(range));
                }
                if (rawMessage.contains("':defs' requires definition block")) {
                    annotationBuilder = annotationBuilder.withFix(new InsertDefinitionBlockIntentionAction(range));
                }
                if (rawMessage.contains("':type' requires a schema type")) {
                    annotationBuilder = annotationBuilder.withFix(new InsertSchemaTypeIntentionAction(range));
                }
                if (rawMessage.contains("':body' requires a value payload")) {
                    annotationBuilder = annotationBuilder.withFix(new InsertDefaultBodyValueIntentionAction(range));
                }
                if (DiagnosticBag.ERR_EMPTY_DEFS_BLOCK.equals(diagnosticId)
                        || rawMessage.contains("requires at least one type definition inside ':defs { ... }'")
                        || rawMessage.contains("Empty definition block ':defs {}' is redundant")) {
                    var fileName = file.getName();
                    if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd") || fileName.endsWith(".stvn_di")) {
                        annotationBuilder = annotationBuilder.withFix(new InsertTypeDefinitionIntentionAction(range));
                    } else {
                        annotationBuilder = annotationBuilder.withFix(new RemoveRedundantDefsBlockIntentionAction(range));
                    }
                }

                annotationBuilder = attachWrapSumVariantQuickFixes(annotationBuilder, file, range, diagnosticId, rawMessage);

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

                annotationBuilder.create();
            }
        }

        // Validate DateTime literals in :body slots governed by degraded schemas that retain a valid mode
        var bodyClause = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (bodyClause != null) {
            for (var strLit : PsiTreeUtil.findChildrenOfType(bodyClause, StringLiteral.class)) {
                if (strLit.getParent() instanceof Value valNode) {
                    var govSchema = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(valNode);
                    if (govSchema != null) {
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
                                var failureOpt = StvnCompiler.validateDateTimeLiteral(strLit.getText().trim(), mode);
                                if (failureOpt.isPresent()) {
                                    var failure = failureOpt.get();
                                    var valRange = valNode.getTextRange();
                                    var formattedMsg = StvnDiagnosticFormatter.formatCore(
                                        failure.message(),
                                        failure.symbolicId()
                                    );
                                    var exactKey = valRange.getStartOffset() + ":" + valRange.getEndOffset() + ":" + formattedMsg;
                                    boolean rangeAlreadyHasError = registeredExactAnnotations.stream()
                                        .anyMatch(k -> k.startsWith(valRange.getStartOffset() + ":" + valRange.getEndOffset() + ":"));
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
                    var fallbackBase = ":Value";
                    if (irSchema instanceof StvnSchema.PoisonedSchema poisoned
                            && poisoned.bestEffortFallback().isPresent()) {
                        fallbackBase = StvnSchemaFormatter.formatCleanIrSchema(poisoned.bestEffortFallback().get());
                    } else {
                        var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new HashSet<>());
                        var td = StvnPsiUtils.resolveTargetTypeDefinition(targetDef);
                        if (td != null && td.getSchemaType() != null) {
                            var resolved = StvnTypeResolver.resolveNominalSchema(td.getSchemaType());
                            if (resolved != null) {
                                fallbackBase = StvnSchemaFormatter.formatCleanSchema(resolved);
                            }
                        }
                    }
                    var degradedMsg = "Schema '" + aliasName
                        + "' has constraint errors at definition site in :defs. Operating on fallback base ('"
                        + fallbackBase + "').";
                    var warnBuilder = holder.newAnnotation(HighlightSeverity.WARNING, degradedMsg)
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
            || StvnDiagnosticCodes.ERR_INVALID_FILENAME_STEM.equals(diagnosticId)
            || DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED.equals(diagnosticId)
            || DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
            || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diagnosticId)
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

        // 3. StvnSemanticAnnotator owns in-flight and committed :Union tag overflow checks
        if (DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW.equals(diagnosticId)
                || (message.startsWith("Union variant tag '") && message.contains("exceeds branch count"))) {
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
            var directPath = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true)
                .orElseGet(() -> stringLit.getText().replace("\"", ""));
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
            var dedupKey = targetRangeElement.getTextOffset() + ":" + attributedMsg;
            if (seenMessageKeys.add(dedupKey)) {
                var directVf = resolveIncludeVirtualFile(file, matchedNode.directFile(), matchedNode.directPath());
                var targetVf = resolveIncludeVirtualFile(file, matchedNode.targetFile(), matchedNode.targetPath());
                groupedMap.computeIfAbsent(targetRangeElement, k -> new ArrayList<>())
                    .add(new GroupedIncludeDiag(
                        msg,
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
                finalMessage = StvnDiagnosticFormatter.formatCore(single.attributedMessage(), "ERR_MODULE_IMPORT");
                if (single.depth() > 1) {
                    htmlTooltip = "<html>In included module '" + escapeHtml(single.chainStr()) + "':<br/>"
                        + "&nbsp;&nbsp;&bull; " + escapeHtml(single.rawMessage()) + "</html>";
                } else {
                    htmlTooltip = "<html>Included file '" + escapeHtml(single.directPath())
                        + "' contains compilation errors:<br/>"
                        + "&nbsp;&nbsp;&bull; " + escapeHtml(single.rawMessage()) + "</html>";
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
                    finalMessage = StvnDiagnosticFormatter.formatCore(plainMsg, "ERR_MODULE_IMPORT");

                    var htmlSb = new StringBuilder();
                    if (primaryRoot.depth() > 1) {
                        htmlSb.append("<html>In included module '").append(escapeHtml(primaryRoot.chainStr())).append("':<br/>");
                    } else {
                        htmlSb.append("<html>Included file '").append(escapeHtml(primaryRoot.directPath()))
                            .append("' contains compilation errors:<br/>");
                    }
                    htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(primaryRoot.rawMessage())).append("<br/><br/>");
                    htmlSb.append("<font color=\"#888888\"><i>(+").append(count)
                        .append(" secondary cascading undefined type errors suppressed)</i></font></html>");
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
                    finalMessage = StvnDiagnosticFormatter.formatCore(sb.toString().trim(), "ERR_MODULE_IMPORT");

                    var htmlSb = new StringBuilder();
                    if (!isHierarchy) {
                        htmlSb.append("<html>Included file '").append(escapeHtml(directPath))
                            .append("' contains compilation errors:<br/>");
                        for (var r : rootCauses) {
                            htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(r.rawMessage())).append("<br/>");
                        }
                    } else {
                        htmlSb.append("<html>Included module hierarchy contains compilation errors:<br/>");
                        for (var r : rootCauses) {
                            htmlSb.append("&nbsp;&nbsp;&bull; <i>[")
                                .append(escapeHtml(r.depth() > 1 ? r.chainStr() : r.directPath()))
                                .append("]</i> ").append(escapeHtml(r.rawMessage())).append("<br/>");
                        }
                    }
                    htmlSb.append("<br/><font color=\"#888888\"><i>(+").append(count)
                        .append(" secondary cascading undefined type errors suppressed)</i></font></html>");
                    htmlTooltip = htmlSb.toString();
                }
            } else if (!isHierarchy) {
                var sb = new StringBuilder();
                sb.append("Included file '").append(directPath).append("' contains compilation errors:\n");
                for (var d : diagList) {
                    sb.append("• ").append(d.rawMessage()).append("\n");
                }
                finalMessage = StvnDiagnosticFormatter.formatCore(sb.toString().trim(), "ERR_MODULE_IMPORT");

                var htmlSb = new StringBuilder();
                htmlSb.append("<html>Included file '").append(escapeHtml(directPath))
                    .append("' contains compilation errors:<br/>");
                for (var d : diagList) {
                    htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(d.rawMessage())).append("<br/>");
                }
                htmlSb.append("</html>");
                htmlTooltip = htmlSb.toString();
            } else {
                var sb = new StringBuilder();
                sb.append("Included module hierarchy contains compilation errors:\n");
                for (var d : diagList) {
                    sb.append("• [").append(d.depth() > 1 ? d.chainStr() : d.directPath()).append("] ")
                        .append(d.rawMessage()).append("\n");
                }
                finalMessage = StvnDiagnosticFormatter.formatCore(sb.toString().trim(), "ERR_MODULE_IMPORT");

                var htmlSb = new StringBuilder();
                htmlSb.append("<html>Included module hierarchy contains compilation errors:<br/>");
                for (var d : diagList) {
                    htmlSb.append("&nbsp;&nbsp;&bull; <i>[")
                        .append(escapeHtml(d.depth() > 1 ? d.chainStr() : d.directPath()))
                        .append("]</i> ").append(escapeHtml(d.rawMessage())).append("<br/>");
                }
                htmlSb.append("</html>");
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
            var pathText = StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), true)
                .orElseGet(() -> strLit.getText().replace("\"", ""));
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

    private static @Nullable ListLiteral findMapTargetListLiteral(PsiFile file, TextRange range) {
        var lists = PsiTreeUtil.findChildrenOfType(file, ListLiteral.class);
        for (var listLit : lists) {
            if (listLit.getTextRange().intersects(range)) {
                var valueParent = PsiTreeUtil.getParentOfType(listLit, Value.class);
                if (valueParent != null) {
                    var typeInfo = StvnTypeResolver.resolveBaseTypeInfo(valueParent);
                    if (typeInfo != null && StvnMapStructuralInspection.isMapSchema(typeInfo.getSchema())) {
                        return listLit;
                    }
                }
            }
        }
        return null;
    }

    private static TextRange expandEmptyCompositeRange(PsiFile file, TextRange range) {
        var text = file.getText();
        var textLength = text.length();
        if (textLength == 0) {
            return range;
        }

        var start = Math.max(0, Math.min(range.getStartOffset(), textLength));
        var element = file.findElementAt(start);
        if (element != null) {
            var product = PsiTreeUtil.getParentOfType(element, ProductType.class);
            if (product != null
                    && product.getText().startsWith(StvnVocabulary.TYPE_TUPLE)
                    && product.getSchemaTypeList().isEmpty()) {
                return product.getTextRange();
            }

            var sum = PsiTreeUtil.getParentOfType(element, SumType.class);
            if (sum != null) {
                var sumText = sum.getText();
                if (sum.getEnumDef() != null && sum.getEnumDef().getValueKeywordList().isEmpty()) {
                    return sum.getTextRange();
                }
                if ((sumText.startsWith(StvnVocabulary.TYPE_UNION)
                        || sumText.startsWith(StvnVocabulary.TYPE_OPTION)
                        || sumText.startsWith(StvnVocabulary.TYPE_EITHER))
                        && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
            }

            var coll = PsiTreeUtil.getParentOfType(element, CollectionType.class);
            if (coll != null && coll.getSchemaTypeList().isEmpty()) {
                return coll.getTextRange();
            }
        }

        if (start < textLength && (text.charAt(start) == ')' || text.charAt(start) == ']')) {
            return expandClosingDelimiterToCompositeKeyword(text, start);
        }
        if (start > 0 && (text.charAt(start - 1) == ')' || text.charAt(start - 1) == ']')) {
            var expanded = expandClosingDelimiterToCompositeKeyword(text, start - 1);
            if (!expanded.equals(new TextRange(start - 1, start))) {
                return expanded;
            }
        }
        if (start < textLength && (text.charAt(start) == '(' || text.charAt(start) == '[')) {
            char openChar = text.charAt(start);
            char targetClose = openChar == '(' ? ')' : ']';
            int closeIdx = start + 1;
            while (closeIdx < textLength && Character.isWhitespace(text.charAt(closeIdx))) {
                closeIdx++;
            }
            if (closeIdx < textLength && text.charAt(closeIdx) == targetClose) {
                return expandOpenClosePairToCompositeKeyword(text, start, closeIdx, range);
            }
        }

        return range;
    }

    private static TextRange expandClosingDelimiterToCompositeKeyword(String text, int closeIdx) {
        int openIdx = closeIdx - 1;
        while (openIdx >= 0 && Character.isWhitespace(text.charAt(openIdx))) {
            openIdx--;
        }
        if (openIdx >= 0) {
            char openChar = text.charAt(openIdx);
            char closeChar = text.charAt(closeIdx);
            if ((openChar == '(' && closeChar == ')') || (openChar == '[' && closeChar == ']')) {
                return expandOpenClosePairToCompositeKeyword(
                    text,
                    openIdx,
                    closeIdx,
                    new TextRange(closeIdx, closeIdx + 1)
                );
            }
        }
        return new TextRange(closeIdx, closeIdx + 1);
    }

    private static TextRange expandOpenClosePairToCompositeKeyword(
        String text,
        int openIdx,
        int closeIdx,
        TextRange fallback
    ) {
        int kwEnd = openIdx;
        while (kwEnd > 0 && Character.isWhitespace(text.charAt(kwEnd - 1))) {
            kwEnd--;
        }
        int kwStart = kwEnd;
        while (kwStart > 0 && isCompositeKeywordChar(text.charAt(kwStart - 1))) {
            kwStart--;
        }
        if (kwStart < kwEnd && text.charAt(kwStart) == ':') {
            var kw = text.substring(kwStart, kwEnd);
            if (isKnownCompositeKeyword(kw)) {
                return new TextRange(kwStart, closeIdx + 1);
            }
        }
        return fallback;
    }

    private static boolean isCompositeKeywordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':';
    }

    private static boolean isKnownCompositeKeyword(String kw) {
        return kw.equals(StvnVocabulary.TYPE_TUPLE)
            || kw.equals(StvnVocabulary.TYPE_UNION)
            || kw.equals(StvnVocabulary.TYPE_ENUM)
            || kw.equals(StvnVocabulary.TYPE_OPTION)
            || kw.equals(StvnVocabulary.TYPE_EITHER)
            || kw.equals(StvnVocabulary.TYPE_SEQ)
            || kw.equals(StvnVocabulary.TYPE_SET)
            || kw.equals(StvnVocabulary.TYPE_MAP);
    }

    private static @Nullable String canonicalEmptyCompositeSyntaxMessage(String spanText) {
        var trimmed = spanText.trim();
        int openParen = trimmed.indexOf('(');
        int openBracket = trimmed.indexOf('[');
        int openIdx;
        char openChar;
        char expectedClose;
        if (openParen >= 0 && (openBracket < 0 || openParen < openBracket)) {
            openIdx = openParen;
            openChar = '(';
            expectedClose = ')';
        } else if (openBracket >= 0) {
            openIdx = openBracket;
            openChar = '[';
            expectedClose = ']';
        } else {
            return null;
        }
        if (trimmed.charAt(trimmed.length() - 1) != expectedClose) {
            return null;
        }
        if (!trimmed.substring(openIdx + 1, trimmed.length() - 1).isBlank()) {
            return null;
        }
        var kw = trimmed.substring(0, openIdx).trim();
        if (openChar == '[' && StvnVocabulary.TYPE_ENUM.equals(kw)) {
            return "STVN Syntax Error: empty enum variant list: "
                + StvnVocabulary.TYPE_ENUM
                + "[] requires at least one value keyword variant";
        }
        if (openChar == '(') {
            if (StvnVocabulary.TYPE_TUPLE.equals(kw)) {
                return "STVN Syntax Error: empty composite argument list: "
                    + StvnVocabulary.TYPE_TUPLE
                    + "() requires at least one schema type";
            }
            if (StvnVocabulary.TYPE_UNION.equals(kw)) {
                return "STVN Syntax Error: empty composite argument list: "
                    + StvnVocabulary.TYPE_UNION
                    + "() requires at least one schema type";
            }
            if (StvnVocabulary.TYPE_SEQ.equals(kw)
                    || StvnVocabulary.TYPE_SET.equals(kw)
                    || StvnVocabulary.TYPE_OPTION.equals(kw)) {
                return "STVN Syntax Error: empty composite argument list: collection requires schema type argument";
            }
            if (StvnVocabulary.TYPE_MAP.equals(kw) || StvnVocabulary.TYPE_EITHER.equals(kw)) {
                return "STVN Syntax Error: insufficient composite arguments: expected 2 schema type arguments";
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
                new CompleteSumVariantQuickFix(targetElem, "#Right", rightLabel, PriorityAction.Priority.HIGH)
            );
            annotationBuilder = annotationBuilder.withFix(
                new CompleteSumVariantQuickFix(targetElem, "#Left", leftLabel, PriorityAction.Priority.NORMAL)
            );
        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
            for (int i = 0; i < innerSchemas.size(); i++) {
                var branchLabel = StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(i));
                annotationBuilder = annotationBuilder.withFix(
                    new CompleteSumVariantQuickFix(targetElem, "#" + (i + 1), branchLabel, PriorityAction.Priority.NORMAL)
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
                        new WrapSumVariantQuickFix(targetElement, "#" + (i + 1), branchLabel)
                    );
                }
            }
        }
        return annotationBuilder;
    }

    private static final class StvnRemoveTrailingSlashIntentionAction
            extends com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement {

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
}
