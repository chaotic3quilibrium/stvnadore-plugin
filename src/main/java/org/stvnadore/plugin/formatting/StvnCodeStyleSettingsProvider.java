package org.stvnadore.plugin.formatting;

import com.intellij.application.options.CodeStyleAbstractConfigurable;
import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.application.options.TabbedLanguageCodeStylePanel;
import com.intellij.psi.codeStyle.CodeStyleConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Code style settings provider registering the STVN language tab under IDE settings.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnCodeStyleSettingsProvider extends CodeStyleSettingsProvider {

    /**
     * Default constructor for the STVN code style settings provider.
     */
    public StvnCodeStyleSettingsProvider() {
        super();
    }

    @Override
    public @Nullable CustomCodeStyleSettings createCustomSettings(@NotNull CodeStyleSettings settings) {
        return new StvnCodeStyleSettings(settings);
    }

    @Override
    public @NotNull CodeStyleConfigurable createConfigurable(
            @NotNull CodeStyleSettings settings,
            @NotNull CodeStyleSettings modelSettings
    ) {
        return new CodeStyleAbstractConfigurable(settings, modelSettings, "STVN") {
            @Override
            protected @NotNull CodeStyleAbstractPanel createPanel(@NotNull CodeStyleSettings currentSettings) {
                return new TabbedLanguageCodeStylePanel(StvnLanguage.INSTANCE, currentSettings, modelSettings) {
                    @Override
                    protected void initTabs(CodeStyleSettings initSettings) {
                        addIndentOptionsTab(initSettings);
                        addSpacesTab(initSettings);
                        addBlankLinesTab(initSettings);
                    }
                };
            }
        };
    }

    @Override
    public @Nullable String getConfigurableDisplayName() {
        return "STVN";
    }
}
