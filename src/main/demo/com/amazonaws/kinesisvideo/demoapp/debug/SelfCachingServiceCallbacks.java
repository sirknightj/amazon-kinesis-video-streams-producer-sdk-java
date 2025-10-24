package com.amazonaws.kinesisvideo.demoapp.debug;

import com.amazonaws.kinesisvideo.auth.KinesisVideoCredentialsProvider;
import com.amazonaws.kinesisvideo.client.KinesisVideoClientConfiguration;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.common.preconditions.Preconditions;
import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoProducerStream;
import com.amazonaws.kinesisvideo.internal.producer.client.KinesisVideoServiceClient;
import com.amazonaws.kinesisvideo.internal.service.DefaultServiceCallbacksImpl;
import com.amazonaws.kinesisvideo.producer.ProducerException;
import com.amazonaws.kinesisvideo.producer.StreamDescription;
import com.amazonaws.kinesisvideo.producer.Time;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.HTTP_OK;

public class SelfCachingServiceCallbacks extends DefaultServiceCallbacksImpl {

    private final StreamDescriptionInjector streamDescriptionInjector;

    public SelfCachingServiceCallbacks(@Nonnull final Logger log, @Nonnull final ScheduledExecutorService executor,
                                       @Nonnull final KinesisVideoClientConfiguration configuration,
                                       @Nonnull final KinesisVideoServiceClient kinesisVideoServiceClient,
                                       @Nonnull final StreamDescriptionInjector streamDescriptionInjector) {
        super(log, executor, configuration, kinesisVideoServiceClient);
        this.streamDescriptionInjector = streamDescriptionInjector;
    }

    public StreamDescriptionInjector streamDescriptionInjector() {
        return this.streamDescriptionInjector;
    }

    @Override
    public void describeStream(
            @Nonnull final String streamName,
            final long callAfter,
            final long timeout,
            @Nullable final byte[] authData,
            final int authType,
            final long streamHandle,
            final KinesisVideoProducerStream stream) throws ProducerException {
        Preconditions.checkState(isInitialized(), "Service callbacks object should be initialized first");
        this.log.info("Calling cached describeStream for stream: " + streamName);
        final StreamDescription streamDescription = this.streamDescriptionInjector.getStreamDescription(streamName);

        if (streamDescription == null) {
            this.log.info("No describeStream entry for stream: " + streamName + " in cache, using API call");
            final long delay = calculateRelativeServiceCallAfter(callAfter);

            final Runnable task = new Runnable() {
                @Override
                public void run() {
                    int statusCode;
                    StreamDescription streamDescription = null;

                    final KinesisVideoCredentialsProvider credentialsProvider = getCredentialsProvider(authData, SelfCachingServiceCallbacks.this.log);
                    final long timeoutInMillis = timeout / Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;

                    try {
                        streamDescription = SelfCachingServiceCallbacks.this.kinesisVideoServiceClient.describeStream(streamName,
                                timeoutInMillis,
                                credentialsProvider);
                        statusCode = HTTP_OK;
                        SelfCachingServiceCallbacks.this.streamDescriptionInjector.registerStream(streamName, streamDescription);
                    } catch (final KinesisVideoException e) {
                        statusCode = getStatusCodeFromException(e);
                        SelfCachingServiceCallbacks.this.log.error("Kinesis Video service client returned an error. Reporting to Kinesis Video PIC.", e);
                    }

                    try {
                        SelfCachingServiceCallbacks.this.kinesisVideoProducer.describeStreamResult(stream, streamHandle, streamDescription, statusCode);
                    } catch (final ProducerException e) {
                        throw new RuntimeException(e);
                    }
                }
            };

            this.executor.schedule(task, delay, TimeUnit.NANOSECONDS);
        } else {
            try {
                this.kinesisVideoProducer.describeStreamResult(stream, streamHandle, streamDescription, HTTP_OK);
            } catch (final ProducerException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void getStreamingEndpoint(
            @Nonnull final String streamName,
            @Nonnull final String apiName,
            final long callAfter,
            final long timeout,
            @Nullable final byte[] authData,
            final int authType,
            final long streamHandle,
            final KinesisVideoProducerStream stream) throws ProducerException {

        Preconditions.checkState(isInitialized(), "Service callbacks object should be initialized first");
        this.log.info("Calling cached getStreamingEndpoint for stream: " + streamName);
        final String endpoint = this.streamDescriptionInjector.getStreamEndpoint(streamName);

        if (endpoint == null) {
            this.log.info("No getStreamingEndpoint entry for stream: " + streamName + " in cache, using API call");
            final long delay = calculateRelativeServiceCallAfter(callAfter);

            final Runnable task = new Runnable() {
                @Override
                public void run() {
                    final KinesisVideoCredentialsProvider credentialsProvider = getCredentialsProvider(authData, SelfCachingServiceCallbacks.this.log);
                    final long timeoutInMillis = timeout / Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
                    int statusCode = HTTP_OK;
                    String endpoint = "";
                    try {
                        endpoint = SelfCachingServiceCallbacks.this.kinesisVideoServiceClient.getDataEndpoint(streamName,
                                apiName,
                                timeoutInMillis,
                                credentialsProvider);
                        SelfCachingServiceCallbacks.this.streamDescriptionInjector.registerStreamEndpoint(streamName, endpoint);
                    } catch (final KinesisVideoException e) {
                        SelfCachingServiceCallbacks.this.log.error("Kinesis Video service client returned an error " + e.getMessage() + ". Reporting to Kinesis Video PIC.");
                        statusCode = getStatusCodeFromException(e);
                    }
                    try {
                        SelfCachingServiceCallbacks.this.kinesisVideoProducer.getStreamingEndpointResult(stream, streamHandle, endpoint, statusCode);
                    } catch (final ProducerException e) {
                        throw new RuntimeException(e);
                    }
                }
            };

            this.executor.schedule(task, delay, TimeUnit.NANOSECONDS);
        } else {
            final int statusCode = HTTP_OK;
            try {
                this.kinesisVideoProducer.getStreamingEndpointResult(stream, streamHandle, endpoint, statusCode);
            } catch (final ProducerException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private long calculateRelativeServiceCallAfter(final long absoluteCallAfter) {
        return Math.max(0, absoluteCallAfter * Time.NANOS_IN_A_TIME_UNIT -
                System.currentTimeMillis() * Time.NANOS_IN_A_MILLISECOND);
    }
}