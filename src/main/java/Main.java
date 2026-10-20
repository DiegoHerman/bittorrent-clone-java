import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
// import com.dampcake.bencode.Bencode; - available if you need it!

public class Main {
  private static final Gson gson = new Gson();

  public static void main(String[] args) throws Exception {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.err.println("Logs from your program will appear here!");
    
    String command = args[0];
    if("decode".equals(command)) {
      //  TODO: Uncomment the code below to pass the first stage
        String bencodedValue = args[1];
        Object decoded;
        try {
          decoded = decodeBencode(bencodedValue);
        } catch(RuntimeException e) {
          System.out.println(e.getMessage());
          return;
        }
        System.out.println(gson.toJson(decoded));

    } else {
      System.out.println("Unknown command: " + command);
    }

  }

  static Object decodeBencode(String bencodedString) {
    char firstChar = bencodedString.charAt(0);
    char lastChar = bencodedString.charAt(bencodedString.length()-1);
    if (Character.isDigit(firstChar)) {
      return decodeString(bencodedString);
    }
     else if(firstChar=='i'&&lastChar=='e') {
       return decodeInt(bencodedString);
     }
     else if(firstChar=='l'&&lastChar=='e') {
       return decodeList(bencodedString);
    }
     else {
      throw new RuntimeException("Only strings and integers are supported at the moment");
    }
  }

  private static String decodeString(String bencodedString){
    int firstColonIndex = 0;
    for (int i = 0; i < bencodedString.length(); i++) {
      if (bencodedString.charAt(i) == ':') {
        firstColonIndex = i;
        break;
      }
    }
    int length = Integer.parseInt(bencodedString.substring(0, firstColonIndex));
    return bencodedString.substring(firstColonIndex + 1, firstColonIndex + 1 + length);
  }

  private static Long decodeInt(String bencodedString) {
    return Long.parseLong(bencodedString.substring(1, bencodedString.indexOf("e")));
  }

  private static List<Object> decodeList(String bencodedString){
    List<Object> objects=new ArrayList<>();
    int listEnd=bencodedString.length()-1;
    int i=1;
    while(i<listEnd){
      if(Character.isDigit(bencodedString.charAt(i))){
        int firstColonIndex=0;
        for(int j=i;j<listEnd;j++){
          if(bencodedString.charAt(j)==':'){
            firstColonIndex=j;
            break;
          }
        }
        int stringLength=Integer.parseInt(bencodedString.substring(i,firstColonIndex));
        int endOfStringIndex=firstColonIndex+stringLength;
        objects.add(decodeString(bencodedString.substring(i,endOfStringIndex+1)));
        i=endOfStringIndex;
      }
      else if(bencodedString.charAt(i)=='i'){
        int closingChar=0;
        for(int j=i;j<listEnd;j++){
          if(bencodedString.charAt(j)=='e'){
            closingChar=j;
            break;
          }
        }
        objects.add(decodeInt(bencodedString.substring(i,closingChar+1)));
        i=closingChar;
      }
      else{
        throw new IllegalArgumentException("Unsupported type at index: "+i);
      }
      i++;
    }
    return objects;
  }
  
}
