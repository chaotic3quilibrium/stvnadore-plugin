package org.stvnadore.plugin.documentation;

import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.HashSet;
import java.util.Set;

@NullMarked
public final class StvnDocumentationProvider implements DocumentationProvider {

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        var settings = StvnProjectSettings.getInstance(element.getProject());
        if (settings != null && !settings.getState().showHoverDocs) {
            return null;
        }
        var target = getDocumentationElement(element, originalElement);
        if (target == null) {
            return null;
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
                if (targetDef != null && targetDef.getParent() instanceof TypeDefinition td && td.getSchemaType() != null) {
                    var resolved = StvnTypeResolver.resolveNominalSchema(td.getSchemaType());
                    if (resolved != null) {
                        fallbackBase = StvnSchemaFormatter.formatCleanSchema(resolved);
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

    private @Nullable String buildTargetDocumentation(PsiElement target, @Nullable PsiElement originalElement) {
        if (target instanceof TypeDefinition typeDef) {
            var keyword = typeDef.getTypeKeyword();
            var schemaType = typeDef.getSchemaType();
            var underlying = schemaType != null ? StvnSchemaFormatter.formatCleanSchema(schemaType) : "Unknown";

            var sb = new StringBuilder();
            if (keyword != null) {
                sb.append("<b>Type Alias:</b> ").append(keyword.getText()).append("<br/>");

                var containingFile = typeDef.getContainingFile();
                if (containingFile != null) {
                    var fileName = containingFile.getName();
                    if (fileName.endsWith(".stvn_incl") || fileName.endsWith(".stvn_inclf")) {
                        sb.append("<b>Imported From:</b> <code>\"").append(fileName).append("\"</code><br/>");
                    } else {
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

            sb.append("<hr/>");
            sb.append("<b>Underlying Structure:</b> ").append(underlying);
            return sb.toString();
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
            var underlying = "Unknown";
            if (resolvedTarget != null && resolvedTarget.getParent() instanceof TypeDefinition remTypeDef && remTypeDef.getSchemaType() != null) {
                underlying = StvnSchemaFormatter.formatCleanSchema(remTypeDef.getSchemaType());
            }
            sb.append("<hr/>");
            sb.append("<b>Underlying Structure:</b> ").append(underlying);
            return sb.toString();
        }

        if (target instanceof ConstantDefinition constDef) {
            var keyword = constDef.getValueKeyword();
            var schemaType = constDef.getSchemaType();
            var value = constDef.getValue();

            var sb = new StringBuilder();
            if (keyword != null) {
                sb.append("<b>Typed Constant:</b> ").append(keyword.getText()).append("<br/>");
            }
            if (schemaType != null) {
                sb.append("<b>Type:</b> ").append(StvnSchemaFormatter.formatCleanSchema(schemaType)).append("<br/>");
            }
            if (value != null) {
                sb.append("<hr/>");
                sb.append("<b>Value:</b> ").append(value.getText());
            }
            return sb.toString();
        }

        if (target instanceof ValueKeyword valKw && valKw.getParent() instanceof org.stvnadore.psi.EnumDef enumDef) {
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

        if (target instanceof ProductType productType) {
            int arity = productType.getSchemaTypeList().size();
            return String.format("""
                <b>Product Type Constructor:</b> :Tuple( ... )<br/>
                <b>Arity:</b> %d<br/>
                <hr/>
                Defines a fixed-length, heterogeneous positional product type.<br/>
                <b>Payload Syntax:</b> Parenthesized values: <code>( val1 val2 ... )</code>.
                """, arity);
        }

        if (target instanceof SumType sumType) {
            if (sumType.getEnumDef() != null) {
                int variantCount = sumType.getEnumDef().getValueKeywordList().size();
                return String.format("""
                    <b>Enumeration Constructor:</b> :Enum [ ... ]<br/>
                    <b>Variant Count:</b> %d<br/>
                    <hr/>
                    Defines a closed set of symbolic value keywords.<br/>
                    <b>Payload Syntax:</b> <code>#val1</code>.
                    """, variantCount);
            }
            if (isUnionSumType(sumType)) {
                var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                int branchCount = branches.size();
                return String.format("""
                    <b>Algebraic Union Constructor:</b> :Union( ... )<br/>
                    <b>Branch Count:</b> %d<br/>
                    <hr/>
                    Defines an n-ary tagged algebraic union.<br/>
                    <b>Variants:</b> Explicit tag prefix <code>#1 val</code>, <code>#2 val</code>, or compiler-inferred disambiguation.
                    """, branchCount);
            }
            var firstChild = sumType.getFirstChild();
            var text = firstChild != null ? firstChild.getText().trim() : sumType.getText().trim();
            var doc = getBuiltInSpecificationDoc(text);
            if (doc != null) {
                return doc;
            }
        }

        if (target instanceof EnumDef enumDef) {
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
                int arity = productType.getSchemaTypeList().size();
                return String.format("""
                    <b>Product Type Constructor:</b> :Tuple( ... )<br/>
                    <b>Arity:</b> %d<br/>
                    <hr/>
                    Defines a fixed-length, heterogeneous positional product type.<br/>
                    <b>Payload Syntax:</b> Parenthesized values: <code>( val1 val2 ... )</code>.
                    """, arity);
            }
            if (parent instanceof SumType sumType) {
                if (sumType.getEnumDef() != null) {
                    int variantCount = sumType.getEnumDef().getValueKeywordList().size();
                    return String.format("""
                        <b>Enumeration Constructor:</b> :Enum [ ... ]<br/>
                        <b>Variant Count:</b> %d<br/>
                        <hr/>
                        Defines a closed set of symbolic value keywords.<br/>
                        <b>Payload Syntax:</b> <code>#val1</code>.
                        """, variantCount);
                }
                if (isUnionSumType(sumType)) {
                    var branches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    int branchCount = branches.size();
                    return String.format("""
                        <b>Algebraic Union Constructor:</b> :Union( ... )<br/>
                        <b>Branch Count:</b> %d<br/>
                        <hr/>
                        Defines an n-ary tagged algebraic union.<br/>
                        <b>Variants:</b> Explicit tag prefix <code>#1 val</code>, <code>#2 val</code>, or compiler-inferred disambiguation.
                        """, branchCount);
                }
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
            var firstChild = collType.getFirstChild();
            var text = firstChild != null ? firstChild.getText().trim() : collType.getText().trim();
            var doc = getBuiltInSpecificationDoc(text);
            if (doc != null) {
                return doc;
            }
        }

        if (target instanceof Value value) {
            var typeLabel = StvnTypeResolver.resolveValueType(value);
            if (typeLabel != null) {
                var sb = new StringBuilder();
                sb.append("<b>Value Type:</b> ").append(typeLabel).append("<br/>");
                sb.append("<hr/>");
                sb.append("<b>Expression:</b> ").append(value.getText());
                return sb.toString();
            }
        }

        return null;
    }

    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, @Nullable PsiElement originalElement) {
        var settings = StvnProjectSettings.getInstance(element.getProject());
        if (settings != null && !settings.getState().showHoverDocs) {
            return null;
        }
        var target = getDocumentationElement(element, originalElement);
        if (target == null) {
            return null;
        }

        if (target instanceof TypeDefinition typeDef) {
            var keyword = typeDef.getTypeKeyword();
            if (keyword != null) {
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

        if (target instanceof ConstantDefinition constDef) {
            var keyword = constDef.getValueKeyword();
            var schemaType = constDef.getSchemaType();
            if (keyword != null && schemaType != null) {
                return "Constant: " + keyword.getText() + " " + StvnSchemaFormatter.formatCleanSchema(schemaType);
            }
        }

        if (target instanceof ValueKeyword valKw && valKw.getParent() instanceof org.stvnadore.psi.EnumDef enumDef) {
            var typeDef = PsiTreeUtil.getParentOfType(enumDef, TypeDefinition.class);
            var typeName = (typeDef != null && typeDef.getTypeKeyword() != null) ? typeDef.getTypeKeyword().getText() : "Enum";
            return "Enum Variant: " + valKw.getText() + " in " + typeName;
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
                <b>Constraint:</b> Prohibits integer truthiness (<code>0</code> / <code>1</code>).
                """;
        }

        if (keyword.startsWith(":Int") || keyword.startsWith(":Uint")) {
            var isUnsigned = keyword.startsWith(":Uint");
            var widthStr = keyword.replaceAll("[^0-9]", "");
            var width = widthStr.isEmpty()
                ? (isUnsigned ? "Arbitrary-precision unsigned" : "Arbitrary-precision signed")
                : widthStr + "-bit " + (isUnsigned ? "unsigned" : "signed");
            return String.format("""
                <b>Built-in Numeric:</b> %s<br/>
                <hr/>
                Represents a %s integer value.<br/>
                <b>Lexical Forms:</b> Decimal (<code>42</code>), Hex (<code>0x2A</code>), Binary (<code>0b101010</code>), Octal (<code>0o52</code>).
                """, keyword, width);
        }

        if (keyword.startsWith(":Float")) {
            var isExact = keyword.equals(":FloatExact");
            return String.format("""
                <b>Built-in Floating-Point:</b> %s<br/>
                <hr/>
                Represents %s floating-point numeric value.<br/>
                <b>Lexical Form:</b> Standard decimal notation with decimal point (e.g. <code>3.14159</code>, <code>-0.5</code>).
                """, keyword, isExact ? "an exact decimal" : "an IEEE 754");
        }

        if (keyword.startsWith(":StringFixed")) {
            var widthStr = keyword.substring(":StringFixed".length());
            return String.format("""
                <b>Built-in String Type:</b> %s<br/>
                <hr/>
                Represents an <b>exact fixed-length UTF-8 string</b> of exactly %s characters.<br/>
                <b>Constraint:</b> String length must satisfy <code>len == %s</code>.<br/>
                <b>Forms:</b> Double-quoted string literals (<code>"..."</code>).
                """, keyword, widthStr.isEmpty() ? "fixed" : widthStr, widthStr.isEmpty() ? "N" : widthStr);
        }

        if (keyword.startsWith(":StringNonEmpty")) {
            var maxLen = keyword.substring(":StringNonEmpty".length());
            return String.format("""
                <b>Built-in String Type:</b> %s<br/>
                <hr/>
                Represents a <b>non-empty UTF-8 string</b>%s.<br/>
                <b>Constraint:</b> Prohibits empty string (<code>""</code>)%s.<br/>
                <b>Forms:</b> Simple double-quoted (<code>"text"</code>), Multi-line block (<code>\"\"\"...\"\"\"</code>), Polyglot fenced (<code>\"\"\"-&gt;[LANG]...[LANG]\"\"\"</code>).
                """,
                keyword,
                maxLen.isEmpty() ? "" : " with maximum length of " + maxLen + " characters",
                maxLen.isEmpty() ? " (1 &le; len)" : " (1 &le; len &le; " + maxLen + ")");
        }

        if (keyword.startsWith(":String") && keyword.length() > 7 && Character.isDigit(keyword.charAt(7))) {
            var maxLen = keyword.substring(":String".length());
            return String.format("""
                <b>Built-in String Type:</b> %s<br/>
                <hr/>
                Represents a <b>max-bounded UTF-8 string</b> with a maximum length of %s characters.<br/>
                <b>Constraint:</b> String length must satisfy <code>0 &le; len &le; %s</code>.<br/>
                <b>Forms:</b> Simple double-quoted (<code>"text"</code>), Multi-line block (<code>\"\"\"...\"\"\"</code>), Polyglot fenced (<code>\"\"\"-&gt;[LANG]...[LANG]\"\"\"</code>).
                """, keyword, maxLen, maxLen);
        }

        if (keyword.equals(":String")) {
            return String.format("""
                <b>Built-in String Type:</b> %s<br/>
                <hr/>
                Represents an <b>unbounded UTF-8 text string</b> (up to maximum allocation size of 16,777,216 characters).<br/>
                <b>Forms:</b> Simple double-quoted (<code>"text"</code>), Multi-line block (<code>\"\"\"...\"\"\"</code>), Polyglot fenced (<code>\"\"\"-&gt;[LANG]...[LANG]\"\"\"</code>).
                """, keyword);
        }

        return switch (keyword) {
            case ":Tuple" -> """
                <b>Product Type Constructor:</b> :Tuple( :T1 :T2 ... )<br/>
                <hr/>
                Defines a fixed-length, heterogeneous positional product type.<br/>
                <b>Payload Syntax:</b> Parenthesized values: <code>( val1 val2 ... )</code>.
                """;
            case ":Map", ":MapNonEmpty" -> """
                <b>Collection Constructor:</b> :Map( :KeyType :ValType )<br/>
                <hr/>
                Defines a key-value mapping with strictly unique keys.<br/>
                <b>Payload Syntax:</b> <code>{ [ key1 val1 ] [ key2 val2 ] }</code>.
                """;
            case ":MapInv", ":MapInvNonEmpty" -> """
                <b>Collection Constructor:</b> :MapInv( :KeyType :ValType )<br/>
                <hr/>
                Defines an inverted key-value mapping enforcing bijective uniqueness (unique keys AND unique values).<br/>
                <b>Payload Syntax:</b> <code>{ [ key1 val1 ] [ key2 val2 ] }</code>.
                """;
            case ":Seq", ":SeqNonEmpty" -> """
                <b>Collection Constructor:</b> :Seq( :ElemType )<br/>
                <hr/>
                Defines an ordered homogeneous sequence of elements.<br/>
                <b>Payload Syntax:</b> <code>[ elem1 elem2 ... ]</code>.
                """;
            case ":Set", ":SetNonEmpty" -> """
                <b>Collection Constructor:</b> :Set( :ElemType )<br/>
                <hr/>
                Defines an unordered homogeneous collection of unique elements.<br/>
                <b>Payload Syntax:</b> <code>[ elem1 elem2 ... ]</code>.
                """;
            case ":Option" -> """
                <b>Sum Type Constructor:</b> :Option( :Type )<br/>
                <hr/>
                Defines an optional algebraic value representing presence or absence.<br/>
                <b>Variants:</b> <code>#Some value</code> (or <code>#S value</code> / inferred) vs. <code>#None</code> (or <code>#N</code>).
                """;
            case ":Either" -> """
                <b>Sum Type Constructor:</b> :Either( :LeftType :RightType )<br/>
                <hr/>
                Defines a disjoint union of exactly two distinct types.<br/>
                <b>Variants:</b> <code>#Left val</code> (or <code>#L val</code>) vs. <code>#Right val</code> (or <code>#R val</code>).
                """;
            case ":Union" -> """
                <b>Algebraic Union Constructor:</b> :Union( :T1 :T2 ... :Tn )<br/>
                <hr/>
                Defines an n-ary tagged algebraic union.<br/>
                <b>Variants:</b> Explicit tag prefix <code>#1 val</code>, <code>#2 val</code>, or compiler-inferred disambiguation.
                """;
            case ":Enum" -> """
                <b>Enumeration Constructor:</b> :Enum [ #val1 #val2 ... ]<br/>
                <hr/>
                Defines a closed set of symbolic value keywords.<br/>
                <b>Payload Syntax:</b> <code>#val1</code>.
                """;
            default -> null;
        };
    }

    private static @Nullable String getBuiltInSpecificationQuickNavigateInfo(String keyword) {
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
        if (keyword.startsWith(":Int")) {
            return "Primitive Type: " + keyword + " (Signed Integer)";
        }
        if (keyword.startsWith(":Uint")) {
            return "Primitive Type: " + keyword + " (Unsigned Integer)";
        }
        if (keyword.startsWith(":Float")) {
            return "Primitive Type: " + keyword + " (Floating-Point)";
        }
        if (keyword.startsWith(":StringFixed")) {
            return "Primitive Type: " + keyword + " (Fixed-Length String)";
        }
        if (keyword.startsWith(":StringNonEmpty")) {
            return "Primitive Type: " + keyword + " (Non-Empty String)";
        }
        if (keyword.startsWith(":String") && keyword.length() > 7 && Character.isDigit(keyword.charAt(7))) {
            return "Primitive Type: " + keyword + " (Max-Bounded String)";
        }
        if (keyword.equals(":String")) {
            return "Primitive Type: :String (UTF-8 String)";
        }

        return switch (keyword) {
            case ":Tuple" -> "Product Type Constructor: :Tuple( ... )";
            case ":Map" -> "Collection Constructor: :Map( :Key :Val )";
            case ":MapNonEmpty" -> "Collection Constructor: :MapNonEmpty( :Key :Val )";
            case ":MapInv" -> "Collection Constructor: :MapInv( :Key :Val )";
            case ":MapInvNonEmpty" -> "Collection Constructor: :MapInvNonEmpty( :Key :Val )";
            case ":Seq" -> "Collection Constructor: :Seq( :Elem )";
            case ":SeqNonEmpty" -> "Collection Constructor: :SeqNonEmpty( :Elem )";
            case ":Set" -> "Collection Constructor: :Set( :Elem )";
            case ":SetNonEmpty" -> "Collection Constructor: :SetNonEmpty( :Elem )";
            case ":Option" -> "Sum Type Constructor: :Option( :Type )";
            case ":Either" -> "Sum Type Constructor: :Either( :Left :Right )";
            case ":Union" -> "Algebraic Union Constructor: :Union( ... )";
            case ":Enum" -> "Enumeration Constructor: :Enum [ ... ]";
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
            case ":Uuid" -> """
                <b>Standard Library Prelude:</b> :Uuid<br/>
                <b>Underlying Type:</b> <code>:StringFixed36</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"</code><br/>
                <hr/>
                Represents an <b>RFC 4122 Universally Unique Identifier</b> (ITU-T X.667).<br/>
                <b>Lexical Form:</b> Fixed 36-character string in 8-4-4-4-12 hyphenated hexadecimal notation.<br/>
                <b>Examples:</b> <code>"123e4567-e89b-12d3-a456-426614174000"</code>, <code>"f47ac10b-58cc-4372-a567-0e02b2c3d479"</code>.
                """;
            case ":Ulid" -> """
                <b>Standard Library Prelude:</b> :Ulid<br/>
                <b>Underlying Type:</b> <code>:StringFixed26</code><br/>
                <b>Validation Constraint:</b> <code>#regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$"</code><br/>
                <hr/>
                Represents a <b>Universally Unique Lexicographically Sortable Identifier</b> encoded in Crockford's Base32.<br/>
                <b>Lexical Form:</b> Fixed 26-character alphanumeric string (excludes <code>I</code>, <code>L</code>, <code>O</code>, <code>U</code>; first character in <code>0-7</code>).<br/>
                <b>Examples:</b> <code>"01ARZ3NDEKTSV4RRFFQ69G5FAV"</code>, <code>"01EDZZZG8X69W1V3Q2P8P5MNQ3"</code>.
                """;
            case ":Sha256" -> """
                <b>Standard Library Prelude:</b> :Sha256<br/>
                <b>Underlying Type:</b> <code>:StringFixed64</code><br/>
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
                <b>Underlying Type:</b> <code>:Uint16</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl 1 #maxIncl 65535</code><br/>
                <hr/>
                Represents a valid <b>TCP/UDP network socket port number</b>.<br/>
                <b>Allowed Range:</b> <code>1</code> through <code>65535</code> (inclusive). Prohibits port <code>0</code>.<br/>
                <b>Examples:</b> <code>80</code>, <code>443</code>, <code>8080</code>, <code>5432</code>, <code>65535</code>.
                """;
            case ":Percentage" -> """
                <b>Standard Library Prelude:</b> :Percentage<br/>
                <b>Underlying Type:</b> <code>:Float64</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl 0.0 #maxIncl 100.0</code><br/>
                <hr/>
                Represents a <b>percentage ratio</b> bounded between 0% and 100%.<br/>
                <b>Allowed Range:</b> <code>0.0</code> through <code>100.0</code> (inclusive).<br/>
                <b>Examples:</b> <code>0.0</code>, <code>50.0</code>, <code>99.95</code>, <code>100.0</code>.
                """;
            case ":Probability" -> """
                <b>Standard Library Prelude:</b> :Probability<br/>
                <b>Underlying Type:</b> <code>:Float64</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl 0.0 #maxIncl 1.0</code><br/>
                <hr/>
                Represents a <b>normalized statistical probability measure</b>.<br/>
                <b>Allowed Range:</b> <code>0.0</code> through <code>1.0</code> (inclusive).<br/>
                <b>Examples:</b> <code>0.0</code>, <code>0.5</code>, <code>0.7071</code>, <code>1.0</code>.
                """;
            case ":Currency" -> """
                <b>Standard Library Prelude:</b> :Currency<br/>
                <b>Underlying Type:</b> <code>:FloatExact</code><br/>
                <hr/>
                Represents an <b>arbitrary-precision exact decimal currency amount</b>.<br/>
                <b>Constraint:</b> Eliminates binary IEEE 754 floating-point rounding inaccuracies.<br/>
                <b>Examples:</b> <code>19.99</code>, <code>1500.50</code>, <code>0.01</code>, <code>-42.50</code>.
                """;
            case ":Latitude" -> """
                <b>Standard Library Prelude:</b> :Latitude<br/>
                <b>Underlying Type:</b> <code>:Float64</code><br/>
                <b>Validation Constraint:</b> <code>#minIncl -90.0 #maxIncl 90.0</code><br/>
                <hr/>
                Represents a <b>geographic latitude coordinate</b> (North-South angle).<br/>
                <b>Allowed Range:</b> <code>-90.0</code> (South Pole) through <code>+90.0</code> (North Pole).<br/>
                <b>Examples:</b> <code>0.0</code> (Equator), <code>37.7749</code> (San Francisco), <code>-33.8688</code> (Sydney).
                """;
            case ":Longitude" -> """
                <b>Standard Library Prelude:</b> :Longitude<br/>
                <b>Underlying Type:</b> <code>:Float64</code><br/>
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
            case ":Uuid" -> "Standard Prelude Type: :Uuid (RFC 4122 UUID)";
            case ":Ulid" -> "Standard Prelude Type: :Ulid (Crockford Base32 ULID)";
            case ":Sha256" -> "Standard Prelude Type: :Sha256 (SHA-256 cryptographic hash (64 hex characters))";
            case ":SemVer" -> "Standard Prelude Type: :SemVer (Semantic Version 2.0.0)";
            case ":Email" -> "Standard Prelude Type: :Email (RFC 5322 Email Address)";
            case ":Port" -> "Standard Prelude Type: :Port (TCP/UDP Port 1-65535)";
            case ":Percentage" -> "Standard Prelude Type: :Percentage (Float64 0.0 - 100.0)";
            case ":Probability" -> "Standard Prelude Type: :Probability (Float64 0.0 - 1.0)";
            case ":Currency" -> "Standard Prelude Type: :Currency (Arbitrary-Precision Decimal)";
            case ":Latitude" -> "Standard Prelude Type: :Latitude (Float64 -90.0 to +90.0)";
            case ":Longitude" -> "Standard Prelude Type: :Longitude (Float64 -180.0 to +180.0)";
            default -> null;
        };
    }

    private static @Nullable String getBuiltInTemporalDoc(String keyword) {
        return switch (keyword) {
            case ":DateTimeOffset" -> """
                <b>Primitive Type:</b> :DateTimeOffset<br/>
                <hr/>
                Represents an <b>unambiguous physical instant</b> on the universal timeline.<br/>
                <b>Lexical Form:</b> ISO-8601 timestamp with explicit UTC offset (<code>±HH:mm</code> or <code>Z</code>).<br/>
                <b>Constraint:</b> Prohibits IANA zone brackets (<code>[...]</code>).<br/>
                <b>Example:</b> <code>"2026-08-18T18:00:00-05:00"</code>
                """;
            case ":DateTimeZoned" -> """
                <b>Primitive Type:</b> :DateTimeZoned<br/>
                <hr/>
                Represents a <b>civil wall-clock schedule</b> bound to an IANA time zone jurisdiction.<br/>
                <b>Lexical Form:</b> ISO-8601 local timestamp with bracketed IANA zone ID (<code>[Region/City]</code>).<br/>
                <b>Constraint:</b> Prohibits explicit numerical offsets (<code>±HH:mm</code>) or <code>Z</code>. Validates DST transition gaps.<br/>
                <b>Example:</b> <code>"2026-08-18T18:00:00[America/Chicago]"</code>
                """;
            case ":DateTimeAudited" -> """
                <b>Primitive Type:</b> :DateTimeAudited<br/>
                <hr/>
                Represents an <b>audited compliance record</b> capturing both observed UTC offset and regulatory IANA jurisdiction.<br/>
                <b>Lexical Form:</b> ISO-8601 timestamp with explicit UTC offset AND bracketed IANA zone ID.<br/>
                <b>Constraint:</b> Mandates both offset and zone. Validates offset consistency against IANA zone rules for that date-time.<br/>
                <b>Example:</b> <code>"2026-08-18T18:00:00-05:00[America/Chicago]"</code>
                """;
            case ":TimeEpochS" -> """
                <b>Primitive Type:</b> :TimeEpochS<br/>
                <hr/>
                Represents Unix Epoch timestamp measured in seconds.<br/>
                <b>Lexical Form:</b> Integer timestamp value (e.g. <code>1755532800</code>).
                """;
            case ":TimeEpochMs" -> """
                <b>Primitive Type:</b> :TimeEpochMs<br/>
                <hr/>
                Represents Unix Epoch timestamp measured in milliseconds.<br/>
                <b>Lexical Form:</b> Integer timestamp value (e.g. <code>1755532800000</code>).
                """;
            case ":TimeEpochNs" -> """
                <b>Primitive Type:</b> :TimeEpochNs<br/>
                <hr/>
                Represents Unix Epoch timestamp measured in nanoseconds.<br/>
                <b>Lexical Form:</b> Integer timestamp value.
                """;
            default -> null;
        };
    }

    private static @Nullable String getBuiltInTemporalQuickNavigateInfo(String keyword) {
        return switch (keyword) {
            case ":DateTimeOffset" -> "Primitive Type: :DateTimeOffset (Physical Instant)";
            case ":DateTimeZoned" -> "Primitive Type: :DateTimeZoned (Civil Wall-Clock Schedule)";
            case ":DateTimeAudited" -> "Primitive Type: :DateTimeAudited (Audited Compliance Record)";
            case ":TimeEpochS" -> "Primitive Type: :TimeEpochS (Unix Epoch Seconds)";
            case ":TimeEpochMs" -> "Primitive Type: :TimeEpochMs (Unix Epoch Milliseconds)";
            case ":TimeEpochNs" -> "Primitive Type: :TimeEpochNs (Unix Epoch Nanoseconds)";
            default -> null;
        };
    }

    private @Nullable PsiElement getDocumentationElement(PsiElement element, @Nullable PsiElement originalElement) {
        if (element instanceof TypeDefinition || element instanceof ConstantDefinition) {
            return element;
        }

        if (element instanceof TypeKeyword typeKw) {
            var parent = typeKw.getParent();
            if (parent instanceof TypeDefinition typeDef && typeDef.getTypeKeyword() == typeKw) {
                return typeDef;
            }

            if (parent instanceof IncludeMapAlias alias) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2 && list.get(1) == typeKw) {
                    return alias;
                }
                var resolved = new StvnTypeReference(typeKw).resolve();
                if (resolved != null && resolved.getParent() instanceof TypeDefinition targetTypeDef) {
                    return targetTypeDef;
                }
                if (resolved != null && resolved.getParent() instanceof IncludeMapAlias targetAlias) {
                    return targetAlias;
                }
            }

            // Usage site inside composite schema (e.g. :MapInv, :Tuple, :Union, :Seq, :Option, :Either):
            var ref = typeKw.getReference();
            var resolved = ref != null ? ref.resolve() : new StvnTypeReference(typeKw).resolve();
            if (resolved instanceof TypeKeyword resolvedKw) {
                var resParent = resolvedKw.getParent();
                if (resParent instanceof TypeDefinition targetDef) {
                    return targetDef;
                }
                if (resParent instanceof IncludeMapAlias targetAlias) {
                    return targetAlias;
                }
                return resolvedKw;
            }

            return typeKw;
        }

        if (element instanceof ValueKeyword valKw) {
            var parent = valKw.getParent();
            if (parent instanceof ConstantDefinition constDef && constDef.getValueKeyword() == valKw) {
                return constDef;
            }
            if (parent instanceof org.stvnadore.psi.EnumDef) {
                return valKw;
            }
            var ref = valKw.getReference();
            var resolved = ref != null ? ref.resolve() : new StvnConstantReference(valKw).resolve();
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

        if (originalElement != null) {
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
        var valueAncestor = PsiTreeUtil.getParentOfType(contextElement, Value.class);
        if (valueAncestor != null && PsiTreeUtil.getParentOfType(valueAncestor, BodyEntry.class) != null) {
            return valueAncestor;
        }
        var typeKwAncestor = PsiTreeUtil.getParentOfType(contextElement, TypeKeyword.class);
        if (typeKwAncestor != null) {
            return typeKwAncestor;
        }
        var valKwAncestor = PsiTreeUtil.getParentOfType(contextElement, ValueKeyword.class);
        if (valKwAncestor != null) {
            return valKwAncestor;
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
            var targetParent = targetKw.getParent();
            if (targetParent instanceof TypeDefinition targetDef) {
                return resolveTerminalSchemaType(targetDef, visited);
            }
            if (targetParent instanceof IncludeMapAlias targetAlias) {
                return resolveTerminalSchemaType(targetAlias, visited);
            }
        }
        if (resolved instanceof TypeDefinition targetDef) {
            return resolveTerminalSchemaType(targetDef, visited);
        }
        if (resolved instanceof IncludeMapAlias targetAlias) {
            return resolveTerminalSchemaType(targetAlias, visited);
        }
        return null;
    }

    private static @Nullable String extractStructuralMetricHtml(PsiElement element) {
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

    private static boolean isUnionSumType(SumType sumType) {
        if (sumType.getEnumDef() != null) {
            return false;
        }
        var node = sumType.getNode();
        if (node != null && node.findChildByType(StvnTypes.KW_UNION) != null) {
            return true;
        }
        var firstChild = sumType.getFirstChild();
        return firstChild != null && firstChild.getText().equals(":Union");
    }
}
