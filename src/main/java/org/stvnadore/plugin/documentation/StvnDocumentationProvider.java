package org.stvnadore.plugin.documentation;

import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
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
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.ir.StvnEnumSubsetInfo;
import org.stvnadore.plugin.metadata.StvnFacetMetadata;
import org.stvnadore.plugin.metadata.StvnMetadataRegistry;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.validation.quickfix.StvnTraitViolationQuickFixProvider;
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
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    public @Nullable PsiElement getDocumentationElementForLink(PsiManager psiManager, String link, PsiElement context) {
        if (context == null || link == null) {
            return null;
        }
        if (link.startsWith("TypeDefinition/")) {
            var typeName = link.substring("TypeDefinition/".length()).trim();
            var file = context.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
                if (resolved != null) {
                    var typeDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                    return (typeDef != null) ? typeDef : resolved;
                }
            }
        } else if (link.startsWith("offset/")) {
            try {
                int offset = Integer.parseInt(link.substring("offset/".length()).trim());
                var file = context.getContainingFile();
                if (file != null && offset >= 0 && offset < file.getTextLength()) {
                    return file.findElementAt(offset);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
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
                    var ir = StvnTypeResolver.resolveNominalIrSchema(typeDef);
                    if (ir instanceof StvnSchema.PoisonedSchema p && p.bestEffortFallback().isPresent()) {
                        return new DegradedSchemaInfo(alias, StvnSchemaFormatter.formatCleanIrSchema(p.bestEffortFallback().get()));
                    }
                    var schemaType = typeDef.getSchemaType();
                    var resolved = schemaType != null ? StvnTypeResolver.resolveNominalSchema(schemaType) : null;
                    var base = resolved != null ? StvnSchemaFormatter.formatCleanSchema(resolved) : (schemaType != null ? StvnSchemaFormatter.formatCleanSchema(schemaType) : ":Value");
                    return new DegradedSchemaInfo(alias, base);
                }
            }
        } else if (target instanceof TypeKeyword typeKw) {
            var alias = typeKw.getText();
            if (StvnTypeResolver.isDegradedNominalAlias(typeKw.getContainingFile(), alias)) {
                var ir = StvnTypeResolver.resolveIrSchemaByAlias(typeKw.getContainingFile(), alias);
                if (ir instanceof StvnSchema.PoisonedSchema p && p.bestEffortFallback().isPresent()) {
                    return new DegradedSchemaInfo(alias, StvnSchemaFormatter.formatCleanIrSchema(p.bestEffortFallback().get()));
                }
                var targetDef = StvnTypeReference.resolveTypeInFile(typeKw.getContainingFile(), alias, new HashSet<>());
                var fallbackBase = ":Value";
                var td = StvnPsiUtils.getParentTypeDefinition(targetDef);
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
                    var ir = StvnTypeResolver.resolveIrSchemaByAlias(value.getContainingFile(), alias);
                    if (ir instanceof StvnSchema.PoisonedSchema p && p.bestEffortFallback().isPresent()) {
                        return new DegradedSchemaInfo(alias, StvnSchemaFormatter.formatCleanIrSchema(p.bestEffortFallback().get()));
                    }
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

        if (target instanceof TypeDefinition td
                && td.getTypeDefTarget() != null
                && StvnVocabulary.KEYWORD_PACKAGE.equals(td.getTypeDefTarget().getText().trim())) {
            return null;
        }

        // 1. Contextual Section Routing: Check if target is RHS base type in :defs
        if ((target instanceof TypeKeyword || target instanceof AtomicType) && originalElement != null) {
            var enclosingDef = PsiTreeUtil.getParentOfType(originalElement, TypeDefinition.class);
            if (enclosingDef != null && enclosingDef.getTypeKeyword() != target) {
                if (enclosingDef.getTypeDefTarget() != null
                        && StvnVocabulary.KEYWORD_PACKAGE.equals(enclosingDef.getTypeDefTarget().getText().trim())) {
                    return null;
                }
                return renderRhsBaseTypeCard(target.getText().trim(), enclosingDef, originalElement);
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

    private static @Nullable StvnSchema resolveNearestNonPoisonedAncestorIr(TypeDefinition startDef) {
        var visited = new HashSet<String>();
        TypeDefinition cursor = startDef;
        while (cursor != null) {
            var schemaType = cursor.getSchemaType();
            if (schemaType == null || schemaType.getTypeKeyword() == null) {
                return null;
            }
            String parentAlias = schemaType.getTypeKeyword().getText().trim();
            if (!visited.add(parentAlias)
                    || StvnVocabulary.isBaseScalarType(parentAlias)
                    || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(parentAlias)) {
                return null;
            }
            var resolved = StvnTypeReference.resolveTypeInFile(cursor.getContainingFile(), parentAlias, new HashSet<>());
            var parentDef = resolved instanceof TypeDefinition td
                ? td
                : PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
            if (parentDef == null || parentDef == cursor) {
                var byAlias = StvnTypeResolver.resolveIrSchemaByAlias(cursor.getContainingFile(), parentAlias);
                if (byAlias instanceof StvnSchema.PoisonedSchema p) {
                    return p.bestEffortFallback().orElse(null);
                }
                return byAlias;
            }
            var parentRawIr = StvnTypeResolver.resolveNominalIrSchema(parentDef);
            if (parentRawIr instanceof StvnSchema.PoisonedSchema p) {
                if (p.bestEffortFallback().isPresent()) {
                    return p.bestEffortFallback().get();
                }
            } else if (parentRawIr != null) {
                return parentRawIr;
            }
            cursor = parentDef;
        }
        return null;
    }

    private static @Nullable String buildEffectiveFacetsTable(TypeDefinition typeDef) {
        var rawIr = StvnTypeResolver.resolveNominalIrSchema(typeDef);
        StvnSchema irSchema = (rawIr instanceof StvnSchema.PoisonedSchema p)
            ? p.bestEffortFallback().orElse(null)
            : rawIr;
        if (irSchema == null) {
            irSchema = resolveNearestNonPoisonedAncestorIr(typeDef);
        }

        var keyword = typeDef.getTypeKeyword();
        var currentAlias = (keyword != null) ? keyword.getText().trim() : ":Type";
        String baseType;
        if (irSchema != null) {
            baseType = StvnSchemaFormatter.formatCleanIrSchema(irSchema);
        } else {
            var terminal = resolveTerminalSchemaType(typeDef, new HashSet<>());
            baseType = terminal != null
                ? StvnSchemaFormatter.formatCleanSchema(terminal)
                : (typeDef.getSchemaType() != null ? StvnSchemaFormatter.formatCleanSchema(typeDef.getSchemaType()) : ":Value");
        }

        var trace = (keyword != null) ? StvnTypeReference.extractResolutionTrace(keyword) : List.<String>of();

        record FacetRow(String tag, String effectiveValue, String origin, String status) {}
        var rows = new ArrayList<FacetRow>();

        var file = typeDef.getContainingFile();
        var diagnostics = StvnTypeResolver.getCompilationDiagnostics(file);
        var invalidFacetTags = new HashSet<String>();
        if (diagnostics != null) {
            var bareAlias = currentAlias.startsWith(":") ? currentAlias.substring(1) : currentAlias;
            for (var d : diagnostics) {
                var sym = d.symbolicId().orElse("");
                var canon = d.canonicalCode().orElse("");
                if (StvnTypeResolver.FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(sym)
                        || StvnTypeResolver.FATAL_SCHEMA_DIAGNOSTIC_CODES.contains(canon)
                        || DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(sym)
                        || "E009".equals(canon)) {
                    var msg = d.message();
                    if (msg.contains("Constraint violation (" + currentAlias + "):")
                            || msg.contains("Constraint violation (:" + bareAlias + "):")
                            || msg.contains("Constraint violation (" + bareAlias + "):")) {
                        for (var tag : List.of("#minIncl", "#minExcl", "#maxIncl", "#maxExcl", "#size", "#minSize", "#maxSize", "#regex")) {
                            if (msg.contains(tag) || msg.contains(tag.substring(1))) {
                                invalidFacetTags.add(tag);
                            }
                        }
                    }
                }
            }
        }

        var declaredMeta = typeDef.getMetadataMap();
        if (declaredMeta == null && typeDef.getSchemaType() != null) {
            declaredMeta = typeDef.getSchemaType().getMetadataMap();
        }
        String declaredMinSize = null;
        String declaredMaxSize = null;
        String declaredSize = null;
        String declaredPreserveIndent = null;
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
                } else if (txt.startsWith("#preserveIndent")) {
                    var parts = txt.split("\\s+");
                    if (parts.length >= 2) declaredPreserveIndent = parts[1];
                }
            }
        }

        boolean isExactFloat = false;
        if (irSchema != null) {
            switch (irSchema) {
                case StvnSchema.IntSchema i -> {
                    if (i.unsigned()) {
                        rows.add(new FacetRow("#unsigned", "#TRUE", "Declared", "Active"));
                    }
                    if (declaredSize != null
                            || !i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)
                            || hasFacetInLineage(typeDef, trace, StvnVocabulary.FACET_NAME_SIZE)) {
                        String eff = declaredSize != null ? declaredSize : i.bitWidth().toString();
                        var originInfo = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_SIZE);
                        String status = invalidFacetTags.contains("#size") ? "Invalid" : originInfo.status();
                        rows.add(new FacetRow("#size", eff, originInfo.origin(), status));
                    }
                    if (i.explicitMinIncl().isPresent()) {
                        String status = invalidFacetTags.contains("#minIncl") ? "Invalid" : "Active";
                        rows.add(new FacetRow("#minIncl", i.explicitMinIncl().get().toString(), "Declared", status));
                    }
                    if (i.explicitMaxExcl().isPresent()) {
                        String status = invalidFacetTags.contains("#maxExcl") ? "Invalid" : "Active";
                        rows.add(new FacetRow("#maxExcl", i.explicitMaxExcl().get().toString(), "Declared", status));
                    }
                }
                case StvnSchema.FloatSchema f -> {
                    if (f.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
                        isExactFloat = true;
                        rows.add(new FacetRow("#exact", "#TRUE", "Declared", "Active"));
                        if (declaredMaxSize != null
                                || !f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)
                                || hasFacetInLineage(typeDef, trace, StvnVocabulary.FACET_NAME_MAX_SIZE)) {
                            String eff = declaredMaxSize != null ? declaredMaxSize : f.exactMaxDigits().toString();
                            var originInfo = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MAX_SIZE);
                            String status = invalidFacetTags.contains("#maxSize") ? "Invalid" : originInfo.status();
                            rows.add(new FacetRow("#maxSize", eff, originInfo.origin(), status));
                        }
                    } else if (declaredSize != null
                            || f.representation() == StvnSchema.FloatRepresentation.IEEE_32
                            || hasFacetInLineage(typeDef, trace, StvnVocabulary.FACET_NAME_SIZE)) {
                        String eff = declaredSize != null ? declaredSize : (f.representation() == StvnSchema.FloatRepresentation.IEEE_32 ? "32" : "64");
                        var originInfo = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_SIZE);
                        String status = invalidFacetTags.contains("#size") ? "Invalid" : originInfo.status();
                        rows.add(new FacetRow("#size", eff, originInfo.origin(), status));
                    }
                    if (f.lowerBound().isPresent()) {
                        var lb = f.lowerBound().get();
                        String tag = lb.kind() == StvnSchema.BoundKind.INCLUSIVE ? "#minIncl" : "#minExcl";
                        String status = invalidFacetTags.contains(tag) ? "Invalid" : "Active";
                        rows.add(new FacetRow(tag, lb.value().toPlainString(), "Declared", status));
                    }
                    if (f.upperBound().isPresent()) {
                        var ub = f.upperBound().get();
                        String tag = ub.kind() == StvnSchema.BoundKind.INCLUSIVE ? "#maxIncl" : "#maxExcl";
                        String status = invalidFacetTags.contains(tag) ? "Invalid" : "Active";
                        rows.add(new FacetRow(tag, ub.value().toPlainString(), "Declared", status));
                    }
                }
                case StvnSchema.StringSchema s -> {
                    var piOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_PRESERVE_INDENT);
                    String effPreserve = declaredPreserveIndent != null
                        ? declaredPreserveIndent
                        : (s.preserveIndent() ? "#TRUE" : "#FALSE");
                    rows.add(new FacetRow("#preserveIndent", effPreserve, piOrigin.origin(), piOrigin.status()));

                    String effMin = declaredMinSize != null ? declaredMinSize : s.minSize().toString();
                    var minOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MIN_SIZE);
                    rows.add(new FacetRow("#minSize", effMin, minOrigin.origin(), invalidFacetTags.contains("#minSize") ? "Invalid" : minOrigin.status()));

                    String effMax = declaredMaxSize != null ? declaredMaxSize : s.maxSize().toString();
                    var maxOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MAX_SIZE);
                    rows.add(new FacetRow("#maxSize", effMax, maxOrigin.origin(), invalidFacetTags.contains("#maxSize") ? "Invalid" : maxOrigin.status()));

                    if (s.rawRegexPattern().isPresent()) {
                        String effRegex = "\"" + s.rawRegexPattern().get() + "\"";
                        var regexOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_REGEX);
                        rows.add(new FacetRow("#regex", effRegex, regexOrigin.origin(), invalidFacetTags.contains("#regex") ? "Invalid" : regexOrigin.status()));
                    }
                    if (s.lowerBound().isPresent()) {
                        var lb = s.lowerBound().get();
                        String tag = lb.kind() == StvnSchema.BoundKind.INCLUSIVE ? "#minIncl" : "#minExcl";
                        rows.add(new FacetRow(tag, "\"" + lb.value() + "\"", "Declared", invalidFacetTags.contains(tag) ? "Invalid" : "Active"));
                    }
                    if (s.upperBound().isPresent()) {
                        var ub = s.upperBound().get();
                        String tag = ub.kind() == StvnSchema.BoundKind.INCLUSIVE ? "#maxIncl" : "#maxExcl";
                        rows.add(new FacetRow(tag, "\"" + ub.value() + "\"", "Declared", invalidFacetTags.contains(tag) ? "Invalid" : "Active"));
                    }
                }
                case StvnSchema.TimeEpochSchema te -> {
                    rows.add(new FacetRow("#scale", "#" + te.scale().name().toLowerCase(Locale.ROOT), "Declared", "Active"));
                    if (te.explicitMinIncl().isPresent()) {
                        String status = invalidFacetTags.contains("#minIncl") ? "Invalid" : "Active";
                        rows.add(new FacetRow("#minIncl", te.explicitMinIncl().get().toString(), "Declared", status));
                    }
                    if (te.explicitMaxExcl().isPresent()) {
                        String status = invalidFacetTags.contains("#maxExcl") ? "Invalid" : "Active";
                        rows.add(new FacetRow("#maxExcl", te.explicitMaxExcl().get().toString(), "Declared", status));
                    }
                }
                case StvnSchema.DateTimeSchema dt -> {
                    String modeTag = switch (dt.mode()) {
                        case OFFSET -> "#offset";
                        case ZONED -> "#zoned";
                        case AUDITED -> "#audited";
                    };
                    rows.add(new FacetRow(modeTag, "#TRUE", "Declared", "Active"));
                    if (dt.explicitMinInclIso().isPresent()) {
                        String status = invalidFacetTags.contains("#minIncl") ? "Invalid" : "Active";
                        rows.add(new FacetRow("#minIncl", "\"" + dt.explicitMinInclIso().get() + "\"", "Declared", status));
                    }
                    if (dt.explicitMaxExclIso().isPresent()) {
                        String status = invalidFacetTags.contains("#maxExcl") ? "Invalid" : "Active";
                        rows.add(new FacetRow("#maxExcl", "\"" + dt.explicitMaxExclIso().get() + "\"", "Declared", status));
                    }
                }
                case StvnSchema.SeqSchema sq -> {
                    String effMin = declaredMinSize != null ? declaredMinSize : sq.minSize().toString();
                    var minOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MIN_SIZE);
                    rows.add(new FacetRow("#minSize", effMin, minOrigin.origin(), invalidFacetTags.contains("#minSize") ? "Invalid" : minOrigin.status()));

                    String effMax = declaredMaxSize != null ? declaredMaxSize : sq.maxSize().toString();
                    var maxOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MAX_SIZE);
                    rows.add(new FacetRow("#maxSize", effMax, maxOrigin.origin(), invalidFacetTags.contains("#maxSize") ? "Invalid" : maxOrigin.status()));
                }
                case StvnSchema.SetSchema st -> {
                    String effMin = declaredMinSize != null ? declaredMinSize : st.minSize().toString();
                    var minOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MIN_SIZE);
                    rows.add(new FacetRow("#minSize", effMin, minOrigin.origin(), invalidFacetTags.contains("#minSize") ? "Invalid" : minOrigin.status()));

                    String effMax = declaredMaxSize != null ? declaredMaxSize : st.maxSize().toString();
                    var maxOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MAX_SIZE);
                    rows.add(new FacetRow("#maxSize", effMax, maxOrigin.origin(), invalidFacetTags.contains("#maxSize") ? "Invalid" : maxOrigin.status()));
                }
                case StvnSchema.MapSchema mp -> {
                    if (mp.invertible()) {
                        rows.add(new FacetRow("#invertible", "#TRUE", "Declared", "Active"));
                    }
                    String effMin = declaredMinSize != null ? declaredMinSize : mp.minSize().toString();
                    var minOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MIN_SIZE);
                    rows.add(new FacetRow("#minSize", effMin, minOrigin.origin(), invalidFacetTags.contains("#minSize") ? "Invalid" : minOrigin.status()));

                    String effMax = declaredMaxSize != null ? declaredMaxSize : mp.maxSize().toString();
                    var maxOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_MAX_SIZE);
                    rows.add(new FacetRow("#maxSize", effMax, maxOrigin.origin(), invalidFacetTags.contains("#maxSize") ? "Invalid" : maxOrigin.status()));
                }
                default -> {
                }
            }
        }

        // Recover declared facets stripped by core during degraded schema sanitization
        if (declaredMeta != null) {
            for (var entry : declaredMeta.getMetadataEntryList()) {
                var entryText = entry.getText().trim();
                for (var tag : List.of("#preserveIndent", "#minIncl", "#minExcl", "#maxIncl", "#maxExcl", "#size", "#regex", "#minSize", "#maxSize")) {
                    if (entryText.startsWith(tag)) {
                        boolean alreadyPresent = rows.stream().anyMatch(r -> r.tag().equals(tag));
                        if (!alreadyPresent) {
                            var parts = entryText.split("\\s+", 2);
                            var val = parts.length > 1 ? parts[1].trim() : "";
                            if ("#preserveIndent".equals(tag)) {
                                var piOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_PRESERVE_INDENT);
                                rows.add(new FacetRow(tag, val, piOrigin.origin(), piOrigin.status()));
                            } else {
                                String status = invalidFacetTags.contains(tag) ? "Invalid" : "Active";
                                rows.add(new FacetRow(tag, val, "Declared", status));
                            }
                        }
                    }
                }
            }
        }

        boolean isComposite = isCompositeOrCollectionType(baseType);
        final boolean exactFlag = isExactFloat;

        // 5. #equatable
        boolean effEquatable = irSchema != null
            ? irSchema.traits().equatable()
            : resolveExplicitTraitFromPsi(declaredMeta, StvnVocabulary.FACET_KW_EQUATABLE)
                .orElseGet(() -> resolveDefaultEquatable(baseType, exactFlag));
        OriginInfo equatableOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_EQUATABLE, effEquatable, isComposite);
        rows.add(new FacetRow("#equatable", effEquatable ? "#TRUE" : "#FALSE", equatableOrigin.origin(), equatableOrigin.status()));

        // 6. #comparable
        boolean effComparable = irSchema != null
            ? irSchema.traits().comparable()
            : resolveExplicitTraitFromPsi(declaredMeta, StvnVocabulary.FACET_KW_COMPARABLE)
                .orElseGet(() -> resolveDefaultComparable(baseType));
        OriginInfo comparableOrigin = resolveFacetOrigin(typeDef, trace, currentAlias, baseType, StvnVocabulary.FACET_NAME_COMPARABLE, effComparable, isComposite);
        rows.add(new FacetRow("#comparable", effComparable ? "#TRUE" : "#FALSE", comparableOrigin.origin(), comparableOrigin.status()));

        if (rows.isEmpty()) return null;

        // Sort rows strictly into canonical tier order
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

        // Render Non-Conforming Constituents section if equatability failed due to constituent bubbling breakdown
        if (!effEquatable && isComposite && !hasExplicitFacetInPsi(typeDef, StvnVocabulary.FACET_NAME_EQUATABLE)) {
            var nonConforming = StvnTraitViolationQuickFixProvider.extractNonConformingConstituents(typeDef);
            if (!nonConforming.isEmpty()) {
                html.append("<br/><b>Non-Conforming Constituents:</b><br/>");
                html.append("<span style=\"font-size: 0.9em; color: #").append(getViolationBadgeColorHex()).append(";\">");
                html.append("Constituent fields breaking inductive trait bubbling:</span><ul style=\"margin-top: 2px; margin-bottom: 4px; padding-left: 18px;\">");
                for (var info : nonConforming) {
                    html.append("<li>Field #").append(info.index()).append(": ");
                    html.append("<a href=\"psi_element://offset/").append(info.schema().getTextOffset()).append("\">");
                    html.append("<code>").append(info.label()).append("</code></a></li>");
                }
                html.append("</ul>");
            }
        }

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

    private static boolean isScalarPrimitiveType(String baseType) {
        return StvnVocabulary.TYPE_INT.equals(baseType)
                || StvnVocabulary.TYPE_STRING.equals(baseType)
                || StvnVocabulary.TYPE_BOOLEAN.equals(baseType)
                || StvnVocabulary.TYPE_FLOAT.equals(baseType)
                || StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)
                || StvnVocabulary.TYPE_DATE_TIME.equals(baseType);
    }

    private static String resolveTypeCategoryNoun(String baseType) {
        if (isCompositeOrCollectionType(baseType)) {
            return "composite schema";
        }
        if (isScalarPrimitiveType(baseType)) {
            return "scalar primitive";
        }
        return "type alias";
    }

    private static boolean hasFacetInLineage(TypeDefinition rootTypeDef, List<String> trace, String facetKey) {
        if (hasExplicitFacetInPsi(rootTypeDef, facetKey)) {
            return true;
        }
        var file = rootTypeDef.getContainingFile();
        if (file == null) {
            return false;
        }
        for (int i = 1; i < trace.size(); i++) {
            var alias = trace.get(i);
            if (alias.startsWith(":")) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
                var ancestorTypeDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                if (ancestorTypeDef != null && hasExplicitFacetInPsi(ancestorTypeDef, facetKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record OriginInfo(String origin, String status) {}

    private static OriginInfo resolveFacetOrigin(
            TypeDefinition rootTypeDef,
            List<String> trace,
            String currentAlias,
            String baseType,
            String facetKey
    ) {
        return resolveFacetOrigin(rootTypeDef, trace, currentAlias, baseType, facetKey, false, false);
    }

    private static OriginInfo resolveFacetOrigin(
            TypeDefinition rootTypeDef,
            List<String> trace,
            String currentAlias,
            String baseType,
            String facetKey,
            boolean effectiveValue,
            boolean isComposite
    ) {
        if (hasExplicitFacetInPsi(rootTypeDef, facetKey)) {
            String typeNameWithSigil = currentAlias.startsWith(":") ? currentAlias : (":" + currentAlias);
            return new OriginInfo("Declared on<br/><a href=\"psi_element://TypeDefinition/" + typeNameWithSigil + "\"><code>" + typeNameWithSigil + "</code></a>", "Active Override");
        }

        var file = rootTypeDef.getContainingFile();
        for (int i = 1; i < trace.size(); i++) {
            var alias = trace.get(i);
            if (file != null && alias.startsWith(":")) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
                var ancestorTypeDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                if (ancestorTypeDef != null && hasExplicitFacetInPsi(ancestorTypeDef, facetKey)) {
                    String aliasWithSigil = alias.startsWith(":") ? alias : (":" + alias);
                    return new OriginInfo("Inherited from<br/><a href=\"psi_element://TypeDefinition/" + aliasWithSigil + "\"><code>" + aliasWithSigil + "</code></a>", "Inherited");
                }
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

    private static List<String> extractNonConformingConstituents(TypeDefinition typeDef, boolean checkComparable) {
        var nonConforming = new ArrayList<String>();
        var terminal = resolveTerminalSchemaType(typeDef, new HashSet<>());
        var schemaType = (terminal != null) ? terminal : typeDef.getSchemaType();
        if (schemaType == null || schemaType.getSchemaConstructor() == null) {
            return nonConforming;
        }

        var ctor = schemaType.getSchemaConstructor();
        List<SchemaType> constituents = List.of();
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
                } else if (checkComparable && (typeName.equals(":Set") || typeName.equals(":Map"))) {
                    nonConforming.add(fieldName);
                    continue;
                } else if (typeName.equals(":Int") || typeName.equals(":String") || typeName.equals(":Boolean") || typeName.equals(":TimeEpoch") || typeName.equals(":DateTime")) {
                    continue;
                }
                if (file != null) {
                    var resolvedDef = StvnTypeReference.resolveTypeInFile(file, typeName, new HashSet<>());
                    var targetTypeDef = StvnPsiUtils.getParentTypeDefinition(resolvedDef);
                    if (targetTypeDef != null) {
                        var targetIr = StvnTypeResolver.resolveNominalIrSchema(targetTypeDef);
                        if (targetIr != null) {
                            boolean conforms = checkComparable
                                ? targetIr.traits().comparable()
                                : targetIr.traits().equatable();
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
                } else if (checkComparable && (inlineText.startsWith(":Set") || inlineText.startsWith(":Map"))) {
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
                if (isContainerLiteral(valueAncestor) && !isScalarSlotArityOneTuple(valueAncestor)) {
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
            if (isContainerLiteral((Value) element) && !isScalarSlotArityOneTuple((Value) element)) {
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
            if (isContainerLiteral(valueAncestor) && !isScalarSlotArityOneTuple(valueAncestor)) {
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

    private static @Nullable String formatDerivationLineage(@Nullable TypeDefinition typeDef, StvnEnumSubsetInfo subset) {
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
        if (element instanceof org.stvnadore.psi.NestedPackageIllegal
                || PsiTreeUtil.getParentOfType(element, org.stvnadore.psi.NestedPackageIllegal.class, false) != null) {
            return true;
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

    private static boolean isScalarSlotArityOneTuple(Value value) {
        var coll = value.getCollectionValue();
        if (coll == null || coll.getTupleLiteral() == null || coll.getTupleLiteral().getValueList().size() != 1) {
            return false;
        }
        var info = StvnTypeResolver.resolveBaseTypeInfo(value);
        var schema = info != null ? info.getSchema() : null;
        if (schema == null) {
            return false;
        }
        var baseType = resolveBaseTypeString(schema, new HashSet<>());
        return baseType != null
            && !baseType.startsWith(StvnVocabulary.TYPE_TUPLE)
            && !":Value".equals(baseType)
            && !baseType.startsWith(StvnVocabulary.TYPE_UNION)
            && !baseType.startsWith(StvnVocabulary.TYPE_EITHER)
            && !baseType.startsWith(StvnVocabulary.TYPE_OPTION);
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
        BigInteger bitWidth = StvnVocabulary.DEFAULT_INT_BIT_WIDTH;
        boolean isExact = false;
        for (var e : metaMap.getMetadataEntryList()) {
            var et = e.getText().trim();
            if (et.startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
                isUnsigned = true;
            } else if (et.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                var parts = et.split("\\s+");
                if (parts.length >= 2) {
                    var parsed = StvnPsiLiteralEvaluator.parseBigInteger(parts[1]).orElse(null);
                    if (parsed != null) {
                        bitWidth = parsed;
                    }
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
                var val = StvnPsiLiteralEvaluator.parseBigInteger(valStr).orElse(null);
                if (val != null) {
                    if (isUnsigned && val.compareTo(BigInteger.ZERO) == 0 && (baseType.endsWith(":Int") || baseType.equals(":Int"))) {
                        reason = "unsigned integer <code>{ #unsigned } :Int</code> is already bounded from below by 0.";
                    } else if (!isUnsigned
                            && (baseType.endsWith(":Int") || baseType.equals(":Int"))
                            && bitWidth.compareTo(BigInteger.ONE) >= 0
                            && bitWidth.compareTo(BigInteger.valueOf(StvnPsiLiteralEvaluator.MAX_EAGER_SHIFT_BIT_WIDTH)) <= 0) {
                        var naturalMin = BigInteger.ONE.shiftLeft(bitWidth.intValue() - 1).negate();
                        if (val.compareTo(naturalMin) == 0) {
                            reason = "<code>#size " + bitWidth + "</code> already bounds the minimum inclusive bound to " + valStr + ".";
                        }
                    }
                }
            }
        }
        // 2. Natural upper bound #maxExcl
        else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                var val = StvnPsiLiteralEvaluator.parseBigInteger(valStr).orElse(null);
                if (val != null
                        && (baseType.endsWith(":Int") || baseType.equals(":Int"))
                        && bitWidth.compareTo(BigInteger.ONE) >= 0
                        && bitWidth.compareTo(BigInteger.valueOf(StvnPsiLiteralEvaluator.MAX_EAGER_SHIFT_BIT_WIDTH)) <= 0) {
                    var naturalMax = isUnsigned
                        ? BigInteger.ONE.shiftLeft(bitWidth.intValue())
                        : BigInteger.ONE.shiftLeft(bitWidth.intValue() - 1);
                    if (val.compareTo(naturalMax) == 0) {
                        reason = "<code>#size " + bitWidth + "</code> already bounds the maximum exclusive bound to " + valStr + ".";
                    }
                }
            }
        }
        // 3. Sizing tautologies
        else if (text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(valStr).orElse(null);
                if (parsed != null && parsed.equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH) && (baseType.endsWith(":Int") || baseType.equals(":Int"))) {
                    String desc = isUnsigned ? "unsigned integer <code>{ #unsigned } :Int</code>" : "signed integer <code>:Int</code>";
                    reason = desc + " intrinsically defaults to 32-bit storage.";
                } else if (parsed != null && parsed.equals(StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH) && (baseType.endsWith(":Float") || baseType.equals(":Float")) && !isExact) {
                    reason = "continuous <code>:Float</code> intrinsically defaults to 64-bit IEEE-754 double precision.";
                }
            }
        }
        // 4. Default capacity and exact float precision ceiling (Rule W003)
        else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2) {
                var valStr = parts[1].trim();
                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(valStr).orElse(null);
                if (parsed != null && parsed.equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                    if ((baseType.endsWith(StvnVocabulary.TYPE_FLOAT) || baseType.equals(StvnVocabulary.TYPE_FLOAT)) && isExact) {
                        reason = "exact decimal float <code>{ #exact } :Float</code> intrinsically defaults to a ceiling of 16,777,216 significant decimal digits.";
                    } else if (baseType.equals(StvnVocabulary.TYPE_STRING)) {
                        reason = "<code>:String</code> intrinsically defaults to a maximum cardinality of 16,777,216.";
                    } else if (baseType.equals(StvnVocabulary.TYPE_SEQ) || baseType.startsWith(StvnVocabulary.TYPE_SEQ + "(")) {
                        reason = "<code>:Seq</code> intrinsically defaults to a maximum cardinality of 16,777,216.";
                    } else if (baseType.equals(StvnVocabulary.TYPE_SET) || baseType.startsWith(StvnVocabulary.TYPE_SET + "(")) {
                        reason = "<code>:Set</code> intrinsically defaults to a maximum cardinality of 16,777,216.";
                    } else if (baseType.equals(StvnVocabulary.TYPE_MAP) || baseType.startsWith(StvnVocabulary.TYPE_MAP + "(")) {
                        reason = "<code>:Map</code> intrinsically defaults to a maximum cardinality of 16,777,216.";
                    }
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
        // 6. Primitive and composite default traits
        else if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE) || text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            var parts = text.split("\\s+");
            if (parts.length >= 2 && ("#TRUE".equals(parts[1].trim()) || "#T".equals(parts[1].trim()))) {
                boolean isEq = text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE);
                boolean isIntrinsicallyConforming = isEq
                        ? StvnTraitViolationQuickFixProvider.isUnderlyingIntrinsicallyEquatable(typeDef)
                        : StvnTraitViolationQuickFixProvider.isUnderlyingIntrinsicallyComparable(typeDef);
                if (isIntrinsicallyConforming) {
                    String category = resolveTypeCategoryNoun(baseType);
                    reason = category + " <code>" + baseType + "</code> is intrinsically " + (isEq ? "equatable" : "comparable") + ".";
                }
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
    private @NotNull String renderRhsBaseTypeCard(
            String baseName,
            TypeDefinition enclosingDef,
            @Nullable PsiElement originalElement
    ) {
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
        var inlineSchema = originalElement != null ? PsiTreeUtil.getParentOfType(originalElement, SchemaType.class) : null;
        boolean isInlineCompositeChild = inlineSchema != null && inlineSchema != enclosingDef.getSchemaType();
        var facetsTable = isInlineCompositeChild
            ? buildInlineSchemaFacetsSummaryHtml(inlineSchema)
            : buildEffectiveFacetsTable(enclosingDef);
        if (facetsTable != null) {
            sb.append(facetsTable).append("<br/>");
        } else {
            sb.append("<b>Active Constraints:</b> <i>None (defaults active)</i><br/>");
        }
        sb.append("</div><hr/>");

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
        var activeTags = isInlineCompositeChild
            ? extractInlineSchemaFacetTags(inlineSchema, baseName)
            : extractActiveFacetTags(enclosingDef);
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

    private static @Nullable String buildInlineSchemaFacetsSummaryHtml(SchemaType inlineSchema) {
        var meta = inlineSchema.getMetadataMap();
        if (meta == null || meta.getMetadataEntryList().isEmpty()) {
            return null;
        }
        var items = new ArrayList<String>();
        for (var entry : meta.getMetadataEntryList()) {
            items.add("<code>" + escapeHtmlBody(entry.getText().trim()) + "</code>");
        }
        return "<b>Active Inline Facets:</b> " + String.join(" ", items);
    }

    private static Set<String> extractInlineSchemaFacetTags(SchemaType inlineSchema, String baseName) {
        var tags = new HashSet<String>();
        var meta = inlineSchema.getMetadataMap();
        if (meta != null) {
            for (var entry : meta.getMetadataEntryList()) {
                var parts = entry.getText().trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    tags.add(parts[0]);
                }
            }
        }
        if (!StvnMetadataRegistry.getSupportedFacets(baseName).isEmpty() && !isConstructorBaseType(baseName)) {
            tags.add(StvnVocabulary.FACET_KW_EQUATABLE);
            tags.add(StvnVocabulary.FACET_KW_COMPARABLE);
        }
        return tags;
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
        var lhsIrSchema = StvnTypeResolver.resolveNominalIrSchema(typeDef);
        var lhsExemplar = StvnExemplarValueGenerator.generateExemplar(schemaType, lhsIrSchema);
        if (lhsExemplar != null && !lhsExemplar.isBlank()) {
            sb.append("<br/><b>Exemplar Value:</b> <code>").append(escapeHtmlBody(lhsExemplar)).append("</code>");
        }
        sb.append("</div><hr/>");

        // Zone 3: Authoring Capabilities & Context
        sb.append("<div class='content'>");
        sb.append("<b>Authoring Capabilities &amp; Context:</b><br/>");
        var irSchema = StvnTypeResolver.resolveNominalIrSchema(typeDef);
        var baseType = irSchema != null
            ? StvnSchemaFormatter.formatCleanIrSchema(irSchema)
            : (schemaType != null ? StvnSchemaFormatter.formatCleanSchema(schemaType) : ":Value");
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
            var minSize = BigInteger.ZERO;
            var maxSize = StvnVocabulary.DEFAULT_MAX_SIZE;
            if (metaMap != null) {
                for (var entry : metaMap.getMetadataEntryList()) {
                    var txt = entry.getText().trim();
                    if (txt.startsWith("#minSize")) {
                        var parts = txt.split("\\s+");
                        if (parts.length >= 2) {
                            var parsed = StvnPsiLiteralEvaluator.parseBigInteger(parts[1]).orElse(null);
                            if (parsed != null) minSize = parsed;
                        }
                    } else if (txt.startsWith("#maxSize")) {
                        var parts = txt.split("\\s+");
                        if (parts.length >= 2) {
                            var parsed = StvnPsiLiteralEvaluator.parseBigInteger(parts[1]).orElse(null);
                            if (parsed != null) maxSize = parsed;
                        }
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

        Value enclosingIllegalTuple = null;
        if (isScalarSlotArityOneTuple(value)) {
            enclosingIllegalTuple = value;
        } else if (value.getParent() instanceof org.stvnadore.psi.TupleLiteral tl
                && tl.getValueList().size() == 1
                && tl.getParent() != null
                && tl.getParent().getParent() instanceof Value outerTuple
                && isScalarSlotArityOneTuple(outerTuple)) {
            enclosingIllegalTuple = outerTuple;
        }
        if (enclosingIllegalTuple != null) {
            targetValueForCompliance = enclosingIllegalTuple;
            if (enclosingIllegalTuple.getParent() instanceof org.stvnadore.psi.ExplicitOptionValue optVal
                    && optVal.getParent() instanceof Value optOuterVal) {
                var optInfo = StvnTypeResolver.resolveBaseTypeInfo(optOuterVal);
                if (optInfo != null && optInfo.getSchema() != null && optInfo.getSchema().getTypeKeyword() != null) {
                    var optAlias = optInfo.getSchema().getTypeKeyword().getText().trim();
                    var innerSchemaLabel = (schemaType != null)
                        ? StvnSchemaFormatter.formatSchema(schemaType)
                        : displayType;
                    governingSchema = optAlias + " (" + innerSchemaLabel + ")";
                }
            }
        }

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
        String badgeColor = ("Satisfied (Precision Loss)".equals(compliance.statusLabel())
                || "Satisfied (Degraded Schema)".equals(compliance.statusLabel()))
            ? getWarningBadgeColorHex()
            : (compliance.isSatisfied() ? getSatisfiedBadgeColorHex() : getViolationBadgeColorHex());
        String badgeText = compliance.statusLabel();
        sb.append("<b>Compliance Status:</b> <b style=\"color: #").append(badgeColor).append(";\">").append(badgeText).append("</b><br/>");
        sb.append(compliance.detailExplanation()).append("<br/>");

        var epochDoc = buildTimeEpochValueHoverDoc(targetValueForCompliance, displayType);
        if (epochDoc != null) {
            sb.append(epochDoc);
        }
        StvnSchema leafIrSchema = targetSchemaForCompliance != null
            ? resolveEffectiveIrSchemaForCompliance(targetValueForCompliance, targetSchemaForCompliance)
            : null;
        String exemplar = StvnExemplarValueGenerator.generateExemplar(targetSchemaForCompliance, leafIrSchema);
        if (exemplar != null && !exemplar.isBlank()) {
            sb.append("<b>Exemplar Value:</b> <code>")
              .append(escapeHtmlBody(exemplar))
              .append("</code><br/>");
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
        var rawResult = evaluateValueComplianceInternal(value, schemaType);
        if (rawResult.isSatisfied()) {
            var file = value.getContainingFile();
            var range = value.getTextRange();
            if (file != null && range != null) {
                for (var diag : StvnCompiledDocumentCache.getSnapshot(file).diagnostics()) {
                    if (diag.severity() == org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR
                            && diag.startOffset() == range.getStartOffset()
                            && diag.endOffset() == range.getEndOffset()) {
                        rawResult = new StvnValueComplianceResult(
                            false,
                            "Violated",
                            escapeHtmlBody(diag.message()),
                            rawResult.evaluatedConstraints(),
                            rawResult.validLexicalForms()
                        );
                        break;
                    }
                }
            }
        }
        if (schemaType == null || !isGoverningSchemaDegraded(value, schemaType)) {
            return rawResult;
        }
        String schemaLabel = schemaType.getText().trim();
        String degradedNote = "<b>Note:</b> Governing schema <code>" + schemaLabel
            + "</code> is degraded (contains metadata facet errors); evaluated against best-effort fallback.<br/>";
        String statusLabel = rawResult.isSatisfied() ? "Satisfied (Degraded Schema)" : "Violated (Degraded Schema)";
        return new StvnValueComplianceResult(
            rawResult.isSatisfied(),
            statusLabel,
            degradedNote + rawResult.detailExplanation(),
            rawResult.evaluatedConstraints(),
            rawResult.validLexicalForms()
        );
    }

    private static boolean isGoverningSchemaDegraded(Value value, SchemaType schemaType) {
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var file = schemaType.getContainingFile();
            if (file != null) {
                var aliasText = kw.getText().trim();
                if (StvnTypeResolver.isDegradedNominalAlias(file, aliasText)) {
                    return true;
                }
                var target = StvnTypeReference.resolveTypeInFile(file, aliasText, new HashSet<>());
                var td = StvnPsiUtils.resolveTargetTypeDefinition(target);
                if (td != null && StvnTypeResolver.resolveNominalIrSchema(td) instanceof StvnSchema.PoisonedSchema) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @NotNull StvnValueComplianceResult evaluateValueComplianceInternal(Value value, @Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return new StvnValueComplianceResult(true, "Unchecked", "No governing schema identified", Collections.emptyMap(), Collections.emptyList());
        }

        var baseType = resolveBaseTypeString(schemaType, new HashSet<>());
        if (baseType == null) baseType = ":Value";

        StvnSchema irSchema = resolveEffectiveIrSchemaForCompliance(value, schemaType);

        if (value.getCollectionValue() != null
                && value.getCollectionValue().getTupleLiteral() != null
                && !baseType.startsWith(StvnVocabulary.TYPE_TUPLE)
                && !":Value".equals(baseType)) {
            return new StvnValueComplianceResult(
                false,
                "In Violation",
                "Type mismatch: expected scalar " + escapeHtmlBody(baseType) + ", found product constructor :Tuple",
                Collections.emptyMap(),
                buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
            );
        }

        // 1. Numeric integer literal evaluation
        if (value.getIntegerLiteral() != null) {
            var rawIntText = value.getIntegerLiteral().getText().trim();
            boolean isIntOrEpoch = irSchema instanceof StvnSchema.IntSchema
                || irSchema instanceof StvnSchema.TimeEpochSchema
                || StvnVocabulary.TYPE_INT.equals(baseType)
                || StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)
                || ":Value".equals(baseType);
            if (!isIntOrEpoch) {
                return new StvnValueComplianceResult(
                    false,
                    "Violated",
                    "Type mismatch: expected " + formatExpectedSlotDescription(schemaType, irSchema, baseType)
                        + ", found :Int literal (" + escapeHtmlBody(rawIntText) + ")",
                    Collections.emptyMap(),
                    buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
                );
            }
            try {
                var text = rawIntText;
                var valBI = StvnPsiLiteralEvaluator.parseBigInteger(text).orElse(null);
                if (valBI == null) {
                    return new StvnValueComplianceResult(false, "In Violation", "Invalid integer literal representation: " + text, Collections.emptyMap(), Collections.emptyList());
                }

                boolean isUnsigned = false;
                BigInteger bitWidth = StvnVocabulary.DEFAULT_INT_BIT_WIDTH;
                BigInteger minVal = null;
                BigInteger maxVal = null;

                if (irSchema instanceof StvnSchema.IntSchema intSchema) {
                    isUnsigned = intSchema.unsigned();
                    bitWidth = intSchema.bitWidth();
                    if (intSchema.explicitMinIncl().isPresent()) {
                        minVal = intSchema.explicitMinIncl().get();
                    }
                    if (intSchema.explicitMaxExcl().isPresent()) {
                        maxVal = intSchema.explicitMaxExcl().get().subtract(BigInteger.ONE);
                    }
                } else if (irSchema instanceof StvnSchema.TimeEpochSchema teSchema) {
                    bitWidth = teSchema.scale().bitWidth();
                    if (teSchema.explicitMinIncl().isPresent()) {
                        minVal = teSchema.explicitMinIncl().get();
                    }
                    if (teSchema.explicitMaxExcl().isPresent()) {
                        maxVal = teSchema.explicitMaxExcl().get().subtract(BigInteger.ONE);
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
                            if (p.length >= 2) {
                                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                if (parsed != null) bitWidth = parsed;
                            }
                        }
                        if (t.startsWith("#minIncl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) {
                                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                if (parsed != null) minVal = parsed;
                            }
                        }
                        if (t.startsWith("#maxExcl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) {
                                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                if (parsed != null) maxVal = parsed.subtract(BigInteger.ONE);
                            }
                        }
                        if (t.startsWith("#minExcl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) {
                                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                if (parsed != null) {
                                    var excl = parsed.add(BigInteger.ONE);
                                    minVal = minVal != null ? minVal.max(excl) : excl;
                                }
                            }
                        }
                        if (t.startsWith("#maxIncl")) {
                            var p = t.split("\\s+");
                            if (p.length >= 2) {
                                var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                if (parsed != null) {
                                    maxVal = maxVal != null ? maxVal.min(parsed) : parsed;
                                }
                            }
                        }
                    }
                }

                boolean satisfied;
                String explanation;
                if (bitWidth.compareTo(BigInteger.ONE) >= 0
                        && bitWidth.compareTo(BigInteger.valueOf(StvnPsiLiteralEvaluator.MAX_EAGER_SHIFT_BIT_WIDTH)) <= 0) {
                    int bw = bitWidth.intValue();
                    var bitMin = isUnsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bw - 1).negate();
                    var bitMax = isUnsigned ? BigInteger.ONE.shiftLeft(bw).subtract(BigInteger.ONE) : BigInteger.ONE.shiftLeft(bw - 1).subtract(BigInteger.ONE);

                    var effMin = minVal != null ? minVal.max(bitMin) : bitMin;
                    var effMax = maxVal != null ? maxVal.min(bitMax) : bitMax;

                    satisfied = valBI.compareTo(effMin) >= 0 && valBI.compareTo(effMax) <= 0;
                    if (satisfied) {
                        explanation = String.format("Value %s conforms to interval [%s, %s) and %s-bit representation",
                            valBI, effMin, effMax.add(BigInteger.ONE), bitWidth);
                    } else if (valBI.compareTo(effMin) < 0) {
                        explanation = String.format("Value %s is below minimum allowable bound %s", valBI, effMin);
                    } else {
                        explanation = String.format("Value %s exceeds upper bound %s", valBI, effMax);
                    }
                } else {
                    BigInteger bitLen = BigInteger.valueOf(valBI.bitLength());
                    boolean fitsWidth = isUnsigned
                        ? (valBI.signum() >= 0 && bitLen.compareTo(bitWidth) <= 0)
                        : (bitWidth.compareTo(BigInteger.ONE) >= 0 && bitLen.compareTo(bitWidth.subtract(BigInteger.ONE)) <= 0);
                    boolean fitsMin = minVal == null || valBI.compareTo(minVal) >= 0;
                    boolean fitsMax = maxVal == null || valBI.compareTo(maxVal) <= 0;
                    satisfied = fitsWidth && fitsMin && fitsMax;
                    if (satisfied) {
                        explanation = String.format("Value %s conforms to %s-bit representation", valBI, bitWidth);
                    } else if (!fitsMin && minVal != null) {
                        explanation = String.format("Value %s is below minimum allowable bound %s", valBI, minVal);
                    } else if (!fitsMax && maxVal != null) {
                        explanation = String.format("Value %s exceeds upper bound %s", valBI, maxVal);
                    } else {
                        explanation = String.format("Value %s exceeds %s-bit representation capacity", valBI, bitWidth);
                    }
                }

                int padBits = (bitWidth.compareTo(BigInteger.ONE) >= 0 && bitWidth.compareTo(BigInteger.valueOf(128)) <= 0)
                    ? bitWidth.intValue()
                    : 0;
                int hexDigits = padBits > 0 ? Math.max(1, (padBits + 3) / 4) : 1;
                String hexStr = (valBI.compareTo(BigInteger.ZERO) >= 0 && padBits > 0)
                    ? String.format("%0" + hexDigits + "X", valBI)
                    : valBI.toString(16).toUpperCase(Locale.ROOT);
                String binStr = (valBI.compareTo(BigInteger.ZERO) >= 0 && padBits > 0)
                    ? String.format("%" + padBits + "s", valBI.toString(2)).replace(' ', '0')
                    : valBI.toString(2);

                var forms = new ArrayList<String>();
                forms.add("Decimal: <code>" + valBI.toString(10) + "</code>");
                forms.add("Hex: <code>0x" + hexStr + "</code>");
                forms.add("Binary: <code>0b" + binStr + "</code>");
                forms.add("Octal: <code>0o" + valBI.toString(8) + "</code>");

                if (satisfied && rawIntText.contains("_")) {
                    satisfied = false;
                    explanation = "STVN Syntax Error: Underscore separators are prohibited in numeric literals: '"
                        + escapeHtmlBody(rawIntText) + "'";
                }
                return new StvnValueComplianceResult(satisfied, satisfied ? "Satisfied" : "In Violation", explanation, Collections.emptyMap(), forms);
            } catch (Exception e) {
                return new StvnValueComplianceResult(false, "In Violation", "Error evaluating integer constraints", Collections.emptyMap(), Collections.emptyList());
            }
        }

        // 2. DateTime & String literal evaluation
        if (value.getStringLiteral() != null) {
            if (irSchema instanceof StvnSchema.DateTimeSchema
                    || StvnVocabulary.TYPE_DATE_TIME.equals(baseType)) {
                return evaluateDateTimeLiteralCompliance(value, schemaType, irSchema);
            }

            var rawText = value.getText();
            boolean isStringSchema = irSchema instanceof StvnSchema.StringSchema
                || StvnVocabulary.TYPE_STRING.equals(baseType)
                || ":Value".equals(baseType);
            if (!isStringSchema) {
                return new StvnValueComplianceResult(
                    false,
                    "Violated",
                    "Type mismatch: expected " + formatExpectedSlotDescription(schemaType, irSchema, baseType)
                        + ", found :String literal (" + escapeHtmlBody(rawText.trim()) + ")",
                    Collections.emptyMap(),
                    buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
                );
            }

            int len = StvnPsiLiteralEvaluator.evaluateStringContentLength(value.getStringLiteral());

            var minSize = BigInteger.ZERO;
            var maxSize = StvnVocabulary.DEFAULT_MAX_SIZE;
            String regex = null;

            if (irSchema instanceof StvnSchema.StringSchema strSchema) {
                minSize = strSchema.minSize();
                maxSize = strSchema.maxSize();
                regex = strSchema.rawRegexPattern().orElse(null);
            }

            var metaMap = schemaType.getMetadataMap();
            if (metaMap != null) {
                for (var entry : metaMap.getMetadataEntryList()) {
                    var t = entry.getText().trim();
                    if (t.startsWith("#minSize")) {
                        var p = t.split("\\s+");
                        if (p.length >= 2) {
                            var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                            if (parsed != null) minSize = parsed;
                        }
                    }
                    if (t.startsWith("#maxSize")) {
                        var p = t.split("\\s+");
                        if (p.length >= 2) {
                            var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                            if (parsed != null) maxSize = parsed;
                        }
                    }
                    if (t.startsWith("#regex")) {
                        var p = t.split("\\s+", 2);
                        if (p.length >= 2) regex = p[1].replaceAll("^\"|\"$", "");
                    }
                }
            }

            var lenBI = BigInteger.valueOf(len);
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
            var rawFloatText = value.getFloatLiteral().getText().trim();
            boolean isFloatSchema = irSchema instanceof StvnSchema.FloatSchema
                || StvnVocabulary.TYPE_FLOAT.equals(baseType)
                || ":Value".equals(baseType);
            if (!isFloatSchema) {
                return new StvnValueComplianceResult(
                    false,
                    "Violated",
                    "Type mismatch: expected " + formatExpectedSlotDescription(schemaType, irSchema, baseType)
                        + ", found :Float literal (" + escapeHtmlBody(rawFloatText) + ")",
                    Collections.emptyMap(),
                    buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
                );
            }
            try {
                var text = rawFloatText;
                var valBD = StvnPsiLiteralEvaluator.parseBigDecimal(text).orElse(null);
                if (valBD == null) {
                    if (StvnVocabulary.LITERAL_NAN.equals(text)
                            || StvnVocabulary.LITERAL_POS_INFINITY.equals(text)
                            || StvnVocabulary.LITERAL_NEG_INFINITY.equals(text)) {
                        return new StvnValueComplianceResult(true, "Satisfied", "IEEE-754 special floating-point literal " + text, Collections.emptyMap(), List.of("Special Float: <code>" + text + "</code>"));
                    }
                    return new StvnValueComplianceResult(false, "In Violation", "Invalid float literal representation: " + text, Collections.emptyMap(), Collections.emptyList());
                }

                boolean isExact = irSchema instanceof StvnSchema.FloatSchema fs
                    && fs.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL;
                if (!isExact && schemaType.getMetadataMap() != null) {
                    for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                        if (entry.getText().trim().startsWith("#exact")) isExact = true;
                    }
                }

                if (isExact) {
                    int precision = valBD.precision();
                    BigInteger maxSize = (irSchema instanceof StvnSchema.FloatSchema fs)
                        ? fs.exactMaxDigits()
                        : StvnVocabulary.DEFAULT_MAX_SIZE;
                    if (schemaType.getMetadataMap() != null) {
                        for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                            var t = entry.getText().trim();
                            if (t.startsWith("#maxSize")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                    if (parsed != null) maxSize = parsed;
                                }
                            }
                        }
                    }

                    StvnSchema.ContinuousBound<BigDecimal> lowerBound = (irSchema instanceof StvnSchema.FloatSchema fs)
                        ? fs.lowerBound().orElse(null)
                        : null;
                    StvnSchema.ContinuousBound<BigDecimal> upperBound = (irSchema instanceof StvnSchema.FloatSchema fs)
                        ? fs.upperBound().orElse(null)
                        : null;
                    if (schemaType.getMetadataMap() != null) {
                        for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                            var t = entry.getText().trim();
                            if (t.startsWith("#minIncl")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    var parsed = StvnPsiLiteralEvaluator.parseBigDecimal(p[1]).orElse(null);
                                    if (parsed != null) lowerBound = new StvnSchema.ContinuousBound<>(parsed, StvnSchema.BoundKind.INCLUSIVE);
                                }
                            } else if (t.startsWith("#minExcl")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    var parsed = StvnPsiLiteralEvaluator.parseBigDecimal(p[1]).orElse(null);
                                    if (parsed != null) lowerBound = new StvnSchema.ContinuousBound<>(parsed, StvnSchema.BoundKind.EXCLUSIVE);
                                }
                            } else if (t.startsWith("#maxIncl")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    var parsed = StvnPsiLiteralEvaluator.parseBigDecimal(p[1]).orElse(null);
                                    if (parsed != null) upperBound = new StvnSchema.ContinuousBound<>(parsed, StvnSchema.BoundKind.INCLUSIVE);
                                }
                            } else if (t.startsWith("#maxExcl")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    var parsed = StvnPsiLiteralEvaluator.parseBigDecimal(p[1]).orElse(null);
                                    if (parsed != null) upperBound = new StvnSchema.ContinuousBound<>(parsed, StvnSchema.BoundKind.EXCLUSIVE);
                                }
                            }
                        }
                    }

                    boolean precOk = BigInteger.valueOf(precision).compareTo(maxSize) <= 0;
                    boolean minOk = lowerBound == null
                        || (lowerBound.kind() == StvnSchema.BoundKind.INCLUSIVE
                            ? valBD.compareTo(lowerBound.value()) >= 0
                            : valBD.compareTo(lowerBound.value()) > 0);
                    boolean maxOk = upperBound == null
                        || (upperBound.kind() == StvnSchema.BoundKind.INCLUSIVE
                            ? valBD.compareTo(upperBound.value()) <= 0
                            : valBD.compareTo(upperBound.value()) < 0);
                    boolean satisfied = precOk && minOk && maxOk;

                    String explanation;
                    if (!precOk) {
                        explanation = String.format("Mantissa precision %d exceeds maximum allowable significant digits %s", precision, maxSize);
                    } else if (!minOk) {
                        explanation = String.format("Value %s is below minimum allowable bound %s", valBD.toPlainString(), lowerBound.value().toPlainString());
                    } else if (!maxOk) {
                        explanation = String.format("Value %s meets or exceeds upper bound %s", valBD.toPlainString(), upperBound.value().toPlainString());
                    } else {
                        explanation = String.format("Value %s conforms to discrete decimal interval and mantissa precision (%d digits <= %s)",
                            valBD.toPlainString(), precision, maxSize);
                    }

                    var forms = new ArrayList<String>();
                    forms.add("Exact Decimal: <code>" + valBD.toPlainString() + "</code>");
                    forms.add("Scientific: <code>" + valBD.toString() + "</code>");
                    forms.add("Precision (Digits): <code>" + precision + "</code>");
                    forms.add("Scale: <code>" + valBD.scale() + "</code>");
                    if (satisfied && rawFloatText.contains("_")) {
                        satisfied = false;
                        explanation = "STVN Syntax Error: Underscore separators are prohibited in numeric literals: '"
                            + escapeHtmlBody(rawFloatText) + "'";
                    }
                    return new StvnValueComplianceResult(satisfied, satisfied ? "Satisfied" : "In Violation", explanation, Collections.emptyMap(), forms);
                } else {
                    int bitWidth = (irSchema instanceof StvnSchema.FloatSchema fs
                            && fs.representation() == StvnSchema.FloatRepresentation.IEEE_32)
                        ? 32
                        : 64;
                    if (schemaType.getMetadataMap() != null) {
                        for (var entry : schemaType.getMetadataMap().getMetadataEntryList()) {
                            var t = entry.getText().trim();
                            if (t.startsWith("#size")) {
                                var p = t.split("\\s+");
                                if (p.length >= 2) {
                                    var parsed = StvnPsiLiteralEvaluator.parseBigInteger(p[1]).orElse(null);
                                    if (parsed != null && parsed.equals(BigInteger.valueOf(32))) {
                                        bitWidth = 32;
                                    } else if (parsed != null && parsed.equals(BigInteger.valueOf(64))) {
                                        bitWidth = 64;
                                    }
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
                    } else if (rawFloatText.contains("_")) {
                        capOk = false;
                        statusLabel = "In Violation";
                        explanation = "STVN Syntax Error: Underscore separators are prohibited in numeric literals: '"
                            + escapeHtmlBody(rawFloatText) + "'";
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
                return new StvnValueComplianceResult(false, "In Violation", "Error evaluating float constraints", Collections.emptyMap(), Collections.emptyList());
            }
        }

        // 4. Boolean literal evaluation
        if (value.getBooleanValue() != null) {
            var rawBoolText = value.getBooleanValue().getText().trim();
            boolean isBoolSchema = irSchema instanceof StvnSchema.BooleanSchema
                || StvnVocabulary.TYPE_BOOLEAN.equals(baseType)
                || ":Value".equals(baseType);
            if (!isBoolSchema) {
                return new StvnValueComplianceResult(
                    false,
                    "Violated",
                    "Type mismatch: expected " + formatExpectedSlotDescription(schemaType, irSchema, baseType)
                        + ", found :Boolean literal (" + escapeHtmlBody(rawBoolText) + ")",
                    Collections.emptyMap(),
                    buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
                );
            }
            return new StvnValueComplianceResult(
                true,
                "Satisfied",
                "Boolean literal <code>" + escapeHtmlBody(rawBoolText) + "</code> conforms to <code>:Boolean</code>",
                Collections.emptyMap(),
                buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
            );
        }

        // 5. Value keyword / Enum variant evaluation
        if (value.getValueKeyword() != null) {
            var rawKwText = value.getValueKeyword().getText().trim();
            if (irSchema instanceof StvnSchema.EnumSubsetSchema subsetSchema) {
                boolean allowed = subsetSchema.activeVariants().contains(rawKwText);
                return new StvnValueComplianceResult(
                    allowed,
                    allowed ? "Satisfied" : "Violated",
                    allowed
                        ? "Variant <code>" + escapeHtmlBody(rawKwText) + "</code> is permitted in active enum subset"
                        : "Variant <code>" + escapeHtmlBody(rawKwText) + "</code> is excluded from active enum subset",
                    Collections.emptyMap(),
                    buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
                );
            }
            if (irSchema instanceof StvnSchema.EnumSchema enumSchema) {
                boolean allowed = enumSchema.rootVariants().contains(rawKwText);
                return new StvnValueComplianceResult(
                    allowed,
                    allowed ? "Satisfied" : "Violated",
                    allowed
                        ? "Variant <code>" + escapeHtmlBody(rawKwText) + "</code> is a declared member of governing <code>:Enum</code>"
                        : "Variant <code>" + escapeHtmlBody(rawKwText) + "</code> is not a declared member of governing <code>:Enum</code>",
                    Collections.emptyMap(),
                    buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
                );
            }
        }

        return new StvnValueComplianceResult(
            true,
            "Satisfied",
            "Payload value conforms to schema shape",
            Collections.emptyMap(),
            buildSchemaValidLexicalForms(schemaType, irSchema, baseType)
        );
    }

    private static String formatExpectedSlotDescription(
        SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        String baseType
    ) {
        String formattedSchema = StvnTypeResolver.formatSchemaType(schemaType);
        if (formattedSchema.isEmpty()) {
            formattedSchema = baseType;
        }
        if (irSchema instanceof StvnSchema.TimeEpochSchema
                || StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) {
            return escapeHtmlBody(formattedSchema) + " integer tick literal";
        }
        return escapeHtmlBody(formattedSchema);
    }

    private static List<String> buildSchemaValidLexicalForms(
        SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        String baseType
    ) {
        if (irSchema instanceof StvnSchema.TimeEpochSchema
                || irSchema instanceof StvnSchema.IntSchema
                || StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)
                || StvnVocabulary.TYPE_INT.equals(baseType)) {
            return List.of(
                "Decimal: <code>0</code>, <code>42</code>, <code>1_000_000</code>",
                "Hexadecimal: <code>0x0</code>, <code>0x2A</code>",
                "Binary: <code>0b0</code>, <code>0b101010</code>",
                "Octal: <code>0o0</code>, <code>0o52</code>"
            );
        }
        if (irSchema instanceof StvnSchema.DateTimeSchema
                || StvnVocabulary.TYPE_DATE_TIME.equals(baseType)) {
            return List.of("Simple Quoted: <code>\"...\"</code> (ISO-8601 String)");
        }
        if (irSchema instanceof StvnSchema.StringSchema
                || StvnVocabulary.TYPE_STRING.equals(baseType)) {
            return List.of(
                "Simple Quoted: <code>\"...\"</code>",
                "Verbatim Fenced: <code>\"\"\"[TAG]\\n...\\n[TAG]\"\"\"</code>"
            );
        }
        if (irSchema instanceof StvnSchema.FloatSchema
                || StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
            return List.of(
                "Decimal: <code>0.0</code>, <code>3.14</code>",
                "Scientific: <code>1.0e-5</code>, <code>6.022E23</code>"
            );
        }
        if (irSchema instanceof StvnSchema.BooleanSchema
                || StvnVocabulary.TYPE_BOOLEAN.equals(baseType)) {
            return List.of(
                "Canonical: <code>#TRUE</code>, <code>#FALSE</code>",
                "Short Form: <code>#T</code>, <code>#F</code>"
            );
        }
        if (irSchema instanceof StvnSchema.EnumSchema
                || irSchema instanceof StvnSchema.EnumSubsetSchema
                || StvnVocabulary.TYPE_ENUM.equals(baseType)) {
            return List.of("Enum Variant Tag: <code>#VARIANT</code>");
        }
        return List.of("Standard STVN literal");
    }

    /**
     * Resolves the exact leaf pure-IR {@link StvnSchema} governing {@code value} and {@code schemaType}
     * without climbing from inline composite child schemas to enclosing {@link TypeDefinition} parents.
     */
    private static @Nullable StvnSchema resolveEffectiveIrSchemaForCompliance(Value value, SchemaType schemaType) {
        var coreVal = StvnTypeResolver.resolveCoreValue(value);
        if (coreVal != null) {
            var leafSchema = unwrapTerminalValueSchema(coreVal, schemaType);
            var cleanLeaf = unwrapPoisonedSchema(leafSchema);
            if (cleanLeaf != null && !(cleanLeaf instanceof StvnSchema.TupleSchema
                    || cleanLeaf instanceof StvnSchema.SeqSchema
                    || cleanLeaf instanceof StvnSchema.SetSchema
                    || cleanLeaf instanceof StvnSchema.MapSchema)) {
                return cleanLeaf;
            }
        }

        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var file = schemaType.getContainingFile();
            if (file != null) {
                var target = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                var td = StvnPsiUtils.resolveTargetTypeDefinition(target);
                if (td != null) {
                    return unwrapPoisonedSchema(StvnTypeResolver.resolveNominalIrSchema(td));
                }
            }
        }

        var parentDef = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
        if (parentDef != null) {
            if (parentDef.getSchemaType() == schemaType) {
                return unwrapPoisonedSchema(StvnTypeResolver.resolveNominalIrSchema(parentDef));
            }
            var rootIr = unwrapPoisonedSchema(StvnTypeResolver.resolveNominalIrSchema(parentDef));
            var rootPsi = parentDef.getSchemaType();
            if (rootIr != null && rootPsi != null) {
                var descended = descendChildIrSchema(rootPsi, rootIr, schemaType);
                if (descended != null) {
                    return unwrapPoisonedSchema(descended);
                }
            }
        }

        var typeEntry = PsiTreeUtil.getParentOfType(schemaType, TypeEntry.class);
        if (typeEntry != null && typeEntry.getSchemaType() != null) {
            var file = schemaType.getContainingFile();
            if (file != null) {
                var rootTypeOpt = StvnCompiledDocumentCache.getSnapshot(file).rootType();
                if (rootTypeOpt.isPresent()) {
                    var rootIr = unwrapPoisonedSchema(rootTypeOpt.get());
                    if (rootIr != null) {
                        if (typeEntry.getSchemaType() == schemaType) {
                            return rootIr;
                        }
                        var descended = descendChildIrSchema(typeEntry.getSchemaType(), rootIr, schemaType);
                        if (descended != null) {
                            return unwrapPoisonedSchema(descended);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable StvnSchema unwrapPoisonedSchema(@Nullable StvnSchema raw) {
        if (raw instanceof StvnSchema.PoisonedSchema p) {
            return p.bestEffortFallback().orElse(null);
        }
        return raw;
    }

    private static @Nullable StvnSchema unwrapTerminalValueSchema(StvnValue rootVal, SchemaType targetSchemaType) {
        var ctor = targetSchemaType.getSchemaConstructor();
        boolean targetIsSum = ctor != null && ctor.getSumType() != null && ctor.getSumType().getEnumDef() == null;
        if (targetIsSum) {
            return rootVal.schema();
        }
        StvnValue curr = rootVal;
        while (true) {
            if (curr instanceof StvnValue.StvnOption opt && opt.value().isPresent()) {
                curr = opt.value().get();
            } else if (curr instanceof StvnValue.StvnEither either) {
                curr = either.value();
            } else if (curr instanceof StvnValue.StvnUnion union) {
                curr = union.value();
            } else {
                return curr.schema();
            }
        }
    }

    private static @Nullable StvnSchema descendChildIrSchema(
            SchemaType rootPsi,
            StvnSchema rootIr,
            SchemaType targetChildPsi
    ) {
        var steps = new ArrayDeque<Integer>();
        PsiElement curr = targetChildPsi;
        while (curr != null && curr != rootPsi) {
            var parent = curr.getParent();
            if (parent instanceof ProductType pt && curr instanceof SchemaType st) {
                int idx = pt.getSchemaTypeList().indexOf(st);
                if (idx < 0) return null;
                steps.push(idx);
                curr = PsiTreeUtil.getParentOfType(pt, SchemaType.class);
            } else if (parent instanceof CollectionType ct && curr instanceof SchemaType st) {
                int idx = ct.getSchemaTypeList().indexOf(st);
                if (idx < 0) return null;
                steps.push(idx);
                curr = PsiTreeUtil.getParentOfType(ct, SchemaType.class);
            } else if (parent instanceof SumType stSum && curr instanceof SchemaType st) {
                var branches = PsiTreeUtil.getChildrenOfTypeAsList(stSum, SchemaType.class);
                int idx = branches.indexOf(st);
                if (idx < 0) return null;
                steps.push(idx);
                curr = PsiTreeUtil.getParentOfType(stSum, SchemaType.class);
            } else {
                curr = parent;
            }
        }
        if (curr != rootPsi) {
            return null;
        }
        StvnSchema active = rootIr;
        while (!steps.isEmpty() && active != null) {
            int idx = steps.pop();
            active = unwrapPoisonedSchema(active);
            active = switch (active) {
                case StvnSchema.TupleSchema ts -> (idx < ts.fieldSchemas().size()) ? ts.fieldSchemas().get(idx) : null;
                case StvnSchema.SeqSchema sq -> (idx == 0) ? sq.elementSchema() : null;
                case StvnSchema.SetSchema st -> (idx == 0) ? st.elementSchema() : null;
                case StvnSchema.MapSchema mp -> (idx == 0) ? mp.keySchema() : (idx == 1 ? mp.valueSchema() : null);
                case StvnSchema.OptionSchema op -> (idx == 0) ? op.valueSchema() : null;
                case StvnSchema.EitherSchema ei -> (idx == 0) ? ei.leftSchema() : (idx == 1 ? ei.rightSchema() : null);
                case StvnSchema.UnionSchema un -> (idx < un.branchSchemas().size()) ? un.branchSchemas().get(idx) : null;
                case null, default -> null;
            };
        }
        return active;
    }

    /**
     * Evaluates a {@code :DateTime} string literal against Core's unified tripartite validator
     * and optional {@code [#minIncl, #maxExcl)} instant interval bounds.
     */
    private static StvnValueComplianceResult evaluateDateTimeLiteralCompliance(
            Value value,
            SchemaType schemaType,
            @Nullable StvnSchema irSchema
    ) {
        var forms = List.of("Simple Quoted: <code>\"...\"</code> (ISO-8601 String)");
        var strLit = value.getStringLiteral();
        var rawText = strLit != null ? strLit.getText().trim() : value.getText().trim();

        StvnSchema.DateTimeMode mode = null;
        String minInclIso = null;
        String maxExclIso = null;

        if (irSchema instanceof StvnSchema.DateTimeSchema dtSchema) {
            mode = dtSchema.mode();
            minInclIso = dtSchema.explicitMinInclIso().orElse(null);
            maxExclIso = dtSchema.explicitMaxExclIso().orElse(null);
        }

        if (mode == null) {
            mode = extractDateTimeModeFromPsiChain(schemaType, new HashSet<>());
        }
        var localMin = extractStringMetadataBound(schemaType, StvnVocabulary.FACET_KW_MIN_INCL, new HashSet<>());
        var localMax = extractStringMetadataBound(schemaType, StvnVocabulary.FACET_KW_MAX_EXCL, new HashSet<>());
        if (minInclIso == null) minInclIso = localMin;
        if (maxExclIso == null) maxExclIso = localMax;

        if (mode == null) {
            return new StvnValueComplianceResult(
                false,
                "Violated",
                "Temporal type <code>" + StvnVocabulary.TYPE_DATE_TIME + "</code> requires an explicit mode facet: <code>"
                    + StvnVocabulary.FACET_KW_OFFSET + "</code>, <code>"
                    + StvnVocabulary.FACET_KW_ZONED + "</code>, or <code>"
                    + StvnVocabulary.FACET_KW_AUDITED + "</code>",
                Collections.emptyMap(),
                forms
            );
        }

        var failureOpt = StvnCompiler.validateDateTimeLiteral(rawText, mode);
        if (failureOpt.isPresent()) {
            return new StvnValueComplianceResult(
                false,
                "Violated",
                escapeHtmlBody(failureOpt.get().message()),
                Collections.emptyMap(),
                forms
            );
        }

        String cleanIso = stripSimpleQuotes(rawText);
        if (minInclIso != null || maxExclIso != null) {
            Instant actualInst = parseValidatedDateTimeInstant(cleanIso, mode);
            if (actualInst != null) {
                if (minInclIso != null) {
                    Instant minInst = parseValidatedDateTimeInstant(minInclIso, mode);
                    if (minInst != null && actualInst.isBefore(minInst)) {
                        String rangeDesc = "[" + minInclIso + ", " + (maxExclIso != null ? maxExclIso : "+infinity") + ")";
                        return new StvnValueComplianceResult(
                            false,
                            "Violated",
                            escapeHtmlBody("Timestamp '" + cleanIso + "' is below minimum inclusive bound '" + minInclIso + "' for interval " + rangeDesc),
                            Collections.emptyMap(),
                            forms
                        );
                    }
                }
                if (maxExclIso != null) {
                    Instant maxInst = parseValidatedDateTimeInstant(maxExclIso, mode);
                    if (maxInst != null && !actualInst.isBefore(maxInst)) {
                        String rangeDesc = "[" + (minInclIso != null ? minInclIso : "-infinity") + ", " + maxExclIso + ")";
                        return new StvnValueComplianceResult(
                            false,
                            "Violated",
                            escapeHtmlBody("Timestamp '" + cleanIso + "' meets or exceeds exclusive upper bound '" + maxExclIso + "' for interval " + rangeDesc),
                            Collections.emptyMap(),
                            forms
                        );
                    }
                }
            }
        }

        String modeClause = switch (mode) {
            case OFFSET -> "{ " + StvnVocabulary.FACET_KW_OFFSET + " } " + StvnVocabulary.TYPE_DATE_TIME + " (YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm))";
            case ZONED -> "{ " + StvnVocabulary.FACET_KW_ZONED + " } " + StvnVocabulary.TYPE_DATE_TIME + " (YYYY-MM-DDTHH:mm:ss[.fraction][Region/City])";
            case AUDITED -> "{ " + StvnVocabulary.FACET_KW_AUDITED + " } " + StvnVocabulary.TYPE_DATE_TIME + " (YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)[Region/City])";
        };
        String explanation = (minInclIso != null || maxExclIso != null)
            ? "Timestamp conforms to <code>" + modeClause + "</code> and interval <code>["
                + (minInclIso != null ? escapeHtmlBody(minInclIso) : "-infinity") + ", "
                + (maxExclIso != null ? escapeHtmlBody(maxExclIso) : "+infinity") + ")</code>"
            : "Timestamp conforms to <code>" + modeClause + "</code>";

        return new StvnValueComplianceResult(true, "Satisfied", explanation, Collections.emptyMap(), forms);
    }

    private static StvnSchema.@Nullable DateTimeMode extractDateTimeModeFromPsiChain(
            @Nullable SchemaType schemaType,
            Set<String> visited
    ) {
        if (schemaType == null) return null;
        var flag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(schemaType);
        if (StvnVocabulary.FACET_KW_OFFSET.equals(flag)) return StvnSchema.DateTimeMode.OFFSET;
        if (StvnVocabulary.FACET_KW_ZONED.equals(flag)) return StvnSchema.DateTimeMode.ZONED;
        if (StvnVocabulary.FACET_KW_AUDITED.equals(flag)) return StvnSchema.DateTimeMode.AUDITED;

        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var alias = kw.getText().trim();
            if (visited.add(alias)) {
                var file = schemaType.getContainingFile();
                if (file != null) {
                    var target = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
                    var td = StvnPsiUtils.resolveTargetTypeDefinition(target);
                    if (td != null && td.getSchemaType() != null) {
                        return extractDateTimeModeFromPsiChain(td.getSchemaType(), visited);
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String extractStringMetadataBound(
            @Nullable SchemaType schemaType,
            String facetKw,
            Set<String> visited
    ) {
        if (schemaType == null) return null;
        var meta = schemaType.getMetadataMap();
        if (meta != null) {
            for (var entry : meta.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.startsWith(facetKw + " ") || text.startsWith(facetKw + "\"")) {
                    var rest = text.substring(facetKw.length()).trim();
                    if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
                        return stripSimpleQuotes(rest);
                    }
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var alias = kw.getText().trim();
            if (visited.add(alias)) {
                var file = schemaType.getContainingFile();
                if (file != null) {
                    var target = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
                    var td = StvnPsiUtils.resolveTargetTypeDefinition(target);
                    if (td != null && td.getSchemaType() != null) {
                        return extractStringMetadataBound(td.getSchemaType(), facetKw, visited);
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable Instant parseValidatedDateTimeInstant(String iso, StvnSchema.DateTimeMode mode) {
        try {
            int bracketIdx = iso.indexOf('[');
            if (bracketIdx < 0) {
                return OffsetDateTime.parse(iso).toInstant();
            }
            if (mode == StvnSchema.DateTimeMode.AUDITED) {
                return ZonedDateTime.parse(iso).toInstant();
            }
            String localPart = iso.substring(0, bracketIdx);
            String zonePart = iso.substring(bracketIdx + 1, iso.length() - 1);
            return LocalDateTime.parse(localPart)
                .atZone(ZoneId.of(zonePart))
                .withEarlierOffsetAtOverlap()
                .toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String stripSimpleQuotes(String raw) {
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"") && !raw.startsWith("\"\"\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String escapeHtmlBody(String raw) {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /**
     * Extracts full structural resolution trace from root down to primitive representations,
     * strictly enforcing a depth ceiling ({@code <= 16}) and cycle detection to eliminate recursion risks.
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
        collectDeclaredFacetTagsFromTypeDef(typeDef, tags);

        var keyword = typeDef.getTypeKeyword();
        var trace = (keyword != null) ? StvnTypeReference.extractResolutionTrace(keyword) : List.<String>of();
        var file = typeDef.getContainingFile();
        if (file != null) {
            for (int i = 1; i < trace.size(); i++) {
                var alias = trace.get(i);
                if (alias.startsWith(":")) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
                    var ancestorTypeDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                    if (ancestorTypeDef != null) {
                        collectDeclaredFacetTagsFromTypeDef(ancestorTypeDef, tags);
                    }
                }
            }
        }

        var rawIr = StvnTypeResolver.resolveNominalIrSchema(typeDef);
        var irSchema = (rawIr instanceof StvnSchema.PoisonedSchema p)
            ? p.bestEffortFallback().orElse(null)
            : rawIr;
        var baseType = irSchema != null ? StvnSchemaFormatter.formatCleanIrSchema(irSchema) : null;
        if (baseType == null && typeDef.getSchemaType() != null) {
            baseType = StvnTypeResolver.formatSchemaType(typeDef.getSchemaType());
        }
        boolean isCtor = isConstructorBaseType(baseType);

        if (irSchema != null) {
            switch (irSchema) {
                case StvnSchema.IntSchema i -> {
                    if (i.unsigned()) tags.add(StvnVocabulary.FACET_KW_UNSIGNED);
                    if (!i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) tags.add(StvnVocabulary.FACET_KW_SIZE);
                    if (i.explicitMinIncl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MIN_INCL);
                    if (i.explicitMaxExcl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MAX_EXCL);
                }
                case StvnSchema.FloatSchema f -> {
                    if (f.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
                        tags.add(StvnVocabulary.FACET_KW_EXACT);
                        if (!f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                            tags.add(StvnVocabulary.FACET_KW_MAX_SIZE);
                        }
                    } else if (f.representation() == StvnSchema.FloatRepresentation.IEEE_32) {
                        tags.add(StvnVocabulary.FACET_KW_SIZE);
                    }
                    f.lowerBound().ifPresent(lb -> tags.add(
                        lb.kind() == StvnSchema.BoundKind.INCLUSIVE ? StvnVocabulary.FACET_KW_MIN_INCL : StvnVocabulary.FACET_KW_MIN_EXCL));
                    f.upperBound().ifPresent(ub -> tags.add(
                        ub.kind() == StvnSchema.BoundKind.INCLUSIVE ? StvnVocabulary.FACET_KW_MAX_INCL : StvnVocabulary.FACET_KW_MAX_EXCL));
                }
                case StvnSchema.StringSchema s -> {
                    if (!s.minSize().equals(BigInteger.ZERO)) tags.add(StvnVocabulary.FACET_KW_MIN_SIZE);
                    if (!s.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) tags.add(StvnVocabulary.FACET_KW_MAX_SIZE);
                    if (s.rawRegexPattern().isPresent()) tags.add(StvnVocabulary.FACET_KW_REGEX);
                    if (s.preserveIndent()) tags.add(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
                    s.lowerBound().ifPresent(lb -> tags.add(
                        lb.kind() == StvnSchema.BoundKind.INCLUSIVE ? StvnVocabulary.FACET_KW_MIN_INCL : StvnVocabulary.FACET_KW_MIN_EXCL));
                    s.upperBound().ifPresent(ub -> tags.add(
                        ub.kind() == StvnSchema.BoundKind.INCLUSIVE ? StvnVocabulary.FACET_KW_MAX_INCL : StvnVocabulary.FACET_KW_MAX_EXCL));
                }
                case StvnSchema.TimeEpochSchema te -> {
                    tags.add("#" + te.scale().name().toLowerCase(Locale.ROOT));
                    if (te.explicitMinIncl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MIN_INCL);
                    if (te.explicitMaxExcl().isPresent()) tags.add(StvnVocabulary.FACET_KW_MAX_EXCL);
                }
                case StvnSchema.DateTimeSchema dt -> {
                    switch (dt.mode()) {
                        case OFFSET -> tags.add(StvnVocabulary.FACET_KW_OFFSET);
                        case ZONED -> tags.add(StvnVocabulary.FACET_KW_ZONED);
                        case AUDITED -> tags.add(StvnVocabulary.FACET_KW_AUDITED);
                    }
                    if (dt.explicitMinInclIso().isPresent()) tags.add(StvnVocabulary.FACET_KW_MIN_INCL);
                    if (dt.explicitMaxExclIso().isPresent()) tags.add(StvnVocabulary.FACET_KW_MAX_EXCL);
                }
                case StvnSchema.SeqSchema sq -> {
                    if (!sq.minSize().equals(BigInteger.ZERO)) tags.add(StvnVocabulary.FACET_KW_MIN_SIZE);
                    if (!sq.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) tags.add(StvnVocabulary.FACET_KW_MAX_SIZE);
                }
                case StvnSchema.SetSchema st -> {
                    if (!st.minSize().equals(BigInteger.ZERO)) tags.add(StvnVocabulary.FACET_KW_MIN_SIZE);
                    if (!st.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) tags.add(StvnVocabulary.FACET_KW_MAX_SIZE);
                }
                case StvnSchema.MapSchema mp -> {
                    if (mp.invertible()) tags.add(StvnVocabulary.FACET_KW_INVERTIBLE);
                    if (!mp.minSize().equals(BigInteger.ZERO)) tags.add(StvnVocabulary.FACET_KW_MIN_SIZE);
                    if (!mp.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) tags.add(StvnVocabulary.FACET_KW_MAX_SIZE);
                }
                default -> {
                }
            }
        }
        if (baseType != null && !StvnMetadataRegistry.getSupportedFacets(baseType).isEmpty() && !isCtor) {
            tags.add(StvnVocabulary.FACET_KW_EQUATABLE);
            tags.add(StvnVocabulary.FACET_KW_COMPARABLE);
        }
        return tags;
    }

    private static void collectDeclaredFacetTagsFromTypeDef(TypeDefinition typeDef, Set<String> tags) {
        var metaMap = typeDef.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var parts = entry.getText().trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    tags.add(parts[0]);
                }
            }
        }
        if (typeDef.getSchemaType() != null && typeDef.getSchemaType().getMetadataMap() != null) {
            for (var entry : typeDef.getSchemaType().getMetadataMap().getMetadataEntryList()) {
                var parts = entry.getText().trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    tags.add(parts[0]);
                }
            }
        }
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
        ExplicitUnionValue union = null;
        Value payloadVal = null;
        if (value.getExplicitUnionValue() != null) {
            union = value.getExplicitUnionValue();
            payloadVal = union.getValue();
        } else if (originalElement != null && originalElement.getParent() instanceof ExplicitUnionValue origUnion) {
            union = origUnion;
            payloadVal = origUnion.getValue();
        }
        if (union == null && value.getParent() instanceof ExplicitUnionValue pUnion && value.getCollectionValue() == null) {
            union = pUnion;
            payloadVal = value;
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

        int branchIdx = StvnPsiLiteralEvaluator.resolveZeroBasedUnionBranchIndex(tagPrefix.getText().trim(), branches.size()).orElse(-1);
        if (branchIdx < 0) {
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

    /**
     * Positional slot projection descriptor for composite product schemas.
     *
     * @param index              0-based positional slot index
     * @param declaredSchema     formatted declared slot schema
     * @param baseType           resolved underlying base type label
     * @param constraintsSummary HTML-formatted summary of active slot constraints
     */
    public record StvnSlotProjection(
        int index,
        String declaredSchema,
        String baseType,
        String constraintsSummary
    ) {}

    /**
     * Compliance evaluation summary for a payload value against its governing schema.
     *
     * @param isSatisfied          {@code true} when all schema bounds and patterns hold
     * @param statusLabel          badge label ({@code Satisfied}, {@code Satisfied (Precision Loss)}, {@code Violated}, or {@code In Violation})
     * @param detailExplanation    human-readable compliance summary
     * @param evaluatedConstraints evaluated constraint key-value map
     * @param validLexicalForms    HTML list items illustrating valid alternative lexical forms
     */
    public record StvnValueComplianceResult(
        boolean isSatisfied,
        String statusLabel,
        String detailExplanation,
        Map<String, String> evaluatedConstraints,
        List<String> validLexicalForms
    ) {}
}
