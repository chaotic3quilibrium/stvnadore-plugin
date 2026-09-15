package org.stvnadore.plugin.browser;

import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableRowSorter;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.StvnFileType;

/**
 * UI controller constructing and displaying the lightweight interactive dependency browser popup.
 */
@NullMarked
public final class StvnNamespaceBrowserPopup {

    private StvnNamespaceBrowserPopup() {
    }

    /**
     * Constructs and displays the namespace browser popup filtered to the given scope.
     *
     * @param project the active IntelliJ project
     * @param file the source STVN file
     * @param scope the initial section scope
     */
    public static void showPopup(Project project, PsiFile file, StvnNamespaceScope scope) {
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, scope);
        var tableModel = new StvnNamespaceTableModel(entries);
        var table = new JBTable(tableModel);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(new TableRowSorter<>(tableModel));
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        TableSpeedSearch.installOn(table);

        var panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(750, 380));

        var headerLabel = new JBLabel("Scope: " + scope.getDisplayTitle() + " (" + entries.size() + " symbols)");
        headerLabel.setBorder(JBUI.Borders.emptyBottom(4));
        panel.add(headerLabel, BorderLayout.NORTH);

        var scrollPane = new JBScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        var popupRef = new JBPopup[1];

        var navigationRunnable = (Runnable) () -> {
            int visualRow = table.getSelectedRow();
            if (visualRow < 0) return;
            int modelRow = table.convertRowIndexToModel(visualRow);
            var entry = tableModel.getItem(modelRow);

            if (popupRef[0] != null) {
                popupRef[0].cancel();
            }

            navigateToSymbol(project, file, entry);
        };

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    navigationRunnable.run();
                }
            }
        });

        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    navigationRunnable.run();
                    e.consume();
                }
            }
        });

        var popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, table)
            .setTitle("STVN Namespace Dependencies — " + scope.getSectionKeyword())
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup();

        popupRef[0] = popup;
        popup.showInFocusCenter();
    }

    private static void navigateToSymbol(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        if (entry.isPrelude()) {
            var lightFile = new LightVirtualFile(
                "org_stvnadore_prelude.stvn_inclf",
                StvnFileType.Inclf.INSTANCE,
                StvnPrelude.PRELUDE_STVN_INCLF
            );
            lightFile.setWritable(false);
            FileEditorManager.getInstance(project).openTextEditor(
                new OpenFileDescriptor(project, lightFile, entry.navigationOffset()),
                true
            );
            return;
        }

        var targetPsi = entry.targetElement();
        if (targetPsi != null) {
            var targetFile = targetPsi.getContainingFile();
            if (targetFile != null && targetFile.getVirtualFile() != null) {
                FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(project, targetFile.getVirtualFile(), entry.navigationOffset()),
                    true
                );
                return;
            }
        }

        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {
            editor.getCaretModel().moveToOffset(entry.navigationOffset());
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
        }
    }
}
