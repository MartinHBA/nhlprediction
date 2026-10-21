package sk.nhl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static sk.nhl.Json.*;

/** Owner-operated recovery of the exact archived publication. No prediction or research is rewritten. */
public final class RepairPublishedDay {
    public static void main(String[] args){
        String day=Schedule.day(args[0]);boolean apply=Arrays.asList(args).contains("--apply");Store store=Store.cloud();
        Workflow workflow=new Workflow(store,d->{throw new IllegalStateException("Recovery cannot create a day");},j->{throw new IllegalStateException("Recovery cannot queue research");},null,new GameScores());
        ObjectNode original=store.get("days/"+day+".json"),recovered=workflow.require(day);
        if(original==null||!Publication.exists(recovered)||!Publication.frozen(recovered))throw new IllegalStateException("Cannot identify exact archived inputs for this publication");
        if(apply){
            store.locked("day/"+day,()->{ObjectNode latest=store.get("days/"+day+".json");
                if(!text(latest,"final_tips.published_at").equals(text(original,"final_tips.published_at")))throw new BusyException();
                store.create("history/repair/"+day+"/"+UUID.randomUUID()+".json",latest);
                ObjectNode tips=(ObjectNode)latest.path("final_tips");for(String key:List.of("dataset_snapshot","odds_import_snapshot","enrichment_snapshot","snapshot_recovery"))if(recovered.path("final_tips").has(key))tips.set(key,recovered.path("final_tips").path(key).deepCopy());
                store.put("days/"+day+".json",latest);return null;});
            workflow.refreshScores(day,Instant.now());
            if(!original.path("final_tips").path("games").equals(store.get("days/"+day+".json").path("final_tips").path("games")))throw new IllegalStateException("Stored GPT decisions changed unexpectedly");
        }
        ObjectNode result=workflow.read(day);System.out.println(obj("day",day,"applied",apply,"publication",result.path("publication"),"decisions_preserved",true,
            "score_state",result.path("scoreboard").path("state"),"tip_count",result.path("final_tips").path("tip_count"),"no_bet_count",result.path("final_tips").path("no_bet_count"),
            "wins",result.path("final_tips").path("wins"),"losses",result.path("final_tips").path("losses")).toPrettyString());
    }
}
