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
                if (name != null && !name.isEmpty()) {
                    var bareName = name.startsWith(":") ? name.substring(1) : name;
                    var canonicalName = ":" + bareName;
                    if (!bareName.isEmpty() && seenNames.add(bareName)) {
                        if (!processor.process(bareName)) return false;
                    }
                    if (seenNames.add(canonicalName)) {
                        if (!processor.process(canonicalName)) return false;
                    }
                }
            }
            var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
            for (var def : constDefs) {
                var name = def.getName();
                if (name != null && !name.isEmpty()) {
                    var bareName = name.startsWith("#") ? name.substring(1) : name;
                    var canonicalName = "#" + bareName;
                    if (!bareName.isEmpty() && seenNames.add(bareName)) {
                        if (!processor.process(bareName)) return false;
                    }
                    if (seenNames.add(canonicalName)) {
                        if (!processor.process(canonicalName)) return false;
                    }
                }
            }
            var packages = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class);
            for (var pkg : packages) {
                var pkgPath = pkg.getPackagePath();
                if (pkgPath == null) continue;
                var pathText = pkgPath.getText();
                for (var elem : pkg.getPackageElementList()) {
                    var typeDef = elem.getTypeDefinition();
                    if (typeDef != null) {
                        var kw = typeDef.getTypeKeyword();
                        if (kw != null) {
                            var fqni = pathText + "/" + (kw.getText().startsWith(":") ? kw.getText().substring(1) : kw.getText());
                            if (seenNames.add(fqni)) {
                                if (!processor.process(fqni)) return false;
                            }
                        }
                    }
                    var constDef = elem.getConstantDefinition();
                    if (constDef != null) {
                        var kw = constDef.getValueKeyword();
                        if (kw != null) {
                            var fqni = pathText + "/" + kw.getText();
                            if (seenNames.add(fqni)) {
                                if (!processor.process(fqni)) return false;
                            }
                        }
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
                var defName = def.getName();
                if (defName != null) {
                    var bareDefName = defName.startsWith(":") ? defName.substring(1) : defName;
                    var bareSearchName = name.startsWith(":") ? name.substring(1) : name;
                    if (bareDefName.equals(bareSearchName)) {
                        var kw = def.getTypeKeyword();
                        if (kw instanceof NavigationItem item) {
                            if (!processor.process(item)) {
                                return false;
                            }
                        }
                    }
                }
            }
            var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
            for (var def : constDefs) {
                var defName = def.getName();
                if (defName != null) {
                    var bareDefName = defName.startsWith("#") ? defName.substring(1) : defName;
                    var bareSearchName = name.startsWith("#") ? name.substring(1) : name;
                    if (bareDefName.equals(bareSearchName)) {
                        var kw = def.getValueKeyword();
                        if (kw instanceof NavigationItem item) {
                            if (!processor.process(item)) {
                                return false;
                            }
                        }
                    }
                }
            }
            var packages = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.PackageEnclosure.class);
            for (var pkg : packages) {
                var pkgPath = pkg.getPackagePath();
                if (pkgPath == null) continue;
                var pathText = pkgPath.getText();
                for (var elem : pkg.getPackageElementList()) {
                    var typeDef = elem.getTypeDefinition();
                    if (typeDef != null) {
                        var kw = typeDef.getTypeKeyword();
                        if (kw != null) {
                            var fqni = pathText + "/" + (kw.getText().startsWith(":") ? kw.getText().substring(1) : kw.getText());
                            if (name.equals(fqni) || name.equals(kw.getText()) || name.equals(typeDef.getName())) {
                                if (kw instanceof NavigationItem item) {
                                    if (!processor.process(item)) return false;
                                }
                            }
                        }
                    }
                    var constDef = elem.getConstantDefinition();
                    if (constDef != null) {
                        var kw = constDef.getValueKeyword();
                        if (kw != null) {
                            var fqni = pathText + "/" + kw.getText();
                            if (name.equals(fqni) || name.equals(kw.getText()) || name.equals(constDef.getName())) {
                                if (kw instanceof NavigationItem item) {
                                    if (!processor.process(item)) return false;
                                }
                            }
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
        var flatFiles = FileTypeIndex.getFiles(StvnFileType.FlatPayload.INSTANCE, scope);
        for (var vf : flatFiles) {
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
