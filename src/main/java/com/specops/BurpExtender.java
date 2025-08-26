package com.specops;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import com.specops.ui.MainTab;

import javax.swing.*;

public class BurpExtender implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("SpecOps");

        final SpecOpsContext context = new SpecOpsContext(api);

        SwingUtilities.invokeLater(() -> {
            MainTab mainTab = new MainTab(context);
            api.userInterface().registerSuiteTab("SpecOps", mainTab);
        });

        api.extension().registerUnloadingHandler(new ExtensionUnloadingHandler() {
            @Override
            public void extensionUnloaded() {
                // Log to Burp's main output stream.
                api.logging().logToOutput("SpecOps unloaded.");
            }
        });

        api.logging().logToOutput("SpecOps by pr1nc3 loaded successfully.");
    }
}