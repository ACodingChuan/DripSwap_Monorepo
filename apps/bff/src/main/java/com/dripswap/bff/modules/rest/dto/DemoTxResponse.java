package com.dripswap.bff.modules.rest.dto;

/**
 * Response DTO for demo transaction submission.
 */
public class DemoTxResponse {

    private boolean success;
    private String txHash;
    private String message;

    // Constructors
    public DemoTxResponse() {
    }

    public DemoTxResponse(boolean success, String txHash, String message) {
        this.success = success;
        this.txHash = txHash;
        this.message = message;
    }

    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "DemoTxResponse{" +
                "success=" + success +
                ", txHash='" + txHash + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
