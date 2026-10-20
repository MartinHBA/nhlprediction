package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** AI-authored decisions stored separately from data collection and deterministic model results. */
final class Tips {
    static ObjectNode inputSchema(){
        ObjectNode decision=Mcp.schema(obj(
            "game_id",obj("type","string","minLength",1),
            "decision",obj("enum",List.of("TIP","NO_BET")),
            "candidate_id",obj("type",List.of("string","null")),
            "confidence",obj("enum",List.of("LOW","MODERATE","HIGH")),
            "rationale",obj("type","string","minLength",40,"maxLength",2000),
            "comparison",obj("type","string","minLength",20,"maxLength",2000),
            "risks",obj("type","array","maxItems",8,"items",obj("type","string","minLength",5,"maxLength",400)),
            "evidence_ids",obj("type","array","maxItems",20,"uniqueItems",true,"items",obj("type","string","minLength",1))),
            "game_id","decision","candidate_id","confidence","rationale","comparison","risks","evidence_ids");
        return Mcp.schema(obj("day",obj("type","string","pattern","^\\d{4}-\\d{2}-\\d{2}$"),
            "revision",obj("type","integer","minimum",0),"analysis_id",obj("type","string","minLength",1),
            "decisions",obj("type","array","maxItems",32,"items",decision)),"day","revision","analysis_id","decisions");
    }
    static void validateInput(JsonNode args){
        var schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(inputSchema());
        var errors=schema.validate(args);if(!errors.isEmpty())throw new IllegalArgumentException(errors.iterator().next().getMessage());
    }
    static JsonNode canonical(JsonNode n){
        if(n.isObject()){ObjectNode out=obj();List<String> keys=new ArrayList<>();n.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)out.set(key,canonical(n.path(key)));return out;}
        if(n.isArray()){ArrayNode out=arr();for(JsonNode v:n)out.add(canonical(v));return out;}
        if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());
        return n.deepCopy();
    }
    static void removeCheckTimes(JsonNode n){
        if(n.isObject()){((ObjectNode)n).remove("checked_at");n.forEach(Tips::removeCheckTimes);}
        else if(n.isArray())n.forEach(Tips::removeCheckTimes);
    }
    static String gameBasis(JsonNode game,Instant now){
        ObjectNode copy=game.deepCopy();copy.remove("checks");removeCheckTimes(copy);
        List<JsonNode> quotes=new ArrayList<>();copy.path("markets").forEach(quotes::add);quotes.sort(Comparator.comparing(m->canonical(m).toString()));copy.set("markets",M.valueToTree(quotes));
        ObjectNode age=obj();for(String side:List.of("home","away"))for(String group:List.of("goalie","roster"))
            age.put(side+"."+group,old(text(game,side+"."+group+".source.checked_at"),now,21600));
        ObjectNode statuses=obj();for(String group:List.of("fields","market_searches"))game.path("checks").path(group).fields().forEachRemaining(e->statuses.put(group+"."+e.getKey(),text(e.getValue(),"status")));
        List<String> staleIds=new ArrayList<>();for(JsonNode quote:game.path("markets"))if(old(text(quote,"source.checked_at"),now,7200))staleIds.add(text(quote,"id"));Collections.sort(staleIds);JsonNode staleQuotes=M.valueToTree(staleIds);
        return OAuth.hash(canonical(obj("engine",Engine.VERSION,"sims",Engine.SIMULATIONS,"min_odds",Recommendations.MIN_ODDS,"max_odds",Recommendations.MAX_ODDS,"game",copy,"check_statuses",statuses,"old_checks",age,"stale_quotes",staleQuotes)).toString());
    }
    static boolean old(String time,Instant now,long seconds){try{return Checks.time(time).isBefore(now.minusSeconds(seconds));}catch(RuntimeException e){return true;}}
    static String analysisId(JsonNode day,Instant now){
        ObjectNode games=obj();for(JsonNode game:day.path("dataset").path("games"))games.put(text(game,"game_id"),gameBasis(game,now));
        return "analysis-"+OAuth.hash(canonical(games).toString());
    }
    static boolean calculated(JsonNode day,Instant now){
        if(!List.of("COMPLETE","INCOMPLETE").contains(text(day,"state"))||!text(day,"report.engine_version").equals(Engine.VERSION))return false;
        Instant asOf;try{asOf=Checks.time(text(day,"report.as_of"));}catch(RuntimeException e){return false;}
        for(JsonNode game:day.path("dataset").path("games"))if(!gameBasis(game,now).equals(gameBasis(game,asOf)))return false;
        return true;
    }
    static JsonNode reportGame(JsonNode day,String id){for(JsonNode game:day.path("report").path("games"))if(text(game,"game_id").equals(id))return game;return obj();}
    static Set<String> references(JsonNode game){
        Set<String> refs=new LinkedHashSet<>();for(JsonNode check:game.path("checks").path("fields"))refs.addAll(strings(check.path("evidence_ids")));
        for(JsonNode check:game.path("checks").path("market_searches"))refs.addAll(strings(check.path("evidence_ids")));return refs;
    }
    static ObjectNode candidates(JsonNode day,String gameId,Instant now){
        String state=text(day,"state").equals("NO_GAMES")?"NO_GAMES":calculated(day,now)?"READY":"CALCULATION_REQUIRED";
        ArrayNode games=arr();boolean found=gameId.isBlank();
        for(JsonNode game:day.path("dataset").path("games")){
            String id=text(game,"game_id");if(!gameId.isBlank()&&!gameId.equals(id))continue;found=true;
            JsonNode result=reportGame(day,id);ArrayNode evidence=arr();Set<String> refs=references(game);
            for(JsonNode e:day.path("dataset").path("evidence"))if(refs.contains(text(e,"id")))evidence.add(e);
            games.add(obj("game_id",id,"starts_at",text(game,"starts_at"),"status",text(game,"status"),"basis_id",gameBasis(game,now),
                "home",game.path("home"),"away",game.path("away"),"evidence",evidence,
                "projection",obj("lambda_home",result.path("lambda_home"),"lambda_away",result.path("lambda_away"),"details",result.path("projection_details")),
                "candidates",state.equals("READY")?result.path("candidates"):arr(),"skipped",result.path("skipped")));
        }
        if(!found)throw new IllegalArgumentException("Unknown game_id");
        return obj("day",text(day,"day"),"state",state,"revision",day.path("revision"),"analysis_id",analysisId(day,now),
            "simulations",Engine.SIMULATIONS,"odds_min",Recommendations.MIN_ODDS,"odds_max",Recommendations.MAX_ODDS,"games",games,
            "instruction",state.equals("READY")?"Evaluate the research and compare all candidate types. Java is an uncalibrated input, not the final decision. Post exactly one TIP or NO_BET decision per official game with this analysis_id and latest revision. Do not force variety or a pick. Never fabricate prices or probabilities. Estimated prices remain conditional; model disagreement must be explicit.":"Finish collection and finalize_day(sims=1000), then poll get_results and reload get_candidates.");
    }
    static ObjectNode view(JsonNode day,Instant now){
        JsonNode saved=day.path("final_tips");if(!saved.isObject())return obj("state",text(day,"state").equals("NO_GAMES")?"NO_GAMES":"AWAITING_ANALYSIS","games",arr());
        Map<String,JsonNode> current=new HashMap<>();for(JsonNode g:day.path("dataset").path("games"))current.put(text(g,"game_id"),g);
        ArrayNode games=arr();boolean review=current.size()!=saved.path("games").size();int tips=0,noBet=0;
        for(JsonNode entry:saved.path("games")){
            ObjectNode decision=entry.deepCopy();JsonNode game=current.get(text(entry,"game_id"));String state="CURRENT";
            if(game==null)state="NEEDS_REVIEW";
            else if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now))state="STARTED";
            else if(!text(entry,"basis_id").equals(gameBasis(game,now)))state="NEEDS_REVIEW";
            decision.put("state",state);if(state.equals("NEEDS_REVIEW"))review=true;
            if(state.equals("CURRENT")){if(text(entry,"decision").equals("TIP"))tips++;else noBet++;}
            games.add(decision);
        }
        return obj("state",review?"NEEDS_REVIEW":"PUBLISHED","published_at",saved.path("published_at"),"analysis_id",saved.path("analysis_id"),
            "tip_count",tips,"no_bet_count",noBet,"games",games,"chosen_by","CHATGPT");
    }
    static ObjectNode prepare(JsonNode day,JsonNode args,Instant now){
        validateInput(args);
        if(!calculated(day,now)||!day.path("research_complete").asBoolean()||!day.path("report").path("checklist").path("complete").asBoolean())throw new IllegalArgumentException("Calculation/research not current. Complete research, finalize_day and reload get_candidates.");
        if(!text(args,"analysis_id").equals(analysisId(day,now)))throw new IllegalArgumentException("Analysis snapshot changed. Reload get_candidates and re-evaluate.");
        Map<String,JsonNode> games=new LinkedHashMap<>();for(JsonNode g:day.path("dataset").path("games"))games.put(text(g,"game_id"),g);
        Set<String> seen=new HashSet<>();ArrayNode decisions=arr();
        for(JsonNode request:args.path("decisions")){
            String id=text(request,"game_id");JsonNode game=games.get(id);
            if(game==null||!seen.add(id))throw new IllegalArgumentException("Unknown or duplicate game_id: "+id);
            if(text(request,"rationale").strip().length()<40||text(request,"comparison").strip().length()<20)throw new IllegalArgumentException("Provide a substantive rationale and comparison");
            Set<String> refs=references(game);Set<String> actual=new HashSet<>();for(JsonNode e:day.path("dataset").path("evidence"))actual.add(text(e,"id"));
            for(String eid:strings(request.path("evidence_ids")))if(!refs.contains(eid)||!actual.contains(eid))throw new IllegalArgumentException("Evidence does not belong to this game: "+eid);
            ObjectNode decision=request.deepCopy();decision.put("basis_id",gameBasis(game,now));decision.put("chosen_by","CHATGPT");
            if(text(request,"decision").equals("NO_BET")){
                if(!request.path("candidate_id").isNull())throw new IllegalArgumentException("NO_BET must have candidate_id=null");
                decision.putNull("candidate");
            }else{
                if(day.path("dataset").path("synthetic_data").asBoolean())throw new IllegalArgumentException("Cannot publish a real TIP from synthetic data");
                if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now))throw new IllegalArgumentException("Cannot publish a pregame tip after the start");
                JsonNode selected=null;for(JsonNode c:reportGame(day,id).path("candidates"))if(text(c,"candidate_id").equals(text(request,"candidate_id")))selected=c;
                if(selected==null||!selected.path("selectable").asBoolean()||!Recommendations.inRange(number(selected,"price")))throw new IllegalArgumentException("Select a current, selectable candidate in 1.20–1.35");
                if(request.path("evidence_ids").isEmpty())throw new IllegalArgumentException("TIP requires supporting evidence_ids");
                ObjectNode candidate=selected.deepCopy();ArrayNode reasons=arr();
                boolean low=!candidate.path("model_positive_ev").asBoolean()||!candidate.path("model_robust").asBoolean();
                if(!candidate.path("model_positive_ev").asBoolean())reasons.add("MODEL_DISAGREES_WITH_AI_SELECTION");
                if(!candidate.path("model_robust").asBoolean())reasons.add("SCENARIO_EDGE_NOT_ROBUST");
                if(Duration.between(now,Checks.time(text(game,"starts_at"))).toSeconds()>28800){low=true;reasons.add("FINAL_LINEUP_WINDOW_NOT_REACHED");}
                if(text(candidate,"odds_type").equals("ESTIMATED"))reasons.add("ESTIMATED_ODDS_NOT_VERIFIED");
                for(String side:List.of("home","away")){
                    if(!text(game,side+".goalie.status").equals("confirmed")){low=true;reasons.add(side.toUpperCase()+"_GOALIE_NOT_CONFIRMED");}
                    if(number(game,side+".goalie.starts_current")+number(game,side+".goalie.starts_prior")<10){low=true;reasons.add(side.toUpperCase()+"_GOALIE_INSUFFICIENT_HISTORY");}
                    if(!at(game,side+".roster.quantified").asBoolean()){low=true;reasons.add(side.toUpperCase()+"_LINEUP_IMPACT_UNQUANTIFIED");}
                    for(String group:List.of("goalie","roster"))if(old(text(game,side+"."+group+".source.checked_at"),now,21600)){low=true;reasons.add(side.toUpperCase()+"_"+group.toUpperCase()+"_STALE_OVER_6_HOURS");}
                }
                if(!reasons.isEmpty()&&request.path("risks").isEmpty())throw new IllegalArgumentException("Conditional tip requires an explicit risk explanation");
                String requested=text(request,"confidence"),confidence=low?"LOW":text(candidate,"odds_type").equals("ESTIMATED")&&requested.equals("HIGH")?"MODERATE":requested;
                if(requested.equals("LOW"))confidence="LOW";
                candidate.put("confidence",confidence);candidate.set("confidence_reasons",reasons);candidate.put("recommendation_status",reasons.isEmpty()?"REVIEW_REQUIRED":"PRELIMINARY");
                candidate.put("robust_edge",candidate.path("model_robust").asBoolean());candidate.put("confidence_basis","AI_ASSESSMENT_CAPPED_BY_INPUT_QUALITY");
                decision.put("requested_confidence",requested);decision.put("confidence",confidence);decision.set("candidate",candidate);
            }
            decisions.add(decision);
        }
        if(seen.size()!=games.size())throw new IllegalArgumentException("Post one TIP or NO_BET decision for EVERY official game");
        return obj("analysis_id",text(args,"analysis_id"),"published_at",now.toString(),"data_revision",day.path("revision"),"games",decisions,"chosen_by","CHATGPT");
    }
    static String requestSignature(JsonNode args){ObjectNode copy=args.deepCopy();copy.remove("revision");return OAuth.hash(canonical(copy).toString());}
}
