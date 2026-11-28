/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records webhook requests instead of sending them, so no test reaches the network. */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestWebhookTransport implements WebhookTransport {
  record Call(WebhookUrlValidator.Target target, Map<String, String> headers, String body) {}

  static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  static volatile int status = 200;
  static volatile IOException failure;

  static void reset() {
    CALLS.clear();
    status = 200;
    failure = null;
  }

  /** The requests sent for one delivery. */
  static List<Call> forDelivery(Long id) {
    return CALLS.stream()
        .filter(c -> String.valueOf(id).equals(c.headers().get(WebhookService.DELIVERY_HEADER)))
        .toList();
  }

  @Override
  public int post(WebhookUrlValidator.Target target, Map<String, String> headers, byte[] body)
      throws IOException {
    CALLS.add(new Call(target, Map.copyOf(headers), new String(body, StandardCharsets.UTF_8)));
    if (failure != null) {
      throw failure;
    }
    return status;
  }
}
