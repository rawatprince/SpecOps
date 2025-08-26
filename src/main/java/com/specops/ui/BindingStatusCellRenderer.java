package com.specops.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Colorizes the Binding Status column.
 * Supports either the BindingStatus enum or a String value.
 */
public class BindingStatusCellRenderer extends DefaultTableCellRenderer {

    public enum BindingStatus {
        UNKNOWN,
        READY,
        MISSING_REQUIRED
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        BindingStatus status = BindingStatus.UNKNOWN;
        if (value instanceof BindingStatus) {
            status = (BindingStatus) value;
        } else if (value != null) {
            try {
                status = BindingStatus.valueOf(value.toString().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                status = BindingStatus.UNKNOWN;
            }
        }

        Color fg;

        if (!isSelected) {
            switch (status) {
                case READY:
                    fg = Color.GREEN.brighter();
                    break;
                case MISSING_REQUIRED:
                    fg = Color.RED.brighter();
                    break;
                case UNKNOWN:
                default:
                    fg = Color.BLUE.brighter();
                    break;
            }
            c.setForeground(fg);
        }

        if (c instanceof JComponent) {
            ((JComponent) c).setOpaque(true);
            ((JComponent) c).setToolTipText("Binding status: " + (value == null ? "UNKNOWN" : value.toString()));
        }
        setHorizontalAlignment(SwingConstants.CENTER);
        return c;
    }
}
