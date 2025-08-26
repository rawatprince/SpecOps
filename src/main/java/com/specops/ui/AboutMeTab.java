package com.specops.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.net.URI;

public class AboutMeTab extends JPanel {

    public AboutMeTab(String extensionName, String version) {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout());
        JLabel title = new JLabel(extensionName + "  v" + version);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        header.add(title, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        String html = """
        <html>
          <body style='font-family: sans-serif;'>
            <div style='font-size:12px;color:#666;margin:4px 0 12px 0;'>
              OpenAPI to Burp requests with parameter store, auth helpers, and attack workflow.
            </div>

            <div style='margin:8px 0 16px 0;'>
              <span style='border:1px solid #ddd;border-radius:4px;padding:3px 6px;margin-right:6px;'>Java</span>
              <span style='border:1px solid #ddd;border-radius:4px;padding:3px 6px;margin-right:6px;'>OpenAPI 3.x</span>
              <span style='border:1px solid #ddd;border-radius:4px;padding:3px 6px;margin-right:6px;'>JSON + YAML</span>
              <span style='border:1px solid #ddd;border-radius:4px;padding:3px 6px;'>Burp Montoya</span>
            </div>

            <h3 style='margin:12px 0 6px 0;'>Features</h3>
            <ul style='margin-top:4px;'>
              <li>Parse OpenAPI specs from file or URL and generate ready-to-use Burp requests</li>
              <li>Global Parameter Store with CSV or JSON import or export</li>
              <li>Auth helpers for apiKey, bearer, and basic</li>
              <li>Custom Headers applied after auth so user overrides win</li>
              <li>Endpoints workbench and results viewer integrated with core Burp tools</li>
            </ul>

            <h3 style='margin:12px 0 6px 0;'>Docs and links</h3>
            <ul style='margin-top:4px;'>
              <li><a href='https://github.com/rawatprince'>GitHub repository</a></li>
              <li><a href='https://github.com/rawatprince'>Releases</a></li>
            </ul>

            <h3 style='margin:12px 0 6px 0;'>Author</h3>
            <div style='margin-top:4px;'>
              Prince Rawat
              <div style='margin-top:6px;'>
                <a href='https://github.com/rawatprince'>GitHub</a>  •
                <a href='https://www.linkedin.com/in/princerawat/'>LinkedIn</a>  •
                <a href='https://princerawat.com/'>Website</a>
              </div>
            </div>

            <div style='margin-top:14px;color:#777;font-size:11px;'>
              Built for Burp Suite. Java 21 recommended. If this tool saves you time, consider a star on GitHub.
            </div>
          </body>
        </html>
        """;

        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                try { Desktop.getDesktop().browse(new URI(e.getURL().toString())); } catch (Exception ignore) {}
            }
        });

        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }
}
