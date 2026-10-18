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
     * Constructs a new StvnSettings instance.
     */
    public StvnSettings() {
    }

    /**
     * Multiline string enter-key auto-closing delimiter placement options.
     */
    public enum BlockStringEnterStyle {
        /** Inserts newline, indented body line, and closing delimiter on line 3. */
        EXPANDED_THREE_LINE,
        /** Inserts newline, indented body offset, and closing delimiter on line 2. */
        TIGHT_TWO_LINE
    }

    /**
     * Serializable persistent state properties.
     */
    public static final class State {
        public static final String DEFAULT_TEMPLATE_STVN = "{\n  :type :String\n  :body \"\"\n}\n";
        public static final String DEFAULT_TEMPLATE_STVN_I = "{\n  :meta {\n    #name \"payload\"\n    #kind #BODY_INCLUDE\n  }\n  :type :String\n  :body \"\"\n}\n";
        public static final String DEFAULT_TEMPLATE_STVN_D = "{\n  :meta {\n    #name \"schema\"\n    #kind #DEFS\n  }\n  :defs {\n    :Type :String\n  }\n}\n";
        public static final String DEFAULT_TEMPLATE_STVN_DI = "{\n  :meta {\n    #name \"schema\"\n    #kind #DEFS_INCLUDE\n  }\n  :defs {\n    :Type :String\n  }\n}\n";

        /**
         * Constructs a new State instance with default editor settings.
         */
        public State() {
        }
        /** If true, uses long-form sum type formatting. */
        public boolean useLongFormSumTypes = true;
        /** Multiline string auto-closing delimiter shape. */
        public BlockStringEnterStyle blockStringEnterStyle = BlockStringEnterStyle.EXPANDED_THREE_LINE;
        /** Canonical bootstrap template for modular payload documents (.stvn). */
        public String bootstrapTemplateStvn = DEFAULT_TEMPLATE_STVN;
        /** Canonical bootstrap template for isolated payload documents (.stvn_i). */
        public String bootstrapTemplateStvnI = DEFAULT_TEMPLATE_STVN_I;
        /** Canonical bootstrap template for modular definitions schema documents (.stvn_d). */
        public String bootstrapTemplateStvnD = DEFAULT_TEMPLATE_STVN_D;
        /** Canonical bootstrap template for isolated definitions schema documents (.stvn_di). */
        public String bootstrapTemplateStvnDi = DEFAULT_TEMPLATE_STVN_DI;
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