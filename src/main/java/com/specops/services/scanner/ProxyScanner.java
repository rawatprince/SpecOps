package com.specops.services.scanner;

import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.specops.SpecOpsContext;
import com.specops.domain.Parameter;
import io.swagger.v3.core.util.Json;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scans Burp's Proxy history to find real-world values
 * for parameters defined in the OpenAPI specification.
 */
public class ProxyScanner {

    private final SpecOpsContext context;

    public ProxyScanner(SpecOpsContext context) {
        this.context = context;
    }

    private static Map<String, String> parseCookieHeader(String cookieHeader) {
        Map<String, String> out = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return out;
        }
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2) {
                out.put(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
            }
        }
        return out;
    }

    private static boolean canPopulateFromProxy(Parameter p) {
        if (p.isLocked()) {
            return false;
        }

        if (p.getValue().isEmpty()) {
            return true;
        }

        Parameter.ValueSource source = p.getSource();
        return source == Parameter.ValueSource.UNKNOWN
                || source == Parameter.ValueSource.DEFAULT
                || source == Parameter.ValueSource.PARSER
                || source == Parameter.ValueSource.GENERATED;
    }

    private static Map<String, String> extractBodyValues(String contentType, String bodyText) {
        Map<String, String> out = new HashMap<>();
        if (bodyText == null || bodyText.isBlank()) {
            return out;
        }

        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        if (ct.contains("application/json") || ct.contains("+json")) {
            parseJsonBodyValues(bodyText, out);
            return out;
        }

        if (ct.contains("application/x-www-form-urlencoded")) {
            parseFormUrlEncodedValues(bodyText, out);
            return out;
        }

        if (ct.contains("multipart/form-data")) {
            parseMultipartValues(bodyText, out);
            return out;
        }

        // Fallbacks for missing/incorrect content-type.
        parseFormUrlEncodedValues(bodyText, out);
        if (!out.isEmpty()) {
            return out;
        }
        parseJsonBodyValues(bodyText, out);
        return out;
    }

    private static void parseJsonBodyValues(String bodyText, Map<String, String> out) {
        try {
            Object root = Json.mapper().readValue(bodyText, Object.class);
            collectJsonScalars(root, "", out);
        } catch (Exception ignored) {
            // Ignore malformed JSON payloads.
        }
    }

    private static void parseFormUrlEncodedValues(String bodyText, Map<String, String> out) {
        if (bodyText == null || bodyText.isBlank()) {
            return;
        }

        String[] pairs = bodyText.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            String rawKey = kv[0];
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }

            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                continue;
            }

            String value = kv.length == 2
                    ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                    : "";

            out.putIfAbsent(key.toLowerCase(Locale.ROOT), value);

            // normalize common bracket notation tags[0][name] -> tags[].name
            String normalized = key
                    .replaceAll("\\[\\d+\\]", "[]")
                    .replaceAll("\\[(\\w+)\\]", ".$1");
            out.putIfAbsent(normalized.toLowerCase(Locale.ROOT), value);

            int dot = normalized.lastIndexOf('.');
            String leaf = dot >= 0 ? normalized.substring(dot + 1) : normalized;
            if (leaf.endsWith("[]")) {
                leaf = leaf.substring(0, leaf.length() - 2);
            }
            if (!leaf.isEmpty()) {
                out.putIfAbsent(leaf.toLowerCase(Locale.ROOT), value);
            }
        }
    }

    private static void parseMultipartValues(String bodyText, Map<String, String> out) {
        if (bodyText == null || bodyText.isBlank()) {
            return;
        }

        String[] parts = bodyText.split("\r?\n\r?\n");
        Pattern namePattern = Pattern.compile("name=\"([^\"]+)\"");

        for (int i = 0; i + 1 < parts.length; i += 2) {
            String headers = parts[i];
            String valueBlock = parts[i + 1];

            Matcher m = namePattern.matcher(headers);
            if (!m.find()) {
                continue;
            }

            String key = m.group(1).trim();
            if (key.isEmpty()) {
                continue;
            }

            String value = valueBlock;
            int boundaryIdx = value.indexOf("\r\n--");
            if (boundaryIdx >= 0) {
                value = value.substring(0, boundaryIdx);
            }
            value = value.trim();
            out.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectJsonScalars(Object node, String path, Map<String, String> out) {
        if (node instanceof Map<?, ?> mapNode) {
            for (Map.Entry<?, ?> e : mapNode.entrySet()) {
                String key = String.valueOf(e.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                collectJsonScalars(e.getValue(), childPath, out);
            }
            return;
        }

        if (node instanceof List<?> listNode) {
            for (Object item : listNode) {
                String childPath = path + "[]";
                collectJsonScalars(item, childPath, out);
            }
            return;
        }

        if (path.isEmpty() || node == null) {
            return;
        }

        String value = String.valueOf(node);
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        out.putIfAbsent(normalizedPath, value);

        int dotIdx = normalizedPath.lastIndexOf('.');
        String leaf = dotIdx >= 0 ? normalizedPath.substring(dotIdx + 1) : normalizedPath;
        if (leaf.endsWith("[]")) {
            leaf = leaf.substring(0, leaf.length() - 2);
        }
        if (!leaf.isEmpty()) {
            out.putIfAbsent(leaf, value);
        }
    }

    private static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }

        // Accept user input such as https://api.example.com:443/path and reduce it to host.
        int schemeIdx = normalized.indexOf("://");
        if (schemeIdx >= 0 && schemeIdx + 3 < normalized.length()) {
            normalized = normalized.substring(schemeIdx + 3);
        }

        int slashIdx = normalized.indexOf('/');
        if (slashIdx >= 0) {
            normalized = normalized.substring(0, slashIdx);
        }

        // IPv6 literals include ':' in the address, so only strip ports when safe.
        if (normalized.startsWith("[")) {
            // Bracketed IPv6 with optional :port, e.g. [2001:db8::1]:443
            int closingBracketIdx = normalized.indexOf(']');
            if (closingBracketIdx > 1) {
                normalized = normalized.substring(1, closingBracketIdx);
            }
        } else {
            int firstColonIdx = normalized.indexOf(':');
            int lastColonIdx = normalized.lastIndexOf(':');

            // Strip :port only for hostnames/IPv4 (single ':').
            if (firstColonIdx >= 0 && firstColonIdx == lastColonIdx) {
                String maybePort = normalized.substring(lastColonIdx + 1);
                if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                    normalized = normalized.substring(0, lastColonIdx);
                }
            }
        }

        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private static boolean hostMatchesTarget(String host, String targetDomain) {
        String normalizedHost = normalizeHost(host);
        String normalizedTarget = normalizeHost(targetDomain);
        if (normalizedHost.isEmpty() || normalizedTarget.isEmpty()) {
            return false;
        }

        // Support wildcard user input: *.example.com
        if (normalizedTarget.startsWith("*.")) {
            String suffix = normalizedTarget.substring(2);
            return !suffix.isEmpty()
                    && normalizedHost.endsWith("." + suffix)
                    && !normalizedHost.equals(suffix);
        }

        // Match exact domain and any subdomain of the target.
        return normalizedHost.equals(normalizedTarget)
                || normalizedHost.endsWith("." + normalizedTarget);
    }

    /**
     * Scans the proxy history for requests to the specified domain and updates
     * the Global Parameter Store with any discovered values.
     */
    public int scanAndPopulate(String targetDomain) {
        int updatedCount = 0;
        Map<String, Parameter> paramStore = context.getGlobalParameterStore();

        String normalizedTargetDomain = targetDomain == null ? "" : targetDomain.trim();
        if (normalizedTargetDomain.isEmpty()) {
            return 0;
        }

        // Request only matching entries from proxy history when ProxyHistoryFilter is available.
        List<ProxyHttpRequestResponse> history = filteredHistory(normalizedTargetDomain);

        // Iterate newest first
        for (int i = history.size() - 1; i >= 0; i--) {
            ProxyHttpRequestResponse phr = history.get(i);

            // Defensive host check: protects correctness when filtered API is unavailable.
            String host = phr.finalRequest().httpService().host();
            if (!hostMatchesTarget(host, normalizedTargetDomain)) {
                continue;
            }

            // Cache lookups for this request
            var req = phr.finalRequest();

            // Pre-parse Cookie header into a Map<String,String>
            Map<String, String> cookieMap = parseCookieHeader(req.headerValue("Cookie"));

            // Pre-index URL parameters into a case-insensitive map
            Map<String, String> urlParams = req.parameters(HttpParameterType.URL).stream()
                    .collect(Collectors.toMap(
                            p -> p.name().toLowerCase(Locale.ROOT),
                            p -> p.value(),
                            (a, b) -> a)); // keep first

            // Pre-index body values from supported content types (json/form/multipart)
            Map<String, String> bodyValues = extractBodyValues(
                    req.headerValue("Content-Type"),
                    req.body().toString());

            for (Parameter p : paramStore.values()) {
                if (!canPopulateFromProxy(p)) {
                    continue;
                }

                Optional<String> found = Optional.empty();
                String pname = p.getName();

                switch (p.getIn().toLowerCase(Locale.ROOT)) {
                    case "header":
                        String headerVal = req.headerValue(pname);
                        if (headerVal != null && !headerVal.isEmpty()) {
                            found = Optional.of(headerVal);
                        }
                        break;

                    case "cookie":
                        String cv = cookieMap.get(pname.toLowerCase(Locale.ROOT));
                        if (cv != null && !cv.isEmpty()) {
                            found = Optional.of(cv);
                        }
                        break;

                    case "query":
                        String qv = urlParams.get(pname.toLowerCase(Locale.ROOT));
                        if (qv == null || qv.isEmpty()) {
                            // Fallback to direct accessor in case-insensitive manner
                            qv = req.parameterValue(pname, HttpParameterType.URL);
                        }
                        if (qv != null && !qv.isEmpty()) {
                            found = Optional.of(qv);
                        }
                        break;

                    case "body":
                        String bodyKey = p.getJsonPath();
                        if (bodyKey == null || bodyKey.isEmpty()) {
                            bodyKey = pname;
                        }

                        String bv = bodyValues.get(bodyKey.toLowerCase(Locale.ROOT));
                        if ((bv == null || bv.isEmpty()) && pname != null && !pname.isEmpty()) {
                            bv = bodyValues.get(pname.toLowerCase(Locale.ROOT));
                        }
                        if (bv != null && !bv.isEmpty()) {
                            found = Optional.of(bv);
                        }
                        break;

                    default:
                        // path or others are not derived here
                        break;
                }

                if (found.isPresent()) {
                    p.setValue(found.get());
                    p.setSource(Parameter.ValueSource.PROXY);
                    updatedCount++;
                }
            }
        }
        return updatedCount;
    }

    @SuppressWarnings("unchecked")
    private List<ProxyHttpRequestResponse> filteredHistory(String targetDomain) {
        Object proxyApi = context.api.proxy();

        try {
            Class<?> filterClass = Class.forName("burp.api.montoya.proxy.ProxyHistoryFilter");
            Method historyWithFilter = Arrays.stream(proxyApi.getClass().getMethods())
                    .filter(m -> m.getName().equals("history"))
                    .filter(m -> m.getParameterCount() == 1)
                    .filter(m -> m.getParameterTypes()[0].equals(filterClass))
                    .findFirst()
                    .orElse(null);

            if (historyWithFilter == null || !filterClass.isInterface()) {
                context.api.logging().logToOutput("ProxyScanner using full proxy history path (ProxyHistoryFilter unavailable).");
                return context.api.proxy().history();
            }

            final boolean[] filterInvocationFailed = {false};
            Object filter = java.lang.reflect.Proxy.newProxyInstance(
                    filterClass.getClassLoader(),
                    new Class<?>[]{filterClass},
                    (ignoredProxy, method, args) -> {
                        switch (method.getName()) {
                            case "equals":
                                return ignoredProxy == (args != null && args.length == 1 ? args[0] : null);
                            case "hashCode":
                                return System.identityHashCode(ignoredProxy);
                            case "toString":
                                return "ProxyHistoryFilterProxy(" + targetDomain + ")";
                            case "matches":
                                if (!method.getReturnType().equals(boolean.class) || args == null || args.length != 1 || args[0] == null) {
                                    return false;
                                }

                                try {
                                    Object entry = args[0];
                                    Method finalRequestMethod = entry.getClass().getMethod("finalRequest");
                                    Object request = finalRequestMethod.invoke(entry);
                                    Method httpServiceMethod = request.getClass().getMethod("httpService");
                                    Object service = httpServiceMethod.invoke(request);
                                    Method hostMethod = service.getClass().getMethod("host");
                                    String host = (String) hostMethod.invoke(service);
                                    return hostMatchesTarget(host, targetDomain);
                                } catch (ReflectiveOperationException | RuntimeException malformedEntry) {
                                    filterInvocationFailed[0] = true;
                                    return false;
                                }
                            default:
                                return false;
                        }
                    });

            Object filteredHistory = historyWithFilter.invoke(proxyApi, filter);
            if (filterInvocationFailed[0]) {
                throw new RuntimeException("ProxyHistoryFilter invocation failed; falling back to full history.");
            }
            if (filteredHistory instanceof List<?>) {
                context.api.logging().logToOutput("ProxyScanner using filtered proxy history path.");
                return (List<ProxyHttpRequestResponse>) filteredHistory;
            }
        } catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException | RuntimeException e) {
            context.api.logging().logToError("ProxyHistoryFilter unavailable, falling back to full history scan: " + e.getMessage());
        }

        context.api.logging().logToOutput("ProxyScanner using full proxy history path.");
        return context.api.proxy().history();
    }
}
