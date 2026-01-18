package com.specops.ui;

import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.specops.SpecOpsContext;
import com.specops.domain.AttackResult;
import com.specops.services.export.ResultExporter;
import com.specops.ui.models.ResultTableModel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Attack Results with bottom preview, matching Endpoints Workbench layout.
 * Top: results table
 * Bottom: Request and Response preview tabs (read only)
 */
public class ResultsTab extends JPanel {

    private final SpecOpsContext context;
    private final ResultTableModel tableModel;
    private final JTable resultsTable;
    private final TableRowSorter<ResultTableModel> sorter;
    private final JTextField filterText;

    // Bottom preview editors
    private final HttpRequestEditor reqViewer;
    private final HttpResponseEditor respViewer;

    public ResultsTab(SpecOpsContext context) {
        this.context = context;

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        tableModel = new ResultTableModel(context);
        resultsTable = new JTable(tableModel);
        sorter = new TableRowSorter<>(tableModel);
        resultsTable.setRowSorter(sorter);
        resultsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(100); // Timestamp
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(60);  // Method
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(350); // Path
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Status Code
        resultsTable.getColumnModel().getColumn(4).setPreferredWidth(120); // Length

        addRightClickMenu();

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton clearButton = new JButton("Clear Results");
        clearButton.addActionListener(e -> clearResults());
        JButton exportCsvButton = new JButton("Export CSV");
        exportCsvButton.addActionListener(e -> exportResults(ExportFormat.CSV));
        JButton exportJsonButton = new JButton("Export JSON");
        exportJsonButton.addActionListener(e -> exportResults(ExportFormat.JSON));
        controlPanel.add(clearButton);
        controlPanel.add(exportCsvButton);
        controlPanel.add(exportJsonButton);
        controlPanel.add(new JLabel("Filter:"));
        filterText = new JTextField(20);
        controlPanel.add(filterText);
        add(controlPanel, BorderLayout.NORTH);

        UserInterface ui = context.api.userInterface();
        reqViewer = ui.createHttpRequestEditor(EditorOptions.READ_ONLY);
        respViewer = ui.createHttpResponseEditor(EditorOptions.READ_ONLY);

        JTabbedPane previewTabs = new JTabbedPane();
        previewTabs.addTab("Request", reqViewer.uiComponent());
        previewTabs.addTab("Response", respViewer.uiComponent());

        // Split pane: table on top, preview on bottom
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setTopComponent(new JScrollPane(resultsTable));
        split.setBottomComponent(previewTabs);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        // Selection listener updates preview
        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreviewFromSelection();
            }
        });

        // When new results arrive, refresh table and keep preview in sync if selected row changed
        context.setAttackResultListener(result -> {
            tableModel.fireTableDataChanged();
            // If one row is selected, reapply preview so it stays fresh
            if (resultsTable.getSelectedRowCount() == 1) {
                updatePreviewFromSelection();
            }
        });

        setupFilterListener();
    }

    public void refreshData() {
        tableModel.fireTableDataChanged();
        updatePreviewFromSelection();
    }

    private void clearResults() {
        context.getAttackResults().clear();
        refreshData();
    }

    private AttackResult getSelectedResult() {
        int selectedViewRow = resultsTable.getSelectedRow();
        if (selectedViewRow == -1) return null;
        int modelRow = resultsTable.convertRowIndexToModel(selectedViewRow);
        if (modelRow < 0 || modelRow >= context.getAttackResults().size()) return null;
        return context.getAttackResults().get(modelRow);
    }

    private void updatePreviewFromSelection() {
        if (resultsTable.getSelectedRowCount() != 1) {
            reqViewer.setRequest(null);
            respViewer.setResponse(null);
            return;
        }
        AttackResult ar = getSelectedResult();
        if (ar == null) {
            reqViewer.setRequest(null);
            respViewer.setResponse(null);
            return;
        }
        reqViewer.setRequest(ar.getRequest());
        respViewer.setResponse(ar.getResponse());
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
        if (text == null || text.trim().isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            String needle = text.trim().toLowerCase(Locale.ROOT);
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends ResultTableModel, ? extends Integer> entry) {
                    int modelRow = entry.getIdentifier();
                    if (modelRow < 0 || modelRow >= context.getAttackResults().size()) {
                        return false;
                    }
                    AttackResult result = context.getAttackResults().get(modelRow);
                    return matchesResult(result, needle);
                }
            });
        }
        updatePreviewFromSelection();
    }

    private boolean matchesResult(AttackResult result, String needle) {
        String timestamp = result.getTimestamp() != null ? result.getTimestamp() : "";
        String method = result.getEndpoint().getMethod() != null
                ? result.getEndpoint().getMethod().toString()
                : "";
        String path = result.getEndpoint().getPath() != null ? result.getEndpoint().getPath() : "";
        String status = String.valueOf(result.getStatusCode());
        String length = String.valueOf(result.getResponseLength());
        return timestamp.toLowerCase(Locale.ROOT).contains(needle)
                || method.toLowerCase(Locale.ROOT).contains(needle)
                || path.toLowerCase(Locale.ROOT).contains(needle)
                || status.toLowerCase(Locale.ROOT).contains(needle)
                || length.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void addRightClickMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem viewRequestResponseItem = new JMenuItem("View Request/Response");
        viewRequestResponseItem.addActionListener(e -> viewSelectedRequestResponse());
        popupMenu.add(viewRequestResponseItem);

        JMenuItem sendToRepeaterItem = new JMenuItem("Send to Repeater");
        sendToRepeaterItem.addActionListener(e -> sendSelectedToRepeater());
        popupMenu.add(sendToRepeaterItem);

        resultsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = resultsTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && !resultsTable.isRowSelected(row)) {
                        resultsTable.setRowSelectionInterval(row, row);
                    }
                    if (resultsTable.getSelectedRowCount() > 0) {
                        popupMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void viewSelectedRequestResponse() {
        AttackResult result = getSelectedResult();
        if (result == null) return;

        UserInterface ui = context.api.userInterface();

        HttpRequestEditor reqEditor = ui.createHttpRequestEditor(EditorOptions.READ_ONLY);
        reqEditor.setRequest(result.getRequest());

        HttpResponseEditor respEditor = ui.createHttpResponseEditor(EditorOptions.READ_ONLY);
        respEditor.setResponse(result.getResponse());

        SwingUtilities.invokeLater(() -> {
            JDialog dialog = new JDialog((Frame) null, "Request / Response", false);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Request", reqEditor.uiComponent());
            tabs.addTab("Response", respEditor.uiComponent());
            dialog.getContentPane().add(tabs);
            dialog.setSize(1000, 800);
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }

    private void sendSelectedToRepeater() {
        AttackResult result = getSelectedResult();
        if (result == null) return;

        String tabName = result.getEndpoint().getMethod() + " " + result.getEndpoint().getPath();
        context.api.repeater().sendToRepeater(result.getRequest(), tabName);
    }

    private void exportResults(ExportFormat format) {
        List<AttackResult> results = context.getAttackResults();
        if (results.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No results available to export.", "Export Results",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Results as " + format.label);
        chooser.setFileFilter(new FileNameExtensionFilter(format.filterLabel, format.extension));
        chooser.setSelectedFile(new File("specops-results." + format.extension));
        JCheckBox includePayloads = new JCheckBox("Include request/response", false);
        chooser.setAccessory(includePayloads);

        int selection = chooser.showSaveDialog(this);
        if (selection != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File selectedFile = chooser.getSelectedFile();
        Path outputPath = ensureExtension(selectedFile.toPath(), format.extension);

        try {
            if (format == ExportFormat.CSV) {
                ResultExporter.exportCsv(results, outputPath, includePayloads.isSelected());
            } else {
                ResultExporter.exportJson(results, outputPath, includePayloads.isSelected());
            }
            JOptionPane.showMessageDialog(this, "Exported results to " + outputPath, "Export Results",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to export results: " + ex.getMessage(), "Export Results",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private Path ensureExtension(Path path, String extension) {
        String fileName = path.getFileName().toString();
        String suffix = "." + extension;
        if (fileName.toLowerCase().endsWith(suffix)) {
            return path;
        }
        return path.resolveSibling(fileName + suffix);
    }

    private enum ExportFormat {
        CSV("CSV", "csv", "CSV Files (*.csv)"),
        JSON("JSON", "json", "JSON Files (*.json)");

        private final String label;
        private final String extension;
        private final String filterLabel;

        ExportFormat(String label, String extension, String filterLabel) {
            this.label = label;
            this.extension = extension;
            this.filterLabel = filterLabel;
        }
    }
}
