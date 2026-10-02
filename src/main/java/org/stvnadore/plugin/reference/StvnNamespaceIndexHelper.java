package org.stvnadore.plugin.reference;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.PackageEnclosure;

/**
 * Utility indexing and resolving package namespaces exporting specific leaf symbol names.
 */
@NullMarked
public final class StvnNamespaceIndexHelper {

    private StvnNamespaceIndexHelper() {}

    /**
     * Finds all package namespace paths across the workspace that declare the target type name.
     *
     * @param project the active project
     * @param leafTypeName the bare or colon-prefixed leaf type name (e.g. :RemoteHost)
     * @return list of matching package namespace paths (e.g. [":org/example/net"])
     */
    public static List<String> findExportingNamespaces(Project project, String leafTypeName) {
        var results = new ArrayList<String>();
        var cleanLeaf = leafTypeName.startsWith(":") ? leafTypeName : ":" + leafTypeName;
        var scope = GlobalSearchScope.projectScope(project);
        var psiManager = PsiManager.getInstance(project);

        var virtualFiles = new ArrayList<com.intellij.openapi.vfs.VirtualFile>();
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Payload.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.FlatPayload.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Incl.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Inclf.INSTANCE, scope));

        for (var vf : virtualFiles) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile == null) continue;

            var packages = PsiTreeUtil.findChildrenOfType(psiFile, PackageEnclosure.class);
            for (var pkg : packages) {
                var path = pkg.getPackagePath();
                if (path == null) continue;
                for (var elem : pkg.getPackageElementList()) {
                    var def = elem.getTypeDefinition();
                    if (def != null && def.getTypeKeyword() != null) {
                        var kwText = def.getTypeKeyword().getText();
                        if (kwText.equals(cleanLeaf)) {
                            var pkgText = path.getText();
                            if (!results.contains(pkgText)) {
                                results.add(pkgText);
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(results);
    }
}
