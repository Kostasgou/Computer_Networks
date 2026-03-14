import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class LockManager {
    private static final LockManager instance = new LockManager();
    private static final long TIMEOUT_SECONDS = 30; // π.χ. 30s timeout

    private final ConcurrentHashMap<String, LockInfo> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private LockManager() {}

    public static LockManager get() {
        return instance;
    }

    // Request lock, returns true αν δόθηκε αμέσως, false αν μπαίνει στην ουρά.
    public synchronized boolean requestLock(String clientId, String filename, ClientHandler handler) {
        LockInfo info = locks.get(filename);
        if (info == null) {
            // δεν υπάρχει lock → το δημιουργούμε
            info = new LockInfo(clientId, handler);
            scheduleTimeout(filename, info);
            locks.put(filename, info);
            return true;
        } else {
            // υπάρχει ήδη → βάζουμε στην ουρά
            info.queue.add(new WaitingClient(clientId, handler));
            return false;
        }
    }

    // Απελευθερώνει lock (κατόπιν ρητού release ή timeout)
    public synchronized void releaseLock(String filename) {
    LockInfo info = locks.get(filename);
    if (info == null) return;

    // Cancel τρέχον timeout
    info.timeoutTask.cancel(false);

    // Επόμενος στη σειρά;
    WaitingClient next = info.queue.poll();
    if (next != null) {
        // Δημιουργούμε νέο lock για αυτόν
        LockInfo newInfo = new LockInfo(next.clientId, next.handler);
        scheduleTimeout(filename, newInfo);
        locks.put(filename, newInfo);

        // 1) Στέλνουμε diagnostic unlock
        next.handler.sendDiagnostic("FILE_UNLOCKED",
            "File " + filename + " is now unlocked for you.");

        // 2) Αυτόματη παράδοση PROFILE_DATA στον επόμενο
        int targetId = Integer.parseInt(filename.replaceAll("\\D+",""));
        try {
            next.handler.sendProfileData(targetId);
        } catch (IOException e) {
            e.printStackTrace();
        }

    } else {
        // Καθαρίζουμε το lock
        locks.remove(filename);
    }
}


    

    // Προγραμματίζει timeout warning + auto-release
    private void scheduleTimeout(String filename, LockInfo info) {
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            // Timeout expired: προειδοποίηση και release
            info.handler.sendDiagnostic("FILE_LOCK_TIMEOUT",
                "Timeout expired: file " + filename + " will be unlocked automatically.");
            releaseLock(filename);
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        info.timeoutTask = task;
    }

    // Εσωτερικές βοηθητικές κλάσεις
    private static class LockInfo {
        final String ownerId;
        final ClientHandler handler;
        final Queue<WaitingClient> queue = new ArrayDeque<>();
        ScheduledFuture<?> timeoutTask;
        LockInfo(String ownerId, ClientHandler handler) {
            this.ownerId = ownerId;
            this.handler = handler;
        }
    }
    private static class WaitingClient {
        final String clientId;
        final ClientHandler handler;
        WaitingClient(String clientId, ClientHandler handler) {
            this.clientId = clientId;
            this.handler = handler;
        }
    }
}
