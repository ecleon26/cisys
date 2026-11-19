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
import java.util.List;

/** A comment on a build. Only its author may change it. */
@Entity
public class BuildComment extends PanacheEntity {
  /** The longest comment, in characters. */
  public static final int MAX_BODY = 10000;

  @Column(nullable = false)
  public Long buildId;

  /** The Keycloak subject of the author. */
  @Column(nullable = false, length = 255)
  public String authorSubject;

  @Column(nullable = false, length = MAX_BODY)
  public String body;

  @Column(nullable = false)
  public Instant createdAt;

  /** When the author last edited the comment, or null when it was never edited. */
  public Instant updatedAt;

  /** The build's comments, newest first. */
  static List<BuildComment> of(Long buildId) {
    return list("buildId = ?1 order by createdAt desc, id desc", buildId);
  }
}
