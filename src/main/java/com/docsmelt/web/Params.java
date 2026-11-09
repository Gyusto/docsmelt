package com.docsmelt.web;

import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/**
 * The request's form fields, copied out of the request. Background jobs keep
 * running after the HTTP request has finished, so they can't read the
 * request itself.
 */
final class Params {

    private final Map<String, List<String>> fields;

    private Params(Map<String, List<String>> fields) {
        this.fields = fields;
    }

    static Params of(Context ctx) {
        return new Params(Map.copyOf(ctx.formParamMap()));
    }

    static Params of(Map<String, String> values) {
        return new Params(values.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.of(e.getValue()))));
    }

    /** First value of a field, or null. */
    String formParam(String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
