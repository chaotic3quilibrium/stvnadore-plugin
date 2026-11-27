package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnConvertToModularQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveDuplicateUseAliasQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveDuplicateUseEntryQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveIllegalIncludeModifierQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveUnresolvedUsePackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnReplaceStripWithExplicitAliasesQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedUsePackageQuickFixProvider;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.PackageElement;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.UseAliasBlock;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.Visitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates module {@code :include} and lexical {@code :use} directives under STVN 2.0.0
 * Specification § 1.2, § 3.9, and § 4.2:
 * <ul>
 *   <li>Prohibits {@code :include} statements inside hermetic documents ({@code E005}).</li>
 *   <li>Flags illegal modifier blocks ({@code { #strip }}, {@code { :A :B }}, {@code {}}) inside {@code :include [...]} ({@code E002}).</li>
 *   <li>Prohibits scoped {@code :use} directives targeting external unloaded packages inside hermetic documents ({@code E035}).</li>
 *   <li>Flags duplicate same-scope {@code :use} package targets and duplicate {@code (LHS, RHS)} alias pairs ({@code E005}).</li>
 *   <li>Flags coordinate-pinned {@code :use} and unpackaged {@code :include} namespace collisions ({@code E006}).</li>
 * </ul>
 */
@NullMarked
public final class StvnFlatDocumentIncludeInspection extends LocalInspectionTool {

    /**
     * Constructs a new {@code StvnFlatDocumentIncludeInspection} instance.
     */
    public StvnFlatDocumentIncludeInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitIncludeStmt(@NotNull IncludeStmt stmt) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(stmt)) {
                    return;
                }
                PsiFile file = stmt.getContainingFile();
                if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
                    return;
                }

                int stmtBase = stmt.getTextRange().getStartOffset();
                String illegalIncludeModProse = "STVN Syntax Error: '"
                    + StvnVocabulary.KEYWORD_INCLUDE
                    + "' accepts only module path string literals; use '"
                    + StvnVocabulary.KEYWORD_PACKAGE
                    + "' and '"
                    + StvnVocabulary.KEYWORD_USE
                    + "' for '"
                    + StvnVocabulary.FACET_KW_STRIP
                    + "' or symbol aliasing";
                for (TextRange modSpan : findIllegalIncludeModifierSpans(stmt)) {
                    TextRange relSpan = new TextRange(
                        modSpan.getStartOffset() - stmtBase,
                        modSpan.getEndOffset() - stmtBase
                    );
                    holder.registerProblem(
                        stmt,
                        StvnDiagnosticFormatter.formatCore(
                            illegalIncludeModProse,
                            "E002",
                            "STVN_SYNTAX_ERROR"
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        relSpan,
                        new StvnRemoveIllegalIncludeModifierQuickFix(stmt, modSpan)
                    );
                }

                if (prohibitsIncludeStatements(file)) {
                    List<LocalQuickFix> fixes = new ArrayList<>(2);
                    fixes.add(new RemoveIncludeStatementQuickFix(extractSingleIncludeTargetPath(stmt)));
                    if (StvnConvertToModularQuickFix.canConvertToModular(file)) {
                        fixes.add(new StvnConvertToModularQuickFix(stmt, file.getName().endsWith(".stvn_d")));
                    }
                    holder.registerProblem(
                        stmt,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Hermetic document or schema (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot contain include statements: "
                                + file.getName(),
                            StvnDiagnosticCodes.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                }
            }

            @Override
            public void visitDefsEntry(@NotNull DefsEntry defsEntry) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(defsEntry)) {
                    return;
                }
                PsiFile file = defsEntry.getContainingFile();
                if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
                    return;
                }
                validateRootDefsScope(file, defsEntry, holder);
            }

            @Override
            public void visitPackageEnclosure(@NotNull PackageEnclosure pkgEnclosure) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(pkgEnclosure)) {
                    return;
                }
                PsiFile file = pkgEnclosure.getContainingFile();
                if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
                    return;
                }
                validatePackageScope(file, pkgEnclosure, holder);
            }

            @Override
            public void visitUseElement(@NotNull UseElement element) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
                    return;
                }
                PsiFile file = element.getContainingFile();
                if (file == null || StvnPreludeBridge.isPreludeFile(file) || !isHermeticCompilationUnit(file)) {
                    return;
                }
                UseTarget useTarget = element.getUseTarget();
                if (useTarget == null || StvnTrailingSlashInspection.hasTrailingSlash(useTarget)) {
                    return;
                }
                String target = StvnTrailingSlashInspection.extractNonCommentText(useTarget).trim();
                if (target.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(target)) {
                    return;
                }
                if (isDuplicateValidUseElementInScope(element, target)) {
                    return;
                }
                if (StvnPrelude.isPreludePackageTarget(target)) {
                    return;
                }
                Set<String> localPackages = collectLocalNonReservedPackages(file);
                if (matchesDeclaredOrParentPackage(target, localPackages)) {
                    return;
                }

                List<LocalQuickFix> fixes = StvnUnresolvedUsePackageQuickFixProvider.createLocalQuickFixes(
                    file,
                    useTarget,
                    target
                );
                holder.registerProblem(
                    useTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Scoped " + StvnVocabulary.KEYWORD_USE
                            + " in hermetic document (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot target external package: "
                            + target,
                        StvnDiagnosticCodes.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }
        };
    }

    /**
     * Collects absolute document {@link TextRange} spans for illegal modifier blocks
     * (such as {@code { #strip }}, {@code { :A :B }}, or {@code {}}) inside {@code :include [...]}.
     *
     * @param stmt target {@code :include} statement
     * @return ordered list of offending modifier ranges
     */
    public static List<TextRange> findIllegalIncludeModifierSpans(IncludeStmt stmt) {
        List<TextRange> spans = new ArrayList<>();
        ASTNode stmtNode = stmt.getNode();
        ASTNode lbrack = stmtNode.findChildByType(StvnTypes.LBRACK);
        ASTNode rbrack = stmtNode.findChildByType(StvnTypes.RBRACK);
        if (lbrack == null) {
            return spans;
        }
        int limitOffset = rbrack != null ? rbrack.getStartOffset() : stmt.getTextRange().getEndOffset();

        for (PsiElement child = lbrack.getPsi().getNextSibling();
             child != null && child.getTextRange().getStartOffset() < limitOffset;
             child = child.getNextSibling()) {
            if (child instanceof IncludeElement || child instanceof PsiWhiteSpace || child instanceof PsiComment) {
                continue;
            }
            if (child instanceof PsiErrorElement errElem) {
                collectModifierSpansFromErrorElement(errElem, spans);
            } else if (child.getNode() != null && child.getNode().getElementType() != StvnTypes.RBRACK) {
                spans.add(child.getTextRange());
            }
        }
        return spans;
    }

    private static void collectModifierSpansFromErrorElement(PsiErrorElement errElem, List<TextRange> outSpans) {
        ASTNode cur = errElem.getNode().getFirstChildNode();
        while (cur != null) {
            IElementType type = cur.getElementType();
            if (type == TokenType.WHITE_SPACE || type == StvnTypes.COMMENT) {
                cur = cur.getTreeNext();
                continue;
            }
            if (type == StvnTypes.LBRACE) {
                int start = cur.getStartOffset();
                int end = cur.getTextRange().getEndOffset();
                int depth = 1;
                cur = cur.getTreeNext();
                while (cur != null && depth > 0) {
                    IElementType innerType = cur.getElementType();
                    if (innerType == StvnTypes.LBRACE) {
                        depth++;
                    } else if (innerType == StvnTypes.RBRACE) {
                        depth--;
                    }
                    end = cur.getTextRange().getEndOffset();
                    cur = cur.getTreeNext();
                }
                outSpans.add(new TextRange(start, end));
            } else {
                int start = cur.getStartOffset();
                int end = cur.getTextRange().getEndOffset();
                cur = cur.getTreeNext();
                while (cur != null) {
                    IElementType nextType = cur.getElementType();
                    if (nextType == TokenType.WHITE_SPACE
                            || nextType == StvnTypes.COMMENT
                            || nextType == StvnTypes.LBRACE) {
                        break;
                    }
                    end = cur.getTextRange().getEndOffset();
                    cur = cur.getTreeNext();
                }
                outSpans.add(new TextRange(start, end));
            }
        }
    }

    /**
     * Determines whether {@code offset} in {@code file} matches an illegal modifier span inside
     * a primary {@code :include} statement governed by {@link StvnFlatDocumentIncludeInspection}.
     *
     * @param file   target PSI file
     * @param offset document start offset of the diagnostic
     * @return {@code true} if governed
     */
    public static boolean isGovernedIllegalIncludeModifierOffset(PsiFile file, int offset) {
        if (offset < 0 || offset >= file.getTextLength() || StvnPreludeBridge.isPreludeFile(file)) {
            return false;
        }
        PsiElement leaf = file.findElementAt(offset);
        IncludeStmt stmt = leaf instanceof IncludeStmt inc
            ? inc
            : PsiTreeUtil.getParentOfType(leaf, IncludeStmt.class, false);
        if (stmt == null || StvnMetaTemplateUtil.isInSecondaryRootSection(stmt)) {
            return false;
        }
        for (TextRange span : findIllegalIncludeModifierSpans(stmt)) {
            if (span.getStartOffset() == offset) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether {@code offset} in {@code file} points to a duplicate {@code :use}
     * target or duplicate {@code UseMapAlias} entry governed by {@link StvnFlatDocumentIncludeInspection}.
     *
     * @param file   target PSI file
     * @param offset document start offset of the diagnostic
     * @return {@code true} if governed
     */
    public static boolean isGovernedDuplicateModuleImportOffset(PsiFile file, int offset) {
        if (offset < 0 || offset >= file.getTextLength() || StvnPreludeBridge.isPreludeFile(file)) {
            return false;
        }
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null || StvnMetaTemplateUtil.isInSecondaryRootSection(leaf)) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(leaf, UseElement.class, false) != null
            || leaf instanceof UseElement;
    }

    /**
     * Determines whether {@code offset} in {@code file} points to a {@code :use} or unpackaged
     * {@code :include} namespace collision governed by {@link StvnFlatDocumentIncludeInspection},
     * leaving local {@code TypeDefinition} / {@code ConstantDefinition} duplicates ungoverned.
     *
     * @param file   target PSI file
     * @param offset document start offset of the diagnostic
     * @return {@code true} if governed
     */
    public static boolean isGovernedNamespaceCollisionOffset(PsiFile file, int offset) {
        if (offset < 0 || offset >= file.getTextLength() || StvnPreludeBridge.isPreludeFile(file)) {
            return false;
        }
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null || StvnMetaTemplateUtil.isInSecondaryRootSection(leaf)) {
            return false;
        }
        IncludeElement incElem = leaf instanceof IncludeElement ie
            ? ie
            : PsiTreeUtil.getParentOfType(leaf, IncludeElement.class, false);
        if (incElem != null && incElem.getStringLiteral() != null) {
            return !prohibitsIncludeStatements(file)
                && StvnTypeReference.resolveIncludeFile(incElem.getStringLiteral()) != null;
        }
        UseMapAlias mapAlias = leaf instanceof UseMapAlias ua
            ? ua
            : PsiTreeUtil.getParentOfType(leaf, UseMapAlias.class, false);
        if (mapAlias != null) {
            PsiElement rhs = StvnReplaceStripWithExplicitAliasesQuickFix.extractAliasRhsElement(mapAlias);
            return rhs != null && rhs.getTextRange().getStartOffset() == offset;
        }
        UseElement useElem = leaf instanceof UseElement ue
            ? ue
            : PsiTreeUtil.getParentOfType(leaf, UseElement.class, false);
        return useElem != null
            && useElem.getTextRange().getStartOffset() == offset
            && StvnPsiUtils.hasValidStripOption(useElem.getUseOptionsBlock())
            && useElem.getUseTarget() != null;
    }

    private record PsiScopeClaim(boolean rawStripImport, PsiElement anchor, String originKey) {}

    private static void validateRootDefsScope(PsiFile file, DefsEntry defsEntry, ProblemsHolder holder) {
        Set<String> localRootNames = new LinkedHashSet<>();
        StvnReplaceStripWithExplicitAliasesQuickFix.collectUnpackagedRootDeclarations(file, localRootNames);

        Map<String, String> unpackagedOwnerBySymbol = new HashMap<>();
        Set<String> loadedCanonicalModuleKeys = new HashSet<>();
        loadedCanonicalModuleKeys.add(StvnReplaceStripWithExplicitAliasesQuickFix.canonicalFileKey(file));

        if (!prohibitsIncludeStatements(file)) {
            for (DefsElement elem : defsEntry.getDefsElementList()) {
                IncludeStmt incStmt = elem.getIncludeStmt();
                if (incStmt == null) {
                    continue;
                }
                for (IncludeElement incElem : incStmt.getIncludeElementList()) {
                    StringLiteral lit = incElem.getStringLiteral();
                    if (lit == null || StvnPsiLiteralEvaluator.decodeStringLiteral(lit.getText(), true).isEmpty()) {
                        continue;
                    }
                    PsiFile resolvedDirect = StvnTypeReference.resolveIncludeFile(lit);
                    if (resolvedDirect == null) {
                        continue;
                    }
                    List<PsiFile> branchOrder = new ArrayList<>();
                    StvnReplaceStripWithExplicitAliasesQuickFix.collectIncludedFilesInOrder(
                        resolvedDirect,
                        branchOrder,
                        new HashSet<>()
                    );
                    if (!branchOrder.contains(resolvedDirect)) {
                        branchOrder.add(resolvedDirect);
                    }

                    Set<String> collidingInInclude = new LinkedHashSet<>();
                    for (PsiFile modFile : branchOrder) {
                        String modKey = StvnReplaceStripWithExplicitAliasesQuickFix.canonicalFileKey(modFile);
                        if (!loadedCanonicalModuleKeys.add(modKey)) {
                            continue;
                        }
                        Set<String> modSymbols = new LinkedHashSet<>();
                        StvnReplaceStripWithExplicitAliasesQuickFix.collectUnpackagedRootDeclarations(modFile, modSymbols);
                        for (String sym : modSymbols) {
                            if (localRootNames.contains(sym)) {
                                continue;
                            }
                            String prevOwner = unpackagedOwnerBySymbol.putIfAbsent(sym, modKey);
                            if (prevOwner != null && !prevOwner.equals(modKey)) {
                                collidingInInclude.add(sym);
                            }
                        }
                    }

                    if (!collidingInInclude.isEmpty()) {
                        String incColMsg = "Namespace collision(s) detected: "
                            + StvnVocabulary.DELIM_OPEN_BRACKET
                            + String.join(", ", collidingInInclude)
                            + StvnVocabulary.DELIM_CLOSE_BRACKET
                            + "; enclose module declarations in '"
                            + StvnVocabulary.KEYWORD_PACKAGE
                            + "' and disambiguate local symbols with '"
                            + StvnVocabulary.KEYWORD_USE
                            + "'";
                        holder.registerProblem(
                            lit,
                            StvnDiagnosticFormatter.formatCore(
                                incColMsg,
                                StvnDiagnosticCodes.ERR_NAMESPACE_COLLISION
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                }
            }
        }

        List<UseStmt> rootUseStmts = new ArrayList<>();
        for (DefsElement elem : defsEntry.getDefsElementList()) {
            if (elem.getUseStmt() != null) {
                rootUseStmts.add(elem.getUseStmt());
            }
        }
        validateScopeUseStatements(file, rootUseStmts, localRootNames, holder);
    }

    private static void validatePackageScope(PsiFile file, PackageEnclosure pkgEnclosure, ProblemsHolder holder) {
        if (pkgEnclosure.getPackagePath() == null
                || StvnTrailingSlashInspection.hasTrailingSlash(pkgEnclosure.getPackagePath())) {
            return;
        }
        String pkgPath = StvnTrailingSlashInspection.extractNonCommentText(pkgEnclosure.getPackagePath()).trim();
        if (pkgPath.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(pkgPath)
                || StvnNamespaceRules.isReservedPackage(pkgPath)) {
            return;
        }
        Set<String> packageLocalDeclared = new LinkedHashSet<>();
        List<UseStmt> pkgUseStmts = new ArrayList<>();
        for (PackageElement pe : pkgEnclosure.getPackageElementList()) {
            if (pe.getUseStmt() != null) {
                pkgUseStmts.add(pe.getUseStmt());
            } else if (pe.getTypeDefinition() != null && pe.getTypeDefinition().getTypeDefTarget() != null) {
                packageLocalDeclared.add(pe.getTypeDefinition().getTypeDefTarget().getText().trim());
            } else if (pe.getConstantDefinition() != null && pe.getConstantDefinition().getValueKeyword() != null) {
                packageLocalDeclared.add(pe.getConstantDefinition().getValueKeyword().getText().trim());
            }
        }
        validateScopeUseStatements(file, pkgUseStmts, packageLocalDeclared, holder);
    }

    private static void validateScopeUseStatements(
            PsiFile file,
            List<UseStmt> useStmts,
            Set<String> localDeclaredNames,
            ProblemsHolder holder
    ) {
        Set<String> seenValidTargets = new HashSet<>();
        Map<String, List<PsiScopeClaim>> typeClaimsBySymbol = new LinkedHashMap<>();
        Map<String, List<PsiScopeClaim>> constClaimsBySymbol = new LinkedHashMap<>();

        for (UseStmt useStmt : useStmts) {
            for (UseElement ue : useStmt.getUseElementList()) {
                UseTarget useTarget = ue.getUseTarget();
                if (useTarget == null
                        || useTarget.getUseTargetIllegal() != null
                        || StvnTrailingSlashInspection.hasTrailingSlash(useTarget)) {
                    continue;
                }
                String pkg = StvnTrailingSlashInspection.extractNonCommentText(useTarget).trim();
                if (pkg.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(pkg)) {
                    continue;
                }
                if (StvnTypeReference.isStructurallyValidUseElementForDedup(ue) && !seenValidTargets.add(pkg)) {
                    String dupTargetMsg = "Duplicate '"
                        + StvnVocabulary.KEYWORD_USE
                        + "' target '"
                        + pkg
                        + "' in the same scope; consolidate bindings into a single '"
                        + StvnVocabulary.KEYWORD_USE
                        + "' entry";
                    holder.registerProblem(
                        ue,
                        StvnDiagnosticFormatter.formatCore(
                            dupTargetMsg,
                            StvnDiagnosticCodes.ERR_DUPLICATE_MODULE_IMPORT
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new StvnRemoveDuplicateUseEntryQuickFix(ue)
                    );
                    continue;
                }

                Set<String> evictedSimpleNames = new HashSet<>();
                Set<String> seenCanonicalPairs = new HashSet<>();
                UseAliasBlock aliasBlock = ue.getUseAliasBlock();
                if (aliasBlock != null) {
                    for (UseMapAlias alias : aliasBlock.getUseMapAliasList()) {
                        String rawLhs = StvnReplaceStripWithExplicitAliasesQuickFix.extractAliasLhsText(alias);
                        String rawRhs = StvnReplaceStripWithExplicitAliasesQuickFix.extractAliasRhsText(alias);
                        PsiElement rhsElem = StvnReplaceStripWithExplicitAliasesQuickFix.extractAliasRhsElement(alias);
                        if (rawLhs == null || rawRhs == null || rhsElem == null) {
                            continue;
                        }
                        String canonicalLhs = canonicalizeUseAliasLhs(pkg, rawLhs);
                        if (!seenCanonicalPairs.add(canonicalLhs + "=>" + rawRhs)) {
                            String dupPairMsg = "Duplicate '"
                                + StvnVocabulary.KEYWORD_USE
                                + "' alias mapping '"
                                + rawLhs + " " + rawRhs
                                + "' in '"
                                + pkg
                                + "'; remove the redundant alias entry";
                            holder.registerProblem(
                                alias,
                                StvnDiagnosticFormatter.formatCore(
                                    dupPairMsg,
                                    StvnDiagnosticCodes.ERR_DUPLICATE_MODULE_IMPORT
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                new StvnRemoveDuplicateUseAliasQuickFix(alias)
                            );
                            continue;
                        }

                        evictedSimpleNames.add(StvnReplaceStripWithExplicitAliasesQuickFix.toSimpleSymbolName(rawLhs));
                        Map<String, List<PsiScopeClaim>> targetMap = rawRhs.startsWith(StvnVocabulary.SIGIL_VALUE)
                            ? constClaimsBySymbol
                            : typeClaimsBySymbol;
                        targetMap.computeIfAbsent(rawRhs, k -> new ArrayList<>())
                            .add(new PsiScopeClaim(false, rhsElem, ""));
                    }
                }

                if (StvnPsiUtils.hasValidStripOption(ue.getUseOptionsBlock())) {
                    List<String> exported = StvnReplaceStripWithExplicitAliasesQuickFix
                        .collectExportedPackageSymbolsInOrder(file, pkg);
                    for (String sym : exported) {
                        if (evictedSimpleNames.contains(sym)) {
                            continue;
                        }
                        Map<String, List<PsiScopeClaim>> targetMap = sym.startsWith(StvnVocabulary.SIGIL_VALUE)
                            ? constClaimsBySymbol
                            : typeClaimsBySymbol;
                        targetMap.computeIfAbsent(sym, k -> new ArrayList<>())
                            .add(new PsiScopeClaim(true, ue, pkg + "::" + sym));
                    }
                }
            }
        }

        Map<PsiElement, LinkedHashSet<String>> collisionsByAnchor = new LinkedHashMap<>();
        resolvePsiScopeClaims(typeClaimsBySymbol, localDeclaredNames, collisionsByAnchor);
        resolvePsiScopeClaims(constClaimsBySymbol, localDeclaredNames, collisionsByAnchor);

        for (var colEntry : collisionsByAnchor.entrySet()) {
            PsiElement anchor = colEntry.getKey();
            List<String> collidingSymbols = new ArrayList<>(colEntry.getValue());
            String formattedMsg = StvnDiagnosticFormatter.formatCore(
                formatUseCollisionMessage(collidingSymbols),
                StvnDiagnosticCodes.ERR_NAMESPACE_COLLISION
            );
            if (anchor instanceof UseElement ue) {
                holder.registerProblem(
                    ue,
                    formattedMsg,
                    ProblemHighlightType.GENERIC_ERROR,
                    new StvnReplaceStripWithExplicitAliasesQuickFix(ue)
                );
            } else {
                holder.registerProblem(anchor, formattedMsg, ProblemHighlightType.GENERIC_ERROR);
            }
        }
    }

    private static void resolvePsiScopeClaims(
            Map<String, List<PsiScopeClaim>> claimsBySymbol,
            Set<String> localDeclaredNames,
            Map<PsiElement, LinkedHashSet<String>> collisionsByAnchorOut
    ) {
        for (var entry : claimsBySymbol.entrySet()) {
            String sym = entry.getKey();
            List<PsiScopeClaim> claims = entry.getValue();
            if (localDeclaredNames.contains(sym)) {
                for (PsiScopeClaim c : claims) {
                    if (!c.rawStripImport()) {
                        collisionsByAnchorOut.computeIfAbsent(c.anchor(), k -> new LinkedHashSet<>()).add(sym);
                    }
                }
                continue;
            }
            List<PsiScopeClaim> activeClaims = new ArrayList<>();
            Set<String> seenRawOrigins = new HashSet<>();
            for (PsiScopeClaim c : claims) {
                if (!c.rawStripImport()) {
                    activeClaims.add(c);
                } else if (seenRawOrigins.add(c.originKey())) {
                    activeClaims.add(c);
                }
            }
            if (activeClaims.size() > 1) {
                activeClaims.sort(
                    java.util.Comparator
                        .comparingInt((PsiScopeClaim c) -> c.anchor().getTextRange().getStartOffset())
                        .thenComparingInt(c -> c.anchor().getTextRange().getEndOffset())
                );
                for (int i = 1; i < activeClaims.size(); i++) {
                    collisionsByAnchorOut.computeIfAbsent(activeClaims.get(i).anchor(), k -> new LinkedHashSet<>())
                        .add(sym);
                }
            }
        }
    }

    private static String formatUseCollisionMessage(List<String> collidingSymbols) {
        return "Namespace collision(s) in '"
            + StvnVocabulary.KEYWORD_USE
            + "': "
            + StvnVocabulary.DELIM_OPEN_BRACKET
            + String.join(", ", collidingSymbols)
            + StvnVocabulary.DELIM_CLOSE_BRACKET
            + "; replace '"
            + StvnVocabulary.FACET_KW_STRIP
            + "' with explicit symbol aliases or rename colliding '"
            + StvnVocabulary.KEYWORD_USE
            + "' aliases";
    }

    private static String canonicalizeUseAliasLhs(String pkg, String rawLhs) {
        if (rawLhs.indexOf('/') >= 0) {
            return rawLhs;
        }
        if (rawLhs.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return StvnVocabulary.SIGIL_VALUE + pkg.substring(1) + "/" + rawLhs.substring(1);
        }
        return pkg + "/" + rawLhs.substring(1);
    }

    private static boolean isDuplicateValidUseElementInScope(UseElement targetElem, String targetPkg) {
        if (!StvnTypeReference.isStructurallyValidUseElementForDedup(targetElem)) {
            return false;
        }
        PackageEnclosure pkgEnc = PsiTreeUtil.getParentOfType(targetElem, PackageEnclosure.class);
        List<UseStmt> scopeStmts = new ArrayList<>();
        if (pkgEnc != null) {
            for (PackageElement pe : pkgEnc.getPackageElementList()) {
                if (pe.getUseStmt() != null) {
                    scopeStmts.add(pe.getUseStmt());
                }
            }
        } else {
            DefsEntry defs = PsiTreeUtil.getParentOfType(targetElem, DefsEntry.class);
            if (defs != null) {
                for (DefsElement de : defs.getDefsElementList()) {
                    if (de.getUseStmt() != null) {
                        scopeStmts.add(de.getUseStmt());
                    }
                }
            }
        }
        for (UseStmt stmt : scopeStmts) {
            for (UseElement ue : stmt.getUseElementList()) {
                if (ue == targetElem) {
                    return false;
                }
                if (!StvnTypeReference.isStructurallyValidUseElementForDedup(ue) || ue.getUseTarget() == null) {
                    continue;
                }
                String pkg = StvnTrailingSlashInspection.extractNonCommentText(ue.getUseTarget()).trim();
                if (targetPkg.equals(pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determines whether {@code file} prohibits {@code :include} statements ({@code E005})
     * under STVN 2.0.0 Specification § 1.2 across physical hermetic extensions
     * ({@code .stvn}, {@code .stvn_d}, {@code .stvn_b}, {@code .stvn_bd}) and explicit
     * {@code #kind #BODY} or {@code #kind #DEFS} headers.
     *
     * @param file target PSI file, or {@code null}
     * @return {@code true} when {@code :include} statements are prohibited in {@code file}
     */
    public static boolean prohibitsIncludeStatements(@Nullable PsiFile file) {
        if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
            return false;
        }
        String name = file.getName();
        if (StvnPsiUtils.isHermeticDocument(file)
            || name.endsWith(".stvn_b")
            || name.endsWith(".stvn_bd")) {
            return true;
        }
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                continue;
            }
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                if (StvnVocabulary.KIND_KW_BODY.equals(kindText) || StvnVocabulary.KIND_KW_DEFS.equals(kindText)) {
                    return true;
                }
            }
            break;
        }
        return false;
    }

    /**
     * Determines whether a PSI file is a hermetic compilation unit ({@code .stvn}, {@code .stvn_d},
     * {@code .stvn_b}, {@code .stvn_bd}, or explicit {@code #kind #BODY} / {@code #DEFS}).
     *
     * @param file target PSI file
     * @return {@code true} when hermetic isolation rules apply; {@code false} for modular include units
     */
    public static boolean isHermeticCompilationUnit(PsiFile file) {
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                continue;
            }
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                if (StvnVocabulary.KIND_KW_BODY.equals(kindText) || StvnVocabulary.KIND_KW_DEFS.equals(kindText)) {
                    return true;
                }
                if (StvnVocabulary.KIND_KW_BODY_INCLUDE.equals(kindText) || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindText)) {
                    return false;
                }
            }
            break;
        }
        String name = file.getName();
        return StvnPsiUtils.isHermeticDocument(file)
            || name.endsWith(".stvn_b")
            || name.endsWith(".stvn_bd");
    }

    /**
     * Collects all non-reserved local package paths declared in the primary {@code :defs} section of {@code file},
     * including {@code :package :pkg} enclosures, qualified {@code :pkg/Type} definitions, and qualified {@code #pkg/CONST} constants.
     *
     * @param file target PSI file
     * @return ordered set of locally declared non-reserved package paths
     */
    public static Set<String> collectLocalNonReservedPackages(PsiFile file) {
        Set<String> declared = new LinkedHashSet<>();
        for (DefsEntry defsEntry : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defsEntry)) {
                continue;
            }
            for (DefsElement elem : defsEntry.getDefsElementList()) {
                if (elem.getPackageEnclosure() != null && elem.getPackageEnclosure().getPackagePath() != null) {
                    String pkgPath = StvnTrailingSlashInspection.stripTrailingSlashes(
                        StvnTrailingSlashInspection.extractNonCommentText(elem.getPackageEnclosure().getPackagePath())
                    );
                    if (pkgPath.startsWith(StvnVocabulary.SIGIL_TYPIC) && !StvnNamespaceRules.isReservedPackage(pkgPath)) {
                        declared.add(pkgPath);
                    }
                } else if (elem.getTypeDefinition() != null && elem.getTypeDefinition().getTypeDefTarget() != null) {
                    String rawName = elem.getTypeDefinition().getTypeDefTarget().getText().trim();
                    int lastSlash = rawName.lastIndexOf('/');
                    if (rawName.startsWith(StvnVocabulary.SIGIL_TYPIC) && lastSlash > 1) {
                        String pkgPrefix = rawName.substring(0, lastSlash);
                        if (!StvnNamespaceRules.isReservedPackage(pkgPrefix)) {
                            declared.add(pkgPrefix);
                        }
                    }
                } else if (elem.getConstantDefinition() != null && elem.getConstantDefinition().getValueKeyword() != null) {
                    String rawConst = elem.getConstantDefinition().getValueKeyword().getText().trim();
                    int lastSlash = rawConst.lastIndexOf('/');
                    if (rawConst.startsWith(StvnVocabulary.SIGIL_VALUE) && lastSlash > 1) {
                        String pkgPrefix = StvnVocabulary.SIGIL_TYPIC + rawConst.substring(1, lastSlash);
                        if (!StvnNamespaceRules.isReservedPackage(pkgPrefix)) {
                            declared.add(pkgPrefix);
                        }
                    }
                }
            }
            break;
        }
        return declared;
    }

    private static boolean matchesDeclaredOrParentPackage(String target, Set<String> declaredPackages) {
        String prefix = target + "/";
        for (String pkg : declaredPackages) {
            if (pkg.equals(target) || pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String extractSingleIncludeTargetPath(IncludeStmt stmt) {
        List<IncludeElement> elements = stmt.getIncludeElementList();
        if (elements.size() != 1) {
            return null;
        }
        StringLiteral literal = elements.get(0).getStringLiteral();
        if (literal == null) {
            return null;
        }
        String raw = literal.getText().trim();
        if (!raw.startsWith("\"\"\"")) {
            var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(raw, true);
            if (decodedOpt.isEmpty()) {
                return null;
            }
            String unquoted = decodedOpt.get().trim();
            if (!unquoted.isEmpty() && unquoted.indexOf('\n') < 0 && unquoted.indexOf('\r') < 0) {
                return unquoted;
            }
        }
        return null;
    }

    private static boolean isDefsSchemaDocument(PsiFile file) {
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                continue;
            }
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                if (StvnVocabulary.KIND_KW_DEFS.equals(kindText)
                    || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindText)) {
                    return true;
                }
                if (StvnVocabulary.KIND_KW_BODY.equals(kindText)
                    || StvnVocabulary.KIND_KW_BODY_INCLUDE.equals(kindText)) {
                    return false;
                }
            }
            break;
        }
        String fileName = file.getName();
        if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_di") || fileName.endsWith(".stvn_bd")) {
            return true;
        }
        if (fileName.endsWith(".stvn") || fileName.endsWith(".stvn_i") || fileName.endsWith(".stvn_b")) {
            return false;
        }
        return PsiTreeUtil.findChildOfType(file, TypeEntry.class) == null
            && PsiTreeUtil.findChildOfType(file, BodyEntry.class) == null;
    }

    private static boolean hasNonDirectiveContentInDefs(DefsEntry defsEntry, DefsElement targetElement) {
        ASTNode lBrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
        ASTNode rBrace = defsEntry.getNode().findChildByType(StvnTypes.RBRACE);
        if (lBrace == null || rBrace == null) {
            return true;
        }
        for (PsiElement child = lBrace.getPsi().getNextSibling();
             child != null && child != rBrace.getPsi();
             child = child.getNextSibling()) {
            if (child == targetElement || child instanceof PsiWhiteSpace) {
                continue;
            }
            return true;
        }
        if (PsiTreeUtil.findChildOfType(targetElement, PsiComment.class) != null) {
            return true;
        }
        return false;
    }

    /**
     * Removes a top-level or package-nested directive statement ({@code :include} or {@code :use})
     * while preserving adjacent {@code //} comments and omitting comment-free empty {@code :defs}
     * sections in payload documents.
     *
     * @param project       active project
     * @param directiveStmt target directive PSI element
     */
    public static void removeDirectiveFromDefs(Project project, PsiElement directiveStmt) {
        PsiFile file = directiveStmt.getContainingFile();
        if (file == null) {
            return;
        }
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = PsiDocumentManager.getInstance(project).getDocument(file);
        }
        if (doc == null) {
            return;
        }
        if (file.isPhysical()) {
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(doc);
        }

        CharSequence chars = doc.getCharsSequence();
        DefsElement parentDefsElem = directiveStmt.getParent() instanceof DefsElement de ? de : null;
        DefsEntry defsEntry = parentDefsElem != null && parentDefsElem.getParent() instanceof DefsEntry entry
            ? entry
            : null;

        if (defsEntry != null
            && defsEntry.getDefsElementList().size() == 1
            && !hasNonDirectiveContentInDefs(defsEntry, parentDefsElem)) {
            if (isDefsSchemaDocument(file)) {
                ASTNode lBrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
                ASTNode rBrace = defsEntry.getNode().findChildByType(StvnTypes.RBRACE);
                if (lBrace != null && rBrace != null) {
                    int defsStart = defsEntry.getTextRange().getStartOffset();
                    int defsLineStart = defsStart;
                    while (defsLineStart > 0
                        && (chars.charAt(defsLineStart - 1) == ' ' || chars.charAt(defsLineStart - 1) == '\t')) {
                        defsLineStart--;
                    }
                    String defsIndent = (defsLineStart == 0 || chars.charAt(defsLineStart - 1) == '\n')
                        ? chars.subSequence(defsLineStart, defsStart).toString()
                        : "  ";
                    doc.replaceString(lBrace.getStartOffset() + 1, rBrace.getStartOffset(), "\n" + defsIndent);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            } else {
                int defsStart = defsEntry.getTextRange().getStartOffset();
                int defsEnd = defsEntry.getTextRange().getEndOffset();
                int defsWsEnd = defsEnd;
                while (defsWsEnd < chars.length()
                    && (chars.charAt(defsWsEnd) == ' ' || chars.charAt(defsWsEnd) == '\t')) {
                    defsWsEnd++;
                }
                boolean hasSameLineTrailingComment = defsWsEnd + 1 < chars.length()
                    && chars.charAt(defsWsEnd) == '/'
                    && chars.charAt(defsWsEnd + 1) == '/';
                if (!hasSameLineTrailingComment) {
                    int lineStart = defsStart;
                    while (lineStart > 0
                        && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
                        lineStart--;
                    }
                    int deleteStart = (lineStart == 0 || chars.charAt(lineStart - 1) == '\n') ? lineStart : defsStart;
                    int deleteEnd = advancePastSingleNewline(chars, defsWsEnd, defsEnd);
                    if (isPrecededByBlankLine(chars, deleteStart)
                        && deleteEnd < chars.length()
                        && (chars.charAt(deleteEnd) == '\n' || chars.charAt(deleteEnd) == '\r')) {
                        deleteEnd = advancePastSingleNewline(chars, deleteEnd, deleteEnd);
                    }
                    doc.deleteString(deleteStart, deleteEnd);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            }
        }

        int start = directiveStmt.getTextRange().getStartOffset();
        int end = directiveStmt.getTextRange().getEndOffset();
        int lineStart = start;
        while (lineStart > 0 && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        int wsEnd = end;
        while (wsEnd < chars.length() && (chars.charAt(wsEnd) == ' ' || chars.charAt(wsEnd) == '\t')) {
            wsEnd++;
        }
        boolean ownsLinePrefix = lineStart == 0 || chars.charAt(lineStart - 1) == '\n';
        if (ownsLinePrefix && (wsEnd == chars.length() || chars.charAt(wsEnd) == '\n' || chars.charAt(wsEnd) == '\r')) {
            int deleteEnd = advancePastSingleNewline(chars, wsEnd, wsEnd);
            doc.deleteString(lineStart, deleteEnd);
        } else if (ownsLinePrefix && wsEnd + 1 < chars.length()
            && chars.charAt(wsEnd) == '/' && chars.charAt(wsEnd + 1) == '/') {
            doc.deleteString(start, wsEnd);
        } else {
            doc.deleteString(start, Math.max(end, wsEnd));
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    private static boolean isPrecededByBlankLine(CharSequence chars, int lineStart) {
        if (lineStart <= 0 || chars.charAt(lineStart - 1) != '\n') {
            return false;
        }
        int prevEnd = lineStart - 1;
        if (prevEnd > 0 && chars.charAt(prevEnd - 1) == '\r') {
            prevEnd--;
        }
        while (prevEnd > 0 && (chars.charAt(prevEnd - 1) == ' ' || chars.charAt(prevEnd - 1) == '\t')) {
            prevEnd--;
        }
        return prevEnd > 0 && chars.charAt(prevEnd - 1) == '\n';
    }

    private static int advancePastSingleNewline(CharSequence chars, int wsEnd, int fallbackEnd) {
        if (wsEnd < chars.length()) {
            if (chars.charAt(wsEnd) == '\r') {
                return (wsEnd + 1 < chars.length() && chars.charAt(wsEnd + 1) == '\n') ? wsEnd + 2 : wsEnd + 1;
            }
            if (chars.charAt(wsEnd) == '\n') {
                return wsEnd + 1;
            }
        }
        return fallbackEnd;
    }

    /**
     * Quick-fix that removes a prohibited {@code :include} directive from a hermetic document or schema.
     */
    public static final class RemoveIncludeStatementQuickFix implements LocalQuickFix, PriorityAction {
        @SafeFieldForPreview
        private final @Nullable String singleTargetPath;

        /**
         * Constructs a new {@code :include} removal quick-fix.
         *
         * @param singleTargetPath single quoted include target path when unambiguous, or {@code null}
         */
        public RemoveIncludeStatementQuickFix(@Nullable String singleTargetPath) {
            this.singleTargetPath = singleTargetPath;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @NotNull String getName() {
            if (singleTargetPath != null && !singleTargetPath.isEmpty()) {
                return "Remove " + StvnVocabulary.KEYWORD_INCLUDE + " directive for '" + singleTargetPath + "'";
            }
            return "Remove " + StvnVocabulary.KEYWORD_INCLUDE + " directive";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove invalid " + StvnVocabulary.KEYWORD_INCLUDE + " directive";
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            applyFix(project, previewDescriptor);
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            IncludeStmt includeStmt = elem instanceof IncludeStmt inc
                ? inc
                : PsiTreeUtil.getParentOfType(elem, IncludeStmt.class, false);
            if (includeStmt == null) {
                return;
            }
            removeDirectiveFromDefs(project, includeStmt);
        }
    }

    /**
     * Quick-fix that removes an unresolved package entry from a {@code :use} directive in a hermetic document.
     */
    public static final class RemoveUseStatementQuickFix implements LocalQuickFix, PriorityAction {
        @SafeFieldForPreview
        private final String targetPackage;

        /**
         * Constructs a new {@code :use} package removal quick-fix.
         *
         * @param targetPackage unresolved external package target path
         */
        public RemoveUseStatementQuickFix(String targetPackage) {
            this.targetPackage = targetPackage;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.NORMAL;
        }

        @Override
        public @NotNull String getName() {
            return "Remove unresolved package '" + targetPackage + "' from " + StvnVocabulary.KEYWORD_USE;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove unresolved package from " + StvnVocabulary.KEYWORD_USE;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            applyFix(project, previewDescriptor);
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var elem = descriptor.getPsiElement();
            if (elem != null) {
                StvnRemoveUnresolvedUsePackageQuickFix.removeTargetFromUse(project, elem);
            }
        }
    }
}
