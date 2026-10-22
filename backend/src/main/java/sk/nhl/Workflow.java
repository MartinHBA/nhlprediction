package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static sk.nhl.Json.*;

final class Workflow {
    final Store store;final Function<String,ObjectNode> schedule;final Consumer<ObjectNode> queue;final DataCollector collector;final GameScores scores;
    final Map<String,ObjectNode> recoveredSnapshots=new java.util.concurrent.ConcurrentHashMap<>();
    Workflow(Store store,Function<String,ObjectNode> schedule,Consumer<ObjectNode> queue){this(store,schedule,queue,null);}
    Workflow(Store store,Function<String,ObjectNode> schedule,Consumer<ObjectNode> queue,DataCollector collector){this(store,schedule,queue,collector,null);}
    Workflow(Store store,Function<String,ObjectNode> schedule,Consumer<ObjectNode> queue,DataCollector collector,GameScores scores){this.store=store;this.schedule=schedule;this.queue=queue;this.collector=collector;this.scores=scores;}
    ObjectNode require(String day){ObjectNode d=store.get("days/"+Schedule.day(day)+".json");if(d==null)throw new IllegalArgumentException("Day not found. Call start_day first.");restoreSnapshot(d);return migrate(d);}
    void restoreSnapshot(ObjectNode d){
        if(!Publication.exists(d)||Publication.frozen(d))return;
        String key=text(d,"day")+"|"+text(d,"final_tips.analysis_id")+"|"+text(d,"final_tips.published_at");ObjectNode cached=recoveredSnapshots.get(key);
        if(cached==null){Publication.recover(d,store);cached=obj();for(String field:List.of("dataset_snapshot","odds_import_snapshot","enrichment_snapshot","snapshot_recovery"))if(d.path("final_tips").has(field))cached.set(field,d.path("final_tips").path(field).deepCopy());if(recoveredSnapshots.size()>32)recoveredSnapshots.clear();recoveredSnapshots.put(key,cached);}
        else for(var it=cached.fields();it.hasNext();){var field=it.next();((ObjectNode)d.path("final_tips")).set(field.getKey(),field.getValue().deepCopy());}
    }
    static ObjectNode migrate(ObjectNode d){
        if(d.hasNonNull("report")||List.of("QUEUED","RUNNING").contains(text(d,"state"))){
            d.put("state",d.path("research_complete").asBoolean()?"READY_FOR_ANALYSIS":"COLLECTING");
        }
        d.remove(List.of("report","last_sims","job_id"));
        if(Publication.exists(d)&&!d.path("research_session").path("active").asBoolean())d.put("state","COMPLETE");
        return d;
    }
    ObjectNode start(String day){return start(day,false);}
    ObjectNode start(String day,boolean refreshSources){
        Schedule.day(day);ObjectNode existing=store.get("days/"+day+".json");if(existing!=null){
            ObjectNode current=require(day);if(!Publication.exists(current))return requestPrefill(day,refreshSources);
            if(current.path("dataset").path("games").valueStream().noneMatch(g->Checks.time(text(g,"starts_at")).isAfter(Instant.now())))return summary(current);
            if(!text(current,"state").equals("ERROR")&&current.path("research_session").path("active").asBoolean()&&!Tips.old(text(current,"research_session.started_at"),Instant.now(),7200))return summary(current);
            store.locked("day/"+day,()->{ObjectNode d=require(day);refreshStarted((ObjectNode)d.path("dataset"));d.set("research_session",obj("active",true,"started_at",Instant.now().toString()));d.put("state","COLLECTING");d.put("research_complete",false);d.put("revision",d.path("revision").asInt()+1);store.put("days/"+day+".json",d);return null;});
            return requestPrefill(day,true);
        }
        ObjectNode data=schedule.apply(day);
        store.locked("day/"+day,()->{
            ObjectNode found=store.get("days/"+day+".json");if(found!=null)return summary(found);
            ObjectNode d=obj("day",day,"state",data.path("games").isEmpty()?"NO_GAMES":"COLLECTING","updated_at",Instant.now().toString(),"revision",0,"dataset",data,"official_games",data.path("games").deepCopy(),"report",null,"error",null);
            store.create("days/"+day+".json",d);return summary(d);
        });return requestPrefill(day);
    }
    ObjectNode requestPrefill(String day){return requestPrefill(day,false);}
    ObjectNode requestPrefill(String day,boolean force){
        if(collector==null)return summary(require(day));
        return store.locked("day/"+day,()->{
            ObjectNode d=require(day);if(List.of("PREFILLING","QUEUED","RUNNING","NO_GAMES").contains(text(d,"state")))return summary(d);
            if(Publication.exists(d)&&!force)return summary(d);
            JsonNode previous=d.path("enrichment");
            if(!force&&text(previous,"version").equals(DataCollector.VERSION)&&text(previous,"transport").equals("owner_upload")&&text(previous,"state").equals("DONE")&&Checks.time(text(previous,"updated_at")).isAfter(Instant.now().minusSeconds(21600))&&Checks.coverage(d.path("dataset")).path("critical_found").equals(Checks.coverage(d.path("dataset")).path("critical_total")))return summary(d);
            // A resumed GPT run must not restart a completed import because of source warnings.
            if(!force&&text(previous,"version").equals(DataCollector.VERSION)&&text(previous,"state").equals("DONE"))return summary(d);
            if(!force&&previous.hasNonNull("updated_at")&&Checks.time(text(previous,"updated_at")).isAfter(Instant.now().minusSeconds(60)))return summary(d);
            String job=UUID.randomUUID().toString();d.put("state","PREFILLING");d.put("updated_at",Instant.now().toString());d.putNull("error");d.set("enrichment",obj("state","QUEUED","version",DataCollector.VERSION,"job_id",job,"updated_at",Instant.now().toString(),"warnings",arr()));store.put("days/"+day+".json",d);
            try{queue.accept(obj("kind","prefill","day",day,"job_id",job));}catch(RuntimeException e){d.put("state","ERROR");((ObjectNode)d.path("enrichment")).put("state","ERROR");d.put("error","Could not enqueue source import. Retry start_day.");store.put("days/"+day+".json",d);throw e;}
            return summary(d);
        });
    }
    void prefill(JsonNode job){
        String day=Schedule.day(text(job,"day"));ObjectNode snapshot=store.locked("day/"+day,()->{ObjectNode d=require(day);if(!text(d,"state").equals("PREFILLING")||!text(d,"enrichment.job_id").equals(text(job,"job_id")))return null;((ObjectNode)d.path("enrichment")).put("state","RUNNING");store.put("days/"+day+".json",d);return d;});if(snapshot==null)return;
        try{
            ObjectNode result=collector.enrich(day,snapshot.path("dataset"));
            store.locked("day/"+day,()->{ObjectNode d=require(day);if(!text(d,"enrichment.job_id").equals(text(job,"job_id"))||!text(d,"state").equals("PREFILLING")||d.path("revision").asInt()!=snapshot.path("revision").asInt())return null;
                store.put("history/"+day+"/"+d.path("revision").asInt()+".json",d);d.set("dataset",result.remove("dataset"));JsonNode odds=result.remove("odds_import");if(odds!=null&&!odds.isNull())d.set("odds_import",odds);ObjectNode paths=(ObjectNode)result.remove("auto_fields");d.path("auto_fields").fields().forEachRemaining(entry->{ArrayNode combined=paths.has(entry.getKey())?(ArrayNode)paths.path(entry.getKey()):arr();Set<String> all=new LinkedHashSet<>(strings(combined));all.addAll(strings(entry.getValue()));paths.set(entry.getKey(),M.valueToTree(all));});d.set("auto_fields",paths);result.remove("coverage");result.put("state","DONE");result.put("job_id",text(job,"job_id"));d.set("enrichment",result);d.put("revision",d.path("revision").asInt()+1);d.put("state","COLLECTING");d.remove("report");d.put("updated_at",Instant.now().toString());store.put("days/"+day+".json",d);return null;});
        }catch(RuntimeException e){store.locked("day/"+day,()->{ObjectNode d=require(day);if(text(d,"enrichment.job_id").equals(text(job,"job_id"))&&text(d,"state").equals("PREFILLING")){d.put("state","ERROR");d.put("error","Source import failed. Retry start_day. "+DataCollector.rootMessage(e));((ObjectNode)d.path("enrichment")).put("state","ERROR").put("updated_at",Instant.now().toString());store.put("days/"+day+".json",d);}return null;});throw e;}
    }
    ObjectNode saveGame(String day,JsonNode game,JsonNode evidence,int expectedRevision){
        Schedule.day(day);ObjectNode before=require(day);if(before.path("revision").asInt()!=expectedRevision||List.of("PREFILLING","RUNNING","QUEUED").contains(text(before,"state")))throw new BusyException();
        ObjectNode enriched=obj("games",List.of(game.deepCopy()),"evidence",evidence.deepCopy());
        if(collector!=null)for(JsonNode old:before.path("dataset").path("games"))if(text(old,"game_id").equals(text(game,"game_id")))for(String side:List.of("home","away"))if(!at(old,side+".goalie.name").equals(at(game,side+".goalie.name"))){ObjectNode incoming=(ObjectNode)enriched.path("games").get(0);for(String f:Checks.GOALIE_HISTORY){String p=side+".goalie."+f;if(old.path("checks").path("fields").path(p).equals(game.path("checks").path("fields").path(p))){((ObjectNode)incoming.path(side).path("goalie")).putNull(f);((ObjectNode)incoming.path("checks").path("fields")).set(p,Schedule.check());}}}
        ObjectNode goalieImport=collector==null?obj("warnings",arr()):collector.enrichGoalies(day,enriched);
        JsonNode savedGame=enriched.path("games").get(0),savedEvidence=enriched.path("evidence");
        return store.locked("day/"+Schedule.day(day),()->{
            ObjectNode d=require(day);if(d.path("revision").asInt()!=expectedRevision)throw new BusyException();
            if(List.of("PREFILLING","RUNNING","QUEUED").contains(text(d,"state")))throw new BusyException();
            String id=text(game,"game_id");JsonNode official=null;for(JsonNode g:d.path("official_games"))if(text(g,"game_id").equals(id))official=g;
            if(official==null)throw new IllegalArgumentException("Game is not in official schedule");
            for(String p:List.of("game_id","starts_at","home.name","away.name"))if(!at(official,p).equals(at(game,p)))throw new IllegalArgumentException("Cannot change official "+p);
            if(Checks.time(text(game,"starts_at")).isAfter(Instant.now())&&!text(game,"status").equals("scheduled"))throw new IllegalArgumentException("Upcoming game must remain scheduled");
            if(!Checks.time(text(game,"starts_at")).isAfter(Instant.now())&&text(game,"status").equals("scheduled"))throw new IllegalArgumentException("Game has started. Set live/final and remove markets.");
            ObjectNode dataset=d.path("dataset").deepCopy();ArrayNode games=(ArrayNode)dataset.path("games");
            for(int i=0;i<games.size();i++)if(text(games.get(i),"game_id").equals(id)){
                for(JsonNode oldMarket:games.get(i).path("markets"))if(NhlOdds.managed(oldMarket)&&text(savedGame,"status").equals("scheduled")){
                    ArrayNode incomingMarkets=(ArrayNode)savedGame.path("markets");int index=-1;for(int j=0;j<incomingMarkets.size();j++)if(text(incomingMarkets.get(j),"id").equals(text(oldMarket,"id")))index=j;
                    JsonNode incoming=index<0?null:incomingMarkets.get(index);String changed=Json.difference(oldMarket,incoming);
                    if(!changed.isEmpty())throw new IllegalArgumentException("Backend NHL odds are immutable: market "+text(oldMarket,"id")+", field "+changed+". Reload get_game and preserve backend market records.");
                    // Preserve every original native field, including its numeric representation.
                    incomingMarkets.set(index,oldMarket.deepCopy());
                }
                for(JsonNode incoming:savedGame.path("markets"))if(NhlOdds.managed(incoming)){JsonNode original=null;for(JsonNode oldMarket:games.get(i).path("markets"))if(text(oldMarket,"id").equals(text(incoming,"id")))original=oldMarket;if(!incoming.equals(original))throw new IllegalArgumentException("Cannot create or change a backend NHL market through save_game");}
                for(JsonNode incoming:savedGame.path("markets"))if(!NhlOdds.managed(incoming)){
                    JsonNode original=null;for(JsonNode oldMarket:games.get(i).path("markets"))if(text(oldMarket,"id").equals(text(incoming,"id")))original=oldMarket;
                    if(!incoming.equals(original)&&(!MarketQuotes.fresh(incoming,Instant.now())||incoming.path("evidence_ids").isEmpty()))throw new IllegalArgumentException("New/revised researched prices need fresh verified source, settlement rules and evidence_ids. Never save an estimate as a quote.");
                }
                for(String search:Checks.SEARCHES){JsonNode original=games.get(i).path("checks").path("market_searches").path(search),incoming=savedGame.path("checks").path("market_searches").path(search);if(NhlOdds.nativeCheck(original)){if(!incoming.isMissingNode()&&!original.equals(incoming))throw new IllegalArgumentException("Backend market checks are immutable: "+search);((ObjectNode)savedGame.path("checks").path("market_searches")).set(search,original.deepCopy());}}
                // An independently verified offer can satisfy a category absent from the NHL feed.
                // The caller still preserves native checks; the server records the additional proof.
                for(String search:Checks.SEARCHES){
                    Set<String> manualProofs=new LinkedHashSet<>();boolean nativeFound=false;
                    for(JsonNode quote:savedGame.path("markets"))if(Checks.matches(search,quote)){if(NhlOdds.managed(quote))nativeFound=true;else manualProofs.addAll(strings(quote.path("evidence_ids")));}
                    if(!nativeFound&&!manualProofs.isEmpty())((ObjectNode)savedGame.path("checks").path("market_searches")).set(search,obj("status","found","evidence_ids",manualProofs,"note","Additional bookmaker offer verified by GPT research; not an inferred NHL quote."));
                }
                for(JsonNode path:d.path("auto_fields").path(id)){
                    String p=path.asText();JsonNode nativeValue=at(games.get(i),p),incomingValue=at(savedGame,p);
                    JsonNode original=games.get(i).path("checks").path("fields").path(p),incoming=savedGame.path("checks").path("fields").path(p);
                    // JSON 5 and 5.0 are the same value. Never tolerate rounding or store the client's representation.
                    boolean sameValue=nativeValue.isNumber()&&incomingValue.isNumber()?nativeValue.decimalValue().compareTo(incomingValue.decimalValue())==0:nativeValue.equals(incomingValue);
                    if(!sameValue||(!incoming.isMissingNode()&&!original.equals(incoming)))throw new IllegalArgumentException("Backend-prefilled field is immutable: "+p+". Reload get_game and preserve its value; native checks may be omitted and will be restored by the backend.");
                    String[] parts=p.split("\\.");ObjectNode parent=(ObjectNode)savedGame;for(int j=0;j<parts.length-1;j++)parent=(ObjectNode)parent.path(parts[j]);parent.set(parts[parts.length-1],nativeValue.deepCopy());
                    if(!original.isMissingNode())((ObjectNode)savedGame.path("checks").path("fields")).set(p,original.deepCopy());
                }
                games.set(i,savedGame.deepCopy());
            }
            Map<String,JsonNode>refs=new LinkedHashMap<>();for(JsonNode e:dataset.path("evidence"))refs.put(text(e,"id"),e);
            if(!evidence.isArray())throw new IllegalArgumentException("evidence must be an array");
            for(JsonNode e:savedEvidence){String eid=text(e,"id");if(refs.containsKey(eid)&&!refs.get(eid).equals(e))throw new IllegalArgumentException("Evidence IDs are immutable; use a new ID for a recheck");refs.put(eid,e);}
            dataset.set("evidence",M.valueToTree(refs.values()));refreshStarted(dataset);
            List<String>errors=Checks.validate(dataset);if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors.subList(0,Math.min(12,errors.size()))));
            d.set("dataset",dataset);d.put("research_complete",false);d.put("revision",expectedRevision+1);d.put("state","COLLECTING");d.remove("report");d.putNull("error");d.put("updated_at",Instant.now().toString());
            store.put("days/"+day+".json",d);return obj("day",day,"revision",expectedRevision+1,"checklist",Checks.audit(dataset),"coverage",Checks.coverage(dataset),"import_warnings",goalieImport.path("warnings"));
        });
    }
    ObjectNode finish(String day){

        return store.locked("day/"+Schedule.day(day),()->{
            ObjectNode d=require(day);if(List.of("PREFILLING","QUEUED","RUNNING").contains(text(d,"state")))return summary(d);
            if(text(d,"state").equals("NO_GAMES"))return summary(d);
            refreshStarted((ObjectNode)d.path("dataset"));
            List<String>errors=Checks.validate(d.path("dataset"));if(!errors.isEmpty())return obj("status","INVALID_INPUT","errors",errors);
            if(!Checks.audit(d.path("dataset")).path("complete").asBoolean())return obj("status","CHECKLIST_INCOMPLETE","checklist",Checks.audit(d.path("dataset")));
            d.put("research_complete",true);d.put("state","READY_FOR_ANALYSIS");d.put("updated_at",Instant.now().toString());d.putNull("error");
            d.remove(List.of("report","last_sims","job_id"));store.put("days/"+day+".json",d);
            return summary(d);
        });
    }
    void processImport(JsonNode job){
        if(text(job,"kind").equals("prefill"))prefill(job);
        // Old queued prediction jobs are deliberately discarded after migration. No model exists.
    }
    ObjectNode read(String day){return read(require(day));}
    ObjectNode read(ObjectNode d){restoreSnapshot(d);migrate(d);JsonNode data=Publication.data(d);ObjectNode out=summary(d);out.put("data_snapshot_id",webSnapshot(d));out.put("details_loaded",true);out.set("checklist",Checks.audit(data));out.set("evidence",data.path("evidence"));out.set("games",data.path("games"));return out;}
    static String webSnapshot(JsonNode d){return OAuth.hash(Publication.exists(d)?text(d,"final_tips.published_at")+"|"+text(d,"final_tips.analysis_id"):text(d,"revision")+"|"+text(d,"updated_at")+"|"+text(d,"state"));}
    ObjectNode readCards(ObjectNode d){
        restoreSnapshot(d);migrate(d);ObjectNode out=summary(d);out.put("data_snapshot_id",webSnapshot(d));out.put("details_loaded",false);
        ArrayNode cards=arr();for(JsonNode game:Publication.data(d).path("games")){
            ObjectNode card=obj("game_id",game.path("game_id"),"starts_at",game.path("starts_at"),"status",game.path("status"),"has_analysis_context",!game.path("analysis_context").isEmpty());
            for(String side:List.of("home","away")){
                JsonNode goalie=game.path(side).path("goalie");
                card.set(side,obj("name",game.path(side).path("name"),"goalie",obj("name",goalie.path("name"),"status",goalie.path("status"))));
            }
            cards.add(card);
        }
        out.set("games",cards);out.set("enrichment",obj("warnings",out.path("enrichment").path("warnings")));
        for(JsonNode decision:out.path("final_tips").path("games")){
            ((ObjectNode)decision).remove(List.of("comparison","risks","evidence_ids"));
            if(text(decision,"decision").equals("TIP"))((ObjectNode)decision).remove("rationale");
            if(decision.path("candidate").isObject()){ObjectNode quote=(ObjectNode)decision.path("candidate");quote.retain("kind","selection","line","team","score_basis","price","odds","estimated_odds","odds_type","confidence");}
        }
        return out;
    }
    ObjectNode readLive(String day){ObjectNode d=require(day);return obj("day",day,"state",d.path("state"),"revision",d.path("revision"),"publication",Publication.info(d),"scoreboard",d.path("scoreboard"),"final_tips",Tips.view(d,Instant.now()));}
    ObjectNode summary(JsonNode input){
        ObjectNode d=migrate(input.deepCopy());restoreSnapshot(d);JsonNode data=Publication.data(d);
        return obj("day",text(d,"day"),"state",text(d,"state"),"updated_at",text(d,"updated_at"),"revision",d.path("revision"),"game_count",data.path("games").size(),"as_of",data.path("as_of"),"error",d.path("error"),
            "enrichment",Publication.metadata(d,"enrichment"),"odds_import",Publication.metadata(d,"odds_import"),"research_complete",d.path("research_complete").asBoolean(),"coverage",Checks.coverage(data),"publication",Publication.info(d),
            "scoreboard",d.path("scoreboard"),"final_tips",Tips.view(d,Instant.now()),"web_url",System.getenv().getOrDefault("WEB_URL","")+"?day="+text(d,"day"));
    }
    void eveningTick(Instant now){
        String day=NhlOdds.pollDay(now);ObjectNode existing=store.get("days/"+day+".json");
        if(existing==null){start(day);refreshScores(day,now);return;}
        if(Publication.exists(existing)){refreshScores(day,now);return;}
        if(collector!=null&&!text(existing,"enrichment.version").equals(DataCollector.VERSION))requestPrefill(day);
        else refreshOdds(day);
        refreshScores(day,now);
    }
    ObjectNode refreshScores(String day,Instant now){
        if(scores==null)return summary(require(day));ObjectNode before=require(day),result;
        try{result=scores.collect(day,before.path("dataset"),before.path("scoreboard"),now);}
        catch(RuntimeException error){result=before.path("scoreboard").isObject()?before.path("scoreboard").deepCopy():obj("games",arr());result.put("state","ERROR");result.put("last_attempt_at",now.toString());result.put("error",DataCollector.rootMessage(error));}
        final ObjectNode scoreboard=result;
        return store.locked("day/"+day,()->{ObjectNode latest=require(day);latest.set("scoreboard",scoreboard);latest.put("scores_updated_at",now.toString());
            // Scores are outside the analysis revision/snapshot; they cannot conflict with GPT research.
            store.put("days/"+day+".json",latest);return summary(latest);});
    }
    ObjectNode refreshOdds(String day){
        if(collector==null||collector.odds==null)return summary(require(day));
        ObjectNode before=require(day);if(Publication.exists(before))return summary(before);if(List.of("PREFILLING","QUEUED","RUNNING","NO_GAMES").contains(text(before,"state")))return summary(before);
        ObjectNode imported=collector.odds.collect(day,before.path("dataset"));JsonNode data=imported.path("dataset");
        List<String> errors=Checks.validate(data);if(!errors.isEmpty()||!Checks.audit(data).path("issues").isEmpty())throw new IllegalStateException("NHL odds import failed validation: "+errors);
        boolean resume=before.path("research_complete").asBoolean();
        ObjectNode saved=store.locked("day/"+day,()->{ObjectNode latest=require(day);if(Publication.exists(latest))return null;if(latest.path("revision").asInt()!=before.path("revision").asInt()||!text(latest,"state").equals(text(before,"state")))return null;
            store.put("history/"+day+"/"+latest.path("revision").asInt()+".json",latest);latest.set("dataset",data);latest.set("odds_import",imported.path("odds_import"));latest.put("revision",latest.path("revision").asInt()+1);latest.put("updated_at",Instant.now().toString());latest.put("state","COLLECTING");latest.putNull("report");store.put("days/"+day+".json",latest);return latest;});
        if(saved==null)return summary(require(day));
        if(resume&&Checks.audit(data).path("complete").asBoolean())return finish(day);
        return summary(saved);
    }
    ArrayNode archive(){ArrayNode a=arr();store.keys("days/").stream().filter(k->k.matches("days/\\d{4}-\\d{2}-\\d{2}\\.json")).sorted(Comparator.reverseOrder()).limit(120).forEach(k->{ObjectNode d=store.get(k);if(d!=null){migrate(d);a.add(obj("day",d.path("day"),"state",d.path("state"),"game_count",Publication.data(d).path("games").size(),"updated_at",d.path("updated_at")));}});return a;}
    ObjectNode candidates(String day,String gameId){return Tips.candidates(require(day),gameId,Instant.now());}
    ObjectNode postTips(String day,JsonNode args){
        Tips.validateInput(args);
        return store.locked("day/"+Schedule.day(day),()->{
            ObjectNode d=require(day);Instant now=Instant.now();String signature=Tips.requestSignature(args);
            if(text(d,"final_tips.request_signature").equals(signature)
                &&text(Tips.view(d,now),"state").equals("PUBLISHED"))return summary(d);
            if(d.path("revision").asInt()!=args.path("revision").asInt())throw new BusyException();
            ObjectNode tips=Tips.prepare(d,args,now);tips.put("request_signature",signature);Publication.capture(tips,d);
            store.put("history/tips/"+day+"/"+d.path("revision").asInt()+".json",d.path("final_tips").isObject()?(ObjectNode)d.path("final_tips"):obj());
            d.set("final_tips",tips);d.set("research_session",obj("active",false));d.put("state","COMPLETE");d.put("revision",d.path("revision").asInt()+1);d.put("updated_at",now.toString());
            store.put("days/"+day+".json",d);return summary(d);
        });
    }
    static void refreshStarted(ObjectNode dataset){Instant now=Instant.now();dataset.put("as_of",now.toString());for(JsonNode g:dataset.path("games"))if(text(g,"status").equals("scheduled")&&!Checks.time(text(g,"starts_at")).isAfter(now)){((ObjectNode)g).put("status","live");((ObjectNode)g).set("markets",arr());}}
}
