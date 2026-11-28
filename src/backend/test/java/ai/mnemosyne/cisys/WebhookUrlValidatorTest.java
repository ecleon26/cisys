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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebhookUrlValidatorTest {
  private static HostResolver resolved(String... addresses) {
    return host -> {
      InetAddress[] result = new InetAddress[addresses.length];
      for (int i = 0; i < addresses.length; i++) {
        result[i] = InetAddress.getByName(addresses[i]);
      }
      return result;
    };
  }

  private static WebhookUrlValidator validator(HostResolver resolver) {
    return new WebhookUrlValidator(resolver, Optional.empty(), "443", Optional.empty());
  }

  private static void rejects(WebhookUrlValidator v, String url, String message) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> v.validate(url));
    assertEquals(message, e.getMessage());
  }

  @Test
  void acceptsAnyPublicHttpsHostAndPinsTheAddress() {
    WebhookUrlValidator.Target t =
        validator(resolved("203.0.114.7"))
            .validate("https://Hooks.Example.com/services/T0/B0/xyz?token=abc");
    assertEquals("hooks.example.com", t.host());
    assertEquals(443, t.port());
    assertEquals("203.0.114.7", t.address().getHostAddress());
  }

  @Test
  void rejectsOtherSchemesCredentialsAndFragments() {
    WebhookUrlValidator v = validator(resolved("203.0.114.7"));
    rejects(v, "http://hooks.example.com/x", WebhookUrlValidator.SHAPE);
    rejects(v, "https://user:pass@hooks.example.com/x", WebhookUrlValidator.SHAPE);
    rejects(v, "https://hooks.example.com/x#frag", WebhookUrlValidator.SHAPE);
    rejects(v, "not a url", WebhookUrlValidator.SHAPE);
    rejects(v, "", WebhookUrlValidator.SHAPE);
  }

  @Test
  void rejectsPortsThatAreNotAllowed() {
    rejects(
        validator(resolved("203.0.114.7")),
        "https://hooks.example.com:8443/x",
        WebhookUrlValidator.PORT);
  }

  @Test
  void blocksPrivateLoopbackMetadataAndMappedAddresses() {
    for (String address :
        new String[] {"10.0.0.5", "127.0.0.1", "169.254.169.254", "::1", "::ffff:192.168.1.1"}) {
      rejects(
          validator(resolved(address)), "https://hooks.example.com/x", WebhookUrlValidator.BLOCKED);
    }
    rejects(validator(resolved("10.0.0.5")), "https://10.0.0.5/x", WebhookUrlValidator.BLOCKED);
  }

  @Test
  void blocksWhenAnyAddressIsPrivate() {
    rejects(
        validator(resolved("203.0.114.7", "10.0.0.5")),
        "https://hooks.example.com/x",
        WebhookUrlValidator.BLOCKED);
  }

  @Test
  void allowsAPrivateNetworkOnPurpose() {
    WebhookUrlValidator v =
        new WebhookUrlValidator(
            resolved("10.1.2.3"), Optional.empty(), "443", Optional.of("10.1.0.0/16"));
    assertEquals("10.1.2.3", v.validate("https://chat.internal/x").address().getHostAddress());
  }

  @Test
  void limitsHostsWhenAdminsListThem() {
    WebhookUrlValidator v =
        new WebhookUrlValidator(
            resolved("203.0.114.7"), Optional.of("hooks.slack.com"), "443", Optional.empty());
    v.validate("https://hooks.slack.com/services/x");
    rejects(v, "https://evil.example.com/x", WebhookUrlValidator.HOST);
  }

  @Test
  void reportsUnknownHosts() {
    HostResolver unknown =
        host -> {
          throw new UnknownHostException(host);
        };
    rejects(validator(unknown), "https://nowhere.example/x", WebhookUrlValidator.UNKNOWN);
  }

  @Test
  void displayHidesPathAndQuery() {
    assertEquals(
        "https://hooks.example.com/…",
        WebhookUrlValidator.display("https://hooks.example.com/services/T0/B0/secret"));
    assertEquals(
        "https://hooks.example.com:8443/…",
        WebhookUrlValidator.display("https://hooks.example.com:8443/?token=abc"));
    assertEquals(
        "https://hooks.example.com", WebhookUrlValidator.display("https://hooks.example.com/"));
  }
}
