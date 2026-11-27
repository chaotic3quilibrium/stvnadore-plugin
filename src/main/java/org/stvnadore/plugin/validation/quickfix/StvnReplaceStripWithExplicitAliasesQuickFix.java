package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.StvnTrailingSlashInspection;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.PackageElement;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseAliasBlock;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseOptionsBlock;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Surgical quick-fix replacing {@code { #strip }} on a colliding {@link UseElement} with a
 * column-aligned {@code { ... }} {@link UseAliasBlock} that maps every exported symbol in the
 * target package to a deterministic, collision-free local identifier.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnReplaceStripWithExplicitAliasesQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    /**
     * Canonical STE quick-fix label.
     */
    public static final String QUICK_FIX_NAME =
        "Replace '" + StvnVocabulary.FACET_KW_STRIP + "' with explicit symbol aliases";

    /**
     * Constructs a new {@code StvnReplaceStripWithExplicitAliasesQuickFix} anchored to {@code useElement}.
     *
     * @param useElement offending {@code UseElement} carrying {@code { #strip }}
     */
    public StvnReplaceStripWithExplicitAliasesQuickFix(UseElement useElement) {
        super(useElement);
    }

    @Override
    public @NotNull String getText() {
        return QUICK_FIX_NAME;
    }

    @Override
    public @NotNull String getFamilyName() {
        return QUICK_FIX_NAME;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!(startElement instanceof UseElement useElement) || !useElement.isValid()) {
            return;
        }
        UseOptionsBlock optionsBlock = useElement.getUseOptionsBlock();
        UseTarget useTarget = useElement.getUseTarget();
        if (optionsBlock == null || useTarget == null) {
            return;
        }
        String pkgPath = StvnTrailingSlashInspection.stripTrailingSlashes(
            StvnTrailingSlashInspection.extractNonCommentText(useTarget).trim()
        );
        if (pkgPath.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(pkgPath)) {
            return;
        }

        List<String> exportedSymbols = collectExportedPackageSymbolsInOrder(file, pkgPath);
        if (exportedSymbols.isEmpty()) {
            return;
        }

        Set<String> occupiedNames = collectOccupiedNamesExcludingTargetStrip(file, useElement);
        UseAliasBlock existingAliasBlock = useElement.getUseAliasBlock();
        Map<String, List<String>> existingLhsToRhs = new LinkedHashMap<>();
        if (existingAliasBlock != null) {
            for (UseMapAlias alias : existingAliasBlock.getUseMapAliasList()) {
                String rawLhs = extractAliasLhsText(alias);
                String rawRhs = extractAliasRhsText(alias);
                if (rawLhs != null && rawRhs != null) {
                    String simpleLhs = toSimpleSymbolName(rawLhs);
                    existingLhsToRhs.computeIfAbsent(simpleLhs, k -> new ArrayList<>()).add(rawRhs);
                    occupiedNames.add(rawRhs);
                }
            }
        }

        String terminalSegment = extractTerminalPackageSegment(pkgPath);
        String pascalPrefix = toPascalCase(terminalSegment);
        String screamingPrefix = toScreamingSnakeCase(terminalSegment);

        record AliasPair(String lhs, String rhs) {}
        List<AliasPair> finalPairs = new ArrayList<>();
        for (var entry : existingLhsToRhs.entrySet()) {
            for (String rhs : entry.getValue()) {
                finalPairs.add(new AliasPair(entry.getKey(), rhs));
            }
        }

        for (String sym : exportedSymbols) {
            if (existingLhsToRhs.containsKey(sym)) {
                continue;
            }
            String chosenRhs;
            if (!occupiedNames.contains(sym)) {
                chosenRhs = sym;
            } else if (sym.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
                String bare = sym.substring(1);
                String baseCandidate = StvnVocabulary.SIGIL_TYPIC + pascalPrefix + bare;
                chosenRhs = disambiguateCandidate(baseCandidate, occupiedNames, false);
            } else {
                String bare = sym.substring(1);
                String baseCandidate = StvnVocabulary.SIGIL_VALUE + screamingPrefix + "_" + bare;
                chosenRhs = disambiguateCandidate(baseCandidate, occupiedNames, true);
            }
            occupiedNames.add(chosenRhs);
            finalPairs.add(new AliasPair(sym, chosenRhs));
        }

        if (finalPairs.isEmpty()) {
            return;
        }

        Document document = file.getViewProvider().getDocument();
        if (document == null) {
            return;
        }
        int baseIndent = computeLineLeadingSpaces(document, useElement.getTextRange().getStartOffset());
        String entryIndent = " ".repeat(baseIndent + 2);
        String closeIndent = " ".repeat(baseIndent);
        int maxLhsLen = 0;
        for (AliasPair p : finalPairs) {
            maxLhsLen = Math.max(maxLhsLen, p.lhs().length());
        }

        StringBuilder blockSb = new StringBuilder();
        blockSb.append(StvnVocabulary.DELIM_OPEN_BRACE);
        for (AliasPair p : finalPairs) {
            int pad = Math.max(1, (maxLhsLen - p.lhs().length()) + 1);
            blockSb.append('\n')
                .append(entryIndent)
                .append(p.lhs())
                .append(" ".repeat(pad))
                .append(p.rhs());
        }
        blockSb.append('\n').append(closeIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);

        int replaceStart = optionsBlock.getTextRange().getStartOffset();
        int replaceEnd = existingAliasBlock != null
            ? existingAliasBlock.getTextRange().getEndOffset()
            : optionsBlock.getTextRange().getEndOffset();

        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(useElement);
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(document);
        }
        document.replaceString(replaceStart, replaceEnd, blockSb.toString());
        if (commitPhysical) {
            docManager.commitDocument(document);
        }
    }

    /**
     * Collects all exported {@code :Type} and {@code #CONST} simple names declared in {@code pkgPath}
     * across {@code rootFile}, its transitive {@code :include} graph, and the Standard Library Prelude.
     *
     * @param rootFile active PSI file
     * @param pkgPath  canonical target package path (e.g. {@code :subsea/pump/raman})
     * @return deduplicated exported simple symbols in declaration order
     */
    public static List<String> collectExportedPackageSymbolsInOrder(PsiFile rootFile, String pkgPath) {
        Set<String> symbols = new LinkedHashSet<>();
        if (StvnPrelude.isPreludePackageTarget(pkgPath)) {
            PsiFile preludeFile = StvnPreludeBridge.getPreludeFile(rootFile.getProject());
            collectPackageSymbolsFromSingleFile(preludeFile, pkgPath, symbols);
        }
        List<PsiFile> moduleOrder = new ArrayList<>();
        Set<String> visitedPaths = new HashSet<>();
        collectIncludedFilesInOrder(rootFile, moduleOrder, visitedPaths);
        for (PsiFile included : moduleOrder) {
            collectPackageSymbolsFromSingleFile(included, pkgPath, symbols);
        }
        collectPackageSymbolsFromSingleFile(rootFile, pkgPath, symbols);
        return List.copyOf(symbols);
    }

    /**
     * Traverses {@code current} and its transitive {@code :include} modules in DFS post-order,
     * deduplicating visited canonical file paths so diamond {@code :include} graphs visit each module once.
     *
     * @param current      starting PSI file
     * @param outOrder     ordered output list of included PSI files
     * @param visitedPaths visited canonical file keys
     */
    public static void collectIncludedFilesInOrder(
            PsiFile current,
            List<PsiFile> outOrder,
            Set<String> visitedPaths
    ) {
        String key = canonicalFileKey(current);
        if (!visitedPaths.add(key)) {
            return;
        }
        for (DefsEntry defs : PsiTreeUtil.findChildrenOfType(current, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defs)) {
                continue;
            }
            for (DefsElement elem : defs.getDefsElementList()) {
                IncludeStmt incStmt = elem.getIncludeStmt();
                if (incStmt == null) {
                    continue;
                }
                for (IncludeElement incElem : incStmt.getIncludeElementList()) {
                    StringLiteral lit = incElem.getStringLiteral();
                    if (lit == null || StvnPsiLiteralEvaluator.decodeStringLiteral(lit.getText(), true).isEmpty()) {
                        continue;
                    }
                    PsiFile resolved = StvnTypeReference.resolveIncludeFile(lit);
                    if (resolved != null) {
                        collectIncludedFilesInOrder(resolved, outOrder, visitedPaths);
                        if (!outOrder.contains(resolved)) {
                            outOrder.add(resolved);
                        }
                    }
                }
            }
            break;
        }
    }

    private static void collectPackageSymbolsFromSingleFile(
            PsiFile file,
            String targetPkg,
            Set<String> outSymbols
    ) {
        String constPrefix = StvnVocabulary.SIGIL_VALUE + targetPkg.substring(1) + "/";
        String typePrefix = targetPkg + "/";
        for (DefsEntry defs : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defs)) {
                continue;
            }
            for (DefsElement elem : defs.getDefsElementList()) {
                PackageEnclosure pkgEnc = elem.getPackageEnclosure();
                if (pkgEnc != null && pkgEnc.getPackagePath() != null
                        && !StvnTrailingSlashInspection.hasTrailingSlash(pkgEnc.getPackagePath())) {
                    String encPath = StvnTrailingSlashInspection.extractNonCommentText(pkgEnc.getPackagePath()).trim();
                    if (targetPkg.equals(encPath)) {
                        for (PackageElement pe : pkgEnc.getPackageElementList()) {
                            if (pe.getTypeDefinition() != null && pe.getTypeDefinition().getTypeDefTarget() != null) {
                                String t = pe.getTypeDefinition().getTypeDefTarget().getText().trim();
                                if (t.startsWith(StvnVocabulary.SIGIL_TYPIC) && t.indexOf('/') < 0) {
                                    outSymbols.add(t);
                                }
                            } else if (pe.getConstantDefinition() != null
                                    && pe.getConstantDefinition().getValueKeyword() != null) {
                                String c = pe.getConstantDefinition().getValueKeyword().getText().trim();
                                if (c.startsWith(StvnVocabulary.SIGIL_VALUE) && c.indexOf('/') < 0) {
                                    outSymbols.add(c);
                                }
                            }
                        }
                    }
                } else if (elem.getTypeDefinition() != null && elem.getTypeDefinition().getTypeDefTarget() != null) {
                    String raw = elem.getTypeDefinition().getTypeDefTarget().getText().trim();
                    if (raw.startsWith(typePrefix) && raw.indexOf('/', typePrefix.length()) < 0) {
                        outSymbols.add(StvnVocabulary.SIGIL_TYPIC + raw.substring(typePrefix.length()));
                    }
                } else if (elem.getConstantDefinition() != null
                        && elem.getConstantDefinition().getValueKeyword() != null) {
                    String raw = elem.getConstantDefinition().getValueKeyword().getText().trim();
                    if (raw.startsWith(constPrefix) && raw.indexOf('/', constPrefix.length()) < 0) {
                        outSymbols.add(StvnVocabulary.SIGIL_VALUE + raw.substring(constPrefix.length()));
                    }
                }
            }
            break;
        }
    }

    private static Set<String> collectOccupiedNamesExcludingTargetStrip(PsiFile file, UseElement targetUseElement) {
        Set<String> occupied = new HashSet<>();
        PackageEnclosure enclosingPkg = PsiTreeUtil.getParentOfType(targetUseElement, PackageEnclosure.class);
        if (enclosingPkg != null && enclosingPkg.getPackagePath() != null) {
            String pkgPath = StvnTrailingSlashInspection.stripTrailingSlashes(
                StvnTrailingSlashInspection.extractNonCommentText(enclosingPkg.getPackagePath()).trim()
            );
            occupied.addAll(collectExportedPackageSymbolsInOrder(file, pkgPath));
            for (PackageElement pe : enclosingPkg.getPackageElementList()) {
                UseStmt useStmt = pe.getUseStmt();
                if (useStmt != null) {
                    collectUseStmtOccupiedNames(file, useStmt, targetUseElement, occupied);
                }
            }
            return occupied;
        }

        List<PsiFile> incOrder = new ArrayList<>();
        collectIncludedFilesInOrder(file, incOrder, new HashSet<>());
        for (PsiFile inc : incOrder) {
            collectUnpackagedRootDeclarations(inc, occupied);
        }
        collectUnpackagedRootDeclarations(file, occupied);

        for (DefsEntry defs : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defs)) {
                continue;
            }
            for (DefsElement elem : defs.getDefsElementList()) {
                if (elem.getUseStmt() != null) {
                    collectUseStmtOccupiedNames(file, elem.getUseStmt(), targetUseElement, occupied);
                }
            }
            break;
        }
        return occupied;
    }

    /**
     * Collects unpackaged root-level {@code :Type} and {@code #CONST} simple names declared in {@code file}.
     *
     * @param file target PSI file
     * @param out  destination symbol set
     */
    public static void collectUnpackagedRootDeclarations(PsiFile file, Set<String> out) {
        for (DefsEntry defs : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defs)) {
                continue;
            }
            for (DefsElement elem : defs.getDefsElementList()) {
                TypeDefinition td = elem.getTypeDefinition();
                if (td != null && td.getTypeDefTarget() != null) {
                    String raw = td.getTypeDefTarget().getText().trim();
                    if (raw.startsWith(StvnVocabulary.SIGIL_TYPIC) && raw.indexOf('/') < 0) {
                        out.add(raw);
                    }
                }
                ConstantDefinition cd = elem.getConstantDefinition();
                if (cd != null && cd.getValueKeyword() != null) {
                    String raw = cd.getValueKeyword().getText().trim();
                    if (raw.startsWith(StvnVocabulary.SIGIL_VALUE) && raw.indexOf('/') < 0) {
                        out.add(raw);
                    }
                }
            }
            break;
        }
    }

    private static void collectUseStmtOccupiedNames(
            PsiFile file,
            UseStmt useStmt,
            UseElement excludedStripElement,
            Set<String> occupied
    ) {
        for (UseElement ue : useStmt.getUseElementList()) {
            if (ue == excludedStripElement || ue.getUseTarget() == null) {
                continue;
            }
            String pkg = StvnTrailingSlashInspection.stripTrailingSlashes(
                StvnTrailingSlashInspection.extractNonCommentText(ue.getUseTarget()).trim()
            );
            Set<String> evicted = new HashSet<>();
            if (ue.getUseAliasBlock() != null) {
                for (UseMapAlias alias : ue.getUseAliasBlock().getUseMapAliasList()) {
                    String lhs = extractAliasLhsText(alias);
                    String rhs = extractAliasRhsText(alias);
                    if (lhs != null) {
                        evicted.add(toSimpleSymbolName(lhs));
                    }
                    if (rhs != null) {
                        occupied.add(rhs);
                    }
                }
            }
            if (StvnPsiUtils.hasValidStripOption(ue.getUseOptionsBlock())) {
                for (String exported : collectExportedPackageSymbolsInOrder(file, pkg)) {
                    if (!evicted.contains(exported)) {
                        occupied.add(exported);
                    }
                }
            }
        }
    }

    private static String disambiguateCandidate(String baseCandidate, Set<String> occupied, boolean screamingSnake) {
        if (!occupied.contains(baseCandidate)) {
            return baseCandidate;
        }
        int suffix = 2;
        while (true) {
            String next = screamingSnake ? (baseCandidate + "_" + suffix) : (baseCandidate + suffix);
            if (!occupied.contains(next)) {
                return next;
            }
            suffix++;
        }
    }

    private static String extractTerminalPackageSegment(String pkgPath) {
        int slash = pkgPath.lastIndexOf('/');
        String raw = slash >= 0 ? pkgPath.substring(slash + 1) : pkgPath.substring(1);
        return raw.isEmpty() ? "Pkg" : raw;
    }

    private static String toPascalCase(String segment) {
        StringBuilder sb = new StringBuilder(segment.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '_' || c == '-' || c == '.') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "Pkg" : sb.toString();
    }

    private static String toScreamingSnakeCase(String segment) {
        StringBuilder sb = new StringBuilder(segment.length() + 4);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '-' || c == '.') {
                sb.append('_');
            } else if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(segment.charAt(i - 1))) {
                sb.append('_').append(c);
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        String res = sb.toString().replaceAll("_+", "_");
        return res.isEmpty() ? "PKG" : res;
    }

    /**
     * Extracts the trimmed LHS identifier text from {@code alias}, or {@code null} if incomplete.
     *
     * @param alias target {@link UseMapAlias}
     * @return LHS text or {@code null}
     */
    public static @Nullable String extractAliasLhsText(UseMapAlias alias) {
        List<TypeKeyword> typeKws = alias.getTypeKeywordList();
        if (typeKws.size() == 2) {
            return typeKws.get(0).getText().trim();
        }
        List<ValueKeyword> valKws = alias.getValueKeywordList();
        if (valKws.size() == 2) {
            return valKws.get(0).getText().trim();
        }
        return null;
    }

    /**
     * Extracts the trimmed RHS identifier text from {@code alias}, or {@code null} if incomplete.
     *
     * @param alias target {@link UseMapAlias}
     * @return RHS text or {@code null}
     */
    public static @Nullable String extractAliasRhsText(UseMapAlias alias) {
        List<TypeKeyword> typeKws = alias.getTypeKeywordList();
        if (typeKws.size() == 2) {
            return typeKws.get(1).getText().trim();
        }
        List<ValueKeyword> valKws = alias.getValueKeywordList();
        if (valKws.size() == 2) {
            return valKws.get(1).getText().trim();
        }
        return null;
    }

    /**
     * Extracts the RHS PSI element ({@link TypeKeyword} or {@link ValueKeyword}) from {@code alias}.
     *
     * @param alias target {@link UseMapAlias}
     * @return RHS PSI element or {@code null}
     */
    public static @Nullable PsiElement extractAliasRhsElement(UseMapAlias alias) {
        List<TypeKeyword> typeKws = alias.getTypeKeywordList();
        if (typeKws.size() == 2) {
            return typeKws.get(1);
        }
        List<ValueKeyword> valKws = alias.getValueKeywordList();
        if (valKws.size() == 2) {
            return valKws.get(1);
        }
        return null;
    }

    /**
     * Strips any package path prefix from {@code rawSymbol}, returning its sigiled simple name.
     *
     * @param rawSymbol sigiled symbol ({@code :Foo}, {@code :pkg/Foo}, {@code #BAR}, or {@code #pkg/BAR})
     * @return sigiled simple identifier ({@code :Foo} or {@code #BAR})
     */
    public static String toSimpleSymbolName(String rawSymbol) {
        int slash = rawSymbol.lastIndexOf('/');
        if (slash < 0) {
            return rawSymbol;
        }
        String sigil = rawSymbol.startsWith(StvnVocabulary.SIGIL_VALUE)
            ? StvnVocabulary.SIGIL_VALUE
            : StvnVocabulary.SIGIL_TYPIC;
        return sigil + rawSymbol.substring(slash + 1);
    }

    /**
     * Computes a normalized, case-insensitive file identity key for cycle and diamond deduplication.
     *
     * @param file target PSI file
     * @return normalized identity key
     */
    public static String canonicalFileKey(PsiFile file) {
        var vf = file.getVirtualFile();
        return vf != null ? vf.getPath().replace('\\', '/').toLowerCase(Locale.ROOT) : file.getName();
    }

    private static int computeLineLeadingSpaces(Document doc, int offset) {
        int line = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(line);
        int lineEnd = doc.getLineEndOffset(line);
        CharSequence chars = doc.getCharsSequence();
        int spaces = 0;
        while (lineStart + spaces < lineEnd && chars.charAt(lineStart + spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }
}
