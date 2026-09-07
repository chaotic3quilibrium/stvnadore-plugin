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
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Icon;
import java.awt.BorderLayout;
import java.awt.Dimension;

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
    private @Nullable JComboBox<StvnSettings.BlockStringEnterStyle> blockStringEnterStyleComboBox;

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

        panel.add(javax.swing.Box.createVerticalStrut(10));
        var styleLabel = new JLabel("Multiline string auto-closing style:");
        styleLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(styleLabel);

        var styleCombo = new JComboBox<>(StvnSettings.BlockStringEnterStyle.values());
        styleCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == StvnSettings.BlockStringEnterStyle.EXPANDED_THREE_LINE) {
                    setText("Expanded (3 lines): Delimiter on line 3 (Default)");
                } else if (value == StvnSettings.BlockStringEnterStyle.TIGHT_TWO_LINE) {
                    setText("Tight (2 lines): Delimiter on line 2");
                }
                return this;
            }
        });
        styleCombo.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        styleCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, styleCombo.getPreferredSize().height));
        panel.add(styleCombo);
        panel.add(javax.swing.Box.createVerticalGlue());

        mainPanel = panel;
        useLongFormSumTypesCheckBox = cb1;
        showTypeHintsCheckBox = cb2;
        showHoverDocsCheckBox = cb3;
        enableRedundantTagInspectionCheckBox = cb4;
        enableFormDiscrepancyInspectionCheckBox = cb5;
        preferImpliedSumTypesCheckBox = cb6;
        blockStringEnterStyleComboBox = styleCombo;

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
        var combo = blockStringEnterStyleComboBox;

        if (cb1 == null || cb2 == null || cb3 == null || cb4 == null || cb5 == null || cb6 == null || combo == null) {
            return false;
        }

        var settings = StvnSettings.getInstance(project);
        var projSettings = StvnProjectSettings.getInstance(project);

        return cb1.isSelected() != settings.getState().useLongFormSumTypes ||
               cb2.isSelected() != projSettings.getState().showTypeHints ||
               cb3.isSelected() != projSettings.getState().showHoverDocs ||
               cb4.isSelected() != projSettings.getState().enableRedundantTagInspection ||
               cb5.isSelected() != projSettings.getState().enableFormDiscrepancyInspection ||
               cb6.isSelected() != projSettings.getState().preferImpliedSumTypes ||
               combo.getSelectedItem() != settings.getState().blockStringEnterStyle;
    }

    @Override
    public void apply() {
        var cb1 = useLongFormSumTypesCheckBox;
        var cb2 = showTypeHintsCheckBox;
        var cb3 = showHoverDocsCheckBox;
        var cb4 = enableRedundantTagInspectionCheckBox;
        var cb5 = enableFormDiscrepancyInspectionCheckBox;
        var cb6 = preferImpliedSumTypesCheckBox;
        var combo = blockStringEnterStyleComboBox;

        if (cb1 == null || cb2 == null || cb3 == null || cb4 == null || cb5 == null || cb6 == null || combo == null) {
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
        settings.getState().blockStringEnterStyle = (StvnSettings.BlockStringEnterStyle) combo.getSelectedItem();
    }

    @Override
    public void reset() {
        var cb1 = useLongFormSumTypesCheckBox;
        var cb2 = showTypeHintsCheckBox;
        var cb3 = showHoverDocsCheckBox;
        var cb4 = enableRedundantTagInspectionCheckBox;
        var cb5 = enableFormDiscrepancyInspectionCheckBox;
        var cb6 = preferImpliedSumTypesCheckBox;
        var combo = blockStringEnterStyleComboBox;

        if (cb1 == null || cb2 == null || cb3 == null || cb4 == null || cb5 == null || cb6 == null || combo == null) {
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
        combo.setSelectedItem(settings.getState().blockStringEnterStyle);
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
        blockStringEnterStyleComboBox = null;
    }

}
