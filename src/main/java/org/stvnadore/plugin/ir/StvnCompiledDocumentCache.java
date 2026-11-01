package org.stvnadore.plugin.ir;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.DefsEntry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Thread-safe, per-{@link PsiFile} cache storing immutable STVN 2.0.0 pure-IR compilation results.
 */
@NullMarked
public final class StvnCompiledDocumentCache {

    private static final Key<Path> VFS_TEMP_DIR_KEY =
        Key.create("org.stvnadore.plugin.ir.VFS_TEMP_DIR");

    /**
     * Immutable pure-IR compilation snapshot bound to a {@link PsiFile} modification stamp.
     *
     * @param normalizedDocPath normalized POSIX-style document path used during compilation
     * @param document          lowered pure-IR {@link StvnDocument}, when produced by the compiler
     * @param catalog           lowered pure-IR {@link StvnDefsCatalog} (recovered from {@code :defs} on partial errors)
     * @param rootType          lowered root {@code :type} {@link StvnSchema}, when present
     * @param payload           lowered root {@code :body} {@link StvnValue}, when present
     * @param diagnostics       immutable list of compiler diagnostics accumulated for the compilation unit
     */
    public record CompiledSnapshot(
        String normalizedDocPath,
        Optional<StvnDocument> document,
        StvnDefsCatalog catalog,
        Optional<StvnSchema> rootType,
        Optional<StvnValue> payload,
        List<StvnDiagnostic> diagnostics
    ) {
        /**
         * Returns the resolved root payload schema, matching {@link #rootType()}.
         *
         * @return optional root {@link StvnSchema}
         */
        public Optional<StvnSchema> rootPayloadSchema() {
            return rootType;
        }
    }

    private StvnCompiledDocumentCache() {
    }

    /**
     * Returns the cached pure-IR {@link CompiledSnapshot} for {@code psiFile}.
     *
     * @param psiFile active STVN PSI file
     * @return immutable pure-IR snapshot
     */
    public static CompiledSnapshot getSnapshot(PsiFile psiFile) {
        return CachedValuesManager.getCachedValue(psiFile, () -> {
            CompiledSnapshot snapshot = compilePsiFile(psiFile);
            return CachedValueProvider.Result.create(
                snapshot,
                psiFile,
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            );
        });
    }

    /**
     * Determines whether {@code fileName} belongs to a reserved STVN system substrate enclave.
     *
     * @param fileName simple file name including extensions
     * @return {@code true} when system substrate parser configuration applies
     */
    public static boolean isSystemSubstrateFileName(String fileName) {
        return fileName.contains(".stvn_ir.")
            || fileName.contains(".stvn_cas.")
            || fileName.contains(".stvn_core.")
            || "prelude.stvn_d".equals(fileName)
            || "org_stvnadore_prelude.stvn_d".equals(fileName);
    }

    /**
     * Resolves a physical filesystem path for {@code virtualFile}, staging in-memory VFS files and
     * unsaved open editor buffers into a per-project temporary directory when {@code :include}
     * resolution requires disk visibility.
     *
     * @param virtualFile virtual file being compiled, or {@code null} for synthetic buffers
     * @param project     active IntelliJ project, or {@code null}
     * @param text        current UTF-8 buffer text of the active document
     * @return physical path string suitable for {@link StvnCompiler#compileToResult}
     */
    public static String resolvePhysicalPath(
        @Nullable VirtualFile virtualFile,
        @Nullable Project project,
        String text
    ) {
        if (virtualFile == null) {
            return "synthetic.stvn";
        }
        boolean localExists = virtualFile.isInLocalFileSystem() && new File(virtualFile.getPath()).exists();
        boolean needsIncludeStaging = text.contains(":include")
            && (!localExists || hasUnsavedStvnBuffers());
        if (localExists && !needsIncludeStaging) {
            return virtualFile.getPath();
        }
        if (!needsIncludeStaging) {
            return virtualFile.getPath();
        }

        try {
            Path tempDir = getOrCreateProjectTempDir(project);
            VirtualFile root = findStagingRoot(virtualFile, project);
            materializeVirtualTree(root, tempDir);

            String relativePath = getRelativeVirtualPath(root, virtualFile);
            Path targetFile = tempDir.resolve(relativePath);
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(targetFile, text, StandardCharsets.UTF_8);
            return targetFile.toAbsolutePath().toString();
        } catch (IOException ex) {
            return virtualFile.getPath();
        }
    }

    /**
     * Collects a virtual workspace map overlaying unsaved editor buffers atop project STVN files.
     *
     * @param project              active IntelliJ project
     * @param activeFile           active PSI file
     * @param activeNormalizedPath normalized path of active file
     * @return map of normalized file path to UTF-8 text content
     */
    public static Map<String, String> collectWorkspaceOverlay(
        Project project,
        PsiFile activeFile,
        String activeNormalizedPath
    ) {
        Map<String, String> overlay = new LinkedHashMap<>();
        FileDocumentManager docMgr = FileDocumentManager.getInstance();
        VirtualFile activeVf = activeFile.getVirtualFile();
        VirtualFile rootDir = (activeVf != null && activeVf.getParent() != null)
            ? activeVf.getParent()
            : null;

        for (VirtualFile contentRoot : ProjectRootManager.getInstance(project).getContentRoots()) {
            collectStvnFilesUnderRoot(contentRoot, docMgr, overlay);
        }
        if (rootDir != null) {
            collectStvnFilesUnderRoot(rootDir, docMgr, overlay);
        }
        overlay.put(activeNormalizedPath, activeFile.getText());
        return overlay;
    }

    private static void collectStvnFilesUnderRoot(
        VirtualFile root,
        FileDocumentManager docMgr,
        Map<String, String> overlay
    ) {
        VfsUtilCore.iterateChildrenRecursively(root, null, vf -> {
            if (!vf.isDirectory() && isTextStvnExtension(vf.getName())) {
                String normPath = StvnSchemaFlattener.normalizePath(vf.getPath());
                if (!overlay.containsKey(normPath)) {
                    var cachedDoc = docMgr.getCachedDocument(vf);
                    if (cachedDoc != null) {
                        overlay.put(normPath, cachedDoc.getText());
                    } else {
                        try {
                            overlay.put(normPath, new String(vf.contentsToByteArray(), StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                            // Unreadable VFS entry skipped cleanly
                        }
                    }
                }
            }
            return true;
        });
    }

    private static boolean isTextStvnExtension(String fileName) {
        return fileName.endsWith(".stvn")
            || fileName.endsWith(".stvn_i")
            || fileName.endsWith(".stvn_d")
            || fileName.endsWith(".stvn_di");
    }

    private static boolean isBinaryStvnExtension(String fileName) {
        return fileName.endsWith(".stvn_b") || fileName.endsWith(".stvn_bd");
    }

    private static CompiledSnapshot compilePsiFile(PsiFile psiFile) {
        VirtualFile vf = psiFile.getVirtualFile();
        String fileName = vf != null ? vf.getName() : psiFile.getName();
        boolean systemMode = isSystemSubstrateFileName(fileName) || StvnPreludeBridge.isPreludeFile(psiFile);

        StvnParserConfig config = systemMode
            ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
            : StvnParserConfig.DEFAULT;

        if (vf != null && isBinaryStvnExtension(fileName)) {
            String rawPath = vf.getPath();
            String normalizedPath = StvnSchemaFlattener.normalizePath(rawPath);
            try {
                byte[] bytes = vf.contentsToByteArray();
                StvnCompilationResult<StvnDocument> binResult =
                    StvnCompiler.compileToResult(bytes, rawPath, config);
                return buildSnapshot(normalizedPath, binResult, null, rawPath, config);
            } catch (IOException ex) {
                return new CompiledSnapshot(
                    normalizedPath,
                    Optional.empty(),
                    StvnDefsCatalog.EMPTY,
                    Optional.empty(),
                    Optional.empty(),
                    List.of()
                );
            }
        }

        String text = psiFile.getText();
        String resolvedPath = vf != null
            ? resolvePhysicalPath(vf, psiFile.getProject(), text)
            : fileName;
        String normalizedPath = StvnSchemaFlattener.normalizePath(resolvedPath);

        if (text.isBlank()) {
            return new CompiledSnapshot(
                normalizedPath,
                Optional.empty(),
                StvnDefsCatalog.EMPTY,
                Optional.empty(),
                Optional.empty(),
                List.of()
            );
        }

        synchronized (normalizedPath.intern()) {
            StvnCompilationResult<StvnDocument> result =
                StvnCompiler.compileToResult(text, resolvedPath, config);
            return buildSnapshot(normalizedPath, result, psiFile, resolvedPath, config);
        }
    }

    private static CompiledSnapshot buildSnapshot(
        String normalizedPath,
        StvnCompilationResult<StvnDocument> result,
        @Nullable PsiFile psiFile,
        String resolvedPath,
        StvnParserConfig config
    ) {
        Optional<StvnDocument> docOpt = result.document();
        StvnDefsCatalog catalog = docOpt.map(StvnDocument::defs).orElse(StvnDefsCatalog.EMPTY);
        if (docOpt.isEmpty() && psiFile != null) {
            catalog = recoverDefsCatalog(psiFile, resolvedPath, config);
        }
        Optional<StvnSchema> rootSchema = docOpt.flatMap(StvnDocument::rootType);
        Optional<StvnValue> payload = docOpt.flatMap(StvnDocument::payload);

        return new CompiledSnapshot(
            normalizedPath,
            docOpt,
            catalog,
            rootSchema,
            payload,
            List.copyOf(result.diagnostics())
        );
    }

    /**
     * Recovers lowered {@link StvnDefsCatalog} entries when errors outside {@code :defs} (or inside
     * a {@code :body}-less {@code .stvn_d} module) prevent {@link StvnCompiler} from emitting a full
     * {@link StvnDocument}.
     */
    private static StvnDefsCatalog recoverDefsCatalog(
        PsiFile psiFile,
        String resolvedPath,
        StvnParserConfig config
    ) {
        DefsEntry defsEntry = PsiTreeUtil.findChildOfType(psiFile, DefsEntry.class);
        if (defsEntry == null) {
            return StvnDefsCatalog.EMPTY;
        }
        String defsText = defsEntry.getText();
        if (defsText == null || defsText.isBlank()) {
            return StvnDefsCatalog.EMPTY;
        }
        String probeSource = "{\n  " + defsText + "\n  :type :Boolean\n  :body #TRUE\n}";
        boolean hasInclude = defsText.contains(":include");
        String probePath = deriveSiblingProbePath(resolvedPath, hasInclude);
        StvnParserConfig probeConfig = new StvnParserConfig(
            false,
            config.maxDiagnostics(),
            true
        );
        StvnCompilationResult<StvnDocument> probeResult =
            StvnCompiler.compileToResult(probeSource, probePath, probeConfig);
        if (probeResult.document().isPresent()) {
            return probeResult.document().get().defs();
        }
        String sanitizedDefs = buildSanitizedDefsProbeText(defsEntry);
        if (sanitizedDefs != null) {
            String sanitizedProbeSource = "{\n  " + sanitizedDefs + "\n  :type :Boolean\n  :body #TRUE\n}";
            StvnCompilationResult<StvnDocument> sanitizedResult =
                StvnCompiler.compileToResult(sanitizedProbeSource, probePath, probeConfig);
            if (sanitizedResult.document().isPresent()) {
                return sanitizedResult.document().get().defs();
            }
        }
        return StvnDefsCatalog.EMPTY;
    }

    private static @Nullable String buildSanitizedDefsProbeText(DefsEntry defsEntry) {
        StringBuilder sb = new StringBuilder(":defs {\n");
        boolean anyKept = false;
        for (var child : defsEntry.getChildren()) {
            if (child instanceof org.stvnadore.psi.PackageEnclosure pkg) {
                if (!PsiTreeUtil.hasErrorElements(pkg)) {
                    sb.append("    ").append(pkg.getText()).append("\n");
                    anyKept = true;
                } else if (pkg.getPackagePath() != null && !PsiTreeUtil.hasErrorElements(pkg.getPackagePath())) {
                    StringBuilder pkgSb = new StringBuilder("    :package ")
                        .append(pkg.getPackagePath().getText())
                        .append(" {\n");
                    boolean pkgKept = false;
                    for (var elem : pkg.getPackageElementList()) {
                        if (!PsiTreeUtil.hasErrorElements(elem)) {
                            pkgSb.append("      ").append(elem.getText()).append("\n");
                            pkgKept = true;
                        }
                    }
                    pkgSb.append("    }\n");
                    if (pkgKept) {
                        sb.append(pkgSb);
                        anyKept = true;
                    }
                }
            } else if (!PsiTreeUtil.hasErrorElements(child)) {
                sb.append("    ").append(child.getText()).append("\n");
                anyKept = true;
            }
        }
        sb.append("  }");
        return anyKept ? sb.toString() : null;
    }

    private static String deriveSiblingProbePath(String resolvedPath, boolean hasInclude) {
        String probeFileName = hasInclude ? "__stvn_defs_probe.stvn_i" : "__stvn_defs_probe.stvn";
        String posix = resolvedPath.replace('\\', '/');
        int slash = posix.lastIndexOf('/');
        if (slash >= 0) {
            return posix.substring(0, slash + 1) + probeFileName;
        }
        return probeFileName;
    }

    private static boolean hasUnsavedStvnBuffers() {
        FileDocumentManager docMgr = FileDocumentManager.getInstance();
        for (var doc : docMgr.getUnsavedDocuments()) {
            VirtualFile vf = docMgr.getFile(doc);
            if (vf != null && isTextStvnExtension(vf.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Path getOrCreateProjectTempDir(@Nullable Project project) throws IOException {
        Path tempDir = project != null ? project.getUserData(VFS_TEMP_DIR_KEY) : null;
        if (tempDir == null || !Files.exists(tempDir)) {
            tempDir = Files.createTempDirectory("stvn_vfs_");
            if (project != null) {
                project.putUserData(VFS_TEMP_DIR_KEY, tempDir);
            }
        }
        return tempDir;
    }

    private static VirtualFile findStagingRoot(VirtualFile virtualFile, @Nullable Project project) {
        if (!virtualFile.isInLocalFileSystem()) {
            VirtualFile root = virtualFile;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            return root;
        }
        if (project != null) {
            for (VirtualFile contentRoot : ProjectRootManager.getInstance(project).getContentRoots()) {
                if (VfsUtilCore.isAncestor(contentRoot, virtualFile, false)) {
                    return contentRoot;
                }
            }
        }
        return virtualFile.getParent() != null ? virtualFile.getParent() : virtualFile;
    }

    private static void materializeVirtualTree(VirtualFile dir, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        FileDocumentManager docMgr = FileDocumentManager.getInstance();
        VirtualFile[] children = dir.getChildren();
        Set<String> activeNames = new HashSet<>();
        for (VirtualFile child : children) {
            activeNames.add(child.getName());
            Path targetPath = targetDir.resolve(child.getName());
            if (child.isDirectory()) {
                materializeVirtualTree(child, targetPath);
            } else if (isTextStvnExtension(child.getName()) || isBinaryStvnExtension(child.getName())) {
                var cachedDoc = isTextStvnExtension(child.getName()) ? docMgr.getCachedDocument(child) : null;
                if (cachedDoc != null) {
                    Files.writeString(targetPath, cachedDoc.getText(), StandardCharsets.UTF_8);
                } else {
                    Files.write(targetPath, child.contentsToByteArray());
                }
            }
        }
        try (Stream<Path> existing = Files.list(targetDir)) {
            for (Path entry : existing.toList()) {
                String name = entry.getFileName().toString();
                if (!activeNames.contains(name)) {
                    deleteRecursively(entry);
                }
            }
        }
    }

    private static void deleteRecursively(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            try (Stream<Path> walk = Files.list(entry)) {
                for (Path child : walk.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(entry);
    }

    private static String getRelativeVirtualPath(VirtualFile root, VirtualFile target) {
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (targetPath.startsWith(rootPath)) {
            String rel = targetPath.substring(rootPath.length());
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            if (!rel.isEmpty()) {
                return rel.replace('/', File.separatorChar);
            }
        }
        return target.getName();
    }
}
