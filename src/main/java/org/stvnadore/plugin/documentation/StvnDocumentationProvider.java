package org.stvnadore.plugin.documentation;

import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.TokenType;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnLiteralParser;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.ResolvedType;
import org.stvnadore.plugin.metadata.StvnFacetMetadata;
import org.stvnadore.plugin.metadata.StvnMetadataRegistry;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.PackageElement;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides hover documentation and quick navigation info for STVN symbols.
 */
@NullMarked
public final class StvnDocumentationProvider implements DocumentationProvider {

    /**
     * Constructs a new StvnDocumentationProvider.
     */
    public StvnDocumentationProvider() {
    }

    @Override
    public @Nullable String generateHoverDoc(PsiElement element, @Nullable PsiElement originalElement) {
        return generateDoc(element, originalElement);
    }

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        var settings = StvnProjectSettings.getInstance(element.getProject());
        if (settings != null && !settings.getState().showHoverDocs) {
            return null;
        }

        // 1. If originalElement resides within a metadata facet or kind value, generate facet card directly
        if (originalElement != null) {
            var metaKindVal = PsiTreeUtil.getParentOfType(originalElement, MetaKindValue.class, false);
            if (metaKindVal != null) return buildTargetDocumentation(metaKindVal, originalElement);
            var metaKind = PsiTreeUtil.getParentOfType(originalElement, MetaKindFacet.class, false);
            if (metaKind != null) return buildTargetDocumentation(metaKind, originalElement);
            var metaName = PsiTreeUtil.getParentOfType(originalElement, MetaNameFacet.class, false);
            if (metaName != null) return buildTargetDocumentation(metaName, originalElement);
            var metaDomain = PsiTreeUtil.getParentOfType(originalElement, MetaDomainFacet.class, false);
            if (metaDomain != null) return buildTargetDocumentation(metaDomain, originalElement);
        }

        // 2. Direct facet element fallback
        if (element instanceof MetaNameFacet || element instanceof MetaDomainFacet
            || element instanceof MetaKindFacet || element instanceof MetaKindValue || element instanceof MetadataEntry) {
            return buildTargetDocumentation(element, originalElement);
        }
        if (isContainerDocumentationSuppressionTarget(element) || (originalElement != null && isContainerDocumentationSuppressionTarget(originalElement))) {
            return null;
        }
        var target = getDocumentationElement(element, originalElement);
        if (target == null) {
            return null;
        }

        var unqualifiedNotice = checkUnqualifiedPreludeReferenceNotice(target, element, originalElement);
        if (unqualifiedNotice != null) {
            var renderedPreludeDoc = buildPreludeTypeDocumentation(target, element, originalElement);
            return unqualifiedNotice + (renderedPreludeDoc != null ? renderedPreludeDoc : "");
        }

        var baseDoc = buildTargetDocumentation(target, originalElement);
        if (baseDoc == null) {
            return null;
        }

        var degradedInfo = extractDegradedSchemaInfo(target, originalElement);
        if (degradedInfo != null) {
            var banner = """
                <div style="padding: 6px 10px; border-left: 4px solid #F5A623; background-color: rgba(245, 166, 35, 0.12); color: #D68910; margin-bottom: 10px; border-radius: 2px; font-family: sans-serif;">
                    <b style="font-size: 1.05em;">&#9888; Degraded Schema Warning</b><br/>
                    Nominal type <code>%s</code> contains constraint errors in <code>:defs</code>.<br/>
                    Currently evaluating payload using fallback base type (<code>%s</code>).
                </div>
                """.formatted(degradedInfo.aliasName(), degradedInfo.fallbackBase());
            return banner + baseDoc;
        }

        return baseDoc;
    }

    private record DegradedSchemaInfo(String aliasName, String fallbackBase) {}

    private @Nullable DegradedSchemaInfo extractDegradedSchemaInfo(PsiElement target, @Nullable PsiElement originalElement) {
        if (target instanceof TypeDefinition typeDef) {
            var kw = typeDef.getTypeKeyword();
            if (kw != null) {
                var alias = kw.getText();
                if (StvnTypeResolver.isDegradedNominalAlias(typeDef.getContainingFile(), alias)) {
                    var schemaType = typeDef.getSchemaType();
                    var resolved = schemaType != null ? StvnTypeResolver.resolveNominalSchema(schemaType) : null;
                    var base = resolved != null ? StvnSchemaFormatter.formatCleanSchema(resolved) : (schemaType != null ? StvnSchemaFormatter.formatCleanSchema(schemaType) : ":Value");
                    return new DegradedSchemaInfo(alias, base);
                }
            }
        } else if (target instanceof TypeKeyword typeKw) {
            var alias = typeKw.getText();
            if (StvnTypeResolver.isDegradedNominalAlias(typeKw.getContainingFile(), alias)) {
                var targetDef = StvnTypeReference.resolveTypeInFile(typeKw.getContainingFile(), alias, new HashSet<>());
                var fallbackBase = ":Value";
                var td = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(targetDef);
                if (td != null && td.getSchemaType() != null) {
                    var resolved = StvnTypeResolver.resolveNominalSchema(td.getSchemaType());
                    if (resolved != null) {
                        fallbackBase = StvnSchemaFormatter.formatCleanSchema(resolved);
                    } else {
                        fallbackBase = StvnSchemaFormatter.formatCleanSchema(td.getSchemaType());
                    }
                }
                return new DegradedSchemaInfo(alias, fallbackBase);
            }
        } else if (target instanceof Value value) {
            var info = StvnTypeResolver.resolveBaseTypeInfo(value);
            if (info != null && info.getSchema() != null) {
                var schema = info.getSchema();
                var kw = schema.getTypeKeyword();
                var alias = kw != null ? kw.getText() : null;
                if (alias != null && StvnTypeResolver.isDegradedNominalAlias(value.getContainingFile(), alias)) {
                    var resolved = StvnTypeResolver.resolveNominalSchema(schema);
                    var base = resolved != null ? StvnSchemaFormatter.formatCleanSchema(resolved) : StvnSchemaFormatter.formatCleanSchema(schema);
                    return new DegradedSchemaInfo(alias, base);
                }
            }
        }
        return null;
    }

    private static @Nullable String checkUnqualifiedPreludeReferenceNotice(
            @Nullable PsiElement target,
            PsiElement element,
            @Nullable PsiElement originalElement
    ) {
        TypeKeyword targetKw = null;
        if (element instanceof TypeKeyword kw) {
            targetKw = kw;
        } else if (originalElement != null) {
            targetKw = PsiTreeUtil.getParentOfType(originalElement, TypeKeyword.class, false);
        }
        if (targetKw == null) {
            return null;
        }

        var text = targetKw.getText().trim();
        if (text.startsWith(":org/stvnadore/prelude/")) {
            return null;
        }
        if (!text.startsWith(":") || text.substring(1).contains("/")) {
            return null;
        }

        var project = targetKw.getProject();
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(project, text);
        if (preludeDef == null) {
            return null;
        }

        var file = targetKw.getContainingFile();
        if (file == null || file.getName().contains("prelude")) {
            return null;
        }

        var localDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : localDefs) {
            var kw = def.getTypeKeyword();
            if (kw != null && text.equals(kw.getText()) && def != targetKw.getParent()) {
                return null;
            }
        }
        var useStmts = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class);
        for (var use : useStmts) {
            var aliasBlock = use.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2 && list.get(1) != null && text.equals(list.get(1).getText())) {
                        return null;
                    }
                    if (list.size() == 1 && list.get(0) != null && text.equals(list.get(0).getText())) {
                        return null;
                    }
                }
            }
            var opts = use.getUseOptionsBlock();
            if (opts != null && opts.getText().contains("#strip")) {
                var useTarget = use.getUseTarget();
                if (useTarget != null && useTarget.getText().equals(":org/stvnadore/prelude")) {
                    return null;
                }
            }
        }

        var fqni = ":org/stvnadore/prelude/" + text.substring(1);
        return """
            <div style="padding: 6px 10px; border-left: 4px solid #E57373; background-color: rgba(229, 115, 115, 0.12); color: #C62828; margin-bottom: 10px; border-radius: 2px; font-family: sans-serif;">
                <b style="font-size: 1.05em;">&#9888; Unqualified Reference Warning</b><br/>
                Unqualified reference to Standard Library Prelude type '<code>%s</code>'.<br/>
                Use fully qualified identifier, import via <code>:use</code>, or brand locally in <code>:defs</code>.
            </div>
            """.formatted(fqni);
    }

    private static @Nullable String buildPreludeTypeDocumentation(
            @Nullable PsiElement target,
            PsiElement element,
            @Nullable PsiElement originalElement
    ) {
        TypeKeyword targetKw = null;
        if (element instanceof TypeKeyword kw) {
            targetKw = kw;
        } else if (originalElement != null) {
            targetKw = PsiTreeUtil.getParentOfType(originalElement, TypeKeyword.class, false);
        }
        var text = targetKw != null ? targetKw.getText().trim() : element.getText().trim();
        var project = element.getProject();
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(project, text);
        if (preludeDef == null) {
            return null;
        }

        var sb = new StringBuilder();
        var kw = preludeDef.getTypeKeyword();
        var fqni = kw != null ? kw.getText() : (text.startsWith(":org/stvnadore/prelude/") ? text : ":org/stvnadore/prelude/" + text.substring(1));
        sb.append("<b>Standard Library Prelude:</b> ").append(fqni).append("<br/>");

        var schemaType = preludeDef.getSchemaType();
        if (schemaType != null) {
            sb.append("<b>Underlying Type:</b> <code>").append(StvnSchemaFormatter.formatCleanSchema(schemaType)).append("</code><br/>");
        }
        var metaMap = preludeDef.getMetadataMap();
        if (metaMap != null) {
            sb.append("<b>Validation Constraint:</b> <code>").append(metaMap.getText()).append("</code><br/>");
        }
        sb.append("<hr/>");
        sb.append("<b>Underlying Structure:</b> ").append(schemaType != null ? StvnSchemaFormatter.formatCleanSchema(schemaType) : ":Value");

        var specDoc = getBuiltInPreludeDoc(text.startsWith(":org/stvnadore/prelude/") ? ":" + text.substring(":org/stvnadore/prelude/".length()) : text);
        if (specDoc != null && specDoc.contains("<hr/>")) {
            var descPart = specDoc.substring(specDoc.indexOf("<hr/>") + 5);
            sb.append("<br/>").append(descPart);
        }

        return sb.toString();
    }

    private @Nullable String buildTargetDocumentation(PsiElement target, @Nullable PsiElement originalElement) {
        if (target != null) {
            var targetText = target.getText().trim();
            if (targetText.startsWith(":org/stvnadore/prelude/DateTime") || targetText.startsWith(":org/stvnadore/prelude/TimeEpoch")) {
                return null;
            }
        }

        // 1. Contextual Section Routing: Check if target is RHS base type in :defs
        if ((target instanceof TypeKeyword || target instanceof AtomicType) && originalElement != null) {
            var enclosingDef = PsiTreeUtil.getParentOfType(originalElement, TypeDefinition.class);
            if (enclosingDef != null && enclosingDef.getTypeKeyword() != target) {
                return renderRhsBaseTypeCard(target.getText().trim(), enclosingDef);
            }
        }

        // 2. Contextual Section Routing: Check if hovering in :type root clause on an alias
        if (originalElement != null) {
            var typeEntry = PsiTreeUtil.getParentOfType(originalElement, TypeEntry.class);
            if (typeEntry != null && target instanceof TypeDefinition) {
                var rootSchema = typeEntry.getSchemaType();
                var elemSchema = PsiTreeUtil.getParentOfType(originalElement, SchemaType.class);
                if (rootSchema != null && elemSchema == rootSchema) {
                    return renderRootPayloadSchemaCard(rootSchema, typeEntry);
                }
            }
        }

        if (target instanceof TypeDefinition typeDef) {
            return renderLhsTypeAliasCard(typeDef, originalElement);
        }

        if (target instanceof IncludeMapAlias alias) {
            var list = alias.getTypeKeywordList();
            var localKw = list.size() >= 2 ? list.get(1) : (list.size() >= 1 ? list.get(0) : null);
            var remoteKw = list.size() >= 1 ? list.get(0) : null;
            var includeElement = PsiTreeUtil.getParentOfType(alias, IncludeElement.class);
            var fileString = includeElement != null && includeElement.getStringLiteral() != null
                ? includeElement.getStringLiteral().getText()
                : "Unknown";

            var sb = new StringBuilder();
            if (localKw != null) {
                sb.append("<b>Type Alias:</b> ").append(localKw.getText()).append("<br/>");
            }
            sb.append("<b>Imported From:</b> <code>").append(fileString).append("</code><br/>");

            if (localKw != null) {
                var trace = StvnTypeReference.extractResolutionTrace(localKw);
                if (trace.size() > 1) {
                    sb.append("<b>Resolution Path:</b> ");
                    for (var i = 0; i < trace.size(); i++) {
                        if (i > 0) sb.append(" &rarr; ");
                        sb.append(trace.get(i));
                    }
                    sb.append("<br/>");
                }
            }

            var metricHtml = extractStructuralMetricHtml(alias);
            if (metricHtml != null) {
                sb.append(metricHtml).append("<br/>");
            }

            var resolvedTarget = remoteKw != null ? new StvnTypeReference(remoteKw).resolve() : null;
            var remTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolvedTarget);
            var subset = StvnTypeResolver.resolveEnumSubset(alias);
            if (subset == null && remTypeDef != null) {
                subset = StvnTypeResolver.resolveEnumSubset(remTypeDef);
            }

            if (subset != null) {
                var lineage = formatDerivationLineage(remTypeDef, subset);
                if (lineage != null) {
                    sb.append("<b>Derivation:</b> ").append(lineage).append("<br/>");
                }
            }

            var underlying = "Unknown";
            if (subset != null) {
                underlying = StvnSchemaFormatter.formatEnumVariants(subset.allowedVariants());
            } else if (remTypeDef != null && remTypeDef.getSchemaType() != null) {
                underlying = StvnTypeResolver.formatSchemaType(remTypeDef.getSchemaType());
            }
            sb.append("<hr/>");
            if (remTypeDef != null) {
                var facetsTable = buildEffectiveFacetsTable(remTypeDef);
                if (facetsTable != null) {
                    sb.append(facetsTable).append("<br/>");
                }
            }
            sb.append("<b>Underlying Structure:</b> ").append(underlying);
            return sb.toString();
        }

        if (target instanceof org.stvnadore.psi.UseMapAlias alias) {
            var typeList = alias.getTypeKeywordList();
            var valList = alias.getValueKeywordList();
            var useStmt = PsiTreeUtil.getParentOfType(alias, org.stvnadore.psi.UseStmt.class);
            var targetText = useStmt != null && useStmt.getUseTarget() != null
                ? useStmt.getUseTarget().getText()
                : "Unknown";

            var sb = new StringBuilder();
            if (typeList.size() >= 2) {
                var remoteKw = typeList.get(0);
                var localKw = typeList.get(1);
                var remoteName = remoteKw.getText();
                var localName = localKw.getText();
                if (remoteName.equals(localName)) {
                    sb.append("<b>Scoped Import:</b> ").append(localName).append("<br/>");
                    sb.append("<b>Imported From:</b> <code>").append(targetText).append("</code><br/>");
                    sb.append("<hr/>");
                    sb.append("<b>Target Type:</b> ").append(remoteName);
                } else {
                    var bareRemote = remoteName.startsWith(":") ? remoteName.substring(1) : remoteName;
                    var fqni = targetText + "/" + bareRemote;
                    sb.append("<b>Renamed Reference:</b> ").append(localName).append(" &rarr; ").append(fqni).append("<br/>");
                    sb.append("<b>Source Namespace:</b> ").append(targetText);
                }

                var chainedDoc = resolveChainedTypeDocumentation(alias, remoteName, targetText, originalElement);
                if (chainedDoc != null) {
                    sb.append("<hr/>").append(chainedDoc);
                }
                return sb.toString();
            } else if (valList.size() >= 2) {
                var remoteKw = valList.get(0);
                var localKw = valList.get(1);
                var remoteName = remoteKw.getText();
                var localName = localKw.getText();
                if (remoteName.equals(localName)) {
                    sb.append("<b>Scoped Import:</b> ").append(localName).append("<br/>");
                    sb.append("<b>Imported From:</b> <code>").append(targetText).append("</code><br/>");
                    sb.append("<hr/>");
                    sb.append("<b>Target Constant:</b> ").append(remoteName);
                } else {
                    var bareRemote = remoteName.startsWith("#") ? remoteName.substring(1) : remoteName;
                    var fqni = targetText + "/" + bareRemote;
                    sb.append("<b>Renamed Reference:</b> ").append(localName).append(" &rarr; ").append(fqni).append("<br/>");
                    sb.append("<b>Source Namespace:</b> ").append(targetText);
                }

                var chainedConstDoc = resolveChainedConstantDocumentation(alias, remoteName, targetText, originalElement);
                if (chainedConstDoc != null) {
                    sb.append("<hr/>").append(chainedConstDoc);
                }
                return sb.toString();
            }
        }

        if (target instanceof ConstantDefinition constDef) {
            var keyword = constDef.getValueKeyword();
            var schemaType = constDef.getSchemaType();
            var value = constDef.getValue();

            var sb = new StringBuilder();
            var containingFile = constDef.getContainingFile();
            var isPrelude = containingFile != null && StvnPreludeBridge.isPreludeFile(containingFile);
            var enclosingPkg = PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class);

            if (isPrelude || (enclosingPkg != null && enclosingPkg.getPackagePath() != null && enclosingPkg.getPackagePath().getText().startsWith(":org/stvnadore/prelude"))) {
                sb.append("<b>Standard Library Prelude Constant:</b> ");
            } else {
                sb.append("<b>Typed Constant:</b> ");
            }
            if (keyword != null) {
                sb.append("<code>").append(keyword.getText()).append("</code><br/>");
            }
            if (enclosingPkg != null && enclosingPkg.getPackagePath() != null) {
                var pkgPath = enclosingPkg.getPackagePath().getText().trim();
                var localName = keyword != null ? keyword.getText() : "";
                var cleanPkg = pkgPath.startsWith(":") ? pkgPath.substring(1) : pkgPath;
                var cleanLocal = localName.startsWith("#") ? localName.substring(1) : localName;
                var fqni = "#" + cleanPkg + "/" + cleanLocal;
                sb.append("<b>Package:</b> <code>").append(pkgPath).append("</code><br/>");
                sb.append("<b>FQNI:</b> <code>").append(fqni).append("</code><br/>");
            }
            if (schemaType != null) {
                sb.append("<b>Type:</b> ").append(StvnSchemaFormatter.formatSchema(schemaType)).append("<br/>");
            }
            if (value != null) {
                sb.append("<hr/>");
                sb.append("<b>Value:</b> ").append(value.getText());
            }
            return sb.toString();
        }

        if (target instanceof ValueKeyword valKw) {
            var text = valKw.getText().trim();
            if (valKw.getParent() instanceof ConstantDefinition constDef) {
                return buildTargetDocumentation(constDef, originalElement);
            }
            var ref = valKw.getReference();
            var resolved = ref != null ? ref.resolve() : new StvnConstantReference(valKw).resolve();
            if (resolved instanceof ConstantDefinition cDef) {
                return buildTargetDocumentation(cDef, originalElement);
            }
            if (resolved != null && resolved.getParent() instanceof ConstantDefinition cDef) {
                return buildTargetDocumentation(cDef, originalElement);
            }
            if (valKw.getParent() instanceof org.stvnadore.psi.EnumDef enumDef) {
                var typeDef = PsiTreeUtil.getParentOfType(enumDef, TypeDefinition.class);
                var typeName = (typeDef != null && typeDef.getTypeKeyword() != null) ? typeDef.getTypeKeyword().getText() : "Enum";
                var variants = enumDef.getValueKeywordList();
                var idx = variants.indexOf(valKw);

                var sb = new StringBuilder();
                sb.append("<b>Enum Variant:</b> ").append(valKw.getText()).append("<br/>");
                sb.append("<b>Declared In:</b> <code>").append(typeName).append("</code><br/>");
                if (idx >= 0) {
                    sb.append("<b>Variant Index:</b> ").append(idx);
                }
                return sb.toString();
            }
            if (text.equals("#BODY") || text.equals("#DEFS") || text.equals("#BODY_INCLUDE") || text.equals("#DEFS_INCLUDE")) {
                return """
                    <b>Document Kind Variant:</b> <code>%s</code><br/>
                    <b>Enclosing Enum:</b> <code>:org/stvnadore/prelude/meta/StvnDocumentKind</code> (<code>:StvnDocumentKind</code>)<br/>
                    <b>Allowed Variants:</b> <code>[ #BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE ]</code>
                    """.formatted(text);
            }
        }

        if (target instanceof MetaNameFacet facet) {
            String baseDoc = null;
            try {
                var containingFile = facet.getContainingFile();
                var preludeDef = StvnPreludeBridge.isPreludeFile(containingFile)
                    ? StvnPreludeBridge.resolvePreludeTypeDefinition(containingFile, ":org/stvnadore/prelude/meta/StvnPosixId64")
                    : StvnPreludeBridge.resolvePreludeTypeDefinition(facet.getProject(), ":org/stvnadore/prelude/meta/StvnPosixId64");
                baseDoc = preludeDef != null ? buildTargetDocumentation(preludeDef, originalElement) : null;
            } catch (Exception ignored) {
            }
            return """
                <b>Root Metadata Facet:</b> <code>#name</code> &rarr; <code>:org/stvnadore/prelude/meta/StvnPosixId64</code><br/>
                Declares the nominal document name identifier.<br/>
                <hr/>
                """ + (baseDoc != null ? baseDoc : "<b>Underlying Structure:</b> <code>{ #maxSize 64 } :PosixId</code>");
        }

        if (target instanceof MetaDomainFacet facet) {
            String baseDoc = null;
            try {
                var containingFile = facet.getContainingFile();
                var preludeDef = StvnPreludeBridge.isPreludeFile(containingFile)
                    ? StvnPreludeBridge.resolvePreludeTypeDefinition(containingFile, ":org/stvnadore/prelude/meta/StvnPosixId64")
                    : StvnPreludeBridge.resolvePreludeTypeDefinition(facet.getProject(), ":org/stvnadore/prelude/meta/StvnPosixId64");
                baseDoc = preludeDef != null ? buildTargetDocumentation(preludeDef, originalElement) : null;
            } catch (Exception ignored) {
            }
            return """
                <b>Root Metadata Facet:</b> <code>#domain</code> &rarr; <code>:org/stvnadore/prelude/meta/StvnPosixId64</code><br/>
                Declares the optional secondary domain classification for filename slot 1.<br/>
                <hr/>
                """ + (baseDoc != null ? baseDoc : "<b>Underlying Structure:</b> <code>{ #maxSize 64 } :PosixId</code>");
        }

        if (target instanceof MetaKindFacet facet) {
            String baseDoc = null;
            try {
                var containingFile = facet.getContainingFile();
                var preludeDef = StvnPreludeBridge.isPreludeFile(containingFile)
                    ? StvnPreludeBridge.resolvePreludeTypeDefinition(containingFile, ":org/stvnadore/prelude/meta/StvnDocumentKind")
                    : StvnPreludeBridge.resolvePreludeTypeDefinition(facet.getProject(), ":org/stvnadore/prelude/meta/StvnDocumentKind");
                baseDoc = preludeDef != null ? buildTargetDocumentation(preludeDef, originalElement) : null;
            } catch (Exception ignored) {
            }
            return """
                <b>Root Metadata Facet:</b> <code>#kind</code> &rarr; <code>:org/stvnadore/prelude/meta/StvnDocumentKind</code><br/>
                Declares the architectural document kind variant.<br/>
                <hr/>
                """ + (baseDoc != null ? baseDoc : "<b>Underlying Structure:</b> <code>:Enum [ #BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE ]</code>");
        }

        if (target instanceof MetaKindValue kindVal) {
            var text = kindVal.getText().trim();
            return """
                <b>Document Kind Variant:</b> <code>%s</code><br/>
                <b>Enclosing Enum:</b> <code>:org/stvnadore/prelude/meta/StvnDocumentKind</code> (<code>:StvnDocumentKind</code>)<br/>
                <b>Allowed Variants:</b> <code>[ #BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE ]</code>
                """.formatted(text);
        }

        if (target instanceof MetadataEntry entry) {
            var redundantCard = buildRedundantFacetHoverDoc(entry);
            if (redundantCard != null) {
                return redundantCard;
            }
            var text = entry.getText().trim();
            var keyword = text.split("\\s+")[0];
            var specDesc = getFacetSpecificationDoc(keyword);
            if (specDesc != null) {
                return "<b>Metadata Facet:</b> <code>" + text + "</code><br/><hr/>" + specDesc;
            }
            return "<b>Metadata Facet:</b> <code>" + text + "</code>";
        }

        if (target instanceof ProductType productType) {
            return renderProductTypeCard(productType, originalElement);
        }

        if (target instanceof SumType sumType) {
            return renderSumTypeCard(sumType, originalElement);
        }

        if (target instanceof EnumDef enumDef) {
            var parentSum = PsiTreeUtil.getParentOfType(enumDef, SumType.class);
            if (parentSum != null) {
                return renderSumTypeCard(parentSum, originalElement);
            }
            int variantCount = enumDef.getValueKeywordList().size();
            return String.format("""
                <b>Enumeration Constructor:</b> :Enum [ ... ]<br/>
                <b>Variant Count:</b> %d<br/>
                <hr/>
                Defines a closed set of symbolic value keywords.<br/>
                <b>Payload Syntax:</b> <code>#val1</code>.
                """, variantCount);
        }

        if (target instanceof TypeKeyword typeKw) {
            var parent = typeKw.getParent();
            if (parent instanceof ProductType productType) {
                return renderProductTypeCard(productType, originalElement);
            }
            if (parent instanceof SumType sumType) {
                return renderSumTypeCard(sumType, originalElement);
            }
            var text = typeKw.getText().trim();
            var doc = getBuiltInSpecificationDoc(text);
            if (doc != null) {
                return doc;
            }
        }

        if (target instanceof AtomicType atomicType) {
            var text = atomicType.getText().trim();
            var doc = getBuiltInSpecificationDoc(text);
            if (doc != null) {
                return doc;
            }
        }

        if (target instanceof CollectionType collType) {
            var typeEntry = PsiTreeUtil.getParentOfType(collType, TypeEntry.class);
            if (typeEntry != null && typeEntry.getSchemaType() != null
                && typeEntry.getSchemaType().getSchemaConstructor() != null
                && typeEntry.getSchemaType().getSchemaConstructor().getCollectionType() == collType) {
                return renderRootPayloadSchemaCard(typeEntry.getSchemaType(), typeEntry);
            }
            return renderCollectionTypeCard(collType, originalElement);
        }

        if (target instanceof Value value) {
            return renderPayloadValueCard(value, originalElement);
        }

        return null;
    }

    private @Nullable String buildTimeEpochValueHoverDoc(Value value, String typeLabel) {
        var intLit = value.getIntegerLiteral();
        if (intLit == null) {
            return null;
        }
        var bodyEntry = PsiTreeUtil.getParentOfType(value, BodyEntry.class);
        if (bodyEntry == null) {
            return null;
        }
        var info = StvnTypeResolver.resolveBaseTypeInfo(value);
        if (info == null) {
            return null;
        }
        var baseType = resolveBaseTypeString(info.getSchema(), new HashSet<>());
        if (!StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) {
            return null;
        }
        var scale = extractScaleFlag(info.getSchema());
        var settings = StvnSettings.getInstance(value.getProject());
        var hourFormat = settings.getState().timeEpochHourFormat;
        var result = StvnTimeEpochFormatter.format(intLit.getText().trim(), scale, hourFormat);

        var sb = new StringBuilder();
        sb.append("<br/><b>UTC Time:</b> ").append(result.formattedUtc());

        if (settings.getState().showEpochMagnitudeWarnings && (result.isCivilWindowViolation() || result.isMagnitudeMismatch())) {
            sb.append("<br/><br/>");
            sb.append("""
                <div style="padding: 6px 10px; border-left: 4px solid #F5A623; background-color: rgba(245, 166, 35, 0.12); color: #D68910; margin-top: 6px; border-radius: 2px; font-family: sans-serif;">
                    <b style="font-size: 1.05em;">&#9888; Suspicious Epoch Magnitude Warning</b><br/>
                """);
            if (result.isCivilWindowViolation() && result.year() != null) {
                sb.append(String.format("Computed year <b>%d</b> falls outside civil window [1900, 2100].<br/>", result.year()));
            }
            if (result.isMagnitudeMismatch()) {
                sb.append(String.format("Literal has %s, but declared scale is <b>%s</b> (expected %s).",
                    result.detectedBandDescription(), scale, result.expectedBandDescription()));
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    private static String extractScaleFlag(SchemaType schemaType) {
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_SCALE_S) || text.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                    || text.equals(StvnVocabulary.FACET_KW_SCALE_US) || text.equals(StvnVocabulary.FACET_KW_SCALE_NS)) {
                    return text;
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                var td = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
                if (td != null) {
                    if (td.getMetadataMap() != null) {
                        for (var entry : td.getMetadataMap().getMetadataEntryList()) {
                            var text = entry.getText().trim();
                            if (text.equals(StvnVocabulary.FACET_KW_SCALE_S) || text.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                                || text.equals(StvnVocabulary.FACET_KW_SCALE_US) || text.equals(StvnVocabulary.FACET_KW_SCALE_NS)) {
                                return text;
                            }
                        }
                    }
                    if (td.getSchemaType() != null) {
                        return extractScaleFlag(td.getSchemaType());
                    }
                }
            }
        }
        return StvnVocabulary.FACET_KW_SCALE_MS;
    }

    private static @Nullable String resolveBaseTypeString(SchemaType schemaType, Set<String> visited) {
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            var atomic = constructor.getAtomicType();
            if (atomic != null) {
                return atomic.getText().trim();
            }
            return constructor.getText().trim();
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(text) || StvnVocabulary.isBaseScalarType(text)) {
                return text;
            }
            if (!visited.add(text)) {
                return null;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                var td = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
                if (td != null && td.getSchemaType() != null) {
                    return resolveBaseTypeString(td.getSchemaType(), visited);
                }
            }
        }
        return null;
    }

    private @Nullable String resolveChainedTypeDocumentation(
            org.stvnadore.psi.UseMapAlias alias,
            String remoteName,
            String targetNamespace,
            @Nullable PsiElement originalElement
    ) {
        boolean isPrelude = ":org/stvnadore/prelude".equals(targetNamespace)
            || targetNamespace.startsWith(":org/stvnadore/prelude/")
            || remoteName.startsWith(":org/stvnadore/prelude/");

        if (isPrelude) {
            var specDoc = getBuiltInSpecificationDoc(remoteName);
            if (specDoc != null) {
                return specDoc;
            }
            var preludeDoc = getBuiltInPreludeDoc(remoteName);
            if (preludeDoc != null) {
                return preludeDoc;
            }
            var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(alias.getProject(), remoteName);
            if (preludeDef != null) {
                return buildTargetDocumentation(preludeDef, originalElement);
            }
            return null;
        }

        var file = alias.getContainingFile();
        if (file != null) {
            var bareRemote = remoteName.startsWith(":") ? remoteName.substring(1) : remoteName;
            var fqni = targetNamespace + "/" + bareRemote;
            var resolved = StvnTypeReference.resolveTypeInFile(file, fqni, new HashSet<>());
            if (resolved == null) {
                resolved = StvnTypeReference.resolveTypeInFile(file, remoteName, new HashSet<>());
            }
            var targetTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
            if (targetTypeDef != null) {
                return buildTargetDocumentation(targetTypeDef, originalElement);
            }
        }
        return null;
    }

    private @Nullable String resolveChainedConstantDocumentation(
            org.stvnadore.psi.UseMapAlias alias,
            String remoteName,
            String targetNamespace,
            @Nullable PsiElement originalElement
    ) {
        var file = alias.getContainingFile();
        if (file != null) {
            var bareRemote = remoteName.startsWith("#") ? remoteName.substring(1) : remoteName;
            var fqni = targetNamespace + "/" + bareRemote;
            var resolved = StvnConstantReference.resolveConstantInFile(file, fqni, new HashSet<>());
            if (resolved == null) {
                resolved = StvnConstantReference.resolveConstantInFile(file, remoteName, new HashSet<>());
            }
            if (resolved != null && resolved.getParent() instanceof ConstantDefinition constDef) {
                return buildTargetDocumentation(constDef, originalElement);
            } else if (resolved instanceof ConstantDefinition constDef) {
                return buildTargetDocumentation(constDef, originalElement);
            }
        }
        return null;
    }

    private static @Nullable String buildEffectiveFacetsTable(TypeDefinition typeDef) {
        var rs = StvnTypeResolver.resolveNominalResolvedSchema(typeDef);
        if (rs == null) return null;
        var keyword = typeDef.getTypeKeyword();
        var currentAlias = (keyword != null) ? keyword.getText().trim() : ":Type";
        var baseType = getResolvedBaseTypeString(rs);

        var trace = (keyword != null) ? StvnTypeReference.extractResolutionTrace(keyword) : java.util.List.<String>of();

        record FacetRow(String tag, String effectiveValue, String origin, String status) {}
        var rows = new java.util.ArrayList<FacetRow>();

        var file = typeDef.getContainingFile();
        var diagnostics = StvnTypeResolver.getCompilationDiagnostics(file);
        var invalidFacetTags = new java.util.HashSet<String>();
        if (diagnostics != null) {
            var bareAlias = currentAlias.startsWith(":") ? currentAlias.substring(1) : currentAlias;
            for (var d : diagnostics) {
                var code = d.errorCode().orElse("");
                if (StvnTypeResolver.FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(code)
                        || DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(code)
                        || "E009".equals(code)) {
                    var msg = d.message();
                    if (msg.contains("Constraint violation (" + currentAlias + "):")
                            || msg.contains("Constraint violation (:" + bareAlias + "):")
                            || msg.contains("Constraint violation (" + bareAlias + "):")) {
                        for (var tag : java.util.List.of("#minIncl", "#minExcl", "#maxIncl", "#maxExcl", "#size", "#minSize", "#maxSize", "#regex")) {
                            if (msg.contains(tag) || msg.contains(tag.substring(1))) {
                                invalidFacetTags.add(tag);
                            }
                        }
                    }
                }
            }
        }

        var constraints = rs.constraints();

        // Tier 1: Definition Facets
        if (constraints.unsigned()) {
            rows.add(new FacetRow("#unsigned", "#TRUE", "Declared", "Active"));
        }
        if (constraints.exact()) {
            rows.add(new FacetRow("#exact", "#TRUE", "Declared", "Active"));
        }
        if (constraints.invertible()) {
            rows.add(new FacetRow("#invertible", "#TRUE", "Declared", "Active"));
        }
        if (constraints.scale().isPresent()) {
            rows.add(new FacetRow("#scale", "#" + constraints.scale().get(), "Declared", "Active"));
        }
        if (constraints.offset()) {
            rows.add(new FacetRow("#offset", "#TRUE", "Declared", "Active"));
        }
        if (constraints.zoned()) {
            rows.add(new FacetRow("#zoned", "#TRUE", "Declared", "Active"));
        }
        if (constraints.audited()) {
            rows.add(new FacetRow("#audited", "#TRUE", "Declared", "Active"));
        }
        var declaredMeta = typeDef.getMetadataMap();
        if (declaredMeta == null && typeDef.getSchemaType() != null) {
            declaredMeta = typeDef.getSchemaType().getMetadataMap();
        }
        String declaredMinSize = null;
        String declaredMaxSize = null;
        String declaredSize = null;
        if (declaredMeta != null) {
            for (var entry : declaredMeta.getMetadataEntryList()) {
                var txt = entry.getText().trim();
                if (txt.startsWith("#minSize")) {
                    var parts = txt.split("\\s+");
                    if (parts.length >= 2) declaredMinSize = parts[1];
                } else if (txt.startsWith("#maxSize")) {
                    var parts = txt.split("\\s+");
                    if (parts.length >= 2) declaredMaxSize = parts[1];
                } else if (txt.startsWith("#size")) {
                    var parts = txt.split("\\s+");
                    if (parts.length >= 2) declaredSize = parts[1];
                }
            }
        }

        if (constraints.size().isPresent() || declaredSize != null) {
            String eff = declaredSize != null ? declaredSize : String.valueOf(constraints.size().get());
            var originInfo = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_SIZE);
            rows.add(new FacetRow("#size", eff, originInfo.origin(), originInfo.status()));
        }

        // 1. #preserveIndent (for String types)
        if (":String".equals(baseType)) {
            boolean eff = constraints.preserveIndent();
            var originInfo = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_PRESERVE_INDENT);
            rows.add(new FacetRow("#preserveIndent", eff ? "#TRUE" : "#FALSE", originInfo.origin(), originInfo.status()));
        }

        // 2. #minSize
        if (declaredMinSize != null || constraints.minSize().isPresent() || ":String".equals(baseType) || baseType.startsWith(":Seq") || baseType.startsWith(":Set") || baseType.startsWith(":Map")) {
            String eff = declaredMinSize != null ? declaredMinSize : (constraints.minSize().isPresent() ? String.valueOf(constraints.minSize().get()) : "0");
            var originInfo = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_MIN_SIZE);
            rows.add(new FacetRow("#minSize", eff, originInfo.origin(), originInfo.status()));
        }

        // 3. #maxSize
        if (declaredMaxSize != null || constraints.maxSize().isPresent() || ":String".equals(baseType) || baseType.startsWith(":Seq") || baseType.startsWith(":Set") || baseType.startsWith(":Map")) {
            String eff = declaredMaxSize != null ? declaredMaxSize : (constraints.maxSize().isPresent() ? String.valueOf(constraints.maxSize().get()) : String.valueOf(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY));
            var originInfo = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_MAX_SIZE);
            rows.add(new FacetRow("#maxSize", eff, originInfo.origin(), originInfo.status()));
        }

        // 4. #regex
        if (constraints.regex().isPresent()) {
            String eff = "\"" + constraints.regex().get() + "\"";
            var originInfo = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_REGEX);
            rows.add(new FacetRow("#regex", eff, originInfo.origin(), originInfo.status()));
        }

        // Tier 3: Bounds Facets
        if (constraints.minIncl().isPresent()) {
            String status = invalidFacetTags.contains("#minIncl") ? "Invalid" : "Active";
            rows.add(new FacetRow("#minIncl", constraints.minIncl().get().toPlainString(), "Declared", status));
        }
        if (constraints.minExcl().isPresent()) {
            String status = invalidFacetTags.contains("#minExcl") ? "Invalid" : "Active";
            rows.add(new FacetRow("#minExcl", constraints.minExcl().get().toPlainString(), "Declared", status));
        }
        if (constraints.maxIncl().isPresent()) {
            String status = invalidFacetTags.contains("#maxIncl") ? "Invalid" : "Active";
            rows.add(new FacetRow("#maxIncl", constraints.maxIncl().get().toPlainString(), "Declared", status));
        }
        if (constraints.maxExcl().isPresent()) {
            String status = invalidFacetTags.contains("#maxExcl") ? "Invalid" : "Active";
            rows.add(new FacetRow("#maxExcl", constraints.maxExcl().get().toPlainString(), "Declared", status));
        }
        if (constraints.dateMinIncl().isPresent()) {
            String status = invalidFacetTags.contains("#dateMinIncl") ? "Invalid" : "Active";
            rows.add(new FacetRow("#dateMinIncl", "\"" + constraints.dateMinIncl().get() + "\"", "Declared", status));
        }
        if (constraints.dateMaxExcl().isPresent()) {
            String status = invalidFacetTags.contains("#dateMaxExcl") ? "Invalid" : "Active";
            rows.add(new FacetRow("#dateMaxExcl", "\"" + constraints.dateMaxExcl().get() + "\"", "Declared", status));
        }

        // Recover declared facets stripped by core during degraded schema sanitization
        if (declaredMeta != null) {
            for (var entry : declaredMeta.getMetadataEntryList()) {
                var entryText = entry.getText().trim();
                for (var tag : java.util.List.of("#minIncl", "#minExcl", "#maxIncl", "#maxExcl", "#size", "#regex", "#minSize", "#maxSize")) {
                    if (entryText.startsWith(tag)) {
                        boolean alreadyPresent = rows.stream().anyMatch(r -> r.tag().equals(tag));
                        if (!alreadyPresent) {
                            var parts = entryText.split("\\s+", 2);
                            var val = parts.length > 1 ? parts[1].trim() : "";
                            String status = invalidFacetTags.contains(tag) ? "Invalid" : "Active";
                            rows.add(new FacetRow(tag, val, "Declared", status));
                        }
                    }
                }
            }
        }

        boolean isComposite = isCompositeOrCollectionType(baseType);

        // 5. #equatable
        boolean effEquatable;
        OriginInfo equatableOrigin;
        if (constraints.equatable().isPresent()) {
            effEquatable = constraints.equatable().get();
            equatableOrigin = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_EQUATABLE, effEquatable, isComposite);
        } else {
            effEquatable = resolveExplicitTraitFromPsi(declaredMeta, StvnVocabulary.FACET_KW_EQUATABLE)
                    .orElseGet(() -> resolveDefaultEquatable(baseType, constraints.exact()));
            equatableOrigin = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_EQUATABLE, effEquatable, isComposite);
        }
        rows.add(new FacetRow("#equatable", effEquatable ? "#TRUE" : "#FALSE", equatableOrigin.origin(), equatableOrigin.status()));

        // 6. #comparable
        boolean effComparable;
        OriginInfo comparableOrigin;
        if (constraints.comparable().isPresent()) {
            effComparable = constraints.comparable().get();
            comparableOrigin = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_COMPARABLE, effComparable, isComposite);
        } else {
            effComparable = resolveExplicitTraitFromPsi(declaredMeta, StvnVocabulary.FACET_KW_COMPARABLE)
                    .orElseGet(() -> resolveDefaultComparable(baseType));
            comparableOrigin = resolveFacetOrigin(typeDef, trace, rs, currentAlias, baseType, StvnVocabulary.FACET_NAME_COMPARABLE, effComparable, isComposite);
        }
        rows.add(new FacetRow("#comparable", effComparable ? "#TRUE" : "#FALSE", comparableOrigin.origin(), comparableOrigin.status()));

        if (rows.isEmpty()) return null;

        // Sort rows strictly into 5-tier canonical order (§ 6.1)
        rows.sort(java.util.Comparator.comparingInt(r -> org.stvnadore.plugin.validation.StvnMetadataOrderInspection.getFacetRank(r.tag())));

        var html = new StringBuilder();
        html.append("<b>Effective Facets &amp; Traits:</b><br/>");
        html.append("<table style=\"width: 100%; border-collapse: collapse; margin-top: 4px; margin-bottom: 6px; font-size: 0.9em;\">");
        html.append("<thead><tr style=\"border-bottom: 1px solid #").append(getBorderColorHex()).append("; text-align: left;\">");
        html.append("<th width=\"28%\" style=\"padding: 2px 4px;\">Facet Tag</th>");
        html.append("<th width=\"28%\" style=\"padding: 2px 4px;\">Effective Value</th>");
        html.append("<th width=\"24%\" style=\"padding: 2px 4px;\">Origin</th>");
        html.append("<th width=\"20%\" style=\"padding: 2px 4px;\">Status</th>");
        html.append("</tr></thead><tbody>");

        for (var row : rows) {
            String statusColor = "Invalid".equals(row.status()) ? ("#" + getViolationBadgeColorHex())
                : ("Active Override".equals(row.status()) ? ("#" + getActiveOverrideColorHex())
                : ("Inherited".equals(row.status()) ? ("#" + getInheritedColorHex()) : "#9E9E9E"));
            html.append("<tr>");
            html.append("<td style=\"padding: 2px 4px;\"><code>").append(row.tag()).append("</code></td>");
            html.append("<td style=\"padding: 2px 4px;\"><code>").append(row.effectiveValue()).append("</code></td>");
            html.append("<td style=\"padding: 2px 4px;\">").append(row.origin()).append("</td>");
            html.append("<td style=\"padding: 2px 4px;\"><b style=\"color: ").append(statusColor).append(";\">").append(row.status()).append("</b></td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static java.util.Optional<Boolean> resolveExplicitTraitFromPsi(@Nullable MetadataMap declaredMeta, String facetTag) {
        if (declaredMeta == null) return java.util.Optional.empty();
        for (var entry : declaredMeta.getMetadataEntryList()) {
            var text = entry.getText().trim();
            if (text.startsWith(facetTag)) {
                var parts = text.split("\\s+");
                if (parts.length > 1) {
                    var val = parts[1].trim();
                    if ("#TRUE".equalsIgnoreCase(val) || "#T".equalsIgnoreCase(val)) {
                        return java.util.Optional.of(true);
                    } else if ("#FALSE".equalsIgnoreCase(val) || "#F".equalsIgnoreCase(val)) {
                        return java.util.Optional.of(false);
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean resolveDefaultEquatable(String baseType, boolean isExact) {
        if (StvnVocabulary.TYPE_FLOAT.equals(baseType) && !isExact) {
            return false;
        }
        return true;
    }

    private static boolean resolveDefaultComparable(String baseType) {
        if (baseType.startsWith(":Set") || baseType.startsWith(":Map")) {
            return false;
        }
        return true;
    }

    private static boolean isCompositeOrCollectionType(String baseType) {
        return baseType.startsWith(":Tuple") || baseType.startsWith(":Seq") || baseType.startsWith(":Set") || baseType.startsWith(":Map")
                || baseType.startsWith(":Union") || baseType.startsWith(":Either") || baseType.startsWith(":Option");
    }

    private static String getResolvedBaseTypeString(org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema rs) {
        return StvnSchemaFormatter.formatCleanAntlrSchema(rs.node());
    }

    private record OriginInfo(String origin, String status) {}

    private static OriginInfo resolveFacetOrigin(
            TypeDefinition rootTypeDef,
            java.util.List<String> trace,
            org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema currentSchema,
            String currentAlias,
            String baseType,
            String facetKey
    ) {
        return resolveFacetOrigin(rootTypeDef, trace, currentSchema, currentAlias, baseType, facetKey, false, false);
    }

    private static OriginInfo resolveFacetOrigin(
            TypeDefinition rootTypeDef,
            java.util.List<String> trace,
            org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema currentSchema,
            String currentAlias,
            String baseType,
            String facetKey,
            boolean effectiveValue,
            boolean isComposite
    ) {
        if (hasExplicitFacetInPsi(rootTypeDef, facetKey) || isFacetExplicitlyOverridden(currentSchema.localConstraints().orElse(null), facetKey)) {
            return new OriginInfo("Declared on " + currentAlias, "Active Override");
        }

        var file = rootTypeDef.getContainingFile();
        for (int i = 1; i < trace.size(); i++) {
            var alias = trace.get(i);
            if (file != null && alias.startsWith(":")) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, alias, new java.util.HashSet<>());
                var ancestorTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
                if (ancestorTypeDef != null && hasExplicitFacetInPsi(ancestorTypeDef, facetKey)) {
                    return new OriginInfo("Inherited from " + alias, "Inherited");
                }
            }
        }

        var curr = currentSchema;
        while (curr.underlyingSchema().isPresent()) {
            String parentAlias = curr.aliasName().orElse(null);
            curr = curr.underlyingSchema().get();
            var parentLocal = curr.localConstraints().orElse(null);
            if (isFacetExplicitlyOverridden(parentLocal, facetKey)) {
                String source = (parentAlias != null) ? parentAlias : "Parent";
                return new OriginInfo("Inherited from " + source, "Inherited");
            }
        }

        if (isComposite && (StvnVocabulary.FACET_NAME_EQUATABLE.equals(facetKey) || StvnVocabulary.FACET_NAME_COMPARABLE.equals(facetKey))) {
            return resolveDerivedTraitOrigin(rootTypeDef, baseType, facetKey, effectiveValue);
        }

        return new OriginInfo("Default (" + baseType + ")", "Default");
    }

    private static OriginInfo resolveDerivedTraitOrigin(
            TypeDefinition rootTypeDef,
            String baseType,
            String facetKey,
            boolean effectiveValue
    ) {
        if (effectiveValue) {
            return new OriginInfo("Derived (all constituent fields conform)", "Derived");
        }

        boolean isComparable = StvnVocabulary.FACET_NAME_COMPARABLE.equals(facetKey);
        if (isComparable && (baseType.startsWith(":Set") || baseType.startsWith(":Map"))) {
            return new OriginInfo("Derived (unordered collection)", "Derived");
        }

        var nonConforming = extractNonConformingConstituents(rootTypeDef, isComparable);
        if (nonConforming.isEmpty()) {
            if (isComparable && (baseType.startsWith(":Set") || baseType.startsWith(":Map"))) {
                return new OriginInfo("Derived (unordered collection)", "Derived");
            }
            return new OriginInfo("Derived (all constituent fields conform)", "Derived");
        }

        String label = isComparable ? "non-comparable: " : "non-equatable: ";
        return new OriginInfo("Derived (" + label + String.join(", ", nonConforming) + ")", "Derived");
    }

    private static java.util.List<String> extractNonConformingConstituents(TypeDefinition typeDef, boolean checkComparable) {
        var nonConforming = new java.util.ArrayList<String>();
        var terminal = resolveTerminalSchemaType(typeDef, new java.util.HashSet<>());
        var schemaType = (terminal != null) ? terminal : typeDef.getSchemaType();
        if (schemaType == null || schemaType.getSchemaConstructor() == null) {
            return nonConforming;
        }

        var ctor = schemaType.getSchemaConstructor();
        java.util.List<SchemaType> constituents = java.util.List.of();
        if (ctor.getProductType() != null) {
            constituents = ctor.getProductType().getSchemaTypeList();
        } else if (ctor.getCollectionType() != null) {
            constituents = ctor.getCollectionType().getSchemaTypeList();
        } else if (ctor.getSumType() != null) {
            constituents = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        }

        var file = typeDef.getContainingFile();
        for (var constituent : constituents) {
            var kw = constituent.getTypeKeyword();
            String fieldName = (constituent.getMetadataMap() != null)
                ? constituent.getText().trim()
                : (kw != null ? kw.getText().trim() : constituent.getText().trim());

            if (kw != null) {
                var typeName = kw.getText().trim();
                if (typeName.equals(":Float")) {
                    boolean exact = hasFacetInMap(constituent.getMetadataMap(), "exact");
                    if (!checkComparable && !exact) {
                        nonConforming.add(fieldName);
                    }
                    continue;
                } else if (typeName.equals(":Int") || typeName.equals(":String") || typeName.equals(":Boolean") || typeName.equals(":TimeEpoch") || typeName.equals(":DateTime")) {
                    continue;
                }
                if (file != null) {
                    var resolvedDef = StvnTypeReference.resolveTypeInFile(file, typeName, new java.util.HashSet<>());
                    var targetTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolvedDef);
                    if (targetTypeDef != null) {
                        var targetRs = StvnTypeResolver.resolveNominalResolvedSchema(targetTypeDef);
                        if (targetRs != null) {
                            boolean conforms = checkComparable
                                ? targetRs.constraints().comparable().orElse(true)
                                : targetRs.constraints().equatable().orElse(false);
                            if (!conforms) {
                                nonConforming.add(fieldName);
                            }
                            continue;
                        }
                    }
                }
            } else if (constituent.getSchemaConstructor() != null) {
                var inlineText = constituent.getText();
                if (!checkComparable && inlineText.contains(":Float") && !inlineText.contains("#exact")) {
                    nonConforming.add(fieldName);
                }
            }
        }
        return nonConforming;
    }

    private static boolean hasExplicitFacetInPsi(TypeDefinition typeDef, String facetKey) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType != null && hasFacetInMap(schemaType.getMetadataMap(), facetKey)) {
            return true;
        }
        return hasFacetInMap(typeDef.getMetadataMap(), facetKey);
    }

    private static boolean hasFacetInMap(@Nullable MetadataMap metaMap, String facetKey) {
        if (metaMap == null) return false;
        for (var entry : metaMap.getMetadataEntryList()) {
            if (matchesFacetEntry(entry, facetKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesFacetEntry(MetadataEntry entry, String facetKey) {
        var text = entry.getText().trim();
        return switch (facetKey) {
            case StvnVocabulary.FACET_NAME_PRESERVE_INDENT ->
                entry.getMetadataTrait() != null || text.startsWith(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
            case StvnVocabulary.FACET_NAME_MIN_SIZE ->
                text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE);
            case StvnVocabulary.FACET_NAME_MAX_SIZE ->
                text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE);
            case StvnVocabulary.FACET_NAME_REGEX ->
                text.startsWith(StvnVocabulary.FACET_KW_REGEX);
            case StvnVocabulary.FACET_NAME_EQUATABLE ->
                text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE);
            case StvnVocabulary.FACET_NAME_COMPARABLE ->
                text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE);
            default ->
                text.startsWith("#" + facetKey);
        };
    }

    private static boolean isFacetExplicitlyOverridden(
            org.stvnadore.core.validation.StvnTypeResolver.@Nullable StvnConstraints local,
            String facetKey
    ) {
        if (local == null) return false;
        if (local.explicitOverrides().contains(facetKey)) return true;
        return switch (facetKey) {
            case StvnVocabulary.FACET_NAME_REGEX -> local.regex().isPresent();
            case StvnVocabulary.FACET_NAME_MIN_SIZE -> local.minSize().isPresent();
            case StvnVocabulary.FACET_NAME_MAX_SIZE -> local.maxSize().isPresent();
            case StvnVocabulary.FACET_NAME_SIZE -> local.size().isPresent();
            default -> false;
        };
    }

    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, @Nullable PsiElement originalElement) {
        var settings = StvnProjectSettings.getInstance(element.getProject());
        if (settings != null && !settings.getState().showHoverDocs) {
            return null;
        }
        if (isContainerDocumentationSuppressionTarget(element) || (originalElement != null && isContainerDocumentationSuppressionTarget(originalElement))) {
            return null;
        }
        var target = getDocumentationElement(element, originalElement);
        if (target == null) {
            return null;
        }

        var unqualifiedNotice = checkUnqualifiedPreludeReferenceNotice(target, element, originalElement);
        if (unqualifiedNotice != null) {
            TypeKeyword targetKw = (element instanceof TypeKeyword kw) ? kw : (originalElement != null ? PsiTreeUtil.getParentOfType(originalElement, TypeKeyword.class, false) : null);
            var text = targetKw != null ? targetKw.getText().trim() : element.getText().trim();
            var name = text.startsWith(":") ? text.substring(1) : text;
            return "Unqualified reference to Standard Library Prelude type ':org/stvnadore/prelude/" + name + "'";
        }

        if (target instanceof TypeDefinition typeDef) {
            var keyword = typeDef.getTypeKeyword();
            if (keyword != null) {
                var kwText = keyword.getText();
                if (kwText.startsWith(":org/stvnadore/prelude/")) {
                    kwText = ":" + kwText.substring(":org/stvnadore/prelude/".length());
                }
                var specQuick = getBuiltInSpecificationQuickNavigateInfo(kwText);
                if (specQuick != null && (typeDef.getContainingFile() == null || typeDef.getContainingFile().getName().contains("prelude"))) {
                    return specQuick;
                }
                var trace = StvnTypeReference.extractResolutionTrace(keyword);
                var resolutionStr = String.join(" -> ", trace);
                return "Type Alias: " + resolutionStr;
            }
        }

        if (target instanceof IncludeMapAlias alias) {
            var list = alias.getTypeKeywordList();
            var localKw = list.size() >= 2 ? list.get(1) : null;
            if (localKw != null) {
                var trace = StvnTypeReference.extractResolutionTrace(localKw);
                var resolutionStr = String.join(" -> ", trace);
                return "Type Alias: " + resolutionStr;
            }
        }

        if (target instanceof org.stvnadore.psi.UseMapAlias alias) {
            var list = alias.getTypeKeywordList();
            var useStmt = PsiTreeUtil.getParentOfType(alias, org.stvnadore.psi.UseStmt.class);
            var targetText = useStmt != null && useStmt.getUseTarget() != null ? useStmt.getUseTarget().getText() : "";
            if (list.size() >= 2) {
                var remote = list.get(0).getText();
                var local = list.get(1).getText();
                if (remote.equals(local)) {
                    return "Scoped Import: " + local + " from " + targetText;
                } else {
                    var bare = remote.startsWith(":") ? remote.substring(1) : remote;
                    return "Renamed Reference: " + local + " -> " + targetText + "/" + bare;
                }
            }
            var valList = alias.getValueKeywordList();
            if (valList.size() >= 2) {
                var remote = valList.get(0).getText();
                var local = valList.get(1).getText();
                if (remote.equals(local)) {
                    return "Scoped Import: " + local + " from " + targetText;
                } else {
                    var bare = remote.startsWith("#") ? remote.substring(1) : remote;
                    return "Renamed Reference: " + local + " -> " + targetText + "/" + bare;
                }
            }
        }

        if (target instanceof ConstantDefinition constDef) {
            var keyword = constDef.getValueKeyword();
            var schemaType = constDef.getSchemaType();
            if (keyword != null && schemaType != null) {
                return "Constant: " + keyword.getText() + " " + StvnSchemaFormatter.formatSchema(schemaType);
            }
        }

        if (target instanceof ValueKeyword valKw && valKw.getParent() instanceof ConstantDefinition constDef) {
            var keyword = constDef.getValueKeyword();
            var schemaType = constDef.getSchemaType();
            if (keyword != null && schemaType != null) {
                return "Constant: " + keyword.getText() + " " + StvnSchemaFormatter.formatSchema(schemaType);
            }
        }

        if (target instanceof ValueKeyword valKw && valKw.getParent() instanceof org.stvnadore.psi.EnumDef enumDef) {
            var typeDef = PsiTreeUtil.getParentOfType(enumDef, TypeDefinition.class);
            var typeName = (typeDef != null && typeDef.getTypeKeyword() != null) ? typeDef.getTypeKeyword().getText() : "Enum";
            return "Enum Variant: " + valKw.getText() + " in " + typeName;
        }

        if (target instanceof MetaNameFacet) {
            return "Root Metadata Facet: #name -> :org/stvnadore/prelude/meta/StvnPosixId64";
        }

        if (target instanceof MetaDomainFacet) {
            return "Root Metadata Facet: #domain -> :org/stvnadore/prelude/meta/StvnPosixId64";
        }

        if (target instanceof MetaKindFacet) {
            return "Root Metadata Facet: #kind -> :org/stvnadore/prelude/meta/StvnDocumentKind";
        }

        if (target instanceof MetaKindValue kindVal) {
            return "Document Kind Variant: " + kindVal.getText().trim() + " in :StvnDocumentKind";
        }

        if (target instanceof TypeKeyword typeKw) {
            var text = typeKw.getText().trim();
            var quickInfo = getBuiltInSpecificationQuickNavigateInfo(text);
            if (quickInfo != null) {
                return quickInfo;
            }
        }

        if (target instanceof AtomicType atomicType) {
            var text = atomicType.getText().trim();
            var quickInfo = getBuiltInSpecificationQuickNavigateInfo(text);
            if (quickInfo != null) {
                return quickInfo;
            }
        }

        if (target instanceof MetadataEntry entry) {
            var redundantCard = buildRedundantFacetHoverDoc(entry);
            if (redundantCard != null) {
                return redundantCard;
            }
            var text = entry.getText().trim();
            var keyword = text.split("\\s+")[0];
            var specDesc = getFacetSpecificationDoc(keyword);
            if (specDesc != null) {
                return "<b>Metadata Facet:</b> <code>" + text + "</code><br/><hr/>" + specDesc;
            }
            return "<b>Metadata Facet:</b> <code>" + text + "</code>";
        }

        if (target instanceof ProductType productType) {
            var firstChild = productType.getFirstChild();
            var text = firstChild != null ? firstChild.getText().trim() : productType.getText().trim();
            var quickInfo = getBuiltInSpecificationQuickNavigateInfo(text);
            if (quickInfo != null) {
                return quickInfo;
            }
        }

        if (target instanceof CollectionType collType) {
            var firstChild = collType.getFirstChild();
            var text = firstChild != null ? firstChild.getText().trim() : collType.getText().trim();
            var quickInfo = getBuiltInSpecificationQuickNavigateInfo(text);
            if (quickInfo != null) {
                return quickInfo;
            }
        }

        if (target instanceof SumType sumType) {
            var firstChild = sumType.getFirstChild();
            var text = firstChild != null ? firstChild.getText().trim() : sumType.getText().trim();
            var quickInfo = getBuiltInSpecificationQuickNavigateInfo(text);
            if (quickInfo != null) {
                return quickInfo;
            }
        }

        if (target instanceof Value value) {
            var typeLabel = StvnTypeResolver.resolveValueType(value);
            if (typeLabel != null) {
                return "Value Type: " + typeLabel;
            }
        }

        return null;
    }

    private static @Nullable String getBuiltInSpecificationDoc(String keyword) {
        if (keyword.startsWith(":org/stvnadore/prelude/")) {
            var shortName = ":" + keyword.substring(":org/stvnadore/prelude/".length());
            var preludeDoc = getBuiltInPreludeDoc(shortName);
            if (preludeDoc != null) {
                return preludeDoc.replace("<b>Standard Library Prelude:</b> " + shortName, "<b>Standard Library Prelude:</b> " + keyword);
            }
            return null;
        }
        var temporalDoc = getBuiltInTemporalDoc(keyword);
        if (temporalDoc != null) {
            return temporalDoc;
        }
        var preludeDoc = getBuiltInPreludeDoc(keyword);
        if (preludeDoc != null) {
            return preludeDoc;
        }

        if (keyword.equals(":Boolean")) {
            return """
                <b>Built-in Primitive:</b> :Boolean<br/>
                <hr/>
                Represents a strict mathematical boolean truth value.<br/>
                <b>Allowed Literals:</b> <code>#TRUE</code>, <code>#FALSE</code>, <code>#T</code>, <code>#F</code>.<br/>
                <b>Constraint:</b> Prohibits integer truthiness (<code>0</code> / <code>1</code>).<br/>
                <b>Supported Facets:</b> <code>#equatable</code>, <code>#comparable</code>.
                """;
        }

        if (keyword.equals(":Int")) {
            return """
                <b>Built-in Numeric:</b> :Int<br/>
                <hr/>
                Represents an integer value.<br/>
                <b>Lexical Forms:</b> Decimal (<code>42</code>), Hex (<code>0x2A</code>), Binary (<code>0b101010</code>), Octal (<code>0o52</code>).<br/>
                <b>Supported Facets:</b> <code>#size</code>, <code>#unsigned</code>, <code>#minIncl</code>, <code>#maxExcl</code>, <code>#equatable</code>, <code>#comparable</code>.
                """;
        }

        if (keyword.equals(":Float")) {
            return """
                <b>Built-in Floating-Point:</b> :Float<br/>
                <hr/>
                Represents a floating-point numeric value.<br/>
                <b>Lexical Form:</b> Standard decimal notation with decimal point (e.g. <code>3.14159</code>, <code>-0.5</code>).<br/>
                <b>Supported Facets:</b> <code>#size</code>, <code>#exact</code>, <code>#minIncl</code>, <code>#maxIncl</code>, <code>#minExcl</code>, <code>#maxExcl</code>, <code>#equatable</code>, <code>#comparable</code>.
                """;
        }

        if (keyword.equals(":String")) {
            return """
                <b>Built-in String Type:</b> :String<br/>
                <hr/>
                Represents an unbounded UTF-8 text string (up to maximum allocation size of 16,777,216 characters).<br/>
                <b>Forms:</b> Simple double-quoted (<code>"text"</code>), Fenced verbatim (<code>\"\"\"[TAG]...[TAG]\"\"\"</code>).<br/>
                <b>Supported Facets:</b> <code>#minSize</code>, <code>#maxSize</code>, <code>#minIncl</code>, <code>#minExcl</code>, <code>#maxIncl</code>, <code>#maxExcl</code>, <code>#regex</code>, <code>#preserveIndent</code>, <code>#equatable</code>, <code>#comparable</code>.
                """;
        }

        return switch (keyword) {
            case StvnVocabulary.TYPE_TUPLE -> """
                <b>Product Type Constructor:</b> :Tuple( :T1 :T2 ... )<br/>
                <hr/>
                Defines a fixed-length, heterogeneous positional product type.<br/>
                <b>Payload Syntax:</b> Parenthesized values: <code>( val1 val2 ... )</code>.
                """;
            case StvnVocabulary.TYPE_MAP -> """
                <b>Collection Constructor:</b> :Map( :KeyType :ValType )<br/>
                <hr/>
                Defines a key-value mapping with strictly unique keys.<br/>
                <b>Payload Syntax:</b> <code>{ [ key1 val1 ] [ key2 val2 ] }</code>.
                """;
            case StvnVocabulary.TYPE_SEQ -> """
                <b>Collection Constructor:</b> :Seq( :ElemType )<br/>
                <hr/>
                Defines an ordered homogeneous sequence of elements.<br/>
                <b>Payload Syntax:</b> <code>[ elem1 elem2 ... ]</code>.
                """;
            case StvnVocabulary.TYPE_SET -> """
                <b>Collection Constructor:</b> :Set( :ElemType )<br/>
                <hr/>
                Defines an unordered homogeneous collection of unique elements.<br/>
                <b>Payload Syntax:</b> <code>[ elem1 elem2 ... ]</code>.
                """;
            case StvnVocabulary.TYPE_OPTION -> """
                <b>Sum Type Constructor:</b> :Option( :Type )<br/>
                <hr/>
                Defines an optional algebraic value representing presence or absence.<br/>
                <b>Variants:</b> <code>#Some value</code> (or <code>#S value</code> / inferred) vs. <code>#None</code> (or <code>#N</code>).
                """;
            case StvnVocabulary.TYPE_EITHER -> """
                <b>Sum Type Constructor:</b> :Either( :LeftType :RightType )<br/>
                <hr/>
                Defines a disjoint union of exactly two distinct types.<br/>
                <b>Variants:</b> <code>#Left val</code> (or <code>#L val</code>) vs. <code>#Right val</code> (or <code>#R val</code>).
                """;
            case StvnVocabulary.TYPE_UNION -> """
                <b>Algebraic Union Constructor:</b> :Union( :T1 :T2 ... :Tn )<br/>
                <hr/>
                Defines an n-ary tagged algebraic union.<br/>
                <b>Variants:</b> Explicit tag prefix <code>#1 val</code>, <code>#2 val</code>, or compiler-inferred disambiguation.
                """;
            case StvnVocabulary.TYPE_ENUM -> """
                <b>Enumeration Constructor:</b> :Enum [ #val1 #val2 ... ]<br/>
                <hr/>
                Defines a closed set of symbolic value keywords.<br/>
                <b>Payload Syntax:</b> <code>#val1</code>.
                """;
            default -> null;
        };
    }

    private static @Nullable String getBuiltInSpecificationQuickNavigateInfo(String keyword) {
        if (keyword.startsWith(":org/stvnadore/prelude/")) {
            var shortName = ":" + keyword.substring(":org/stvnadore/prelude/".length());
            var preludeQuick = getBuiltInPreludeQuickNavigateInfo(shortName);
            if (preludeQuick != null) {
                return "Standard Library Prelude: " + keyword;
            }
            return null;
        }
        var temporalQuick = getBuiltInTemporalQuickNavigateInfo(keyword);
        if (temporalQuick != null) {
            return temporalQuick;
        }
        var preludeQuick = getBuiltInPreludeQuickNavigateInfo(keyword);
        if (preludeQuick != null) {
            return preludeQuick;
        }

        if (keyword.equals(":Boolean")) {
            return "Primitive Type: :Boolean";
        }
        if (keyword.equals(":Int")) {
            return "Primitive Type: :Int (Integer)";
        }
        if (keyword.equals(":Float")) {
            return "Primitive Type: :Float (Floating-Point)";
        }
        if (keyword.equals(":String")) {
            return "Primitive Type: :String (UTF-8 String)";
        }

        return switch (keyword) {
            case StvnVocabulary.TYPE_TUPLE -> "Product Type Constructor: :Tuple( ... )";
            case StvnVocabulary.TYPE_MAP -> "Collection Constructor: :Map( :Key :Val )";
            case StvnVocabulary.TYPE_SEQ -> "Collection Constructor: :Seq( :Elem )";
            case StvnVocabulary.TYPE_SET -> "Collection Constructor: :Set( :Elem )";
            case StvnVocabulary.TYPE_OPTION -> "Sum Type Constructor: :Option( :Type )";
            case StvnVocabulary.TYPE_EITHER -> "Sum Type Constructor: :Either( :Left :Right )";
            case StvnVocabulary.TYPE_UNION -> "Algebraic Union Constructor: :Union( ... )";
            case StvnVocabulary.TYPE_ENUM -> "Enumeration Constructor: :Enum [ ... ]";
            default -> null;
        };
    }

    private static @Nullable String getBuiltInPreludeDoc(String keyword) {
        return switch (keyword) {
            case ":IPv4" -> """
                <b>Standard Library Prelude:</b> :IPv4<br/>
                <b>Underlying Type:</b> <code>:String</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$"</code><br/>
                <hr/>
                Represents a standard <b>dotted-quad IPv4 internet protocol address</b> spanning <code>0.0.0.0</code> through <code>255.255.255.255</code>.<br/>
                <b>Lexical Form:</b> Four decimal octets (0–255) separated by periods (<code>.</code>).<br/>
                <b>Examples:</b> <code>"127.0.0.1"</code>, <code>"192.168.1.1"</code>, <code>"10.0.0.1"</code>, <code>"255.255.255.255"</code>.
                """;
            case ":UuidLowerCase" -> """
                <b>Standard Library Prelude:</b> :UuidLowerCase<br/>
                <b>Underlying Type:</b> <code>{ #minSize 36 #maxSize 36 #regex #UUID_LOWER_CASE_REGEX } :String</code><br/>
                <hr/>
                Represents an <b>RFC 4122 Universally Unique Identifier</b> in strict lowercase hexadecimal format.<br/>
                <b>Lexical Form:</b> Fixed 36-character string in 8-4-4-4-12 hyphenated lowercase notation.<br/>
                <b>Examples:</b> <code>"123e4567-e89b-12d3-a456-426614174000"</code>, <code>"f47ac10b-58cc-4372-a567-0e02b2c3d479"</code>.
                """;
            case ":UuidMixedCase" -> """
                <b>Standard Library Prelude:</b> :UuidMixedCase<br/>
                <b>Underlying Type:</b> <code>{ #minSize 36 #maxSize 36 #regex #UUID_MIXED_CASE_REGEX } :String</code><br/>
                <hr/>
                Represents an <b>RFC 4122 Universally Unique Identifier</b> in case-insensitive mixed hexadecimal format.<br/>
                <b>Lexical Form:</b> Fixed 36-character string in 8-4-4-4-12 hyphenated mixed-case notation.<br/>
                <b>Examples:</b> <code>"123E4567-E89B-12D3-A456-426614174000"</code>.
                """;
            case ":Uuid" -> """
                <b>Standard Library Prelude:</b> :Uuid<br/>
                <b>Underlying Type:</b> <code>{ #size 36 } :String</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"</code><br/>
                <hr/>
                Represents an <b>RFC 4122 Universally Unique Identifier</b> (ITU-T X.667).<br/>
                <b>Lexical Form:</b> Fixed 36-character string in 8-4-4-4-12 hyphenated hexadecimal notation.<br/>
                <b>Examples:</b> <code>"123e4567-e89b-12d3-a456-426614174000"</code>, <code>"f47ac10b-58cc-4372-a567-0e02b2c3d479"</code>.
                """;
            case ":Ulid" -> """
                <b>Standard Library Prelude:</b> :Ulid<br/>
                <b>Underlying Type:</b> <code>{ #size 26 } :String</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$"</code><br/>
                <hr/>
                Represents a <b>Universally Unique Lexicographically Sortable Identifier</b> encoded in Crockford's Base32.<br/>
                <b>Lexical Form:</b> Fixed 26-character alphanumeric string (excludes <code>I</code>, <code>L</code>, <code>O</code>, <code>U</code>; first character in <code>0-7</code>).<br/>
                <b>Examples:</b> <code>"01ARZ3NDEKTSV4RRFFQ69G5FAV"</code>, <code>"01EDZZZG8X69W1V3Q2P8P5MNQ3"</code>.
                """;
            case ":Sha256LowerCase" -> """
                <b>Standard Library Prelude:</b> :Sha256LowerCase<br/>
                <b>Underlying Type:</b> <code>{ #minSize 64 #maxSize 64 #regex #SHA256_LOWER_CASE_REGEX } :String</code><br/>
                <hr/>
                Represents a <b>SHA-256 cryptographic hash</b> in strict lowercase hexadecimal notation.<br/>
                <b>Lexical Form:</b> Fixed 64-character lowercase hexadecimal string.<br/>
                <b>Examples:</b> <code>"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"</code>.
                """;
            case ":Sha256MixedCase" -> """
                <b>Standard Library Prelude:</b> :Sha256MixedCase<br/>
                <b>Underlying Type:</b> <code>{ #minSize 64 #maxSize 64 #regex #SHA256_MIXED_CASE_REGEX } :String</code><br/>
                <hr/>
                Represents a <b>SHA-256 cryptographic hash</b> in case-insensitive mixed hexadecimal notation.<br/>
                <b>Lexical Form:</b> Fixed 64-character hexadecimal string.<br/>
                <b>Examples:</b> <code>"BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"</code>.
                """;
            case ":Sha256" -> """
                <b>Standard Library Prelude:</b> :Sha256<br/>
                <b>Underlying Type:</b> <code>{ #size 64 } :String</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^[0-9a-fA-F]{64}$"</code><br/>
                <hr/>
                Represents a <b>SHA-256 cryptographic hash (64 hex characters)</b>.<br/>
                <b>Lexical Form:</b> Fixed 64-character hexadecimal string.<br/>
                <b>Examples:</b> <code>"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"</code>, <code>"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"</code>.
                """;
            case ":SemVer" -> """
                <b>Standard Library Prelude:</b> :SemVer<br/>
                <b>Underlying Type:</b> <code>:String</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"</code><br/>
                <hr/>
                Represents a release version adhering to the <b>Semantic Versioning 2.0.0 specification</b>.<br/>
                <b>Lexical Form:</b> <code>MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]</code> without leading zeros in numeric components.<br/>
                <b>Examples:</b> <code>"1.0.0"</code>, <code>"2.1.4-beta.1"</code>, <code>"0.3.0-alpha.1+20260819"</code>.
                """;
            case ":EmailLowerCase" -> """
                <b>Standard Library Prelude:</b> :EmailLowerCase<br/>
                <b>Underlying Type:</b> <code>{ #minSize 3 #maxSize 254 #regex #EMAIL_LOWER_CASE_REGEX } :String</code><br/>
                <hr/>
                Represents an <b>RFC 5322 electronic mail address</b> in strict lowercase format.<br/>
                <b>Lexical Form:</b> Standard <code>local-part@domain.tld</code> syntax restricted to lowercase characters.<br/>
                <b>Examples:</b> <code>"developer@stvnadore.org"</code>, <code>"jane.doe@example.com"</code>.
                """;
            case ":EmailMixedCase" -> """
                <b>Standard Library Prelude:</b> :EmailMixedCase<br/>
                <b>Underlying Type:</b> <code>{ #minSize 3 #maxSize 254 #regex #EMAIL_MIXED_CASE_REGEX } :String</code><br/>
                <hr/>
                Represents an <b>RFC 5322 electronic mail address</b> permitting mixed-case characters.<br/>
                <b>Lexical Form:</b> Standard <code>local-part@domain.tld</code> syntax.<br/>
                <b>Examples:</b> <code>"Developer@stvnadore.org"</code>, <code>"Jane.Doe@Example.COM"</code>.
                """;
            case ":Email" -> """
                <b>Standard Library Prelude:</b> :Email<br/>
                <b>Underlying Type:</b> <code>:String</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"</code><br/>
                <hr/>
                Represents an <b>RFC 5322 electronic mail address</b>.<br/>
                <b>Lexical Form:</b> Standard <code>local-part@domain.tld</code> syntax with valid TLD.<br/>
                <b>Examples:</b> <code>"developer@stvnadore.org"</code>, <code>"jane.doe@subdomain.example.com"</code>.
                """;
            case ":Port" -> """
                <b>Standard Library Prelude:</b> :Port<br/>
                <b>Underlying Type:</b> <code>{ #unsigned #size 16 } :Int</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl 1 #maxExcl 65536</code><br/>
                <hr/>
                Represents a valid <b>TCP/UDP network socket port number</b>.<br/>
                <b>Allowed Range:</b> <code>1</code> through <code>65535</code> (inclusive). Prohibits port <code>0</code>.<br/>
                <b>Examples:</b> <code>80</code>, <code>443</code>, <code>8080</code>, <code>5432</code>, <code>65535</code>.
                """;
            case ":Percentage" -> """
                <b>Standard Library Prelude:</b> :Percentage<br/>
                <b>Underlying Type:</b> <code>{ #size 64 } :Float</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl 0.0 #maxIncl 100.0</code><br/>
                <hr/>
                Represents a <b>percentage ratio</b> bounded between 0% and 100%.<br/>
                <b>Allowed Range:</b> <code>0.0</code> through <code>100.0</code> (inclusive).<br/>
                <b>Examples:</b> <code>0.0</code>, <code>50.0</code>, <code>99.95</code>, <code>100.0</code>.
                """;
            case ":Probability" -> """
                <b>Standard Library Prelude:</b> :Probability<br/>
                <b>Underlying Type:</b> <code>{ #size 64 } :Float</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl 0.0 #maxIncl 1.0</code><br/>
                <hr/>
                Represents a <b>normalized statistical probability measure</b>.<br/>
                <b>Allowed Range:</b> <code>0.0</code> through <code>1.0</code> (inclusive).<br/>
                <b>Examples:</b> <code>0.0</code>, <code>0.5</code>, <code>0.7071</code>, <code>1.0</code>.
                """;
            case ":Currency" -> """
                <b>Standard Library Prelude:</b> :Currency<br/>
                <b>Underlying Type:</b> <code>{ #exact } :Float</code><br/>
                <hr/>
                Represents an <b>arbitrary-precision exact decimal currency amount</b>.<br/>
                <b>Constraint:</b> Eliminates binary IEEE 754 floating-point rounding inaccuracies.<br/>
                <b>Examples:</b> <code>19.99</code>, <code>1500.50</code>, <code>0.01</code>, <code>-42.50</code>.
                """;
            case ":Latitude" -> """
                <b>Standard Library Prelude:</b> :Latitude<br/>
                <b>Underlying Type:</b> <code>{ #size 64 } :Float</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl -90.0 #maxIncl 90.0</code><br/>
                <hr/>
                Represents a <b>geographic latitude coordinate</b> (North-South angle).<br/>
                <b>Allowed Range:</b> <code>-90.0</code> (South Pole) through <code>+90.0</code> (North Pole).<br/>
                <b>Examples:</b> <code>0.0</code> (Equator), <code>37.7749</code> (San Francisco), <code>-33.8688</code> (Sydney).
                """;
            case ":Longitude" -> """
                <b>Standard Library Prelude:</b> :Longitude<br/>
                <b>Underlying Type:</b> <code>{ #size 64 } :Float</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl -180.0 #maxIncl 180.0</code><br/>
                <hr/>
                Represents a <b>geographic longitude coordinate</b> (East-West angle from Prime Meridian).<br/>
                <b>Allowed Range:</b> <code>-180.0</code> through <code>+180.0</code>.<br/>
                <b>Examples:</b> <code>0.0</code> (Prime Meridian), <code>-122.4194</code> (San Francisco), <code>151.2093</code> (Sydney).
                """;
            default -> null;
        };
    }

    private static @Nullable String getBuiltInPreludeQuickNavigateInfo(String keyword) {
        return switch (keyword) {
            case ":IPv4" -> "Standard Prelude Type: :IPv4 (Dotted-Quad IPv4 Address)";
            case ":UuidLowerCase" -> "Standard Prelude Type: :UuidLowerCase (RFC 4122 Lowercase UUID)";
            case ":UuidMixedCase" -> "Standard Prelude Type: :UuidMixedCase (RFC 4122 Mixed-Case UUID)";
            case ":Uuid" -> "Standard Prelude Type: :Uuid (RFC 4122 UUID)";
            case ":Ulid" -> "Standard Prelude Type: :Ulid (Crockford Base32 ULID)";
            case ":Sha256LowerCase" -> "Standard Prelude Type: :Sha256LowerCase (SHA-256 lowercase hash)";
            case ":Sha256MixedCase" -> "Standard Prelude Type: :Sha256MixedCase (SHA-256 mixed-case hash)";
            case ":Sha256" -> "Standard Prelude Type: :Sha256 (SHA-256 cryptographic hash (64 hex characters))";
            case ":SemVer" -> "Standard Prelude Type: :SemVer (Semantic Version 2.0.0)";
            case ":EmailLowerCase" -> "Standard Prelude Type: :EmailLowerCase (RFC 5322 Lowercase Email)";
            case ":EmailMixedCase" -> "Standard Prelude Type: :EmailMixedCase (RFC 5322 Mixed-Case Email)";
            case ":Email" -> "Standard Prelude Type: :Email (RFC 5322 Email Address)";
            case ":Port" -> "Standard Prelude Type: :Port (TCP/UDP Port 1-65535)";
            case ":Percentage" -> "Standard Prelude Type: :Percentage (Float 0.0 - 100.0)";
            case ":Probability" -> "Standard Prelude Type: :Probability (Float 0.0 - 1.0)";
            case ":Currency" -> "Standard Prelude Type: :Currency (Arbitrary-Precision Decimal)";
            case ":Latitude" -> "Standard Prelude Type: :Latitude (Float -90.0 to +90.0)";
            case ":Longitude" -> "Standard Prelude Type: :Longitude (Float -180.0 to +180.0)";
            default -> null;
        };
    }

    private static @Nullable String getBuiltInTemporalDoc(String keyword) {
        return switch (keyword) {
            case ":TimeEpoch" -> """
                <b>Built-in Temporal:</b> :TimeEpoch<br/>
                <hr/>
                Represents a Unix Epoch timestamp.<br/>
                <b>Lexical Form:</b> Integer timestamp value (e.g. <code>1755532800</code>).<br/>
                <b>Mandatory Scale Facet:</b> Exactly one bare scale flag: <code>#s</code>, <code>#ms</code>, <code>#us</code>, or <code>#ns</code>.<br/>
                <b>Bounds:</b> Half-open intervals <code>[#minIncl, #maxExcl)</code>.
                """;
            case ":DateTime" -> """
                <b>Built-in Temporal:</b> :DateTime<br/>
                <hr/>
                Represents a calendar date and time instant or schedule.<br/>
                <b>Lexical Form:</b> ISO-8601 formatted string (e.g. <code>"2026-08-18T18:00:00-05:00"</code>).<br/>
                <b>Mandatory Mode Facet:</b> Exactly one mode flag: <code>#offset</code> (physical instant), <code>#zoned</code> (civil wall-clock schedule with IANA zone), or <code>#audited</code> (both offset and IANA zone).<br/>
                <b>Bounds:</b> Half-open intervals <code>[#minIncl, #maxExcl)</code>.
                """;
            default -> null;
        };
    }

    private static @Nullable String getBuiltInTemporalQuickNavigateInfo(String keyword) {
        return switch (keyword) {
            case ":TimeEpoch" -> "Primitive Type: :TimeEpoch (Unix Epoch Timestamp)";
            case ":DateTime" -> "Primitive Type: :DateTime (ISO-8601 Date and Time)";
            default -> null;
        };
    }

    private @Nullable PsiElement getDocumentationElement(PsiElement element, @Nullable PsiElement originalElement) {
        if (element instanceof MetaNameFacet || element instanceof MetaDomainFacet
            || element instanceof MetaKindFacet || element instanceof MetaKindValue || element instanceof MetadataEntry) {
            return element;
        }
        if (originalElement != null && isContainerDocumentationSuppressionTarget(originalElement)) {
            return null;
        }
        if (isContainerDocumentationSuppressionTarget(element)) {
            return null;
        }

        if (originalElement != null) {
            var metaKindVal = PsiTreeUtil.getParentOfType(originalElement, MetaKindValue.class, false);
            if (metaKindVal != null) return metaKindVal;
            var metaKind = PsiTreeUtil.getParentOfType(originalElement, MetaKindFacet.class, false);
            if (metaKind != null) return metaKind;
            var metaName = PsiTreeUtil.getParentOfType(originalElement, MetaNameFacet.class, false);
            if (metaName != null) return metaName;
            var metaDomain = PsiTreeUtil.getParentOfType(originalElement, MetaDomainFacet.class, false);
            if (metaDomain != null) return metaDomain;
        }

        var metaKindValElem = PsiTreeUtil.getParentOfType(element, MetaKindValue.class, false);
        if (metaKindValElem != null) return metaKindValElem;
        var metaKindElem = PsiTreeUtil.getParentOfType(element, MetaKindFacet.class, false);
        if (metaKindElem != null) return metaKindElem;
        var metaNameElem = PsiTreeUtil.getParentOfType(element, MetaNameFacet.class, false);
        if (metaNameElem != null) return metaNameElem;
        var metaDomainElem = PsiTreeUtil.getParentOfType(element, MetaDomainFacet.class, false);
        if (metaDomainElem != null) return metaDomainElem;

        if (element instanceof LeafPsiElement) {
            var valKwParent = PsiTreeUtil.getParentOfType(element, ValueKeyword.class, false);
            if (valKwParent != null) {
                return getDocumentationElement(valKwParent, originalElement);
            }
            var typeKwParent = PsiTreeUtil.getParentOfType(element, TypeKeyword.class, false);
            if (typeKwParent != null) {
                return getDocumentationElement(typeKwParent, originalElement);
            }
        }

        if (element instanceof TypeDefinition || element instanceof ConstantDefinition) {
            return element;
        }

        if (element instanceof TypeKeyword typeKw) {
            if (org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(typeKw)) {
                var typeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(typeKw);
                if (typeDef != null) {
                    return typeDef;
                }
            }

            var parent = typeKw.getParent();
            if (parent instanceof IncludeMapAlias alias) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2 && list.get(1) == typeKw) {
                    return alias;
                }
                var resolved = new StvnTypeReference(typeKw).resolve();
                var targetTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
                if (targetTypeDef != null) {
                    return targetTypeDef;
                }
                if (resolved instanceof IncludeMapAlias targetAlias) {
                    return targetAlias;
                }
                if (resolved != null && resolved.getParent() instanceof IncludeMapAlias targetAlias) {
                    return targetAlias;
                }
            }
            if (parent instanceof org.stvnadore.psi.UseMapAlias alias) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2 && (list.get(1) == typeKw || list.get(0) == typeKw)) {
                    return alias;
                }
                var resolved = new StvnTypeReference(typeKw).resolve();
                var targetTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolved);
                if (targetTypeDef != null) {
                    return targetTypeDef;
                }
                if (resolved instanceof org.stvnadore.psi.UseMapAlias targetAlias) {
                    return targetAlias;
                }
                if (resolved != null && resolved.getParent() instanceof org.stvnadore.psi.UseMapAlias targetAlias) {
                    return targetAlias;
                }
            }

            // Usage site inside composite schema (e.g. :Map, :Tuple, :Union, :Seq, :Option, :Either):
            var ref = typeKw.getReference();
            var resolved = ref != null ? ref.resolve() : new StvnTypeReference(typeKw).resolve();
            if (resolved instanceof TypeDefinition targetDef) {
                return targetDef;
            }
            if (resolved instanceof IncludeMapAlias targetAlias) {
                return targetAlias;
            }
            if (resolved instanceof org.stvnadore.psi.UseMapAlias targetAlias) {
                return targetAlias;
            }
            if (resolved instanceof TypeKeyword resolvedKw) {
                var targetDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolvedKw);
                if (targetDef != null) {
                    return targetDef;
                }
                var resParent = resolvedKw.getParent();
                if (resParent instanceof IncludeMapAlias targetAlias) {
                    return targetAlias;
                }
                if (resParent instanceof org.stvnadore.psi.UseMapAlias targetAlias) {
                    return targetAlias;
                }
                return resolvedKw;
            }

            return typeKw;
        }

        if (element instanceof ValueKeyword valKw) {
            var parent = valKw.getParent();
            if (parent instanceof org.stvnadore.psi.UseMapAlias alias) {
                var list = alias.getValueKeywordList();
                if (list.size() >= 2 && (list.get(1) == valKw || list.get(0) == valKw)) {
                    return alias;
                }
            }
            if (parent instanceof ConstantDefinition constDef && constDef.getValueKeyword() == valKw) {
                return constDef;
            }
            if (parent instanceof org.stvnadore.psi.EnumDef) {
                return valKw;
            }
            var ref = valKw.getReference();
            var resolved = ref != null ? ref.resolve() : new StvnConstantReference(valKw).resolve();
            if (resolved instanceof ConstantDefinition targetConstDef) {
                return targetConstDef;
            }
            if (resolved != null && resolved.getParent() instanceof ConstantDefinition targetConstDef) {
                return targetConstDef;
            }
            if (resolved != null && resolved.getParent() instanceof org.stvnadore.psi.EnumDef) {
                return resolved;
            }
            return valKw;
        }

        if (element instanceof org.stvnadore.psi.SomeLiteral || element instanceof org.stvnadore.psi.NoneLiteral ||
            element instanceof org.stvnadore.psi.LeftLiteral || element instanceof org.stvnadore.psi.RightLiteral ||
            element instanceof org.stvnadore.psi.TrueLiteral || element instanceof org.stvnadore.psi.FalseLiteral ||
            element instanceof org.stvnadore.psi.SomeShortLiteral || element instanceof org.stvnadore.psi.NoneShortLiteral ||
            element instanceof org.stvnadore.psi.LeftShortLiteral || element instanceof org.stvnadore.psi.RightShortLiteral ||
            element instanceof org.stvnadore.psi.TrueShortLiteral || element instanceof org.stvnadore.psi.FalseShortLiteral ||
            element instanceof org.stvnadore.psi.BooleanValue) {
            var ref = element.getReference();
            var resolved = ref != null ? ref.resolve() : null;
            if (resolved != null && resolved.getParent() instanceof ConstantDefinition targetConstDef) {
                return targetConstDef;
            }
            if (resolved != null && resolved.getParent() instanceof org.stvnadore.psi.EnumDef) {
                return resolved;
            }
        }

        if (element instanceof ProductType || element instanceof CollectionType || element instanceof SumType || element instanceof EnumDef) {
            return element;
        }

        if (element instanceof MetaNameFacet || element instanceof MetaDomainFacet || element instanceof MetaKindFacet || element instanceof MetaKindValue || element instanceof MetadataEntry) {
            return element;
        }

        if (originalElement != null) {
            var metaKindVal = PsiTreeUtil.getParentOfType(originalElement, MetaKindValue.class);
            if (metaKindVal != null) return metaKindVal;
            var metaKind = PsiTreeUtil.getParentOfType(originalElement, MetaKindFacet.class);
            if (metaKind != null) return metaKind;
            var metaName = PsiTreeUtil.getParentOfType(originalElement, MetaNameFacet.class);
            if (metaName != null) return metaName;
            var metaDomain = PsiTreeUtil.getParentOfType(originalElement, MetaDomainFacet.class);
            if (metaDomain != null) return metaDomain;
            var metaEntry = PsiTreeUtil.getParentOfType(originalElement, MetadataEntry.class, false);
            if (metaEntry != null) return metaEntry;

            var typeKw = PsiTreeUtil.getParentOfType(originalElement, TypeKeyword.class);
            if (typeKw != null) {
                return getDocumentationElement(typeKw, null);
            }
            var valKw = PsiTreeUtil.getParentOfType(originalElement, ValueKeyword.class);
            if (valKw != null) {
                return getDocumentationElement(valKw, null);
            }
            var valueAncestor = PsiTreeUtil.getParentOfType(originalElement, Value.class);
            if (valueAncestor != null && PsiTreeUtil.getParentOfType(valueAncestor, BodyEntry.class) != null) {
                if (isContainerLiteral(valueAncestor)) {
                    if (!isExplicitContainerDelimiter(valueAncestor, originalElement)) {
                        return null;
                    }
                }
                return valueAncestor;
            }
            var atomicType = PsiTreeUtil.getParentOfType(originalElement, AtomicType.class);
            if (atomicType != null) {
                return atomicType;
            }
            var origParent = originalElement.getParent();
            if (origParent instanceof ProductType || origParent instanceof CollectionType || origParent instanceof SumType || origParent instanceof EnumDef) {
                return origParent;
            }
        }

        if (element instanceof AtomicType) {
            return element;
        }

        if (element instanceof Value && PsiTreeUtil.getParentOfType(element, BodyEntry.class) != null) {
            if (isContainerLiteral((Value) element)) {
                if (originalElement != null && !isExplicitContainerDelimiter((Value) element, originalElement)) {
                    return null;
                }
            }
            return element;
        }

        return null;
    }

    @Override
    public @Nullable PsiElement getCustomDocumentationElement(
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset) {
        if (contextElement == null) {
            return null;
        }

        // 1. FIRST PRIORITY: Evaluate metadata facets before any container suppression filters
        var metaKindVal = PsiTreeUtil.getParentOfType(contextElement, MetaKindValue.class, false);
        if (metaKindVal != null) {
            return metaKindVal;
        }
        var metaKind = PsiTreeUtil.getParentOfType(contextElement, MetaKindFacet.class, false);
        if (metaKind != null) {
            return metaKind;
        }
        var metaName = PsiTreeUtil.getParentOfType(contextElement, MetaNameFacet.class, false);
        if (metaName != null) {
            return metaName;
        }
        var metaDomain = PsiTreeUtil.getParentOfType(contextElement, MetaDomainFacet.class, false);
        if (metaDomain != null) {
            return metaDomain;
        }

        // 2. Container suppression check for non-metadata elements
        if (isContainerDocumentationSuppressionTarget(contextElement)) {
            return null;
        }
        var typeKwAncestor = PsiTreeUtil.getParentOfType(contextElement, TypeKeyword.class);
        if (typeKwAncestor != null) {
            return typeKwAncestor;
        }
        var valKwAncestor = PsiTreeUtil.getParentOfType(contextElement, ValueKeyword.class);
        if (valKwAncestor != null) {
            return valKwAncestor;
        }
        var metaEntry = PsiTreeUtil.getParentOfType(contextElement, MetadataEntry.class, false);
        if (metaEntry != null) {
            return metaEntry;
        }
        var valueAncestor = PsiTreeUtil.getParentOfType(contextElement, Value.class);
        if (valueAncestor != null && PsiTreeUtil.getParentOfType(valueAncestor, BodyEntry.class) != null) {
            if (isContainerLiteral(valueAncestor)) {
                if (!isExplicitContainerDelimiter(valueAncestor, contextElement)) {
                    return null;
                }
            }
            return valueAncestor;
        }
        var atomicAncestor = PsiTreeUtil.getParentOfType(contextElement, AtomicType.class);
        if (atomicAncestor != null) {
            return atomicAncestor;
        }
        var parent = contextElement.getParent();
        if (parent instanceof ProductType || parent instanceof CollectionType || parent instanceof SumType || parent instanceof EnumDef) {
            return parent;
        }
        return null;
    }

    /**
     * Resolves the terminal schema definition by following nominal type alias chains.
     *
     * @param element the PSI element (typically a TypeDefinition or TypeKeyword) to resolve
     * @param visited set of already visited PSI elements to prevent circular resolution loops
     * @return the terminal resolved {@link SchemaType}, or {@code null} if unresolvable
     */
    public static @Nullable SchemaType resolveTerminalSchemaType(PsiElement element, Set<PsiElement> visited) {
        if (!visited.add(element)) {
            return null;
        }

        if (element instanceof TypeDefinition typeDef) {
            var schemaType = typeDef.getSchemaType();
            if (schemaType == null) {
                return null;
            }
            if (schemaType.getSchemaConstructor() != null) {
                return schemaType;
            }
            var typeKw = schemaType.getTypeKeyword();
            if (typeKw != null) {
                return resolveTerminalFromKeyword(typeKw, visited);
            }
            return schemaType;
        }

        if (element instanceof IncludeMapAlias alias) {
            var list = alias.getTypeKeywordList();
            var remoteKw = list.size() >= 1 ? list.get(0) : null;
            if (remoteKw != null) {
                return resolveTerminalFromKeyword(remoteKw, visited);
            }
        }

        if (element instanceof SchemaType schemaType) {
            if (schemaType.getSchemaConstructor() != null) {
                return schemaType;
            }
            var typeKw = schemaType.getTypeKeyword();
            if (typeKw != null) {
                return resolveTerminalFromKeyword(typeKw, visited);
            }
            return schemaType;
        }

        if (element instanceof TypeKeyword typeKw) {
            return resolveTerminalFromKeyword(typeKw, visited);
        }

        return null;
    }

    private static @Nullable SchemaType resolveTerminalFromKeyword(TypeKeyword keyword, Set<PsiElement> visited) {
        var resolved = new StvnTypeReference(keyword).resolve();
        if (resolved instanceof TypeKeyword targetKw) {
            var targetDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(targetKw);
            if (targetDef != null) {
                return resolveTerminalSchemaType(targetDef, visited);
            }
            var targetParent = targetKw.getParent();
            if (targetParent instanceof IncludeMapAlias targetAlias) {
                return resolveTerminalSchemaType(targetAlias, visited);
            }
            if (targetParent instanceof org.stvnadore.psi.UseMapAlias targetAlias) {
                return resolveTerminalSchemaType(targetAlias, visited);
            }
        }
        if (resolved instanceof TypeDefinition targetDef) {
            return resolveTerminalSchemaType(targetDef, visited);
        }
        if (resolved instanceof IncludeMapAlias targetAlias) {
            return resolveTerminalSchemaType(targetAlias, visited);
        }
        if (resolved instanceof org.stvnadore.psi.UseMapAlias targetAlias) {
            return resolveTerminalSchemaType(targetAlias, visited);
        }
        return null;
    }

    private static @Nullable String extractStructuralMetricHtml(PsiElement element) {
        var subset = StvnTypeResolver.resolveEnumSubsetFromElement(element);
        if (subset != null) {
            return "<b>Variant Count:</b> " + subset.allowedVariants().size();
        }

        var terminal = resolveTerminalSchemaType(element, new HashSet<>());
        if (terminal == null || terminal.getSchemaConstructor() == null) {
            return null;
        }
        var ctor = terminal.getSchemaConstructor();

        // 1. :Tuple Arity
        if (ctor.getProductType() != null) {
            int arity = ctor.getProductType().getSchemaTypeList().size();
            return "<b>Arity:</b> " + arity;
        }

        // 2. :Enum Variant Count or :Union Branch Count
        if (ctor.getSumType() != null) {
            var sumType = ctor.getSumType();
            if (sumType.getEnumDef() != null) {
                int variantCount = sumType.getEnumDef().getValueKeywordList().size();
                return "<b>Variant Count:</b> " + variantCount;
            }
            if (isUnionSumType(sumType)) {
                var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                return "<b>Branch Count:</b> " + branches.size();
            }
        }

        return null;
    }

    private static @Nullable String formatDerivationLineage(@Nullable TypeDefinition typeDef, ResolvedType.EnumSubset subset) {
        if (typeDef != null) {
            var metaMap = typeDef.getMetadataMap();
            if (metaMap != null) {
                for (var entry : metaMap.getMetadataEntryList()) {
                    var filter = entry.getMetadataFilter();
                    if (filter != null) {
                        var isIncl = filter.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null;
                        var filterKw = isIncl ? "#filterIncl" : "#filterExcl";
                        var variants = filter.getVariantList() != null
                            ? filter.getVariantList().getValueKeywordList().stream().map(ValueKeyword::getText).toList()
                            : java.util.List.<String>of();
                        var formattedList = variants.isEmpty() ? "[]" : "[ " + String.join(" ", variants) + " ]";
                        return "Parent: " + subset.parentType() + " via " + filterKw + " " + formattedList;
                    }
                }
            }
        }
        if (subset.parentType() != null && !subset.parentType().isEmpty()) {
            return "Parent: " + subset.parentType();
        }
        return null;
    }

    private static boolean isUnionSumType(SumType sumType) {
        if (sumType.getEnumDef() != null) {
            return false;
        }
        var node = sumType.getNode();
        if (node != null && node.findChildByType(StvnTypes.KW_UNION) != null) {
            return true;
        }
        var firstChild = sumType.getFirstChild();
        return firstChild != null && firstChild.getText().equals(StvnVocabulary.TYPE_UNION);
    }

    private static boolean isOptionSumType(SumType sumType) {
        if (sumType.getEnumDef() != null) {
            return false;
        }
        var node = sumType.getNode();
        if (node != null && node.findChildByType(StvnTypes.KW_OPTION) != null) {
            return true;
        }
        var firstChild = sumType.getFirstChild();
        return firstChild != null && firstChild.getText().equals(StvnVocabulary.TYPE_OPTION);
    }

    private static boolean isEitherSumType(SumType sumType) {
        if (sumType.getEnumDef() != null) {
            return false;
        }
        var node = sumType.getNode();
        if (node != null && node.findChildByType(StvnTypes.KW_EITHER) != null) {
            return true;
        }
        var firstChild = sumType.getFirstChild();
        return firstChild != null && firstChild.getText().equals(StvnVocabulary.TYPE_EITHER);
    }

    private static boolean isContainerDocumentationSuppressionTarget(@Nullable PsiElement element) {
        if (element != null) {
            var rawSigil = element.getText().trim();
            if (rawSigil.equals(StvnVocabulary.SIGIL_VALUE) || rawSigil.equals(StvnVocabulary.SIGIL_TYPIC) || rawSigil.equals("/")) {
                return true;
            }
        }
        if (element == null) {
            return false;
        }
        // NEVER suppress documentation for metadata facets or kind values
        if (PsiTreeUtil.getParentOfType(element, MetaNameFacet.class, false) != null
            || PsiTreeUtil.getParentOfType(element, MetaDomainFacet.class, false) != null
            || PsiTreeUtil.getParentOfType(element, MetaKindFacet.class, false) != null
            || PsiTreeUtil.getParentOfType(element, MetaKindValue.class, false) != null
            || PsiTreeUtil.getParentOfType(element, MetadataEntry.class, false) != null) {
            return false;
        }
        if (element instanceof PsiErrorElement) {
            return true;
        }
        if (element instanceof com.intellij.psi.PsiWhiteSpace) {
            return true;
        }
        var node = element.getNode();
        if (node != null && node.getElementType() == TokenType.BAD_CHARACTER) {
            return true;
        }
        var text = element.getText().trim();
        return text.equals(StvnVocabulary.SIGIL_VALUE) || text.equals(StvnVocabulary.SIGIL_TYPIC) || text.equals("/");
    }

    private static boolean isContainerLiteral(Value value) {
        var coll = value.getCollectionValue();
        return coll != null && (coll.getTupleLiteral() != null || coll.getListLiteral() != null || coll.getMapLiteral() != null);
    }

    private static boolean isExplicitContainerDelimiter(Value container, PsiElement element) {
        var coll = container.getCollectionValue();
        if (coll == null) {
            return false;
        }
        PsiElement targetContainer = coll.getTupleLiteral();
        if (targetContainer == null) {
            targetContainer = coll.getListLiteral();
        }
        if (targetContainer == null) {
            targetContainer = coll.getMapLiteral();
        }
        if (targetContainer == null) {
            return false;
        }
        return element == targetContainer.getFirstChild() || element == targetContainer.getLastChild();
    }

    private static @Nullable String buildRedundantFacetHoverDoc(MetadataEntry entry) {
        var metaMap = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
        var typeDef = PsiTreeUtil.getParentOfType(entry, TypeDefinition.class);
        if (metaMap == null || typeDef == null) {
            return null;
        }

        var typeKeyword = typeDef.getTypeKeyword();
        String typeName = typeKeyword != null ? typeKeyword.getText().trim() : ":Type";
        var schemaType = typeDef.getSchemaType();
        String baseType = schemaType != null ? StvnSchemaFormatter.formatCleanSchema(schemaType) : "";

        String text = entry.getText().trim();
        String reason = null;

        boolean isUnsigned = false;
        int bitWidth = 32;
        boolean isExact = false;
        for (var e : metaMap.getMetadataEntryList()) {
            var et = e.getText().trim();
            if (et.startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
                isUnsigned = true;
            } else if (et.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                var parts = et.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        bitWidth = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
            } else if (et.startsWith(StvnVocabulary.FACET_KW_EXACT)) {
                isExact = true;
            }
        }

        // 1. Unsigned lower bound #minIncl 0 or signed natural min bound
        if (text.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                try {
                    var val = new java.math.BigInteger(valStr);
                    if (isUnsigned && val.compareTo(java.math.BigInteger.ZERO) == 0 && (baseType.endsWith(":Int") || baseType.equals(":Int"))) {
                        reason = "unsigned integer <code>{ #unsigned } :Int</code> is already bounded from below by 0.";
                    } else if (!isUnsigned && (baseType.endsWith(":Int") || baseType.equals(":Int"))) {
                        var naturalMin = java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
                        if (val.compareTo(naturalMin) == 0) {
                            reason = "<code>#size " + bitWidth + "</code> already bounds the minimum inclusive bound to " + valStr + ".";
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        // 2. Natural upper bound #maxExcl
        else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                try {
                    var val = new java.math.BigInteger(valStr);
                    var naturalMax = isUnsigned
                        ? java.math.BigInteger.ONE.shiftLeft(bitWidth)
                        : java.math.BigInteger.ONE.shiftLeft(bitWidth - 1);
                    if (val.compareTo(naturalMax) == 0 && (baseType.endsWith(":Int") || baseType.equals(":Int"))) {
                        reason = "<code>#size " + bitWidth + "</code> already bounds the maximum exclusive bound to " + valStr + ".";
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        // 3. Sizing tautologies
        else if (text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                if ("32".equals(valStr) && (baseType.endsWith(":Int") || baseType.equals(":Int"))) {
                    String desc = isUnsigned ? "unsigned integer <code>{ #unsigned } :Int</code>" : "signed integer <code>:Int</code>";
                    reason = desc + " intrinsically defaults to 32-bit storage.";
                } else if ("64".equals(valStr) && (baseType.endsWith(":Float") || baseType.equals(":Float")) && !isExact) {
                    reason = "continuous <code>:Float</code> intrinsically defaults to 64-bit IEEE-754 double precision.";
                }
            }
        }
        // 4. Exact float max size
        else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                if (String.valueOf(StvnVocabulary.DEFAULT_EXACT_FLOAT_MAX_SIZE).equals(valStr) && (baseType.endsWith(":Float") || baseType.equals(":Float"))) {
                    reason = "exact decimal float <code>{ #exact } :Float</code> intrinsically defaults to a ceiling of 16,777,216 significant decimal digits.";
                }
            }
        }
        // 5. Cardinality min size
        else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2 && "0".equals(parts[1].trim())) {
                String targetDesc = (baseType.endsWith(":String") || baseType.equals(":String"))
                    ? "string cardinality is inherently non-negative."
                    : "container cardinality is inherently non-negative.";
                reason = targetDesc;
            }
        }
        // 6. Primitive default traits
        else if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE) || text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2 && ("#TRUE".equals(parts[1].trim()) || "#T".equals(parts[1].trim()))) {
                boolean isEq = text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE);
                reason = "scalar primitive <code>" + baseType + "</code> is intrinsically " + (isEq ? "equatable" : "comparable") + ".";
            }
        }

        if (reason == null) {
            return null;
        }

        var otherEntries = metaMap.getMetadataEntryList().stream()
            .filter(e -> e != entry)
            .map(e -> e.getText().trim())
            .toList();

        String canonicalForm;
        if (otherEntries.isEmpty()) {
            canonicalForm = typeName + " " + baseType;
        } else {
            canonicalForm = typeName + " { " + String.join(" ", otherEntries) + " } " + baseType;
        }

        return """
            <div style="padding: 6px 10px; border-left: 4px solid #F5A623; background-color: rgba(245, 166, 35, 0.12); color: #D68910; margin-bottom: 10px; border-radius: 2px; font-family: sans-serif;">
                <b style="font-size: 1.05em;">&#9888; Warning W003: Redundant Metadata Facet</b><br/>
                Facet <code>%s</code> is redundant; %s<br/>
                <b>Canonical Form:</b> <code>%s</code>
            </div>
            <hr/>
            <b>Quick-Fix:</b> Press <code>Alt+Enter</code> to invoke <i>Remove redundant facet '%s'</i>.
            """.formatted(text, reason, canonicalForm, text);
    }

    private static @Nullable String getFacetSpecificationDoc(String keyword) {
        return switch (keyword) {
            case StvnVocabulary.FACET_KW_SIZE -> "Configures physical storage bit-width (e.g. <code>#size 16</code>, <code>#size 64</code>).";
            case StvnVocabulary.FACET_KW_UNSIGNED -> "Enforces non-negative physical unsigned boundaries <code>[0, 2^n - 1]</code> on integer domains.";
            case StvnVocabulary.FACET_KW_MIN_INCL -> "Defines inclusive lower bound <code>[v, ...)</code> for numeric or temporal ranges.";
            case StvnVocabulary.FACET_KW_MAX_EXCL -> "Defines exclusive upper bound <code>(..., v)</code> for numeric or temporal ranges.";
            case StvnVocabulary.FACET_KW_MIN_EXCL -> "Defines exclusive lower bound <code>(v, ...)</code> for continuous float ranges.";
            case StvnVocabulary.FACET_KW_MAX_INCL -> "Defines inclusive upper bound <code>(..., v]</code> for continuous float ranges.";
            case StvnVocabulary.FACET_KW_MIN_SIZE -> "Defines minimum non-negative character length or element cardinality.";
            case StvnVocabulary.FACET_KW_MAX_SIZE -> "Defines maximum character capacity or decimal float mantissa precision.";
            case StvnVocabulary.FACET_KW_EXACT -> "Enforces arbitrary-precision decimal arithmetic on floating-point types.";
            case StvnVocabulary.FACET_KW_EQUATABLE -> "Controls participation in value equivalence and hashing operations.";
            case StvnVocabulary.FACET_KW_COMPARABLE -> "Controls participation in total ordering and comparison operations.";
            case StvnVocabulary.FACET_KW_PRESERVE_INDENT -> "Preserves verbatim leading indentation and whitespace across multiline strings.";
            default -> null;
        };
    }

    // ============================================================================
    // THEME INVARIANCE & SEMANTIC COLOR RESOLUTION
    // ============================================================================

    private static String getBorderColorHex() {
        return ColorUtil.toHex(JBColor.border());
    }

    private static String getSatisfiedBadgeColorHex() {
        return ColorUtil.toHex(new JBColor(new java.awt.Color(0x388E3C), new java.awt.Color(0x81C784)));
    }

    private static String getViolationBadgeColorHex() {
        return ColorUtil.toHex(new JBColor(new java.awt.Color(0xD32F2F), new java.awt.Color(0xE57373)));
    }

    private static String getWarningBadgeColorHex() {
        return ColorUtil.toHex(new JBColor(new java.awt.Color(0xF57C00), new java.awt.Color(0xFFB74D)));
    }

    private static String getActiveOverrideColorHex() {
        return ColorUtil.toHex(new JBColor(new java.awt.Color(0x388E3C), new java.awt.Color(0x81C784)));
    }

    private static String getInheritedColorHex() {
        return ColorUtil.toHex(new JBColor(new java.awt.Color(0x1976D2), new java.awt.Color(0x64B5F6)));
    }

    private static String getDefaultColorHex() {
        return ColorUtil.toHex(UIUtil.getContextHelpForeground());
    }

    // ============================================================================
    // ZONE RENDERERS: :defs RHS BASE TYPE & LHS TYPE ALIAS
    // ============================================================================

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for an RHS Base Type in :defs.
     */
    private @NotNull String renderRhsBaseTypeCard(String baseName, TypeDefinition enclosingDef) {
        var aliasKw = enclosingDef.getTypeKeyword();
        var aliasName = aliasKw != null ? aliasKw.getText().trim() : ":Anonymous";

        var sb = new StringBuilder();
        // Zone 1: Identity & Structural Trace
        sb.append("<div class='definition'>");
        String typeTitle = switch (baseName) {
            case StvnVocabulary.TYPE_INT -> "<b>Built-in Numeric:</b> :Int<br/>";
            case StvnVocabulary.TYPE_STRING -> "<b>Built-in String Type:</b> :String<br/>";
            case StvnVocabulary.TYPE_BOOLEAN -> "<b>Built-in Primitive:</b> :Boolean<br/>";
            case StvnVocabulary.TYPE_FLOAT -> "<b>Built-in Floating-Point:</b> :Float<br/>";
            case StvnVocabulary.TYPE_TIME_EPOCH -> "<b>Built-in Temporal:</b> :TimeEpoch<br/>";
            case StvnVocabulary.TYPE_DATE_TIME -> "<b>Built-in Temporal:</b> :DateTime<br/>";
            default -> "";
        };
        sb.append(typeTitle);
        sb.append("<b>Base Type:</b> <code>").append(baseName).append("</code><br/>");
        sb.append("<b>Declared For:</b> Type Alias <code>").append(aliasName).append("</code> in <code>:defs</code><br/>");
        sb.append("</div><hr/>");

        // Zone 2: Effective Semantic Contract
        sb.append("<div class='content'>");
        var facetsTable = buildEffectiveFacetsTable(enclosingDef);
        if (facetsTable != null) {
            sb.append(facetsTable).append("<br/>");
        } else {
            sb.append("<b>Active Constraints:</b> <i>None (defaults active)</i><br/>");
        }
        sb.append("</div><hr/>");

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
        var activeTags = extractActiveFacetTags(enclosingDef);
        var unsetFacets = StvnMetadataRegistry.getUnsetFacets(baseName, activeTags);
        if (!unsetFacets.isEmpty()) {
            sb.append("<b>Configurable Options (Unset):</b> ");
            var list = new ArrayList<String>();
            for (var f : unsetFacets) {
                list.add("<code>" + f.facetTag() + "</code>");
            }
            sb.append(String.join(", ", list)).append("<br/>");
        }
        appendValidLexicalForms(sb, baseName);
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for an LHS Type Alias in :defs.
     */
    private @NotNull String renderLhsTypeAliasCard(TypeDefinition typeDef, @Nullable PsiElement originalElement) {
        var keyword = typeDef.getTypeKeyword();
        if (keyword != null) {
            var kwText = keyword.getText();
            if (kwText.startsWith(":org/stvnadore/prelude/")) {
                kwText = ":" + kwText.substring(":org/stvnadore/prelude/".length());
            }
            var specDoc = getBuiltInSpecificationDoc(kwText);
            if (specDoc != null && (typeDef.getContainingFile() == null || typeDef.getContainingFile().getName().contains("prelude"))) {
                return specDoc;
            }
        }

        var schemaType = typeDef.getSchemaType();
        var subset = StvnTypeResolver.resolveEnumSubset(typeDef);
        var underlying = subset != null
            ? StvnSchemaFormatter.formatEnumVariants(subset.allowedVariants())
            : (schemaType != null ? StvnTypeResolver.formatSchemaType(schemaType) : "Unknown");

        var sb = new StringBuilder();
        // Zone 1: Identity & Structural Trace
        sb.append("<div class='definition'>");
        if (keyword != null) {
            sb.append("<b>Type Alias:</b> ").append(keyword.getText()).append("<br/>");

            var enclosingPkg = PsiTreeUtil.getParentOfType(typeDef, PackageEnclosure.class);
            if (enclosingPkg != null && enclosingPkg.getPackagePath() != null) {
                var pkgPath = enclosingPkg.getPackagePath().getText().trim();
                var localName = keyword.getText().startsWith(":") ? keyword.getText().substring(1) : keyword.getText();
                var fqni = pkgPath + "/" + localName;
                sb.append("<b>Package:</b> <code>").append(pkgPath).append("</code><br/>");
                sb.append("<b>FQNI:</b> <code>").append(fqni).append("</code><br/>");
            }

            var containingFile = typeDef.getContainingFile();
            if (containingFile != null) {
                var fileName = containingFile.getName();
                if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_di")) {
                    sb.append("<b>Imported From:</b> <code>\"").append(fileName).append("\"</code><br/>");
                } else if (enclosingPkg == null) {
                    sb.append("<b>Declared In:</b> Local Definitions (<code>:defs</code>)<br/>");
                }
            }

            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            if (trace.size() > 1) {
                sb.append("<b>Resolution Path:</b> ");
                for (var i = 0; i < trace.size(); i++) {
                    if (i > 0) sb.append(" &rarr; ");
                    sb.append(trace.get(i));
                }
                sb.append("<br/>");
            }
        }

        var metricHtml = extractStructuralMetricHtml(typeDef);
        if (metricHtml != null) {
            sb.append(metricHtml).append("<br/>");
        }

        if (subset != null) {
            var lineage = formatDerivationLineage(typeDef, subset);
            if (lineage != null) {
                sb.append("<b>Derivation:</b> ").append(lineage).append("<br/>");
            }
            sb.append("<b>Variant Count:</b> ").append(subset.allowedVariants().size()).append("<br/>");
        }
        sb.append("</div><hr/>");

        // Zone 2: Effective Semantic Contract
        sb.append("<div class='content'>");
        var facetsTable = buildEffectiveFacetsTable(typeDef);
        if (facetsTable != null) {
            sb.append(facetsTable).append("<br/>");
        }
        sb.append("<b>Underlying Structure:</b> ").append(underlying);
        sb.append("</div><hr/>");

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
        var rs = StvnTypeResolver.resolveNominalResolvedSchema(typeDef);
        var baseType = rs != null ? getResolvedBaseTypeString(rs) : ":Value";
        var activeTags = extractActiveFacetTags(typeDef);
        var unsetFacets = StvnMetadataRegistry.getUnsetFacets(baseType, activeTags);
        if (!unsetFacets.isEmpty()) {
            sb.append("<b>Configurable Options (Unset):</b> ");
            var list = new ArrayList<String>();
            for (var f : unsetFacets) {
                list.add("<code>" + f.facetTag() + "</code>");
            }
            sb.append(String.join(", ", list)).append("<br/>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    // ============================================================================
    // ZONE RENDERERS: COMPOSITE CONSTRUCTORS (:Tuple, :Map, :Union, :Enum)
    // ============================================================================

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for a ProductType (:Tuple).
     */
    private @NotNull String renderProductTypeCard(ProductType productType, @Nullable PsiElement originalElement) {
        var slots = productType.getSchemaTypeList();
        int arity = slots.size();
        var sb = new StringBuilder();

        // Zone 1: Identity & Structural Trace
        sb.append("<div class='definition'>");
        sb.append("<b>Product Type Constructor:</b> :Tuple( ... )<br/>");
        sb.append("<b>Positional Arity:</b> ").append(arity).append("<br/>");
        sb.append("</div><hr/>");

        // Zone 2: Effective Semantic Contract (Slot Breakdown Table with explicit column widths)
        sb.append("<div class='content'>");
        sb.append("<b>Positional Slot Projection:</b><br/>");
        sb.append("<table style=\"width: 100%; border-collapse: collapse; margin-top: 4px; margin-bottom: 6px; font-size: 0.9em;\">");
        sb.append("<thead><tr style=\"border-bottom: 1px solid #").append(getBorderColorHex()).append("; text-align: left;\">");
        sb.append("<th width=\"15%\" style=\"padding: 2px 4px;\">Slot</th>");
        sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Declared Schema</th>");
        sb.append("<th width=\"25%\" style=\"padding: 2px 4px;\">Base Type</th>");
        sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Constraints</th>");
        sb.append("</tr></thead><tbody>");

        for (int i = 0; i < slots.size(); i++) {
            var slotSchema = slots.get(i);
            var declStr = StvnSchemaFormatter.formatSchema(slotSchema);
            var resolved = StvnTypeResolver.resolveNominalSchema(slotSchema);
            var baseStr = resolved != null ? StvnSchemaFormatter.formatCleanSchema(resolved) : StvnSchemaFormatter.formatCleanSchema(slotSchema);
            var constraintsStr = extractConstraintsSummary(slotSchema);

            sb.append("<tr>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>(").append(i).append(")</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>").append(declStr).append("</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>").append(baseStr).append("</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\">").append(constraintsStr).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div><hr/>");

        // Zone 2: Effective Semantic Contract
        var enclosingDef = PsiTreeUtil.getParentOfType(productType, TypeDefinition.class);
        if (enclosingDef != null) {
            var facetsTable = buildEffectiveFacetsTable(enclosingDef);
            if (facetsTable != null) {
                sb.append("<div class='content'>").append(facetsTable).append("</div><hr/>");
            }
        }

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
        sb.append("<b>Payload Syntax:</b> Parenthesized values: <code>( val0 val1 ... )</code>.<br/>");
        sb.append("<b>Trait Bubbling:</b> <code>#equatable</code> and <code>#comparable</code> require all child slots to satisfy capability.");
        appendUnsetFacetsForConstructor(sb, enclosingDef, StvnVocabulary.TYPE_TUPLE);
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for a CollectionType (:Seq, :Set, :Map).
     */
    private @NotNull String renderCollectionTypeCard(CollectionType collType, @Nullable PsiElement originalElement) {
        var first = collType.getFirstChild();
        var collName = first != null ? first.getText().trim() : ":Collection";
        var innerSchemas = collType.getSchemaTypeList();
        var sb = new StringBuilder();

        sb.append("<div class='definition'>");
        if (StvnVocabulary.TYPE_MAP.equals(collName)) {
            sb.append("<b>Collection Constructor:</b> :Map( :KeyType :ValType )<br/>");
        } else if (StvnVocabulary.TYPE_SET.equals(collName)) {
            sb.append("<b>Collection Constructor:</b> :Set( :ElemType )<br/>");
        } else {
            sb.append("<b>Collection Constructor:</b> :Seq( :ElemType )<br/>");
        }
        sb.append("</div><hr/>");

        sb.append("<div class='content'>");
        if (StvnVocabulary.TYPE_MAP.equals(collName) && innerSchemas.size() >= 2) {
            sb.append("<b>Key-Value Component Projection:</b><br/>");
            sb.append("<table style=\"width: 100%; border-collapse: collapse; margin-top: 4px; margin-bottom: 6px; font-size: 0.9em;\">");
            sb.append("<thead><tr style=\"border-bottom: 1px solid #").append(getBorderColorHex()).append("; text-align: left;\">");
            sb.append("<th width=\"18%\" style=\"padding: 2px 4px;\">Component</th>");
            sb.append("<th width=\"28%\" style=\"padding: 2px 4px;\">Declared Schema</th>");
            sb.append("<th width=\"24%\" style=\"padding: 2px 4px;\">Base Type</th>");
            sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Constraints</th>");
            sb.append("</tr></thead><tbody>");

            var keySchema = innerSchemas.get(0);
            var valSchema = innerSchemas.get(1);
            sb.append("<tr><td style=\"padding: 2px 4px;\"><code>Key (Unique)</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>").append(StvnSchemaFormatter.formatSchema(keySchema)).append("</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>").append(StvnSchemaFormatter.formatCleanSchema(keySchema)).append("</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\">").append(extractConstraintsSummary(keySchema)).append("</td></tr>");

            sb.append("<tr><td style=\"padding: 2px 4px;\"><code>Value</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>").append(StvnSchemaFormatter.formatSchema(valSchema)).append("</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\"><code>").append(StvnSchemaFormatter.formatCleanSchema(valSchema)).append("</code></td>");
            sb.append("<td style=\"padding: 2px 4px;\">").append(extractConstraintsSummary(valSchema)).append("</td></tr>");
            sb.append("</tbody></table>");
        } else if (!innerSchemas.isEmpty()) {
            var elemSchema = innerSchemas.get(0);
            sb.append("<b>Element Schema:</b> <code>").append(StvnSchemaFormatter.formatSchema(elemSchema)).append("</code><br/>");
            sb.append("<b>Element Base Type:</b> <code>").append(StvnSchemaFormatter.formatCleanSchema(elemSchema)).append("</code><br/>");
        }
        sb.append("</div><hr/>");

        // Zone 2: Effective Semantic Contract
        var enclosingDef = PsiTreeUtil.getParentOfType(collType, TypeDefinition.class);
        if (enclosingDef != null) {
            var facetsTable = buildEffectiveFacetsTable(enclosingDef);
            if (facetsTable != null) {
                sb.append("<div class='content'>").append(facetsTable).append("</div><hr/>");
            }
        }

        sb.append("<div class='content'>");
        sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
        if (StvnVocabulary.TYPE_MAP.equals(collName)) {
            sb.append("Defines a key-value mapping with strictly unique keys.<br/>");
            sb.append("<b>Payload Syntax:</b> <code>{ [ key1 val1 ] [ key2 val2 ] }</code>.<br/>");
            sb.append("<b>Supported Facets:</b> <code>#invertible</code>, <code>#minSize</code>, <code>#maxSize</code>.");
        } else {
            sb.append("<b>Payload Syntax:</b> <code>[ elem1 elem2 ... ]</code>.<br/>");
            sb.append("<b>Supported Facets:</b> <code>#minSize</code>, <code>#maxSize</code>.");
        }
        appendUnsetFacetsForConstructor(sb, enclosingDef, collName);
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for a SumType (:Union, :Option, :Either, :Enum).
     */
    private @NotNull String renderSumTypeCard(SumType sumType, @Nullable PsiElement originalElement) {
        var sb = new StringBuilder();
        if (sumType.getEnumDef() != null) {
            var variants = sumType.getEnumDef().getValueKeywordList();
            sb.append("<div class='definition'>");
            sb.append("<b>Enumeration Constructor:</b> :Enum [ ... ]<br/>");
            sb.append("<b>Variant Count:</b> ").append(variants.size()).append("<br/>");
            sb.append("</div><hr/>");

            sb.append("<div class='content'>");
            sb.append("<b>Variant Ledger:</b><br/>");
            sb.append("<table style=\"width: 100%; border-collapse: collapse; margin-top: 4px; margin-bottom: 6px; font-size: 0.9em;\">");
            sb.append("<thead><tr style=\"border-bottom: 1px solid #").append(getBorderColorHex()).append("; text-align: left;\">");
            sb.append("<th width=\"15%\" style=\"padding: 2px 4px;\">Index</th>");
            sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Variant Tag</th>");
            sb.append("<th width=\"25%\" style=\"padding: 2px 4px;\">Status</th>");
            sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Origin</th>");
            sb.append("</tr></thead><tbody>");

            for (int i = 0; i < variants.size(); i++) {
                sb.append("<tr>");
                sb.append("<td style=\"padding: 2px 4px;\">").append(i).append("</td>");
                sb.append("<td style=\"padding: 2px 4px;\"><code>").append(variants.get(i).getText()).append("</code></td>");
                sb.append("<td style=\"padding: 2px 4px;\"><b style=\"color: #").append(getActiveOverrideColorHex()).append(";\">Active</b></td>");
                sb.append("<td style=\"padding: 2px 4px;\">Declared</td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div><hr/>");

            // Zone 2: Effective Semantic Contract
            var enclosingDef = PsiTreeUtil.getParentOfType(sumType, TypeDefinition.class);
            if (enclosingDef != null) {
                var facetsTable = buildEffectiveFacetsTable(enclosingDef);
                if (facetsTable != null) {
                    sb.append("<div class='content'>").append(facetsTable).append("</div><hr/>");
                }
            }

            sb.append("<div class='content'>");
            sb.append("<b>Payload Syntax:</b> <code>#variant</code>.<br/>");
            sb.append("<b>Subsetting:</b> Derivations support <code>#filterIncl</code> and <code>#filterExcl</code>.");
            appendUnsetFacetsForConstructor(sb, enclosingDef, StvnVocabulary.TYPE_ENUM);
            sb.append("</div>");
            return sb.toString();
        }

        if (isUnionSumType(sumType)) {
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
            sb.append("<div class='definition'>");
            sb.append("<b>Algebraic Union Constructor:</b> :Union( ... )<br/>");
            sb.append("<b>Branch Count:</b> ").append(branches.size()).append("<br/>");
            sb.append("</div><hr/>");

            sb.append("<div class='content'>");
            sb.append("<b>Tagged Branch Ledger:</b><br/>");
            sb.append("<table style=\"width: 100%; border-collapse: collapse; margin-top: 4px; margin-bottom: 6px; font-size: 0.9em;\">");
            sb.append("<thead><tr style=\"border-bottom: 1px solid #").append(getBorderColorHex()).append("; text-align: left;\">");
            sb.append("<th width=\"15%\" style=\"padding: 2px 4px;\">Branch Tag</th>");
            sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Declared Schema</th>");
            sb.append("<th width=\"25%\" style=\"padding: 2px 4px;\">Base Type</th>");
            sb.append("<th width=\"30%\" style=\"padding: 2px 4px;\">Inference</th>");
            sb.append("</tr></thead><tbody>");

            for (int i = 0; i < branches.size(); i++) {
                var branchSchema = branches.get(i);
                sb.append("<tr>");
                sb.append("<td style=\"padding: 2px 4px;\"><code>#").append(i + 1).append("</code></td>");
                sb.append("<td style=\"padding: 2px 4px;\"><code>").append(StvnSchemaFormatter.formatSchema(branchSchema)).append("</code></td>");
                sb.append("<td style=\"padding: 2px 4px;\"><code>").append(StvnSchemaFormatter.formatCleanSchema(branchSchema)).append("</code></td>");
                sb.append("<td style=\"padding: 2px 4px;\">Implied Rule C</td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div><hr/>");

            // Zone 2: Effective Semantic Contract
            var enclosingDef = PsiTreeUtil.getParentOfType(sumType, TypeDefinition.class);
            if (enclosingDef != null) {
                var facetsTable = buildEffectiveFacetsTable(enclosingDef);
                if (facetsTable != null) {
                    sb.append("<div class='content'>").append(facetsTable).append("</div><hr/>");
                }
            }

            // Zone 3: Authoring Capabilities & Context
            sb.append("<div class='content'>");
            sb.append("<b>Payload Syntax:</b> Explicit tag <code>#1 val</code>, <code>#2 val</code>, or unambiguous compiler inference.");
            appendUnsetFacetsForConstructor(sb, enclosingDef, StvnVocabulary.TYPE_UNION);
            sb.append("</div>");
            return sb.toString();
        }

        if (isOptionSumType(sumType)) {
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
            sb.append("<div class='definition'>");
            sb.append("<b>Optional Value Constructor:</b> :Option( :ElemType )<br/>");
            sb.append("</div><hr/>");

            sb.append("<div class='content'>");
            if (!branches.isEmpty()) {
                var elemSchema = branches.get(0);
                sb.append("<b>Element Schema:</b> <code>").append(StvnSchemaFormatter.formatSchema(elemSchema)).append("</code><br/>");
                sb.append("<b>Element Base Type:</b> <code>").append(StvnSchemaFormatter.formatCleanSchema(elemSchema)).append("</code><br/>");
            }
            sb.append("</div><hr/>");

            // Zone 2: Effective Semantic Contract
            var enclosingDef = PsiTreeUtil.getParentOfType(sumType, TypeDefinition.class);
            if (enclosingDef != null) {
                var facetsTable = buildEffectiveFacetsTable(enclosingDef);
                if (facetsTable != null) {
                    sb.append("<div class='content'>").append(facetsTable).append("</div><hr/>");
                }
            }

            // Zone 3: Authoring Capabilities & Context
            sb.append("<div class='content'>");
            sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
            sb.append("<b>Payload Syntax:</b> <code>#NONE</code> or <code>#SOME val</code>.<br/>");
            sb.append("<b>Trait Bubbling:</b> <code>#equatable</code> and <code>#comparable</code> require inner element to satisfy capability.");
            appendUnsetFacetsForConstructor(sb, enclosingDef, StvnVocabulary.TYPE_OPTION);
            sb.append("</div>");
            return sb.toString();
        }

        if (isEitherSumType(sumType)) {
            var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
            sb.append("<div class='definition'>");
            sb.append("<b>Disjoint Union Constructor:</b> :Either( :LeftType :RightType )<br/>");
            sb.append("</div><hr/>");

            sb.append("<div class='content'>");
            if (branches.size() >= 2) {
                var leftSchema = branches.get(0);
                var rightSchema = branches.get(1);
                sb.append("<b>Left Schema:</b> <code>").append(StvnSchemaFormatter.formatSchema(leftSchema)).append("</code><br/>");
                sb.append("<b>Right Schema:</b> <code>").append(StvnSchemaFormatter.formatSchema(rightSchema)).append("</code><br/>");
            }
            sb.append("</div><hr/>");

            // Zone 2: Effective Semantic Contract
            var enclosingDef = PsiTreeUtil.getParentOfType(sumType, TypeDefinition.class);
            if (enclosingDef != null) {
                var facetsTable = buildEffectiveFacetsTable(enclosingDef);
                if (facetsTable != null) {
                    sb.append("<div class='content'>").append(facetsTable).append("</div><hr/>");
                }
            }

            // Zone 3: Authoring Capabilities & Context
            sb.append("<div class='content'>");
            sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
            sb.append("<b>Payload Syntax:</b> <code>#LEFT leftVal</code> or <code>#RIGHT rightVal</code>.<br/>");
            sb.append("<b>Trait Bubbling:</b> <code>#equatable</code> and <code>#comparable</code> require both branches to satisfy capability.");
            appendUnsetFacetsForConstructor(sb, enclosingDef, StvnVocabulary.TYPE_EITHER);
            sb.append("</div>");
            return sb.toString();
        }

        var text = sumType.getText().trim();
        var doc = getBuiltInSpecificationDoc(text);
        return doc != null ? doc : "<b>Sum Type Constructor:</b> " + text;
    }

    private static void appendUnsetFacetsForConstructor(StringBuilder sb, @Nullable TypeDefinition enclosingDef, String baseType) {
        if (enclosingDef == null) return;
        var activeTags = extractActiveFacetTags(enclosingDef);
        var unsetFacets = StvnMetadataRegistry.getUnsetFacets(baseType, activeTags);
        if (!unsetFacets.isEmpty()) {
            sb.append("<br/><b>Configurable Options (Unset):</b> ");
            var list = new ArrayList<String>();
            for (var f : unsetFacets) {
                list.add("<code>" + f.facetTag() + "</code>");
            }
            sb.append(String.join(", ", list)).append("<br/>");
        }
    }

    // ============================================================================
    // ZONE RENDERERS: :type ROOT SCHEMA & :body PAYLOAD VALUE
    // ============================================================================

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for Root Payload Schema in :type.
     */
    private @NotNull String renderRootPayloadSchemaCard(SchemaType rootSchema, TypeEntry typeEntry) {
        var sb = new StringBuilder();

        TypeDefinition typeDef = null;
        var kw = rootSchema.getTypeKeyword();
        if (kw != null) {
            var file = rootSchema.getContainingFile();
            if (file != null) {
                var target = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                typeDef = PsiTreeUtil.getParentOfType(target, TypeDefinition.class);
            }
        }

        // Zone 1: Identity & Structural Trace
        sb.append("<div class='definition'>");
        sb.append("<b>Document Root Payload Schema:</b> <code>").append(StvnSchemaFormatter.formatSchema(rootSchema)).append("</code><br/>");
        if (typeDef != null && typeDef.getTypeKeyword() != null) {
            sb.append("<b>Type Alias:</b> ").append(typeDef.getTypeKeyword().getText()).append("<br/>");
        }
        sb.append("<b>Locus:</b> Document Root (<code>:type</code> clause)<br/>");
        var structuralTrace = extractRootStructuralTrace(rootSchema, 0, new HashSet<>());
        if (structuralTrace.size() > 1) {
            sb.append("<b>Structural Resolution Trace:</b><br/>");
            for (int i = 0; i < structuralTrace.size(); i++) {
                sb.append("&nbsp;&nbsp;").append(i > 0 ? "&rarr; " : "&bull; ").append("<code>").append(structuralTrace.get(i)).append("</code><br/>");
            }
        }

        var subset = typeDef != null ? StvnTypeResolver.resolveEnumSubset(typeDef) : null;
        if (subset != null && typeDef != null) {
            var lineage = formatDerivationLineage(typeDef, subset);
            if (lineage != null) {
                sb.append("<b>Derivation:</b> ").append(lineage).append("<br/>");
            }
            sb.append("<b>Variant Count:</b> ").append(subset.allowedVariants().size()).append("<br/>");
        }
        sb.append("</div><hr/>");

        // Zone 2: Effective Semantic Contract
        sb.append("<div class='content'>");
        sb.append("<b>Governing Payload Contract:</b><br/>");
        if (typeDef != null) {
            var facetsTable = buildEffectiveFacetsTable(typeDef);
            if (facetsTable != null) {
                sb.append(facetsTable).append("<br/>");
            }
            var underlying = subset != null
                ? StvnSchemaFormatter.formatEnumVariants(subset.allowedVariants())
                : (typeDef.getSchemaType() != null ? StvnSchemaFormatter.formatSchema(typeDef.getSchemaType()) : "Unknown");
            sb.append("<b>Underlying Structure:</b> ").append(underlying).append("<br/>");
        } else {
            var metaMap = rootSchema.getMetadataMap();
            var minSize = java.math.BigInteger.ZERO;
            var maxSize = java.math.BigInteger.valueOf(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY);
            if (metaMap != null) {
                for (var entry : metaMap.getMetadataEntryList()) {
                    var txt = entry.getText().trim();
                    if (txt.startsWith("#minSize")) {
                        var parts = txt.split("\\s+");
                        if (parts.length >= 2) try { minSize = StvnLiteralParser.parseBigInteger(parts[1]); } catch (Exception ignored) {}
                    } else if (txt.startsWith("#maxSize")) {
                        var parts = txt.split("\\s+");
                        if (parts.length >= 2) try { maxSize = StvnLiteralParser.parseBigInteger(parts[1]); } catch (Exception ignored) {}
                    }
                }
            }
            sb.append("<b>Payload Sizing:</b> Capacity bounds <code>[#minSize ").append(minSize)
              .append(", #maxSize ").append(maxSize).append("]</code><br/>");
        }
        sb.append("</div><hr/>");

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Document Payload Authoring Rules:</b><br/>");
        sb.append("&bull; Body clause (<code>:body</code>) must supply root payload matching this schema.<br/>");
        sb.append("&bull; Collections enforce delimiter matching: <code>[ ... ]</code> for sequences and sets, <code>{ ... }</code> for maps.<br/>");
        sb.append("&bull; Empty payload representation: <code>[]</code>.");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Renders Zone 1, Zone 2, and Zone 3 for a Payload Value in :body.
     */
    private @NotNull String renderPayloadValueCard(Value value, @Nullable PsiElement originalElement) {
        var sb = new StringBuilder();
        var info = StvnTypeResolver.resolveBaseTypeInfo(value);
        var schemaType = info != null ? info.getSchema() : null;
        var resolvedType = StvnTypeResolver.resolveValueType(value);
        var displayType = resolvedType != null ? resolvedType : (info != null ? info.getLabel() : "Unknown");

        // Resolve union variant context if value resides in or is an ExplicitUnionValue
        var unionContext = resolveUnionVariantHoverContext(value, originalElement);
        var governingSchema = (unionContext != null) ? unionContext.governingSchema() : displayType;
        var targetSchemaForCompliance = (unionContext != null && unionContext.branchSchema() != null)
            ? unionContext.branchSchema()
            : schemaType;
        var targetValueForCompliance = (unionContext != null && unionContext.payloadValue() != null)
            ? unionContext.payloadValue()
            : value;

        // Zone 1: Identity & Structural Trace
        sb.append("<div class='definition'>");
        sb.append("<b>Payload Value:</b> <code>").append(value.getText()).append("</code><br/>");
        sb.append("<b>Value Type:</b> ").append(displayType).append("<br/>");
        sb.append("<b>Expression:</b> ").append(value.getText()).append("<br/>");
        sb.append("<b>Locus:</b> Document Payload Body (<code>:body</code> clause)<br/>");
        sb.append("<b>Governing Schema:</b> <code>").append(governingSchema).append("</code><br/>");
        sb.append("</div><hr/>");

        // Zone 2: Effective Semantic Contract (Dynamic Constraint Evaluation)
        sb.append("<div class='content'>");
        var compliance = evaluateValueCompliance(targetValueForCompliance, targetSchemaForCompliance);
        String badgeColor = "Satisfied (Precision Loss)".equals(compliance.statusLabel())
            ? getWarningBadgeColorHex()
            : (compliance.isSatisfied() ? getSatisfiedBadgeColorHex() : getViolationBadgeColorHex());
        String badgeText = compliance.statusLabel();
        sb.append("<b>Compliance Status:</b> <b style=\"color: #").append(badgeColor).append(";\">").append(badgeText).append("</b><br/>");
        sb.append(compliance.detailExplanation()).append("<br/>");

        var epochDoc = buildTimeEpochValueHoverDoc(targetValueForCompliance, displayType);
        if (epochDoc != null) {
            sb.append(epochDoc);
        }
        sb.append("</div><hr/>");

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Valid Lexical &amp; Syntax Forms:</b><br/>");
        for (var form : compliance.validLexicalForms()) {
            sb.append("&bull; ").append(form).append("<br/>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    // ============================================================================
    // COMPLETE HELPER IMPLEMENTATIONS
    // ============================================================================

    /**
     * Evaluates a payload value against governing schema constraints, computing compliance status,
     * explanation text, and valid alternative lexical representations.
     */
    private static @NotNull StvnValueComplianceResult evaluateValueCompliance(Value value, @Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return new StvnValueComplianceResult(true, "Unchecked", "No governing schema identified", Collections.emptyMap(), Collections.emptyList());
        }

        var baseType = resolveBaseTypeString(schemaType, new HashSet<>());
        if (baseType == null) baseType = ":Value";

        // Resolve nominal constraints if nominal alias (Mandatory Technical Amendments 1 & 3)
        org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints constraints = null;
        TypeDefinition td = null;
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var file = schemaType.getContainingFile();
            if (file != null) {
                var target = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                td = PsiTreeUtil.getParentOfType(target, TypeDefinition.class);
            }
        }
        if (td == null) {
            td = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
        }
        if (td != null) {
            var rs = StvnTypeResolver.resolveNominalResolvedSchema(td);
            if (rs != null) {
                constraints = rs.constraints();
            }
        }

        // 1. Numeric integer literal evaluation
        if (value.getIntegerLiteral() != null) {
            try {
                var text = value.getIntegerLiteral().getText().trim();
                var valBI = org.stvnadore.core.ir.StvnLiteralParser.parseBigInteger(text);

                boolean isUnsigned = false;
                int bitWidth = 32;
                java.math.BigInteger minVal = null;
                java.math.BigInteger maxVal = null;

                if (constraints != null) {
                    isUnsigned = constraints.unsigned();
                    bitWidth = constraints.size().orElse(32);
                    if (constraints.minIncl().isPresent()) minVal = constraints.minIncl().get().toBigIntegerExact();
                    if (constraints.maxExcl().isPresent()) maxVal = constraints.maxExcl().get().toBigIntegerExact().subtract(java.math.BigInteger.ONE);
                    if (constraints.minExcl().isPresent()) {
                        var excl = constraints.minExcl().get().toBigIntegerExact().add(java.math.BigInteger.ONE);
                        minVal = minVal != null ? minVal.max(excl) : excl;
                    }
                    if (constraints.maxIncl().isPresent()) {
                        var incl = constraints.maxIncl().get().toBigIntegerExact();
                        maxVal = maxVal != null ? maxVal.min(incl) : incl;
                    }
                }

                // Local metadata override inspect
                var metaMap = schemaType.getMetadataMap();
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        var t = entry.getText().trim();
                        if (t.equals("#unsigned") || t.startsWith("#unsigned ")) isUnsigned = true;
                        if (t.startsWith("#size")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) try { bitWidth = Integer.parseInt(p[1]); } catch (Exception ignored) {}
                        }
                        if (t.startsWith("#minIncl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) try { minVal = new java.math.BigInteger(p[1]); } catch (Exception ignored) {}
                        }
                        if (t.startsWith("#maxExcl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) try { maxVal = new java.math.BigInteger(p[1]).subtract(java.math.BigInteger.ONE); } catch (Exception ignored) {}
                        }
                        if (t.startsWith("#minExcl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) try {
                                var excl = new java.math.BigInteger(p[1]).add(java.math.BigInteger.ONE);
                                minVal = minVal != null ? minVal.max(excl) : excl;
                            } catch (Exception ignored) {}
                        }
                        if (t.startsWith("#maxIncl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) try {
                                var incl = new java.math.BigInteger(p[1]);
                                maxVal = maxVal != null ? maxVal.min(incl) : incl;
                            } catch (Exception ignored) {}
                        }
                    }
                }

                var bitMin = isUnsigned ? java.math.BigInteger.ZERO : java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
                var bitMax = isUnsigned ? java.math.BigInteger.ONE.shiftLeft(bitWidth).subtract(java.math.BigInteger.ONE) : java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(java.math.BigInteger.ONE);

                var effMin = minVal != null ? minVal.max(bitMin) : bitMin;
                var effMax = maxVal != null ? maxVal.min(bitMax) : bitMax;

                boolean satisfied = valBI.compareTo(effMin) >= 0 && valBI.compareTo(effMax) <= 0;
                String explanation;
                if (satisfied) {
                    explanation = String.format("Value %s conforms to interval [%s, %s) and %d-bit representation",
                        valBI, effMin, effMax.add(java.math.BigInteger.ONE), bitWidth);
                } else if (valBI.compareTo(effMin) < 0) {
                    explanation = String.format("Value %s is below minimum allowable bound %s", valBI, effMin);
                } else {
                    explanation = String.format("Value %s exceeds upper bound %s", valBI, effMax);
                }

                int hexDigits = Math.max(1, (bitWidth + 3) / 4);
                String hexStr = valBI.compareTo(java.math.BigInteger.ZERO) >= 0
                    ? String.format("%0" + hexDigits + "X", valBI)
                    : valBI.toString(16).toUpperCase();
                String binStr = valBI.compareTo(java.math.BigInteger.ZERO) >= 0
                    ? String.format("%" + bitWidth + "s", valBI.toString(2)).replace(' ', '0')
                    : valBI.toString(2);

                var forms = new ArrayList<String>();
                forms.add("Decimal: <code>" + valBI.toString(10) + "</code>");
                forms.add("Hex: <code>0x" + hexStr + "</code>");
                forms.add("Binary: <code>0b" + binStr + "</code>");
                forms.add("Octal: <code>0o" + valBI.toString(8) + "</code>");

                return new StvnValueComplianceResult(satisfied, satisfied ? "Satisfied" : "In Violation", explanation, Collections.emptyMap(), forms);
            } catch (Exception e) {
                return new StvnValueComplianceResult(false, "In Violation", "Invalid integer literal representation: " + e.getMessage(), Collections.emptyMap(), Collections.emptyList());
            }
        }

        // 2. String literal evaluation
        if (value.getStringLiteral() != null) {
            var rawText = value.getText();
            int len = rawText.startsWith("\"\"\"") ? Math.max(0, rawText.length() - 8) : Math.max(0, rawText.length() - 2);

            var minSize = java.math.BigInteger.ZERO;
            var maxSize = java.math.BigInteger.valueOf(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY);
            String regex = null;

            if (constraints != null) {
                minSize = constraints.minSize().map(java.math.BigInteger::valueOf).orElse(java.math.BigInteger.ZERO);
                maxSize = constraints.maxSize().map(java.math.BigInteger::valueOf).orElse(java.math.BigInteger.valueOf(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY));
                regex = constraints.regex().orElse(null);
            }

            var metaMap = schemaType.getMetadataMap();
            if (metaMap != null) {
                for (var entry : metaMap.getMetadataEntryList()) {
                    var t = entry.getText().trim();
                    if (t.startsWith("#minSize")) {
                        var p = t.split("\\s+");
                        if (p.length >= 2) try { minSize = StvnLiteralParser.parseBigInteger(p[1]); } catch (Exception ignored) {}
                    }
                    if (t.startsWith("#maxSize")) {
                        var p = t.split("\\s+");
                        if (p.length >= 2) try { maxSize = StvnLiteralParser.parseBigInteger(p[1]); } catch (Exception ignored) {}
                    }
                    if (t.startsWith("#regex")) {
                        var p = t.split("\\s+", 2);
                        if (p.length >= 2) regex = p[1].replaceAll("^\"|\"$", "");
                    }
                }
            }

            var lenBI = java.math.BigInteger.valueOf(len);
            boolean satisfied = lenBI.compareTo(minSize) >= 0 && lenBI.compareTo(maxSize) <= 0;
            String explanation;
            if (!satisfied) {
                explanation = lenBI.compareTo(minSize) < 0
                    ? String.format("String length %d is below minimum required size %s", len, minSize)
                    : String.format("String length %d exceeds maximum capacity %s", len, maxSize);
            } else if (regex != null) {
                var cleanContent = rawText.replaceAll("^\"|\"$", "");
                if (!cleanContent.matches(regex)) {
                    satisfied = false;
                    explanation = "String does not match required regex pattern: <code>" + regex + "</code>";
                } else {
                    explanation = String.format("String length %d satisfies bounds [%s, %s] and regex pattern", len, minSize, maxSize);
                }
            } else {
                explanation = String.format("String length %d satisfies capacity bounds [%s, %s]", len, minSize, maxSize);
            }

            var forms = List.of(
                "Simple Quoted: <code>\"...\"</code>",
                "Verbatim Fenced: <code>\"\"\"[TAG]\\n...\\n[TAG]\"\"\"</code>"
            );
            return new StvnValueComplianceResult(satisfied, satisfied ? "Satisfied" : "In Violation", explanation, Collections.emptyMap(), forms);
        }

        // 3. Floating-point literal evaluation (continuous and exact)
        if (value.getFloatLiteral() != null) {
            try {
                var text = value.getFloatLiteral().getText().trim();
                var valBD = StvnLiteralParser.parseBigDecimal(text);
                boolean isExact = constraints != null && constraints.exact();
                if (!isExact && schemaType.getMetadataMap() != null) {
                    for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                        if (entry.getText().trim().startsWith("#exact")) isExact = true;
                    }
                }

                if (isExact) {
                    int precision = valBD.precision();
                    int maxSize = constraints != null ? constraints.maxSize().orElse(StvnVocabulary.DEFAULT_EXACT_FLOAT_MAX_SIZE) : StvnVocabulary.DEFAULT_EXACT_FLOAT_MAX_SIZE;
                    if (schemaType.getMetadataMap() != null) {
                        for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                            var t = entry.getText().trim();
                            if (t.startsWith("#maxSize")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    try {
                                        maxSize = StvnLiteralParser.parseBigInteger(p[1]).intValue();
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    BigDecimal minVal = constraints != null ? constraints.minIncl().orElse(null) : null;
                    BigDecimal maxVal = constraints != null ? constraints.maxExcl().orElse(null) : null;
                    if (schemaType.getMetadataMap() != null) {
                        for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                            var t = entry.getText().trim();
                            if (t.startsWith("#minIncl")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    try { minVal = StvnLiteralParser.parseBigDecimal(p[1]); } catch (Exception ignored) {}
                                }
                            } else if (t.startsWith("#maxExcl")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    try { maxVal = StvnLiteralParser.parseBigDecimal(p[1]); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    boolean precOk = precision <= maxSize;
                    boolean minOk = minVal == null || valBD.compareTo(minVal) >= 0;
                    boolean maxOk = maxVal == null || valBD.compareTo(maxVal) < 0;
                    boolean satisfied = precOk && minOk && maxOk;

                    String explanation;
                    if (!precOk) {
                        explanation = String.format("Mantissa precision %d exceeds maximum allowable significant digits %d", precision, maxSize);
                    } else if (!minOk) {
                        explanation = String.format("Value %s is below minimum allowable bound %s", valBD.toPlainString(), minVal.toPlainString());
                    } else if (!maxOk) {
                        explanation = String.format("Value %s meets or exceeds exclusive upper bound %s", valBD.toPlainString(), maxVal.toPlainString());
                    } else {
                        explanation = String.format("Value %s conforms to discrete decimal interval and mantissa precision (%d digits <= %d)",
                            valBD.toPlainString(), precision, maxSize);
                    }

                    var forms = new ArrayList<String>();
                    forms.add("Exact Decimal: <code>" + valBD.toPlainString() + "</code>");
                    forms.add("Scientific: <code>" + valBD.toString() + "</code>");
                    forms.add("Precision (Digits): <code>" + precision + "</code>");
                    forms.add("Scale: <code>" + valBD.scale() + "</code>");
                    return new StvnValueComplianceResult(satisfied, satisfied ? "Satisfied" : "In Violation", explanation, Collections.emptyMap(), forms);
                } else {
                    int bitWidth = constraints != null ? constraints.size().orElse(64) : 64;
                    if (schemaType.getMetadataMap() != null) {
                        for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                            var t = entry.getText().trim();
                            if (t.startsWith("#size")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    try { bitWidth = StvnLiteralParser.parseBigInteger(p[1]).intValue(); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    BigDecimal capMin = (bitWidth == 32) ? StvnVocabulary.FLOAT32_MIN : StvnVocabulary.FLOAT64_MIN;
                    BigDecimal capMax = (bitWidth == 32) ? StvnVocabulary.FLOAT32_MAX : StvnVocabulary.FLOAT64_MAX;

                    boolean capOk = valBD.compareTo(capMin) >= 0 && valBD.compareTo(capMax) <= 0;
                    String explanation;
                    String statusLabel = capOk ? "Satisfied" : "In Violation";
                    int precision = valBD.precision();
                    int safeDigits = (bitWidth == 32) ? 8 : 17;

                    String roundedStr = (bitWidth == 32)
                        ? Float.toString(valBD.floatValue())
                        : Double.toString(valBD.doubleValue());

                    boolean selfEquivalent = false;
                    try {
                        selfEquivalent = new BigDecimal(roundedStr).compareTo(valBD) == 0;
                    } catch (Exception ignored) {}

                    boolean precisionLoss = capOk && (precision > safeDigits) && !selfEquivalent;

                    if (!capOk) {
                        explanation = String.format("Value %s is out of physical capacity bounds [%s, %s] for %d-bit float",
                            valBD.toString(), capMin, capMax, bitWidth);
                    } else if (precisionLoss) {
                        statusLabel = "Satisfied (Precision Loss)";
                        explanation = String.format(
                            "Floating-point literal precision (%d digits) exceeds IEEE-754 %s capacity (~%d digits); binary encoding truncates and rounds value to %s",
                            precision,
                            (bitWidth == 32 ? "Single (32-bit)" : "Double (64-bit)"),
                            (bitWidth == 32 ? 8 : 16),
                            roundedStr
                        );
                    } else {
                        explanation = String.format("Value %s conforms to %d-bit continuous float representation",
                            valBD.toString(), bitWidth);
                    }

                    var forms = new ArrayList<String>();
                    if (precisionLoss) {
                        forms.add("Authored Literal: <code>" + text + "</code>");
                        forms.add("Encoded Binary Float: <code>" + roundedStr + "</code>");
                    }
                    forms.add("Decimal: <code>" + valBD.toPlainString() + "</code>");
                    forms.add("Scientific: <code>" + valBD.toString() + "</code>");
                    forms.add("Format: <code>IEEE-754 " + (bitWidth == 32 ? "Single (32-bit)" : "Double (64-bit)") + "</code>");
                    return new StvnValueComplianceResult(capOk, statusLabel, explanation, Collections.emptyMap(), forms);
                }
            } catch (Exception e) {
                return new StvnValueComplianceResult(false, "In Violation", "Invalid float literal representation: " + e.getMessage(), Collections.emptyMap(), Collections.emptyList());
            }
        }

        return new StvnValueComplianceResult(true, "Satisfied", "Payload value conforms to schema shape", Collections.emptyMap(), Collections.emptyList());
    }

    /**
     * Extracts full structural resolution trace from root down to primitive representations,
     * strictly enforcing a depth ceiling (<= 16) and cycle detection to eliminate recursion risks.
     */
    private static @NotNull List<String> extractRootStructuralTrace(SchemaType rootSchema, int depth, Set<String> visited) {
        var trace = new ArrayList<String>();
        if (depth > 16) {
            trace.add(StvnSchemaFormatter.formatSchema(rootSchema) + " (Max Depth Reached)");
            return trace;
        }

        var currentSig = StvnSchemaFormatter.formatSchema(rootSchema);
        trace.add(currentSig);

        var kw = rootSchema.getTypeKeyword();
        if (kw != null) {
            var typeName = kw.getText().trim();
            if (visited.contains(typeName)) {
                trace.add(typeName + " (Recursive Nominal Type)");
                return trace;
            }
            visited.add(typeName);
            var file = rootSchema.getContainingFile();
            if (file != null) {
                var targetDef = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
                var td = PsiTreeUtil.getParentOfType(targetDef, TypeDefinition.class);
                if (td != null && td.getSchemaType() != null) {
                    var childTrace = extractRootStructuralTrace(td.getSchemaType(), depth + 1, new HashSet<>(visited));
                    trace.addAll(childTrace);
                }
            }
            return trace;
        }

        var constructor = rootSchema.getSchemaConstructor();
        if (constructor != null && constructor.getCollectionType() != null) {
            var coll = constructor.getCollectionType();
            var inner = coll.getSchemaTypeList();
            if (!inner.isEmpty()) {
                var first = coll.getFirstChild();
                var collName = first != null ? first.getText().trim() : ":Seq";
                var childTrace = extractRootStructuralTrace(inner.get(0), depth + 1, new HashSet<>(visited));
                for (int i = 1; i < childTrace.size(); i++) {
                    trace.add(collName + "( " + childTrace.get(i) + " )");
                }
            }
        }
        return trace;
    }

    /**
     * Extracts human-readable constraint summaries for composite slot projections.
     */
    private static @NotNull String extractConstraintsSummary(SchemaType schema) {
        var metaMap = schema.getMetadataMap();
        var facets = new ArrayList<String>();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                facets.add("<code>" + entry.getText().trim() + "</code>");
            }
        }
        var kw = schema.getTypeKeyword();
        if (kw != null) {
            var file = schema.getContainingFile();
            if (file != null) {
                var target = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                var td = PsiTreeUtil.getParentOfType(target, TypeDefinition.class);
                if (td != null && td.getMetadataMap() != null) {
                    for (var entry : td.getMetadataMap().getMetadataEntryList()) {
                        facets.add("<code>" + entry.getText().trim() + "</code>");
                    }
                }
            }
        }
        return facets.isEmpty() ? "<i>None</i>" : String.join(" ", facets);
    }

    /**
     * Extracts all active facet tags from a type definition's local metadata and resolved constraints.
     */
    private static @NotNull Set<String> extractActiveFacetTags(TypeDefinition typeDef) {
        var tags = new HashSet<String>();
        var metaMap = typeDef.getMetadataMap();
        if (metaMap == null && typeDef.getSchemaType() != null) {
            metaMap = typeDef.getSchemaType().getMetadataMap();
        }
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var txt = entry.getText().trim();
                var parts = txt.split("\\s+");
                if (parts.length > 0) tags.add(parts[0]);
            }
        }
        var rs = StvnTypeResolver.resolveNominalResolvedSchema(typeDef);
        var baseType = rs != null ? getResolvedBaseTypeString(rs) : null;
        if (baseType == null && typeDef.getSchemaType() != null) {
            baseType = StvnTypeResolver.formatSchemaType(typeDef.getSchemaType());
        }
        boolean isCtor = isConstructorBaseType(baseType);

        if (rs != null) {
            var c = rs.constraints();
            if (c.unsigned()) tags.add(StvnVocabulary.FACET_KW_UNSIGNED);
            if (c.size().isPresent()) tags.add(StvnVocabulary.FACET_KW_SIZE);
            if (c.exact()) tags.add(StvnVocabulary.FACET_KW_EXACT);
            if (c.invertible()) tags.add(StvnVocabulary.FACET_KW_INVERTIBLE);
            if (c.scale().isPresent()) tags.add("#" + c.scale().get());
            if (c.offset()) tags.add(StvnVocabulary.FACET_KW_OFFSET);
            if (c.zoned()) tags.add(StvnVocabulary.FACET_KW_ZONED);
            if (c.audited()) tags.add(StvnVocabulary.FACET_KW_AUDITED);
            if (c.minIncl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MIN_INCL);
            if (c.maxExcl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MAX_EXCL);
            if (c.minExcl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MIN_EXCL);
            if (c.maxIncl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MAX_INCL);
            if (c.minSize().isPresent()) tags.add(StvnVocabulary.FACET_KW_MIN_SIZE);
            if (c.maxSize().isPresent()) tags.add(StvnVocabulary.FACET_KW_MAX_SIZE);
            if (c.regex().isPresent()) tags.add(StvnVocabulary.FACET_KW_REGEX);
            if (c.preserveIndent()) tags.add(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
            if (c.equatable().isPresent() && (!isCtor || c.explicitOverrides().contains("equatable"))) {
                tags.add(StvnVocabulary.FACET_KW_EQUATABLE);
            }
            if (c.comparable().isPresent() && (!isCtor || c.explicitOverrides().contains("comparable"))) {
                tags.add(StvnVocabulary.FACET_KW_COMPARABLE);
            }
        }
        if (baseType != null && !StvnMetadataRegistry.getSupportedFacets(baseType).isEmpty() && !isCtor) {
            tags.add(StvnVocabulary.FACET_KW_EQUATABLE);
            tags.add(StvnVocabulary.FACET_KW_COMPARABLE);
        }
        return tags;
    }

    private static boolean isConstructorBaseType(@Nullable String baseType) {
        if (baseType == null) return false;
        return baseType.startsWith(StvnVocabulary.TYPE_TUPLE)
                || baseType.startsWith(StvnVocabulary.TYPE_SEQ)
                || baseType.startsWith(StvnVocabulary.TYPE_SET)
                || baseType.startsWith(StvnVocabulary.TYPE_MAP)
                || baseType.startsWith(StvnVocabulary.TYPE_UNION)
                || baseType.startsWith(StvnVocabulary.TYPE_EITHER)
                || baseType.startsWith(StvnVocabulary.TYPE_OPTION)
                || baseType.startsWith(StvnVocabulary.TYPE_ENUM);
    }

    /**
     * Appends valid canonical alternative lexical representations to Zone 3 content.
     */
    private static void appendValidLexicalForms(StringBuilder sb, String baseType) {
        if (StvnVocabulary.TYPE_INT.equals(baseType)) {
            sb.append("<b>Valid Lexical Forms:</b> Decimal (<code>42</code>), Hex (<code>0x2A</code>), Binary (<code>0b101010</code>), Octal (<code>0o52</code>).<br/>");
        } else if (StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
            sb.append("<b>Valid Lexical Forms:</b> Standard decimal (<code>3.14159</code>), Negative (<code>-0.5</code>), Scientific (<code>1.0e-5</code>).<br/>");
        } else if (StvnVocabulary.TYPE_STRING.equals(baseType)) {
            sb.append("<b>Valid Lexical Forms:</b> Simple quoted (<code>\"text\"</code>), Verbatim fenced (<code>\"\"\"[TAG]...[TAG]\"\"\"</code>).<br/>");
        } else if (StvnVocabulary.TYPE_BOOLEAN.equals(baseType)) {
            sb.append("<b>Valid Lexical Forms:</b> <code>#TRUE</code>, <code>#FALSE</code>, <code>#T</code>, <code>#F</code>.<br/>");
        } else if (StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) {
            sb.append("<b>Valid Lexical Forms:</b> Integer ticks matching scale flag (e.g. <code>1704067200</code> for <code>#s</code>).<br/>");
        } else if (StvnVocabulary.TYPE_DATE_TIME.equals(baseType)) {
            sb.append("<b>Valid Lexical Forms:</b> ISO-8601 timestamps (e.g. <code>\"2026-03-15T08:00:00Z\"</code>).<br/>");
        }
    }

    private record UnionVariantHoverContext(
        String governingSchema,
        @Nullable SchemaType branchSchema,
        @Nullable Value payloadValue
    ) {}

    private static @Nullable UnionVariantHoverContext resolveUnionVariantHoverContext(Value value, @Nullable PsiElement originalElement) {
        ExplicitUnionValue union = value.getExplicitUnionValue();
        Value payloadVal = null;
        if (union != null) {
            payloadVal = union.getValue();
        } else if (value.getParent() instanceof ExplicitUnionValue pUnion) {
            union = pUnion;
            payloadVal = value;
        } else if (originalElement != null) {
            var origUnion = PsiTreeUtil.getParentOfType(originalElement, ExplicitUnionValue.class);
            if (origUnion != null) {
                union = origUnion;
                payloadVal = origUnion.getValue();
            }
        }

        if (union == null) {
            return null;
        }

        var outerVal = PsiTreeUtil.getParentOfType(union, Value.class);
        if (outerVal == null && union.getParent() instanceof Value pVal) {
            outerVal = pVal;
        }
        if (outerVal == null) {
            return null;
        }

        var info = StvnTypeResolver.resolveBaseTypeInfo(outerVal);
        if (info == null || info.getSchema() == null) {
            return null;
        }

        var unionSchema = info.getSchema();
        var nominalUnion = StvnTypeResolver.resolveNominalSchema(unionSchema);
        var schemaToInspect = (nominalUnion != null) ? nominalUnion : unionSchema;
        var ctor = schemaToInspect.getSchemaConstructor();
        if (ctor == null || ctor.getSumType() == null) {
            return null;
        }

        var branches = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        var tagPrefix = union.getUnionTagPrefix();
        if (tagPrefix == null || !tagPrefix.getText().startsWith("#")) {
            return null;
        }

        int branchIdx;
        try {
            branchIdx = Integer.parseInt(tagPrefix.getText().substring(1)) - 1;
        } catch (NumberFormatException e) {
            return null;
        }

        if (branchIdx < 0 || branchIdx >= branches.size()) {
            return null;
        }

        var branchSchema = branches.get(branchIdx);
        var nominalName = "";
        if (unionSchema.getTypeKeyword() != null) {
            nominalName = unionSchema.getTypeKeyword().getText().trim();
        } else {
            var parentDef = PsiTreeUtil.getParentOfType(unionSchema, TypeDefinition.class);
            if (parentDef != null && parentDef.getTypeKeyword() != null) {
                nominalName = parentDef.getTypeKeyword().getText().trim();
            } else {
                nominalName = ":Union";
            }
        }

        // Format branch representation: ({ #size 32 } :Float) or (:Float)
        var branchRepr = StvnSchemaFormatter.formatSchema(branchSchema);
        if (branchRepr.isEmpty()) {
            branchRepr = ":Value";
        }
        var governingSchemaStr = nominalName + " " + tagPrefix.getText().trim() + " (" + branchRepr + ")";

        return new UnionVariantHoverContext(governingSchemaStr, branchSchema, payloadVal);
    }

    public record StvnSlotProjection(
        int index,
        String declaredSchema,
        String baseType,
        String constraintsSummary
    ) {}

    public record StvnValueComplianceResult(
        boolean isSatisfied,
        String statusLabel,
        String detailExplanation,
        Map<String, String> evaluatedConstraints,
        List<String> validLexicalForms
    ) {}
}
