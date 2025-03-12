package com.amazonaws.kinesisvideo.http;

import com.amazonaws.kinesisvideo.client.IPVersionFilter;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class KvsFilteredDnsResolver implements DnsResolver, com.amazonaws.DnsResolver {

    private static final Logger log = LogManager.getLogger(MethodHandles.lookup().lookupClass());

    @Nonnull
    private final IPVersionFilter ipVersionFilter;

    public KvsFilteredDnsResolver(@Nullable final IPVersionFilter ipVersionFilter) {
        super();

        if (ipVersionFilter != null) {
            this.ipVersionFilter = ipVersionFilter;
        } else {
            // No filter
            this.ipVersionFilter = IPVersionFilter.IPV4_AND_IPV6;
            log.debug("Defaulting to filter: {}", this.ipVersionFilter);
        }
    }

    @Override
    public InetAddress[] resolve(final String host) throws UnknownHostException {
        log.debug("Resolving {}", host);
        final InetAddress[] resolvedAddresses = Arrays.stream(SystemDefaultDnsResolver.INSTANCE.resolve(host))
                .peek(inetAddr -> log.debug("Resolved IP: {}", inetAddr))
                .filter(this.ipVersionFilter::matches)
                .toArray(InetAddress[]::new);

        if (resolvedAddresses.length == 0) {
            throw new UnknownHostException(host + " doesn't support " + ipVersionFilter + "!");
        }

        log.debug("{} filtered IP's: {}", ipVersionFilter, Arrays.toString(resolvedAddresses));

        // Will always return an array with at least 1 element
        return resolvedAddresses;
    }
}