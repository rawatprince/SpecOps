package com.specops.ui.models;

import com.specops.SpecOpsContext;
import com.specops.domain.AttackResult;

import javax.swing.table.AbstractTableModel;

/**
 * A custom TableModel for displaying AttackResult objects in the Results JTable.
 */
public class ResultTableModel extends AbstractTableModel {

    private final SpecOpsContext context;
    private final String[] columnNames = {"Timestamp", "Method", "Path", "Server", "Status Code", "Response Length"};

    public ResultTableModel(SpecOpsContext context) {
        this.context = context;
    }

    @Override
    public int getRowCount() {
        return context.getAttackResultCount();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        // This helps the table's sorter to sort numbers correctly.
        if (columnIndex == 4 || columnIndex == 5) {
            return Integer.class;
        }
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        AttackResult result = context.getAttackResultAt(rowIndex);
        if (result == null) {
            return null;
        }
        switch (columnIndex) {
            case 0:
                return result.getTimestamp();
            case 1:
                return result.getEndpoint().getMethod().toString();
            case 2:
                return result.getEndpoint().getPath();
            case 3:
                return result.getTarget();
            case 4:
                return (int) result.getStatusCode();
            case 5:
                return result.getResponseLength();
            default:
                return null;
        }
    }
}