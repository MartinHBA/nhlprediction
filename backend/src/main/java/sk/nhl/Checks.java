package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

final class Checks {
    static final List<String> STATS=List.of("games","points_pct","xgf_5v5_per60","xga_5v5_per60","pp_xgf_per60","pk_xga_per60","pp_drawn_per_game","penalties_taken_per_game","shots_for_per_game","shots_against_per_game","high_danger_for_per60","high_danger_against_per60");
    static final List<String> GOALIE_HISTORY=List.of("starts_current","starts_prior","gsax_per60_current","gsax_per60_prior","save_pct_current","save_pct_prior");
    static final List<String> GOALIE=List.of("name","status","starts_current","starts_prior","gsax_per60_current","gsax_per60_prior","save_pct_current","save_pct_prior");
    // Older published snapshots keep their original checklist and are never backfilled on a public read.
    static List<String> paths(JsonNode game){return PATHS.stream().filter(p->!p.contains(".goalie.save_pct_")||!at(game,p).isMissingNode()||game.path("checks").path("fields").has(p)).toList();}
    static final List<String> ROSTER=List.of("quantified","attack_delta_goals","defense_delta_goals_allowed","absences","returns","scratches","forward_lines","defense_pairs","power_play_units");
    static final List<String> CONTEXT=List.of("rest_days","travel_timezones");
    static final List<String> SEARCHES=List.of("total_over","total_under","handicap_home","handicap_away","moneyline_home","moneyline_away","regulation_1x2","double_chance","team_total_under","team_total_over_other");
    static final List<String> RATE=List.of("games","xgf_5v5_per60","xga_5v5_per60","pp_xgf_per60","pk_xga_per60","pp_drawn_per_game","penalties_taken_per_game");
    static final List<String> PATHS=new ArrayList<>();
    static final Set<String> REQUIRED=new LinkedHashSet<>();
    static final JsonSchema SCHEMA=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource("schema.json"),formatConfig());
    static SchemaValidatorsConfig formatConfig(){SchemaValidatorsConfig config=new SchemaValidatorsConfig();config.setFormatAssertionsEnabled(true);return config;}
    static {
        for(String side:List.of("home","away")) {
            for(String period:List.of("prior","current")) for(String f:STATS) PATHS.add(side+"."+period+"."+f);
            for(String f:GOALIE)PATHS.add(side+".goalie."+f);
            for(String f:ROSTER)PATHS.add(side+".roster."+f);
            for(String f:CONTEXT)PATHS.add(side+".context."+f);
            for(String period:List.of("prior","current"))for(String f:RATE)REQUIRED.add(side+"."+period+"."+f);
            for(String f:CONTEXT)REQUIRED.add(side+".context."+f);
        }
    }
    static Instant time(String s) { return OffsetDateTime.parse(s).toInstant(); }
    static boolean matches(String search,JsonNode m) {
        String k=text(m,"kind"),s=text(m,"selection");
        return switch(search) {
            case "total_over","total_under" -> k.equals("total") && search.endsWith("_"+s);
            case "handicap_home","handicap_away" -> k.equals("handicap") && search.endsWith("_"+s);
            case "moneyline_home","moneyline_away" -> k.equals("moneyline") && search.endsWith("_"+s);
            case "regulation_1x2","double_chance" -> k.equals(search);
            case "team_total_under" -> k.equals("team_total") && s.equals("under");
            case "team_total_over_other" -> k.equals("team_total") && s.equals("over") && m.path("line").asDouble()!=2.5;
            default -> false;
        };
    }
    static List<String> validate(JsonNode d) {
        List<String> errors=new ArrayList<>(); SCHEMA.validate(d).forEach(e->errors.add("SCHEMA "+e.getMessage()));
        if(!errors.isEmpty())return errors;
        try {
            Instant cutoff=time(text(d,"as_of")); Set<String> ids=new HashSet<>();
            Map<String,JsonNode> proofs=new HashMap<>();for(JsonNode proof:d.path("evidence"))proofs.put(text(proof,"id"),proof);
            Set<String> expected=new HashSet<>(strings(d.path("expected_game_ids")));
            if(d.path("games").isEmpty() && !expected.isEmpty())errors.add("SCHEDULE_MISMATCH");
            for(JsonNode g:d.path("games")) {
                String id=text(g,"game_id"); if(!ids.add(id))errors.add(id+": DUPLICATE_GAME");
                Instant start=time(text(g,"starts_at")); boolean scheduled=text(g,"status").equals("scheduled");
                if(scheduled && !start.isAfter(cutoff))errors.add(id+": scheduled game starts before as_of");
                if(text(g,"home.name").equals(text(g,"away.name")))errors.add(id+": same teams");
                for(String side:List.of("home","away")) {
                    JsonNode t=g.path(side),r=t.path("roster"),goalie=t.path("goalie");
                    if(r.path("quantified").asBoolean() && (missing(r.path("attack_delta_goals"))||missing(r.path("defense_delta_goals_allowed"))))errors.add(id+"/"+side+": quantified lineup has null deltas");
                    for(String group:List.of("prior","current","goalie","roster")) {
                        Instant checked=time(text(t,group+".source.checked_at"));
                        if(checked.isAfter(cutoff)||(scheduled&&checked.isAfter(start)))errors.add(id+"/"+side+": source checked after prediction cutoff");
                    }
                    for(String p:List.of("current","prior"))if(goalie.path("starts_"+p).asInt()>0&&missing(goalie.path("gsax_per60_"+p)))errors.add(id+"/"+side+": goalie starts >0 but GSAx null");
                }
                if(!scheduled&&!g.path("markets").isEmpty())errors.add(id+": live/final game has pregame markets");
                List<JsonNode> sourced=new ArrayList<>();g.path("analysis_context").forEach(sourced::add);g.path("markets").forEach(sourced::add);
                for(JsonNode entry:sourced)for(String ref:strings(entry.path("evidence_ids"))){JsonNode proof=proofs.get(ref);if(proof==null)errors.add(id+": unknown context/price evidence "+ref);else if(time(text(proof,"checked_at")).isAfter(cutoff.isBefore(start)?cutoff:start))errors.add(id+": context/price evidence violates pregame cutoff");}
                Set<String> marketIds=new HashSet<>();
                for(JsonNode m:g.path("markets")) {
                    String k=text(m,"kind"),s=text(m,"selection"),basis=text(m,"score_basis"); JsonNode line=m.path("line");
                    if(!marketIds.add(text(m,"id")))errors.add(id+": duplicate market id");
                    if(time(text(m,"source.checked_at")).isAfter(cutoff.isBefore(start)?cutoff:start))errors.add(id+": market violates pregame cutoff");
                    boolean valid=switch(k) {
                        case "total","team_total" -> List.of("over","under").contains(s)&&!missing(line)&&line.asDouble()>=0&&(k.equals("total")||m.has("team"));
                        case "handicap" -> List.of("home","away").contains(s)&&!missing(line)&&text(m,"team").equals(s);
                        case "moneyline" -> List.of("home","away").contains(s)&&line.isNull()&&basis.equals("official_final");
                        case "regulation_1x2" -> List.of("home","away","draw").contains(s)&&line.isNull()&&basis.equals("regulation");
                        case "double_chance" -> List.of("1X","X2","12").contains(s)&&line.isNull()&&basis.equals("regulation");
                        default -> false;
                    };
                    if(!valid)errors.add(id+"/"+text(m,"id")+": invalid "+k+" definition");
                    if(!List.of("team_total","handicap").contains(k)&&m.has("team"))errors.add(id+": unexpected market team");
                    if(m.path("settlement_verified").asBoolean()&&missing(m.path("rules_url")))errors.add(id+": verified settlement requires rules_url");
                    if(!missing(line)&&Math.abs(line.asDouble()*2-Math.rint(line.asDouble()*2))>1e-8)errors.add(id+": whole/half goal lines only");
                }
            }
            if(!ids.equals(expected)||ids.size()!=d.path("expected_game_ids").size())errors.add("SCHEDULE_MISMATCH");
        } catch(Exception e) { errors.add("INVALID_TIMESTAMP: "+e.getMessage()); }
        return errors;
    }
    static ObjectNode audit(JsonNode d) {
        Map<String,JsonNode> evidence=new HashMap<>(); ArrayNode issues=arr(),games=arr(); int[] counts={0,0,0};
        for(JsonNode e:d.path("evidence"))if(evidence.put(text(e,"id"),e)!=null)issues.add("DUPLICATE_EVIDENCE_ID");
        Instant cutoff=time(text(d,"as_of"));
        for(JsonNode g:d.path("games")) {
            ArrayNode unchecked=arr(),unavailable=arr(),missing=arr(),marketUnchecked=arr(),local=arr();
            if(!text(g,"status").equals("scheduled")) { games.add(obj("game_id",text(g,"game_id"),"status","SKIPPED_ALREADY_STARTED","unchecked",unchecked,"unavailable",unavailable,"missing_core_fields",missing,"market_categories_unchecked",marketUnchecked,"issues",local,"checked_count",0));continue; }
            Instant start=time(text(g,"starts_at")); Instant limit=cutoff.isBefore(start)?cutoff:start;
            for(String p:paths(g)) {
                JsonNode v=at(g,p),c=g.path("checks").path("fields").path(p);
                check(p,c,v,evidence,limit,unchecked,unavailable,local,counts);
                if(REQUIRED.contains(p)&&(Json.missing(v)||!text(c,"status").equals("found")))missing.add(p);
            }
            g.path("checks").path("fields").fieldNames().forEachRemaining(p->{if(!PATHS.contains(p))local.add(p+": UNKNOWN_FIELD_CHECK");});
            for(String k:SEARCHES) {
                JsonNode c=g.path("checks").path("market_searches").path(k); boolean found=false;
                for(JsonNode m:g.path("markets"))if(matches(k,m))found=true;
                check("market_searches."+k,c,found?BooleanNode.TRUE:NullNode.instance,evidence,limit,unchecked,unavailable,local,counts);
                if(text(c,"status").equals("not_checked")||c.isMissingNode())marketUnchecked.add(k);
            }
            g.path("checks").path("market_searches").fieldNames().forEachRemaining(k->{if(!SEARCHES.contains(k))local.add(k+": UNKNOWN_MARKET_CATEGORY");});
            for(JsonNode issue:local)issues.add(text(g,"game_id")+": "+issue.asText());
            long fieldUnchecked=strings(unchecked).stream().filter(x->!x.startsWith("market_searches.")).count();
            games.add(obj("game_id",text(g,"game_id"),"status",missing.isEmpty()&&unchecked.isEmpty()&&local.isEmpty()?"CORE_DATA_AVAILABLE":"INCOMPLETE","unchecked",unchecked,"unavailable",unavailable,"missing_core_fields",missing,"market_categories_unchecked",marketUnchecked,"issues",local,"checked_count",paths(g).size()-(int)fieldUnchecked));
        }
        return obj("games",games,"found_total",counts[0],"unavailable_total",counts[1],"unchecked_total",counts[2],"issues",issues,"complete",issues.isEmpty()&&counts[2]==0);
    }
    /** Data availability is distinct from having closed a checklist item as unavailable. */
    static ObjectNode coverage(JsonNode data) {
        int found=0,unavailable=0,total=0,critical=0,criticalTotal=0,ready=0,scheduled=0;ArrayNode games=arr();
        for(JsonNode g:data.path("games")) {
            if(!text(g,"status").equals("scheduled"))continue;scheduled++;int f=0,u=0,c=0;
            for(String p:paths(g)){JsonNode check=g.path("checks").path("fields").path(p);boolean present=text(check,"status").equals("found")&&!missing(at(g,p));if(present)f++;else if(text(check,"status").equals("unavailable"))u++;if(present&&REQUIRED.contains(p))c++;}
            found+=f;unavailable+=u;total+=paths(g).size();critical+=c;criticalTotal+=REQUIRED.size();if(c==REQUIRED.size())ready++;
            games.add(obj("game_id",text(g,"game_id"),"field_found",f,"field_total",paths(g).size(),"critical_found",c,"critical_total",REQUIRED.size(),"core_complete",c==REQUIRED.size()));
        }
        ObjectNode audit=audit(data);int all=audit.path("found_total").asInt()+audit.path("unavailable_total").asInt()+audit.path("unchecked_total").asInt();
        return obj("field_found",found,"field_unavailable",unavailable,"field_total",total,"field_found_pct",total==0?0.0:100.0*found/total,"critical_found",critical,"critical_total",criticalTotal,"critical_ready_games",ready,"scheduled_games",scheduled,"checklist_closed",all-audit.path("unchecked_total").asInt(),"checklist_total",all,"games",games);
    }
    private static void check(String path,JsonNode c,JsonNode value,Map<String,JsonNode> evidence,Instant limit,ArrayNode unchecked,ArrayNode unavailable,ArrayNode issues,int[] counts) {
        String s=c.path("status").asText("not_checked");
        if(s.equals("not_checked")){counts[2]++;unchecked.add(path);return;}
        if(s.equals("found"))counts[0]++;else if(s.equals("unavailable")){counts[1]++;unavailable.add(path);}else issues.add(path+": INVALID_CHECK_STATUS");
        if(c.path("evidence_ids").isEmpty())issues.add(path+": MISSING_EVIDENCE_REFERENCE");
        for(JsonNode id:c.path("evidence_ids")) {
            JsonNode e=evidence.get(id.asText()); if(e==null)issues.add(path+": UNKNOWN_EVIDENCE_ID "+id.asText());
            else if(time(text(e,"checked_at")).isAfter(limit))issues.add(path+": FUTURE_EVIDENCE");
        }
        if(s.equals("found")&&missing(value))issues.add(path+": MARKED_FOUND_BUT_VALUE_NULL");
        if(s.equals("unavailable")&&!missing(value))issues.add(path+": MARKED_UNAVAILABLE_BUT_VALUE_PRESENT");
        if(s.equals("unavailable")&&text(c,"note").isBlank())issues.add(path+": UNAVAILABLE_REQUIRES_REASON");
    }
}
