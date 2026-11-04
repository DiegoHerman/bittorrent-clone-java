import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Asks the trackers listed in a .torrent file for peers.
 *
 * Supports HTTP/HTTPS trackers (BEP 3, compact responses per BEP 23) and UDP trackers (BEP 15).
 * Trackers are queried in parallel and the peers are merged and de-duplicated.
 *
 * The Bencode instance you pass in MUST be in byte mode (new Bencode(true)), because tracker
 * responses contain raw binary peer data that would be corrupted by UTF-8 decoding.
 *
 * Progress and errors are logged to stderr, so stdout stays clean for the peer list.
 */
public class TorrentPeers {

    private static final int TIMEOUT_MS = 5_000;
    private static final long UDP_PROTOCOL_ID = 0x41727101980L;   // magic constant from BEP 15

    public record TrackerResult(long seeders, long leechers, List<InetSocketAddress> peers) {}

    private final Bencode bencode;
    private final byte[] peerId;
    private final int port;
    private final Random random = new Random();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param bencode byte-mode Bencode instance (new Bencode(true))
     * @param peerId  exactly 20 bytes identifying this client
     * @param port    the port this client says it is listening on
     */
    public TorrentPeers(Bencode bencode, byte[] peerId, int port) {
        if (peerId.length != 20) {
            throw new IllegalArgumentException("peer id must be exactly 20 bytes, got " + peerId.length);
        }
        this.bencode = bencode;
        this.peerId = peerId.clone();
        this.port = port;
    }

    /**
     * Queries every tracker in the decoded torrent and returns the unique peers found.
     *
     * @param torrent  the decoded top-level .torrent dictionary
     * @param infoHash SHA-1 of the bencoded "info" dictionary (20 bytes)
     * @param left     total size of the content in bytes
     */
    public List<InetSocketAddress> discover(Map<String, Object> torrent, byte[] infoHash, long left) {
        List<String> trackers = trackerUrls(torrent);
        Set<InetSocketAddress> peers = new LinkedHashSet<>();
        if (trackers.isEmpty()) {
            System.err.println("No trackers found in torrent");
            return new ArrayList<>();
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(trackers.size(), 8));
        try {
            List<Callable<List<InetSocketAddress>>> tasks = new ArrayList<>();
            for (String tracker : trackers) {
                tasks.add(() -> {
                    try {
                        TrackerResult result = announce(tracker, infoHash, left);
                        System.err.printf("OK    %-50s seeders=%d leechers=%d peers=%d%n",
                                tracker, result.seeders(), result.leechers(), result.peers().size());
                        return result.peers();
                    } catch (Exception e) {
                        System.err.printf("FAIL  %-50s %s%n", tracker, e);
                        return List.<InetSocketAddress>of();
                    }
                });
            }
            for (Future<List<InetSocketAddress>> future : pool.invokeAll(tasks)) {
                try {
                    peers.addAll(future.get());
                } catch (ExecutionException ignored) {
                    // tasks catch their own errors, nothing to do here
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(peers);
    }

    /** Announces to a single tracker URL (udp://, http:// or https://). */
    public TrackerResult announce(String tracker, byte[] infoHash, long left)
            throws IOException, InterruptedException {
        URI uri = URI.create(tracker);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        return switch (scheme) {
            case "udp" -> announceUdp(uri, infoHash, left);
            case "http", "https" -> announceHttp(tracker, infoHash, left);
            default -> throw new IOException("unsupported tracker scheme: " + scheme);
        };
    }

    // ---------------------------------------------------------------- UDP tracker (BEP 15)

    private TrackerResult announceUdp(URI uri, byte[] infoHash, long left) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            InetSocketAddress tracker = new InetSocketAddress(uri.getHost(), uri.getPort());

            // Step 1: connect. Magic constant, action 0, random transaction id (16 bytes).
            int connectTx = random.nextInt();
            ByteBuffer connect = ByteBuffer.allocate(16);
            connect.putLong(UDP_PROTOCOL_ID).putInt(0).putInt(connectTx);
            socket.send(new DatagramPacket(connect.array(), connect.capacity(), tracker));

            ByteBuffer reply = receive(socket, 16);
            int action = reply.getInt();
            if (action != 0 || reply.getInt() != connectTx) {
                throw new IOException("bad connect response (action " + action + ")");
            }
            long connectionId = reply.getLong();

            // Step 2: announce (98 bytes).
            int announceTx = random.nextInt();
            ByteBuffer request = ByteBuffer.allocate(98);
            request.putLong(connectionId)
                   .putInt(1)                   // action: announce
                   .putInt(announceTx)
                   .put(infoHash)               // 20 bytes
                   .put(peerId)                 // 20 bytes
                   .putLong(0)                  // downloaded
                   .putLong(left)               // left
                   .putLong(0)                  // uploaded
                   .putInt(2)                   // event: 2 = started
                   .putInt(0)                   // ip: 0 = use sender address
                   .putInt(random.nextInt())    // key
                   .putInt(-1)                  // num_want: -1 = tracker default
                   .putShort((short) port);
            socket.send(new DatagramPacket(request.array(), request.capacity(), tracker));

            reply = receive(socket, 8);
            action = reply.getInt();
            int tx = reply.getInt();
            if (action == 3) {   // error packet: the rest is a text message
                byte[] message = new byte[reply.remaining()];
                reply.get(message);
                throw new IOException("tracker error: " + new String(message, StandardCharsets.UTF_8));
            }
            if (action != 1 || tx != announceTx || reply.remaining() < 12) {
                throw new IOException("bad announce response (action " + action + ")");
            }
            reply.getInt();                      // interval (seconds until next announce)
            int leechers = reply.getInt();
            int seeders = reply.getInt();

            List<InetSocketAddress> peers = new ArrayList<>();
            parseCompactPeers(reply, peers);     // remaining bytes: 6 per peer
            return new TrackerResult(seeders, leechers, peers);
        }
    }

    /** Receives one datagram as a ByteBuffer positioned at 0, at least minLen bytes long. */
    private static ByteBuffer receive(DatagramSocket socket, int minLen) throws IOException {
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);   // throws SocketTimeoutException if the tracker is silent
        if (packet.getLength() < minLen) {
            throw new IOException("short response (" + packet.getLength() + " bytes)");
        }
        return ByteBuffer.wrap(buffer, 0, packet.getLength());
    }

    // ---------------------------------------------------------------- HTTP tracker (BEP 3 / BEP 23)

    private TrackerResult announceHttp(String announceUrl, byte[] infoHash, long left)
            throws IOException, InterruptedException {
        String url = announceUrl + (announceUrl.contains("?") ? "&" : "?")
                + "info_hash=" + percentEncode(infoHash)
                + "&peer_id=" + percentEncode(peerId)
                + "&port=" + port
                + "&uploaded=0&downloaded=0&left=" + left
                + "&compact=1&event=started&numwant=50";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        Map<String, Object> body = bencode.decode(response.body(), Type.DICTIONARY);
        if (body.containsKey("failure reason")) {
            throw new IOException("tracker error: " + asString(body.get("failure reason")));
        }

        List<InetSocketAddress> peers = new ArrayList<>();
        if (body.get("peers") instanceof ByteBuffer compact) {   // compact form: 6 bytes per peer
            parseCompactPeers(compact.duplicate(), peers);
        }
        long seeders = body.get("complete") instanceof Number n ? n.longValue() : -1;
        long leechers = body.get("incomplete") instanceof Number n ? n.longValue() : -1;
        return new TrackerResult(seeders, leechers, peers);
    }

    /** info_hash and peer_id are raw bytes, so they must be percent-encoded byte by byte. */
    static String percentEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    /** Compact peer list: repeated [4 bytes IPv4][2 bytes port], big-endian. */
    private static void parseCompactPeers(ByteBuffer buffer, List<InetSocketAddress> out) throws IOException {
        while (buffer.remaining() >= 6) {
            byte[] ip = new byte[4];
            buffer.get(ip);
            int port = Short.toUnsignedInt(buffer.getShort());
            out.add(new InetSocketAddress(InetAddress.getByAddress(ip), port));
        }
    }

    /** "announce" plus every URL in "announce-list" (a list of tiers, each a list of URLs), de-duplicated. */
    static List<String> trackerUrls(Map<String, Object> torrent) {
        Set<String> urls = new LinkedHashSet<>();
        if (torrent.get("announce") != null) {
            urls.add(asString(torrent.get("announce")));
        }
        if (torrent.get("announce-list") instanceof List<?> tiers) {
            for (Object tier : tiers) {
                for (Object url : (List<?>) tier) {
                    urls.add(asString(url));
                }
            }
        }
        return new ArrayList<>(urls);
    }

    /** Works whether the bencode library is in byte mode (ByteBuffer) or string mode (String). */
    private static String asString(Object value) {
        if (value instanceof String s) {
            return s;
        }
        ByteBuffer buffer = ((ByteBuffer) value).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
