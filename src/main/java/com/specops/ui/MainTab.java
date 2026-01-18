package com.specops.ui;

import com.specops.SpecOpsContext;

import javax.swing.*;
import java.awt.*;

/**
 * The main UI component for the extension. It acts as a container for the
 * various sub-tabs.
 */
public class MainTab extends JPanel {

    public MainTab(SpecOpsContext context) {
        setLayout(new BorderLayout());
        JTabbedPane tabbedPane = new JTabbedPane();

        SpecificationTab specTab = new SpecificationTab(context, tabbedPane);
        ParameterStoreTab paramsTab = new ParameterStoreTab(context);
        CustomHeadersTab customHeadersTab = new CustomHeadersTab(context);
        EndpointsTab endpointsTab = new EndpointsTab(context, tabbedPane);
        ResultsTab resultsTab = new ResultsTab(context);
        ServersPanel serversPanel = new ServersPanel(context);
        AuthProfilesTab authTab = new AuthProfilesTab(context);

        specTab.setSuccessCallback(() -> {
            paramsTab.refreshData();
            endpointsTab.refreshData();
            resultsTab.refreshData();
        });

        tabbedPane.addTab("1. Specification", specTab);
        tabbedPane.addTab("2. Servers", serversPanel);
        tabbedPane.addTab("3. Global Parameters", paramsTab);
        tabbedPane.addTab("4. Auth Profiles", authTab);
        tabbedPane.addTab("5. Custom Headers", customHeadersTab);
        tabbedPane.addTab("6. Endpoints Workbench", endpointsTab);
        tabbedPane.addTab("7. Attack Results", resultsTab);
        tabbedPane.addTab("About", new AboutMeTab("SpecOps", "1.1.2"));


        add(tabbedPane, BorderLayout.CENTER);
    }
}
