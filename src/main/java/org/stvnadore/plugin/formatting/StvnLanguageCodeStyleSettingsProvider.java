package org.stvnadore.plugin.formatting;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Language code style settings provider configuring the canonical 2-space indentation
 * standard and Zero-Tab Invariant for STVN documents.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    /**
     * Default constructor for the STVN language code style settings provider.
     */
    public StvnLanguageCodeStyleSettingsProvider() {
        super();
    }

    @Override
    public @NotNull Language getLanguage() {
        return StvnLanguage.INSTANCE;
    }

    @Override
    protected void customizeDefaults(
            @NotNull CommonCodeStyleSettings commonSettings,
            @NotNull CommonCodeStyleSettings.IndentOptions indentOptions
    ) {
        indentOptions.INDENT_SIZE = 2;
        indentOptions.TAB_SIZE = 2;
        indentOptions.CONTINUATION_INDENT_SIZE = 2;
        indentOptions.USE_TAB_CHARACTER = false;
    }

    @Override
    public @Nullable String getCodeSample(@NotNull SettingsType settingsType) {
        return """
            {
              :defs {
                :UserID :Uint64
                :Status :Enum [ #ACTIVE #SUSPENDED ]
              }
              :type :Tuple(:UserID :Status)
              :body (
                1001
                #ACTIVE
              )
            }
            """;
    }
}
