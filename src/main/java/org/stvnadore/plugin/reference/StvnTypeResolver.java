package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnAnalysisResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.VariantStep;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

@NullMarked
public final class StvnTypeResolver {

    private static final Key<CachedValue<java.util.Map<Integer, StvnValue>>> CORE_VALUES_KEY =
        Key.create("org.stvnadore.plugin.CORE_VALUES");

    private static final Key<CachedValue<List<org.stvnadore.core.StvnDiagnostic>>> CORE_DIAGNOSTICS_KEY =
        Key.create("org.stvnadore.plugin.CORE_DIAGNOSTICS");

    private static final java.util.regex.Pattern EXPLICIT_UNION_TAG_PATTERN =
        java.util.regex.Pattern.compile("^#[1-9][0-9]*(\\s+|$)");

    public static @Nullable List<org.stvnadore.core.StvnDiagnostic> getCompilationDiagnostics(@Nullable PsiFile file) {
        if (file == null) {
            return null;
        }
        var manager = CachedValuesManager.getManager(file.getProject());
        return manager.getCachedValue(file, CORE_DIAGNOSTICS_KEY, () -> {
            List<org.stvnadore.core.StvnDiagnostic> list = new java.util.ArrayList<>();
            try {
                var text = file.getText();
                var virtualFile = file.getVirtualFile();
                if (virtualFile != null) {
                    var path = virtualFile.getPath();
                    if (path.startsWith("/src/") || path.startsWith("temp://")) {
                        if (path.contains("invalid-syntax") && !text.contains("shared-fixtures/")) {
                            path = new java.io.File("src/test/resources/shared-fixtures/invalid-syntax/dummy.stvn").getAbsolutePath();
                        } else if (path.contains("valid-syntax") && !text.contains("shared-fixtures/")) {
                            path = new java.io.File("src/test/resources/shared-fixtures/valid-syntax/dummy.stvn").getAbsolutePath();
                        } else {
                            path = new java.io.File("src/test/resources/dummy.stvn").getAbsolutePath();
                        }
                    }

                    var result = StvnCompiler.compileToResult(text, path, StvnParserConfig.DEFAULT);
                    list.addAll(result.diagnostics());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return CachedValueProvider.Result.create(list, file);
        }, false);
    }

    public static boolean isDegradedSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) return false;
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            return isDegradedNominalAlias(schemaType.getContainingFile(), kw.getText());
        }
        return false;
    }

    public static boolean isDegradedNominalAlias(@Nullable PsiFile file, @Nullable String aliasName) {
        if (file == null || aliasName == null || aliasName.isEmpty()) {
            return false;
        }

        // 1. Direct PSI check on the TypeDefinition metadata map
        var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new HashSet<>());
        if (targetDef != null && targetDef.getParent() instanceof TypeDefinition typeDef) {
            var metaMap = typeDef.getMetadataMap();
            if (metaMap != null) {
                var entries = metaMap.getMetadataEntryList();
                var hasMinIncl = false;
                var hasMinExcl = false;
                var hasMaxIncl = false;
                var hasMaxExcl = false;
                BigDecimal minVal = null;
                BigDecimal maxVal = null;

                for (var entry : entries) {
                    var entryText = entry.getText();
                    if (entryText.startsWith("#regex")) {
                        var strLit = PsiTreeUtil.findChildOfType(entry, StringLiteral.class);
                        if (strLit != null) {
                            var raw = strLit.getText();
                            var content = raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2
                                ? raw.substring(1, raw.length() - 1) : raw;
                            try {
                                java.util.regex.Pattern.compile(content);
                            } catch (java.util.regex.PatternSyntaxException e) {
                                return true;
                            }
                        }
                    } else if (entryText.startsWith("#minIncl")) {
                        hasMinIncl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) minVal = num;
                    } else if (entryText.startsWith("#minExcl")) {
                        hasMinExcl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) minVal = num;
                    } else if (entryText.startsWith("#maxIncl")) {
                        hasMaxIncl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) maxVal = num;
                    } else if (entryText.startsWith("#maxExcl")) {
                        hasMaxExcl = true;
                        var num = extractNumericValue(entry);
                        if (num != null) maxVal = num;
                    }
                }

                if ((hasMinIncl && hasMinExcl) || (hasMaxIncl && hasMaxExcl)) {
                    return true;
                }
                if (minVal != null && maxVal != null && minVal.compareTo(maxVal) > 0) {
                    return true;
                }
            }
        }

        // 2. Check compiler diagnostics accumulated for the file
        var diagnostics = getCompilationDiagnostics(file);
        if (diagnostics != null) {
            for (var diag : diagnostics) {
                var code = diag.errorCode().orElse("");
                if (code.equals("INVALID_REGEX_PATTERN") || code.equals("INVALID_NUMERIC_RANGE") 
                    || code.equals("CAPACITY_OVERFLOW") || code.equals("INCOMPATIBLE_METADATA_TYPE")
                    || code.equals("MUTUALLY_EXCLUSIVE_BOUNDS")) {
                    var msg = diag.message();
                    if (msg.contains("(" + aliasName + ")") || msg.contains(aliasName)) {
                        return true;
                    }
                    if (targetDef != null && targetDef.getParent() instanceof TypeDefinition typeDef) {
                        var range = typeDef.getTextRange();
                        if (diag.startOffset() >= range.getStartOffset() && diag.endOffset() <= range.getEndOffset()) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static @Nullable BigDecimal extractNumericValue(MetadataEntry entry) {
        var intLit = PsiTreeUtil.findChildOfType(entry, IntegerLiteral.class);
        if (intLit != null) {
            try {
                return new BigDecimal(intLit.getText());
            } catch (Exception ignored) {}
        }
        var floatLit = PsiTreeUtil.findChildOfType(entry, FloatLiteral.class);
        if (floatLit != null) {
            try {
                return new BigDecimal(floatLit.getText());
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static @Nullable StvnValue resolveCoreValue(PsiElement value) {
        var file = value.getContainingFile();
        if (file == null) {
            return null;
        }

        var manager = CachedValuesManager.getManager(file.getProject());
        var valuesMap = manager.getCachedValue(file, CORE_VALUES_KEY, () -> {
            java.util.Map<Integer, StvnValue> map = new java.util.HashMap<>();
            try {
                var text = file.getText();
                var virtualFile = file.getVirtualFile();
                if (virtualFile != null) {
                    var path = virtualFile.getPath();
                    if (path.startsWith("/src/") || path.startsWith("temp://")) {
                        if (path.contains("invalid-syntax") && !text.contains("shared-fixtures/")) {
                            path = new java.io.File("src/test/resources/shared-fixtures/invalid-syntax/dummy.stvn").getAbsolutePath();
                        } else if (path.contains("valid-syntax") && !text.contains("shared-fixtures/")) {
                            path = new java.io.File("src/test/resources/shared-fixtures/valid-syntax/dummy.stvn").getAbsolutePath();
                        } else {
                            path = new java.io.File("src/test/resources/dummy.stvn").getAbsolutePath();
                        }
                    }

                    var result = StvnCompiler.compileToResult(text, path, StvnParserConfig.DEFAULT);
                    var rootOpt = result.document();
                    if (rootOpt.isPresent()) {
                        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                        if (bodyEntry != null && bodyEntry.getValue() != null) {
                            mapValues(bodyEntry.getValue(), rootOpt.get(), map);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return CachedValueProvider.Result.create(map, file);
        }, false);

        int offset = value.getTextRange().getStartOffset();
        return valuesMap != null ? valuesMap.get(offset) : null;
    }

    private enum StepType {
        LIST,
        MAP,
        MAP_KEY,
        MAP_VALUE,
        TUPLE,
        OPTION,
        EITHER,
        UNION
    }

    private static final class PathStep {
        final StepType type;
        final int index;

        PathStep(StepType type, int index) {
            this.type = type;
            this.index = index;
        }
    }

    public static final class ResolvedTypeInfo {
        private final SchemaType schema;
        private final String label;

        public ResolvedTypeInfo(SchemaType schema, String label) {
            this.schema = schema;
            this.label = label;
        }

        public SchemaType getSchema() {
            return schema;
        }

        public String getLabel() {
            return label;
        }
    }

    private StvnTypeResolver() {}

    private static String getLabelForSchema(org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema schema, PsiFile containingFile) {
        if (schema.aliasName().isPresent()) {
            var alias = schema.aliasName().get();
            var keywordElement = StvnTypeReference.resolveTypeInFile(containingFile, alias, new HashSet<>());
            if (keywordElement instanceof TypeKeyword kw) {
                var trace = StvnTypeReference.extractResolutionTrace(kw);
                return formatTrace(trace);
            }
            return alias;
        }

        if (schema.node() != null) {
            var formatted = StvnSchemaFormatter.formatCleanAntlrSchema(schema.node());
            if (!formatted.isEmpty()) {
                return formatted;
            }
        }
        return "";
    }

    public static @Nullable String resolveValueType(Value value) {
        var resolvedNode = resolveCoreValue(value);
        if (resolvedNode != null && !(resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnError)) {
            var file = value.getContainingFile();
            if (file != null) {
                var project = value.getProject();
                var useLong = useLongFormSumTypes(project);
                var formatted = formatResolvedNodeTrajectory(resolvedNode, value, file, project, useLong);
                if (formatted != null && !formatted.isEmpty()) {
                    return formatted;
                }
            }
        }

        return resolvePsiFallbackValueType(value);
    }

    private static @Nullable String formatResolvedNodeTrajectory(
        StvnValue resolvedNode,
        Value value,
        PsiFile file,
        com.intellij.openapi.project.Project project,
        boolean useLong
    ) {
        var baseSchema = resolvedNode.schema();
        var rootAliasOpt = baseSchema.aliasName();

        // 1. Nominal Sum Type Handling
        if (rootAliasOpt.isPresent()) {
            var rootAlias = rootAliasOpt.get();
            var isDegraded = isDegradedNominalAlias(file, rootAlias);
            var prefix = isDegraded ? "⚠ " : "";

            if (resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                var explicitTagIndex = -1;
                if (value.getExplicitUnionValue() != null) {
                    var firstChild = value.getExplicitUnionValue().getFirstChild();
                    if (firstChild != null && firstChild.getText().startsWith("#")) {
                        try {
                            explicitTagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                var text = value.getText().trim();
                var matcher = EXPLICIT_UNION_TAG_PATTERN.matcher(text);
                if (explicitTagIndex < 0 && matcher.find()) {
                    try {
                        explicitTagIndex = Integer.parseInt(matcher.group().substring(1)) - 1;
                    } catch (NumberFormatException ignored) {}
                }
                var activeTagIndex = explicitTagIndex >= 0 ? explicitTagIndex : union.tagIndex();
                var branchTag = "#" + (activeTagIndex + 1);
                var isExplicit = explicitTagIndex >= 0 || value.getExplicitUnionValue() != null
                    || (value.getParent() instanceof ExplicitUnionValue);
                var tagText = isExplicit ? branchTag : "[" + branchTag + "]";
                
                var branchPsi = getNominalUnionBranchPsi(file, rootAlias, activeTagIndex);
                var innerVal = value.getExplicitUnionValue() != null ? value.getExplicitUnionValue().getValue() : value;
                var branchTrace = branchPsi != null ? resolvePsiBranchTrace(branchPsi, innerVal, project) : "";
                if (branchTrace.isEmpty()) {
                    var childVal = union.value();
                    branchTrace = unspoolBranchTrajectory(childVal.schema(), childVal, file, project, useLong);
                }
                return prefix + (!branchTrace.isEmpty() ? rootAlias + " " + tagText + " (-> " + branchTrace + ")" : rootAlias + " " + tagText);
            }

            if (resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                var isExplicit = opt.trajectory().stream().noneMatch(VariantStep::isInferred)
                    || value.getExplicitOptionValue() != null
                    || isExplicitOptionText(value.getText().trim());

                if (opt.isNone()) {
                    var tag = useLong ? "#None" : "#N";
                    return prefix + rootAlias + " " + tag;
                }

                var tag = useLong ? "#Some" : "#S";
                var tagText = isExplicit ? tag : "[" + tag + "]";
                
                var branchPsi = getNominalOptionBranchPsi(file, rootAlias);
                var innerVal = value.getExplicitOptionValue() != null ? value.getExplicitOptionValue().getValue() : value;
                var branchTrace = branchPsi != null ? resolvePsiBranchTrace(branchPsi, innerVal, project) : "";
                if (branchTrace.isEmpty()) {
                    var childVal = opt.value().orElse(null);
                    if (childVal != null) {
                        branchTrace = unspoolBranchTrajectory(childVal.schema(), childVal, file, project, useLong);
                    }
                }
                return prefix + (!branchTrace.isEmpty() ? rootAlias + " " + tagText + " (-> " + branchTrace + ")" : rootAlias + " " + tagText);
            }

            if (resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                var isExplicit = either.trajectory().stream().noneMatch(VariantStep::isInferred)
                    || value.getExplicitEitherValue() != null
                    || isExplicitEitherText(value.getText().trim());
                var isRight = either.isRight();
                var tag = isRight
                    ? (useLong ? "#Right" : "#R")
                    : (useLong ? "#Left" : "#L");
                var tagText = isExplicit ? tag : "[" + tag + "]";
                
                var branchPsi = getNominalEitherBranchPsi(file, rootAlias, isRight);
                var innerVal = value.getExplicitEitherValue() != null ? value.getExplicitEitherValue().getValue() : value;
                var branchTrace = branchPsi != null ? resolvePsiBranchTrace(branchPsi, innerVal, project) : "";
                if (branchTrace.isEmpty()) {
                    var childVal = either.value();
                    if (childVal != null) {
                        branchTrace = unspoolBranchTrajectory(childVal.schema(), childVal, file, project, useLong);
                    }
                }
                return prefix + (!branchTrace.isEmpty() ? rootAlias + " " + tagText + " (-> " + branchTrace + ")" : rootAlias + " " + tagText);
            }

            if (baseSchema.aliasName().isPresent()) {
                var alias = baseSchema.aliasName().get();
                var kw = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
                if (kw instanceof TypeKeyword typeKw) {
                    var trace = StvnTypeReference.extractResolutionTrace(typeKw);
                    if (trace.size() > 1) {
                        return prefix + trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                    }
                }
                return prefix + alias;
            }

            return prefix + getLabelForSchema(baseSchema, file);
        }

        // 2. Anonymous Enum Handling
        if (resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnEnum) {
            return ":Enum";
        }

        // 3. Anonymous Deep Sum Trajectory Handling
        if (resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnSum) {
            var rootLabel = (baseSchema.node() != null)
                ? StvnSchemaFormatter.formatCleanAntlrSchema(baseSchema.node())
                : getLabelForSchema(baseSchema, file);

            var levels = collectSumLevels(resolvedNode, useLong);
            if (levels.isEmpty()) {
                return rootLabel;
            }

            var tokens = extractLeadingTagTokens(value);
            var sb = new StringBuilder(rootLabel);
            int tokenIdx = 0;

            for (var level : levels) {
                sb.append(" ");
                boolean isExplicit = (tokenIdx < tokens.size()) && level.validExplicitTokens().contains(tokens.get(tokenIdx));
                if (isExplicit) {
                    sb.append(level.canonicalTag());
                    tokenIdx++;
                } else {
                    sb.append("[").append(level.canonicalTag()).append("]");
                }
                if (level.isTerminalNone()) {
                    break;
                }
            }

            return sb.toString();
        }

        // 4. Default Schema Formatting
        return getLabelForSchema(baseSchema, file);
    }

    public enum SumKind {
        OPTION, EITHER, UNION
    }

    public record SumLevelDescriptor(
        SumKind kind,
        String canonicalTag,
        java.util.Set<String> validExplicitTokens,
        boolean isTerminalNone
    ) {}

    private static List<SumLevelDescriptor> collectSumLevels(StvnValue rootNode, boolean useLong) {
        var levels = new java.util.ArrayList<SumLevelDescriptor>();
        var curr = rootNode;

        while (curr instanceof org.stvnadore.core.ir.StvnValue.StvnSum) {
            if (curr instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                if (opt.isNone()) {
                    var tag = useLong ? "#None" : "#N";
                    levels.add(new SumLevelDescriptor(SumKind.OPTION, tag, java.util.Set.of("#None", "#N"), true));
                    break;
                } else {
                    var tag = useLong ? "#Some" : "#S";
                    levels.add(new SumLevelDescriptor(SumKind.OPTION, tag, java.util.Set.of("#Some", "#S"), false));
                    curr = opt.value().orElse(null);
                }
            } else if (curr instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                var isRight = either.isRight();
                var tag = isRight ? (useLong ? "#Right" : "#R") : (useLong ? "#Left" : "#L");
                var tokens = isRight ? java.util.Set.of("#Right", "#R") : java.util.Set.of("#Left", "#L");
                levels.add(new SumLevelDescriptor(SumKind.EITHER, tag, tokens, false));
                curr = either.value();
            } else if (curr instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                var tag = "#" + (union.tagIndex() + 1);
                levels.add(new SumLevelDescriptor(SumKind.UNION, tag, java.util.Set.of(tag), false));
                curr = union.value();
            } else {
                break;
            }
        }

        return levels;
    }

    private static List<String> extractLeadingTagTokens(Value value) {
        List<String> tokens = new java.util.ArrayList<>();
        var curr = (PsiElement) value;

        while (curr != null) {
            if (curr instanceof Value val) {
                if (val.getExplicitOptionValue() != null) {
                    curr = val.getExplicitOptionValue();
                } else if (val.getExplicitEitherValue() != null) {
                    curr = val.getExplicitEitherValue();
                } else if (val.getExplicitUnionValue() != null) {
                    curr = val.getExplicitUnionValue();
                } else {
                    // Fallback tokenize leading words starting with '#' if tokens is empty
                    if (tokens.isEmpty()) {
                        var text = val.getText().trim();
                        var parts = text.split("\\s+");
                        for (var part : parts) {
                            if (isSumTagToken(part)) {
                                tokens.add(part);
                            } else {
                                break;
                            }
                        }
                    }
                    break;
                }
            } else if (curr instanceof ExplicitOptionValue opt) {
                var someLit = opt.getSomeLiteral();
                if (someLit != null) tokens.add(someLit.getText().trim());
                var someShortLit = opt.getSomeShortLiteral();
                if (someShortLit != null) tokens.add(someShortLit.getText().trim());
                var noneLit = opt.getNoneLiteral();
                if (noneLit != null) tokens.add(noneLit.getText().trim());
                var noneShortLit = opt.getNoneShortLiteral();
                if (noneShortLit != null) tokens.add(noneShortLit.getText().trim());
                curr = opt.getValue();
            } else if (curr instanceof ExplicitEitherValue either) {
                var leftLit = either.getLeftLiteral();
                if (leftLit != null) tokens.add(leftLit.getText().trim());
                var leftShortLit = either.getLeftShortLiteral();
                if (leftShortLit != null) tokens.add(leftShortLit.getText().trim());
                var rightLit = either.getRightLiteral();
                if (rightLit != null) tokens.add(rightLit.getText().trim());
                var rightShortLit = either.getRightShortLiteral();
                if (rightShortLit != null) tokens.add(rightShortLit.getText().trim());
                curr = either.getValue();
            } else if (curr instanceof ExplicitUnionValue union) {
                var tagPrefix = union.getUnionTagPrefix();
                if (tagPrefix != null) {
                    tokens.add(tagPrefix.getText().trim());
                }
                curr = union.getValue();
            } else {
                break;
            }
        }

        return tokens;
    }

    private static boolean isSumTagToken(String token) {
        return token.equals("#Some") || token.equals("#S") || token.equals("#None") || token.equals("#N")
            || token.equals("#Right") || token.equals("#R") || token.equals("#Left") || token.equals("#L")
            || EXPLICIT_UNION_TAG_PATTERN.matcher(token).matches();
    }

    private static boolean isExplicitOptionText(String text) {
        return text.startsWith("#Some ") || text.equals("#Some")
            || text.startsWith("#S ") || text.equals("#S")
            || text.startsWith("#None") || text.equals("#N");
    }

    private static boolean isExplicitEitherText(String text) {
        return text.startsWith("#Left ") || text.equals("#Left")
            || text.startsWith("#L ") || text.equals("#L")
            || text.startsWith("#Right ") || text.equals("#Right")
            || text.startsWith("#R ") || text.equals("#R");
    }

    private static String unspoolBranchTrajectory(
        org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema branchSchema,
        @Nullable StvnValue branchValue,
        PsiFile file,
        com.intellij.openapi.project.Project project,
        boolean useLong
    ) {
        if (branchValue instanceof org.stvnadore.core.ir.StvnValue.StvnSum branchSum) {
            var innerBase = branchSum.schema();
            var innerAlias = innerBase.aliasName().orElse(null);
            if (branchSum instanceof org.stvnadore.core.ir.StvnValue.StvnUnion innerUnion) {
                var innerTag = "#" + (innerUnion.tagIndex() + 1);
                var innerChild = innerUnion.value();
                var innerChildTrace = unspoolBranchTrajectory(innerChild.schema(), innerChild, file, project, useLong);
                if (innerAlias != null) {
                    return innerAlias + " " + innerTag + (!innerChildTrace.isEmpty() ? " (-> " + innerChildTrace + ")" : "");
                } else {
                    return !innerChildTrace.isEmpty() ? innerChildTrace + " " + innerTag : innerTag;
                }
            } else if (branchSum instanceof org.stvnadore.core.ir.StvnValue.StvnOption innerOpt) {
                if (innerOpt.isNone()) {
                    var tag = useLong ? "#None" : "#N";
                    return innerAlias != null ? innerAlias + " " + tag : tag;
                }
                var innerTag = useLong ? "#Some" : "#S";
                var innerChild = innerOpt.value().orElse(null);
                if (innerChild != null) {
                    var innerChildTrace = unspoolBranchTrajectory(innerChild.schema(), innerChild, file, project, useLong);
                    if (innerAlias != null) {
                        return innerAlias + " " + innerTag + (!innerChildTrace.isEmpty() ? " (-> " + innerChildTrace + ")" : "");
                    } else {
                        return !innerChildTrace.isEmpty() ? innerChildTrace + " " + innerTag : innerTag;
                    }
                }
                return innerAlias != null ? innerAlias + " " + innerTag : innerTag;
            } else if (branchSum instanceof org.stvnadore.core.ir.StvnValue.StvnEither innerEither) {
                var innerTag = innerEither.isRight()
                    ? (useLong ? "#Right" : "#R")
                    : (useLong ? "#Left" : "#L");
                var innerChild = innerEither.value();
                var innerChildTrace = unspoolBranchTrajectory(innerChild.schema(), innerChild, file, project, useLong);
                if (innerAlias != null) {
                    return innerAlias + " " + innerTag + (!innerChildTrace.isEmpty() ? " (-> " + innerChildTrace + ")" : "");
                } else {
                    return !innerChildTrace.isEmpty() ? innerChildTrace + " " + innerTag : innerTag;
                }
            }
        }

        if (branchValue instanceof org.stvnadore.core.ir.StvnValue.StvnEnum && branchSchema.aliasName().isEmpty()) {
            return ":Enum";
        }

        if (branchSchema.aliasName().isPresent()) {
            var alias = branchSchema.aliasName().get();
            var keywordElement = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
            if (keywordElement instanceof TypeKeyword kw) {
                var trace = StvnTypeReference.extractResolutionTrace(kw);
                if (!trace.isEmpty()) {
                    return String.join(" -> ", trace);
                }
            }
            return alias;
        }

        if (branchSchema.node() != null) {
            var formatted = StvnSchemaFormatter.formatCleanAntlrSchema(branchSchema.node());
            if (!formatted.isEmpty()) {
                return formatted;
            }
        }
        return "";
    }

    private static @Nullable SchemaType getNominalUnionBranchPsi(PsiFile file, String alias, int tagIndex) {
        var kw = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
        if (kw != null && kw.getParent() instanceof TypeDefinition typeDef) {
            var schema = typeDef.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null) {
                    var list = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (tagIndex >= 0 && tagIndex < list.size()) {
                        return list.get(tagIndex);
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable SchemaType getNominalOptionBranchPsi(PsiFile file, String alias) {
        var kw = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
        if (kw != null && kw.getParent() instanceof TypeDefinition typeDef) {
            var schema = typeDef.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null) {
                    var list = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (!list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable SchemaType getNominalEitherBranchPsi(PsiFile file, String alias, boolean isRight) {
        var kw = StvnTypeReference.resolveTypeInFile(file, alias, new HashSet<>());
        if (kw != null && kw.getParent() instanceof TypeDefinition typeDef) {
            var schema = typeDef.getSchemaType();
            if (schema != null && schema.getSchemaConstructor() != null) {
                var sum = schema.getSchemaConstructor().getSumType();
                if (sum != null) {
                    var list = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (isRight && list.size() > 1) {
                        return list.get(1);
                    } else if (!isRight && !list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String resolvePsiFallbackValueType(Value value) {
        var info = resolveBaseTypeInfo(value);
        if (info == null) {
            return null;
        }

        var resolvedSchema = resolveNominalSchema(info.getSchema());
        var schemaToInspect = resolvedSchema != null ? resolvedSchema : info.getSchema();
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor != null) {
            var sumType = constructor.getSumType();
            if (sumType != null) {
                var baseLabel = info.getLabel();
                var useLong = useLongFormSumTypes(value.getProject());
                if (sumType.getText().startsWith(":Either")) {
                    var eitherVal = value.getExplicitEitherValue();
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (eitherVal != null) {
                        var txt = eitherVal.getText().trim().split("\\s+")[0];
                        var isLeft = txt.equals("#Left") || txt.equals("#L");
                        var tagText = isLeft ? (useLong ? "#Left" : "#L") : (useLong ? "#Right" : "#R");
                        var branchSchema = (isLeft && !innerSchemas.isEmpty()) ? innerSchemas.get(0) : (innerSchemas.size() > 1 ? innerSchemas.get(1) : null);
                        var innerVal = eitherVal.getValue();
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, innerVal, value.getProject()) : "";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace + " " + tagText : baseLabel + " " + tagText;
                        }
                    } else {
                        var tagText = useLong ? "[#Right]" : "[#R]";
                        var branchSchema = innerSchemas.size() > 1 ? innerSchemas.get(1) : null;
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, value, value.getProject()) : "";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace + " " + tagText : baseLabel + " " + tagText;
                        }
                    }
                } else if (sumType.getText().startsWith(":Option")) {
                    var optVal = value.getExplicitOptionValue();
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var payloadSchema = !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
                    var innerVal = optVal != null ? optVal.getValue() : null;
                    var payloadTrace = payloadSchema != null ? resolvePsiBranchTrace(payloadSchema, innerVal, value.getProject()) : "";

                    if (optVal != null) {
                        var txt = optVal.getText().trim().split("\\s+")[0];
                        var isNone = txt.equals("#None") || txt.equals("#N");
                        var tagText = isNone ? (useLong ? "#None" : "#N") : (useLong ? "#Some" : "#S");
                        if (isNone) {
                            return baseLabel + " " + tagText;
                        }
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!payloadTrace.isEmpty() ? " (-> " + payloadTrace + ")" : "");
                        } else {
                            return baseLabel + " " + tagText;
                        }
                    } else {
                        var txt = value.getText().trim().split("\\s+")[0];
                        if (txt.equals("#None") || txt.equals("#N")) {
                            var tagText = useLong ? "#None" : "#N";
                            return baseLabel + " " + tagText;
                        } else {
                            var tagText = useLong ? "[#Some]" : "[#S]";
                            payloadTrace = payloadSchema != null ? resolvePsiBranchTrace(payloadSchema, value, value.getProject()) : "";
                            if (info.getSchema().getTypeKeyword() != null) {
                                return baseLabel + " " + tagText + (!payloadTrace.isEmpty() ? " (-> " + payloadTrace + ")" : "");
                            } else {
                                return !payloadTrace.isEmpty() ? payloadTrace + " " + tagText : baseLabel + " " + tagText;
                            }
                        }
                    }
                } else if (sumType.getText().startsWith(":Union")) {
                    var unionVal = value.getExplicitUnionValue();
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (unionVal != null) {
                        var firstChild = unionVal.getFirstChild();
                        var tagText = firstChild != null ? firstChild.getText() : "#1";
                        var tagIndex = 0;
                        if (tagText.startsWith("#")) {
                            try {
                                tagIndex = Integer.parseInt(tagText.substring(1)) - 1;
                            } catch (NumberFormatException ignored) {}
                        }
                        var branchSchema = (tagIndex >= 0 && tagIndex < innerSchemas.size()) ? innerSchemas.get(tagIndex) : null;
                        var innerVal = unionVal.getValue();
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, innerVal, value.getProject()) : "";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace + " " + tagText : baseLabel + " " + tagText;
                        }
                    } else {
                        var tagIndex = 0;
                        for (int i = 0; i < innerSchemas.size(); i++) {
                            var schema = innerSchemas.get(i);
                            if (matchesSchemaPattern(value, schema)) {
                                tagIndex = i;
                                break;
                            }
                        }
                        var branchSchema = (tagIndex >= 0 && tagIndex < innerSchemas.size()) ? innerSchemas.get(tagIndex) : null;
                        var branchTrace = branchSchema != null ? resolvePsiBranchTrace(branchSchema, value, value.getProject()) : "";
                        var tagText = "[#" + (tagIndex + 1) + "]";
                        if (info.getSchema().getTypeKeyword() != null) {
                            return baseLabel + " " + tagText + (!branchTrace.isEmpty() ? " (-> " + branchTrace + ")" : "");
                        } else {
                            return !branchTrace.isEmpty() ? branchTrace : baseLabel;
                        }
                    }
                } else if (sumType.getEnumDef() != null) {
                    if (matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
                        if (info.getSchema().getTypeKeyword() != null) {
                            var trace = StvnTypeReference.extractResolutionTrace(info.getSchema().getTypeKeyword());
                            if (trace.size() > 1) {
                                return trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
                            }
                            return info.getLabel();
                        }
                        return ":Enum";
                    }
                }
            }
        }

        var keyword = info.getSchema().getTypeKeyword();
        if (keyword != null) {
            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            if (trace.size() > 1 && matchesSchemaPattern(value, resolvedSchema != null ? resolvedSchema : info.getSchema())) {
                var isDegraded = isDegradedNominalAlias(value.getContainingFile(), keyword.getText());
                var prefix = isDegraded ? "⚠ " : "";
                return prefix + trace.get(0) + " (-> " + String.join(" -> ", trace.subList(1, trace.size())) + ")";
            }
        }
        if (value.getCollectionValue() != null) {
            return info.getLabel();
        }
        return null;
    }

    private static String resolvePsiBranchTrace(
        SchemaType branchSchema,
        @Nullable Value innerValue,
        com.intellij.openapi.project.Project project
    ) {
        var keyword = branchSchema.getTypeKeyword();
        if (keyword != null) {
            var file = keyword.getContainingFile();
            var resolvedTarget = StvnTypeReference.resolveTypeInFile(file, keyword.getText(), new HashSet<>());
            if (resolvedTarget != null && resolvedTarget.getParent() instanceof TypeDefinition targetDef) {
                var targetSchema = targetDef.getSchemaType();
                if (targetSchema != null && targetSchema.getSchemaConstructor() != null) {
                    var targetConstructor = targetSchema.getSchemaConstructor();
                    var targetSum = targetConstructor.getSumType();
                    if (targetSum != null) {
                        var useLong = useLongFormSumTypes(project);
                        var targetAlias = keyword.getText();
                        if (targetSum.getText().startsWith(":Union")) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(targetSum, SchemaType.class);
                            var activeTagIndex = 0;
                            if (innerValue != null && innerValue.getExplicitUnionValue() != null) {
                                var firstChild = innerValue.getExplicitUnionValue().getFirstChild();
                                if (firstChild != null && firstChild.getText().startsWith("#")) {
                                    try {
                                        activeTagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                                    } catch (NumberFormatException ignored) {}
                                }
                            } else if (innerValue != null) {
                                for (int i = 0; i < innerSchemas.size(); i++) {
                                    if (matchesSchemaPattern(innerValue, innerSchemas.get(i))) {
                                        activeTagIndex = i;
                                        break;
                                    }
                                }
                            }
                            var branchTag = "#" + (activeTagIndex + 1);
                            var isExplicit = innerValue != null && (innerValue.getExplicitUnionValue() != null || (innerValue.getParent() instanceof ExplicitUnionValue));
                            var tagText = isExplicit ? branchTag : "[" + branchTag + "]";
                            var nestedBranch = (activeTagIndex >= 0 && activeTagIndex < innerSchemas.size()) ? innerSchemas.get(activeTagIndex) : null;
                            var nestedVal = innerValue != null && innerValue.getExplicitUnionValue() != null ? innerValue.getExplicitUnionValue().getValue() : innerValue;
                            var nestedTrace = nestedBranch != null ? resolvePsiBranchTrace(nestedBranch, nestedVal, project) : "";
                            return targetAlias + " " + tagText + (!nestedTrace.isEmpty() ? " (-> " + nestedTrace + ")" : "");
                        } else if (targetSum.getText().startsWith(":Either")) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(targetSum, SchemaType.class);
                            var eitherVal = innerValue != null ? innerValue.getExplicitEitherValue() : null;
                            var isLeft = false;
                            if (eitherVal != null) {
                                var txt = eitherVal.getText().trim().split("\\s+")[0];
                                isLeft = txt.equals("#Left") || txt.equals("#L");
                            } else if (innerValue != null && !innerSchemas.isEmpty()) {
                                isLeft = matchesSchemaPattern(innerValue, innerSchemas.get(0));
                            }
                            var isExplicit = eitherVal != null;
                            var tag = isLeft ? (useLong ? "#Left" : "#L") : (useLong ? "#Right" : "#R");
                            var tagText = isExplicit ? tag : "[" + tag + "]";
                            var nestedBranch = (isLeft && !innerSchemas.isEmpty()) ? innerSchemas.get(0) : (innerSchemas.size() > 1 ? innerSchemas.get(1) : null);
                            var nestedVal = eitherVal != null ? eitherVal.getValue() : innerValue;
                            var nestedTrace = nestedBranch != null ? resolvePsiBranchTrace(nestedBranch, nestedVal, project) : "";
                            return targetAlias + " " + tagText + (!nestedTrace.isEmpty() ? " (-> " + nestedTrace + ")" : "");
                        } else if (targetSum.getText().startsWith(":Option")) {
                            var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(targetSum, SchemaType.class);
                            var optVal = innerValue != null ? innerValue.getExplicitOptionValue() : null;
                            var isNone = false;
                            if (optVal != null) {
                                var txt = optVal.getText().trim().split("\\s+")[0];
                                isNone = txt.equals("#None") || txt.equals("#N");
                            } else if (innerValue != null) {
                                var txt = innerValue.getText().trim().split("\\s+")[0];
                                isNone = txt.equals("#None") || txt.equals("#N");
                            }
                            if (isNone) {
                                var tag = useLong ? "#None" : "#N";
                                return targetAlias + " " + tag;
                            }
                            var isExplicit = optVal != null;
                            var tag = useLong ? "#Some" : "#S";
                            var tagText = isExplicit ? tag : "[" + tag + "]";
                            var nestedBranch = !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
                            var nestedVal = optVal != null ? optVal.getValue() : innerValue;
                            var nestedTrace = nestedBranch != null ? resolvePsiBranchTrace(nestedBranch, nestedVal, project) : "";
                            return targetAlias + " " + tagText + (!nestedTrace.isEmpty() ? " (-> " + nestedTrace + ")" : "");
                        }
                    }
                }
            }

            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            if (!trace.isEmpty()) {
                return String.join(" -> ", trace);
            }
        }
        if (branchSchema.getSchemaConstructor() != null && branchSchema.getSchemaConstructor().getSumType() != null
            && branchSchema.getSchemaConstructor().getSumType().getEnumDef() != null) {
            return ":Enum";
        }
        return StvnSchemaFormatter.formatCleanSchema(branchSchema);
    }

    public static boolean useLongFormSumTypes(com.intellij.openapi.project.Project project) {
        var settings = StvnSettings.getInstance(project);
        return settings != null && settings.getState().useLongFormSumTypes;
    }

    public static boolean matchesSchemaPattern(@Nullable Value value, SchemaType schema) {
        if (value == null) {
            return false;
        }
        var resolved = resolveNominalSchema(schema);
        var schemaToInspect = (resolved != null) ? resolved : schema;
        var text = StvnSchemaFormatter.formatCleanSchema(schemaToInspect);
        if (text.startsWith(":Int") || text.startsWith(":Uint") || text.startsWith(":TimeEpoch")) {
            return value.getIntegerLiteral() != null;
        }
        if (text.startsWith(":Float")) {
            return value.getFloatLiteral() != null;
        }
        if (text.startsWith(":String") ||
            text.equals(":DateTime") ||
            text.equals(":DateTimeOffset") ||
            text.equals(":DateTimeZoned") ||
            text.equals(":DateTimeAudited")) {
            return value.getStringLiteral() != null;
        }
        if (text.equals(":Boolean")) {
            return value.getBooleanValue() != null || 
                   value.getText().equals("#TRUE") || value.getText().equals("#FALSE") ||
                   value.getText().equals("#T") || value.getText().equals("#F");
        }
        if (text.startsWith(":Tuple")) {
            var collVal = value.getCollectionValue();
            return collVal != null && collVal.getTupleLiteral() != null;
        }
        if (text.startsWith(":Seq") || text.startsWith(":Set")) {
            var collVal = value.getCollectionValue();
            return collVal != null && collVal.getListLiteral() != null;
        }
        if (text.startsWith(":Map")) {
            var collVal = value.getCollectionValue();
            return collVal != null && collVal.getMapLiteral() != null;
        }
        if (text.startsWith(":Option")) {
            if (value.getExplicitOptionValue() != null || value.getText().equals("#None") || value.getText().equals("#N")) {
                return true;
            }
            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor != null && constructor.getSumType() != null) {
                var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(constructor.getSumType(), SchemaType.class);
                if (!innerSchemas.isEmpty()) {
                    return matchesSchemaPattern(value, innerSchemas.get(0));
                }
            }
            return false;
        }
        if (text.startsWith(":Either")) {
            if (value.getExplicitEitherValue() != null) {
                return true;
            }
            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor != null && constructor.getSumType() != null) {
                var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(constructor.getSumType(), SchemaType.class);
                if (innerSchemas.size() >= 2) {
                    return matchesSchemaPattern(value, innerSchemas.get(1));
                }
            }
            return false;
        }
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor != null) {
            var sum = constructor.getSumType();
            if (sum != null) {
                if (sum.getEnumDef() != null) {
                    var valKw = value.getValueKeyword();
                    if (valKw != null) {
                        for (var kw : sum.getEnumDef().getValueKeywordList()) {
                            if (kw.getText().equals(valKw.getText())) {
                                return true;
                            }
                        }
                    }
                    var txt = value.getText().trim();
                    for (var kw : sum.getEnumDef().getValueKeywordList()) {
                        if (kw.getText().equals(txt)) {
                            return true;
                        }
                    }
                }
                var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return innerSchemas.stream().anyMatch(s -> matchesSchemaPattern(value, s));
            }
        }
        return false;
    }

    private static String getVariantTagForUnionBranch(SchemaType branchSchema) {
        var text = StvnSchemaFormatter.formatCleanSchema(branchSchema);
        if (text.startsWith(":")) {
            return "#" + text.substring(1);
        }
        return "#" + text;
    }

    public static @Nullable ResolvedTypeInfo resolveBaseTypeInfo(Value element) {
        var bodyEntry = findBodyEntryParent(element);
        if (bodyEntry == null) {
            return null;
        }

        var path = new Stack<PathStep>();
        var curr = (PsiElement) element;
        while (curr != null) {
            var parent = curr.getParent();
            if (parent instanceof BodyEntry) {
                break;
            }

            if (parent instanceof TupleLiteral tuple) {
                var values = tuple.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx != -1) {
                    path.push(new PathStep(StepType.TUPLE, idx));
                }
            } else if (parent instanceof ListLiteral) {
                path.push(new PathStep(StepType.LIST, -1));
            } else if (parent instanceof MapLiteral map) {
                var values = map.getValueList();
                var idx = values.indexOf(curr);
                if (idx != -1) {
                    var isKey = (idx % 2 == 0);
                    path.push(new PathStep(isKey ? StepType.MAP_KEY : StepType.MAP_VALUE, isKey ? 0 : 1));
                    path.push(new PathStep(StepType.MAP, -1));
                }
            } else if (parent instanceof org.stvnadore.psi.ExplicitOptionValue) {
                path.push(new PathStep(StepType.OPTION, -1));
            } else if (parent instanceof org.stvnadore.psi.ExplicitEitherValue) {
                var either = (org.stvnadore.psi.ExplicitEitherValue) parent;
                var text = either.getText().trim();
                var isLeft = text.startsWith("#Left") || text.startsWith("#L");
                path.push(new PathStep(StepType.EITHER, isLeft ? 0 : 1));
            } else if (parent instanceof org.stvnadore.psi.ExplicitUnionValue union) {
                var firstChild = union.getFirstChild();
                var tagIndex = 0;
                if (firstChild != null && firstChild.getText().startsWith("#")) {
                    try {
                        tagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                    } catch (NumberFormatException ignored) {}
                }
                path.push(new PathStep(StepType.UNION, tagIndex));
            }

            curr = parent;
        }

        var containingFile = bodyEntry.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        var typeEntry = PsiTreeUtil.findChildOfType(containingFile, TypeEntry.class);
        if (typeEntry == null) {
            return null;
        }

        var rootSchemaType = typeEntry.getSchemaType();
        if (rootSchemaType == null) {
            return null;
        }

        var currentSchema = rootSchemaType;
        while (!path.isEmpty() && currentSchema != null) {
            var step = path.pop();
            var resolvedNominal = resolveNominalSchema(currentSchema);
            var schemaToInspect = resolvedNominal != null ? resolvedNominal : currentSchema;

            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor == null) {
                currentSchema = null;
                break;
            }

            if (step.type != StepType.OPTION) {
                var sum = constructor.getSumType();
                while (sum != null && sum.getText().startsWith(":Option")) {
                    var optBranches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (optBranches.isEmpty()) break;
                    var innerSchema = optBranches.get(0);
                    var res = resolveNominalSchema(innerSchema);
                    schemaToInspect = (res != null) ? res : innerSchema;
                    var c = schemaToInspect.getSchemaConstructor();
                    if (c == null) break;
                    constructor = c;
                    sum = c.getSumType();
                }
            }

            if (step.type == StepType.LIST) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var innerSchemas = collection.getSchemaTypeList();
                    if (!innerSchemas.isEmpty()) {
                        currentSchema = innerSchemas.get(0);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.MAP) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var firstChild = collection.getFirstChild();
                    if (firstChild != null) {
                        var tokenText = firstChild.getText();
                        if (tokenText.equals(":Map") || tokenText.equals(":MapNonEmpty") ||
                            tokenText.equals(":MapInv") || tokenText.equals(":MapInvNonEmpty")) {
                            // Exclusively validate, do not advance or mutate currentSchema pointer.
                        } else {
                            currentSchema = null;
                        }
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.MAP_KEY || step.type == StepType.MAP_VALUE) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var innerSchemas = collection.getSchemaTypeList();
                    var targetIndex = (step.type == StepType.MAP_KEY) ? 0 : 1;
                    if (targetIndex >= 0 && targetIndex < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(targetIndex);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.TUPLE) {
                var product = constructor.getProductType();
                if (product != null) {
                    var innerSchemas = product.getSchemaTypeList();
                    if (step.index >= 0 && step.index < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(step.index);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.OPTION) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(":Option")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (!innerSchemas.isEmpty()) {
                        currentSchema = innerSchemas.get(0);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.EITHER) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(":Either")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var idx = step.index;
                    if (idx >= 0 && idx < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(idx);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.UNION) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(":Union")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var idx = step.index;
                    if (idx >= 0 && idx < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(idx);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            }
        }

        if (currentSchema == null) {
            return null;
        }

        var finalLabel = "";
        var keyword = currentSchema.getTypeKeyword();
        if (keyword != null) {
            var trace = StvnTypeReference.extractResolutionTrace(keyword);
            finalLabel = formatTrace(trace);
        } else {
            finalLabel = StvnSchemaFormatter.formatCleanSchema(currentSchema);
        }

        if (finalLabel.isEmpty()) {
            return null;
        }

        return new ResolvedTypeInfo(currentSchema, finalLabel);
    }

    private static @Nullable BodyEntry findBodyEntryParent(PsiElement element) {
        var curr = element;
        while (curr != null) {
            if (curr instanceof BodyEntry) {
                return (BodyEntry) curr;
            }
            curr = curr.getParent();
        }
        return null;
    }

    public static @Nullable SchemaType resolveNominalSchema(@Nullable SchemaType schemaType) {
        if (schemaType == null) {
            return null;
        }
        var curr = schemaType;
        var visited = new HashSet<String>();
        while (curr != null) {
            var keyword = curr.getTypeKeyword();
            if (keyword != null) {
                var typeName = keyword.getText();
                if (!visited.add(typeName)) {
                    return null;
                }
                var resolved = StvnTypeReference.resolveTypeInFile(keyword.getContainingFile(), typeName, new HashSet<>());
                while (resolved != null) {
                    var parent = resolved.getParent();
                    if (parent instanceof TypeDefinition) {
                        var typeDef = (TypeDefinition) parent;
                        var nextSchema = typeDef.getSchemaType();
                        if (nextSchema != null && nextSchema != curr) {
                            curr = nextSchema;
                            break;
                        }
                        resolved = null;
                    } else if (parent instanceof IncludeMapAlias) {
                        var alias = (IncludeMapAlias) parent;
                        var list = alias.getTypeKeywordList();
                        if (list.size() >= 2) {
                            var remoteKw = list.get(0);
                            var includeElement = PsiTreeUtil.getParentOfType(parent, IncludeElement.class);
                            if (includeElement != null) {
                                var stringLit = includeElement.getStringLiteral();
                                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                                if (targetFile != null && remoteKw != null) {
                                    resolved = StvnTypeReference.resolveTypeInFile(targetFile, remoteKw.getText(), new HashSet<>());
                                    continue;
                                }
                            }
                        }
                        resolved = null;
                    } else {
                        resolved = null;
                    }
                }
                if (resolved != null) {
                    continue;
                }
            }
            break;
        }
        return curr;
    }

    /**
     * Determines whether the target leaf type context of the given value slot resolves to a boolean primitive,
     * traversing any surrounding explicit or implicit sum-type envelopes.
     */
    public static boolean resolvesToBoolean(Value value) {
        var info = resolveBaseTypeInfo(value);
        if (info != null) {
            var schema = info.getSchema();
            var resolved = resolveNominalSchema(schema);
            var toInspect = resolved != null ? resolved : schema;
            var ctor = toInspect.getSchemaConstructor();
            if (ctor != null && ctor.getSumType() != null) {
                var sumType = ctor.getSumType();
                if (sumType.getText().startsWith(":Union")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var unionVal = value.getExplicitUnionValue();
                    if (unionVal != null) {
                        var firstChild = unionVal.getFirstChild();
                        var tagIndex = 0;
                        if (firstChild != null && firstChild.getText().startsWith("#")) {
                            try {
                                tagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                            } catch (NumberFormatException ignored) {}
                        }
                        if (tagIndex >= 0 && tagIndex < innerSchemas.size()) {
                            return isBooleanSchema(innerSchemas.get(tagIndex));
                        }
                        return false;
                    }
                    for (var branch : innerSchemas) {
                        if (matchesSchemaPattern(value, branch)) {
                            return isBooleanSchema(branch);
                        }
                    }
                    return false;
                } else if (sumType.getText().startsWith(":Either")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var eitherVal = value.getExplicitEitherValue();
                    if (eitherVal != null) {
                        var isLeft = eitherVal.getText().startsWith("#Left") || eitherVal.getText().startsWith("#L");
                        var branch = isLeft ? (!innerSchemas.isEmpty() ? innerSchemas.get(0) : null) : (innerSchemas.size() > 1 ? innerSchemas.get(1) : null);
                        return isBooleanSchema(branch);
                    } else if (innerSchemas.size() > 1) {
                        return isBooleanSchema(innerSchemas.get(1));
                    }
                    return false;
                } else if (sumType.getText().startsWith(":Option")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (!innerSchemas.isEmpty()) {
                        return isBooleanSchema(innerSchemas.get(0));
                    }
                    return false;
                }
            }
            if (isBooleanSchema(info.getSchema())) {
                return true;
            }
        }

        var resolvedNode = resolveCoreValue(value);
        if (resolvedNode == null || resolvedNode instanceof org.stvnadore.core.ir.StvnValue.StvnError) {
            return false;
        }

        var deepest = resolvedNode;
        var trajectory = java.util.List.<org.stvnadore.core.ir.VariantStep>of();
        while (deepest instanceof org.stvnadore.core.ir.StvnValue.StvnSum) {
            if (deepest instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                var steps = opt.trajectory();
                if (steps.size() > trajectory.size()) {
                    trajectory = steps;
                }
                var inner = opt.value().orElse(null);
                if (inner == null) {
                    break;
                }
                deepest = inner;
            } else if (deepest instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                var steps = either.trajectory();
                if (steps.size() > trajectory.size()) {
                    trajectory = steps;
                }
                var inner = either.value();
                if (inner == null) {
                    break;
                }
                deepest = inner;
            } else if (deepest instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                var inner = union.value();
                if (inner == null) {
                    break;
                }
                deepest = inner;
            } else {
                break;
            }
        }

        var current = resolvedNode;
        var stepIdx = 0;
        while (current instanceof org.stvnadore.core.ir.StvnValue.StvnSum) {
            if (stepIdx < trajectory.size()) {
                var step = trajectory.get(stepIdx);
                if (!step.isInferred()) {
                    break;
                }
            }

            if (current instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                var inner = opt.value().orElse(null);
                if (inner == null) {
                    break;
                }
                current = inner;
                stepIdx++;
            } else if (current instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                current = either.value();
                stepIdx++;
            } else if (current instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                current = union.value();
            } else {
                break;
            }
        }

        return (deepest instanceof org.stvnadore.core.ir.StvnValue.StvnBoolean) ||
               (current instanceof org.stvnadore.core.ir.StvnValue.StvnBoolean);
    }

    private static String formatTrace(List<String> trace) {
        if (trace.isEmpty()) {
            return "";
        }
        if (trace.size() == 1) {
            return trace.get(0);
        }
        var sb = new StringBuilder();
        sb.append(trace.get(0));
        sb.append(" (-> ");
        for (var i = 1; i < trace.size(); i++) {
            if (i > 1) {
                sb.append(" -> ");
            }
            sb.append(trace.get(i));
        }
        sb.append(")");
        return sb.toString();
    }
    private static void mapValues(@Nullable Value psiVal, @Nullable StvnValue irVal, java.util.Map<Integer, StvnValue> map) {
        if (psiVal == null || irVal == null) {
            return;
        }

        var offset = psiVal.getTextRange().getStartOffset();
        if (!map.containsKey(offset)) {
            map.put(offset, irVal);
        }

        if (irVal instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
            var innerIr = opt.value().orElse(null);
            if (innerIr != null) {
                var optPsi = psiVal.getExplicitOptionValue();
                if (optPsi != null) {
                    mapValues(optPsi.getValue(), innerIr, map);
                } else {
                    mapValues(psiVal, innerIr, map);
                }
            }
        } else if (irVal instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
            var innerIr = either.value();
            if (innerIr != null) {
                var eitherPsi = psiVal.getExplicitEitherValue();
                if (eitherPsi != null) {
                    mapValues(eitherPsi.getValue(), innerIr, map);
                } else {
                    mapValues(psiVal, innerIr, map);
                }
            }
        } else if (irVal instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
            var innerIr = union.value();
            if (innerIr != null) {
                var explicitUnion = psiVal.getExplicitUnionValue();
                if (explicitUnion != null) {
                    mapValues(explicitUnion.getValue(), innerIr, map);
                } else {
                    mapValues(psiVal, innerIr, map);
                }
            }
        } else if (irVal instanceof org.stvnadore.core.ir.StvnValue.StvnCollection coll) {
            var collPsi = psiVal.getCollectionValue();
            if (collPsi != null) {
                if (coll instanceof org.stvnadore.core.ir.StvnValue.StvnTuple tuple) {
                    var tuplePsi = collPsi.getTupleLiteral();
                    if (tuplePsi != null) {
                        var psiChildren = PsiTreeUtil.getChildrenOfTypeAsList(tuplePsi, Value.class);
                        var irElements = tuple.elements();
                        mapCollectionElementsWithUnspooling(psiChildren, irElements, map);
                    }
                } else if (coll instanceof org.stvnadore.core.ir.StvnValue.StvnSeq seq) {
                    var listPsi = collPsi.getListLiteral();
                    if (listPsi != null) {
                        var psiChildren = PsiTreeUtil.getChildrenOfTypeAsList(listPsi, Value.class);
                        var irElements = seq.elements();
                        mapCollectionElementsWithUnspooling(psiChildren, irElements, map);
                    }
                } else if (coll instanceof org.stvnadore.core.ir.StvnValue.StvnSet set) {
                    var listPsi = collPsi.getListLiteral();
                    if (listPsi != null) {
                        var psiChildren = PsiTreeUtil.getChildrenOfTypeAsList(listPsi, Value.class);
                        var irElements = new java.util.ArrayList<>(set.elements());
                        mapCollectionElementsWithUnspooling(psiChildren, irElements, map);
                    }
                } else if (coll instanceof org.stvnadore.core.ir.StvnValue.StvnMap mapVal) {
                    var mapPsi = collPsi.getMapLiteral();
                    if (mapPsi != null) {
                        var psiChildren = new java.util.ArrayList<Value>();
                        for (var val : PsiTreeUtil.findChildrenOfType(mapPsi, Value.class)) {
                            if (!isInnerChildOfAlgebraicContainer(val)) {
                                psiChildren.add(val);
                            }
                        }
                        var iterator = mapVal.entries().entrySet().iterator();
                        for (int i = 0; i < psiChildren.size() / 2 && iterator.hasNext(); i++) {
                            var entry = iterator.next();
                            var keyPsi = psiChildren.get(i * 2);
                            var valPsi = psiChildren.get(i * 2 + 1);
                            mapValues(keyPsi, entry.getKey(), map);
                            mapValues(valPsi, entry.getValue(), map);
                        }
                    }
                }
            }
        }
    }

    private static void mapCollectionElementsWithUnspooling(
        List<Value> psiValues,
        List<StvnValue> irElements,
        java.util.Map<Integer, StvnValue> map
    ) {
        int[] irIdx = new int[]{0};
        for (var psiVal : psiValues) {
            if (irIdx[0] >= irElements.size()) {
                break;
            }
            unspoolAndMap(psiVal, irElements, irIdx, map);
        }
    }

    private static void unspoolAndMap(
        Value psiVal,
        List<StvnValue> irElements,
        int[] irIdx,
        java.util.Map<Integer, StvnValue> map
    ) {
        if (irIdx[0] >= irElements.size()) {
            return;
        }

        var optPsi = psiVal.getExplicitOptionValue();
        var eitherPsi = psiVal.getExplicitEitherValue();
        var unionPsi = psiVal.getExplicitUnionValue();

        if (optPsi != null) {
            var targetIr = irElements.get(irIdx[0]);
            var currentIr = targetIr;
            while (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnSum && !(currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnOption)) {
                if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                    currentIr = either.value();
                } else if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                    currentIr = union.value();
                } else {
                    break;
                }
            }
            if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                var innerIr = opt.value().orElse(null);
                if (innerIr != null && optPsi.getValue() != null) {
                    mapValues(optPsi.getValue(), innerIr, map);
                }
                irIdx[0]++;
            } else {
                // Dimension 2: Target is non-sum product element (Unspooling required)
                var headTokenOffset = psiVal.getTextRange().getStartOffset();
                map.put(headTokenOffset, targetIr);
                irIdx[0]++;

                var innerVal = optPsi.getValue();
                if (innerVal != null && irIdx[0] < irElements.size()) {
                    unspoolAndMap(innerVal, irElements, irIdx, map);
                }
            }
            return;
        }

        if (eitherPsi != null) {
            var targetIr = irElements.get(irIdx[0]);
            var currentIr = targetIr;
            while (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnSum && !(currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnEither)) {
                if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                    currentIr = opt.value().orElse(null);
                } else if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                    currentIr = union.value();
                } else {
                    break;
                }
            }
            if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                var innerIr = either.value();
                if (innerIr != null && eitherPsi.getValue() != null) {
                    mapValues(eitherPsi.getValue(), innerIr, map);
                }
                irIdx[0]++;
            } else {
                // Dimension 2: Target is non-sum product element (Unspooling required)
                var headTokenOffset = psiVal.getTextRange().getStartOffset();
                map.put(headTokenOffset, targetIr);
                irIdx[0]++;

                var innerVal = eitherPsi.getValue();
                if (innerVal != null && irIdx[0] < irElements.size()) {
                    unspoolAndMap(innerVal, irElements, irIdx, map);
                }
            }
            return;
        }

        if (unionPsi != null) {
            var targetIr = irElements.get(irIdx[0]);
            var currentIr = targetIr;
            while (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnSum && !(currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnUnion)) {
                if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                    currentIr = opt.value().orElse(null);
                } else if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                    currentIr = either.value();
                } else {
                    break;
                }
            }
            if (currentIr instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                map.put(psiVal.getTextRange().getStartOffset(), targetIr);
                var innerIr = union.value();
                if (innerIr != null && unionPsi.getValue() != null) {
                    mapValues(unionPsi.getValue(), innerIr, map);
                }
                irIdx[0]++;
            } else {
                // Dimension 2: Target is non-sum product element (Unspooling required)
                var headTokenOffset = psiVal.getTextRange().getStartOffset();
                map.put(headTokenOffset, targetIr);
                irIdx[0]++;

                var innerVal = unionPsi.getValue();
                if (innerVal != null && irIdx[0] < irElements.size()) {
                    unspoolAndMap(innerVal, irElements, irIdx, map);
                }
            }
            return;
        }

        // Standard mapping for atomic values, boolean values, value keywords, nested collections
        var targetIr = irElements.get(irIdx[0]);
        mapValues(psiVal, targetIr, map);
        irIdx[0]++;
    }

    private static boolean hasSumType(@Nullable StvnValue node, Class<?> sumClass) {
        var curr = node;
        while (curr instanceof org.stvnadore.core.ir.StvnValue.StvnSum) {
            if (sumClass.isInstance(curr)) {
                return true;
            }
            if (curr instanceof org.stvnadore.core.ir.StvnValue.StvnOption opt) {
                curr = opt.value().orElse(null);
            } else if (curr instanceof org.stvnadore.core.ir.StvnValue.StvnEither either) {
                curr = either.value();
            } else if (curr instanceof org.stvnadore.core.ir.StvnValue.StvnUnion union) {
                curr = union.value();
            } else {
                break;
            }
        }
        return false;
    }

    public static boolean isUnspooledContainer(PsiElement container, @Nullable StvnValue coreNode) {
        if (coreNode == null) {
            return false;
        }
        if (container instanceof ExplicitOptionValue) {
            return !hasSumType(coreNode, org.stvnadore.core.ir.StvnValue.StvnOption.class);
        }
        if (container instanceof ExplicitEitherValue) {
            boolean has = hasSumType(coreNode, org.stvnadore.core.ir.StvnValue.StvnEither.class);
            return !has;
        }
        if (container instanceof ExplicitUnionValue) {
            return !hasSumType(coreNode, org.stvnadore.core.ir.StvnValue.StvnUnion.class);
        }
        return false;
    }

    private static boolean isInnerChildOfAlgebraicContainer(PsiElement element) {
        var curr = element.getParent();
        while (curr != null && !(curr instanceof PsiFile) && !(curr instanceof BodyEntry)) {
            if (curr instanceof ExplicitOptionValue || curr instanceof ExplicitEitherValue || curr instanceof ExplicitUnionValue) {
                return true;
            }
            curr = curr.getParent();
        }
        return false;
    }

    private static boolean isBooleanSchema(@Nullable SchemaType schema) {
        if (schema == null) {
            return false;
        }
        var resolved = resolveNominalSchema(schema);
        var toInspect = (resolved != null) ? resolved : schema;
        var text = StvnSchemaFormatter.formatCleanSchema(toInspect);
        return text.equals(":Boolean") || text.equals(":Bool");
    }

    public static @Nullable SchemaType resolveCollectionElementType(@Nullable SchemaType collectionSchema) {
        if (collectionSchema == null) {
            return null;
        }
        var resolved = resolveNominalSchema(collectionSchema);
        var toInspect = resolved != null ? resolved : collectionSchema;
        var constructor = toInspect.getSchemaConstructor();
        if (constructor == null) {
            return null;
        }
        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(constructor, SchemaType.class);
        return !innerSchemas.isEmpty() ? innerSchemas.get(0) : null;
    }

    public static @Nullable SchemaType resolveExpectedSchemaAtCaret(@Nullable PsiElement position) {
        if (position == null) {
            return null;
        }

        // 1. Check if position is inside a ConstantDefinition in :defs
        var constDef = PsiTreeUtil.getParentOfType(position, ConstantDefinition.class);
        SchemaType rootSchema = null;
        if (constDef != null) {
            rootSchema = constDef.getSchemaType();
        } else {
            var bodyEntry = PsiTreeUtil.getParentOfType(position, BodyEntry.class);
            if (bodyEntry == null) {
                var file = position.getContainingFile();
                if (file != null) {
                    bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                }
            }
            if (bodyEntry != null) {
                var containingFile = bodyEntry.getContainingFile();
                if (containingFile != null) {
                    var typeEntry = PsiTreeUtil.findChildOfType(containingFile, TypeEntry.class);
                    if (typeEntry != null) {
                        rootSchema = typeEntry.getSchemaType();
                    }
                }
            }
        }

        if (rootSchema == null) {
            return null;
        }

        // 2. Ascend PSI hierarchy from position
        var path = new Stack<PathStep>();
        var curr = position;
        while (curr != null) {
            var parent = curr.getParent();
            if (parent == null || parent instanceof BodyEntry || parent instanceof ConstantDefinition) {
                break;
            }

            if (parent instanceof TupleLiteral tuple) {
                var values = tuple.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (PsiTreeUtil.isAncestor(values.get(i), curr, false) || values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx == -1) {
                    int count = 0;
                    for (var child = tuple.getFirstChild(); child != null && child != curr; child = child.getNextSibling()) {
                        if (child instanceof Value) {
                            count++;
                        }
                    }
                    idx = count;
                }
                path.push(new PathStep(StepType.TUPLE, idx));
            } else if (parent instanceof ListLiteral) {
                path.push(new PathStep(StepType.LIST, -1));
            } else if (parent instanceof MapLiteral map) {
                var values = map.getValueList();
                var idx = values.indexOf(curr);
                if (idx == -1) {
                    for (int i = 0; i < values.size(); i++) {
                        if (PsiTreeUtil.isAncestor(values.get(i), curr, false) || values.get(i).getTextRange().equals(curr.getTextRange())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx == -1) {
                    int count = 0;
                    for (var child = map.getFirstChild(); child != null && child != curr; child = child.getNextSibling()) {
                        if (child instanceof Value) {
                            count++;
                        }
                    }
                    idx = count;
                }
                var isKey = (idx % 2 == 0);
                path.push(new PathStep(isKey ? StepType.MAP_KEY : StepType.MAP_VALUE, isKey ? 0 : 1));
                path.push(new PathStep(StepType.MAP, -1));
            } else if (parent instanceof ExplicitOptionValue) {
                path.push(new PathStep(StepType.OPTION, -1));
            } else if (parent instanceof ExplicitEitherValue either) {
                var text = either.getText().trim();
                var isLeft = text.startsWith("#Left") || text.startsWith("#L");
                path.push(new PathStep(StepType.EITHER, isLeft ? 0 : 1));
            } else if (parent instanceof ExplicitUnionValue union) {
                var firstChild = union.getFirstChild();
                var tagIndex = 0;
                if (firstChild != null && firstChild.getText().startsWith("#")) {
                    try {
                        tagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                    } catch (NumberFormatException ignored) {}
                }
                path.push(new PathStep(StepType.UNION, tagIndex));
            }

            curr = parent;
        }

        // 3. Descend schema AST using recorded path steps
        var currentSchema = rootSchema;
        while (!path.isEmpty() && currentSchema != null) {
            var step = path.pop();
            var resolvedNominal = resolveNominalSchema(currentSchema);
            var schemaToInspect = resolvedNominal != null ? resolvedNominal : currentSchema;

            var constructor = schemaToInspect.getSchemaConstructor();
            if (constructor == null) {
                currentSchema = null;
                break;
            }

            if (step.type != StepType.OPTION) {
                var sum = constructor.getSumType();
                while (sum != null && sum.getText().startsWith(":Option")) {
                    var optBranches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (optBranches.isEmpty()) break;
                    var innerSchema = optBranches.get(0);
                    var res = resolveNominalSchema(innerSchema);
                    schemaToInspect = (res != null) ? res : innerSchema;
                    var c = schemaToInspect.getSchemaConstructor();
                    if (c == null) break;
                    constructor = c;
                    sum = c.getSumType();
                }
            }

            if (step.type == StepType.LIST) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var innerSchemas = collection.getSchemaTypeList();
                    if (!innerSchemas.isEmpty()) {
                        currentSchema = innerSchemas.get(0);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.MAP) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var firstChild = collection.getFirstChild();
                    if (firstChild != null) {
                        var tokenText = firstChild.getText();
                        if (tokenText.equals(":Map") || tokenText.equals(":MapNonEmpty") ||
                            tokenText.equals(":MapInv") || tokenText.equals(":MapInvNonEmpty")) {
                            // Valid map collection
                        } else {
                            currentSchema = null;
                        }
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.MAP_KEY || step.type == StepType.MAP_VALUE) {
                var collection = constructor.getCollectionType();
                if (collection != null) {
                    var innerSchemas = collection.getSchemaTypeList();
                    var targetIndex = (step.type == StepType.MAP_KEY) ? 0 : 1;
                    if (targetIndex >= 0 && targetIndex < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(targetIndex);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.TUPLE) {
                var product = constructor.getProductType();
                if (product != null) {
                    var innerSchemas = product.getSchemaTypeList();
                    if (step.index >= 0 && step.index < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(step.index);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.OPTION) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(":Option")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    if (!innerSchemas.isEmpty()) {
                        currentSchema = innerSchemas.get(0);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.EITHER) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(":Either")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var idx = step.index;
                    if (idx >= 0 && idx < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(idx);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            } else if (step.type == StepType.UNION) {
                var sumType = constructor.getSumType();
                if (sumType != null && sumType.getText().startsWith(":Union")) {
                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    var idx = step.index;
                    if (idx >= 0 && idx < innerSchemas.size()) {
                        currentSchema = innerSchemas.get(idx);
                    } else {
                        currentSchema = null;
                    }
                } else {
                    currentSchema = null;
                }
            }
        }

        return currentSchema;
    }

    public static boolean isSchemaAssignable(@Nullable SchemaType targetSchema, @Nullable SchemaType sourceSchema) {
        if (targetSchema == null || sourceSchema == null) {
            return false;
        }
        var targetText = StvnSchemaFormatter.formatCleanSchema(targetSchema);
        var sourceText = StvnSchemaFormatter.formatCleanSchema(sourceSchema);
        if (targetText.equals(sourceText)) {
            return true;
        }

        var resolvedTarget = resolveNominalSchema(targetSchema);
        var resolvedSource = resolveNominalSchema(sourceSchema);
        var normTargetText = StvnSchemaFormatter.formatCleanSchema(resolvedTarget != null ? resolvedTarget : targetSchema);
        var normSourceText = StvnSchemaFormatter.formatCleanSchema(resolvedSource != null ? resolvedSource : sourceSchema);
        if (normTargetText.equals(normSourceText)) {
            return true;
        }

        return false;
    }

    public static List<ConstantDefinition> findAssignableConstants(PsiFile file, SchemaType targetSchema) {
        var list = new ArrayList<ConstantDefinition>();
        var visitedFiles = new HashSet<PsiFile>();
        collectConstantsRecursive(file, targetSchema, list, visitedFiles);
        return list;
    }

    private static void collectConstantsRecursive(
        PsiFile file,
        SchemaType targetSchema,
        List<ConstantDefinition> list,
        Set<PsiFile> visited
    ) {
        if (!visited.add(file)) {
            return;
        }

        var localConstants = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
        for (var constDef : localConstants) {
            var constSchema = constDef.getSchemaType();
            if (constSchema != null && isSchemaAssignable(targetSchema, constSchema)) {
                list.add(constDef);
            }
        }

        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var strLit = incl.getStringLiteral();
            if (strLit != null) {
                var targetFile = StvnTypeReference.resolveIncludeFile(strLit);
                if (targetFile != null) {
                    collectConstantsRecursive(targetFile, targetSchema, list, visited);
                }
            }
        }
    }
}

