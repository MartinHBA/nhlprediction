package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** An immutable copy of the actual pregame inputs, separate from working research and live scores. */
final class Publication {
    static boolean exists(JsonNode day){return day.path("final_tips").path("games").isArray()&&!day.path("final_tips").path("games").isEmpty()&&!text(day,"final_tips.published_at").isBlank();}
    static boolean frozen(JsonNode day){return day.path("final_tips").path("dataset_snapshot").isObject();}
    static JsonNode data(JsonNode day){return frozen(day)?day.path("final_tips").path("dataset_snapshot"):day.path("dataset");}
    static JsonNode metadata(JsonNode day,String key){JsonNode saved=day.path("final_tips").path(key+"_snapshot");return frozen(day)&&!saved.isMissingNode()?saved:day.path(key);}
    static ObjectNode info(JsonNode day){return obj("frozen",exists(day),"snapshot_available",frozen(day),"published_at",day.path("final_tips").path("published_at"),"data_revision",day.path("final_tips").path("data_revision"),
        "refresh_in_progress",day.path("research_session").path("active").asBoolean(),"recovery",day.path("final_tips").path("snapshot_recovery"));}
    static void capture(ObjectNode tips,JsonNode day){
        ObjectNode snapshot=day.path("dataset").deepCopy();JsonNode previous=day.path("final_tips");
        if(frozen(day)){
            Map<String,JsonNode> old=new HashMap<>();for(JsonNode g:previous.path("dataset_snapshot").path("games"))old.put(text(g,"game_id"),g);
            ArrayNode games=(ArrayNode)snapshot.path("games");
            for(int i=0;i<games.size();i++){
                String id=text(games.get(i),"game_id");boolean kept=false;for(JsonNode d:tips.path("games"))if(text(d,"game_id").equals(id))kept=d.path("retained_previous_analysis").asBoolean();
                if(kept&&old.containsKey(id))games.set(i,old.get(id).deepCopy());
            }
            Map<String,JsonNode> evidence=new LinkedHashMap<>();for(JsonNode e:snapshot.path("evidence"))evidence.put(text(e,"id"),e);
            for(JsonNode e:previous.path("dataset_snapshot").path("evidence"))evidence.putIfAbsent(text(e,"id"),e);snapshot.set("evidence",M.valueToTree(evidence.values()));
        }
        tips.set("dataset_snapshot",snapshot);tips.set("odds_import_snapshot",day.path("odds_import").deepCopy());tips.set("enrichment_snapshot",day.path("enrichment").deepCopy());
    }
    static boolean matches(JsonNode source,JsonNode tips){
        try{return text(tips,"analysis_id").equals(Tips.analysisId(source,Checks.time(text(tips,"published_at"))));}catch(RuntimeException e){return false;}
    }
    /** Read-only recovery from a revision written before the old timer changed the dataset. */
    static boolean recover(ObjectNode day,Store store){
        if(!exists(day)||frozen(day))return frozen(day);ObjectNode tips=(ObjectNode)day.path("final_tips");JsonNode source=null;
        if(matches(day,tips))source=day;
        else{
            String prefix="history/"+text(day,"day")+"/";int min=tips.path("data_revision").asInt(0);
            List<String> keys=new ArrayList<>(store.keys(prefix));keys.removeIf(k->!k.substring(prefix.length()).matches("\\d+\\.json"));
            keys.sort(Comparator.comparingInt(k->Integer.parseInt(k.substring(prefix.length(),k.length()-5))));
            for(String key:keys){int revision=Integer.parseInt(key.substring(prefix.length(),key.length()-5));if(revision<min)continue;
                ObjectNode history=store.get(key);if(history!=null&&matches(history,tips)){source=history;break;}}
        }
        if(source==null){tips.set("snapshot_recovery",obj("state","UNAVAILABLE","reason","No archived dataset matches the published analysis fingerprint. Current statistics are not presented as the original inputs."));return false;}
        tips.set("dataset_snapshot",source.path("dataset").deepCopy());tips.set("odds_import_snapshot",source.path("odds_import").deepCopy());tips.set("enrichment_snapshot",source.path("enrichment").deepCopy());
        tips.set("snapshot_recovery",obj("state","RECOVERED","source_revision",source.path("revision")));return true;
    }
}
