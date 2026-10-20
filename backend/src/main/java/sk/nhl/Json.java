package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

final class Json {
    static final ObjectMapper M = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static JsonNode parse(String s) { try { return M.readTree(s); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON"); } }
    static JsonNode resource(String name) { try(var in=Json.class.getResourceAsStream("/"+name)) { return M.readTree(in); } catch(Exception e) { throw new IllegalStateException(name,e); } }
    static String resourceText(String name) { try(var in=Json.class.getResourceAsStream("/"+name)) { return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); } catch(Exception e) { throw new IllegalStateException(name,e); } }
    static ObjectNode obj(Object... pairs) { ObjectNode n=M.createObjectNode(); for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],M.valueToTree(pairs[i+1])); return n; }
    static ArrayNode arr() { return M.createArrayNode(); }
    static JsonNode at(JsonNode n,String path) { for(String p:path.split("\\."))n=n.path(p); return n; }
    static boolean missing(JsonNode n) { return n.isNull() || n.isMissingNode(); }
    static double number(JsonNode n,String p) { return at(n,p).asDouble(); }
    static String text(JsonNode n,String p) { return at(n,p).asText(); }
    static List<String> strings(JsonNode n) { List<String> a=new ArrayList<>(); n.forEach(x->a.add(x.asText())); return a; }
}
