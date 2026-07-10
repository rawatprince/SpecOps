package com.specops.ui;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import com.specops.SpecOpsContext;
import com.specops.services.openapi.OpenApiParser;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Represents the first tab in the SpecOps UI.
 * This tab is responsible for loading the OpenAPI/Swagger specification from a URL,
 * a local file, or by pasting text directly. It then orchestrates the parsing process.
 */
public class SpecificationTab extends JPanel {

    private final SpecOpsContext context;
    private final JTabbedPane mainPane;
    private final RawEditor specArea;
    private SpecificationOrigin specificationOrigin = SpecificationOrigin.MANUAL;
    private String stagedRemoteBaseHost;
    private Runnable successCallback;

    private enum SpecificationOrigin {
        MANUAL,
        LOCAL_FILE,
        REMOTE_URL
    }

    private record LoadedRemoteSpecification(String contents, String baseHost) {}

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

        specArea = context.api.userInterface().createRawEditor(EditorOptions.WRAP_LINES);
        specArea.setEditable(true);
        setSpecificationText("Paste your OpenAPI/Swagger JSON or YAML here, or use the buttons above to load a specification.");

        add(controlPanel, BorderLayout.NORTH);
        add(specArea.uiComponent(), BorderLayout.CENTER);
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
                    try (BufferedReader reader = Files.newBufferedReader(selectedFile.toPath(), StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append(System.lineSeparator());
                        }
                    }
                    return content.toString();
                }

                @Override
                protected void done() {
                    try {
                        setSpecificationText(get());
                        specificationOrigin = SpecificationOrigin.LOCAL_FILE;
                        stagedRemoteBaseHost = null;
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

        setSpecificationText("Loading from " + urlString + "...");
        specificationOrigin = SpecificationOrigin.MANUAL;
        stagedRemoteBaseHost = null;

        new SwingWorker<LoadedRemoteSpecification, Void>() {
            @Override
            protected LoadedRemoteSpecification doInBackground() {
                try {
                    URL url = new URL(urlString.trim());
                    String host = url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());

                    Http http = context.api.http();

                    HttpRequest request = HttpRequest.httpRequestFromUrl(urlString.trim())
                            .withHeader("User-Agent", "SpecOps/1.0");

                    HttpResponse resp = http.sendRequest(
                            request,
                            RequestOptions.requestOptions().withUpstreamTLSVerification()
                    ).response();

                    int status = resp.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new IllegalStateException("HTTP " + status + " when fetching spec");
                    }

                    byte[] bodyBytes = resp.body().getBytes();
                    return new LoadedRemoteSpecification(
                            new String(bodyBytes, StandardCharsets.UTF_8),
                            host
                    );
                } catch (Exception ex) {
                    context.api.logging().logToError("Failed to load from URL via Montoya: " + ex);
                    throw new RuntimeException(ex);
                }
            }

            @Override
            protected void done() {
                try {
                    LoadedRemoteSpecification loaded = get();
                    setSpecificationText(loaded.contents());
                    specificationOrigin = SpecificationOrigin.REMOTE_URL;
                    stagedRemoteBaseHost = loaded.baseHost();
                } catch (Exception e) {
                    String message = failureMessage(e);
                    setSpecificationText("Failed to load from URL: " + message);
                    JOptionPane.showMessageDialog(
                            SpecificationTab.this,
                            "Error loading from URL: " + message,
                            "URL Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        }.execute();
    }

    private void parseSpecification() {
        String specContent = new String(specArea.getContents().getBytes(), StandardCharsets.UTF_8);
        if (specContent.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Specification content is empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SpecificationOrigin originAtParse = specificationOrigin;
        String remoteBaseHostAtParse = stagedRemoteBaseHost;

        new SwingWorker<Optional<OpenApiParser.ParsedSpecification>, Void>() {
            @Override
            protected Optional<OpenApiParser.ParsedSpecification> doInBackground() {
                OpenApiParser parser = new OpenApiParser(context);
                return parser.parse(specContent);
            }

            @Override
            protected void done() {
                try {
                    Optional<OpenApiParser.ParsedSpecification> parsed = get();
                    if (parsed.isEmpty()) {
                        JOptionPane.showMessageDialog(SpecificationTab.this, "Failed to parse the specification. See the extension output for details.", "Parsing Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    OpenApiParser.ParsedSpecification staged = parsed.get();
                    if (originAtParse == SpecificationOrigin.REMOTE_URL
                            && !confirmRemoteAutomaticValues(staged.automaticValues())) {
                        return;
                    }

                    if (originAtParse == SpecificationOrigin.REMOTE_URL) {
                        context.setApiHost(remoteBaseHostAtParse);
                    }
                    context.resetModel(staged.openAPI(), staged.endpoints(), staged.parameters());

                    JOptionPane.showMessageDialog(SpecificationTab.this, "Specification parsed successfully!\n"
                            + staged.endpoints().size() + " endpoints found.", "Success", JOptionPane.INFORMATION_MESSAGE);
                    if (successCallback != null) {
                        successCallback.run();
                    }
                    mainPane.setSelectedIndex(1);
                } catch (Exception e) {
                    context.api.logging().logToError("An unexpected error occurred during parsing: " + e.getMessage());
                    JOptionPane.showMessageDialog(SpecificationTab.this, "An unexpected error occurred: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setSpecificationText(String text) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        specArea.setContents(ByteArray.byteArray(bytes));
        specArea.setCaretPosition(0);
    }

    private boolean confirmRemoteAutomaticValues(List<OpenApiParser.AutomaticValue> values) {
        if (values.isEmpty()) {
            return true;
        }

        List<OpenApiParser.AutomaticValue> sorted = values.stream()
                .sorted(Comparator
                        .comparing(OpenApiParser.AutomaticValue::endpoint, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(OpenApiParser.AutomaticValue::location, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(OpenApiParser.AutomaticValue::parameter, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(OpenApiParser.AutomaticValue::source, String.CASE_INSENSITIVE_ORDER))
                .toList();

        String[] columns = {"Endpoint", "Location", "Parameter / path", "Source", "Value preview"};
        Object[][] rows = sorted.stream()
                .map(value -> new Object[]{
                        escapeForDisplay(value.endpoint()),
                        escapeForDisplay(value.location()),
                        escapeForDisplay(value.parameter()),
                        escapeForDisplay(value.source()),
                        escapeForDisplay(value.value())
                })
                .toArray(Object[][]::new);

        DefaultTableModel model = new DefaultTableModel(rows, columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(190);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(300);

        JScrollPane tableScrollPane = new JScrollPane(table);
        tableScrollPane.setPreferredSize(new Dimension(900, Math.min(340, 70 + rows.length * 22)));

        // Burp's look-and-feel disables HTML rendering in Swing labels, so keep the
        // header as plain-text lines rather than an <html> block.
        Box header = Box.createVerticalBox();
        header.add(new JLabel("This remote specification supplies non-empty defaults, examples, or enum values "
                + "that can be used in generated requests."));
        header.add(new JLabel("Review them before applying the specification."));

        JPanel message = new JPanel(new BorderLayout(0, 10));
        message.add(header, BorderLayout.NORTH);
        message.add(tableScrollPane, BorderLayout.CENTER);

        String apply = "Apply values";
        String cancel = "Cancel parse";
        int choice = JOptionPane.showOptionDialog(
                this,
                message,
                "Confirm values from remote specification",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[]{apply, cancel},
                cancel
        );
        return choice == 0;
    }

    private static String escapeForDisplay(String value) {
        if (value == null) return "";

        StringBuilder escaped = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '<' -> escaped.append("\\u003C");
                case '>' -> escaped.append("\\u003E");
                default -> {
                    if (Character.isISOControl(codePoint)) {
                        escaped.append(String.format("\\u%04X", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });

        int maxPreviewLength = 240;
        return escaped.length() > maxPreviewLength
                ? escaped.substring(0, maxPreviewLength) + "…"
                : escaped.toString();
    }

    private static String failureMessage(Exception exception) {
        Throwable cause = exception;
        if (exception instanceof ExecutionException && exception.getCause() != null) {
            cause = exception.getCause();
        }
        while (cause.getMessage() == null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
