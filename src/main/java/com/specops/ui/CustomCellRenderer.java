package com.specops.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * A custom TableCellRenderer to add colors to the Parameter Store table,
 * making it easier to read and interpret at a glance.
 */
public class CustomCellRenderer extends DefaultTableCellRenderer {

    private static final Color COLOR_HEADER = new Color(0, 128, 0);       // Green
    private static final Color COLOR_QUERY = new Color(0, 100, 200);      // Blue
    private static final Color COLOR_PATH = new Color(200, 0, 0);         // Red
    private static final Color COLOR_COOKIE = new Color(150, 75, 0);      // Brown
    private static final Color COLOR_BODY = new Color(102, 51, 0);      // Brown
    private static final Color COLOR_SOURCE_PROXY = new Color(0, 128, 128); // Teal
    private static final Color COLOR_SOURCE_USER = new Color(0, 0, 150);      // Dark Blue
    private static final Color COLOR_SOURCE_GENERATED = new Color(128, 0, 128); // Purple
    private static final Color COLOR_SOURCE_DEFAULT = new Color(69, 96, 69);
    private static final Color COLOR_SOURCE_UNKNOWN = new Color(255, 102, 0);
    private static final Color COLOR_SOURCE_IMPORTED = new Color(51, 153, 255);

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        if (!isSelected) {
            c.setForeground(table.getForeground()); // Reset to default first
        }

        int modelColumn = table.convertColumnIndexToModel(column);
        String stringValue = value != null ? value.toString() : "";

        switch (modelColumn) {
            case 2: // "In" column
                if ("header".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_HEADER);
                } else if ("query".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_QUERY);
                } else if ("path".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_PATH);
                } else if ("cookie".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_COOKIE);
                } else if ("body".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_BODY);
                }
                break;

            case 5: // "Source" column
                if ("PROXY".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_SOURCE_PROXY);
                } else if ("USER".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_SOURCE_USER);
                } else if ("GENERATED".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_SOURCE_GENERATED);
                } else if ("DEFAULT".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_SOURCE_DEFAULT);
                } else if ("UNKNOWN".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_SOURCE_UNKNOWN);
                } else if ("IMPORTED".equalsIgnoreCase(stringValue)) {
                    c.setForeground(COLOR_SOURCE_IMPORTED);
                }
                break;
        }

        if (modelColumn == 5) {
            ((JComponent) c).setToolTipText("The origin of this parameter's value");
        } else {
            ((JComponent) c).setToolTipText(null);
        }

        return c;
    }
}