/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The HTTP exchange over a plain local socket; TLS is the JDK's. */
class SocketWebhookTransportTest {

  /** Answers one connection with {@code response} and returns what the client sent. */
  private static CompletableFuture<String> serve(ServerSocket server, String response) {
    return CompletableFuture.supplyAsync(() -> {
      try (Socket s = server.accept()) {
        s.setSoTimeout(5000);
        InputStream in = s.getInputStream();
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
          seen.write(buf, 0, n);
          String text = seen.toString(StandardCharsets.UTF_8);
          if (text.contains("\r\n\r\n") && text.endsWith("}")) {
            break;
          }
        }
        s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        return seen.toString(StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private static WebhookUrlValidator.Target target(String url) throws IOException {
    URI uri = URI.create(url);
    return new WebhookUrlValidator.Target(
        uri,
        uri.getHost(),
        uri.getPort() == -1 ? 443 : uri.getPort(),
        InetAddress.getLoopbackAddress());
  }

  @Test
  void sendsTheRequestAndReadsTheStatus() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<String> request =
          serve(server, "HTTP/1.1 204 No Content\r\nLocation: https://elsewhere/\r\n\r\n");
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
        int status =
            SocketWebhookTransport.exchange(
                client,
                target("https://hooks.example.com/services/x?token=a%20b"),
                Map.of("X-Cisys-Event", "build.failed"),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(204, status);
      }
      String sent = request.get(5, TimeUnit.SECONDS);
      assertTrue(sent.startsWith("POST /services/x?token=a%20b HTTP/1.1\r\n"), sent);
      assertTrue(sent.contains("\r\nHost: hooks.example.com\r\n"), sent);
      assertTrue(sent.contains("\r\nContent-Length: 7\r\n"), sent);
      assertTrue(sent.contains("\r\nX-Cisys-Event: build.failed\r\n"), sent);
      assertTrue(sent.endsWith("\r\n\r\n{\"a\":1}"), sent);
    }
  }

  @Test
  void namesANonDefaultPortInTheHostHeader() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<String> request = serve(server, "HTTP/1.1 200 OK\r\n\r\n");
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
        SocketWebhookTransport.exchange(
            client, target("https://hooks.example.com:8443"), Map.of(), "{}".getBytes());
      }
      String sent = request.get(5, TimeUnit.SECONDS);
      assertTrue(sent.startsWith("POST / HTTP/1.1\r\n"), sent);
      assertTrue(sent.contains("\r\nHost: hooks.example.com:8443\r\n"), sent);
    }
  }

  @Test
  void aSilentServerHitsTheDeadline() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
          Socket accepted = server.accept()) {
        long start = System.nanoTime();
        IOException e =
            assertThrows(
                IOException.class,
                () ->
                    SocketWebhookTransport.withDeadline(
                        client,
                        300,
                        () ->
                            SocketWebhookTransport.exchange(
                                client,
                                target("https://hooks.example.com/x"),
                                Map.of(),
                                "{}".getBytes())));
        assertEquals("Timed out after 300 ms", e.getMessage());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
      }
    }
  }

  @Test
  void rejectsAResponseThatIsNotHttp() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      serve(server, "SSH-2.0-OpenSSH\r\n");
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
        assertThrows(
            IOException.class,
            () ->
                SocketWebhookTransport.exchange(
                    client, target("https://hooks.example.com/x"), Map.of(), "{}".getBytes()));
      }
    }
  }
}
