package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Interpolate handicap prices only between TWO independent quoted lines. No simulated score distribution. */
final class AlternativeHandicaps {
    record Anchor(JsonNode home,JsonNode away,double line,double logit,double margin) {}
    static ArrayNode estimate(JsonNode game,Instant now){
        ArrayNode out=arr();for(String basis:List.of("official_final","regulation")){
            List<Anchor> anchors=new ArrayList<>();
            for(JsonNode home:game.path("markets")){
                if(!text(home,"kind").equals("handicap")||!text(home,"selection").equals("home")||!text(home,"score_basis").equals(basis)||!MarketQuotes.fresh(home,now)||Math.abs(number(home,"line"))%1!=.5)continue;
                for(JsonNode away:game.path("markets"))if(text(away,"kind").equals("handicap")&&text(away,"selection").equals("away")&&text(away,"score_basis").equals(basis)&&number(away,"line")==-number(home,"line")&&MarketQuotes.fresh(away,now)&&AlternativeTotals.samePublication(home,away)){
                    double margin=1/number(home,"odds")+1/number(away,"odds"),p=(1/number(home,"odds"))/margin;if(margin<1||margin>1.3||p<=0||p>=1)continue;
                    anchors.add(new Anchor(home,away,number(home,"line"),Math.log(p/(1-p)),margin));
                }
            }
            anchors.sort(Comparator.comparingDouble(Anchor::line));
            for(double line=-4.5;line<=4.5;line++)for(String selection:List.of("home","away")){
                if(AlternativeTotals.alreadyQuoted(game,"handicap",selection,basis,line))continue;double homeLine=selection.equals("home")?line:-line;
                Anchor low=null,high=null;
                for(Anchor a:anchors)for(Anchor b:anchors)if(a.line<=homeLine&&b.line>=homeLine&&a.line<b.line&&b.logit>a.logit&&AlternativeTotals.samePublication(a.home,b.home)){
                    if(low==null||b.line-a.line<high.line-low.line){low=a;high=b;}
                }
                if(low==null)continue;double weight=(homeLine-low.line)/(high.line-low.line),logit=low.logit+weight*(high.logit-low.logit);
                double p=1/(1+Math.exp(-logit));if(selection.equals("away"))p=1-p;double implied=p*(low.margin+weight*(high.margin-low.margin));
                if(implied<=0||implied>=1)continue;double price=MarketQuotes.round(1/implied,5);
                out.add(obj("id","handicap-approx-"+basis+"-"+selection+"-"+line,"kind","handicap","selection",selection,"team",selection,"line",line,"score_basis",basis,
                    "price",price,"estimated_odds",price,"odds_type","ESTIMATED","source",low.home.path("source"),"rules_url",low.home.path("rules_url"),"settlement_verified",true,
                    "method","BOOKMAKER_HANDICAP_LOGIT_INTERPOLATION","assumptions","Interpolated only between two independent complete handicap quote pairs from the same publication; not a game prediction. Verify the real offer.",
                    "anchor_market_ids",List.of(text(low.home,"id"),text(low.away,"id"),text(high.home,"id"),text(high.away,"id")),"anchor_checked_at",text(low.home,"source.checked_at")));
            }
        }return out;
    }
}
