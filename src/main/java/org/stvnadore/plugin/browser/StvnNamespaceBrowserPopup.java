package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableRowSorter;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.UseStmt;

/**
 * UI controller constructing and displaying the lightweight interactive dependency browser popup.
 */
@NullMarked
public final class StvnNamespaceBrowserPopup {

    private StvnNamespaceBrowserPopup() {
    }

    record BrowserContent(
        JPanel panel,
        JBTable table,
        JBCheckBox showPreludeCheckBox,
        StvnNamespaceTableModel tableModel,
        JBLabel headerLabel
    ) {}

    static BrowserContent createBrowserContent(Project project, PsiFile file, StvnNamespaceScope scope) {
        var entries = ReadAction.compute(() -> StvnNamespaceSymbolCollector.collectSymbols(file, scope));
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
            var updatedEntries = ReadAction.compute(() -> {
                var list = new ArrayList<>(StvnNamespaceSymbolCollector.collectSymbols(file, scope));
                if (selected) {
                    var preludeEntries = StvnNamespaceSymbolCollector.collectPreludeSymbols(file.getProject());
                    for (var pe : preludeEntries) {
                        if (!list.contains(pe)) {
                            list.add(pe);
                        }
                    }
                }
                return list;
            });
            tableModel.setItems(updatedEntries);
            headerLabel.setText("Scope: " + scope.getDisplayTitle() + " (" + updatedEntries.size() + " symbols)");
        });
        northPanel.add(showPreludeCheckBox, BorderLayout.EAST);
        panel.add(northPanel, BorderLayout.NORTH);

        var scrollPane = new JBScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        return new BrowserContent(panel, table, showPreludeCheckBox, tableModel, headerLabel);
    }

    /**
     * Constructs and displays the namespace browser popup filtered to the given scope.
     *
     * @param project the active IntelliJ project
     * @param file the source STVN file
     * @param scope the initial section scope
     */
    public static void showPopup(Project project, PsiFile file, StvnNamespaceScope scope) {
        var content = createBrowserContent(project, file, scope);
        var panel = content.panel();
        var table = content.table();
        var tableModel = content.tableModel();

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

        var popupMenu = buildContextMenu(project, sourceFile, entry);
        popupMenu.show(table, e.getX(), e.getY());
    }

    static JBPopupMenu buildContextMenu(
            Project project,
            PsiFile sourceFile,
            StvnNamespaceSymbolEntry entry
    ) {
        var popupMenu = new JBPopupMenu();
        var itemUse = new JMenuItem("Insert Reference (:use)");
        itemUse.addActionListener(ev -> WriteCommandAction.runWriteCommandAction(project, () ->
            insertUseReferenceInEditor(project, sourceFile, entry)
        ));
        popupMenu.add(itemUse);

        var itemCopyFqni = new JMenuItem("Copy FQNI");
        itemCopyFqni.addActionListener(ev -> copyFqniToClipboard(project, entry));
        popupMenu.add(itemCopyFqni);

        if (!isConstantSymbol(entry)) {
            var itemBrand = new JMenuItem("Brand as New Nominal Type in :defs");
            itemBrand.addActionListener(ev -> WriteCommandAction.runWriteCommandAction(project, () ->
                insertNominalBrandInDefs(project, sourceFile, entry)
            ));
            popupMenu.add(itemBrand);
        }

        return popupMenu;
    }

    static void insertUseReferenceInEditor(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        var doc = PsiDocumentManager.getInstance(project).getDocument(sourceFile);
        if (doc == null) return;

        var rawSegment = entry.name().contains("/")
            ? entry.name().substring(entry.name().lastIndexOf('/') + 1)
            : entry.name();
        var cleanSegment = rawSegment.startsWith("#") || rawSegment.startsWith(":")
            ? rawSegment.substring(1)
            : rawSegment;
        var bareName = (entry.name().startsWith("#") || rawSegment.startsWith("#") ? "#" : ":") + cleanSegment;

        var enclosingPkg = PsiTreeUtil.findChildOfType(sourceFile, PackageEnclosure.class);
        if (enclosingPkg != null) {
            var existingUse = findExistingUse(enclosingPkg, entry.source());
            if (existingUse != null) {
                appendSymbolToExistingUse(doc, existingUse, bareName);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
        } else {
            var defsEntry = PsiTreeUtil.findChildOfType(sourceFile, DefsEntry.class);
            if (defsEntry != null) {
                var existingUse = findExistingUse(defsEntry, entry.source());
                if (existingUse != null) {
                    appendSymbolToExistingUse(doc, existingUse, bareName);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            }
        }

        int insertOffset;
        String baseIndent;
        if (enclosingPkg != null) {
            var openBrace = enclosingPkg.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : enclosingPkg.getTextOffset();
            baseIndent = computeElementIndent(doc, enclosingPkg);
        } else {
            var defsEntry = org.stvnadore.plugin.psi.StvnPsiUtils.getOrCreateDefsBlock(sourceFile);
            if (defsEntry != null) {
                var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
                insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : defsEntry.getTextOffset();
                baseIndent = computeElementIndent(doc, defsEntry);
            } else {
                return;
            }
        }
        var childIndent = baseIndent + "  ";
        var importStatement = "\n" + childIndent + ":use [ " + entry.source() + " { " + bareName + " " + bareName + " } ]";
        doc.insertString(insertOffset, importStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    private static @Nullable org.stvnadore.psi.UseStmt findExistingUse(PsiElement container, String targetSource) {
        var useStmts = PsiTreeUtil.findChildrenOfType(container, org.stvnadore.psi.UseStmt.class);
        for (var use : useStmts) {
            if (container instanceof PackageEnclosure pkg) {
                if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != pkg) continue;
            } else if (container instanceof DefsEntry defs) {
                if (PsiTreeUtil.getParentOfType(use, DefsEntry.class) != defs
                        || PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null) continue;
            }
            var target = use.getUseTarget();
            if (target != null && target.getText().equals(targetSource)) {
                return use;
            }
        }
        return null;
    }

    private static void appendSymbolToExistingUse(com.intellij.openapi.editor.Document doc, org.stvnadore.psi.UseStmt existingUse, String bareName) {
        var opts = existingUse.getUseOptionsBlock();
        if (opts != null && opts.getText().contains("#strip")) {
            return; // Symbols already in scope via #strip
        }
        if (existingUse.getText().contains("#strip")) {
            return;
        }
        var aliasBlock = existingUse.getUseAliasBlock();
        var chars = doc.getCharsSequence();
        if (aliasBlock != null) {
            for (var alias : aliasBlock.getUseMapAliasList()) {
                for (var tk : alias.getTypeKeywordList()) {
                    if (bareName.equals(tk.getText())) return;
                }
                for (var vk : alias.getValueKeywordList()) {
                    if (bareName.equals(vk.getText())) return;
                }
            }
            var rbrace = aliasBlock.getNode().findChildByType(org.stvnadore.psi.StvnTypes.RBRACE);
            int rbraceOffset = rbrace != null ? rbrace.getStartOffset() : aliasBlock.getTextRange().getEndOffset() - 1;
            if (rbraceOffset > 0 && chars.charAt(rbraceOffset - 1) == ' ') {
                doc.insertString(rbraceOffset - 1, " " + bareName + " " + bareName);
            } else {
                doc.insertString(rbraceOffset, " " + bareName + " " + bareName + " ");
            }
        } else {
            var rbrack = existingUse.getNode().findChildByType(org.stvnadore.psi.StvnTypes.RBRACK);
            int rbrackOffset = rbrack != null ? rbrack.getStartOffset() : existingUse.getTextRange().getEndOffset() - 1;
            if (rbrackOffset > 0 && chars.charAt(rbrackOffset - 1) == ' ') {
                doc.insertString(rbrackOffset - 1, " { " + bareName + " " + bareName + " }");
            } else {
                doc.insertString(rbrackOffset, " { " + bareName + " " + bareName + " } ");
            }
        }
    }

    static boolean isConstantSymbol(StvnNamespaceSymbolEntry entry) {
        var name = entry.name();
        if (name.startsWith("#")) {
            return true;
        }
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < name.length()) {
            return name.charAt(lastSlash + 1) == '#';
        }
        return false;
    }

    static String resolveCanonicalFqni(StvnNamespaceSymbolEntry entry) {
        if (isConstantSymbol(entry)) {
            if (entry.name().contains("/")) {
                var raw = entry.name();
                if (raw.startsWith(":")) {
                    raw = "#" + raw.substring(1);
                }
                int lastSlash = raw.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash + 1 < raw.length() && raw.charAt(lastSlash + 1) == '#') {
                    return raw.substring(0, lastSlash + 1) + raw.substring(lastSlash + 2);
                }
                return raw;
            }
            var src = entry.source().startsWith(":") ? entry.source().substring(1) : entry.source();
            var cleanName = entry.name().startsWith("#") ? entry.name().substring(1) : entry.name();
            return src.contains("/") ? "#" + src + "/" + cleanName : "#" + cleanName;
        } else {
            if (entry.name().contains("/")) {
                var raw = entry.name();
                return raw.startsWith(":") ? raw : ":" + raw;
            }
            var src = entry.source().startsWith(":") ? entry.source().substring(1) : entry.source();
            var cleanName = entry.name().startsWith(":") ? entry.name().substring(1) : entry.name();
            return src.contains("/") ? ":" + src + "/" + cleanName : ":" + cleanName;
        }
    }

    static void copyFqniToClipboard(Project project, StvnNamespaceSymbolEntry entry) {
        var fqni = resolveCanonicalFqni(entry);
        CopyPasteManager.getInstance().setContents(new StringSelection(fqni));
        var statusBar = WindowManager.getInstance().getStatusBar(project);
        if (statusBar != null) {
            statusBar.setInfo("Copied FQNI to clipboard: " + fqni);
        }
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {
            HintManager.getInstance().showInformationHint(editor, "Copied FQNI: " + fqni);
        }
    }

    static void insertNominalBrandInDefs(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        if (isConstantSymbol(entry)) {
            return;
        }
        var doc = PsiDocumentManager.getInstance(project).getDocument(sourceFile);
        if (doc == null) return;

        var defsEntry = org.stvnadore.plugin.psi.StvnPsiUtils.getOrCreateDefsBlock(sourceFile);
        if (defsEntry != null) {
            var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            if (openBrace != null) {
                int insertOffset = openBrace.getStartOffset() + 1;
                var fqni = resolveCanonicalFqni(entry);
                var rawSegment = entry.name().contains("/")
                    ? entry.name().substring(entry.name().lastIndexOf('/') + 1)
                    : entry.name();
                var cleanSegment = rawSegment.startsWith("#") || rawSegment.startsWith(":")
                    ? rawSegment.substring(1)
                    : rawSegment;
                var bareName = ":" + cleanSegment;
                var baseIndent = computeElementIndent(doc, defsEntry);
                var childIndent = baseIndent + "  ";
                var brandStatement = "\n" + childIndent + bareName + " " + fqni;
                doc.insertString(insertOffset, brandStatement);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    private static String computeElementIndent(com.intellij.openapi.editor.Document doc, PsiElement element) {
        int line = doc.getLineNumber(element.getTextOffset());
        int lineStart = doc.getLineStartOffset(line);
        int elemOffset = element.getTextOffset();
        if (elemOffset < lineStart) return "  ";
        String prefix = doc.getText(new TextRange(lineStart, elemOffset));
        var sb = new StringBuilder();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == ' ') sb.append(' ');
            else break;
        }
        return sb.toString();
    }

    private static void navigateToSymbol(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
        if (entry.isPrelude()) {
            var lightFile = StvnPreludeBridge.getPreludeVirtualFile(project);
            FileEditorManager.getInstance(project).openTextEditor(
                new OpenFileDescriptor(project, lightFile, entry.navigationOffset()),
                true
            );
            return;
        }

        var targetPsi = entry.targetElement();
        if (targetPsi != null) {
            var targetVirtualFile = ReadAction.compute(() -> {
                if (targetPsi.isValid()) {
                    var targetFile = targetPsi.getContainingFile();
                    return targetFile != null ? targetFile.getVirtualFile() : null;
                }
                return null;
            });
            if (targetVirtualFile != null) {
                FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(project, targetVirtualFile, entry.navigationOffset()),
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
