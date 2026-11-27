package org.stvnadore.plugin.validation;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Validates STVN {@code :Set} capacity governance invariants ({@code STVN | Governance | Capacity}).
 *
 * @since 2.0.0
 */
@NullMarked
public class StvnSetCapacityInspection extends AbstractStvnCapacityInspection {

    /**
     * Configured capacity threshold in elements (default: 4,096).
     */
    public int maxCapacityThreshold = DEFAULT_INSPECTION_THRESHOLD;

    /**
     * Toggle enabling set literal element count verification in {@code :body} payloads and {@code #CONST} values.
     */
    public boolean inspectBodyAndConstantLiterals = false;

    /**
     * Explicit severity override used during automated tests.
     */
    public String configuredSeverity = "WARNING";

    /**
     * Constructs a new {@code StvnSetCapacityInspection} instance.
     */
    public StvnSetCapacityInspection() {
        super(CapacityTargetKind.SET);
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnSetCapacity";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Set capacity governance inspection";
    }

    @Override
    protected int getConfiguredThreshold() {
        return maxCapacityThreshold;
    }

    @Override
    protected boolean isInspectLiteralsEnabled() {
        return inspectBodyAndConstantLiterals;
    }

    @Override
    protected String getConfiguredSeverityOverride() {
        return configuredSeverity;
    }

    @Override
    protected String getThresholdFieldName() {
        return "maxCapacityThreshold";
    }

    @Override
    protected String getInspectLiteralsFieldName() {
        return "inspectBodyAndConstantLiterals";
    }

    @Override
    protected String getThresholdOptionLabel() {
        return "Capacity threshold (elements):";
    }

    @Override
    protected String getInspectLiteralsOptionLabel() {
        return "Inspect set literals in body payloads and constants";
    }
}
