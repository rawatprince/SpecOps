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

    public ServersPanel(SpecOpsContext context) {
        super(new BorderLayout(8, 8));
        this.context = context;

        // Top row: server selector and iterate checkbox
        serverCombo = new JComboBox<>();
        serverCombo.addActionListener(e -> {
            int idx = serverCombo.getSelectedIndex();
            if (idx >= 0) {
                context.setSelectedServerIndex(idx);
            }
            reloadVariables();
            updateResolvedBadge();
        });

        iterateAllServers = new JCheckBox("Iterate across all servers when sending");
        iterateAllServers.setSelected(context.isIterateAcrossAllServers());
        iterateAllServers.addActionListener(e ->
                context.setIterateAcrossAllServers(iterateAllServers.isSelected())
        );

        JPanel north = new JPanel(new BorderLayout(8, 8));
        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.add(new JLabel("Server"), BorderLayout.WEST);
        left.add(serverCombo, BorderLayout.CENTER);
        north.add(left, BorderLayout.CENTER);
        north.add(iterateAllServers, BorderLayout.SOUTH);
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

        context.setServersUpdateListener(_void -> SwingUtilities.invokeLater(() -> {
            loadServersIntoCombo();
            selectInitialServerIndex();
            installEnumEditors();
            reloadVariables();
            updateResolvedBadge();
        }));
    }

    private JPanel resolvedPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        p.add(resolvedUrlBadge, BorderLayout.WEST);
        return p;
    }

    private void updateResolvedBadge() {
        String resolved = resolveSelectedServerUrl();
        resolvedUrlBadge.setText("Server: " + (resolved == null || resolved.isBlank() ? "(none)" : resolved));
        resolvedUrlBadge.setToolTipText(resolvedUrlBadge.getText());
    }

    private String resolveSelectedServerUrl() {
        OpenAPI oa = context.getOpenAPI();
        if (oa == null || oa.getServers() == null || oa.getServers().isEmpty()) return null;
        int idx = Math.min(Math.max(context.getSelectedServerIndex(), 0), oa.getServers().size() - 1);
        Server s = oa.getServers().get(idx);
        String template = s.getUrl() == null ? "" : s.getUrl();
        Map<String, String> values = effectiveVariableValues(s, idx);
        String resolved = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            String token = "{" + e.getKey() + "}";
            resolved = resolved.replace(token, e.getValue() == null ? "" : e.getValue());
        }
        return resolved;
    }

    private Map<String, String> effectiveVariableValues(Server server, int serverIndex) {
        Map<String, String> out = new HashMap<>();
        Map<String, ServerVariable> specVars = server.getVariables();
        if (specVars != null) {
            for (var en : specVars.entrySet()) {
                out.put(en.getKey(), Optional.ofNullable(en.getValue().getDefault()).orElse(""));
            }
        }
        Map<String, String> overrides = context.getServerVariableOverrides(serverIndex);
        for (var en : overrides.entrySet()) {
            if (en.getValue() != null && !en.getValue().isBlank()) {
                out.put(en.getKey(), en.getValue());
            }
        }
        return out;
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

        for (Server s : servers) {
            String label;
            if (s.getDescription() != null && !s.getDescription().isBlank()) {
                label = s.getDescription() + "  [" + s.getUrl() + "]";
            } else {
                label = s.getUrl();
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
