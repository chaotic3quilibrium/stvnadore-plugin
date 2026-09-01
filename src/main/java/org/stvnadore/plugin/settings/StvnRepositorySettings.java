package org.stvnadore.plugin.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Project-level persistent settings for remote STVN Schema Repository connections.
 */
@NullMarked
@State(
    name = "StvnRepositorySettings",
    storages = @Storage("stvn_repository.xml")
)
public final class StvnRepositorySettings implements PersistentStateComponent<StvnRepositorySettings.State> {

    /**
     * Serializable persistent state properties.
     */
    public static final class State {
        /** Remote repository base URL. */
        public String repoUrl = "http://localhost:8080";
        /** HTTP socket and connection timeout in milliseconds. */
        public int timeoutMs = 5000;
    }

    private final Object lock = new Object();
    private State myState = new State();

    /**
     * Returns the project-level StvnRepositorySettings service instance.
     *
     * @param project the active IntelliJ project
     * @return settings component instance
     */
    public static StvnRepositorySettings getInstance(Project project) {
        var settings = project.getService(StvnRepositorySettings.class);
        if (settings == null) {
            throw new IllegalStateException("StvnRepositorySettings service is not available for project: " + project.getName());
        }
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

    /**
     * Returns the configured repository base URL.
     *
     * @return base URL string
     */
    public String getRepoUrl() {
        synchronized (lock) {
            return myState.repoUrl;
        }
    }

    /**
     * Sets the configured repository base URL.
     *
     * @param repoUrl base URL string
     */
    public void setRepoUrl(String repoUrl) {
        synchronized (lock) {
            myState.repoUrl = repoUrl;
        }
    }

    /**
     * Returns the HTTP timeout in milliseconds.
     *
     * @return timeout duration in milliseconds
     */
    public int getTimeoutMs() {
        synchronized (lock) {
            return myState.timeoutMs;
        }
    }

    /**
     * Sets the HTTP timeout in milliseconds.
     *
     * @param timeoutMs timeout duration in milliseconds
     */
    public void setTimeoutMs(int timeoutMs) {
        synchronized (lock) {
            myState.timeoutMs = timeoutMs;
        }
    }
}