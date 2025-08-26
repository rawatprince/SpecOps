package com.specops.domain;

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
}