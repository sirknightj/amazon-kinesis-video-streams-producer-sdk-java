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
    public void testRetryStrategyWithConfig() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(5, 8000, 1000);
        KvsRetryStrategy strategy = new KvsRetryStrategy(config);
        
        assertEquals("Should use EXPONENTIAL_BACKOFF_WAIT type", 
                KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, 
                strategy.getRetryStrategyType());
        
        assertSame("Should use specified config", config, strategy.getExponentialBackoffConfig());
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
    public void testClientInfoWithRetryStrategy() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(3, 5000, 1000);
        KvsRetryStrategy strategy = new KvsRetryStrategy(config);
        
        ClientInfo clientInfo = new ClientInfo(
                1000L, // createClientTimeout
                2000L, // createStreamTimeout
                3000L, // stopStreamTimeout
                4000L, // offlineBufferAvailabilityTimeout
                4,     // logLevel
                true,  // logMetric
                ClientInfo.AutomaticStreamingFlags.AUTOMATIC_STREAMING_INTERMITTENT_PRODUCER,
                5000L, // serviceCallCompletionTimeout
                6000L, // serviceCallConnectionTimeout
                7000L, // metricLoggingPeriod
                8000L, // reservedCallbackPeriod
                strategy // kvsRetryStrategy
        );
        
        assertNotNull("Retry strategy should not be null", clientInfo.getKvsRetryStrategy());
        assertSame("Should be the same strategy instance", strategy, clientInfo.getKvsRetryStrategy());
        assertEquals("Metric logging period should be set", 7000L, clientInfo.getMetricLoggingPeriod());
        assertEquals("Reserved callback period should be set", 8000L, clientInfo.getReservedCallbackPeriod());
    }

    @Test
    public void testDisabledStrategyIgnoresConfig() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(5, 8000, 1000);
        KvsRetryStrategy strategy = new KvsRetryStrategy(
                KvsRetryStrategy.RetryStrategyType.DISABLED, config);
        
        assertEquals("Should use DISABLED type", 
                KvsRetryStrategy.RetryStrategyType.DISABLED, 
                strategy.getRetryStrategyType());
        assertNull("Should ignore config for DISABLED strategy", 
                strategy.getExponentialBackoffConfig());
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
