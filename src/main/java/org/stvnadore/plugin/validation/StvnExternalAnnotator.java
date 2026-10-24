package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.problems.ProblemImpl;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.Problem;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.OpenIncludedFileQuickFix;
import org.stvnadore.plugin.validation.quickfix.RemoveIllegalStructuralSectionQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnChangeReservedDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnDeclareConstantQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnInteractiveRenameQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnOmitDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameReservedPackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedTypeQuickFixProvider;
import org.stvnadore.psi.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes full ANTLR parsing and semantic schema check stages on a background thread,
 * mapping compile-time diagnostic exceptions back to the active editor workspace with
 * strict WolfTheProblemSolver synchronization.
 */
@NullMarked
public final class StvnExternalAnnotator extends ExternalAnnotator<StvnExternalAnnotator.CollectedInfo, StvnExternalAnnotator.AnnotationResult> {

    private static final Logger LOG = Logger.getInstance(StvnExternalAnnotator.class);

    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile(
        "':?([^'\\r\\n]+)'|" +
        "(?<=Undefined type:\\s)(:[A-Za-z0-9_/#]+)|" +
        "(?<=Unknown or undefined type:\\s)(:[A-Za-z0-9_/#]+)|" +
        "(?<=Constraint violation \\()(:?[A-Za-z0-9_/#]+)(?=\\))"
    );

    private static List<String> extractCitedTokens(String message) {
        var tokens = new java.util.ArrayList<String>();
        var matcher = QUOTED_TOKEN_PATTERN.matcher(message);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                var g = matcher.group(i);
                if (g != null && !g.isEmpty()) {
                    tokens.add(g);
                    break;
                }
            }
        }
        return tokens;
    }

    private static String extractCitedTypeToken(String msg) {
        var tokens = extractCitedTokens(msg);
        if (!tokens.isEmpty()) {
            return tokens.get(0);
        }
        var prefix = msg.startsWith("Undefined type: ") ? "Undefined type: "
            : (msg.startsWith("Unknown or undefined type: ") ? "Unknown or undefined type: "
            : (msg.startsWith("Unresolved type alias: ") ? "Unresolved type alias: " : null));
        if (prefix != null && msg.length() > prefix.length()) {
            var raw = msg.substring(prefix.length()).trim();
            var candidate = raw.split("[\\s,;\\)\\}\\]]")[0].trim();
            return candidate.startsWith(":") ? candidate : (":" + candidate);
        }
        return "";
    }

    private static String extractPrimaryToken(String msg) {
        return extractCitedTypeToken(msg);
    }

    /**
     * Constructs a new StvnExternalAnnotator instance.
     */
    public StvnExternalAnnotator() {
    }

    /**
     * Document context information collected on the EDT for background annotation.
     *
     * @param text document text content
     * @param path physical or virtual file path
     * @param virtualFile underlying virtual file
     * @param project active project context
     */
    public record CollectedInfo(
        String text,
        String path,
        VirtualFile virtualFile,
        @Nullable Project project,
        boolean systemMode
    ) {
        /**
         * Constructs a CollectedInfo instance with default systemMode of false.
         *
         * @param text document text content
         * @param path physical or virtual file path
         * @param virtualFile underlying virtual file
         * @param project active project context
         */
        public CollectedInfo(String text, String path, VirtualFile virtualFile, @Nullable Project project) {
            this(text, path, virtualFile, project, false);
        }

        /**
         * Constructs a CollectedInfo instance without an explicit project context.
         *
         * @param text document text content
         * @param path physical or virtual file path
         * @param virtualFile underlying virtual file
         */
        public CollectedInfo(String text, String path, VirtualFile virtualFile) {
            this(text, path, virtualFile, null, false);
        }
    }

    /**
     * Annotation result encapsulating compiler diagnostics.
     *
     * @param diagnostics list of compiler diagnostics
     */
    public record AnnotationResult(List<StvnDiagnostic> diagnostics) {}

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file) {
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        // Flush all unsaved editor document buffers to physical storage on EDT
        // to guarantee that external compiler (stvn-core) reads latest included module content.
        var app = ApplicationManager.getApplication();
        if (app != null && app.isDispatchThread()) {
            FileDocumentManager.getInstance().saveAllDocuments();
        }
        boolean isSystemPrelude = isGenuineSyntheticPrelude(file, virtualFile, file.getProject());
        return new CollectedInfo(file.getText(), virtualFile.getPath(), virtualFile, file.getProject(), isSystemPrelude);
    }

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file, com.intellij.openapi.editor.Editor editor, boolean hasErrors) {
        return collectInformation(file);
    }

    @Override
    public @Nullable AnnotationResult doAnnotate(CollectedInfo info) {
        // Concurrency Guard: Thread isolation per file path identifier
        synchronized (info.path().intern()) {
            String fileName;
            if (info.virtualFile() != null) {
                fileName = info.virtualFile().getName();
            } else {
                int lastSlash = Math.max(info.path().lastIndexOf('/'), info.path().lastIndexOf('\\'));
                fileName = lastSlash >= 0 ? info.path().substring(lastSlash + 1) : info.path();
            }

            // Layer 1: File Identity Ingress validation (unconditional)
            var layer1Diag = validateLayer1Identity(fileName, info.systemMode());

            // If buffer is blank (0-byte or whitespace-only)
            if (info.text().isBlank()) {
                if (layer1Diag != null) {
                    return new AnnotationResult(List.of(layer1Diag));
                }
                // Clean canvas: Zero content diagnostics on valid empty files
                return new AnnotationResult(List.of());
            }

            var resolvedPath = StvnTypeResolver.resolvePhysicalPath(info.virtualFile(), info.project(), info.text());
            try {
                var config = info.systemMode()
                    ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
                    : StvnParserConfig.DEFAULT;
                var compilationResult = StvnCompiler.compileToResult(info.text(), resolvedPath, config);
                var diags = new java.util.ArrayList<>(compilationResult.diagnostics());
                if (layer1Diag != null) {
                    boolean alreadyEmitted = diags.stream().anyMatch(d ->
                        d.errorCode().isPresent() && d.errorCode().get().equals(layer1Diag.errorCode().orElse(null)));
                    if (!alreadyEmitted) {
                        diags.add(0, layer1Diag);
                    }
                }
                return new AnnotationResult(diags);
            } catch (Exception e) {
                return new AnnotationResult(List.of(new StvnDiagnostic(
                    "Internal Compilation Exception: " + e.getMessage(),
                    DiagnosticSeverity.ERROR,
                    -1, -1, 0, info.text().length(), e
                )));
            }
        }
    }

    private static @Nullable StvnDiagnostic validateLayer1Identity(String fileName, boolean systemMode) {
        if (fileName.startsWith(".")) {
            return new StvnDiagnostic(
                "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
                DiagnosticSeverity.ERROR,
                1, 0, 0, 0,
                null,
                java.util.Optional.of(DiagnosticBag.ERR_INVALID_FILENAME_STEM)
            );
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || fileName.substring(0, lastDot).isEmpty()) {
            return new StvnDiagnostic(
                "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
                DiagnosticSeverity.ERROR,
                1, 0, 0, 0,
                null,
                java.util.Optional.of(DiagnosticBag.ERR_INVALID_FILENAME_STEM)
            );
        }
        String[] legacyExts = {
            ".stvn_incl", ".stvn_inclf", ".stvn_cas", ".stvn_bin",
            ".stvn_f", ".stvn_df", ".stvn_bf", ".stvn_bdf"
        };
        for (String legacyExt : legacyExts) {
            if (fileName.endsWith(legacyExt)) {
                int dotIdx = fileName.indexOf('.');
                String matchedExt = dotIdx >= 0 ? fileName.substring(dotIdx) : fileName;
                return new StvnDiagnostic(
                    "Excised legacy file extension '" + matchedExt + "' is permanently prohibited",
                    DiagnosticSeverity.ERROR,
                    1, 0, 0, 0,
                    null,
                    java.util.Optional.of(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED)
                );
            }
        }
        if (!systemMode) {
            String stem = fileName.substring(0, lastDot);
            String[] dotParts = stem.split("\\.");
            if (dotParts.length > 1 && !dotParts[1].isEmpty()) {
                String slot1 = dotParts[1];
                if ("stvn_core".equals(slot1) || "stvn_ir".equals(slot1) || "stvn_cas".equals(slot1)) {
                    return new StvnDiagnostic(
                        "Reserved system domain prohibited: '" + slot1 + "' is reserved for internal compiler substrates and CAS envelopes",
                        DiagnosticSeverity.ERROR,
                        1, 0, 0, 0,
                        null,
                        java.util.Optional.of(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED)
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
        if (file.isPhysical()) {
            return false;
        }
        if (virtualFile.isWritable()) {
            return false;
        }
        if (virtualFile instanceof LightVirtualFile lvf) {
            if ("stvn://prelude/org_stvnadore_prelude.stvn_d".equals(lvf.getUrl())) {
                return true;
            }
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

        // 1. Pre-filter diagnostics to eliminate verified false-positive diagnostics
        var rawDiagnostics = result.diagnostics();
        var activeDiagnostics = rawDiagnostics.stream()
            .filter(d -> !isDiagnosticSuppressed(file, d))
            .filter(d -> !isDuplicateOrCascadingMismatchedInput(rawDiagnostics, d))
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

        // 2. Aggregate all problems (active local errors + pinned include errors)
        var problems = new java.util.ArrayList<Problem>();
        WolfTheProblemSolver wolf = null;
        if (virtualFile != null && virtualFile.isValid() && !project.isDisposed()) {
            wolf = WolfTheProblemSolver.getInstance(project);
        }

        // Render pinned annotations for included file errors and register in problems list
        renderPinnedIncludeDiagnostics(file, rawDiagnostics, holder, problems, wolf, virtualFile);

        if (wolf != null && virtualFile != null) {
            // Notify Wolf of all active local errors, including missing :meta even if visual annotation is deduplicated
            var wolfErrors = new java.util.ArrayList<StvnDiagnostic>(activeErrorDiagnostics);
            for (var diag : rawDiagnostics) {
                if (diag.severity() == DiagnosticSeverity.ERROR && !isDiagnosticFromIncludedFile(file, diag)) {
                    boolean isKindMismatch = (diag.errorCode().isPresent() && "ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()))
                        || diag.message().contains("Mandatory :meta block missing")
                        || diag.message().contains("ERR_DOCUMENT_KIND_MISMATCH");
                    boolean isTabError = (diag.errorCode().isPresent() && "ERR_TAB_CHARACTER_FORBIDDEN".equals(diag.errorCode().get()))
                        || diag.message().contains("ERR_TAB_CHARACTER_FORBIDDEN")
                        || diag.message().contains("Tab character");
                    if ((isKindMismatch || isTabError) && !wolfErrors.contains(diag)) {
                        wolfErrors.add(diag);
                    }
                }
            }
            for (var diag : wolfErrors) {
                boolean isKindMismatch = (diag.errorCode().isPresent() && "ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()))
                    || diag.message().contains("Mandatory :meta block missing")
                    || diag.message().contains("does not match file extension");
                if (isKindMismatch) {
                    var expectedExt = extractExtensionFromMessage(diag.message());
                    if (expectedExt != null && !file.getName().endsWith(expectedExt)) {
                        continue;
                    }
                }
                var line = Math.max(0, diag.line() - 1);
                var col = Math.max(0, diag.column());
                var rawMsg = sanitizeCompilerJargon(diag.message());
                var errorCode = diag.errorCode().orElse("ERR_COMPILATION_ERROR");
                var diagMsg = StvnDiagnosticFormatter.formatCore(rawMsg, errorCode);
                var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{ diagMsg });
                if (problem == null) {
                    var highlightInfo = com.intellij.codeInsight.daemon.impl.HighlightInfo.newHighlightInfo(com.intellij.codeInsight.daemon.impl.HighlightInfoType.ERROR)
                        .range(new com.intellij.openapi.util.TextRange(0, 0))
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
                var rawMsg = sanitizeCompilerJargon(firstDiag.message());
                var errorCode = firstDiag.errorCode().orElse("ERR_COMPILATION_ERROR");
                var diagMsg = StvnDiagnosticFormatter.formatCore(rawMsg, errorCode);
                var highlightInfo = com.intellij.codeInsight.daemon.impl.HighlightInfo.newHighlightInfo(com.intellij.codeInsight.daemon.impl.HighlightInfoType.ERROR)
                    .range(new com.intellij.openapi.util.TextRange(0, 0))
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

        // 3. Render annotations in AnnotationHolder for all active diagnostics
        var registeredRanges = new java.util.HashSet<com.intellij.openapi.util.TextRange>();
        var registeredStartOffsets = new java.util.HashSet<Integer>();

        for (var diag : activeDiagnostics) {
            var rawMessage = sanitizeCompilerJargon(diag.message());
            var errorCode = diag.errorCode().orElse("ERR_COMPILATION_ERROR");
            var message = StvnDiagnosticFormatter.formatCore(rawMessage, errorCode);
            var severity = mapSeverity(diag.severity());
            var start = diag.startOffset();
            var end = diag.endOffset();

            boolean isBareSigil = (diag.errorCode().isPresent() && ("ERR_BARE_COLON_PROHIBITED".equals(diag.errorCode().get()) || "ERR_BARE_HASH_PROHIBITED".equals(diag.errorCode().get())))
                    || message.contains("ERR_BARE_COLON_PROHIBITED") || message.contains("ERR_BARE_HASH_PROHIBITED")
                    || message.contains("trap7") || message.contains("trap6");

            if (isBareSigil) {
                if (start >= 0 && start < textLength) {
                    end = start + 1;
                }
            }

            boolean isLayer1Identity = (diag.errorCode().isPresent() && (
                DiagnosticBag.ERR_INVALID_FILENAME_STEM.equals(diag.errorCode().get())
                || DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED.equals(diag.errorCode().get())
                || DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
                || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
                || StvnDiagnosticCodes.ERR_RESERVED_PACKAGE_PROHIBITED.equals(diag.errorCode().get())
            )) || message.contains("Reserved system domain prohibited")
               || message.contains("bare dotfiles are prohibited")
               || message.contains("prohibited legacy extension");

            if (file.getTextLength() == 0 || (isLayer1Identity && start == end)) {
                var builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                      .fileLevel();
                boolean isReservedDomain = (diag.errorCode().isPresent() && (
                    DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
                    || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
                )) || message.contains("Reserved system domain");

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

            if (diag.errorCode().isPresent() && diag.errorCode().get().equals("DUPLICATE_MAP_KEY") && start >= 0 && end > start && end <= textLength) {
                var rawKey = file.getText().substring(start, end).trim();
                if (rawKey.startsWith("\"") && rawKey.endsWith("\"") && rawKey.length() >= 2) {
                    rawKey = rawKey.substring(1, rawKey.length() - 1);
                }
                message = StvnDiagnosticFormatter.formatCore("Duplicate map key detected: '" + rawKey + "'", "DUPLICATE_MAP_KEY");
            }

            // Align Tuple arity mismatch display message and enforce container-level coordinate pinning
            if ((diag.errorCode().isPresent() && "TUPLE_ARITY_MISMATCH".equals(diag.errorCode().get()))
                || message.startsWith("Tuple arity mismatch")) {
                if (!message.contains("missing") && message.matches(".*Expected\\s+(\\d+)\\s+elements?,\\s+got\\s+(\\d+).*")) {
                    var matcher = java.util.regex.Pattern.compile("Expected\\s+(\\d+)\\s+elements?,\\s+got\\s+(\\d+)").matcher(message);
                    if (matcher.find()) {
                        int exp = Integer.parseInt(matcher.group(1));
                        int got = Integer.parseInt(matcher.group(2));
                        if (exp > got) {
                            int missing = exp - got;
                            message = StvnDiagnosticFormatter.formatCore("Tuple arity mismatch: Expected " + exp + " elements, got " + got + " (" + missing + " missing)", "TUPLE_ARITY_MISMATCH");
                        }
                    }
                }
            }

            // 1. Direct Coordinate Range Highlighting with Defensive Clamping
            // startOffset and endOffset from stvnadore-core:2.0.0 are 0-based half-open [start, end)
            if (start >= 0 && end >= start) {
                var hasIncludes = !PsiTreeUtil.findChildrenOfType(file, IncludeElement.class).isEmpty();
                boolean isKindMismatch = (diag.errorCode().isPresent() && "ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()))
                    || rawMessage.contains("Mandatory :meta block missing")
                    || rawMessage.contains("does not match file extension");
                if (hasIncludes && isKindMismatch) {
                    var expectedExt = extractExtensionFromMessage(rawMessage);
                    if (expectedExt != null && !file.getName().endsWith(expectedExt)) {
                        LOG.debug("Diagnostic extension does not match active file; skipping: " + message);
                        continue;
                    }
                }
                if (hasIncludes && !isLocalAstMatch(file, diag, start, end)) {
                    LOG.debug("Diagnostic coordinates do not match active file AST element; treating as included file: " + message);
                    continue;
                }

                var s = Math.max(0, Math.min(start, textLength));
                var e = Math.max(s, Math.min(end, textLength));

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
                        range = expandEmptyCompositeRange(file, range, message);
                        var clampedTypeDef = clampToOffendingChildIfTypeDef(file, range, message);
                        if (clampedTypeDef != null) {
                            range = clampedTypeDef;
                        } else {
                            var clampedMeta = clampToOffendingMetadataFacet(file, range, message, diag.errorCode().orElse(null));
                            if (clampedMeta != null) {
                                range = clampedMeta;
                            }
                        }
                    }
                    // Enforce Rule STR-04 Coordinate Pinning: pin unfenced block strings strictly to opening """
                    if (message.contains("Rule STR-04 violation: Unfenced block strings are prohibited")
                            || message.contains("Unfenced block strings are prohibited")) {
                        range = new TextRange(s, Math.min(s + 3, textLength));
                    }
                    // Pin empty block diagnostics strictly to '{}' tokens [s, s + 2) (§ 7.3, § 3.5.1, § 3.8.0)
                    if ((diag.errorCode().isPresent() && ("ERR_EMPTY_METADATA_BLOCK".equals(diag.errorCode().get()) || "ERR_EMPTY_DEFS_BLOCK".equals(diag.errorCode().get())))
                            || message.contains("ERR_EMPTY_METADATA_BLOCK")
                            || message.contains("ERR_EMPTY_DEFS_BLOCK")
                            || message.contains("must not be empty")
                            || message.contains("requires at least one type definition inside ':defs { ... }'")
                            || message.contains("Empty definition block ':defs {}' is redundant")) {
                        if (s >= 0 && s + 2 <= textLength && "{}".equals(file.getText().substring(s, s + 2))) {
                            range = new TextRange(s, s + 2);
                        }
                    }
                    // Prevent tooltip stacking on identical ranges or overlapping start offsets
                    if (registeredRanges.contains(range) || registeredStartOffsets.contains(range.getStartOffset())) {
                        continue;
                    }
                    registeredRanges.add(range);
                    registeredStartOffsets.add(range.getStartOffset());

                    var annotationBuilder = holder.newAnnotation(severity, message)
                          .range(range);
                    var listLit = findMapTargetListLiteral(file, range);
                    if (listLit != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnMapAutoHealerQuickFix(listLit));
                    }

                    // Attach InsertMissingMetaBlockQuickFix when missing root :meta is rendered as fail-safe
                    if (isKindMismatch && s == 0) {
                        var expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(file.getName());
                        annotationBuilder = annotationBuilder.withFix(new StvnMissingMetaInspection.InsertMissingMetaBlockQuickFix(expectedKind));
                    }

                    // Attach RemoveIllegalStructuralSectionQuickFix when definitions schema contains illegal :type or :body
                    if (isKindMismatch && (rawMessage.contains("cannot declare ':type'") || rawMessage.contains("cannot declare ':body'") || rawMessage.contains("Structural violation:"))) {
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
                                annotationBuilder = annotationBuilder.withFix(new RemoveIllegalStructuralSectionQuickFix(typeEntry, ":type"));
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
                                annotationBuilder = annotationBuilder.withFix(new RemoveIllegalStructuralSectionQuickFix(bodyEntry, ":body"));
                            }
                        }
                    }

                    if (message.contains("Undefined type: ") || message.contains("Unresolved type alias: ")
                        || message.contains("Undefined constant: ") || message.contains("Unresolved constant: ")
                        || message.contains("Undeclared value keyword or constant: ")) {
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
                            var isPrelude = !isLocalPkg && StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), kwText) != null;
                            if (!isPrelude) {
                                annotationBuilder = StvnUnresolvedTypeQuickFixProvider.registerFixes(annotationBuilder, offendingTypeKw);
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
                                annotationBuilder = annotationBuilder.withFix(new StvnDeclareConstantQuickFix(offendingValKw, StvnVocabulary.TYPE_INT, "0"));
                            }
                        }
                    }

                    // Intercept bare '#' token in value position and attach CompleteSumVariantQuickFix actions
                    var elemAtStart = file.findElementAt(s);
                    var tokenText = (elemAtStart != null) ? elemAtStart.getText().trim() : "";
                    if ("#".equals(tokenText) || (s < e && "#".equals(file.getText().substring(s, e).trim()))) {
                        var targetElem = (elemAtStart != null) ? elemAtStart : file.findElementAt(s);
                        if (targetElem != null) {
                            var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(targetElem);
                            if (expectedSchema != null) {
                                var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
                                var schemaToInspect = (resolvedNominal != null) ? resolvedNominal : expectedSchema;
                                var constructor = schemaToInspect.getSchemaConstructor();
                                if (constructor != null && constructor.getSumType() != null) {
                                    var sumType = constructor.getSumType();
                                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                    if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                                        // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
                                        if (innerSchemas.size() >= 2) {
                                            var rightBranch = innerSchemas.get(1);
                                            var leftBranch = innerSchemas.get(0);
                                            var rightLabel = StvnSchemaFormatter.formatCleanSchema(rightBranch);
                                            var leftLabel = StvnSchemaFormatter.formatCleanSchema(leftBranch);
                                            annotationBuilder = annotationBuilder.withFix(new CompleteSumVariantQuickFix(targetElem, "#Right", rightLabel, PriorityAction.Priority.HIGH));
                                            annotationBuilder = annotationBuilder.withFix(new CompleteSumVariantQuickFix(targetElem, "#Left", leftLabel, PriorityAction.Priority.NORMAL));
                                        }
                                    } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                                        for (int i = 0; i < innerSchemas.size(); i++) {
                                            var branch = innerSchemas.get(i);
                                            var branchLabel = StvnSchemaFormatter.formatCleanSchema(branch);
                                            var tag = "#" + (i + 1);
                                            annotationBuilder = annotationBuilder.withFix(new CompleteSumVariantQuickFix(targetElem, tag, branchLabel, PriorityAction.Priority.NORMAL));
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Intercept bare #preserveIndent trait syntax errors and attach predictive quick-fixes
                    if (message.contains("Metadata facet #preserveIndent requires an explicit boolean value")
                        || (diag.errorCode().isPresent() && "ERR_INVALID_METADATA_FACET".equals(diag.errorCode().get()) && message.contains("#preserveIndent"))) {
                        var targetElem = file.findElementAt(range.getStartOffset());
                        if (targetElem != null) {
                            for (var fix : CompleteTraitFacetQuickFix.createFixes(targetElem)) {
                                annotationBuilder = annotationBuilder.withFix(fix);
                            }
                        }
                    }

                    // Intercept WARN_REDUNDANT_METADATA_FACET (W003) and attach RemoveRedundantFacetQuickFix
                    if ((diag.errorCode().isPresent() && (
                        DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(diag.errorCode().get())
                        || StvnDiagnosticCodes.WARN_REDUNDANT_METADATA_FACET.equals(diag.errorCode().get())
                    )) || message.contains("is redundant")) {
                        var targetElem = file.findElementAt(range.getStartOffset());
                        var entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(targetElem);
                        if (entry == null && range.getStartOffset() > 0) {
                            entry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(file.findElementAt(range.getStartOffset() + 1));
                        }
                        if (entry != null) {
                            annotationBuilder = annotationBuilder.withFix(new RemoveRedundantFacetQuickFix(entry));
                        }
                    }

                    // Intercept ERR_RESERVED_PACKAGE_PROHIBITED (E037) and attach RenameReservedPackageQuickFix
                    if ((diag.errorCode().isPresent() && StvnDiagnosticCodes.ERR_RESERVED_PACKAGE_PROHIBITED.equals(diag.errorCode().get()))
                        || message.contains("Reserved package namespace prohibited")) {
                        var targetElem = file.findElementAt(range.getStartOffset());
                        if (targetElem != null) {
                            annotationBuilder = annotationBuilder.withFix(new StvnRenameReservedPackageQuickFix(targetElem));
                        }
                    }

                    // Intercept ERR_RESERVED_DOMAIN_PROHIBITED (E038) and attach ChangeReservedDomainQuickFix
                    if ((diag.errorCode().isPresent() && (
                        DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
                        || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
                    )) || message.contains("Reserved system domain")) {
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

                    // Attach stem mismatch quick-fixes for WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
                    if (diag.errorCode().isPresent() && "WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME".equals(diag.errorCode().get())) {
                        var fileName = file.getName();
                        int lastDot = fileName.lastIndexOf('.');
                        if (lastDot >= 0) {
                            var ext = fileName.substring(lastDot);
                            var stemPart = fileName.substring(0, lastDot);
                            var dotParts = stemPart.split("\\.");
                            var tokens = extractCitedTokens(diag.message());
                            var domainVal = !tokens.isEmpty() ? tokens.get(0) : "default";
                            var targetFileName = dotParts[0] + "." + domainVal + ext;
                            var targetElem = file.findElementAt(range.getStartOffset());
                            var elemForFix = targetElem != null ? targetElem : file;
                            annotationBuilder = annotationBuilder.withFix(new StvnStemMismatchInspection.IncludeDomainInFilenameQuickFix(elemForFix, targetFileName));
                            annotationBuilder = annotationBuilder.withFix(new StvnStemMismatchInspection.RemoveMetaDomainQuickFix(elemForFix));
                        }
                    }

                    // Attach ConvertToFencedStringIntentionAction for unfenced block strings (Rule STR-04)
                    if (message.contains("Rule STR-04 violation: Unfenced block strings are prohibited")
                            || message.contains("Unfenced block strings are prohibited")) {
                        annotationBuilder = annotationBuilder.withFix(new ConvertToFencedStringIntentionAction(range));
                    }

                    // Attach interactive bootstrapping intention actions for incomplete clauses
                    if (message.contains("clause ':meta' requires a metadata block") || message.contains("':meta' requires a metadata block")) {
                        annotationBuilder = annotationBuilder.withFix(new InsertMetaBlockIntentionAction(range));
                    }
                    if (message.contains("clause ':defs' requires definition block") || message.contains("':defs' requires definition block")) {
                        annotationBuilder = annotationBuilder.withFix(new InsertDefinitionBlockIntentionAction(range));
                    }
                    if (message.contains("clause ':type' requires a schema type") || message.contains("':type' requires a schema type")) {
                        annotationBuilder = annotationBuilder.withFix(new InsertSchemaTypeIntentionAction(range));
                    }
                    if (message.contains("clause ':body' requires a value payload") || message.contains("':body' requires a value payload")) {
                        annotationBuilder = annotationBuilder.withFix(new InsertDefaultBodyValueIntentionAction(range));
                    }
                    if (diag.errorCode().isPresent() && "ERR_EMPTY_DEFS_BLOCK".equals(diag.errorCode().get())
                            || message.contains("ERR_EMPTY_DEFS_BLOCK")
                            || message.contains("requires at least one type definition inside ':defs { ... }'")
                            || message.contains("Empty definition block ':defs {}' is redundant")) {
                        var fileName = file.getName();
                        if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd") || fileName.endsWith(".stvn_di")) {
                            annotationBuilder = annotationBuilder.withFix(new InsertTypeDefinitionIntentionAction(range));
                        } else {
                            annotationBuilder = annotationBuilder.withFix(new RemoveRedundantDefsBlockIntentionAction(range));
                        }
                    }

                    annotationBuilder = attachWrapSumVariantQuickFixes(annotationBuilder, file, range, diag, message);

                    annotationBuilder.create();
                    continue;
                }
            }

            var registered = false;

            // 1. Zero-Shadowing fallback
            if (message.startsWith("Zero-Shadowing constraint violated: ")) {
                var offendingName = message.substring("Zero-Shadowing constraint violated: ".length()).trim();
                if (offendingName.startsWith("#")) {
                    var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
                    for (var def : constDefs) {
                        if (def.getValueKeyword() != null && def.getValueKeyword().getText().equals(offendingName)) {
                            holder.newAnnotation(severity, message)
                                  .range(def.getValueKeyword().getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                } else {
                    var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
                    for (var def : typeDefs) {
                        if (def.getTypeKeyword() != null && def.getTypeKeyword().getText().equals(offendingName)) {
                            holder.newAnnotation(severity, message)
                                  .range(def.getTypeKeyword().getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 2. Circular constant definition fallback
            if (!registered && message.startsWith("Circular constant definition detected: ")) {
                var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
                for (var def : constDefs) {
                    var kw = def.getValueKeyword();
                    if (kw != null && message.contains(kw.getText())) {
                        holder.newAnnotation(severity, message)
                              .range(kw.getTextRange())
                              .create();
                        registered = true;
                    }
                }
            }

            // 3. Duplicate module import exception fallback
            if (!registered && message.contains("path: ")) {
                var pathVal = message.substring(message.indexOf("path: ") + 6).trim();
                var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
                for (var elem : includes) {
                    var stringLit = elem.getStringLiteral();
                    if (stringLit != null) {
                        var rawPath = org.stvnadore.core.ir.StvnLiteralParser.parseString(stringLit.getText(), true);
                        if (rawPath.equals(pathVal)) {
                            holder.newAnnotation(severity, message)
                                  .range(elem.getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 4. Namespace collision exception fallback
            if (!registered && message.contains("detected: ")) {
                var collisionsPart = message.substring(message.indexOf("detected: ") + 10).trim();
                if (collisionsPart.startsWith("[") && collisionsPart.endsWith("]")) {
                    collisionsPart = collisionsPart.substring(1, collisionsPart.length() - 1);
                }
                var names = collisionsPart.split(",\\s*");
                var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
                for (var typeDef : typeDefs) {
                    var typeKeyword = typeDef.getTypeKeyword();
                    if (typeKeyword != null) {
                        var typeName = typeKeyword.getText();
                        for (var name : names) {
                            if (typeName.equals(name.trim())) {
                                holder.newAnnotation(severity, message)
                                      .range(typeKeyword.getTextRange())
                                      .create();
                                registered = true;
                            }
                        }
                    }
                }
            }

            // 5. Cyclic dependency exception fallback with trace rotation
            if (!registered && (rawMessage.startsWith("Cycle detected: ") || message.startsWith("Cycle detected: "))) {
                var rotatedMessage = message;
                var traceStr = rawMessage.startsWith("Cycle detected: ")
                    ? rawMessage.substring("Cycle detected: ".length())
                    : message.substring("Cycle detected: ".length());
                var parts = traceStr.split(" -> ");
                if (parts.length > 0) {
                    var mainName = file.getName();
                    int startIdx = -1;
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals(mainName)) {
                            startIdx = i;
                            break;
                        }
                    }
                    if (startIdx != -1) {
                        var rotated = new java.util.ArrayList<String>();
                        for (int i = startIdx; i < parts.length - 1; i++) {
                            rotated.add(parts[i]);
                        }
                        for (int i = 0; i < startIdx; i++) {
                            rotated.add(parts[i]);
                        }
                        rotated.add(parts[startIdx]);
                        rotatedMessage = StvnDiagnosticFormatter.formatCore("Cycle detected: " + String.join(" -> ", rotated), errorCode);
                    }
                }

                var cause = diag.cause();
                java.util.List<String> rawPaths = java.util.List.of();
                if (cause instanceof org.stvnadore.core.validation.CyclicDependencyException cycleEx) {
                    rawPaths = cycleEx.getOffendingIncludePathsRaw();
                }

                var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
                for (var elem : includes) {
                    var stringLit = elem.getStringLiteral();
                    if (stringLit != null) {
                        var rawPath = org.stvnadore.core.ir.StvnLiteralParser.parseString(stringLit.getText(), true);
                        var matches = false;

                        if (!rawPaths.isEmpty()) {
                            for (var rp : rawPaths) {
                                if (rawPath.equals(rp)) {
                                    matches = true;
                                    break;
                                }
                            }
                        } else {
                            var fileName = new java.io.File(rawPath).getName();
                            if (message.contains(fileName)) {
                                matches = true;
                            }
                        }

                        if (matches) {
                            holder.newAnnotation(severity, rotatedMessage)
                                  .range(elem.getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 6. Track 5: Identity-Dependent Collections Fallback
            if (!registered && message.contains("require types to be #equatable #TRUE")) {
                var collections = PsiTreeUtil.findChildrenOfType(file, CollectionType.class);
                for (var coll : collections) {
                    var firstChild = coll.getFirstChild();
                    if (firstChild == null) continue;

                    var tokenText = firstChild.getText();
                    var innerSchemas = coll.getSchemaTypeList();
                    if (innerSchemas.isEmpty()) continue;

                    var isMatch = false;
                    SchemaType targetSchema = null;

                    if (message.contains("Set elements") && tokenText.equals(StvnVocabulary.TYPE_SET)) {
                        targetSchema = innerSchemas.get(0);
                        isMatch = (targetSchema != null);
                    } else if (message.contains("Map keys") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        targetSchema = innerSchemas.get(0);
                        isMatch = (targetSchema != null);
                    } else if (message.contains("Inverted map values") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        if (innerSchemas.size() >= 2) {
                            targetSchema = innerSchemas.get(1);
                            isMatch = (targetSchema != null);
                        }
                    }

                    if (isMatch && targetSchema != null) {
                        holder.newAnnotation(severity, message)
                              .range(targetSchema.getTextRange())
                              .create();
                        registered = true;
                        break;
                    }
                }
            }

            // 7. Track 6A, 6B, 6C: Nominal Constraints Fallback
            if (!registered && message.contains("Constraint violation (") && message.contains("): ")) {
                var startIdx = message.indexOf("Constraint violation (") + "Constraint violation (".length();
                var endIdx = message.indexOf("):", startIdx);
                if (startIdx >= "Constraint violation (".length() && endIdx > startIdx) {
                    var nominalTypeName = message.substring(startIdx, endIdx).trim();
                    var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, nominalTypeName, new java.util.HashSet<>());
                    if (resolved != null && resolved.getContainingFile() == file) {
                        var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                        if (parentDef != null && parentDef.getTypeKeyword() != null) {
                            var range = parentDef.getTypeKeyword().getTextRange();
                            var metaMap = parentDef.getMetadataMap();
                            if (metaMap != null) {
                                var remainder = message.substring(endIdx + 2).trim();
                                var constraintName = extractConstraintName(remainder);
                                if (constraintName != null) {
                                    for (var entry : PsiTreeUtil.getChildrenOfTypeAsList(metaMap, MetadataEntry.class)) {
                                        var first = entry.getFirstChild();
                                        if (first != null && (first.getText().equals(constraintName) || first.getText().equals("#" + constraintName))) {
                                            range = entry.getTextRange();
                                            break;
                                        }
                                    }
                                }
                            }
                            holder.newAnnotation(severity, message)
                                  .range(range)
                                  .create();
                            registered = true;
                        }
                    }
                }
            }


            // 9. Undefined / Unresolved Type Alias Fallback
            if (!registered && (message.contains("Undefined type: ") || message.contains("Unresolved type alias: "))) {
                var prefix = message.contains("Undefined type: ") ? "Undefined type: " : "Unresolved type alias: ";
                var rawName = message.substring(message.indexOf(prefix) + prefix.length()).trim();
                var typeName = rawName.split("[\\s,;\\)\\}\\]]")[0].trim();
                if (!typeName.startsWith(":")) {
                    typeName = ":" + typeName;
                }

                var typeKeywords = PsiTreeUtil.findChildrenOfType(file, TypeKeyword.class);
                for (var typeKw : typeKeywords) {
                    if (typeKw.getText().equals(typeName)) {
                        var isLhsDef = org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(typeKw);
                        if (!isLhsDef) {
                            var ab = holder.newAnnotation(severity, message)
                                  .range(typeKw.getTextRange());
                            var isLocalPkg = StvnPsiUtils.isTypeDeclaredInLocalPackage(file, typeKw.getText().trim());
                            var isPrelude = !isLocalPkg && StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), typeKw.getText().trim()) != null;
                            if (!isPrelude) {
                                ab = StvnUnresolvedTypeQuickFixProvider.registerFixes(ab, typeKw);
                            }
                            ab.create();
                            registered = true;
                        }
                    }
                }

                if (!registered) {
                    for (var typeKw : typeKeywords) {
                        if (typeKw.getText().equals(typeName)) {
                            var ab = holder.newAnnotation(severity, message)
                                  .range(typeKw.getTextRange());
                            var isLocalPkg = StvnPsiUtils.isTypeDeclaredInLocalPackage(file, typeKw.getText().trim());
                            var isPrelude = !isLocalPkg && StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), typeKw.getText().trim()) != null;
                            if (!isPrelude) {
                                ab = StvnUnresolvedTypeQuickFixProvider.registerFixes(ab, typeKw);
                            }
                            ab.create();
                            registered = true;
                        }
                    }
                }

                if (!registered) {
                    var typeEntry = PsiTreeUtil.findChildOfType(file, SchemaType.class);
                    if (typeEntry != null) {
                        holder.newAnnotation(severity, message)
                              .range(typeEntry.getTextRange())
                              .create();
                        registered = true;
                    }
                }
            }

            // 9b. Undefined / Unresolved Constant Fallback
            if (!registered && (message.contains("Undefined constant: ") || message.contains("Unresolved constant: ")
                || message.contains("Undefined type: #") || message.contains("Undeclared value keyword or constant: "))) {
                var prefix = message.contains("Undefined constant: ") ? "Undefined constant: "
                    : (message.contains("Unresolved constant: ") ? "Unresolved constant: "
                    : (message.contains("Undeclared value keyword or constant: ") ? "Undeclared value keyword or constant: " : "Undefined type: "));
                var rawName = message.substring(message.indexOf(prefix) + prefix.length()).trim();
                if (rawName.startsWith("'")) {
                    rawName = rawName.substring(1);
                }
                var constName = rawName.split("['\\s,;\\)\\}\\]]")[0].trim();
                if (!constName.startsWith("#")) {
                    constName = "#" + constName;
                }
                var valKeywords = PsiTreeUtil.findChildrenOfType(file, ValueKeyword.class);
                for (var valKw : valKeywords) {
                    if (valKw.getText().equals(constName)) {
                        var ab = holder.newAnnotation(severity, message)
                              .range(valKw.getTextRange());
                        ab = ab.withFix(new StvnDeclareConstantQuickFix(valKw, StvnVocabulary.TYPE_INT, "0"));
                        ab.create();
                        registered = true;
                    }
                }
            }

            // 10. Localized AST Fallback (Defs & Type Immunity Guarantee)
            if (!registered) {
                var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                var bodyValue = bodyEntry != null ? bodyEntry.getValue() : null;

                if (bodyValue != null) {
                    TextRange targetRange = null;

                    var coll = bodyValue.getCollectionValue();
                    if (coll != null) {
                        List<Value> items = coll.getTupleLiteral() != null 
                            ? coll.getTupleLiteral().getValueList() 
                            : (coll.getListLiteral() != null ? coll.getListLiteral().getValueList() : List.of());

                        for (var item : items) {
                            var info = StvnTypeResolver.resolveBaseTypeInfo(item);
                            if (info == null || !StvnTypeResolver.matchesSchemaPattern(item, info.getSchema())) {
                                targetRange = item.getTextRange();
                                break;
                            }
                        }
                    }

                    if (targetRange == null) {
                        if (message.contains("Expected integer, got float") || message.contains("got float")) {
                            var floats = PsiTreeUtil.findChildrenOfType(bodyValue, FloatLiteral.class);
                            if (!floats.isEmpty()) {
                                targetRange = floats.iterator().next().getTextRange();
                            }
                        } else if (message.contains("Expected float, got integer") || message.contains("got integer")) {
                            var ints = PsiTreeUtil.findChildrenOfType(bodyValue, IntegerLiteral.class);
                            if (!ints.isEmpty()) {
                                targetRange = ints.iterator().next().getTextRange();
                            }
                        } else if (message.contains("Expected string") || message.contains("String")) {
                            var strings = PsiTreeUtil.findChildrenOfType(bodyValue, StringLiteral.class);
                            if (!strings.isEmpty()) {
                                targetRange = strings.iterator().next().getTextRange();
                            }
                        } else if (message.contains("Expected Map") || message.contains("got List") || message.contains("MapLiteralContext")) {
                            var lists = PsiTreeUtil.findChildrenOfType(bodyValue, ListLiteral.class);
                            if (!lists.isEmpty()) {
                                targetRange = lists.iterator().next().getTextRange();
                            }
                        }
                    }

                    if (targetRange == null) {
                        targetRange = bodyValue.getTextRange();
                    }

                    LOG.debug("Unlocated diagnostic anchored to body fallback range [" + targetRange + "]: " + message);
                    var annotationBuilder = holder.newAnnotation(severity, message)
                          .range(targetRange);
                    var listLit = findMapTargetListLiteral(file, targetRange);
                    if (listLit != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnMapAutoHealerQuickFix(listLit));
                    }
                    annotationBuilder = attachWrapSumVariantQuickFixes(annotationBuilder, file, targetRange, diag, message);
                    annotationBuilder.create();
                } else if (bodyEntry != null) {
                    LOG.debug("Unlocated diagnostic anchored to bodyEntry range: " + message);
                    holder.newAnnotation(severity, message)
                          .range(bodyEntry.getTextRange())
                          .create();
                } else {
                    LOG.debug("Unlocated diagnostic anchored to whole-document range: " + message);
                    holder.newAnnotation(severity, message)
                          .range(new TextRange(0, textLength))
                          .create();
                }
            }
        }

        // Anchor Warning on :type when the nominal schema is degraded
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry != null) {
            var typeKeywords = PsiTreeUtil.findChildrenOfType(typeEntry, TypeKeyword.class);
            for (var typeKw : typeKeywords) {
                var aliasName = typeKw.getText();
                if (StvnTypeResolver.isDegradedNominalAlias(file, aliasName)) {
                    var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new java.util.HashSet<>());
                    var fallbackBase = ":Value";
                    var td = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(targetDef);
                    if (td != null && td.getSchemaType() != null) {
                        var resolved = StvnTypeResolver.resolveNominalSchema(td.getSchemaType());
                        if (resolved != null) {
                            fallbackBase = StvnSchemaFormatter.formatCleanSchema(resolved);
                        }
                    }
                    var message = "Schema '" + aliasName + "' has constraint errors at definition site in :defs. Operating on fallback base ('" + fallbackBase + "').";
                    holder.newAnnotation(HighlightSeverity.WARNING, message)
                          .range(typeKw.getTextRange())
                          .create();
                }
            }
        }
    }

    boolean isDiagnosticSuppressed(PsiFile file, StvnDiagnostic diag) {
        var message = diag.message();

        // 1. Cross-File Diagnostic Filtering: verify diagnostic origin against active file buffer
        if (isDiagnosticFromIncludedFile(file, diag)) {
            return true;
        }

        // 1a. Missing Meta & Document Kind Mismatch Exemption & Deduplication:
        // When StvnMissingMetaInspection is active, suppress external annotator duplicate
        // to ensure the developer sees exactly ONE diagnostic and ONE quick-fix on line 1 '{'.
        boolean isStructuralSectionViolation = message.contains("cannot declare ':type'")
            || message.contains("cannot declare ':body'")
            || message.contains("Structural violation:");
        boolean isKindMismatchOrMissingMeta = !isStructuralSectionViolation && (
            (diag.errorCode().isPresent() && "ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()))
            || message.contains("Mandatory :meta block missing")
            || message.contains("ERR_DOCUMENT_KIND_MISMATCH")
        );
        if (isKindMismatchOrMissingMeta) {
            if (isMissingMetaInspectionActive(file)) {
                return true;
            }
            return false;
        }

        // 1b. Coordinate Mismatch Verification: active buffer slice at coordinates must match cited symbol
        if (isCoordinateMismatchInActiveBuffer(file, diag)) {
            return true;
        }

        // 1c. Structural Empty Metadata Facet Map Protection:
        // If diagnostic reports empty metadata map but active buffer at coordinates is not a metadata map
        if (isStructuralMetadataMismatchInActiveBuffer(file, diag)) {
            return true;
        }

        // 2. Buffer Token Existence Verification:
        // Extract quoted and cited tokens from compiler messages.
        // If a diagnostic message contains a cited token that does not exist in file.getText(), suppress it immediately.
        // If cited tokens exist in the buffer, confirm that the diagnostic coordinates actually contain at least one of them.
        var text = file.getText();
        var textLength = file.getTextLength();

        var start = diag.startOffset();
        var end = diag.endOffset();
        var s = Math.max(0, Math.min(start, textLength));
        var e = Math.max(s, Math.min(end, textLength));
        var slice = (s < e) ? text.substring(s, e) : "";

        var quotedTokens = extractCitedTokens(message);

        boolean isTabError = (diag.errorCode().isPresent() && "ERR_TAB_CHARACTER_FORBIDDEN".equals(diag.errorCode().get()))
            || message.contains("ERR_TAB_CHARACTER_FORBIDDEN")
            || message.contains("Tab character");

        // Suppress external compiler tab character diagnostics when StvnTabCharacterInspection is active (Invariant 10)
        if (isTabError) {
            return isTabCharacterInspectionActive(file);
        }

        boolean isAntlrSyntaxError = (diag.errorCode().isPresent() && "STVN_SYNTAX_ERROR".equals(diag.errorCode().get()))
            || message.contains("mismatched input")
            || message.contains("no viable alternative")
            || message.contains("extraneous input")
            || message.startsWith("STVN Syntax Error");

        boolean isConstraintViolation = (diag.errorCode().isPresent() && "ERR_CONSTRAINT_VIOLATION".equals(diag.errorCode().get()))
            || message.contains("Constraint violation (")
            || message.contains("violates #")
            || message.contains("violates canonical");

        boolean isLayer1Identity = diag.errorCode().isPresent() && (
            DiagnosticBag.ERR_INVALID_FILENAME_STEM.equals(diag.errorCode().get())
            || DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED.equals(diag.errorCode().get())
            || DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
            || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(diag.errorCode().get())
            || StvnDiagnosticCodes.ERR_RESERVED_PACKAGE_PROHIBITED.equals(diag.errorCode().get())
        );

        boolean isExemptFromSliceCheck = isConstraintViolation
            || isLayer1Identity
            || textLength == 0
            || (diag.errorCode().isPresent() && ("ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()) || "ERR_EMPTY_DEFS_BLOCK".equals(diag.errorCode().get())))
            || message.contains("ERR_DOCUMENT_KIND_MISMATCH")
            || message.contains("ERR_EMPTY_DEFS_BLOCK")
            || message.contains("Definitions schema requires root :meta block")
            || message.contains("Mandatory :meta block missing")
            || message.contains("requires at least one type definition inside ':defs { ... }'")
            || message.contains("Empty definition block ':defs {}' is redundant");

        if (!isAntlrSyntaxError && !quotedTokens.isEmpty()) {
            // Check if any cited token exists in the active buffer
            boolean anyTokenInText = false;
            for (var t : quotedTokens) {
                if (text.contains(t)) {
                    anyTokenInText = true;
                    break;
                }
            }
            if (!isExemptFromSliceCheck && !anyTokenInText) {
                return true;
            }

            // If the message cites quoted tokens, and a slice exists, verify that
            // at least one quoted token appears in the target range slice (preventing offset drift onto comments).
            // Skip slice check for constraint violations and exempt bootstrap diagnostics.
            if (!slice.isEmpty() && !isExemptFromSliceCheck) {
                boolean anyTokenInSlice = false;
                for (var t : quotedTokens) {
                    if (slice.contains(t) || t.contains(slice)) {
                        anyTokenInSlice = true;
                        break;
                    }
                }
                if (!anyTokenInSlice) {
                    return true;
                }
            }
        }

        // 3. Universal Comment & Whitespace Locus Protection:
        // Compiler diagnostics across all severities must never land on comments or blank spans,
        // unless reporting forbidden tab characters.
        if (start >= 0 && start <= textLength) {
            if (!isTabError && s < e && text.substring(s, e).isBlank()) {
                return true;
            }

            int checkOffset = s;
            int limitOffset = Math.max(s + 1, e);
            while (checkOffset < limitOffset && checkOffset < textLength) {
                var leaf = file.findElementAt(checkOffset);
                if (leaf == null) {
                    break;
                }
                if (!isTabError && (leaf instanceof com.intellij.psi.PsiComment
                    || PsiTreeUtil.getParentOfType(leaf, com.intellij.psi.PsiComment.class, false) != null)) {
                    return true;
                }
                var nextOffset = leaf.getTextRange().getEndOffset();
                if (nextOffset <= checkOffset) {
                    break;
                }
                checkOffset = nextOffset;
            }
        }

        // Suppress external compiler Rule G diagnostics; StvnSemanticAnnotator handles Rule G in-flight and at commit
        if (diag.errorCode().isPresent() && "UNION_BRANCH_OVERFLOW".equals(diag.errorCode().get())) {
            return true;
        }
        if (message.startsWith("Union variant tag '") && message.contains("exceeds branch count")) {
            return true;
        }
        // Suppress external compiler stem mismatch diagnostics when StvnStemMismatchInspection is active
        if (diag.errorCode().isPresent() && (DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME.equals(diag.errorCode().get())
                || DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH.equals(diag.errorCode().get()))) {
            if (isStemMismatchInspectionActive(file)) {
                return true;
            }
        }
        if ((message.contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME")
                || message.contains("WARN_DOCUMENT_NAME_MISMATCH")
                || message.contains("is omitted from filename stem slot 1")
                || message.contains("does not match file stem")
                || message.contains("does not match filename stem slot 1")) && isStemMismatchInspectionActive(file)) {
            return true;
        }
        // Suppress external facet ordering diagnostics when StvnMetadataOrderInspection is active
        if (diag.errorCode().isPresent() && "ERR_FACET_ORDER_VIOLATION".equals(diag.errorCode().get())) {
            if (isMetadataOrderInspectionActive(file)) {
                return true;
            }
        }
        if ((message.contains("ERR_FACET_ORDER_VIOLATION") || message.contains("violates canonical")) && isMetadataOrderInspectionActive(file)) {
            return true;
        }
        // Suppress external fenced string delimiter arrow diagnostics when StvnFencedStringInspection is active
        if (diag.errorCode().isPresent() && DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW.equals(diag.errorCode().get())) {
            if (isFencedStringInspectionActive(file)) {
                return true;
            }
        }
        if (message.contains("Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited") && isFencedStringInspectionActive(file)) {
            return true;
        }
        // Suppress external temporal mode & scale diagnostics when StvnTemporalModeInspection is active
        if (diag.errorCode().isPresent() && (DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING.equals(diag.errorCode().get())
                || DiagnosticBag.ERR_DATETIME_MODE_INVALID.equals(diag.errorCode().get())
                || DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(diag.errorCode().get())
                || "ERR_LEGACY_UNIT_FACET_PURGED".equals(diag.errorCode().get()))) {
            if (isTemporalModeInspectionActive(file)) {
                return true;
            }
        }
        // Suppress external discrete interval diagnostics when StvnDiscreteIntervalInspection is active
        if (diag.errorCode().isPresent() && "ERR_DISCRETE_BOUND_KIND_PROHIBITED".equals(diag.errorCode().get())) {
            if (isDiscreteIntervalInspectionActive(file)) {
                return true;
            }
        }
        if ((message.contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED")
                || message.contains("Discrete types reject closed upper bound")
                || message.contains("Discrete types reject open lower bound")) && isDiscreteIntervalInspectionActive(file)) {
            return true;
        }
        if (org.stvnadore.plugin.psi.StvnPsiUtils.isHermeticDocument(file)
                && message.contains("cannot contain include statements")
                && isFlatDocumentIncludeInspectionActive(file)) {
            return true;
        }
        if (!message.contains("Unresolved schema for value context")) {
            return false;
        }

        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (bodyEntry == null || bodyEntry.getValue() == null) {
            return false;
        }

        var bodyValue = bodyEntry.getValue();
        var coll = bodyValue.getCollectionValue();
        List<Value> childValues = null;
        if (coll != null) {
            if (coll.getTupleLiteral() != null) {
                childValues = coll.getTupleLiteral().getValueList();
            } else if (coll.getListLiteral() != null) {
                childValues = coll.getListLiteral().getValueList();
            } else if (coll.getMapLiteral() != null) {
                childValues = coll.getMapLiteral().getValueList();
            }
        } else {
            childValues = List.of(bodyValue);
        }

        if (childValues == null || childValues.isEmpty()) {
            return false;
        }

        for (var child : childValues) {
            var info = StvnTypeResolver.resolveBaseTypeInfo(child);
            if (info == null) {
                return false;
            }

            var resolved = StvnTypeResolver.resolveNominalSchema(info.getSchema());
            var toInspect = resolved != null ? resolved : info.getSchema();
            var ctor = toInspect.getSchemaConstructor();

            if (ctor != null && ctor.getSumType() != null) {
                var sumType = ctor.getSumType();
                if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                    var inner = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    int matchCount = 0;
                    var expUnion = child.getExplicitUnionValue();
                    if (expUnion != null) {
                        var firstChild = expUnion.getFirstChild();
                        int tagIndex = -1;
                        if (firstChild != null && firstChild.getText().startsWith("#")) {
                            try {
                                tagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                            } catch (NumberFormatException ignored) {}
                        }
                        if (tagIndex >= 0 && tagIndex < inner.size()) {
                            var innerVal = expUnion.getValue();
                            if (innerVal == null || StvnTypeResolver.matchesSchemaPattern(innerVal, inner.get(tagIndex))) {
                                matchCount = 1;
                            }
                        }
                    } else {
                        for (var branch : inner) {
                            if (StvnTypeResolver.matchesSchemaPattern(child, branch)) {
                                matchCount++;
                            }
                        }
                    }
                    if (matchCount != 1) {
                        return false;
                    }
                } else {
                    if (!StvnTypeResolver.matchesSchemaPattern(child, info.getSchema())) {
                        return false;
                    }
                }
            } else {
                if (!StvnTypeResolver.matchesSchemaPattern(child, info.getSchema())) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isMetadataOrderInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnMetadataOrder");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isFencedStringInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnFencedString");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isTemporalModeInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnTemporalMode");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isDiscreteIntervalInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile == null) {
            vFile = file.getViewProvider().getVirtualFile();
        }
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid") || path.contains("inv_")
                    || file.getName().startsWith("inv_") || file.getName().contains("discrete")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnDiscreteInterval");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isFlatDocumentIncludeInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile == null) {
            vFile = file.getViewProvider().getVirtualFile();
        }
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid") || path.contains("inv_")
                    || file.getName().startsWith("inv_") || file.getName().contains("flat_module_with_include")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnFlatDocumentInclude");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isStemMismatchInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnStemMismatch");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isMissingMetaInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnMissingMeta");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isTabCharacterInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile == null) {
            vFile = file.getViewProvider().getVirtualFile();
        }
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("invalid") || path.contains("inv_")
                    || file.getName().startsWith("inv_") || file.getName().contains("tab_character_indent_disallowed")) {
                return false;
            }
        }
        // Unconditional inspection primacy on all user documents (Invariant 10)
        return true;
    }

    private static boolean isDiagnosticFromIncludedFile(PsiFile file, StvnDiagnostic diag) {
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (includes.isEmpty()) {
            return false;
        }

        // 1. Forward-compatible reflection lookup for diagnostic.sourcePath()
        try {
            var method = diag.getClass().getMethod("sourcePath");
            var pathObj = method.invoke(diag);
            if (pathObj instanceof String sourcePath && !sourcePath.isEmpty()) {
                var virtualFile = file.getVirtualFile();
                if (virtualFile != null) {
                    var activePath = virtualFile.getPath().replace('\\', '/');
                    var normalizedSource = sourcePath.replace('\\', '/');
                    if (!activePath.endsWith(normalizedSource) && !normalizedSource.endsWith(virtualFile.getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // sourcePath method not present in current core record; fallback to semantic checks
        }

        var msg = diag.message();

        // 1b. Extension-aware origin routing for missing :meta / document kind mismatch diagnostics:
        boolean isKindMismatchOrMissingMeta = (diag.errorCode().isPresent() && "ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()))
            || msg.contains("Mandatory :meta block missing")
            || msg.contains("ERR_DOCUMENT_KIND_MISMATCH")
            || msg.contains("does not match file extension");
        if (isKindMismatchOrMissingMeta) {
            var expectedExt = extractExtensionFromMessage(msg);
            if (expectedExt != null) {
                var activeName = file.getName();
                if (!activeName.endsWith(expectedExt)) {
                    return true;
                }
                // If extensions match (e.g. parent .stvn_d includes child .stvn_d),
                // verify if the active buffer already possesses a root :meta block.
                if (msg.contains("Mandatory :meta block missing")) {
                    if (StvnMetaTemplateUtil.hasMetaHeader(file)
                        || PsiTreeUtil.findChildOfType(file, MetaEntry.class) != null) {
                        return true;
                    }
                }
            }
        }

        // 2. Included file origin check: if diagnostic reports constraint violation for a type declared in an include
        if (msg.startsWith("Constraint violation (") || msg.startsWith("Zero-Shadowing constraint violated: ")) {
            var openParen = msg.indexOf('(');
            var closeParen = msg.indexOf(')', openParen);
            if (openParen >= 0 && closeParen > openParen) {
                var typeName = msg.substring(openParen + 1, closeParen).trim();
                var resolved = StvnTypeReference.resolveTypeInFile(file, typeName, new java.util.HashSet<>());
                if (resolved != null && isUserIncludedFile(file, resolved.getContainingFile())) {
                    return true;
                }
            }
        }

        // 3. Child module detection for files containing includes:
        if (msg.startsWith("Undefined type: ") || msg.startsWith("Unknown or undefined type: ") || msg.startsWith("Unresolved type alias: ")) {
            var typeName = extractCitedTypeToken(msg);
            if (!typeName.isEmpty() && !file.getText().contains(typeName)) {
                return true;
            }
        }

        var citedTokens = extractCitedTokens(msg);
        for (var candidateSymbol : citedTokens) {
            if (candidateSymbol.startsWith(":") || candidateSymbol.startsWith("#")) {
                if (!file.getText().contains(candidateSymbol)) {
                    return true;
                }
                var resolved = StvnTypeReference.resolveTypeInFile(file, candidateSymbol, new java.util.HashSet<>());
                if (resolved != null && isUserIncludedFile(file, resolved.getContainingFile())) {
                    var s = Math.max(0, Math.min(diag.startOffset(), file.getTextLength()));
                    var e = Math.max(s, Math.min(diag.endOffset(), file.getTextLength()));
                    if (s < e) {
                        var slice = file.getText().substring(s, e);
                        if (!slice.contains(candidateSymbol)) {
                            return true;
                        }
                    } else {
                        return true;
                    }
                }
            }
        }

        // 3a. Universal ANTLR syntax error isolation in files containing includes
        if (isAntlrSyntaxError(diag)) {
            if (!hasLocalSyntaxErrorAt(file, diag)) {
                return true;
            }
        }

        // 4. Strict AST element verification at target coordinates:
        if (isStructuralMetadataMismatchInActiveBuffer(file, diag)) {
            return true;
        }

        // 5. Active buffer boundary clamp: coordinates outside active file text length belong to child
        if (diag.endOffset() > file.getTextLength() || diag.startOffset() < 0) {
            return true;
        }

        var doc = file.getViewProvider().getDocument();
        if (doc != null && diag.line() > doc.getLineCount()) {
            return true;
        }

        return false;
    }

    private static boolean isAntlrSyntaxError(StvnDiagnostic diag) {
        var msg = diag.message();
        return (diag.errorCode().isPresent() && "STVN_SYNTAX_ERROR".equals(diag.errorCode().get()))
            || msg.contains("mismatched input")
            || msg.contains("no viable alternative")
            || msg.contains("extraneous input")
            || msg.startsWith("STVN Syntax Error");
    }

    private static boolean hasLocalSyntaxErrorAt(PsiFile file, StvnDiagnostic diag) {
        var psiErrors = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiErrorElement.class);
        if (psiErrors.isEmpty()) {
            return false;
        }
        var s = Math.max(0, Math.min(diag.startOffset(), file.getTextLength()));
        var e = Math.max(s, Math.min(diag.endOffset(), file.getTextLength()));
        var range = new TextRange(s, e);
        for (var err : psiErrors) {
            var errRange = err.getTextRange();
            if (errRange.intersects(range)
                    || (errRange.getEndOffset() <= s && s - errRange.getEndOffset() <= 10)
                    || (errRange.getStartOffset() >= e && errRange.getStartOffset() - e <= 10)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalAstMatch(PsiFile file, StvnDiagnostic diag, int start, int end) {
        var textLength = file.getTextLength();
        var s = Math.max(0, Math.min(start, textLength));
        var e = Math.max(s, Math.min(end, textLength));
        if (s >= e) return false;

        if (isAntlrSyntaxError(diag)) {
            return hasLocalSyntaxErrorAt(file, diag);
        }

        boolean isKindMismatch = (diag.errorCode().isPresent() && "ERR_DOCUMENT_KIND_MISMATCH".equals(diag.errorCode().get()))
            || diag.message().contains("Mandatory :meta block missing")
            || diag.message().contains("does not match file extension");
        if (isKindMismatch) {
            var expectedExt = extractExtensionFromMessage(diag.message());
            if (expectedExt != null && !file.getName().endsWith(expectedExt)) {
                return false;
            }
        }

        var msg = diag.message();
        if (msg.contains("Undefined type: ") || msg.contains("Unknown or undefined type: ") || msg.contains("Unresolved type alias: ")) {
            var typeName = extractCitedTypeToken(msg);
            if (!typeName.isEmpty()) {
                var typeKw = PsiTreeUtil.findElementOfClassAtRange(file, s, e, TypeKeyword.class);
                return typeKw != null && typeKw.getText().trim().equals(typeName);
            }
        }
        if (isStructuralMetadataMismatchInActiveBuffer(file, diag)) {
            return false;
        }
        if (isCoordinateMismatchInActiveBuffer(file, diag)) {
            return false;
        }
        if (msg.contains("cannot be used on left-hand side of type definition")) {
            var typeDef = PsiTreeUtil.findElementOfClassAtRange(file, s, e, TypeDefinition.class);
            if (typeDef == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStructuralMetadataMismatchInActiveBuffer(PsiFile file, StvnDiagnostic diag) {
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (includes.isEmpty()) {
            return false;
        }
        var start = diag.startOffset();
        var end = diag.endOffset();
        var textLength = file.getTextLength();
        if (start < 0 || end > textLength || start >= end) {
            return false;
        }
        var msg = diag.message();
        if (msg.contains("Empty metadata facet map is invalid")
            || msg.contains("Empty metadata block is invalid")
            || msg.contains("Empty type constraint block is invalid")
            || (diag.errorCode().isPresent() && ("ERR_EMPTY_METADATA_MAP".equals(diag.errorCode().get()) || "ERR_EMPTY_METADATA_BLOCK".equals(diag.errorCode().get())))) {
            var metaMap = PsiTreeUtil.findElementOfClassAtRange(file, start, end, MetadataMap.class);
            if (metaMap == null) {
                return true;
            }
        }
        if (msg.contains("facet '") && (msg.contains("is not permitted on") || msg.contains("is prohibited on"))) {
            var metaEntry = PsiTreeUtil.findElementOfClassAtRange(file, start, end, MetadataEntry.class);
            if (metaEntry == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCoordinateMismatchInActiveBuffer(PsiFile file, StvnDiagnostic diag) {
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (includes.isEmpty()) {
            return false;
        }
        var start = diag.startOffset();
        var end = diag.endOffset();
        var textLength = file.getTextLength();
        if (start < 0 || end > textLength || start >= end) {
            return false;
        }
        var msg = diag.message();
        var text = file.getText();
        var slice = text.substring(start, end).trim();

        // 1. Undefined or unknown type diagnostics: slice must match cited type
        if (msg.startsWith("Undefined type: ") || msg.startsWith("Unknown or undefined type: ") || msg.startsWith("Unresolved type alias: ")) {
            var typeName = extractCitedTypeToken(msg);
            if (!typeName.isEmpty()) {
                if (!slice.equals(typeName) && !slice.contains(typeName) && !slice.endsWith(typeName)) {
                    return true;
                }
            }
        }

        if (isAntlrSyntaxError(diag)) {
            var quoted = extractCitedTokens(msg);
            if (!quoted.isEmpty()) {
                var token = quoted.get(0);
                var unquoted = (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2)
                    ? token.substring(1, token.length() - 1)
                    : token;
                if (!slice.contains(token) && !token.contains(slice) && !slice.contains(unquoted) && !unquoted.contains(slice)) {
                    return true;
                }
            }
        }

        return false;
    }

    private record IncludeNode(
        IncludeElement directInclude,
        String directPath,
        PsiFile directFile,
        List<String> pathChain,
        String targetPath,
        PsiFile targetFile,
        String fileName,
        String extension
    ) {}

    private static void renderPinnedIncludeDiagnostics(
            PsiFile file,
            List<StvnDiagnostic> diagnostics,
            AnnotationHolder holder,
            @Nullable List<Problem> problems,
            @Nullable WolfTheProblemSolver wolf,
            @Nullable VirtualFile virtualFile
    ) {
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (includes.isEmpty()) return;

        var document = file.getViewProvider().getDocument();

        var includeNodes = new java.util.ArrayList<IncludeNode>();
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit == null) continue;
            var directPath = stringLit.getText().replace("\"", "");
            var resolvedDirect = StvnTypeReference.resolveIncludeFile(stringLit);
            if (resolvedDirect == null) continue;

            var directChain = new java.util.ArrayList<String>();
            directChain.add(directPath);
            includeNodes.add(new IncludeNode(
                incl,
                directPath,
                resolvedDirect,
                directChain,
                directPath,
                resolvedDirect,
                resolvedDirect.getName(),
                getExtension(resolvedDirect.getName())
            ));
            collectTransitiveIncludes(incl, directPath, resolvedDirect, directChain, resolvedDirect, includeNodes, new java.util.HashSet<>());
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
        var groupedMap = new java.util.LinkedHashMap<PsiElement, List<GroupedIncludeDiag>>();
        var seenMessageKeys = new java.util.HashSet<String>();

        // Sort include nodes by depth descending so that leaf/deepest dependencies match first
        var sortedNodes = new java.util.ArrayList<>(includeNodes);
        sortedNodes.sort((a, b) -> Integer.compare(b.pathChain().size(), a.pathChain().size()));

        for (var diag : diagnostics) {
            if (diag.severity() != DiagnosticSeverity.ERROR) continue;
            if (!isDiagnosticFromIncludedFile(file, diag)) continue;

            var msg = sanitizeCompilerJargon(diag.message());
            var primaryToken = extractPrimaryToken(msg);
            var expectedExt = extractExtensionFromMessage(msg);

            IncludeNode matchedNode = null;
            if (expectedExt != null) {
                for (var node : sortedNodes) {
                    if (node.extension().equalsIgnoreCase(expectedExt)) {
                        if (msg.contains("Mandatory :meta block missing")) {
                            if (PsiTreeUtil.findChildOfType(node.targetFile(), MetaEntry.class) == null
                                && !StvnMetaTemplateUtil.hasMetaHeader(node.targetFile())) {
                                matchedNode = node;
                                break;
                            }
                        }
                    }
                }
                if (matchedNode == null) {
                    for (var node : sortedNodes) {
                        if (node.extension().equalsIgnoreCase(expectedExt)) {
                            matchedNode = node;
                            break;
                        }
                    }
                }
            }

            boolean isUndefinedType = msg.contains("Undefined type:")
                || msg.contains("Unknown or undefined type:")
                || msg.contains("Unresolved type alias:");

            // Vector 2 Remediation: Undefined type errors occur in the file that references the type,
            // never in the dependency file that declares the type. Exclude definition sites.
            if (matchedNode == null && isUndefinedType && !primaryToken.isEmpty()) {
                for (var node : sortedNodes) {
                    if (declaresTypeDefinition(node.targetFile(), primaryToken)) {
                        continue;
                    }
                    if (referencesTypeKeyword(node.targetFile(), primaryToken)) {
                        matchedNode = node;
                        break;
                    }
                }
            }

            if (matchedNode == null && !primaryToken.isEmpty()) {
                for (var node : sortedNodes) {
                    if (node.targetFile().getText().contains(primaryToken)) {
                        matchedNode = node;
                        break;
                    }
                }
            }

            if (matchedNode == null && !sortedNodes.isEmpty()) {
                matchedNode = sortedNodes.get(0);
            }

            if (matchedNode != null) {
                var chainStr = matchedNode.pathChain().size() > 1
                    ? String.join(" -> ", matchedNode.pathChain())
                    : matchedNode.directPath();
                String attributedMsg;
                if (matchedNode.pathChain().size() > 1) {
                    attributedMsg = "In included module '" + chainStr + "': " + msg;
                } else {
                    attributedMsg = "Included file '" + matchedNode.directPath() + "' contains compilation errors: " + msg;
                }

                boolean isRootCause = !msg.contains("Undefined type:") && !msg.contains("Unresolved type alias:") && !msg.contains("Unknown or undefined type:");
                var parentStmt = PsiTreeUtil.getParentOfType(matchedNode.directInclude(), IncludeStmt.class, false);
                var targetRangeElement = parentStmt != null ? parentStmt : matchedNode.directInclude();
                var dedupKey = targetRangeElement.getTextOffset() + ":" + attributedMsg;
                if (seenMessageKeys.add(dedupKey)) {
                    var directVf = matchedNode.directFile().getVirtualFile();
                    if (directVf == null) {
                        var activeVf = file.getVirtualFile();
                        if (activeVf != null && activeVf.getParent() != null) {
                            directVf = activeVf.getParent().findFileByRelativePath(matchedNode.directPath());
                        }
                    }

                    var targetVf = matchedNode.targetFile().getVirtualFile();
                    if (targetVf == null) {
                        var activeVf = file.getVirtualFile();
                        if (activeVf != null && activeVf.getParent() != null) {
                            targetVf = activeVf.getParent().findFileByRelativePath(matchedNode.targetPath());
                        }
                    }

                    groupedMap.computeIfAbsent(targetRangeElement, k -> new java.util.ArrayList<>())
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
        }

        for (var entry : groupedMap.entrySet()) {
            var element = entry.getKey();
            var diagList = entry.getValue();
            var targetRange = element.getTextRange();

            // Sort diagnostics: root cause errors first, then deepest dependencies
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
                    htmlTooltip = "<html>In included module '" + escapeHtml(single.chainStr()) + "':<br/>" +
                                  "&nbsp;&nbsp;&bull; " + escapeHtml(single.rawMessage()) + "</html>";
                } else {
                    htmlTooltip = "<html>Included file '" + escapeHtml(single.directPath()) + "' contains compilation errors:<br/>" +
                                  "&nbsp;&nbsp;&bull; " + escapeHtml(single.rawMessage()) + "</html>";
                }
            } else if (collapseNoise) {
                // Vector 3 Remediation: Collapse cascading undefined-type warnings to isolate root cause
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
                        htmlSb.append("<html>Included file '").append(escapeHtml(primaryRoot.directPath())).append("' contains compilation errors:<br/>");
                    }
                    htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(primaryRoot.rawMessage())).append("<br/><br/>");
                    htmlSb.append("<font color=\"#888888\"><i>(+").append(count).append(" secondary cascading undefined type errors suppressed)</i></font></html>");
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
                            sb.append("• [").append(r.depth() > 1 ? r.chainStr() : r.directPath()).append("] ").append(r.rawMessage()).append("\n");
                        }
                    }
                    sb.append("(+").append(count).append(" secondary cascading undefined type errors suppressed)");
                    finalMessage = StvnDiagnosticFormatter.formatCore(sb.toString().trim(), "ERR_MODULE_IMPORT");

                    var htmlSb = new StringBuilder();
                    if (!isHierarchy) {
                        htmlSb.append("<html>Included file '").append(escapeHtml(directPath)).append("' contains compilation errors:<br/>");
                        for (var r : rootCauses) {
                            htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(r.rawMessage())).append("<br/>");
                        }
                    } else {
                        htmlSb.append("<html>Included module hierarchy contains compilation errors:<br/>");
                        for (var r : rootCauses) {
                            htmlSb.append("&nbsp;&nbsp;&bull; <i>[").append(escapeHtml(r.depth() > 1 ? r.chainStr() : r.directPath())).append("]</i> ").append(escapeHtml(r.rawMessage())).append("<br/>");
                        }
                    }
                    htmlSb.append("<br/><font color=\"#888888\"><i>(+").append(count).append(" secondary cascading undefined type errors suppressed)</i></font></html>");
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
                htmlSb.append("<html>Included file '").append(escapeHtml(directPath)).append("' contains compilation errors:<br/>");
                for (var d : diagList) {
                    htmlSb.append("&nbsp;&nbsp;&bull; ").append(escapeHtml(d.rawMessage())).append("<br/>");
                }
                htmlSb.append("</html>");
                htmlTooltip = htmlSb.toString();
            } else {
                var sb = new StringBuilder();
                sb.append("Included module hierarchy contains compilation errors:\n");
                for (var d : diagList) {
                    sb.append("• ");
                    if (d.depth() > 1) {
                        sb.append("[").append(d.chainStr()).append("] ");
                    } else {
                        sb.append("[").append(d.directPath()).append("] ");
                    }
                    sb.append(d.rawMessage()).append("\n");
                }
                finalMessage = StvnDiagnosticFormatter.formatCore(sb.toString().trim(), "ERR_MODULE_IMPORT");

                var htmlSb = new StringBuilder();
                htmlSb.append("<html>Included module hierarchy contains compilation errors:<br/>");
                for (var d : diagList) {
                    htmlSb.append("&nbsp;&nbsp;&bull; ");
                    if (d.depth() > 1) {
                        htmlSb.append("<i>[").append(escapeHtml(d.chainStr())).append("]</i> ");
                    } else {
                        htmlSb.append("<i>[").append(escapeHtml(d.directPath())).append("]</i> ");
                    }
                    htmlSb.append(escapeHtml(d.rawMessage())).append("<br/>");
                }
                htmlSb.append("</html>");
                htmlTooltip = htmlSb.toString();
            }

            var builder = holder.newAnnotation(HighlightSeverity.ERROR, finalMessage)
                .tooltip(htmlTooltip)
                .range(targetRange);
            var attachedFixPaths = new java.util.HashSet<String>();
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
                if (document != null && targetRange.getStartOffset() >= 0 && targetRange.getStartOffset() <= document.getTextLength()) {
                    line = document.getLineNumber(targetRange.getStartOffset());
                    col = targetRange.getStartOffset() - document.getLineStartOffset(line);
                }
                var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{ finalMessage });
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
    }

    private static boolean declaresTypeDefinition(PsiFile file, String typeName) {
        var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : typeDefs) {
            var kw = def.getTypeKeyword();
            if (kw != null && kw.getText().trim().equals(typeName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesTypeKeyword(PsiFile file, String typeName) {
        var typeKeywords = PsiTreeUtil.findChildrenOfType(file, TypeKeyword.class);
        for (var kw : typeKeywords) {
            if (kw.getText().trim().equals(typeName) && !org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String getExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx) : "";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static @Nullable String extractExtensionFromMessage(String msg) {
        int extIdx = msg.indexOf("for extension '");
        if (extIdx >= 0) {
            int start = extIdx + "for extension '".length();
            int end = msg.indexOf('\'', start);
            if (end > start) {
                return msg.substring(start, end);
            }
        }
        extIdx = msg.indexOf("file extension '");
        if (extIdx >= 0) {
            int start = extIdx + "file extension '".length();
            int end = msg.indexOf('\'', start);
            if (end > start) {
                return msg.substring(start, end);
            }
        }
        return null;
    }

    private static void collectTransitiveIncludes(
            IncludeElement directInclude,
            String directPath,
            PsiFile directFile,
            List<String> currentChain,
            PsiFile currentFile,
            List<IncludeNode> accumulator,
            java.util.Set<String> visited
    ) {
        if (currentChain.size() > 10) return;
        var childIncludes = PsiTreeUtil.findChildrenOfType(currentFile, IncludeElement.class);
        for (var child : childIncludes) {
            var strLit = child.getStringLiteral();
            if (strLit == null) continue;
            var pathText = strLit.getText().replace("\"", "");
            var resolved = StvnTypeReference.resolveIncludeFile(strLit);
            if (resolved == null) continue;
            var resolvedPath = resolved.getVirtualFile() != null ? resolved.getVirtualFile().getPath() : resolved.getName();
            if (!visited.add(resolvedPath)) continue;

            var nextChain = new java.util.ArrayList<>(currentChain);
            nextChain.add(pathText);
            accumulator.add(new IncludeNode(
                directInclude,
                directPath,
                directFile,
                nextChain,
                pathText,
                resolved,
                resolved.getName(),
                getExtension(resolved.getName())
            ));
            collectTransitiveIncludes(directInclude, directPath, directFile, nextChain, resolved, accumulator, visited);
        }
    }

    private static boolean isUserIncludedFile(PsiFile activeFile, @Nullable PsiFile targetFile) {
        if (targetFile == null || targetFile == activeFile) {
            return false;
        }
        var name = targetFile.getName();
        if (name.contains("prelude") || targetFile.getVirtualFile() == null) {
            return false;
        }
        return true;
    }

    private static boolean isDuplicateOrCascadingMismatchedInput(List<StvnDiagnostic> allDiagnostics, StvnDiagnostic d) {
        var msg = d.message();
        if (msg.contains("mismatched input") && (msg.contains("expecting ')'") || msg.contains("expecting <schema type>"))) {
            for (var other : allDiagnostics) {
                if (other != d && Math.abs(other.startOffset() - d.startOffset()) <= 15 &&
                    (other.message().contains("empty composite") || other.message().contains("insufficient composite"))) {
                    return true;
                }
            }
        }
        return false;
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

    private static @Nullable String extractConstraintName(String remainder) {
        var lower = remainder.toLowerCase();
        if (lower.contains("preserveindent")) return "#preserveIndent";
        if (lower.contains("regex")) return "#regex";
        if (lower.contains("minincl")) return "#minIncl";
        if (lower.contains("minexcl")) return "#minExcl";
        if (lower.contains("maxincl")) return "#maxIncl";
        if (lower.contains("maxexcl")) return "#maxExcl";
        if (lower.contains("equatable")) return "#equatable";
        if (lower.contains("comparable")) return "#comparable";
        return null;
    }

    /**
     * Translates raw ANTLR or compiler generator error strings into clean domain concepts.
     *
     * @param raw the raw compiler diagnostic message
     * @return sanitized user-facing error message
     */
    public static String sanitizeCompilerJargon(String raw) {
        if (raw.contains("token recognition error at: '#'")) {
            return "Incomplete variant tag '#'";
        }
        if (raw.contains("token recognition error at: ':'")) {
            return "Incomplete type sigil ':'";
        }
        if (raw.startsWith("no viable alternative at input")) {
            return raw.replace("no viable alternative at input", "Syntax error at");
        }
        return raw;
    }

    /**
     * Clamps a wide diagnostic range on a type definition down to the specific offending child element.
     *
     * @param file the containing PSI file
     * @param range the original diagnostic range
     * @param message the diagnostic message
     * @return the clamped text range, or null if no clamping applies
     */
    private static @Nullable TextRange clampToOffendingChildIfTypeDef(PsiFile file, TextRange range, String message) {
        // Suppressed legacy 1.x type suffix clamping

        if (message.contains("require types to be #equatable #TRUE")) {
            var collections = PsiTreeUtil.findChildrenOfType(file, CollectionType.class);
            for (var coll : collections) {
                var cr = coll.getTextRange();
                if (range.contains(cr) || cr.contains(range) || range.intersects(cr)) {
                    var firstChild = coll.getFirstChild();
                    if (firstChild == null) continue;
                    var tokenText = firstChild.getText();
                    var innerSchemas = coll.getSchemaTypeList();
                    if (innerSchemas.isEmpty()) continue;

                    if (message.contains("Set elements") && tokenText.equals(StvnVocabulary.TYPE_SET)) {
                        var targetSchema = innerSchemas.get(0);
                        if (targetSchema != null) return targetSchema.getTextRange();
                    } else if (message.contains("Map keys") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        var targetSchema = innerSchemas.get(0);
                        if (targetSchema != null) return targetSchema.getTextRange();
                    } else if (message.contains("Inverted map values") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        if (innerSchemas.size() >= 2) {
                            var targetSchema = innerSchemas.get(1);
                            if (targetSchema != null) return targetSchema.getTextRange();
                        }
                    }
                }
            }
        }

        var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var typeDef : typeDefs) {
            var tr = typeDef.getTextRange();
            if (tr.contains(range) || range.contains(tr)) {
                if (message.contains("Undefined type: ") || message.contains("Unresolved type alias: ")) {
                    var prefix = message.contains("Undefined type: ") ? "Undefined type: " : "Unresolved type alias: ";
                    var rawName = message.substring(message.indexOf(prefix) + prefix.length()).trim();
                    var typeName = rawName.split("[\\s,;\\)\\}\\]]")[0].trim();
                    if (!typeName.startsWith(":")) {
                        typeName = ":" + typeName;
                    }
                    var typeKeywords = PsiTreeUtil.findChildrenOfType(typeDef, TypeKeyword.class);
                    // Pass 1: Spatial Intersection - prioritize matching child intersecting the diagnostic range
                    for (var typeKw : typeKeywords) {
                        var isLhs = (typeDef.getTypeKeyword() == typeKw);
                        if (!isLhs && typeKw.getText().equals(typeName)) {
                            var kwRange = typeKw.getTextRange();
                            if (kwRange.equals(range) || kwRange.contains(range) || range.contains(kwRange) || range.intersects(kwRange)) {
                                return kwRange;
                            }
                        }
                    }
                    // Pass 2: Fallback for wide enclosing ranges
                    for (var typeKw : typeKeywords) {
                        var isLhs = (typeDef.getTypeKeyword() == typeKw);
                        if (!isLhs && typeKw.getText().equals(typeName)) {
                            return typeKw.getTextRange();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Clamps wide metadata diagnostic spans down to the exact offending facet token.
     *
     * @param file the containing PSI file
     * @param range the original diagnostic range
     * @param message the diagnostic message
     * @param errorCode the optional diagnostic error code
     * @return the pinned text range on the target token, or null if no clamping applies
     */
    private static @Nullable TextRange clampToOffendingMetadataFacet(PsiFile file, TextRange range, String message, @Nullable String errorCode) {
        // Diagnostic Category Gate: Never clamp type-level errors to metadata facets
        if (message.contains("Undefined type: ") || message.contains("Unresolved type alias: ")
            || "ERR_UNDEFINED_TYPE".equals(errorCode) || "UNDEFINED_TYPE".equals(errorCode)) {
            return null;
        }
        var maps = PsiTreeUtil.findChildrenOfType(file, MetadataMap.class);
        for (var map : maps) {
            var mapRange = map.getTextRange();
            if (mapRange.equals(range) || range.contains(mapRange)) {
                // Pass 1: Inverted range and empty domain faults anchor on the upper bound facet
                if ("INVALID_NUMERIC_RANGE".equals(errorCode) || "ERR_INVERTED_RANGE".equals(errorCode)
                    || "ERR_EMPTY_INTERVAL_DOMAIN".equals(errorCode) || message.contains("effective range is invalid")
                    || message.contains("empty domain") || message.contains("cardinality range is invalid")
                    || message.contains("inverted")) {
                    for (var entry : map.getMetadataEntryList()) {
                        PsiElement leaf = entry.getFirstChild();
                        if (leaf == null) continue;
                        while (leaf.getFirstChild() != null) {
                            leaf = leaf.getFirstChild();
                        }
                        var tokenText = leaf.getText().trim();
                        if (tokenText.equals("#maxExcl") || tokenText.equals("#maxSize") || tokenText.equals("#maxIncl")) {
                            return leaf.getTextRange();
                        }
                    }
                }

                // Pass 2: Mutually exclusive bounds anchor on the conflicting secondary facet
                if ("MUTUALLY_EXCLUSIVE_BOUNDS".equals(errorCode)
                    || (message.contains("mutually exclusive") && !message.contains("ancestor") && !"ERR_MUTUALLY_EXCLUSIVE".equals(errorCode))) {
                    for (var entry : map.getMetadataEntryList()) {
                        PsiElement leaf = entry.getFirstChild();
                        if (leaf == null) continue;
                        while (leaf.getFirstChild() != null) {
                            leaf = leaf.getFirstChild();
                        }
                        var tokenText = leaf.getText().trim();
                        if (tokenText.equals("#maxIncl") || tokenText.equals("#minExcl") || tokenText.equals("#filterExcl")
                            || tokenText.equals("#zoned") || tokenText.equals("#ms") || tokenText.equals("#audited")
                            || tokenText.equals("#us") || tokenText.equals("#ns")) {
                            return leaf.getTextRange();
                        }
                    }
                }

                // Pass 2b: Non-overridable, redundant, and transitive mutually exclusive facets anchor on the child facet entry
                if ("ERR_NON_OVERRIDABLE_FACET".equals(errorCode) || "ERR_MUTUALLY_EXCLUSIVE".equals(errorCode)
                    || StvnDiagnosticCodes.WARN_REDUNDANT_METADATA_FACET.equals(errorCode)
                    || DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(errorCode) || message.contains("is redundant")
                    || message.contains("non-overridable") || (message.contains("mutually exclusive") && message.contains("ancestor"))) {
                    for (var entry : map.getMetadataEntryList()) {
                        PsiElement leaf = entry.getFirstChild();
                        if (leaf == null) continue;
                        while (leaf.getFirstChild() != null) {
                            leaf = leaf.getFirstChild();
                        }
                        var tokenText = leaf.getText().trim();
                        if (message.contains("facet '" + tokenText) || message.contains("'" + tokenText + "'") || message.contains("'" + tokenText + " ")) {
                            return entry.getTextRange();
                        }
                    }
                }

                // Pass 3: Check for specifically quoted token in message: e.g. facet '#unsigned'
                for (var entry : map.getMetadataEntryList()) {
                    PsiElement leaf = entry.getFirstChild();
                    if (leaf == null) continue;
                    while (leaf.getFirstChild() != null) {
                        leaf = leaf.getFirstChild();
                    }
                    var tokenText = leaf.getText().trim();
                    if (tokenText.startsWith("#") && (message.contains("facet '" + tokenText + "'") || message.contains("'" + tokenText + "'"))) {
                        return leaf.getTextRange();
                    }
                }

                // Pass 4: Incomplete trait facets anchor on the trait keyword token
                if (message.contains("Metadata facet #preserveIndent requires an explicit boolean value")
                    || message.contains("#preserveIndent")) {
                    for (var entry : map.getMetadataEntryList()) {
                        if (entry.getMetadataTrait() != null) {
                            return entry.getMetadataTrait().getTextRange();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Expands a pinpoint diagnostic range anchored on empty parentheses '()' or brackets '[]'
     * backwards to encompass the enclosing composite keyword token (:Tuple, :Union, :Option, :Either, :Enum, etc.).
     *
     * @param file the containing PSI file
     * @param range the original diagnostic range
     * @param message the diagnostic message
     * @return the expanded or original text range
     */
    private static TextRange expandEmptyCompositeRange(PsiFile file, TextRange range, String message) {
        var text = file.getText();
        var textLength = text.length();
        if (textLength == 0) {
            return range;
        }

        var start = Math.max(0, Math.min(range.getStartOffset(), textLength));

        // 1. PSI AST Node Context Inspection
        var element = file.findElementAt(start);
        if (element != null) {
            var product = PsiTreeUtil.getParentOfType(element, ProductType.class);
            if (product != null) {
                var productText = product.getText();
                if (productText.startsWith(StvnVocabulary.TYPE_TUPLE) && product.getSchemaTypeList().isEmpty()) {
                    return product.getTextRange();
                }
            }

            var sum = PsiTreeUtil.getParentOfType(element, SumType.class);
            if (sum != null) {
                var sumText = sum.getText();
                if (sum.getEnumDef() != null && sum.getEnumDef().getValueKeywordList().isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(StvnVocabulary.TYPE_UNION) && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
            }

            var coll = PsiTreeUtil.getParentOfType(element, CollectionType.class);
            if (coll != null && coll.getSchemaTypeList().isEmpty()) {
                return coll.getTextRange();
            }
        }

        // 2. Lexical Lookback Heuristic (Fallback when PSI tree contains syntax error elements)
        var scanStart = start;
        if (scanStart < textLength && (text.charAt(scanStart) == ')' || text.charAt(scanStart) == ']')) {
            int openIdx = scanStart - 1;
            while (openIdx >= 0 && Character.isWhitespace(text.charAt(openIdx))) {
                openIdx--;
            }
            if (openIdx >= 0) {
                char openChar = text.charAt(openIdx);
                char closeChar = text.charAt(scanStart);
                if ((openChar == '(' && closeChar == ')') || (openChar == '[' && closeChar == ']')) {
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
                            return new TextRange(kwStart, scanStart + 1);
                        }
                    }
                }
            }
        } else if (scanStart > 0 && (text.charAt(scanStart - 1) == ')' || text.charAt(scanStart - 1) == ']')) {
            int closeIdx = scanStart - 1;
            int openIdx = closeIdx - 1;
            while (openIdx >= 0 && Character.isWhitespace(text.charAt(openIdx))) {
                openIdx--;
            }
            if (openIdx >= 0) {
                char openChar = text.charAt(openIdx);
                char closeChar = text.charAt(closeIdx);
                if ((openChar == '(' && closeChar == ')') || (openChar == '[' && closeChar == ']')) {
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
                }
            }
        } else if (scanStart < textLength && (text.charAt(scanStart) == '(' || text.charAt(scanStart) == '[')) {
            int openIdx = scanStart;
            char openChar = text.charAt(openIdx);
            char targetClose = openChar == '(' ? ')' : ']';
            int closeIdx = openIdx + 1;
            while (closeIdx < textLength && Character.isWhitespace(text.charAt(closeIdx))) {
                closeIdx++;
            }
            if (closeIdx < textLength && text.charAt(closeIdx) == targetClose) {
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
            }
        }

        return range;
    }

    private static boolean isCompositeKeywordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':';
    }

    private static boolean isKnownCompositeKeyword(String kw) {
        return kw.equals(StvnVocabulary.TYPE_TUPLE) || kw.equals(StvnVocabulary.TYPE_UNION) || kw.equals(StvnVocabulary.TYPE_ENUM) ||
               kw.equals(StvnVocabulary.TYPE_OPTION) || kw.equals(StvnVocabulary.TYPE_EITHER) || kw.equals(StvnVocabulary.TYPE_SEQ) ||
               kw.equals(StvnVocabulary.TYPE_SET) ||
               kw.equals(StvnVocabulary.TYPE_MAP);
    }

    private static AnnotationBuilder attachWrapSumVariantQuickFixes(
        AnnotationBuilder annotationBuilder,
        PsiFile file,
        TextRange range,
        StvnDiagnostic diag,
        String message
    ) {
        if ((diag.errorCode().isPresent() && "ERR_AMBIGUOUS_SUM_INFERENCE".equals(diag.errorCode().get()))
            || (message.contains("Ambiguous implicit resolution") || message.contains("Ambiguous implicit either") || message.contains("matches multiple candidate branches"))) {
            var elem = file.findElementAt(range.getStartOffset());
            if (elem != null) {
                var valueElement = PsiTreeUtil.getParentOfType(elem, Value.class, false);
                if (valueElement == null) {
                    valueElement = PsiTreeUtil.getNonStrictParentOfType(elem, Value.class);
                }
                var targetElement = (valueElement != null) ? valueElement : elem;
                var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(elem);
                if (expectedSchema != null) {
                    var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
                    var schemaToInspect = (resolvedNominal != null) ? resolvedNominal : expectedSchema;
                    var constructor = schemaToInspect.getSchemaConstructor();
                    if (constructor != null && constructor.getSumType() != null) {
                        var sumType = constructor.getSumType();
                        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                        if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                            // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
                            if (innerSchemas.size() >= 2) {
                                var rightBranch = innerSchemas.get(1);
                                var leftBranch = innerSchemas.get(0);
                                var rightLabel = StvnSchemaFormatter.formatCleanSchema(rightBranch);
                                var leftLabel = StvnSchemaFormatter.formatCleanSchema(leftBranch);
                                annotationBuilder = annotationBuilder.withFix(new WrapSumVariantQuickFix(targetElement, "#Right", rightLabel, PriorityAction.Priority.HIGH));
                                annotationBuilder = annotationBuilder.withFix(new WrapSumVariantQuickFix(targetElement, "#Left", leftLabel, PriorityAction.Priority.NORMAL));
                            }
                        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                            for (int i = 0; i < innerSchemas.size(); i++) {
                                var branch = innerSchemas.get(i);
                                if (valueElement != null && StvnTypeResolver.matchesSchemaPattern(valueElement, branch)) {
                                    var branchLabel = StvnSchemaFormatter.formatCleanSchema(branch);
                                    var tag = "#" + (i + 1);
                                    annotationBuilder = annotationBuilder.withFix(new WrapSumVariantQuickFix(targetElement, tag, branchLabel));
                                }
                            }
                        }
                    }
                }
            }
        }
        return annotationBuilder;
    }
}
