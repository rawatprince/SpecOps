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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final JButton btnPause;
    private final JProgressBar progressBar;
    private final JLabel etaLabel;
    private final JLabel countLabel;
    private final JLabel statusLabel;

    private SwingWorker<Void, AttackResult> activeWorker;

    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean stopAfterCurrent = new AtomicBoolean(false);
    private final Object pauseLock = new Object();

    public EndpointsTab(SpecOpsContext context, JTabbedPane mainPane) {
        this.context = context;
        this.mainPane = mainPane;
        this.requestFactory = new RequestFactory(context);

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        btnPing = new JButton("Ping Selected");
        btnCancel = new JButton("Stop After Current");
        btnCancel.setEnabled(false);
        btnPause = new JButton("Pause");
        btnPause.setEnabled(false);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(220, 18));

        etaLabel = new JLabel("ETA: --:--");
        countLabel = new JLabel("0 / 0");
        statusLabel = new JLabel("Status: Idle");
        filterText = new JTextField(20);

        toolbar.add(btnPing);
        toolbar.add(btnCancel);
        toolbar.add(btnPause);
        toolbar.add(progressBar);
        toolbar.add(countLabel);
        toolbar.add(new JLabel("|"));
        toolbar.add(etaLabel);
        toolbar.add(new JLabel("|"));
        toolbar.add(statusLabel);
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
        endpointsTable.getColumnModel().getColumn(1).setPreferredWidth(300); // Path
        endpointsTable.getColumnModel().getColumn(2).setPreferredWidth(340); // Summary
        endpointsTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Binding Status
        endpointsTable.getColumnModel().getColumn(3).setCellRenderer(new BindingStatusCellRenderer());
        endpointsTable.getColumnModel().getColumn(4).setPreferredWidth(220); // Server

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
        context.addEndpointsUpdateListener(v -> runOnEdt(this::refreshData));
        context.addParametersUpdateListener(v -> runOnEdt(() -> {
            tableModel.recalculateBindingStatus();
            tableModel.fireTableDataChanged();
            updatePreviewPanels();
            updateCountLabel();
        }));
        context.addBindingsUpdateListener(() -> runOnEdt(() -> {
            tableModel.recalculateBindingStatus();
            tableModel.fireTableDataChanged();
            updateCountLabel();
        }));
        // Reflect server selection / iterate changes in the Server column and the request preview.
        context.addServersUpdateListener(v -> runOnEdt(() -> {
            tableModel.fireTableDataChanged();
            updatePreviewPanels();
        }));

        // Wire actions
        btnPing.addActionListener(e -> pingSelectedEndpointsWorker());
        btnCancel.addActionListener(e -> cancelActiveJob());
        btnPause.addActionListener(e -> togglePause());
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

    private void runOnEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void updatePreviewPanels() {
        int[] selectedViewRows = endpointsTable.getSelectedRows();
        if (selectedViewRows.length == 1) {
            int modelRow = endpointsTable.convertRowIndexToModel(selectedViewRows[0]);
            Endpoint selectedEndpoint = context.getEndpoints().get(modelRow);
            HttpRequest request = null;
            try {
                request = requestFactory.buildRequest(selectedEndpoint);
            } catch (Throwable t) {
                context.api.logging().logToError(
                        "Preview failed for " + selectedEndpoint.getMethod() + " " + selectedEndpoint.getPath() + ": " + t);
            }
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

        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.addActionListener(e -> endpointsTable.selectAll());
        popupMenu.add(selectAllItem);

        popupMenu.addSeparator();

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

        // Cross-platform popup trigger. On macOS the trigger can arrive on press OR release,
        // and a Control-click is reported as BUTTON1 (so isRightMouseButton would miss it).
        // e.isPopupTrigger() is the portable check.
        endpointsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { maybeShowPopup(e); }

            @Override
            public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }

            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = endpointsTable.rowAtPoint(e.getPoint());
                if (row >= 0 && !endpointsTable.isRowSelected(row)) {
                    endpointsTable.setRowSelectionInterval(row, row);
                }
                popupMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        // Make select-all reliable from the keyboard on every platform:
        // Cmd+A on macOS, Ctrl+A elsewhere, and bind both so neither chord surprises the user.
        InputMap im = endpointsTable.getInputMap(JComponent.WHEN_FOCUSED);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "selectAll");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll");
    }

    /**
     * Build requests for one endpoint without ever throwing, so a single malformed
     * endpoint can't abort an entire multi-select batch (e.g. Select All -> Send to Repeater).
     * Returns one request per server when iterate is on, a single selected-server request otherwise.
     */
    private List<HttpRequest> safeBuildRequests(Endpoint endpoint) {
        try {
            return requestFactory.buildRequestsForBulkSend(endpoint);
        } catch (Throwable t) {
            context.api.logging().logToError(
                    "Skipping endpoint " + endpoint.getMethod() + " " + endpoint.getPath()
                            + " (could not build request): " + t);
            return List.of();
        }
    }

    private void sendSelectedToRepeater() {
        boolean iterate = context.isIterateAcrossAllServers();
        for (Endpoint endpoint : getSelectedEndpoints()) {
            // safeBuildRequests yields one request per server when iterate is on,
            // and a single request for the selected server otherwise (never throws).
            List<HttpRequest> requests = safeBuildRequests(endpoint);

            // describeTarget is scheme://host[:port]; servers that differ only by base path
            // (e.g. /v1 vs /v2 on the same host) would collide. Add an ordinal only when needed
            // so the common case (distinct hosts/schemes) keeps clean tab names.
            boolean ordinalNeeded = false;
            if (iterate && requests.size() > 1) {
                Set<String> seen = new HashSet<>();
                for (HttpRequest r : requests) {
                    if (r != null && !seen.add(AttackResult.describeTarget(r))) {
                        ordinalNeeded = true;
                        break;
                    }
                }
            }

            for (int i = 0; i < requests.size(); i++) {
                HttpRequest request = requests.get(i);
                if (request == null) continue;
                String tabName = endpoint.getMethod() + " " + endpoint.getPath();
                if (iterate) {
                    tabName += " @ " + AttackResult.describeTarget(request);
                    if (ordinalNeeded) {
                        tabName += " #" + (i + 1);
                    }
                }
                context.api.repeater().sendToRepeater(request, tabName);
            }
        }
    }

    private void sendSelectedToIntruder() {
        // Send every selected endpoint (one Intruder request per server when iterate is on).
        for (Endpoint endpoint : getSelectedEndpoints()) {
            for (HttpRequest request : safeBuildRequests(endpoint)) {
                if (request != null) {
                    context.api.intruder().sendToIntruder(request);
                }
            }
        }
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

        // safeBuildRequests handles both iterate (one request per server) and single-server modes,
        // and never throws, so one malformed endpoint can't abort the whole plan.
        for (Endpoint ep : endpointsToPing) {
            List<HttpRequest> reqs = safeBuildRequests(ep);
            plan.put(ep, reqs);
            tmpCount += reqs.size();
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
        btnPause.setEnabled(true);
        btnPause.setText("Pause");
        pauseRequested.set(false);
        stopAfterCurrent.set(false);
        progressBar.setValue(0);
        progressBar.setString("0%");
        countLabel.setText("0 / " + totalCount);
        etaLabel.setText("ETA: --:--");
        statusLabel.setText("Status: Running");

        activeWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int done = 0;
                AtomicInteger completedCount = new AtomicInteger(0);

                for (Map.Entry<Endpoint, List<HttpRequest>> entry : plan.entrySet()) {
                    if (stopAfterCurrent.get()) break;

                    Endpoint endpoint = entry.getKey();
                    List<HttpRequest> requests = entry.getValue();
                    if (requests == null || requests.isEmpty()) {
                        continue;
                    }

                    for (HttpRequest request : requests) {
                        waitIfPaused();
                        if (stopAfterCurrent.get()) break;

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
                        completedCount.set(done);
                        int pct = (int) Math.round((done * 100.0) / totalCount);
                        setProgress(pct);
                        updateCountAndEtaOnEDT(done, totalCount, startNano);
                    }
                }
                if (stopAfterCurrent.get() && completedCount.get() < totalCount) {
                    runOnEdt(() -> statusLabel.setText("Status: Stopping"));
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
                btnPause.setEnabled(false);
                btnPause.setText("Pause");
                pauseRequested.set(false);
                statusLabel.setText("Status: Idle");

                if (mainPane != null) {
                    // Adjust index if your tab order changes
                    mainPane.setSelectedIndex(6);
                }

                if (stopAfterCurrent.get()) {
                    JOptionPane.showMessageDialog(EndpointsTab.this,
                            "Ping stopped after current request. See Attack Results for partial results.",
                            "Stopped", JOptionPane.WARNING_MESSAGE);
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
        runOnEdt(() -> {
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

    private void waitIfPaused() {
        synchronized (pauseLock) {
            while (pauseRequested.get() && !stopAfterCurrent.get()) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void cancelActiveJob() {
        if (activeWorker != null && !activeWorker.isDone()) {
            stopAfterCurrent.set(true);
            btnCancel.setEnabled(false);
            btnPause.setEnabled(false);
            pauseRequested.set(false);
            statusLabel.setText("Status: Stopping");
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
    }

    private void togglePause() {
        if (activeWorker == null || activeWorker.isDone()) {
            return;
        }
        if (pauseRequested.compareAndSet(false, true)) {
            btnPause.setText("Resume");
            statusLabel.setText("Status: Paused");
        } else {
            pauseRequested.set(false);
            btnPause.setText("Pause");
            statusLabel.setText("Status: Running");
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
    }

    private void updateCountLabel() {
        int selected = endpointsTable.getSelectedRowCount();
        countLabel.setText(selected + " / " + endpointsTable.getRowCount());
    }
}
