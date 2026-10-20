package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static sk.nhl.Json.*;

final class Mcp {
    static final String VERSION="2.4.3";
    final Workflow workflow;
    static final String INSTRUCTIONS="You are the NHL collector. First call get_instructions, then start_day for the NHL calendar date (America/New_York), and get_game for EACH official game. While PREFILLING, poll get_results with a few seconds between requests; do not edit yet. Backend imports team metrics, context, named goalie histories and actual NHL/DraftKings odds. Preserve backend_prefilled_fields, backend_prefilled_market_ids and all native market checks exactly; omitted native field checks are restored on save. Current odds scope is moneyline including OT/SO, 60-minute 1X2, totals over/under and handicaps. Do not derive missing bookmaker prices or force double chance/team totals into this scope. Backend closes unsupported or not-yet-published markets honestly as unavailable. Research remaining player/lineup/goalie fields using real opened sources. A probable goalie is not confirmed. When changing a goalie name, clear old starts/gsax values and checks; backend imports the new history on save. Save each complete game sequentially with evidence and latest revision. On conflict reload get_game. Never invent data. Use unavailable with a specific evidence-backed reason when searched but absent. Run check_day and repair every not_checked or inconsistent entry, then finalize_day with sims=1000. Do not wait indefinitely for missing odds: Azure polls every half hour from 18:00 Europe/Bratislava and recalculates after research is complete. Poll get_results until COMPLETE, INCOMPLETE, ERROR or NO_GAMES. Report odds_import separately from research completion and NO_BET. Backend performs all calculations. Do not replace an empty day with a different date. Never place bets.";
    Mcp(Workflow w){workflow=w;}
    static ObjectNode property(String type,String description){return obj("type",type,"description",description);}
    static ObjectNode schema(ObjectNode props,String...required){return obj("type","object","properties",props,"required",List.of(required),"additionalProperties",false);}
    static ObjectNode daySchema(){return schema(obj("day",obj("type","string","pattern","^\\d{4}-\\d{2}-\\d{2}$","description","NHL calendar date YYYY-MM-DD in America/New_York")),"day");}
    static ObjectNode tool(String name,String description,ObjectNode input,boolean read){return obj("name",name,"description",description,"inputSchema",input,"annotations",obj("readOnlyHint",read,"destructiveHint",false,"idempotentHint",read||name.equals("start_day")||name.equals("finalize_day"),"openWorldHint",name.equals("start_day")),"securitySchemes",List.of(obj("type","oauth2","scopes",List.of(OAuth.SCOPE))));}
    static ArrayNode tools(){
        ArrayNode tools=arr();tools.add(tool("get_instructions","Read the complete collection checklist and MCP workflow before starting any NHL research.",schema(obj()),true));
        tools.add(tool("get_schema","Return the authoritative JSON schema for games and evidence.",schema(obj()),true));
        tools.add(tool("start_day","Create or resume a day from the full official NHL schedule. Queues published MoneyPuck/NHL imports without AI calls, including repair of previously collected days. Wait for PREFILLING to finish before get_game/save_game. Returns every game ID and latest revision.",daySchema(),false));
        ObjectNode game=daySchema();((ObjectNode)game.path("properties")).set("game_id",property("string","Exact game ID returned by start_day"));((ArrayNode)game.path("required")).add("game_id");
        tools.add(tool("get_game","Get one game's editable scaffold, evidence, and current day revision. Use after PREFILLING finishes, before researching/saving each game. Preserve backend-prefilled fields and checks exactly.",game,true));
        ObjectNode save=daySchema();ObjectNode p=(ObjectNode)save.path("properties");p.set("game",obj("$ref","#/$defs/game"));p.set("evidence",obj("type","array","items",resource("schema.json").path("properties").path("evidence").path("items")));p.set("revision",obj("type","integer","minimum",0,"description","Latest revision from start_day/get_game/save_game. On conflict reload before retry."));save.set("$defs",resource("schema.json").path("$defs"));((ArrayNode)save.path("required")).add("game").add("evidence").add("revision");
        tools.add(tool("save_game","Save one whole game and newly opened evidence. Official teams/start/ID are immutable. Never reuse an evidence ID with changed URL/time. Returns updated revision and audit; unchecked fields must still be researched.",save,false));
        tools.add(tool("check_day","Audit ALL games, fields and market searches. Returns missing/unchecked fields and evidence errors; does not calculate probabilities.",daySchema(),true));
        ObjectNode finish=daySchema();((ObjectNode)finish.path("properties")).set("sims",obj("type","integer","minimum",1000,"maximum",250000,"default",Engine.SIMULATIONS,"description","Backend always uses 1000 simulations per game; larger legacy values are accepted and reduced to 1000."));
        tools.add(tool("finalize_day","Validate full checklist and enqueue Java calculation. If incomplete, repair it. After QUEUED poll get_results, allowing a few seconds between requests. Missing real critical data yields INCOMPLETE, not fabricated predictions.",finish,false));
        tools.add(tool("get_results","Get day state, saved results and website URL. COMPLETE means processing finished, not that a betting candidate exists.",daySchema(),true));return tools;
    }
    ObjectNode handle(JsonNode request){
        String method=text(request,"method");JsonNode id=request.path("id");
        if(!text(request,"jsonrpc").equals("2.0")||!request.path("method").isTextual())return error(id,-32600,"Invalid JSON-RPC request");
        if(id.isMissingNode()&&!method.startsWith("notifications/"))return error(id,-32600,"Requests require an id");
        try{
            JsonNode result=switch(method){
                case "initialize" -> obj("protocolVersion",protocol(text(request,"params.protocolVersion")),"capabilities",obj("tools",obj("listChanged",false)),"serverInfo",obj("name","nhl-prediction","version",VERSION),"instructions",INSTRUCTIONS);
                case "ping" -> obj();
                case "tools/list" -> obj("tools",tools());
                case "tools/call" -> call(text(request,"params.name"),request.path("params").path("arguments"));
                case "notifications/initialized","notifications/cancelled" -> null;
                default -> throw new UnsupportedOperationException("Unknown method");
            };
            if(id.isMissingNode())return null;return obj("jsonrpc","2.0","id",id,"result",result);
        }catch(UnsupportedOperationException e){return error(id,-32601,e.getMessage());}catch(IllegalArgumentException e){return error(id,-32602,e.getMessage());}
    }
    static String protocol(String requested){return List.of("2024-11-05","2025-03-26","2025-06-18","2025-11-25").contains(requested)?requested:"2025-06-18";}
    ObjectNode call(String name,JsonNode args){
        ObjectNode definition=null;for(JsonNode t:tools())if(text(t,"name").equals(name))definition=(ObjectNode)t;
        if(definition==null)throw new IllegalArgumentException("Unknown tool");
        var schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(definition.path("inputSchema"));
        var errors=schema.validate(args.isMissingNode()?obj():args);if(!errors.isEmpty())throw new IllegalArgumentException(errors.iterator().next().getMessage());
        try{
            String day=args.path("day").asText();JsonNode value=switch(name){
                case "get_instructions" -> obj("workflow",INSTRUCTIONS,"collector_prompt",resourceText("AI_COLLECTOR_PROMPT_v1.8.md"),"adaptation","Use MCP tools for collection, saving, validation and finalization. Get the schema through get_schema. Backend uses real UTC save time as as_of. Submit games sequentially with revision. For already started games set live/final and remove markets. Backend prefill takes precedence over the original prompt: do not re-search or replace backend-prefilled fields/checks. Read coverage separately from checklist: unavailable is NOT available data. Research remaining fields; keep uncertain values unavailable or probable. Never mark all remaining items unavailable just because one website failed; try an appropriate alternative and record an honest item-specific reason. The publication is not historical validation.");
                case "get_schema" -> resource("schema.json");
                case "start_day" -> {ObjectNode summary=compact(workflow.start(day));summary.set("expected_game_ids",workflow.require(day).path("dataset").path("expected_game_ids"));yield summary;}
                case "get_game" -> editable(workflow.require(day),text(args,"game_id"));
                case "save_game" -> workflow.saveGame(day,args.path("game"),args.path("evidence"),args.path("revision").asInt());
                case "check_day" -> {ObjectNode d=workflow.require(day);yield text(d,"state").equals("NO_GAMES")?obj("complete",true,"games",arr()):obj("validation_errors",Checks.validate(d.path("dataset")),"checklist",Checks.audit(d.path("dataset")),"coverage",Checks.coverage(d.path("dataset")));}
                case "finalize_day" -> workflow.finish(day,args.path("sims").asInt(Engine.SIMULATIONS));
                case "get_results" -> {ObjectNode d=workflow.require(day),summary=compact(workflow.summary(d));summary.set("report",d.path("report"));yield summary;}
                default -> throw new IllegalArgumentException("Unknown tool");
            };
            return obj("content",List.of(obj("type","text","text",value.toString())),"isError",false);
        }catch(IllegalArgumentException|BusyException e){return obj("content",List.of(obj("type","text","text",e.getMessage())),"isError",true);}
    }
    static ObjectNode compact(ObjectNode summary){JsonNode e=summary.path("enrichment");if(e.isObject()){ObjectNode reduced=e.deepCopy();reduced.put("source_count",reduced.path("sources").size());reduced.remove("sources");summary.set("enrichment",reduced);}return summary;}
    static ObjectNode editable(ObjectNode d,String id){
        if(text(d,"state").equals("PREFILLING"))return obj("state","PREFILLING","instruction","Poll get_results until source import finishes, then reload get_game.");
        ObjectNode game=null;for(JsonNode g:d.path("dataset").path("games"))if(text(g,"game_id").equals(id))game=g.deepCopy();if(game==null)throw new IllegalArgumentException("Unknown game ID");
        JsonNode nativePaths=d.path("auto_fields").path(id);for(JsonNode p:nativePaths)((ObjectNode)game.path("checks").path("fields")).remove(p.asText());
        Set<String> refs=new HashSet<>();for(JsonNode check:game.path("checks").path("fields"))refs.addAll(strings(check.path("evidence_ids")));for(JsonNode check:game.path("checks").path("market_searches"))refs.addAll(strings(check.path("evidence_ids")));ArrayNode evidence=arr();for(JsonNode e:d.path("dataset").path("evidence"))if(refs.contains(text(e,"id")))evidence.add(e);
        ArrayNode marketIds=arr();for(JsonNode market:game.path("markets"))if(NhlOdds.managed(market))marketIds.add(text(market,"id"));
        return obj("day",text(d,"day"),"revision",d.path("revision"),"as_of",d.path("dataset").path("as_of"),"game",game,"backend_prefilled_fields",nativePaths,"backend_prefilled_market_ids",marketIds,"odds_import",d.path("odds_import"),"native_checks","Native field checks are omitted and restored on save. Preserve native market records and market checks exactly; reload on revision conflict after an automatic odds refresh.","data_quality_warnings",d.path("enrichment").path("warnings"),"evidence",evidence);
    }
    static ObjectNode error(JsonNode id,int code,String message){return obj("jsonrpc","2.0","id",id.isMissingNode()?null:id,"error",obj("code",code,"message",message));}
}
