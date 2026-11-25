package org.stvnadore.plugin;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.PropertyKey;
import org.jspecify.annotations.NullMarked;

/**
 * Resource bundle accessor for localized STVN plugin UI titles, labels, tooltips, and menus.
 */
@NullMarked
public final class StvnBundle extends DynamicBundle {

    /** Qualified resource bundle base name. */
    @NonNls
    public static final String BUNDLE = "messages.StvnBundle";

    private static final StvnBundle INSTANCE = new StvnBundle();

    private StvnBundle() {
        super(BUNDLE);
    }

    /**
     * Resolves a formatted message string from {@code messages/StvnBundle.properties}.
     *
     * @param key    property key in {@code StvnBundle.properties}
     * @param params optional format arguments
     * @return formatted UI message
     */
    public static @Nls String message(
        @PropertyKey(resourceBundle = BUNDLE) String key,
        Object... params
    ) {
        return INSTANCE.getMessage(key, params);
    }
}
