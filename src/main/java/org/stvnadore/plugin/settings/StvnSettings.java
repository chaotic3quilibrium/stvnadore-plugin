package org.stvnadore.plugin.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Project-level persistent settings for STVN code style and syntax formatting.
 */
@NullMarked
@State(
    name = "StvnSettings",
    storages = @Storage("stvnadore.xml")
)
public final class StvnSettings implements PersistentStateComponent<StvnSettings.State> {

    /**
     * Serializable persistent state properties.
     */
    public static final class State {
        /** If true, uses long-form sum type formatting. */
        public boolean useLongFormSumTypes = true;
    }

    private final Object lock = new Object();
    private State myState = new State();

    /**
     * Returns the project-level StvnSettings service instance.
     *
     * @param project the active IntelliJ project
     * @return settings component instance
     */
    public static StvnSettings getInstance(Project project) {
        var settings = project.getService(StvnSettings.class);
        return settings;
    }

    @Override
    public State getState() {
        synchronized (lock) {
            return myState;
        }
    }

    @Override
    public void loadState(State state) {
        synchronized (lock) {
            myState = state;
        }
    }
}