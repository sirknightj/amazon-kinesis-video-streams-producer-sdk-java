package com.amazonaws.kinesisvideo.producer;

import com.amazonaws.kinesisvideo.auth.DefaultAuthCallbacks;
import com.amazonaws.kinesisvideo.auth.KinesisVideoCredentials;
import com.amazonaws.kinesisvideo.auth.KinesisVideoCredentialsProvider;
import com.amazonaws.kinesisvideo.auth.StaticCredentialsProvider;
import com.amazonaws.kinesisvideo.client.KinesisVideoClientConfiguration;
import com.amazonaws.kinesisvideo.internal.producer.jni.NativeKinesisVideoProducerJni;
import com.amazonaws.kinesisvideo.internal.service.DefaultServiceCallbacksImpl;
import com.amazonaws.kinesisvideo.java.service.JavaKinesisVideoServiceClient;
import com.amazonaws.kinesisvideo.storage.DefaultStorageCallbacks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.amazonaws.kinesisvideo.internal.producer.jni.NativeKinesisVideoProducerJni.PRODUCER_NATIVE_LIBRARY_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;

/**
 * Direct JNI Multi-Client Test
 * <p>
 * Tests the NativeKinesisVideoProducerJni class directly to validate
 * that multiple instances can coexist with independent JVM contexts.
 */
public class MultiJniTest {
    private static final Logger log = LogManager.getLogger(MultiJniTest.class);

    private final List<NativeKinesisVideoProducerJni> jniInstances = new ArrayList<>();

    @Rule
    public Timeout globalTimeout = Timeout.seconds(15);

    @Before
    public void setUp() {
        try {
            System.loadLibrary(PRODUCER_NATIVE_LIBRARY_NAME);
        } catch (final UnsatisfiedLinkError e) {
            fail("JNI library not found.");
        }
    }

    @After
    public void tearDown() {
        boolean success = true;

        for (int i = this.jniInstances.size() - 1; i >= 0; i--) {
            try {
                final NativeKinesisVideoProducerJni jni = this.jniInstances.get(i);
                if (jni != null) {
                    jni.free();
                }
            } catch (final Exception e) {
                log.error("Error freeing JNI instance {}: {}", i, e.getMessage());
                success = false;
            }
        }

        this.jniInstances.clear();

        assertTrue("There was an error freeing JNI instances, check the logs above", success);
    }

    /**
     * Core test: Multiple JNI instances can be created independently
     */
    @Test
    public void testMultipleJniInstances() throws Exception {
        final int numInstances = 3;
        final ScheduledExecutorService authExecutorService = Executors.newScheduledThreadPool(numInstances);
        final ScheduledExecutorService serviceCallbacksExecutorService = Executors.newScheduledThreadPool(numInstances);

        // Create multiple JNI instances
        for (int i = 0; i < numInstances; i++) {
            final KinesisVideoCredentialsProvider kvsCredentialsProvider =
                    new StaticCredentialsProvider(new KinesisVideoCredentials("ak" + i, "sk" + i));

            final KinesisVideoClientConfiguration configuration = KinesisVideoClientConfiguration.builder()
                    .withCredentialsProvider(kvsCredentialsProvider)
                    .build();

            final NativeKinesisVideoProducerJni jni = new NativeKinesisVideoProducerJni(
                    new DefaultAuthCallbacks(kvsCredentialsProvider, authExecutorService,
                            LogManager.getLogger(NativeKinesisVideoProducerJni.class)),
                    new DefaultStorageCallbacks(),
                    new DefaultServiceCallbacksImpl(LogManager.getLogger(DefaultServiceCallbacksImpl.class), serviceCallbacksExecutorService, configuration,
                            new JavaKinesisVideoServiceClient())
            );

            assertNotNull("JNI instance " + i + " should be created", jni);
            this.jniInstances.add(jni);
        }

        assertEquals("Should create all JNI instances", numInstances, this.jniInstances.size());

        // Initialize each instance with unique device info
        for (int i = 0; i < numInstances; i++) {
            final String deviceName = "jni-test-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
            final DeviceInfo deviceInfo = createTestDeviceInfo(deviceName);

            // This calls into JNI and creates the native wrapper
            this.jniInstances.get(i).createSync(deviceInfo);

            assertTrue("JNI instance " + i + " should be ready", this.jniInstances.get(i).isReady());
        }
    }

    /**
     * Test concurrent JNI instance creation
     */
    @Test
    public void testConcurrentJniCreation() throws Exception {

        final String className = this.getClass().getName();
        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();

        final int numInstances = 500;
        final ScheduledExecutorService authExecutorService = Executors.newScheduledThreadPool(numInstances);
        final ScheduledExecutorService serviceCallbacksExecutorService = Executors.newScheduledThreadPool(numInstances);
        final ExecutorService executor = Executors.newFixedThreadPool(numInstances);
        final List<Future<NativeKinesisVideoProducerJni>> futures = new ArrayList<>();
        final CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < numInstances; i++) {
            final int index = i;
            final Future<NativeKinesisVideoProducerJni> future = executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for the signal to start

                    final String accessKey = String.join("-", className, methodName, "ak", Integer.toString(index));
                    final String secretKey = String.join("-", className, methodName, "sk", Integer.toString(index));
                    final KinesisVideoCredentialsProvider kvsCredentialsProvider =
                            new StaticCredentialsProvider(new KinesisVideoCredentials(accessKey, secretKey));

                    final KinesisVideoClientConfiguration configuration = KinesisVideoClientConfiguration.builder()
                            .withCredentialsProvider(kvsCredentialsProvider)
                            .build();

                    final NativeKinesisVideoProducerJni jni = new NativeKinesisVideoProducerJni(
                            new DefaultAuthCallbacks(kvsCredentialsProvider, authExecutorService,
                                    LogManager.getLogger(NativeKinesisVideoProducerJni.class)),
                            new DefaultStorageCallbacks(),
                            new DefaultServiceCallbacksImpl(LogManager.getLogger(DefaultServiceCallbacksImpl.class), serviceCallbacksExecutorService, configuration,
                                    new JavaKinesisVideoServiceClient())
                    );

                    final String deviceName = String.join("-", className, methodName, "device", Integer.toString(index));
                    final DeviceInfo deviceInfo = createTestDeviceInfo(deviceName);

                    jni.createSync(deviceInfo);
                    return jni;

                } catch (final Exception e) {
                    log.error("Failed to create concurrent JNI instance {}", index, e);
                    fail("Failed to create concurrent JNI instance " + index);
                    return null;
                }
            });
            futures.add(future);
        }

        // Kickoff all of them at the same time
        startLatch.countDown();

        // Collect results
        for (int i = 0; i < numInstances; i++) {
            final NativeKinesisVideoProducerJni jni = futures.get(i).get(10, TimeUnit.SECONDS);
            assertNotNull("Concurrent JNI instance " + i + " should be created", jni);
            assertTrue("Concurrent JNI instance " + i + " should be ready", jni.isReady());
            this.jniInstances.add(jni);
        }

        executor.shutdown();
        assertTrue("Didn't shutdown in time", executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("Should create all concurrent instances", numInstances, this.jniInstances.size());
        log.info("Successfully created {} concurrent JNI instances", numInstances);
    }


    /**
     * Test that destroying one JNI instance doesn't affect others
     */
    @Test
    public void testJniInstanceIndependence() throws Exception {
        final ScheduledExecutorService authExecutorService = Executors.newScheduledThreadPool(1);
        final ScheduledExecutorService serviceCallbacksExecutorService = Executors.newScheduledThreadPool(1);

        // Create 3 instances
        testMultipleJniInstances();

        // Verify all are ready
        for (int i = 0; i < 3; i++) {
            assertTrue("Instance " + i + " should be ready", this.jniInstances.get(i).isReady());
        }

        // Destroy middle instance
        log.info("Destroying JNI instance 1");
        this.jniInstances.get(1).free();
        this.jniInstances.set(1, null);

        // Verify others still work
        assertTrue("Instance 0 should still be ready", this.jniInstances.get(0).isReady());
        assertTrue("Instance 2 should still be ready", this.jniInstances.get(2).isReady());

        // Create a new instance to verify JNI layer still works
        final KinesisVideoCredentialsProvider kvsCredentialsProvider =
                new StaticCredentialsProvider(new KinesisVideoCredentials("ak4", "sk4"));

        final KinesisVideoClientConfiguration configuration = KinesisVideoClientConfiguration.builder()
                .withCredentialsProvider(kvsCredentialsProvider)
                .build();

        final NativeKinesisVideoProducerJni jni = new NativeKinesisVideoProducerJni(
                new DefaultAuthCallbacks(kvsCredentialsProvider, authExecutorService,
                        LogManager.getLogger(NativeKinesisVideoProducerJni.class)),
                new DefaultStorageCallbacks(),
                new DefaultServiceCallbacksImpl(LogManager.getLogger(DefaultServiceCallbacksImpl.class), serviceCallbacksExecutorService, configuration,
                        new JavaKinesisVideoServiceClient()));

        final DeviceInfo newDeviceInfo = createTestDeviceInfo("device-created-after-one-was-previously-freed");
        jni.createSync(newDeviceInfo);
        assertTrue("New JNI instance should be ready", jni.isReady());

        jni.free();
    }

    @Nonnull
    @SuppressWarnings({"ConstantConditions"})
    private DeviceInfo createTestDeviceInfo(@Nonnull final String deviceName) {
        assumeNotNull(deviceName);

        final int storageInfoVersion = 0;
        final StorageInfo.DeviceStorageType storageType = StorageInfo.DeviceStorageType.DEVICE_STORAGE_TYPE_IN_MEM;
        final long storageSizeBytes = 1024 * 1024 * 10; // 10 MB
        final int spillRatio = 90;
        final String rootDirectory = "/tmp";
        final StorageInfo storageInfo = new StorageInfo(storageInfoVersion,
                storageType,
                storageSizeBytes,
                spillRatio,
                rootDirectory);

        final int deviceInfoVersion = 0;
        final Tag[] tags = null;
        final int numStreams = 1;
        return new DeviceInfo(deviceInfoVersion,
                deviceName,
                storageInfo,
                numStreams,
                tags);
    }
}
