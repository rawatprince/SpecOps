package com.specops.services.scanner;

import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.specops.SpecOpsContext;
import com.specops.domain.Parameter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
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

        int colonIdx = normalized.indexOf(':');
        if (colonIdx >= 0) {
            normalized = normalized.substring(0, colonIdx);
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

            for (Parameter p : paramStore.values()) {
                if (p.isLocked() || !p.getValue().isEmpty()) {
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
                return context.api.proxy().history();
            }

            Object filter = java.lang.reflect.Proxy.newProxyInstance(
                    filterClass.getClassLoader(),
                    new Class<?>[]{filterClass},
                    (ignoredProxy, method, args) -> {
                        if (method.getReturnType().equals(boolean.class) && args != null && args.length == 1) {
                            Object entry = args[0];
                            Method finalRequestMethod = entry.getClass().getMethod("finalRequest");
                            Object request = finalRequestMethod.invoke(entry);
                            Method httpServiceMethod = request.getClass().getMethod("httpService");
                            Object service = httpServiceMethod.invoke(request);
                            Method hostMethod = service.getClass().getMethod("host");
                            String host = (String) hostMethod.invoke(service);
                            return hostMatchesTarget(host, targetDomain);
                        }
                        return null;
                    });

            Object filteredHistory = historyWithFilter.invoke(proxyApi, filter);
            if (filteredHistory instanceof List<?>) {
                return (List<ProxyHttpRequestResponse>) filteredHistory;
            }
        } catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException e) {
            context.api.logging().logToError("ProxyHistoryFilter unavailable, falling back to full history scan: " + e.getMessage());
        }

        return context.api.proxy().history();
    }
}
