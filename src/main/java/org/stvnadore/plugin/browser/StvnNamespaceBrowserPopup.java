package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseStmt;

import javax.swing.ButtonGroup;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * UI controller constructing and displaying the interactive 3-scope, 5-column
 * {@code STVN Namespace Dependencies} browser popup.
 */
@NullMarked
public final class StvnNamespaceBrowserPopup {

    /** Tooltip displayed on the disabled {@code :type} scope button when {@code :type} is absent. */
    public static final String DISABLED_TYPE_SCOPE_TOOLTIP =
        "Unavailable: this document does not declare a root :type section.";

    /** Tooltip displayed on the disabled {@code :body} scope button when {@code :body} is absent. */
    public static final String DISABLED_BODY_SCOPE_TOOLTIP =
        "Unavailable: this document does not declare a root :body section.";

    private StvnNamespaceBrowserPopup() {
    }

    /**
     * Encapsulates the constructed Swing components, live scope buttons, filter input, and async snapshot future.
     *
     * @param panel               outer popup container panel
     * @param table               5-column symbol table
     * @param showPreludeCheckBox prelude toggle checkbox
     * @param filterField         live symbol filter field
     * @param scopeButtons        radio buttons keyed by {@link StvnNamespaceScope}
     * @param activeScopeRef      mutable reference tracking the currently selected scope
     * @param tableModel          underlying 5-column {@link StvnNamespaceTableModel}
     * @param headerLabel         dynamic scope and count summary label
     * @param loadingPanel        asynchronous loading wrapper panel
     * @param analysisFuture      completion future resolving to {@link StvnNamespaceSymbolCollector.StvnNamespaceSnapshot}
     */
    record BrowserContent(
        JPanel panel,
        JBTable table,
        JBCheckBox showPreludeCheckBox,
        SearchTextField filterField,
        Map<StvnNamespaceScope, JRadioButton> scopeButtons,
        AtomicReference<StvnNamespaceScope> activeScopeRef,
        StvnNamespaceTableModel tableModel,
        JBLabel headerLabel,
        JBLoadingPanel loadingPanel,
        CompletableFuture<StvnNamespaceSymbolCollector.StvnNamespaceSnapshot> analysisFuture
    ) {
        /**
         * Waits for the background dependency snapshot analysis to complete.
         *
         * @param timeout maximum wait time
         * @param unit    time unit
         * @throws Exception if interrupted or timed out
         */
        public void waitForAnalysis(long timeout, TimeUnit unit) throws Exception {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
                while (!analysisFuture.isDone()) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new TimeoutException("Timed out waiting for namespace dependency analysis");
                    }
                    com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
                    Thread.sleep(10);
                }
                analysisFuture.get(0, TimeUnit.MILLISECONDS);
            } else {
                analysisFuture.get(timeout, unit);
            }
        }
    }

    static BrowserContent createBrowserContent(Project project, PsiFile file, StvnNamespaceScope scope) {
        return createBrowserContent(project, file, scope, Disposer.newDisposable(), title -> {});
    }

    static BrowserContent createBrowserContent(
        Project project,
        PsiFile file,
        StvnNamespaceScope scope,
        Disposable parentDisposable
    ) {
        return createBrowserContent(project, file, scope, parentDisposable, title -> {});
    }

    static BrowserContent createBrowserContent(
        Project project,
        PsiFile file,
        StvnNamespaceScope initialScope,
        Disposable parentDisposable,
        Consumer<String> popupTitleUpdater
    ) {
        var tableModel = new StvnNamespaceTableModel(new ArrayList<>());
        var table = new JBTable(tableModel);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(new TableRowSorter<>(tableModel));
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        StvnPrioritizedTableSpeedSearch.installOn(table);

        var panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(880, 420));

        var activeScopeRef = new AtomicReference<>(initialScope);
        var snapshotRef = new AtomicReference<StvnNamespaceSymbolCollector.@Nullable StvnNamespaceSnapshot>(null);

        var northPanel = new JPanel(new BorderLayout(0, 4));
        var topRow = new JPanel(new BorderLayout(8, 0));
        var headerLabel = new JBLabel(
            "Scope: " + initialScope.getSectionKeyword() + " \u2014 " + initialScope.getDisplayTitle() + " (Analyzing...)"
        );
        topRow.add(headerLabel, BorderLayout.WEST);

        var showPreludeCheckBox = new JBCheckBox("Show Standard Library Prelude", false);
        showPreludeCheckBox.setEnabled(false);
        topRow.add(showPreludeCheckBox, BorderLayout.EAST);
        northPanel.add(topRow, BorderLayout.NORTH);

        var controlsRow = new JPanel(new BorderLayout(10, 0));
        var scopeSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scopeSelectorPanel.add(new JBLabel("Scope:"));

        var scopeGroup = new ButtonGroup();
        Map<StvnNamespaceScope, JRadioButton> scopeButtons = new EnumMap<>(StvnNamespaceScope.class);
        int mnemonicIndex = 1;
        for (StvnNamespaceScope candidateScope : StvnNamespaceScope.values()) {
            var radio = new JBRadioButton(candidateScope.getSectionKeyword(), candidateScope == initialScope);
            radio.setMnemonic(KeyEvent.VK_0 + mnemonicIndex++);
            radio.setEnabled(false);
            scopeGroup.add(radio);
            scopeButtons.put(candidateScope, radio);
            scopeSelectorPanel.add(radio);
        }
        controlsRow.add(scopeSelectorPanel, BorderLayout.WEST);

        var filterField = new SearchTextField(false);
        filterField.getTextEditor().getEmptyText().setText("Filter symbols by name, source, target, or type...");
        filterField.setEnabled(false);
        controlsRow.add(filterField, BorderLayout.CENTER);

        northPanel.add(controlsRow, BorderLayout.SOUTH);
        panel.add(northPanel, BorderLayout.NORTH);

        var scrollPane = new JBScrollPane(table);
        var loadingPanel = new JBLoadingPanel(new BorderLayout(), parentDisposable);
        loadingPanel.add(scrollPane, BorderLayout.CENTER);
        loadingPanel.setLoadingText("Analyzing " + initialScope.getSectionKeyword() + " dependencies...");
        loadingPanel.startLoading();
        panel.add(loadingPanel, BorderLayout.CENTER);

        Runnable refreshView = () -> {
            var snapshot = snapshotRef.get();
            if (snapshot == null) {
                return;
            }
            StvnNamespaceScope currentScope = activeScopeRef.get();
            boolean showPrelude = showPreludeCheckBox.isSelected();
            String filterText = filterField.getText().trim();

            List<StvnNamespaceSymbolEntry> baseEntries = snapshot.entriesByScope()
                .getOrDefault(currentScope, List.of());
            List<StvnNamespaceSymbolEntry> combined = mergeWithPrelude(baseEntries, snapshot.preludeEntries(), showPrelude);
            List<StvnNamespaceSymbolEntry> filtered = applyFilterQuery(combined, filterText);

            tableModel.setItems(filtered);
            if (!filtered.isEmpty()) {
                table.setRowSelectionInterval(0, 0);
            }
            headerLabel.setText(formatScopeSummaryText(currentScope, filtered.size(), combined.size(), showPrelude, !filterText.isEmpty()));
            popupTitleUpdater.accept(formatPopupTitle(file.getName(), currentScope));
        };

        for (var entry : scopeButtons.entrySet()) {
            StvnNamespaceScope buttonScope = entry.getKey();
            JRadioButton radio = entry.getValue();
            radio.addActionListener(e -> {
                if (radio.isSelected() && radio.isEnabled()) {
                    activeScopeRef.set(buttonScope);
                    refreshView.run();
                }
            });
        }

        showPreludeCheckBox.addActionListener(e -> refreshView.run());

        filterField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refreshView.run();
            }
        });

        var analysisFuture = new CompletableFuture<StvnNamespaceSymbolCollector.StvnNamespaceSnapshot>();

        var promise = ReadAction.nonBlocking(() -> {
            ProgressManager.checkCanceled();
            return StvnNamespaceSymbolCollector.collectSnapshot(project, file);
        })
        .inSmartMode(project)
        .expireWith(parentDisposable)
        .expireWhen(() -> !file.isValid() || project.isDisposed())
        .finishOnUiThread(ModalityState.any(), snapshot -> {
            snapshotRef.set(snapshot);
            StvnNamespaceScope resolvedScope = snapshot.resolveAvailableScope(initialScope);
            activeScopeRef.set(resolvedScope);

            var defsBtn = scopeButtons.get(StvnNamespaceScope.DEFS);
            defsBtn.setEnabled(true);
            defsBtn.setToolTipText("Display all in-scope :defs declarations and :use projections");

            var typeBtn = scopeButtons.get(StvnNamespaceScope.TYPE);
            typeBtn.setEnabled(snapshot.hasTypeSection());
            typeBtn.setToolTipText(snapshot.hasTypeSection()
                ? "Display transitive symbol closure reachable from root :type"
                : DISABLED_TYPE_SCOPE_TOOLTIP);

            var bodyBtn = scopeButtons.get(StvnNamespaceScope.BODY);
            bodyBtn.setEnabled(snapshot.hasBodySection());
            bodyBtn.setToolTipText(snapshot.hasBodySection()
                ? "Display transitive symbol closure reachable from :type and :body"
                : DISABLED_BODY_SCOPE_TOOLTIP);

            scopeButtons.get(resolvedScope).setSelected(true);
            showPreludeCheckBox.setEnabled(true);
            filterField.setEnabled(true);

            refreshView.run();
            loadingPanel.stopLoading();
            analysisFuture.complete(snapshot);
        })
        .submit(AppExecutorUtil.getAppExecutorService());

        promise.onError(analysisFuture::completeExceptionally);

        return new BrowserContent(
            panel,
            table,
            showPreludeCheckBox,
            filterField,
            Map.copyOf(scopeButtons),
            activeScopeRef,
            tableModel,
            headerLabel,
            loadingPanel,
            analysisFuture
        );
    }

    static String formatPopupTitle(String fileName, StvnNamespaceScope scope) {
        return "STVN Namespace Dependencies \u2014 " + fileName + " [" + scope.getSectionKeyword() + "]";
    }

    private static String formatScopeSummaryText(
        StvnNamespaceScope scope,
        int visibleCount,
        int totalInScopeCount,
        boolean preludeEnabled,
        boolean filterActive
    ) {
        String countPart = filterActive
            ? visibleCount + "/" + totalInScopeCount + " symbols"
            : visibleCount + " symbols";
        String preludePart = preludeEnabled ? ", Prelude shown" : "";
        return "Scope: " + scope.getSectionKeyword() + " \u2014 " + scope.getDisplayTitle()
            + " (" + countPart + preludePart + ")";
    }

    private static List<StvnNamespaceSymbolEntry> mergeWithPrelude(
        List<StvnNamespaceSymbolEntry> scopeEntries,
        List<StvnNamespaceSymbolEntry> preludeEntries,
        boolean showPrelude
    ) {
        if (!showPrelude) {
            return List.copyOf(scopeEntries);
        }
        var merged = new ArrayList<>(scopeEntries);
        var existingNames = new HashSet<String>();
        for (var e : scopeEntries) {
            existingNames.add(e.name());
        }
        for (var pe : preludeEntries) {
            if (existingNames.add(pe.name())) {
                merged.add(pe);
            }
        }
        return List.copyOf(merged);
    }

    private static List<StvnNamespaceSymbolEntry> applyFilterQuery(
        List<StvnNamespaceSymbolEntry> entries,
        String query
    ) {
        if (query.isEmpty()) {
            return entries;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return entries.stream()
            .filter(e -> e.name().toLowerCase(Locale.ROOT).contains(needle)
                || e.source().toLowerCase(Locale.ROOT).contains(needle)
                || e.upstreamTarget().toLowerCase(Locale.ROOT).contains(needle)
                || e.resolvedType().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    }

    /**
     * Constructs and displays the interactive 3-scope namespace browser popup.
     *
     * @param project the active IntelliJ project
     * @param file    the source STVN file
     * @param scope   the initial section scope resolved from caret or gutter icon
     */
    public static void showPopup(Project project, PsiFile file, StvnNamespaceScope scope) {
        var popupDisposable = Disposer.newDisposable("StvnNamespaceBrowserPopup");
        var popupRef = new JBPopup[1];
        var content = createBrowserContent(
            project,
            file,
            scope,
            popupDisposable,
            updatedTitle -> {
                if (popupRef[0] != null && !popupRef[0].isDisposed()) {
                    popupRef[0].setCaption(updatedTitle);
                }
            }
        );
        var panel = content.panel();
        var table = content.table();
        var tableModel = content.tableModel();

        Runnable navigationRunnable = () -> {
            int visualRow = table.getSelectedRow();
            if (visualRow < 0) {
                return;
            }
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

        KeyAdapter enterNavigationListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    navigationRunnable.run();
                    e.consume();
                }
            }
        };
        table.addKeyListener(enterNavigationListener);
        content.filterField().getTextEditor().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    navigationRunnable.run();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN && table.getRowCount() > 0) {
                    int next = Math.min(table.getRowCount() - 1, Math.max(0, table.getSelectedRow() + 1));
                    table.setRowSelectionInterval(next, next);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_UP && table.getRowCount() > 0) {
                    int prev = Math.max(0, table.getSelectedRow() - 1);
                    table.setRowSelectionInterval(prev, prev);
                    e.consume();
                }
            }
        });

        var popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, table)
            .setTitle(formatPopupTitle(file.getName(), scope))
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup();

        Disposer.register(popup, popupDisposable);
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
        if (row < 0) {
            return;
        }
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
        if (doc == null) {
            return;
        }

        var rawSegment = entry.name().contains("/")
            ? entry.name().substring(entry.name().lastIndexOf('/') + 1)
            : entry.name();
        var cleanSegment = rawSegment.startsWith(StvnVocabulary.SIGIL_VALUE) || rawSegment.startsWith(StvnVocabulary.SIGIL_TYPIC)
            ? rawSegment.substring(1)
            : rawSegment;
        var bareName = (entry.name().startsWith(StvnVocabulary.SIGIL_VALUE) || rawSegment.startsWith(StvnVocabulary.SIGIL_VALUE)
            ? StvnVocabulary.SIGIL_VALUE
            : StvnVocabulary.SIGIL_TYPIC) + cleanSegment;

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
            var openBrace = enclosingPkg.getNode().findChildByType(StvnTypes.LBRACE);
            insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : enclosingPkg.getTextOffset();
            baseIndent = computeElementIndent(doc, enclosingPkg);
        } else {
            var defsEntry = StvnPsiUtils.getOrCreateDefsBlock(sourceFile);
            if (defsEntry != null) {
                var openBrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
                insertOffset = openBrace != null ? openBrace.getStartOffset() + 1 : defsEntry.getTextOffset();
                baseIndent = computeElementIndent(doc, defsEntry);
            } else {
                return;
            }
        }
        var childIndent = baseIndent + "  ";
        var importStatement = "\n" + childIndent + StvnVocabulary.KEYWORD_USE + " "
            + StvnVocabulary.DELIM_OPEN_BRACKET + " " + entry.source() + " "
            + StvnVocabulary.DELIM_OPEN_BRACE + " " + bareName + " " + bareName + " "
            + StvnVocabulary.DELIM_CLOSE_BRACE + " " + StvnVocabulary.DELIM_CLOSE_BRACKET;
        doc.insertString(insertOffset, importStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    private static @Nullable UseElement findExistingUse(PsiElement container, String targetSource) {
        for (var use : PsiTreeUtil.findChildrenOfType(container, UseStmt.class)) {
            if (container instanceof PackageEnclosure pkg) {
                if (PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != pkg) {
                    continue;
                }
            } else if (container instanceof DefsEntry defs) {
                if (PsiTreeUtil.getParentOfType(use, DefsEntry.class) != defs
                        || PsiTreeUtil.getParentOfType(use, PackageEnclosure.class) != null) {
                    continue;
                }
            }
            for (var useElem : use.getUseElementList()) {
                var target = useElem.getUseTarget();
                if (target != null && target.getText().equals(targetSource)) {
                    return useElem;
                }
            }
        }
        return null;
    }

    private static void appendSymbolToExistingUse(
        com.intellij.openapi.editor.Document doc,
        UseElement existingUse,
        String bareName
    ) {
        if (StvnPsiUtils.hasValidStripOption(existingUse.getUseOptionsBlock())) {
            return;
        }
        var aliasBlock = existingUse.getUseAliasBlock();
        var chars = doc.getCharsSequence();
        if (aliasBlock != null) {
            for (var alias : aliasBlock.getUseMapAliasList()) {
                for (var tk : alias.getTypeKeywordList()) {
                    if (bareName.equals(tk.getText())) {
                        return;
                    }
                }
                for (var vk : alias.getValueKeywordList()) {
                    if (bareName.equals(vk.getText())) {
                        return;
                    }
                }
            }
            var rbrace = aliasBlock.getNode().findChildByType(StvnTypes.RBRACE);
            int rbraceOffset = rbrace != null ? rbrace.getStartOffset() : aliasBlock.getTextRange().getEndOffset() - 1;
            if (rbraceOffset > 0 && chars.charAt(rbraceOffset - 1) == ' ') {
                doc.insertString(rbraceOffset - 1, " " + bareName + " " + bareName);
            } else {
                doc.insertString(rbraceOffset, " " + bareName + " " + bareName + " ");
            }
        } else {
            int elemEndOffset = existingUse.getTextRange().getEndOffset();
            doc.insertString(
                elemEndOffset,
                " " + StvnVocabulary.DELIM_OPEN_BRACE + " " + bareName + " " + bareName + " " + StvnVocabulary.DELIM_CLOSE_BRACE
            );
        }
    }

    static boolean isConstantSymbol(StvnNamespaceSymbolEntry entry) {
        var name = entry.name();
        if (name.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return true;
        }
        int lastSlash = name.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash + 1 < name.length() && name.charAt(lastSlash + 1) == '#';
    }

    static String resolveCanonicalFqni(StvnNamespaceSymbolEntry entry) {
        if (!StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH.equals(entry.upstreamTarget())
                && entry.upstreamTarget().contains("/")) {
            return entry.upstreamTarget();
        }
        if (isConstantSymbol(entry)) {
            if (entry.name().contains("/")) {
                var raw = entry.name();
                if (raw.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
                    raw = StvnVocabulary.SIGIL_VALUE + raw.substring(1);
                }
                int lastSlash = raw.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash + 1 < raw.length() && raw.charAt(lastSlash + 1) == '#') {
                    return raw.substring(0, lastSlash + 1) + raw.substring(lastSlash + 2);
                }
                return raw;
            }
            var src = entry.source().startsWith(StvnVocabulary.SIGIL_TYPIC) ? entry.source().substring(1) : entry.source();
            var cleanName = entry.name().startsWith(StvnVocabulary.SIGIL_VALUE) ? entry.name().substring(1) : entry.name();
            return src.contains("/") ? StvnVocabulary.SIGIL_VALUE + src + "/" + cleanName : StvnVocabulary.SIGIL_VALUE + cleanName;
        } else {
            if (entry.name().contains("/")) {
                var raw = entry.name();
                return raw.startsWith(StvnVocabulary.SIGIL_TYPIC) ? raw : StvnVocabulary.SIGIL_TYPIC + raw;
            }
            var src = entry.source().startsWith(StvnVocabulary.SIGIL_TYPIC) ? entry.source().substring(1) : entry.source();
            var cleanName = entry.name().startsWith(StvnVocabulary.SIGIL_TYPIC) ? entry.name().substring(1) : entry.name();
            return src.contains("/") ? StvnVocabulary.SIGIL_TYPIC + src + "/" + cleanName : StvnVocabulary.SIGIL_TYPIC + cleanName;
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
        if (doc == null) {
            return;
        }

        var defsEntry = StvnPsiUtils.getOrCreateDefsBlock(sourceFile);
        if (defsEntry != null) {
            var openBrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
            if (openBrace != null) {
                int insertOffset = openBrace.getStartOffset() + 1;
                var fqni = resolveCanonicalFqni(entry);
                var rawSegment = entry.name().contains("/")
                    ? entry.name().substring(entry.name().lastIndexOf('/') + 1)
                    : entry.name();
                var cleanSegment = rawSegment.startsWith(StvnVocabulary.SIGIL_VALUE) || rawSegment.startsWith(StvnVocabulary.SIGIL_TYPIC)
                    ? rawSegment.substring(1)
                    : rawSegment;
                var bareName = StvnVocabulary.SIGIL_TYPIC + cleanSegment;
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
        if (elemOffset < lineStart) {
            return "  ";
        }
        String prefix = doc.getText(new TextRange(lineStart, elemOffset));
        var sb = new StringBuilder();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == ' ') {
                sb.append(' ');
            } else {
                break;
            }
        }
        return sb.toString();
    }

    static void navigateToSymbol(Project project, PsiFile sourceFile, StvnNamespaceSymbolEntry entry) {
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
