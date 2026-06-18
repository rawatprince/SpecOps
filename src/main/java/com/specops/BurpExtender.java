package com.specops;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import com.specops.ui.MainTab;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;

public class BurpExtender implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("SpecOps");

        final SpecOpsContext context = new SpecOpsContext(api);
        final AtomicReference<MainTab> mainTabRef = new AtomicReference<>();

        SwingUtilities.invokeLater(() -> {
            MainTab mainTab = new MainTab(context);
            mainTabRef.set(mainTab);
            api.userInterface().registerSuiteTab("SpecOps", mainTab);
        });

        api.extension().registerUnloadingHandler(new ExtensionUnloadingHandler() {
            @Override
            public void extensionUnloaded() {
                // Stop any in-flight background work so nothing leaks across a reload.
                MainTab tab = mainTabRef.get();
                if (tab != null) {
                    tab.shutdown();
                }
                // Log to Burp's main output stream.
                api.logging().logToOutput("SpecOps unloaded.");
            }
        });

        api.logging().logToOutput("SpecOps loaded successfully by pr1nc3.");
    }
}