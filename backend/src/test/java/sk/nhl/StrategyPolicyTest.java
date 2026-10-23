package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class StrategyPolicyTest {
    @Test void publicationUsesInclusive118To135BandAndAllowsDefensibleLowConfidence(){
        for(double price:new double[]{1.17999,1.18,1.19,1.20,1.35,1.35001}){
            TipsTest f=new TipsTest();f.setup();for(JsonNode q:f.game().path("markets"))if(text(q,"kind").equals("handicap"))((ObjectNode)q).put("odds",price);
            f.store.put("days/"+f.date+".json",f.day);ObjectNode args=f.body(true);((ObjectNode)args.path("decisions").get(0)).put("confidence","LOW");
            if(price>=1.18&&price<=1.35){JsonNode result=f.workflow.postTips(f.date,args);assertEquals("PUBLISHED",text(result,"final_tips.state"));assertEquals("LOW",text(result.path("final_tips").path("games").get(0),"confidence"));}
            else assertThrows(IllegalArgumentException.class,()->f.workflow.postTips(f.date,args));
        }
    }
    @Test void totalPublicationAllowsBothBasesButRequiresVerifiedSettlement(){
        for(String basis:List.of("regulation","official_final"))for(boolean verified:List.of(true,false)){
            TipsTest f=new TipsTest();f.setup();ObjectNode total=null;for(JsonNode q:f.game().path("markets"))if(text(q,"kind").equals("total")){total=(ObjectNode)q;break;}
            assertNotNull(total);total.put("odds",1.18).put("score_basis",basis).put("settlement_verified",verified);
            f.store.put("days/"+f.date+".json",f.day);String id=text(total,"id");
            JsonNode candidate=MarketQuotes.all(f.game(),f.now).valueStream().filter(q->text(q,"id").equals(id)).findFirst().orElseThrow();
            ObjectNode args=f.body(true);((ObjectNode)args.path("decisions").get(0)).put("candidate_id",text(candidate,"candidate_id"));
            if(verified)assertEquals("PUBLISHED",text(f.workflow.postTips(f.date,args),"final_tips.state"));
            else {assertThrows(IllegalArgumentException.class,()->f.workflow.postTips(f.date,args));assertFalse(f.workflow.require(f.date).has("final_tips"));}
        }
    }
}
