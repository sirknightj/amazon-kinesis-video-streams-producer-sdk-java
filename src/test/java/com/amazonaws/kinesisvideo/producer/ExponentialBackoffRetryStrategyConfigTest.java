package com.amazonaws.kinesisvideo.producer;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExponentialBackoffRetryStrategyConfigTest {

    @Test
    public void testDefaultConfiguration() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig();
        
        assertEquals("Default max retry count should be 0 (use PIC default)", 
                0L, config.getMaxRetryCount());
        assertEquals("Default max retry wait time should be 0 (use PIC default)", 
                0L, config.getMaxRetryWaitTimeMs());
        assertEquals("Default retry factor time should be 0 (use PIC default)", 
                0L, config.getRetryFactorTimeMs());
        assertEquals("Default min time to reset retry state should be 0 (use PIC default)", 
                0L, config.getMinTimeToResetRetryStateMs());
        assertNull("Default jitter type should be null (use PIC default)", config.getJitterType());
        assertEquals("Default jitter factor should be 0 (use PIC default)", 
                0L, config.getJitterFactor());
    }

    @Test
    public void testCustomConfiguration() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                5,      // maxRetryCount
                8000,   // maxRetryWaitTimeMs
                500,    // retryFactorTimeMs
                60000,  // minTimeToResetRetryStateMs
                ExponentialBackoffRetryStrategyConfig.JitterType.FIXED_JITTER,
                100     // jitterFactor
        );
        
        assertEquals("Max retry count should be 5", 5L, config.getMaxRetryCount());
        assertEquals("Max retry wait time should be 8000ms", 8000L, config.getMaxRetryWaitTimeMs());
        assertEquals("Retry factor time should be 500ms", 500L, config.getRetryFactorTimeMs());
        assertEquals("Min time to reset retry state should be 60000ms", 60000L, config.getMinTimeToResetRetryStateMs());
        assertEquals("Jitter type should be FIXED_JITTER", 
                ExponentialBackoffRetryStrategyConfig.JitterType.FIXED_JITTER, config.getJitterType());
        assertEquals("Jitter factor should be 100", 100L, config.getJitterFactor());
    }

    @Test
    public void testJitterTypeEnum() {
        assertEquals("FULL_JITTER should have value 0x01", 
                0x01, ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER.getValue());
        assertEquals("FIXED_JITTER should have value 0x02", 
                0x02, ExponentialBackoffRetryStrategyConfig.JitterType.FIXED_JITTER.getValue());
        assertEquals("NO_JITTER should have value 0x03", 
                0x03, ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER.getValue());
        
        // Test fromValue method
        assertEquals("Should convert 0x01 to FULL_JITTER", 
                ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER,
                ExponentialBackoffRetryStrategyConfig.JitterType.fromValue(0x01));
        assertEquals("Should convert 0x02 to FIXED_JITTER", 
                ExponentialBackoffRetryStrategyConfig.JitterType.FIXED_JITTER,
                ExponentialBackoffRetryStrategyConfig.JitterType.fromValue(0x02));
        assertEquals("Should convert 0x03 to NO_JITTER", 
                ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER,
                ExponentialBackoffRetryStrategyConfig.JitterType.fromValue(0x03));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testJitterTypeFromInvalidValue() {
        ExponentialBackoffRetryStrategyConfig.JitterType.fromValue(0x99);
    }

    @Test
    public void testTimeConversionMethods() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                3, 5000, 1000, 90000, 
                ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER, 300);
        
        // Test conversion from milliseconds to hundreds of nanoseconds
        // 1ms = 10,000 hundreds of nanoseconds
        assertEquals("5000ms should convert to 50,000,000 hundreds of nanos", 
                50000000L, config.getMaxRetryWaitTimeHundredsOfNanos());
        assertEquals("1000ms should convert to 10,000,000 hundreds of nanos", 
                10000000L, config.getRetryFactorTimeHundredsOfNanos());
        assertEquals("90000ms should convert to 900,000,000 hundreds of nanos", 
                900000000L, config.getMinTimeToResetRetryStateHundredsOfNanos());
    }

    @Test
    public void testNullJitterTypeHandling() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                3, 5000, 1000, 90000, null, 300);
        
        assertNull("Null jitter type should remain null (use PIC default)", config.getJitterType());
        assertEquals("getJitterTypeValue should return 0 for null (PIC default)", 
                0, config.getJitterTypeValue());
    }

    @Test
    public void testKvsRetryStrategyWithConfig() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                5, 8000, 500);
        
        KvsRetryStrategy retryStrategy = new KvsRetryStrategy(config);
        
        assertEquals("Should use EXPONENTIAL_BACKOFF_WAIT type", 
                KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, 
                retryStrategy.getRetryStrategyType());
        assertSame("Should store the same config instance", 
                config, retryStrategy.getExponentialBackoffConfig());
    }

    @Test
    public void testKvsRetryStrategyWithDisabledIgnoresConfig() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                5, 8000, 500);
        
        KvsRetryStrategy retryStrategy = new KvsRetryStrategy(
                KvsRetryStrategy.RetryStrategyType.DISABLED, config);
        
        assertEquals("Should use DISABLED type", 
                KvsRetryStrategy.RetryStrategyType.DISABLED, 
                retryStrategy.getRetryStrategyType());
        assertNull("Should ignore config for DISABLED strategy", 
                retryStrategy.getExponentialBackoffConfig());
    }

    @Test
    public void testToString() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                3, 5000, 1000);
        
        String str = config.toString();
        assertTrue("toString should contain maxRetryCount", str.contains("maxRetryCount=3"));
        assertTrue("toString should contain maxRetryWaitTimeMs", str.contains("maxRetryWaitTimeMs=5000"));
        assertTrue("toString should contain retryFactorTimeMs", str.contains("retryFactorTimeMs=1000"));
        assertTrue("toString should contain jitterType", str.contains("jitterType=PIC_DEFAULT"));
    }
}
