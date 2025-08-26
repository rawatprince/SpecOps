package com.specops.ui.models;

import com.specops.domain.rules.HeaderRule;

import javax.swing.table.AbstractTableModel;
import java.util.List;

public class HeaderRuleTableModel extends AbstractTableModel {
    private final String[] cols = {"Enabled", "Header", "Value", "Scope", "Match", "Overwrite"};
    private final List<HeaderRule> rules;

    public HeaderRuleTableModel(List<HeaderRule> rules) {
        this.rules = rules;
    }

    @Override
    public int getRowCount() {
        return rules.size();
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
        return true;
    }

    @Override
    public Object getValueAt(int r, int c) {
        HeaderRule x = rules.get(r);
        switch (c) {
            case 0:
                return x.enabled;
            case 1:
                return x.name;
            case 2:
                return x.value;
            case 3:
                return x.scope;
            case 4:
                return x.match;
            case 5:
                return x.overwrite;
            default:
                return null;
        }
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        HeaderRule x = rules.get(r);
        switch (c) {
            case 0:
                x.enabled = v != null && (Boolean) v;
                break;
            case 1:
                x.name = v == null ? "" : v.toString();
                break;
            case 2:
                x.value = v == null ? "" : v.toString();
                break;
            case 3:
                if (v instanceof HeaderRule.Scope) x.scope = (HeaderRule.Scope) v;
                break;
            case 4:
                x.match = v == null ? "" : v.toString();
                break;
            case 5:
                x.overwrite = v != null && (Boolean) v;
                break;
        }
        fireTableRowsUpdated(r, r);
    }


    @Override
    public Class<?> getColumnClass(int c) {
        switch (c) {
            case 0:
                return Boolean.class;                 // Enabled
            case 1:
                return String.class;                  // Header
            case 2:
                return String.class;                  // Value
            case 3:
                return HeaderRule.Scope.class; // Scope (enum)
            case 4:
                return String.class;                  // Match
            case 5:
                return Boolean.class;                 // Overwrite
            default:
                return Object.class;
        }
    }


    // helpers for Add, Remove, Move
    public void addRule(HeaderRule r) {
        rules.add(r);
        fireTableRowsInserted(rules.size() - 1, rules.size() - 1);
    }

    public void removeRow(int r) {
        rules.remove(r);
        fireTableRowsDeleted(r, r);
    }

    public void moveUp(int r) {
        if (r > 0) {
            HeaderRule tmp = rules.get(r);
            rules.set(r, rules.get(r - 1));
            rules.set(r - 1, tmp);
            fireTableDataChanged();
        }
    }

    public void moveDown(int r) {
        if (r < rules.size() - 1) {
            HeaderRule tmp = rules.get(r);
            rules.set(r, rules.get(r + 1));
            rules.set(r + 1, tmp);
            fireTableDataChanged();
        }
    }
}
