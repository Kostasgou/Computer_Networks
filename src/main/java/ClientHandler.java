import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles one client connection. Implements the full command‑set required for
 * Phase 1 (follow‑graph, requests, upload, profile access).
 */
class ClientHandler extends Thread {

    private final Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream  in;
    private Integer clientId = null;          // null until LOGIN
    
    /*──────────────────────────── ctor / run ───────────────────────────*/

    ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in  = new ObjectInputStream(socket.getInputStream());

            while (true) {
                Object cmdObj = in.readObject();
                if (!(cmdObj instanceof String cmd)) break;   // connection closed

                String resp;
                switch (cmd) {
                    case "SIGNUP" -> resp = handleSignup((String) in.readObject());
                    case "LOGIN"  -> resp = handleLogin((String) in.readObject());
                    /*──── follow mechanics ───*/
                    case "FOLLOW"          -> resp = handleFollow((String) in.readObject());
                    case "FOLLOW_REQUEST"  -> resp = handleFollowRequest((String) in.readObject());
                    case "VIEW_REQUESTS"   -> resp = handleViewRequests();
                    case "RESPOND_REQUEST" -> resp = handleRespondRequest((String) in.readObject(),
                                                                             (String) in.readObject());
                    case "UNFOLLOW"        -> resp = handleUnfollow((String) in.readObject());
                    case "VIEW_FOLLOWERS"  -> resp = handleViewFollowers();
                    case "VIEW_FOLLOWING"  -> resp = handleViewFollowing();
                    /*──── profile & media ───*/
                    case "UPLOAD"          -> resp = handleUpload();
                    case "ACCESS_PROFILE" -> {
                        // 0) Πάρε το target
                        String tgtStr = (String) in.readObject();
                        int target = Integer.parseInt(tgtStr.trim());

                        // ΕΛΕΓΧΟΣ: ακολουθείς τον target;
                        boolean follows = Server.socialGraph.getFollowing(clientId).contains(target);
                        if (clientId != target && !follows) {
                            out.writeObject("DENY_PROFILE");
                            out.writeObject("Access denied: you do not follow client " + target);
                            out.flush();
                            continue;
                        }

                        // 1) Lock
                        String filename = "Profile_" + target + ".txt";
                        String fullPath = ProfileManager.getUserDir(target).resolve(filename).toString();
                        boolean granted = LockManager.get().requestLock(clientId.toString(), fullPath, this);

                        if (!granted) {
                            sendDiagnostic("FILE_LOCKED", "File " + filename + " is locked!");
                            out.writeObject("DENY_PROFILE");
                            out.writeObject("The file is locked!");
                            out.flush();
                        } else {
                            // 2) Επιτυχία → στείλε profile
                            List<String> lines = ProfileManager.readProfile(target);
                            out.writeObject("PROFILE_DATA");
                            out.writeObject(String.join("\n", lines));
                            out.flush();
                            // releaseLock θα έρθει από τον client explicit
                        }
                        continue;
                    }

                case "RELEASE_PROFILE" -> {
            String targetStr = (String) in.readObject();     // π.χ. "3"
            int target = Integer.parseInt(targetStr);
            String fn        = (String) in.readObject();     // π.χ. "Profile_3.txt"
            String fullPath  = ProfileManager
                                .getUserDir(target)       // εδώ χρησιμοποιείς το target, όχι το clientId
                                .resolve(fn)
                                .toString();
            LockManager.get().releaseLock(fullPath);
            out.writeObject("OK: RELEASED " + fn);
            out.flush();
            continue;
        }

                    case "SEARCH" -> {
                        // 1) Διαβάζουμε όνομα φωτογραφίας
                        String photoName = (String) in.readObject();
                        // 2) Διαβάζουμε την προτίμηση γλώσσας (en ή gr)
                        String langPref  = (String) in.readObject();
                        // 3) Καλούμε την ανανεωμένη μέθοδο
                        resp = handleSearch(photoName, langPref);
                    }

                    case "DOWNLOAD" -> {
                        String photoName = (String) in.readObject();
                        String langPref  = (String) in.readObject();
                        handleDownload(photoName, langPref);
                        // handleDownload κάνει handshake + frames + caption + στο τέλος out.writeObject("OK")
                        continue;  
                    }
                    case "REPOST"          -> resp = handleRepost();
                    case "COMMENT"         -> resp = handleComment();

                    case "FETCH_OTHERS" -> {
                        // Φορτώνουμε όλο το Others_<clientId>.txt και το επιστρέφουμε σαν ένα string
                        Path others = ProfileManager.getUserDir(clientId)
                                .resolve("Others_" + clientId + ".txt");
                        String content = "";
                        if (Files.exists(others)) {
                            content = String.join("\n", Files.readAllLines(others));
                        }
                        resp = content;
                    }
                    case "GRANT_PERMISSION" -> {
                        int grantee = Integer.parseInt((String) in.readObject());
                        boolean ok = SocialGraph.grantPermission(clientId, grantee);
                        resp = ok
                            ? "OK: Permission granted to " + grantee
                            : "ERROR: Permission was already granted";
                        send(resp);
                        continue;
                    }
                    case "REVOKE_PERMISSION" -> {
                        int grantee = Integer.parseInt((String) in.readObject());
                        boolean ok = SocialGraph.revokePermission(clientId, grantee);
                        resp = ok
                            ? "OK: Permission revoked for " + grantee
                            : "ERROR: No such permission to revoke";
                        send(resp);
                        continue;
                    }
                    case "LIST_PERMISSIONS" -> {
                        Set<Integer> perms = SocialGraph.getExtraPermissions(clientId);
                        resp = "Your extra permissions: " + perms;
                    }




                    /*──── session control ───*/
                    case "LOGOUT" -> resp = handleLogout();
                    case "QUIT"   -> { send("BYE"); return; }
                    case "DELETE_ACCOUNT" -> resp = handleDeleteAccount();
                    default        -> resp = "ERROR: Unknown command";
                }
                send(resp);
            }
        } catch (Exception ignored) {
        } finally { cleanup(); }
    }

    /*──────────────────── signup / login / logout ─────────────────────*/

    private String handleSignup(String idStr) {
        if (idStr == null || idStr.isBlank()) return "ERROR: No clientID";
        try {
            int id = Integer.parseInt(idStr.trim());
            if (!Server.registeredClients.add(id)) return "ERROR: id exists";
            boolean ok = Server.socialGraph.addClient(id);
            if (!ok) return "ERROR: id exists in graph";
            ProfileManager.ensureUserDir(id);
            return "OK: SIGNED UP " + id;
        } catch (NumberFormatException e) {
            return "ERROR: invalid id";
        } catch (IOException ioe) {
            return "ERROR: cannot create user dir";
        }
    }

    

    public void sendProfileData(int targetId) throws IOException {
    List<String> lines = ProfileManager.readProfile(targetId);
    out.writeObject("PROFILE_DATA");
    out.writeObject(String.join("\n", lines));
    out.flush();
}

    

    private String handleLogin(String idStr) {
        if (idStr == null) return "ERROR: No ID";
        try {
            int id = Integer.parseInt(idStr.trim());
            if (!Server.registeredClients.contains(id)) return "ERROR: not signed up";
           // if (Server.loggedInClients.contains(id) || clientId != null) return "ERROR: already logged in";
            ProfileManager.ensureUserDir(id);    // idempotent
            clientId = id;
            Server.loggedInClients.add(id);
            Server.activeClients.put(id, this);
            return "Welcome client " + id + "\nOK: LOGGED IN " + id;
        } catch (NumberFormatException e) {
            return "ERROR: invalid id";
        } catch (IOException ioe) {
            return "ERROR: cannot access user dir";
        }
    }

    private String handleLogout() {
        if (clientId == null) return "ERROR: Not logged in";
        Server.loggedInClients.remove(clientId);
        Server.activeClients.remove(clientId);
        int old = clientId;
        clientId = null;
        return "OK: LOGGED OUT " + old;
    }

    /*─────────────────────── follow mechanics ─────────────────────────*/

    private String handleFollow(String idStr) {
        if (clientId == null) return "ERROR: Please LOGIN first";
        try {
            int target = Integer.parseInt(idStr.trim());
            if (!Server.registeredClients.contains(target)) return "ERROR: No such id";
            if (target == clientId) return "ERROR: cannot follow yourself";
            boolean added = Server.socialGraph.addFollow(clientId, target);
            return added ? "OK: FOLLOWED " + target : "ERROR: Already following " + target;
        } catch (NumberFormatException e) {
            return "ERROR: invalid id";
        }
    }

    private String handleFollowRequest(String targetStr) {
        if (clientId == null) return "ERROR: Please LOGIN first";
        int target;
        try { target = Integer.parseInt(targetStr.trim()); }
        catch (NumberFormatException e) { return "ERROR: invalid id"; }

        if (target == clientId) return "ERROR: cannot follow yourself";
        if (!Server.registeredClients.contains(target)) return "ERROR: no such id";
        if (Server.socialGraph.getFollowing(clientId).contains(target))
            return "ERROR: already following " + target;

        Server.pendingRequests.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(clientId);
        notifyUser(target, "FOLLOW_REQUEST from " + clientId);
        return "OK: Follow request sent to " + target;
    }

    private String handleViewRequests() {
        if (clientId == null) return "ERROR: Please LOGIN first";
        List<Integer> reqs = Server.pendingRequests.getOrDefault(clientId, List.of());
        return "Pending follow-requests: " + reqs;
    }

    /** decision = MUTUAL | ONEWAY | REJECT */
    private String handleRespondRequest(String fromStr, String decision) {
        if (clientId == null) return "ERROR: Please LOGIN first";
        int fromId;
        try { fromId = Integer.parseInt(fromStr.trim()); }
        catch (NumberFormatException e) { return "ERROR: invalid id"; }

        List<Integer> list = Server.pendingRequests.getOrDefault(clientId, new CopyOnWriteArrayList<>());
        if (!list.remove(Integer.valueOf(fromId))) return "ERROR: no such pending request";

        switch (decision) {
            case "MUTUAL" -> {
                Server.socialGraph.addFollow(clientId, fromId);
                Server.socialGraph.addFollow(fromId, clientId);
                notifyUser(fromId, "Follow request accepted (MUTUAL) by " + clientId);
                System.out.println("Server: Created MUTUAL follow between " +
                        clientId + " and " + fromId);
                return "OK: Mutual follow with " + fromId;
            }
            case "ONEWAY" -> {
                Server.socialGraph.addFollow(fromId, clientId);
                notifyUser(fromId, "Follow request accepted by " + clientId);
                System.out.println("Server: Created ONEWAY follow from " +
                        fromId + " to " + clientId);
                return "OK: Accepted follow from " + fromId;
            }
            case "REJECT" -> {
                notifyUser(fromId, "Follow request REJECTED by " + clientId);
                System.out.println("Server: Follow request from " + fromId +
                        " REJECTED by " + clientId);
                return "OK: Rejected follow from " + fromId;
            }
            default -> {
                list.add(fromId);   // push back
                return "ERROR: invalid decision";
            }
        }
    }

    private String handleUnfollow(String idStr) {
        if (clientId == null) return "ERROR: Please LOGIN first";
        try {
            int target = Integer.parseInt(idStr.trim());
            boolean removed = Server.socialGraph.removeFollow(clientId, target);
            if (removed) {
                // διαγνωστικό μήνυμα στο server
                System.out.println("Server: Client " + clientId +
                        " unfollowed client " + target);
                return "OK: UNFOLLOWED " + target;
            } else {
                return "ERROR: not following " + target;
            }
        } catch (NumberFormatException e) {
            return "ERROR: invalid id";
        }
    }

    private String handleViewFollowers() {
        if (clientId == null) return "ERROR: Please LOGIN first";
        Set<Integer> fl = Server.socialGraph.getFollowers(clientId);
        return "Your followers: " + fl;
    }

    private String handleViewFollowing() {
        if (clientId == null) return "ERROR: Please LOGIN first";
        Set<Integer> fw = Server.socialGraph.getFollowing(clientId);
        return "You are following: " + fw;
    }

    /*─────────────────────── profile & media ──────────────────────────*/

    /**
     * Robust handler: supports both old clients (filename, bytes) and new ones
     * (filename, caption, bytes).
     */
    private String handleUpload() {
        if (clientId == null) return "ERROR: Please LOGIN first";
        try {
            // 1) Διαβάζουμε filename και δύο captions
            String filename  = (String) in.readObject();
            String enCaption = (String) in.readObject();  // Αγγλικό caption ή null
            String grCaption = (String) in.readObject();  // Ελληνικό caption ή null
            byte[] data      = (byte[]) in.readObject();

            // 2) Βεβαιωνόμαστε ότι υπάρχει τουλάχιστον ένα caption
            if (enCaption == null && grCaption == null) {
                return "ERROR: Must provide at least one caption (en or gr)";
            }

            // 3) Αποθήκευση της εικόνας στον δίσκο
            ProfileManager.savePhoto(clientId, filename, data);

            // 4) Δημιουργούμε το entry με tags [en:] και [gr:]
            StringBuilder capEntry = new StringBuilder("posted " + filename +  " with caption:");
            if (enCaption != null) capEntry.append(" [en: ").append(enCaption).append("]");
            if (grCaption != null) capEntry.append(" [gr: ").append(grCaption).append("]");

            // 5) Ενημέρωση του προσωπικού προφίλ
            ProfileManager.appendToProfile(clientId, capEntry.toString());

            // 6) Ενημέρωση Others και ειδοποιήσεις στους followers
            String postEntry = clientId + " " + capEntry.toString();
            for (int follower : Server.socialGraph.getFollowers(clientId)) {
                ProfileManager.appendToOthers(follower, postEntry);
                notifyUser(follower, "NEW_POST from " + clientId + ": " + filename);
            }

            return "OK: Uploaded " + filename;
        } catch (Exception e) {
            return "ERROR: failed to save file";
        }
    }





    private String handleAccessProfile(String tgtStr) {
        if (clientId == null) return "ERROR: Please LOGIN first";
        int target;
        try { target = Integer.parseInt(tgtStr.trim()); }
        catch (NumberFormatException e) { return "ERROR: invalid id"; }

        boolean allowed = target == clientId ||
                           Server.socialGraph.getFollowing(clientId).contains(target);
        if (!allowed) return "ERROR: ACCESS DENIED";

        try {
            List<String> lines = ProfileManager.readProfile(target);
            return "--- Profile " + target + " ---\n" + String.join("\n", lines);
        } catch (IOException e) {
            return "ERROR: cannot read profile";
        }
    }




    private String handleSearch(String photoName, String langPref) {
        if (clientId == null) return "ERROR: Please LOGIN first";
        String query = photoName.trim();
        if (query.isEmpty())  return "ERROR: empty name";

        // pool = εαυτός + όσους ακολουθώ
        Set<Integer> pool = new HashSet<>(Server.socialGraph.getFollowing(clientId));
        pool.add(clientId);

        List<String> results = new ArrayList<>();
        for (int owner : pool) {
            try {
                List<String> prof = ProfileManager.readProfile(owner);
                // βρίσκουμε την τελευταία γραμμή που περιέχει το query
                Optional<String> lineOpt = prof.stream()
                        .filter(l -> l.contains(query))
                        .reduce((a, b) -> b);

                if (lineOpt.isPresent()) {
                    String full = lineOpt.get();
                    // επιλέγουμε μόνο αν υπάρχει το αντίστοιχο tag
                    String tag = langPref.equals("en") ? "[en:" : "[gr:";
                    if (full.contains(tag)) {
                        results.add(owner + " -> " + full);
                    }
                }
            } catch (IOException ignored) {}
        }

        return results.isEmpty()
                ? "NOT_FOUND"
                : String.join("\n", results);
    }




    /*──────────────────── 3‑way handshake + Stop‑and‑Wait ──────────────*/
    private String handleDownload(String photoName, String langPref) throws IOException, ClassNotFoundException {
        if (clientId == null) {
            return "ERROR: Please LOGIN first";
        }
        String name = photoName.trim();
        if (name.isEmpty()) {
            return "ERROR: empty name";
        }

        // 1) Βρες ποιοι έχουν αυτή τη photo
        Set<Integer> pool = new HashSet<>(Server.socialGraph.getFollowing(clientId));
        pool.add(clientId);
        List<Integer> owners = new ArrayList<>();
        for (Integer owner : pool) {
            try {
                if (ProfileManager.listPhotos(owner).contains(name)) {
                    owners.add(owner);
                }
            } catch (IOException ignored) {}
        }
        if (owners.isEmpty()) {
            return "ERROR: Photo not found";
        }
        Integer selectedOwner = owners.get(new Random().nextInt(owners.size()));

        // 2) Έλεγχος δικαιωμάτων: μόνο ο ίδιος ή mutual φίλοι
        
        Set<Integer> allowed = SocialGraph.getAllowedViewers(selectedOwner);
        if (!allowed.contains(clientId)) {
            return "ERROR: You do not have permission to download this photo";
        }

        // 2) Φορτώνουμε τα bytes
        byte[] data;
        try {
            Optional<byte[]> opt = ProfileManager.getPhoto(selectedOwner, name);
            if (opt.isEmpty()) return "ERROR: Photo not found";
            data = opt.get();
        } catch (IOException e) {
            return "ERROR: cannot read photo";
        }

        try {
            // ── 3-way handshake ────────────────────────────────────────────
            System.out.println("Server: [handshake] SYN → sending owner ID " + selectedOwner);
            out.writeObject("SYN");
    out.flush();
    String synAck = (String) in.readObject();               // περιμένουμε "SYN-ACK"
    if (!"SYN-ACK".equals(synAck)) return "ERROR: handshake";
    out.writeObject("ACK");
    out.flush();

    // ── Go-Back-N Data Transfer ──────────────────────────────────
/* το byte[] της εικόνας */;
    final int chunkSize   = 1024;
    final int totalChunks = (data.length + chunkSize - 1) / chunkSize;
    List<Frame> frames = new ArrayList<>();
    for (int i = 0; i < totalChunks; i++) {
        int start = i * chunkSize;
        int len   = Math.min(chunkSize, data.length - start);
        frames.add(new Frame(i, totalChunks,
                             Arrays.copyOfRange(data, start, start + len)));
    }

    final int windowSize = 3;
    int base = 0, nextSeq = 0;
    socket.setSoTimeout(2000);  // timeout 2s

    while (base < totalChunks) {
        // στείλε όσα χωράνε στο παράθυρο
        while (nextSeq < totalChunks && nextSeq < base + windowSize) {
            Frame f = frames.get(nextSeq);
            out.writeObject(f);
            out.flush();
            System.out.println("Server: Sent frame " + f.seq);
            nextSeq++;
        }

        try {
            Object resp = in.readObject();
            if (resp instanceof Integer ack) {
                System.out.println("Server: Received ACK=" + ack);
                if (ack > base) {
                    base = ack;
                }
            }
        } catch (SocketTimeoutException e) {
            // timeout → επαναμετάδοση από base
            System.out.println("Server: Timeout, resending from " + base);
            nextSeq = base;
        }
    }



            // 6) caption & ολοκλήρωση
            String fullCaption = getCaption(selectedOwner, name);
// τώρα διαλέγουμε ποιο tag κρατάμε
            String selectedCaption = "";
            Pattern enPat = Pattern.compile("\\[en: (.*?)]");
            Pattern grPat = Pattern.compile("\\[gr: (.*?)]");

            Matcher enM = enPat.matcher(fullCaption);
            Matcher grM = grPat.matcher(fullCaption);

            if (langPref.equalsIgnoreCase("en")) {
                if (enM.find()) {
                    selectedCaption = enM.group(1);
                } else if (grM.find()) {
                    selectedCaption = grM.group(1);  // fallback
                }
            } else { // "gr"
                if (grM.find()) {
                    selectedCaption = grM.group(1);
                } else if (enM.find()) {
                    selectedCaption = enM.group(1);  // fallback
                }
            }

            out.writeObject("CAPTION:" + selectedCaption);
            out.flush();


            // Μόλις ολοκληρωθεί επιτυχώς η μετάδοση:
            Server.recordDownload(name, clientId);
            socket.setSoTimeout(0);


            System.out.println("Server: Photo+text transmission completed successfully");
            return "OK";
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("Server: ERROR during transmission: " + e.getMessage());
            return "ERROR: transmission failed";
        }
    }



    private String getCaption(int owner, String photo) {
        try {
            return ProfileManager.readProfile(owner).stream()
                    .filter(l -> l.contains(photo) && l.contains("caption"))
                    .reduce((first, second) -> second)   // παίρνει το τελευταίο matching
                    .map(line -> {
                        // αφαιρώ timestamp και το "posted <photo>"
                        int idx = line.indexOf("caption");
                        return line.substring(idx - /* πριν από το 'with' */ 5);
                    })
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }


    private String handleDeleteAccount() {
        if (clientId == null) return "ERROR: Not logged in";
        Server.registeredClients.remove(clientId);
        Server.socialGraph.removeClient(clientId);
        // προαιρετικά: διαγραφή data dir
        System.out.println("Server: Deleted client " + clientId +
                " from social graph");
        int old = clientId; clientId = null;
        return "OK: Deleted client " + old;
    }

    private String handleRepost() {
        if (clientId == null) return "ERROR: Please LOGIN first";
        try {
            // 1) Δέξου τα στοιχεία: ownerId, filename, enCap, grCap
            int ownerId     = Integer.parseInt((String) in.readObject());
            String filename = (String) in.readObject();
            String enCap    = (String) in.readObject();
            String grCap    = (String) in.readObject();

            // ── Έλεγχος: πρέπει να ακολουθάει ο client τον owner ─────────
            if (!Server.socialGraph.getFollowing(clientId).contains(ownerId)) {
                return "ERROR: You must follow user " + ownerId + " to repost their posts";
            }

            // 2) Εντοπίστε το αρχικό post
            List<String> profile = ProfileManager.readProfile(ownerId);
            String record = profile.stream()
                    .filter(l -> l.contains("posted " + filename))
                    .reduce((first, last) -> last)
                    .orElse(null);
            if (record == null) {
                return "ERROR: Original post not found";
            }

            // 3) Αποθήκευσε το repost στο Profile του Α
            StringBuilder entry = new StringBuilder("reposted " + filename);
            if (enCap != null) entry.append(" [en: ").append(enCap).append("]");
            if (grCap != null) entry.append(" [gr: ").append(grCap).append("]");
            entry.append(" (owner ").append(ownerId).append(")");
            ProfileManager.appendToProfile(clientId, entry.toString());

            // 4) Ενημέρωσε το Others του follower (αν θέλεις)
            for (int follower : Server.socialGraph.getFollowers(clientId)) {
                ProfileManager.appendToOthers(follower,
                        clientId + " " + entry.toString());
                notifyUser(follower, "NEW_REPOST from " + clientId + ": " + filename);
            }

            return "OK: Reposted " + filename;
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: repost failed";
        }
    }





    private String handleComment() {
        if (clientId == null) return "ERROR: Please LOGIN first";
        try {
            // 1) Δέξου ownerId, filename, comment, commenterId
            int ownerId   = Integer.parseInt((String) in.readObject());
            String filename = (String) in.readObject();
            String comment  = (String) in.readObject();
            int commenter   = Integer.parseInt((String) in.readObject());

            // ── Έλεγχος: πρέπει να ακολουθάει ο commenter τον owner ─────────
            if (!Server.socialGraph.getFollowing(commenter).contains(ownerId)) {
                return "ERROR: You must follow user " + ownerId + " to comment their posts";
            }



            // 2) Έλεγχος ύπαρξης του post
            List<String> profile = ProfileManager.readProfile(ownerId);
            boolean exists = profile.stream()
                    .anyMatch(l -> l.contains("posted " + filename));
            if (!exists) {
                return "ERROR: Post not found for owner " + ownerId;
            }

            Set<Integer> allowed = SocialGraph.getAllowedViewers(ownerId);
            if (!allowed.contains(clientId)) {
                return "ERROR: You do not have permission to download this photo";
            }

            // 3) Εγγραφή στο Profile_Commenter
            String commentEntry = "commented on " + filename +  " (owner " + ownerId + "): " + comment;
            ProfileManager.appendToProfile(commenter, commentEntry);

            // 4) Εγγραφή στο Others του owner
            ProfileManager.appendToOthers(ownerId,
                    commenter + " " + commentEntry);
            notifyUser(ownerId, "NEW_COMMENT from " + commenter + ": " + filename);

            return "OK: Comment posted";
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: comment failed";
        }
    }





    /*──────────────────────── helpers ────────────────────────────────*/

    private void send(String s) throws IOException {
        out.writeObject(s);
        out.flush();
    }

    public void sendNotification(String note) {
        try { send("NOTIFY: " + note); } catch (IOException ignored) {}
    }

    private void notifyUser(int uid, String note) {
        Optional.ofNullable(Server.activeClients.get(uid))
                .ifPresent(h -> h.sendNotification(note));
    }

    private void cleanup() {
        try {
            if (clientId != null) {
                Server.loggedInClients.remove(clientId);
                Server.activeClients.remove(clientId);
            }
            socket.close();
        } catch (IOException ignored) {}
    }

    public void sendDiagnostic(String code, String message) {
            try {
            // Επέλεξε το format που ταιριάζει με το πώς διαβάζεις στον client.
            // Εδώ στέλνουμε ένα String "DIAG:<code>:<message>"
            out.writeObject("DIAG:" + code + ":" + message);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
