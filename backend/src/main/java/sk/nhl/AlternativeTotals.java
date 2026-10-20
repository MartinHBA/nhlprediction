package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Indicative alternative totals, calibrated to a real two-sided quote, never bookmaker offers. */
final class AlternativeTotals {
    record Anchor(JsonNode over,JsonNode under,double margin,double lambda) {}

    static double under(double lambda,double line) {
        double term=Math.exp(-lambda),sum=term;
        for(int k=1;k<=Math.floor(line);k++){term*=lambda/k;sum+=term;}
        return sum;
    }
    static double fit(double line,double probability) {
        double low=.01,high=30;
        for(int i=0;i<80;i++){double mid=(low+high)/2;if(under(mid,line)>probability)low=mid;else high=mid;}
        return (low+high)/2;
    }
    static boolean fresh(JsonNode m,Instant cutoff) {
        Instant checked=Checks.time(text(m,"source.checked_at"));
        return text(m,"source.status").equals("verified")&&m.path("settlement_verified").asBoolean()
            &&!text(m,"source.url").isBlank()&&!text(m,"rules_url").isBlank()
            &&!checked.isAfter(cutoff)&&!checked.isBefore(cutoff.minusSeconds(7200));
    }
    static Anchor anchor(JsonNode game,Instant cutoff,String basis) {
        List<Anchor> anchors=new ArrayList<>();
        for(JsonNode over:game.path("markets")) {
            double line=number(over,"line");
            if(!text(over,"kind").equals("total")||!text(over,"selection").equals("over")
                ||!text(over,"score_basis").equals(basis)||line<2.5||line>10.5||line%1!=.5||!fresh(over,cutoff))continue;
            for(JsonNode under:game.path("markets")) {
                if(!text(under,"kind").equals("total")||!text(under,"selection").equals("under")
                    ||number(under,"line")!=line||!text(under,"score_basis").equals(basis)||!fresh(under,cutoff)
                    ||!text(over,"source.url").equals(text(under,"source.url"))
                    ||!text(over,"rules_url").equals(text(under,"rules_url"))
                    ||!text(over,"source.checked_at").equals(text(under,"source.checked_at")))continue;
                double o=number(over,"odds"),u=number(under,"odds");
                if(!Double.isFinite(o)||!Double.isFinite(u)||o<=1||u<=1)continue;
                double margin=1/o+1/u;if(margin<1||margin>1.3)continue;
                anchors.add(new Anchor(over,under,margin,fit(line,(1/u)/margin)));
            }
        }
        // Prefer the freshest complete pair, then a central line. Never blend different publications.
        anchors.sort(Comparator.comparing((Anchor a)->Checks.time(text(a.over,"source.checked_at"))).reversed()
            .thenComparingDouble(a->Math.abs(number(a.over,"line")-6.5)));
        return anchors.isEmpty()?null:anchors.getFirst();
    }
    static Double fair(double probability){return probability>0?1/probability:null;}
    static Double rounded(Double value){return value==null?null:Engine.round(value,5);}
    static ArrayNode estimate(JsonNode game,Engine.Scores base,List<Engine.Scores> scenarios,String asOf) {
        ArrayNode result=arr();Instant cutoff=Checks.time(asOf);
        if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(cutoff))return result;
        for(String basis:List.of("official_final","regulation")) {
            Anchor a=anchor(game,cutoff,basis);if(a==null)continue;
            for(double line=2.5;line<=10.5;line++)for(String selection:List.of("over","under")) {
                // Existing quotes remain in the real market table; this section is only for missing lines.
                boolean quoted=false;
                for(JsonNode m:game.path("markets"))if(text(m,"kind").equals("total")&&text(m,"selection").equals(selection)
                    &&text(m,"score_basis").equals(basis)&&number(m,"line")==line){quoted=true;break;}
                if(quoted)continue;
                ObjectNode market=obj("kind","total","selection",selection,"line",line,"score_basis",basis);
                double probability=Engine.settle(market,base)[0],minimum=probability,maximum=probability;
                for(Engine.Scores scores:scenarios){double p=Engine.settle(market,scores)[0];minimum=Math.min(minimum,p);maximum=Math.max(maximum,p);}
                double marketProbability=under(a.lambda,line);if(selection.equals("over"))marketProbability=1-marketProbability;
                double implied=marketProbability*a.margin;
                // A constant overround can imply an impossible price on extreme favourites; omit that price.
                Double estimate=implied>0&&implied<1?1/implied:null;
                result.add(obj("kind","total","selection",selection,"line",line,"score_basis",basis,
                    "odds_type","ESTIMATED","estimated_odds",rounded(estimate),"market_fair_odds",rounded(fair(marketProbability)),
                    "p_win",Engine.round(probability,5),"p_win_min",Engine.round(minimum,5),"p_win_max",Engine.round(maximum,5),
                    "model_fair_odds",rounded(fair(probability)),"minimum_odds_conservative",rounded(fair(minimum)),
                    "near_preferred_odds",estimate!=null&&estimate>=1.10&&estimate<=1.35,
                    "research_eligible",false,"issues",List.of("ESTIMATED_ODDS_NOT_VERIFIED"),
                    "method","POISSON_FIT_TO_NO_VIG_TOTAL_WITH_CONSTANT_OVERROUND",
                    "anchor_line",number(a.over,"line"),"anchor_over_odds",number(a.over,"odds"),"anchor_under_odds",number(a.under,"odds"),
                    "anchor_market_ids",List.of(text(a.over,"id"),text(a.under,"id")),"anchor_source_url",text(a.over,"source.url"),
                    "anchor_checked_at",text(a.over,"source.checked_at"),"anchor_overround",Engine.round(a.margin-1,6),
                    "market_implied_lambda",Engine.round(a.lambda,5)));
            }
        }
        return result;
    }
}
