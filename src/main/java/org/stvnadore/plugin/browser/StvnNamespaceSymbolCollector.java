package org.stvnadore.plugin.browser;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.*;

/**
 * AST inspection engine that collects namespace symbols and computes resolution depth metrics.
 */
@NullMarked
public final class StvnNamespaceSymbolCollector {

    private static final String PRELUDE_URI = "stvn://prelude/org_stvnadore_prelude.stvn_inclf";

    private StvnNamespaceSymbolCollector() {
    }

    /**
     * Collects all symbols matching the specified section scope from the STVN file.
     *
     * @param file the source STVN file
     * @param scope the target namespace scope
     * @return ordered list of symbol entries
     */
    public static List<StvnNamespaceSymbolEntry> collectSymbols(PsiFile file, StvnNamespaceScope scope) {
        return switch (scope) {
            case DEFS -> collectDefsSymbols(file);
            case TYPE -> collectTypeSymbols(file);
            case BODY -> collectBodySymbols(file);
        };
    }

    private static List<StvnNamespaceSymbolEntry> collectDefsSymbols(PsiFile file) {
        var results = new ArrayList<StvnNamespaceSymbolEntry>();
        var fileName = file.getName();
        var processedNames = new HashSet<String>();

        // 1. Local TypeDefinitions in :defs
        var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : typeDefs) {
            var kw = def.getTypeKeyword();
            if (kw != null && processedNames.add(kw.getText())) {
                var schemaType = def.getSchemaType();
                var schemaText = schemaType != null ? schemaType.getText() : "Unknown";
                var depth = computeAliasHopCount(def, new HashSet<>());
                results.add(new StvnNamespaceSymbolEntry(
                    kw.getText(),
                    fileName,
                    schemaText,
                    depth,
                    kw,
                    false,
                    kw.getTextOffset(),
                    StvnNamespaceScope.DEFS
                ));
            }
        }

        // 2. Local ConstantDefinitions in :defs
        var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var def : constDefs) {
            var kw = def.getValueKeyword();
            if (kw != null && processedNames.add(kw.getText())) {
                var schemaType = def.getSchemaType();
                var schemaText = schemaType != null ? schemaType.getText() : "Unknown";
                results.add(new StvnNamespaceSymbolEntry(
                    kw.getText(),
                    fileName,
                    schemaText,
                    0,
                    kw,
                    false,
                    kw.getTextOffset(),
                    StvnNamespaceScope.DEFS
                ));
            }
        }

        // 3. Package Enclosures
        var packages = PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class);
        for (var pkg : packages) {
            var path = pkg.getPackagePath();
            var pathText = path != null ? path.getText() : ":package";
            for (var elem : pkg.getPackageElementList()) {
                var pkgTypeDef = elem.getTypeDefinition();
                if (pkgTypeDef != null) {
                    var kw = pkgTypeDef.getTypeKeyword();
                    if (kw != null && processedNames.add(kw.getText())) {
                        var schemaType = pkgTypeDef.getSchemaType();
                        var schemaText = schemaType != null ? schemaType.getText() : "Unknown";
                        results.add(new StvnNamespaceSymbolEntry(
                            kw.getText(),
                            pathText,
                            schemaText,
                            0,
                            kw,
                            false,
                            kw.getTextOffset(),
                            StvnNamespaceScope.DEFS
                        ));
                    }
                }
            }
        }

        // 4. Scoped :use statements
        var useStmts = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : useStmts) {
            var target = use.getUseTarget();
            var targetText = target != null ? target.getText() : ":use";
            var aliasBlock = use.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var localKw = list.get(1);
                        var remoteKw = list.get(0);
                        if (localKw != null && remoteKw != null && processedNames.add(localKw.getText())) {
                            results.add(new StvnNamespaceSymbolEntry(
                                localKw.getText(),
                                targetText,
                                remoteKw.getText(),
                                1,
                                localKw,
                                false,
                                localKw.getTextOffset(),
                                StvnNamespaceScope.DEFS
                            ));
                        }
                    }
                }
            }
        }

        // 5. Included modules (:include)
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit == null) continue;
            var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
            var includeSource = targetFile != null ? targetFile.getName() : stringLit.getText();

            var aliasBlock = incl.getIncludeAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getIncludeMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var localKw = list.get(1);
                        var remoteKw = list.get(0);
                        if (localKw != null && remoteKw != null && processedNames.add(localKw.getText())) {
                            results.add(new StvnNamespaceSymbolEntry(
                                localKw.getText(),
                                includeSource,
                                remoteKw.getText(),
                                1,
                                localKw,
                                false,
                                localKw.getTextOffset(),
                                StvnNamespaceScope.DEFS
                            ));
                        }
                    }
                }
            }

            if (targetFile != null) {
                var remoteDefs = PsiTreeUtil.findChildrenOfType(targetFile, TypeDefinition.class);
                for (var rDef : remoteDefs) {
                    var kw = rDef.getTypeKeyword();
                    if (kw != null && processedNames.add(kw.getText())) {
                        var schemaType = rDef.getSchemaType();
                        var schemaText = schemaType != null ? schemaType.getText() : "Unknown";
                        results.add(new StvnNamespaceSymbolEntry(
                            kw.getText(),
                            includeSource,
                            schemaText,
                            1,
                            kw,
                            false,
                            kw.getTextOffset(),
                            StvnNamespaceScope.DEFS
                        ));
                    }
                }
            }
        }

        return results;
    }

    private static List<StvnNamespaceSymbolEntry> collectTypeSymbols(PsiFile file) {
        var results = new ArrayList<StvnNamespaceSymbolEntry>();
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry == null || typeEntry.getSchemaType() == null) {
            return results;
        }

        var processed = new HashSet<String>();
        collectSchemaTypeSymbols(typeEntry.getSchemaType(), file, 0, results, processed);
        return results;
    }

    private static void collectSchemaTypeSymbols(
        SchemaType schema,
        PsiFile file,
        int depth,
        List<StvnNamespaceSymbolEntry> results,
        Set<String> processed
    ) {
        var kw = schema.getTypeKeyword();
        if (kw != null) {
            var name = kw.getText();
            if (processed.add(name)) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, name, new HashSet<>());
                if (resolved instanceof TypeKeyword targetKw) {
                    var targetFile = targetKw.getContainingFile();
                    var source = targetFile != null ? targetFile.getName() : file.getName();
                    var parentDef = StvnPsiUtils.getParentTypeDefinition(targetKw);
                    var typeStruct = parentDef != null && parentDef.getSchemaType() != null
                        ? parentDef.getSchemaType().getText()
                        : name;
                    results.add(new StvnNamespaceSymbolEntry(
                        name,
                        source,
                        typeStruct,
                        depth,
                        targetKw,
                        false,
                        targetKw.getTextOffset(),
                        StvnNamespaceScope.TYPE
                    ));
                } else {
                    var preludeKw = StvnPreludeBridge.resolvePreludeType(file.getProject(), name);
                    if (preludeKw != null) {
                        results.add(new StvnNamespaceSymbolEntry(
                            name,
                            PRELUDE_URI,
                            preludeKw.getText(),
                            depth,
                            preludeKw,
                            true,
                            preludeKw.getTextOffset(),
                            StvnNamespaceScope.TYPE
                        ));
                    }
                }
            }
        }

        var constructor = schema.getSchemaConstructor();
        if (constructor != null) {
            var childSchemas = PsiTreeUtil.findChildrenOfType(constructor, SchemaType.class);
            for (var child : childSchemas) {
                if (child != schema) {
                    collectSchemaTypeSymbols(child, file, depth + 1, results, processed);
                }
            }
        }
    }

    private static List<StvnNamespaceSymbolEntry> collectBodySymbols(PsiFile file) {
        var results = new ArrayList<StvnNamespaceSymbolEntry>();
        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (bodyEntry == null || bodyEntry.getValue() == null) {
            return results;
        }

        var processed = new HashSet<String>();
        collectValueSymbols(bodyEntry.getValue(), file, 0, results, processed);
        return results;
    }

    private static void collectValueSymbols(
        Value value,
        PsiFile file,
        int depth,
        List<StvnNamespaceSymbolEntry> results,
        Set<String> processed
    ) {
        var valueKw = value.getValueKeyword();
        if (valueKw != null) {
            var name = valueKw.getText();
            if (processed.add(name)) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, name, new HashSet<>());
                var source = resolved != null && resolved.getContainingFile() != null
                    ? resolved.getContainingFile().getName()
                    : file.getName();
                results.add(new StvnNamespaceSymbolEntry(
                    name,
                    source,
                    "Constant / Variant",
                    depth,
                    resolved != null ? resolved : valueKw,
                    false,
                    resolved != null ? resolved.getTextOffset() : valueKw.getTextOffset(),
                    StvnNamespaceScope.BODY
                ));
            }
        }

        // Traverse nested values
        var collValue = value.getCollectionValue();
        if (collValue != null) {
            var childValues = PsiTreeUtil.findChildrenOfType(collValue, Value.class);
            for (var child : childValues) {
                if (child != value) {
                    collectValueSymbols(child, file, depth + 1, results, processed);
                }
            }
        }

        if (value.getExplicitOptionValue() != null && value.getExplicitOptionValue().getValue() != null) {
            collectValueSymbols(value.getExplicitOptionValue().getValue(), file, depth + 1, results, processed);
        }
        if (value.getExplicitEitherValue() != null && value.getExplicitEitherValue().getValue() != null) {
            collectValueSymbols(value.getExplicitEitherValue().getValue(), file, depth + 1, results, processed);
        }
        if (value.getExplicitUnionValue() != null && value.getExplicitUnionValue().getValue() != null) {
            collectValueSymbols(value.getExplicitUnionValue().getValue(), file, depth + 1, results, processed);
        }
    }

    private static int computeAliasHopCount(TypeDefinition def, Set<String> visited) {
        var kw = def.getTypeKeyword();
        if (kw == null || !visited.add(kw.getText())) {
            return 0;
        }

        var schemaType = def.getSchemaType();
        if (schemaType == null) {
            return 0;
        }

        var nextKw = schemaType.getTypeKeyword();
        if (nextKw == null) {
            return 0; // Terminal primitive or composite constructor
        }

        var nextName = nextKw.getText();
        var file = def.getContainingFile();

        // Check if nextName is an include alias in this file
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var aliasBlock = incl.getIncludeAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getIncludeMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && remoteKw != null && nextName.equals(localKw.getText())) {
                            var stringLit = incl.getStringLiteral();
                            var targetFile = stringLit != null ? StvnTypeReference.resolveIncludeFile(stringLit) : null;
                            if (targetFile != null) {
                                var remoteResolved = StvnTypeReference.resolveTypeInFile(targetFile, remoteKw.getText(), new HashSet<>());
                                if (remoteResolved instanceof TypeKeyword rKw) {
                                    var parent = StvnPsiUtils.getParentTypeDefinition(rKw);
                                    if (parent != null) {
                                        return 2 + computeAliasHopCount(parent, visited);
                                    }
                                }
                            }
                            return 2;
                        }
                    }
                }
            }
        }

        // Check if nextName is a use alias in this file
        var useStmts = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : useStmts) {
            var aliasBlock = use.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && remoteKw != null && nextName.equals(localKw.getText())) {
                            return 2;
                        }
                    }
                }
            }
        }

        var resolved = StvnTypeReference.resolveTypeInFile(file, nextName, new HashSet<>());
        if (resolved instanceof TypeKeyword targetKw) {
            var parentDef = StvnPsiUtils.getParentTypeDefinition(targetKw);
            if (parentDef != null) {
                return 1 + computeAliasHopCount(parentDef, visited);
            }
        }

        return 1;
    }
}
