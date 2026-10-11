package org.stvnadore.plugin.reference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightVirtualFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

/**
 * Bridge providing an in-memory synthetic AST of the canonical standard library prelude
 * (:org/stvnadore/prelude/*) for navigation, documentation, and reference resolution.
 */
@NullMarked
public final class StvnPreludeBridge {

    private static final Key<PsiFile> PRELUDE_PSI_FILE_KEY = Key.create("STVN_PRELUDE_PSI_FILE");
    private static final Key<LightVirtualFile> PRELUDE_VIRTUAL_FILE_KEY = Key.create("STVN_PRELUDE_VIRTUAL_FILE");

    private StvnPreludeBridge() {
    }

    /**
     * Obtains the single canonical in-memory VirtualFile for the standard library prelude.
     *
     * @param project the active IntelliJ project
     * @return the shared LightVirtualFile representing prelude.stvn_d
     */
    public static LightVirtualFile getPreludeVirtualFile(Project project) {
        var file = getPreludeFile(project);
        var vFile = file.getViewProvider().getVirtualFile();
        if (vFile instanceof LightVirtualFile lvf) {
            return lvf;
        }
        var customVFile = project.getUserData(PRELUDE_VIRTUAL_FILE_KEY);
        if (customVFile == null || !customVFile.isValid()) {
            customVFile = new LightVirtualFile("prelude.stvn_d", StvnFileType.Defs.INSTANCE, StvnPrelude.PRELUDE_STVN_D);
            customVFile.setWritable(false);
            project.putUserData(PRELUDE_VIRTUAL_FILE_KEY, customVFile);
        }
        return customVFile;
    }

    /**
     * Retrieves the cached synthetic AST file representing the canonical standard library prelude.
     *
     * @param project the active IntelliJ project
     * @return the synthetic prelude PSI file
     */
    public static PsiFile getPreludeFile(Project project) {
        var file = project.getUserData(PRELUDE_PSI_FILE_KEY);
        if (file == null || !file.isValid()) {
            file = PsiFileFactory.getInstance(project).createFileFromText(
                "prelude.stvn_d",
                StvnFileType.Defs.INSTANCE,
                StvnPrelude.PRELUDE_STVN_D
            );
            project.putUserData(PRELUDE_PSI_FILE_KEY, file);
        }
        return file;
    }

    /**
     * Resolves the canonical package namespace path of a prelude type definition.
     *
     * @param def the TypeDefinition AST node from the prelude
     * @return the package namespace path (for example, {@code ":org/stvnadore/prelude/meta"})
     */
    public static String getPreludePackagePath(TypeDefinition def) {
        var pkg = PsiTreeUtil.getParentOfType(def, org.stvnadore.psi.PackageEnclosure.class);
        if (pkg != null && pkg.getPackagePath() != null) {
            return pkg.getPackagePath().getText();
        }
        return ":org/stvnadore/prelude";
    }

    /**
     * Checks whether the specified file is a standard library prelude file.
     *
     * @param file the PSI file to inspect
     * @return {@code true} if the file name matches canonical prelude naming conventions
     */
    public static boolean isPreludeFile(@Nullable PsiFile file) {
        if (file == null) return false;
        var name = file.getName();
        return "prelude.stvn_d".equals(name) || "org_stvnadore_prelude.stvn_d".equals(name)
            || "prelude.stvn_inclf".equals(name) || "org_stvnadore_prelude.stvn_inclf".equals(name);
    }

    /**
     * Resolves the canonical package namespace path for a prelude type by nominal name or FQNI.
     *
     * @param project the active IntelliJ project
     * @param nameOrFqni the type identifier or FQNI to resolve
     * @return the package namespace path, or {@code null} if not found in the prelude
     */
    public static @Nullable String resolvePreludePackagePath(Project project, String nameOrFqni) {
        var def = resolvePreludeTypeDefinition(project, nameOrFqni);
        return def != null ? getPreludePackagePath(def) : null;
    }

    /**
     * Resolves a prelude type definition AST node by nominal name or fully qualified nominal identifier (FQNI).
     *
     * @param project the active IntelliJ project
     * @param nameOrFqni the type identifier or FQNI to resolve
     * @return the resolved TypeDefinition element, or {@code null} if not found in the prelude
     */
    public static @Nullable TypeDefinition resolvePreludeTypeDefinition(Project project, String nameOrFqni) {
        var file = getPreludeFile(project);
        return resolvePreludeTypeDefinition(file, nameOrFqni);
    }

    /**
     * Resolves a prelude type definition AST node directly within the specified file.
     *
     * @param file the target prelude PSI file to scan
     * @param nameOrFqni the type identifier or FQNI to resolve
     * @return the resolved TypeDefinition element, or {@code null} if not found
     */
    public static @Nullable TypeDefinition resolvePreludeTypeDefinition(PsiFile file, String nameOrFqni) {
        var normName = nameOrFqni.startsWith(":") ? nameOrFqni : ":" + nameOrFqni;

        var packages = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class);
        for (var pkg : packages) {
            var pkgPath = pkg.getPackagePath();
            var pkgPrefix = pkgPath != null ? pkgPath.getText() : "";
            var defs = PsiTreeUtil.findChildrenOfType(pkg, TypeDefinition.class);
            for (var def : defs) {
                var kw = def.getTypeKeyword();
                if (kw == null) continue;
                var localName = kw.getText();
                var fqni = pkgPrefix + "/" + (localName.startsWith(":") ? localName.substring(1) : localName);
                if (localName.equals(normName) || fqni.equals(normName)) {
                    return def;
                }
            }
        }

        var rootDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : rootDefs) {
            var kw = def.getTypeKeyword();
            if (kw != null && kw.getText().equals(normName)) {
                return def;
            }
        }
        return null;
    }

    /**
     * Resolves a prelude type keyword by nominal name or fully qualified nominal identifier (FQNI).
     *
     * @param project the active IntelliJ project
     * @param nameOrFqni the type identifier or FQNI to resolve
     * @return the resolved TypeKeyword element, or {@code null} if not found in the prelude
     */
    public static @Nullable TypeKeyword resolvePreludeType(Project project, String nameOrFqni) {
        var def = resolvePreludeTypeDefinition(project, nameOrFqni);
        return def != null ? def.getTypeKeyword() : null;
    }
}
