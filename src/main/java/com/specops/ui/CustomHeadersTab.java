package com.specops.ui;

import com.specops.SpecOpsContext;
import com.specops.domain.rules.HeaderRule;
import com.specops.ui.models.HeaderRuleTableModel;

import javax.swing.*;
import java.awt.*;

public class CustomHeadersTab extends JPanel {
    public CustomHeadersTab(SpecOpsContext context) {
        super(new BorderLayout());

        HeaderRuleTableModel model = new HeaderRuleTableModel(context.getHeaderRules());
        JTable table = new JTable(model);

        // Scope editor: enum combo
        JComboBox<HeaderRule.Scope> scopeCombo = new JComboBox<>(HeaderRule.Scope.values());
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(scopeCombo));

        table.setRowHeight(22);


        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JCheckBox apply = new JCheckBox("Apply to Workbench requests", context.isHeadersApplyToWorkbench());
        apply.addActionListener(e -> context.setHeadersApplyToWorkbench(apply.isSelected()));
        JButton add = new JButton("Add");
        JButton remove = new JButton("Remove");
        JButton up = new JButton("Up");
        JButton down = new JButton("Down");

        add.addActionListener(e -> {
            HeaderRule r = new HeaderRule();
            r.enabled = true;
            model.addRule(r);
        });
        remove.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0) model.removeRow(i);
        });
        up.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i > 0) model.moveUp(i);
        });
        down.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0) model.moveDown(i);
        });

        top.add(apply);
        top.add(add);
        top.add(remove);
        top.add(up);
        top.add(down);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }
}
