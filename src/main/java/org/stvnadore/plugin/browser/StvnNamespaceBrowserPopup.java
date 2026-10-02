package org.stvnadore.plugin.browser;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.components.JBCheckBox;
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
import java.util.ArrayList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableRowSorter;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.PackageEnclosure;

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
        StvnPrioritizedTableSpeedSearch.installOn(table);

        var panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(750, 380));

        var northPanel = new JPanel(new BorderLayout(0, 0));
        var headerLabel = new JBLabel("Scope: " + scope.getDisplayTitle() + " (" + entries.size() + " symbols)");
        headerLabel.setBorder(JBUI.Borders.emptyBottom(4));
        northPanel.add(headerLabel, BorderLayout.WEST);

        var showPreludeCheckBox = new JBCheckBox("Show Standard Library Prelude", false);
        showPreludeCheckBox.addActionListener(e -> {
            boolean selected = showPreludeCheckBox.isSelected();
            var updatedEntries = new ArrayList<>(StvnNamespaceSymbolCollector.collectSymbols(file, scope));
            if (selected) {
                var preludeEntries = StvnNamespaceSymbolCollector.collectPreludeSymbols(file.getProject());
                for (var pe : preludeEntries) {
                    if (!updatedEntries.contains(pe)) {
                        updatedEntries.add(pe);
                    }
                }
            }
            tableModel.setItems(updatedEntries);
            headerLabel.setText("Scope: " + scope.getDisplayTitle() + " (" + updatedEntries.size() + " symbols)");
        });
        northPanel.add(showPreludeCheckBox, BorderLayout.EAST);
        panel.add(northPanel, BorderLayout.NORTH);

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
                if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
                    showContextMenu(e, project, file, table, tableModel);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, project, file, table, tableModel);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, project, file, table, tableModel);
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

    private static void showContextMenu(
            MouseEvent e,
            Project project,
            PsiFile sourceFile,
            JBTable table,
            StvnNamespaceTableModel tableModel
    ) {
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        int modelRow = table.convertRowIndexToModel(row);
        var entry = tableModel.getItem(modelRow);

        var popupMenu = new JBPopupMenu();
        var itemUse = new JMenuItem("Insert Reference (:use)");
        itemUse.addActionListener(ev -> WriteCommandAction.runWriteCommandAction(project, () ->
            insertUseReferenceInEditor(project, sourceFile, entry)
        ));
        popupMenu.add(itemUse);

        var itemQualify = new JMenuItem("Qualify Reference in Editor (FQNI)");
        itemQualify.addActionListener(ev -> WriteCommandAction.runWriteCommandAction(project, () ->
            insertQualifiedReferenceInEditor(project, sourceFile, entry)
        ));
        popupMenu.add(itemQualify);

        var itemBrand = new JMenuItem("Brand as New Nominal Type in :defs");
        itemBrand.addActionListener(ev -> WriteCommandAction.runWriteCommandAction(project, () ->
            insertNominalBrandInDefs(project, sourceFile, entry)
        ));
        popupMenu.add(itemBrand);

        popupMenu.show(table, e.getX(), e.getY());
    }

    private static void insertUseReferenceInEditor(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        var doc = PsiDocumentManager.getInstance(project).getDocument(sourceFile);
        if (doc == null) return;

        var enclosingPkg = PsiTreeUtil.findChildOfType(sourceFile, PackageEnclosure.class);
        int insertOffset;
        if (enclosingPkg != null) {
            var openBrace = enclosingPkg.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : enclosingPkg.getTextOffset();
        } else {
            var defsEntry = PsiTreeUtil.findChildOfType(sourceFile, DefsEntry.class);
            if (defsEntry != null) {
                var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
                insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : defsEntry.getTextOffset();
            } else {
                return;
            }
        }
        var importStatement = "\n    :use [ " + entry.source() + " { " + entry.name() + " } ]";
        doc.insertString(insertOffset, importStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(sourceFile);
    }

    private static void insertQualifiedReferenceInEditor(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {
            var cleanSymbol = entry.name().startsWith(":") ? entry.name().substring(1) : entry.name();
            var fqni = entry.source() + "/" + cleanSymbol;
            var caret = editor.getCaretModel().getPrimaryCaret();
            var offset = caret.getOffset();
            var doc = editor.getDocument();
            doc.insertString(offset, fqni);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }

    private static void insertNominalBrandInDefs(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        var doc = PsiDocumentManager.getInstance(project).getDocument(sourceFile);
        if (doc == null) return;

        var defsEntry = PsiTreeUtil.findChildOfType(sourceFile, DefsEntry.class);
        if (defsEntry != null) {
            var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            if (openBrace != null) {
                int insertOffset = openBrace.getStartOffset() + 1;
                var cleanSymbol = entry.name().startsWith(":") ? entry.name().substring(1) : entry.name();
                var fqni = entry.source() + "/" + cleanSymbol;
                var brandStatement = "\n    " + entry.name() + " " + fqni;
                doc.insertString(insertOffset, brandStatement);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                CodeStyleManager.getInstance(project).reformat(sourceFile);
            }
        }
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
