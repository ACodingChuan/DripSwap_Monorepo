package com.dripswap.bff.sync;

import com.dripswap.bff.entity.User;
import com.dripswap.bff.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncHandler {

    private final UserRepository userRepository;

    @Transactional
    public void handleUsers(String chainId, JsonNode usersNode) {
        if (usersNode == null || !usersNode.isArray()) {
            return;
        }

        List<User> users = new ArrayList<>();
        for (JsonNode node : usersNode) {
            try {
                User user = new User();
                user.setId(node.get("id").asText().toLowerCase());
                user.setChainId(chainId);
                users.add(user);
            } catch (Exception e) {
                log.error("Failed to parse user: {}", node, e);
            }
        }

        if (!users.isEmpty()) {
            userRepository.saveAll(users);
            log.info("Saved {} users for chain: {}", users.size(), chainId);
        }
    }
}

