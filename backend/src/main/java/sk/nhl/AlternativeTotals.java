package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Price curve fitted only to a complete bookmaker over/under pair. Never a team/score prediction. */
final class AlternativeTotals {
    record Anchor(JsonNode over,JsonNode under,double margin,double parameter) {}
    static double under(double parameter,double line){double term=Math.exp(-parameter),sum=term;for(int k=1;k<=Math.floor(line);k++){term*=parameter/k;sum+=term;}return sum;}
    static double fit(double line,double probability){double low=.01,high=30;for(int i=0;i<80;i++){double mid=(low+high)/2;if(under(mid,line)>probability)low=mid;else high=mid;}return (low+high)/2;}
    static boolean samePublication(JsonNode a,JsonNode b){return text(a,"source.url").equals(text(b,"source.url"))&&text(a,"rules_url").equals(text(b,"rules_url"))&&text(a,"source.checked_at").equals(text(b,"source.checked_at"));}
    static Anchor anchor(JsonNode game,Instant now,String basis){
        List<Anchor> anchors=new ArrayList<>();
        for(JsonNode over:game.path("markets")){
            double line=number(over,"line");if(!text(over,"kind").equals("total")||!text(over,"selection").equals("over")||!text(over,"score_basis").equals(basis)||line<2.5||line>10.5||line%1!=.5||!MarketQuotes.fresh(over,now))continue;
            for(JsonNode under:game.path("markets"))if(text(under,"kind").equals("total")&&text(under,"selection").equals("under")&&text(under,"score_basis").equals(basis)&&number(under,"line")==line&&MarketQuotes.fresh(under,now)&&samePublication(over,under)){
                double margin=1/number(over,"odds")+1/number(under,"odds");if(margin<1||margin>1.3)continue;
                anchors.add(new Anchor(over,under,margin,fit(line,(1/number(under,"odds"))/margin)));
            }
        }
        anchors.sort(Comparator.comparing((Anchor a)->Checks.time(text(a.over,"source.checked_at"))).reversed().thenComparingDouble(a->Math.abs(number(a.over,"line")-6.5)));
        return anchors.isEmpty()?null:anchors.getFirst();
    }
    static boolean alreadyQuoted(JsonNode game,String kind,String selection,String basis,double line){for(JsonNode quote:game.path("markets"))if(text(quote,"kind").equals(kind)&&text(quote,"selection").equals(selection)&&text(quote,"score_basis").equals(basis)&&number(quote,"line")==line)return true;return false;}
    static ArrayNode estimate(JsonNode game,Instant now){
        ArrayNode out=arr();for(String basis:List.of("official_final","regulation")){
            Anchor a=anchor(game,now,basis);if(a==null)continue;
            for(double line=2.5;line<=10.5;line++)for(String selection:List.of("over","under")){
                if(alreadyQuoted(game,"total",selection,basis,line))continue;
                double share=under(a.parameter,line);if(selection.equals("over"))share=1-share;double implied=share*a.margin;
                if(implied<=0||implied>=1)continue;double price=MarketQuotes.round(1/implied,5);
                out.add(obj("id","total-approx-"+basis+"-"+selection+"-"+line,"kind","total","selection",selection,"line",line,"score_basis",basis,
                    "price",price,"estimated_odds",price,"odds_type","ESTIMATED","source",a.over.path("source"),"rules_url",a.over.path("rules_url"),"settlement_verified",true,
                    "method","BOOKMAKER_TOTAL_PAIR_PRICE_CURVE","assumptions","Poisson-shaped price curve fitted to bookmaker quotes with constant overround; pricing approximation only, not a game prediction. Verify the actual offered line and price.",
                    "anchor_market_ids",List.of(text(a.over,"id"),text(a.under,"id")),"anchor_line",number(a.over,"line"),"anchor_over_odds",number(a.over,"odds"),"anchor_under_odds",number(a.under,"odds"),"anchor_checked_at",text(a.over,"source.checked_at")));
            }
        }return out;
    }
}
