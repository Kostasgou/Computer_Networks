import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Server {
    private static final int PORT        = 12345;
    private static final int MAX_THREADS = 8;
    private static final Path DATA_ROOT = Paths.get("data_server");
    // μετράει πόσες φορές κατέβηκε κάθε αρχείο
    static final ConcurrentMap<String, AtomicInteger> downloadCounts = new ConcurrentHashMap<>();
    // κρατάει το σύνολο των μοναδικών clientIds που κατέβασαν το κάθε αρχείο
    static final ConcurrentMap<String, Set<Integer>> downloaders = new ConcurrentHashMap<>();


    // ── Shared structures ───────────────────────────────────────────────
    static final SocialGraph socialGraph = SocialGraph.getInstance();
    static final Set<Integer>                   registeredClients = ConcurrentHashMap.newKeySet();
    static final Set<Integer>                   loggedInClients   = ConcurrentHashMap.newKeySet();
    static final ConcurrentMap<Integer,ClientHandler> activeClients   = new ConcurrentHashMap<>();
    static final ConcurrentMap<Integer,List<Integer>> pendingRequests = new ConcurrentHashMap<>();

    public static void recordDownload(String filename, int clientId) {
        // αύξησε μετρητή
        downloadCounts.computeIfAbsent(filename, f -> new AtomicInteger())
                .incrementAndGet();
        // πρόσθεσε τον client στο σύνολο των downloader
        downloaders.computeIfAbsent(filename, f -> ConcurrentHashMap.newKeySet())
                .add(clientId);
    }



    public static void main(String[] args) {
        try {
            // (0) data/ root για profiles + photos
            Files.createDirectories(DATA_ROOT);

            // (1) φόρτωση SocialGraph.txt από classpath
            try (InputStream is = Server.class.getClassLoader()
                                              .getResourceAsStream("SocialGraph.txt")) {
                if (is == null) {
                    System.err.println("ERROR: SocialGraph.txt not found on classpath!");
                    return;
                }
                List<String> lines = new BufferedReader(new InputStreamReader(is))
                        .lines().collect(Collectors.toList());
                socialGraph.loadFromLines(lines);
            }
            System.out.println("Social graph loaded.");

            // μέσα στο main, π.χ. αμέσως μετά το load του SocialGraph:
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n=== Download Statistics ===");
                downloadCounts.entrySet().stream()
                        .sorted(Map.Entry.<String,AtomicInteger>comparingByValue(
                                        Comparator.comparingInt(AtomicInteger::get))
                                .reversed())
                        .forEach(e -> {
                            String file = e.getKey();
                            int cnt = e.getValue().get();
                            Set<Integer> users = downloaders.getOrDefault(file, Set.of());
                            System.out.printf("%s : %d downloads by clients %s%n",
                                    file, cnt, users);
                        });
                System.out.println("=== End Statistics ===");
            }));




            // ─────────────────────────────────────────────────────────────────
            // (2) αυτόματη εγγραφή χρηστών βάσει φακέλων που υπάρχουν ήδη στο data/
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(DATA_ROOT)) {
                for (Path userDir : ds) {
                    if (!Files.isDirectory(userDir)) continue;
                    String name = userDir.getFileName().toString();
                    try {
                        int id = Integer.parseInt(name);
                        // αν δεν υπάρχει ήδη στο graph/registeredClients, το προσθέτουμε
                        if (!registeredClients.contains(id)) {
                            registeredClients.add(id);
                            socialGraph.addClient(id);
                            System.out.println("Auto-registered existing user: " + id);
                        }
                        // βεβαιωνόμαστε ότι έχει profile+photos dirs
                        ProfileManager.ensureUserDir(id);
                    } catch (NumberFormatException | IOException e) {
                        System.err.println("Ignoring non-numeric or bad dir: " + name);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            // ─────────────────────────────────────────────────────────────────

            // (3) thread-pool ≤ 8 νήματα
            ExecutorService pool = Executors.newFixedThreadPool(MAX_THREADS);

            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.printf("Server listening on *:%d (pool=%d)%n", PORT, MAX_THREADS);
                while (true) {
                    Socket s = serverSocket.accept();
                    pool.execute(new ClientHandler(s));
                }
            }
        } catch (IOException e) {
            System.err.println("FATAL: server crashed");
            e.printStackTrace();
        }
    }
}
