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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImageReferenceTest {
  static final String ALPINE = "164b5c6a230f645862b6123e07cf0a67f2f03371696aa82a12ccf3589e87c016";
  static final String DEBIAN = "596c6d80c879d7e2b4d1a4a1c4e5b7f1a1f5f6a3f9e0d8c7b6a5948372615049";
  static final String VALKEY = "9033f1e0a509a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3";
  static final String DANGLING = "d2b120a24cdd6effd15c0f4ba1a9da247a96f8da5fdf2edbbe205dad7e08de4a";

  /** As {@code podman images} lists them on a build host. */
  static final List<ImageReference.Local> IMAGES =
      List.of(
          new ImageReference.Local("<none>", "<none>", DANGLING),
          new ImageReference.Local("docker.io/library/alpine", "3.23", ALPINE),
          new ImageReference.Local("docker.io/library/debian", "13-slim", DEBIAN),
          new ImageReference.Local("docker.io/valkey/valkey", "8-alpine", VALKEY));

  @Test
  void allFormsResolveToTheSameImage() {
    for (String base :
        List.of(
            "alpine",
            "alpine:3.23",
            "docker.io/library/alpine",
            "docker.io/library/alpine:3.23",
            "164b5c6a230f",
            "sha256:164b5c6a230f",
            ALPINE)) {
      var resolved = ImageReference.resolve(base, IMAGES);
      assertEquals(ALPINE, resolved.id(), base);
      assertEquals(List.of("docker.io/library/alpine:3.23"), resolved.names(), base);
      assertEquals("docker.io/library/alpine:3.23", resolved.name(), base);
    }
  }

  @Test
  void dockerHubNamesResolveAsDockerNormalizesThem() {
    // docker.io/alpine is docker.io/library/alpine, and index.docker.io is docker.io.
    for (String base :
        List.of(
            "docker.io/alpine",
            "docker.io/alpine:3.23",
            "index.docker.io/alpine:3.23",
            "index.docker.io/library/alpine:3.23")) {
      assertEquals(ALPINE, ImageReference.resolve(base, IMAGES).id(), base);
    }
    assertEquals(VALKEY, ImageReference.resolve("index.docker.io/valkey/valkey", IMAGES).id());
    var docker = List.of(new ImageReference.Local("alpine", "3.23", ALPINE));
    assertEquals(ALPINE, ImageReference.resolve("docker.io/alpine:3.23", docker).id());
  }

  @Test
  void resolvesNonLibraryImages() {
    assertEquals(VALKEY, ImageReference.resolve("valkey/valkey", IMAGES).id());
    assertEquals(VALKEY, ImageReference.resolve("valkey/valkey:8-alpine", IMAGES).id());
  }

  @Test
  void resolvesShortRepositoriesAsDockerListsThem() {
    var images =
        List.of(
            new ImageReference.Local("alpine", "3.23", ALPINE),
            new ImageReference.Local("valkey/valkey", "8-alpine", VALKEY));
    assertEquals(ALPINE, ImageReference.resolve("alpine", images).id());
    assertEquals(ALPINE, ImageReference.resolve("alpine:3.23", images).id());
    assertEquals(ALPINE, ImageReference.resolve("docker.io/library/alpine:3.23", images).id());
    assertEquals(VALKEY, ImageReference.resolve("valkey/valkey:8-alpine", images).id());
  }

  @Test
  void otherRegistriesAreNotDockerHub() {
    var images = List.of(new ImageReference.Local("quay.io/keycloak/keycloak", "26.8.2", ALPINE));
    assertEquals(ALPINE, ImageReference.resolve("quay.io/keycloak/keycloak", images).id());
    assertThrows(
        Workflow.InvalidWorkflowException.class,
        () -> ImageReference.resolve("keycloak/keycloak", images));
  }

  @Test
  void untaggedNameWithSeveralVersionsUsesLatestOrAsksForTheTag() {
    var two =
        List.of(
            new ImageReference.Local("docker.io/library/alpine", "3.23", ALPINE),
            new ImageReference.Local("docker.io/library/alpine", "3.22", DEBIAN));
    var e =
        assertThrows(
            Workflow.InvalidWorkflowException.class, () -> ImageReference.resolve("alpine", two));
    assertTrue(
        e.getMessage().contains("docker.io/library/alpine:3.22, docker.io/library/alpine:3.23"),
        e.getMessage());
    var withLatest =
        List.of(
            two.get(0),
            two.get(1),
            new ImageReference.Local("docker.io/library/alpine", "latest", DEBIAN));
    assertEquals(DEBIAN, ImageReference.resolve("alpine", withLatest).id());
    // Two tags of one image are not ambiguous.
    var sameImage =
        List.of(two.get(0), new ImageReference.Local("docker.io/library/alpine", "3", ALPINE));
    var resolved = ImageReference.resolve("alpine", sameImage);
    assertEquals(
        List.of("docker.io/library/alpine:3", "docker.io/library/alpine:3.23"), resolved.names());
  }

  @Test
  void missingImagesAreReported() {
    for (String base :
        List.of(
            "alpine:3.22",
            "ubuntu",
            "docker.io/library/ubuntu:24.04",
            "abcdefabcdef",
            "alpine@sha256:" + ALPINE)) {
      assertThrows(
          Workflow.InvalidWorkflowException.class,
          () -> ImageReference.resolve(base, IMAGES),
          base);
    }
    var e =
        assertThrows(
            Workflow.InvalidWorkflowException.class,
            () -> ImageReference.resolve("ubuntu", IMAGES));
    assertTrue(e.getMessage().contains("Images page"), e.getMessage());
  }

  @Test
  void danglingImagesHaveNoNames() {
    var resolved = ImageReference.resolve("d2b120a24cdd", IMAGES);
    assertEquals(DANGLING, resolved.id());
    assertEquals(List.of(), resolved.names());
    assertEquals("d2b120a24cdd", resolved.name());
  }

  @Test
  void ambiguousIdPrefix() {
    var images =
        List.of(
            new ImageReference.Local("a/b", "1", ALPINE),
            new ImageReference.Local("a/c", "1", "164b5c6a230f" + "0".repeat(52)));
    assertThrows(
        Workflow.InvalidWorkflowException.class,
        () -> ImageReference.resolve("164b5c6a230f", images));
    assertEquals(ALPINE, ImageReference.resolve(ALPINE.substring(0, 13), images).id());
  }
}
