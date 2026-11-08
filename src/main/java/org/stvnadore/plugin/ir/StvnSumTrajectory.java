package org.stvnadore.plugin.ir;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs explicit versus Happy-Path inferred sum-type steps via a deterministic
 * dual-cursor {@code (PSI Value, Pure-IR StvnValue)} walk.
 *
 * @param steps                      ordered list of explicit and inferred sum-constructor steps
 * @param terminalPsiValue           PSI value node reached after unwrapping explicit sum wrappers
 * @param terminalIrValue            pure-IR value node reached at the end of sum traversal
 * @param stoppedAtConstantReference {@code true} when traversal halted at a {@code #CONST} reference token
 */
@NullMarked
public record StvnSumTrajectory(
    List<Step> steps,
    @Nullable Value terminalPsiValue,
    StvnValue terminalIrValue,
    boolean stoppedAtConstantReference
) {
    /**
     * Single sum-constructor traversal step.
     *
     * @param inferred  {@code true} when inferred via Happy-Path Rules A-C; {@code false} when written explicitly
     * @param shortTag  canonical short tag ({@code #S}, {@code #N}, {@code #L}, {@code #R}, or {@code #1}, {@code #2}, ...)
     * @param sumSchema pure-IR schema of the enclosing {@code :Option}, {@code :Either}, or {@code :Union}
     */
    public record Step(boolean inferred, String shortTag, StvnSchema sumSchema) {
    }

    /**
     * Evaluates the sum-type trajectory for {@code (psiValue, irValue)}.
     *
     * @param psiValue PSI value node at the current cursor
     * @param irValue  aligned pure-IR value node
     * @param catalog  pure-IR definitions catalog for constant-barrier detection
     * @return reconstructed sum trajectory
     */
    public static StvnSumTrajectory evaluate(
        @Nullable Value psiValue,
        StvnValue irValue,
        StvnDefsCatalog catalog
    ) {
        List<Step> steps = new ArrayList<>();
        Value currPsi = psiValue;
        StvnValue currIr = irValue;
        boolean hitConst = false;

        while (isSumValue(currIr)) {
            if (currPsi != null && isConstantReferenceToken(currPsi, currIr, catalog)) {
                hitConst = true;
                break;
            }
            if (currIr instanceof StvnValue.StvnOption opt) {
                var explicitOpt = currPsi != null ? currPsi.getExplicitOptionValue() : null;
                if (explicitOpt != null) {
                    String tag = opt.value().isPresent() ? StvnVocabulary.VAL_SOME_SHORT : StvnVocabulary.VAL_NONE_SHORT;
                    steps.add(new Step(false, tag, opt.schema()));
                    if (opt.value().isEmpty()) {
                        break;
                    }
                    currPsi = explicitOpt.getValue();
                    currIr = opt.value().get();
                } else if (opt.value().isPresent()) {
                    steps.add(new Step(true, StvnVocabulary.VAL_SOME_SHORT, opt.schema()));
                    currIr = opt.value().get();
                } else {
                    steps.add(new Step(false, StvnVocabulary.VAL_NONE_SHORT, opt.schema()));
                    break;
                }
            } else if (currIr instanceof StvnValue.StvnEither either) {
                String tag = either.branch() == StvnValue.EitherBranch.LEFT
                    ? StvnVocabulary.VAL_LEFT_SHORT
                    : StvnVocabulary.VAL_RIGHT_SHORT;
                var explicitEither = currPsi != null ? currPsi.getExplicitEitherValue() : null;
                if (explicitEither != null) {
                    steps.add(new Step(false, tag, either.schema()));
                    currPsi = explicitEither.getValue();
                    currIr = either.value();
                } else {
                    steps.add(new Step(true, tag, either.schema()));
                    currIr = either.value();
                }
            } else if (currIr instanceof StvnValue.StvnUnion union) {
                String tag = StvnVocabulary.SIGIL_VALUE + union.oneBasedBranchIndex();
                var explicitUnion = currPsi != null ? currPsi.getExplicitUnionValue() : null;
                if (explicitUnion != null) {
                    steps.add(new Step(false, tag, union.schema()));
                    currPsi = explicitUnion.getValue();
                    currIr = union.value();
                } else {
                    steps.add(new Step(true, tag, union.schema()));
                    currIr = union.value();
                }
            }
        }
        return new StvnSumTrajectory(List.copyOf(steps), currPsi, currIr, hitConst);
    }

    /**
     * Checks whether {@code value} is an {@code :Option}, {@code :Either}, or {@code :Union} IR node.
     *
     * @param value pure-IR value candidate
     * @return {@code true} if {@code value} is a sum wrapper value
     */
    public static boolean isSumValue(@Nullable StvnValue value) {
        return value instanceof StvnValue.StvnOption
            || value instanceof StvnValue.StvnEither
            || value instanceof StvnValue.StvnUnion;
    }

    /**
     * Unwraps all outer {@code :Option}, {@code :Either}, and {@code :Union} sum layers to the inner payload value.
     *
     * @param value starting pure-IR value
     * @return terminal non-sum value (or {@code #None} {@link StvnValue.StvnOption} when empty)
     */
    public static StvnValue unwrapTerminalIrValue(StvnValue value) {
        StvnValue curr = value;
        while (isSumValue(curr)) {
            if (curr instanceof StvnValue.StvnOption opt) {
                if (opt.value().isEmpty()) {
                    return opt;
                }
                curr = opt.value().get();
            } else if (curr instanceof StvnValue.StvnEither either) {
                curr = either.value();
            } else if (curr instanceof StvnValue.StvnUnion union) {
                curr = union.value();
            }
        }
        return curr;
    }

    private static boolean isConstantReferenceToken(
        Value psiValue,
        StvnValue irValue,
        StvnDefsCatalog catalog
    ) {
        var vk = psiValue.getValueKeyword();
        if (vk == null) {
            return false;
        }
        String text = vk.getText().trim();
        if (!text.startsWith(StvnVocabulary.SIGIL_VALUE) || text.length() <= 1) {
            return false;
        }
        if (StvnVocabulary.VAL_NONE.equals(text) || StvnVocabulary.VAL_NONE_SHORT.equals(text)
            || StvnVocabulary.VAL_TRUE.equals(text) || StvnVocabulary.VAL_TRUE_SHORT.equals(text)
            || StvnVocabulary.VAL_FALSE.equals(text) || StvnVocabulary.VAL_FALSE_SHORT.equals(text)) {
            return false;
        }
        StvnValue leaf = unwrapTerminalIrValue(irValue);
        if (leaf instanceof StvnValue.StvnEnum enumVal && enumVal.variantTag().equals(text)) {
            return false;
        }
        StvnDefsCatalog.StvnConstant matched = findCatalogConstant(text, catalog);
        if (matched == null) {
            matched = findCatalogConstant(text, StvnPrelude.preludeCatalog());
        }
        if (matched == null) {
            return false;
        }
        return matched.value().equals(irValue) || matched.schema().equals(irValue.schema());
    }

    private static StvnDefsCatalog.@Nullable StvnConstant findCatalogConstant(
        String tokenText,
        StvnDefsCatalog catalog
    ) {
        StvnDefsCatalog.StvnConstant direct = catalog.constants().get(tokenText);
        if (direct != null) {
            return direct;
        }
        String bareIdentifier = tokenText.substring(1);
        for (var entry : catalog.constants().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("/" + bareIdentifier) || key.endsWith("/#" + bareIdentifier)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
