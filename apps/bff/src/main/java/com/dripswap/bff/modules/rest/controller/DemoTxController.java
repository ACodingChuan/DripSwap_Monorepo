package com.dripswap.bff.modules.rest.controller;

import com.dripswap.bff.modules.rest.dto.DemoTxRequest;
import com.dripswap.bff.modules.rest.dto.DemoTxResponse;
import com.dripswap.bff.service.DemoTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for demo transaction operations.
 * Provides write endpoints for transaction submission.
 */
@RestController
@RequestMapping("/tx")
public class DemoTxController {

    private static final Logger logger = LoggerFactory.getLogger(DemoTxController.class);

    private final DemoTxService demoTxService;

    public DemoTxController(DemoTxService demoTxService) {
        this.demoTxService = demoTxService;
    }

    /**
     * Submit a demo transaction.
     *
     * @param request Demo transaction request containing txHash, chainId, payload
     * @return Response with success status
     */
    @PostMapping("/demo")
    public ResponseEntity<DemoTxResponse> submitDemoTx(@RequestBody DemoTxRequest request) {
        try {
            logger.info("Received demo transaction request: txHash={}, chainId={}", 
                    request.getTxHash(), request.getChainId());

            DemoTxResponse response = demoTxService.submitDemoTx(request);

            if (response.isSuccess()) {
                logger.info("Demo transaction submitted successfully: txHash={}", request.getTxHash());
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            } else {
                logger.warn("Demo transaction submission failed: txHash={}, message={}", 
                        request.getTxHash(), response.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
        } catch (Exception e) {
            logger.error("Error submitting demo transaction: {}", e.getMessage(), e);
            DemoTxResponse errorResponse = new DemoTxResponse(false, 
                    request.getTxHash(), 
                    "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}
