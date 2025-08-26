package com.specops;

import burp.api.montoya.MontoyaApi;
import com.specops.domain.AttackResult;
import com.specops.domain.Endpoint;
import com.specops.domain.Parameter;
import com.specops.domain.rules.HeaderRule;
import io.swagger.v3.oas.models.OpenAPI;

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

    private Consumer<Void> endpointsUpdateListener;
    private Consumer<Void> parametersUpdateListener;
    private Consumer<Void> serversUpdateListener;
    private Runnable bindingsUpdateListener; // Runnable by design (no arg needed)
    private Consumer<AttackResult> attackResultListener;

    public SpecOpsContext(MontoyaApi api) {
        this.api = api;
        this.endpoints = new CopyOnWriteArrayList<>();
        this.globalParameterStore = new ConcurrentHashMap<>();
        this.attackResults = new CopyOnWriteArrayList<>();
        this.headerRules = new CopyOnWriteArrayList<>();
        this.serverVariableOverrides = new ConcurrentHashMap<>();
        this.authTokens = new ConcurrentHashMap<>();
        this.headersApplyToWorkbench = false;
    }

    public OpenAPI getOpenAPI() { return openAPI; }
    public List<Endpoint> getEndpoints() { return endpoints; }
    public Map<String, Parameter> getGlobalParameterStore() { return globalParameterStore; }
    public List<AttackResult> getAttackResults() { return attackResults; }

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

        this.attackResults.clear();

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
        this.attackResults.add(result);
        if (attackResultListener != null) {
            attackResultListener.accept(result);
        }
    }

    public void setAttackResultListener(Consumer<AttackResult> listener) {
        this.attackResultListener = listener;
    }

    public void setEndpointsUpdateListener(Consumer<Void> listener) {
        this.endpointsUpdateListener = listener;
    }

    public void setParametersUpdateListener(Consumer<Void> listener) {
        this.parametersUpdateListener = listener;
    }

    public void setServersUpdateListener(Consumer<Void> listener) {
        this.serversUpdateListener = listener;
    }

    /** Bindings/stats panel; Runnable is fine since there’s no payload. */
    public void setBindingsUpdateListener(Runnable r) {
        this.bindingsUpdateListener = r;
    }

    public void notifyEndpointsChanged() {
        if (endpointsUpdateListener != null) endpointsUpdateListener.accept(null);
    }

    public void notifyParametersChanged() {
        if (parametersUpdateListener != null) parametersUpdateListener.accept(null);
    }

    public void notifyServersChanged() {
        if (serversUpdateListener != null) serversUpdateListener.accept(null);
    }

    public void notifyBindingsChanged() {
        if (bindingsUpdateListener != null) bindingsUpdateListener.run();
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
