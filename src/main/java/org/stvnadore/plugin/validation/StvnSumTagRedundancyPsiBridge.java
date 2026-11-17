package org.stvnadore.plugin.validation;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.ir.StvnSumTrajectory;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ExplicitEitherValue;
import org.stvnadore.psi.ExplicitOptionValue;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-{@link PsiFile} cached bridge connecting IntelliJ PSI sum expressions across {@code :body}
 * and {@code :defs} {@link ConstantDefinition} slots to {@link StvnSumTagRedundancyEngine}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnSumTagRedundancyPsiBridge {

    private static final Key<CachedValue<Map<Integer, PsiElement>>> REDUNDANT_TAGS_KEY =
        Key.create("org.stvnadore.plugin.validation.REDUNDANT_SUM_TAGS");

    private static final Key<CachedValue<Map<Integer, StvnValue>>> CONST_VALUES_KEY =
        Key.create("org.stvnadore.plugin.validation.CONSTANT_DEF_CORE_VALUES");

    private record ExtractedSlot(
        Value slotRoot,
        List<PsiElement> tagElements,
        StvnSumTagRedundancyEngine.SlotProbe probe
    ) {
    }

    private StvnSumTagRedundancyPsiBridge() {
    }

    /**
     * Returns an unmodifiable map from tag token start offset to redundant tag {@link PsiElement}
     * for {@code file}, computed at most once per PSI modification stamp.
     *
     * @param file active STVN PSI file
     * @return map of redundant tag start offsets to tag elements
     */
    public static Map<Integer, PsiElement> getRedundantTagElements(PsiFile file) {
        if (StvnPreludeBridge.isPreludeFile(file)) {
            return Map.of();
        }
        var manager = CachedValuesManager.getManager(file.getProject());
        Map<Integer, PsiElement> cached = manager.getCachedValue(file, REDUNDANT_TAGS_KEY, () -> {
            Map<Integer, PsiElement> computed = computeRedundantTagsForFile(file);
            return CachedValueProvider.Result.create(
                computed,
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            );
        }, false);
        return cached != null ? cached : Map.of();
    }

    /**
     * Resolves the lowered Pure-IR {@link StvnValue} for {@code element} across both {@code :body}
     * payload nodes and {@code :defs} {@link ConstantDefinition} RHS values without altering
     * {@link StvnTypeResolver#resolveCoreValue(PsiElement)} for {@code :body}-only callers.
     *
     * @param element PSI element inside {@code :body} or {@code :defs} {@link ConstantDefinition}
     * @return aligned {@link StvnValue}, or {@code null} when unresolvable
     */
    public static @Nullable StvnValue resolveCoreOrConstantValue(PsiElement element) {
        StvnValue bodyVal = StvnTypeResolver.resolveCoreValue(element);
        if (bodyVal != null) {
            return bodyVal;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || PsiTreeUtil.getParentOfType(element, ConstantDefinition.class) == null) {
            return null;
        }
        Map<Integer, StvnValue> constMap = getConstantCoreValuesMap(file);
        return constMap.get(element.getTextRange().getStartOffset());
    }

    /**
     * Live soundness guard invoked by {@code StripVariantTagQuickFix.applyFix} (both on physical
     * files during single/batch fixes and on non-physical copies during {@code IntentionPreviewInfo.DIFF}).
     *
     * @param explicitWrapper {@link ExplicitOptionValue}, {@link ExplicitEitherValue}, or {@link ExplicitUnionValue}
     * @return {@code true} iff stripping {@code explicitWrapper} preserves Pure-IR {@link StvnValue} identity with zero errors
     */
    public static boolean canSafelyStripWrapper(PsiElement explicitWrapper) {
        PsiFile file = explicitWrapper.getContainingFile();
        if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
            return false;
        }
        if (!(explicitWrapper.getParent() instanceof Value wrapperValue)) {
            return false;
        }

        StvnCompiledDocumentCache.CompiledSnapshot snapshot = resolvePreviewSafeSnapshot(file);
        List<ExtractedSlot> slots = collectFileSumSlots(file, snapshot);
        for (ExtractedSlot slot : slots) {
            if (!PsiTreeUtil.isAncestor(slot.slotRoot(), wrapperValue, false)) {
                continue;
            }
            PsiElement targetTag = extractTagTokenElement(explicitWrapper);
            if (targetTag == null) {
                return false;
            }
            int idx = -1;
            for (int i = 0; i < slot.tagElements().size(); i++) {
                if (slot.tagElements().get(i).getTextRange().equals(targetTag.getTextRange())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return false;
            }
            StvnSumTagRedundancyEngine.TagSpan span = slot.probe().tags().get(idx);
            if (span.directControlKeywordStringCollision()) {
                return false;
            }
            String candidateSlotExpr = StvnSumTagRedundancyEngine.applyTagRemovals(
                slot.probe().slotText(),
                slot.probe().tags(),
                Set.of(idx)
            );
            if (!StvnSumTagRedundancyEngine.evaluatesToEquivalentValue(
                candidateSlotExpr,
                slot.probe().vOrig(),
                snapshot.catalog(),
                slot.probe().constantBindings()
            )) {
                return false;
            }
            // Extra guard when slotRoot itself sits inside an unspooled Explicit*Value container
            PsiElement slotParent = slot.slotRoot().getParent();
            if (slotParent instanceof ExplicitOptionValue
                || slotParent instanceof ExplicitEitherValue
                || slotParent instanceof ExplicitUnionValue) {
                return verifyContainerLevelUnspooledReplacement(slot.slotRoot(), wrapperValue, snapshot);
            }
            return true;
        }
        return false;
    }

    private static boolean verifyContainerLevelUnspooledReplacement(
        Value slotRoot,
        Value wrapperValue,
        StvnCompiledDocumentCache.CompiledSnapshot snapshot
    ) {
        Value innerChild = extractInnerChildValue(wrapperValue);
        if (innerChild == null) {
            return false;
        }
        Value enclosingContainerValue = findEnclosingCollectionSlotValue(slotRoot);
        if (enclosingContainerValue == null) {
            return false;
        }
        StvnValue containerIr = resolveValueInSnapshot(enclosingContainerValue, enclosingContainerValue.getContainingFile(), snapshot);
        if (containerIr == null) {
            return false;
        }
        TextRange containerRange = enclosingContainerValue.getTextRange();
        TextRange wrapperRange = wrapperValue.getTextRange();
        String containerText = enclosingContainerValue.getText();
        int relStart = wrapperRange.getStartOffset() - containerRange.getStartOffset();
        int relEnd = wrapperRange.getEndOffset() - containerRange.getStartOffset();
        if (relStart < 0 || relEnd > containerText.length() || relStart >= relEnd) {
            return false;
        }
        String mutatedContainerText = containerText.substring(0, relStart)
            + innerChild.getText()
            + containerText.substring(relEnd);
        Map<String, StvnDefsCatalog.StvnConstant> consts =
            collectReferencedConstants(enclosingContainerValue, snapshot.catalog());
        return StvnSumTagRedundancyEngine.evaluatesToEquivalentValue(
            mutatedContainerText,
            containerIr,
            snapshot.catalog(),
            consts
        );
    }

    private static @Nullable Value findEnclosingCollectionSlotValue(Value start) {
        PsiElement curr = start.getParent();
        while (curr != null && !(curr instanceof BodyEntry) && !(curr instanceof ConstantDefinition)) {
            if (curr instanceof Value v && v.getCollectionValue() != null) {
                return v;
            }
            curr = curr.getParent();
        }
        return null;
    }

    private static StvnCompiledDocumentCache.CompiledSnapshot resolvePreviewSafeSnapshot(PsiFile file) {
        if (file.isPhysical()) {
            return StvnCompiledDocumentCache.getSnapshot(file);
        }
        PsiFile orig = file.getOriginalFile();
        String text = file.getText();
        if (orig != file && orig.isPhysical() && text.equals(orig.getText())) {
            return StvnCompiledDocumentCache.getSnapshot(orig);
        }
        String fileName = orig.getVirtualFile() != null ? orig.getVirtualFile().getName() : orig.getName();
        StvnParserConfig config = StvnCompiledDocumentCache.isSystemSubstrateFileName(fileName)
            ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
            : StvnParserConfig.DEFAULT;
        StvnCompilationResult<StvnDocument> res = StvnCompiler.compileToResult(text, fileName, config);
        Optional<StvnDocument> docOpt = res.document();
        return new StvnCompiledDocumentCache.CompiledSnapshot(
            StvnSchemaFlattener.normalizePath(fileName),
            docOpt,
            docOpt.map(StvnDocument::defs).orElse(StvnDefsCatalog.EMPTY),
            docOpt.flatMap(StvnDocument::rootType),
            docOpt.flatMap(StvnDocument::payload),
            List.copyOf(res.diagnostics())
        );
    }

    private static Map<Integer, PsiElement> computeRedundantTagsForFile(PsiFile file) {
        StvnCompiledDocumentCache.CompiledSnapshot snapshot = StvnCompiledDocumentCache.getSnapshot(file);
        List<ExtractedSlot> extracted = collectFileSumSlots(file, snapshot);
        if (extracted.isEmpty()) {
            return Map.of();
        }
        List<StvnSumTagRedundancyEngine.SlotProbe> probes = extracted.stream()
            .map(ExtractedSlot::probe)
            .toList();
        List<Set<Integer>> selectedPerSlot =
            StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(probes, snapshot.catalog());

        Map<Integer, PsiElement> out = new LinkedHashMap<>();
        for (int s = 0; s < extracted.size(); s++) {
            Set<Integer> selectedIndices = selectedPerSlot.get(s);
            if (selectedIndices.isEmpty()) {
                continue;
            }
            List<PsiElement> tagElements = extracted.get(s).tagElements();
            for (int idx : selectedIndices) {
                if (idx >= 0 && idx < tagElements.size()) {
                    PsiElement tagEl = tagElements.get(idx);
                    out.put(tagEl.getTextRange().getStartOffset(), tagEl);
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<ExtractedSlot> collectFileSumSlots(
        PsiFile file,
        StvnCompiledDocumentCache.CompiledSnapshot snapshot
    ) {
        List<ExtractedSlot> out = new ArrayList<>();
        String fileText = file.getText();
        List<StvnDiagnostic> activeErrors = snapshot.diagnostics().stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
            .filter(d -> d.sourcePath().isEmpty()
                || StvnSchemaFlattener.normalizePath(d.sourcePath().get()).equals(snapshot.normalizedDocPath()))
            .toList();

        if (snapshot.payload().isPresent()) {
            BodyEntry bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
            if (bodyEntry != null
                && !StvnMetaTemplateUtil.isInSecondaryRootSection(bodyEntry)
                && bodyEntry.getValue() != null) {
                collectSlotsRecursive(
                    bodyEntry.getValue(),
                    snapshot.payload().get(),
                    fileText,
                    snapshot.catalog(),
                    activeErrors,
                    out
                );
            }
        }

        if (!snapshot.catalog().constants().isEmpty()) {
            for (ConstantDefinition constDef : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef) || constDef.getValue() == null) {
                    continue;
                }
                StvnDefsCatalog.StvnConstant irConst = resolveCatalogConstantForDef(constDef, snapshot.catalog());
                if (irConst != null) {
                    collectSlotsRecursive(
                        constDef.getValue(),
                        irConst.value(),
                        fileText,
                        snapshot.catalog(),
                        activeErrors,
                        out
                    );
                }
            }
        }
        return out;
    }

    private static void collectSlotsRecursive(
        @Nullable Value psiVal,
        @Nullable StvnValue irVal,
        String fileText,
        StvnDefsCatalog catalog,
        List<StvnDiagnostic> activeErrors,
        List<ExtractedSlot> out
    ) {
        if (psiVal == null || irVal == null) {
            return;
        }
        if (StvnSumTrajectory.isSumValue(irVal)) {
            ExtractedSlot slot = buildExtractedSlot(psiVal, irVal, fileText, catalog, activeErrors);
            if (slot != null) {
                out.add(slot);
            }
            StvnSumTrajectory traj = StvnSumTrajectory.evaluate(psiVal, irVal, catalog);
            if (!traj.stoppedAtConstantReference()
                && traj.terminalPsiValue() != null
                && !StvnSumTrajectory.isSumValue(traj.terminalIrValue())) {
                descendCompositeSlotChildren(traj.terminalPsiValue(), traj.terminalIrValue(), fileText, catalog, activeErrors, out);
            }
            return;
        }
        descendCompositeSlotChildren(psiVal, irVal, fileText, catalog, activeErrors, out);
    }

    private static void descendCompositeSlotChildren(
        Value psiVal,
        StvnValue irVal,
        String fileText,
        StvnDefsCatalog catalog,
        List<StvnDiagnostic> activeErrors,
        List<ExtractedSlot> out
    ) {
        var collPsi = psiVal.getCollectionValue();
        if (collPsi == null) {
            return;
        }
        if (irVal instanceof StvnValue.StvnTuple tup && collPsi.getTupleLiteral() != null) {
            collectContainerElementsWithUnspooling(
                PsiTreeUtil.getChildrenOfTypeAsList(collPsi.getTupleLiteral(), Value.class),
                tup.elements(),
                fileText,
                catalog,
                activeErrors,
                out
            );
        } else if (irVal instanceof StvnValue.StvnSeq seq && collPsi.getListLiteral() != null) {
            collectContainerElementsWithUnspooling(
                PsiTreeUtil.getChildrenOfTypeAsList(collPsi.getListLiteral(), Value.class),
                seq.elements(),
                fileText,
                catalog,
                activeErrors,
                out
            );
        } else if (irVal instanceof StvnValue.StvnSet set && collPsi.getListLiteral() != null) {
            collectContainerElementsWithUnspooling(
                PsiTreeUtil.getChildrenOfTypeAsList(collPsi.getListLiteral(), Value.class),
                new ArrayList<>(set.elements()),
                fileText,
                catalog,
                activeErrors,
                out
            );
        } else if (irVal instanceof StvnValue.StvnMap mapVal && collPsi.getMapLiteral() != null) {
            List<Value> psiChildren = collPsi.getMapLiteral().getValueList();
            var entries = mapVal.entries();
            for (int i = 0; i < psiChildren.size() / 2 && i < entries.size(); i++) {
                collectSlotsRecursive(psiChildren.get(i * 2), entries.get(i).key(), fileText, catalog, activeErrors, out);
                collectSlotsRecursive(psiChildren.get(i * 2 + 1), entries.get(i).value(), fileText, catalog, activeErrors, out);
            }
        }
    }

    private static void collectContainerElementsWithUnspooling(
        List<Value> psiValues,
        List<StvnValue> irElements,
        String fileText,
        StvnDefsCatalog catalog,
        List<StvnDiagnostic> activeErrors,
        List<ExtractedSlot> out
    ) {
        int[] irIdx = new int[]{0};
        for (Value psiVal : psiValues) {
            if (irIdx[0] >= irElements.size()) {
                break;
            }
            unspoolAndCollect(psiVal, irElements, irIdx, fileText, catalog, activeErrors, out);
        }
    }

    private static void unspoolAndCollect(
        Value psiVal,
        List<StvnValue> irElements,
        int[] irIdx,
        String fileText,
        StvnDefsCatalog catalog,
        List<StvnDiagnostic> activeErrors,
        List<ExtractedSlot> out
    ) {
        if (irIdx[0] >= irElements.size()) {
            return;
        }
        StvnValue targetIr = irElements.get(irIdx[0]);
        PsiElement explicitContainer = getDirectExplicitSumChild(psiVal);
        if (explicitContainer != null && StvnTypeResolver.isUnspooledContainer(explicitContainer, targetIr)) {
            irIdx[0]++;
            Value trailingSibling = extractInnerChildValue(psiVal);
            if (trailingSibling != null && irIdx[0] < irElements.size()) {
                unspoolAndCollect(trailingSibling, irElements, irIdx, fileText, catalog, activeErrors, out);
            }
            return;
        }
        collectSlotsRecursive(psiVal, targetIr, fileText, catalog, activeErrors, out);
        irIdx[0]++;
        if (StvnSumTrajectory.isSumValue(targetIr) && irIdx[0] < irElements.size()) {
            StvnSumTrajectory traj = StvnSumTrajectory.evaluate(psiVal, targetIr, catalog);
            Value termPsi = traj.terminalPsiValue();
            if (termPsi != null && !StvnSumTrajectory.isSumValue(traj.terminalIrValue())) {
                PsiElement termExplicit = getDirectExplicitSumChild(termPsi);
                if (termExplicit != null && StvnTypeResolver.isUnspooledContainer(termExplicit, traj.terminalIrValue())) {
                    Value trailingFromTerm = extractInnerChildValue(termPsi);
                    if (trailingFromTerm != null) {
                        unspoolAndCollect(trailingFromTerm, irElements, irIdx, fileText, catalog, activeErrors, out);
                    }
                }
            }
        }
    }

    private static @Nullable ExtractedSlot buildExtractedSlot(
        Value slotRoot,
        StvnValue vOrig,
        String fileText,
        StvnDefsCatalog catalog,
        List<StvnDiagnostic> activeErrors
    ) {
        if (PsiTreeUtil.hasErrorElements(slotRoot)
            || StvnSumTagRedundancyEngine.containsErrorValue(vOrig)
            || StvnSumTagRedundancyEngine.containsRealPoisonedSchema(vOrig.schema())) {
            return null;
        }
        int slotStart = slotRoot.getTextRange().getStartOffset();
        int slotEnd = computeUnspoolTruncatedSlotEnd(slotRoot, vOrig, catalog);
        if (slotEnd <= slotStart || slotEnd > fileText.length()) {
            return null;
        }
        for (StvnDiagnostic err : activeErrors) {
            if (err.startOffset() < slotEnd && err.endOffset() > slotStart) {
                return null;
            }
        }

        List<PsiElement> tagElements = new ArrayList<>();
        List<StvnSumTagRedundancyEngine.TagSpan> tagSpans = new ArrayList<>();
        Value currPsi = slotRoot;
        StvnValue currIr = vOrig;

        while (currPsi != null && StvnSumTrajectory.isSumValue(currIr)) {
            StvnSumTrajectory probeTraj = StvnSumTrajectory.evaluate(currPsi, currIr, catalog);
            if (probeTraj.stoppedAtConstantReference()) {
                break;
            }
            if (currIr instanceof StvnValue.StvnOption opt) {
                ExplicitOptionValue explicitOpt = currPsi.getExplicitOptionValue();
                if (explicitOpt != null) {
                    if (opt.value().isEmpty()) {
                        break;
                    }
                    PsiElement tagTok = extractTagTokenElement(explicitOpt);
                    Value childVal = explicitOpt.getValue();
                    if (tagTok == null || childVal == null) {
                        return null;
                    }
                    StvnValue innerIr = opt.value().get();
                    appendWrapperSpan(slotStart, slotEnd, fileText, tagTok, childVal, innerIr, catalog, tagElements, tagSpans);
                    currPsi = childVal;
                    currIr = innerIr;
                } else if (opt.value().isPresent()) {
                    currIr = opt.value().get();
                } else {
                    break;
                }
            } else if (currIr instanceof StvnValue.StvnEither eith) {
                ExplicitEitherValue explicitEither = currPsi.getExplicitEitherValue();
                if (explicitEither != null) {
                    PsiElement tagTok = extractTagTokenElement(explicitEither);
                    Value childVal = explicitEither.getValue();
                    if (tagTok == null || childVal == null) {
                        return null;
                    }
                    StvnValue innerIr = eith.value();
                    appendWrapperSpan(slotStart, slotEnd, fileText, tagTok, childVal, innerIr, catalog, tagElements, tagSpans);
                    currPsi = childVal;
                    currIr = innerIr;
                } else {
                    currIr = eith.value();
                }
            } else if (currIr instanceof StvnValue.StvnUnion un) {
                ExplicitUnionValue explicitUnion = currPsi.getExplicitUnionValue();
                if (explicitUnion != null) {
                    PsiElement tagTok = extractTagTokenElement(explicitUnion);
                    Value childVal = explicitUnion.getValue();
                    if (tagTok == null || childVal == null) {
                        return null;
                    }
                    StvnValue innerIr = un.value();
                    appendWrapperSpan(slotStart, slotEnd, fileText, tagTok, childVal, innerIr, catalog, tagElements, tagSpans);
                    currPsi = childVal;
                    currIr = innerIr;
                } else {
                    currIr = un.value();
                }
            }
        }

        if (tagSpans.isEmpty()) {
            return null;
        }
        String slotText = fileText.substring(slotStart, slotEnd);
        Map<String, StvnDefsCatalog.StvnConstant> constBindings = collectReferencedConstants(slotRoot, catalog);
        return new ExtractedSlot(
            slotRoot,
            List.copyOf(tagElements),
            new StvnSumTagRedundancyEngine.SlotProbe(slotText, List.copyOf(tagSpans), vOrig, constBindings)
        );
    }

    private static void appendWrapperSpan(
        int slotStart,
        int slotEnd,
        String fileText,
        PsiElement tagTok,
        Value childVal,
        StvnValue innerIr,
        StvnDefsCatalog catalog,
        List<PsiElement> tagElements,
        List<StvnSumTagRedundancyEngine.TagSpan> tagSpans
    ) {
        int startInSlot = tagTok.getTextRange().getStartOffset() - slotStart;
        int tokEndInFile = tagTok.getTextRange().getEndOffset();
        int childStartInFile = Math.min(slotEnd, childVal.getTextRange().getStartOffset());
        int delEndInFile = tokEndInFile;
        // Consume separating whitespace up to childVal, halting before any '//' comment
        PsiElement cursor = tagTok.getNextSibling();
        while (cursor != null && cursor.getTextRange().getStartOffset() < childStartInFile) {
            if (cursor instanceof PsiComment) {
                break;
            }
            if (cursor instanceof PsiWhiteSpace) {
                delEndInFile = Math.min(childStartInFile, cursor.getTextRange().getEndOffset());
            } else {
                break;
            }
            cursor = cursor.getNextSibling();
        }
        if (delEndInFile == tokEndInFile) {
            while (delEndInFile < childStartInFile && fileText.charAt(delEndInFile) == ' ') {
                delEndInFile++;
            }
        }
        boolean collides = StvnSumTagRedundancyEngine.isDirectControlOrConstantCollision(innerIr, catalog);
        tagElements.add(tagTok);
        tagSpans.add(new StvnSumTagRedundancyEngine.TagSpan(
            startInSlot,
            delEndInFile - slotStart,
            tagTok.getText(),
            collides
        ));
    }

    private static int computeUnspoolTruncatedSlotEnd(
        Value slotRoot,
        StvnValue vOrig,
        StvnDefsCatalog catalog
    ) {
        StvnSumTrajectory traj = StvnSumTrajectory.evaluate(slotRoot, vOrig, catalog);
        Value termPsi = traj.terminalPsiValue();
        if (termPsi != null && !StvnSumTrajectory.isSumValue(traj.terminalIrValue())) {
            PsiElement explicit = getDirectExplicitSumChild(termPsi);
            if (explicit != null && StvnTypeResolver.isUnspooledContainer(explicit, traj.terminalIrValue())) {
                PsiElement headTok = extractTagTokenElement(explicit);
                if (headTok != null) {
                    return headTok.getTextRange().getEndOffset();
                }
            }
        }
        return slotRoot.getTextRange().getEndOffset();
    }

    private static Map<String, StvnDefsCatalog.StvnConstant> collectReferencedConstants(
        Value root,
        StvnDefsCatalog catalog
    ) {
        Map<String, StvnDefsCatalog.StvnConstant> bindings = new LinkedHashMap<>();
        for (ValueKeyword vk : PsiTreeUtil.findChildrenOfType(root, ValueKeyword.class)) {
            String localName = vk.getText().trim();
            if (!localName.startsWith(StvnVocabulary.SIGIL_VALUE)
                || StvnVocabulary.CONTROL_KEYWORDS.contains(localName)) {
                continue;
            }
            StvnDefsCatalog.StvnConstant direct = findConstantInCatalogOrPrelude(localName, catalog);
            if (direct != null) {
                bindings.putIfAbsent(localName, direct);
                continue;
            }
            PsiElement resolved = StvnConstantReference.resolveConstantWithContext(vk);
            if (resolved != null) {
                StvnDefsCatalog.StvnConstant target = resolveConstantFromPsiTarget(resolved, catalog);
                if (target != null) {
                    bindings.putIfAbsent(localName, target);
                }
            }
        }
        return bindings.isEmpty() ? Map.of() : Collections.unmodifiableMap(bindings);
    }

    private static StvnDefsCatalog.@Nullable StvnConstant resolveConstantFromPsiTarget(
        PsiElement resolved,
        StvnDefsCatalog catalog
    ) {
        if (resolved.getParent() instanceof UseMapAlias alias && !alias.getValueKeywordList().isEmpty()) {
            String remoteKw = alias.getValueKeywordList().get(0).getText().trim();
            UseStmt useStmt = PsiTreeUtil.getParentOfType(alias, UseStmt.class);
            if (useStmt != null && useStmt.getUseTarget() != null) {
                String fqni = StvnConstantReference.buildPackageConstantFqni(
                    useStmt.getUseTarget().getText(),
                    remoteKw
                );
                StvnDefsCatalog.StvnConstant byFqni = findConstantInCatalogOrPrelude(fqni, catalog);
                if (byFqni != null) {
                    return byFqni;
                }
            }
            return findConstantInCatalogOrPrelude(remoteKw, catalog);
        }
        ConstantDefinition def = resolved instanceof ConstantDefinition cd
            ? cd
            : PsiTreeUtil.getParentOfType(resolved, ConstantDefinition.class);
        if (def != null) {
            return resolveCatalogConstantForDef(def, catalog);
        }
        return null;
    }

    private static StvnDefsCatalog.@Nullable StvnConstant findConstantInCatalogOrPrelude(
        String nameOrFqni,
        StvnDefsCatalog catalog
    ) {
        String canonical = StvnConstantReference.canonicalizeConstantQuery(nameOrFqni);
        StvnDefsCatalog.StvnConstant found = catalog.constants().get(canonical);
        if (found != null) {
            return found;
        }
        found = StvnPrelude.preludeCatalog().constants().get(canonical);
        if (found != null) {
            return found;
        }
        String bare = canonical.substring(1);
        String slashBare = "/" + bare;
        String slashSigilBare = "/" + StvnVocabulary.SIGIL_VALUE + bare;
        for (var entry : catalog.constants().entrySet()) {
            if (entry.getKey().endsWith(slashBare) || entry.getKey().endsWith(slashSigilBare)) {
                return entry.getValue();
            }
        }
        for (var entry : StvnPrelude.preludeCatalog().constants().entrySet()) {
            if (entry.getKey().endsWith(slashBare) || entry.getKey().endsWith(slashSigilBare)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static StvnDefsCatalog.@Nullable StvnConstant resolveCatalogConstantForDef(
        ConstantDefinition constDef,
        StvnDefsCatalog catalog
    ) {
        if (constDef.getValueKeyword() == null) {
            return null;
        }
        String kwText = constDef.getValueKeyword().getText().trim();
        PackageEnclosure pkg = PsiTreeUtil.getParentOfType(constDef, PackageEnclosure.class);
        String fqni = (pkg != null && pkg.getPackagePath() != null)
            ? StvnConstantReference.buildPackageConstantFqni(pkg.getPackagePath().getText(), kwText)
            : StvnConstantReference.canonicalizeConstantQuery(kwText);
        return findConstantInCatalogOrPrelude(fqni, catalog);
    }

    private static Map<Integer, StvnValue> getConstantCoreValuesMap(PsiFile file) {
        var manager = CachedValuesManager.getManager(file.getProject());
        Map<Integer, StvnValue> cached = manager.getCachedValue(file, CONST_VALUES_KEY, () -> {
            Map<Integer, StvnValue> map = new HashMap<>();
            StvnCompiledDocumentCache.CompiledSnapshot snapshot = StvnCompiledDocumentCache.getSnapshot(file);
            for (ConstantDefinition constDef : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef) || constDef.getValue() == null) {
                    continue;
                }
                StvnDefsCatalog.StvnConstant irConst = resolveCatalogConstantForDef(constDef, snapshot.catalog());
                if (irConst != null) {
                    mapPsiValuesSimple(constDef.getValue(), irConst.value(), map);
                }
            }
            return CachedValueProvider.Result.create(
                map,
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            );
        }, false);
        return cached != null ? cached : Map.of();
    }

    private static @Nullable StvnValue resolveValueInSnapshot(
        Value targetValue,
        @Nullable PsiFile file,
        StvnCompiledDocumentCache.CompiledSnapshot snapshot
    ) {
        if (file == null) {
            return null;
        }
        if (file.isPhysical()) {
            return resolveCoreOrConstantValue(targetValue);
        }
        Map<Integer, StvnValue> tempMap = new HashMap<>();
        if (snapshot.payload().isPresent()) {
            BodyEntry body = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
            if (body != null && body.getValue() != null) {
                mapPsiValuesSimple(body.getValue(), snapshot.payload().get(), tempMap);
            }
        }
        for (ConstantDefinition cd : PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class)) {
            if (cd.getValue() != null) {
                StvnDefsCatalog.StvnConstant irConst = resolveCatalogConstantForDef(cd, snapshot.catalog());
                if (irConst != null) {
                    mapPsiValuesSimple(cd.getValue(), irConst.value(), tempMap);
                }
            }
        }
        return tempMap.get(targetValue.getTextRange().getStartOffset());
    }

    private static void mapPsiValuesSimple(@Nullable Value psiVal, @Nullable StvnValue irVal, Map<Integer, StvnValue> out) {
        if (psiVal == null || irVal == null) {
            return;
        }
        out.putIfAbsent(psiVal.getTextRange().getStartOffset(), irVal);
        PsiElement explicitChild = getDirectExplicitSumChild(psiVal);
        if (explicitChild != null) {
            out.putIfAbsent(explicitChild.getTextRange().getStartOffset(), irVal);
        }
        if (irVal instanceof StvnValue.StvnOption opt && opt.value().isPresent()) {
            ExplicitOptionValue eo = psiVal.getExplicitOptionValue();
            mapPsiValuesSimple(eo != null ? eo.getValue() : psiVal, opt.value().get(), out);
        } else if (irVal instanceof StvnValue.StvnEither eith) {
            ExplicitEitherValue ee = psiVal.getExplicitEitherValue();
            mapPsiValuesSimple(ee != null ? ee.getValue() : psiVal, eith.value(), out);
        } else if (irVal instanceof StvnValue.StvnUnion un) {
            ExplicitUnionValue eu = psiVal.getExplicitUnionValue();
            mapPsiValuesSimple(eu != null ? eu.getValue() : psiVal, un.value(), out);
        } else if (psiVal.getCollectionValue() != null) {
            var coll = psiVal.getCollectionValue();
            if (irVal instanceof StvnValue.StvnTuple tup && coll.getTupleLiteral() != null) {
                List<Value> ch = PsiTreeUtil.getChildrenOfTypeAsList(coll.getTupleLiteral(), Value.class);
                for (int i = 0; i < ch.size() && i < tup.elements().size(); i++) {
                    mapPsiValuesSimple(ch.get(i), tup.elements().get(i), out);
                }
            } else if (irVal instanceof StvnValue.StvnSeq seq && coll.getListLiteral() != null) {
                List<Value> ch = PsiTreeUtil.getChildrenOfTypeAsList(coll.getListLiteral(), Value.class);
                for (int i = 0; i < ch.size() && i < seq.elements().size(); i++) {
                    mapPsiValuesSimple(ch.get(i), seq.elements().get(i), out);
                }
            } else if (irVal instanceof StvnValue.StvnSet set && coll.getListLiteral() != null) {
                List<Value> ch = PsiTreeUtil.getChildrenOfTypeAsList(coll.getListLiteral(), Value.class);
                List<StvnValue> els = new ArrayList<>(set.elements());
                for (int i = 0; i < ch.size() && i < els.size(); i++) {
                    mapPsiValuesSimple(ch.get(i), els.get(i), out);
                }
            } else if (irVal instanceof StvnValue.StvnMap map && coll.getMapLiteral() != null) {
                List<Value> ch = coll.getMapLiteral().getValueList();
                var entries = map.entries();
                for (int i = 0; i < ch.size() / 2 && i < entries.size(); i++) {
                    mapPsiValuesSimple(ch.get(i * 2), entries.get(i).key(), out);
                    mapPsiValuesSimple(ch.get(i * 2 + 1), entries.get(i).value(), out);
                }
            }
        }
    }

    private static @Nullable PsiElement getDirectExplicitSumChild(Value v) {
        if (v.getExplicitOptionValue() != null) {
            return v.getExplicitOptionValue();
        }
        if (v.getExplicitEitherValue() != null) {
            return v.getExplicitEitherValue();
        }
        if (v.getExplicitUnionValue() != null) {
            return v.getExplicitUnionValue();
        }
        return null;
    }

    private static @Nullable Value extractInnerChildValue(Value v) {
        if (v.getExplicitOptionValue() != null) {
            return v.getExplicitOptionValue().getValue();
        }
        if (v.getExplicitEitherValue() != null) {
            return v.getExplicitEitherValue().getValue();
        }
        if (v.getExplicitUnionValue() != null) {
            return v.getExplicitUnionValue().getValue();
        }
        return null;
    }

    static @Nullable PsiElement extractTagTokenElement(PsiElement explicitWrapper) {
        if (explicitWrapper instanceof ExplicitOptionValue o) {
            if (o.getSomeLiteral() != null) {
                return o.getSomeLiteral();
            }
            if (o.getSomeShortLiteral() != null) {
                return o.getSomeShortLiteral();
            }
            if (o.getNoneLiteral() != null) {
                return o.getNoneLiteral();
            }
            return o.getNoneShortLiteral();
        }
        if (explicitWrapper instanceof ExplicitEitherValue e) {
            if (e.getLeftLiteral() != null) {
                return e.getLeftLiteral();
            }
            if (e.getLeftShortLiteral() != null) {
                return e.getLeftShortLiteral();
            }
            if (e.getRightLiteral() != null) {
                return e.getRightLiteral();
            }
            return e.getRightShortLiteral();
        }
        if (explicitWrapper instanceof ExplicitUnionValue u) {
            return u.getUnionTagPrefix();
        }
        return null;
    }
}
