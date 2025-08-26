package com.specops.services.scanner;

import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.specops.SpecOpsContext;
import com.specops.domain.Parameter;

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

    /**
     * Scans the proxy history for requests to the specified domain and updates
     * the Global Parameter Store with any discovered values.
     */
    public int scanAndPopulate(String targetDomain) {
        int updatedCount = 0;
        Map<String, Parameter> paramStore = context.getGlobalParameterStore();

        // Full proxy history
        List<ProxyHttpRequestResponse> history = context.api.proxy().history();

        // Iterate newest first
        for (int i = history.size() - 1; i >= 0; i--) {
            ProxyHttpRequestResponse phr = history.get(i);

            // Use finalRequest().httpService() to get host reliably
            String host = phr.finalRequest().httpService().host();
            if (!host.equalsIgnoreCase(targetDomain)) {
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
}
