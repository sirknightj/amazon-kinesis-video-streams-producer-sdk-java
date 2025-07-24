package com.amazonaws.kinesisvideo.common;

import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoProducerStream;
import com.amazonaws.kinesisvideo.producer.ClientInfo;
import com.amazonaws.kinesisvideo.producer.DeviceInfo;
import com.amazonaws.kinesisvideo.producer.ExponentialBackoffRetryStrategyConfig;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.KvsRetryStrategy;
import com.amazonaws.kinesisvideo.producer.ProducerException;
import com.amazonaws.kinesisvideo.producer.StorageInfo;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.Tag;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.nio.ByteBuffer;

import static com.amazonaws.kinesisvideo.producer.ProducerException.STATUS_SUCCESS;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * These tests check the struct migration/versioning pattern used in PIC.
 * <ul>
 *     <li>{@link DeviceInfo} is the main parameter object for configuring the KVS Producer client.</li>
 *     <li>The v0 of the struct has a few members</li>
 *     <li>The v1 of the struct has a couple new members, in particular, the {@link ClientInfo} parameter,
 *     which also has multiple struct versions on its own</li>
 * </ul>
 *
 * @see <a href="https://github.com/awslabs/amazon-kinesis-video-streams-pic">Amazon Kinesis Video Streams PIC</a>
 */
public class RetryStrategyTest extends ProducerTestBase {

    private static final int STORAGE_INFO_VERSION_ZERO = 0;
    private static final int ONE_SECOND_HUNDREDS_OF_NANOS = 1000 * 10000;
    private static final int TEN_SECONDS_HUNDREDS_OF_NANOS = 10 * ONE_SECOND_HUNDREDS_OF_NANOS;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(15);

    private StorageInfo storageInfo;

    @Before
    public void setUp() {
        final boolean jniLoaded = isJNILoaded();
        if (!jniLoaded) {
            fail("JNI library not found.");
        }

        final long storageSizeBytes = 10 * 1024 * 1024; // 10 MiB
        final int spillRatioPercent = 90;
        final String rootDirectory = "/tmp";

        this.storageInfo = new StorageInfo(STORAGE_INFO_VERSION_ZERO,
                StorageInfo.DeviceStorageType.DEVICE_STORAGE_TYPE_IN_MEM, storageSizeBytes,
                spillRatioPercent, rootDirectory);
    }


    @SuppressWarnings({"UnnecessaryLocalVariable", "ConstantConditions"})
    private ClientInfo createClientInfoV2WithRetryStrategy(final KvsRetryStrategy retryStrategy) {
        // ClientInfo V0 fields
        final long createClientTimeout = ONE_SECOND_HUNDREDS_OF_NANOS;
        final long createStreamTimeout = TEN_SECONDS_HUNDREDS_OF_NANOS;
        final long stopStreamTimeout = ONE_SECOND_HUNDREDS_OF_NANOS;
        final long offlineBufferAvailabilityTimeout = ONE_SECOND_HUNDREDS_OF_NANOS;
        final int logLevel = ClientInfo.LOG_LEVEL_DEBUG;

        // ClientInfo V1 fields
        final boolean doLogMetrics = false;

        // ClientInfo V2 fields
        final long metricsLoggingPeriod = ONE_SECOND_HUNDREDS_OF_NANOS;
        final ClientInfo.AutomaticStreamingFlags automaticStreamingFlags = null;
        final long reservedCallbackPeriod = ONE_SECOND_HUNDREDS_OF_NANOS;
        final KvsRetryStrategy kvsRetryStrategy = retryStrategy;

        return ClientInfo.createClientInfoV2(createClientTimeout,
                createStreamTimeout, stopStreamTimeout, offlineBufferAvailabilityTimeout,
                logLevel, doLogMetrics, metricsLoggingPeriod, automaticStreamingFlags,
                reservedCallbackPeriod, kvsRetryStrategy);
    }

    @SuppressWarnings("ConstantConditions")
    private DeviceInfo createDeviceInfoV1(final ClientInfo clientInfo) {
        final String deviceName = "java-test-application";
        final int streamCount = 10;
        final Tag[] tags = null;
        final String clientId = String.format("ProducerJava-%s-%s", this.getClass().getSimpleName(),
                new Object() {
                }.getClass().getEnclosingMethod().getName());

        return DeviceInfo.createDeviceInfoV1(deviceName, this.storageInfo, streamCount, tags,
                clientId, clientInfo);
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void test_null_retryStrategy_is_accepted() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = null;

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void test_default_retryStrategy_is_accepted() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy();

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void test_disabled_retryStrategy_is_accepted() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(KvsRetryStrategy.RetryStrategyType.DISABLED);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void test_exponentialBackOff_retryStrategy_is_accepted() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void test_retryStrategy_using_PIC_defaults_is_accepted() throws ProducerException {

        final ExponentialBackoffRetryStrategyConfig exponentialBackoffStrategyConfig =
                new ExponentialBackoffRetryStrategyConfig();

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(exponentialBackoffStrategyConfig);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings({"ConstantConditions", "ExtractMethodRecommender"})
    public void test_configured_retryStrategy_is_accepted() throws ProducerException {

        final long maxRetryCount = 100;
        final long maxRetryWaitTimeMs = 20000;
        final long retryFactorTimeMs = 1000;
        final long minTimeToResetRetryStateMs = 50;
        final ExponentialBackoffRetryStrategyConfig.JitterType jitterType = ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER;
        final long jitterFactor = 100;

        final ExponentialBackoffRetryStrategyConfig exponentialBackoffStrategyConfig =
                new ExponentialBackoffRetryStrategyConfig(maxRetryCount, maxRetryWaitTimeMs, retryFactorTimeMs,
                        minTimeToResetRetryStateMs, jitterType, jitterFactor);

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, exponentialBackoffStrategyConfig);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings({"ConstantConditions", "ExtractMethodRecommender"})
    public void test_retryStrategyValuesActuallyGetUsed() throws ProducerException {

        final long maxRetryCount = 1;
        final long maxRetryWaitTimeMs = 10000;
        final long retryFactorTimeMs = 50;
        final long minTimeToResetRetryStateMs = 90000;
        final ExponentialBackoffRetryStrategyConfig.JitterType jitterType = ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER;
        final long jitterFactor = 50;

        final ExponentialBackoffRetryStrategyConfig exponentialBackoffStrategyConfig =
                new ExponentialBackoffRetryStrategyConfig(maxRetryCount, maxRetryWaitTimeMs, retryFactorTimeMs,
                        minTimeToResetRetryStateMs, jitterType, jitterFactor);

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(exponentialBackoffStrategyConfig);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String invalidStreamName = ",/?";
//        final String invalidStreamName = "adsf";
        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamExpectCreateFailure(methodName + "-" + invalidStreamName);
//        streamNormally(methodName + "-" + invalidStreamName);

        free();

    }

    private void streamNormally(final String methodName) {
        testStreaming(methodName, false);

        // frameDropped_ is set to false initially. It can be set to true by droppedFrameReport callback in case there
        // was a frame that was dropped during the test
        assertFalse(this.frameDropped_);
        // errorStatus_ is set to STATUS_SUCCESS initially. It can be set to a different statusCode by
        // streamErrorReport callback in case an error is encountered during the test
        assertEquals(STATUS_SUCCESS, this.errorStatus_);
        // bufferingAckInSequence_ is true initially. It can be set to false by fragmentAckReceived callback in case the
        // (current timestamp - previous timestamp of the ack) > fragment duration
        assertTrue(this.bufferingAckInSequence_);
    }

    private void streamExpectCreateFailure(final String methodName) {
        testStreaming(methodName, true);

        // frameDropped_ is set to false initially. It can be set to true by droppedFrameReport callback in case there
        // was a frame that was dropped during the test
        assertFalse(this.frameDropped_);
        // errorStatus_ is set to STATUS_SUCCESS initially. It can be set to a different statusCode by
        // streamErrorReport callback in case an error is encountered during the test
        assertEquals(0x4000002B, this.errorStatus_);
        // bufferingAckInSequence_ is true initially. It can be set to false by fragmentAckReceived callback in case the
        // (current timestamp - previous timestamp of the ack) > fragment duration
        assertTrue(this.bufferingAckInSequence_);
    }

    @SuppressWarnings({"UnnecessaryLocalVariable"})
    private void testStreaming(final String methodName, final boolean skipPreparation) {
        final String streamName = "DeviceInfoClientInfoVersionTest-" + methodName + "-" + System.currentTimeMillis();
        final StreamInfo.StreamingType streamingType = StreamInfo.StreamingType.STREAMING_TYPE_REALTIME;
        final long maxLatency = TEN_SECONDS_HUNDREDS_OF_NANOS;
        final long bufferDuration = TEN_SECONDS_HUNDREDS_OF_NANOS;

        final KinesisVideoProducerStream stream = createTestStream(streamName, streamingType, maxLatency,
                bufferDuration, StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE, skipPreparation);

        final long now = System.currentTimeMillis();
        final int fps = 10;
        final int durationSec = 3;
        final int keyFrameIntervalSec = 1;

        // Stream durationSec seconds ago until now
        for (int index = 0; index < durationSec * fps; index++) {
            final int flags = index % (keyFrameIntervalSec * fps) == 0 ? FRAME_FLAG_KEY_FRAME : FRAME_FLAG_NONE;
            final long frameDurationMs = 1000 / fps;
            final long timestampMs = now - (durationSec * 1000) + index * frameDurationMs;
            final long dtsPtsHundredsOfNanos = timestampMs * HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
            final byte[] mockData = new byte[]{1, 2, 3, 4};
            final KinesisVideoFrame testFrame = new KinesisVideoFrame(index, flags,
                    dtsPtsHundredsOfNanos, dtsPtsHundredsOfNanos,
                    1000 / fps * HUNDREDS_OF_NANOS_IN_A_MILLISECOND, ByteBuffer.wrap(mockData));
            try {
                stream.putFrame(testFrame);

                Thread.sleep(frameDurationMs);
            } catch (final Exception e) {
                e.printStackTrace();
                fail("Failed to put the frames into the stream! " + e.getMessage());
            }
        }
        try {
            Thread.sleep(WAIT_5_SECONDS_FOR_ACKS);
        } catch (final InterruptedException e) {
            e.printStackTrace();
            fail();
        }

        freeTestStream(stream);

        deleteStream(streamName);
    }

}
