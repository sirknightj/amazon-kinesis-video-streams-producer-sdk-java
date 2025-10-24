package com.amazonaws.kinesisvideo.demoapp.debug;

import com.amazonaws.kinesisvideo.producer.StreamDescription;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Class which converts {@link com.amazonaws.services.kinesisvideo.model.StreamInfo} (describeStream result via KVS api)
 * to {@link com.amazonaws.kinesisvideo.producer.StreamDescription} (describeStream result via Acuity KVS producer).
 * Stores StreamDescription in map which can be used to pass this result
 * from {@link com.amazon.telephony.voice.streaming.resources.ResourceManager} into {@link com.amazonaws.kinesisvideo.internal.service.LilyKVSServiceCallbacksImpl}
 */
public class StreamDescriptionInjector {
    private final ConcurrentHashMap<String, StreamDescription> streamDescriptionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> endpointMap = new ConcurrentHashMap<>();

    private static final String SLASH_SEPARATOR = "/";

    public StreamDescriptionInjector() {
    }

    public void registerStream(final String streamName, final StreamDescription streamDescription) {
        this.streamDescriptionMap.put(streamName, streamDescription);
//                streamInfo.getStreamName(),
//                new StreamDescription(
//                        VERSION_ZERO,
//                        streamInfo.getDeviceName(),
//                        streamInfo.getStreamName(),
//                        streamInfo.getMediaType(),
//                        streamInfo.getVersion(),
//                        streamInfo.getStreamARN(),
//                        StreamStatus.valueOf(streamInfo.getStatus()),
//                        streamInfo.getCreationTime().toEpochSecond() * 1000,
//                        streamInfo.getDataRetentionInHours(),
//                        streamInfo.getKmsKeyId()
//                )
//        );
    }

    public void registerStreamEndpoint(final String streamName, final String endpoint) {
        this.endpointMap.put(streamName, endpoint);
    }

    public void deregisterStream(final String streamName) {
        this.streamDescriptionMap.remove(streamName);
    }

    public void deregisterStreamEndpoint(final String streamName) {
        this.endpointMap.remove(streamName);
    }

    public StreamDescription getStreamDescription(final String streamName) {
        return this.streamDescriptionMap.get(streamName);
    }

    public String getStreamEndpoint(final String streamName) {
        return this.endpointMap.get(streamName);
    }

    public String getStreamNameFromARN(final String streamARN) {
        if (streamARN == null || streamARN.trim().isEmpty() || streamARN.split(SLASH_SEPARATOR, -1).length - 1 < 2) {
            return "";
        }
        return streamARN.substring(streamARN.indexOf(SLASH_SEPARATOR) + 1, streamARN.lastIndexOf(SLASH_SEPARATOR));
    }
}