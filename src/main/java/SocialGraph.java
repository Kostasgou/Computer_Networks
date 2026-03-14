import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Singleton για τον κοινωνικό γράφο με persistence:
 * - followers
 * - following
 * - extra permissions
 * Υποστηρίζει φόρτωση από αρχείο (loadFromLines) και
 * αυτόματη αποθήκευση σε text files.
 */
public class SocialGraph {
    // Paths must be declared before INSTANCE to avoid null during initialization
    private static final Path STORAGE_DIR = Paths.get("data_server");
    private static final Path FOLLOWERS_FILE = STORAGE_DIR.resolve("followers.txt");
    private static final Path FOLLOWING_FILE = STORAGE_DIR.resolve("following.txt");
    private static final Path EXTRA_PERMS_FILE = STORAGE_DIR.resolve("extra_permissions.txt");
    
    // Singleton instance loaded after paths
    private static final SocialGraph INSTANCE = new SocialGraph();

    private final ConcurrentMap<Integer, Set<Integer>> followers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Set<Integer>> following = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Set<Integer>> extraPermissions = new ConcurrentHashMap<>();

    private SocialGraph() {
        try {
            Files.createDirectories(STORAGE_DIR);
            loadState();
        } catch (IOException e) {
            throw new RuntimeException("Cannot initialize SocialGraph storage", e);
        }
    }

    public static SocialGraph getInstance() {
        return INSTANCE;
    }

    /** Φόρτωση persisted state */
    private void loadState() throws IOException {
        loadMap(followers, FOLLOWERS_FILE);
        loadMap(following, FOLLOWING_FILE);
        loadMap(extraPermissions, EXTRA_PERMS_FILE);
    }

    private void loadMap(ConcurrentMap<Integer, Set<Integer>> map, Path file) throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            String[] parts = line.split(":");
            int id = Integer.parseInt(parts[0]);
            Set<Integer> set = ConcurrentHashMap.newKeySet();
            if (parts.length > 1 && !parts[1].isBlank()) {
                for (String s : parts[1].split(",")) {
                    set.add(Integer.parseInt(s.trim()));
                }
            }
            map.put(id, set);
        }
    }

    /** Αποθήκευση όλων των maps */
    private synchronized void saveState() {
        try {
            saveMap(followers, FOLLOWERS_FILE);
            saveMap(following, FOLLOWING_FILE);
            saveMap(extraPermissions, EXTRA_PERMS_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveMap(ConcurrentMap<Integer, Set<Integer>> map, Path file) throws IOException {
        List<String> lines = map.entrySet().stream()
            .map(e -> e.getKey() + ":" +
                      e.getValue().stream().map(Object::toString)
                      .collect(Collectors.joining(",")))
            .collect(Collectors.toList());
        Files.write(file, lines);
    }

    /** Φορτώνει graph από legacy SocialGraph.txt format και σώζει νέο state */
    public synchronized void loadFromLines(List<String> lines) throws IOException {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            int user = Integer.parseInt(parts[0]);
            followers.computeIfAbsent(user, k -> ConcurrentHashMap.newKeySet());
            following.computeIfAbsent(user, k -> ConcurrentHashMap.newKeySet());
            extraPermissions.computeIfAbsent(user, k -> ConcurrentHashMap.newKeySet());
            for (int i = 1; i < parts.length; i++) {
                int f = Integer.parseInt(parts[i]);
                followers.get(user).add(f);
                following.computeIfAbsent(f, k -> ConcurrentHashMap.newKeySet()).add(user);
            }
        }
        saveState();
    }

    // Static wrappers for convenience
    public static boolean addClient(int id) {
        boolean added = INSTANCE.followers.putIfAbsent(id, ConcurrentHashMap.newKeySet()) == null;
        INSTANCE.following.putIfAbsent(id, ConcurrentHashMap.newKeySet());
        INSTANCE.extraPermissions.putIfAbsent(id, ConcurrentHashMap.newKeySet());
        if (added) INSTANCE.saveState();
        return added;
    }

    public static boolean removeClient(int id) {
        boolean existed = INSTANCE.followers.remove(id) != null;
        INSTANCE.following.remove(id);
        INSTANCE.extraPermissions.remove(id);
        INSTANCE.followers.values().forEach(s -> s.remove(id));
        INSTANCE.following.values().forEach(s -> s.remove(id));
        INSTANCE.extraPermissions.values().forEach(s -> s.remove(id));
        if (existed) INSTANCE.saveState();
        return existed;
    }

    public static Set<Integer> getFriends(int userId) {
        Set<Integer> a = INSTANCE.followers.getOrDefault(userId, Collections.emptySet());
        Set<Integer> b = INSTANCE.following.getOrDefault(userId, Collections.emptySet());
        Set<Integer> mutual = new HashSet<>(a);
        mutual.retainAll(b);
        return Collections.unmodifiableSet(mutual);
    }

    public static Set<Integer> getAllowedViewers(int owner) {
        Set<Integer> allowed = new HashSet<>();
        allowed.addAll(INSTANCE.extraPermissions.getOrDefault(owner, Collections.emptySet()));
        return Collections.unmodifiableSet(allowed);
    }

    public static boolean grantPermission(int owner, int grantee) {
        boolean added = INSTANCE.extraPermissions
                             .computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet())
                             .add(grantee);
        if (added) INSTANCE.saveState();
        return added;
    }

    public static boolean revokePermission(int owner, int grantee) {
        Set<Integer> set = INSTANCE.extraPermissions.get(owner);
        boolean removed = set != null && set.remove(grantee);
        if (removed) INSTANCE.saveState();
        return removed;
    }

    public static Set<Integer> getExtraPermissions(int owner) {
        return Collections.unmodifiableSet(
            INSTANCE.extraPermissions.getOrDefault(owner, Collections.emptySet())
        );
    }

    public static boolean addFollow(int followerId, int followeeId) {
        boolean added = INSTANCE.following
                             .computeIfAbsent(followerId, k -> ConcurrentHashMap.newKeySet())
                             .add(followeeId);
        if (added) {
            INSTANCE.followers.computeIfAbsent(followeeId, k -> ConcurrentHashMap.newKeySet())
                               .add(followerId);
            INSTANCE.saveState();
        }
        return added;
    }

    public static boolean removeFollow(int followerId, int followeeId) {
        Set<Integer> f = INSTANCE.following.get(followerId);
        if (f != null && f.remove(followeeId)) {
            INSTANCE.followers.getOrDefault(followeeId, Collections.emptySet()).remove(followerId);
            INSTANCE.saveState();
            return true;
        }
        return false;
    }

    public static Set<Integer> getFollowers(int id) {
        return Collections.unmodifiableSet(
            INSTANCE.followers.getOrDefault(id, Collections.emptySet())
        );
    }

    public static Set<Integer> getFollowing(int id) {
        return Collections.unmodifiableSet(
            INSTANCE.following.getOrDefault(id, Collections.emptySet())
        );
    }
}
