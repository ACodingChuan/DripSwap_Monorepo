package com.dripswap.bff.config;

import com.dripswap.bff.sync.SubgraphSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * 同步配置
 * 可选：应用启动后自动触发全量同步
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SyncConfig {
    
    private final SubgraphSyncService subgraphSyncService;
    
    /**
     * 应用启动后自动触发全量同步
     * 
     * 如果不需要自动同步，注释掉这个方法即可
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application is ready, checking if auto-sync is enabled...");
        
        // 可以通过配置控制是否自动同步
        boolean autoSync = false; // 默认关闭，需要手动触发
        
        if (autoSync) {
            log.info("Auto-sync is enabled, starting full sync...");
            new Thread(() -> {
                try {
                    Thread.sleep(5000); // 等待 5 秒，确保应用完全启动
                    subgraphSyncService.syncAll();
                } catch (Exception e) {
                    log.error("Auto-sync failed", e);
                }
            }).start();
        } else {
            log.info("Auto-sync is disabled. Use POST /api/sync/full to trigger manually.");
        }
    }
}
