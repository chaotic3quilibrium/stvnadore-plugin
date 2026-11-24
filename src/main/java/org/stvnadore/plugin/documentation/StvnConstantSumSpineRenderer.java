package org.stvnadore.plugin.documentation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ExplicitEitherValue;
import org.stvnadore.psi.ExplicitOptionValue;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Renders inferred sum-spine hover rows ({@code Expanded Value:}, {@code Inferred Defaults:},
 * and clickable {@code Resolution Path:}) for well-typed sum-governed {@link ConstantDefinition}
 * declarations in {@code :defs} and {@code :body} {@code #CONST} references.
 */
@NullMarked
public final class StvnConstantSumSpineRenderer {

    private static final int MAX_SPINE_DEPTH = 16;

    private StvnConstantSumSpineRenderer() {}

    /**
     * Renders the HTML rows ({@code Expanded Value:}, {@code Inferred Defaults:}, and {@code Resolution Path:})
     * for {@code constDef} when its governing schema resolves to a coproduct sum ({@code :Option}, {@code :Either},
     * or {@code :Union}) and its right-hand-side value is well-typed.
     *
     * @param constDef    target typed constant declaration in {@code :defs}
     * @param useLongForm {@code true} to format sum tags in long form ({@code #Some}/{@code #Right});
     *                    {@code false} for short form ({@code #S}/{@code #R})
     * @return formatted HTML snippet starting with {@code <br/>}, or {@code null} when inapplicable or ill-typed
     */
    public static @Nullable String renderConstantSumSpineRows(
        ConstantDefinition constDef,
        boolean useLongForm
    ) {
        if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)
                || PsiTreeUtil.hasErrorElements(constDef)) {
            return null;
        }
        SchemaType declaredSchema = constDef.getSchemaType();
        Value declaredValue = constDef.getValue();
        ValueKeyword constKw = constDef.getValueKeyword();
        PsiFile constFile = constDef.getContainingFile();
        if (declaredSchema == null || declaredValue == null || constKw == null || constFile == null) {
            return null;
        }
        if (!resolvesToCoproductSumSchema(declaredSchema)) {
            return null;
        }

        if (unwrapConstantChainValue(declaredValue) == null) {
            return null;
        }

        boolean isPrelude = StvnPreludeBridge.isPreludeFile(constFile);
        PackageEnclosure enclosingPkg = PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class);
        if (enclosingPkg != null && StvnPsiUtils.hasIllegalTrailingSlash(enclosingPkg)) {
            return null;
        }
        String pkgPath = (enclosingPkg != null && enclosingPkg.getPackagePath() != null)
            ? enclosingPkg.getPackagePath().getText().trim()
            : null;
        String constName = constKw.getText().trim();

        Optional<StvnDefsCatalog.StvnConstant> loweredConstOpt;
        if (isPrelude) {
            loweredConstOpt = StvnPreludeBridge.getPreludeCatalog().findConstant(pkgPath, constName);
        } else {
            var snapshot = StvnCompiledDocumentCache.getSnapshot(constFile);
            if (hasActiveCompilerErrorOnConstant(constFile, snapshot.normalizedDocPath(), snapshot.diagnostics(), constDef)) {
                return null;
            }
            loweredConstOpt = snapshot.catalog().findConstant(pkgPath, constName);
        }

        StvnValue rootIrValue = null;
        if (loweredConstOpt.isPresent()) {
            rootIrValue = loweredConstOpt.get().value();
            if (containsErrorValue(rootIrValue)) {
                return null;
            }
        } else if (StvnDocumentationProvider.hasPurePsiConstantValueViolation(constDef)) {
            return null;
        }

        List<String> expandedTokens = new ArrayList<>();
        List<String> inferredTagSegments = new ArrayList<>();
        List<String> resolutionPathSegments = new ArrayList<>();
        Set<PsiElement> visitedAliases = new HashSet<>();

        SchemaType currPsiSchema = declaredSchema;
        Value currPsiValue = declaredValue;
        StvnValue currIrValue = rootIrValue;
        boolean sawAtLeastOneSumStep = false;
        boolean terminatedAtNone = false;

        for (int depth = 0; depth < MAX_SPINE_DEPTH && currPsiSchema != null; depth++) {
            SchemaType layerStartSchema = currPsiSchema;
            currPsiSchema = appendAndUnwrapNominalHops(currPsiSchema, resolutionPathSegments, visitedAliases);
            if (currPsiSchema == null) {
                return null;
            }
            SchemaConstructor ctor = currPsiSchema.getSchemaConstructor();
            SumType sumPsi = ctor != null ? ctor.getSumType() : null;
            if (sumPsi == null || sumPsi.getEnumDef() != null) {
                break;
            }

            currPsiValue = unwrapConstantForCurrentSumLayer(currPsiValue, layerStartSchema, currPsiSchema);
            List<SchemaType> branches = PsiTreeUtil.getChildrenOfTypeAsList(sumPsi, SchemaType.class);
            String sumText = sumPsi.getText().trim();

            if (isOptionSum(sumPsi, sumText)) {
                if (branches.isEmpty()) {
                    return null;
                }
                sawAtLeastOneSumStep = true;
                ExplicitOptionValue explicitOpt = currPsiValue != null ? currPsiValue.getExplicitOptionValue() : null;
                boolean isNone = resolveIsOptionNone(currPsiValue, explicitOpt, currIrValue);
                if (isNone) {
                    String noneTag = useLongForm ? StvnVocabulary.VAL_NONE : StvnVocabulary.VAL_NONE_SHORT;
                    expandedTokens.add(noneTag);
                    resolutionPathSegments.add("<code>" + escapeHtml(noneTag) + "</code>");
                    terminatedAtNone = true;
                    break;
                }
                boolean isExplicit = explicitOpt != null;
                String someTag = useLongForm ? StvnVocabulary.VAL_SOME : StvnVocabulary.VAL_SOME_SHORT;
                if (isExplicit) {
                    expandedTokens.add(someTag);
                    resolutionPathSegments.add("<code>" + escapeHtml(someTag) + "</code>");
                    currPsiValue = explicitOpt.getValue();
                } else {
                    String bracketed = StvnVocabulary.DELIM_OPEN_BRACKET + someTag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                    expandedTokens.add(bracketed);
                    String codeBadge = "<code>" + escapeHtml(bracketed) + "</code>";
                    inferredTagSegments.add(codeBadge);
                    resolutionPathSegments.add(codeBadge);
                }
                if (currIrValue instanceof StvnValue.StvnOption optIr) {
                    currIrValue = optIr.value().orElse(null);
                }
                currPsiSchema = branches.get(0);
            } else if (isEitherSum(sumPsi, sumText)) {
                if (branches.size() < 2) {
                    return null;
                }
                sawAtLeastOneSumStep = true;
                ExplicitEitherValue explicitEither = currPsiValue != null ? currPsiValue.getExplicitEitherValue() : null;
                boolean isRight;
                if (currIrValue instanceof StvnValue.StvnEither eitherIr) {
                    isRight = eitherIr.branch() == StvnValue.EitherBranch.RIGHT;
                } else if (explicitEither != null) {
                    isRight = explicitEither.getRightLiteral() != null || explicitEither.getRightShortLiteral() != null;
                } else {
                    // Under Rule B and Rule E, an untagged value in :Either always targets the Right branch
                    isRight = true;
                }
                String eitherTag = isRight
                    ? (useLongForm ? StvnVocabulary.VAL_RIGHT : StvnVocabulary.VAL_RIGHT_SHORT)
                    : (useLongForm ? StvnVocabulary.VAL_LEFT : StvnVocabulary.VAL_LEFT_SHORT);
                if (explicitEither != null) {
                    expandedTokens.add(eitherTag);
                    resolutionPathSegments.add("<code>" + escapeHtml(eitherTag) + "</code>");
                    currPsiValue = explicitEither.getValue();
                } else {
                    if (!isRight) {
                        return null;
                    }
                    String bracketed = StvnVocabulary.DELIM_OPEN_BRACKET + eitherTag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                    expandedTokens.add(bracketed);
                    String codeBadge = "<code>" + escapeHtml(bracketed) + "</code>";
                    inferredTagSegments.add(codeBadge);
                    resolutionPathSegments.add(codeBadge);
                }
                if (currIrValue instanceof StvnValue.StvnEither eitherIr) {
                    currIrValue = eitherIr.value();
                }
                currPsiSchema = isRight ? branches.get(1) : branches.get(0);
            } else if (isUnionSum(sumPsi, sumText)) {
                if (branches.size() < 2) {
                    return null;
                }
                sawAtLeastOneSumStep = true;
                ExplicitUnionValue explicitUnion = currPsiValue != null ? currPsiValue.getExplicitUnionValue() : null;
                int zeroIdx;
                String unionTag;
                if (explicitUnion != null) {
                    String prefixText = explicitUnion.getUnionTagPrefix().getText().trim();
                    if (!prefixText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                        return null;
                    }
                    Optional<BigInteger> parsed = StvnPsiLiteralEvaluator.parseBigInteger(prefixText.substring(1));
                    if (parsed.isEmpty()
                            || parsed.get().compareTo(BigInteger.ONE) < 0
                            || parsed.get().compareTo(BigInteger.valueOf(branches.size())) > 0) {
                        return null;
                    }
                    zeroIdx = parsed.get().intValue() - 1;
                    unionTag = StvnVocabulary.SIGIL_VALUE + parsed.get();
                    expandedTokens.add(unionTag);
                    resolutionPathSegments.add("<code>" + escapeHtml(unionTag) + "</code>");
                    currPsiValue = explicitUnion.getValue();
                } else if (currIrValue instanceof StvnValue.StvnUnion unionIr) {
                    BigInteger oneBased = unionIr.oneBasedBranchIndex();
                    if (oneBased.compareTo(BigInteger.ONE) < 0
                            || oneBased.compareTo(BigInteger.valueOf(branches.size())) > 0) {
                        return null;
                    }
                    zeroIdx = oneBased.intValue() - 1;
                    unionTag = StvnVocabulary.SIGIL_VALUE + oneBased;
                    String bracketed = StvnVocabulary.DELIM_OPEN_BRACKET + unionTag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                    expandedTokens.add(bracketed);
                    String codeBadge = "<code>" + escapeHtml(bracketed) + "</code>";
                    inferredTagSegments.add(codeBadge);
                    resolutionPathSegments.add(codeBadge);
                } else {
                    int matchedIdx = resolveUniqueMatchingUnionBranch(currPsiValue, branches);
                    if (matchedIdx < 0) {
                        return null;
                    }
                    zeroIdx = matchedIdx;
                    unionTag = StvnVocabulary.SIGIL_VALUE + (zeroIdx + 1);
                    String bracketed = StvnVocabulary.DELIM_OPEN_BRACKET + unionTag + StvnVocabulary.DELIM_CLOSE_BRACKET;
                    expandedTokens.add(bracketed);
                    String codeBadge = "<code>" + escapeHtml(bracketed) + "</code>";
                    inferredTagSegments.add(codeBadge);
                    resolutionPathSegments.add(codeBadge);
                }
                if (currIrValue instanceof StvnValue.StvnUnion unionIr) {
                    currIrValue = unionIr.value();
                }
                currPsiSchema = branches.get(zeroIdx);
            } else {
                break;
            }
        }

        if (!sawAtLeastOneSumStep) {
            return null;
        }

        if (!terminatedAtNone) {
            currPsiValue = unwrapConstantChainValue(currPsiValue);
            if (currPsiValue == null) {
                return null;
            }
            expandedTokens.add(formatTerminalPsiLeaf(currPsiValue, currPsiSchema));
            if (currPsiSchema != null) {
                SchemaType terminalConstructed = appendAndUnwrapNominalHops(
                    currPsiSchema,
                    resolutionPathSegments,
                    visitedAliases
                );
                if (terminalConstructed != null && terminalConstructed.getSchemaConstructor() != null) {
                    String formattedLeafType = StvnTypeResolver.formatSchemaType(terminalConstructed);
                    if (!formattedLeafType.isEmpty()) {
                        resolutionPathSegments.add("<code>" + escapeHtml(formattedLeafType) + "</code>");
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<br/><b>Expanded Value:</b> <code>")
          .append(escapeHtml(String.join(" ", expandedTokens)))
          .append("</code>");
        sb.append("<br/><b>Inferred Defaults:</b> ");
        if (inferredTagSegments.isEmpty()) {
            sb.append("<i>None (all sum tags explicitly authored)</i>");
        } else {
            sb.append(String.join(" &rarr; ", inferredTagSegments));
        }
        if (!resolutionPathSegments.isEmpty()) {
            sb.append("<br/><b>Resolution Path:</b> ")
              .append(String.join(" &rarr; ", resolutionPathSegments));
        }
        return sb.toString();
    }

    private static boolean resolvesToCoproductSumSchema(SchemaType schemaType) {
        SchemaType curr = schemaType;
        Set<PsiElement> visited = new HashSet<>();
        for (int depth = 0; depth < MAX_SPINE_DEPTH && curr != null && visited.add(curr); depth++) {
            SchemaConstructor ctor = curr.getSchemaConstructor();
            if (ctor != null) {
                SumType sum = ctor.getSumType();
                return sum != null && sum.getEnumDef() == null;
            }
            SchemaType next = StvnTypeResolver.resolveNominalSchema(curr);
            if (next == null || next == curr) {
                return false;
            }
            curr = next;
        }
        return false;
    }

    private static @Nullable Value unwrapConstantForCurrentSumLayer(
        @Nullable Value startValue,
        SchemaType layerStartSchema,
        SchemaType unwrappedSumSchema
    ) {
        Value curr = startValue;
        Set<PsiElement> visited = new HashSet<>();
        for (int hop = 0; hop < MAX_SPINE_DEPTH && curr != null && visited.add(curr); hop++) {
            ValueKeyword vk = curr.getValueKeyword();
            if (vk == null || StvnVocabulary.CONTROL_KEYWORDS.contains(vk.getText().trim())) {
                return curr;
            }
            PsiElement resolved = StvnConstantReference.resolveConstantWithContext(vk);
            ConstantDefinition targetConst = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
            if (targetConst == null || targetConst.getSchemaType() == null) {
                return curr;
            }
            if (!doesConstantSchemaMatchSumLayer(targetConst.getSchemaType(), layerStartSchema, unwrappedSumSchema)) {
                return curr;
            }
            Value nextVal = targetConst.getValue();
            if (nextVal == null || !visited.add(targetConst)) {
                return curr;
            }
            curr = nextVal;
        }
        return curr;
    }

    private static boolean doesConstantSchemaMatchSumLayer(
        SchemaType constSchema,
        SchemaType layerStartSchema,
        SchemaType unwrappedSumSchema
    ) {
        TypeDefinition constTargetDef = resolveNominalTargetDef(constSchema);
        TypeDefinition layerTargetDef = resolveNominalTargetDef(layerStartSchema);
        if (layerTargetDef != null) {
            if (constTargetDef == null) {
                return false;
            }
            if (constTargetDef == layerTargetDef) {
                return true;
            }
            TypeDefinition unwrappedOwnerDef = PsiTreeUtil.getParentOfType(unwrappedSumSchema, TypeDefinition.class);
            return constTargetDef == unwrappedOwnerDef;
        }
        if (constTargetDef == null) {
            SchemaType unwrappedConst = StvnTypeResolver.resolveNominalSchema(constSchema);
            SchemaType effectiveConst = unwrappedConst != null ? unwrappedConst : constSchema;
            return StvnTypeResolver.formatSchemaType(effectiveConst)
                .equals(StvnTypeResolver.formatSchemaType(unwrappedSumSchema));
        }
        return false;
    }

    private static @Nullable TypeDefinition resolveNominalTargetDef(SchemaType schema) {
        TypeKeyword kw = schema.getTypeKeyword();
        if (kw == null) {
            return null;
        }
        String raw = kw.getText().trim();
        if (StvnNamespaceRules.isReservedFundamentalType(raw)) {
            return null;
        }
        PsiElement resolved = new StvnTypeReference(kw).resolve();
        TypeDefinition td = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
        if (td != null) {
            return td;
        }
        return StvnTypeResolver.findTypeDefinition(kw.getContainingFile(), raw, kw);
    }

    private static @Nullable Value unwrapConstantChainValue(@Nullable Value startValue) {
        Value curr = startValue;
        Set<PsiElement> visited = new HashSet<>();
        for (int hop = 0; hop < MAX_SPINE_DEPTH && curr != null && visited.add(curr); hop++) {
            ValueKeyword vk = curr.getValueKeyword();
            if (vk == null) {
                return curr;
            }
            String text = vk.getText().trim();
            if (StvnVocabulary.VAL_NONE.equals(text)
                    || StvnVocabulary.VAL_NONE_SHORT.equals(text)
                    || StvnVocabulary.VAL_TRUE.equals(text)
                    || StvnVocabulary.VAL_TRUE_SHORT.equals(text)
                    || StvnVocabulary.VAL_FALSE.equals(text)
                    || StvnVocabulary.VAL_FALSE_SHORT.equals(text)) {
                return curr;
            }
            PsiElement resolved = StvnConstantReference.resolveConstantWithContext(vk);
            ConstantDefinition targetConst = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
            if (targetConst == null) {
                return curr;
            }
            Value nextVal = targetConst.getValue();
            if (nextVal == null || !visited.add(targetConst)) {
                return null;
            }
            curr = nextVal;
        }
        return curr;
    }

    private static boolean hasActiveCompilerErrorOnConstant(
        PsiFile constFile,
        String activeNormalizedPath,
        List<StvnDiagnostic> diagnostics,
        ConstantDefinition constDef
    ) {
        TextRange constRange = constDef.getTextRange();
        int fileLen = constFile.getTextLength();
        for (StvnDiagnostic diag : diagnostics) {
            if (diag.severity() != DiagnosticSeverity.ERROR) {
                continue;
            }
            if (diag.sourcePath().isPresent() && !activeNormalizedPath.isEmpty()) {
                String diagNorm = StvnSchemaFlattener.normalizePath(diag.sourcePath().get());
                if (!diagNorm.equals(activeNormalizedPath)
                        && !diagNorm.endsWith("/" + constFile.getName())
                        && !diagNorm.equals(constFile.getName())) {
                    continue;
                }
            }
            int s = Math.max(0, Math.min(diag.startOffset(), fileLen));
            int e = Math.max(s, Math.min(diag.endOffset(), fileLen));
            if (s >= constRange.getStartOffset() && s < constRange.getEndOffset()
                    && e <= constRange.getEndOffset()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsErrorValue(@Nullable StvnValue val) {
        if (val == null) {
            return false;
        }
        if (val instanceof StvnValue.StvnErrorValue) {
            return true;
        }
        if (val instanceof StvnValue.StvnOption opt) {
            return opt.value().map(StvnConstantSumSpineRenderer::containsErrorValue).orElse(false);
        }
        if (val instanceof StvnValue.StvnEither either) {
            return containsErrorValue(either.value());
        }
        if (val instanceof StvnValue.StvnUnion union) {
            return containsErrorValue(union.value());
        }
        if (val instanceof StvnValue.StvnTuple tuple) {
            return tuple.elements().stream().anyMatch(StvnConstantSumSpineRenderer::containsErrorValue);
        }
        if (val instanceof StvnValue.StvnSeq seq) {
            return seq.elements().stream().anyMatch(StvnConstantSumSpineRenderer::containsErrorValue);
        }
        if (val instanceof StvnValue.StvnSet set) {
            return set.elements().stream().anyMatch(StvnConstantSumSpineRenderer::containsErrorValue);
        }
        if (val instanceof StvnValue.StvnMap map) {
            return map.entries().stream()
                .anyMatch(entry -> containsErrorValue(entry.key()) || containsErrorValue(entry.value()));
        }
        return false;
    }

    private static @Nullable SchemaType appendAndUnwrapNominalHops(
        SchemaType startSchema,
        List<String> resolutionPathSegments,
        Set<PsiElement> visitedAliases
    ) {
        SchemaType curr = startSchema;
        for (int hop = 0; hop < MAX_SPINE_DEPTH && curr != null; hop++) {
            TypeKeyword typeKw = curr.getTypeKeyword();
            if (typeKw == null) {
                return curr;
            }
            String surfaceAlias = typeKw.getText().trim();
            if (StvnNamespaceRules.isReservedFundamentalType(surfaceAlias)) {
                resolutionPathSegments.add("<code>" + escapeHtml(surfaceAlias) + "</code>");
                return curr;
            }
            PsiElement resolved = new StvnTypeReference(typeKw).resolve();
            TypeDefinition targetDef = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
            if (targetDef == null) {
                targetDef = StvnTypeResolver.findTypeDefinition(typeKw.getContainingFile(), surfaceAlias, typeKw);
            }
            if (targetDef == null || !visitedAliases.add(targetDef)) {
                return null;
            }
            String targetFqni = buildTypeDefinitionLinkTarget(targetDef, surfaceAlias);
            resolutionPathSegments.add(
                "<a href=\"psi_element://TypeDefinition/" + escapeHtml(targetFqni) + "\"><code>"
                    + escapeHtml(surfaceAlias) + "</code></a>"
            );
            curr = targetDef.getSchemaType();
        }
        return curr;
    }

    private static String buildTypeDefinitionLinkTarget(TypeDefinition targetDef, String fallbackAlias) {
        TypeKeyword defKw = targetDef.getTypeKeyword();
        String localName = defKw != null ? defKw.getText().trim() : fallbackAlias;
        PackageEnclosure pkg = PsiTreeUtil.getParentOfType(targetDef, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            String pkgPath = pkg.getPackagePath().getText().trim();
            String cleanPkg = pkgPath.startsWith(StvnVocabulary.SIGIL_TYPIC) ? pkgPath.substring(1) : pkgPath;
            String cleanLocal = localName.startsWith(StvnVocabulary.SIGIL_TYPIC) ? localName.substring(1) : localName;
            if (!cleanPkg.isEmpty() && !cleanLocal.isEmpty()) {
                return StvnVocabulary.SIGIL_TYPIC + cleanPkg + "/" + cleanLocal;
            }
        }
        return localName;
    }

    private static boolean resolveIsOptionNone(
        @Nullable Value currPsiValue,
        @Nullable ExplicitOptionValue explicitOpt,
        @Nullable StvnValue currIrValue
    ) {
        if (currIrValue instanceof StvnValue.StvnOption optIr) {
            return optIr.value().isEmpty();
        }
        if (explicitOpt != null) {
            return explicitOpt.getNoneLiteral() != null || explicitOpt.getNoneShortLiteral() != null;
        }
        if (currPsiValue != null) {
            String trimmed = currPsiValue.getText().trim();
            return StvnVocabulary.VAL_NONE.equals(trimmed) || StvnVocabulary.VAL_NONE_SHORT.equals(trimmed);
        }
        return false;
    }

    private static int resolveUniqueMatchingUnionBranch(@Nullable Value psiValue, List<SchemaType> branches) {
        if (psiValue == null) {
            return -1;
        }
        int matchedIdx = -1;
        for (int i = 0; i < branches.size(); i++) {
            if (StvnTypeResolver.matchesSchemaPattern(psiValue, branches.get(i))) {
                if (matchedIdx != -1) {
                    return -1;
                }
                matchedIdx = i;
            }
        }
        return matchedIdx;
    }

    private static String formatTerminalPsiLeaf(Value leafValue, @Nullable SchemaType leafSchema) {
        if (leafValue.getStringLiteral() != null && leafValue.getStringLiteral().getText().startsWith("\"\"\"")) {
            boolean preserveIndent = StvnPsiLiteralEvaluator.extractPreserveIndentFromSchema(leafSchema);
            return StvnPsiLiteralEvaluator.decodeStringLiteral(leafValue.getStringLiteral().getText(), preserveIndent)
                .map(StvnPsiLiteralEvaluator::formatCanonicalQuotedString)
                .orElseGet(() -> leafValue.getText().trim());
        }
        return leafValue.getText().trim();
    }

    private static boolean isOptionSum(SumType sumPsi, String sumText) {
        var node = sumPsi.getNode();
        return (node != null && node.findChildByType(StvnTypes.KW_OPTION) != null)
            || sumText.startsWith(StvnVocabulary.TYPE_OPTION);
    }

    private static boolean isEitherSum(SumType sumPsi, String sumText) {
        var node = sumPsi.getNode();
        return (node != null && node.findChildByType(StvnTypes.KW_EITHER) != null)
            || sumText.startsWith(StvnVocabulary.TYPE_EITHER);
    }

    private static boolean isUnionSum(SumType sumPsi, String sumText) {
        var node = sumPsi.getNode();
        return (node != null && node.findChildByType(StvnTypes.KW_UNION) != null)
            || sumText.startsWith(StvnVocabulary.TYPE_UNION);
    }

    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;");
    }
}
