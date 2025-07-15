package com.amazonaws.kinesisvideo.producer;

/**
 * Configuration class for retry strategy settings.
 * This maps to the native KvsRetryStrategy struct in PIC.
 */
public class KvsRetryStrategy {
    
    public enum RetryStrategyType {
        DISABLED(0x00),
        EXPONENTIAL_BACKOFF_WAIT(0x01);
        
        private final int value;
        
        RetryStrategyType(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static RetryStrategyType fromValue(int value) {
            for (RetryStrategyType type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown retry strategy type: " + value);
        }
    }
    
    private final RetryStrategyType retryStrategyType;
    private final ExponentialBackoffRetryStrategyConfig exponentialBackoffConfig;
    
    /**
     * Constructor with default exponential backoff
     */
    public KvsRetryStrategy() {
        this(RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, null);
    }
    
    /**
     * Constructor with specified type
     */
    public KvsRetryStrategy(RetryStrategyType retryStrategyType) {
        this(retryStrategyType, null);
    }
    
    /**
     * Constructor with exponential backoff configuration
     */
    public KvsRetryStrategy(ExponentialBackoffRetryStrategyConfig exponentialBackoffConfig) {
        this(RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, exponentialBackoffConfig);
    }
    
    /**
     * Constructor with type and config
     */
    public KvsRetryStrategy(RetryStrategyType retryStrategyType, ExponentialBackoffRetryStrategyConfig exponentialBackoffConfig) {
        this.retryStrategyType = retryStrategyType != null ? retryStrategyType : RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT;
        
        // Only use config for exponential backoff strategy
        if (this.retryStrategyType == RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT) {
            this.exponentialBackoffConfig = exponentialBackoffConfig; // null is OK, means use PIC defaults
        } else {
            this.exponentialBackoffConfig = null; // Disabled strategy doesn't use config
        }
    }
    
    public RetryStrategyType getRetryStrategyType() {
        return retryStrategyType;
    }
    
    public int getRetryStrategyTypeValue() {
        return retryStrategyType.getValue();
    }
    
    public ExponentialBackoffRetryStrategyConfig getExponentialBackoffConfig() {
        return exponentialBackoffConfig;
    }
    
    // This method is called by JNI to get the native strategy pointer
    public long getRetryStrategy() {
        return 0; // PIC will populate this when creating the strategy
    }
}
