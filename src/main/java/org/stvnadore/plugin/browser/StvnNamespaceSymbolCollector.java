package org.stvnadore.plugin.browser;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.*;

/**
 * AST inspection engine that collects namespace symbols and computes resolution depth metrics.
 */
@NullMarked
public final class StvnNamespaceSymbolCollector {

    private static final String PRELUDE_URI = "stvn://prelude/org_stvnadore_prelude.stvn_inclf";
    private static final java.util.regex.Pattern PRIMITIVE_TYPE_PATTERN = java.util.regex.Pattern.compile(
        "^:(?:Boolean|Uint[0-9]*|Int[0-9]*|Float[0-9]*|FloatExact|StringFixed[0-9]*|StringNonEmpty[0-9]*|String[0-9]*)$"
    );

    private static boolean isPrimitiveTypeName(String name) {
        return PRIMITIVE_TYPE_PATTERN.matcher(name).matches();
    }

    private static boolean isPackageStripped(PsiFile file, String packagePath) {
        var uses = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : uses) {
            var target = use.getUseTarget();
            if (target != null && target.getText().equals(packagePath)) {
                var opts = use.getUseOptionsBlock();
                if (opts != null && opts.getText().contains("#strip")) {
                    return true;
                }
            }
        }
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var opts = incl.getIncludeOptionsBlock();
            if (opts != null && opts.getText().contains("#strip")) {
                var stringLit = incl.getStringLiteral();
                var targetFile = stringLit != null ? StvnTypeReference.resolveIncludeFile(stringLit) : null;
                if (targetFile != null) {
                    var pkgs = PsiTreeUtil.findChildrenOfType(targetFile, PackageEnclosure.class);
                    for (var pkg : pkgs) {
                        var p = pkg.getPackagePath();
                        if (p != null && p.getText().equals(packagePath)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

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
            if (PsiTreeUtil.getParentOfType(def, PackageEnclosure.class) != null) {
                continue;
            }
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
            if (PsiTreeUtil.getParentOfType(def, PackageEnclosure.class) != null) {
                continue;
            }
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
                    if (kw != null) {
                        var kwText = kw.getText();
                        var fqni = pathText + "/" + stripColon(kwText);
                        var schemaType = pkgTypeDef.getSchemaType();
                        var schemaText = schemaType != null ? schemaType.getText() : "Unknown";
                        if (processedNames.add(fqni)) {
                            results.add(new StvnNamespaceSymbolEntry(
                                fqni,
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
                var pkgConstDef = elem.getConstantDefinition();
                if (pkgConstDef != null) {
                    var kw = pkgConstDef.getValueKeyword();
                    if (kw != null) {
                        var kwText = kw.getText();
                        var fqni = pathText + "/" + kwText;
                        var schemaType = pkgConstDef.getSchemaType();
                        var schemaText = schemaType != null ? schemaType.getText() : "Unknown";
                        if (processedNames.add(fqni)) {
                            results.add(new StvnNamespaceSymbolEntry(
                                fqni,
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
        }

        // 4. Scoped :use statements
        var useStmts = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : useStmts) {
            if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null) {
                continue;
            }
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
                    var valList = alias.getValueKeywordList();
                    if (valList.size() >= 2) {
                        var localKw = valList.get(1);
                        var remoteKw = valList.get(0);
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
            var optionsBlock = use.getUseOptionsBlock();
            if (optionsBlock != null && optionsBlock.getText().contains("#strip")) {
                collectStrippedPackageSymbols(file, targetText, processedNames, results);
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

            var optionsBlock = incl.getIncludeOptionsBlock();
            boolean hasStrip = optionsBlock != null && optionsBlock.getText().contains("#strip");
            if (hasStrip && targetFile != null) {
                var remotePackages = PsiTreeUtil.findChildrenOfType(targetFile, PackageEnclosure.class);
                for (var pkg : remotePackages) {
                    var path = pkg.getPackagePath();
                    var pathText = path != null ? path.getText() : includeSource;
                    collectStrippedElements(pkg, pathText, processedNames, results);
                }
            }

            if (targetFile != null) {
                var remoteDefs = PsiTreeUtil.findChildrenOfType(targetFile, TypeDefinition.class);
                for (var rDef : remoteDefs) {
                    if (PsiTreeUtil.getParentOfType(rDef, PackageEnclosure.class) != null) {
                        continue;
                    }
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
                var remoteConsts = PsiTreeUtil.findChildrenOfType(targetFile, ConstantDefinition.class);
                for (var rConst : remoteConsts) {
                    if (PsiTreeUtil.getParentOfType(rConst, PackageEnclosure.class) != null) {
                        continue;
                    }
                    var kw = rConst.getValueKeyword();
                    if (kw != null && processedNames.add(kw.getText())) {
                        var schemaType = rConst.getSchemaType();
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
        var visitedNominals = new HashSet<String>();

        record TypeWorkItem(SchemaType schema, int depth, PsiFile contextFile) {}
        Queue<TypeWorkItem> worklist = new ArrayDeque<>();
        worklist.add(new TypeWorkItem(typeEntry.getSchemaType(), 0, file));

        while (!worklist.isEmpty()) {
            var item = worklist.poll();
            var currentSchema = item.schema();
            var currentDepth = item.depth();
            var currentFile = item.contextFile();

            var kw = currentSchema.getTypeKeyword();
            if (kw != null) {
                var name = kw.getText();
                if (isPrimitiveTypeName(name)) {
                    continue;
                }
                if (visitedNominals.add(name)) {
                    var info = resolveNominalShapeInfo(currentFile, name);
                    if (processed.add(name)) {
                        results.add(new StvnNamespaceSymbolEntry(
                            name,
                            info.source(),
                            info.typeStructure(),
                            currentDepth,
                            info.targetElement(),
                            info.isPrelude(),
                            info.targetElement() != null ? info.targetElement().getTextOffset() : kw.getTextOffset(),
                            StvnNamespaceScope.TYPE
                        ));
                    }

                    // Transitive expansion into underlying definition
                    var targetElem = info.targetElement();
                    if (targetElem instanceof TypeKeyword targetKw) {
                        var parentDef = StvnPsiUtils.getParentTypeDefinition(targetKw);
                        if (parentDef != null && parentDef.getSchemaType() != null) {
                            var nextSchema = parentDef.getSchemaType();
                            int nextDepth = (currentDepth == 0 || nextSchema.getSchemaConstructor() != null)
                                    ? currentDepth
                                    : currentDepth + 1;
                            worklist.add(new TypeWorkItem(
                                nextSchema,
                                nextDepth,
                                parentDef.getContainingFile()
                            ));
                        } else {
                            var parent = targetKw.getParent();
                            if (parent instanceof UseMapAlias alias) {
                                var list = alias.getTypeKeywordList();
                                if (list.size() >= 2) {
                                    var remoteKw = list.get(0);
                                    var useStmt = PsiTreeUtil.getParentOfType(parent, UseStmt.class);
                                    var target = useStmt != null ? useStmt.getUseTarget() : null;
                                    var targetText = target != null ? target.getText() : "";
                                    var fqni = !targetText.isEmpty()
                                        ? targetText + "/" + stripColon(remoteKw.getText())
                                        : remoteKw.getText();
                                    var resolvedRemote = StvnTypeReference.resolveTypeInFile(currentFile, fqni, new HashSet<>());
                                    if (resolvedRemote == null) {
                                        resolvedRemote = StvnTypeReference.resolveTypeInFile(currentFile, remoteKw.getText(), new HashSet<>());
                                    }
                                    if (resolvedRemote instanceof TypeKeyword rKw) {
                                        var rDef = StvnPsiUtils.getParentTypeDefinition(rKw);
                                        if (rDef != null && rDef.getSchemaType() != null) {
                                            var nextSchema = rDef.getSchemaType();
                                            int nextDepth = (currentDepth == 0 || nextSchema.getSchemaConstructor() != null)
                                                    ? currentDepth
                                                    : currentDepth + 1;
                                            worklist.add(new TypeWorkItem(
                                                nextSchema,
                                                nextDepth,
                                                rDef.getContainingFile()
                                            ));
                                        }
                                    }
                                }
                            } else if (parent instanceof IncludeMapAlias alias) {
                                var list = alias.getTypeKeywordList();
                                if (list.size() >= 2) {
                                    var remoteKw = list.get(0);
                                    var incl = PsiTreeUtil.getParentOfType(parent, IncludeElement.class);
                                    var stringLit = incl != null ? incl.getStringLiteral() : null;
                                    var targetFile = stringLit != null ? StvnTypeReference.resolveIncludeFile(stringLit) : null;
                                    if (targetFile != null) {
                                        var resolvedRemote = StvnTypeReference.resolveTypeInFile(targetFile, remoteKw.getText(), new HashSet<>());
                                        if (resolvedRemote instanceof TypeKeyword rKw) {
                                            var rDef = StvnPsiUtils.getParentTypeDefinition(rKw);
                                            if (rDef != null && rDef.getSchemaType() != null) {
                                                var nextSchema = rDef.getSchemaType();
                                                int nextDepth = (currentDepth == 0 || nextSchema.getSchemaConstructor() != null)
                                                        ? currentDepth
                                                        : currentDepth + 1;
                                                worklist.add(new TypeWorkItem(
                                                    nextSchema,
                                                    nextDepth,
                                                    targetFile
                                                ));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            var constructor = currentSchema.getSchemaConstructor();
            if (constructor != null) {
                var childSchemas = PsiTreeUtil.findChildrenOfType(constructor, SchemaType.class);
                for (var child : childSchemas) {
                    if (child != currentSchema) {
                        worklist.add(new TypeWorkItem(child, currentDepth + 1, currentFile));
                    }
                }
            }
        }

        return results;
    }

    private static List<StvnNamespaceSymbolEntry> collectBodySymbols(PsiFile file) {
        var results = new ArrayList<StvnNamespaceSymbolEntry>();
        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (bodyEntry == null || bodyEntry.getValue() == null) {
            return results;
        }

        var processed = new HashSet<String>();

        // 1. Lockstep positional schema shape correlation
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry != null && typeEntry.getSchemaType() != null) {
            var rootKw = typeEntry.getSchemaType().getTypeKeyword();
            if (rootKw != null && !isPrimitiveTypeName(rootKw.getText()) && processed.add(rootKw.getText())) {
                var info = resolveNominalShapeInfo(file, rootKw.getText());
                results.add(new StvnNamespaceSymbolEntry(
                    rootKw.getText(),
                    info.source(),
                    info.typeStructure(),
                    0,
                    info.targetElement(),
                    info.isPrelude(),
                    info.targetElement() != null ? info.targetElement().getTextOffset() : rootKw.getTextOffset(),
                    StvnNamespaceScope.BODY
                ));
            }
            correlateShapeAndValue(typeEntry.getSchemaType(), bodyEntry.getValue(), file, 0, results, processed, new HashSet<>());
        }

        // 2. Modernized payload IR and AST traversal for nominal types and variants
        collectPayloadSymbols(bodyEntry.getValue(), bodyEntry.getValue(), file, results, processed);
        return results;
    }

    private static void collectPayloadSymbols(
        Value currentVal,
        Value rootVal,
        PsiFile file,
        List<StvnNamespaceSymbolEntry> results,
        Set<String> processed
    ) {
        var depth = calculateValueNestingDepth(currentVal, rootVal);

        // 1. Check for value keywords (#NULL, #BUY)
        var valueKw = currentVal.getValueKeyword();
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

        // 2. Check for boolean variants (#TRUE, #FALSE)
        var boolVal = currentVal.getBooleanValue();
        if (boolVal != null) {
            var name = boolVal.getText().trim();
            if (name.startsWith("#") && processed.add(name)) {
                results.add(new StvnNamespaceSymbolEntry(
                    name,
                    file.getName(),
                    "Boolean Variant",
                    depth,
                    boolVal,
                    false,
                    boolVal.getTextOffset(),
                    StvnNamespaceScope.BODY
                ));
            }
        }

        // 3. Inspect resolved core IR node for nominal type
        var coreNode = StvnTypeResolver.resolveCoreValue(currentVal);
        String nominalName = null;
        if (coreNode != null) {
            if (coreNode instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union && union.schema().aliasName().isPresent()) {
                var branchPsi = StvnTypeResolver.getNominalUnionBranchPsi(file, union.schema().aliasName().get(), union.tagIndex());
                if (branchPsi != null && branchPsi.getTypeKeyword() != null) {
                    nominalName = branchPsi.getTypeKeyword().getText();
                }
            } else if (coreNode instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt && !opt.isNone() && opt.schema().aliasName().isPresent()) {
                var branchPsi = StvnTypeResolver.getNominalOptionBranchPsi(file, opt.schema().aliasName().get());
                if (branchPsi != null && branchPsi.getTypeKeyword() != null) {
                    nominalName = branchPsi.getTypeKeyword().getText();
                }
            } else if (coreNode instanceof org.stvnadore.core.ir.StvnValue.StvnEither either && either.schema().aliasName().isPresent()) {
                var branchPsi = StvnTypeResolver.getNominalEitherBranchPsi(file, either.schema().aliasName().get(), either.isRight());
                if (branchPsi != null && branchPsi.getTypeKeyword() != null) {
                    nominalName = branchPsi.getTypeKeyword().getText();
                }
            } else if (coreNode.schema().aliasName().isPresent()) {
                nominalName = coreNode.schema().aliasName().get();
            }
        }

        if (nominalName != null) {
            if (!nominalName.startsWith(":")) {
                nominalName = ":" + nominalName;
            }
            if (isPrimitiveTypeName(nominalName)) {
                nominalName = null;
            }
        }

        var expected = StvnTypeResolver.resolveExpectedSchemaAtCaret(currentVal);
        boolean alreadyTyped = false;
        if (expected != null && expected.getTypeKeyword() != null) {
            var expectedName = expected.getTypeKeyword().getText();
            if (processed.contains(expectedName) || processed.contains(":" + expectedName)) {
                alreadyTyped = true;
            }
        }

        if (nominalName != null && !nominalName.isEmpty() && !alreadyTyped) {
            if (nominalName.contains("/")) {
                var pkgPath = nominalName.substring(0, nominalName.lastIndexOf('/'));
                if (isPackageStripped(file, pkgPath)) {
                    nominalName = ":" + nominalName.substring(nominalName.lastIndexOf('/') + 1);
                }
            }
            if (processed.add(nominalName)) {
                var info = resolveNominalShapeInfo(file, nominalName);
                results.add(new StvnNamespaceSymbolEntry(
                    nominalName,
                    info.source(),
                    info.typeStructure(),
                    depth,
                    info.targetElement(),
                    info.isPrelude(),
                    info.targetElement() != null ? info.targetElement().getTextOffset() : currentVal.getTextOffset(),
                    StvnNamespaceScope.BODY
                ));
            }
        } else if (!alreadyTyped) {
            var inferred = org.stvnadore.plugin.hints.StvnTypeInferenceHelper.resolveValueTypeWithDepth(currentVal, 16);
            if (inferred != null && !inferred.isEmpty()) {
                var matcher = java.util.regex.Pattern.compile(":[a-zA-Z0-9_/-]+").matcher(inferred);
                while (matcher.find()) {
                    var candidate = matcher.group();
                    if (!candidate.startsWith(":org/stvnadore/prelude/")
                            && !StvnTypeReference.EXACT_TERMINAL_TYPE_NAMES.contains(candidate)
                            && !isPrimitiveTypeName(candidate)) {
                        if (candidate.contains("/")) {
                            var pkgPath = candidate.substring(0, candidate.lastIndexOf('/'));
                            if (isPackageStripped(file, pkgPath)) {
                                candidate = ":" + candidate.substring(candidate.lastIndexOf('/') + 1);
                            }
                        }
                        if (processed.add(candidate)) {
                            var info = resolveNominalShapeInfo(file, candidate);
                            results.add(new StvnNamespaceSymbolEntry(
                                candidate,
                                info.source(),
                                info.typeStructure(),
                                depth,
                                info.targetElement(),
                                info.isPrelude(),
                                info.targetElement() != null ? info.targetElement().getTextOffset() : currentVal.getTextOffset(),
                                StvnNamespaceScope.BODY
                            ));
                        }
                    }
                }
            }
        }

        // 4. Traverse child values in collection structures
        var coll = currentVal.getCollectionValue();
        if (coll != null) {
            var mapLit = coll.getMapLiteral();
            if (mapLit != null) {
                for (var child : mapLit.getValueList()) {
                    collectPayloadSymbols(child, rootVal, file, results, processed);
                }
            }
            var listLit = coll.getListLiteral();
            if (listLit != null) {
                for (var child : listLit.getValueList()) {
                    collectPayloadSymbols(child, rootVal, file, results, processed);
                }
            }
            var tupleLit = coll.getTupleLiteral();
            if (tupleLit != null) {
                for (var child : tupleLit.getValueList()) {
                    collectPayloadSymbols(child, rootVal, file, results, processed);
                }
            }
        }

        var optVal = currentVal.getExplicitOptionValue();
        if (optVal != null && optVal.getValue() != null) {
            collectPayloadSymbols(optVal.getValue(), rootVal, file, results, processed);
        }
        var eitherVal = currentVal.getExplicitEitherValue();
        if (eitherVal != null && eitherVal.getValue() != null) {
            collectPayloadSymbols(eitherVal.getValue(), rootVal, file, results, processed);
        }
        var unionVal = currentVal.getExplicitUnionValue();
        if (unionVal != null && unionVal.getValue() != null) {
            collectPayloadSymbols(unionVal.getValue(), rootVal, file, results, processed);
        }
    }

    private static int calculateValueNestingDepth(Value value, Value rootValue) {
        if (value == rootValue) {
            return 0;
        }
        int depth = 0;
        var curr = value.getParent();
        while (curr != null && curr != rootValue) {
            if (curr instanceof MapLiteral || curr instanceof ListLiteral || curr instanceof TupleLiteral) {
                depth++;
            }
            curr = curr.getParent();
        }
        if (depth == 0) {
            depth = 1;
        }
        return depth;
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

    private static void correlateShapeAndValue(
            @Nullable SchemaType currentSchema,
            @Nullable Value currentValue,
            PsiFile file,
            int depth,
            List<StvnNamespaceSymbolEntry> results,
            Set<String> processed,
            Set<String> visitedNominals
    ) {
        if (currentSchema == null || currentValue == null) {
            return;
        }

        var kw = currentSchema.getTypeKeyword();
        if (kw != null) {
            var name = kw.getText();
            if (isPrimitiveTypeName(name)) {
                return;
            }
            if (!visitedNominals.add(name)) {
                return;
            }

            if (processed.add(name)) {
                var info = resolveNominalShapeInfo(file, name);
                results.add(new StvnNamespaceSymbolEntry(
                        name,
                        info.source(),
                        info.typeStructure(),
                        depth,
                        info.targetElement(),
                        info.isPrelude(),
                        info.targetElement() != null ? info.targetElement().getTextOffset() : kw.getTextOffset(),
                        StvnNamespaceScope.BODY
                ));
            }

            var resolvedSchema = StvnTypeResolver.resolveNominalSchema(currentSchema);
            if (resolvedSchema != null && resolvedSchema != currentSchema && resolvedSchema.getSchemaConstructor() != null) {
                correlateShapeAndValue(resolvedSchema, currentValue, file, depth, results, processed, visitedNominals);
            }
            return;
        }

        var constructor = currentSchema.getSchemaConstructor();
        if (constructor == null) {
            return;
        }

        var product = constructor.getProductType();
        if (product != null) {
            var childSchemas = PsiTreeUtil.getChildrenOfTypeAsList(product, SchemaType.class);
            var coll = currentValue.getCollectionValue();
            var tuple = coll != null ? coll.getTupleLiteral() : null;
            if (tuple != null) {
                var childValues = tuple.getValueList();
                int limit = Math.min(childSchemas.size(), childValues.size());
                for (int i = 0; i < limit; i++) {
                    correlateShapeAndValue(childSchemas.get(i), childValues.get(i), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                }
            }
            return;
        }

        var collType = constructor.getCollectionType();
        if (collType != null) {
            var childSchemas = PsiTreeUtil.getChildrenOfTypeAsList(collType, SchemaType.class);
            var coll = currentValue.getCollectionValue();
            var list = coll != null ? coll.getListLiteral() : null;
            if (list != null && !childSchemas.isEmpty()) {
                var elemSchema = childSchemas.get(0);
                for (var val : list.getValueList()) {
                    correlateShapeAndValue(elemSchema, val, file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                }
            }
            var map = coll != null ? coll.getMapLiteral() : null;
            if (map != null && childSchemas.size() >= 2) {
                var keySchema = childSchemas.get(0);
                var valSchema = childSchemas.get(1);
                var entryVals = map.getValueList();
                for (int i = 0; i + 1 < entryVals.size(); i += 2) {
                    correlateShapeAndValue(keySchema, entryVals.get(i), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                    correlateShapeAndValue(valSchema, entryVals.get(i + 1), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                }
            }
            return;
        }

        var sum = constructor.getSumType();
        if (sum != null) {
            var childSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            if (sum.getText().startsWith(":Option")) {
                var innerSchema = !childSchemas.isEmpty() ? childSchemas.get(0) : null;
                var optVal = currentValue.getExplicitOptionValue();
                if (optVal != null) {
                    if ((optVal.getSomeLiteral() != null || optVal.getSomeShortLiteral() != null) && optVal.getValue() != null && innerSchema != null) {
                        correlateShapeAndValue(innerSchema, optVal.getValue(), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                    }
                } else if (innerSchema != null) {
                    correlateShapeAndValue(innerSchema, currentValue, file, depth, results, processed, new HashSet<>(visitedNominals));
                }
            } else if (sum.getText().startsWith(":Either")) {
                var eitherVal = currentValue.getExplicitEitherValue();
                if (eitherVal != null && eitherVal.getValue() != null && childSchemas.size() >= 2) {
                    if (eitherVal.getLeftLiteral() != null || eitherVal.getLeftShortLiteral() != null) {
                        correlateShapeAndValue(childSchemas.get(0), eitherVal.getValue(), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                    } else if (eitherVal.getRightLiteral() != null || eitherVal.getRightShortLiteral() != null) {
                        correlateShapeAndValue(childSchemas.get(1), eitherVal.getValue(), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                    }
                }
            } else if (sum.getText().startsWith(":Union")) {
                var unionVal = currentValue.getExplicitUnionValue();
                if (unionVal != null && unionVal.getValue() != null) {
                    var tagElem = unionVal.getFirstChild();
                    var tagText = tagElem != null ? tagElem.getText() : "";
                    if (tagText.startsWith("#") && tagText.substring(1).matches("\\d+")) {
                        int idx = Integer.parseInt(tagText.substring(1)) - 1;
                        if (idx >= 0 && idx < childSchemas.size()) {
                            correlateShapeAndValue(childSchemas.get(idx), unionVal.getValue(), file, depth + 1, results, processed, new HashSet<>(visitedNominals));
                        }
                    }
                }
            }
        }
    }

    private record NominalResolutionInfo(
            String source,
            String typeStructure,
            @Nullable PsiElement targetElement,
            boolean isPrelude
    ) {}

    private static NominalResolutionInfo resolveNominalShapeInfo(PsiFile file, String name) {
        var preludeKw = StvnPreludeBridge.resolvePreludeType(file.getProject(), name);
        if (preludeKw != null) {
            return new NominalResolutionInfo(PRELUDE_URI, preludeKw.getText(), preludeKw, true);
        }

        var useStmts = PsiTreeUtil.findChildrenOfType(file, UseStmt.class);
        for (var use : useStmts) {
            var aliasBlock = use.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && remoteKw != null && name.equals(localKw.getText())) {
                            var target = use.getUseTarget();
                            var targetText = target != null ? target.getText() : ":use";
                            var fqni = target != null ? targetText + "/" + stripColon(remoteKw.getText()) : remoteKw.getText();
                            var terminalStructure = resolveTerminalStructure(file, fqni, new HashSet<>());
                            return new NominalResolutionInfo(
                                    targetText,
                                    terminalStructure != null ? terminalStructure : remoteKw.getText(),
                                    localKw,
                                    false
                            );
                        }
                    }
                }
            }
        }

        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            var targetFile = stringLit != null ? StvnTypeReference.resolveIncludeFile(stringLit) : null;
            var includeSource = targetFile != null ? targetFile.getName() : (stringLit != null ? stringLit.getText() : "include");
            var aliasBlock = incl.getIncludeAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getIncludeMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && remoteKw != null && name.equals(localKw.getText())) {
                            var terminalStructure = targetFile != null
                                    ? resolveTerminalStructure(targetFile, remoteKw.getText(), new HashSet<>())
                                    : remoteKw.getText();
                            return new NominalResolutionInfo(
                                    includeSource,
                                    terminalStructure != null ? terminalStructure : remoteKw.getText(),
                                    localKw,
                                    false
                            );
                        }
                    }
                }
            }
        }

        var packages = PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class);
        for (var pkg : packages) {
            var pkgPath = pkg.getPackagePath();
            var pathText = pkgPath != null ? pkgPath.getText() : ":package";
            for (var elem : pkg.getPackageElementList()) {
                var typeDef = elem.getTypeDefinition();
                if (typeDef != null) {
                    var kw = typeDef.getTypeKeyword();
                    if (kw != null) {
                        var kwText = kw.getText();
                        var fqni = pathText + "/" + stripColon(kwText);
                        if (name.equals(kwText) || name.equals(fqni)) {
                            var terminal = resolveTerminalStructureFromDef(typeDef, new HashSet<>());
                            return new NominalResolutionInfo(pathText, terminal, kw, false);
                        }
                    }
                }
            }
        }

        var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var td : typeDefs) {
            var kw = td.getTypeKeyword();
            if (kw != null && name.equals(kw.getText())) {
                var terminal = resolveTerminalStructureFromDef(td, new HashSet<>());
                return new NominalResolutionInfo(file.getName(), terminal, kw, false);
            }
        }

        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            var targetFile = stringLit != null ? StvnTypeReference.resolveIncludeFile(stringLit) : null;
            if (targetFile != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(targetFile, name, new HashSet<>());
                if (resolved instanceof TypeKeyword targetKw) {
                    var parent = StvnPsiUtils.getParentTypeDefinition(targetKw);
                    var struct = parent != null ? resolveTerminalStructureFromDef(parent, new HashSet<>()) : name;
                    return new NominalResolutionInfo(targetFile.getName(), struct, targetKw, false);
                }
            }
        }

        var resolved = StvnTypeReference.resolveTypeInFile(file, name, new HashSet<>());
        var source = resolved != null && resolved.getContainingFile() != null ? resolved.getContainingFile().getName() : file.getName();
        return new NominalResolutionInfo(source, name, resolved, false);
    }

    private static @Nullable String resolveTerminalStructure(PsiFile file, String name, Set<String> visited) {
        if (!visited.add(name)) {
            return name;
        }
        var resolved = StvnTypeReference.resolveTypeInFile(file, name, new HashSet<>());
        if (resolved instanceof TypeKeyword kw) {
            var parent = StvnPsiUtils.getParentTypeDefinition(kw);
            if (parent != null) {
                return resolveTerminalStructureFromDef(parent, visited);
            }
        }
        return null;
    }

    private static String resolveTerminalStructureFromDef(TypeDefinition def, Set<String> visited) {
        var defKw = def.getTypeKeyword();
        if (defKw != null && !visited.add(defKw.getText())) {
            return defKw.getText();
        }
        var schemaType = def.getSchemaType();
        if (schemaType == null) {
            return defKw != null ? defKw.getText() : "Unknown";
        }
        var nextKw = schemaType.getTypeKeyword();
        if (nextKw != null) {
            var nextResolved = resolveNominalShapeInfo(def.getContainingFile(), nextKw.getText());
            return nextResolved.typeStructure();
        }
        return schemaType.getText();
    }

    private static String stripColon(String text) {
        return text.startsWith(":") ? text.substring(1) : text;
    }

    private static void collectStrippedPackageSymbols(
            PsiFile file,
            String targetPackagePath,
            Set<String> processedNames,
            List<StvnNamespaceSymbolEntry> results
    ) {
        var localPackages = PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class);
        for (var pkg : localPackages) {
            var path = pkg.getPackagePath();
            if (path != null && path.getText().equals(targetPackagePath)) {
                collectStrippedElements(pkg, targetPackagePath, processedNames, results);
            }
        }

        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit == null) continue;
            var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
            if (targetFile != null) {
                var remotePackages = PsiTreeUtil.findChildrenOfType(targetFile, PackageEnclosure.class);
                for (var pkg : remotePackages) {
                    var path = pkg.getPackagePath();
                    if (path != null && path.getText().equals(targetPackagePath)) {
                        collectStrippedElements(pkg, targetPackagePath, processedNames, results);
                    }
                }
            }
        }
    }

    private static void collectStrippedElements(
            PackageEnclosure pkg,
            String targetPackagePath,
            Set<String> processedNames,
            List<StvnNamespaceSymbolEntry> results
    ) {
        for (var elem : pkg.getPackageElementList()) {
            var typeDef = elem.getTypeDefinition();
            if (typeDef != null) {
                var kw = typeDef.getTypeKeyword();
                if (kw != null) {
                    var bareName = kw.getText();
                    var fqni = targetPackagePath + "/" + stripColon(bareName);
                    if (processedNames.add(bareName)) {
                        results.add(new StvnNamespaceSymbolEntry(
                            bareName,
                            targetPackagePath,
                            fqni,
                            1,
                            kw,
                            false,
                            kw.getTextOffset(),
                            StvnNamespaceScope.DEFS
                        ));
                    }
                }
            }
            var constDef = elem.getConstantDefinition();
            if (constDef != null) {
                var kw = constDef.getValueKeyword();
                if (kw != null) {
                    var bareName = kw.getText();
                    var fqni = targetPackagePath + "/" + bareName;
                    if (processedNames.add(bareName)) {
                        results.add(new StvnNamespaceSymbolEntry(
                            bareName,
                            targetPackagePath,
                            fqni,
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
    }
}
