package org.stvnadore.plugin.reference;

import com.intellij.navigation.ChooseByNameContributorEx;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FindSymbolParameters;
import com.intellij.util.indexing.IdFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.TypeDefinition;

import java.util.HashSet;

@NullMarked
public final class StvnChooseByNameContributor implements ChooseByNameContributorEx {

    @Override
    public void processNames(
            @NotNull Processor<? super String> processor,
            @NotNull GlobalSearchScope scope,
            @Nullable IdFilter filter
    ) {
        var project = scope.getProject();
        if (project == null) {
            return;
        }

        var seenNames = new HashSet<String>();
        processAllFiles(project, scope, file -> {
            var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
            for (var def : typeDefs) {
                var name = def.getName();
                if (name != null && !name.isEmpty() && seenNames.add(name)) {
                    if (!processor.process(name)) {
                        return false;
                    }
                }
            }
            var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
            for (var def : constDefs) {
                var name = def.getName();
                if (name != null && !name.isEmpty() && seenNames.add(name)) {
                    if (!processor.process(name)) {
                        return false;
                    }
                }
            }
            return true;
        });
    }

    @Override
    public void processElementsWithName(
            @NotNull String name,
            @NotNull Processor<? super NavigationItem> processor,
            @NotNull FindSymbolParameters parameters
    ) {
        var project = parameters.getProject();
        var scope = parameters.getSearchScope();

        processAllFiles(project, scope, file -> {
            var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
            for (var def : typeDefs) {
                if (name.equals(def.getName()) || name.equals(":" + def.getName())) {
                    var kw = def.getTypeKeyword();
                    if (kw instanceof NavigationItem item) {
                        if (!processor.process(item)) {
                            return false;
                        }
                    }
                }
            }
            var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
            for (var def : constDefs) {
                if (name.equals(def.getName()) || name.equals("#" + def.getName())) {
                    var kw = def.getValueKeyword();
                    if (kw instanceof NavigationItem item) {
                        if (!processor.process(item)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        });
    }

    private static void processAllFiles(Project project, GlobalSearchScope scope, Processor<PsiFile> fileProcessor) {
        var psiManager = PsiManager.getInstance(project);
        var files = FileTypeIndex.getFiles(StvnFileType.Payload.INSTANCE, scope);
        for (var vf : files) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile != null && !fileProcessor.process(psiFile)) {
                return;
            }
        }
        var inclFiles = FileTypeIndex.getFiles(StvnFileType.Incl.INSTANCE, scope);
        for (var vf : inclFiles) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile != null && !fileProcessor.process(psiFile)) {
                return;
            }
        }
        var inclfFiles = FileTypeIndex.getFiles(StvnFileType.Inclf.INSTANCE, scope);
        for (var vf : inclfFiles) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile != null && !fileProcessor.process(psiFile)) {
                return;
            }
        }
    }
}
