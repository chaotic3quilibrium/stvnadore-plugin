package org.stvnadore.plugin.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.core.printer.StvnSourceFormatterOptions;

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
     * Hour presentation format for TimeEpoch hover documentation cards.
     */
    public enum TimeEpochHourFormat {
        /** 12-hour format with AM/PM indicator (e.g., 7:30:00 PM UTC). */
        HOUR_12,
        /** 24-hour format (e.g., 19:30:00 UTC). */
        HOUR_24
    }

    /**
     * Formatting engine delegation mode for Reformat Code ({@code Ctrl+Alt+L}).
     *
     * @since 2.0.0
     */
    public enum ReformatDelegationMode {
        /**
         * Delegates whole-file formatting to {@code StvnSourceFormatter} (Operation 1 non-destructive
         * CST and hidden-channel comment formatter in {@code ij_stvnadore_core}), falling back to the
         * native IntelliJ PSI formatter on partial selections or Pass 1 syntax errors.
         */
        CORE_SOURCE_FORMATTER,
        /**
         * Uses the native IntelliJ PSI formatter, preserving {@code //} comments,
         * {@code :package}/{@code :use} authoring sugar, and broken in-flight syntax.
         */
        PSI_STRUCTURE_PRESERVING,
        /**
         * Delegates whole-file formatting to {@code AstPrettyPrinter} when the document compiles
         * cleanly without comments or authoring sugar that canonicalization would discard, or to
         * {@code StvnSourceFormatter} when authoring constructs are present.
         */
        CORE_PRETTY_WHEN_LOSSLESS,
        /**
         * Delegates whole-file formatting to {@code AstPrettyPrinter} whenever the document compiles cleanly.
         */
        CORE_PRETTY_CANONICAL
    }

    /**
     * Serializable persistent state properties.
     */
    public static final class State {
        public static final String DEFAULT_TEMPLATE_STVN = "{\n  :meta {\n    #name \"payload\"\n    #domain \"domain\"\n    #kind #BODY\n  }\n  :defs {\n    :Type :String\n  }\n  :type :Type\n  :body \"\"\n}\n";
        public static final String DEFAULT_TEMPLATE_STVN_I = "{\n  :meta {\n    #name \"payload\"\n    #domain \"domain\"\n    #kind #BODY_INCLUDE\n  }\n  :defs {\n    :include [ \"models.domain.stvn_di\" ]\n\n    :Type :String\n  }\n  :type :Type\n  :body \"\"\n}\n";
        public static final String DEFAULT_TEMPLATE_STVN_D = "{\n  :meta {\n    #name \"schema\"\n    #domain \"domain\"\n    #kind #DEFS\n  }\n  :defs {\n    :Type :String\n  }\n}\n";
        public static final String DEFAULT_TEMPLATE_STVN_DI = "{\n  :meta {\n    #name \"module\"\n    #domain \"domain\"\n    #kind #DEFS_INCLUDE\n  }\n  :defs {\n    :include [ \"primitives.common.stvn_d\" ]\n\n    :Type :String\n  }\n}\n";
        public static final int DEFAULT_BROWSER_PRESERVED_SUFFIX_CHARS = 12;
        public static final int MIN_BROWSER_PRESERVED_SUFFIX_CHARS = 4;
        public static final int MAX_BROWSER_PRESERVED_SUFFIX_CHARS = 32;

        /**
         * Constructs a new State instance with default editor settings.
         */
        public State() {
        }
        /** If true, uses long-form sum type formatting. */
        public boolean useLongFormSumTypes = true;
        /** Multiline string auto-closing delimiter shape. */
        public BlockStringEnterStyle blockStringEnterStyle = BlockStringEnterStyle.EXPANDED_THREE_LINE;
        /** Hour presentation format for TimeEpoch hover documentation cards. */
        public TimeEpochHourFormat timeEpochHourFormat = TimeEpochHourFormat.HOUR_12;
        /** If true, displays warning banner on suspicious epoch magnitude in hover cards. */
        public boolean showEpochMagnitudeWarnings = true;
        /** Engine delegation mode for Reformat Code (Ctrl+Alt+L). */
        public ReformatDelegationMode reformatDelegationMode = ReformatDelegationMode.CORE_SOURCE_FORMATTER;
        /** Nested container wrapping policy for schema definitions and root type sections. */
        public NestedContainerWrapPolicy schemaNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
        /** Nested container wrapping policy for body payloads and constant values. */
        public NestedContainerWrapPolicy payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
        /** Cell ellipsis placement mode in the Namespace Dependencies Browser. */
        public NamespaceEllipsisMode browserEllipsisMode = NamespaceEllipsisMode.MIDDLE;
        /** Trailing suffix code-point count preserved under {@link NamespaceEllipsisMode#MIDDLE}. */
        public int browserPreservedSuffixChars = DEFAULT_BROWSER_PRESERVED_SUFFIX_CHARS;
        /** Canonical bootstrap template for hermetic payload documents ({@code .stvn}). */
        public String bootstrapTemplateStvn = DEFAULT_TEMPLATE_STVN;
        /** Canonical bootstrap template for modular payload documents ({@code .stvn_i}). */
        public String bootstrapTemplateStvnI = DEFAULT_TEMPLATE_STVN_I;
        /** Canonical bootstrap template for hermetic definitions schema documents ({@code .stvn_d}). */
        public String bootstrapTemplateStvnD = DEFAULT_TEMPLATE_STVN_D;
        /** Canonical bootstrap template for modular definitions schema modules ({@code .stvn_di}). */
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
        if (state.reformatDelegationMode == null) {
            state.reformatDelegationMode = ReformatDelegationMode.CORE_SOURCE_FORMATTER;
        }
        if (state.schemaNestedWrapPolicy == null) {
            state.schemaNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
        }
        if (state.payloadNestedWrapPolicy == null) {
            state.payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
        }
        if (state.browserEllipsisMode == null) {
            state.browserEllipsisMode = NamespaceEllipsisMode.MIDDLE;
        }
        state.browserPreservedSuffixChars = Math.clamp(
            state.browserPreservedSuffixChars,
            State.MIN_BROWSER_PRESERVED_SUFFIX_CHARS,
            State.MAX_BROWSER_PRESERVED_SUFFIX_CHARS
        );
        synchronized (lock) {
            myState = state;
        }
    }

    /**
     * Returns the active non-null {@link NamespaceEllipsisMode} for the Namespace Dependencies Browser.
     *
     * @return configured ellipsis mode, defaulting to {@link NamespaceEllipsisMode#MIDDLE}
     */
    public NamespaceEllipsisMode getBrowserEllipsisMode() {
        synchronized (lock) {
            return myState.browserEllipsisMode != null
                ? myState.browserEllipsisMode
                : NamespaceEllipsisMode.MIDDLE;
        }
    }

    /**
     * Returns the clamped preserved suffix length in Unicode code points ({@code [4, 32]})
     * for {@link NamespaceEllipsisMode#MIDDLE}.
     *
     * @return preserved suffix length clamped to {@code [4, 32]}
     */
    public int getBrowserPreservedSuffixChars() {
        synchronized (lock) {
            return Math.clamp(
                myState.browserPreservedSuffixChars,
                State.MIN_BROWSER_PRESERVED_SUFFIX_CHARS,
                State.MAX_BROWSER_PRESERVED_SUFFIX_CHARS
            );
        }
    }

    /**
     * Converts the persisted schema and payload wrap policies into Core formatter options.
     *
     * @return non-null {@link StvnSourceFormatterOptions} instance
     */
    public StvnSourceFormatterOptions toCoreFormatterOptions() {
        State snapshot;
        synchronized (lock) {
            snapshot = myState;
        }
        NestedContainerWrapPolicy schemaPolicy = snapshot.schemaNestedWrapPolicy != null
            ? snapshot.schemaNestedWrapPolicy
            : NestedContainerWrapPolicy.PRESERVE_AUTHORED;
        NestedContainerWrapPolicy payloadPolicy = snapshot.payloadNestedWrapPolicy != null
            ? snapshot.payloadNestedWrapPolicy
            : NestedContainerWrapPolicy.PRESERVE_AUTHORED;
        return new StvnSourceFormatterOptions(schemaPolicy, payloadPolicy);
    }
}
