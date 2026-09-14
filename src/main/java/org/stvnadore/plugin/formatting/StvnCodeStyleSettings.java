package org.stvnadore.plugin.formatting;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import org.jspecify.annotations.NullMarked;

/**
 * Custom code style settings container for the STVN language.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnCodeStyleSettings extends CustomCodeStyleSettings {

    /**
     * Constructs STVN code style settings attached to the parent settings container.
     *
     * @param container the parent code style settings container
     */
    public StvnCodeStyleSettings(CodeStyleSettings container) {
        super("StvnCodeStyleSettings", container);
    }
}
