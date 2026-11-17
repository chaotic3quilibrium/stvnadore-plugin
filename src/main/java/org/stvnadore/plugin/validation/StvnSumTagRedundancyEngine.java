package org.stvnadore.plugin.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.io.CanonicalLayoutWriter;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-IR algebraic sum-tag redundancy and semantic round-trip equivalence engine.
 * <p>
 * Evaluates candidate tag removals across complete enclosing slot value spines against
 * {@link StvnCompiler} and {@code StvnValue.equals}, selecting only deterministic
 * inner-to-outer subsets that preserve {@link StvnValue} identity individually, jointly,
 * and across every forward prefix and reverse suffix.
 * <p>
 * Contains zero {@code com.intellij.*} dependencies so it executes directly inside both
 * headless {@code :unitTest} suites and interactive IDE inspection caches.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnSumTagRedundancyEngine {

    private static final String RECURSIVE_SENTINEL_CODE = "INTERNAL_RECURSIVE_NOMINAL_REF";
    private static final String PRELUDE_TYPE_PREFIX = StvnVocabulary.SIGIL_TYPIC + "org/stvnadore/prelude/";
    private static final String PRELUDE_CONST_PREFIX = StvnVocabulary.SIGIL_VALUE + "org/stvnadore/prelude/";

    /**
     * Describes a single explicit unary sum-constructor tag along a slot's outer tag chain.
     *
     * @param startInSlot                        inclusive character start offset within {@code slotText}
     * @param deleteEndInSlot                    exclusive character end offset deleted when stripping this tag
     * @param tagText                            authored tag token ({@code #Some}, {@code #S}, {@code #Left}, {@code #L}, {@code #Right}, {@code #R}, or {@code #1}..{@code #N})
     * @param directControlKeywordStringCollision {@code true} when this tag directly wraps a string or enum value whose lexical form collides with a control, union-tag, or constant token
     */
    public record TagSpan(
        int startInSlot,
        int deleteEndInSlot,
        String tagText,
        boolean directControlKeywordStringCollision
    ) {
    }

    /**
     * Encapsulates a single governed sum-typed slot spine ready for batched Pure-IR verification.
     *
     * @param slotText         authored source slice of the slot expression
     * @param tags             ordered outer-to-inner unary sum-tag spans along the slot's explicit prefix
     * @param vOrig            lowered Pure-IR {@link StvnValue} of the unmodified slot
     * @param constantBindings local or {@code :use}-renamed constant identifiers referenced inside {@code slotText}
     */
    public record SlotProbe(
        String slotText,
        List<TagSpan> tags,
        StvnValue vOrig,
        Map<String, StvnDefsCatalog.StvnConstant> constantBindings
    ) {
    }

    private record ProbeGroupKey(StvnSchema schema, String constantSignature) {
    }

    private record ProbeCacheKey(StvnSchema schema, String constantSignature, String candidateExpr) {
    }

    private record StagedProbe(
        ProbeGroupKey groupKey,
        ProbeCacheKey cacheKey,
        Map<String, StvnDefsCatalog.StvnConstant> constantBindings
    ) {
    }

    private StvnSumTagRedundancyEngine() {
    }

    /**
     * Selects composable redundant tag indices for every slot in {@code slots} using schema-grouped
     * batched Pure-IR compilation.
     *
     * @param slots   ordered list of sum-typed slot probes in the compilation unit
     * @param catalog lowered Pure-IR definitions catalog for reachable nominal types
     * @return list of selected zero-based tag index sets aligned 1:1 with {@code slots}
     */
    public static List<Set<Integer>> selectComposableRemovableTagsBatched(
        List<SlotProbe> slots,
        StvnDefsCatalog catalog
    ) {
        if (slots.isEmpty()) {
            return List.of();
        }

        Map<ProbeCacheKey, Optional<StvnValue>> evalCache = new HashMap<>();
        List<String> constSignatures = new ArrayList<>(slots.size());
        List<List<Integer>> candidateIndicesPerSlot = new ArrayList<>(slots.size());
        List<StagedProbe> round1Batch = new ArrayList<>();
        Set<ProbeCacheKey> stagedKeys = new HashSet<>();

        for (SlotProbe slot : slots) {
            String sig = computeConstantSignature(slot.constantBindings());
            constSignatures.add(sig);

            if (slot.tags().isEmpty()
                || containsErrorValue(slot.vOrig())
                || containsRealPoisonedSchema(slot.vOrig().schema())) {
                candidateIndicesPerSlot.add(List.of());
                continue;
            }

            List<Integer> candIndices = new ArrayList<>(slot.tags().size());
            for (int i = 0; i < slot.tags().size(); i++) {
                TagSpan span = slot.tags().get(i);
                if (!isNonInferableLeftTag(span.tagText()) && !span.directControlKeywordStringCollision()) {
                    candIndices.add(i);
                }
            }
            candidateIndicesPerSlot.add(candIndices);
            if (candIndices.isEmpty()) {
                continue;
            }

            Set<Set<Integer>> initialSubsets = new LinkedHashSet<>();
            int r = candIndices.size();
            for (int idx : candIndices) {
                initialSubsets.add(Set.of(idx));
            }
            Set<Integer> ascPrefix = new LinkedHashSet<>();
            for (int idx : candIndices) {
                ascPrefix.add(idx);
                initialSubsets.add(new LinkedHashSet<>(ascPrefix));
            }
            Set<Integer> descSuffix = new LinkedHashSet<>();
            for (int k = r - 1; k >= 0; k--) {
                descSuffix.add(candIndices.get(k));
                initialSubsets.add(new LinkedHashSet<>(descSuffix));
            }

            ProbeGroupKey groupKey = new ProbeGroupKey(slot.vOrig().schema(), sig);
            for (Set<Integer> subset : initialSubsets) {
                String expr = applyTagRemovals(slot.slotText(), slot.tags(), subset);
                if (expr.isEmpty()) {
                    continue;
                }
                ProbeCacheKey cacheKey = new ProbeCacheKey(slot.vOrig().schema(), sig, expr);
                if (stagedKeys.add(cacheKey)) {
                    round1Batch.add(new StagedProbe(groupKey, cacheKey, slot.constantBindings()));
                }
            }
        }

        executeBatchProbes(round1Batch, catalog, evalCache);

        List<Set<Integer>> results = new ArrayList<>(slots.size());
        for (int s = 0; s < slots.size(); s++) {
            SlotProbe slot = slots.get(s);
            List<Integer> candIndices = candidateIndicesPerSlot.get(s);
            if (candIndices.isEmpty()) {
                results.add(Set.of());
                continue;
            }
            String sig = constSignatures.get(s);
            ProbeGroupKey groupKey = new ProbeGroupKey(slot.vOrig().schema(), sig);

            // Fast-path: check if all candidate tags are jointly, prefix-wise, and suffix-wise removable
            Set<Integer> allCandidates = new LinkedHashSet<>(candIndices);
            if (isSubsetFullySound(slot, allCandidates, groupKey, catalog, evalCache)) {
                results.add(Set.copyOf(allCandidates));
                continue;
            }

            // Deterministic inner-to-outer greedy selection
            Set<Integer> selected = new LinkedHashSet<>();
            for (int k = candIndices.size() - 1; k >= 0; k--) {
                int idx = candIndices.get(k);
                if (!evaluatesCachedOrCompile(slot, Set.of(idx), groupKey, catalog, evalCache)) {
                    continue;
                }
                Set<Integer> nextSet = new LinkedHashSet<>(selected);
                nextSet.add(idx);
                if (isSubsetFullySound(slot, nextSet, groupKey, catalog, evalCache)) {
                    selected = nextSet;
                }
            }
            results.add(Set.copyOf(selected));
        }
        return List.copyOf(results);
    }

    /**
     * Verifies whether a single candidate post-removal expression compiles with zero errors and
     * lowers to a Pure-IR {@link StvnValue} equal to {@code vOrig}.
     *
     * @param candidateSlotText post-removal slot expression text
     * @param vOrig             lowered Pure-IR value of the pre-removal slot
     * @param catalog           Pure-IR definitions catalog
     * @param constantBindings  local or {@code :use}-renamed constant bindings referenced in the slot
     * @return {@code true} iff {@code candidateSlotText} compiles cleanly and equals {@code vOrig}
     */
    public static boolean evaluatesToEquivalentValue(
        String candidateSlotText,
        StvnValue vOrig,
        StvnDefsCatalog catalog,
        Map<String, StvnDefsCatalog.StvnConstant> constantBindings
    ) {
        if (candidateSlotText.isBlank()
            || containsErrorValue(vOrig)
            || containsRealPoisonedSchema(vOrig.schema())) {
            return false;
        }
        String sig = computeConstantSignature(constantBindings);
        ProbeGroupKey groupKey = new ProbeGroupKey(vOrig.schema(), sig);
        ProbeCacheKey cacheKey = new ProbeCacheKey(vOrig.schema(), sig, candidateSlotText.strip());
        Map<ProbeCacheKey, Optional<StvnValue>> singleCache = new HashMap<>(2);
        executeBatchProbes(List.of(new StagedProbe(groupKey, cacheKey, constantBindings)), catalog, singleCache);
        Optional<StvnValue> got = singleCache.get(cacheKey);
        return got != null && got.isPresent() && vOrig.equals(got.get());
    }

    /**
     * Applies the specified zero-based tag removals to {@code slotText}.
     *
     * @param slotText        authored slot text
     * @param tags            ordered tag spans within {@code slotText}
     * @param indicesToRemove zero-based indices in {@code tags} to strip
     * @return post-removal slot expression string
     */
    public static String applyTagRemovals(
        String slotText,
        List<TagSpan> tags,
        Set<Integer> indicesToRemove
    ) {
        if (indicesToRemove.isEmpty()) {
            return slotText;
        }
        StringBuilder sb = new StringBuilder(slotText.length());
        int cursor = 0;
        for (int i = 0; i < tags.size(); i++) {
            if (indicesToRemove.contains(i)) {
                TagSpan span = tags.get(i);
                if (span.startInSlot() > cursor) {
                    sb.append(slotText, cursor, span.startInSlot());
                }
                cursor = Math.max(cursor, span.deleteEndInSlot());
            }
        }
        if (cursor < slotText.length()) {
            sb.append(slotText, cursor, slotText.length());
        }
        return sb.toString().stripLeading();
    }

    /**
     * Parses a contiguous chain of explicit unary sum tags from a plain STVN value string
     * paired with its lowered {@link StvnValue} for headless unit verification.
     *
     * @param slotText authored STVN slot expression
     * @param vOrig    lowered Pure-IR value for {@code slotText}
     * @return ordered list of unary {@link TagSpan}s
     */
    public static List<TagSpan> parseSimpleSlotTagChain(String slotText, StvnValue vOrig) {
        return parseSimpleSlotTagChain(slotText, vOrig, StvnDefsCatalog.EMPTY);
    }

    /**
     * Parses a contiguous chain of explicit unary sum tags from a plain STVN value string
     * paired with its lowered {@link StvnValue} and {@link StvnDefsCatalog}.
     *
     * @param slotText authored STVN slot expression
     * @param vOrig    lowered Pure-IR value for {@code slotText}
     * @param catalog  lowered Pure-IR definitions catalog
     * @return ordered list of unary {@link TagSpan}s
     */
    public static List<TagSpan> parseSimpleSlotTagChain(
        String slotText,
        StvnValue vOrig,
        StvnDefsCatalog catalog
    ) {
        List<TagSpan> spans = new ArrayList<>();
        int pos = 0;
        int len = slotText.length();
        StvnValue currIr = vOrig;

        while (pos < len && isSumValue(currIr)) {
            while (pos < len && Character.isWhitespace(slotText.charAt(pos))) {
                pos++;
            }
            if (pos >= len || !slotText.startsWith(StvnVocabulary.SIGIL_VALUE, pos)) {
                break;
            }
            int tokStart = pos;
            pos += StvnVocabulary.SIGIL_VALUE.length();
            while (pos < len
                && !Character.isWhitespace(slotText.charAt(pos))
                && "()[]{}".indexOf(slotText.charAt(pos)) < 0) {
                pos++;
            }
            String tok = slotText.substring(tokStart, pos);
            StvnValue nextIr = matchExplicitUnaryStep(tok, currIr);
            if (nextIr == null) {
                break;
            }
            int wsEnd = pos;
            while (wsEnd < len && slotText.charAt(wsEnd) == ' ') {
                wsEnd++;
            }
            if (wsEnd >= len) {
                break;
            }
            boolean collides = isDirectControlOrConstantCollision(nextIr, catalog);
            spans.add(new TagSpan(tokStart, wsEnd, tok, collides));
            currIr = nextIr;
            pos = wsEnd;
        }
        return List.copyOf(spans);
    }

    /**
     * Checks recursively whether {@code value} is or contains an {@link StvnValue.StvnErrorValue}.
     *
     * @param value Pure-IR value to inspect
     * @return {@code true} if any node in {@code value} is an error sentinel
     */
    public static boolean containsErrorValue(StvnValue value) {
        return switch (value) {
            case StvnValue.StvnErrorValue ignored -> true;
            case StvnValue.StvnTuple t -> t.elements().stream().anyMatch(StvnSumTagRedundancyEngine::containsErrorValue);
            case StvnValue.StvnSeq s -> s.elements().stream().anyMatch(StvnSumTagRedundancyEngine::containsErrorValue);
            case StvnValue.StvnSet s -> s.elements().stream().anyMatch(StvnSumTagRedundancyEngine::containsErrorValue);
            case StvnValue.StvnMap m -> m.entries().stream()
                .anyMatch(e -> containsErrorValue(e.key()) || containsErrorValue(e.value()));
            case StvnValue.StvnOption o -> o.value().map(StvnSumTagRedundancyEngine::containsErrorValue).orElse(false);
            case StvnValue.StvnEither e -> containsErrorValue(e.value());
            case StvnValue.StvnUnion u -> containsErrorValue(u.value());
            default -> false;
        };
    }

    /**
     * Checks recursively whether {@code schema} contains a genuine {@link StvnSchema.PoisonedSchema}
     * (excluding guarded recursive nominal back-edges).
     *
     * @param schema Pure-IR schema to inspect
     * @return {@code true} if poisoned/degraded
     */
    public static boolean containsRealPoisonedSchema(StvnSchema schema) {
        return containsRealPoisonedSchemaRecursive(schema, new HashSet<>());
    }

    /**
     * Returns {@code true} when {@code innerIrValue} is a direct {@link StvnValue.StvnString}
     * or {@link StvnValue.StvnEnum} whose lexical value matches a control keyword or numeric
     * union tag ({@code #1}, {@code #2}, ...), matching {@code AstPrettyPrinter}'s tag-retention guard.
     *
     * @param innerIrValue immediate child Pure-IR value of an explicit sum wrapper
     * @return {@code true} when the enclosing sum tag must be retained for formatter parity
     */
    public static boolean isDirectControlKeywordStringCollision(StvnValue innerIrValue) {
        return isDirectControlOrConstantCollision(innerIrValue, StvnDefsCatalog.EMPTY);
    }

    /**
     * Returns {@code true} when {@code innerIrValue} is a direct {@link StvnValue.StvnString}
     * or {@link StvnValue.StvnEnum} whose lexical token collides with a control keyword, numeric
     * union tag, or declared constant in {@code catalog}, matching {@code AstPrettyPrinter}.
     *
     * @param innerIrValue immediate child Pure-IR value of an explicit sum wrapper
     * @param catalog      lowered Pure-IR definitions catalog
     * @return {@code true} when the enclosing sum tag must be retained for formatter and container parity
     */
    public static boolean isDirectControlOrConstantCollision(
        StvnValue innerIrValue,
        StvnDefsCatalog catalog
    ) {
        if (innerIrValue instanceof StvnValue.StvnString strVal) {
            return isControlOrUnionTagToken(strVal.value());
        }
        if (innerIrValue instanceof StvnValue.StvnEnum enumVal) {
            String tag = enumVal.variantTag();
            return isControlOrUnionTagToken(tag)
                || catalog.findConstant(tag).isPresent()
                || tag.startsWith(PRELUDE_CONST_PREFIX);
        }
        return false;
    }

    /**
     * Verifies that a re-compiled {@link StvnDocument} preserves 100% Pure-IR payload and
     * constant value equivalence with {@code original}.
     *
     * @param original   compiled pre-print Pure-IR document
     * @param recompiled compiled post-print Pure-IR document, or {@code null}
     * @return {@code true} iff payload and all constant values match
     */
    public static boolean isDocumentsPayloadAndConstantsEquivalent(
        StvnDocument original,
        @Nullable StvnDocument recompiled
    ) {
        if (recompiled == null) {
            return false;
        }
        if (!Objects.equals(original.payload(), recompiled.payload())) {
            return false;
        }
        for (var entry : original.defs().constants().entrySet()) {
            var recompConst = recompiled.defs().constants().get(entry.getKey());
            if (recompConst != null && !Objects.equals(entry.getValue().value(), recompConst.value())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isControlOrUnionTagToken(String s) {
        if (StvnVocabulary.CONTROL_KEYWORDS.contains(s)) {
            return true;
        }
        if (s.length() >= 2 && s.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isUnionBranchTag(String tok) {
        if (tok.length() < 2 || !tok.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return false;
        }
        char firstDigit = tok.charAt(1);
        if (firstDigit < '1' || firstDigit > '9') {
            return false;
        }
        for (int i = 2; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSubsetFullySound(
        SlotProbe slot,
        Set<Integer> candidateSet,
        ProbeGroupKey groupKey,
        StvnDefsCatalog catalog,
        Map<ProbeCacheKey, Optional<StvnValue>> evalCache
    ) {
        if (!evaluatesCachedOrCompile(slot, candidateSet, groupKey, catalog, evalCache)) {
            return false;
        }
        List<Integer> asc = candidateSet.stream().sorted().toList();
        Set<Integer> prefix = new LinkedHashSet<>();
        for (int idx : asc) {
            prefix.add(idx);
            if (!evaluatesCachedOrCompile(slot, prefix, groupKey, catalog, evalCache)) {
                return false;
            }
        }
        Set<Integer> suffix = new LinkedHashSet<>();
        for (int k = asc.size() - 1; k >= 0; k--) {
            suffix.add(asc.get(k));
            if (!evaluatesCachedOrCompile(slot, suffix, groupKey, catalog, evalCache)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluatesCachedOrCompile(
        SlotProbe slot,
        Set<Integer> indicesToRemove,
        ProbeGroupKey groupKey,
        StvnDefsCatalog catalog,
        Map<ProbeCacheKey, Optional<StvnValue>> evalCache
    ) {
        String expr = applyTagRemovals(slot.slotText(), slot.tags(), indicesToRemove);
        if (expr.isEmpty()) {
            return false;
        }
        ProbeCacheKey cacheKey = new ProbeCacheKey(slot.vOrig().schema(), groupKey.constantSignature(), expr);
        if (!evalCache.containsKey(cacheKey)) {
            executeBatchProbes(
                List.of(new StagedProbe(groupKey, cacheKey, slot.constantBindings())),
                catalog,
                evalCache
            );
        }
        Optional<StvnValue> lowered = evalCache.get(cacheKey);
        return lowered != null && lowered.isPresent() && slot.vOrig().equals(lowered.get());
    }

    private static void executeBatchProbes(
        List<StagedProbe> probes,
        StvnDefsCatalog fullCatalog,
        Map<ProbeCacheKey, Optional<StvnValue>> outCache
    ) {
        if (probes.isEmpty()) {
            return;
        }
        // Partition by constant-binding signature so renamed #CONST names never collide across scopes
        Map<String, List<StagedProbe>> byConstScope = new LinkedHashMap<>();
        for (StagedProbe p : probes) {
            byConstScope.computeIfAbsent(p.groupKey().constantSignature(), ignored -> new ArrayList<>()).add(p);
        }
        for (List<StagedProbe> scopeBatch : byConstScope.values()) {
            try {
                executeScopedBatch(scopeBatch, fullCatalog, outCache);
            } catch (IOException ex) {
                for (StagedProbe p : scopeBatch) {
                    outCache.put(p.cacheKey(), Optional.empty());
                }
            }
        }
    }

    private static void executeScopedBatch(
        List<StagedProbe> probes,
        StvnDefsCatalog fullCatalog,
        Map<ProbeCacheKey, Optional<StvnValue>> outCache
    ) throws IOException {
        Map<String, StvnSchema> reachableTypes = new LinkedHashMap<>();
        Map<String, StvnDefsCatalog.StvnConstant> mergedConsts = new LinkedHashMap<>();
        Set<String> activeStack = new HashSet<>();
        Map<StvnSchema, List<StagedProbe>> schemaGroups = new LinkedHashMap<>();

        for (StagedProbe item : probes) {
            schemaGroups.computeIfAbsent(item.cacheKey().schema(), ignored -> new ArrayList<>()).add(item);
            collectReachableTypesPostOrder(item.cacheKey().schema(), fullCatalog, reachableTypes, activeStack);
            for (var c : item.constantBindings().entrySet()) {
                mergedConsts.putIfAbsent(c.getKey(), c.getValue());
                collectReachableTypesPostOrder(c.getValue().schema(), fullCatalog, reachableTypes, activeStack);
                collectReachableFromValue(c.getValue().value(), fullCatalog, reachableTypes, activeStack);
            }
        }

        StringWriter sw = new StringWriter(schemaGroups.size() * 128 + 128);
        CanonicalLayoutWriter layout = new CanonicalLayoutWriter(sw);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
        if (!reachableTypes.isEmpty() || !mergedConsts.isEmpty()) {
            layout.writeLiteral(StvnVocabulary.KEYWORD_DEFS);
            layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
            for (var t : reachableTypes.entrySet()) {
                layout.writeLiteral(t.getKey());
                CanonicalStvnWriter.writeSchemaDefinitionBody(t.getValue(), layout);
            }
            for (var c : mergedConsts.entrySet()) {
                CanonicalStvnWriter.writeConstantDefinition(c.getKey(), c.getValue(), layout);
            }
            layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
        }
        layout.writeLiteral(StvnVocabulary.KEYWORD_TYPE);
        layout.writeLiteral(StvnVocabulary.TYPE_TUPLE);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        for (StvnSchema s : schemaGroups.keySet()) {
            layout.writeLiteral(StvnVocabulary.TYPE_SEQ);
            layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
            layout.writeLiteral(StvnVocabulary.TYPE_TUPLE);
            layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
            if (s.aliasName().isPresent()) {
                layout.writeLiteral(s.aliasName().get());
            } else {
                CanonicalStvnWriter.writeSchemaDefinitionBody(s, layout);
            }
            layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
            layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
        layout.writeLiteral(StvnVocabulary.KEYWORD_BODY);
        layout.flush();

        StringBuilder fullDoc = new StringBuilder(sw.toString().length() + probes.size() * 32);
        fullDoc.append(sw).append(' ').append(StvnVocabulary.DELIM_OPEN_PAREN);
        for (List<StagedProbe> groupItems : schemaGroups.values()) {
            fullDoc.append(StvnVocabulary.DELIM_OPEN_BRACKET);
            for (StagedProbe item : groupItems) {
                fullDoc.append(StvnVocabulary.DELIM_OPEN_PAREN)
                    .append(item.cacheKey().candidateExpr())
                    .append('\n')
                    .append(StvnVocabulary.DELIM_CLOSE_PAREN);
            }
            fullDoc.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        fullDoc.append(StvnVocabulary.DELIM_CLOSE_PAREN).append(StvnVocabulary.DELIM_CLOSE_BRACE);

        StvnParserConfig batchConfig = new StvnParserConfig(false, Math.max(256, probes.size() * 4), true);
        StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(fullDoc.toString(), null, batchConfig);
        Optional<StvnValue> rootPayload = res.document().flatMap(StvnDocument::payload);
        if (rootPayload.isPresent()
            && rootPayload.get() instanceof StvnValue.StvnTuple outerTuple
            && outerTuple.elements().size() == schemaGroups.size()) {
            boolean aligned = true;
            int gIdx = 0;
            for (List<StagedProbe> groupItems : schemaGroups.values()) {
                StvnValue seqCell = outerTuple.elements().get(gIdx++);
                if (!(seqCell instanceof StvnValue.StvnSeq seqVal) || seqVal.elements().size() != groupItems.size()) {
                    aligned = false;
                    break;
                }
            }
            if (aligned) {
                gIdx = 0;
                for (List<StagedProbe> groupItems : schemaGroups.values()) {
                    StvnValue.StvnSeq seqVal = (StvnValue.StvnSeq) outerTuple.elements().get(gIdx++);
                    for (int i = 0; i < groupItems.size(); i++) {
                        StvnValue cell = seqVal.elements().get(i);
                        if (cell instanceof StvnValue.StvnTuple innerTuple
                            && innerTuple.elements().size() == 1
                            && !containsErrorValue(innerTuple.elements().getFirst())) {
                            outCache.put(groupItems.get(i).cacheKey(), Optional.of(innerTuple.elements().getFirst()));
                        } else {
                            outCache.put(groupItems.get(i).cacheKey(), Optional.empty());
                        }
                    }
                }
                return;
            }
        }

        // Per-item isolated fallback when a candidate contains unbalanced delimiters or syntax errors
        StvnParserConfig singleConfig = new StvnParserConfig(false, 64, true);
        for (StagedProbe item : probes) {
            String singleDoc = buildSingleSlotDoc(item, fullCatalog);
            StvnCompilationResult<StvnDocument> sRes = StvnCompiler.compile(singleDoc, null, singleConfig);
            if (!sRes.hasErrors()
                && sRes.document().isPresent()
                && sRes.document().get().payload().isPresent()
                && !containsErrorValue(sRes.document().get().payload().get())) {
                outCache.put(item.cacheKey(), sRes.document().get().payload());
            } else {
                outCache.put(item.cacheKey(), Optional.empty());
            }
        }
    }

    private static String buildSingleSlotDoc(StagedProbe item, StvnDefsCatalog fullCatalog) throws IOException {
        Map<String, StvnSchema> reachableTypes = new LinkedHashMap<>();
        Set<String> activeStack = new HashSet<>();
        collectReachableTypesPostOrder(item.cacheKey().schema(), fullCatalog, reachableTypes, activeStack);
        for (var c : item.constantBindings().values()) {
            collectReachableTypesPostOrder(c.schema(), fullCatalog, reachableTypes, activeStack);
            collectReachableFromValue(c.value(), fullCatalog, reachableTypes, activeStack);
        }

        StringWriter sw = new StringWriter(256);
        CanonicalLayoutWriter layout = new CanonicalLayoutWriter(sw);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
        if (!reachableTypes.isEmpty() || !item.constantBindings().isEmpty()) {
            layout.writeLiteral(StvnVocabulary.KEYWORD_DEFS);
            layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
            for (var t : reachableTypes.entrySet()) {
                layout.writeLiteral(t.getKey());
                CanonicalStvnWriter.writeSchemaDefinitionBody(t.getValue(), layout);
            }
            for (var c : item.constantBindings().entrySet()) {
                CanonicalStvnWriter.writeConstantDefinition(c.getKey(), c.getValue(), layout);
            }
            layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
        }
        layout.writeLiteral(StvnVocabulary.KEYWORD_TYPE);
        StvnSchema s = item.cacheKey().schema();
        if (s.aliasName().isPresent()) {
            layout.writeLiteral(s.aliasName().get());
        } else {
            CanonicalStvnWriter.writeSchemaDefinitionBody(s, layout);
        }
        layout.writeLiteral(StvnVocabulary.KEYWORD_BODY);
        layout.flush();
        return sw + " " + item.cacheKey().candidateExpr() + "\n" + StvnVocabulary.DELIM_CLOSE_BRACE;
    }

    private static boolean isNonInferableLeftTag(String tagText) {
        return StvnVocabulary.VAL_LEFT.equals(tagText) || StvnVocabulary.VAL_LEFT_SHORT.equals(tagText);
    }

    private static boolean isSumValue(StvnValue v) {
        return v instanceof StvnValue.StvnOption
            || v instanceof StvnValue.StvnEither
            || v instanceof StvnValue.StvnUnion;
    }

    private static @Nullable StvnValue matchExplicitUnaryStep(String tok, StvnValue currIr) {
        StvnValue cursor = currIr;
        while (isSumValue(cursor)) {
            if ((StvnVocabulary.VAL_SOME.equals(tok) || StvnVocabulary.VAL_SOME_SHORT.equals(tok))
                && cursor instanceof StvnValue.StvnOption opt && opt.value().isPresent()) {
                return opt.value().get();
            }
            if ((StvnVocabulary.VAL_LEFT.equals(tok) || StvnVocabulary.VAL_LEFT_SHORT.equals(tok))
                && cursor instanceof StvnValue.StvnEither eith && eith.branch() == StvnValue.EitherBranch.LEFT) {
                return eith.value();
            }
            if ((StvnVocabulary.VAL_RIGHT.equals(tok) || StvnVocabulary.VAL_RIGHT_SHORT.equals(tok))
                && cursor instanceof StvnValue.StvnEither eith && eith.branch() == StvnValue.EitherBranch.RIGHT) {
                return eith.value();
            }
            if (isUnionBranchTag(tok)
                && cursor instanceof StvnValue.StvnUnion un
                && (StvnVocabulary.SIGIL_VALUE + un.oneBasedBranchIndex()).equals(tok)) {
                return un.value();
            }
            cursor = switch (cursor) {
                case StvnValue.StvnOption o -> o.value().orElse(null);
                case StvnValue.StvnEither e -> e.value();
                case StvnValue.StvnUnion u -> u.value();
                default -> null;
            };
            if (cursor == null) {
                break;
            }
        }
        return null;
    }

    private static String computeConstantSignature(Map<String, StvnDefsCatalog.StvnConstant> bindings) {
        if (bindings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        bindings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue().fqni()).append(';'));
        return sb.toString();
    }

    private static boolean containsRealPoisonedSchemaRecursive(StvnSchema schema, Set<String> seenAliases) {
        if (schema instanceof StvnSchema.PoisonedSchema p) {
            return !RECURSIVE_SENTINEL_CODE.equals(p.primaryDiagnosticCode());
        }
        if (schema.aliasName().isPresent() && !seenAliases.add(schema.aliasName().get())) {
            return false;
        }
        return switch (schema) {
            case StvnSchema.OptionSchema o -> containsRealPoisonedSchemaRecursive(o.valueSchema(), seenAliases);
            case StvnSchema.EitherSchema e -> containsRealPoisonedSchemaRecursive(e.leftSchema(), seenAliases)
                || containsRealPoisonedSchemaRecursive(e.rightSchema(), seenAliases);
            case StvnSchema.UnionSchema u -> u.branchSchemas().stream()
                .anyMatch(b -> containsRealPoisonedSchemaRecursive(b, seenAliases));
            case StvnSchema.TupleSchema t -> t.fieldSchemas().stream()
                .anyMatch(f -> containsRealPoisonedSchemaRecursive(f, seenAliases));
            case StvnSchema.SeqSchema s -> containsRealPoisonedSchemaRecursive(s.elementSchema(), seenAliases);
            case StvnSchema.SetSchema s -> containsRealPoisonedSchemaRecursive(s.elementSchema(), seenAliases);
            case StvnSchema.MapSchema m -> containsRealPoisonedSchemaRecursive(m.keySchema(), seenAliases)
                || containsRealPoisonedSchemaRecursive(m.valueSchema(), seenAliases);
            default -> false;
        };
    }

    private static void collectReachableFromValue(
        StvnValue value,
        StvnDefsCatalog catalog,
        Map<String, StvnSchema> orderedOut,
        Set<String> activeStack
    ) {
        collectReachableTypesPostOrder(value.schema(), catalog, orderedOut, activeStack);
        switch (value) {
            case StvnValue.StvnTuple tup ->
                tup.elements().forEach(c -> collectReachableFromValue(c, catalog, orderedOut, activeStack));
            case StvnValue.StvnOption opt ->
                opt.value().ifPresent(c -> collectReachableFromValue(c, catalog, orderedOut, activeStack));
            case StvnValue.StvnEither eith -> collectReachableFromValue(eith.value(), catalog, orderedOut, activeStack);
            case StvnValue.StvnUnion un -> collectReachableFromValue(un.value(), catalog, orderedOut, activeStack);
            case StvnValue.StvnSeq seq ->
                seq.elements().forEach(c -> collectReachableFromValue(c, catalog, orderedOut, activeStack));
            case StvnValue.StvnSet set ->
                set.elements().forEach(c -> collectReachableFromValue(c, catalog, orderedOut, activeStack));
            case StvnValue.StvnMap map -> map.entries().forEach(e -> {
                collectReachableFromValue(e.key(), catalog, orderedOut, activeStack);
                collectReachableFromValue(e.value(), catalog, orderedOut, activeStack);
            });
            default -> {
            }
        }
    }

    private static void collectReachableTypesPostOrder(
        StvnSchema schema,
        StvnDefsCatalog catalog,
        Map<String, StvnSchema> orderedOut,
        Set<String> activeStack
    ) {
        if (schema instanceof StvnSchema.PoisonedSchema p) {
            if (p.aliasName().isPresent()) {
                String target = p.aliasName().get();
                if (!target.startsWith(PRELUDE_TYPE_PREFIX)
                    && !orderedOut.containsKey(target)
                    && !activeStack.contains(target)) {
                    catalog.findType(target).ifPresent(res -> visitType(target, res, catalog, orderedOut, activeStack));
                }
            }
            p.bestEffortFallback().ifPresent(fb -> collectReachableTypesPostOrder(fb, catalog, orderedOut, activeStack));
            return;
        }
        if (schema.aliasName().isPresent()) {
            String fqni = schema.aliasName().get();
            if (fqni.startsWith(PRELUDE_TYPE_PREFIX) || orderedOut.containsKey(fqni) || activeStack.contains(fqni)) {
                return;
            }
            StvnSchema targetSchema = catalog.findType(fqni).orElse(schema);
            visitType(fqni, targetSchema, catalog, orderedOut, activeStack);
            return;
        }
        traverseChildSchemas(schema, catalog, orderedOut, activeStack);
    }

    private static void visitType(
        String fqni,
        StvnSchema schema,
        StvnDefsCatalog catalog,
        Map<String, StvnSchema> orderedOut,
        Set<String> activeStack
    ) {
        if (orderedOut.containsKey(fqni) || !activeStack.add(fqni)) {
            return;
        }
        try {
            traverseChildSchemas(schema, catalog, orderedOut, activeStack);
            if (!fqni.startsWith(PRELUDE_TYPE_PREFIX)) {
                orderedOut.put(fqni, schema);
            }
        } finally {
            activeStack.remove(fqni);
        }
    }

    private static void traverseChildSchemas(
        StvnSchema schema,
        StvnDefsCatalog catalog,
        Map<String, StvnSchema> orderedOut,
        Set<String> activeStack
    ) {
        switch (schema) {
            case StvnSchema.EnumSubsetSchema sub -> {
                collectReachableTypesPostOrder(sub.parentEnum(), catalog, orderedOut, activeStack);
                collectReachableTypesPostOrder(sub.rootEnum(), catalog, orderedOut, activeStack);
            }
            case StvnSchema.TupleSchema tup ->
                tup.fieldSchemas().forEach(c -> collectReachableTypesPostOrder(c, catalog, orderedOut, activeStack));
            case StvnSchema.OptionSchema opt ->
                collectReachableTypesPostOrder(opt.valueSchema(), catalog, orderedOut, activeStack);
            case StvnSchema.EitherSchema eith -> {
                collectReachableTypesPostOrder(eith.leftSchema(), catalog, orderedOut, activeStack);
                collectReachableTypesPostOrder(eith.rightSchema(), catalog, orderedOut, activeStack);
            }
            case StvnSchema.UnionSchema un ->
                un.branchSchemas().forEach(c -> collectReachableTypesPostOrder(c, catalog, orderedOut, activeStack));
            case StvnSchema.SeqSchema seq ->
                collectReachableTypesPostOrder(seq.elementSchema(), catalog, orderedOut, activeStack);
            case StvnSchema.SetSchema set ->
                collectReachableTypesPostOrder(set.elementSchema(), catalog, orderedOut, activeStack);
            case StvnSchema.MapSchema map -> {
                collectReachableTypesPostOrder(map.keySchema(), catalog, orderedOut, activeStack);
                collectReachableTypesPostOrder(map.valueSchema(), catalog, orderedOut, activeStack);
            }
            default -> {
            }
        }
    }
}
