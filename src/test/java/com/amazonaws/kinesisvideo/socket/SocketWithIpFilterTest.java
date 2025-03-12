package com.amazonaws.kinesisvideo.socket;

import com.amazonaws.kinesisvideo.client.IPVersionFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.*;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SocketWithIpFilterTest {

    private static final int httpPort = 8080;
    private static final int httpsPort = 8443;

    private final int port;
    private final IPVersionFilter ipVersionFilter;
    private final Class<?> expectedClass;

    public SocketWithIpFilterTest(final int port, final IPVersionFilter ipVersionFilter, final Class<?> expectedClass) {
        this.port = port;
        this.ipVersionFilter = ipVersionFilter;
        this.expectedClass = expectedClass;
    }

    @Parameterized.Parameters(name = "{index}: port={0}, filter={1} => expectedClass={2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {httpPort, IPVersionFilter.IPV4, Inet4Address.class},
                {httpPort, IPVersionFilter.IPV6, Inet6Address.class},
                {httpPort, IPVersionFilter.IPV4_AND_IPV6, InetAddress.class},
                {httpsPort, IPVersionFilter.IPV4, Inet4Address.class},
                {httpsPort, IPVersionFilter.IPV6, Inet6Address.class},
                {httpsPort, IPVersionFilter.IPV4_AND_IPV6, InetAddress.class}
        });
    }

    @Test
    public void testCreateSocket_WithIpFilter() throws Exception {
        try (ServerSocket server = new ServerSocket(port)) {
            URI uri = new URI("https://localhost:" + port);
            Socket socket = new SocketFactory().createSocket(uri, ipVersionFilter);

            assertNotNull(socket);
            assertTrue(socket.isConnected());
            assertEquals("localhost", socket.getInetAddress().getHostName());
            assertTrue(expectedClass.isInstance(socket.getInetAddress()));
            assertEquals(port, socket.getPort());
        }
    }
}