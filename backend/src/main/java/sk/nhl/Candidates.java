package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Alternatives for the AI analyst, including negative model EV; never final selections. */
final class Candidates {
    static final Set<String> BLOCKING=Set.of("SUSPENDED_TEAM_OVER_2_5","UNVERIFIED_TEAM_INPUT","UNVERIFIED_ODDS",
        "MARKET_SETTLEMENT_NOT_VERIFIED","ODDS_STALE_OVER_2_HOURS","LAMBDA_OUT_OF_RANGE","CHECKLIST_INCOMPLETE");
    static ArrayNode build(JsonNode game,JsonNode quoted,JsonNode estimated,JsonNode projection){
        ArrayNode result=arr();List<JsonNode> all=new ArrayList<>();quoted.forEach(all::add);estimated.forEach(all::add);
        boolean sources=true;for(String side:List.of("home","away"))for(String group:List.of("prior","current","goalie","roster"))
            sources&=text(game,side+"."+group+".source.status").equals("verified");
        for(JsonNode raw:all){
            boolean estimate=text(raw,"odds_type").equals("ESTIMATED");double price=number(raw,estimate?"estimated_odds":"odds");
            if(!Recommendations.inRange(price))continue;
            ObjectNode c=raw.deepCopy();c.put("odds_type",estimate?"ESTIMATED":"QUOTED");c.put("price",price);
            c.put("model_ev",estimate?Engine.round(number(raw,"p_win")*price-1,5):number(raw,"ev"));
            c.put("conservative_ev",estimate?Engine.round(number(raw,"p_win_min")*price-1,5):number(raw,"scenario_ev_min"));
            c.put("model_positive_ev",number(c,"model_ev")>0);c.put("model_robust",number(c,"conservative_ev")>0);
            ArrayNode blockers=arr();for(String issue:strings(raw.path("issues")))if(BLOCKING.contains(issue))blockers.add(issue);
            if(!sources&&!strings(blockers).contains("UNVERIFIED_TEAM_INPUT"))blockers.add("UNVERIFIED_TEAM_INPUT");
            if(strings(projection.path("warnings")).stream().anyMatch(w->w.startsWith("PROJECTED_LAMBDA_OUTSIDE_PLAUSIBLE_RANGE"))&&!strings(blockers).contains("LAMBDA_OUT_OF_RANGE"))blockers.add("LAMBDA_OUT_OF_RANGE");
            c.set("blocking_reasons",blockers);c.put("selectable",blockers.isEmpty());
            String line=c.path("line").isNumber()?c.path("line").decimalValue().stripTrailingZeros().toPlainString():c.path("line").asText();
            String key=text(game,"game_id")+"|"+text(c,"kind")+"|"+text(c,"selection")+"|"+line+"|"+text(c,"score_basis")+"|"+text(c,"odds_type")+"|"+text(c,"id");
            c.put("candidate_id","candidate-"+OAuth.hash(key).substring(0,22));result.add(c);
        }
        return result;
    }
}
