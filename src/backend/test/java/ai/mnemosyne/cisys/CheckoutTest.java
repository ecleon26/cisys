/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutTest {
    private static final int MIB = 1024 * 1024;

    @TempDir Path source;
    @TempDir Path parent;

    @BeforeEach
    void initSource() throws Exception {
        Git.init().setDirectory(source.toFile()).setInitialBranch("main").call().close();
    }

    private String commit() throws Exception {
        try (Git git = Git.open(source.toFile())) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage("files").setAuthor("Test", "test@example.com").setCommitter("Test", "test@example.com").call().name();
        }
    }

    private Path dir() {
        return parent.resolve("workspace");
    }

    private String fetch(int maxMib, int maxFiles) throws Exception {
        return Checkout.fetch(dir(), source.toUri().toString(), "main", null, maxMib, maxFiles);
    }

    private void assertRejected(String message, int maxMib, int maxFiles) throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> fetch(maxMib, maxFiles));
        assertEquals(message, e.getMessage());
        try (var left = Files.list(parent)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void checksOutBelowTheLimits() throws Exception {
        Files.writeString(source.resolve("README"), "hello\n");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/main.sh"), "echo hi\n");
        String revision = commit();
        assertEquals(revision, fetch(1, 2));
        assertEquals("hello\n", Files.readString(dir().resolve("README")));
        assertEquals("echo hi\n", Files.readString(dir().resolve("src/main.sh")));
    }

    @Test
    void checksOutTheBranchTrackingOrigin() throws Exception {
        Files.writeString(source.resolve("README"), "hello\n");
        String revision = commit();
        fetch(1, 1);
        try (Git git = Git.open(dir().toFile())) {
            assertEquals("main", git.getRepository().getBranch());
            assertEquals(source.toUri().toString(), git.getRepository().getConfig().getString("remote", "origin", "url"));
            assertEquals("origin", git.getRepository().getConfig().getString("branch", "main", "remote"));
            assertEquals(revision, git.getRepository().resolve("refs/remotes/origin/main").name());
        }
    }

    @Test
    void acceptsExactlyTheLimit() throws Exception {
        Files.write(source.resolve("zeros"), new byte[MIB]);
        String revision = commit();
        assertEquals(revision, fetch(1, 1));
    }

    @Test
    void countsLineEndingConversion() throws Exception {
        // 1,000,000 bytes in Git, 1,500,000 bytes once checked out with CRLF line endings.
        Files.writeString(source.resolve(".gitattributes"), "*.txt text eol=crlf\n");
        Files.writeString(source.resolve("lines.txt"), "a\n".repeat(500_000));
        commit();
        assertRejected("Repository checkout exceeds the limit of 1 MiB", 1, 10);
    }

    @Test
    void acceptsLineEndingConversionWithinTheLimit() throws Exception {
        Files.writeString(source.resolve(".gitattributes"), "*.txt text eol=crlf\n");
        Files.writeString(source.resolve("lines.txt"), "ab\n".repeat(1000));
        commit();
        fetch(1, 10);
        assertEquals(4000, Files.size(dir().resolve("lines.txt")));
    }

    @Test
    void rejectsCompressibleContentBeforeCheckout() throws Exception {
        Files.write(source.resolve("zeros"), new byte[2 * MIB]);
        commit();
        assertRejected("Repository checkout exceeds the limit of 1 MiB", 1, 10);
    }

    @Test
    void rejectsALargePackDuringFetch() throws Exception {
        byte[] noise = new byte[2 * MIB];
        new Random(32).nextBytes(noise);
        Files.write(source.resolve("noise"), noise);
        commit();
        assertRejected("Repository checkout exceeds the limit of 1 MiB", 1, 10);
    }

    @Test
    void rejectsTooManyFiles() throws Exception {
        for (int i = 0; i < 6; i++) Files.writeString(source.resolve("f" + i), "x");
        commit();
        assertRejected("Repository checkout exceeds the limit of 5 files", 1, 5);
    }
}
