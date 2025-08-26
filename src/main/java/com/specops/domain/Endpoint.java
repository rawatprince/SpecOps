package com.specops.domain;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.util.*;

/**
 * Represents a single API endpoint discovered from the OpenAPI specification.
 * One HTTP method on a specific path.
 */
public class Endpoint {

    private final String path;
    private final PathItem.HttpMethod method;
    private final Operation operation;
    private BindingStatus bindingStatus = BindingStatus.UNKNOWN;

    public Endpoint(String path, PathItem.HttpMethod method, Operation operation) {
        this.path = path;
        this.method = method;
        this.operation = operation;
    }

    public String getPath() {
        return path;
    }

    public PathItem.HttpMethod getMethod() {
        return method;
    }

    public Operation getOperation() {
        return operation;
    }

    /**
     * Original operation-level parameter list (may be empty).
     */
    public List<Parameter> getParameters() {
        return operation.getParameters() == null
                ? Collections.emptyList()
                : operation.getParameters();
    }

    public BindingStatus getBindingStatus() {
        return bindingStatus;
    }

    public void setBindingStatus(BindingStatus bindingStatus) {
        this.bindingStatus = bindingStatus;
    }

    public String getSummary() {
        return operation.getSummary() != null ? operation.getSummary() : "";
    }

    /**
     * Effective parameter list per OpenAPI rules:
     * path-level parameters plus operation-level parameters,
     * with operation-level overriding on the in|name key.
     */
    public List<Parameter> getAllParameters(OpenAPI openAPI) {
        Map<String, Parameter> uniq = new LinkedHashMap<>();

        PathItem pathItem = (openAPI.getPaths() != null) ? openAPI.getPaths().get(path) : null;

        // path-level first
        if (pathItem != null && pathItem.getParameters() != null) {
            for (Parameter p : pathItem.getParameters()) {
                if (p != null && p.getName() != null && p.getIn() != null) {
                    uniq.put(p.getIn() + "|" + p.getName(), p);
                }
            }
        }

        // operation-level overrides
        if (operation.getParameters() != null) {
            for (Parameter p : operation.getParameters()) {
                if (p != null && p.getName() != null && p.getIn() != null) {
                    uniq.put(p.getIn() + "|" + p.getName(), p);
                }
            }
        }

        return new ArrayList<>(uniq.values());
    }

    public enum BindingStatus {
        UNKNOWN,
        READY,
        MISSING_REQUIRED
    }
}
