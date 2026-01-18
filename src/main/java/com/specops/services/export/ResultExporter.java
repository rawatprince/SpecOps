package com.specops.services.export;

import com.specops.domain.AttackResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ResultExporter {

    private ResultExporter() {
    }

    public static void exportCsv(List<AttackResult> results, Path outputPath, boolean includePayloads)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writeCsvHeader(writer, includePayloads);
            for (AttackResult result : results) {
                writeCsvRow(writer, result, includePayloads);
            }
        }
    }

    public static void exportJson(List<AttackResult> results, Path outputPath, boolean includePayloads)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("[");
            writer.newLine();
            for (int i = 0; i < results.size(); i++) {
                writeJsonEntry(writer, results.get(i), includePayloads, i == results.size() - 1);
            }
            writer.write("]");
            writer.newLine();
        }
    }

    private static void writeCsvHeader(BufferedWriter writer, boolean includePayloads) throws IOException {
        writer.write("timestamp,method,path,status_code,response_length");
        if (includePayloads) {
            writer.write(",request,response");
        }
        writer.newLine();
    }

    private static void writeCsvRow(BufferedWriter writer, AttackResult result, boolean includePayloads)
            throws IOException {
        writer.write(csvField(result.getTimestamp()));
        writer.write(",");
        writer.write(csvField(result.getEndpoint().getMethod().toString()));
        writer.write(",");
        writer.write(csvField(result.getEndpoint().getPath()));
        writer.write(",");
        writer.write(String.valueOf(result.getStatusCode()));
        writer.write(",");
        writer.write(String.valueOf(result.getResponseLength()));
        if (includePayloads) {
            writer.write(",");
            writer.write(csvField(requestText(result)));
            writer.write(",");
            writer.write(csvField(responseText(result)));
        }
        writer.newLine();
    }

    private static void writeJsonEntry(BufferedWriter writer, AttackResult result, boolean includePayloads,
                                       boolean isLast) throws IOException {
        writer.write("  {");
        writer.newLine();
        writer.write("    \"timestamp\": \"" + jsonEscape(result.getTimestamp()) + "\",");
        writer.newLine();
        writer.write("    \"method\": \"" + jsonEscape(result.getEndpoint().getMethod().toString()) + "\",");
        writer.newLine();
        writer.write("    \"path\": \"" + jsonEscape(result.getEndpoint().getPath()) + "\",");
        writer.newLine();
        writer.write("    \"statusCode\": " + result.getStatusCode() + ",");
        writer.newLine();
        writer.write("    \"responseLength\": " + result.getResponseLength());
        if (includePayloads) {
            writer.write(",");
            writer.newLine();
            writer.write("    \"request\": \"" + jsonEscape(requestText(result)) + "\",");
            writer.newLine();
            writer.write("    \"response\": \"" + jsonEscape(responseText(result)) + "\"");
        }
        writer.newLine();
        writer.write("  }");
        if (!isLast) {
            writer.write(",");
        }
        writer.newLine();
    }

    private static String requestText(AttackResult result) {
        return result.getRequest() != null ? result.getRequest().toString() : "";
    }

    private static String responseText(AttackResult result) {
        return result.getResponse() != null ? result.getResponse().toString() : "";
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = sanitizeCsvFormula(value);
        boolean needsQuotes = sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n")
                || sanitized.contains("\r");
        String escaped = sanitized.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private static String sanitizeCsvFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        return builder.toString();
    }
}
