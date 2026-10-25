package org.stvnadore.plugin.inspection;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.validation.StvnStringCapacityInspection;

/**
 * Inspection enforcing nominal type capacity governance with active syntax error resilience.
 * <p>
 * Aborts execution when metadata maps contain active syntax errors ({@code PsiErrorElement}),
 * and performs fallback lexical scans to suppress false-positive warnings during in-flight authoring.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public class StvnMissingNominalCapacityInspection extends StvnStringCapacityInspection {

    /**
     * Constructs a new StvnMissingNominalCapacityInspection instance.
     */
    public StvnMissingNominalCapacityInspection() {
        super();
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnMissingNominalCapacity";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Missing nominal capacity governance inspection";
    }
}
