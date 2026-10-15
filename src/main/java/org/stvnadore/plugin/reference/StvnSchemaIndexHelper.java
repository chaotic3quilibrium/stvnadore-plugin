package org.stvnadore.plugin.reference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.TypeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Workspace index helper discovering schema files declaring specific nominal types.
 */
@NullMarked
public final class StvnSchemaIndexHelper {

    private StvnSchemaIndexHelper() {}

    /**
     * Finds workspace schema files (.stvn_d, .stvn_di) declaring the target type name.
     *
     * @param project active project
     * @param leafTypeName bare or colon-prefixed leaf type name (e.g. :OrderHeader)
     * @return list of matching schema PsiFile candidates
     */
    public static List<PsiFile> findDeclaringSchemaFiles(Project project, String leafTypeName) {
        var results = new ArrayList<PsiFile>();
        var cleanLeaf = leafTypeName.startsWith(":") ? leafTypeName : ":" + leafTypeName;
        var scope = GlobalSearchScope.projectScope(project);
        var psiManager = PsiManager.getInstance(project);

        var virtualFiles = new ArrayList<com.intellij.openapi.vfs.VirtualFile>();
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Defs.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.DefsInclude.INSTANCE, scope));

        for (var vf : virtualFiles) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile == null) continue;

            var typeDefs = PsiTreeUtil.findChildrenOfType(psiFile, TypeDefinition.class);
            for (var def : typeDefs) {
                var kw = def.getTypeKeyword();
                if (kw != null && kw.getText().equals(cleanLeaf)) {
                    if (!results.contains(psiFile)) {
                        results.add(psiFile);
                    }
                    break;
                }
            }
        }
        return List.copyOf(results);
    }

    /**
     * Computes the canonical relative include path between host file and declaring schema file.
     *
     * @param hostFile file containing unresolved type reference
     * @param targetFile schema file defining the type
     * @return relative path string with forward slashes, or null if unresolvable
     */
    public static @Nullable String computeRelativeIncludePath(PsiFile hostFile, PsiFile targetFile) {
        var hostVf = hostFile.getVirtualFile();
        var targetVf = targetFile.getVirtualFile();
        if (hostVf == null || targetVf == null) return null;
        var parentDir = hostVf.getParent();
        if (parentDir != null) {
            var rel = VfsUtilCore.findRelativePath(parentDir, targetVf, '/');
            if (rel != null && !rel.isEmpty()) return rel;
        }
        return targetVf.getName();
    }
}
