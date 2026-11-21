package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.StvnFlatDocumentIncludeInspection;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.PackagePath;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseAliasBlock;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseOptionsBlock;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Code completion contributor for package paths ({@code :package :<caret>}), multi-element module imports
 * ({@code :use [ :pkg/a { #strip } :<caret> ]}), {@code #strip} directive options, and {@code :use} alias
 * LHS exported symbols ({@code { :Old :New #OLD #NEW }}) across every {@link UseElement}.
 */
@NullMarked
public final class StvnPackageCompletionContributor extends CompletionContributor {

    private static final String PRELUDE_ROOT_PKG = ":org/stvnadore/prelude";
    private static final String PRELUDE_META_PKG = ":org/stvnadore/prelude/meta";

    /**
     * Constructs and registers the STVN package and {@code :use} element completion contributor.
     */
    public StvnPackageCompletionContributor() {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(StvnLanguage.INSTANCE),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result
                ) {
                    var position = parameters.getPosition();
                    var file = parameters.getOriginalFile();
                    var project = file.getProject();

                    // 1. Caret inside UseOptionsBlock or UseAliasBlock of any UseElement (1st, 2nd, 3rd, ...)
                    var useOptionsBlock = PsiTreeUtil.getParentOfType(position, UseOptionsBlock.class);
                    var useAliasBlock = PsiTreeUtil.getParentOfType(position, UseAliasBlock.class);
                    if (useOptionsBlock != null || useAliasBlock != null) {
                        addUseElementBlockCompletions(position, file, project, useOptionsBlock, useAliasBlock, result);
                        return;
                    }

                    var packagePath = PsiTreeUtil.getParentOfType(position, PackagePath.class);
                    var useTarget = PsiTreeUtil.getParentOfType(position, UseTarget.class);
                    boolean isPackageContext = packagePath != null || isCaretDirectlyAfterPackageKeyword(position);
                    boolean isUseTargetContext = useTarget != null || isCaretAtUseElementTargetPosition(position);

                    if (!isPackageContext && !isUseTargetContext) {
                        return;
                    }

                    var prefix = extractDirectivePrefix(position, result.getPrefixMatcher().getPrefix());
                    var matcher = result.getPrefixMatcher();
                    if (!prefix.isEmpty() && !prefix.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
                        matcher = matcher.cloneWithPrefix(StvnVocabulary.SIGIL_TYPIC + prefix);
                    } else if (!prefix.equals(result.getPrefixMatcher().getPrefix())) {
                        matcher = matcher.cloneWithPrefix(prefix);
                    }
                    var targetResult = result.withPrefixMatcher(matcher);

                    // 2. Caret in :package :<caret>
                    if (isPackageContext) {
                        var candidatePackages = collectWorkspacePackages(project);
                        for (var pkg : candidatePackages) {
                            if (!StvnNamespaceRules.isReservedPackage(pkg)) {
                                targetResult.addElement(LookupElementBuilder.create(pkg)
                                    .withIcon(AllIcons.Nodes.Package)
                                    .withTypeText("Package Enclosure", true));
                            }
                        }
                        return;
                    }

                    // 3. Caret at any UseElement target slot inside :use [ elem1 elem2 :<caret> ]
                    targetResult.addElement(LookupElementBuilder.create(PRELUDE_ROOT_PKG)
                        .withIcon(AllIcons.Nodes.PpLib)
                        .withTypeText("Standard Library Prelude", true));
                    targetResult.addElement(LookupElementBuilder.create(PRELUDE_META_PKG)
                        .withIcon(AllIcons.Nodes.PpLib)
                        .withTypeText("Standard Library Metadata", true));

                    var candidatePackages = StvnFlatDocumentIncludeInspection.isHermeticCompilationUnit(file)
                        ? StvnFlatDocumentIncludeInspection.collectLocalNonReservedPackages(file)
                        : collectWorkspacePackages(project);
                    for (var pkg : candidatePackages) {
                        if (!StvnNamespaceRules.isReservedPackage(pkg)) {
                            targetResult.addElement(LookupElementBuilder.create(pkg)
                                .withIcon(AllIcons.Nodes.Package)
                                .withTypeText("Import Target", true));
                        }
                    }
                }
            }
        );
    }

    private static void addUseElementBlockCompletions(
        PsiElement position,
        PsiFile file,
        Project project,
        @Nullable UseOptionsBlock useOptionsBlock,
        @Nullable UseAliasBlock useAliasBlock,
        CompletionResultSet result
    ) {
        PsiElement enclosingBlock = useOptionsBlock != null ? useOptionsBlock : useAliasBlock;
        if (enclosingBlock == null) {
            return;
        }
        UseElement useElement = PsiTreeUtil.getParentOfType(enclosingBlock, UseElement.class);
        if (useElement == null) {
            return;
        }

        String effectivePrefix = extractBraceInnerPrefix(position, result.getPrefixMatcher().getPrefix());
        CompletionResultSet customResult = result.withPrefixMatcher(effectivePrefix);

        // Offer #strip when in UseOptionsBlock, or in the first brace block of UseElement when no UseOptionsBlock precedes it
        boolean canOfferStrip = false;
        if (useOptionsBlock != null) {
            var rbrace = useOptionsBlock.getNode().findChildByType(StvnTypes.RBRACE);
            boolean beforeClose = rbrace == null || position.getTextOffset() <= rbrace.getStartOffset();
            canOfferStrip = beforeClose && useOptionsBlock.getUseOptionList().isEmpty();
        } else if (useAliasBlock != null && useElement.getUseOptionsBlock() == null) {
            boolean emptyAliases = useAliasBlock.getUseMapAliasList().isEmpty()
                || (useAliasBlock.getUseMapAliasList().size() == 1
                    && useAliasBlock.getUseMapAliasList().get(0).getText().contains("IntellijIdeaRulezzz"));
            canOfferStrip = emptyAliases
                && (effectivePrefix.isEmpty() || effectivePrefix.startsWith(StvnVocabulary.SIGIL_VALUE));
        }

        if (canOfferStrip && !effectivePrefix.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
            customResult.addElement(
                LookupElementBuilder.create(StvnVocabulary.FACET_KW_STRIP)
                    .withIcon(AllIcons.Nodes.Parameter)
                    .withTypeText("Directive Flag", true)
            );
        }

        // Offer exported :Type and #CONST LHS symbols when at an LHS slot inside UseAliasBlock
        if (useAliasBlock != null && isAtUseAliasLhsSlot(position, useAliasBlock)) {
            UseTarget targetNode = useElement.getUseTarget();
            String targetPkg = targetNode != null ? targetNode.getText().trim() : "";
            if (targetPkg.isEmpty() || targetNode.getUseTargetIllegal() != null) {
                return;
            }

            Set<String> alreadyAliasedLhs = collectAlreadyAliasedLhsSymbols(useAliasBlock, position);
            Map<String, String> exportedSymbols = collectExportedPackageSymbols(file, project, targetPkg);
            for (var entry : exportedSymbols.entrySet()) {
                String sym = entry.getKey();
                if (alreadyAliasedLhs.contains(sym)) {
                    continue;
                }
                if (effectivePrefix.startsWith(StvnVocabulary.SIGIL_TYPIC)
                        && !sym.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
                    continue;
                }
                if (effectivePrefix.startsWith(StvnVocabulary.SIGIL_VALUE)
                        && !sym.startsWith(StvnVocabulary.SIGIL_VALUE)) {
                    continue;
                }
                boolean isConst = sym.startsWith(StvnVocabulary.SIGIL_VALUE);
                customResult.addElement(
                    LookupElementBuilder.create(sym)
                        .withIcon(isConst ? AllIcons.Nodes.Constant : AllIcons.Nodes.Class)
                        .withTypeText(entry.getValue(), true)
                );
            }
        }
    }

    private static boolean isAtUseAliasLhsSlot(PsiElement position, UseAliasBlock aliasBlock) {
        var rbrace = aliasBlock.getNode().findChildByType(StvnTypes.RBRACE);
        if (rbrace != null && position.getTextOffset() > rbrace.getStartOffset()) {
            return false;
        }
        UseMapAlias enclosingPair = PsiTreeUtil.getParentOfType(position, UseMapAlias.class);
        if (enclosingPair != null && enclosingPair.getParent() == aliasBlock) {
            var typeKws = enclosingPair.getTypeKeywordList();
            if (!typeKws.isEmpty()) {
                return PsiTreeUtil.isAncestor(typeKws.get(0), position, false);
            }
            var valKws = enclosingPair.getValueKeywordList();
            if (!valKws.isEmpty()) {
                return PsiTreeUtil.isAncestor(valKws.get(0), position, false);
            }
            return true;
        }
        // Outside a parsed UseMapAlias inside UseAliasBlock: verify previous non-whitespace sibling is '{' or a complete 2-token UseMapAlias
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (prev != null && (StvnVocabulary.SIGIL_TYPIC.equals(prev.getText())
                || StvnVocabulary.SIGIL_VALUE.equals(prev.getText()))) {
            prev = PsiTreeUtil.prevVisibleLeaf(prev);
        }
        if (prev == null) {
            return false;
        }
        if ("{".equals(prev.getText())) {
            return true;
        }
        UseMapAlias prevAlias = PsiTreeUtil.getParentOfType(prev, UseMapAlias.class, false);
        if (prevAlias != null && prevAlias.getParent() == aliasBlock) {
            return prevAlias.getTypeKeywordList().size() >= 2 || prevAlias.getValueKeywordList().size() >= 2;
        }
        return false;
    }

    private static Set<String> collectAlreadyAliasedLhsSymbols(UseAliasBlock aliasBlock, PsiElement position) {
        Set<String> seen = new HashSet<>();
        for (UseMapAlias alias : aliasBlock.getUseMapAliasList()) {
            if (PsiTreeUtil.isAncestor(alias, position, false)) {
                continue;
            }
            if (!alias.getTypeKeywordList().isEmpty()) {
                seen.add(alias.getTypeKeywordList().get(0).getText().trim());
            }
            if (!alias.getValueKeywordList().isEmpty()) {
                seen.add(alias.getValueKeywordList().get(0).getText().trim());
            }
        }
        return seen;
    }

    private static Map<String, String> collectExportedPackageSymbols(
        PsiFile file,
        Project project,
        String targetPkg
    ) {
        Map<String, String> symbols = new LinkedHashMap<>();
        if (StvnPrelude.isPreludePackageTarget(targetPkg)) {
            PsiFile preludeFile = StvnPreludeBridge.getPreludeFile(project);
            collectPackageSymbolsFromFile(preludeFile, targetPkg, symbols, "Prelude Symbol");
            return symbols;
        }
        collectPackageSymbolsFromFile(file, targetPkg, symbols, targetPkg);
        if (!StvnFlatDocumentIncludeInspection.isHermeticCompilationUnit(file)) {
            collectTransitiveIncludedPackageSymbols(file, targetPkg, symbols, new HashSet<>());
        }
        return symbols;
    }

    private static void collectTransitiveIncludedPackageSymbols(
        PsiFile currentFile,
        String targetPkg,
        Map<String, String> symbols,
        Set<VirtualFile> visited
    ) {
        VirtualFile vf = currentFile.getVirtualFile();
        if (vf != null && !visited.add(vf)) {
            return;
        }
        for (IncludeElement incl : PsiTreeUtil.findChildrenOfType(currentFile, IncludeElement.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(incl) || incl.getStringLiteral() == null) {
                continue;
            }
            PsiFile incFile = StvnTypeReference.resolveIncludeFile(incl.getStringLiteral());
            if (incFile != null) {
                collectPackageSymbolsFromFile(incFile, targetPkg, symbols, targetPkg);
                collectTransitiveIncludedPackageSymbols(incFile, targetPkg, symbols, visited);
            }
        }
    }

    private static void collectPackageSymbolsFromFile(
        PsiFile sourceFile,
        String targetPkg,
        Map<String, String> out,
        String originLabel
    ) {
        for (PackageEnclosure pkg : PsiTreeUtil.findChildrenOfType(sourceFile, PackageEnclosure.class)) {
            var pkgPath = pkg.getPackagePath();
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(pkg)
                    || pkgPath == null
                    || org.stvnadore.plugin.psi.StvnPsiUtils.hasIllegalTrailingSlash(pkg)) {
                continue;
            }
            String pathText = pkgPath.getText().trim();
            if (!targetPkg.equals(pathText)) {
                continue;
            }
            for (var elem : pkg.getPackageElementList()) {
                TypeDefinition td = elem.getTypeDefinition();
                if (td != null && td.getTypeKeyword() != null) {
                    out.putIfAbsent(td.getTypeKeyword().getText().trim(), originLabel);
                }
                ConstantDefinition cd = elem.getConstantDefinition();
                if (cd != null && cd.getValueKeyword() != null) {
                    out.putIfAbsent(cd.getValueKeyword().getText().trim(), originLabel);
                }
            }
        }
        String typePrefix = targetPkg + "/";
        String constPrefix = StvnVocabulary.SIGIL_VALUE
            + (targetPkg.startsWith(StvnVocabulary.SIGIL_TYPIC) ? targetPkg.substring(1) : targetPkg)
            + "/";
        for (DefsEntry defs : PsiTreeUtil.findChildrenOfType(sourceFile, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defs)) {
                continue;
            }
            for (var elem : defs.getDefsElementList()) {
                TypeDefinition td = elem.getTypeDefinition();
                if (td != null && td.getTypeKeyword() != null) {
                    String raw = td.getTypeKeyword().getText().trim();
                    if (raw.startsWith(typePrefix) && raw.indexOf('/', typePrefix.length()) < 0) {
                        out.putIfAbsent(StvnVocabulary.SIGIL_TYPIC + raw.substring(typePrefix.length()), originLabel);
                    }
                }
                ConstantDefinition cd = elem.getConstantDefinition();
                if (cd != null && cd.getValueKeyword() != null) {
                    String raw = cd.getValueKeyword().getText().trim();
                    if (raw.startsWith(constPrefix) && raw.indexOf('/', constPrefix.length()) < 0) {
                        out.putIfAbsent(StvnVocabulary.SIGIL_VALUE + raw.substring(constPrefix.length()), originLabel);
                    }
                }
            }
        }
    }

    private static String extractDirectivePrefix(PsiElement position, String defaultPrefix) {
        String rawText = position.getText();
        int dummyIdx = rawText.indexOf("IntellijIdeaRulezzz");
        String head = dummyIdx >= 0 ? rawText.substring(0, dummyIdx) : defaultPrefix;
        PsiElement directiveTarget = PsiTreeUtil.getParentOfType(position, UseTarget.class, PackagePath.class);
        if (directiveTarget != null) {
            String parentText = directiveTarget.getText();
            int pDummyIdx = parentText.indexOf("IntellijIdeaRulezzz");
            if (pDummyIdx >= 0) {
                return parentText.substring(0, pDummyIdx).trim();
            }
        }
        var prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (head.isEmpty() && prev != null && StvnVocabulary.SIGIL_TYPIC.equals(prev.getText())) {
            return StvnVocabulary.SIGIL_TYPIC;
        }
        return head;
    }

    private static String extractBraceInnerPrefix(PsiElement position, String defaultPrefix) {
        String rawText = position.getText();
        int dummyIdx = rawText.indexOf("IntellijIdeaRulezzz");
        String head = dummyIdx >= 0 ? rawText.substring(0, dummyIdx) : defaultPrefix;
        if (head.isEmpty()) {
            var prev = PsiTreeUtil.prevVisibleLeaf(position);
            if (prev != null && (StvnVocabulary.SIGIL_TYPIC.equals(prev.getText())
                    || StvnVocabulary.SIGIL_VALUE.equals(prev.getText()))) {
                return prev.getText();
            }
        }
        return head;
    }

    private static boolean isCaretDirectlyAfterPackageKeyword(PsiElement position) {
        var prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (prev != null && StvnVocabulary.KEYWORD_PACKAGE.equals(prev.getText())) {
            return true;
        }
        var pkgEnc = PsiTreeUtil.getParentOfType(position, PackageEnclosure.class);
        if (pkgEnc != null) {
            var openBrace = pkgEnc.getNode().findChildByType(StvnTypes.LBRACE);
            if (openBrace == null || position.getTextOffset() < openBrace.getStartOffset()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCaretAtUseElementTargetPosition(PsiElement elem) {
        if (PsiTreeUtil.getParentOfType(elem, UseOptionsBlock.class, UseAliasBlock.class) != null) {
            return false;
        }
        var useStmt = PsiTreeUtil.getParentOfType(elem, UseStmt.class);
        if (useStmt == null) {
            var prev = PsiTreeUtil.prevVisibleLeaf(elem);
            if (prev != null && (StvnVocabulary.KEYWORD_USE.equals(prev.getText()) || "[".equals(prev.getText()))) {
                var grandPrev = PsiTreeUtil.prevVisibleLeaf(prev);
                return StvnVocabulary.KEYWORD_USE.equals(prev.getText())
                    || (grandPrev != null && StvnVocabulary.KEYWORD_USE.equals(grandPrev.getText()));
            }
            return false;
        }
        var lbrack = useStmt.getNode().findChildByType(StvnTypes.LBRACK);
        if (lbrack != null && elem.getTextOffset() <= lbrack.getStartOffset()) {
            return false;
        }
        var rbrack = useStmt.getNode().findChildByType(StvnTypes.RBRACK);
        return rbrack == null || elem.getTextOffset() <= rbrack.getStartOffset();
    }

    private static Set<String> collectWorkspacePackages(Project project) {
        var packages = new HashSet<String>();
        var scope = GlobalSearchScope.projectScope(project);
        var psiManager = PsiManager.getInstance(project);

        var virtualFiles = new ArrayList<VirtualFile>();
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Defs.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.DefsInclude.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.PayloadInclude.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Payload.INSTANCE, scope));

        for (var vf : virtualFiles) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile == null) continue;
            for (var pkg : PsiTreeUtil.findChildrenOfType(psiFile, PackageEnclosure.class)) {
                var path = pkg.getPackagePath();
                if (path != null && path.getTypeKeyword() != null) {
                    packages.add(path.getTypeKeyword().getText());
                }
            }
        }
        return packages;
    }
}
