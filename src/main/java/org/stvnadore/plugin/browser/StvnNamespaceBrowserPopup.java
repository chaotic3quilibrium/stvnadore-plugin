package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
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
import com.intellij.util.ui.JBFont;
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
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Tooltip displayed on the live symbol filter field and its inner text editor. */
    public static final String FILTER_FIELD_TOOLTIP =
        StvnBundle.message("stvn.browser.filter.tooltip");

    private StvnNamespaceBrowserPopup() {
    }

    /**
     * Encapsulates the constructed Swing components, live scope buttons, filter input,
     * column manager, popup-scoped shortcuts, navigation callback, and async snapshot future.
     *
     * @param panel                    outer popup container panel
     * @param table                    5-column symbol table
     * @param showPreludeCheckBox      prelude toggle checkbox
     * @param filterField              live symbol filter field
     * @param scopeButtons             radio buttons keyed by {@link StvnNamespaceScope}
     * @param activeScopeRef           mutable reference tracking the currently selected scope
     * @param tableModel               underlying 5-column {@link StvnNamespaceTableModel}
     * @param filePrefixLabel          Row 1 bold {@code File:} prefix label aligned at {@code x = 0}
     * @param fileLabel                Row 1 plain {@code <filename>} value label carrying relative-path tooltip
     * @param scopeSummaryPrefixLabel  Row 2 bold {@code Scope:} prefix label aligned at {@code x = 0}
     * @param headerLabel              Row 2 plain dynamic scope and count summary value label
     * @param scopeSelectorPrefixLabel Row 3 bold {@code Scope:} prefix label aligned at {@code x = 0}
     * @param columnManager            spreadsheet column sizing and width-lock manager
     * @param scopeShortcutActions     popup-scoped {@code Alt+1..3} {@link AnAction}s keyed by scope
     * @param filterFocusAction        popup-scoped {@code Alt+F} / {@code Ctrl+F} / {@code Meta+F} filter focus action
     * @param rightSizeAllUnlockedAction popup-scoped {@code Alt+R} action right-sizing all unlocked columns
     * @param navigationHandlerRef     mutable callback invoked on {@code Enter} from {@code table} or {@code filterField}
     * @param popupTitle               static window caption ({@code STVN Namespace Dependencies})
     * @param loadingPanel             asynchronous loading wrapper panel
     * @param analysisFuture           completion future resolving to {@link StvnNamespaceSymbolCollector.StvnNamespaceSnapshot}
     */
    public record BrowserContent(
        JPanel panel,
        JBTable table,
        JBCheckBox showPreludeCheckBox,
        SearchTextField filterField,
        Map<StvnNamespaceScope, JRadioButton> scopeButtons,
        AtomicReference<StvnNamespaceScope> activeScopeRef,
        StvnNamespaceTableModel tableModel,
        JBLabel filePrefixLabel,
        JBLabel fileLabel,
        JBLabel scopeSummaryPrefixLabel,
        JBLabel headerLabel,
        JBLabel scopeSelectorPrefixLabel,
        StvnNamespaceColumnManager columnManager,
        Map<StvnNamespaceScope, AnAction> scopeShortcutActions,
        AnAction filterFocusAction,
        AnAction rightSizeAllUnlockedAction,
        AtomicReference<Runnable> navigationHandlerRef,
        String popupTitle,
        JBLoadingPanel loadingPanel,
        CompletableFuture<StvnNamespaceSymbolCollector.StvnNamespaceSnapshot> analysisFuture
    ) {
        /**
         * Returns the combined plain-text representation of Row 1 ({@code "File: filename"}).
         *
         * @return combined Row 1 file header text
         */
        public String fullFileHeaderText() {
            return filePrefixLabel.getText() + " " + fileLabel.getText();
        }

        /**
         * Returns the combined plain-text representation of Row 2 ({@code "Scope: summary"}).
         *
         * @return combined Row 2 scope summary text
         */
        public String fullScopeSummaryText() {
            return scopeSummaryPrefixLabel.getText() + " " + headerLabel.getText();
        }

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

    /**
     * Constructs the interactive {@code STVN Namespace Dependencies} browser UI model with a fresh disposable.
     *
     * @param project active IntelliJ project
     * @param file    source STVN file under inspection
     * @param scope   requested initial scope
     * @return constructed {@link BrowserContent} UI bundle
     */
    public static BrowserContent createBrowserContent(Project project, PsiFile file, StvnNamespaceScope scope) {
        return createBrowserContent(project, file, scope, Disposer.newDisposable());
    }

    /**
     * Constructs the interactive {@code STVN Namespace Dependencies} browser UI model bound to {@code parentDisposable}.
     *
     * @param project          active IntelliJ project
     * @param file             source STVN file under inspection
     * @param initialScope     requested initial scope
     * @param parentDisposable disposable governing popup-scoped actions and background read actions
     * @return constructed {@link BrowserContent} UI bundle
     */
    public static BrowserContent createBrowserContent(
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
        // Intentional: do NOT call StvnPrioritizedTableSpeedSearch.installOn(table) here so typing on JBTable
        // routes exclusively into filterField without opening a competing floating TableSpeedSearch bubble.

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
        northPanel.setBorder(JBUI.Borders.empty());

        // Row 1: Flush-left bold "File:" + plain "<filename>" (with relative-path tooltip) + right-aligned Prelude checkbox
        var fileRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        fileRow.setBorder(JBUI.Borders.empty());
        fileRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        var fileHeaderPanel = new JPanel();
        fileHeaderPanel.setLayout(new BoxLayout(fileHeaderPanel, BoxLayout.X_AXIS));
        fileHeaderPanel.setBorder(JBUI.Borders.empty());
        fileHeaderPanel.setOpaque(false);

        String fileTooltipPath = resolveFileTooltipPath(project, file);
        var filePrefixLabel = createBoldPrefixLabel(StvnBundle.message("stvn.browser.file.label"));
        var fileLabel = createPlainHeaderValueLabel(file.getName());
        filePrefixLabel.setToolTipText(fileTooltipPath);
        fileLabel.setToolTipText(fileTooltipPath);
        fileHeaderPanel.setToolTipText(fileTooltipPath);
        fileHeaderPanel.add(filePrefixLabel);
        fileHeaderPanel.add(Box.createHorizontalStrut(JBUI.scale(4)));
        fileHeaderPanel.add(fileLabel);
        fileRow.add(fileHeaderPanel, BorderLayout.WEST);

        var showPreludeCheckBox = new JBCheckBox(StvnBundle.message("stvn.browser.prelude.checkbox"), false);
        showPreludeCheckBox.setFont(JBFont.label().asPlain());
        showPreludeCheckBox.setEnabled(false);
        fileRow.add(showPreludeCheckBox, BorderLayout.EAST);
        northPanel.add(fileRow);
        Component strut1 = Box.createVerticalStrut(JBUI.scale(4));
        if (strut1 instanceof JComponent jc1) {
            jc1.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        northPanel.add(strut1);

        // Row 2: Flush-left bold "Scope:" + plain "<scope> — <scopeTitle> (<count> symbols...)" directly under File:
        var summaryRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        summaryRow.setBorder(JBUI.Borders.empty());
        summaryRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        var scopeSummaryPanel = new JPanel();
        scopeSummaryPanel.setLayout(new BoxLayout(scopeSummaryPanel, BoxLayout.X_AXIS));
        scopeSummaryPanel.setBorder(JBUI.Borders.empty());
        scopeSummaryPanel.setOpaque(false);

        var scopeSummaryPrefixLabel = createBoldPrefixLabel(StvnBundle.message("stvn.browser.scope.label"));
        var headerLabel = createPlainHeaderValueLabel(
            StvnBundle.message(
                "stvn.browser.header.scope.analyzing.value",
                initialScope.getSectionKeyword(),
                initialScope.getDisplayTitle()
            )
        );
        scopeSummaryPanel.add(scopeSummaryPrefixLabel);
        scopeSummaryPanel.add(Box.createHorizontalStrut(JBUI.scale(4)));
        scopeSummaryPanel.add(headerLabel);
        summaryRow.add(scopeSummaryPanel, BorderLayout.WEST);
        northPanel.add(summaryRow);
        Component strut2 = Box.createVerticalStrut(JBUI.scale(4));
        if (strut2 instanceof JComponent jc2) {
            jc2.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        northPanel.add(strut2);

        // Row 3: Flush-left bold "Scope:" + plain :defs / :type / :body radios (zero leading inset) + live SearchTextField
        var controlsRow = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        controlsRow.setBorder(JBUI.Borders.empty());
        controlsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        var scopeSelectorPanel = new JPanel();
        scopeSelectorPanel.setLayout(new BoxLayout(scopeSelectorPanel, BoxLayout.X_AXIS));
        scopeSelectorPanel.setBorder(JBUI.Borders.empty());
        scopeSelectorPanel.setOpaque(false);

        var scopeSelectorPrefixLabel = createBoldPrefixLabel(StvnBundle.message("stvn.browser.scope.label"));
        scopeSelectorPanel.add(scopeSelectorPrefixLabel);

        var scopeGroup = new ButtonGroup();
        Map<StvnNamespaceScope, JRadioButton> scopeButtons = new EnumMap<>(StvnNamespaceScope.class);
        for (StvnNamespaceScope candidateScope : StvnNamespaceScope.values()) {
            scopeSelectorPanel.add(Box.createHorizontalStrut(JBUI.scale(6)));
            var radio = new JBRadioButton(candidateScope.getSectionKeyword(), candidateScope == initialScope);
            radio.setFont(JBFont.label().asPlain());
            radio.setAlignmentY(Component.CENTER_ALIGNMENT);
            radio.setEnabled(false);
            radio.setToolTipText(resolveEnabledScopeTooltip(candidateScope));
            scopeGroup.add(radio);
            scopeButtons.put(candidateScope, radio);
            scopeSelectorPanel.add(radio);
        }
        controlsRow.add(scopeSelectorPanel, BorderLayout.WEST);

        var filterField = new SearchTextField(false);
        filterField.getTextEditor().getEmptyText().setText(StvnBundle.message("stvn.browser.filter.placeholder"));
        filterField.setToolTipText(FILTER_FIELD_TOOLTIP);
        filterField.getTextEditor().setToolTipText(FILTER_FIELD_TOOLTIP);
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
                int targetViewRow = !filterText.isEmpty() ? table.convertRowIndexToView(0) : 0;
                if (targetViewRow < 0 || targetViewRow >= table.getRowCount()) {
                    targetViewRow = 0;
                }
                selectAndScrollTableRow(table, targetViewRow);
            }
            headerLabel.setText(formatScopeSummaryValueText(
                currentScope, filtered.size(), combined.size(), showPrelude, !filterText.isEmpty()
            ));
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
                    activeScopeRef.set(buttonScope);
                    refreshView.run();
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

        AnAction filterFocusAction = registerFilterFocusShortcut(panel, parentDisposable, filterField);
        AnAction rightSizeAllUnlockedAction = registerRightSizeAllUnlockedShortcut(
            panel,
            parentDisposable,
            snapshotRef,
            columnManager
        );

        AtomicReference<Runnable> navigationHandlerRef = new AtomicReference<>(() -> {
            int visualRow = table.getSelectedRow();
            if (visualRow < 0) {
                return;
            }
            int modelRow = table.convertRowIndexToModel(visualRow);
            var entry = tableModel.getItem(modelRow);
            navigateToSymbol(project, file, entry);
        });

        installTypeAnywhereAndNavigationListeners(
            panel,
            table,
            scopeButtons,
            showPreludeCheckBox,
            filterField,
            navigationHandlerRef
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
            filePrefixLabel,
            fileLabel,
            scopeSummaryPrefixLabel,
            headerLabel,
            scopeSelectorPrefixLabel,
            columnManager,
            Map.copyOf(scopeShortcutActions),
            filterFocusAction,
            rightSizeAllUnlockedAction,
            navigationHandlerRef,
            resolvePopupTitle(),
            loadingPanel,
            analysisFuture
        );
    }

    private static JBLabel createBoldPrefixLabel(String text) {
        var label = new JBLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setFont(JBFont.label().asBold());
        label.setBorder(JBUI.Borders.empty());
        label.setAlignmentY(Component.CENTER_ALIGNMENT);
        label.setText(text);
        return label;
    }

    private static JBLabel createPlainHeaderValueLabel(String text) {
        var label = new JBLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setFont(JBFont.label().asPlain());
        label.setBorder(JBUI.Borders.empty());
        label.setAlignmentY(Component.CENTER_ALIGNMENT);
        label.setText(text);
        return label;
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

    private static AnAction registerFilterFocusShortcut(
        JPanel rootPanel,
        Disposable parentDisposable,
        SearchTextField filterField
    ) {
        KeyStroke altF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.ALT_DOWN_MASK);
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
        KeyStroke metaF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.META_DOWN_MASK);

        var action = new DumbAwareAction() {
            @Override
            public void update(@NotNull AnActionEvent e) {
                // Keep enabled inside popup so Alt+F / Ctrl+F / Cmd+F never leak to IDE File menu or editor Find bar
                e.getPresentation().setEnabled(true);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                focusAndSelectFilterField(filterField);
            }
        };
        action.registerCustomShortcutSet(
            new CustomShortcutSet(
                new KeyboardShortcut(altF, null),
                new KeyboardShortcut(ctrlF, null),
                new KeyboardShortcut(metaF, null)
            ),
            rootPanel,
            parentDisposable
        );

        String actionKey = "stvn.browser.filter.focus";
        var inputMap = rootPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        inputMap.put(altF, actionKey);
        inputMap.put(ctrlF, actionKey);
        inputMap.put(metaF, actionKey);
        rootPanel.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                focusAndSelectFilterField(filterField);
            }
        });
        return action;
    }

    private static AnAction registerRightSizeAllUnlockedShortcut(
        JPanel rootPanel,
        Disposable parentDisposable,
        AtomicReference<StvnNamespaceSymbolCollector.StvnNamespaceSnapshot> snapshotRef,
        StvnNamespaceColumnManager columnManager
    ) {
        KeyStroke altR = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK);
        Runnable tryRightSizeAllUnlocked = () -> {
            if (snapshotRef.get() == null || !columnManager.hasAnyUnlockedColumn()) {
                return;
            }
            columnManager.rightSizeAllUnlockedColumns();
        };

        var action = new DumbAwareAction() {
            @Override
            public void update(@NotNull AnActionEvent e) {
                // Keep enabled inside popup so Alt+R never leaks to IntelliJ's top-level Run menu
                e.getPresentation().setEnabled(true);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                tryRightSizeAllUnlocked.run();
            }
        };
        action.registerCustomShortcutSet(new CustomShortcutSet(altR), rootPanel, parentDisposable);

        String actionKey = "stvn.browser.columns.rightSizeAllUnlocked";
        rootPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(altR, actionKey);
        rootPanel.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                tryRightSizeAllUnlocked.run();
            }
        });
        return action;
    }

    static void focusAndSelectFilterField(SearchTextField filterField) {
        if (!filterField.isEnabled()) {
            return;
        }
        var editor = filterField.getTextEditor();
        IdeFocusManager.getGlobalInstance().requestFocus(editor, true);
        editor.requestFocusInWindow();
        editor.selectAll();
    }

    static boolean isStvnTypeAnywhereFilterChar(char ch) {
        return (ch >= 'A' && ch <= 'Z')
            || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9')
            || ch == StvnVocabulary.SIGIL_TYPIC.charAt(0)
            || ch == StvnVocabulary.SIGIL_VALUE.charAt(0)
            || ch == '/'
            || ch == '_'
            || ch == '.'
            || ch == '-';
    }

    private static boolean hasNoCommandModifiers(KeyEvent e) {
        int forbidden = InputEvent.CTRL_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK
            | InputEvent.META_DOWN_MASK
            | InputEvent.ALT_GRAPH_DOWN_MASK;
        return (e.getModifiersEx() & forbidden) == 0;
    }

    private static void installTypeAnywhereAndNavigationListeners(
        JPanel rootPanel,
        JBTable table,
        Map<StvnNamespaceScope, JRadioButton> scopeButtons,
        JBCheckBox showPreludeCheckBox,
        SearchTextField filterField,
        AtomicReference<Runnable> navigationHandlerRef
    ) {
        var editor = filterField.getTextEditor();

        KeyAdapter nonEditorListener = new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.isConsumed() || !filterField.isEnabled() || e.getComponent() == editor) {
                    return;
                }
                if (!hasNoCommandModifiers(e)) {
                    return;
                }
                char ch = e.getKeyChar();
                if (!isStvnTypeAnywhereFilterChar(ch)) {
                    return;
                }
                IdeFocusManager.getGlobalInstance().requestFocus(editor, true);
                editor.requestFocusInWindow();
                if (editor.getSelectionStart() == editor.getSelectionEnd()) {
                    editor.setCaretPosition(editor.getDocument().getLength());
                }
                editor.replaceSelection(String.valueOf(ch));
                e.consume();
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isConsumed() || e.getComponent() != table) {
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ENTER && hasNoCommandModifiers(e)) {
                    navigationHandlerRef.get().run();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE
                        && hasNoCommandModifiers(e)
                        && filterField.isEnabled()
                        && !filterField.getText().isEmpty()) {
                    IdeFocusManager.getGlobalInstance().requestFocus(editor, true);
                    editor.requestFocusInWindow();
                    if (editor.getSelectionStart() < editor.getSelectionEnd()) {
                        editor.replaceSelection("");
                    } else {
                        int len = editor.getDocument().getLength();
                        if (len > 0) {
                            try {
                                editor.getDocument().remove(len - 1, 1);
                                editor.setCaretPosition(editor.getDocument().getLength());
                            } catch (BadLocationException ignored) {
                            }
                        }
                    }
                    e.consume();
                }
            }
        };

        table.addKeyListener(nonEditorListener);
        for (JRadioButton radio : scopeButtons.values()) {
            radio.addKeyListener(nonEditorListener);
        }
        showPreludeCheckBox.addKeyListener(nonEditorListener);
        rootPanel.addKeyListener(nonEditorListener);

        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if ((e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0) {
                    char ch = e.getKeyChar();
                    if (ch == 'r' || ch == 'R' || ch == '\u00AE') {
                        e.consume();
                    }
                }
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (!e.isConsumed()
                        && (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0
                        && e.getKeyCode() == KeyEvent.VK_R) {
                    return;
                }
                if (e.isConsumed() || !hasNoCommandModifiers(e)) {
                    return;
                }
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ENTER) {
                    navigationHandlerRef.get().run();
                    e.consume();
                    return;
                }
                int rowCount = table.getRowCount();
                if (rowCount <= 0) {
                    return;
                }
                int current = table.getSelectedRow();
                if (code == KeyEvent.VK_DOWN) {
                    int next = current < 0 ? 0 : Math.min(rowCount - 1, current + 1);
                    selectAndScrollTableRow(table, next);
                    e.consume();
                } else if (code == KeyEvent.VK_UP) {
                    int prev = current < 0 ? 0 : Math.max(0, current - 1);
                    selectAndScrollTableRow(table, prev);
                    e.consume();
                } else if (code == KeyEvent.VK_PAGE_DOWN) {
                    int step = computeTablePageStep(table);
                    int next = current < 0 ? 0 : Math.min(rowCount - 1, current + step);
                    selectAndScrollTableRow(table, next);
                    e.consume();
                } else if (code == KeyEvent.VK_PAGE_UP) {
                    int step = computeTablePageStep(table);
                    int prev = current < 0 ? 0 : Math.max(0, current - step);
                    selectAndScrollTableRow(table, prev);
                    e.consume();
                }
            }
        });
    }

    private static void selectAndScrollTableRow(JBTable table, int visualRow) {
        if (visualRow < 0 || visualRow >= table.getRowCount()) {
            return;
        }
        table.setRowSelectionInterval(visualRow, visualRow);
        table.scrollRectToVisible(table.getCellRect(visualRow, 0, true));
    }

    private static int computeTablePageStep(JBTable table) {
        int rowHeight = Math.max(1, table.getRowHeight());
        int visibleHeight = table.getVisibleRect().height;
        if (visibleHeight <= 0 && table.getParent() != null) {
            visibleHeight = table.getParent().getHeight();
        }
        int visibleRows = visibleHeight > 0 ? (visibleHeight / rowHeight) : 10;
        return Math.max(1, visibleRows - 1);
    }

    private static String formatScopeSummaryValueText(
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
            "stvn.browser.header.scope.summary.value",
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

    static List<StvnNamespaceSymbolEntry> applyFilterQuery(
        List<StvnNamespaceSymbolEntry> entries,
        String query
    ) {
        if (query.isEmpty()) {
            return entries;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        record RankedEntry(StvnNamespaceSymbolEntry entry, int priority, int originalIndex) {}
        List<RankedEntry> matches = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            StvnNamespaceSymbolEntry entry = entries.get(i);
            int priority = computeFilterMatchPriority(entry, needle);
            if (priority >= 0) {
                matches.add(new RankedEntry(entry, priority, i));
            }
        }
        matches.sort(Comparator.comparingInt(RankedEntry::priority).thenComparingInt(RankedEntry::originalIndex));
        return matches.stream().map(RankedEntry::entry).toList();
    }

    private static int computeFilterMatchPriority(StvnNamespaceSymbolEntry entry, String needle) {
        String nameLower = entry.name().toLowerCase(Locale.ROOT);
        String bareNameLower = stripLeadingSigil(nameLower);
        if (nameLower.equals(needle) || bareNameLower.equals(needle)) {
            return 0;
        }
        if (nameLower.startsWith(needle) || bareNameLower.startsWith(needle)) {
            return 1;
        }
        if (nameLower.contains(needle)) {
            return 2;
        }
        if (!StvnNamespaceSymbolEntry.LOCAL_UPSTREAM_DASH.equals(entry.upstreamTarget())
                && entry.upstreamTarget().toLowerCase(Locale.ROOT).contains(needle)) {
            return 3;
        }
        if (entry.source().toLowerCase(Locale.ROOT).contains(needle)) {
            return 4;
        }
        if (entry.resolvedType().toLowerCase(Locale.ROOT).contains(needle)) {
            return 5;
        }
        return -1;
    }

    private static String stripLeadingSigil(String text) {
        if (text.startsWith(StvnVocabulary.SIGIL_TYPIC) || text.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return text.substring(1);
        }
        return text;
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
        content.navigationHandlerRef().set(navigationRunnable);

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
