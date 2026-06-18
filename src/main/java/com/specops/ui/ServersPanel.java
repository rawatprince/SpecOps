package com.specops.ui;

import com.specops.SpecOpsContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI panel that lets users pick an OpenAPI server and edit server variables.
 * It also exposes a checkbox to iterate across all servers during bulk send.
 */
public class ServersPanel extends JPanel {

    private final SpecOpsContext context;

    private final JComboBox<String> serverCombo;
    private final JTable varsTable;
    private final VariablesModel varsModel;
    private final JCheckBox iterateAllServers;
    private final JLabel resolvedUrlBadge = new JLabel("Server: (none)");
    private final JTextField hostField = new JTextField();

    /**
     * EDT-only guard. Set while we are programmatically repopulating our own
     * widgets in response to a context notification, so the widgets' action
     * listeners don't write the change back into the context and trigger an
     * endless notify -> refresh -> notify loop.
     */
    private boolean refreshing = false;

    public ServersPanel(SpecOpsContext context) {
        super(new BorderLayout(8, 8));
        this.context = context;

        // Top row: server selector and iterate checkbox
        serverCombo = new JComboBox<>();
        serverCombo.addActionListener(e -> {
            if (refreshing) return; // programmatic repopulation, not a user action
            int idx = serverCombo.getSelectedIndex();
            if (idx >= 0) {
                context.setSelectedServerIndex(idx);
            }
            reloadVariables();
            updateResolvedBadge();
        });

        iterateAllServers = new JCheckBox("Iterate across all servers when sending");
        iterateAllServers.setSelected(context.isIterateAcrossAllServers());
        iterateAllServers.addActionListener(e -> {
            if (refreshing) return;
            context.setIterateAcrossAllServers(iterateAllServers.isSelected());
        });

        // Base host: resolves relative server URLs (e.g. "/api/v3"). Auto-filled when loading
        // from URL; editable so specs loaded from a file or pasted can still target a host.
        hostField.setToolTipText("Base host for relative server URLs (e.g. /api/v3). "
                + "Auto-filled from the URL you load; set it here for file/pasted specs.");
        hostField.setText(context.getApiHost() == null ? "" : context.getApiHost());
        Runnable commitHost = () -> {
            if (refreshing) return; // programmatic sync, not a user edit
            String h = hostField.getText() == null ? "" : hostField.getText().trim();
            context.setApiHost(h.isEmpty() ? null : h);
            context.notifyServersChanged();
        };
        hostField.addActionListener(e -> commitHost.run());
        hostField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) { commitHost.run(); }
        });

        JLabel serverLbl = new JLabel("Server");
        JLabel hostLbl = new JLabel("Base host");
        Dimension lblSize = new Dimension(70, hostLbl.getPreferredSize().height);
        serverLbl.setPreferredSize(lblSize);
        hostLbl.setPreferredSize(lblSize);

        JPanel serverRow = new JPanel(new BorderLayout(8, 8));
        serverRow.add(serverLbl, BorderLayout.WEST);
        serverRow.add(serverCombo, BorderLayout.CENTER);

        JPanel hostRow = new JPanel(new BorderLayout(8, 8));
        hostRow.add(hostLbl, BorderLayout.WEST);
        hostRow.add(hostField, BorderLayout.CENTER);

        JPanel southStack = new JPanel(new BorderLayout(8, 8));
        southStack.add(hostRow, BorderLayout.NORTH);
        southStack.add(iterateAllServers, BorderLayout.SOUTH);

        JPanel north = new JPanel(new BorderLayout(8, 8));
        north.add(serverRow, BorderLayout.NORTH);
        north.add(southStack, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        varsModel = new VariablesModel();
        varsTable = new JTable(varsModel);
        varsTable.setFillsViewportHeight(true);
        varsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        varsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        add(new JScrollPane(varsTable), BorderLayout.CENTER);

        add(resolvedPanel(), BorderLayout.SOUTH);

        loadServersIntoCombo();
        selectInitialServerIndex();
        installEnumEditors(); // after model is ready
        reloadVariables();
        updateResolvedBadge();

        context.addServersUpdateListener(_void -> SwingUtilities.invokeLater(() -> {
            refreshing = true;
            try {
                loadServersIntoCombo();
                selectInitialServerIndex();
                iterateAllServers.setSelected(context.isIterateAcrossAllServers());
                hostField.setText(context.getApiHost() == null ? "" : context.getApiHost());
                installEnumEditors();
                reloadVariables();
                updateResolvedBadge();
            } finally {
                refreshing = false;
            }
        }));
    }

    private JPanel resolvedPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        p.add(resolvedUrlBadge, BorderLayout.WEST);
        return p;
    }

    private void updateResolvedBadge() {
        int idx = serverCombo.getSelectedIndex();
        String resolved = idx < 0 ? "" : context.resolveAbsoluteServerUrl(idx);
        resolvedUrlBadge.setText("Server: " + (resolved == null || resolved.isBlank() ? "(none)" : resolved));
        resolvedUrlBadge.setToolTipText(resolvedUrlBadge.getText());
    }

    private void loadServersIntoCombo() {
        serverCombo.removeAllItems();

        OpenAPI oa = context.getOpenAPI();
        List<Server> servers = oa == null ? null : oa.getServers();
        if (servers == null || servers.isEmpty()) {
            serverCombo.addItem("(no servers in spec)");
            serverCombo.setEnabled(false);
            iterateAllServers.setEnabled(false);
            return;
        }

        // Show the absolute target (relative spec URLs like "/api/v3" resolved against the spec host).
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            String url = context.resolveAbsoluteServerUrl(i);
            String label;
            if (s.getDescription() != null && !s.getDescription().isBlank()) {
                label = s.getDescription() + "  [" + url + "]";
            } else {
                label = url;
            }
            serverCombo.addItem(label);
        }
        serverCombo.setEnabled(true);
        iterateAllServers.setEnabled(true);
    }

    private void selectInitialServerIndex() {
        int count = serverCombo.getItemCount();
        if (count == 0) return;
        int idx = Math.min(Math.max(context.getSelectedServerIndex(), 0), count - 1);
        serverCombo.setSelectedIndex(idx);
    }

    private void reloadVariables() {
        int idx = serverCombo.getSelectedIndex();
        varsModel.setServerIndex(idx);
        installEnumEditors();
    }

    private void installEnumEditors() {
        int valueCol = 1;
        TableColumn col = varsTable.getColumnModel().getColumn(valueCol);
        col.setCellEditor(new EnumAwareCellEditor(varsModel));
    }

    // Table model

    private static final class EnumAwareCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final VariablesModel model;
        private final JTextField textEditor = new JTextField();
        private final JComboBox<String> comboEditor = new JComboBox<>();
        private Component current;

        EnumAwareCellEditor(VariablesModel model) {
            this.model = model;
        }

        @Override
        public Object getCellEditorValue() {
            if (current == comboEditor) {
                Object sel = comboEditor.getSelectedItem();
                return sel == null ? "" : sel.toString();
            } else {
                return textEditor.getText();
            }
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            String enumCsv = Objects.toString(table.getModel().getValueAt(row, 3), "").trim();
            if (!enumCsv.isEmpty()) {
                comboEditor.removeAllItems();
                for (String item : enumCsv.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) comboEditor.addItem(trimmed);
                }
                comboEditor.setSelectedItem(value == null ? "" : value.toString());
                current = comboEditor;
                return comboEditor;
            } else {
                textEditor.setText(value == null ? "" : value.toString());
                current = textEditor;
                return textEditor;
            }
        }
    }

    private final class VariablesModel extends AbstractTableModel {
        private final String[] cols = {"Variable", "Value", "Default", "Enum"};
        private final List<Row> rows = new ArrayList<>();
        private int serverIndex = -1;

        void setServerIndex(int idx) {
            rows.clear();
            this.serverIndex = idx;

            OpenAPI oa = context.getOpenAPI();
            if (oa == null || oa.getServers() == null || oa.getServers().isEmpty()) {
                fireTableDataChanged();
                return;
            }
            int real = Math.min(Math.max(idx, 0), oa.getServers().size() - 1);
            Server s = oa.getServers().get(real);

            Map<String, ServerVariable> vars = s.getVariables();
            Map<String, String> overrides = context.getServerVariableOverrides(real);

            if (vars != null) {
                for (var e : vars.entrySet()) {
                    String name = e.getKey();
                    ServerVariable v = e.getValue();
                    String def = Optional.ofNullable(v.getDefault()).orElse("");
                    String current = overrides.getOrDefault(name, def);
                    List<String> enumValues = v.getEnum();
                    rows.add(new Row(name, current, def, enumValues));
                }
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 1;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.name;
                case 1 -> row.value;
                case 2 -> row.def;
                case 3 -> String.join(", ", row.enums);
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int r, int c) {
            if (c != 1) return;
            Row row = rows.get(r);
            String val = aValue == null ? "" : aValue.toString();
            row.value = val;

            Map<String, String> overrides = new ConcurrentHashMap<>(context.getServerVariableOverrides(serverIndex));
            overrides.put(row.name, val);
            context.setServerVariableOverrides(serverIndex, overrides);

            fireTableCellUpdated(r, c);
            updateResolvedBadge();
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return String.class;
        }

        private final class Row {
            final String name;
            final String def;
            final List<String> enums;
            String value;

            Row(String name, String value, String def, List<String> enums) {
                this.name = name;
                this.value = value;
                this.def = def;
                this.enums = enums == null ? List.of() : enums;
            }
        }
    }
}
