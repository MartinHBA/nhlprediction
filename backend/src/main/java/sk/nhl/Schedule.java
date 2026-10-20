package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

final class Schedule {
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    static String day(String day){LocalDate.parse(day);return day;}
    static ObjectNode fetch(String day){
        day(day);String url="https://api-web.nhle.com/v1/schedule/"+day;
        try{
            var response=HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).header("User-Agent","nhl-prediction/2.0").GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("NHL schedule returned "+response.statusCode());
            return template(parse(response.body()),day,Instant.now().toString(),url);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("NHL schedule interrupted",e);}catch(java.io.IOException e){throw new IllegalStateException("NHL schedule unavailable",e);}
    }
    static ObjectNode template(JsonNode payload,String day,String now,String url){
        JsonNode games=null;for(JsonNode entry:payload.path("gameWeek"))if(text(entry,"date").equals(day))games=entry.path("games");
        if(games==null)throw new IllegalArgumentException("NHL response does not contain requested day");
        ArrayNode out=arr(),ids=arr();Set<String>seen=new HashSet<>();
        for(JsonNode game:games){
            if(game.path("gameType").asInt(2)!=2)continue;String id=text(game,"id");if(!seen.add(id))throw new IllegalArgumentException("Duplicate NHL game ID");
            ids.add(id);String state=text(game,"gameState"),status=List.of("FUT","PRE").contains(state)?"scheduled":List.of("OFF","FINAL").contains(state)?"final":"live";
            if(status.equals("scheduled")&&!Checks.time(text(game,"startTimeUTC")).isAfter(Checks.time(now)))status="live";
            ObjectNode fields=obj(),searches=obj();for(String p:Checks.PATHS)fields.set(p,check());for(String p:Checks.SEARCHES)searches.set(p,check());
            out.add(obj("game_id",id,"starts_at",text(game,"startTimeUTC"),"status",status,"home",team(text(game,"homeTeam.abbrev"),now,url),"away",team(text(game,"awayTeam.abbrev"),now,url),"markets",arr(),"checks",obj("fields",fields,"market_searches",searches)));
        }
        return obj("as_of",now,"synthetic_data",false,"expected_game_ids",ids,"evidence",arr(),"games",out);
    }
    static ObjectNode check(){return obj("status","not_checked","evidence_ids",arr(),"note","");}
    static ObjectNode source(String now,String url){return obj("url",url,"checked_at",now,"status","unverified");}
    static ObjectNode team(String name,String now,String url){
        ObjectNode t=obj("name",name);for(String period:List.of("prior","current")){ObjectNode stats=obj();Checks.STATS.forEach(f->stats.putNull(f));stats.set("source",source(now,url));t.set(period,stats);}
        t.set("goalie",obj("name","UNKNOWN","status","unknown","starts_current",0,"starts_prior",0,"gsax_per60_current",null,"gsax_per60_prior",null,"source",source(now,url)));
        ObjectNode roster=obj();Checks.ROSTER.forEach(f->roster.putNull(f));roster.put("quantified",false);roster.set("absences",arr());roster.set("source",source(now,url));t.set("roster",roster);t.set("context",obj("rest_days",null,"travel_timezones",null));return t;
    }
}
