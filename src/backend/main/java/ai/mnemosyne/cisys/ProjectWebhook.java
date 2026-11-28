/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.time.Instant;

/** A URL that receives a signed JSON POST when one of the project's builds finishes. */
@Entity
public class ProjectWebhook extends PanacheEntity {
  static final int MAX_PER_PROJECT = 10;

  @Column(nullable = false)
  public Long projectId;

  @Column(nullable = false, length = 2048)
  public String url;

  /** The HMAC key, hex. Never returned after it was created or rotated. */
  @Column(nullable = false, length = 64)
  public String secret;

  /** Every passed or failed build. */
  @Column(nullable = false)
  public boolean onFinished;

  @Column(nullable = false)
  public boolean onFailed;

  /** A passed build whose previous build on the same branch or pull request failed. */
  @Column(nullable = false)
  public boolean onFixed;

  @Column(nullable = false)
  public Instant createdAt;

  boolean wants(WebhookDelivery.Event event) {
    return onFinished
        || (onFailed && event == WebhookDelivery.Event.FAILED)
        || (onFixed && event == WebhookDelivery.Event.FIXED);
  }
}
