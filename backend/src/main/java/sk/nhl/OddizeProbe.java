package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Manual, master-key-only diagnostic. Never imports odds or writes daily data. */
public final class OddizeProbe {
    private static final String BASE="https://oddize.com/api/v1";
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    record Upstream(int status,HttpHeaders headers,String body){}
    @FunctionalInterface interface Transport {Upstream send(HttpRequest request)throws IOException,InterruptedException;}

    // Azure registers routes in function-name order; this must precede the "http" catch-all.
    @FunctionName("diagnosticsOddize")
    public HttpResponseMessage run(@HttpTrigger(name="request",methods={HttpMethod.GET},authLevel=AuthorizationLevel.ADMIN,route="api/diagnostics/oddize")HttpRequestMessage<Optional<String>> request,ExecutionContext context){
        try{
            String mode=request.getQueryParameters().getOrDefault("mode","connection");
            String day=request.getQueryParameters().getOrDefault("day",LocalDate.now(ZoneId.of("America/New_York")).toString());
            ObjectNode result=probe(mode,day,System.getenv("ODDIZE_API_KEY"),outbound->{
                var response=HTTP.send(outbound,HttpResponse.BodyHandlers.ofString());
                return new Upstream(response.statusCode(),response.headers(),response.body());
            });
            return Functions.response(request,200,result);
        }catch(IllegalArgumentException e){return Functions.response(request,400,obj("error","Use mode=connection, usage or nhl and an optional valid day=YYYY-MM-DD."));}
    }

    static ObjectNode probe(String mode,String day,String apiKey,Transport transport){
        if(!Set.of("connection","usage","nhl").contains(mode))throw new IllegalArgumentException("Invalid mode");
        try{if(!day.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("Invalid day");LocalDate.parse(day);}
        catch(DateTimeException e){throw new IllegalArgumentException("Invalid day");}
        String key=apiKey==null?"":apiKey.trim();
        String url=BASE+switch(mode){case "usage"->"/account/usage";case "nhl"->"/odds/latest?sport=nhl&date="+day+"&limit=3";default->"/sports";};
        ObjectNode result=obj("mode",mode,"endpoint",url,"checked_at",Instant.now().toString(),"api_key_configured",!key.isEmpty(),"expected_credit_cost",mode.equals("nhl")?1:0,"writes_daily_data",false,"reachable",false,"success",false);
        long started=System.nanoTime();
        try{
            var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Accept","application/json").header("User-Agent","NHL-Analyzer-Online/Oddize-connection-test").GET();
            if(!key.isEmpty())builder.header("X-API-Key",key);
            Upstream response=transport.send(builder.build());
            result.put("reachable",true);result.put("oddize_http_status",response.status());
            ObjectNode credits=obj();
            for(String header:List.of("X-Credits-Cost","X-Credits-Remaining","X-Credits-Limit","X-RateLimit-Limit","X-RateLimit-Remaining")){
                response.headers().firstValue(header).ifPresent(value->{try{credits.put(header,Long.parseLong(value));}catch(NumberFormatException ignored){}});
            }
            result.set("limits",credits);
            if(response.status()!=200){
                String state=switch(response.status()){case 401->key.isEmpty()?"API_KEY_REQUIRED":"API_KEY_REJECTED";case 402->"CREDITS_EXHAUSTED";case 403->"ACCESS_DENIED";case 429->"RATE_LIMITED";default->response.status()>=300&&response.status()<400?"REDIRECT_NOT_FOLLOWED":"UPSTREAM_HTTP_ERROR";};
                result.put("result",state);
                result.put("note",key.isEmpty()?"Oddize responded from Azure. Add ODDIZE_API_KEY to the Function App settings to test authenticated API access.":"Oddize returned a non-success status. No response body or credentials are exposed by this diagnostic.");
                return result;
            }
            JsonNode body;
            try{body=parse(response.body());}catch(IllegalArgumentException e){result.put("result","INVALID_JSON_RESPONSE");return result;}
            if(body==null||!body.isObject()){result.put("result","UNEXPECTED_RESPONSE");return result;}
            ObjectNode summary=obj();
            if(mode.equals("connection")){
                if(!body.path("sports").isArray()){result.put("result","UNEXPECTED_RESPONSE");return result;}
                ArrayNode sports=arr();for(JsonNode sport:body.path("sports"))if(sport.path("sport").isTextual())sports.add(text(sport,"sport"));summary.set("sports",sports);
            }else if(mode.equals("usage")){
                if(!body.path("credits_remaining").isNumber()){result.put("result","UNEXPECTED_RESPONSE");return result;}
                for(String field:List.of("plan_id","credits_limit","credits_used","credits_remaining","rate_limit_rpm","period_start","period_end"))if(body.has(field))summary.set(field,body.path(field));
            }else{
                if(!body.path("events").isArray()||!text(body,"sport").equals("nhl")){result.put("result","UNEXPECTED_RESPONSE");return result;}
                ArrayNode events=arr();Set<String> types=new TreeSet<>(),books=new TreeSet<>();int quotes=0;
                for(JsonNode event:body.path("events")){
                    quotes+=event.path("odds").size();
                    for(JsonNode price:event.path("odds")){if(price.path("offer_type").isTextual())types.add(text(price,"offer_type"));if(price.path("book").isTextual())books.add(text(price,"book"));}
                    if(events.size()<3){ObjectNode sample=obj("event_id",event.path("event_id"),"away",event.path("team1"),"home",event.path("team2"),"starts_at",event.path("start_date"),"quote_count",event.path("odds").size());ArrayNode prices=arr();for(JsonNode price:event.path("odds")){if(prices.size()>=3)break;ObjectNode selected=obj();for(String field:List.of("book","offer_type","market","team","line","american_odds","timestamp"))if(price.has(field))selected.set(field,price.path(field));prices.add(selected);}sample.set("sample_prices",prices);events.add(sample);}
                }
                summary.put("events_returned",body.path("events").size());summary.put("quotes_returned",quotes);summary.set("market_codes",M.valueToTree(types));summary.set("bookmakers",M.valueToTree(books));summary.set("sample_events",events);
            }
            result.put("success",true);result.put("result","OK");result.set("summary",summary);return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();result.put("result","INTERRUPTED");return result;}
        catch(IOException e){result.put("result","NETWORK_ERROR");result.put("error_type",e.getClass().getSimpleName());return result;}
        finally{result.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);}
    }
}
