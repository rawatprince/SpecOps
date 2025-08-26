package com.specops.ui;

import com.specops.SpecOpsContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Lists security schemes from the OpenAPI spec and lets the user bind credentials.
 * Values are stored in SpecOpsContext so RequestFactory can inject them automatically.
 */
public class AuthProfilesTab extends JPanel {

    private final SpecOpsContext context;
    private final AuthTableModel tableModel;
    private final JTable table;

    public AuthProfilesTab(SpecOpsContext ctx) {
        super(new BorderLayout(8, 8));
        this.context = ctx;

        JLabel title = new JLabel("Auth Profiles", JLabel.LEFT);
        title.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        add(title, BorderLayout.NORTH);

        tableModel = new AuthTableModel();
        table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Column sizing
        table.getColumnModel().getColumn(0).setPreferredWidth(180); // Scheme
        table.getColumnModel().getColumn(1).setPreferredWidth(110); // Type
        table.getColumnModel().getColumn(2).setPreferredWidth(120); // In
        table.getColumnModel().getColumn(3).setPreferredWidth(160); // Name or Header
        table.getColumnModel().getColumn(4).setPreferredWidth(240); // Scopes
        table.getColumnModel().getColumn(5).setPreferredWidth(380); // Value

        // Sorting
        TableRowSorter<AuthTableModel> sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);

        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshBtn = new JButton("Refresh");
        JButton applyBtn = new JButton("Apply");
        actions.add(refreshBtn);
        actions.add(applyBtn);
        add(actions, BorderLayout.SOUTH);

        refreshBtn.addActionListener(e -> reloadFromSpec());
        applyBtn.addActionListener(e -> {
            table.editCellAt(-1, -1); // ensure any active editor stops
            tableModel.commitToContext(context);
            JOptionPane.showMessageDialog(this, "Auth profiles saved.", "Saved", JOptionPane.INFORMATION_MESSAGE);
            // Notify preview consumers to rebuild requests with new auth
            context.notifyServersChanged();
        });

        // Initial load
        reloadFromSpec();

        // When spec or server settings change, refresh our view
        context.setServersUpdateListener(v -> SwingUtilities.invokeLater(this::reloadFromSpec));
    }

    private void reloadFromSpec() {
        OpenAPI oa = context.getOpenAPI();
        if (oa == null || oa.getComponents() == null || oa.getComponents().getSecuritySchemes() == null) {
            tableModel.setRows(List.of());
            return;
        }
        Map<String, SecurityScheme> schemes = oa.getComponents().getSecuritySchemes();

        // Gather effective security requirements to show scopes hints
        List<SecurityRequirement> effectiveReqs = new ArrayList<>();
        if (oa.getSecurity() != null) effectiveReqs.addAll(oa.getSecurity());
        // Endpoint specific scopes hints are not available here. This tab is global.

        List<AuthRow> rows = new ArrayList<>();
        for (Map.Entry<String, SecurityScheme> e : schemes.entrySet()) {
            String name = e.getKey();
            SecurityScheme s = e.getValue();

            String type = s.getType() == null ? "" : s.getType().toString();
            String inStr = s.getIn() == null ? "" : s.getIn().toString();
            String headerOrName = "";
            if (s.getType() == SecurityScheme.Type.APIKEY) {
                headerOrName = s.getName() == null ? "" : s.getName();
            } else if (s.getType() == SecurityScheme.Type.HTTP) {
                headerOrName = s.getScheme() == null ? "" : s.getScheme();
            }

            String scopesHint = scopesSummaryForScheme(name, effectiveReqs);
            String existing = context.getAuthToken(name);
            rows.add(new AuthRow(name, type, inStr, headerOrName, scopesHint, existing == null ? "" : existing));
        }
        tableModel.setRows(rows);
    }

    private String scopesSummaryForScheme(String schemeName, List<SecurityRequirement> reqs) {
        if (reqs == null || reqs.isEmpty()) return "";
        // Collect distinct scopes from global requirements for this scheme
        Set<String> scopes = new LinkedHashSet<>();
        for (SecurityRequirement r : reqs) {
            List<String> list = r.get(schemeName);
            if (list != null) scopes.addAll(list);
        }
        if (scopes.isEmpty()) return "";
        return String.join(" ", scopes);
    }

    // table model

    static class AuthRow {
        final String scheme;
        final String type;
        final String inWhere;
        final String nameOrHeader;
        final String scopesHint;
        String value;

        AuthRow(String scheme, String type, String inWhere, String nameOrHeader, String scopesHint, String value) {
            this.scheme = scheme;
            this.type = type;
            this.inWhere = inWhere;
            this.nameOrHeader = nameOrHeader;
            this.scopesHint = scopesHint;
            this.value = value;
        }
    }

    static class AuthTableModel extends AbstractTableModel {
        private final String[] cols = new String[]{"Scheme", "Type", "In", "Name or Header", "Scopes", "Value"};
        private List<AuthRow> rows = new ArrayList<>();

        public void setRows(List<AuthRow> newRows) {
            rows = new ArrayList<>(newRows);
            fireTableDataChanged();
        }

        public void commitToContext(SpecOpsContext ctx) {
            for (AuthRow r : rows) {
                ctx.setAuthToken(r.scheme, r.value == null ? "" : r.value);
            }
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
            return c == 5;
        }

        @Override
        public Object getValueAt(int r, int c) {
            AuthRow row = rows.get(r);
            return switch (c) {
                case 0 -> row.scheme;
                case 1 -> row.type;
                case 2 -> row.inWhere;
                case 3 -> row.nameOrHeader;
                case 4 -> row.scopesHint;
                case 5 -> row.value;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int r, int c) {
            if (c == 5) {
                rows.get(r).value = aValue == null ? "" : String.valueOf(aValue);
            }
        }
    }
}
