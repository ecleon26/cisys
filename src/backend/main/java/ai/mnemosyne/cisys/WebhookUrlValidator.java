/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where webhooks may post. Any public HTTPS host is allowed unless admins list hosts; the protected
 * networks of {@link NetworkAllowlist} stay blocked unless a network is allowed on purpose. The
 * check runs when a webhook is saved and again before every attempt, and the request goes to the
 * address the check approved, so DNS cannot change it in between.
 */
@ApplicationScoped
public class WebhookUrlValidator {
  static final String SHAPE = "Use an HTTPS webhook URL without credentials";
  static final String HOST = "Use a webhook on an allowed host";
  static final String PORT = "The webhook host must use an allowed port";
  static final String BLOCKED = "The webhook host resolves to a blocked address";
  static final String UNKNOWN = "The webhook host could not be resolved";

  private final HostResolver resolver;
  private final Set<String> allowedHosts;
  private final Set<Integer> allowedPorts;
  private final List<RepositoryUrls.Cidr> allowedNetworks;

  @Inject
  public WebhookUrlValidator(
      HostResolver resolver,
      @ConfigProperty(name = "cisys.webhooks.allowed-hosts") Optional<String> allowedHosts,
      @ConfigProperty(name = "cisys.webhooks.allowed-ports", defaultValue = "443")
          String allowedPorts,
      @ConfigProperty(name = "cisys.webhooks.allowed-networks") Optional<String> allowedNetworks) {
    this.resolver = resolver;
    this.allowedHosts = RepositoryUrls.parseHosts(allowedHosts.orElse(""));
    this.allowedPorts = RepositoryUrls.parsePorts(allowedPorts);
    this.allowedNetworks = RepositoryUrls.parseNetworks(allowedNetworks.orElse(""));
  }

  /** A checked URL and the address to connect to. */
  record Target(URI uri, String host, int port, InetAddress address) {}

  /** Throws {@link IllegalArgumentException} with a message safe to show to the caller. */
  Target validate(String url) {
    if (url == null || url.isBlank() || url.length() > 2048) {
      throw new IllegalArgumentException(SHAPE);
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(SHAPE);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || uri.getHost() == null) {
      throw new IllegalArgumentException(SHAPE);
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    if (!allowedPorts.contains(port)) {
      throw new IllegalArgumentException(PORT);
    }
    if (!allowedHosts.isEmpty() && !allowedHosts.contains(host)) {
      throw new IllegalArgumentException(HOST);
    }
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(UNKNOWN);
    }
    if (addresses == null || addresses.length == 0) {
      throw new IllegalArgumentException(UNKNOWN);
    }
    for (InetAddress address : addresses) {
      if (address == null) {
        throw new IllegalArgumentException(UNKNOWN);
      }
      byte[] raw = address.getAddress();
      boolean allowed =
          RepositoryUrls.inside(raw, allowedNetworks)
              || RepositoryUrls.inside(RepositoryUrls.unwrap(raw), allowedNetworks);
      if (!allowed && NetworkAllowlist.isProtected(address)) {
        throw new IllegalArgumentException(BLOCKED);
      }
    }
    return new Target(uri, host, port, addresses[0]);
  }

  /** The URL without its path and query, which often carry a token: {@code https://host/…}. */
  static String display(String url) {
    try {
      URI uri = new URI(url);
      String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
      boolean more =
          (uri.getRawPath() != null && uri.getRawPath().length() > 1) || uri.getRawQuery() != null;
      return uri.getScheme() + "://" + uri.getHost() + port + (more ? "/…" : "");
    } catch (URISyntaxException | RuntimeException e) {
      return "…";
    }
  }
}
