package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static sk.nhl.Json.*;

/** One value selection per game; estimated quotes remain explicitly conditional and outside the ticket. */
final class Recommendations {
    static final double MIN_ODDS=1.20,MAX_ODDS=1.35;
    static boolean inRange(double odds){return Double.isFinite(odds)&&odds>=MIN_ODDS&&odds<=MAX_ODDS;}
    static ObjectNode select(JsonNode game,JsonNode markets,JsonNode estimates,JsonNode projection,String asOf) {
        if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(Checks.time(asOf)))return null;
        if(strings(projection.path("warnings")).stream().anyMatch(w->w.startsWith("PROJECTED_LAMBDA_OUTSIDE_PLAUSIBLE_RANGE")))return null;
        ObjectNode best=null;double bestEdge=Double.NEGATIVE_INFINITY;
        // Compare the conservative edge of real and estimated prices on the same basis.
        Set<String> uncertain=Set.of("GOALIE_NOT_CONFIRMED","HOME_GOALIE_STALE_OVER_6_HOURS","AWAY_GOALIE_STALE_OVER_6_HOURS","HOME_ROSTER_STALE_OVER_6_HOURS","AWAY_ROSTER_STALE_OVER_6_HOURS","HOME_GOALIE_INSUFFICIENT_HISTORY","AWAY_GOALIE_INSUFFICIENT_HISTORY","FINAL_LINEUP_WINDOW_NOT_REACHED","NO_CONSERVATIVE_EDGE_IN_SCENARIOS");
        for(JsonNode m:markets)if((m.path("research_eligible").asBoolean()||(!m.path("issues").isMissingNode()&&strings(m.path("issues")).stream().allMatch(uncertain::contains)))
            &&inRange(number(m,"odds"))&&number(m,"ev")>0&&number(m,"scenario_ev_min")>bestEdge){
            best=((ObjectNode)m).deepCopy();best.put("odds_type","QUOTED");best.put("recommendation_status",m.path("research_eligible").asBoolean()?"REVIEW_REQUIRED":"PRELIMINARY");bestEdge=number(m,"scenario_ev_min");
        }
        boolean sources=true;
        for(String side:List.of("home","away"))for(String group:List.of("prior","current","goalie","roster"))
            sources&=text(game,side+"."+group+".source.status").equals("verified");
        if(sources)for(JsonNode m:estimates) {
            double price=number(m,"estimated_odds"),edge=number(m,"p_win_min")*price-1;
            if(!inRange(price)||number(m,"p_win")*price<=1||edge<=bestEdge)continue;
            best=((ObjectNode)m).deepCopy();best.put("recommendation_status","PRELIMINARY");
            best.put("estimated_ev",Engine.round(number(m,"p_win")*price-1,5));
            best.put("estimated_scenario_ev_min",Engine.round(edge,5));bestEdge=edge;
        }
        if(best==null)return null;
        ArrayNode reasons=arr();for(String issue:strings(best.path("issues")))if(!strings(reasons).contains(issue))reasons.add(issue);
        if(text(best,"odds_type").equals("ESTIMATED")&&!strings(reasons).contains("ESTIMATED_ODDS_NOT_VERIFIED"))reasons.add("ESTIMATED_ODDS_NOT_VERIFIED");
        best.put("robust_edge",bestEdge>0);
        if(bestEdge<=0)reasons.add("SCENARIO_EDGE_NOT_ROBUST");
        for(String side:List.of("home","away")) {
            if(!text(game,side+".goalie.status").equals("confirmed"))reasons.add(side.toUpperCase()+"_GOALIE_NOT_CONFIRMED");
            if(!at(game,side+".roster.quantified").asBoolean())reasons.add(side.toUpperCase()+"_LINEUP_IMPACT_UNQUANTIFIED");
            if(number(game,side+".goalie.starts_current")+number(game,side+".goalie.starts_prior")<10)reasons.add(side.toUpperCase()+"_GOALIE_INSUFFICIENT_HISTORY");
            for(String group:List.of("goalie","roster"))if(java.time.Duration.between(Checks.time(text(game,side+"."+group+".source.checked_at")),Checks.time(asOf)).toSeconds()>21600)
                reasons.add(side.toUpperCase()+"_"+group.toUpperCase()+"_STALE_OVER_6_HOURS");
        }
        for(String warning:strings(projection.path("warnings")))if(!strings(reasons).contains(warning))reasons.add(warning);
        // Data quality, not a calibrated claim of predictive accuracy. The underlying model is uncalibrated.
        best.put("confidence",reasons.isEmpty()?"MODERATE":"LOW");best.set("confidence_reasons",reasons);
        best.put("confidence_basis","INPUT_QUALITY_NOT_CALIBRATED_ACCURACY");
        return best;
    }
}
