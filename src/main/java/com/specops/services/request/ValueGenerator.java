package com.specops.services.request;

import burp.api.montoya.core.ByteArray;
import com.specops.domain.Parameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Json;

import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class ValueGenerator {

    public enum GroupKey { NONE, NAME, NAME_IN, NAME_TYPE }

    private static volatile GroupKey GROUP_KEY = GroupKey.NAME_IN;

    private static final Map<String, String> groupCache = new HashMap<>();

    public static void setGroupKey(GroupKey key) {
        GROUP_KEY = key == null ? GroupKey.NAME_IN : key;
    }

    public static int generateValues(Map<String, Parameter> parameterStore, String typeFilter) {
        int count = 0;
        groupCache.clear(); // important per-run reset
        for (Parameter param : parameterStore.values()) {
            if (!param.isLocked() && empty(param.getValue())) {
                boolean typeMatch = typeFilter == null
                        || eq(param.getType(), typeFilter)
                        || ("integer".equalsIgnoreCase(typeFilter) && "number".equalsIgnoreCase(s(param.getType())));
                if (!typeMatch) continue;

                String gk = buildGroupKey(param, GROUP_KEY);

                String value = groupCache.computeIfAbsent(gk, k -> generateValueDeterministic(param, k));

                param.setValue(value);
                param.setSource(Parameter.ValueSource.GENERATED);
                count++;
            }
        }
        return count;
    }

    private static String buildGroupKey(Parameter p, GroupKey mode) {
        if (mode == GroupKey.NONE) return pUniqueKey(p); // current behavior
        String name = s(p.getName());
        switch (mode) {
            case NAME:      return name;
            case NAME_TYPE: return name + "|" + s(p.getType());
            case NAME_IN:
            default:        return name + "|" + s(p.getIn());
        }
    }

    private static String generateValueDeterministic(Parameter p, String groupKey) {
        long seed = stableSeed(groupKey);
        Random rnd = new Random(seed);
        return generateValueWithSeed(p, rnd, seed, groupKey);
    }

    private static long stableSeed(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long s = 0L;
            for (int i = 0; i < 8; i++) s = (s << 8) | (h[i] & 0xFFL);
            return s;
        } catch (Exception e) {
            return key.hashCode();
        }
    }

    private static String generateValueWithSeed(Parameter p, Random rnd, long seed, String unique) {
        final String type = s(p.getType());
        final String name = s(p.getName());
        String n = name.toLowerCase(Locale.ROOT);

        if (n.contains("email")) return "user" + posMod(seed, 10000) + "@example.test";
        if (n.contains("phone") || n.contains("mobile")) return "+91" + (7000000000L + posMod(seed, 2999999999L));
        if (n.contains("uuid")) return UUID.nameUUIDFromBytes(unique.getBytes(StandardCharsets.UTF_8)).toString();
        if (endsWithIdish(n)) return String.valueOf(1 + posMod(seed, 9999));
        if (n.contains("name")) return pick(rnd, "biryani", "pasta", "momo", "chicken");
        if (n.contains("amount") || n.contains("price") || n.contains("balance"))
            return String.format(Locale.ROOT, "%.2f", (posMod(seed, 100000) / 100.0));
        if (n.contains("token") || n.contains("key") || n.contains("secret")) return hex(seed, 32);

        // Date and time: keep your current realism
        if (n.contains("date")) return LocalDate.now().toString();
        if (n.contains("time")) return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        if (n.contains("ip") && !n.contains("zip"))
            return String.format("%d.%d.%d.%d", 1 + rnd.nextInt(223), rnd.nextInt(255), rnd.nextInt(255), 1 + rnd.nextInt(254));
        if (n.contains("ipv6")) return Inet6Address.getLoopbackAddress().getHostAddress();
        if (n.contains("url") || n.contains("uri")) return "https://example.test/api/" + posMod(seed, 1000);
        if (n.contains("blob") || n.contains("base64"))
            return Base64.getEncoder().encodeToString(("blob-" + unique).getBytes(StandardCharsets.UTF_8));
        if (n.contains("flag") || n.contains("bool")) return String.valueOf((seed & 1) == 0);

        switch (type) {
            case "integer":
            case "number":
                return String.valueOf(1 + posMod(seed, 9999));
            case "boolean":
                return String.valueOf((seed & 1) == 0);
            case "string":
            default:
                return randAlphaNum(rnd, 12);
        }
    }

    public static ByteArray exportValues(Map<String, Parameter> parameterStore) {
        ObjectNode root = Json.mapper().createObjectNode();
        ObjectNode paramsNode = Json.mapper().createObjectNode();
        root.set("parameters", paramsNode);

        for (Parameter param : parameterStore.values()) {
            String val = s(param.getValue());
            boolean include = !val.isEmpty() || param.isLocked();
            if (!include) continue;

            String key = safeUniqueKey(param);
            ObjectNode item = Json.mapper().createObjectNode();
            item.put("value", val);
            item.put("isLocked", param.isLocked());
            item.put("name", s(param.getName()));
            item.put("in", s(param.getIn()));
            paramsNode.set(key, item);
        }

        try {
            byte[] bytes = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            return ByteArray.byteArray(bytes);
        } catch (Exception e) {
            return ByteArray.byteArray("{\"parameters\":{}}");
        }
    }

    public static int importValues(Map<String, Parameter> parameterStore, ByteArray content) {
        int updated = 0;
        try {
            JsonNode root = Json.mapper().readTree(content.getBytes());
            JsonNode params = root.path("parameters");
            if (!params.isObject()) return 0;

            Iterator<String> fieldNames = params.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                JsonNode item = params.path(key);
                if (!item.isObject()) continue;

                Parameter param = parameterStore.get(key);
                if (param == null) {
                    continue;
                }

                String newVal = optText(item, "value", "");
                boolean isLocked = item.path("isLocked").asBoolean(false);

                param.setValue(newVal);
                param.setLocked(isLocked);
                try {
                    param.setSource(Parameter.ValueSource.IMPORTED);
                } catch (Throwable ignore) {  }

                updated++;
            }
        } catch (Exception e) {
        }
        return updated;
    }

    private static String optText(JsonNode n, String field, String dflt) {
        JsonNode v = n.get(field);
        return v != null && !v.isNull() ? v.asText() : dflt;
    }

    private static boolean empty(String v) { return v == null || v.isEmpty(); }

    private static String pUniqueKey(Parameter p) {
        try {
            return String.valueOf(p.getUniqueKey());
        } catch (Exception e) {
            return (s(p.getName()) + "|" + s(p.getIn())).trim();
        }
    }

    private static String safeUniqueKey(Parameter p) {
        try {
            return String.valueOf(p.getUniqueKey());
        } catch (Exception e) {
            return (s(p.getName()) + "|" + s(p.getIn())).trim();
        }
    }

    private static boolean endsWithIdish(String n) {
        return n.endsWith("id") || n.endsWith("_id") || n.contains("id_") || n.contains("identifier");
    }

    private static String randAlphaNum(Random rnd, int len) {
        final char[] alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
        char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = alphabet[rnd.nextInt(alphabet.length)];
        return new String(out);
    }

    private static String hex(long seed, int len) {
        byte[] b = new byte[len / 2];
        new Random(seed).nextBytes(b);
        StringBuilder sb = new StringBuilder(len);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static long posMod(long v, long mod) {
        long m = v % mod;
        return m < 0 ? m + mod : m;
    }

    private static String pick(Random rnd, String a, String b, String c, String d) {
        String[] arr = {a, b, c, d};
        return arr[rnd.nextInt(arr.length)];
    }

    private static String s(String v) { return v == null ? "" : v; }

    private static boolean eq(String a, String b) {
        return Objects.equals(s(a).toLowerCase(Locale.ROOT), s(b).toLowerCase(Locale.ROOT));
    }
}
