package com.dripswap.bff.controller;

import java.util.List;

import com.dripswap.bff.controller.dto.CcipBridgeRecordCreateRequest;
import com.dripswap.bff.controller.dto.CcipBridgeRecordResponse;
import com.dripswap.bff.repository.CcipBridgeRecord;
import com.dripswap.bff.service.CcipBridgeRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bridge/ccip")
public class CcipBridgeRecordController {
    private final CcipBridgeRecordService service;

    public CcipBridgeRecordController(CcipBridgeRecordService service) {
        this.service = service;
    }

    @PostMapping("/records")
    public CcipBridgeRecordResponse create(@RequestBody CcipBridgeRecordCreateRequest req) {
        CcipBridgeRecord row = service.createOrGet(req);
        return toResp(row);
    }

    @GetMapping("/records/by-message")
    public CcipBridgeRecordResponse byMessage(@RequestParam("messageId") String messageId) {
        CcipBridgeRecord row = service.findByMessageId(messageId);
        if (row == null) return null;
        return toResp(row);
    }

    @GetMapping("/records/by-user")
    public List<CcipBridgeRecordResponse> byUser(
            @RequestParam("userAddress") String userAddress,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        return service.findByUserAddress(userAddress, limit).stream().map(CcipBridgeRecordController::toResp).toList();
    }

    @GetMapping("/records/search")
    public List<CcipBridgeRecordResponse> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        return service.search(q, limit).stream().map(CcipBridgeRecordController::toResp).toList();
    }

    private static CcipBridgeRecordResponse toResp(CcipBridgeRecord row) {
        return new CcipBridgeRecordResponse(
                row.getId(),
                row.getMessageId(),
                row.getUserAddress(),
                row.getTokenSymbol(),
                row.getTokenAddress(),
                row.getFromChainId(),
                row.getToChainId(),
                row.getSourceTxHash(),
                row.getCreatedAt()
        );
    }
}
