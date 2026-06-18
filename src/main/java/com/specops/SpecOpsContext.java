package com.specops;

import burp.api.montoya.MontoyaApi;
import com.specops.domain.AttackResult;
import com.specops.domain.Endpoint;
import com.specops.domain.Parameter;
import com.specops.domain.rules.HeaderRule;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central shared state for the extension.
 * UI and services read and update this context to stay in sync.
 */
public class SpecOpsContext {
    public final MontoyaApi api;

    private final List<Endpoint> endpoints;
    private final Map<String, Parameter> globalParameterStore;
    private final List<AttackResult> attackResults;
    private final List<HeaderRule> headerRules;
    private final Map<Integer, Map<String, String>> serverVariableOverrides;
    private final Map<String, String> authTokens;

    private OpenAPI openAPI;
    private String apiHost;

    private volatile boolean headersApplyToWorkbench;
    private volatile int selectedServerIndex = 0;
    private volatile boolean iterateAcrossAllServers = false;

    // Listener registries: each notifier supports multiple subscribers so that
    // independent tabs can react to the same event without overwriting each other.
    private final List<Consumer<Void>> endpointsUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Void>> parametersUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Void>> serversUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> bindingsUpdateListeners = new CopyOnWriteArrayList<>(); // Runnable by design (no arg needed)
    private final List<Consumer<AttackResult>> attackResultListeners = new CopyOnWriteArrayList<>();

    public SpecOpsContext(MontoyaApi api) {
        this.api = api;
        this.endpoints = new CopyOnWriteArrayList<>();
        this.globalParameterStore = new ConcurrentHashMap<>();
        this.attackResults = Collections.synchronizedList(new ArrayList<>());
        this.headerRules = new CopyOnWriteArrayList<>();
        this.serverVariableOverrides = new ConcurrentHashMap<>();
        this.authTokens = new ConcurrentHashMap<>();
        this.headersApplyToWorkbench = false;
    }

    public OpenAPI getOpenAPI() { return openAPI; }
    public List<Endpoint> getEndpoints() { return endpoints; }
    public Map<String, Parameter> getGlobalParameterStore() { return globalParameterStore; }
    public List<AttackResult> getAttackResults() { return attackResults; }

    public int getAttackResultCount() {
        synchronized (attackResults) {
            return attackResults.size();
        }
    }

    public AttackResult getAttackResultAt(int index) {
        synchronized (attackResults) {
            if (index < 0 || index >= attackResults.size()) {
                return null;
            }
            return attackResults.get(index);
        }
    }

    public List<AttackResult> getAttackResultsSnapshot() {
        synchronized (attackResults) {
            return new ArrayList<>(attackResults);
        }
    }

    public void clearAttackResults() {
        synchronized (attackResults) {
            attackResults.clear();
        }
    }

    /**
     * Replace the whole model (used after parsing a spec).
     * Calls all relevant notifiers (including bindings) so dependent UIs refresh.
     */
    public void resetModel(OpenAPI openAPI, List<Endpoint> endpoints, Map<String, Parameter> parameters) {
        this.openAPI = openAPI;

        this.endpoints.clear();
        if (endpoints != null) this.endpoints.addAll(endpoints);

        this.globalParameterStore.clear();
        if (parameters != null) {
            for (Parameter p : parameters.values()) {
                normalizeParameterForStore(p);
                String key = canonicalKey(p);
                Parameter prev = this.globalParameterStore.putIfAbsent(key, p);
                if (prev != null) {
                    // merge basic fields into existing
                    mergeParameter(prev, p, false, null);
                }
            }
        }

        clearAttackResults();

        this.selectedServerIndex = 0;
        this.serverVariableOverrides.clear();
        this.iterateAcrossAllServers = false;

        notifyEndpointsChanged();
        notifyParametersChanged();
        notifyBindingsChanged();     // important: bindings/stat panels refresh on reset
        notifyServersChanged();

        int endpointsCount = endpoints != null ? endpoints.size() : 0;
        int paramsCount = this.globalParameterStore.size();

        api.logging().logToOutput(
                "Parsed OpenAPI spec. Found " + endpointsCount + " endpoints and "
                        + paramsCount + " unique parameters."
        );
    }

    /** Insert or update a single global parameter. */
    public void upsertGlobalParameter(Parameter incoming, boolean overwriteLocked, Parameter.ValueSource sourceIfUpdate) {
        if (incoming == null) return;
        normalizeParameterForStore(incoming);
        String key = canonicalKey(incoming);

        globalParameterStore.merge(key, incoming, (existing, inc) -> {
            mergeParameter(existing, inc, overwriteLocked, sourceIfUpdate);
            return existing;
        });

        notifyParametersChanged();
        notifyBindingsChanged(); // keep binding stats in sync with parameter mutations
    }

    /** Canonical key: non-body -> "in:name", body -> "body:jsonPath" (array indices wildcarded). */
    public static String canonicalKey(Parameter p) {
        String in = nz(p.getIn()).toLowerCase(Locale.ROOT);
        if ("body".equals(in)) {
            String path = wildcardArrays(nz(p.getJsonPath()));
            if (!path.isEmpty()) {
                return in + ":" + path.toLowerCase(Locale.ROOT);
            }
        }
        return in + ":" + nz(p.getName()).toLowerCase(Locale.ROOT);
    }

    /** Replace concrete array indices with [] so keys remain stable across examples. */
    public static String wildcardArrays(String path) {
        if (path == null || path.isEmpty()) return "";
        return path.replaceAll("\\[\\d+\\]", "[]");
    }

    private static void normalizeParameterForStore(Parameter p) {
        if (p == null) return;
        if ("body".equalsIgnoreCase(nz(p.getIn()))) {
            String jp = p.getJsonPath();
            if (jp != null && !jp.isEmpty()) {
                p.setJsonPath(wildcardArrays(jp));
            }
        }
    }

    private static void mergeParameter(Parameter existing, Parameter inc,
                                       boolean overwriteLocked,
                                       Parameter.ValueSource sourceIfUpdate) {
        if (existing == null || inc == null) return;

        // Value (respect lock unless explicitly overwriting)
        if (!isEmpty(inc.getValue())) {
            if (!existing.isLocked() || overwriteLocked) {
                existing.setValue(inc.getValue());
                if (sourceIfUpdate != null) {
                    existing.setSource(sourceIfUpdate);
                }
            }
        }

        // Lock
        if (inc.isLocked()) {
            existing.setLocked(true);
        }

        // Metadata
        if (isEmpty(existing.getDescription()) && !isEmpty(inc.getDescription())) {
            existing.setDescription(inc.getDescription());
        }
        if (!existing.hasEnum() && inc.hasEnum()) {
            existing.setEnumValues(inc.getEnumValues());
        }
        if (!existing.isRequired() && inc.isRequired()) {
            existing.setRequired(true);
        }

        // Body path
        if ("body".equalsIgnoreCase(nz(existing.getIn()))) {
            if (isEmpty(existing.getJsonPath()) && !isEmpty(inc.getJsonPath())) {
                existing.setJsonPath(wildcardArrays(inc.getJsonPath()));
            }
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }

    public void addAttackResult(AttackResult result) {
        synchronized (attackResults) {
            this.attackResults.add(result);
        }
        for (Consumer<AttackResult> listener : attackResultListeners) {
            dispatch(() -> listener.accept(result));
        }
    }

    /** Register a listener for new attack results. Multiple listeners are supported. */
    public void addAttackResultListener(Consumer<AttackResult> listener) {
        if (listener != null) attackResultListeners.add(listener);
    }

    /** Register a listener for endpoint-model changes. Multiple listeners are supported. */
    public void addEndpointsUpdateListener(Consumer<Void> listener) {
        if (listener != null) endpointsUpdateListeners.add(listener);
    }

    /** Register a listener for parameter-store changes. Multiple listeners are supported. */
    public void addParametersUpdateListener(Consumer<Void> listener) {
        if (listener != null) parametersUpdateListeners.add(listener);
    }

    /** Register a listener for server/auth changes. Multiple listeners are supported. */
    public void addServersUpdateListener(Consumer<Void> listener) {
        if (listener != null) serversUpdateListeners.add(listener);
    }

    /** Bindings/stats panel; Runnable is fine since there’s no payload. Multiple listeners are supported. */
    public void addBindingsUpdateListener(Runnable r) {
        if (r != null) bindingsUpdateListeners.add(r);
    }

    /**
     * Invokes every endpoints listener on the calling thread.
     * Swing listeners must dispatch UI mutations to the EDT.
     */
    public void notifyEndpointsChanged() {
        for (Consumer<Void> listener : endpointsUpdateListeners) {
            dispatch(() -> listener.accept(null));
        }
    }

    /**
     * Invokes every parameters listener on the calling thread.
     * Swing listeners must dispatch UI mutations to the EDT.
     */
    public void notifyParametersChanged() {
        for (Consumer<Void> listener : parametersUpdateListeners) {
            dispatch(() -> listener.accept(null));
        }
    }

    /**
     * Invokes every server listener on the calling thread.
     * Swing listeners must dispatch UI mutations to the EDT.
     */
    public void notifyServersChanged() {
        for (Consumer<Void> listener : serversUpdateListeners) {
            dispatch(() -> listener.accept(null));
        }
    }

    /**
     * Invokes every bindings listener on the calling thread.
     * Swing listeners must dispatch UI mutations to the EDT.
     */
    public void notifyBindingsChanged() {
        for (Runnable listener : bindingsUpdateListeners) {
            dispatch(listener);
        }
    }

    /**
     * Runs a single listener callback, isolating failures so one misbehaving
     * subscriber cannot prevent the others from being notified.
     */
    private void dispatch(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            api.logging().logToError("SpecOps listener notification failed: " + ex.getMessage());
        }
    }

    public String getApiHost() { return apiHost; }
    public void setApiHost(String apiHost) { this.apiHost = apiHost; }

    public List<HeaderRule> getHeaderRules() { return headerRules; }

    public boolean isHeadersApplyToWorkbench() { return headersApplyToWorkbench; }
    public void setHeadersApplyToWorkbench(boolean v) { headersApplyToWorkbench = v; }

    public int getSelectedServerIndex() { return selectedServerIndex; }
    public void setSelectedServerIndex(int idx) {
        if (idx < 0) idx = 0;
        this.selectedServerIndex = idx;
        notifyServersChanged();
    }

    public void setServerVariableOverrides(int serverIndex, Map<String, String> overrides) {
        if (overrides == null) {
            this.serverVariableOverrides.remove(serverIndex);
        } else {
            this.serverVariableOverrides.put(serverIndex, new ConcurrentHashMap<>(overrides));
        }
        notifyServersChanged();
    }

    public Map<String, String> getServerVariableOverrides(int serverIndex) {
        return this.serverVariableOverrides.computeIfAbsent(serverIndex, k -> new ConcurrentHashMap<>());
    }

    public boolean isIterateAcrossAllServers() { return iterateAcrossAllServers; }
    public void setIterateAcrossAllServers(boolean iterateAcrossAllServers) {
        this.iterateAcrossAllServers = iterateAcrossAllServers;
        notifyServersChanged();
    }

    /**
     * Human-readable target for the Endpoints Workbench: the resolved selected-server URL,
     * or an all-servers summary when iterate mode is on.
     */
    public String getServerTargetLabel() {
        if (openAPI == null || openAPI.getServers() == null || openAPI.getServers().isEmpty()) {
            return "(no server)";
        }
        List<Server> servers = openAPI.getServers();
        if (iterateAcrossAllServers) {
            return "All servers (" + servers.size() + ")";
        }
        return resolveAbsoluteServerUrl(selectedServerIndex);
    }

    /**
     * Absolute target URL for a server index: variables resolved, and a relative server URL
     * (e.g. "/api/v3") resolved against the host the spec was loaded from.
     */
    public String resolveAbsoluteServerUrl(int serverIndex) {
        if (openAPI == null || openAPI.getServers() == null || openAPI.getServers().isEmpty()) return "";
        List<Server> servers = openAPI.getServers();
        int idx = Math.min(Math.max(serverIndex, 0), servers.size() - 1);
        return absolutize(resolveServerUrl(servers.get(idx), idx));
    }

    /** Prepend the spec host to a relative server URL so it displays and targets as an absolute URL. */
    private String absolutize(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("/") && apiHost != null && !apiHost.isBlank()) {
            String host = apiHost.endsWith("/") ? apiHost.substring(0, apiHost.length() - 1) : apiHost;
            return host + url;
        }
        return url;
    }

    /** Resolve a server URL template against its variable defaults and any user overrides. */
    private String resolveServerUrl(Server server, int serverIndex) {
        if (server == null || server.getUrl() == null) return "";
        String url = server.getUrl();
        Map<String, String> values = new HashMap<>();
        if (server.getVariables() != null) {
            server.getVariables().forEach((k, v) ->
                    values.put(k, v != null && v.getDefault() != null ? v.getDefault() : ""));
        }
        values.putAll(getServerVariableOverrides(serverIndex));
        for (Map.Entry<String, String> e : values.entrySet()) {
            url = url.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return url;
    }

    public void setAuthToken(String schemeName, String value) {
        if (schemeName == null) return;
        if (value == null) {
            authTokens.remove(schemeName);
        } else {
            authTokens.put(schemeName, value);
        }
        notifyServersChanged();
    }

    public String getAuthToken(String schemeName) {
        return schemeName == null ? null : authTokens.get(schemeName);
    }

    public Map<String, String> getAllAuthTokens() { return authTokens; }
}
