/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
/** A build. Its log is a large object in the unmapped logoid column, see {@link BuildLogs}. */
@Entity public class Build extends PanacheEntity {
 public Long projectId;
 public String requestedBy;
 public enum Status { QUEUED, RUNNING, PASSED, FAILED, CANCELLED }
 @Enumerated(EnumType.STRING) public Status status;
 public Instant requestedAt;
 @Column(length=2048) public String repositoryUrl;
 public String revision;
 public String branch;
 /** The full name of the local image the workflow's container resolved to, once the build has read it. */
 public String image;
 /** The text of the branch's {@value Workflow#PATH}, as the build read it. */
 @Column(length=Workflow.MAX_BYTES) public String workflow;
 public String credentialRef;
 @Column(length=300) public String imageDigest;
 public Integer exitCode;
 @Column(nullable=false) public boolean logTruncated;
 /** Pinned builds are kept by retention cleanup. */
 @Column(nullable=false) public boolean pinned;
 /** Set on a running build; the runner stops it and records it as cancelled. */
 @Column(nullable=false) public boolean cancelRequested;
 public Instant startedAt;
 public Instant finishedAt;
}
