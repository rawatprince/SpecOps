package com.specops.services.request;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.specops.SpecOpsContext;
import com.specops.domain.Endpoint;
import com.specops.domain.Parameter;
import com.specops.domain.rules.HeaderRule;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static burp.api.montoya.http.HttpService.httpService;
import static burp.api.montoya.http.message.HttpHeader.httpHeader;
import static burp.api.montoya.http.message.params.HttpParameter.urlParameter;

/**
 * Service class for constructing a real-world HttpRequest from an Endpoint object.
 */
public class RequestFactory {

    private final SpecOpsContext context;

    public RequestFactory(SpecOpsContext context) {
        this.context = context;
    }

    public HttpRequest buildRequest(Endpoint endpoint) {
        Map<String, Parameter> paramStore = context.getGlobalParameterStore();

        if (context.getOpenAPI() == null
                || context.getOpenAPI().getServers() == null
                || context.getOpenAPI().getServers().isEmpty()) {
            context.api.logging().logToError("Cannot build request: OpenAPI specification or servers not loaded.");
            return null;
        }

        // Resolve server URLs from context. For a single request build, first resolved URL.
        List<String> serverUrls = resolveServerBaseUrls();
        if (serverUrls.isEmpty()) {
            context.api.logging().logToError("Cannot build request: no server URLs resolved.");
            return null;
        }
        String serverUrl = serverUrls.get(0);

        URL parsed;
        try {
            if (serverUrl.startsWith("/")) {
                String host = context.getApiHost();
                if (host == null) {
                    context.api.logging().logToError("Server URL is relative, but no host is defined. Please set one in the Specification tab.");
                    return null;
                }
                parsed = new URL(new URL(host), serverUrl);
            } else {
                parsed = new URL(serverUrl);
            }
        } catch (MalformedURLException e) {
            context.api.logging().logToError("Invalid server URL in spec: " + serverUrl);
            return null;
        }

        // Substitute path params from store or spec fallbacks
        String finalPath = substitutePathParameters(endpoint, paramStore);

        // Build HttpService
        int port = parsed.getPort() == -1 ? parsed.getDefaultPort() : parsed.getPort();
        boolean secure = "https".equalsIgnoreCase(parsed.getProtocol());
        HttpService service = httpService(parsed.getHost(), port, secure);

        // Build full path: base path from server + endpoint path
        String pathWithPrefix = parsed.getPath().equals("/") ? "" : parsed.getPath();
        if (pathWithPrefix.endsWith("/")) {
            pathWithPrefix = pathWithPrefix.substring(0, pathWithPrefix.length() - 1);
        }
        pathWithPrefix += finalPath;
        if (pathWithPrefix.isEmpty()) {
            pathWithPrefix = "/";
        }

        // Headers from store
        List<HttpHeader> headers = new ArrayList<>();
        addHeadersFromStore(headers, endpoint, paramStore);
        addCookieHeader(headers, endpoint, paramStore);

        // Inject auth headers and cookie per security schemes before custom header rules
        addAuthFromSecuritySchemes(headers, endpoint);

        // Ensure Host header is present
        boolean hasHost = headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("Host"));
        if (!hasHost) {
            String hostValue = parsed.getHost();
            if ((secure && port != 443) || (!secure && port != 80)) {
                hostValue = hostValue + ":" + port;
            }
            headers.add(httpHeader("Host", hostValue));
        }

        // Body from OpenAPI examples or synthesized from schema, then overlay global store overrides for in="body"
        String body = "";
        RequestBody rb = endpoint.getOperation().getRequestBody();
        rb = derefRequestBody(rb);
        BuiltBody built = buildBodyFromSpecExamples(rb, paramStore, headers);

        if (!built.headers.isEmpty()) {
            boolean haveCT = headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("Content-Type"));
            for (HttpHeader h : built.headers) {
                if (h.name().equalsIgnoreCase("Content-Type")) {
                    if (!haveCT) {
                        headers.add(h);
                        haveCT = true;
                    }
                } else {
                    headers.add(h);
                }
            }
        }
        if (built.body != null) {
            body = built.body;
        }

        // Apply Custom Header rules from the Headers tab, if enabled
        if (context.isHeadersApplyToWorkbench()) {
            List<HeaderRule> rules = context.getHeaderRules();
            for (HeaderRule r : rules) {
                if (r == null || !r.enabled) continue;
                if (r.name == null || r.name.trim().isEmpty()) continue;

                // Match scope
                boolean match = false;
                String epPath = endpoint.getPath() == null ? "" : endpoint.getPath();
                switch (r.scope) {
                    case ALL:
                        match = true;
                        break;
                    case HOST:
                        match = parsed.getHost().equalsIgnoreCase(r.match);
                        break;
                    case PATH_PREFIX: {
                        String normPath = epPath == null ? "" : epPath.trim();
                        if (!normPath.startsWith("/")) normPath = "/" + normPath;

                        String m = r.match == null ? "" : r.match.trim();
                        if (!m.isEmpty() && !m.startsWith("/")) m = "/" + m;

                        match = m.isEmpty() || normPath.startsWith(m);
                        break;
                    }
                    case TAG:
                        match = endpoint.getOperation() != null
                                && endpoint.getOperation().getTags() != null
                                && r.match != null
                                && endpoint.getOperation().getTags().contains(r.match);
                        break;
                    case METHOD:
                        match = endpoint.getMethod().toString().equalsIgnoreCase(r.match);
                        break;
                    default:
                        match = false;
                }
                if (!match) continue;

                // Guardrails for dangerous or managed headers
                String nameLc = r.name.toLowerCase(Locale.ROOT);
                if (nameLc.equals("host") || nameLc.equals("content-length") || nameLc.equals("transfer-encoding") || nameLc.equals("connection")) {
                    continue;
                }

                // Compute value with ${param.*} substitution from the global parameter store
                String value = r.value == null ? "" : r.value;
                if (value.contains("${param.")) {
                    for (Map.Entry<String, Parameter> e : paramStore.entrySet()) {
                        String unique = e.getKey(); // now canonical, like "in:name" or "body:json.path[]"
                        String v = e.getValue() != null && e.getValue().getValue() != null ? e.getValue().getValue() : "";

                        value = value.replace("${param." + unique + "}", v);

                        String simple = e.getValue() != null ? e.getValue().getName() : null;
                        if (simple != null && !simple.isEmpty()) {
                            value = value.replace("${param." + simple + "}", v);
                        }
                    }
                }

                boolean exists = headers.stream().anyMatch(h -> h.name().equalsIgnoreCase(r.name));
                if (exists && r.overwrite) {
                    headers.removeIf(h -> h.name().equalsIgnoreCase(r.name));
                    headers.add(httpHeader(r.name, value));
                } else if (!exists) {
                    headers.add(httpHeader(r.name, value));
                }
            }
        }

        // Create base request with service, path, method, headers, body
        HttpRequest request = HttpRequest.httpRequest()
                .withService(service)
                .withPath(pathWithPrefix)
                .withMethod(endpoint.getMethod().toString())
                .withAddedHeaders(headers)
                .withBody(body);

        // Add query parameters from the store and spec fallbacks
        request = addQueryParameters(request, endpoint, paramStore);

        // Add auth query parameter if the applicable scheme is API key in=query
        request = addAuthQueryParameters(request, endpoint);

        return request;
    }

    // servers and variables

    /**
     * Resolve server base URLs from the spec based on user selection and overrides.
     * If iterateAcrossAllServers is enabled, all servers are resolved and returned.
     * buildRequest uses only the first entry for preview/single.
     */
    private List<String> resolveServerBaseUrls() {
        var oa = context.getOpenAPI();
        if (oa == null || oa.getServers() == null || oa.getServers().isEmpty()) {
            return List.of();
        }
        List<Server> servers = oa.getServers();

        if (context.isIterateAcrossAllServers()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < servers.size(); i++) {
                String resolved = resolveServerUrlWithVars(servers.get(i), i);
                if (resolved != null && !resolved.isBlank()) {
                    out.add(resolved);
                }
            }
            return out;
        } else {
            int idx = Math.min(Math.max(context.getSelectedServerIndex(), 0), servers.size() - 1);
            String resolved = resolveServerUrlWithVars(servers.get(idx), idx);
            if (resolved == null || resolved.isBlank()) {
                return List.of();
            }
            return List.of(resolved);
        }
    }

    private String resolveServerUrlWithVars(Server server, int serverIndex) {
        if (server == null || server.getUrl() == null) return "";
        String url = server.getUrl();

        Map<String, String> vals = new HashMap<>();
        if (server.getVariables() != null) {
            for (var e : server.getVariables().entrySet()) {
                String def = (e.getValue() != null && e.getValue().getDefault() != null)
                        ? e.getValue().getDefault()
                        : "";
                vals.put(e.getKey(), def);
            }
        }
        vals.putAll(context.getServerVariableOverrides(serverIndex));

        for (var e : vals.entrySet()) {
            url = url.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return url;
    }

    // path, header, cookie, query helpers

    private String substitutePathParameters(Endpoint endpoint, Map<String, Parameter> store) {
        String finalPath = endpoint.getPath();
        for (io.swagger.v3.oas.models.parameters.Parameter specParam
                : endpoint.getAllParameters(context.getOpenAPI())) {
            if (!"path".equalsIgnoreCase(specParam.getIn())) continue;

            Parameter stored = findParam(store, "path", specParam.getName());
            String value = stored != null ? stored.getValue() : null;

            if (value == null || value.isEmpty()) {
                if (specParam.getExample() != null) value = String.valueOf(specParam.getExample());
                if ((value == null || value.isEmpty()) && specParam.getSchema() != null && specParam.getSchema().getDefault() != null)
                    value = String.valueOf(specParam.getSchema().getDefault());
                if ((value == null || value.isEmpty()) && specParam.getSchema() != null
                        && specParam.getSchema().getEnum() != null && !specParam.getSchema().getEnum().isEmpty())
                    value = String.valueOf(specParam.getSchema().getEnum().get(0));
            }

            if (value == null || value.isEmpty()) value = "id";
            finalPath = finalPath.replace("{" + specParam.getName() + "}", encodePathSegment(value));
        }
        return finalPath;
    }

    private void addHeadersFromStore(List<HttpHeader> headers, Endpoint endpoint, Map<String, Parameter> store) {
        for (io.swagger.v3.oas.models.parameters.Parameter specParam
                : endpoint.getAllParameters(context.getOpenAPI())) {

            if (!"header".equalsIgnoreCase(specParam.getIn())) continue;

            Parameter stored = findParam(store, "header", specParam.getName());
            String value = stored != null ? stored.getValue() : null;

            String nlc = specParam.getName() != null ? specParam.getName().toLowerCase(Locale.ROOT) : "";
            if (nlc.equals("host") || nlc.equals("content-length") || nlc.equals("transfer-encoding") || nlc.equals("connection")) {
                continue;
            }

            if (value == null || value.isEmpty()) {
                if (specParam.getExample() != null) value = String.valueOf(specParam.getExample());
                if ((value == null || value.isEmpty()) && specParam.getSchema() != null && specParam.getSchema().getDefault() != null)
                    value = String.valueOf(specParam.getSchema().getDefault());
                if ((value == null || value.isEmpty()) && specParam.getSchema() != null
                        && specParam.getSchema().getEnum() != null && !specParam.getSchema().getEnum().isEmpty())
                    value = String.valueOf(specParam.getSchema().getEnum().get(0));
            }

            if (value != null && !value.isEmpty()) {
                boolean isCT = "content-type".equalsIgnoreCase(specParam.getName());
                boolean alreadyHasCT = isCT && headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("Content-Type"));
                if (!alreadyHasCT) headers.add(httpHeader(specParam.getName(), value));
            }
        }
    }

    private void addCookieHeader(List<HttpHeader> headers, Endpoint endpoint, Map<String, Parameter> store) {
        String cookieString = endpoint.getAllParameters(context.getOpenAPI()).stream()
                .filter(p -> "cookie".equalsIgnoreCase(p.getIn()))
                .map(p -> {
                    Parameter stored = findParam(store, "cookie", p.getName());
                    String value = stored != null ? stored.getValue() : null;
                    if (value == null || value.isEmpty()) {
                        if (p.getExample() != null) value = String.valueOf(p.getExample());
                        if ((value == null || value.isEmpty()) && p.getSchema() != null && p.getSchema().getDefault() != null)
                            value = String.valueOf(p.getSchema().getDefault());
                        if ((value == null || value.isEmpty()) && p.getSchema() != null
                                && p.getSchema().getEnum() != null && !p.getSchema().getEnum().isEmpty())
                            value = String.valueOf(p.getSchema().getEnum().get(0));
                    }
                    return (value != null && !value.isEmpty()) ? (p.getName() + "=" + value) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));

        String existing = headers.stream()
                .filter(h -> h.name().equalsIgnoreCase("Cookie"))
                .map(HttpHeader::value)
                .findFirst()
                .orElse("");

        if (!cookieString.isEmpty()) {
            String combined = existing.isEmpty() ? cookieString
                    : existing.endsWith(";") ? existing + " " + cookieString
                    : existing + "; " + cookieString;
            headers.removeIf(h -> h.name().equalsIgnoreCase("Cookie"));
            headers.add(httpHeader("Cookie", combined));
        }
    }

    private HttpRequest addQueryParameters(HttpRequest request, Endpoint endpoint, Map<String, Parameter> store) {
        HttpRequest newRequest = request;

        // names of query params that are actually auth API keys and have a token
        java.util.Set<String> reservedAuthQueryNames = getAuthQueryParamNamesForEndpoint(endpoint);

        for (io.swagger.v3.oas.models.parameters.Parameter specParam
                : endpoint.getAllParameters(context.getOpenAPI())) {

            if (!"query".equalsIgnoreCase(specParam.getIn())) continue;

            // skip if this param is an auth API key name; let addAuthQueryParameters do it
            if (reservedAuthQueryNames.contains(specParam.getName())) {
                continue;
            }

            Parameter stored = findParam(store, "query", specParam.getName());
            String value = stored != null ? stored.getValue() : null;

            if (value == null || value.isEmpty()) {
                if (specParam.getExample() != null) value = String.valueOf(specParam.getExample());
                if ((value == null || value.isEmpty()) && specParam.getSchema() != null && specParam.getSchema().getDefault() != null)
                    value = String.valueOf(specParam.getSchema().getDefault());
                if ((value == null || value.isEmpty()) && specParam.getSchema() != null
                        && specParam.getSchema().getEnum() != null && !specParam.getSchema().getEnum().isEmpty())
                    value = String.valueOf(specParam.getSchema().getEnum().get(0));
            }

            if (value != null && !value.isEmpty()) {
                newRequest = newRequest.withAddedParameters(urlParameter(specParam.getName(), value));
            }
        }
        return newRequest;
    }

    // Add auth query param if applicable (API key in=query)
    private HttpRequest addAuthQueryParameters(HttpRequest request, Endpoint endpoint) {
        var oa = context.getOpenAPI();
        if (oa == null || oa.getComponents() == null || oa.getComponents().getSecuritySchemes() == null) return request;

        Map<String, SecurityScheme> schemes = oa.getComponents().getSecuritySchemes();
        List<SecurityRequirement> reqs = getEffectiveSecurity(endpoint);

        HttpRequest out = request;
        boolean added = false;

        if (reqs != null && !reqs.isEmpty()) {
            // OR across requirement objects: satisfy first one we have tokens for
            for (SecurityRequirement r : reqs) {
                boolean addedSomething = false;
                for (String schemeName : r.keySet()) {
                    SecurityScheme scheme = schemes.get(schemeName);
                    if (scheme == null) continue;

                    String token = context.getAuthToken(schemeName);
                    if (token == null || token.isBlank()) continue;

                    if (scheme.getType() == SecurityScheme.Type.APIKEY && scheme.getIn() == SecurityScheme.In.QUERY) {
                        String name = scheme.getName() != null ? scheme.getName() : "api_key";
                        out = out.withAddedParameters(urlParameter(name, token));
                        addedSomething = true;
                    }
                }
                if (addedSomething) {
                    added = true;
                    break;
                }
            }
        }

        // Fallback
        if (!added) {
            for (Map.Entry<String, SecurityScheme> e : schemes.entrySet()) {
                String schemeName = e.getKey();
                SecurityScheme scheme = e.getValue();
                if (scheme == null) continue;

                if (scheme.getType() == SecurityScheme.Type.APIKEY && scheme.getIn() == SecurityScheme.In.QUERY) {
                    String token = context.getAuthToken(schemeName);
                    if (token == null || token.isBlank()) continue;

                    String name = scheme.getName() != null ? scheme.getName() : "api_key";
                    out = out.withAddedParameters(urlParameter(name, token));
                }
            }
        }
        return out;
    }

    // auth injection: headers and cookie
    // auth injection that overwrites any example values
    private void addAuthFromSecuritySchemes(List<HttpHeader> headers, Endpoint endpoint) {
        var oa = context.getOpenAPI();
        if (oa == null || oa.getComponents() == null || oa.getComponents().getSecuritySchemes() == null) return;

        Map<String, SecurityScheme> schemes = oa.getComponents().getSecuritySchemes();
        List<SecurityRequirement> reqs = getEffectiveSecurity(endpoint);

        boolean injected = false;

        if (reqs != null && !reqs.isEmpty()) {
            // OR across requirement objects: satisfy the first object we can
            for (SecurityRequirement r : reqs) {
                if (tryInjectOneRequirement(headers, r, schemes)) {
                    injected = true;
                    break;
                }
            }
        }

        // Fallback
        if (!injected) {
            fallbackInjectApiKeyHeaderAndCookie(headers, schemes);
        }
    }

    private void fallbackInjectApiKeyHeaderAndCookie(List<HttpHeader> headers, Map<String, SecurityScheme> schemes) {
        if (schemes == null || schemes.isEmpty()) return;

        for (Map.Entry<String, SecurityScheme> e : schemes.entrySet()) {
            String schemeName = e.getKey();
            SecurityScheme scheme = e.getValue();
            if (scheme == null || scheme.getType() != SecurityScheme.Type.APIKEY) continue;

            String token = context.getAuthToken(schemeName);
            if (token == null || token.isBlank()) continue;

            SecurityScheme.In in = scheme.getIn();
            String name = scheme.getName() != null ? scheme.getName() : "api_key";

            if (in == SecurityScheme.In.HEADER) {
                upsertHeader(headers, name, token);
            } else if (in == SecurityScheme.In.COOKIE) {
                upsertCookieKV(headers, name, token);
            }
        }
    }

    private boolean tryInjectOneRequirement(List<HttpHeader> headers,
                                            SecurityRequirement requirement,
                                            Map<String, SecurityScheme> schemes) {
        boolean didInject = false;

        for (String schemeName : requirement.keySet()) {
            SecurityScheme scheme = schemes.get(schemeName);
            if (scheme == null) continue;

            String token = context.getAuthToken(schemeName);
            if (token == null || token.isBlank()) continue;

            switch (scheme.getType()) {
                case APIKEY -> {
                    SecurityScheme.In in = scheme.getIn();
                    String name = scheme.getName() != null ? scheme.getName() : "api_key";

                    if (in == SecurityScheme.In.HEADER) {
                        upsertHeader(headers, name, token);
                        didInject = true;
                    } else if (in == SecurityScheme.In.COOKIE) {
                        upsertCookieKV(headers, name, token);
                        didInject = true;
                    }
                }

                case HTTP -> {
                    String schemeNameLc = scheme.getScheme() == null ? "" : scheme.getScheme().toLowerCase(Locale.ROOT);
                    if ("bearer".equals(schemeNameLc)) {
                        upsertHeader(headers, "Authorization", "Bearer " + token);
                        didInject = true;
                    } else if ("basic".equals(schemeNameLc)) {
                        upsertHeader(headers, "Authorization", "Basic " + token);
                        didInject = true;
                    }
                }

                case OAUTH2, OPENIDCONNECT -> {
                    upsertHeader(headers, "Authorization", "Bearer " + token);
                    didInject = true;
                }

                default -> {
                    // unsupported types ignored safely
                }
            }
        }
        return didInject;
    }

    // helpers: upsert header and cookie KV
    private void upsertHeader(List<HttpHeader> headers, String name, String value) {
        headers.removeIf(h -> h.name().equalsIgnoreCase(name));
        headers.add(httpHeader(name, value));
    }

    private void upsertCookieKV(List<HttpHeader> headers, String cookieName, String cookieValue) {
        String existing = headers.stream()
                .filter(h -> h.name().equalsIgnoreCase("Cookie"))
                .map(HttpHeader::value)
                .findFirst()
                .orElse("");

        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (!existing.isEmpty()) {
            for (String part : existing.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                String k = eq >= 0 ? p.substring(0, eq).trim() : p;
                String v = eq >= 0 ? p.substring(eq + 1).trim() : "";
                map.put(k, v);
            }
        }
        map.put(cookieName, cookieValue);

        String joined = map.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
        headers.removeIf(h -> h.name().equalsIgnoreCase("Cookie"));
        headers.add(httpHeader("Cookie", joined));
    }

    private java.util.Set<String> getAuthQueryParamNamesForEndpoint(Endpoint endpoint) {
        var oa = context.getOpenAPI();
        if (oa == null || oa.getComponents() == null || oa.getComponents().getSecuritySchemes() == null)
            return java.util.Set.of();

        Map<String, SecurityScheme> schemes = oa.getComponents().getSecuritySchemes();
        List<io.swagger.v3.oas.models.security.SecurityRequirement> reqs = getEffectiveSecurity(endpoint);
        if (reqs == null || reqs.isEmpty()) return java.util.Set.of();

        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (var r : reqs) {
            for (String schemeName : r.keySet()) {
                SecurityScheme s = schemes.get(schemeName);
                if (s == null) continue;
                String token = context.getAuthToken(schemeName);
                if (token == null || token.isBlank()) continue;
                if (s.getType() == SecurityScheme.Type.APIKEY && s.getIn() == SecurityScheme.In.QUERY) {
                    String name = s.getName() != null ? s.getName() : "api_key";
                    names.add(name);
                }
            }
        }
        return names;
    }

    private List<SecurityRequirement> getEffectiveSecurity(Endpoint endpoint) {
        List<SecurityRequirement> reqs = endpoint.getOperation() != null
                ? endpoint.getOperation().getSecurity()
                : null;
        if (reqs == null || reqs.isEmpty()) {
            reqs = context.getOpenAPI().getSecurity();
        }
        return reqs;
    }

    // legacy method
    private String resolveServerUrl(Server server) {
        String url = server.getUrl();
        if (server.getVariables() != null && !server.getVariables().isEmpty()) {
            for (Map.Entry<String, io.swagger.v3.oas.models.servers.ServerVariable> e : server.getVariables().entrySet()) {
                String var = e.getKey();
                io.swagger.v3.oas.models.servers.ServerVariable sv = e.getValue();
                String def = sv != null && sv.getDefault() != null ? sv.getDefault() : "";
                url = url.replace("{" + var + "}", def);
            }
        }
        return url;
    }

    // body building and synthesis

    private BuiltBody buildBodyFromSpecExamples(RequestBody rb,
                                                Map<String, Parameter> store,
                                                List<HttpHeader> existingHeaders) {
        if (rb == null || rb.getContent() == null || rb.getContent().isEmpty()) {
            return BuiltBody.empty();
        }

        String desired = existingHeaders == null ? "" :
                existingHeaders.stream()
                        .filter(h -> "Content-Type".equalsIgnoreCase(h.name()))
                        .map(HttpHeader::value)
                        .findFirst()
                        .map(this::normalizeMediaKey)
                        .orElse("");

        List<Map.Entry<String, MediaType>> entries = new ArrayList<>(rb.getContent().entrySet());
        entries.sort((a, b) -> Integer.compare(
                mediaRank(a.getKey(), desired),
                mediaRank(b.getKey(), desired))
        );

        for (Map.Entry<String, MediaType> e : entries) {
            String headerKey = e.getKey();
            String norm = normalizeMediaKey(headerKey);
            MediaType media = e.getValue();

            Object example = pickExample(media);
            Schema<?> schema = deref(media.getSchema());
            if (example == null && schema != null) {
                if (schema.getExample() != null) example = schema.getExample();
                else if (schema.getDefault() != null) example = schema.getDefault();
            }

            // Synthesize if no explicit example
            if (example == null && schema != null) {
                if (norm.equals("application/x-www-form-urlencoded")) {
                    Map<String, Object> m = materializeMapFromSchema(schema, 0);
                    applyBodyOverridesToMap(m, store, "");
                    return new BuiltBody(renderWwwForm(m), List.of(httpHeader("Content-Type", headerKey)));
                } else if (norm.equals("multipart/form-data")) {
                    Map<String, Object> m = materializeMapFromSchema(schema, 0);
                    applyBodyOverridesToMap(m, store, "");
                    String boundary = "----SpecOps" + UUID.randomUUID();
                    String body = renderMultipart(m, boundary, media.getEncoding()) + "\r\n";
                    return new BuiltBody(body, List.of(httpHeader("Content-Type", headerKey + "; boundary=" + boundary)));
                } else if (isJsonLike(norm) || norm.equals("*/*") || norm.isEmpty()) {
                    String json = materializeJsonFromSchema(schema, 0);
                    json = applyBodyOverridesToJson(json, store, schema);
                    String ctHeader = isJsonLike(norm) ? headerKey : "application/json";
                    return new BuiltBody(json, List.of(httpHeader("Content-Type", ctHeader)));
                } else {
                    String json = materializeJsonFromSchema(schema, 0);
                    json = applyBodyOverridesToJson(json, store, schema);
                    return new BuiltBody(json, List.of(httpHeader("Content-Type", "application/json")));
                }
            }

            // We have an explicit example now
            if (example != null) {
                if (norm.equals("application/x-www-form-urlencoded")) {
                    Map<String, Object> m = coerceToMap(example);
                    applyBodyOverridesToMap(m, store, "");
                    return new BuiltBody(renderWwwForm(m), List.of(httpHeader("Content-Type", headerKey)));
                } else if (norm.equals("multipart/form-data")) {
                    Map<String, Object> m = coerceToMap(example);
                    applyBodyOverridesToMap(m, store, "");
                    String boundary = "----SpecOps" + UUID.randomUUID();
                    String body = renderMultipart(m, boundary, media.getEncoding()) + "\r\n";
                    return new BuiltBody(body, List.of(httpHeader("Content-Type", headerKey + "; boundary=" + boundary)));
                } else if (isJsonLike(norm) || norm.equals("*/*") || norm.isEmpty()) {
                    String ctHeader = isJsonLike(norm) ? headerKey : "application/json";
                    String json = renderJson(example);
                    json = applyBodyOverridesToJson(json, store, schema);
                    return new BuiltBody(json, List.of(httpHeader("Content-Type", ctHeader)));
                } else if (norm.startsWith("text/")) {
                    String s = String.valueOf(example);
                    String rootOverride = readBodyOverride(store, "");
                    if (rootOverride != null) s = rootOverride;
                    return new BuiltBody(s, List.of(httpHeader("Content-Type", headerKey)));
                } else {
                    String s = String.valueOf(example);
                    String rootOverride = readBodyOverride(store, "");
                    if (rootOverride != null) s = rootOverride;
                    return new BuiltBody(s, List.of(httpHeader("Content-Type", headerKey)));
                }
            }
        }

        // Fallback to JSON if any entry had a schema
        for (Map.Entry<String, MediaType> e : entries) {
            Schema<?> schema = deref(e.getValue().getSchema());
            if (schema != null) {
                String json = materializeJsonFromSchema(schema, 0);
                json = applyBodyOverridesToJson(json, store, schema);
                return new BuiltBody(json, List.of(httpHeader("Content-Type", "application/json")));
            }
        }

        return BuiltBody.empty();
    }

    /**
     * Rank media types with preference:
     * 1) Exact match to desired Content-Type
     * 2) JSON-like when desired is JSON-like (including application/*+json)
     * 3) application/json or *+json
     * 4) application/x-www-form-urlencoded
     * 5) multipart/form-data
     * 6) text/*
     * 7) everything else
     */
    private int mediaRank(String key, String desired) {
        String norm = normalizeMediaKey(key);
        String want = normalizeMediaKey(desired);

        if (!want.isEmpty() && norm.equals(want)) return -100;

        boolean normJson = isJsonLike(norm);
        boolean wantJson = isJsonLike(want);

        if (wantJson && normJson) return -90;
        if (normJson) return -80;

        if (norm.equals("application/x-www-form-urlencoded")) return -70;
        if (norm.equals("multipart/form-data")) return -60;
        if (norm.startsWith("text/")) return -50;

        if (norm.equals("*/*") || norm.isEmpty()) return -40;

        return 0;
    }

    private String normalizeMediaKey(String mt) {
        if (mt == null) return "";
        String base = mt.toLowerCase(Locale.ROOT).trim();
        int sc = base.indexOf(';');
        if (sc >= 0) base = base.substring(0, sc).trim();
        return base;
    }

    // media helpers

    private boolean isJsonLike(String normalized) {
        if (normalized == null) return false;
        String n = normalized.toLowerCase(Locale.ROOT);
        return n.equals("application/json") || n.endsWith("+json") || n.contains("json");
    }

    private int mediaRank(String key) {
        String norm = normalizeMediaKey(key);
        if (isJsonLike(norm) || norm.equals("*/*") || norm.isEmpty()) return 0;
        if (norm.equals("application/x-www-form-urlencoded")) return 1;
        if (norm.equals("multipart/form-data")) return 2;
        if (norm.startsWith("text/")) return 3;
        return 4;
    }

    private Object pickExample(MediaType media) {
        if (media.getExample() != null) return media.getExample();
        if (media.getExamples() != null && !media.getExamples().isEmpty()) {
            for (Example ex : media.getExamples().values()) {
                if (ex.getValue() != null) return ex.getValue();
            }
        }
        return null;
    }

    private Schema<?> deref(Schema<?> s) {
        if (s == null || s.get$ref() == null) return s;
        String name = s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1);
        return context.getOpenAPI().getComponents() != null
                ? context.getOpenAPI().getComponents().getSchemas().getOrDefault(name, s)
                : s;
    }

    private RequestBody derefRequestBody(RequestBody rb) {
        if (rb == null || rb.get$ref() == null) return rb;
        String name = rb.get$ref().substring(rb.get$ref().lastIndexOf('/') + 1);
        return context.getOpenAPI().getComponents() != null
                && context.getOpenAPI().getComponents().getRequestBodies() != null
                ? context.getOpenAPI().getComponents().getRequestBodies().getOrDefault(name, rb)
                : rb;
    }

    private String renderMultipart(Object example, String boundary) {
        return renderMultipart(example, boundary, null);
    }

    // multipart rendering with per-part encodings

    private String renderMultipart(Object example, String boundary, Map<String, Encoding> encMap) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> parts = coerceToMap(example);

        for (Map.Entry<String, Object> e : parts.entrySet()) {
            String name = String.valueOf(e.getKey());
            Object value = e.getValue();

            String partCT = null;
            String fileName = null;
            boolean isBinary = false;

            if (encMap != null) {
                Encoding enc = encMap.get(name);
                if (enc != null) {
                    if (enc.getContentType() != null && !enc.getContentType().isEmpty()) {
                        partCT = enc.getContentType();
                        isBinary = !(partCT.contains("json") || partCT.startsWith("text/"));
                    }
                }
            }

            if (value instanceof byte[]) {
                isBinary = true;
            }

            if (isBinary) {
                fileName = "file.bin";
                if (partCT == null) partCT = "application/octet-stream";
            } else {
                if (partCT == null) partCT = "text/plain; charset=UTF-8";
            }

            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
            if (fileName != null) sb.append("; filename=\"").append(fileName).append("\"");
            sb.append("\r\n");
            sb.append("Content-Type: ").append(partCT).append("\r\n\r\n");

            if (isBinary) {
                sb.append("[[FILE-CONTENT]]").append("\r\n");
            } else {
                String s = (value instanceof String) ? (String) value : renderJson(value);
                sb.append(s).append("\r\n");
            }
        }
        sb.append("--").append(boundary).append("--");
        return sb.toString();
    }

    private Map<String, Object> coerceToMap(Object example) {
        if (example instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("field", example);
        return single;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> materializeMapFromSchema(Schema<?> schema, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema == null || depth > 8) return out;
        schema = deref(schema);

        Schema<?> composed = composeSchema(schema, 0);
        if (composed != schema) schema = composed;

        if ("object".equals(schema.getType()) || schema.getProperties() != null || schema.getAdditionalProperties() != null) {
            Map<String, Schema> props = schema.getProperties();

            if ((props == null || props.isEmpty()) && schema.getAdditionalProperties() != null) {
                Schema<?> vSchema = schema.getAdditionalProperties() instanceof Schema
                        ? deref((Schema<?>) schema.getAdditionalProperties())
                        : null;
                Object v = (vSchema != null) ? pickMaterializedValue(vSchema, depth + 1) : "string";
                out.put("key", v);
                return out;
            }

            if (props != null) {
                for (Map.Entry<String, Schema> e : props.entrySet()) {
                    String name = e.getKey();
                    Schema<?> ps = deref(e.getValue());
                    if (Boolean.TRUE.equals(ps.getReadOnly())) continue; // skip readOnly in requests
                    Object v = pickMaterializedValue(ps, depth + 1);
                    out.put(name, v);
                }
            }
            return out;
        }

        if ("array".equals(schema.getType()) && schema instanceof ArraySchema as) {
            Schema<?> items = deref(as.getItems());
            Object v = pickMaterializedValue(items, depth + 1);
            out.put("items", List.of(v));
        } else {
            out.put("value", pickMaterializedValue(schema, depth + 1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String materializeJsonFromSchema(Schema<?> schema, int depth) {
        if (schema == null || depth > 8) return "{}";
        schema = deref(schema);

        Schema<?> composed = composeSchema(schema, 0);
        if (composed != schema) schema = composed;

        if ("array".equals(schema.getType()) && schema instanceof ArraySchema as) {
            Schema<?> items = deref(as.getItems());
            String item = materializeJsonFromSchema(items, depth + 1);
            return "[" + (item == null || item.isEmpty() ? "{}" : item) + "]";
        }

        String type = schema.getType();
        if (type != null && !"object".equals(type) && !"array".equals(type)) {
            Object v = pickMaterializedValue(schema, depth + 1);
            if (v == null) return "null";
            return v instanceof String ? "\"" + escapeJson(String.valueOf(v)) + "\"" : String.valueOf(v);
        }

        Map<String, Schema> props = schema.getProperties();

        if ((props == null || props.isEmpty()) && schema.getAdditionalProperties() != null) {
            Schema<?> vSchema = schema.getAdditionalProperties() instanceof Schema
                    ? deref((Schema<?>) schema.getAdditionalProperties())
                    : null;
            String v = (vSchema != null) ? materializeJsonFromSchema(vSchema, depth + 1) : "\"string\"";
            return "{\"key\":" + v + "}";
        }

        if (props == null || props.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Schema> e : props.entrySet()) {
            String name = e.getKey();
            Schema<?> ps = deref(e.getValue());
            if (Boolean.TRUE.equals(ps.getReadOnly())) continue;

            String valueJson;
            if (ps.getExample() != null) {
                Object ex = ps.getExample();
                valueJson = (ex instanceof String && !looksLikeJson(String.valueOf(ex)))
                        ? "\"" + escapeJson(String.valueOf(ex)) + "\""
                        : renderJson(ex);
            } else if (ps.getDefault() != null) {
                Object dv = ps.getDefault();
                valueJson = (dv instanceof String && !looksLikeJson(String.valueOf(dv)))
                        ? "\"" + escapeJson(String.valueOf(dv)) + "\""
                        : renderJson(dv);
            } else if ("object".equals(ps.getType()) || "array".equals(ps.getType()) || ps.get$ref() != null
                    || ps.getProperties() != null || ps.getAdditionalProperties() != null) {
                valueJson = materializeJsonFromSchema(ps, depth + 1);
            } else {
                Object v = pickMaterializedValue(ps, depth + 1);
                if (v == null) valueJson = "null";
                else
                    valueJson = (v instanceof String) ? "\"" + escapeJson(String.valueOf(v)) + "\"" : String.valueOf(v);
            }

            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(name)).append("\":").append(valueJson);
        }
        sb.append("}");
        return sb.toString();
    }

    private Schema<?> composeSchema(Schema<?> schema, int depth) {
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            return deref((Schema<?>) schema.getOneOf().get(0));
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            return deref((Schema<?>) schema.getAnyOf().get(0));
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            Schema<?> merged = new Schema<>();
            merged.setType("object");
            Map<String, Schema> props = new LinkedHashMap<>();
            for (Object o : schema.getAllOf()) {
                Schema<?> part = deref((Schema<?>) o);
                if (part.getProperties() != null) props.putAll(part.getProperties());
            }
            merged.setProperties(props);
            return merged;
        }
        return schema;
    }

    private Object pickMaterializedValue(Schema<?> s, int depth) {
        if (s == null) return "string";

        if (Boolean.TRUE.equals(s.getNullable())) {
            if (s.getDefault() == null && s.getExample() == null && (s.getEnum() == null || s.getEnum().isEmpty())) {
                return null;
            }
        }

        if (s.getEnum() != null && !s.getEnum().isEmpty()) return s.getEnum().get(0);
        if (s.getExample() != null) return s.getExample();
        if (s.getDefault() != null) return s.getDefault();

        String t = s.getType();
        String f = s.getFormat();

        if ("array".equals(t) && s instanceof ArraySchema as) {
            Schema<?> items = deref(as.getItems());
            return List.of(pickMaterializedValue(items, depth + 1));
        }

        if ("boolean".equals(t)) return false;
        if ("integer".equals(t) || "number".equals(t)) return 0;
        if ("string".equals(t) || t == null) {
            if ("date".equals(f)) return "1970-01-01";
            if ("date-time".equals(f)) return "1970-01-01T00:00:00Z";
            if ("uuid".equals(f)) return "00000000-0000-0000-0000-000000000000";
            if ("byte".equals(f) || "binary".equals(f)) return "[[FILE-CONTENT]]";
            return "string";
        }

        if ("object".equals(t)) {
            Map<String, Object> m = materializeMapFromSchema(s, depth + 1);
            return m.isEmpty() ? new LinkedHashMap<String, Object>() : m;
        }

        return "string";
    }

    private String readBodyOverride(Map<String, Parameter> store, String path) {
        String target = wildcardArrays(path).toLowerCase(Locale.ROOT);
        for (Parameter p : store.values()) {
            if (!"body".equalsIgnoreCase(p.getIn())) continue;
            String jp = p.getJsonPath();
            if (jp == null) continue;
            if (jp.toLowerCase(Locale.ROOT).equals(target)) {
                String v = p.getValue();
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    private String applyBodyOverridesToJson(String json, Map<String, Parameter> store, Schema<?> schema) {
        if (json == null || json.isEmpty() || store == null || store.isEmpty()) return json;
        try {
            Object root = Json.mapper().readValue(json, Object.class);

            Map<String, String> types = topLevelTypes(schema);

            // Support root array body
            if (schema != null) {
                Schema<?> s = deref(schema);
                Schema<?> composed = composeSchema(s, 0);
                if (composed != null) s = composed;
                if ("array".equals(s.getType())) {
                    types.put("[]", "array");
                }
            }

            for (Parameter p : store.values()) {
                if (!"body".equalsIgnoreCase(p.getIn())) continue;
                if (p.getValue() == null) continue;

                String rawPath = p.getJsonPath();
                if (rawPath == null) continue;

                String normalized = wildcardToIndex(rawPath); // translate [] -> [0] for writes

                List<Object> tokens = tokenizePath(normalized);
                if (tokens.isEmpty()) continue;

                Object first = tokens.get(0);
                String top;
                if (first instanceof String) {
                    top = (String) first;
                } else {
                    // path may start with [0] when root is array, use pseudo key "[]"
                    top = "[]";
                }
                String kind = types.get(top);
                if (kind == null) continue;

                Object val = parseScalarOrJson(p.getValue());

                // 1) Do NOT wrap unless we are setting the entire array variable itself
                //    Whole array means the path is exactly the top-level key, so tokens.size() == 1.
                boolean settingWholeArray = tokens.size() == 1;

                if ("array".equals(kind) && settingWholeArray && !(val instanceof java.util.List)) {
                    val = java.util.List.of(val);
                }

                // 2) If value is an empty string, skip the override
                //  This prevents wiping out the spec example "string" for photoUrls[0].
                if (val instanceof String && ((String) val).isEmpty()) {
                    continue;
                }

                // If setting a whole object key with a scalar, ignore
                if ("object".equals(kind) && tokens.size() == 1 && !(val instanceof java.util.Map)) {
                    continue;
                }

                setJsonPathValue(root, normalized, val);

            }
            return Json.mapper().writeValueAsString(root);
        } catch (Throwable t) {
            return json;
        }
    }

    private void applyBodyOverridesToMap(Map<String, Object> map, Map<String, Parameter> store, String prefix) {
        if (map == null || store == null) return;
        for (Parameter p : store.values()) {
            if (!"body".equalsIgnoreCase(p.getIn())) continue;
            if (p.getValue() == null) continue;

            String path = p.getJsonPath();
            if (path == null) continue;

            // Only simple top-level keys apply to www-form or multipart
            if (!path.contains(".") && !path.contains("[") && !path.contains("]")) {
                if (!map.containsKey(path)) {
                    continue;
                }
                Object existing = map.get(path);
                Object val = p.getValue();
                if (existing instanceof java.util.List && !(val instanceof java.util.List)) {
                    val = java.util.List.of(val);
                }
                map.put(path, val);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void setJsonPathValue(Object root, String path, Object value) {
        if (root == null || path == null || path.isEmpty()) return;
        List<Object> tokens = tokenizePath(path);
        Object cur = root;
        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            boolean last = i == tokens.size() - 1;
            if (t instanceof String key) {
                if (!(cur instanceof Map)) return;
                Map<String, Object> m = (Map<String, Object>) cur;
                if (last) {
                    m.put(key, value);
                } else {
                    Object next = m.get(key);
                    if (next == null) {
                        Object nextTok = tokens.get(i + 1);
                        next = (nextTok instanceof Integer) ? new ArrayList<>() : new LinkedHashMap<String, Object>();
                        m.put(key, next);
                    }
                    cur = next;
                }
            } else if (t instanceof Integer idx) {
                if (!(cur instanceof List)) return;
                List<Object> arr = (List<Object>) cur;
                while (arr.size() <= idx) arr.add(null);
                if (last) {
                    arr.set(idx, value);
                } else {
                    Object next = arr.get(idx);
                    if (next == null) {
                        Object nextTok = tokens.get(i + 1);
                        next = (nextTok instanceof Integer) ? new ArrayList<>() : new LinkedHashMap<String, Object>();
                        arr.set(idx, next);
                    }
                    cur = next;
                }
            }
        }
    }

    private List<Object> tokenizePath(String path) {
        List<Object> out = new ArrayList<>();
        int i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                i++;
            } else if (c == '[') {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                int j = path.indexOf(']', i);
                if (j > i + 1) {
                    String num = path.substring(i + 1, j);
                    try {
                        out.add(Integer.parseInt(num));
                    } catch (Exception ignored) {
                    }
                    i = j + 1;
                } else if (j == i + 1) { // "[]"
                    out.add(0); // treat [] as index 0 for writes
                    i = j + 1;
                } else {
                    i++;
                }
            } else {
                buf.append(c);
                i++;
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    private Object parseScalarOrJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        try {
            if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
                return Json.mapper().readValue(t, Object.class);
            }
            if ("null".equalsIgnoreCase(t)) return null;
            if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
            if (t.matches("^-?\\d+$")) return Long.parseLong(t);
            if (t.matches("^-?\\d+\\.\\d+$")) return Double.parseDouble(t);
        } catch (Throwable ignored) {
        }
        return s;
    }

    private boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private String renderJson(Object example) {
        try {
            return Json.mapper().writeValueAsString(example);
        } catch (Throwable t) {
            return String.valueOf(example);
        }
    }

    private String renderWwwForm(Object example) {
        if (example instanceof String) return (String) example;
        if (example instanceof Map<?, ?> map) {
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = java.net.URLEncoder.encode(String.valueOf(e.getKey()), StandardCharsets.UTF_8);
                String v = java.net.URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8);
                pairs.add(k + "=" + v);
            }
            return String.join("&", pairs);
        }
        return String.valueOf(example);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private List<io.swagger.v3.oas.models.parameters.Parameter> effectiveParameters(Endpoint endpoint) {
        List<io.swagger.v3.oas.models.parameters.Parameter> out = new ArrayList<>();
        io.swagger.v3.oas.models.PathItem pi =
                context.getOpenAPI().getPaths() != null
                        ? context.getOpenAPI().getPaths().get(endpoint.getPath())
                        : null;
        if (pi != null && pi.getParameters() != null) out.addAll(pi.getParameters());
        if (endpoint.getOperation() != null && endpoint.getOperation().getParameters() != null) {
            out.addAll(endpoint.getOperation().getParameters());
        }
        Map<String, io.swagger.v3.oas.models.parameters.Parameter> uniq = new LinkedHashMap<>();
        for (io.swagger.v3.oas.models.parameters.Parameter p : out) {
            io.swagger.v3.oas.models.parameters.Parameter dp = derefParam(p);
            if (dp.getName() != null && dp.getIn() != null) {
                uniq.put(dp.getIn() + "|" + dp.getName(), dp);
            }
        }
        return new ArrayList<>(uniq.values());
    }

    private io.swagger.v3.oas.models.parameters.Parameter derefParam(io.swagger.v3.oas.models.parameters.Parameter p) {
        if (p == null || p.get$ref() == null) return p;
        String name = p.get$ref().substring(p.get$ref().lastIndexOf('/') + 1);
        return context.getOpenAPI() != null && context.getOpenAPI().getComponents() != null
                ? Optional.ofNullable(context.getOpenAPI().getComponents().getParameters())
                .map(m -> m.get(name)).orElse(p)
                : p;
    }

    private Map<String, String> topLevelTypes(Schema<?> schema) {
        Map<String, String> out = new LinkedHashMap<>();
        if (schema == null) return out;
        schema = deref(schema);
        Schema<?> composed = composeSchema(schema, 0);
        if (composed != schema) schema = composed;

        if ("array".equals(schema.getType())) {
            out.put("[]", "array"); // root array support
        }

        Map<String, Schema> props = schema.getProperties();
        if (props != null) {
            for (Map.Entry<String, Schema> e : props.entrySet()) {
                Schema<?> ps = deref(e.getValue());
                String t = ps.getType();
                if (t == null) {
                    if (ps.getProperties() != null || ps.getAdditionalProperties() != null) t = "object";
                }
                out.put(e.getKey(), t == null ? "string" : t);
            }
        }
        return out;
    }

    public List<HttpRequest> buildRequestsForBulkSend(Endpoint endpoint) {
        List<String> bases = resolveServerBaseUrls();
        List<HttpRequest> out = new ArrayList<>();
        if (bases.isEmpty()) return out;

        HttpRequest baseReq = buildRequest(endpoint);
        if (baseReq == null) return out;

        if (!context.isIterateAcrossAllServers() || bases.size() == 1) {
            out.add(baseReq);
            return out;
        }

        String originalBasePath = "";
        try {
            URL first = bases.get(0).startsWith("/")
                    ? new URL(new URL(Objects.requireNonNull(context.getApiHost())), bases.get(0))
                    : new URL(bases.get(0));
            originalBasePath = first.getPath();
            if ("/".equals(originalBasePath)) originalBasePath = "";
            if (originalBasePath.endsWith("/")) {
                originalBasePath = originalBasePath.substring(0, originalBasePath.length() - 1);
            }
        } catch (Throwable ignored) {
        }

        String baseReqPath = baseReq.path();
        String pathRemainder = baseReqPath;
        if (!originalBasePath.isEmpty() && baseReqPath.startsWith(originalBasePath)) {
            pathRemainder = baseReqPath.substring(originalBasePath.length());
            if (!pathRemainder.startsWith("/")) pathRemainder = "/" + pathRemainder;
        }

        for (String base : bases) {
            try {
                URL u = base.startsWith("/")
                        ? new URL(new URL(Objects.requireNonNull(context.getApiHost())), base)
                        : new URL(base);

                int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
                boolean secure = "https".equalsIgnoreCase(u.getProtocol());
                HttpService svc = httpService(u.getHost(), port, secure);

                String newBasePath = u.getPath();
                if ("/".equals(newBasePath)) newBasePath = "";
                if (newBasePath.endsWith("/")) {
                    newBasePath = newBasePath.substring(0, newBasePath.length() - 1);
                }
                String finalPath = joinUrl(newBasePath, pathRemainder);

                List<HttpHeader> newHeaders = new ArrayList<>();
                for (HttpHeader h : baseReq.headers()) {
                    if (!h.name().equalsIgnoreCase("Host")) {
                        newHeaders.add(h);
                    }
                }
                String hostValue = u.getHost();
                if ((secure && port != 443) || (!secure && port != 80)) {
                    hostValue = hostValue + ":" + port;
                }
                newHeaders.add(httpHeader("Host", hostValue));

                HttpRequest copy = HttpRequest.httpRequest()
                        .withService(svc)
                        .withPath(finalPath)
                        .withMethod(baseReq.method())
                        .withAddedHeaders(newHeaders)
                        .withBody(baseReq.body().toString());

                out.add(copy);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private String joinUrl(String base, String path) {
        if (base == null) base = "";
        if (path == null) path = "/";
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    // Percent-encode for path segment safety
    private String encodePathSegment(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte v : b) {
                    out.append('%').append(String.format("%02X", v));
                }
            }
        }
        return out.toString();
    }

    private static class BuiltBody {
        final String body;
        final List<HttpHeader> headers;

        BuiltBody(String body, List<HttpHeader> headers) {
            this.body = body;
            this.headers = headers;
        }

        static BuiltBody empty() {
            return new BuiltBody("", List.of());
        }
    }

    private static String wildcardArrays(String path) {
        if (path == null) return "";
        return path.replaceAll("\\[\\d+\\]", "[]");
    }

    private static String wildcardToIndex(String path) {
        if (path == null) return "";
        return path.replace("[]", "[0]");
    }

    private Parameter findParam(Map<String, Parameter> store, String in, String name) {
        if (store == null || in == null || name == null) return null;
        for (Parameter p : store.values()) {
            if (p == null) continue;
            if (!in.equalsIgnoreCase(p.getIn())) continue;
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}
