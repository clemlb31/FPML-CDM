package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CDM JSON semantic comparator. Drops globalKey/globalReference, treats numbers
 * as BigDecimal, order-insensitive on object keys. Ported from main branch.
 */
public final class SemanticDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DROPPED_META_KEYS = Set.of("globalKey");
    private static final Set<String> DROPPED_ANYWHERE = Set.of(
        "globalReference", "assetType", "securityType", "priceSubType");
    private static final Set<String> UNWRAP_SINGLE_ARRAY = Set.of("stubPeriodType");
    private static final Map<String, String> FIELD_ALIASES = Map.of(
        "notionalReference", "notionaReference",
        "barrier", "knock");
    private static final Set<String> HOIST_WRAPPER = Set.of("unscheduledTransfer");

    private SemanticDiff() {}

    public static Result compare(JsonNode expected, JsonNode actual) {
        List<String> diffs = new ArrayList<>();
        JsonNode normExpected = normalise(expected.deepCopy());
        JsonNode normActual   = normalise(actual.deepCopy());
        walk("", normExpected, normActual, diffs);
        return new Result(diffs);
    }

    public static Result compare(String expectedJson, String actualJson) throws Exception {
        return compare(MAPPER.readTree(expectedJson), MAPPER.readTree(actualJson));
    }

    static JsonNode normalise(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (DROPPED_ANYWHERE.contains(e.getKey())) {
                    it.remove();
                } else if (e.getKey().equals("meta")) {
                    JsonNode cleaned = stripDropped(e.getValue().deepCopy());
                    if (cleaned == null || (cleaned.isObject() && cleaned.size() == 0)) {
                        it.remove();
                    } else {
                        e.setValue(normalise(cleaned));
                    }
                } else if (UNWRAP_SINGLE_ARRAY.contains(e.getKey())
                        && e.getValue().isArray() && e.getValue().size() == 1) {
                    e.setValue(normalise(e.getValue().get(0)));
                } else {
                    e.setValue(normalise(e.getValue()));
                }
            }
            for (Map.Entry<String, String> alias : FIELD_ALIASES.entrySet()) {
                JsonNode val = obj.get(alias.getKey());
                if (val != null) {
                    obj.remove(alias.getKey());
                    obj.set(alias.getValue(), val);
                }
            }
            for (String wrapper : HOIST_WRAPPER) {
                JsonNode child = obj.get(wrapper);
                if (child != null && child.isObject()) {
                    obj.remove(wrapper);
                    child.fields().forEachRemaining(f -> obj.set(f.getKey(), f.getValue()));
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) arr.set(i, normalise(arr.get(i)));
        }
        return node;
    }

    private static JsonNode stripDropped(JsonNode node) {
        if (!node.isObject()) return node;
        ObjectNode obj = (ObjectNode) node;
        for (String k : DROPPED_META_KEYS) obj.remove(k);
        return obj;
    }

    private static void walk(String path, JsonNode expected, JsonNode actual, List<String> diffs) {
        if (expected.isObject() && actual.isObject()) {
            walkObj(path, (ObjectNode) expected, (ObjectNode) actual, diffs);
        } else if (expected.isArray() && actual.isArray()) {
            walkArr(path, (ArrayNode) expected, (ArrayNode) actual, diffs);
        } else if (expected.isNumber() && actual.isNumber()) {
            BigDecimal a = new BigDecimal(expected.asText());
            BigDecimal b = new BigDecimal(actual.asText());
            if (a.compareTo(b) != 0) diffs.add("~ " + path + " : " + a + " -> " + b);
        } else if (!expected.equals(actual)) {
            diffs.add("~ " + path + " : " + brief(expected) + " -> " + brief(actual));
        }
    }

    private static void walkObj(String path, ObjectNode ex, ObjectNode ac, List<String> diffs) {
        Map<String, JsonNode> e = sortFields(ex);
        Map<String, JsonNode> a = sortFields(ac);
        Set<String> all = new LinkedHashSet<>();
        all.addAll(e.keySet());
        all.addAll(a.keySet());
        for (String k : all) {
            String p = path.isEmpty() ? k : path + "." + k;
            if (!e.containsKey(k)) diffs.add("+ " + p + " : " + brief(a.get(k)));
            else if (!a.containsKey(k)) diffs.add("- " + p + " : " + brief(e.get(k)));
            else walk(p, e.get(k), a.get(k), diffs);
        }
    }

    private static void walkArr(String path, ArrayNode ex, ArrayNode ac, List<String> diffs) {
        int n = Math.max(ex.size(), ac.size());
        for (int i = 0; i < n; i++) {
            String p = path + "[" + i + "]";
            if (i >= ex.size()) diffs.add("+ " + p + " : " + brief(ac.get(i)));
            else if (i >= ac.size()) diffs.add("- " + p + " : " + brief(ex.get(i)));
            else walk(p, ex.get(i), ac.get(i), diffs);
        }
    }

    private static Map<String, JsonNode> sortFields(ObjectNode node) {
        Map<String, JsonNode> m = new TreeMap<>();
        node.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    private static String brief(JsonNode node) {
        String s = node.toString();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    public record Result(List<String> diffs) {
        public boolean isEqual() { return diffs.isEmpty(); }
        public int size() { return diffs.size(); }
        @Override public String toString() {
            return diffs.isEmpty() ? "<equal>" : String.join("\n", diffs);
        }
    }
}