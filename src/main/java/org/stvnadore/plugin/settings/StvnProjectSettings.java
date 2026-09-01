package org.stvnadore.plugin.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Project-level persistent settings for STVN editor inspections, hints, and documentation.
 */
@NullMarked
@State(
    name = "StvnProjectSettings",
    storages = @Storage("stvn_settings.xml")
)
public final class StvnProjectSettings implements PersistentStateComponent<StvnProjectSettings.State> {

    /**
     * Serializable persistent state properties.
     */
    public static class State {
        /** If true, renders inlay type hints in editor. */
        public boolean showTypeHints = true;
        /** If true, renders documentation on hover. */
        public boolean showHoverDocs = true;
        /** If true, inspects and warns on redundant tags. */
        public boolean enableRedundantTagInspection = true;
        /** If true, warns on type form discrepancies. */
        public boolean enableFormDiscrepancyInspection = true;
        /** If true, favors implied sum types during completion. */
        public boolean preferImpliedSumTypes = true;
    }

    private State myState = new State();

    /**
     * Returns the project-level StvnProjectSettings service instance.
     *
     * @param project the active IntelliJ project
     * @return settings component instance
     */
    public static StvnProjectSettings getInstance(Project project) {
        var settings = project.getService(StvnProjectSettings.class);
        return settings;
    }

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        myState = state;
    }
}