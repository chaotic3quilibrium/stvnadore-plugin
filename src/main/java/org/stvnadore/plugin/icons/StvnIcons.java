package org.stvnadore.plugin.icons;

import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jspecify.annotations.NullMarked;

/**
 * Static icon repository for STVN file types and UI elements.
 */
@NullMarked
public final class StvnIcons {

    /** Standard STVN file icon (.stvn, .stvn_incl, .stvn_inclf, .stvn_bin). */
    public static final Icon FILE = IconLoader.getIcon("/icons/stvn_icon.svg", StvnIcons.class);

    private StvnIcons() {
        // Prevent instantiation
    }
}
