import java.io.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.net.SocketTimeoutException;
import java.io.ByteArrayOutputStream;
public class Client {
    private static final String HOST = "localhost";
    private static final int    PORT = 12345;
    private static final Path CLIENT_ROOT = Paths.get("data_client");

    static void syncOthers(ObjectOutputStream out, ObjectInputStream in, int myId) throws Exception {
        out.writeObject("FETCH_OTHERS");
        out.flush();
        String all = (String) in.readObject();
        Path dir = CLIENT_ROOT.resolve(String.valueOf(myId));
        Files.createDirectories(dir);
        Path file = dir.resolve("Others_" + myId + ".txt");
        Files.writeString(
                file,
                all + (all.isEmpty() ? "" : System.lineSeparator()),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        System.out.println("» Others synchronized, " + (all.isEmpty() ? "empty" : all.split("\n").length + " lines"));
    }



    public static void main(String[] args) {


        try (Socket socket = new Socket(HOST, PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream());
             Scanner sc = new Scanner(System.in)) {
            System.out.println("Client: Connected to server at " + HOST + ":" + PORT);

            int myId = -1;

            boolean loggedIn = false;



            mainLoop:
            while (true) {
                if (!loggedIn) {
                    System.out.println("""
                            \n--- Main Menu ---
                            1) Signup
                            2) Login
                            3) Quit""");
                } else {
                    // μόλις συνδεθούμε επιτυχώς...
                    syncOthers(out, in, myId);
                    System.out.println("""
                            \n--- User Menu ---
                            4) Send Follow Request
                            5) Unfollow
                            6) View Followers
                            7) View Following
                            8) View Pending Requests
                            9) Respond to Request
                            10) Logout
                            11) Upload photo
                            12) Search photo
                            13) Download photo
                            14) Access profile
                            15) Delete account
                            16) Repost notified post
                            17) Comment on a post                        
                            18) Quit
                            19) Grant extra permission to a friend
                            20) Revoke extra permission from a friend""")
                            ;
                }

                System.out.print("> ");
                String ch = sc.nextLine().trim();

                switch (ch) {
                    case "1" -> {

                        out.writeObject("SIGNUP");
                        System.out.print("New id: ");
                        out.writeObject(sc.nextLine());
                    }
                    case "2" -> {

                        out.writeObject("LOGIN");
                        System.out.print("Id: ");
                        String idStr = sc.nextLine();
                        out.writeObject(idStr);
                        myId = Integer.parseInt(idStr.trim());
                    }

                    case "3" -> {

                        out.writeObject("QUIT");
                        break mainLoop;
                    }

                    case "4" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("FOLLOW_REQUEST");
                        System.out.print("Target id: ");
                        out.writeObject(sc.nextLine());
                    }
                    case "5" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("UNFOLLOW");
                        System.out.print("Target id: ");
                        out.writeObject(sc.nextLine());
                    }
                    case "6" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("VIEW_FOLLOWERS");
                    }
                    case "7" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("VIEW_FOLLOWING");
                    }
                    case "8" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("VIEW_REQUESTS");
                    }
                    case "9" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("RESPOND_REQUEST");
                        System.out.print("Requester id: ");
                        out.writeObject(sc.nextLine());
                        System.out.print("Decision (MUTUAL/ONEWAY/REJECT): ");
                        out.writeObject(sc.nextLine().toUpperCase());
                    }
                    case "10" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("LOGOUT");
                    }
                    case "11" -> {
                        if (!chk(loggedIn)) break;

                        // 1) Ζητάμε το πλήρες path της φωτογραφίας
                        System.out.print("Path to photo: ");
                        Path originalPath = Paths.get(sc.nextLine().trim());
                        if (!Files.exists(originalPath) || !Files.isRegularFile(originalPath)) {
                            System.out.println("File not found!");
                            break;
                        }

                        // 2) Αντιγράφουμε στον local client directory: data_client/<myId>/photos
                        String fn = originalPath.getFileName().toString();
                        Path saveDir  = CLIENT_ROOT.resolve(String.valueOf(myId)).resolve("photos");
                        Files.createDirectories(saveDir);
                        Path localCopy = saveDir.resolve(fn);
                        Files.copy(originalPath, localCopy, StandardCopyOption.REPLACE_EXISTING);

                        // 3) Ζητάμε captions
                        System.out.print("English caption (empty=none): ");
                        String enCap = sc.nextLine().trim();
                        System.out.print("Greek caption   (empty=none): ");
                        String grCap = sc.nextLine().trim();

                        // 4) Στέλνουμε UPLOAD στο server
                        out.writeObject("UPLOAD");
                        out.writeObject(fn);
                        out.writeObject(enCap.isEmpty() ? null : enCap);
                        out.writeObject(grCap.isEmpty() ? null : grCap);
                        out.writeObject(Files.readAllBytes(localCopy));
                        out.flush();

                        // 5) Παίρνουμε απάντηση από server
                        String resp = (String) in.readObject();
                        System.out.println("Server: " + resp);

                        // 6) Αν OK → γράφουμε τοπικά diagnostic entry στο Profile
                        if (resp.startsWith("OK")) {
                            Path profDir  = CLIENT_ROOT.resolve(String.valueOf(myId));
                            Files.createDirectories(profDir);
                            Path profFile = profDir.resolve("Profile_" + myId + ".txt");

                            // φτιάχνουμε το entry με τα caption tags
                            StringBuilder entry = new StringBuilder();
                            entry.append("[").append(java.time.Instant.now()).append("] ")
                                    .append("posted ").append(fn)
                                    .append(" with caption:");

                            if (enCap != null && !enCap.isEmpty()) {
                                entry.append(" [en: ").append(enCap).append("]");
                            }
                            if (grCap != null && !grCap.isEmpty()) {
                                entry.append(" [gr: ").append(grCap).append("]");
                            }

                            Files.writeString(
                                    profFile,
                                    entry.toString() + System.lineSeparator(),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND
                            );
                            System.out.println("Local profile updated: " + entry);
                        }


                        continue;
                    }


                    case "14" -> {
    if (!chk(loggedIn)) break;
    System.out.print("Target id: ");
    String targetId = sc.nextLine().trim();

    boolean gotProfile = false;
    while (!gotProfile) {
        // 1) Στείλε ACCESS_PROFILE
        out.writeObject("ACCESS_PROFILE");
        out.writeObject(targetId);
        out.flush();

        // 2) Διάβασε διαγνωστικά μέχρι να βρεις τον κωδικό
        String code;
        while (true) {
            Object o = in.readObject();
            if (!(o instanceof String s)) continue;
            if (s.startsWith("DIAG:")) {
                String[] parts = s.split(":", 3);
                System.out.println("[SERVER][" + parts[1] + "] " + parts[2]);
                if ("FILE_UNLOCKED".equals(parts[1])) {
                    System.out.println("File unlocked, retrying access...");
                }
                continue;
            }
            code = s;
            break;
        }

        // 3α) DENY_PROFILE
        if ("DENY_PROFILE".equals(code)) {
            String reason = (String) in.readObject();
            System.out.println("Access denied: " + reason);
            // τώρα περιμένουμε το FILE_UNLOCKED:
            continue;  // επιστρέφουμε στο πάνω loop για να ξαναστείλουμε
        }

        // 3β) PROFILE_DATA
        if ("PROFILE_DATA".equals(code)) {
            String profileData = (String) in.readObject();
            System.out.println("---- Profile of client " + targetId + " ----");
            System.out.println(profileData.isBlank() ? "(no posts yet)" : profileData);

            // Αποθήκευση τοπικά
            Path profDir  = CLIENT_ROOT.resolve(String.valueOf(myId));
            Files.createDirectories(profDir);
            Path profFile = profDir.resolve("Profile_" + targetId + ".txt");
            Files.writeString(profFile, profileData + System.lineSeparator(),
                              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("Local profile saved to: " + profFile);

            gotProfile = true;
        } else {
            System.out.println("Unexpected response code: " + code);
            break;
        }
    }

    // 4) Μόλις πήραμε profile, κρατάμε το lock μέχρι ENTER
    System.out.print("Press ENTER to release the profile lock for client " + targetId);
    sc.nextLine();

    // 5) Στέλνουμε RELEASE_PROFILE
    out.writeObject("RELEASE_PROFILE");
    out.writeObject(targetId);                           // π.χ. "3"
    out.writeObject("Profile_" + targetId + ".txt");     // π.χ. "Profile_3.txt"
    out.flush();
    String rel = (String) in.readObject();
    System.out.println("Server: " + rel);

    continue mainLoop;
}
                    case "15" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("DELETE_ACCOUNT");

                    }
                    case "16" -> {
                        if (!chk(loggedIn)) break;

                        // 1) Ρωτάμε για το ID του original owner
                        int originalOwner;
                        while (true) {
                            System.out.print("Enter original owner ID of the post: ");
                            String line = sc.nextLine().trim();
                            try {
                                originalOwner = Integer.parseInt(line);
                                break;
                            } catch (NumberFormatException e) {
                                System.out.println("Invalid ID, please enter a number.");
                            }
                        }

                        // 2) Ρωτάμε για το filename
                        System.out.print("Enter filename to repost (e.g. Screenshot (343).png): ");
                        String filename = sc.nextLine().trim();
                        if (filename.isEmpty()) {
                            System.out.println("Filename cannot be empty.");
                            break;
                        }

                        // 3) Captions για το repost
                        System.out.print("Enter English caption (empty = none): ");
                        String enCaption = sc.nextLine().trim();
                        if (enCaption.isEmpty()) enCaption = null;

                        System.out.print("Enter Greek caption (empty = none): ");
                        String grCaption = sc.nextLine().trim();
                        if (grCaption.isEmpty()) grCaption = null;

                        // 4) Στέλνουμε το REPOST request με ownerId + filename + captions
                        out.writeObject("REPOST");
                        out.writeObject(String.valueOf(originalOwner));
                        out.writeObject(filename);
                        out.writeObject(enCaption);
                        out.writeObject(grCaption);
                        out.flush();

                        // 5) Παίρνουμε και εκτυπώνουμε την απάντηση
                        String resp = (String) in.readObject();
                        System.out.println("Server: " + resp);

                        // 6) Αν OK, συγχρονίζουμε τοπικά (profile + others)
                        if (resp.startsWith("OK")) {
                            Path localDir = CLIENT_ROOT.resolve(String.valueOf(myId));
                            Files.createDirectories(localDir);

                            // Profile_<myId>.txt
                            Path profFile = localDir.resolve("Profile_" + myId + ".txt");
                            String entryProf = "[" + java.time.Instant.now() + "] reposted "
                                    + filename
                                    + (enCaption != null ? " [en: " + enCaption + "]" : "")
                                    + (grCaption != null ? " [gr: " + grCaption + "]" : "")
                                    +" (owner " + originalOwner + ")";
                            Files.writeString(
                                    profFile,
                                    entryProf + System.lineSeparator(),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND
                            );

                            // Others_<myId>.txt
                            Path othersFile = localDir.resolve("Others_" + myId + ".txt");
                            String entryOthers = "[" + java.time.Instant.now() + "] "
                                    + entryProf.substring(entryProf.indexOf("reposted"));
                            Files.writeString(
                                    othersFile,
                                    entryOthers + System.lineSeparator(),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND
                            );

                            System.out.println("Local repost synced: " + entryProf);
                        }

                        continue;
                    }



                    case "17" -> {
                        if (!chk(loggedIn)) break;

                        // 1) Owner ID
                        System.out.print("Enter owner client ID: ");
                        String ownerId = sc.nextLine().trim();

                        // 2) Filename
                        System.out.print("Post filename (e.g. Screenshot (343).png): ");
                        String filename = sc.nextLine().trim();

                        // 3) Το σχόλιο
                        System.out.print("Enter your comment: ");
                        String comment = sc.nextLine().trim();

                        // 4) Στέλνουμε COMMENT με ownerId, filename, comment, myId
                        out.writeObject("COMMENT");
                        out.writeObject(ownerId);
                        out.writeObject(filename);
                        out.writeObject(comment);
                        out.writeObject(String.valueOf(myId));
                        out.flush();

                        // 5) Παίρνουμε απάντηση
                        String resp = (String) in.readObject();
                        System.out.println("Server: " + resp);

                        // 6) Τοπικό συγχρονισμό αν ήταν OK
                        if (resp.startsWith("OK")) {
                            Path localDir = CLIENT_ROOT.resolve(String.valueOf(myId));
                            Files.createDirectories(localDir);

                            Path profFile = localDir.resolve("Profile_" + myId + ".txt");
                            String entry = "[" + java.time.Instant.now() + "] commented on "
                                    + filename + " (owner " + ownerId + "): " + comment;
                            Files.writeString(
                                    profFile,
                                    entry + System.lineSeparator(),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND
                            );
                            System.out.println("Local profile updated: " + entry);
                        }
                        continue;
                    }




                    case "18" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("QUIT");
                        break mainLoop;
                    }
                    default -> {
                        System.out.println("Invalid choice");
                        continue;
                    }
                    case "12" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("SEARCH");

                        // 1. Όνομα φωτογραφίας
                        System.out.print("Photo name: ");
                        String photoName = sc.nextLine().trim();
                        out.writeObject(photoName);

                        // 2. Έλεγχο εγκυρότητας για preferred language
                        String langPref;
                        while (true) {
                            System.out.print("Preferred caption language (en/gr): ");
                            langPref = sc.nextLine().trim().toLowerCase();
                            if (langPref.equals("en") || langPref.equals("gr")) break;
                            System.out.println("Invalid. Enter 'en' or 'gr'.");
                        }
                        out.writeObject(langPref);
                        out.flush();

                        // 3. Διαβάζουμε και τυπώνουμε το αποτέλεσμα
                        String resp = (String)in.readObject();
                        System.out.println("Server:\n" + resp);
                        continue mainLoop;
                    }


                    case "13" -> {
    if (!chk(loggedIn)) break;
    out.writeObject("DOWNLOAD");

    // 1) Photo name
    System.out.print("Photo name: ");
    String photoName = Path.of(sc.nextLine().trim()).getFileName().toString();
    out.writeObject(photoName);
    out.flush();

    // 2) Preferred language
    String langPref;
    do {
        System.out.print("Preferred language (en/gr): ");
        langPref = sc.nextLine().trim().toLowerCase();
    } while (!langPref.equals("en") && !langPref.equals("gr"));
    out.writeObject(langPref);
    out.flush();

    // ── 3-way handshake ───────────────────────────────────────────
    // α) λάβε "SYN" από server
    Object h1 = in.readObject();
    if (h1 instanceof String err && err.startsWith("ERROR")) {
        // ο server απάντησε άμεσα με σφάλμα
        System.out.println("Server: " + err);
        continue mainLoop;
    }
    if (!(h1 instanceof String syn) || !syn.equals("SYN")) {
        System.out.println("Unexpected handshake: " + h1);
        break;
    }
    System.out.println("Client: Received SYN");
    // β) στείλε "SYN-ACK"
    out.writeObject("SYN-ACK");
    out.flush();
    System.out.println("Client: Sent SYN-ACK");

    // γ) λάβε "ACK"
    Object h2 = in.readObject();
    if (!(h2 instanceof String s2) || !s2.equals("ACK")) {
        System.out.println("Unexpected handshake: " + h2);
        break;
    }
    System.out.println("Client: Received ACK");

    // ── Go-Back-N reception ─────────────────────────────────────
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    int expectedSeq = 0;
    Path saveDir = CLIENT_ROOT.resolve(String.valueOf(myId)).resolve("photos");
    Files.createDirectories(saveDir);

    while (true) {
        Object o = in.readObject();
        if (o instanceof Frame f) {
            System.out.println("Client: Received frame " + f.seq +
                               " (expected=" + expectedSeq + ")");
            if (f.seq == expectedSeq) {
                bos.write(f.chunk);
                expectedSeq++;
            } else {
                System.out.println("Client: Discarding frame " + f.seq);
            }
            // cumulative ACK
            out.writeObject(expectedSeq);
            out.flush();
            System.out.println("Client: Sent ACK=" + expectedSeq);

            if (expectedSeq == f.total) {
                Path savePath = saveDir.resolve(photoName);
                Files.write(savePath, bos.toByteArray(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                System.out.println("Client: Download saved to " + savePath);
                break;
            
            }
                            }
                              else if (o instanceof String cap && cap.startsWith("CAPTION:")) {
                                String caption = cap.substring("CAPTION:".length());
                                System.out.println("Client: Received caption.");

                                // Αποθήκευση εικόνας + λεζάντας μόνο τοπικά
                                saveDir  = CLIENT_ROOT
                                        .resolve(String.valueOf(myId))
                                        .resolve("photos");
                                Files.createDirectories(saveDir);
                                Path savePath = saveDir.resolve(photoName);
                                Files.write(savePath, bos.toByteArray());
                                Path textPath = saveDir.resolve(photoName + ".txt");
                                Files.writeString(textPath, caption);

                                System.out.println("Downloaded to: " + savePath.toAbsolutePath());
                                System.out.println("Caption: " + caption);
                                

                                break;

                            } else {
                                throw new IOException("Unexpected response: " + o);
                            }
                        }
                        String finish = (String) in.readObject();
                        System.out.println("Server final: " + finish);
                        continue;
                    }

                    case "19" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("GRANT_PERMISSION");
                        System.out.print("Grant permission to (client ID): ");
                        String grantee = sc.nextLine().trim();
                        out.writeObject(grantee);
                        out.flush();

                        // διαβάζουμε απάντηση
                        String resp = (String) in.readObject();
                        System.out.println("Server: " + resp);
                        continue;
                    }

                    case "20" -> {
                        if (!chk(loggedIn)) break;
                        out.writeObject("REVOKE_PERMISSION");
                        System.out.print("Revoke permission from (client ID): ");
                        String grantee = sc.nextLine().trim();
                        out.writeObject(grantee);
                        out.flush();

                        // διαβάζουμε απάντηση
                        String resp = (String) in.readObject();
                        System.out.println("Server: " + resp);
                        continue;
                    }


                }


                    out.flush();

                // διαβάζουμε απάντηση, αγνοώντας τυχόν NOTIFY
                String resp;
                while (true) {
                    Object o = in.readObject();
                    if (o instanceof String s) {
                        if (s.startsWith("NOTIFY: ")) {
                            System.out.println("\n*** " + s.substring(8));
                            continue;
                        }
                        resp = s;
                        break;
                    }
                }

                for (String line : resp.split("\n")) {
                    System.out.println("Server: " + line);
                }

                loggedIn = resp.contains("OK: LOGGED IN") || (loggedIn && !resp.contains("OK: LOGGED OUT"));

            }
            System.out.println("Client exiting.");
        } catch (Exception e) { e.printStackTrace(); }
    }
    private static boolean chk(boolean ok) { if (!ok) System.out.println("Please login first."); return ok; }
}
