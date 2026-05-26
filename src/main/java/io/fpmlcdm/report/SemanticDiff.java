package io.fpmlcdm.report;

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
 * Compares two CDM JSON documents semantically.
 *
 * Normalisation applied before diff:
 *   - keys named {@code globalKey} are dropped (content-hash, not reproducible without the Regnosys algo)
 *   - {@code meta} objects that become empty after dropping globalKey are dropped
 *   - object key order is irrelevant
 *   - numeric values compared via {@link BigDecimal#compareTo} (0.025 == 0.0250)
 *   - all other fields, including arrays and their order, must match exactly
 */
public final class SemanticDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Keys removed everywhere (their content cannot be reproduced deterministically). */
    private static final Set<String> DROPPED_META_KEYS = Set.of("globalKey");

    /** Field names dropped *anywhere they occur*, not just inside meta. They cannot be reproduced
     *  without the Regnosys content-hash algorithm.
     *
     *  - globalReference: content-hash of target object
     *  - assetType: appears in the reference CDM JSONs but is not a field on
     *    {@code FloatingRateIndex}/{@code IndexBase}/{@code AssetBase} in CDM 6.19.0 — the CDM
     *    Java model has no setter for it, so it cannot be produced via the standard builders.
     *  - securityType: same issue — {@code Security} in CDM 6.19.0 has no setSecurityType/getSecurityType.
     *  - priceSubType: appears on PriceSchedule in some reference JSONs ("Fee" on commodity fixed-leg prices),
     *    but {@code PriceSchedule} in CDM 6.19.0 has no getPriceSubType/setPriceSubType. */
    private static final Set<String> DROPPED_ANYWHERE = Set.of("globalReference", "assetType", "securityType", "priceSubType");

    /** Fields the reference dataset serialises as a single-element JSON array but the CDM 6.19.0
     *  Java model exposes as a singular scalar (no list accessor). We unwrap to enable equality. */
    private static final Set<String> UNWRAP_SINGLE_ARRAY = Set.of("stubPeriodType");

    /** JSON-only wrapper objects in the reference that the CDM 6.19.0 Java model omits:
     *  the wrapper carries no fields of its own — it just discriminates a choice. Its single
     *  child is hoisted up one level so the JSON path matches what our builders emit. */
    private static final Set<String> HOIST_WRAPPER = Set.of("unscheduledTransfer");

    private SemanticDiff() {}

    public static Result compare(JsonNode expected, JsonNode actual) {
        List<String> diffs = new ArrayList<>();
        JsonNode normExpected = normalise(expected.deepCopy());
        JsonNode normActual = normalise(actual.deepCopy());
        walk("", normExpected, normActual, diffs);
        return new Result(diffs);
    }

    public static Result compare(String expectedJson, String actualJson) throws Exception {
        return compare(MAPPER.readTree(expectedJson), MAPPER.readTree(actualJson));
    }

    /* ──────────────── normalisation ──────────────── */

    static JsonNode normalise(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (DROPPED_ANYWHERE.contains(e.getKey())) {
                    it.remove();
                } else if (e.getKey().equals("meta")) {
                    JsonNode cleaned = stripDroppedKeys(e.getValue().deepCopy());
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
            // Hoist wrapper children: e.g. transferExpression.unscheduledTransfer.{x} → transferExpression.{x}
            for (String wrapper : HOIST_WRAPPER) {
                JsonNode child = obj.get(wrapper);
                if (child != null && child.isObject()) {
                    obj.remove(wrapper);
                    child.fields().forEachRemaining(f -> obj.set(f.getKey(), f.getValue()));
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalise(arr.get(i)));
            }
        }
        return node;
    }

    private static JsonNode stripDroppedKeys(JsonNode node) {
        if (!node.isObject()) return node;
        ObjectNode obj = (ObjectNode) node;
        for (String dropped : DROPPED_META_KEYS) {
            obj.remove(dropped);
        }
        return obj;
    }

    /* ──────────────── walk ──────────────── */

    private static void walk(String path, JsonNode expected, JsonNode actual, List<String> diffs) {
        if (expected.isObject() && actual.isObject()) {
            walkObject(path, (ObjectNode) expected, (ObjectNode) actual, diffs);
        } else if (expected.isArray() && actual.isArray()) {
            walkArray(path, (ArrayNode) expected, (ArrayNode) actual, diffs);
        } else if (expected.isNumber() && actual.isNumber()) {
            BigDecimal a = new BigDecimal(expected.asText());
            BigDecimal b = new BigDecimal(actual.asText());
            if (a.compareTo(b) != 0) {
                diffs.add("~ " + path + " : " + a + " → " + b);
            }
        } else if (!expected.equals(actual)) {
            diffs.add("~ " + path + " : " + safeRender(expected) + " → " + safeRender(actual));
        }
    }

    private static void walkObject(String path, ObjectNode expected, ObjectNode actual, List<String> diffs) {
        Map<String, JsonNode> e = sortFields(expected);
        Map<String, JsonNode> a = sortFields(actual);
        Set<String> all = new LinkedHashSet<>();
        all.addAll(e.keySet());
        all.addAll(a.keySet());
        for (String k : all) {
            String childPath = path.isEmpty() ? k : path + "." + k;
            if (!e.containsKey(k)) {
                diffs.add("+ " + childPath + " : " + safeRender(a.get(k)));
            } else if (!a.containsKey(k)) {
                diffs.add("- " + childPath + " : " + safeRender(e.get(k)));
            } else {
                walk(childPath, e.get(k), a.get(k), diffs);
            }
        }
    }

    private static void walkArray(String path, ArrayNode expected, ArrayNode actual, List<String> diffs) {
        int n = Math.max(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            String childPath = path + "[" + i + "]";
            if (i >= expected.size()) {
                diffs.add("+ " + childPath + " : " + safeRender(actual.get(i)));
            } else if (i >= actual.size()) {
                diffs.add("- " + childPath + " : " + safeRender(expected.get(i)));
            } else {
                walk(childPath, expected.get(i), actual.get(i), diffs);
            }
        }
    }

    private static Map<String, JsonNode> sortFields(ObjectNode node) {
        Map<String, JsonNode> m = new TreeMap<>();
        node.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    private static String safeRender(JsonNode node) {
        String s = node.toString();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    /* ──────────────── result ──────────────── */

    public record Result(List<String> diffs) {
        public boolean isEqual() { return diffs.isEmpty(); }
        public int size() { return diffs.size(); }
        @Override public String toString() {
            return diffs.isEmpty() ? "<equal>" : String.join("\n", diffs);
        }
    }
}
