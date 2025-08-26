package com.specops.services.openapi;

import com.specops.SpecOpsContext;
import com.specops.domain.Endpoint;
import com.specops.domain.Parameter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.util.*;

public class OpenApiParser {
    private final SpecOpsContext context;

    public OpenApiParser(SpecOpsContext context) {
        this.context = context;
    }

    private static boolean looksLikeSwagger2(String specContent, List<String> messages) {
        if (messages != null) {
            for (String m : messages) {
                if (m == null) continue;
                String s = m.toLowerCase(Locale.ROOT);
                if (s.contains("attribute openapi is missing")) return true;
                if (s.contains("attribute openapi")) return true;
                if (s.contains("swagger version 2.0")) return true;
            }
        }
        String t = specContent == null ? "" : specContent.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        int i = t.indexOf("\"swagger\":\"2.0\"");
        int j = t.indexOf("swagger:2.0");
        return (i >= 0 && i < 4096) || (j >= 0 && j < 4096);
    }

    /**
     * Parses the given spec content. Tries OAS3 first, then falls back to Swagger 2.0
     * with in-memory conversion to OAS3. On success, updates the context model.
     */
    public boolean parse(String specContent) {
        if (specContent == null || specContent.trim().isEmpty()) {
            context.api.logging().logToError("Failed to parse OpenAPI specification. Issues: empty content");
            return false;
        }

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setFlatten(true);

        // Try OAS3
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(specContent, null, options);
        OpenAPI openAPI = result != null ? result.getOpenAPI() : null;
        List<String> messages = result != null && result.getMessages() != null
                ? new ArrayList<>(result.getMessages())
                : new ArrayList<>();

        boolean parsedAsV3 = openAPI != null && openAPI.getPaths() != null;

        // Fall back to Swagger 2.0 and convert
        if (!parsedAsV3 && looksLikeSwagger2(specContent, messages)) {
            try {
                SwaggerParseResult v2Conv = new SwaggerConverter().readContents(specContent, null, options);
                OpenAPI conv = v2Conv != null ? v2Conv.getOpenAPI() : null;
                if (conv != null && conv.getPaths() != null) {
                    openAPI = conv;
                    messages.add("Converted Swagger 2.0 to OpenAPI 3.x in memory");
                    parsedAsV3 = true;
                } else {
                    if (v2Conv != null && v2Conv.getMessages() != null && !v2Conv.getMessages().isEmpty()) {
                        messages.addAll(v2Conv.getMessages());
                    }
                    messages.add("Conversion from Swagger 2.0 to OpenAPI 3.x returned null or empty paths");
                }
            } catch (Exception e) {
                messages.add("Exception during Swagger 2.0 conversion: " + e.getMessage());
            }
        }

        if (!parsedAsV3) {
            String errorMessage = "Failed to parse OpenAPI specification.";
            if (!messages.isEmpty()) errorMessage += " Issues: " + String.join(", ", messages);
            context.api.logging().logToError(errorMessage);
            return false;
        }

        final OpenAPI oas = openAPI;

        // Build data model
        List<Endpoint> endpoints = new ArrayList<>();
        Map<String, Parameter> parameters = new HashMap<>();

        oas.getPaths().forEach((path, pathItem) -> {
            if (pathItem == null) return;

            pathItem.readOperationsMap().forEach((method, operation) -> {
                if (operation == null) return;

                endpoints.add(new Endpoint(path, method, operation));

                // classic parameter locations
                if (pathItem.getParameters() != null) {
                    for (io.swagger.v3.oas.models.parameters.Parameter p : pathItem.getParameters()) {
                        addParameterToMap(derefParam(p, oas), parameters);
                    }
                }
                if (operation.getParameters() != null) {
                    for (io.swagger.v3.oas.models.parameters.Parameter p : operation.getParameters()) {
                        addParameterToMap(derefParam(p, oas), parameters);
                    }
                }

                try {
                    indexRequestBodyParams(oas, operation, parameters);
                } catch (Exception e) {
                    context.api.logging().logToError("While indexing requestBody for " + method + " " + path + ": " + e.getMessage());
                }
            });
        });

        context.resetModel(oas, endpoints, parameters);
        return true;
    }

    private io.swagger.v3.oas.models.parameters.Parameter derefParam(
            io.swagger.v3.oas.models.parameters.Parameter p, OpenAPI openAPI) {
        if (p == null || p.get$ref() == null) return p;
        String name = p.get$ref().substring(p.get$ref().lastIndexOf('/') + 1);
        if (openAPI.getComponents() != null && openAPI.getComponents().getParameters() != null) {
            io.swagger.v3.oas.models.parameters.Parameter resolved =
                    openAPI.getComponents().getParameters().get(name);
            return resolved != null ? resolved : p;
        }
        return p;
    }

    private void addParameterToMap(io.swagger.v3.oas.models.parameters.Parameter parsedParam,
                                   Map<String, Parameter> parameters) {
        if (parsedParam == null || parsedParam.getName() == null || parsedParam.getIn() == null) return;

        String type = (parsedParam.getSchema() != null && parsedParam.getSchema().getType() != null)
                ? parsedParam.getSchema().getType() : "string";

        Parameter p = new Parameter(parsedParam.getName(), parsedParam.getIn(), type);

        p.setDescription(parsedParam.getDescription());
        p.setRequired(Boolean.TRUE.equals(parsedParam.getRequired()));

        if (parsedParam.getSchema() != null) {
            var schema = parsedParam.getSchema();
            if (schema.getDefault() != null) p.setDefaultValue(String.valueOf(schema.getDefault()));
            if (schema.getExample() != null) p.setExampleValue(String.valueOf(schema.getExample()));
            if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                java.util.List<String> enums = new java.util.ArrayList<>();
                for (Object ev : schema.getEnum()) enums.add(String.valueOf(ev));
                p.setEnumValues(enums);
            }
        }
        if (p.getExampleValue() == null && parsedParam.getExample() != null) {
            p.setExampleValue(String.valueOf(parsedParam.getExample()));
        }

        Parameter existing = parameters.get(p.getUniqueKey());
        if (existing == null) {
            String initial = p.getDefaultValue();
            if ((initial == null || initial.isEmpty()) && p.getExampleValue() != null) initial = p.getExampleValue();
            if ((initial == null || initial.isEmpty()) && p.hasEnum()) initial = p.getEnumValues().get(0);
            if (initial != null && !initial.isEmpty()) {
                p.setValue(initial);
                p.setSource(Parameter.ValueSource.DEFAULT);
            }
            parameters.put(p.getUniqueKey(), p);
        } else {
            // keep existing user value
        }
    }

    // RequestBody indexing to global params

    private void indexRequestBodyParams(OpenAPI oas, Operation op, Map<String, Parameter> out) {
        if (op == null) return;
        RequestBody rb = op.getRequestBody();
        rb = derefRequestBody(oas, rb);
        if (rb == null || rb.getContent() == null || rb.getContent().isEmpty()) return;

        // prefer JSON-like media first, but index all with a budget
        List<Map.Entry<String, io.swagger.v3.oas.models.media.MediaType>> entries = new ArrayList<>(rb.getContent().entrySet());
        entries.sort((a, b) -> Integer.compare(mediaRank(a.getKey()), mediaRank(b.getKey())));

        int budget = 300; // cap per operation

        for (Map.Entry<String, io.swagger.v3.oas.models.media.MediaType> e : entries) {
            String mt = normalizeMediaKey(e.getKey());
            io.swagger.v3.oas.models.media.MediaType media = e.getValue();
            Schema<?> schema = derefSchema(oas, media != null ? media.getSchema() : null);
            if (schema == null) continue;

            if (isJsonLike(mt) || mt.equals("application/x-www-form-urlencoded") || mt.equals("multipart/form-data")) {
                Set<String> requiredSet = new HashSet<>();
                if (schema.getRequired() != null) requiredSet.addAll(schema.getRequired());
                budget = indexSchema("", schema, requiredSet, oas, out, budget);
            } else {
                addBodyParam(out, "", "body", "string", schema.getDescription(), false, schema);
            }

            if (budget <= 0) break;
        }
    }

    private RequestBody derefRequestBody(OpenAPI oas, RequestBody rb) {
        if (rb == null || rb.get$ref() == null) return rb;
        String name = rb.get$ref().substring(rb.get$ref().lastIndexOf('/') + 1);
        return oas.getComponents() != null
                && oas.getComponents().getRequestBodies() != null
                ? oas.getComponents().getRequestBodies().getOrDefault(name, rb)
                : rb;
    }

    private Schema<?> derefSchema(OpenAPI oas, Schema<?> s) {
        if (s == null || s.get$ref() == null) return s;
        String name = s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1);
        return oas.getComponents() != null
                && oas.getComponents().getSchemas() != null
                ? oas.getComponents().getSchemas().getOrDefault(name, s)
                : s;
    }

    private String normalizeMediaKey(String mt) {
        if (mt == null) return "";
        String base = mt.toLowerCase(Locale.ROOT).trim();
        int sc = base.indexOf(';');
        if (sc >= 0) base = base.substring(0, sc).trim();
        return base;
    }

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

    @SuppressWarnings("unchecked")
    private int indexSchema(String path, Schema<?> schema, Set<String> requiredAtThisLevel,
                            OpenAPI oas,
                            Map<String, Parameter> out,
                            int budget) {

        if (schema == null || budget <= 0) return budget;
        schema = composeAndDeref(oas, schema);

        // Handle arrays
        if ("array".equals(schema.getType()) && schema instanceof ArraySchema as) {
            Schema<?> items = derefSchema(oas, as.getItems());
            String childPath = path.isEmpty() ? "[]" : path + "[]";

            // If array items are scalar - add leaf param at childPath
            if (!isObjectLike(items) && !"array".equals(items != null ? items.getType() : null)) {
                addBodyParam(out, childPath, "body", typeOf(items), items != null ? items.getDescription() : null,
                        false, items);
                return --budget;
            }

            // Items are complex - recurse without creating a node param
            return indexSchema(childPath, items, Collections.emptySet(), oas, out, budget);
        }

        // Handle objects and maps
        if (isObjectLike(schema)) {
            Map<String, Schema> props = schema.getProperties();

            // Map-like - additionalProperties
            if ((props == null || props.isEmpty()) && schema.getAdditionalProperties() != null) {
                Schema<?> vSchema = (schema.getAdditionalProperties() instanceof Schema)
                        ? derefSchema(oas, (Schema<?>) schema.getAdditionalProperties())
                        : null;

                // Only create a leaf if the value type is scalar - otherwise recurse
                String mapValuePath = path.isEmpty() ? "key" : path + ".key";
                if (vSchema != null) {
                    if (!isObjectLike(vSchema) && !"array".equals(vSchema.getType())) {
                        addBodyParam(out, mapValuePath, "body", typeOf(vSchema), vSchema.getDescription(), false, vSchema);
                        budget--;
                    } else {
                        budget = indexSchema(mapValuePath, vSchema, Collections.emptySet(), oas, out, budget);
                    }
                }
                return budget;
            }

            if (props != null) {
                Set<String> req = new HashSet<>();
                if (schema.getRequired() != null) req.addAll(schema.getRequired());

                for (Map.Entry<String, Schema> e : props.entrySet()) {
                    if (budget <= 0) break;

                    String name = e.getKey();
                    Schema<?> ps = derefSchema(oas, e.getValue());
                    if (Boolean.TRUE.equals(ps.getReadOnly())) continue;

                    String childPath = path.isEmpty() ? name : path + "." + name;

                    // If leaf - add param. If complex - recurse only. Do not add a param for the parent node.
                    boolean isArray = "array".equals(ps.getType());
                    boolean isLeaf = !isArray && !isObjectLike(ps);

                    if (isLeaf) {
                        addBodyParam(out, childPath, "body", typeOf(ps), ps.getDescription(), req.contains(name), ps);
                        budget--;
                    } else {
                        budget = indexSchema(childPath, ps, req.contains(name) ? req : Collections.emptySet(), oas, out, budget);
                    }
                }
            }
            return budget;
        }

        // Scalar at current path - add leaf (covers scalar body at root too)
        String leafPath = path.isEmpty() ? "" : path;
        addBodyParam(out, leafPath, "body", typeOf(schema), schema.getDescription(), requiredAtThisLevel.contains(path), schema);
        return --budget;
    }

    private boolean isObjectLike(Schema<?> s) {
        if (s == null) return false;
        if ("object".equals(s.getType())) return true;
        return s.getProperties() != null || s.getAdditionalProperties() != null || s.get$ref() != null;
    }

    private Schema<?> composeAndDeref(OpenAPI oas, Schema<?> s) {
        s = derefSchema(oas, s);
        // oneOf: pick first
        if (s.getOneOf() != null && !s.getOneOf().isEmpty()) {
            return composeAndDeref(oas, s.getOneOf().get(0));
        }
        // anyOf: pick first
        if (s.getAnyOf() != null && !s.getAnyOf().isEmpty()) {
            return composeAndDeref(oas, s.getAnyOf().get(0));
        }
        // allOf: merge properties
        if (s.getAllOf() != null && !s.getAllOf().isEmpty()) {
            Schema<?> merged = new Schema<>();
            merged.setType("object");
            Map<String, Schema> props = new LinkedHashMap<>();
            Set<String> required = new LinkedHashSet<>();
            for (Schema<?> partRaw : s.getAllOf()) {
                Schema<?> part = derefSchema(oas, partRaw);
                if (part.getProperties() != null) props.putAll(part.getProperties());
                if (part.getRequired() != null) required.addAll(part.getRequired());
            }
            if (!props.isEmpty()) merged.setProperties(props);
            if (!required.isEmpty()) merged.setRequired(new ArrayList<>(required));
            return merged;
        }
        return s;
    }

    private String typeOf(Schema<?> s) {
        if (s == null) return "string";
        String t = s.getType();
        if (t != null) return t;
        String f = s.getFormat();
        if (f != null) {
            if ("date".equals(f) || "date-time".equals(f) || "uuid".equals(f) || "byte".equals(f) || "binary".equals(f)) {
                return "string";
            }
        }
        return "string";
    }

    private void addBodyParam(Map<String, Parameter> out,
                              String name,
                              String in,
                              String type,
                              String description,
                              boolean required,
                              Schema<?> schema) {

        String jsonPath = wildcardArrays(name == null ? "" : name.trim());

        String leaf = leafNameFromPath(jsonPath);
        if (leaf.isEmpty() && (jsonPath == null || jsonPath.isEmpty())) {
            // root scalar body
            leaf = "(root)";
        }

        Parameter p = new Parameter(leaf, in, type != null ? type : "string");
        p.setJsonPath(jsonPath);               // IMPORTANT: use full path for uniqueness
        p.setDescription(description);
        p.setRequired(required);

        if (schema != null) {
            if (schema.getDefault() != null) p.setDefaultValue(String.valueOf(schema.getDefault()));
            if (schema.getExample() != null) p.setExampleValue(String.valueOf(schema.getExample()));
            if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                java.util.List<String> enums = new java.util.ArrayList<>();
                for (Object ev : schema.getEnum()) enums.add(String.valueOf(ev));
                p.setEnumValues(enums);
            }
        }
        String initial = p.getDefaultValue();
        if ((initial == null || initial.isEmpty()) && p.getExampleValue() != null) initial = p.getExampleValue();
        if ((initial == null || initial.isEmpty()) && p.hasEnum()) initial = p.getEnumValues().get(0);
        if (initial != null && !initial.isEmpty()) {
            p.setValue(initial);
            p.setSource(Parameter.ValueSource.DEFAULT);
        }

        Parameter existing = out.get(p.getUniqueKey()); // uses jsonPath|body under the hood
        if (existing == null) {
            out.put(p.getUniqueKey(), p);
        }
        // else keep existing user value
    }

    private static String wildcardArrays(String path) {
        if (path == null || path.isEmpty()) return "";
        return path.replaceAll("\\[\\d+\\]", "[]");
    }

    private static String leafNameFromPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("\\.");
        String last = parts[parts.length - 1];
        if (last.endsWith("[]")) last = last.substring(0, last.length() - 2);
        return last;
    }
}
