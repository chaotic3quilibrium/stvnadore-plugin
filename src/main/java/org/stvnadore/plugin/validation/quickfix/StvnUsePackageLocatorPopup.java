package org.stvnadore.plugin.validation.quickfix;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.browser.StvnPrioritizedTableSpeedSearch;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.validation.StvnFlatDocumentIncludeInspection;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedUsePackageQuickFixProvider.DeclaredPackageInfo;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedUsePackageQuickFixProvider.WorkspaceModuleCandidate;
import org.stvnadore.psi.UseTarget;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Interactive searchable package locator popup for unresolved {@code :use} package targets
 * across modular ({@code .stvn_i} / {@code .stvn_di}, {@code E030}) and hermetic
 * ({@code .stvn} / {@code .stvn_d}, {@code E035}) compilation units.
 */
@NullMarked
public final class StvnUsePackageLocatorPopup {

    private static final int MAX_PREVIEW_SYMBOLS = 4;

    private static volatile @Nullable Function<List<PackageCandidateEntry>, @Nullable LocatorTestSelection>
        testSelectionOverride;

    private StvnUsePackageLocatorPopup() {
    }

    /**
     * Provenance category for an indexed {@code :package} candidate.
     */
    public enum OriginKind {
        /** Standard Library Prelude ({@code :org/stvnadore/prelude} or {@code :org/stvnadore/prelude/meta}). */
        PRELUDE,
        /** Co-located {@code :package} or qualified prefix declared in the active file's {@code :defs}. */
        LOCAL_DEFS,
        /** Package exported by a module already {@code :include}d (directly or transitively) by the active file. */
        INCLUDED_MODULE,
        /** Package exported by an un-included workspace {@code .stvn_d} or {@code .stvn_di} module. */
        WORKSPACE_MODULE
    }

    /**
     * Action mode executed when a candidate is chosen in headless tests or interactive UI.
     */
    public enum LocatorActionMode {
        /** Replaces {@code UseTarget} (and auto-adds {@code :include} when modular workspace), or locates when hermetic external. */
        SELECT_OR_APPLY,
        /** Navigates directly to the candidate {@code :package} declaration ({@code F4}). */
        NAVIGATE_DECLARATION
    }

    /**
     * Deterministic selection instruction returned by {@link #setTestSelectionOverride}.
     *
     * @param packagePath target package path to match (for example, {@code ":rov/nav"})
     * @param actionMode  action to perform on the matched candidate
     */
    public record LocatorTestSelection(String packagePath, LocatorActionMode actionMode) {
    }

    /**
     * Immutable row entry in the interactive package locator table.
     *
     * @param packagePath            canonical colon-prefixed package path
     * @param originKind             provenance category
     * @param originLabel            bracketed user-facing origin badge
     * @param relativeIncludePath    formatted relative path for auto-{@code :include}, or {@code null}
     * @param targetNormalizedVfPath normalized virtual file path of the declaring module
     * @param exportedSymbolCount    total number of exported {@code :Type} and {@code #CONST} symbols
     * @param exportedSymbolsPreview compact count and symbol preview string
     * @param allExportedSymbols     complete ordered list of exported symbol identifiers for live filtering
     * @param targetVirtualFile      virtual file containing the {@code :package} declaration
     * @param navigationOffset       text offset of the {@code :package} declaration
     * @param canReplaceUseTarget    {@code true} when selecting this entry replaces {@code UseTarget} in-place
     */
    public record PackageCandidateEntry(
            String packagePath,
            OriginKind originKind,
            String originLabel,
            @Nullable String relativeIncludePath,
            String targetNormalizedVfPath,
            int exportedSymbolCount,
            String exportedSymbolsPreview,
            List<String> allExportedSymbols,
            @Nullable VirtualFile targetVirtualFile,
            int navigationOffset,
            boolean canReplaceUseTarget
    ) {
    }

    /**
     * Exposed UI component bundle for deterministic Swing verification in platform tests.
     *
     * @param panel        root popup panel
     * @param searchField  live filter search field
     * @param table        candidate table
     * @param tableModel   sortable table model
     * @param headerLabel  summary header label
     * @param selectButton primary apply/locate button
     * @param navButton    secondary {@code F4} declaration navigation button
     */
    public record LocatorContent(
            JPanel panel,
            SearchTextField searchField,
            JBTable table,
            PackageTableModel tableModel,
            JBLabel headerLabel,
            JButton selectButton,
            JButton navButton
    ) {
    }

    /**
     * Installs or clears a deterministic headless selection callback for automated platform tests.
     *
     * @param override selection callback, or {@code null} to restore interactive popup display
     */
    @TestOnly
    public static void setTestSelectionOverride(
            @Nullable Function<List<PackageCandidateEntry>, @Nullable LocatorTestSelection> override
    ) {
        testSelectionOverride = override;
    }

    /**
     * Collects and ranks all available {@code :package} candidates across Prelude, local {@code :defs},
     * already-{@code :include}d modules, and un-included workspace {@code .stvn_d}/{@code .stvn_di} files.
     *
     * @param project           active IntelliJ project
     * @param hostFile          STVN PSI file containing the unresolved {@code UseTarget}
     * @param unresolvedPackage current unresolved package text in {@code UseTarget}
     * @return ranked immutable list of {@link PackageCandidateEntry} rows
     */
    public static List<PackageCandidateEntry> collectCandidates(
            Project project,
            PsiFile hostFile,
            String unresolvedPackage
    ) {
        PsiFile originHost = hostFile.getOriginalFile();
        boolean isHermetic = StvnFlatDocumentIncludeInspection.isHermeticCompilationUnit(hostFile);
        boolean isModular = !isHermetic
            && (StvnPsiUtils.isModularDocument(hostFile) || StvnPsiUtils.isModularDocument(originHost));

        Map<String, PackageCandidateEntry> deduplicated = new LinkedHashMap<>();

        // 1. Local :defs packages
        VirtualFile hostVf = originHost.getVirtualFile() != null
            ? originHost.getVirtualFile()
            : hostFile.getVirtualFile();
        String hostNormPath = hostVf != null ? StvnSchemaFlattener.normalizePath(hostVf.getPath()) : "";
        Map<String, DeclaredPackageInfo> localPkgs =
            StvnUnresolvedUsePackageQuickFixProvider.collectDeclaredPackagesInFile(hostFile, false);
        for (DeclaredPackageInfo info : localPkgs.values()) {
            String key = OriginKind.LOCAL_DEFS + "|" + info.packagePath();
            deduplicated.putIfAbsent(key, buildCandidateEntry(
                info,
                OriginKind.LOCAL_DEFS,
                "[Local " + StvnVocabulary.KEYWORD_DEFS + "]",
                null,
                hostNormPath,
                hostVf,
                true
            ));
        }

        // 2. Already-:included modules (modular units only)
        Set<String> transitivelyIncludedVfPaths = new HashSet<>();
        if (isModular) {
            for (PsiFile incFile : StvnUnresolvedUsePackageQuickFixProvider.collectTransitiveIncludedFiles(hostFile)) {
                VirtualFile incVf = incFile.getOriginalFile().getVirtualFile();
                if (incVf == null) {
                    incVf = incFile.getVirtualFile();
                }
                String incNormPath = incVf != null ? StvnSchemaFlattener.normalizePath(incVf.getPath()) : "";
                if (!incNormPath.isEmpty()) {
                    transitivelyIncludedVfPaths.add(incNormPath);
                }
                Map<String, DeclaredPackageInfo> incPkgs =
                    StvnUnresolvedUsePackageQuickFixProvider.collectDeclaredPackagesInFile(incFile, false);
                for (DeclaredPackageInfo info : incPkgs.values()) {
                    String key = OriginKind.INCLUDED_MODULE + "|" + info.packagePath() + "|" + incNormPath;
                    deduplicated.putIfAbsent(key, buildCandidateEntry(
                        info,
                        OriginKind.INCLUDED_MODULE,
                        "[Included: " + incFile.getName() + "]",
                        null,
                        incNormPath,
                        incVf,
                        true
                    ));
                }
            }
        }

        // 3. Un-included workspace .stvn_d / .stvn_di modules
        for (WorkspaceModuleCandidate wsMod :
                StvnUnresolvedUsePackageQuickFixProvider.collectWorkspaceSchemaModules(project, hostFile)) {
            if (isModular && (wsMod.alreadyIncluded()
                    || transitivelyIncludedVfPaths.contains(wsMod.normalizedVfPath()))) {
                continue;
            }
            boolean canReplace = isModular && !wsMod.createsIncludeCycle();
            String originLabel = canReplace
                ? "[Workspace: " + wsMod.formattedRelPath() + " \u2014 adds " + StvnVocabulary.KEYWORD_INCLUDE + "]"
                : "[Workspace: " + wsMod.formattedRelPath() + " \u2014 locate declaration]";
            for (DeclaredPackageInfo info : wsMod.declaredPackages().values()) {
                String key = OriginKind.WORKSPACE_MODULE + "|" + info.packagePath() + "|" + wsMod.normalizedVfPath();
                deduplicated.putIfAbsent(key, buildCandidateEntry(
                    info,
                    OriginKind.WORKSPACE_MODULE,
                    originLabel,
                    canReplace ? wsMod.formattedRelPath() : null,
                    wsMod.normalizedVfPath(),
                    wsMod.virtualFile(),
                    canReplace
                ));
            }
        }

        // 4. Standard Library Prelude packages (:org/stvnadore/prelude and :org/stvnadore/prelude/meta)
        PsiFile preludePsi = StvnPreludeBridge.getPreludeFile(project);
        VirtualFile preludeVf = StvnPreludeBridge.getPreludeVirtualFile(project);
        Map<String, DeclaredPackageInfo> preludePkgs =
            StvnUnresolvedUsePackageQuickFixProvider.collectDeclaredPackagesInFile(preludePsi, true);
        for (DeclaredPackageInfo info : preludePkgs.values()) {
            String key = OriginKind.PRELUDE + "|" + info.packagePath();
            deduplicated.putIfAbsent(key, buildCandidateEntry(
                info,
                OriginKind.PRELUDE,
                "[Prelude]",
                null,
                "prelude.stvn_core.stvn_d",
                preludeVf,
                true
            ));
        }

        List<PackageCandidateEntry> sorted = new ArrayList<>(deduplicated.values());
        sorted.sort(Comparator
            .comparingInt((PackageCandidateEntry e) -> computeRelevanceRank(e, unresolvedPackage))
            .thenComparingInt(e -> originPriority(e.originKind()))
            .thenComparing(PackageCandidateEntry::packagePath, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PackageCandidateEntry::originLabel, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    /**
     * Displays the interactive package locator popup (or executes the {@link #setTestSelectionOverride}
     * callback when installed in automated tests).
     *
     * @param project           active IntelliJ project
     * @param editor            active text editor, or {@code null} when invoked without an editor
     * @param hostFile          STVN PSI file containing {@code useTarget}
     * @param useTarget         unresolved {@link UseTarget} PSI node
     * @param unresolvedPackage current unresolved package text
     */
    public static void showPopup(
            Project project,
            @Nullable Editor editor,
            PsiFile hostFile,
            UseTarget useTarget,
            String unresolvedPackage
    ) {
        PsiFile targetHostFile = hostFile.getOriginalFile();
        UseTarget physicalUseTarget = useTarget.getContainingFile() == targetHostFile
            ? useTarget
            : com.intellij.psi.util.PsiTreeUtil.findSameElementInCopy(useTarget, targetHostFile);

        List<PackageCandidateEntry> candidates = collectCandidates(project, targetHostFile, unresolvedPackage);
        SmartPsiElementPointer<UseTarget> targetPointer =
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(physicalUseTarget);

        var override = testSelectionOverride;
        if (override != null) {
            LocatorTestSelection chosen = override.apply(candidates);
            if (chosen != null) {
                PackageCandidateEntry matched = candidates.stream()
                    .filter(c -> c.packagePath().equals(chosen.packagePath()))
                    .findFirst()
                    .orElse(null);
                if (matched != null) {
                    if (chosen.actionMode() == LocatorActionMode.NAVIGATE_DECLARATION) {
                        navigateToCandidate(project, matched);
                    } else {
                        applyOrNavigateCandidate(project, editor, targetHostFile, targetPointer, matched);
                    }
                }
            }
            return;
        }

        JBPopup[] popupHolder = new JBPopup[1];
        boolean[] actionDispatched = {false};

        LocatorContent content = createLocatorContent(
            candidates,
            selected -> {
                actionDispatched[0] = true;
                if (popupHolder[0] != null) {
                    popupHolder[0].cancel();
                }
                applyOrNavigateCandidate(project, editor, targetHostFile, targetPointer, selected);
            },
            selected -> {
                actionDispatched[0] = true;
                if (popupHolder[0] != null) {
                    popupHolder[0].cancel();
                }
                navigateToCandidate(project, selected);
            }
        );

        JBPopup popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content.panel(), content.searchField().getTextEditor())
            .setTitle("STVN Available Packages \u2014 " + unresolvedPackage)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .addListener(new JBPopupListener() {
                @Override
                public void onClosed(@NotNull LightweightWindowEvent event) {
                    if (!actionDispatched[0] && editor != null && !editor.isDisposed() && !project.isDisposed()) {
                        IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
                    }
                }
            })
            .createPopup();

        popupHolder[0] = popup;
        if (editor != null && !editor.isDisposed()) {
            popup.showInBestPositionFor(editor);
        } else {
            popup.showCenteredInCurrentWindow(project);
        }
        IdeFocusManager.getInstance(project).requestFocus(content.searchField().getTextEditor(), true);
    }

    /**
     * Constructs the Swing component hierarchy for the package locator popup.
     *
     * @param allEntries         initial ranked candidate list
     * @param onApplySelection   callback invoked on {@code Enter}, double-click, or Select button
     * @param onNavigateSelected callback invoked on {@code F4} or Navigate button
     * @return assembled {@link LocatorContent}
     */
    public static LocatorContent createLocatorContent(
            List<PackageCandidateEntry> allEntries,
            java.util.function.Consumer<PackageCandidateEntry> onApplySelection,
            java.util.function.Consumer<PackageCandidateEntry> onNavigateSelected
    ) {
        var tableModel = new PackageTableModel(new ArrayList<>(allEntries));
        var table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(new TableRowSorter<>(tableModel));
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        StvnPrioritizedTableSpeedSearch.installOn(table);

        var headerLabel = new JBLabel(formatHeaderCount(allEntries.size()));
        headerLabel.setBorder(JBUI.Borders.emptyBottom(4));

        var searchField = new SearchTextField(false);
        searchField.getTextEditor().getEmptyText().setText(
            "Filter by " + StvnVocabulary.KEYWORD_PACKAGE + " path, module file, or exported symbol..."
        );

        var northPanel = new JPanel(new BorderLayout(0, 4));
        northPanel.add(headerLabel, BorderLayout.NORTH);
        northPanel.add(searchField, BorderLayout.CENTER);

        var selectButton = new JButton("Select Package (Enter)");
        var navButton = new JButton("Navigate to Declaration (F4)");

        Runnable updateButtonStates = () -> {
            PackageCandidateEntry sel = getSelectedEntry(table, tableModel);
            boolean hasSel = sel != null;
            selectButton.setEnabled(hasSel);
            navButton.setEnabled(hasSel && sel.targetVirtualFile() != null);
            if (sel != null && !sel.canReplaceUseTarget()) {
                selectButton.setText("Locate Declaration (Enter)");
            } else {
                selectButton.setText("Select Package (Enter)");
            }
        };

        table.getSelectionModel().addListSelectionListener(e -> updateButtonStates.run());
        if (!allEntries.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
        updateButtonStates.run();

        Runnable triggerApply = () -> {
            PackageCandidateEntry sel = getSelectedEntry(table, tableModel);
            if (sel != null) {
                onApplySelection.accept(sel);
            }
        };
        Runnable triggerNavigate = () -> {
            PackageCandidateEntry sel = getSelectedEntry(table, tableModel);
            if (sel != null) {
                onNavigateSelected.accept(sel);
            }
        };

        selectButton.addActionListener(e -> triggerApply.run());
        navButton.addActionListener(e -> triggerNavigate.run());

        searchField.addDocumentListener(new DocumentListener() {
            private void refilter() {
                String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
                List<PackageCandidateEntry> filtered = query.isEmpty()
                    ? allEntries
                    : allEntries.stream().filter(entry -> matchesFilterQuery(entry, query)).toList();
                tableModel.setItems(new ArrayList<>(filtered));
                headerLabel.setText(formatHeaderCount(filtered.size()));
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
                updateButtonStates.run();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });

        KeyAdapter navigationKeys = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    triggerApply.run();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_F4) {
                    triggerNavigate.run();
                    e.consume();
                } else if (e.getSource() == searchField.getTextEditor()) {
                    int rowCount = table.getRowCount();
                    if (rowCount > 0 && e.getKeyCode() == KeyEvent.VK_DOWN) {
                        int next = Math.min(rowCount - 1, Math.max(0, table.getSelectedRow() + 1));
                        table.setRowSelectionInterval(next, next);
                        table.scrollRectToVisible(table.getCellRect(next, 0, true));
                        e.consume();
                    } else if (rowCount > 0 && e.getKeyCode() == KeyEvent.VK_UP) {
                        int prev = Math.max(0, table.getSelectedRow() - 1);
                        table.setRowSelectionInterval(prev, prev);
                        table.scrollRectToVisible(table.getCellRect(prev, 0, true));
                        e.consume();
                    }
                }
            }
        };
        searchField.getTextEditor().addKeyListener(navigationKeys);
        table.addKeyListener(navigationKeys);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    triggerApply.run();
                }
            }
        });

        var southPanel = new JPanel(new BorderLayout(8, 0));
        var hintLabel = new JBLabel("Enter: Apply  |  F4: Navigate to declaration  |  Esc: Cancel");
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttonsPanel.add(navButton);
        buttonsPanel.add(selectButton);
        southPanel.add(hintLabel, BorderLayout.WEST);
        southPanel.add(buttonsPanel, BorderLayout.EAST);

        var rootPanel = new JPanel(new BorderLayout(0, 6));
        rootPanel.setBorder(JBUI.Borders.empty(8));
        rootPanel.setPreferredSize(new Dimension(780, 380));
        rootPanel.add(northPanel, BorderLayout.NORTH);
        rootPanel.add(new JBScrollPane(table), BorderLayout.CENTER);
        rootPanel.add(southPanel, BorderLayout.SOUTH);

        return new LocatorContent(rootPanel, searchField, table, tableModel, headerLabel, selectButton, navButton);
    }

    private static void applyOrNavigateCandidate(
            Project project,
            @Nullable Editor editor,
            PsiFile hostFile,
            SmartPsiElementPointer<UseTarget> targetPointer,
            PackageCandidateEntry entry
    ) {
        if (!entry.canReplaceUseTarget()) {
            navigateToCandidate(project, entry);
            return;
        }
        WriteCommandAction.runWriteCommandAction(
            project,
            "STVN: Select Package in " + StvnVocabulary.KEYWORD_USE,
            null,
            () -> {
                UseTarget liveTarget = targetPointer.getElement();
                if (liveTarget == null || !liveTarget.isValid()) {
                    return;
                }
                PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
                Document doc = hostFile.getViewProvider().getDocument();
                if (doc == null) {
                    doc = docManager.getDocument(hostFile);
                }
                if (doc == null) {
                    return;
                }
                if (hostFile.isPhysical()) {
                    docManager.doPostponedOperationsAndUnblockDocument(doc);
                }
                // Step 1: Replace UseTarget first before inserting :include above it so offsets never drift
                var range = liveTarget.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), entry.packagePath());
                docManager.commitDocument(doc);

                // Step 2: Auto-append module path to :include [...] when choosing an un-included workspace module
                if (entry.originKind() == OriginKind.WORKSPACE_MODULE && entry.relativeIncludePath() != null) {
                    StvnIncludeModuleForPackageQuickFix.insertOrAppendIncludePath(
                        project,
                        hostFile,
                        entry.relativeIncludePath(),
                        entry.targetNormalizedVfPath()
                    );
                }
            }
        );
        if (editor != null && !editor.isDisposed() && !project.isDisposed()) {
            IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
        }
    }

    private static void navigateToCandidate(Project project, PackageCandidateEntry entry) {
        VirtualFile vf = entry.targetVirtualFile();
        if (vf == null || !vf.isValid() || project.isDisposed()) {
            return;
        }
        FileEditorManager.getInstance(project).openTextEditor(
            new OpenFileDescriptor(project, vf, Math.max(0, entry.navigationOffset())),
            true
        );
    }

    private static @Nullable PackageCandidateEntry getSelectedEntry(JBTable table, PackageTableModel model) {
        int visualRow = table.getSelectedRow();
        if (visualRow < 0 || visualRow >= table.getRowCount()) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(visualRow);
        return modelRow >= 0 && modelRow < model.getRowCount() ? model.getItem(modelRow) : null;
    }

    private static boolean matchesFilterQuery(PackageCandidateEntry entry, String lowerQuery) {
        if (entry.packagePath().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || entry.originLabel().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || entry.exportedSymbolsPreview().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        for (String sym : entry.allExportedSymbols()) {
            if (sym.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    private static String formatHeaderCount(int count) {
        return count == 1 ? "1 available package" : count + " available packages";
    }

    private static PackageCandidateEntry buildCandidateEntry(
            DeclaredPackageInfo info,
            OriginKind originKind,
            String originLabel,
            @Nullable String relativeIncludePath,
            String targetNormVfPath,
            @Nullable VirtualFile targetVf,
            boolean canReplaceUseTarget
    ) {
        List<String> symbols = info.exportedSymbols();
        int count = symbols.size();
        String preview;
        if (count == 0) {
            preview = "0 symbols";
        } else if (count <= MAX_PREVIEW_SYMBOLS) {
            preview = count + " (" + String.join(", ", symbols) + ")";
        } else {
            preview = count + " (" + String.join(", ", symbols.subList(0, MAX_PREVIEW_SYMBOLS))
                + ", +" + (count - MAX_PREVIEW_SYMBOLS) + " more)";
        }
        return new PackageCandidateEntry(
            info.packagePath(),
            originKind,
            originLabel,
            relativeIncludePath,
            targetNormVfPath,
            count,
            preview,
            symbols,
            targetVf,
            info.navigationOffset(),
            canReplaceUseTarget
        );
    }

    private static int computeRelevanceRank(PackageCandidateEntry entry, String unresolvedPkg) {
        String cand = entry.packagePath();
        String normUnresolved = unresolvedPkg.trim();
        if (cand.equals(normUnresolved)) {
            return 0;
        }
        String candLower = cand.toLowerCase(Locale.ROOT);
        String targetLower = normUnresolved.toLowerCase(Locale.ROOT);
        if (candLower.equals(targetLower)) {
            return 1;
        }
        if (candLower.startsWith(targetLower) || targetLower.startsWith(candLower)) {
            return 2;
        }
        String lastSeg = extractLeafSegment(targetLower);
        String candLeaf = extractLeafSegment(candLower);
        if (!lastSeg.isEmpty() && (candLeaf.contains(lastSeg) || lastSeg.contains(candLeaf))) {
            return 3;
        }
        int sharedPrefix = sharedPathPrefixSegments(candLower, targetLower);
        if (sharedPrefix > 0) {
            return 4;
        }
        if (levenshteinBounded(candLower, targetLower, 4) <= 3) {
            return 5;
        }
        return 6;
    }

    private static String extractLeafSegment(String pkg) {
        int slash = pkg.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < pkg.length()) {
            return pkg.substring(slash + 1);
        }
        return pkg.startsWith(StvnVocabulary.SIGIL_TYPIC) ? pkg.substring(1) : pkg;
    }

    private static int sharedPathPrefixSegments(String a, String b) {
        String[] partsA = a.replaceFirst("^:", "").split("/");
        String[] partsB = b.replaceFirst("^:", "").split("/");
        int limit = Math.min(partsA.length, partsB.length);
        int shared = 0;
        for (int i = 0; i < limit; i++) {
            if (partsA[i].equals(partsB[i])) {
                shared++;
            } else {
                break;
            }
        }
        return shared;
    }

    private static int levenshteinBounded(String a, String b, int maxDist) {
        if (Math.abs(a.length() - b.length()) > maxDist) {
            return maxDist + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > maxDist) {
                return maxDist + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static int originPriority(OriginKind kind) {
        return switch (kind) {
            case LOCAL_DEFS -> 0;
            case INCLUDED_MODULE -> 1;
            case WORKSPACE_MODULE -> 2;
            case PRELUDE -> 3;
        };
    }

    /**
     * Sortable three-column table model for {@link StvnUsePackageLocatorPopup}.
     */
    public static final class PackageTableModel extends ListTableModel<PackageCandidateEntry> {

        private static final ColumnInfo<PackageCandidateEntry, String> PACKAGE_COLUMN =
            new ColumnInfo<>("Package") {
                @Override
                public String valueOf(PackageCandidateEntry item) {
                    return item.packagePath();
                }

                @Override
                public @Nullable Comparator<PackageCandidateEntry> getComparator() {
                    return Comparator.comparing(PackageCandidateEntry::packagePath, String.CASE_INSENSITIVE_ORDER);
                }
            };

        private static final ColumnInfo<PackageCandidateEntry, String> ORIGIN_COLUMN =
            new ColumnInfo<>("Origin") {
                @Override
                public String valueOf(PackageCandidateEntry item) {
                    return item.originLabel();
                }

                @Override
                public @Nullable Comparator<PackageCandidateEntry> getComparator() {
                    return Comparator.comparing(PackageCandidateEntry::originLabel, String.CASE_INSENSITIVE_ORDER);
                }
            };

        private static final ColumnInfo<PackageCandidateEntry, String> SYMBOLS_COLUMN =
            new ColumnInfo<>("Exported Symbols") {
                @Override
                public String valueOf(PackageCandidateEntry item) {
                    return item.exportedSymbolsPreview();
                }

                @Override
                public @Nullable Comparator<PackageCandidateEntry> getComparator() {
                    return Comparator.comparingInt(PackageCandidateEntry::exportedSymbolCount).reversed();
                }
            };

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static final ColumnInfo<PackageCandidateEntry, ?>[] COLUMNS = new ColumnInfo[] {
            PACKAGE_COLUMN,
            ORIGIN_COLUMN,
            SYMBOLS_COLUMN
        };

        /**
         * Constructs a new {@code PackageTableModel}.
         *
         * @param items initial list of candidate rows
         */
        public PackageTableModel(List<PackageCandidateEntry> items) {
            super(COLUMNS, items, 0);
        }
    }
}
