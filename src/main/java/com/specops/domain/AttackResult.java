package com.specops.domain;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

/**
 * A data object representing the result of a single HTTP request sent by the "Ping Endpoints" feature.
 * This is used to populate the table in the "Attack Results" tab.
 */
public class AttackResult {

    private final Endpoint endpoint;
    private final HttpRequest request;
    private final HttpResponse response;
    private final String timestamp;

    public AttackResult(Endpoint endpoint, HttpRequest request, HttpResponse response, String timestamp) {
        this.endpoint = endpoint;
        this.request = request;
        this.response = response;
        this.timestamp = timestamp;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public HttpResponse getResponse() {
        return response;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public short getStatusCode() {
        return response != null ? response.statusCode() : -1;
    }

    public int getResponseLength() {
        return response != null ? response.body().length() : 0;
    }

    /** The server this request targeted, e.g. "https://api.example.com" (port shown only when non-default). */
    public String getTarget() {
        return describeTarget(request);
    }

    /** Render an HttpRequest's destination as scheme://host[:port], omitting the default port. */
    public static String describeTarget(HttpRequest request) {
        if (request == null) return "";
        HttpService service = request.httpService();
        if (service == null) return "";
        String scheme = service.secure() ? "https" : "http";
        String target = scheme + "://" + service.host();
        if ((service.secure() && service.port() != 443) || (!service.secure() && service.port() != 80)) {
            target += ":" + service.port();
        }
        return target;
    }
}