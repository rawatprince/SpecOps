package com.specops.ui.models;

import com.specops.SpecOpsContext;
import com.specops.domain.Endpoint;
import com.specops.domain.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import javax.swing.table.AbstractTableModel;
import java.util.Map;

/**
 * A custom TableModel for displaying Endpoint objects in the Endpoints Workbench JTable.
 */
public class EndpointTableModel extends AbstractTableModel {

    private final SpecOpsContext context;
    private final String[] columnNames = {"Method", "Path", "Summary", "Binding Status"};

    public EndpointTableModel(SpecOpsContext context) {
        this.context = context;
    }

    @Override
    public int getRowCount() {
        return context.getEndpoints().size();
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
    public Object getValueAt(int rowIndex, int columnIndex) {
        Endpoint endpoint = context.getEndpoints().get(rowIndex);
        switch (columnIndex) {
            case 0:
                return endpoint.getMethod().toString();
            case 1:
                return endpoint.getPath();
            case 2:
                return endpoint.getSummary();
            case 3:
                return endpoint.getBindingStatus();
            default:
                return null;
        }
    }

    public void recalculateBindingStatus() {
        Map<String, Parameter> paramStore = context.getGlobalParameterStore();
        if (paramStore == null || paramStore.isEmpty()) return;

        for (Endpoint endpoint : context.getEndpoints()) {
            boolean allRequiredMet = true;

            // Check all required parameters from path and operation
            for (io.swagger.v3.oas.models.parameters.Parameter specParam
                    : endpoint.getAllParameters(context.getOpenAPI())) {
                if (Boolean.TRUE.equals(specParam.getRequired())) {
                    // Use canonical key "in:name" in lowercase
                    String key = com.specops.SpecOpsContext.canonicalKey(
                            new com.specops.domain.Parameter(
                                    specParam.getName(),
                                    specParam.getIn(),
                                    null
                            )
                    );

                    Parameter stored = paramStore.get(key);
                    if (stored == null) {
                        allRequiredMet = false;
                        break;
                    }
                    String val = stored.getValue();
                    if (val == null || val.isEmpty()) {
                        allRequiredMet = false;
                        break;
                    }
                }
            }

            io.swagger.v3.oas.models.parameters.RequestBody requestBody =
                    endpoint.getOperation().getRequestBody();

            if (allRequiredMet && requestBody != null
                    && Boolean.TRUE.equals(requestBody.getRequired())) {
                // For now we treat body as satisfiable when other required inputs are present
                endpoint.setBindingStatus(Endpoint.BindingStatus.READY);
            } else if (allRequiredMet) {
                endpoint.setBindingStatus(Endpoint.BindingStatus.READY);
            } else {
                endpoint.setBindingStatus(Endpoint.BindingStatus.MISSING_REQUIRED);
            }
        }
    }


}