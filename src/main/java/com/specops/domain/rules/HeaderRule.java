package com.specops.domain.rules;

public class HeaderRule {
    public boolean enabled;
    public String name;
    public String value; // supports ${param.key}
    public Scope scope = Scope.ALL;  // ALL, HOST, PATH_PREFIX, TAG, METHOD
    public String match; // e.g., "api.example.com" or "/v1/"
    public boolean overwrite;

    public enum Scope {ALL, HOST, PATH_PREFIX, TAG, METHOD}
}
