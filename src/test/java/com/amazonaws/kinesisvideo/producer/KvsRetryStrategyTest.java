package com.amazonaws.kinesisvideo.producer;

import org.junit.Test;
import static org.junit.Assert.*;

public class KvsRetryStrategyTest {

    @Test
    public void testDefaultRetryStrategy() {
        KvsRetryStrategy strategy = new KvsRetryStrategy();
        
        assertEquals("Default should be exponential backoff", 
                KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, 
                strategy.getRetryStrategyType());
        
        assertNull("Default should have no custom config", strategy.getExponentialBackoffConfig());
    }

    @Test
    public void testRetryStrategyWithType() {
        KvsRetryStrategy strategy = new KvsRetryStrategy(
                KvsRetryStrategy.RetryStrategyType.DISABLED);
        
        assertEquals("Should use specified type", 
                KvsRetryStrategy.RetryStrategyType.DISABLED, 
                strategy.getRetryStrategyType());
        
        assertNull("Disabled strategy should have no config", strategy.getExponentialBackoffConfig());
    }

    @Test
    public void testRetryStrategyTypeEnum() {
        assertEquals("DISABLED should have value 0x00", 
                0x00, KvsRetryStrategy.RetryStrategyType.DISABLED.getValue());
        assertEquals("EXPONENTIAL_BACKOFF_WAIT should have value 0x01", 
                0x01, KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT.getValue());
        
        // Test fromValue method
        assertEquals("Should convert value 0x00 to DISABLED", 
                KvsRetryStrategy.RetryStrategyType.DISABLED,
                KvsRetryStrategy.RetryStrategyType.fromValue(0x00));
        assertEquals("Should convert value 0x01 to EXPONENTIAL_BACKOFF_WAIT", 
                KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT,
                KvsRetryStrategy.RetryStrategyType.fromValue(0x01));
    }

    @Test
    public void testNullRetryStrategyType() {
        // Test that null type defaults to exponential backoff
        KvsRetryStrategy strategy = new KvsRetryStrategy(null, (ExponentialBackoffRetryStrategyConfig) null);
        
        assertEquals("Null type should default to exponential backoff", 
                KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, 
                strategy.getRetryStrategyType());
    }
}
