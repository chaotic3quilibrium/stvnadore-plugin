package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
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
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseStmt;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
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

    /** Tooltip displayed on the enabled {@code :defs} scope button. */
    public static final String ENABLED_DEFS_SCOPE_TOOLTIP =
        StvnBundle.message("stvn.browser.scope.defs.enabled");

    /** Tooltip displayed on the enabled {@code :type} scope button. */
    public static final String ENABLED_TYPE_SCOPE_TOOLTIP =
        StvnBundle.message("stvn.browser.scope.type.enabled");

    /** Tooltip displayed on the disabled {@code :type} scope button when {@code :type} is absent. */
    public static final String DISABLED_TYPE_SCOPE_TOOLTIP =
        StvnBundle.message("stvn.browser.scope.type.disabled");

    /** Tooltip displayed on the enabled {@code :body} scope button. */
    public static final String ENABLED_BODY_SCOPE_TOOLTIP =
        StvnBundle.message("stvn.browser.scope.body.enabled");

    /** Tooltip displayed on the disabled {@code :body} scope button when {@code :body} is absent. */
    public static final String DISABLED_BODY_SCOPE_TOOLTIP =
        StvnBundle.message("stvn.browser.scope.body.disabled");

    private StvnNamespaceBrowserPopup() {
    }

    /**
     * Encapsulates the constructed Swing components, live scope buttons, filter input,
     * column manager, popup-scoped shortcuts, and async snapshot future.
     *
     * @param panel                outer popup container panel
     * @param table                5-column symbol table
     * @param showPreludeCheckBox  prelude toggle checkbox
     * @param filterField          live symbol filter field
     * @param scopeButtons         radio buttons keyed by {@link StvnNamespaceScope}
     * @param activeScopeRef       mutable reference tracking the currently selected scope
     * @param tableModel           underlying 5-column {@link StvnNamespaceTableModel}
     * @param fileLabel            Row 1 {@code File: <filename>} label carrying relative-path tooltip
     * @param headerLabel          Row 2 dynamic scope and count summary label
     * @param columnManager        spreadsheet column sizing and width-lock manager
     * @param scopeShortcutActions popup-scoped {@code Alt+1..3} {@link AnAction}s keyed by scope
     * @param popupTitle           static window caption ({@code STVN Namespace Dependencies})
     * @param loadingPanel         asynchronous loading wrapper panel
     * @param analysisFuture       completion future resolving to {@link StvnNamespaceSymbolCollector.StvnNamespaceSnapshot}
     */
    record BrowserContent(
        JPanel panel,
        JBTable table,
        JBCheckBox showPreludeCheckBox,
        SearchTextField filterField,
        Map<StvnNamespaceScope, JRadioButton> scopeButtons,
        AtomicReference<StvnNamespaceScope> activeScopeRef,
        StvnNamespaceTableModel tableModel,
        JBLabel fileLabel,
        JBLabel headerLabel,
        StvnNamespaceColumnManager columnManager,
        Map<StvnNamespaceScope, AnAction> scopeShortcutActions,
        String popupTitle,
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
        return createBrowserContent(project, file, scope, Disposer.newDisposable());
    }

    static BrowserContent createBrowserContent(
        Project project,
        PsiFile file,
        StvnNamespaceScope initialScope,
        Disposable parentDisposable
    ) {
        var tableModel = new StvnNamespaceTableModel(new ArrayList<>());
        var columnManagerHolder = new StvnNamespaceColumnManager[1];
        var table = new JBTable(tableModel) {
            @Override
            public @Nullable String getToolTipText(@NotNull MouseEvent event) {
                var mgr = columnManagerHolder[0];
                return mgr != null ? mgr.resolveTruncatedCellTooltip(event) : super.getToolTipText(event);
            }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        StvnPrioritizedTableSpeedSearch.installOn(table);

        var scrollPane = new JBScrollPane(table);
        var columnManager = new StvnNamespaceColumnManager(table, scrollPane, tableModel);
        columnManagerHolder[0] = columnManager;

        var panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(JBUI.scale(920), JBUI.scale(440)));

        var activeScopeRef = new AtomicReference<>(initialScope);
        var snapshotRef = new AtomicReference<StvnNamespaceSymbolCollector.@Nullable StvnNamespaceSnapshot>(null);

        var northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));

        // Row 1: Left-aligned "File: <filename>" (with relative-path tooltip) + right-aligned Prelude checkbox
        var fileRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        var fileLabel = new JBLabel(StvnBundle.message("stvn.browser.header.file", file.getName()));
        fileLabel.setToolTipText(resolveFileTooltipPath(project, file));
        fileRow.add(fileLabel, BorderLayout.WEST);

        var showPreludeCheckBox = new JBCheckBox(StvnBundle.message("stvn.browser.prelude.checkbox"), false);
        showPreludeCheckBox.setEnabled(false);
        fileRow.add(showPreludeCheckBox, BorderLayout.EAST);
        northPanel.add(fileRow);
        northPanel.add(Box.createVerticalStrut(JBUI.scale(4)));

        // Row 2: Dynamic "Scope: <scope> — <scopeTitle> (<count> symbols...)" summary directly under File:
        var summaryRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        var headerLabel = new JBLabel(
            StvnBundle.message(
                "stvn.browser.header.scope.analyzing",
                initialScope.getSectionKeyword(),
                initialScope.getDisplayTitle()
            )
        );
        summaryRow.add(headerLabel, BorderLayout.WEST);
        northPanel.add(summaryRow);
        northPanel.add(Box.createVerticalStrut(JBUI.scale(4)));

        // Row 3: "Scope:" + clean :defs / :type / :body radio buttons (no mnemonic suffix) + live SearchTextField
        var controlsRow = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        var scopeSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        scopeSelectorPanel.add(new JBLabel(StvnBundle.message("stvn.browser.scope.label")));

        var scopeGroup = new ButtonGroup();
        Map<StvnNamespaceScope, JRadioButton> scopeButtons = new EnumMap<>(StvnNamespaceScope.class);
        for (StvnNamespaceScope candidateScope : StvnNamespaceScope.values()) {
            var radio = new JBRadioButton(candidateScope.getSectionKeyword(), candidateScope == initialScope);
            radio.setEnabled(false);
            radio.setToolTipText(resolveEnabledScopeTooltip(candidateScope));
            scopeGroup.add(radio);
            scopeButtons.put(candidateScope, radio);
            scopeSelectorPanel.add(radio);
        }
        controlsRow.add(scopeSelectorPanel, BorderLayout.WEST);

        var filterField = new SearchTextField(false);
        filterField.getTextEditor().getEmptyText().setText(StvnBundle.message("stvn.browser.filter.placeholder"));
        filterField.setEnabled(false);
        controlsRow.add(filterField, BorderLayout.CENTER);

        northPanel.add(controlsRow);
        panel.add(northPanel, BorderLayout.NORTH);

        var loadingPanel = new JBLoadingPanel(new BorderLayout(), parentDisposable);
        loadingPanel.add(scrollPane, BorderLayout.CENTER);
        loadingPanel.setLoadingText(StvnBundle.message("stvn.browser.loading.text", initialScope.getSectionKeyword()));
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

            tableModel.updateView(filtered);
            if (!filtered.isEmpty()) {
                table.setRowSelectionInterval(0, 0);
            }
            headerLabel.setText(formatScopeSummaryText(currentScope, filtered.size(), combined.size(), showPrelude, !filterText.isEmpty()));
        };

        Consumer<StvnNamespaceScope> trySwitchScope = targetScope -> {
            JRadioButton btn = scopeButtons.get(targetScope);
            if (btn == null || !btn.isEnabled()) {
                return;
            }
            if (activeScopeRef.get() == targetScope && btn.isSelected()) {
                return;
            }
            btn.setSelected(true);
            activeScopeRef.set(targetScope);
            refreshView.run();
        };

        for (var entry : scopeButtons.entrySet()) {
            StvnNamespaceScope buttonScope = entry.getKey();
            JRadioButton radio = entry.getValue();
            radio.addActionListener(e -> {
                if (radio.isSelected() && radio.isEnabled()) {
                    if (activeScopeRef.getAndSet(buttonScope) != buttonScope) {
                        refreshView.run();
                    } else {
                        refreshView.run();
                    }
                }
            });
        }

        Map<StvnNamespaceScope, AnAction> scopeShortcutActions = new EnumMap<>(StvnNamespaceScope.class);
        scopeShortcutActions.put(
            StvnNamespaceScope.DEFS,
            registerScopeShortcut(panel, parentDisposable, KeyEvent.VK_1, StvnNamespaceScope.DEFS, scopeButtons, trySwitchScope)
        );
        scopeShortcutActions.put(
            StvnNamespaceScope.TYPE,
            registerScopeShortcut(panel, parentDisposable, KeyEvent.VK_2, StvnNamespaceScope.TYPE, scopeButtons, trySwitchScope)
        );
        scopeShortcutActions.put(
            StvnNamespaceScope.BODY,
            registerScopeShortcut(panel, parentDisposable, KeyEvent.VK_3, StvnNamespaceScope.BODY, scopeButtons, trySwitchScope)
        );

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
            defsBtn.setToolTipText(ENABLED_DEFS_SCOPE_TOOLTIP);

            var typeBtn = scopeButtons.get(StvnNamespaceScope.TYPE);
            typeBtn.setEnabled(snapshot.hasTypeSection());
            typeBtn.setToolTipText(snapshot.hasTypeSection()
                ? ENABLED_TYPE_SCOPE_TOOLTIP
                : DISABLED_TYPE_SCOPE_TOOLTIP);

            var bodyBtn = scopeButtons.get(StvnNamespaceScope.BODY);
            bodyBtn.setEnabled(snapshot.hasBodySection());
            bodyBtn.setToolTipText(snapshot.hasBodySection()
                ? ENABLED_BODY_SCOPE_TOOLTIP
                : DISABLED_BODY_SCOPE_TOOLTIP);

            scopeButtons.get(resolvedScope).setSelected(true);
            showPreludeCheckBox.setEnabled(true);
            filterField.setEnabled(true);

            refreshView.run();
            columnManager.applyInitialContentWidthsIfUnmodified();
            columnManager.markInitialSnapshotComplete();
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
            fileLabel,
            headerLabel,
            columnManager,
            Map.copyOf(scopeShortcutActions),
            resolvePopupTitle(),
            loadingPanel,
            analysisFuture
        );
    }

    static String resolvePopupTitle() {
        return StvnBundle.message("stvn.browser.title");
    }

    static String resolveFileTooltipPath(Project project, PsiFile file) {
        return ReadAction.compute(() -> {
            PsiFile originFile = file.getOriginalFile();
            VirtualFile vf = originFile.getVirtualFile() != null
                ? originFile.getVirtualFile()
                : file.getVirtualFile();
            if (vf == null) {
                return file.getName();
            }
            String vfPath = FileUtil.toSystemIndependentName(vf.getPath());
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isEmpty()) {
                String normBase = FileUtil.toSystemIndependentName(basePath);
                if (normBase.endsWith("/")) {
                    normBase = normBase.substring(0, normBase.length() - 1);
                }
                if (vfPath.startsWith(normBase + "/")) {
                    return vfPath.substring(normBase.length() + 1);
                }
            }
            if (!project.isDisposed()) {
                VirtualFile contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(vf);
                if (contentRoot != null) {
                    String rel = VfsUtilCore.getRelativePath(vf, contentRoot, '/');
                    if (rel != null && !rel.isEmpty()) {
                        return rel;
                    }
                }
            }
            return vfPath;
        });
    }

    private static String resolveEnabledScopeTooltip(StvnNamespaceScope scope) {
        return switch (scope) {
            case DEFS -> ENABLED_DEFS_SCOPE_TOOLTIP;
            case TYPE -> ENABLED_TYPE_SCOPE_TOOLTIP;
            case BODY -> ENABLED_BODY_SCOPE_TOOLTIP;
        };
    }

    private static AnAction registerScopeShortcut(
        JPanel rootPanel,
        Disposable parentDisposable,
        int keyCode,
        StvnNamespaceScope targetScope,
        Map<StvnNamespaceScope, JRadioButton> scopeButtons,
        Consumer<StvnNamespaceScope> trySwitchScope
    ) {
        KeyStroke keyStroke = KeyStroke.getKeyStroke(keyCode, InputEvent.ALT_DOWN_MASK);
        var action = new DumbAwareAction() {
            @Override
            public void update(@NotNull AnActionEvent e) {
                // Keep enabled inside popup so disabled Alt+2/Alt+3 do not leak to IDE tool windows
                e.getPresentation().setEnabled(true);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                JRadioButton btn = scopeButtons.get(targetScope);
                if (btn == null || !btn.isEnabled()) {
                    return;
                }
                trySwitchScope.accept(targetScope);
            }
        };
        action.registerCustomShortcutSet(new CustomShortcutSet(keyStroke), rootPanel, parentDisposable);

        String actionKey = "stvn.browser.scope." + targetScope.name();
        rootPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionKey);
        rootPanel.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JRadioButton btn = scopeButtons.get(targetScope);
                if (btn != null && btn.isEnabled()) {
                    trySwitchScope.accept(targetScope);
                }
            }
        });
        return action;
    }

    private static String formatScopeSummaryText(
        StvnNamespaceScope scope,
        int visibleCount,
        int totalInScopeCount,
        boolean preludeEnabled,
        boolean filterActive
    ) {
        String countPart = filterActive
            ? StvnBundle.message(
                "stvn.browser.header.symbols.filtered",
                Integer.toString(visibleCount),
                Integer.toString(totalInScopeCount)
            )
            : StvnBundle.message("stvn.browser.header.symbols.total", Integer.toString(visibleCount));
        String preludePart = preludeEnabled ? StvnBundle.message("stvn.browser.header.prelude.suffix") : "";
        return StvnBundle.message(
            "stvn.browser.header.scope.summary",
            scope.getSectionKeyword(),
            scope.getDisplayTitle(),
            countPart,
            preludePart
        );
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
        var content = createBrowserContent(project, file, scope, popupDisposable);
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
            .setTitle(content.popupTitle())
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
        var itemUse = new JMenuItem(StvnBundle.message("stvn.browser.row.menu.insertUse"));
        itemUse.addActionListener(ev -> WriteCommandAction.runWriteCommandAction(project, () ->
            insertUseReferenceInEditor(project, sourceFile, entry)
        ));
        popupMenu.add(itemUse);

        var itemCopyFqni = new JMenuItem(StvnBundle.message("stvn.browser.row.menu.copyFqni"));
        itemCopyFqni.addActionListener(ev -> copyFqniToClipboard(project, entry));
        popupMenu.add(itemCopyFqni);

        if (!isConstantSymbol(entry)) {
            var itemBrand = new JMenuItem(StvnBundle.message("stvn.browser.row.menu.brandNominal"));
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
            statusBar.setInfo(StvnBundle.message("stvn.browser.status.copiedFqni", fqni));
        }
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {
            HintManager.getInstance().showInformationHint(editor, StvnBundle.message("stvn.browser.hint.copiedFqni", fqni));
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
