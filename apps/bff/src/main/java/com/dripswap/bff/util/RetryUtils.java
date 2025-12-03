package com.dripswap.bff.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry utility for exponential backoff strategy
 */
public class RetryUtils {

    private static final Logger logger = LoggerFactory.getLogger(RetryUtils.class);
    private static final long INITIAL_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 60000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /**
     * Execute with exponential backoff retry
     *
     * @param task         Task to execute
     * @param maxRetries   Maximum number of retries
     * @param <T>          Return type
     * @return Result from task execution
     * @throws Exception If all retries failed
     */
    public static <T> T executeWithRetry(
            RetryableTask<T> task,
            int maxRetries
    ) throws Exception {
        long delayMs = INITIAL_DELAY_MS;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return task.execute();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long actualDelay = Math.min(delayMs, MAX_DELAY_MS);
                    logger.warn("Retry attempt {} failed, waiting {}ms before retry: {}",
                            attempt + 1, actualDelay, e.getMessage());
                    Thread.sleep(actualDelay);
                    delayMs = (long) (delayMs * BACKOFF_MULTIPLIER);
                } else {
                    logger.error("All {} retries exhausted", maxRetries + 1);
                }
            }
        }
        throw lastException;
    }

    /**
     * Execute with exponential backoff retry (async sleep)
     *
     * @param task         Task to execute
     * @param maxRetries   Maximum number of retries
     * @param <T>          Return type
     * @return Result from task execution
     * @throws Exception If all retries failed
     */
    public static <T> T executeWithAsyncRetry(
            RetryableTask<T> task,
            int maxRetries
    ) throws Exception {
        return executeWithRetry(task, maxRetries);
    }

    /**
     * Functional interface for retryable task
     */
    @FunctionalInterface
    public interface RetryableTask<T> {
        T execute() throws Exception;
    }
}
