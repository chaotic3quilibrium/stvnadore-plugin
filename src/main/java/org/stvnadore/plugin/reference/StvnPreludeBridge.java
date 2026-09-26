package org.stvnadore.plugin.reference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
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

    private static final Key<CachedValue<PsiFile>> PRELUDE_FILE_KEY = Key.create("STVN_PRELUDE_FILE");

    private StvnPreludeBridge() {
    }

    /**
     * Retrieves the cached synthetic AST file representing the canonical standard library prelude.
     *
     * @param project the active IntelliJ project
     * @return the synthetic prelude PSI file
     */
    public static PsiFile getPreludeFile(Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, PRELUDE_FILE_KEY, () -> {
            var file = PsiFileFactory.getInstance(project).createFileFromText(
                "prelude.stvn_inclf",
                StvnFileType.Inclf.INSTANCE,
                StvnPrelude.PRELUDE_STVN_INCLF
            );
            return CachedValueProvider.Result.create(file, ModificationTracker.NEVER_CHANGED);
        }, false);
    }

    /**
     * Resolves a prelude type keyword by nominal name or fully qualified nominal identifier (FQNI).
     *
     * @param project the active IntelliJ project
     * @param nameOrFqni the type identifier or FQNI to resolve
     * @return the resolved TypeKeyword element, or {@code null} if not found in the prelude
     */
    public static @Nullable TypeKeyword resolvePreludeType(Project project, String nameOrFqni) {
        var file = getPreludeFile(project);
        var targetFqni = nameOrFqni.startsWith(":org/stvnadore/prelude/")
            ? nameOrFqni
            : ":org/stvnadore/prelude/" + (nameOrFqni.startsWith(":") ? nameOrFqni.substring(1) : nameOrFqni);
        var defs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : defs) {
            var kw = def.getTypeKeyword();
            if (kw != null && kw.getText().equals(targetFqni)) {
                return kw;
            }
        }
        return null;
    }
}
