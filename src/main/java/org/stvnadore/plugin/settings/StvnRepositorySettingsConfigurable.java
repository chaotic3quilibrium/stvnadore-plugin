package org.stvnadore.plugin.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * Settings configuration panel under Tools > STVN Schema Repository.
 */
@NullMarked
public final class StvnRepositorySettingsConfigurable implements SearchableConfigurable {

    private final Project project;
    private @Nullable JPanel mainPanel;
    private @Nullable JBTextField repoUrlField;
    private @Nullable JBTextField timeoutMsField;

    /**
     * Constructs an StvnRepositorySettingsConfigurable instance.
     *
     * @param project the active IntelliJ project
     */
    public StvnRepositorySettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return "org.stvnadore.plugin.settings.StvnRepositorySettingsConfigurable";
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "STVN Schema Repository";
    }

    @Override
    public @Nullable JComponent createComponent() {
        repoUrlField = new JBTextField();
        timeoutMsField = new JBTextField();

        JPanel formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Repository Base URL:"), repoUrlField, 1, false)
                .addLabeledComponent(new JBLabel("Connection Timeout (ms):"), timeoutMsField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(formPanel, BorderLayout.NORTH);

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        var urlField = repoUrlField;
        var timeoutField = timeoutMsField;
        if (urlField == null || timeoutField == null) {
            return false;
        }

        var settings = StvnRepositorySettings.getInstance(project);
        boolean urlChanged = !urlField.getText().trim().equals(settings.getRepoUrl());
        boolean timeoutChanged;
        try {
            int val = Integer.parseInt(timeoutField.getText().trim());
            timeoutChanged = val != settings.getTimeoutMs();
        } catch (NumberFormatException e) {
            timeoutChanged = true;
        }
        return urlChanged || timeoutChanged;
    }

    @Override
    public void apply() {
        var urlField = repoUrlField;
        var timeoutField = timeoutMsField;
        if (urlField == null || timeoutField == null) {
            return;
        }

        var settings = StvnRepositorySettings.getInstance(project);
        settings.setRepoUrl(urlField.getText().trim());
        try {
            int timeout = Integer.parseInt(timeoutField.getText().trim());
            if (timeout > 0) {
                settings.setTimeoutMs(timeout);
            }
        } catch (NumberFormatException ignored) {
            // Keep existing valid timeout on invalid input
        }
    }

    @Override
    public void reset() {
        var urlField = repoUrlField;
        var timeoutField = timeoutMsField;
        if (urlField == null || timeoutField == null) {
            return;
        }

        var settings = StvnRepositorySettings.getInstance(project);
        urlField.setText(settings.getRepoUrl());
        timeoutField.setText(String.valueOf(settings.getTimeoutMs()));
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        repoUrlField = null;
        timeoutMsField = null;
    }
}