package com.dripswap.bff.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class AddressBook {
    private final Map<String, String> entries;

    private AddressBook(Map<String, String> entries) {
        this.entries = entries;
    }

    public static AddressBook load(Path path) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0) continue;
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            map.put(key, value);
        }
        return new AddressBook(map);
    }

    public String get(String key) {
        return entries.get(key);
    }

    public String getTokenAddressBySymbol(String symbol) {
        return entries.get("tokens." + symbol + ".address");
    }

    public String getTokenSymbolByAddress(String tokenAddress) {
        if (tokenAddress == null) return null;
        String needle = tokenAddress.trim().toLowerCase();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("tokens.") || !k.endsWith(".address")) continue;
            String v = e.getValue();
            if (v == null) continue;
            if (v.trim().toLowerCase().equals(needle)) {
                // tokens.<SYM>.address
                String mid = k.substring("tokens.".length(), k.length() - ".address".length());
                return mid;
            }
        }
        return null;
    }
}
