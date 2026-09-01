package org.stvnadore.plugin;

import com.intellij.lang.Language;
import org.jspecify.annotations.NullMarked;

/**
 * Core Language extension registration for the STVN language.
 */
@NullMarked
public final class StvnLanguage extends Language {
    /** Singleton language instance. */
    public static final StvnLanguage INSTANCE = new StvnLanguage();

    private StvnLanguage() {
        super("STVN");
    }
}