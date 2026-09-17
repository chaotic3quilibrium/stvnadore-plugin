package org.stvnadore.plugin.browser;

import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * List table model providing multi-column sorting and speed-search support for symbol rows.
 */
@NullMarked
public final class StvnNamespaceTableModel extends ListTableModel<StvnNamespaceSymbolEntry> {

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> NAME_COLUMN =
        new ColumnInfo<>("Name") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.name();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> SOURCE_COLUMN =
        new ColumnInfo<>("Root Namespaced Source") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.source();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::source, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> TYPE_COLUMN =
        new ColumnInfo<>("Type") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.typeStructure();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::typeStructure, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, Integer> DEPTH_COLUMN =
        new ColumnInfo<>("Use Depth") {
            @Override
            public Integer valueOf(StvnNamespaceSymbolEntry item) {
                return item.useDepth();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparingInt(StvnNamespaceSymbolEntry::useDepth);
            }

            @Override
            public Class<?> getColumnClass() {
                return Integer.class;
            }
        };

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final ColumnInfo<StvnNamespaceSymbolEntry, ?>[] COLUMNS = new ColumnInfo[] {
        NAME_COLUMN,
        SOURCE_COLUMN,
        TYPE_COLUMN,
        DEPTH_COLUMN
    };

    /**
     * Constructs a new StvnNamespaceTableModel with the supplied symbol rows.
     *
     * @param items initial list of symbol entries
     */
    public StvnNamespaceTableModel(List<StvnNamespaceSymbolEntry> items) {
        super(COLUMNS, items, 0);
    }
}
