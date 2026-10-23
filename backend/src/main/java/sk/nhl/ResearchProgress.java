package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Read-only work plan over saved research, never over the frozen public snapshot. */
final class ResearchProgress {
    static final long NORMAL_MAX_AGE=21600,NEAR_START_MAX_AGE=1800,NEAR_START_WINDOW=5400;
    static boolean sensitive(String path){return path.endsWith(".goalie.name")||path.endsWith(".goalie.status")||
        path.contains(".roster.")&&!List.of("quantified","attack_delta_goals","defense_delta_goals_allowed").contains(path.substring(path.lastIndexOf('.')+1));}
    static Instant timestamp(String value){try{return Checks.time(value);}catch(RuntimeException ignored){return null;}}
    static boolean stale(JsonNode game,String path,Map<String,JsonNode> evidence,Instant now,long maxAge){
        JsonNode check=game.path("checks").path("fields").path(path);Instant checked=null;
        for(String id:strings(check.path("evidence_ids"))){
            Instant t=timestamp(text(evidence.getOrDefault(id,obj()),"checked_at"));
            if(t==null||t.isAfter(now))return true;
            if(checked==null||t.isAfter(checked))checked=t;
        }
        if(checked==null){String[] parts=path.split("\\.");checked=timestamp(text(at(game,parts[0]+"."+parts[1]+".source"),"checked_at"));}
        return checked==null||checked.isAfter(now)||!checked.isAfter(now.minusSeconds(maxAge));
    }
    static ObjectNode plan(JsonNode day,Instant now){
        JsonNode data=day.path("dataset");ObjectNode audit=Checks.audit(data);Map<String,JsonNode> audits=new HashMap<>(),proofs=new HashMap<>();
        for(JsonNode item:audit.path("games"))audits.put(text(item,"game_id"),item);
        for(JsonNode item:data.path("evidence"))proofs.put(text(item,"id"),item);
        ArrayNode games=arr();int work=0,reused=0,started=0;
        boolean waiting=text(day,"state").equals("PREFILLING"),published=Publication.exists(day)&&!day.path("research_session").path("active").asBoolean();
        for(JsonNode game:data.path("games")){
            String id=text(game,"game_id");JsonNode local=audits.getOrDefault(id,obj());ArrayNode fields=arr(),searches=arr(),refresh=arr();
            for(String path:strings(local.path("unchecked")))if(path.startsWith("market_searches.")){if(!text(day,"odds_import.provider").equals("Oddize"))searches.add(path.substring("market_searches.".length()));}else fields.add(path);
            Instant start=timestamp(text(game,"starts_at"));boolean alreadyStarted=!text(game,"status").equals("scheduled")||start==null||!start.isAfter(now);
            long maxAge=start!=null&&!start.isAfter(now.plusSeconds(NEAR_START_WINDOW))?NEAR_START_MAX_AGE:NORMAL_MAX_AGE;
            Set<String> nativePaths=new HashSet<>(strings(day.path("auto_fields").path(id)));
            if(!waiting&&!published&&!alreadyStarted)for(String path:Checks.paths(game))if(sensitive(path)&&!nativePaths.contains(path)&&!text(game.path("checks").path("fields").path(path),"status").equals("not_checked")&&!strings(fields).contains(path)&&stale(game,path,proofs,now,maxAge))refresh.add(path);
            String action=waiting?"WAIT_FOR_IMPORT":published?"KEEP_PUBLISHED":alreadyStarted?"SKIP_STARTED":!local.path("issues").isEmpty()?"REPAIR":!fields.isEmpty()||!searches.isEmpty()?"RESEARCH_PENDING":!refresh.isEmpty()?"REFRESH_TIME_SENSITIVE":"SKIP_COMPLETE";
            if(List.of("REPAIR","RESEARCH_PENDING","REFRESH_TIME_SENSITIVE").contains(action))work++;
            if(action.equals("SKIP_COMPLETE"))reused++;if(action.equals("SKIP_STARTED"))started++;
            games.add(obj("game_id",id,"away",text(game,"away.name"),"home",text(game,"home.name"),"starts_at",text(game,"starts_at"),"action",action,
                "unchecked_fields",fields,"unchecked_market_searches",searches,"refresh_fields",refresh,"issues",local.path("issues"),"closed_unavailable_count",local.path("unavailable").size()));
        }
        var validation=Checks.validate(data);
        return obj("mode","RESUME_SAVED_RESEARCH","generated_at",now.toString(),"games_requiring_collection",work,"games_reusing_research",reused,"started_games",started,
            "next_step",text(day,"state").equals("NO_GAMES")?"NO_GAMES":waiting?"WAIT_FOR_IMPORT":published?"KEEP_PUBLISHED":work>0?"COLLECT_REMAINING":!validation.isEmpty()||!audit.path("issues").isEmpty()?"REPAIR_VALIDATION":"FINALIZE_AND_ANALYZE",
            "freshness",obj("goalie_and_roster_max_age_seconds",NORMAL_MAX_AGE,"within_90_minutes_of_start_max_age_seconds",NEAR_START_MAX_AGE),
            "instruction","Reuse saved values, evidence and closed unavailable checks. Research only unchecked_fields, refresh_fields or specific errors/new information. All price collection and market searches belong to the backend Oddize snapshot, never web-search odds or create replacement prices. Native prices/checks are restored on save. Do not re-save unchanged games. All games must still be evaluated from get_analysis before post_tips; use only current selectable backend candidates.",
            "validation_errors",validation,"checklist_issues",audit.path("issues"),"games",games);
    }
}
