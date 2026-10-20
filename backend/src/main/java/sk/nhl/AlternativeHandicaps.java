package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Alternative half-goal handicaps: tilt a score-difference distribution to an observed market pair. */
final class AlternativeHandicaps {
    record Anchor(JsonNode home,JsonNode away,double margin,double probability) {}
    static Anchor anchor(JsonNode game,Instant cutoff,String basis) {
        List<Anchor> anchors=new ArrayList<>();
        for(JsonNode home:game.path("markets")) {
            double line=number(home,"line");
            if(!text(home,"kind").equals("handicap")||!text(home,"selection").equals("home")
                ||!text(home,"score_basis").equals(basis)||Math.abs(line)>5||Math.abs(line)%1!=.5||!AlternativeTotals.fresh(home,cutoff))continue;
            for(JsonNode away:game.path("markets")) {
                if(!text(away,"kind").equals("handicap")||!text(away,"selection").equals("away")
                    ||number(away,"line")!= -line||!text(away,"score_basis").equals(basis)||!AlternativeTotals.fresh(away,cutoff)
                    ||!text(home,"source.url").equals(text(away,"source.url"))||!text(home,"rules_url").equals(text(away,"rules_url"))
                    ||!text(home,"source.checked_at").equals(text(away,"source.checked_at")))continue;
                double h=number(home,"odds"),a=number(away,"odds");
                if(!Double.isFinite(h)||!Double.isFinite(a)||h<=1||a<=1)continue;
                double margin=1/h+1/a;if(margin<1||margin>1.3)continue;
                anchors.add(new Anchor(home,away,margin,(1/h)/margin));
            }
        }
        anchors.sort(Comparator.comparing((Anchor a)->Checks.time(text(a.home,"source.checked_at"))).reversed()
            .thenComparingDouble(a->Math.abs(Math.abs(number(a.home,"line"))-1.5)));
        return anchors.isEmpty()?null:anchors.getFirst();
    }
    static SortedMap<Integer,Double> histogram(Engine.Scores scores,String basis) {
        int[] home=basis.equals("regulation")?scores.rh():scores.fh(),away=basis.equals("regulation")?scores.ra():scores.fa();
        SortedMap<Integer,Double> result=new TreeMap<>();
        for(int i=0;i<home.length;i++)result.merge(home[i]-away[i],1.0,Double::sum);
        return result;
    }
    static double probability(Map<Integer,Double> histogram,double tilt,double line) {
        double max=histogram.keySet().stream().mapToDouble(x->tilt*x).max().orElse(0),win=0,sum=0;
        for(var e:histogram.entrySet()){double w=e.getValue()*Math.exp(tilt*e.getKey()-max);sum+=w;if(e.getKey()+line>0)win+=w;}
        return win/sum;
    }
    static double fit(Map<Integer,Double> histogram,double line,double probability) {
        double low=-20,high=20;
        for(int i=0;i<80;i++){double mid=(low+high)/2;if(probability(histogram,mid,line)<probability)low=mid;else high=mid;}
        return (low+high)/2;
    }
    static ArrayNode estimate(JsonNode game,Engine.Scores base,List<Engine.Scores> scenarios,String asOf) {
        ArrayNode result=arr();Instant cutoff=Checks.time(asOf);
        if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(cutoff))return result;
        for(String basis:List.of("official_final","regulation")) {
            Anchor a=anchor(game,cutoff,basis);if(a==null)continue;
            var histogram=histogram(base,basis);double anchorLine=number(a.home,"line");
            if(probability(histogram,-20,anchorLine)>=a.probability||probability(histogram,20,anchorLine)<=a.probability)continue;
            double tilt=fit(histogram,anchorLine,a.probability);
            for(double line=-4.5;line<=4.5;line++)for(String selection:List.of("home","away")) {
                boolean quoted=false;
                for(JsonNode m:game.path("markets"))if(text(m,"kind").equals("handicap")&&text(m,"selection").equals(selection)
                    &&text(m,"score_basis").equals(basis)&&number(m,"line")==line){quoted=true;break;}
                if(quoted)continue;
                ObjectNode market=obj("kind","handicap","selection",selection,"team",selection,"line",line,"score_basis",basis);
                double probability=Engine.settle(market,base)[0],minimum=probability,maximum=probability;
                for(Engine.Scores scores:scenarios){double p=Engine.settle(market,scores)[0];minimum=Math.min(minimum,p);maximum=Math.max(maximum,p);}
                double marketProbability=probability(histogram,tilt,selection.equals("home")?line:-line);
                if(selection.equals("away"))marketProbability=1-marketProbability;
                double implied=marketProbability*a.margin;Double estimate=implied>0&&implied<1?1/implied:null;
                result.add(obj("kind","handicap","selection",selection,"team",selection,"line",line,"score_basis",basis,
                    "odds_type","ESTIMATED","estimated_odds",AlternativeTotals.rounded(estimate),
                    "market_fair_odds",AlternativeTotals.rounded(AlternativeTotals.fair(marketProbability)),
                    "p_win",Engine.round(probability,5),"p_win_min",Engine.round(minimum,5),"p_win_max",Engine.round(maximum,5),
                    "model_fair_odds",AlternativeTotals.rounded(AlternativeTotals.fair(probability)),
                    "minimum_odds_conservative",AlternativeTotals.rounded(AlternativeTotals.fair(minimum)),
                    "research_eligible",false,"issues",List.of("ESTIMATED_ODDS_NOT_VERIFIED"),
                    "method","SCORE_DIFFERENCE_EXPONENTIAL_TILT_TO_NO_VIG_HANDICAP_WITH_CONSTANT_OVERROUND",
                    "anchor_line",anchorLine,"anchor_home_odds",number(a.home,"odds"),"anchor_away_odds",number(a.away,"odds"),
                    "anchor_market_ids",List.of(text(a.home,"id"),text(a.away,"id")),"anchor_source_url",text(a.home,"source.url"),
                    "anchor_checked_at",text(a.home,"source.checked_at"),"anchor_overround",Engine.round(a.margin-1,6)));
            }
        }
        return result;
    }
}
