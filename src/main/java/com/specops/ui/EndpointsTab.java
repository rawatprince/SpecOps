package com.specops.ui;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import com.specops.SpecOpsContext;
import com.specops.domain.AttackResult;
import com.specops.domain.Endpoint;
import com.specops.services.request.RequestFactory;
import com.specops.ui.models.EndpointTableModel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Endpoints workbench with cancellable bulk pings, progress, and ETA.
 * - Uses a precomputed plan that expands each selected endpoint into 1..N HttpRequests.
 * - If "iterate across all servers" is enabled, the plan contains one request per server.
 * - Progress bar and ETA reflect the true total number of requests.
 */
public class EndpointsTab extends JPanel {
    private final SpecOpsContext context;
    private final EndpointTableModel tableModel;
    private final JTable endpointsTable;
    private final HttpRequestEditor requestViewer;
    private final RequestFactory requestFactory;
    private final JTabbedPane mainPane;
    private final TableRowSorter<EndpointTableModel> sorter;
    private final JTextField filterText;

    private final JButton btnPing;
    private final JButton btnCancel;
    private final JProgressBar progressBar;
    private final JLabel etaLabel;
    private final JLabel countLabel;

    private SwingWorker<Void, AttackResult> activeWorker;

    public EndpointsTab(SpecOpsContext context, JTabbedPane mainPane) {
        this.context = context;
        this.mainPane = mainPane;
        this.requestFactory = new RequestFactory(context);

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        btnPing = new JButton("Ping Selected");
        btnCancel = new JButton("Cancel");
        btnCancel.setEnabled(false);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(220, 18));

        etaLabel = new JLabel("ETA: --:--");
        countLabel = new JLabel("0 / 0");
        filterText = new JTextField(20);

        toolbar.add(btnPing);
        toolbar.add(btnCancel);
        toolbar.add(progressBar);
        toolbar.add(countLabel);
        toolbar.add(new JLabel("|"));
        toolbar.add(etaLabel);
        toolbar.add(new JLabel("Filter:"));
        toolbar.add(filterText);

        add(toolbar, BorderLayout.NORTH);

        tableModel = new EndpointTableModel(context);
        sorter = new TableRowSorter<>(tableModel);
        endpointsTable = new JTable(tableModel);
        endpointsTable.setRowSorter(sorter);
        endpointsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        endpointsTable.setDefaultRenderer(Object.class, new CustomCellRenderer());

        endpointsTable.getColumnModel().getColumn(0).setPreferredWidth(60);  // Method
        endpointsTable.getColumnModel().getColumn(1).setPreferredWidth(350); // Path
        endpointsTable.getColumnModel().getColumn(2).setPreferredWidth(400); // Summary
        endpointsTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Binding Status
        endpointsTable.getColumnModel().getColumn(3).setCellRenderer(new BindingStatusCellRenderer());

        endpointsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreviewPanels();
                updateCountLabel();
            }
        });

        addRightClickMenu();

        // Split Pane (table on top, request preview at bottom)
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplitPane.setTopComponent(new JScrollPane(endpointsTable));
        mainSplitPane.setResizeWeight(0.5);

        // Request preview
        requestViewer = context.api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.add(new JLabel("Request Preview", JLabel.CENTER), BorderLayout.NORTH);
        requestPanel.add(requestViewer.uiComponent(), BorderLayout.CENTER);

        mainSplitPane.setBottomComponent(requestPanel);
        add(mainSplitPane, BorderLayout.CENTER);

        // Refresh table when endpoints or parameters change
        context.setEndpointsUpdateListener(v -> refreshData());
        context.setParametersUpdateListener(v -> {
            SwingUtilities.invokeLater(() -> {
                tableModel.recalculateBindingStatus();
                tableModel.fireTableDataChanged();
                updatePreviewPanels();
                updateCountLabel();
            });
        });
        context.setBindingsUpdateListener(() -> {
            SwingUtilities.invokeLater(() -> {
                tableModel.recalculateBindingStatus();
                tableModel.fireTableDataChanged();
                updateCountLabel();
            });
        });

        // Wire actions
        btnPing.addActionListener(e -> pingSelectedEndpointsWorker());
        btnCancel.addActionListener(e -> cancelActiveJob());
        setupFilterListener();

        // Init counts
        updateCountLabel();
    }

    public void refreshData() {
        tableModel.recalculateBindingStatus();
        tableModel.fireTableDataChanged();
        updatePreviewPanels();
        updateCountLabel();
    }

    private void updatePreviewPanels() {
        int[] selectedViewRows = endpointsTable.getSelectedRows();
        if (selectedViewRows.length == 1) {
            int modelRow = endpointsTable.convertRowIndexToModel(selectedViewRows[0]);
            Endpoint selectedEndpoint = context.getEndpoints().get(modelRow);
            HttpRequest request = requestFactory.buildRequest(selectedEndpoint);
            requestViewer.setRequest(request);
        } else {
            requestViewer.setRequest(null);
        }
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
                public boolean include(Entry<? extends EndpointTableModel, ? extends Integer> entry) {
                    int modelRow = entry.getIdentifier();
                    Endpoint endpoint = context.getEndpoints().get(modelRow);
                    return endpoint != null && matchesEndpoint(endpoint, needle);
                }
            });
        }
        updatePreviewPanels();
        updateCountLabel();
    }

    private boolean matchesEndpoint(Endpoint endpoint, String needle) {
        String method = endpoint.getMethod() != null ? endpoint.getMethod().toString() : "";
        String path = endpoint.getPath() != null ? endpoint.getPath() : "";
        String summary = endpoint.getSummary() != null ? endpoint.getSummary() : "";
        if (method.toLowerCase(Locale.ROOT).contains(needle)
                || path.toLowerCase(Locale.ROOT).contains(needle)
                || summary.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }

        if (endpoint.getOperation() != null && endpoint.getOperation().getTags() != null) {
            for (String tag : endpoint.getOperation().getTags()) {
                if (tag != null && tag.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<Endpoint> getSelectedEndpoints() {
        List<Endpoint> selected = new ArrayList<>();
        int[] selectedViewRows = endpointsTable.getSelectedRows();
        for (int viewRow : selectedViewRows) {
            int modelRow = endpointsTable.convertRowIndexToModel(viewRow);
            selected.add(context.getEndpoints().get(modelRow));
        }
        return selected;
    }

    private void addRightClickMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem sendToRepeaterItem = new JMenuItem("Send to Repeater");
        sendToRepeaterItem.addActionListener(e -> sendSelectedToRepeater());
        popupMenu.add(sendToRepeaterItem);

        JMenuItem sendToIntruderItem = new JMenuItem("Send to Intruder");
        sendToIntruderItem.addActionListener(e -> sendSelectedToIntruder());
        popupMenu.add(sendToIntruderItem);

        popupMenu.addSeparator();

        JMenuItem pingEndpointsItem = new JMenuItem("Ping Endpoints");
        pingEndpointsItem.addActionListener(e -> pingSelectedEndpointsWorker());
        popupMenu.add(pingEndpointsItem);

        endpointsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = endpointsTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && !endpointsTable.isRowSelected(row)) {
                        endpointsTable.setRowSelectionInterval(row, row);
                    }
                    if (endpointsTable.getSelectedRowCount() > 0) {
                        popupMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void sendSelectedToRepeater() {
        for (Endpoint endpoint : getSelectedEndpoints()) {
            HttpRequest request = requestFactory.buildRequest(endpoint);
            if (request != null) {
                String tabName = endpoint.getMethod() + " " + endpoint.getPath();
                context.api.repeater().sendToRepeater(request, tabName);
            }
        }
    }

    private void sendSelectedToIntruder() {
        getSelectedEndpoints().stream().findFirst().ifPresent(endpoint -> {
            HttpRequest request = requestFactory.buildRequest(endpoint);
            if (request != null) {
                context.api.intruder().sendToIntruder(request);
            }
        });
    }

    private void pingSelectedEndpointsWorker() {
        if (activeWorker != null && !activeWorker.isDone()) {
            return;
        }

        List<Endpoint> endpointsToPing = getSelectedEndpoints();
        if (endpointsToPing.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No endpoints selected.", "Nothing to do", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final Map<Endpoint, List<HttpRequest>> plan = new LinkedHashMap<>();
        int tmpCount = 0;

        if (context.isIterateAcrossAllServers()) {
            for (Endpoint ep : endpointsToPing) {
                List<HttpRequest> reqs = requestFactory.buildRequestsForBulkSend(ep);
                plan.put(ep, reqs);
                tmpCount += reqs.size();
            }
        } else {
            for (Endpoint ep : endpointsToPing) {
                HttpRequest req = requestFactory.buildRequest(ep);
                List<HttpRequest> list = (req == null) ? List.of() : List.of(req);
                plan.put(ep, list);
                tmpCount += list.size();
            }
        }

        final int totalCount = tmpCount;

        if (totalCount == 0) {
            JOptionPane.showMessageDialog(this, "Nothing to send. Check bindings or server configuration.", "Nothing to do", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final long startNano = System.nanoTime();

        // UI state
        btnPing.setEnabled(false);
        btnCancel.setEnabled(true);
        progressBar.setValue(0);
        progressBar.setString("0%");
        countLabel.setText("0 / " + totalCount);
        etaLabel.setText("ETA: --:--");

        activeWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int done = 0;

                for (Map.Entry<Endpoint, List<HttpRequest>> entry : plan.entrySet()) {
                    if (isCancelled()) break;

                    Endpoint endpoint = entry.getKey();
                    List<HttpRequest> requests = entry.getValue();
                    if (requests == null || requests.isEmpty()) {
                        continue;
                    }

                    for (HttpRequest request : requests) {
                        if (isCancelled()) break;

                        try {
                            var requestResponse = context.api.http().sendRequest(request);
                            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
                            HttpResponse response = requestResponse.response();
                            HttpRequest finalRequest = requestResponse.request();

                            publish(new AttackResult(endpoint, finalRequest, response, timestamp));
                        } catch (Throwable t) {
                            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
                            publish(new AttackResult(endpoint, request, null, timestamp));
                        }

                        done++;
                        int pct = (int) Math.round((done * 100.0) / totalCount);
                        setProgress(pct);
                        updateCountAndEtaOnEDT(done, totalCount, startNano);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<AttackResult> chunks) {
                for (AttackResult ar : chunks) {
                    context.addAttackResult(ar);
                }
            }

            @Override
            protected void done() {
                btnPing.setEnabled(true);
                btnCancel.setEnabled(false);

                if (mainPane != null) {
                    // Adjust index if your tab order changes
                    mainPane.setSelectedIndex(6);
                }

                if (isCancelled()) {
                    JOptionPane.showMessageDialog(EndpointsTab.this,
                            "Ping cancelled. See Attack Results for partial results.",
                            "Cancelled", JOptionPane.WARNING_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(EndpointsTab.this,
                            "Ping complete. See Attack Results for details.",
                            "Done", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        };

        // Bind progress to the bar
        activeWorker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int v = (Integer) evt.getNewValue();
                progressBar.setValue(v);
                progressBar.setString(v + "%");
            }
        });

        activeWorker.execute();
    }

    private void updateCountAndEtaOnEDT(int done, int total, long startNano) {
        SwingUtilities.invokeLater(() -> {
            countLabel.setText(done + " / " + total);
            long elapsedNanos = System.nanoTime() - startNano;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

            long avgMsPerItem = done > 0 ? Math.max(1, elapsedMs / done) : 0;
            long remainingItems = Math.max(0, total - done);
            long remainingMs = remainingItems * avgMsPerItem;

            etaLabel.setText("ETA: " + formatDuration(remainingMs));
        });
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long minutes = totalSec / 60;
        long seconds = totalSec % 60;
        if (minutes > 99) {
            return ">99m";
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void cancelActiveJob() {
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
            btnCancel.setEnabled(false);
        }
    }

    private void updateCountLabel() {
        int selected = endpointsTable.getSelectedRowCount();
        countLabel.setText(selected + " / " + endpointsTable.getRowCount());
    }
}
