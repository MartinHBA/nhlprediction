package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Authenticated HTTPS, bounded requests and durable per-day response reuse. Never stores the key. */
final class OddizeClient {
    static final String BASE="https://oddize.com/api/v1";
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    static long nextRequestNanos;
    final Store store;final Clock clock;final OddizeProbe.Transport transport;final String apiKey,namespace;final boolean rateLimit;
    int requests,creditsUsed;Long creditsRemaining;
    OddizeClient(Store store,Clock clock){this(store,clock,System.getenv("ODDIZE_API_KEY"),request->{var r=HTTP.send(request,HttpResponse.BodyHandlers.ofString());return new OddizeProbe.Upstream(r.statusCode(),r.headers(),r.body());},"snapshot",true);}
    OddizeClient(Store store,Clock clock,String key,OddizeProbe.Transport transport,String namespace,boolean rateLimit){this.store=store;this.clock=clock;this.apiKey=key==null?"":key.trim();this.transport=transport;this.namespace=namespace;this.rateLimit=rateLimit;}
    static synchronized void pace()throws InterruptedException{
        long wait=nextRequestNanos-System.nanoTime();if(wait>0)Thread.sleep(Duration.ofNanos(wait));nextRequestNanos=System.nanoTime()+2_100_000_000L;
    }
    ObjectNode get(String day,String path,int cost){
        Schedule.day(day);if(!path.startsWith("/")||path.contains("..")||path.contains(":")||path.contains("#"))throw new IllegalArgumentException("Unsupported Oddize endpoint");
        String key="cache/oddize/v1/"+namespace+"/"+day+"/"+OAuth.hash(path)+".json";
        ObjectNode saved=store.get(key);if(saved!=null)return reuse(saved);
        return store.locked("oddize/request/"+namespace+"/"+day+"/"+OAuth.hash(path),()->{
            ObjectNode cached=store.get(key);if(cached!=null)return reuse(cached);
            if(apiKey.isBlank())throw new IllegalStateException("ODDIZE_API_KEY is not configured");
            if(creditsRemaining!=null&&creditsRemaining<cost)throw new IllegalStateException("Oddize credit allowance exhausted");
            try{
                if(rateLimit)pace();var request=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(20)).header("X-API-Key",apiKey).header("Accept","application/json").header("User-Agent","NHL-Analyzer-Online/4.0").GET().build();
                var response=transport.send(request);requests++;
                creditsRemaining=header(response.headers(),"X-Credits-Remaining",creditsRemaining);
                Long actual=header(response.headers(),"X-Credits-Cost",null);creditsUsed+=actual==null?cost:Math.toIntExact(actual);
                if(response.status()!=200)throw new IllegalStateException("Oddize HTTP "+response.status()+switch(response.status()){case 401->" (API key rejected)";case 402->" (credit allowance exhausted)";case 429->" (request rate limit)";default->"";});
                JsonNode body=parse(response.body());validate(path,body);
                ObjectNode value=obj("endpoint",BASE+path,"checked_at",clock.instant().toString(),"body",body,"credits_remaining",creditsRemaining);
                store.put(key,value);return value;
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Oddize request interrupted");}
            catch(java.io.IOException e){throw new IllegalStateException("Oddize network error: "+e.getClass().getSimpleName());}
        });
    }
    static void validate(String path,JsonNode body){
        if(body==null||!body.isObject()||!text(body,"sport").equals("nhl"))throw new IllegalStateException("Unexpected Oddize NHL response");
        if(path.startsWith("/events/")){
            String id=path.substring("/events/nhl/".length(),path.indexOf("/alts?")),type=path.substring(path.indexOf("alt_type=")+9);
            if(!text(body,"event_id").equals(id)||!text(body,"alt_type").equals(type)||!body.path("lines").isArray())throw new IllegalStateException("Oddize alternate response does not match request");
        }else if(!body.path("events").isArray()||path.startsWith("/props/")&&!text(body,"prop_type").equals("tt_goals"))throw new IllegalStateException("Unexpected Oddize catalogue response");
    }
    ObjectNode reuse(ObjectNode cached){if(cached.path("credits_remaining").canConvertToLong())creditsRemaining=cached.path("credits_remaining").asLong();return cached;}
    static Long header(HttpHeaders headers,String name,Long fallback){try{return headers.firstValue(name).map(Long::valueOf).orElse(fallback);}catch(NumberFormatException e){return fallback;}}
}
