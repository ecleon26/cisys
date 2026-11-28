/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.io.IOException;
import java.util.Map;

/** Sends one webhook request to an already checked address. */
public interface WebhookTransport {
  /** Returns the HTTP status of the response. */
  int post(WebhookUrlValidator.Target target, Map<String, String> headers, byte[] body)
      throws IOException;
}
