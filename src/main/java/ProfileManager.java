import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;



/**
 * Centralises all file & profile housekeeping for each client.
 * <p>
 * Directory layout per client (id=N):
 * <pre>
 *   data/
 *     N/
 *       Profile_N.txt   (own timeline / actions)
 *       Others_N.txt    (cached timeline of followed users)
 *       photos/         (all uploaded media)
 * </pre>
 * All methods are thread‑safe at class‑level so that concurrent uploads / reads
 * from different ClientHandler threads do not corrupt files.
 */
public final class ProfileManager {
    private static final Set<String> lockedFiles = ConcurrentHashMap.newKeySet();
    private static final String DATA_ROOT = "data_server";

    private ProfileManager() {
    }

    /*────────────────────────────── dir helpers ──────────────────────────────*/

    public static Path getUserDir(int clientId) {
        return Paths.get(DATA_ROOT, String.valueOf(clientId));
    }

    private static Path getPhotosDir(int clientId) {
        return getUserDir(clientId).resolve("photos");
    }

    /*──────────────────────────── public API ────────────────────────────────*/

    /**
     * Ensures that the base directory, profile files and photo folder exist
     * for the given client. Safe to call multiple times.
     */
    public static synchronized void ensureUserDir(int clientId) throws IOException {
        Path dir = getUserDir(clientId);
        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
        }

        createIfMissing(dir.resolve(String.format("Profile_%d.txt", clientId)));
        createIfMissing(dir.resolve(String.format("Others_%d.txt", clientId)));
        createIfMissing(getPhotosDir(clientId));
    }

    public static Set<Integer> getAllowedViewers(int ownerId) {
        // Για τώρα, απλώς οι φίλοι
        return SocialGraph.getFriends(ownerId);
    }

    

    /**
     * Append a single line to the user\'s own profile (timeline). The line is
     * automatically timestamped using ISO‑8601.
     */
    public static synchronized void appendToProfile(int clientId, String entry) throws IOException {
        ensureUserDir(clientId);
        Path profile = getUserDir(clientId).resolve(String.format("Profile_%d.txt", clientId));
        try (BufferedWriter writer = Files.newBufferedWriter(profile, StandardOpenOption.APPEND)) {
            writer.write(String.format("[%s] %s", Instant.now(), entry));
            writer.newLine();
        }
    }

    /**
     * Ψάχνει σε κάθε data/<clientId>/Profile.txt
     * για τη γραμμή “posted <filename>” και
     * επιστρέφει το clientId που την ανέβασε.
     * Αν δεν βρεθεί, επιστρέφει -1.
     */
    public static int findOwnerOfPost(String filename) {
        Path dataDir = Paths.get("data");
        System.out.println("[DEBUG] findOwnerOfPost: searching for \"" + filename +
                "\" under " + dataDir.toAbsolutePath());
        if (!Files.exists(dataDir) || !Files.isDirectory(dataDir)) {
            System.out.println("[DEBUG] dataDir missing or not a dir");
            return -1;
        }

        try (DirectoryStream<Path> users = Files.newDirectoryStream(dataDir)) {
            for (Path userDir : users) {
                if (!Files.isDirectory(userDir)) continue;
                String dirName = userDir.getFileName().toString();
                String profileFileName = "Profile_" + dirName + ".txt";
                Path profileFile = userDir.resolve(profileFileName);
                if (!Files.exists(profileFile)) {
                    System.out.println("[DEBUG] no " + profileFileName + " for user " + dirName);
                    continue;
                }
                System.out.println("[DEBUG] scanning " + profileFileName + " of user " + dirName);
                List<String> lines = Files.readAllLines(profileFile);
                for (String line : lines) {
                    System.out.println("[DEBUG]   line: " + line);
                    if (line.contains("posted " + filename)) {
                        System.out.println("[DEBUG]   ➞ match in user " + dirName);
                        try {
                            return Integer.parseInt(dirName);
                        } catch (NumberFormatException ignore) {}
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("[DEBUG] findOwnerOfPost: not found");
        return -1;
    }



    /**
     * Saves a photo bytes[] in the user\'s photos directory and appends the
     * corresponding post in Profile_N.txt.
     */
    public static void savePhoto(int clientId, String filename, byte[] data) throws IOException {
        ensureUserDir(clientId);
        Path photo = getPhotosDir(clientId).resolve(filename);
        String key = photo.toString();

        // application-level lock
        if (!lockedFiles.add(key)) {
            System.out.println("Server: Unable to acquire lock on " + filename);
            throw new IOException("LOCK_FAILED");
        }
        System.out.println("Server: Acquired lock on " + filename);

        try {
            // κρατάμε το lock 20″ ώστε να προλάβει ο B
            try { Thread.sleep(20000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            // κάνουμε το “write”
            Files.write(photo, data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            System.out.println("Server: Wrote data to " + filename);

        } finally {
            lockedFiles.remove(key);
            System.out.println("Server: Released lock on " + filename);
        }
    }




    public static synchronized void appendToOthers(int followerId,
                                                   String entry) throws IOException {
        Path p = getUserDir(followerId)
                .resolve(String.format("Others_%d.txt", followerId));
        try (BufferedWriter w = Files.newBufferedWriter(p,
                StandardOpenOption.APPEND)) {
            w.write("[" + Instant.now() + "] " + entry);
            w.newLine();
        }
    }


    /**
     * Reads the entire profile timeline.
     */
    public static List<String> readProfile(int clientId) throws IOException {
        ensureUserDir(clientId);
        Path profile = getUserDir(clientId).resolve(String.format("Profile_%d.txt", clientId));
        return Files.readAllLines(profile);
    }



    /**
     * Returns the raw bytes of a stored photo, if present.
     */
    public static Optional<byte[]> getPhoto(int ownerId, String filename) throws IOException {
        Path photo = getPhotosDir(ownerId).resolve(filename);
        if (Files.exists(photo)) {
            return Optional.of(Files.readAllBytes(photo));
        }
        return Optional.empty();
    }

    /**
     * Quick listing of all photo filenames for the given user.
     */
    public static List<String> listPhotos(int clientId) throws IOException {
        ensureUserDir(clientId);
        try (Stream<Path> stream = Files.list(getPhotosDir(clientId))) {
            return stream.filter(Files::isRegularFile)
                         .map(Path::getFileName)
                         .map(Path::toString)
                         .collect(Collectors.toList());
        }
    }

    /*────────────────────────── private helpers ─────────────────────────────*/

    private static void createIfMissing(Path p) throws IOException {
        if (Files.notExists(p)) {
            if (p.toString().endsWith(".txt")) {
                Files.createFile(p);
            } else {
                Files.createDirectories(p);
            }
        }
    }
}

