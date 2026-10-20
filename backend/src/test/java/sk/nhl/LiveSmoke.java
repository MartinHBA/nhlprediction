package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Owner-operated deployment verification. Requires the deployment's storage connection in the process environment.
 * Seeds a single-use test authorization code, tests the real token/MCP path, then revokes every test credential.
 * Interactive Microsoft login is deliberately not simulated or claimed as tested. */
public final class LiveSmoke {
    static String base;
    static HttpResponse<String>request(String path,String body,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(90));if(bearer!=null)b.header("Authorization","Bearer "+bearer);if(body==null)b.GET();else b.header("Content-Type",path.equals("/oauth/token")?"application/x-www-form-urlencoded":"application/json").POST(HttpRequest.BodyPublishers.ofString(body));return Schedule.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static JsonNode rpc(String method,JsonNode params,String token)throws Exception{
        var r=request("/mcp",obj("jsonrpc","2.0","id",1,"method",method,"params",params).toString(),token);require(r.statusCode()==200,"MCP HTTP "+r.statusCode());JsonNode value=parse(r.body());require(!value.has("error"),"JSON-RPC failed: "+value);return value.path("result");
    }
    static void verifyNativeSave(Store store,String sourceDay,String access)throws Exception{
        String day="2099-12-30",key="days/"+day+".json",path="home.current.penalties_taken_per_game";
        require(store.get(key)==null,"Native save test day already exists; refusing to overwrite it");
        ObjectNode source=store.get("days/"+sourceDay+".json");require(source!=null,"Source day is missing");
        ObjectNode game=null;for(JsonNode g:source.path("dataset").path("games"))if(at(g,path).isFloatingPointNumber()&&at(g,path).decimalValue().stripTrailingZeros().scale()<=0){game=g.deepCopy();break;}
        require(game!=null,"Source day has no native integral decimal penalty rate");
        String id=text(game,"game_id");require(strings(source.path("auto_fields").path(id)).contains(path),"Penalty field must be backend-prefilled");
        JsonNode original=at(game,path).deepCopy();game.put("starts_at","2099-12-31T00:00:00Z");game.put("status","scheduled");
        // Keep this fixture independent of future-season goalie imports and external research.
        for(String side:List.of("home","away")){
            ObjectNode goalie=(ObjectNode)game.path(side).path("goalie");goalie.put("name","UNKNOWN").put("status","unknown").put("starts_current",0).put("starts_prior",0).putNull("gsax_per60_current").putNull("gsax_per60_prior");
            for(String field:Checks.GOALIE)((ObjectNode)game.path("checks").path("fields")).set(side+".goalie."+field,Schedule.check());
        }
        ObjectNode dataset=source.path("dataset").deepCopy();dataset.put("synthetic_data",true);dataset.put("as_of",Instant.now().toString());dataset.set("games",arr().add(game));dataset.set("expected_game_ids",arr().add(id));
        ObjectNode fixture=obj("day",day,"state","COLLECTING","revision",0,"dataset",dataset,"official_games",arr().add(game.deepCopy()),"auto_fields",obj(id,source.path("auto_fields").path(id)),"report",null,"updated_at",Instant.now().toString());
        boolean created=false;
        try{
            store.create(key,fixture);created=true;
            JsonNode fetched=rpc("tools/call",obj("name","get_game","arguments",obj("day",day,"game_id",id)),access);require(!fetched.path("isError").asBoolean(),"Native test get_game");
            JsonNode editable=parse(fetched.path("content").get(0).path("text").asText());ObjectNode outgoing=editable.path("game").deepCopy();
            ((ObjectNode)outgoing.path("home").path("current")).put("penalties_taken_per_game",original.intValue());
            JsonNode saved=rpc("tools/call",obj("name","save_game","arguments",obj("day",day,"game",outgoing,"evidence",editable.path("evidence"),"revision",0)),access);require(!saved.path("isError").asBoolean(),"Equivalent numeric notation must save: "+saved);
            ObjectNode persisted=store.get(key);require(original.equals(at(persisted.path("dataset").path("games").get(0),path)),"Original native representation must be retained");
            ((ObjectNode)outgoing.path("home").path("current")).put("penalties_taken_per_game",original.doubleValue()+1);
            JsonNode rejected=rpc("tools/call",obj("name","save_game","arguments",obj("day",day,"game",outgoing,"evidence",editable.path("evidence"),"revision",1)),access);require(rejected.path("isError").asBoolean()&&rejected.path("content").get(0).path("text").asText().contains("Backend-prefilled field is immutable"),"Actual native value change must be rejected");
            require(persisted.equals(store.get(key)),"Rejected edit must not modify stored data");
            System.out.println(obj("native_save","passed","checks",5,"source_day",sourceDay,"source_day_modified",false,"temporary_test_day",day,"native_number",original,"integer_notation_accepted",true,"actual_change_rejected",true).toPrettyString());
        }finally{if(created)store.take(key);}
        require(store.get(key)==null,"Temporary native save fixture cleanup");
    }
    static JsonNode tool(String name,JsonNode args,String access)throws Exception{
        JsonNode result=rpc("tools/call",obj("name",name,"arguments",args),access);
        require(!result.path("isError").asBoolean(),"Tool failed: "+name+" "+result.path("content"));
        return parse(result.path("content").get(0).path("text").asText());
    }
    static void verifyAiTips(Store store,String sourceDay,String access)throws Exception{
        String day="2099-12-29",key="days/"+day+".json",history="history/tips/"+day+"/";
        require(store.get(key)==null&&store.keys(history).isEmpty(),"AI test fixture already exists; refusing to overwrite");
        ObjectNode original=store.get("days/"+sourceDay+".json");require(original!=null,"Source day missing");
        ObjectNode data=original.path("dataset").deepCopy();ObjectNode game=data.path("games").get(0).deepCopy();
        game.put("status","scheduled").put("starts_at","2099-12-30T00:00:00Z");String id=text(game,"game_id");
        data.put("synthetic_data",true).put("as_of",Instant.now().toString());data.set("games",arr().add(game));data.set("expected_game_ids",arr().add(id));
        require(Checks.audit(data).path("complete").asBoolean(),"Source research incomplete");
        ObjectNode fixture=obj("day",day,"state","READY_FOR_ANALYSIS","revision",0,"research_complete",true,"dataset",data,"updated_at",Instant.now().toString());
        boolean created=false;
        try{
            store.create(key,fixture);created=true;
            JsonNode snapshot=tool("get_analysis",obj("day",day,"game_id",id),access);require(text(snapshot,"state").equals("READY"),"AI snapshot ready");
            ObjectNode decision=obj("game_id",id,"decision","NO_BET","candidate_id",null,"confidence","LOW",
                "rationale","Deployment verification on synthetic data; no real betting selection is published.",
                "comparison","No candidate is selected because this is an isolated synthetic deployment test.","risks",List.of("Synthetic test fixture; not a real recommendation."),"evidence_ids",arr());
            ObjectNode args=obj("day",day,"revision",snapshot.path("revision"),"analysis_id",text(snapshot,"analysis_id"),"decisions",arr().add(decision));
            ObjectNode stale=args.deepCopy();stale.put("analysis_id","stale-snapshot");JsonNode rejected=rpc("tools/call",obj("name","post_tips","arguments",stale),access);
            require(rejected.path("isError").asBoolean(),"Stale snapshot must be rejected");
            JsonNode published=tool("post_tips",args,access);require(text(published,"final_tips.state").equals("PUBLISHED"),"AI publication");
            ObjectNode before=store.get(key);tool("post_tips",args,access);require(before.equals(store.get(key)),"AI post retry must be idempotent");
            JsonNode publicDay=parse(request("/api/days/"+day,null,null).body());require(text(publicDay,"final_tips.state").equals("PUBLISHED"),"Public API includes AI decisions");
            ((ArrayNode)before.path("dataset").path("games").get(0).path("home").path("roster").path("absences")).add("ISOLATED_SMOKE_TEST_CHANGE");store.put(key,before);
            publicDay=parse(request("/api/days/"+day,null,null).body());require(text(publicDay,"final_tips.state").equals("NEEDS_REVIEW"),"Changed research invalidates AI decision");
            require(original.equals(store.get("days/"+sourceDay+".json")),"Source day must remain untouched");
            System.out.println(obj("ai_tips","passed","checks",7,"source_day_modified",false,"temporary_test_day",day,"published_real_tips",0));
        }finally{if(created){store.take(key);for(String h:store.keys(history))store.take(h);}}
        require(store.get(key)==null&&store.keys(history).isEmpty(),"AI test fixture cleanup");
    }
    public static void main(String[]args)throws Exception{
        base=System.getenv("PUBLIC_BASE_URL");String day=args.length>0?Schedule.day(args[0]):LocalDate.now(ZoneId.of("America/New_York")).toString();boolean repair=Arrays.asList(args).contains("--repair");Store store=Store.cloud();
        require(request("/health",null,null).statusCode()==200,"health");
        var unauthorized=request("/mcp",obj("jsonrpc","2.0","id",1,"method","initialize").toString(),null);require(unauthorized.statusCode()==401&&unauthorized.headers().firstValue("WWW-Authenticate").orElse("").contains("resource_metadata"),"unauthorized challenge");
        require(request("/mcp","{}",OAuth.random()).statusCode()==401,"Invalid token rejected");
        var registration=request("/oauth/register",obj("redirect_uris",List.of("https://chatgpt.com/connector_platform_oauth_redirect"),"token_endpoint_auth_method","none").toString(),null);require(registration.statusCode()==201,"DCR");String client=text(parse(registration.body()),"client_id");
        String code=OAuth.random(),verifier=OAuth.random(),access=null,refresh=null;
        try{
            store.create("oauth/codes/"+OAuth.hash(code),obj("client_id",client,"redirect_uri","https://chatgpt.com/connector_platform_oauth_redirect","challenge",OAuth.hash(verifier),"expires",Instant.now().plusSeconds(120).toString()));
            Map<String,String>grant=Map.of("grant_type","authorization_code","client_id",client,"code",code,"code_verifier",verifier,"redirect_uri","https://chatgpt.com/connector_platform_oauth_redirect","resource",base+"/mcp");
            var token=request("/oauth/token",OAuth.encode(grant),null);require(token.statusCode()==200,"PKCE token exchange: "+token.statusCode());JsonNode t=parse(token.body());access=text(t,"access_token");refresh=text(t,"refresh_token");
            require(request("/oauth/token",OAuth.encode(grant),null).statusCode()==400,"Authorization code replay rejected");
            if(Arrays.asList(args).contains("--native-save")){verifyNativeSave(store,day,access);return;}
            if(Arrays.asList(args).contains("--ai-tips")){verifyAiTips(store,day,access);return;}
            require(text(rpc("initialize",obj("protocolVersion","2025-06-18","capabilities",obj(),"clientInfo",obj("name","deployment-smoke","version","1")),access),"serverInfo.name").equals("nhl-prediction"),"MCP initialization");
            require(rpc("tools/list",obj(),access).path("tools").size()==11,"Tool count");
            JsonNode instructions=rpc("tools/call",obj("name","get_instructions","arguments",obj()),access);require(!instructions.path("isError").asBoolean(),"instructions");
            var priorResponse=request("/api/days/"+day,null,null);JsonNode prior=priorResponse.statusCode()==200?parse(priorResponse.body()):obj();
            JsonNode start=rpc("tools/call",obj("name","start_day","arguments",obj("day",day)),access);require(!start.path("isError").asBoolean(),"Official schedule: "+start);
            ObjectNode summary=(ObjectNode)parse(start.path("content").get(0).path("text").asText());
            if(text(prior,"enrichment.version").equals(DataCollector.VERSION)&&text(prior,"enrichment.transport").equals("owner_upload")&&Checks.time(text(prior,"enrichment.updated_at")).isAfter(Instant.now().minusSeconds(21600))&&prior.path("coverage").path("critical_found").equals(prior.path("coverage").path("critical_total")))require(!text(summary,"state").equals("PREFILLING"),"Fresh owner upload must not trigger a re-import");
            if(repair)throw new IllegalArgumentException("Legacy calculation repair was removed. Use data collection and get_analysis.");
            int checks=11;
            if(summary.path("game_count").asInt()>0){
                String gameId=summary.path("expected_game_ids").get(0).asText();
                JsonNode gameResult=rpc("tools/call",obj("name","get_game","arguments",obj("day",day,"game_id",gameId)),access);require(!gameResult.path("isError").asBoolean(),"get_game");
                JsonNode editable=parse(gameResult.path("content").get(0).path("text").asText());checks++;
                if(!editable.path("backend_prefilled_fields").isEmpty()){require(editable.has("native_checks"),"Native check compaction");for(JsonNode path:editable.path("backend_prefilled_fields"))require(!editable.path("game").path("checks").path("fields").has(path.asText()),"Native check should be retained on server");checks++;}
                // Only exercise a write on a wholly untouched scaffold; never invalidate a collected publication.
                if(text(summary,"state").equals("COLLECTING")&&summary.path("revision").asInt()==0&&Checks.time(text(editable,"game.starts_at")).isAfter(Instant.now())){
                    JsonNode saved=rpc("tools/call",obj("name","save_game","arguments",obj("day",day,"game",editable.path("game"),"evidence",arr(),"revision",0)),access);require(!saved.path("isError").asBoolean(),"save_game: "+saved);checks++;
                    JsonNode finalized=rpc("tools/call",obj("name","finalize_day","arguments",obj("day",day)),access);require(!finalized.path("isError").asBoolean()&&text(parse(finalized.path("content").get(0).path("text").asText()),"status").equals("CHECKLIST_INCOMPLETE"),"Incomplete checklist must block research finalization");checks++;
                }
            }
            require(request("/api/days/"+day,null,null).statusCode()==200,"Published day API");
            JsonNode compactResult=rpc("tools/call",obj("name","get_results","arguments",obj("day",day)),access);require(!compactResult.path("isError").asBoolean(),"get_results");JsonNode compact=parse(compactResult.path("content").get(0).path("text").asText());require(!compact.has("games")&&!compact.has("evidence")&&compact.has("coverage"),"Polling should not repeat raw research dataset");checks++;
            require(request("/api/days/2099-12-31",null,null).statusCode()==404,"Absent day is not mistaken for a different result");
            System.out.println(obj("status","passed","checks",checks,"bootstrap_day",day,"official_game_count",summary.path("game_count"),"day_state",summary.path("state"),"interactive_microsoft_login","requires owner sign-in through ChatGPT").toPrettyString());
        }finally{
            store.take("oauth/codes/"+OAuth.hash(code));store.take("oauth/clients/"+client);if(access!=null)store.take("oauth/access/"+OAuth.hash(access));if(refresh!=null)store.take("oauth/refresh/"+OAuth.hash(refresh));
        }
    }
}
