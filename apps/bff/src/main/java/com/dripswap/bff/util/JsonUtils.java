package com.dripswap.bff.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON utility functions
 */
@Slf4j
public class JsonUtils {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Convert object to JSON string
     */
    public static String toJson(Object obj) throws Exception {
        return mapper.writeValueAsString(obj);
    }

    /**
     * Parse JSON string to object
     */
    public static <T> T fromJson(String json, Class<T> clazz) throws Exception {
        return mapper.readValue(json, clazz);
    }

    /**
     * Pretty print JSON
     */
    public static String prettyJson(Object obj) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }
}
