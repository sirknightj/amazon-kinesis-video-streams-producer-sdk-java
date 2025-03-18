package com.amazonaws.kinesisvideo.socket;

import org.junit.Test;

import java.io.IOException;
import java.net.*;
import static org.junit.Assert.*;

public class SocketFactoryTest {

    private static final int httpPort = 8080;
    private static final int httpsPort = 8443;
    private final SocketFactory socketFactory = new SocketFactory();

    @Test
    public void testCreateSocket_Http() throws Exception {
        try (final ServerSocket server = new ServerSocket(httpPort)) {
            URI uri = new URI("http://localhost:" + httpPort);
            Socket socket = socketFactory.createSocket(uri);

            assertNotNull(socket);
            assertTrue(socket.isConnected());
            assertEquals("localhost", socket.getInetAddress().getHostName());
            assertEquals(httpPort, socket.getPort());
        }
    }

    @Test
    public void testCreateSocket_Https() throws Exception {
        try (ServerSocket server = new ServerSocket(httpsPort)) {
            URI uri = new URI("https://localhost:" + httpsPort);
            Socket socket = socketFactory.createSocket(uri);

            assertNotNull(socket);
            assertTrue(socket.isConnected());
            assertEquals("localhost", socket.getInetAddress().getHostName());
            assertEquals(httpsPort, socket.getPort());
        }
    }

    @Test(expected = Exception.class)
    public void testCreateSocket_InvalidURI() throws URISyntaxException, IOException {
        try (Socket socket = socketFactory.createSocket(new URI("invalid://localhost"))) {
            fail("It should have thrown an exception");
        }
    }
}
