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
    public void testNullJitterTypeHandling() {
        ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
                3, 5000, 1000, 90000, null, 300);
        
        assertNull("Null jitter type should remain null (use PIC default)", config.getJitterType());
        assertEquals("getJitterTypeValue should return 0 for null (PIC default)", 
                0, config.getJitterTypeValue());
    }
}
