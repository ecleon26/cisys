/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds the local image a workflow's {@code container} means. These all name the same image when
 * {@code docker.io/library/alpine:3.23} with ID {@code 164b5c6a230f…} is the only local Alpine
 * image: {@code alpine}, {@code alpine:3.23}, {@code docker.io/library/alpine}, {@code
 * docker.io/library/alpine:3.23} and {@code 164b5c6a230f}.
 */
public final class ImageReference {
  private ImageReference() {}

  /**
   * One row of the runtime's image list. {@code id} is the full hexadecimal ID without {@code
   * sha256:}.
   */
  public record Local(String repository, String tag, String id) {
    String name() {
      return repository + ":" + tag;
    }

    boolean named() {
      return !repository.equals("<none>") && !tag.equals("<none>");
    }
  }

  /** The image to build from: its full ID and every name the runtime knows it by, sorted. */
  public record Resolved(String id, List<String> names) {
    /** The name to show for the build. */
    public String name() {
      return names.isEmpty() ? id.substring(0, 12) : names.get(0);
    }
  }

  private static final String ID = "(sha256:)?[0-9a-f]{12,64}";

  /**
   * Resolves {@code base} against the local images, or throws an error that is safe to show in the
   * build log.
   */
  public static Resolved resolve(String base, List<Local> images) {
    String reference = base.strip();
    if (reference.matches(ID)) {
      String prefix = reference.toLowerCase(Locale.ROOT).replace("sha256:", "");
      List<String> ids =
          images.stream().map(Local::id).filter(id -> id.startsWith(prefix)).distinct().toList();
      if (ids.size() > 1) {
        throw new Workflow.InvalidWorkflowException(
            "Image ID " + base + " matches several local images");
      }
      if (ids.size() == 1) {
        return resolved(ids.get(0), images);
      }
      // Not an ID after all; a name may also look like one.
    }
    if (reference.contains("@")) {
      throw new Workflow.InvalidWorkflowException(
          "Image " + base + ": digest references are not supported, use the image ID");
    }
    int slash = reference.lastIndexOf('/');
    int colon = reference.lastIndexOf(':');
    String repository = colon > slash ? reference.substring(0, colon) : reference;
    String tag = colon > slash ? reference.substring(colon + 1) : null;
    String qualified = official(repository);
    List<Local> candidates =
        images.stream()
            .filter(Local::named)
            .filter(i -> official(i.repository()).equals(qualified))
            .toList();
    if (tag != null) {
      return candidates.stream()
          .filter(i -> i.tag().equals(tag))
          .findFirst()
          .map(i -> resolved(i.id(), images))
          .orElseThrow(() -> notAvailable(base));
    }
    List<String> ids = candidates.stream().map(Local::id).distinct().toList();
    if (ids.isEmpty()) {
      throw notAvailable(base);
    }
    if (ids.size() == 1) {
      return resolved(ids.get(0), images);
    }
    // Several versions are present: an untagged name means latest, as for the runtime itself.
    return candidates.stream()
        .filter(i -> i.tag().equals("latest"))
        .findFirst()
        .map(i -> resolved(i.id(), images))
        .orElseThrow(() ->
            new Workflow.InvalidWorkflowException(
                "Image "
                    + base
                    + " matches several local images ("
                    + String.join(", ", candidates.stream().map(Local::name).sorted().toList())
                    + "); add the tag"));
  }

  /**
   * A repository without a registry is on Docker Hub, and a single name there is an official image,
   * for example {@code alpine} and {@code docker.io/alpine} are both {@code
   * docker.io/library/alpine}. {@code index.docker.io} is an older name of {@code docker.io}.
   */
  static String official(String repository) {
    int slash = repository.indexOf('/');
    String first = slash < 0 ? "" : repository.substring(0, slash);
    String path = repository;
    if (first.equals("docker.io") || first.equals("index.docker.io")) {
      path = repository.substring(slash + 1);
    } else if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
      return repository;
    }
    return "docker.io/" + (path.contains("/") ? "" : "library/") + path;
  }

  private static Resolved resolved(String id, List<Local> images) {
    List<String> names =
        images.stream()
            .filter(i -> i.id().equals(id))
            .filter(Local::named)
            .map(Local::name)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    return new Resolved(id, names);
  }

  private static Workflow.InvalidWorkflowException notAvailable(String base) {
    return new Workflow.InvalidWorkflowException(
        "Image "
            + base
            + " is not available on the build host; an admin can pull it on the Images page");
  }
}
