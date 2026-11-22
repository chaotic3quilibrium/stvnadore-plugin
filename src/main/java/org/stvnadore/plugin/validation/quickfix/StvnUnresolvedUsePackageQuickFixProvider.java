package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnSchemaIndexHelper;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.StvnFlatDocumentIncludeInspection;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseTarget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Centralized quick-fix provider for unresolved {@code :use} package targets across both
 * modular compilation units ({@code E030} in {@code .stvn_i} / {@code .stvn_di}) and hermetic
 * compilation units ({@code E035} in {@code .stvn} / {@code .stvn_d}).
 * <p>
 * Guarantees 1:1 Pass A ({@code StvnExternalAnnotator}) and Pass B ({@code StvnFlatDocumentIncludeInspection})
 * quick-fix parity with zero duplicate {@code Alt+Enter} intentions.
 * </p>
 */
@NullMarked
public final class StvnUnresolvedUsePackageQuickFixProvider {

    private StvnUnresolvedUsePackageQuickFixProvider() {
    }

    /**
     * Summary of a single {@code :package} declaration and its exported symbols inside a STVN file.
     *
     * @param packagePath      canonical colon-prefixed package path (for example, {@code ":rov/nav"})
     * @param exportedSymbols  ordered list of exported {@code :Type} and {@code #CONST} short names
     * @param navigationOffset declaration text offset inside the declaring file
     */
    public record DeclaredPackageInfo(
            String packagePath,
            List<String> exportedSymbols,
            int navigationOffset
    ) {
    }

    /**
     * Workspace {@code .stvn_d} or {@code .stvn_di} module discovered relative to a host STVN file.
     *
     * @param moduleFile          resolved PSI file of the candidate schema module
     * @param virtualFile         underlying virtual file of the candidate module
     * @param normalizedVfPath    POSIX-normalized virtual file path
     * @param formattedRelPath    relative include path formatted per host file {@code "./"} conventions
     * @param declaredPackages    ordered map of non-reserved packages exported by the module
     * @param alreadyIncluded     {@code true} when already directly included by the host file
     * @param createsIncludeCycle {@code true} when {@code moduleFile} directly or transitively includes the host file
     */
    public record WorkspaceModuleCandidate(
            PsiFile moduleFile,
            VirtualFile virtualFile,
            String normalizedVfPath,
            String formattedRelPath,
            Map<String, DeclaredPackageInfo> declaredPackages,
            boolean alreadyIncluded,
            boolean createsIncludeCycle
    ) {
    }

    /**
     * Attaches the cohesive unresolved-{@code :use}-package quick-fix suite to an {@link AnnotationBuilder}
     * produced by {@code StvnExternalAnnotator} (for modular {@code E030} and Pass-A hermetic {@code E035}).
     *
     * @param builder        active annotation builder
     * @param file           annotated STVN PSI file
     * @param range          diagnostic highlight range
     * @param isHermeticE035 {@code true} when handling a hermetic {@code E035} diagnostic
     * @return updated annotation builder with quick-fixes attached
     */
    public static AnnotationBuilder registerAnnotatorFixes(
            AnnotationBuilder builder,
            PsiFile file,
            TextRange range,
            boolean isHermeticE035
    ) {
        UseTarget useTarget = resolveUseTargetAtRange(file, range);
        if (useTarget == null || hasTrailingSlash(useTarget)) {
            return builder;
        }
        String targetPkg = extractNonCommentText(useTarget).trim();
        if (targetPkg.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(targetPkg)) {
            return builder;
        }

        boolean treatAsHermetic = isHermeticE035 || StvnFlatDocumentIncludeInspection.isHermeticCompilationUnit(file);
        if (!treatAsHermetic && StvnPsiUtils.isModularDocument(file)) {
            for (WorkspaceModuleCandidate match : findWorkspaceDeclaringModules(file, targetPkg)) {
                builder = builder.withFix(new StvnIncludeModuleForPackageQuickFix(
                    useTarget,
                    match.formattedRelPath(),
                    targetPkg,
                    match.normalizedVfPath()
                ));
            }
        }

        if (isTextualStvnUnit(file)) {
            builder = builder.withFix(new StvnSearchAvailablePackagesQuickFix(useTarget, targetPkg));
        }

        builder = builder.withFix(new StvnRemoveUnresolvedUsePackageQuickFix(useTarget, targetPkg));

        if (treatAsHermetic && StvnConvertToModularQuickFix.canConvertToModular(file)) {
            builder = builder.withFix(
                new StvnConvertToModularQuickFix(useTarget, file.getName().endsWith(".stvn_d"))
            );
        }
        return builder;
    }

    /**
     * Creates the ordered {@link LocalQuickFix} array for {@code StvnFlatDocumentIncludeInspection}
     * on hermetic {@code E035} diagnostics, matching {@link #registerAnnotatorFixes} 1:1.
     *
     * @param file          active hermetic STVN PSI file
     * @param useTarget     offending {@link UseTarget} PSI element
     * @param targetPackage unresolved package path text
     * @return ordered list of quick-fixes ({@code Priority.HIGH}, {@code NORMAL}, {@code LOW})
     */
    public static List<LocalQuickFix> createLocalQuickFixes(
            PsiFile file,
            UseTarget useTarget,
            String targetPackage
    ) {
        List<LocalQuickFix> fixes = new ArrayList<>(3);
        if (isTextualStvnUnit(file)) {
            fixes.add(new StvnSearchAvailablePackagesQuickFix(useTarget, targetPackage));
        }
        fixes.add(new StvnRemoveUnresolvedUsePackageQuickFix(useTarget, targetPackage));
        if (StvnConvertToModularQuickFix.canConvertToModular(file)) {
            fixes.add(new StvnConvertToModularQuickFix(useTarget, file.getName().endsWith(".stvn_d")));
        }
        return fixes;
    }

    /**
     * Scans workspace {@code .stvn_d} and {@code .stvn_di} modules to find un-included, cycle-safe
     * modules that declare {@code targetPackage} (exact or hierarchical parent prefix match).
     *
     * @param hostFile      modular STVN PSI file containing the unresolved {@link UseTarget}
     * @param targetPackage unresolved package path (for example, {@code ":rov/telemetry"})
     * @return deterministically ordered list of matching workspace module candidates
     */
    public static List<WorkspaceModuleCandidate> findWorkspaceDeclaringModules(
            PsiFile hostFile,
            String targetPackage
    ) {
        if (!targetPackage.startsWith(StvnVocabulary.SIGIL_TYPIC)
                || StvnNamespaceRules.isReservedPackage(targetPackage)) {
            return List.of();
        }
        List<WorkspaceModuleCandidate> allModules = collectWorkspaceSchemaModules(hostFile.getProject(), hostFile);
        List<WorkspaceModuleCandidate> matches = new ArrayList<>();
        for (WorkspaceModuleCandidate candidate : allModules) {
            if (candidate.alreadyIncluded() || candidate.createsIncludeCycle()) {
                continue;
            }
            if (matchesDeclaredOrParentPackage(targetPackage, candidate.declaredPackages().keySet())) {
                matches.add(candidate);
            }
        }
        matches.sort(Comparator
            .<WorkspaceModuleCandidate, Boolean>comparing(c -> !c.declaredPackages().containsKey(targetPackage))
            .thenComparingInt(c -> countPathSegments(StvnIncludeModuleForPackageQuickFix.stripLeadingDotSlash(c.formattedRelPath())))
            .thenComparing(WorkspaceModuleCandidate::formattedRelPath));
        return List.copyOf(matches);
    }

    /**
     * Collects all workspace {@code .stvn_d} and {@code .stvn_di} schema modules relative to {@code hostFile},
     * computing {@link VirtualFile}-normalized direct-include status and reverse-include cycle flags.
     *
     * @param project  active IntelliJ project
     * @param hostFile host STVN PSI file
     * @return list of discovered workspace schema module candidates
     */
    public static List<WorkspaceModuleCandidate> collectWorkspaceSchemaModules(Project project, PsiFile hostFile) {
        PsiFile originHost = hostFile.getOriginalFile();
        VirtualFile hostVf = originHost.getVirtualFile();
        if (hostVf == null) {
            hostVf = hostFile.getVirtualFile();
        }
        String hostNormVfPath = hostVf != null ? StvnSchemaFlattener.normalizePath(hostVf.getPath()) : "";

        Set<String> directIncludedVfPaths =
            StvnIncludeModuleForPackageQuickFix.collectDirectIncludedNormalizedVfPaths(hostFile);

        Map<String, VirtualFile> candidateVfs = new LinkedHashMap<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        for (VirtualFile vf : FileTypeIndex.getFiles(StvnFileType.Defs.INSTANCE, scope)) {
            registerCandidateVf(vf, hostNormVfPath, candidateVfs);
        }
        for (VirtualFile vf : FileTypeIndex.getFiles(StvnFileType.DefsInclude.INSTANCE, scope)) {
            registerCandidateVf(vf, hostNormVfPath, candidateVfs);
        }
        for (VirtualFile contentRoot : ProjectRootManager.getInstance(project).getContentRoots()) {
            collectSchemaVfsRecursively(contentRoot, hostNormVfPath, candidateVfs);
        }
        if (hostVf != null && hostVf.getParent() != null) {
            collectSchemaVfsRecursively(hostVf.getParent(), hostNormVfPath, candidateVfs);
        }

        PsiManager psiManager = PsiManager.getInstance(project);
        List<WorkspaceModuleCandidate> results = new ArrayList<>();
        for (Map.Entry<String, VirtualFile> entry : candidateVfs.entrySet()) {
            String candNormPath = entry.getKey();
            VirtualFile candVf = entry.getValue();
            PsiFile candPsi = psiManager.findFile(candVf);
            if (candPsi == null || candPsi == originHost || StvnPreludeBridge.isPreludeFile(candPsi)) {
                continue;
            }
            String rawRelPath = StvnSchemaIndexHelper.computeRelativeIncludePath(originHost, candPsi);
            if (rawRelPath == null || rawRelPath.isEmpty()) {
                rawRelPath = candVf.getName();
            }
            String formattedRel = StvnIncludeModuleForPackageQuickFix.formatIncludeRelativePath(hostFile, rawRelPath);
            boolean alreadyIncluded = directIncludedVfPaths.contains(candNormPath)
                || StvnIncludeModuleForPackageQuickFix.hasDirectIncludePath(hostFile, formattedRel, candNormPath);
            boolean cyclic = !hostNormVfPath.isEmpty() && transitivelyIncludes(candPsi, hostNormVfPath);
            Map<String, DeclaredPackageInfo> declaredPkgs = collectDeclaredPackagesInFile(candPsi, false);
            results.add(new WorkspaceModuleCandidate(
                candPsi,
                candVf,
                candNormPath,
                formattedRel,
                declaredPkgs,
                alreadyIncluded,
                cyclic
            ));
        }
        return List.copyOf(results);
    }

    /**
     * Collects all modules transitively {@code :include}d by {@code rootFile} in breadth-first order.
     *
     * @param rootFile starting modular STVN file
     * @return ordered list of resolved transitive included PSI files
     */
    public static List<PsiFile> collectTransitiveIncludedFiles(PsiFile rootFile) {
        List<PsiFile> collected = new ArrayList<>();
        Set<String> visitedNormPaths = new HashSet<>();
        VirtualFile rootVf = rootFile.getOriginalFile().getVirtualFile();
        if (rootVf != null) {
            visitedNormPaths.add(StvnSchemaFlattener.normalizePath(rootVf.getPath()));
        }
        Queue<PsiFile> queue = new ArrayDeque<>();
        queue.add(rootFile);
        while (!queue.isEmpty()) {
            PsiFile curr = queue.poll();
            for (IncludeStmt inc : PsiTreeUtil.findChildrenOfType(curr, IncludeStmt.class)) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(inc)) {
                    continue;
                }
                for (IncludeElement elem : inc.getIncludeElementList()) {
                    StringLiteral strLit = elem.getStringLiteral();
                    if (strLit == null) {
                        continue;
                    }
                    PsiFile resolved = StvnTypeReference.resolveIncludeFile(strLit);
                    if (resolved == null) {
                        continue;
                    }
                    VirtualFile resVf = resolved.getOriginalFile().getVirtualFile();
                    if (resVf == null) {
                        resVf = resolved.getVirtualFile();
                    }
                    String key = resVf != null
                        ? StvnSchemaFlattener.normalizePath(resVf.getPath())
                        : resolved.getName();
                    if (visitedNormPaths.add(key)) {
                        collected.add(resolved);
                        queue.add(resolved);
                    }
                }
            }
        }
        return List.copyOf(collected);
    }

    /**
     * Collects all {@code :package} declarations (and top-level qualified {@code :pkg/Type} /
     * {@code #pkg/CONST} package prefixes) inside {@code file}'s primary {@code :defs} section.
     *
     * @param file                 STVN PSI file to inspect
     * @param allowReservedPrelude {@code true} when scanning the synthetic Standard Library Prelude
     * @return ordered map from package path to {@link DeclaredPackageInfo}
     */
    public static Map<String, DeclaredPackageInfo> collectDeclaredPackagesInFile(
            PsiFile file,
            boolean allowReservedPrelude
    ) {
        Map<String, List<String>> symbolsByPkg = new LinkedHashMap<>();
        Map<String, Integer> offsetByPkg = new LinkedHashMap<>();

        for (DefsEntry defsEntry : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defsEntry)) {
                continue;
            }
            for (DefsElement elem : defsEntry.getDefsElementList()) {
                PackageEnclosure pkg = elem.getPackageEnclosure();
                if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
                    String pkgPath = pkg.getPackagePath().getText().trim();
                    if (!pkgPath.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
                        continue;
                    }
                    if (!allowReservedPrelude && StvnNamespaceRules.isReservedPackage(pkgPath)) {
                        continue;
                    }
                    offsetByPkg.putIfAbsent(pkgPath, pkg.getPackagePath().getTextOffset());
                    List<String> exported = symbolsByPkg.computeIfAbsent(pkgPath, k -> new ArrayList<>());
                    for (var pkgElem : pkg.getPackageElementList()) {
                        TypeDefinition td = pkgElem.getTypeDefinition();
                        if (td != null && td.getTypeKeyword() != null) {
                            String sym = td.getTypeKeyword().getText().trim();
                            if (!exported.contains(sym)) {
                                exported.add(sym);
                            }
                        }
                        ConstantDefinition cd = pkgElem.getConstantDefinition();
                        if (cd != null && cd.getValueKeyword() != null) {
                            String sym = cd.getValueKeyword().getText().trim();
                            if (!exported.contains(sym)) {
                                exported.add(sym);
                            }
                        }
                    }
                } else if (elem.getTypeDefinition() != null && elem.getTypeDefinition().getTypeDefTarget() != null) {
                    TypeDefinition td = elem.getTypeDefinition();
                    String rawName = td.getTypeDefTarget().getText().trim();
                    int lastSlash = rawName.lastIndexOf('/');
                    if (rawName.startsWith(StvnVocabulary.SIGIL_TYPIC) && lastSlash > 1) {
                        String pkgPrefix = rawName.substring(0, lastSlash);
                        if (allowReservedPrelude || !StvnNamespaceRules.isReservedPackage(pkgPrefix)) {
                            offsetByPkg.putIfAbsent(pkgPrefix, td.getTypeDefTarget().getTextOffset());
                            String shortType = StvnVocabulary.SIGIL_TYPIC + rawName.substring(lastSlash + 1);
                            List<String> exported = symbolsByPkg.computeIfAbsent(pkgPrefix, k -> new ArrayList<>());
                            if (!exported.contains(shortType)) {
                                exported.add(shortType);
                            }
                        }
                    }
                } else if (elem.getConstantDefinition() != null && elem.getConstantDefinition().getValueKeyword() != null) {
                    ConstantDefinition cd = elem.getConstantDefinition();
                    String rawConst = cd.getValueKeyword().getText().trim();
                    int lastSlash = rawConst.lastIndexOf('/');
                    if (rawConst.startsWith(StvnVocabulary.SIGIL_VALUE) && lastSlash > 1) {
                        String pkgPrefix = StvnVocabulary.SIGIL_TYPIC + rawConst.substring(1, lastSlash);
                        if (allowReservedPrelude || !StvnNamespaceRules.isReservedPackage(pkgPrefix)) {
                            offsetByPkg.putIfAbsent(pkgPrefix, cd.getValueKeyword().getTextOffset());
                            String shortConst = StvnVocabulary.SIGIL_VALUE + rawConst.substring(lastSlash + 1);
                            List<String> exported = symbolsByPkg.computeIfAbsent(pkgPrefix, k -> new ArrayList<>());
                            if (!exported.contains(shortConst)) {
                                exported.add(shortConst);
                            }
                        }
                    }
                }
            }
            break;
        }

        Map<String, DeclaredPackageInfo> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : symbolsByPkg.entrySet()) {
            String pkgPath = entry.getKey();
            out.put(pkgPath, new DeclaredPackageInfo(
                pkgPath,
                List.copyOf(entry.getValue()),
                offsetByPkg.getOrDefault(pkgPath, 0)
            ));
        }
        return out;
    }

    private static boolean transitivelyIncludes(PsiFile candidateModule, String targetHostNormVfPath) {
        if (!candidateModule.getName().endsWith(".stvn_di")) {
            return false;
        }
        for (PsiFile included : collectTransitiveIncludedFiles(candidateModule)) {
            VirtualFile vf = included.getOriginalFile().getVirtualFile();
            if (vf == null) {
                vf = included.getVirtualFile();
            }
            if (vf != null && targetHostNormVfPath.equals(StvnSchemaFlattener.normalizePath(vf.getPath()))) {
                return true;
            }
        }
        return false;
    }

    private static void registerCandidateVf(
            VirtualFile vf,
            String hostNormVfPath,
            Map<String, VirtualFile> accumulator
    ) {
        if (vf.isDirectory() || StvnPreludeBridge.isPreludeFile(vf)) {
            return;
        }
        String name = vf.getName();
        if (!name.endsWith(".stvn_d") && !name.endsWith(".stvn_di")) {
            return;
        }
        String normPath = StvnSchemaFlattener.normalizePath(vf.getPath());
        if (!normPath.equals(hostNormVfPath)) {
            accumulator.putIfAbsent(normPath, vf);
        }
    }

    private static void collectSchemaVfsRecursively(
            VirtualFile root,
            String hostNormVfPath,
            Map<String, VirtualFile> accumulator
    ) {
        VfsUtilCore.iterateChildrenRecursively(root, null, vf -> {
            registerCandidateVf(vf, hostNormVfPath, accumulator);
            return true;
        });
    }

    private static boolean matchesDeclaredOrParentPackage(String target, Set<String> declaredPackages) {
        if (declaredPackages.contains(target)) {
            return true;
        }
        String prefix = target + "/";
        for (String pkg : declaredPackages) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int countPathSegments(String path) {
        int slashes = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                slashes++;
            }
        }
        return slashes;
    }

    private static boolean isTextualStvnUnit(PsiFile file) {
        String name = file.getOriginalFile().getName();
        return !name.endsWith(".stvn_b") && !name.endsWith(".stvn_bd");
    }

    private static @Nullable UseTarget resolveUseTargetAtRange(PsiFile file, TextRange range) {
        UseTarget direct = PsiTreeUtil.findElementOfClassAtRange(
            file,
            range.getStartOffset(),
            range.getEndOffset(),
            UseTarget.class
        );
        if (direct != null) {
            return direct;
        }
        PsiElement leaf = file.findElementAt(range.getStartOffset());
        return leaf != null ? PsiTreeUtil.getParentOfType(leaf, UseTarget.class, false) : null;
    }

    private static boolean hasTrailingSlash(UseTarget useTarget) {
        if (useTarget.getUseTargetIllegal() != null) {
            return true;
        }
        if (extractNonCommentText(useTarget).endsWith("/")) {
            return true;
        }
        PsiElement sibling = useTarget.getNextSibling();
        while (sibling instanceof PsiWhiteSpace || sibling instanceof PsiComment) {
            sibling = sibling.getNextSibling();
        }
        if (sibling == null) {
            return false;
        }
        if (sibling.getNode().getElementType() == StvnTypes.FSLASH) {
            return true;
        }
        String siblingText = sibling.getText().trim();
        return siblingText.startsWith("/") && !siblingText.startsWith("//") && !siblingText.startsWith("/*");
    }

    private static String extractNonCommentText(PsiElement element) {
        StringBuilder sb = new StringBuilder();
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof PsiComment) && !(child instanceof PsiWhiteSpace)) {
                sb.append(child.getText());
            }
        }
        return sb.length() > 0 ? sb.toString() : element.getText().trim();
    }
}
