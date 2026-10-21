package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static sk.nhl.Json.*;

/** Grades the saved prediction against official scores; never generates a prediction. */
final class TipSettlement {
    static ObjectNode evaluate(JsonNode decision,JsonNode score){
        if(!text(decision,"decision").equals("TIP"))return obj("state","NO_BET");
        if(score==null||!text(score,"status").equals("final"))return obj("state","PENDING");
        JsonNode quote=decision.path("candidate");boolean regulation=text(quote,"score_basis").equals("regulation");
        if(!regulation&&!text(quote,"score_basis").equals("official_final"))return obj("state","UNAVAILABLE","reason","Unknown market score basis");
        JsonNode home=score.path(regulation?"regulation_home_score":"home_score"),away=score.path(regulation?"regulation_away_score":"away_score");
        if(!home.isIntegralNumber()||!away.isIntegralNumber()||home.asInt()<0||away.asInt()<0)return obj("state","UNAVAILABLE","reason","Official score for this settlement basis is unavailable");
        int h=home.asInt(),a=away.asInt();if(!regulation&&h==a)return obj("state","UNAVAILABLE","reason","A tied official final score is not a completed NHL result");String selection=text(quote,"selection"),kind=text(quote,"kind");double value;
        switch(kind){
            case "total","team_total" -> {
                if(!quote.path("line").isNumber()||(!selection.equals("over")&&!selection.equals("under")))return obj("state","UNAVAILABLE","reason","Invalid total definition");
                if(kind.equals("team_total")&&!text(quote,"team").equals("home")&&!text(quote,"team").equals("away"))return obj("state","UNAVAILABLE","reason","Unknown team");
                int goals=kind.equals("total")?h+a:text(quote,"team").equals("home")?h:a;value=selection.equals("over")?goals-number(quote,"line"):number(quote,"line")-goals;
            }
            case "handicap" -> {if(!quote.path("line").isNumber()||(!selection.equals("home")&&!selection.equals("away")))return obj("state","UNAVAILABLE","reason","Invalid handicap definition");value=(selection.equals("home")?h-a:a-h)+number(quote,"line");}
            case "moneyline","regulation_1x2" -> {
                if(!selection.equals("home")&&!selection.equals("away")&&!(kind.equals("regulation_1x2")&&selection.equals("draw")))return obj("state","UNAVAILABLE","reason","Invalid winner selection");
                value=(selection.equals("home")?h>a:selection.equals("away")?a>h:h==a)?1:-1;
            }
            case "double_chance" -> {if(!selection.equals("1X")&&!selection.equals("X2")&&!selection.equals("12"))return obj("state","UNAVAILABLE","reason","Invalid double chance");value=(selection.equals("1X")?h>=a:selection.equals("X2")?a>=h:h!=a)?1:-1;}
            default -> {return obj("state","UNAVAILABLE","reason","Unsupported market");}
        }
        return obj("state",value>0?"WIN":value<0?"LOSS":"PUSH","score_basis",text(quote,"score_basis"),"home_score",h,"away_score",a,"source_url",score.path("source_url"),"checked_at",score.path("checked_at"));
    }
}
