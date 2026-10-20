package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.azure.storage.queue.*;
import com.azure.storage.queue.models.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Owner-operated ingestion, using the same storage authority as deployment. No public write endpoint. */
public final class DataSync {
    static ObjectNode apply(Store store,String day,JsonNode imported,int revision){
        if(!text(imported,"version").equals(DataCollector.VERSION))throw new IllegalArgumentException("Collector version mismatch");
        JsonNode input=imported.path("dataset");List<String> errors=Checks.validate(input);if(!errors.isEmpty()||!Checks.audit(input).path("issues").isEmpty()||input.path("synthetic_data").asBoolean())throw new IllegalArgumentException("Imported data failed validation");
        if(Checks.time(text(input,"as_of")).isBefore(Instant.now().minusSeconds(21600))||Checks.time(text(input,"as_of")).isAfter(Instant.now()))throw new IllegalArgumentException("Import must have been collected within six hours");
        return store.locked("day/"+Schedule.day(day),()->{
            ObjectNode d=store.get("days/"+day+".json");if(d==null)throw new IllegalArgumentException("Start official day first");if(d.path("revision").asInt()!=revision||List.of("PREFILLING","RUNNING","QUEUED").contains(text(d,"state")))throw new BusyException();
            ObjectNode data=d.path("dataset").deepCopy();if(!new HashSet<>(strings(data.path("expected_game_ids"))).equals(new HashSet<>(strings(input.path("expected_game_ids")))))throw new IllegalArgumentException("Official game set differs");
            ObjectNode paths=d.path("auto_fields").isObject()?d.path("auto_fields").deepCopy():obj();
            for(JsonNode g:data.path("games")){
                JsonNode from=null;for(JsonNode candidate:input.path("games"))if(text(candidate,"game_id").equals(text(g,"game_id")))from=candidate;if(from==null)throw new IllegalArgumentException("Missing official game");
                for(String p:List.of("game_id","starts_at","home.name","away.name"))if(!at(g,p).equals(at(from,p)))throw new IllegalArgumentException("Official schedule differs: "+p);
                Set<String> protectedFields=new LinkedHashSet<>(strings(paths.path(text(g,"game_id"))));
                for(JsonNode name:imported.path("auto_fields").path(text(g,"game_id"))){String p=name.asText();boolean allowed=false;for(String side:List.of("home","away")){for(String period:List.of("current","prior"))if(p.startsWith(side+"."+period+".")&&(Checks.STATS.contains(p.substring((side+"."+period+".").length()))||p.equals(side+"."+period+".source")))allowed=true;for(String f:Checks.CONTEXT)if(p.equals(side+".context."+f))allowed=true;}if(!allowed)throw new IllegalArgumentException("Unexpected native field: "+p);copy((ObjectNode)g,from,p);protectedFields.add(p);}
                for(String side:List.of("home","away"))if(at(g,side+".goalie.name").equals(at(from,side+".goalie.name")))for(String f:List.of("starts_current","starts_prior","gsax_per60_current","gsax_per60_prior")){String p=side+".goalie."+f;JsonNode check=from.path("checks").path("fields").path(p);if(text(check,"status").equals("found")&&strings(check.path("evidence_ids")).stream().allMatch(id->id.startsWith("auto-")))copy((ObjectNode)g,from,p);}
                paths.set(text(g,"game_id"),M.valueToTree(protectedFields));
            }
            for(JsonNode e:input.path("evidence"))DataCollector.mergeEvidence(data,e);Workflow.refreshStarted(data);List<String> validation=Checks.validate(data);if(!validation.isEmpty()||!Checks.audit(data).path("issues").isEmpty())throw new IllegalArgumentException("Merged data failed validation: "+validation);
            store.put("history/"+day+"/"+revision+".json",d);d.set("dataset",data);d.set("auto_fields",paths);ObjectNode meta=imported.deepCopy();meta.remove(List.of("dataset","auto_fields","coverage"));meta.put("state","DONE");meta.put("transport","owner_upload");meta.put("updated_at",Instant.now().toString());meta.put("cloud_source_access","MoneyPuck HTTP 403 from Azure; imported by owner-operated collector");d.set("enrichment",meta);d.put("revision",revision+1);d.put("state","COLLECTING");d.putNull("report");d.putNull("error");d.put("updated_at",Instant.now().toString());store.put("days/"+day+".json",d);return d;
        });
    }
    static void copy(ObjectNode target,JsonNode from,String path){String[] p=path.split("\\.");ObjectNode parent=target;for(int i=0;i<p.length-1;i++)parent=(ObjectNode)parent.path(p[i]);parent.set(p[p.length-1],at(from,path).deepCopy());if(!from.path("checks").path("fields").path(path).isMissingNode())((ObjectNode)target.path("checks").path("fields")).set(path,from.path("checks").path("fields").path(path).deepCopy());}
    public static void main(String[] args)throws Exception{
        String day=Schedule.day(args[0]);Store store=Store.cloud();QueueClient queue=new QueueClientBuilder().connectionString(System.getenv("AzureWebJobsStorage")).queueName("predictions").messageEncoding(QueueMessageEncoding.BASE64).buildClient();Workflow workflow=new Workflow(store,Schedule::fetch,j->queue.sendMessage(j.toString()));workflow.start(day);ObjectNode before=workflow.require(day);
        ObjectNode imported;if(args.length>1&&!args[1].equals("-"))imported=(ObjectNode)parse(Files.readString(Path.of(args[1])));else{DataCollector collector=new DataCollector(store);int season=DataCollector.season(LocalDate.parse(day));collector.goalieIndex(day,season);collector.goalieIndex(day,season-1);imported=collector.enrich(day,before.path("dataset"));}ObjectNode result=apply(store,day,imported,before.path("revision").asInt());
        System.out.println(obj("status","imported","day",day,"revision",result.path("revision"),"coverage",Checks.coverage(result.path("dataset")),"warnings",result.path("enrichment").path("warnings")).toPrettyString());
        if(Arrays.asList(args).contains("--finalize"))System.out.println(workflow.finish(day,100000).toPrettyString());
    }
}
