package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.TestOnly;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.printer.AstCompactPrinter;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.validation.StvnSumTagRedundancyEngine;

import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Cohesive Pure-IR execution service for the 3-Submenu STVN VOP Projection and Canonicalization Matrix.
 * <p>
 * Centralizes cancellable modal-progress workspace buffer collection ({@code ACT-01}), multi-root
 * {@code :include} staging, {@link StvnSchemaFlattener} invocation, empty-{@code :defs} validation,
 * Pure-IR {@link StvnDocumentKind#BODY} reconstruction, {@link CopyPasteManager} clipboard transfer,
 * overwrite-guarded undoable {@link WriteCommandAction} VFS sibling file persistence ({@code ACT-02}),
 * and post-verified undoable in-place buffer canonicalization.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnVopProjectionService {

    private static final String SYMBOLIC_ERR_EMPTY_DEFS_BLOCK = "ERR_EMPTY_DEFS_BLOCK";
    private static volatile @Nullable Boolean overwriteConfirmationOverride;

    /**
     * Format profile for Submenu 1 hermetic {@code .stvn_d} schema projections.
     *
     * @since 2.0.0
     */
    public enum SchemaFormatProfile {
        /** Multi-line 2-space indented canonical schema via {@link AstPrettyPrinter}. */
        PRETTY("pretty"),
        /** Single-line zero-padded {@code #DEFS} CAS preimage via {@link StvnSchemaFlattener}. */
        COMPACT_CAS("compact CAS");

        private final String label;

        SchemaFormatProfile(String label) {
            this.label = label;
        }

        /**
         * Returns the human-readable notification label for this profile.
         *
         * @return format label string
         */
        public String label() {
            return label;
        }
    }

    /**
     * Format profile for Submenu 2 hermetic {@code .stvn} payload projections.
     *
     * @since 2.0.0
     */
    public enum PayloadFormatProfile {
        /** Multi-line 2-space indented canonical payload via {@link AstPrettyPrinter}. */
        PRETTY("pretty"),
        /** Single-line compact canonical payload via {@link AstCompactPrinter}. */
        COMPACT("compact");

        private final String label;

        PayloadFormatProfile(String label) {
            this.label = label;
        }

        /**
         * Returns the human-readable notification label for this profile.
         *
         * @return format label string
         */
        public String label() {
            return label;
        }
    }

    /**
     * Format profile for Submenu 3 in-place active buffer canonicalization.
     *
     * @since 2.0.0
     */
    public enum InPlaceFormatProfile {
        /** Multi-line 2-space indented canonical IR via {@link AstPrettyPrinter}. */
        PRETTY_IR("pretty IR", "STVN: Replace Buffer with Pretty IR"),
        /** Single-line compact canonical IR via {@link AstCompactPrinter}. */
        COMPACT_IR("compact IR", "STVN: Replace Buffer with Compact IR");

        private final String label;
        private final String commandName;

        InPlaceFormatProfile(String label, String commandName) {
            this.label = label;
            this.commandName = commandName;
        }

        /**
         * Returns the human-readable notification label for this profile.
         *
         * @return format label string
         */
        public String label() {
            return label;
        }

        /**
         * Returns the undo command name registered with {@link WriteCommandAction}.
         *
         * @return command title string
         */
        public String commandName() {
            return commandName;
        }
    }

    private record InPlacePreparedReplacement(String formattedText, String activeKindKeyword) {
    }

    private StvnVopProjectionService() {
    }

    /**
     * Overrides interactive sibling-file overwrite confirmation during automated tests.
     * Pass {@code null} to restore default headless/interactive dialog behavior.
     *
     * @param allowOverwrite {@code Boolean.TRUE} to approve, {@code Boolean.FALSE} to cancel, or {@code null} to reset
     */
    @TestOnly
    public static void setOverwriteConfirmationOverride(@Nullable Boolean allowOverwrite) {
        overwriteConfirmationOverride = allowOverwrite;
    }

    /**
     * Executes {@code computation} off the EDT inside a cancellable modal progress indicator
     * when invoked on the interactive UI dispatch thread, preventing UI freezes during workspace
     * traversal, temporary directory staging, and compiler lowering.
     *
     * @param project       active IntelliJ project
     * @param progressTitle modal progress dialog title
     * @param computation   background supplier
     * @param <T>           result type
     * @return computed value, or {@code null} if cancelled by the user
     */
    public static <T> @Nullable T computeUnderProgress(
            Project project,
            String progressTitle,
            Supplier<@Nullable T> computation
    ) {
        var app = ApplicationManager.getApplication();
        if (app != null && app.isDispatchThread() && !app.isUnitTestMode()) {
            var ref = new AtomicReference<@Nullable T>();
            boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> ref.set(computation.get()),
                progressTitle,
                true,
                project
            );
            return completed ? ref.get() : null;
        }
        return computation.get();
    }

    /**
     * Resolves the active {@link VirtualFile} from {@link CommonDataKeys#VIRTUAL_FILE} or
     * {@link CommonDataKeys#PSI_FILE} without blocking on EDT.
     *
     * @param event action event context
     * @return resolved virtual file, or {@code null} when absent
     */
    public static @Nullable VirtualFile resolveTargetVirtualFile(AnActionEvent event) {
        VirtualFile vf = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null) {
            return vf;
        }
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        return psiFile != null ? psiFile.getVirtualFile() : null;
    }

    /**
     * Evaluates whether {@code vf} is a valid textual STVN compilation unit (excluding directories
     * and binary {@code .stvn_b}/{@code .stvn_bd} files).
     *
     * @param vf candidate virtual file, or {@code null}
     * @return {@code true} if {@code vf} is a textual STVN file
     */
    public static boolean isTextualStvnVirtualFile(@Nullable VirtualFile vf) {
        return vf != null && !vf.isDirectory() && StvnProjectionStemUtil.isTextualStvnFileName(vf.getName());
    }

    /**
     * Evaluates whether exporting a sibling {@code <stem>.stvn_d} schema file is permitted for {@code vf}.
     * Enforces the {@code .stvn_d} Self-Overwrite Guard, synthetic Prelude exclusion, and writable target checks.
     *
     * @param vf active textual STVN virtual file
     * @return {@code true} if sibling {@code .stvn_d} export is valid and writable
     */
    public static boolean canExportSiblingSchema(VirtualFile vf) {
        if (!isTextualStvnVirtualFile(vf)
                || StvnProjectionStemUtil.isSchemaSiblingSelfOverwrite(vf.getName())
                || StvnPreludeBridge.isPreludeFile(vf)) {
            return false;
        }
        String targetName = StvnProjectionStemUtil.deriveDefsStemName(vf.getName());
        return isSiblingDestinationWritable(vf, targetName);
    }

    /**
     * Evaluates whether exporting a sibling {@code <stem>.stvn} payload file is permitted for {@code vf}.
     * Enforces the {@code .stvn} Self-Overwrite Guard, {@code .stvn_d}/{@code .stvn_di} exclusion,
     * synthetic Prelude exclusion, and writable target checks.
     *
     * @param vf active textual STVN virtual file
     * @return {@code true} if sibling {@code .stvn} export is valid and writable
     */
    public static boolean canExportSiblingPayload(VirtualFile vf) {
        if (!isTextualStvnVirtualFile(vf)
                || StvnProjectionStemUtil.isPayloadSiblingSelfOverwriteOrUnsupported(vf.getName())
                || StvnPreludeBridge.isPreludeFile(vf)) {
            return false;
        }
        String targetName = StvnProjectionStemUtil.derivePayloadStemName(vf.getName());
        return isSiblingDestinationWritable(vf, targetName);
    }

    /**
     * Evaluates whether in-place canonical buffer replacement is permitted for {@code vf}.
     *
     * @param vf active textual STVN virtual file
     * @return {@code true} if {@code vf} and its cached document (when loaded) are writable and non-Prelude
     */
    public static boolean canReplaceBufferInPlace(VirtualFile vf) {
        if (!isTextualStvnVirtualFile(vf)
                || StvnPreludeBridge.isPreludeFile(vf)
                || !vf.isWritable()) {
            return false;
        }
        Document cachedDoc = FileDocumentManager.getInstance().getCachedDocument(vf);
        return cachedDoc == null || cachedDoc.isWritable();
    }

    /**
     * Collects all textual STVN files across project content roots and the entry file's parent directory
     * inside a thread-safe {@link ReadAction}, overlaying unsaved open editor buffers atop disk contents.
     *
     * @param project   active IntelliJ project
     * @param entryFile entry-point STVN virtual file
     * @return ordered map of POSIX-normalized paths to UTF-8 STVN text
     */
    public static Map<String, String> collectWorkspaceSources(Project project, VirtualFile entryFile) {
        return ReadAction.compute(() -> {
            Map<String, String> workspaceMap = new LinkedHashMap<>();
            ProjectFileIndex.getInstance(project).iterateContent(fileOrDir -> {
                if (!fileOrDir.isDirectory() && StvnProjectionStemUtil.isTextualStvnFileName(fileOrDir.getName())) {
                    String normPath = StvnSchemaFlattener.normalizePath(fileOrDir.getPath());
                    workspaceMap.put(normPath, loadLatestBufferOrDiskText(fileOrDir));
                }
                return true;
            });
            for (VirtualFile contentRoot : ProjectRootManager.getInstance(project).getContentRoots()) {
                collectDirectoryRecursively(contentRoot, workspaceMap);
            }
            VirtualFile parent = entryFile.getParent();
            if (parent != null) {
                collectDirectoryRecursively(parent, workspaceMap);
            }
            String entryPath = StvnSchemaFlattener.normalizePath(entryFile.getPath());
            workspaceMap.put(entryPath, loadLatestBufferOrDiskText(entryFile));
            return workspaceMap;
        });
    }

    /**
     * Loads the latest in-memory editor buffer text if cached, falling back to UTF-8 disk contents,
     * safely wrapped in a {@link ReadAction}.
     *
     * @param file target virtual file
     * @return latest UTF-8 text content
     */
    public static String loadLatestBufferOrDiskText(VirtualFile file) {
        return ReadAction.compute(() -> {
            Document cachedDoc = FileDocumentManager.getInstance().getCachedDocument(file);
            if (cachedDoc != null) {
                return cachedDoc.getText();
            }
            return LoadTextUtil.loadText(file).toString();
        });
    }

    /**
     * Executes Submenu 1 clipboard extraction ({@code Pretty} or {@code Compact CAS} hermetic {@code .stvn_d}).
     *
     * @param project   active IntelliJ project
     * @param entryFile active textual STVN virtual file
     * @param profile   requested schema format profile
     */
    public static void copyHermeticSchemaToClipboard(
            Project project,
            VirtualFile entryFile,
            SchemaFormatProfile profile
    ) {
        String targetDefsName = StvnProjectionStemUtil.deriveDefsStemName(entryFile.getName());
        Optional<String> rendered = buildHermeticSchemaProjection(project, entryFile, targetDefsName, profile);
        if (rendered.isEmpty()) {
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(rendered.get()));
        StvnWorkspaceNotificationHelper.showSchemaClipboardSuccess(project, targetDefsName, profile.label());
    }

    /**
     * Executes Submenu 1 and {@code BuildMenu} sibling {@code .stvn_d} file export ({@code Pretty} or {@code Compact CAS}).
     *
     * @param project   active IntelliJ project
     * @param entryFile active textual STVN virtual file
     * @param profile   requested schema format profile
     */
    public static void exportHermeticSchemaSibling(
            Project project,
            VirtualFile entryFile,
            SchemaFormatProfile profile
    ) {
        if (!canExportSiblingSchema(entryFile)) {
            return;
        }
        String targetDefsName = StvnProjectionStemUtil.deriveDefsStemName(entryFile.getName());
        Optional<String> rendered = buildHermeticSchemaProjection(project, entryFile, targetDefsName, profile);
        if (rendered.isEmpty()) {
            return;
        }
        String commandTitle = "STVN: Export Sibling Schema '" + targetDefsName + "'";
        if (writeSiblingAssetAtomically(project, entryFile, targetDefsName, commandTitle, rendered.get())) {
            StvnWorkspaceNotificationHelper.showSchemaExportSuccess(project, targetDefsName, profile.label());
        }
    }

    /**
     * Executes Submenu 2 clipboard extraction ({@code Pretty} or {@code Compact} hermetic {@code .stvn} payload).
     *
     * @param project   active IntelliJ project
     * @param entryFile active payload-bearing STVN virtual file ({@code .stvn_i} or {@code .stvn})
     * @param profile   requested payload format profile
     */
    public static void copyHermeticPayloadToClipboard(
            Project project,
            VirtualFile entryFile,
            PayloadFormatProfile profile
    ) {
        if (!StvnProjectionStemUtil.supportsPayloadExtraction(entryFile.getName())) {
            return;
        }
        String targetPayloadName = StvnProjectionStemUtil.derivePayloadStemName(entryFile.getName());
        Optional<String> rendered = buildHermeticPayloadProjection(project, entryFile, targetPayloadName, profile);
        if (rendered.isEmpty()) {
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(rendered.get()));
        StvnWorkspaceNotificationHelper.showPayloadClipboardSuccess(project, targetPayloadName, profile.label());
    }

    /**
     * Executes Submenu 2 sibling {@code .stvn} payload file export ({@code Pretty} or {@code Compact}).
     *
     * @param project   active IntelliJ project
     * @param entryFile active modular payload STVN virtual file ({@code .stvn_i})
     * @param profile   requested payload format profile
     */
    public static void exportHermeticPayloadSibling(
            Project project,
            VirtualFile entryFile,
            PayloadFormatProfile profile
    ) {
        if (!canExportSiblingPayload(entryFile)) {
            return;
        }
        String targetPayloadName = StvnProjectionStemUtil.derivePayloadStemName(entryFile.getName());
        Optional<String> rendered = buildHermeticPayloadProjection(project, entryFile, targetPayloadName, profile);
        if (rendered.isEmpty()) {
            return;
        }
        String commandTitle = "STVN: Export Sibling Payload '" + targetPayloadName + "'";
        if (writeSiblingAssetAtomically(project, entryFile, targetPayloadName, commandTitle, rendered.get())) {
            StvnWorkspaceNotificationHelper.showPayloadExportSuccess(project, targetPayloadName, profile.label());
        }
    }

    /**
     * Executes Submenu 3 in-place buffer canonicalization ({@code Pretty IR} or {@code Compact IR}),
     * supporting both {@code EditorPopupMenu} and {@code ProjectViewPopupMenu} (with or without an open {@link Editor})
     * and validating the printed IR via {@link StvnCompiler#compileToResult} before mutating {@code document}.
     *
     * @param project active IntelliJ project
     * @param event   action event context
     * @param profile requested in-place format profile
     */
    public static void replaceActiveBufferInPlace(
            Project project,
            AnActionEvent event,
            InPlaceFormatProfile profile
    ) {
        PsiFile psiFile = resolveTargetPsiFile(project, event);
        if (!(psiFile instanceof StvnFile)) {
            return;
        }
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf != null && !canReplaceBufferInPlace(vf)) {
            return;
        }
        Document document = resolveTargetDocument(project, event, psiFile, vf);
        if (document == null || !document.isWritable()) {
            return;
        }

        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        docManager.commitDocument(document);

        if (!StvnCanonicalizationGuard.confirmCanonicalization(project, psiFile)) {
            return;
        }

        String rawText = document.getText();
        String fileName = vf != null ? vf.getName() : psiFile.getName();

        InPlacePreparedReplacement prepared = computeUnderProgress(
            project,
            "Canonicalizing STVN Buffer...",
            () -> {
                String normalizedText = StvnSourceFormatter.tryFormat(rawText).orElse(rawText);
                Optional<StvnDocument> compiledOpt = compileWorkspaceDocument(project, vf, fileName, normalizedText);
                if (compiledOpt.isEmpty()) {
                    return null;
                }

                StvnDocument canonicalDoc = compiledOpt.get();
                String formatted = profile == InPlaceFormatProfile.PRETTY_IR
                    ? AstPrettyPrinter.print(canonicalDoc)
                    : AstCompactPrinter.print(canonicalDoc);

                // Safeguard 2: Verify formatted IR compiles cleanly under fileName before mutating buffer
                StvnParserConfig postConfig = resolveParserConfig(fileName);
                StvnCompilationResult<StvnDocument> postVerification = StvnCompiler.compileToResult(
                    formatted,
                    fileName,
                    postConfig
                );
                boolean isDefinitionsUnit = StvnProjectionStemUtil.classifyTextualExtension(fileName)
                    .map(ext -> ext == StvnProjectionStemUtil.StvnTextExtension.STVN_D
                        || ext == StvnProjectionStemUtil.StvnTextExtension.STVN_DI)
                    .orElse(false);
                boolean emptyInPlaceDefs = (isDefinitionsUnit
                        && canonicalDoc.defs().nominalTypes().isEmpty()
                        && canonicalDoc.defs().constants().isEmpty())
                    || postVerification.diagnostics().stream()
                        .anyMatch(d -> SYMBOLIC_ERR_EMPTY_DEFS_BLOCK.equals(d.symbolicId().orElse("")));
                if (emptyInPlaceDefs) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showEmptyInPlaceDefsWarning(project, fileName));
                    return null;
                }
                if (postVerification.hasErrors()) {
                    reportCompilationDiagnostics(project, fileName, postVerification.diagnostics());
                    return null;
                }
                if (!StvnSumTagRedundancyEngine.isDocumentsPayloadAndConstantsEquivalent(
                    canonicalDoc,
                    postVerification.document().orElse(null)
                )) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showSemanticRoundTripMismatchError(project, fileName));
                    return null;
                }

                String activeKindKeyword = resolveEffectiveKindKeyword(canonicalDoc, fileName);
                return new InPlacePreparedReplacement(formatted, activeKindKeyword);
            }
        );

        if (prepared == null) {
            return;
        }

        WriteCommandAction.runWriteCommandAction(project, profile.commandName(), null, () -> {
            document.replaceString(0, document.getTextLength(), prepared.formattedText());
            docManager.commitDocument(document);
        });

        StvnWorkspaceNotificationHelper.showInPlaceReplaceSuccess(
            project,
            fileName,
            prepared.activeKindKeyword(),
            profile.label()
        );
    }

    /**
     * Reconstructs {@code sourceDoc} as a hermetic {@link StvnDocumentKind#BODY} payload document
     * using pure {@code org.stvnadore.core.ir} records (normalizing {@code #kind} to {@code #BODY}
     * whenever {@code sourceDoc.meta().isPresent()} and clearing {@code hasIncludes}).
     *
     * @param sourceDoc compiled source STVN document
     * @return hermetic {@code #BODY} {@link StvnDocument} ready for {@link AstPrettyPrinter} or {@link AstCompactPrinter}
     */
    public static StvnDocument projectToHermeticBodyDocument(StvnDocument sourceDoc) {
        Optional<StvnDocumentMeta> projectedMeta = sourceDoc.meta().map(meta ->
            new StvnDocumentMeta(meta.name(), meta.domain(), Optional.of(StvnDocumentKind.BODY))
        );
        return new StvnDocument(
            projectedMeta,
            sourceDoc.defs(),
            sourceDoc.rootType(),
            sourceDoc.payload(),
            sourceDoc.hasDefsSection(),
            false
        );
    }

    private static Optional<String> buildHermeticSchemaProjection(
            Project project,
            VirtualFile entryFile,
            String targetDefsName,
            SchemaFormatProfile profile
    ) {
        String computed = computeUnderProgress(
            project,
            "Projecting Hermetic STVN Schema...",
            () -> {
                Map<String, String> workspaceMap = collectWorkspaceSources(project, entryFile);
                String entryPointPath = StvnSchemaFlattener.normalizePath(entryFile.getPath());

                String flattenedCas;
                try {
                    flattenedCas = StvnSchemaFlattener.flatten(workspaceMap, entryPointPath);
                } catch (CyclicDependencyException ex) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showCycleError(project, ex));
                    return null;
                } catch (NamespaceCollisionException ex) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showCollisionError(project, ex));
                    return null;
                } catch (DuplicateModuleImportException ex) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showDuplicateImportError(project, ex));
                    return null;
                } catch (Exception ex) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showGenericError(project, ex));
                    return null;
                }

                StvnParserConfig substrateAwareConfig = new StvnParserConfig(
                    false,
                    StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS,
                    true
                );
                StvnCompilationResult<StvnDocument> validation = StvnCompiler.compileToResult(
                    flattenedCas,
                    targetDefsName,
                    substrateAwareConfig
                );

                boolean emptyDefs = validation.diagnostics().stream()
                    .anyMatch(d -> SYMBOLIC_ERR_EMPTY_DEFS_BLOCK.equals(d.symbolicId().orElse("")))
                    || validation.document()
                        .map(doc -> doc.defs().nominalTypes().isEmpty() && doc.defs().constants().isEmpty())
                        .orElse(false);
                if (emptyDefs) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showEmptySchemaDefsWarning(
                        project,
                        entryFile.getName(),
                        targetDefsName
                    ));
                    return null;
                }

                if (validation.hasErrors() || validation.document().isEmpty()) {
                    reportCompilationDiagnostics(project, entryFile.getName(), validation.diagnostics());
                    return null;
                }

                if (profile == SchemaFormatProfile.COMPACT_CAS) {
                    return flattenedCas;
                }
                StvnDocument validatedSchemaDoc = validation.document().get();
                String prettySchema = AstPrettyPrinter.print(validatedSchemaDoc);
                StvnCompilationResult<StvnDocument> prettyCheck = StvnCompiler.compileToResult(
                    prettySchema,
                    targetDefsName,
                    substrateAwareConfig
                );
                if (prettyCheck.hasErrors()
                        || !StvnSumTagRedundancyEngine.isDocumentsPayloadAndConstantsEquivalent(
                            validatedSchemaDoc,
                            prettyCheck.document().orElse(null)
                        )) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showSemanticRoundTripMismatchError(
                        project,
                        targetDefsName
                    ));
                    return null;
                }
                return prettySchema;
            }
        );
        return Optional.ofNullable(computed);
    }

    private static Optional<String> buildHermeticPayloadProjection(
            Project project,
            VirtualFile entryFile,
            String targetPayloadName,
            PayloadFormatProfile profile
    ) {
        String computed = computeUnderProgress(
            project,
            "Projecting Hermetic STVN Payload...",
            () -> {
                String rawText = loadLatestBufferOrDiskText(entryFile);
                String normalizedText = StvnSourceFormatter.tryFormat(rawText).orElse(rawText);

                Optional<StvnDocument> compiledOpt = compileWorkspaceDocument(
                    project,
                    entryFile,
                    entryFile.getName(),
                    normalizedText
                );
                if (compiledOpt.isEmpty()) {
                    return null;
                }

                StvnDocument hermeticBodyDoc = projectToHermeticBodyDocument(compiledOpt.get());
                String formatted = profile == PayloadFormatProfile.PRETTY
                    ? AstPrettyPrinter.print(hermeticBodyDoc)
                    : AstCompactPrinter.print(hermeticBodyDoc);

                StvnParserConfig config = resolveParserConfig(targetPayloadName);
                StvnCompilationResult<StvnDocument> verification = StvnCompiler.compileToResult(
                    formatted,
                    targetPayloadName,
                    config
                );
                if (verification.hasErrors()) {
                    reportCompilationDiagnostics(project, targetPayloadName, verification.diagnostics());
                    return null;
                }
                if (!StvnSumTagRedundancyEngine.isDocumentsPayloadAndConstantsEquivalent(
                    hermeticBodyDoc,
                    verification.document().orElse(null)
                )) {
                    runOnEdt(() -> StvnWorkspaceNotificationHelper.showSemanticRoundTripMismatchError(
                        project,
                        targetPayloadName
                    ));
                    return null;
                }
                return formatted;
            }
        );
        return Optional.ofNullable(computed);
    }

    private static Optional<StvnDocument> compileWorkspaceDocument(
            Project project,
            @Nullable VirtualFile entryVf,
            String fileName,
            String normalizedEntryText
    ) {
        StvnParserConfig config = resolveParserConfig(fileName);
        Path stagedTempRoot = null;
        try {
            String compilationPath = fileName;
            if (entryVf != null) {
                if (normalizedEntryText.contains(StvnVocabulary.KEYWORD_INCLUDE)) {
                    Map<String, String> workspaceMap = collectWorkspaceSources(project, entryVf);
                    String entryNormPath = StvnSchemaFlattener.normalizePath(entryVf.getPath());
                    workspaceMap.put(entryNormPath, normalizedEntryText);
                    stagedTempRoot = Files.createTempDirectory("stvn_vop_stage_");
                    compilationPath = materializeWorkspaceMapToTemp(workspaceMap, entryNormPath, stagedTempRoot)
                        .toAbsolutePath()
                        .toString();
                } else {
                    compilationPath = entryVf.getPath();
                }
            }

            StvnCompilationResult<StvnDocument> result = StvnCompiler.compileToResult(
                normalizedEntryText,
                compilationPath,
                config
            );
            if (result.hasErrors() || result.document().isEmpty()) {
                reportCompilationDiagnostics(project, fileName, result.diagnostics());
                return Optional.empty();
            }
            return result.document();
        } catch (Exception ex) {
            runOnEdt(() -> StvnWorkspaceNotificationHelper.showGenericError(project, ex));
            return Optional.empty();
        } finally {
            if (stagedTempRoot != null) {
                deleteDirectoryQuietly(stagedTempRoot);
            }
        }
    }

    private static Path materializeWorkspaceMapToTemp(
            Map<String, String> workspaceMap,
            String entryNormPath,
            Path tempRoot
    ) throws IOException {
        List<String[]> splitPaths = workspaceMap.keySet().stream()
            .map(p -> sanitizeDriveSegments(p.split("/")))
            .toList();
        int commonPrefixLen = computeCommonDirectoryPrefixLength(splitPaths);

        Path entryTarget = tempRoot.resolve("entry.stvn_i");
        for (Map.Entry<String, String> entry : workspaceMap.entrySet()) {
            String[] segments = sanitizeDriveSegments(entry.getKey().split("/"));
            Path relPath = Path.of(segments[segments.length - 1]);
            if (commonPrefixLen < segments.length - 1) {
                String[] sub = Arrays.copyOfRange(segments, commonPrefixLen, segments.length);
                relPath = Path.of(sub[0], Arrays.copyOfRange(sub, 1, sub.length));
            }
            Path target = tempRoot.resolve(relPath);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            if (entry.getKey().equals(entryNormPath)) {
                entryTarget = target;
            }
        }
        return entryTarget;
    }

    private static String[] sanitizeDriveSegments(String[] rawSegments) {
        return Arrays.stream(rawSegments)
            .filter(s -> !s.isEmpty())
            .map(s -> s.replace(':', '_'))
            .toArray(String[]::new);
    }

    private static int computeCommonDirectoryPrefixLength(List<String[]> splitPaths) {
        if (splitPaths.isEmpty()) {
            return 0;
        }
        String[] first = splitPaths.get(0);
        int maxDirLen = Math.max(0, first.length - 1);
        for (int idx = 1; idx < splitPaths.size(); idx++) {
            String[] candidate = splitPaths.get(idx);
            maxDirLen = Math.min(maxDirLen, Math.max(0, candidate.length - 1));
            for (int i = 0; i < maxDirLen; i++) {
                if (!first[i].equals(candidate[i])) {
                    maxDirLen = i;
                    break;
                }
            }
        }
        return maxDirLen;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> children = Files.list(dir)) {
                    for (Path child : children.toList()) {
                        deleteDirectoryQuietly(child);
                    }
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // Best-effort cleanup of transient staging directory
        }
    }

    private static boolean writeSiblingAssetAtomically(
            Project project,
            VirtualFile entryFile,
            String outputName,
            String commandTitle,
            String content
    ) {
        String normalizedLfContent = content.replace("\r\n", "\n").replace("\r", "\n");
        VirtualFile parent = entryFile.getParent();
        if (parent == null) {
            return false;
        }

        final boolean[] succeeded = {false};
        Runnable vfsWriteTask = () -> {
            VirtualFile existingTarget = parent.findChild(outputName);
            if (existingTarget != null && !confirmSiblingOverwrite(project, outputName, parent.getName())) {
                return;
            }
            WriteCommandAction.runWriteCommandAction(project, commandTitle, null, () -> {
                try {
                    VirtualFile targetParent = !parent.exists()
                            && LocalFileSystem.PROTOCOL.equals(parent.getFileSystem().getProtocol())
                        ? VfsUtil.createDirectories(parent.getPath())
                        : parent;
                    VirtualFile outputFile = targetParent.findChild(outputName);
                    if (outputFile != null) {
                        outputFile.setDetectedLineSeparator("\n");
                        Document targetDoc = FileDocumentManager.getInstance().getDocument(outputFile);
                        if (targetDoc != null) {
                            targetDoc.setText(normalizedLfContent);
                            PsiDocumentManager.getInstance(project).commitDocument(targetDoc);
                            FileDocumentManager.getInstance().saveDocument(targetDoc);
                        } else {
                            outputFile.setBinaryContent(normalizedLfContent.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        outputFile = targetParent.createChildData(StvnVopProjectionService.class, outputName);
                        outputFile.setDetectedLineSeparator("\n");
                        outputFile.setBinaryContent(normalizedLfContent.getBytes(StandardCharsets.UTF_8));
                    }
                    VfsUtil.markDirty(true, false, outputFile);
                    FileBasedIndex.getInstance().requestReindex(outputFile);
                    succeeded[0] = true;
                } catch (IOException ex) {
                    StvnWorkspaceNotificationHelper.showGenericError(project, ex);
                }
            });
        };

        var app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            vfsWriteTask.run();
        } else {
            app.invokeAndWait(vfsWriteTask);
        }
        return succeeded[0];
    }

    private static boolean confirmSiblingOverwrite(Project project, String outputName, String parentName) {
        Boolean override = overwriteConfirmationOverride;
        if (override != null) {
            return override;
        }
        var app = ApplicationManager.getApplication();
        if (app == null || app.isUnitTestMode() || app.isHeadlessEnvironment()) {
            return true;
        }
        return MessageDialogBuilder.yesNo(
            "STVN: Overwrite Existing Sibling File",
            "File '" + outputName + "' already exists in '" + parentName + "'. Do you want to overwrite it?"
        ).ask(project);
    }

    private static void reportCompilationDiagnostics(
            Project project,
            String entryFileName,
            List<StvnDiagnostic> diagnostics
    ) {
        for (StvnDiagnostic diag : diagnostics) {
            if (diag.severity() != DiagnosticSeverity.ERROR) {
                continue;
            }
            Throwable cause = diag.cause();
            if (cause instanceof CyclicDependencyException cde) {
                runOnEdt(() -> StvnWorkspaceNotificationHelper.showCycleError(project, cde));
                return;
            }
            if (cause instanceof NamespaceCollisionException nce) {
                runOnEdt(() -> StvnWorkspaceNotificationHelper.showCollisionError(project, nce));
                return;
            }
            if (cause instanceof DuplicateModuleImportException dmie) {
                runOnEdt(() -> StvnWorkspaceNotificationHelper.showDuplicateImportError(project, dmie));
                return;
            }
        }
        diagnostics.stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
            .findFirst()
            .ifPresent(err -> runOnEdt(() ->
                StvnWorkspaceNotificationHelper.showCompilationError(project, entryFileName, err)
            ));
    }

    private static void runOnEdt(Runnable runnable) {
        var app = ApplicationManager.getApplication();
        if (app == null || app.isDispatchThread()) {
            runnable.run();
        } else {
            app.invokeLater(runnable);
        }
    }

    private static StvnParserConfig resolveParserConfig(String fileName) {
        return StvnCompiledDocumentCache.isSystemSubstrateFileName(fileName)
                || fileName.contains(".stvn_core.")
            ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
            : StvnParserConfig.DEFAULT;
    }

    private static @Nullable PsiFile resolveTargetPsiFile(Project project, AnActionEvent event) {
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (psiFile != null) {
            return psiFile;
        }
        VirtualFile vf = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null && !vf.isDirectory()) {
            return PsiManager.getInstance(project).findFile(vf);
        }
        return null;
    }

    private static @Nullable Document resolveTargetDocument(
            Project project,
            AnActionEvent event,
            PsiFile psiFile,
            @Nullable VirtualFile vf
    ) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            Document psiDoc = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            if (psiDoc == null || psiDoc == editor.getDocument()) {
                return editor.getDocument();
            }
        }
        Document psiDoc = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (psiDoc != null) {
            return psiDoc;
        }
        return vf != null ? FileDocumentManager.getInstance().getDocument(vf) : null;
    }

    private static String resolveEffectiveKindKeyword(StvnDocument doc, String fileName) {
        if (doc.meta().isPresent() && doc.meta().get().kind().isPresent()) {
            return doc.meta().get().kind().get().keyword();
        }
        return StvnProjectionStemUtil.classifyTextualExtension(fileName)
            .map(ext -> switch (ext) {
                case STVN -> StvnVocabulary.KIND_KW_BODY;
                case STVN_I -> StvnVocabulary.KIND_KW_BODY_INCLUDE;
                case STVN_D -> StvnVocabulary.KIND_KW_DEFS;
                case STVN_DI -> StvnVocabulary.KIND_KW_DEFS_INCLUDE;
            })
            .orElse(StvnVocabulary.KIND_KW_BODY);
    }

    private static boolean isSiblingDestinationWritable(VirtualFile entryFile, String siblingName) {
        VirtualFile parent = entryFile.getParent();
        if (parent == null || !parent.isWritable()) {
            return false;
        }
        VirtualFile existingSibling = parent.findChild(siblingName);
        return existingSibling == null || existingSibling.isWritable();
    }

    private static void collectDirectoryRecursively(VirtualFile root, Map<String, String> workspaceMap) {
        VfsUtilCore.iterateChildrenRecursively(root, null, vf -> {
            if (!vf.isDirectory() && StvnProjectionStemUtil.isTextualStvnFileName(vf.getName())) {
                String normPath = StvnSchemaFlattener.normalizePath(vf.getPath());
                workspaceMap.putIfAbsent(normPath, loadLatestBufferOrDiskText(vf));
            }
            return true;
        });
    }
}
