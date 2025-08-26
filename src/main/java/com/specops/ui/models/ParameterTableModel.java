package com.specops.ui.models;

import com.specops.SpecOpsContext;
import com.specops.domain.Parameter;

import javax.swing.table.AbstractTableModel;
import java.util.*;
import java.util.stream.Collectors;

public class ParameterTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;

    private final SpecOpsContext context;
    private final List<Parameter> rows = new ArrayList<>();

    private static final String[] COLS = {"Name", "Path", "In", "Value", "Type", "Source", "Lock"};

    public static final int COL_NAME   = 0;
    public static final int COL_PATH   = 1;
    public static final int COL_IN     = 2;
    public static final int COL_VALUE  = 3;
    public static final int COL_TYPE   = 4;
    public static final int COL_SOURCE = 5;
    public static final int COL_LOCK   = 6;

    public ParameterTableModel(SpecOpsContext context) {
        this.context = context;
        reloadFromStore();
    }

    /** Rebuild the snapshot from the context store. */
    public void reloadFromStore() {
        rows.clear();
        Collection<Parameter> values = context.getGlobalParameterStore().values();
        rows.addAll(values.stream()
                .sorted(Comparator
                        .comparing(Parameter::getIn, Comparator.nullsFirst(String::compareToIgnoreCase))
                        .thenComparing(Parameter::getName, Comparator.nullsFirst(String::compareToIgnoreCase)))
                .collect(Collectors.toList()));
    }

    public Parameter getParameterAt(int modelRow) {
        if (modelRow < 0 || modelRow >= rows.size()) return null;
        return rows.get(modelRow);
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int column) { return COLS[column]; }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return switch (columnIndex) {
            case COL_LOCK -> Boolean.class;
            default -> String.class;
        };
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == COL_VALUE || columnIndex == COL_LOCK;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Parameter p = getParameterAt(rowIndex);
        if (p == null) return null;

        return switch (columnIndex) {
            case COL_NAME -> nz(p.getName()); // leaf label
            case COL_PATH -> {
                if ("body".equalsIgnoreCase(p.getIn())) {
                    String jp = p.getJsonPath();
                    yield jp != null && !jp.isEmpty() ? jp : nz(p.getName());
                }
                // For non-body, Path equals the param name
                yield nz(p.getName());
            }
            case COL_IN     -> nz(p.getIn());
            case COL_VALUE  -> displayValue(p);
            case COL_TYPE   -> nz(p.getType());
            case COL_SOURCE -> p.getSource() != null ? p.getSource().name() : "";
            case COL_LOCK   -> p.isLocked();
            default         -> "";
        };
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return;
        Parameter p = rows.get(rowIndex);

        switch (columnIndex) {
            case COL_VALUE: {
                if (!p.isLocked()) {
                    String v = aValue == null ? "" : aValue.toString();
                    p.setValue(v);
                    p.setSource(Parameter.ValueSource.USER);
                    fireTableCellUpdated(rowIndex, columnIndex);
                    context.notifyParametersChanged();
                }
                break;
            }
            case COL_LOCK: {
                boolean lock = (aValue instanceof Boolean) && (Boolean) aValue;
                p.setLocked(lock);
                fireTableCellUpdated(rowIndex, columnIndex);
                context.notifyParametersChanged();
                break;
            }
            default:
                break;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String displayValue(Parameter p) {
        String v = p.getValue();
        if (v != null && !v.isEmpty()) return v;

        String ex = tryStringGetter(p, "getExampleValue");
        if (ex != null && !ex.isEmpty()) return ex;

        String def = tryStringGetter(p, "getDefaultValue");
        if (def != null && !def.isEmpty()) return def;

        return "";
    }

    private static String tryStringGetter(Parameter p, String methodName) {
        try {
            var m = p.getClass().getMethod(methodName);
            Object out = m.invoke(p);
            return (out instanceof String) ? (String) out : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}