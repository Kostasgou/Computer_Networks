// Παράδειγμα Frame.java στην ίδια package
import java.io.Serializable;

public class Frame implements Serializable {
    public final int seq;         // αρίθμηση από 0
    public final int total;       // συνολικά frames
    public final byte[] chunk;    // payload

    public Frame(int seq, int total, byte[] chunk) {
        this.seq = seq;
        this.total = total;
        this.chunk = chunk;
    }
}
