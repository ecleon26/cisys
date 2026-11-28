/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

/** One event for one webhook, kept as an outbox row until it is delivered or given up. */
@Entity
public class WebhookDelivery extends PanacheEntity {
  /** What happened to the build, sent as {@code event} and the event header. */
  public enum Event {
    PASSED("build.passed"),
    FAILED("build.failed"),
    FIXED("build.fixed");

    private final String code;

    Event(String code) {
      this.code = code;
    }

    /** The code in the payload and the database. */
    public String code() {
      return code;
    }

    static Event of(String code) {
      for (Event e : values()) {
        if (e.code.equals(code)) {
          return e;
        }
      }
      throw new IllegalArgumentException(code);
    }
  }

  /** PENDING until it is delivered or given up as FAILED. */
  public enum Status {
    PENDING,
    DELIVERED,
    FAILED
  }

  /** Stores the event by its code. */
  @Converter
  public static class EventConverter implements AttributeConverter<Event, String> {
    @Override
    public String convertToDatabaseColumn(Event event) {
      return event == null ? null : event.code();
    }

    @Override
    public Event convertToEntityAttribute(String code) {
      return code == null ? null : Event.of(code);
    }
  }

  @Column(nullable = false)
  public Long webhookId;

  @Column(nullable = false)
  public Long buildId;

  @Convert(converter = EventConverter.class)
  @Column(nullable = false, length = 20)
  public Event event;

  /** The JSON body, fixed when the build finished so retries send the same bytes. */
  @Column(nullable = false, length = 4000)
  public String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public Status status;

  @Column(nullable = false)
  public int attempts;

  /** When a pending delivery is due; also the lease of a delivery being sent. */
  public Instant nextAttemptAt;

  public Instant lastAttemptAt;
  public Integer responseStatus;

  @Column(length = 500)
  public String error;

  @Column(nullable = false)
  public Instant createdAt;
}
