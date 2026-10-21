package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static sk.nhl.Json.*;

/** Official match results, kept entirely separate from the pregame research snapshot. */
final class GameScores {
    static final String BASE="https://api-web.nhle.com/v1/score/";
    final Function<String,JsonNode> fetch;
    GameScores(){this(url->parse(DataCollector.download(url).content()));}
    GameScores(Function<String,JsonNode> fetch){this.fetch=fetch;}
    ObjectNode collect(String day,JsonNode dataset,JsonNode previous,Instant now){
        String url=BASE+Schedule.day(day);JsonNode feed=fetch.apply(url);
        if(!text(feed,"currentDate").isBlank()&&!text(feed,"currentDate").equals(day))throw new IllegalArgumentException("Score feed date differs from requested NHL day");
        if(!feed.path("games").isArray())throw new IllegalArgumentException("NHL score feed has no games");
        Map<String,JsonNode> old=new LinkedHashMap<>();for(JsonNode score:previous.path("games"))old.put(text(score,"game_id"),score);
        ArrayNode results=arr(),warnings=arr();int matched=0;
        for(JsonNode game:dataset.path("games")){
            String id=text(game,"game_id");List<JsonNode> events=feed.path("games").valueStream().filter(e->text(e,"id").equals(id)).toList();
            if(events.size()!=1){warnings.add(id+": missing or duplicate score event");if(old.containsKey(id))results.add(old.get(id));continue;}
            JsonNode event=events.getFirst();
            if(!text(event,"homeTeam.abbrev").equals(text(game,"home.name"))||!text(event,"awayTeam.abbrev").equals(text(game,"away.name"))||!Checks.time(text(event,"startTimeUTC")).equals(Checks.time(text(game,"starts_at")))){warnings.add(id+": score identity/start mismatch");if(old.containsKey(id))results.add(old.get(id));continue;}
            String raw=text(event,"gameState"),schedule=text(event,"gameScheduleState");
            String status=Set.of("OFF","FINAL").contains(raw)?"final":Set.of("LIVE","CRIT").contains(raw)?"live":"scheduled";
            if(Set.of("PPD","PST","CNCL","CANCELLED").contains(schedule))status="postponed";
            if(Set.of("live","final").contains(status)&&Checks.time(text(game,"starts_at")).isAfter(now)){warnings.add(id+": rejected future live/final result");if(old.containsKey(id))results.add(old.get(id));continue;}
            if(!Set.of("OFF","FINAL","LIVE","CRIT","FUT","PRE").contains(raw)&&status.equals("scheduled")){warnings.add(id+": unknown NHL state "+raw);if(old.containsKey(id))results.add(old.get(id));continue;}
            JsonNode h=event.path("homeTeam").path("score"),a=event.path("awayTeam").path("score");
            if((status.equals("live")||status.equals("final"))&&(!h.isIntegralNumber()||!a.isIntegralNumber()||h.asInt()<0||a.asInt()<0)){warnings.add(id+": missing/invalid score");if(old.containsKey(id))results.add(old.get(id));continue;}
            if(text(old.getOrDefault(id,obj()),"status").equals("final")&&!status.equals("final")){results.add(old.get(id));warnings.add(id+": ignored regression from final score");continue;}
            String end=text(event,"gameOutcome.lastPeriodType");if(end.isBlank())end=text(event,"periodDescriptor.periodType");
            ObjectNode score=obj("game_id",id,"status",status,"nhl_state",raw,"home_score",h.isNumber()?h:null,"away_score",a.isNumber()?a:null,
                "regulation_home_score",null,"regulation_away_score",null,"last_period_type",end,"period",event.path("periodDescriptor").path("number"),
                "clock",event.path("clock").path("timeRemaining"),"checked_at",now.toString(),"source_url",url);
            if(status.equals("final")&&h.asInt()!=a.asInt()){
                if(end.equals("REG")){score.put("regulation_home_score",h.asInt()).put("regulation_away_score",a.asInt());}
                else if(Set.of("OT","SO").contains(end)&&Math.abs(h.asInt()-a.asInt())==1){
                    int tied=Math.min(h.asInt(),a.asInt());score.put("regulation_home_score",tied).put("regulation_away_score",tied);
                }
            }
            results.add(score);matched++;
        }
        return obj("state",warnings.isEmpty()?"READY":"PARTIAL","checked_at",now.toString(),"source_url",url,"matched_games",matched,"warnings",warnings,"games",results);
    }
}
