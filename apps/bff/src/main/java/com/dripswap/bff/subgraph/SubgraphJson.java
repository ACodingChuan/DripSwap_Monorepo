package com.dripswap.bff.subgraph;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * JSON parsing helpers for subgraph responses.
 */
public final class SubgraphJson {

    private SubgraphJson() {
    }

    public static BigDecimal bigDecimal(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return BigDecimal.ZERO;
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) return BigDecimal.ZERO;
        try {
            if (v.isNumber()) return v.decimalValue();
            String text = v.asText(null);
            if (text == null || text.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(text);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    public static Integer intValue(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return null;
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) return null;
        try {
            if (v.isInt()) return v.intValue();
            if (v.isNumber()) return v.numberValue().intValue();
            String text = v.asText(null);
            if (text == null || text.isBlank()) return null;
            return new java.math.BigInteger(text).intValue();
        } catch (Exception e) {
            return null;
        }
    }
}
