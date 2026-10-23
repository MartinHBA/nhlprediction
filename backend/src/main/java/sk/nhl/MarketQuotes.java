package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Authoritative imported bookmaker offers only. No price approximations, model or picks. */
final class MarketQuotes {
    static final String VERSION="4.0.0-oddize-quotes";
    static final double MIN_ODDS=1.18,MAX_ODDS=1.35;
    static boolean inRange(double price){return Double.isFinite(price)&&price>=MIN_ODDS&&price<=MAX_ODDS;}
    static double round(double value,int digits){double scale=Math.pow(10,digits);return Math.round(value*scale)/scale;}
    static boolean fresh(JsonNode quote,Instant now){
        try{return text(quote,"source.status").equals("verified")&&quote.path("settlement_verified").asBoolean()
            &&!text(quote,"source.url").isBlank()&&!text(quote,"rules_url").isBlank()
            &&!Checks.time(text(quote,"source.checked_at")).isAfter(now)
            &&!Checks.time(text(quote,"source.checked_at")).isBefore(now.minusSeconds(7200))
            &&(!OddizeOdds.managed(quote)||!Checks.time(text(quote,"observed_at")).isAfter(now)&&!Checks.time(text(quote,"observed_at")).isBefore(now.minusSeconds(7200)));}
        catch(RuntimeException e){return false;}
    }
    static boolean suspended(JsonNode quote){return text(quote,"kind").equals("team_total")&&text(quote,"selection").equals("over")&&number(quote,"line")==2.5;}
    static boolean goalTotal(JsonNode quote){return List.of("total","team_total").contains(text(quote,"kind"));}
    static ArrayNode all(JsonNode game,Instant now){
        ArrayNode result=arr();if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now))return result;
        for(JsonNode raw:game.path("markets")){
            ObjectNode quote=raw.deepCopy();quote.put("odds_type","QUOTED");quote.put("price",number(raw,"odds"));
            ArrayNode blockers=arr();if(!fresh(raw,now))blockers.add("PRICE_OR_SETTLEMENT_UNVERIFIED_OR_STALE");
            if(!OddizeOdds.managed(raw)||!text(raw,"provider").equals("Oddize"))blockers.add("BACKEND_ODDIZE_PRICE_REQUIRED");
            for(String issue:strings(raw.path("quality_warnings")))blockers.add(issue);
            if(suspended(raw))blockers.add("SUSPENDED_TEAM_OVER_2_5");
            add(result,game,quote,blockers,text(raw,"id"));
        }
        return result;
    }
    static void add(ArrayNode result,JsonNode game,ObjectNode quote,ArrayNode blockers,String id){
        boolean band=inRange(number(quote,"price"));quote.put("in_preferred_band",band);
        if(!band)blockers.add("OUTSIDE_1_18_1_35");
        if(!List.of("regulation","official_final").contains(text(quote,"score_basis")))blockers.add("SETTLEMENT_BASIS_UNKNOWN");
        quote.set("blocking_reasons",blockers);quote.put("selectable",blockers.isEmpty());
        quote.put("candidate_id","quote-"+OAuth.hash(text(game,"game_id")+"|"+id+"|"+text(quote,"odds_type")).substring(0,22));result.add(quote);
    }
}
