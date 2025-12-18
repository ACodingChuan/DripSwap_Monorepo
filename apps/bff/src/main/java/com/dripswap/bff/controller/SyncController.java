package com.dripswap.bff.controller;

import com.dripswap.bff.sync.SubgraphSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 同步控制器
 * 提供手动触发 Subgraph 全量同步的 API
 */
@Slf4j
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {
    
    private final SubgraphSyncService subgraphSyncService;
    
    /**
     * 手动触发全量同步
     * 
     * POST /api/sync/full
     * 
     * @return 同步结果
     */
    @PostMapping("/full")
    public ResponseEntity<Map<String, Object>> triggerFullSync() {
        log.info("Received request to trigger full sync");
        
        try {
            // 异步执行同步任务（避免 HTTP 超时）
            new Thread(() -> {
                try {
                    subgraphSyncService.syncAll();
                } catch (Exception e) {
                    log.error("Full sync failed", e);
                }
            }).start();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Full sync started in background");
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to start full sync", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "Failed to start sync: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 获取同步状态
     * 
     * GET /api/sync/status
     * 
     * @return 同步状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Sync status endpoint - TODO: implement");
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(response);
    }
}
