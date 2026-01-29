package com.dripswap.bff.service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.dripswap.bff.config.FaucetV2Properties;
import com.dripswap.bff.util.AddressBook;
import org.springframework.stereotype.Service;

@Service
public class FaucetV2ChainRegistry {
    private final Map<Long, FaucetV2Properties.Chain> byChainId = new HashMap<>();

    public FaucetV2ChainRegistry(FaucetV2Properties props) {
        for (FaucetV2Properties.Chain c : props.getChains()) {
            if (!c.isEnabled()) continue;
            byChainId.put(c.getChainId(), c);
        }
    }

    public Optional<FaucetV2Properties.Chain> get(long chainId) {
        return Optional.ofNullable(byChainId.get(chainId));
    }

    public Map<Long, FaucetV2Properties.Chain> all() {
        return Map.copyOf(byChainId);
    }

    public AddressBook loadAddressBook(FaucetV2Properties.Chain chain) {
        try {
            String p = chain.getAddressBookPath();
            if (p == null || p.isBlank()) throw new IllegalStateException("addressBookPath missing");
            return AddressBook.load(Path.of(p));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load address book for chain " + chain.getChainId(), e);
        }
    }
}
