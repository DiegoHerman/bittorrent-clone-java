import com.dampcake.bencode.Bencode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.client.RestClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
// import com.dampcake.bencode.Bencode; - available if you need it!

public class Main {
  private static final Gson GSON = new Gson();
  private static final Bencode BENCODE=new Bencode(true);
  private static final String PEER_ID="-JD1001-123456799012";
  private static final int PEER_PORT=8082;

  public static void main(String[] args) throws Exception {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.err.println("Logs from your program will appear here!");
    
    String command = args[0];
    switch(command){
        case "decode"->processDecode(args);
        case "info"->processInfo(args);
        case "peers"->processPeers(args);
        default->throw new IllegalArgumentException("Unsupported command");
    }
  }

  private static void processDecode(String[] args){
      String bencodedValue = args[1];
      Object decoded;
      try {
          decoded = decodeBencode(bencodedValue);
      } catch(RuntimeException e) {
          System.out.println(e.getMessage());
          return;
      }
      System.out.println(GSON.toJson(decoded));
  }

  private static void processInfo(String[] args) throws IOException, NoSuchAlgorithmException {
      String torrentFile=args[1];
      byte[] fileBytes= Files.readAllBytes(Path.of(torrentFile));
      Map<String,Object> decoded=BENCODE.decode(fileBytes,Type.DICTIONARY);
      Map<String,Object> info=(Map<String,Object>)decoded.get("info");

      byte[] bencodedInfo=BENCODE.encode(info);
      MessageDigest sha1= MessageDigest.getInstance("SHA-1");
      byte[] infohash=sha1.digest(bencodedInfo);
      ByteBuffer piecesBuffer = ((ByteBuffer) info.get("pieces")).duplicate();
      byte[] pieces = new byte[piecesBuffer.remaining()];
      piecesBuffer.get(pieces);

      System.out.println("announce-list: "+GSON.toJson(convertByteBufferToObject(decoded.get("announce-list"))));
      System.out.println("infohash: "+HexFormat.of().formatHex(infohash));
      System.out.println("Piece Hashes:");
      for (int i = 0; i < pieces.length; i += 20) {
          StringBuilder pieceHex = new StringBuilder();
          for (int j = i; j < i + 20; j++) pieceHex.append(String.format("%02x", pieces[j]));
          System.out.println(pieceHex);
      }
  }

  private static void processPeers(String[] args) throws IOException, NoSuchAlgorithmException {
      byte[] fileBytes=Files.readAllBytes(Path.of(args[1]));
      Map<String,Object> decoded=BENCODE.decode(fileBytes,Type.DICTIONARY);

      Map<String,Object> info=(Map<String,Object>)decoded.get("info");
      byte[] bencodedInfo=BENCODE.encode(info);
      MessageDigest sha1= MessageDigest.getInstance("SHA-1");
      byte[] infohash=sha1.digest(bencodedInfo);

      // Tracker work lives in TorrentPeers.java: HTTP and UDP trackers, queried in parallel.
      TorrentPeers torrentPeers=new TorrentPeers(BENCODE,PEER_ID.getBytes(StandardCharsets.US_ASCII),PEER_PORT);
      List<InetSocketAddress> peers=torrentPeers.discover(decoded,infohash,getTotalLength(info));

      System.out.println("Peers: ");
      for (InetSocketAddress peer : peers) {
          System.out.println(peer.getAddress().getHostAddress()+":"+peer.getPort());
      }
  }

  static Object decodeBencode(String bencodedString) {

    char firstChar = bencodedString.charAt(0);
    char lastChar = bencodedString.charAt(bencodedString.length()-1);
    if (Character.isDigit(firstChar)) {
      return BENCODE.decode(bencodedString.getBytes(StandardCharsets.UTF_8), Type.STRING);
    }
     else if(firstChar=='i'&&lastChar=='e') {
       return BENCODE.decode(bencodedString.getBytes(StandardCharsets.UTF_8), Type.NUMBER);
     }
     else if(firstChar=='l'&&lastChar=='e') {
       return BENCODE.decode(bencodedString.getBytes(StandardCharsets.UTF_8), Type.LIST);
    }
     else if(firstChar=='d'&&lastChar=='e'){
       return BENCODE.decode(bencodedString.getBytes(StandardCharsets.UTF_8), Type.DICTIONARY);
    }
     else {
      throw new RuntimeException("Only strings,integers,lists and dictionaries are supported at the moment");
    }
  }

    public static Object convertByteBufferToObject(Object value) {

        // Convert ByteBuffer to String
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate();

            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);

            return new String(bytes, StandardCharsets.UTF_8);
        }

        // Recursively convert Map values
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(
                        String.valueOf(entry.getKey()),
                        convertByteBufferToObject(entry.getValue())
                );
            }

            return result;
        }

        // Recursively convert List elements
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();

            for (Object item : list) {
                result.add(convertByteBufferToObject(item));
            }

            return result;
        }

        // Numbers, booleans, null, etc. remain unchanged
        return value;
    }

    public static long getTotalLength(Map<String, Object> info) {

        // Single-file torrent
        if (info.containsKey("length")) {
            return (Long) info.get("length");
        }

        // Multi-file torrent
        List<Map<String, Object>> files =
                (List<Map<String, Object>>) info.get("files");

        long totalLength = 0;

        for (Map<String, Object> file : files) {
            totalLength += (Long) file.get("length");
        }

        return totalLength;
    }
}
