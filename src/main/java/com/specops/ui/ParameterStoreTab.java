package com.specops.ui;

import burp.api.montoya.core.ByteArray;
import com.specops.SpecOpsContext;
import com.specops.domain.Parameter;
import com.specops.services.request.ValueGenerator;
import com.specops.services.scanner.ProxyScanner;
import com.specops.ui.models.ParameterTableModel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * Global Parameter Store tab.
 * Shows all discovered parameters and lets the user edit values.
 * If a parameter has enum values in the spec, the Value column renders a dropdown.
 */
public class ParameterStoreTab extends JPanel {

    // Column indices must match ParameterTableModel
    private static final int NAME_COL   = 0;
    private static final int PATH_COL   = 1;
    private static final int IN_COL     = 2;
    private static final int VALUE_COL  = 3;
    private static final int TYPE_COL   = 4;
    private static final int SOURCE_COL = 5;
    private static final int LOCK_COL   = 6;

    private final SpecOpsContext context;
    private final ParameterTableModel tableModel;
    private final JTable parameterTable;
    private final TableRowSorter<ParameterTableModel> sorter;
    private final JTextField filterText;

    private Action copyCellAction;
    private Action pasteCellAction;

    private static final Preferences PREFS = Preferences.userNodeForPackage(ParameterStoreTab.class);
    private static final String PREF_GROUP_KEY = "valueGenerator.groupKey";

    private JLabel groupingStatus;

    public ParameterStoreTab(SpecOpsContext context) {
        this.context = context;

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        tableModel = new ParameterTableModel(context);
        sorter = new TableRowSorter<>(tableModel);
        parameterTable = new JTable(tableModel);
        parameterTable.setRowSorter(sorter);

        parameterTable.setCellSelectionEnabled(true);
        parameterTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        parameterTable.setDefaultRenderer(Boolean.class, new CustomCellRenderer());
        parameterTable.setDefaultRenderer(Object.class, new CustomCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                TableModel tm = table.getModel();
                if (tm instanceof ParameterTableModel) {
                    Parameter p = ((ParameterTableModel) tm).getParameterAt(modelRow);
                    if (p != null && p.hasEnum()) {
                        ((JComponent) c).setToolTipText("Allowed: " + String.join(", ", p.getEnumValues()));
                    } else if (p != null && p.getDescription() != null && !p.getDescription().isEmpty()) {
                        ((JComponent) c).setToolTipText(p.getDescription());
                    } else {
                        ((JComponent) c).setToolTipText(null);
                    }
                }
                return c;
            }
        });

        // Column widths
        parameterTable.getColumnModel().getColumn(NAME_COL).setPreferredWidth(150);
        parameterTable.getColumnModel().getColumn(PATH_COL).setPreferredWidth(200);
        parameterTable.getColumnModel().getColumn(IN_COL).setPreferredWidth(60);
        parameterTable.getColumnModel().getColumn(VALUE_COL).setPreferredWidth(300);
        parameterTable.getColumnModel().getColumn(TYPE_COL).setPreferredWidth(60);
        parameterTable.getColumnModel().getColumn(SOURCE_COL).setPreferredWidth(60);
        parameterTable.getColumnModel().getColumn(LOCK_COL).setPreferredWidth(40);

        // Value editor is enum-aware
        parameterTable.getColumnModel().getColumn(VALUE_COL).setCellEditor(new EnumAwareCellEditor(context));

        JScrollPane scrollPane = new JScrollPane(parameterTable);

        // Top bar
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton populateFromProxyButton = new JButton("Populate from Proxy");
        populateFromProxyButton.addActionListener(e -> populateFromProxy());
        buttonPanel.add(populateFromProxyButton);

        buttonPanel.add(createGenerateValuesButton());

        JButton importButton = new JButton("Import Values");
        importButton.addActionListener(e -> importValues());
        buttonPanel.add(importButton);

        JButton exportButton = new JButton("Export Values");
        exportButton.addActionListener(e -> exportValues());
        buttonPanel.add(exportButton);

        JButton clearButton = new JButton("Clear Values");
        clearButton.addActionListener(e -> clearValues());
        buttonPanel.add(clearButton);

        JCheckBox groupByNameToggle = new JCheckBox("Group by name", true);
        groupByNameToggle.addActionListener(e -> applySortKeys(groupByNameToggle.isSelected()));
        applySortKeys(true); // default on

        filterText = new JTextField(25);

        // Status chip that shows current grouping mode
        groupingStatus = new JLabel();
        groupingStatus.putClientProperty("JComponent.sizeVariant", "small");
        // Initialize with saved preference
        applyGroupKey(loadGroupKeyPref());

        rightPanel.add(groupByNameToggle);
        rightPanel.add(new JLabel("Filter:"));
        rightPanel.add(filterText);
        rightPanel.add(Box.createHorizontalStrut(8));
        rightPanel.add(groupingStatus);

        topPanel.add(buttonPanel, BorderLayout.WEST);
        topPanel.add(rightPanel, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        installClipboardActions();
        installContextMenu();
        installKeyBindings();
        setupFilterListener();

        // Refresh UI when parameter store changes
        context.setParametersUpdateListener(v ->
                javax.swing.SwingUtilities.invokeLater(this::refreshData));
    }

    private void applySortKeys(boolean groupByName) {
        if (groupByName) {
            sorter.setSortKeys(List.of(
                    new RowSorter.SortKey(NAME_COL, SortOrder.ASCENDING),
                    new RowSorter.SortKey(IN_COL,   SortOrder.ASCENDING),
                    new RowSorter.SortKey(TYPE_COL, SortOrder.ASCENDING)
            ));
        } else {
            sorter.setSortKeys(List.of(
                    new RowSorter.SortKey(IN_COL,   SortOrder.ASCENDING),
                    new RowSorter.SortKey(NAME_COL, SortOrder.ASCENDING),
                    new RowSorter.SortKey(TYPE_COL, SortOrder.ASCENDING)
            ));
        }
    }

    public void refreshData() {
        tableModel.reloadFromStore();
        tableModel.fireTableDataChanged();
    }

    private JPopupMenu createGenerateValuesMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem generateAll = new JMenuItem("Generate for all empty fields");
        generateAll.addActionListener(e -> generateValues(null));
        menu.add(generateAll);

        menu.addSeparator();

        JMenuItem generateStrings = new JMenuItem("Generate for empty Strings");
        generateStrings.addActionListener(e -> generateValues("string"));
        menu.add(generateStrings);

        JMenuItem generateNumbers = new JMenuItem("Generate for empty Numbers or Integers");
        generateNumbers.addActionListener(e -> generateValues("integer"));
        menu.add(generateNumbers);

        JMenuItem generateBooleans = new JMenuItem("Generate for empty Booleans");
        generateBooleans.addActionListener(e -> generateValues("boolean"));
        menu.add(generateBooleans);

        // Grouping submenu with radios
        menu.addSeparator();
        JMenu grouping = new JMenu("Grouping");
        ButtonGroup group = new ButtonGroup();

        JRadioButtonMenuItem none     = new JRadioButtonMenuItem("None");
        JRadioButtonMenuItem byName   = new JRadioButtonMenuItem("Name");
        JRadioButtonMenuItem byNameIn = new JRadioButtonMenuItem("Name + In");
        JRadioButtonMenuItem byType   = new JRadioButtonMenuItem("Name + Type");

        group.add(none); group.add(byName); group.add(byNameIn); group.add(byType);
        grouping.add(none); grouping.add(byName); grouping.add(byNameIn); grouping.add(byType);
        menu.add(grouping);

        // Restore and apply saved choice
        ValueGenerator.GroupKey saved = loadGroupKeyPref();
        switch (saved) {
            case NONE:      none.setSelected(true); break;
            case NAME:      byName.setSelected(true); break;
            case NAME_TYPE: byType.setSelected(true); break;
            case NAME_IN:
            default:        byNameIn.setSelected(true); break;
        }
        // Wire listeners
        none.addActionListener(e -> applyGroupKey(ValueGenerator.GroupKey.NONE));
        byName.addActionListener(e -> applyGroupKey(ValueGenerator.GroupKey.NAME));
        byNameIn.addActionListener(e -> applyGroupKey(ValueGenerator.GroupKey.NAME_IN));
        byType.addActionListener(e -> applyGroupKey(ValueGenerator.GroupKey.NAME_TYPE));

        return menu;
    }

    private JComponent createGenerateValuesButton() {
        JButton generateButton = new JButton("Generate Values");
        JPopupMenu popupMenu = createGenerateValuesMenu();
        generateButton.addActionListener(e -> popupMenu.show(generateButton, 0, generateButton.getHeight()));
        return generateButton;
    }

    private void setupFilterListener() {
        filterText.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFilter(); }
            public void removeUpdate(DocumentEvent e) { updateFilter(); }
            public void changedUpdate(DocumentEvent e) { updateFilter(); }
        });
    }

    private void updateFilter() {
        String text = filterText.getText();
        sorter.setRowFilter(text == null || text.trim().isEmpty()
                ? null
                : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
    }

    private void populateFromProxy() {
        String domain = JOptionPane.showInputDialog(this,
                "Enter the target domain to scan from Proxy history, e.g. api.example.com",
                "Populate from Proxy",
                JOptionPane.PLAIN_MESSAGE);

        if (domain != null && !domain.trim().isEmpty()) {
            new SwingWorker<Integer, Void>() {
                @Override
                protected Integer doInBackground() {
                    ProxyScanner scanner = new ProxyScanner(context);
                    return scanner.scanAndPopulate(domain.trim());
                }

                @Override
                protected void done() {
                    try {
                        int updatedCount = get();
                        refreshData();
                        JOptionPane.showMessageDialog(ParameterStoreTab.this,
                                "Scan complete. Updated " + updatedCount + " parameter values.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                        emitChanged();
                    } catch (Exception e) {
                        context.api.logging().logToError("Error during proxy scan: " + e.getMessage());
                        JOptionPane.showMessageDialog(ParameterStoreTab.this,
                                "An error occurred during the proxy scan.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
    }

    private void generateValues(String typeFilter) {
        commitEditsIfAny();
        int count = ValueGenerator.generateValues(context.getGlobalParameterStore(), typeFilter);
        refreshData();
        JOptionPane.showMessageDialog(this,
                "Generated values for " + count + " parameters.",
                "Generation Complete", JOptionPane.INFORMATION_MESSAGE);
        emitChanged();
    }

    private void exportValues() {
        commitEditsIfAny();

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Parameter Values");
        fileChooser.addChoosableFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));
        fileChooser.addChoosableFileFilter(new FileNameExtensionFilter("JSON Files", "json"));
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files", "csv")); // default CSV

        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection != JFileChooser.APPROVE_OPTION) return;

        File file = fileChooser.getSelectedFile();
        String lower = file.getName().toLowerCase();

        try {
            if (!lower.contains(".")) {
                file = new File(file.getParentFile(), file.getName() + ".csv");
                lower = file.getName().toLowerCase();
            }
            Path path = file.toPath();

            if (lower.endsWith(".csv")) {
                writeCsv(path);
            } else if (lower.endsWith(".json")) {
                ByteArray content = ValueGenerator.exportValues(context.getGlobalParameterStore());
                Files.write(path, content.getBytes(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Unsupported file type. Use .csv or .json",
                        "Export Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            JOptionPane.showMessageDialog(this,
                    "Values exported successfully to " + file.getAbsolutePath(),
                    "Export Successful", JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException ex) {
            context.api.logging().logToError("Export failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Export failed: " + ex.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importValues() {
        commitEditsIfAny();

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Import Parameter Values");
        fileChooser.addChoosableFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));
        fileChooser.addChoosableFileFilter(new FileNameExtensionFilter("JSON Files", "json"));
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));

        int userSelection = fileChooser.showOpenDialog(this);
        if (userSelection != JFileChooser.APPROVE_OPTION) return;

        File file = fileChooser.getSelectedFile();
        String lower = file.getName().toLowerCase();
        Path path = file.toPath();

        try {
            int count;
            if (lower.endsWith(".csv")) {
                count = importCsv(path);
            } else if (lower.endsWith(".json")) {
                byte[] bytes = Files.readAllBytes(path);
                ByteArray content = ByteArray.byteArray(bytes);
                count = ValueGenerator.importValues(context.getGlobalParameterStore(), content);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Unsupported file type. Use .csv or .json",
                        "Import Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            refreshData();
            JOptionPane.showMessageDialog(this,
                    "Successfully imported " + count + " parameter values.",
                    "Import Successful", JOptionPane.INFORMATION_MESSAGE);
            emitChanged();
            parameterTable.requestFocusInWindow();

        } catch (IOException ex) {
            context.api.logging().logToError("Import failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Import failed: " + ex.getMessage(),
                    "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void commitEditsIfAny() {
        if (parameterTable.isEditing()) {
            TableCellEditor ce = parameterTable.getCellEditor();
            if (ce != null) ce.stopCellEditing();
        }
    }

    private void installClipboardActions() {
        copyCellAction = new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int r = parameterTable.getSelectedRow();
                int c = parameterTable.getSelectedColumn();
                if (r < 0 || c < 0) return;
                Object v = parameterTable.getValueAt(r, c);
                copyToClipboard(v == null ? "" : v.toString());
            }
        };

        pasteCellAction = new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int[] rows = parameterTable.getSelectedRows();
                int[] cols = parameterTable.getSelectedColumns();
                if (rows == null || cols == null || rows.length == 0 || cols.length == 0) return;

                String clip;
                try {
                    var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clip = (String) cb.getData(DataFlavor.stringFlavor);
                } catch (UnsupportedFlavorException | IOException ex) {
                    return;
                }

                // paste only into Value column and skip locked params
                for (int vr : rows) {
                    int mr = parameterTable.convertRowIndexToModel(vr);
                    Parameter p = tableModel.getParameterAt(mr);
                    if (p != null && p.isLocked()) continue;

                    for (int vc : cols) {
                        if (vc == VALUE_COL && parameterTable.isCellEditable(vr, vc)) {
                            parameterTable.setValueAt(clip, vr, vc);
                        }
                    }
                }

                commitEditsIfAny();
                emitChanged();
            }
        };

        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); // Cmd on macOS, Ctrl on Windows/Linux
        parameterTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, mod), "copyCell");
        parameterTable.getActionMap().put("copyCell", copyCellAction);

        parameterTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, mod), "pasteCell");
        parameterTable.getActionMap().put("pasteCell", pasteCellAction);
    }

    private void installContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem miCopy = new JMenuItem("Copy value");
        miCopy.addActionListener(copyCellAction);
        menu.add(miCopy);

        JMenuItem miPaste = new JMenuItem("Paste value");
        miPaste.addActionListener(pasteCellAction);
        menu.add(miPaste);

        menu.addSeparator();

        JMenu copyAs = new JMenu("Copy as");

        JMenuItem asHeader = new JMenuItem("Header: Name: value");
        asHeader.addActionListener(e -> {
            int r = parameterTable.getSelectedRow();
            if (r < 0) return;
            String name = String.valueOf(parameterTable.getValueAt(r, NAME_COL));
            String val  = String.valueOf(parameterTable.getValueAt(r, VALUE_COL));
            copyToClipboard(name + ": " + val);
        });
        copyAs.add(asHeader);

        JMenuItem asQuery = new JMenuItem("Query: name=value");
        asQuery.addActionListener(e -> {
            int r = parameterTable.getSelectedRow();
            if (r < 0) return;
            String name = String.valueOf(parameterTable.getValueAt(r, NAME_COL));
            String val  = String.valueOf(parameterTable.getValueAt(r, VALUE_COL));
            copyToClipboard(name + "=" + val);
        });
        copyAs.add(asQuery);

        JMenuItem asJson = new JMenuItem("JSON fragment");
        asJson.addActionListener(e -> {
            int r = parameterTable.getSelectedRow();
            if (r < 0) return;
            String name = String.valueOf(parameterTable.getValueAt(r, NAME_COL));
            String val  = String.valueOf(parameterTable.getValueAt(r, VALUE_COL));
            String vEsc = val.replace("\\", "\\\\").replace("\"", "\\\"");
            copyToClipboard("\"" + name + "\": \"" + vEsc + "\"");
        });
        copyAs.add(asJson);

        menu.add(copyAs);
        parameterTable.setComponentPopupMenu(menu);
    }

    private void installKeyBindings() {
        // Ctrl/Cmd+G to generate for empties
        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke gen = KeyStroke.getKeyStroke(KeyEvent.VK_G, mod);
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(gen, "genEmpty");
        getActionMap().put("genEmpty", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { generateValues(null); }
        });

        // Ctrl/Cmd+Shift+G to cycle grouping mode
        KeyStroke cycle = KeyStroke.getKeyStroke(KeyEvent.VK_G, mod | InputEvent.SHIFT_DOWN_MASK);
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(cycle, "cycleGrouping");
        getActionMap().put("cycleGrouping", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                ValueGenerator.GroupKey next = nextGroupKey(loadGroupKeyPref());
                applyGroupKey(next);
            }
        });
    }

    private static void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    // ===== CSV I/O =====

    private void writeCsv(Path path) throws IOException {
        Map<String, Parameter> store = context.getGlobalParameterStore();

        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("name,path,in,value,type,source,locked");
            w.write("\r\n");

            store.values().stream()
                    .sorted(Comparator
                            .comparing((Parameter p) -> nz(p.getName()), Comparator.nullsFirst(String::compareToIgnoreCase))
                            .thenComparing(p -> nz(p.getIn()), Comparator.nullsFirst(String::compareToIgnoreCase)))
                    .forEach(p -> {
                        try {
                            String inLoc  = nz(p.getIn());
                            String raw    = nz(p.getName());
                            String leaf   = "body".equalsIgnoreCase(inLoc) ? computeLeafName(raw) : raw;
                            String pathCol= "body".equalsIgnoreCase(inLoc) ? raw : "";

                            String value  = nz(p.getValue());
                            String type   = nz(p.getType());
                            String source = p.getSource() == null ? "" : p.getSource().name();
                            String locked = String.valueOf(p.isLocked());

                            String line = String.join(",",
                                    escapeCsv(leaf),
                                    escapeCsv(pathCol),
                                    escapeCsv(inLoc),
                                    escapeCsv(value),
                                    escapeCsv(type),
                                    escapeCsv(source),
                                    escapeCsv(locked)
                            );
                            w.write(line);
                            w.write("\r\n");
                        } catch (IOException ioe) {
                            context.api.logging().logToError("CSV export error: " + ioe.getMessage());
                        }
                    });
        }
    }

    private int importCsv(Path path) throws IOException {
        Map<String, Parameter> store = context.getGlobalParameterStore();
        List<Map<String, String>> rows = readCsv(path);

        int imported = 0;

        for (Map<String, String> r : rows) {
            String nameCsv  = nz(r.get("name")).trim();
            String pathCsv  = nz(r.get("path")).trim();
            String inCsvRaw = nz(r.get("in")).trim();
            String value    = nz(r.get("value"));
            String lockedS  = nz(r.get("locked")).trim();

            if (nameCsv.isEmpty() && pathCsv.isEmpty()) continue;

            String inCsv = inCsvRaw.isEmpty() ? "" : inCsvRaw.toLowerCase(Locale.ROOT);
            boolean isBody   = "body".equalsIgnoreCase(inCsv);
            String matchName = isBody ? (pathCsv.isEmpty() ? nameCsv : pathCsv) : nameCsv;

            boolean updatedAny = false;

            for (Parameter existing : store.values()) {
                if (existing == null) continue;

                boolean inMatch = inCsv.isEmpty()
                        || (existing.getIn() != null && existing.getIn().equalsIgnoreCase(inCsv));
                if (!inMatch) continue;

                boolean nameMatch = existing.getName() != null
                        && existing.getName().equalsIgnoreCase(matchName);
                if (!nameMatch) continue;

                // update lock state if provided
                if (!lockedS.isEmpty()) {
                    boolean toLock = parseBool(lockedS);
                    existing.setLocked(toLock);
                }

                // set value only if not locked
                if (!existing.isLocked()) {
                    existing.setValue(value);
                    imported++;
                }

                // Always mark provenance as IMPORTED on CSV import
                existing.setSource(Parameter.ValueSource.IMPORTED);

                updatedAny = true;
            }

            if (updatedAny) continue;

            // not found: create (only if we know 'in')
            if (!inCsv.isEmpty()) {
                // Try to inherit type/lock from same-name param (any in)
                Parameter inherit = store.values().stream()
                        .filter(p -> p != null && p.getName() != null
                                && p.getName().equalsIgnoreCase(matchName))
                        .findFirst().orElse(null);

                String type = inherit != null && inherit.getType() != null ? inherit.getType() : "string";
                boolean lockInherit = inherit != null && inherit.isLocked();

                String newParamName = matchName;

                Parameter p = new Parameter(newParamName, inCsv, type);
                if (!lockedS.isEmpty()) p.setLocked(parseBool(lockedS));
                else p.setLocked(lockInherit);

                if (!p.isLocked()) {
                    p.setValue(value);
                    imported++;
                }

                p.setSource(Parameter.ValueSource.IMPORTED);
                // rely on Parameter#getUniqueKey() to normalize body keys (jsonPath)
                String k = com.specops.SpecOpsContext.canonicalKey(p);
                store.put(k, p);
            }
        }

        return imported;
    }

    private static String computeLeafName(String path) {
        if (path == null || path.isEmpty()) return "";
        String norm = path.replaceAll("\\[\\d+\\]", "[]");
        int dot = norm.lastIndexOf('.');
        String last = dot >= 0 ? norm.substring(dot + 1) : norm;
        if (last.endsWith("[]")) last = last.substring(0, last.length() - 2);
        return last;
    }

    private static boolean parseBool(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("y");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\r") || s.contains("\n");
        String t = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + t + "\"" : t;
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<Map<String, String>> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = r.readLine();
            if (headerLine == null) return out;

            if (!headerLine.isEmpty() && headerLine.charAt(0) == '\uFEFF') {
                headerLine = headerLine.substring(1);
            }

            List<String> header = parseCsvLine(headerLine);

            String line;
            while ((line = r.readLine()) != null) {
                List<String> cols = parseCsvLine(line);
                Map<String, String> obj = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    String key = header.get(i).trim().toLowerCase(Locale.ROOT);
                    String val = i < cols.size() ? cols.get(i).trim() : "";
                    obj.put(key, val);
                }
                out.add(obj);
            }
        }
        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++; // escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString()); cur.setLength(0);
                } else if (c == '"') {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static class EnumAwareCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final SpecOpsContext context;
        private JComponent editor;
        private Parameter currentParam;

        EnumAwareCellEditor(SpecOpsContext ctx) {
            this.context = ctx;
        }

        @Override
        public Object getCellEditorValue() {
            if (currentParam == null) return null;
            if (editor instanceof JComboBox) {
                Object sel = ((JComboBox<?>) editor).getSelectedItem();
                return sel != null ? sel.toString() : "";
            }
            if (editor instanceof JTextField) {
                return ((JTextField) editor).getText();
            }
            return "";
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {

            int modelRow = table.convertRowIndexToModel(row);
            TableModel tm = table.getModel();

            if (!(tm instanceof ParameterTableModel model)) {
                editor = new JTextField(value != null ? value.toString() : "");
                currentParam = null;
                return editor;
            }
            currentParam = model.getParameterAt(modelRow);

            if (currentParam != null && currentParam.hasEnum()) {
                JComboBox<String> box = new JComboBox<>(currentParam.getEnumValues().toArray(new String[0]));
                box.setEditable(false);
                if (value != null) box.setSelectedItem(value.toString());
                editor = box;
            } else {
                JTextField tf = new JTextField(value != null ? value.toString() : "");
                editor = tf;
            }
            return editor;
        }
    }

    private void emitChanged() {
        try { context.notifyParametersChanged(); } catch (Throwable ignored) {}
        try { context.notifyBindingsChanged(); } catch (Throwable ignored) {}
        try { context.notifyEndpointsChanged(); } catch (Throwable ignored) {}
    }

    private void applyGroupKey(ValueGenerator.GroupKey key) {
        ValueGenerator.setGroupKey(key);
        saveGroupKeyPref(key);
        if (groupingStatus != null) groupingStatus.setText("Grouping: " + pretty(key));
    }

    private String pretty(ValueGenerator.GroupKey k) {
        switch (k) {
            case NONE: return "None";
            case NAME: return "Name";
            case NAME_TYPE: return "Name + Type";
            case NAME_IN:
            default: return "Name + In";
        }
    }

    private void saveGroupKeyPref(ValueGenerator.GroupKey k) {
        PREFS.put(PREF_GROUP_KEY, k.name());
    }

    private ValueGenerator.GroupKey loadGroupKeyPref() {
        try {
            return ValueGenerator.GroupKey.valueOf(PREFS.get(PREF_GROUP_KEY, ValueGenerator.GroupKey.NAME_IN.name()));
        } catch (IllegalArgumentException ex) {
            return ValueGenerator.GroupKey.NAME_IN;
        }
    }

    private ValueGenerator.GroupKey nextGroupKey(ValueGenerator.GroupKey cur) {
        switch (cur) {
            case NONE:
                return ValueGenerator.GroupKey.NAME;
            case NAME:
                return ValueGenerator.GroupKey.NAME_IN;
            case NAME_IN:
                return ValueGenerator.GroupKey.NAME_TYPE;
            case NAME_TYPE:
            default:
                return ValueGenerator.GroupKey.NONE;
        }
    }

    private void clearValues() {
        commitEditsIfAny();
        int cleared = 0;
        for (Parameter p : context.getGlobalParameterStore().values()) {
            if (!p.isLocked() && p.getValue() != null && !p.getValue().isEmpty()) {
                p.setValue("");
                cleared++;
            }
        }
        refreshData();
        JOptionPane.showMessageDialog(this,
                "Cleared " + cleared + " parameter values.",
                "Clear Complete", JOptionPane.INFORMATION_MESSAGE);
        emitChanged();
    }
}