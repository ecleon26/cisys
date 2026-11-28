/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTP/1.1 over TLS to the pinned address, with the certificate checked against the URL's host.
 * Redirects are not followed and only the status line is read. The whole exchange, handshake
 * included, has a hard deadline, so a server that answers a byte at a time cannot hold a sender.
 */
@ApplicationScoped
public class SocketWebhookTransport implements WebhookTransport {
  static final int TIMEOUT_MILLIS = 10_000;
  static final int DEADLINE_MILLIS = 20_000;
  private static final int MAX_STATUS_LINE = 1024;

  @Override
  public int post(WebhookUrlValidator.Target target, Map<String, String> headers, byte[] body)
      throws IOException {
    try (Socket plain = new Socket()) {
      return withDeadline(
          plain,
          DEADLINE_MILLIS,
          () -> {
            plain.connect(new InetSocketAddress(target.address(), target.port()), TIMEOUT_MILLIS);
            plain.setSoTimeout(TIMEOUT_MILLIS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls =
                (SSLSocket) factory.createSocket(plain, target.host(), target.port(), true)) {
              SSLParameters params = tls.getSSLParameters();
              params.setEndpointIdentificationAlgorithm("HTTPS");
              if (NetworkAllowlist.literal(target.host()) == null) {
                params.setServerNames(List.of(new SNIHostName(target.host())));
              }
              tls.setSSLParameters(params);
              tls.startHandshake();
              return exchange(tls, target, headers, body);
            }
          });
    }
  }

  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webhook-deadline");
        t.setDaemon(true);
        return t;
      });

  /** Work that talks over a socket. */
  interface SocketWork<T> {
    T run() throws IOException;
  }

  /** Runs {@code work}, closing {@code socket} when it takes longer than {@code millis}. */
  static <T> T withDeadline(Socket socket, long millis, SocketWork<T> work) throws IOException {
    AtomicBoolean expired = new AtomicBoolean();
    ScheduledFuture<?> timer =
        WATCHDOG.schedule(
            () -> {
              expired.set(true);
              try {
                socket.close();
              } catch (IOException ignored) {
                // Closing is all that matters.
              }
            },
            millis,
            TimeUnit.MILLISECONDS);
    try {
      return work.run();
    } catch (IOException e) {
      if (expired.get()) {
        throw new IOException("Timed out after " + millis + " ms");
      }
      throw e;
    } finally {
      timer.cancel(false);
    }
  }

  /** Writes the request and reads the status code of the response. */
  static int exchange(
      Socket socket, WebhookUrlValidator.Target target, Map<String, String> headers, byte[] body)
      throws IOException {
    String path = target.uri().getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    if (target.uri().getRawQuery() != null) {
      path += "?" + target.uri().getRawQuery();
    }
    String host = target.host().contains(":") ? "[" + target.host() + "]" : target.host();
    if (target.port() != 443) {
      host += ":" + target.port();
    }
    StringBuilder head = new StringBuilder();
    head.append("POST ").append(path).append(" HTTP/1.1\r\n");
    head.append("Host: ").append(host).append("\r\n");
    head.append("Content-Type: application/json\r\n");
    head.append("Content-Length: ").append(body.length).append("\r\n");
    head.append("Connection: close\r\n");
    for (Map.Entry<String, String> h : headers.entrySet()) {
      head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
    }
    head.append("\r\n");
    OutputStream out = socket.getOutputStream();
    out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
    return status(new BufferedInputStream(socket.getInputStream()));
  }

  private static int status(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (line.length() >= MAX_STATUS_LINE) {
        throw new IOException("Invalid response");
      }
      line.append((char) c);
    }
    String[] parts = line.toString().trim().split(" ", 3);
    if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
      throw new IOException("Invalid response");
    }
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid response");
    }
  }
}
