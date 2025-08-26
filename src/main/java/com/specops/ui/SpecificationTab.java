package com.specops.ui;

import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.specops.SpecOpsContext;
import com.specops.services.openapi.OpenApiParser;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URL;

/**
 * Represents the first tab in the SpecOps UI.
 * This tab is responsible for loading the OpenAPI/Swagger specification from a URL,
 * a local file, or by pasting text directly. It then orchestrates the parsing process.
 */
public class SpecificationTab extends JPanel {

    private final SpecOpsContext context;
    private final JTabbedPane mainPane;
    private final JTextArea specArea;
    private Runnable successCallback;

    public SpecificationTab(SpecOpsContext context, JTabbedPane mainPane) {
        this.context = context;
        this.mainPane = mainPane;

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel controlPanel = new JPanel(new BorderLayout());
        JPanel fileOperationsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel parsePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton loadFromFileButton = new JButton("Load from File");
        loadFromFileButton.addActionListener(e -> loadFromFile());
        fileOperationsPanel.add(loadFromFileButton);

        JButton loadFromUrlButton = new JButton("Load from URL");
        loadFromUrlButton.addActionListener(e -> loadFromUrl());
        fileOperationsPanel.add(loadFromUrlButton);

        JButton parseButton = new JButton("Parse Specification");
        parseButton.setFont(parseButton.getFont().deriveFont(Font.BOLD));
        parseButton.addActionListener(e -> parseSpecification());
        parsePanel.add(parseButton);

        controlPanel.add(fileOperationsPanel, BorderLayout.WEST);
        controlPanel.add(parsePanel, BorderLayout.EAST);

        specArea = new JTextArea("Paste your OpenAPI/Swagger JSON or YAML here, or use the buttons above to load a specification.");
        specArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        specArea.setWrapStyleWord(true);
        specArea.setLineWrap(true);
        JScrollPane scrollPane = new JScrollPane(specArea);

        add(controlPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Sets a callback to be executed upon successful parsing of a specification.
     * This is used to notify the MainTab to refresh the other UI tabs.
     */
    public void setSuccessCallback(Runnable callback) {
        this.successCallback = callback;
    }

    private void loadFromFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("Swagger/OpenAPI Files (json, yaml, yml)", "json", "yaml", "yml"));
        int result = fileChooser.showOpenDialog(this);

        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    StringBuilder content = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(selectedFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    return content.toString();
                }

                @Override
                protected void done() {
                    try {
                        specArea.setText(get());
                        specArea.setCaretPosition(0); // Scroll to top
                    } catch (Exception e) {
                        context.api.logging().logToError("Failed to read file: " + e.getMessage());
                        JOptionPane.showMessageDialog(SpecificationTab.this, "Error reading file: " + e.getMessage(), "File Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
    }

    private void loadFromUrl() {
        String urlString = JOptionPane.showInputDialog(
                this, "Enter the URL of the specification file:", "Load from URL", JOptionPane.PLAIN_MESSAGE);

        if (urlString == null || urlString.trim().isEmpty()) {
            return;
        }

        specArea.setText("Loading from " + urlString + "...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    // Record base host for later
                    URL url = new URL(urlString.trim());
                    String host = url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
                    context.setApiHost(host);

                    Http http = context.api.http();

                    HttpRequest request = HttpRequest.httpRequestFromUrl(urlString.trim())
                            .withHeader("User-Agent", "SpecOps/1.0");

                    HttpResponse resp = http.sendRequest(request).response();

                    int status = resp.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new IllegalStateException("HTTP " + status + " when fetching spec");
                    }

                    return resp.bodyToString();
                } catch (Exception ex) {
                    context.api.logging().logToError("Failed to load from URL via Montoya: " + ex);
                    throw new RuntimeException(ex);
                }
            }

            @Override
            protected void done() {
                try {
                    specArea.setText(get());
                    specArea.setCaretPosition(0);
                } catch (Exception e) {
                    specArea.setText("Failed to load from URL: " + e.getMessage());
                    JOptionPane.showMessageDialog(
                            SpecificationTab.this,
                            "Error loading from URL: " + e.getMessage(),
                            "URL Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        }.execute();
    }

    private void parseSpecification() {
        String specContent = specArea.getText();
        if (specContent.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Specification content is empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                OpenApiParser parser = new OpenApiParser(context);
                return parser.parse(specContent);
            }

            @Override
            protected void done() {
                try {
                    if (get()) {
                        JOptionPane.showMessageDialog(SpecificationTab.this, "Specification parsed successfully!\n"
                                + context.getEndpoints().size() + " endpoints found.", "Success", JOptionPane.INFORMATION_MESSAGE);
                        if (successCallback != null) {
                            successCallback.run();
                        }
                        mainPane.setSelectedIndex(1);
                    } else {
                        JOptionPane.showMessageDialog(SpecificationTab.this, "Failed to parse the specification. See the extension output for details.", "Parsing Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    context.api.logging().logToError("An unexpected error occurred during parsing: " + e.getMessage());
                    JOptionPane.showMessageDialog(SpecificationTab.this, "An unexpected error occurred: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
