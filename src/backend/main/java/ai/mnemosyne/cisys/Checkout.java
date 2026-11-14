/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Fetches one branch at depth 1 into a new directory, on that branch, with {@code origin} set to the repository. The
 * received pack is capped while it is read, the files the checkout will write are measured before anything is
 * checked out, and the written working tree is measured again afterwards.
 */
final class Checkout {
    private static final long MIB = 1024 * 1024;

    private Checkout() {}

    /** Returns the commit ID. {@code dir} must not exist; it is removed again when the checkout fails. */
    static String fetch(Path dir, String url, String branch, CredentialsProvider credentials, int maxMib, int maxFiles)
            throws Exception {
        String ref = Constants.R_REMOTES + "origin/" + branch;
        Files.createDirectory(dir);
        try (LimitedRepository repo = new LimitedRepository(dir.resolve(".git").toFile(), maxMib * MIB); Git git = Git.wrap(repo)) {
            repo.create();
            var cfg = repo.getConfig();
            cfg.setString("http", null, "followRedirects", "false");
            cfg.setString("remote", "origin", "url", url);
            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            cfg.setString("branch", branch, "remote", "origin");
            cfg.setString("branch", branch, "merge", Constants.R_HEADS + branch);
            cfg.save();
            var fetch = git.fetch().setRemote(url).setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + ref)).setDepth(1).setTimeout(60);
            if (credentials != null) fetch.setCredentialsProvider(credentials);
            try {
                fetch.call();
            } catch (Exception e) {
                if (repo.exceeded) throw tooLarge(maxMib);
                throw e;
            }
            checkTree(repo, ref, maxMib, maxFiles);
            git.checkout().setName(branch).setCreateBranch(true).setStartPoint(ref).call();
            // Smudge filters from the server's Git configuration can still change what is written.
            checkWritten(dir, maxMib, maxFiles);
            return repo.resolve(Constants.HEAD).name();
        } catch (Exception e) {
            try { BuildRunner.delete(dir); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    /** Sizes are those of the files as checked out, after line ending conversion. */
    private static void checkTree(Repository repo, String ref, int maxMib, int maxFiles) throws IOException {
        long bytes = 0;
        int files = 0;
        WorkingTreeOptions options = repo.getConfig().get(WorkingTreeOptions.KEY);
        try (RevWalk revs = new RevWalk(repo); TreeWalk tree = new TreeWalk(repo)) {
            tree.setOperationType(TreeWalk.OperationType.CHECKOUT_OP);
            tree.addTree(revs.parseCommit(repo.resolve(ref)).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                if (++files > maxFiles) throw tooMany(maxFiles);
                // Submodule commits are not fetched.
                if (tree.getFileMode(0) == FileMode.GITLINK) continue;
                CoreConfig.EolStreamType eol = tree.getCheckoutEolStreamType(0);
                if (eol == null || eol == CoreConfig.EolStreamType.DIRECT || tree.getFileMode(0) == FileMode.SYMLINK) {
                    bytes += tree.getObjectReader().getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                } else {
                    Counter written = new Counter(maxMib * MIB - bytes);
                    try {
                        DirCacheCheckout.getContent(repo, tree.getPathString(), new DirCacheCheckout.CheckoutMetadata(eol, null),
                                tree.getObjectReader().open(tree.getObjectId(0), Constants.OBJ_BLOB), options, written);
                    } catch (Counter.Exceeded e) {
                        throw tooLarge(maxMib);
                    }
                    bytes += written.count;
                }
                if (bytes > maxMib * MIB) throw tooLarge(maxMib);
            }
        }
    }

    private static void checkWritten(Path dir, int maxMib, int maxFiles) throws IOException {
        Path git = dir.resolve(".git");
        long[] total = new long[2];
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                return d.equals(git) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (++total[0] > maxFiles) throw tooMany(maxFiles);
                total[1] += attrs.size();
                if (total[1] > maxMib * MIB) throw tooLarge(maxMib);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static IllegalArgumentException tooLarge(int maxMib) {
        return new IllegalArgumentException("Repository checkout exceeds the limit of " + maxMib + " MiB");
    }

    private static IllegalArgumentException tooMany(int maxFiles) {
        return new IllegalArgumentException("Repository checkout exceeds the limit of " + maxFiles + " files");
    }

    /** Counts what is written and stops once it passes {@code max}. */
    private static final class Counter extends OutputStream {
        static final class Exceeded extends IOException {}

        private final long max;
        long count;

        Counter(long max) { this.max = max; }

        @Override public void write(int b) throws IOException { add(1); }

        @Override public void write(byte[] b, int off, int len) throws IOException { add(len); }

        private void add(long n) throws IOException {
            count += n;
            if (count > max) throw new Exceeded();
        }
    }

    private static final class LimitedRepository extends FileRepository {
        private final long maxBytes;
        private long received;
        boolean exceeded;

        LimitedRepository(File gitDir, long maxBytes) throws IOException {
            super(gitDir);
            this.maxBytes = maxBytes;
        }

        @Override public ObjectInserter newObjectInserter() {
            ObjectInserter inserter = super.newObjectInserter();
            return new ObjectInserter.Filter() {
                @Override protected ObjectInserter delegate() { return inserter; }

                @Override public PackParser newPackParser(InputStream in) throws IOException {
                    return super.newPackParser(new Limited(in));
                }
            };
        }

        private final class Limited extends FilterInputStream {
            Limited(InputStream in) { super(in); }

            @Override public int read() throws IOException {
                int b = super.read();
                if (b >= 0) count(1);
                return b;
            }

            @Override public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                if (n > 0) count(n);
                return n;
            }

            @Override public long skip(long n) throws IOException {
                long skipped = super.skip(n);
                if (skipped > 0) count(skipped);
                return skipped;
            }

            private void count(long n) throws IOException {
                received += n;
                if (received > maxBytes) {
                    exceeded = true;
                    throw new IOException("Pack exceeds the checkout size limit");
                }
            }
        }
    }
}
