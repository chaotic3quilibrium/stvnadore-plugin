package org.stvnadore.plugin.reference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightVirtualFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.ValueKeyword;

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
     * @return the shared LightVirtualFile representing prelude.stvn_core.stvn_d
     */
    public static LightVirtualFile getPreludeVirtualFile(Project project) {
        var file = getPreludeFile(project);
        var vFile = file.getViewProvider().getVirtualFile();
        if (vFile instanceof LightVirtualFile lvf) {
            lvf.setWritable(false);
            return lvf;
        }
        var customVFile = project.getUserData(PRELUDE_VIRTUAL_FILE_KEY);
        if (customVFile == null || !customVFile.isValid()) {
            customVFile = new LightVirtualFile("prelude.stvn_core.stvn_d", StvnFileType.Defs.INSTANCE, StvnPrelude.PRELUDE_STVN_D);
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
                "prelude.stvn_core.stvn_d",
                StvnFileType.Defs.INSTANCE,
                StvnPrelude.PRELUDE_STVN_D
            );
            var vFile = file.getViewProvider().getVirtualFile();
            if (vFile instanceof LightVirtualFile lvf) {
                lvf.setWritable(false);
            }
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
        return getPreludePackagePath((PsiElement) def);
    }

    /**
     * Resolves the canonical package namespace path of a prelude AST element.
     *
     * @param element the AST node from the prelude (TypeDefinition or ConstantDefinition)
     * @return the package namespace path (for example, {@code ":org/stvnadore/prelude"})
     */
    public static String getPreludePackagePath(PsiElement element) {
        var pkg = PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.PackageEnclosure.class);
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
        return "prelude.stvn_core.stvn_d".equals(name) || "prelude.stvn_d".equals(name)
            || "org_stvnadore_prelude.stvn_core.stvn_d".equals(name) || "org_stvnadore_prelude.stvn_d".equals(name)
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
    public static @Nullable TypeDefinition resolvePreludeTypeDefinition(@Nullable PsiFile file, String nameOrFqni) {
        if (file == null) return null;
        var normName = nameOrFqni.startsWith(":") ? nameOrFqni : ":" + nameOrFqni;
        var bareName = normName.contains("/") ? ":" + normName.substring(normName.lastIndexOf('/') + 1) : normName;

        // Direct search across all TypeDefinition nodes in file (immune to package structure bugs)
        var allDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : allDefs) {
            var kw = def.getTypeKeyword();
            if (kw != null) {
                var localName = kw.getText();
                if (localName.equals(bareName) || localName.equals(normName)) {
                    return def;
                }
            }
        }

        // Defensive fallback to project prelude cache if file did not contain it
        var project = file.getProject();
        var fallbackFile = project.getUserData(PRELUDE_PSI_FILE_KEY);
        if (fallbackFile != null && fallbackFile != file && fallbackFile.isValid()) {
            return resolvePreludeTypeDefinition(fallbackFile, nameOrFqni);
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

    /**
     * Resolves a prelude type keyword directly within the specified prelude PSI file.
     *
     * @param file the target prelude PSI file to scan
     * @param nameOrFqni the type identifier or FQNI to resolve
     * @return the resolved TypeKeyword element, or {@code null} if not found in the prelude file
     */
    public static @Nullable TypeKeyword resolvePreludeType(@Nullable PsiFile file, String nameOrFqni) {
        var def = resolvePreludeTypeDefinition(file, nameOrFqni);
        return def != null ? def.getTypeKeyword() : null;
    }

    /**
     * Resolves a prelude enum variant AST node within the specified enum type definition.
     *
     * @param project the active IntelliJ project
     * @param enumFqni the fully qualified nominal identifier of the enum type
     * @param variantName the variant identifier (for example, {@code "#DEFS"})
     * @return the resolved ValueKeyword element, or {@code null} if not found in the prelude
     */
    public static @Nullable ValueKeyword resolvePreludeEnumVariant(Project project, String enumFqni, String variantName) {
        var file = getPreludeFile(project);
        return resolvePreludeEnumVariant(file, enumFqni, variantName);
    }

    /**
     * Resolves a prelude enum variant AST node directly within the specified prelude PSI file.
     *
     * @param file the target prelude PSI file to scan
     * @param enumFqni the fully qualified nominal identifier of the enum type
     * @param variantName the variant identifier (for example, {@code "#DEFS"})
     * @return the resolved ValueKeyword element, or {@code null} if not found in the prelude file
     */
    public static @Nullable ValueKeyword resolvePreludeEnumVariant(@Nullable PsiFile file, String enumFqni, String variantName) {
        if (file == null) return null;
        var def = resolvePreludeTypeDefinition(file, enumFqni);
        if (def != null) {
            var valKws = PsiTreeUtil.findChildrenOfType(def, ValueKeyword.class);
            for (var valKw : valKws) {
                if (valKw.getText().equals(variantName)) {
                    return valKw;
                }
            }
        }
        // Direct file scan fallback if def traversal missed it
        var allValKws = PsiTreeUtil.findChildrenOfType(file, ValueKeyword.class);
        for (var valKw : allValKws) {
            if (valKw.getText().equals(variantName) && valKw.getParent() instanceof org.stvnadore.psi.EnumDef) {
                return valKw;
            }
        }
        return null;
    }

    /**
     * Resolves a prelude constant definition AST node by identifier or fully qualified path.
     *
     * @param project the active IntelliJ project
     * @param constName the constant identifier (for example, {@code "#POSIX_ID_REGEX"})
     * @return the resolved ConstantDefinition element, or {@code null} if not found in the prelude
     */
    public static @Nullable ConstantDefinition resolvePreludeConstantDefinition(Project project, String constName) {
        var file = getPreludeFile(project);
        if (file == null) return null;
        var normName = constName.startsWith("#") ? constName : "#" + constName;
        var bareName = normName.contains("/") ? "#" + normName.substring(normName.lastIndexOf('/') + 1) : normName;
        var allConstDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var cDef : allConstDefs) {
            var kw = cDef.getValueKeyword();
            if (kw != null) {
                var text = kw.getText();
                if (text.equals(bareName) || text.equals(normName)) {
                    return cDef;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a prelude constant value keyword by identifier or fully qualified path.
     *
     * @param project the active IntelliJ project
     * @param constName the constant identifier
     * @return the resolved ValueKeyword element, or {@code null} if not found in the prelude
     */
    public static @Nullable ValueKeyword resolvePreludeConstant(Project project, String constName) {
        var def = resolvePreludeConstantDefinition(project, constName);
        return def != null ? def.getValueKeyword() : null;
    }
}
