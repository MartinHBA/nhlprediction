package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Publishes the GPT analyst's decisions against a versioned DATA snapshot, never Java predictions. */
final class Tips {
    static ObjectNode inputSchema(){
        ObjectNode decision=Mcp.schema(obj(
            "game_id",obj("type","string","minLength",1),"decision",obj("enum",List.of("TIP","NO_BET")),
            "candidate_id",obj("type",List.of("string","null")),"confidence",obj("enum",List.of("LOW","MODERATE","HIGH")),
            "rationale",obj("type","string","minLength",40,"maxLength",2000),"comparison",obj("type","string","minLength",20,"maxLength",2000),
            "risks",obj("type","array","maxItems",8,"items",obj("type","string","minLength",5,"maxLength",400)),
            "evidence_ids",obj("type","array","maxItems",20,"uniqueItems",true,"items",obj("type","string","minLength",1))),
            "game_id","decision","candidate_id","confidence","rationale","comparison","risks","evidence_ids");
        return Mcp.schema(obj("day",obj("type","string","pattern","^\\d{4}-\\d{2}-\\d{2}$"),"revision",obj("type","integer","minimum",0),
            "analysis_id",obj("type","string","minLength",1),"decisions",obj("type","array","maxItems",32,"items",decision)),"day","revision","analysis_id","decisions");
    }
    static void validateInput(JsonNode args){var schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(inputSchema());var errors=schema.validate(args);if(!errors.isEmpty())throw new IllegalArgumentException(errors.iterator().next().getMessage());}
    static JsonNode canonical(JsonNode n){
        if(n.isObject()){ObjectNode out=obj();List<String> keys=new ArrayList<>();n.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)out.set(key,canonical(n.path(key)));return out;}
        if(n.isArray()){ArrayNode out=arr();for(JsonNode v:n)out.add(canonical(v));return out;}
        if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());return n.deepCopy();
    }
    static void removeCheckTimes(JsonNode n){if(n.isObject()){((ObjectNode)n).remove("checked_at");n.forEach(Tips::removeCheckTimes);}else if(n.isArray())n.forEach(Tips::removeCheckTimes);}
    static boolean old(String time,Instant now,long seconds){try{return Checks.time(time).isBefore(now.minusSeconds(seconds));}catch(RuntimeException e){return true;}}
    static String gameBasis(JsonNode game,Instant now){
        ObjectNode copy=game.deepCopy();copy.remove("checks");removeCheckTimes(copy);
        List<JsonNode> quotes=new ArrayList<>();copy.path("markets").forEach(quotes::add);quotes.sort(Comparator.comparing(m->canonical(m).toString()));copy.set("markets",M.valueToTree(quotes));
        ObjectNode age=obj();for(String side:List.of("home","away"))for(String group:List.of("goalie","roster"))age.put(side+"."+group,old(text(game,side+"."+group+".source.checked_at"),now,21600));
        ObjectNode statuses=obj();for(String group:List.of("fields","market_searches"))game.path("checks").path(group).fields().forEachRemaining(e->statuses.put(group+"."+e.getKey(),text(e.getValue(),"status")));
        List<String> stale=new ArrayList<>();for(JsonNode q:game.path("markets"))if(old(text(q,"source.checked_at"),now,7200))stale.add(text(q,"id"));Collections.sort(stale);
        return OAuth.hash(canonical(obj("policy",MarketQuotes.VERSION,"game",copy,"checks",statuses,"old_checks",age,"stale_quotes",stale)).toString());
    }
    static String analysisId(JsonNode day,Instant now){ObjectNode games=obj();for(JsonNode g:day.path("dataset").path("games"))games.put(text(g,"game_id"),gameBasis(g,now));return "analysis-"+OAuth.hash(canonical(games).toString());}
    static Set<String> references(JsonNode game){
        Set<String> refs=new LinkedHashSet<>();for(String group:List.of("fields","market_searches"))for(JsonNode check:game.path("checks").path(group))refs.addAll(strings(check.path("evidence_ids")));
        for(JsonNode market:game.path("markets"))refs.addAll(strings(market.path("evidence_ids")));
        for(JsonNode context:game.path("analysis_context"))refs.addAll(strings(context.path("evidence_ids")));return refs;
    }
    static ObjectNode priceBrief(JsonNode quote){
        ObjectNode out=obj();for(String key:List.of("candidate_id","kind","selection","team","line","price","bookmaker_code","score_basis","observed_at","selectable","blocking_reasons"))if(quote.has(key))out.set(key,quote.path(key));return out;
    }
    static ObjectNode snapshot(JsonNode day,Instant now){
        ArrayNode games=arr();for(JsonNode game:day.path("dataset").path("games")){
            ArrayNode band=arr();for(JsonNode q:MarketQuotes.all(game,now))if(q.path("in_preferred_band").asBoolean())band.add(priceBrief(q));
            games.add(obj("game_id",game.path("game_id"),"basis_id",gameBasis(game,now),"status",game.path("status"),"starts_at",game.path("starts_at"),"candidates",band));
        }
        return obj("day",day.path("day"),"state",text(day,"state").equals("PREFILLING")?"PREFILLING":List.of("QUEUED","RUNNING").contains(text(day,"odds_import.state"))?"WAITING_FOR_ODDS":"READY","revision",day.path("revision"),"analysis_id",analysisId(day,now),"summary_only",true,"research_complete",day.path("research_complete"),"odds_import",day.path("odds_import"),"games",games,"instruction","Compare basis_id with the per-game analyses. Reload get_analysis(day,game_id) for changed games before publishing; do not mix snapshots. Prices come only from the backend.");
    }
    static ObjectNode candidates(JsonNode day,String gameId,Instant now){
        ArrayNode games=arr();boolean found=gameId.isBlank();String state=text(day,"state").equals("NO_GAMES")?"NO_GAMES":text(day,"state").equals("PREFILLING")?"PREFILLING":List.of("QUEUED","RUNNING").contains(text(day,"odds_import.state"))?"WAITING_FOR_ODDS":"READY";
        for(JsonNode game:day.path("dataset").path("games")){
            String id=text(game,"game_id");if(!gameId.isBlank()&&!gameId.equals(id))continue;found=true;
            Set<String> refs=references(game);ArrayNode evidence=arr();for(JsonNode e:day.path("dataset").path("evidence"))if(refs.contains(text(e,"id")))evidence.add(e);
            ArrayNode quotes=state.equals("READY")?MarketQuotes.all(game,now):arr(),band=arr(),briefs=arr();for(JsonNode quote:quotes){briefs.add(priceBrief(quote));if(quote.path("in_preferred_band").asBoolean())band.add(quote);}
            ArrayNode warnings=arr();for(String side:List.of("home","away")){
                if(!text(game,side+".goalie.status").equals("confirmed"))warnings.add(side.toUpperCase()+"_GOALIE_NOT_CONFIRMED");
                for(String group:List.of("goalie","roster"))if(old(text(game,side+"."+group+".source.checked_at"),now,21600))warnings.add(side.toUpperCase()+"_"+group.toUpperCase()+"_CHECK_STALE");
            }
            if(quotes.valueStream().noneMatch(OddizeOdds::managed))warnings.add("NO_BACKEND_ODDIZE_QUOTES_DO_NOT_WEB_SEARCH_PRICES");
            if(band.isEmpty())warnings.add("NO_QUOTE_IN_1_18_1_35_BAND");
            games.add(obj("game_id",id,"starts_at",text(game,"starts_at"),"status",text(game,"status"),"basis_id",gameBasis(game,now),"home",game.path("home"),"away",game.path("away"),
                "analysis_context",game.path("analysis_context").isArray()?game.path("analysis_context"):arr(),"checks",game.path("checks"),"evidence",evidence,"markets",briefs,"candidates",band,"data_warnings",warnings));
        }
        if(!found)throw new IllegalArgumentException("Unknown game_id");
        return obj("day",text(day,"day"),"state",state,"revision",day.path("revision"),"analysis_id",analysisId(day,now),"research_complete",day.path("research_complete").asBoolean(),
            "odds_import",day.path("odds_import"),"coverage",Checks.coverage(day.path("dataset")),"odds_min",MarketQuotes.MIN_ODDS,"odds_max",MarketQuotes.MAX_ODDS,"games",games,
            "instruction","DATA ONLY. Oddize is the only source of selectable prices, collected once at 19:45 Europe/Bratislava. QUOTED offers only: no estimates, interpolation or web research for odds. Research goalies, lineups and context as before. Compare both attacks and all offered market types, current/prior 60/40 with sample uncertainty and concrete counterarguments. LOW TIP is allowed when defensible. Choose at most one selectable candidate in 1.18-1.35 per game or NO_BET. Both regulation and official_final (OT/SO) are allowed only with verified bookmaker rules; never change score_basis. Missing categories are unavailable, not permission to invent or web-search a price. Save research only, finalize, reload one current snapshot and post_tips for every game. No model, simulations, probabilities or EV.");
    }
    static ObjectNode view(JsonNode day,Instant now){
        JsonNode saved=day.path("final_tips");if(!saved.isObject())return obj("state",text(day,"state").equals("NO_GAMES")?"NO_GAMES":"AWAITING_ANALYSIS","games",arr());
        Map<String,JsonNode> scores=new HashMap<>();for(JsonNode score:day.path("scoreboard").path("games"))scores.put(text(score,"game_id"),score);
        ArrayNode games=arr();int tips=0,noBet=0,wins=0,losses=0,pushes=0;
        for(JsonNode entry:saved.path("games")){
            ObjectNode decision=entry.deepCopy();decision.put("state","CURRENT"); // Current published version, regardless of match status.
            // A published decision is an historical fact. Age, a new research run or puck drop cannot erase it.
            // The previous data-only policy already stores real quotes; preserve its complete historical metadata.
            if(!List.of(MarketQuotes.VERSION,"3.1.0-regulation-totals","3.0.0-data").contains(text(saved,"policy_version"))&&decision.path("candidate").isObject()){
                ObjectNode cleaned=obj();for(String key:List.of("id","candidate_id","kind","selection","line","team","score_basis","price","odds","estimated_odds","odds_type","source","rules_url","confidence","recommendation_status"))
                    if(decision.path("candidate").has(key))cleaned.set(key,decision.path("candidate").path(key));decision.set("candidate",cleaned);
            }
            JsonNode score=scores.get(text(entry,"game_id"));ObjectNode settlement=TipSettlement.evaluate(decision,score);decision.set("settlement",settlement);
            if(text(entry,"decision").equals("TIP"))tips++;else noBet++;
            switch(text(settlement,"state")){case "WIN" -> wins++;case "LOSS" -> losses++;case "PUSH" -> pushes++;default -> {}}
            games.add(decision);
        }
        return obj("state","PUBLISHED","published_at",saved.path("published_at"),"analysis_id",saved.path("analysis_id"),"tip_count",tips,"no_bet_count",noBet,"reviewed_count",games.size(),
            "wins",wins,"losses",losses,"pushes",pushes,"games",games,"chosen_by","CHATGPT");
    }
    static ObjectNode prepare(JsonNode day,JsonNode args,Instant now){
        validateInput(args);if(List.of("QUEUED","RUNNING").contains(text(day,"odds_import.state")))throw new IllegalArgumentException("Wait for the backend Oddize catalogue to finish, then reload get_analysis.");
        if(text(day,"state").equals("PREFILLING")||!day.path("research_complete").asBoolean()||!Checks.audit(day.path("dataset")).path("complete").asBoolean())throw new IllegalArgumentException("Finish research and finalize_day first. No calculation is required.");
        if(!text(args,"analysis_id").equals(analysisId(day,now)))throw new IllegalArgumentException("Data snapshot changed. Reload get_analysis and re-evaluate.");
        Map<String,JsonNode> games=new LinkedHashMap<>();for(JsonNode g:day.path("dataset").path("games"))games.put(text(g,"game_id"),g);
        Set<String> seen=new HashSet<>();ArrayNode decisions=arr();
        for(JsonNode request:args.path("decisions")){
            String id=text(request,"game_id");JsonNode game=games.get(id);if(game==null||!seen.add(id))throw new IllegalArgumentException("Unknown or duplicate game_id: "+id);
            if(text(request,"rationale").strip().length()<40||text(request,"comparison").strip().length()<20)throw new IllegalArgumentException("Provide a substantive rationale and comparison");
            if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now)){
                if(!text(request,"decision").equals("NO_BET"))throw new IllegalArgumentException("Started games cannot receive a new pregame TIP");
                JsonNode previous=null;for(JsonNode prior:day.path("final_tips").path("games"))if(text(prior,"game_id").equals(id))previous=prior;
                if(previous!=null){ObjectNode retained=previous.deepCopy();retained.put("retained_previous_analysis",true);if(!retained.has("analyzed_at"))retained.set("analyzed_at",day.path("final_tips").path("published_at"));decisions.add(retained);continue;}
            }
            Set<String> refs=references(game),actual=new HashSet<>();for(JsonNode e:day.path("dataset").path("evidence"))actual.add(text(e,"id"));
            for(String eid:strings(request.path("evidence_ids")))if(!refs.contains(eid)||!actual.contains(eid))throw new IllegalArgumentException("Evidence does not belong to this game: "+eid);
            ObjectNode decision=request.deepCopy();decision.put("basis_id",gameBasis(game,now));decision.put("chosen_by","CHATGPT");decision.put("analyzed_at",now.toString());
            if(text(request,"decision").equals("NO_BET")){if(!request.path("candidate_id").isNull())throw new IllegalArgumentException("NO_BET must have candidate_id=null");decision.putNull("candidate");}
            else{
                if(day.path("dataset").path("synthetic_data").asBoolean())throw new IllegalArgumentException("Cannot publish a real TIP from synthetic data");
                if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now))throw new IllegalArgumentException("Cannot publish a pregame tip after the start");
                JsonNode selected=null;for(JsonNode quote:MarketQuotes.all(game,now))if(text(quote,"candidate_id").equals(text(request,"candidate_id")))selected=quote;
                if(selected!=null&&(!OddizeOdds.managed(selected)||!text(selected,"odds_type").equals("QUOTED")))throw new IllegalArgumentException("Only backend Oddize QUOTED offers may be selected. Do not research or submit replacement prices.");
                if(selected==null||!selected.path("selectable").asBoolean())throw new IllegalArgumentException("Select a current selectable price in 1.18-1.35 with verified settlement; read blocking_reasons.");
                if(request.path("evidence_ids").isEmpty())throw new IllegalArgumentException("TIP requires supporting evidence_ids");
                ObjectNode quote=selected.deepCopy();quote.put("confidence",text(request,"confidence"));quote.put("recommendation_status","GPT_SELECTION");decision.set("candidate",quote);
            }decisions.add(decision);
        }
        if(seen.size()!=games.size())throw new IllegalArgumentException("Post one TIP or NO_BET decision for EVERY official game");
        return obj("policy_version",MarketQuotes.VERSION,"analysis_id",text(args,"analysis_id"),"published_at",now.toString(),"data_revision",day.path("revision"),"games",decisions,"chosen_by","CHATGPT");
    }
    static String requestSignature(JsonNode args){ObjectNode copy=args.deepCopy();copy.remove("revision");return OAuth.hash(canonical(copy).toString());}
}
