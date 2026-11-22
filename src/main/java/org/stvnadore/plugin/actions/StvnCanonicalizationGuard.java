package org.stvnadore.plugin.actions;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DoNotAskOption;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.PackagePath;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefTarget;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guard utility preventing unintended loss of authoring constructs during in-place canonicalization projections.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnCanonicalizationGuard {

    private static volatile @Nullable Boolean confirmationOverride;
    private static volatile @Nullable String lastPromptedMessage;
    private static volatile List<DestructiveConstruct> lastDetectedConstructs = List.of();

    /**
     * Categories of STVN authoring syntax removed, lowered, or inlined during in-place canonicalization.
     */
    public enum DestructiveConstruct {
        /** Single-line {@code //} comments removed by canonical IR printing. */
        COMMENTS("single-line // comments"),
        /** Modular {@code :include} directives flattened into canonical definitions. */
        INCLUDE_DIRECTIVES(StvnVocabulary.KEYWORD_INCLUDE + " directives"),
        /** Modular {@code :package} enclosures desugared into canonical definitions. */
        PACKAGE_ENCLOSURES(StvnVocabulary.KEYWORD_PACKAGE + " enclosures"),
        /** Scoped {@code :use} directives desugared and removed during lowering. */
        USE_DIRECTIVES(StvnVocabulary.KEYWORD_USE + " directives"),
        /** Constant definitions inlined into payload values or schema metadata facets. */
        CONSTANT_DEFINITIONS(StvnVocabulary.SIGIL_VALUE + "CONST definitions (inlined into values and facets)"),
        /** Unreferenced type or constant definitions pruned by reachability verification. */
        UNREFERENCED_DEFINITIONS("unreferenced " + StvnVocabulary.KEYWORD_DEFS + " definitions"),
        /** Tautological or redundant metadata facets normalized away during canonical IR emission. */
        REDUNDANT_METADATA_FACETS("redundant metadata facets (W003)");

        private final String label;

        DestructiveConstruct(String label) {
            this.label = label;
        }

        /**
         * Returns the Simplified Technical English (STE) bullet label displayed in the confirmation dialog.
         *
         * @return human-readable construct category description
         */
        public String displayLabel() {
            return label;
        }
    }

    private StvnCanonicalizationGuard() {
    }

    /**
     * Installs or clears a deterministic test override for {@link #confirmCanonicalization}.
     *
     * @param allowCanonicalize {@code Boolean.TRUE} to proceed, {@code Boolean.FALSE} to cancel, or {@code null} to clear
     */
    @TestOnly
    public static void setConfirmationOverride(@Nullable Boolean allowCanonicalize) {
        confirmationOverride = allowCanonicalize;
        if (allowCanonicalize == null) {
            lastPromptedMessage = null;
            lastDetectedConstructs = List.of();
        }
    }

    /**
     * Returns the confirmation prompt text prepared during the most recent {@link #confirmCanonicalization} check.
     *
     * @return formatted confirmation message, or {@code null} when no confirmation prompt was required
     */
    @TestOnly
    public static @Nullable String getLastPromptedMessage() {
        return lastPromptedMessage;
    }

    /**
     * Returns the immutable list of {@link DestructiveConstruct} categories detected during the most recent check.
     *
     * @return detected construct categories in canonical display order
     */
    @TestOnly
    public static List<DestructiveConstruct> getLastDetectedConstructs() {
        return lastDetectedConstructs;
    }

    /**
     * Inspects {@code file} and returns all authoring construct categories that in-place canonicalization
     * removes, desugars, inlines, or prunes.
     *
     * @param file the source STVN PSI file
     * @return immutable ordered list of detected {@link DestructiveConstruct} categories (empty when lossless)
     */
    public static List<DestructiveConstruct> detectDestructiveConstructs(PsiFile file) {
        return ReadAction.compute(() -> {
            var constructs = new ArrayList<DestructiveConstruct>(7);
            if (PsiTreeUtil.findChildOfType(file, PsiComment.class) != null) {
                constructs.add(DestructiveConstruct.COMMENTS);
            }
            if (PsiTreeUtil.findChildOfType(file, IncludeStmt.class) != null) {
                constructs.add(DestructiveConstruct.INCLUDE_DIRECTIVES);
            }
            if (PsiTreeUtil.findChildOfType(file, PackageEnclosure.class) != null) {
                constructs.add(DestructiveConstruct.PACKAGE_ENCLOSURES);
            }
            if (PsiTreeUtil.findChildOfType(file, UseStmt.class) != null) {
                constructs.add(DestructiveConstruct.USE_DIRECTIVES);
            }
            if (hasDestructiveConstantDefinitions(file)) {
                constructs.add(DestructiveConstruct.CONSTANT_DEFINITIONS);
            }
            if (hasUnreferencedDefinitions(file)) {
                constructs.add(DestructiveConstruct.UNREFERENCED_DEFINITIONS);
            }
            if (hasRedundantMetadataFacets(file)) {
                constructs.add(DestructiveConstruct.REDUNDANT_METADATA_FACETS);
            }
            return List.copyOf(constructs);
        });
    }

    /**
     * Verifies whether the document contains any authoring construct that will be discarded or transformed
     * by compiler canonicalization.
     *
     * @param file the source STVN PSI file
     * @return {@code true} when {@link #detectDestructiveConstructs(PsiFile)} is non-empty
     */
    public static boolean hasDestructiveAuthoringConstructs(PsiFile file) {
        return !detectDestructiveConstructs(file).isEmpty();
    }

    /**
     * Checks whether the active STVN file contains unreferenced type or constant definitions in {@code :defs},
     * accounting for transitive references across {@code :type}, reachable {@code :defs} schemas (both top-level
     * and inside {@code :package} enclosures via direct name, FQNI, {@code #strip}, or {@code UseMapAlias}),
     * {@link MetadataValue} constraint facets ({@code #minIncl #CONST}, {@code #size #CONST}),
     * constant RHS values, and {@code :body} payloads.
     *
     * @param file the source STVN PSI file
     * @return {@code true} if any definition in {@code :defs} is unreferenced in the root contract or payload
     */
    public static boolean hasUnreferencedDefinitions(PsiFile file) {
        return ReadAction.compute(() -> {
            if (isDefinitionsOnlyDocument(file)) {
                return false;
            }
            DefsEntry defsEntry = findPrimaryDefsEntry(file);
            if (defsEntry == null) {
                return false;
            }

            var localTypeDefs = new ArrayList<>(PsiTreeUtil.findChildrenOfType(defsEntry, TypeDefinition.class));
            var localConstDefs = new ArrayList<>(PsiTreeUtil.findChildrenOfType(defsEntry, ConstantDefinition.class));
            if (localTypeDefs.isEmpty() && localConstDefs.isEmpty()) {
                return false;
            }

            TypeEntry typeEntry = findPrimaryTypeEntry(file);
            if (typeEntry == null || typeEntry.getSchemaType() == null) {
                return true;
            }

            var typeByFqni = new HashMap<String, List<TypeDefinition>>();
            for (var td : localTypeDefs) {
                var kw = td.getTypeKeyword();
                if (kw == null) {
                    continue;
                }
                String canonical = computeCanonicalTypeFqni(td, kw.getText().trim());
                typeByFqni.computeIfAbsent(canonical, k -> new ArrayList<>()).add(td);
            }

            var constByFqni = new HashMap<String, List<ConstantDefinition>>();
            for (var cd : localConstDefs) {
                var kw = cd.getValueKeyword();
                if (kw == null) {
                    continue;
                }
                String canonical = computeCanonicalConstantFqni(cd, kw.getText().trim());
                constByFqni.computeIfAbsent(canonical, k -> new ArrayList<>()).add(cd);
            }

            var reachableTypes = new HashSet<TypeDefinition>();
            var reachableConsts = new HashSet<ConstantDefinition>();
            var typeQueue = new ArrayDeque<TypeDefinition>();
            var constQueue = new ArrayDeque<ConstantDefinition>();

            enqueueReferencedTypes(file, typeEntry.getSchemaType(), typeByFqni, typeQueue, reachableTypes);
            enqueueMetadataValueConstants(file, typeEntry, constByFqni, constQueue, reachableConsts);

            BodyEntry bodyEntry = findPrimaryBodyEntry(file);
            if (bodyEntry != null && bodyEntry.getValue() != null) {
                enqueueValueConstants(file, bodyEntry.getValue(), constByFqni, constQueue, reachableConsts);
            }

            while (!typeQueue.isEmpty() || !constQueue.isEmpty()) {
                while (!typeQueue.isEmpty()) {
                    var targetDef = typeQueue.poll();
                    if (targetDef.getSchemaType() != null) {
                        enqueueReferencedTypes(file, targetDef.getSchemaType(), typeByFqni, typeQueue, reachableTypes);
                    }
                    enqueueMetadataValueConstants(file, targetDef, constByFqni, constQueue, reachableConsts);
                }
                while (!constQueue.isEmpty()) {
                    var targetConst = constQueue.poll();
                    if (targetConst.getSchemaType() != null) {
                        enqueueReferencedTypes(file, targetConst.getSchemaType(), typeByFqni, typeQueue, reachableTypes);
                    }
                    enqueueMetadataValueConstants(file, targetConst, constByFqni, constQueue, reachableConsts);
                    if (targetConst.getValue() != null) {
                        enqueueValueConstants(file, targetConst.getValue(), constByFqni, constQueue, reachableConsts);
                    }
                }
            }

            for (var td : localTypeDefs) {
                if (td.getTypeKeyword() != null && !reachableTypes.contains(td)) {
                    return true;
                }
            }
            for (var cd : localConstDefs) {
                if (cd.getValueKeyword() != null && !reachableConsts.contains(cd)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Evaluates the active document against destructive canonicalization loss and prompts for confirmation
     * when authoring constructs would be removed.
     *
     * @param project the active IntelliJ project
     * @param file    the source STVN PSI file
     * @return {@code true} if execution can proceed, {@code false} if cancelled by the user
     */
    public static boolean confirmCanonicalization(Project project, PsiFile file) {
        return confirmCanonicalization(project, file, null);
    }

    /**
     * Evaluates the active document against destructive canonicalization loss and displays an itemized,
     * EDT-guaranteed confirmation dialog naming the target format and detected construct categories.
     *
     * @param project     the active IntelliJ project
     * @param file        the committed source STVN PSI file
     * @param formatLabel human-readable target format label (for example, {@code "Pretty IR"} or {@code "Compact IR"})
     * @return {@code true} if execution can proceed, {@code false} if cancelled by the user
     */
    public static boolean confirmCanonicalization(
            Project project,
            PsiFile file,
            @Nullable String formatLabel
    ) {
        List<DestructiveConstruct> constructs = detectDestructiveConstructs(file);
        lastDetectedConstructs = constructs;
        if (constructs.isEmpty()) {
            lastPromptedMessage = null;
            return true;
        }

        var settings = StvnProjectSettings.getInstance(project);
        var state = settings.getState();
        if (!state.confirmDestructiveCanonicalization || state.suppressCanonicalizationWarning) {
            lastPromptedMessage = null;
            return true;
        }

        String fileName = ReadAction.compute(file::getName);
        String message = buildConfirmationMessage(fileName, formatLabel, constructs);
        lastPromptedMessage = message;

        Boolean override = confirmationOverride;
        if (override != null) {
            return override;
        }

        Application app = ApplicationManager.getApplication();
        if (app == null || app.isUnitTestMode() || app.isHeadlessEnvironment()) {
            return true;
        }

        DoNotAskOption doNotAsk = new DoNotAskOption.Adapter() {
            @Override
            public void rememberChoice(boolean isSelected, int exitCode) {
                if (isSelected && exitCode == Messages.YES) {
                    settings.getState().confirmDestructiveCanonicalization = false;
                }
            }

            @Override
            public @NotNull String getDoNotShowMessage() {
                return "Do not show this confirmation again for this project";
            }

            @Override
            public boolean shouldSaveOptionsOnCancel() {
                return false;
            }
        };

        if (app.isDispatchThread()) {
            return showConfirmationDialogOnEdt(project, message, doNotAsk);
        }
        var proceedRef = new AtomicBoolean(false);
        app.invokeAndWait(() -> proceedRef.set(showConfirmationDialogOnEdt(project, message, doNotAsk)));
        return proceedRef.get();
    }

    static String buildConfirmationMessage(
            String fileName,
            @Nullable String formatLabel,
            List<DestructiveConstruct> constructs
    ) {
        String targetFormat = (formatLabel != null && !formatLabel.isBlank()) ? formatLabel : "canonical IR";
        var sb = new StringBuilder();
        sb.append("Canonicalizing '").append(fileName).append("' to ").append(targetFormat)
            .append(" removes these authoring constructs from the active buffer:\n");
        for (DestructiveConstruct construct : constructs) {
            sb.append("  \u2022 ").append(construct.displayLabel()).append("\n");
        }
        sb.append("\nPress Ctrl+Z (Undo) to restore the original buffer.\n")
            .append("Do you want to canonicalize the active buffer?");
        return sb.toString();
    }

    private static boolean showConfirmationDialogOnEdt(
            Project project,
            String message,
            DoNotAskOption doNotAsk
    ) {
        return MessageDialogBuilder.yesNo("STVN: Canonicalize Active Buffer In-Place", message)
            .yesText("Canonicalize Buffer")
            .noText("Cancel")
            .doNotAsk(doNotAsk)
            .ask(project);
    }

    private static boolean hasDestructiveConstantDefinitions(PsiFile file) {
        if (!isDefinitionsOnlyDocument(file)) {
            return PsiTreeUtil.findChildOfType(file, ConstantDefinition.class) != null;
        }
        for (MetadataValue metaValue : PsiTreeUtil.findChildrenOfType(file, MetadataValue.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(metaValue)
                    && metaValue.getValueKeyword() != null) {
                return true;
            }
        }
        for (ConstantDefinition constDef : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef) || constDef.getValue() == null) {
                continue;
            }
            for (ValueKeyword vkw : collectValueKeywordsInSubtree(constDef.getValue())) {
                String text = vkw.getText().trim();
                if (StvnPsiUtils.isConstructorTag(text) || isBooleanLiteralKeyword(text)) {
                    continue;
                }
                PsiElement resolved = StvnConstantReference.resolveConstantWithContext(vkw);
                if (StvnPsiUtils.resolveTargetConstantDefinition(resolved) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRedundantMetadataFacets(PsiFile file) {
        var snapshot = StvnCompiledDocumentCache.getSnapshot(file);
        String activeNormPath = snapshot.normalizedDocPath();
        for (StvnDiagnostic diag : snapshot.diagnostics()) {
            if (!isDiagnosticInActiveFile(file, diag, activeNormPath)) {
                continue;
            }
            String sym = diag.symbolicId().orElse("");
            String code = diag.canonicalCode().orElse("");
            if (DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(sym)
                    || "W003".equals(code)
                    || sym.endsWith("WARN_REDUNDANT_METADATA_FACET")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDiagnosticInActiveFile(
            PsiFile file,
            StvnDiagnostic diag,
            String activeNormalizedPath
    ) {
        if (diag.sourcePath().isEmpty()) {
            return true;
        }
        String diagPath = diag.sourcePath().get();
        if (!activeNormalizedPath.isEmpty()) {
            return diagPath.equals(activeNormalizedPath);
        }
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return true;
        }
        String vfPath = StvnSchemaFlattener.normalizePath(virtualFile.getPath());
        String strippedVf = vfPath.replaceFirst("^/+", "");
        return diagPath.equals(vfPath) || (!strippedVf.isEmpty() && diagPath.endsWith("/" + strippedVf));
    }

    private static boolean isDefinitionsOnlyDocument(PsiFile file) {
        var fileName = file.getName();
        if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_di")) {
            return true;
        }
        var kindVal = PsiTreeUtil.findChildOfType(file, MetaKindValue.class);
        if (kindVal != null) {
            var text = kindVal.getText().trim();
            return StvnVocabulary.KIND_KW_DEFS.equals(text)
                    || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(text);
        }
        return false;
    }

    private static @Nullable DefsEntry findPrimaryDefsEntry(PsiFile file) {
        for (DefsEntry defs : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(defs)) {
                return defs;
            }
        }
        return null;
    }

    private static @Nullable TypeEntry findPrimaryTypeEntry(PsiFile file) {
        for (TypeEntry te : PsiTreeUtil.findChildrenOfType(file, TypeEntry.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(te)) {
                return te;
            }
        }
        return null;
    }

    private static @Nullable BodyEntry findPrimaryBodyEntry(PsiFile file) {
        for (BodyEntry be : PsiTreeUtil.findChildrenOfType(file, BodyEntry.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(be)) {
                return be;
            }
        }
        return null;
    }

    private static String computeCanonicalTypeFqni(TypeDefinition td, String rawKwText) {
        PackageEnclosure pkg = PsiTreeUtil.getParentOfType(td, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            String pkgPath = pkg.getPackagePath().getText().trim();
            String cleanName = rawKwText.startsWith(StvnVocabulary.SIGIL_TYPIC)
                ? rawKwText.substring(1)
                : rawKwText;
            return pkgPath + "/" + cleanName;
        }
        return rawKwText;
    }

    private static String computeCanonicalConstantFqni(ConstantDefinition cd, String rawKwText) {
        PackageEnclosure pkg = PsiTreeUtil.getParentOfType(cd, PackageEnclosure.class);
        if (pkg != null && !StvnPsiUtils.hasIllegalTrailingSlash(pkg) && pkg.getPackagePath() != null) {
            return StvnConstantReference.buildPackageConstantFqni(pkg.getPackagePath().getText(), rawKwText);
        }
        return StvnConstantReference.canonicalizeConstantQuery(rawKwText);
    }

    private static void enqueueReferencedTypes(
            PsiFile hostFile,
            SchemaType schemaType,
            Map<String, List<TypeDefinition>> typeByFqni,
            Queue<TypeDefinition> queue,
            Set<TypeDefinition> reachableTypes
    ) {
        var keywords = new ArrayList<TypeKeyword>();
        if (schemaType.getTypeKeyword() != null) {
            keywords.add(schemaType.getTypeKeyword());
        }
        keywords.addAll(PsiTreeUtil.findChildrenOfType(schemaType, TypeKeyword.class));

        for (TypeKeyword kw : keywords) {
            if (kw.getParent() instanceof TypeDefTarget
                    || PsiTreeUtil.getParentOfType(kw, UseTarget.class, PackagePath.class) != null) {
                continue;
            }
            PsiElement resolved = new StvnTypeReference(kw).resolve();
            TypeDefinition targetTd = StvnPsiUtils.resolveTargetTypeDefinition(resolved);
            if (targetTd != null && targetTd.getContainingFile() == hostFile && reachableTypes.add(targetTd)) {
                queue.add(targetTd);
            }
            List<TypeDefinition> byName = typeByFqni.get(kw.getText().trim());
            if (byName != null) {
                for (TypeDefinition candidate : byName) {
                    if (reachableTypes.add(candidate)) {
                        queue.add(candidate);
                    }
                }
            }
        }
    }

    private static void enqueueMetadataValueConstants(
            PsiFile hostFile,
            PsiElement scopeRoot,
            Map<String, List<ConstantDefinition>> constByFqni,
            Queue<ConstantDefinition> constQueue,
            Set<ConstantDefinition> reachableConsts
    ) {
        for (MetadataValue metaValue : PsiTreeUtil.findChildrenOfType(scopeRoot, MetadataValue.class)) {
            ValueKeyword vkw = metaValue.getValueKeyword();
            if (vkw != null) {
                enqueueResolvedConstant(hostFile, vkw, constByFqni, constQueue, reachableConsts);
            }
        }
    }

    private static void enqueueValueConstants(
            PsiFile hostFile,
            PsiElement valueRoot,
            Map<String, List<ConstantDefinition>> constByFqni,
            Queue<ConstantDefinition> constQueue,
            Set<ConstantDefinition> reachableConsts
    ) {
        for (ValueKeyword vkw : collectValueKeywordsInSubtree(valueRoot)) {
            if (!StvnPsiUtils.isConstantDefinitionTarget(vkw)) {
                enqueueResolvedConstant(hostFile, vkw, constByFqni, constQueue, reachableConsts);
            }
        }
    }

    private static void enqueueResolvedConstant(
            PsiFile hostFile,
            ValueKeyword vkw,
            Map<String, List<ConstantDefinition>> constByFqni,
            Queue<ConstantDefinition> constQueue,
            Set<ConstantDefinition> reachableConsts
    ) {
        PsiElement resolved = StvnConstantReference.resolveConstantWithContext(vkw);
        ConstantDefinition targetCd = StvnPsiUtils.resolveTargetConstantDefinition(resolved);
        if (targetCd != null && targetCd.getContainingFile() == hostFile && reachableConsts.add(targetCd)) {
            constQueue.add(targetCd);
        }
        String canonical = StvnConstantReference.canonicalizeConstantQuery(vkw.getText().trim());
        List<ConstantDefinition> byName = constByFqni.get(canonical);
        if (byName != null) {
            for (ConstantDefinition candidate : byName) {
                if (reachableConsts.add(candidate)) {
                    constQueue.add(candidate);
                }
            }
        }
    }

    private static boolean isBooleanLiteralKeyword(String text) {
        return StvnVocabulary.VAL_TRUE.equals(text)
            || StvnVocabulary.VAL_FALSE.equals(text)
            || StvnVocabulary.VAL_TRUE_SHORT.equals(text)
            || StvnVocabulary.VAL_FALSE_SHORT.equals(text);
    }

    private static List<ValueKeyword> collectValueKeywordsInSubtree(PsiElement root) {
        var result = new ArrayList<ValueKeyword>();
        if (root instanceof ValueKeyword direct) {
            result.add(direct);
        }
        result.addAll(PsiTreeUtil.findChildrenOfType(root, ValueKeyword.class));
        return result;
    }
}
