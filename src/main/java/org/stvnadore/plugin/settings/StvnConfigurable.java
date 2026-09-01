package org.stvnadore.plugin.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.icons.StvnIcons;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Icon;
import java.awt.BorderLayout;

@NullMarked
public final class StvnConfigurable implements SearchableConfigurable {

    private final Project project;
    private @Nullable JPanel mainPanel;
    private @Nullable JCheckBox useLongFormSumTypesCheckBox;
    private @Nullable JCheckBox showTypeHintsCheckBox;
    private @Nullable JCheckBox showHoverDocsCheckBox;
    private @Nullable JCheckBox enableRedundantTagInspectionCheckBox;
    private @Nullable JCheckBox enableFormDiscrepancyInspectionCheckBox;
    private @Nullable JCheckBox preferImpliedSumTypesCheckBox;

    public StvnConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return "org.stvnadore.plugin.settings.StvnConfigurable";
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "STVN";
    }

    @Override
    public @Nullable JComponent createComponent() {
        var panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));

        var cb1 = new JCheckBox("Use long-form variants for algebraic sum types (e.g., #Left instead of #L)");
        var cb2 = new JCheckBox("Show type inlay hints in editor");
        var cb3 = new JCheckBox("Show type information on hover (Quick Doc)");
        var cb4 = new JCheckBox("Enable redundant tag warnings (e.g., explicit #Some when implied)");
        var cb5 = new JCheckBox("Enable tag form discrepancy warnings (e.g., #S vs #Some)");
        var cb6 = new JCheckBox("Enforce implied/implicit algebraic sum types");

        cb1.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        cb2.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        cb3.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        cb4.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        cb5.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        cb6.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        panel.add(cb1);
        panel.add(cb2);
        panel.add(cb3);
        panel.add(cb4);
        panel.add(cb5);
        panel.add(cb6);

        mainPanel = panel;
        useLongFormSumTypesCheckBox = cb1;
        showTypeHintsCheckBox = cb2;
        showHoverDocsCheckBox = cb3;
        enableRedundantTagInspectionCheckBox = cb4;
        enableFormDiscrepancyInspectionCheckBox = cb5;
        preferImpliedSumTypesCheckBox = cb6;

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        var cb1 = useLongFormSumTypesCheckBox;
        var cb2 = showTypeHintsCheckBox;
        var cb3 = showHoverDocsCheckBox;
        var cb4 = enableRedundantTagInspectionCheckBox;
        var cb5 = enableFormDiscrepancyInspectionCheckBox;
        var cb6 = preferImpliedSumTypesCheckBox;

        if (cb1 == null || cb2 == null || cb3 == null || cb4 == null || cb5 == null || cb6 == null) {
            return false;
        }

        var settings = StvnSettings.getInstance(project);
        var projSettings = StvnProjectSettings.getInstance(project);

        return cb1.isSelected() != settings.getState().useLongFormSumTypes ||
               cb2.isSelected() != projSettings.getState().showTypeHints ||
               cb3.isSelected() != projSettings.getState().showHoverDocs ||
               cb4.isSelected() != projSettings.getState().enableRedundantTagInspection ||
               cb5.isSelected() != projSettings.getState().enableFormDiscrepancyInspection ||
               cb6.isSelected() != projSettings.getState().preferImpliedSumTypes;
    }

    @Override
    public void apply() {
        var cb1 = useLongFormSumTypesCheckBox;
        var cb2 = showTypeHintsCheckBox;
        var cb3 = showHoverDocsCheckBox;
        var cb4 = enableRedundantTagInspectionCheckBox;
        var cb5 = enableFormDiscrepancyInspectionCheckBox;
        var cb6 = preferImpliedSumTypesCheckBox;

        if (cb1 == null || cb2 == null || cb3 == null || cb4 == null || cb5 == null || cb6 == null) {
            return;
        }

        var settings = StvnSettings.getInstance(project);
        var projSettings = StvnProjectSettings.getInstance(project);

        settings.getState().useLongFormSumTypes = cb1.isSelected();
        projSettings.getState().showTypeHints = cb2.isSelected();
        projSettings.getState().showHoverDocs = cb3.isSelected();
        projSettings.getState().enableRedundantTagInspection = cb4.isSelected();
        projSettings.getState().enableFormDiscrepancyInspection = cb5.isSelected();
        projSettings.getState().preferImpliedSumTypes = cb6.isSelected();
    }

    @Override
    public void reset() {
        var cb1 = useLongFormSumTypesCheckBox;
        var cb2 = showTypeHintsCheckBox;
        var cb3 = showHoverDocsCheckBox;
        var cb4 = enableRedundantTagInspectionCheckBox;
        var cb5 = enableFormDiscrepancyInspectionCheckBox;
        var cb6 = preferImpliedSumTypesCheckBox;

        if (cb1 == null || cb2 == null || cb3 == null || cb4 == null || cb5 == null || cb6 == null) {
            return;
        }

        var settings = StvnSettings.getInstance(project);
        var projSettings = StvnProjectSettings.getInstance(project);

        cb1.setSelected(settings.getState().useLongFormSumTypes);
        cb2.setSelected(projSettings.getState().showTypeHints);
        cb3.setSelected(projSettings.getState().showHoverDocs);
        cb4.setSelected(projSettings.getState().enableRedundantTagInspection);
        cb5.setSelected(projSettings.getState().enableFormDiscrepancyInspection);
        cb6.setSelected(projSettings.getState().preferImpliedSumTypes);
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        useLongFormSumTypesCheckBox = null;
        showTypeHintsCheckBox = null;
        showHoverDocsCheckBox = null;
        enableRedundantTagInspectionCheckBox = null;
        enableFormDiscrepancyInspectionCheckBox = null;
        preferImpliedSumTypesCheckBox = null;
    }

}
