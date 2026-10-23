package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** One authoritative day catalogue: real main lines, alternates and team totals, with provenance. */
final class OddizeOdds implements OddsSource {
    static final String PREFIX="oddize-",EVIDENCE_PREFIX="auto-oddize-";
    static final ZoneId ZONE=ZoneId.of("Europe/Bratislava");
    static final Map<String,String> ALIASES=Map.ofEntries(Map.entry("MON","MTL"),Map.entry("CBS","CBJ"),Map.entry("UTH","UTA"),Map.entry("WAS","WSH"),Map.entry("WIN","WPG"));
    final OddizeClient client;final Clock clock;
    OddizeOdds(Store store){this(new OddizeClient(store,Clock.systemUTC()),Clock.systemUTC());}
    OddizeOdds(OddizeClient client,Clock clock){this.client=client;this.clock=clock;}
    static boolean managed(JsonNode quote){return text(quote,"id").startsWith(PREFIX);}
    static String team(String code){String normalized=code.toUpperCase(Locale.ROOT);return ALIASES.getOrDefault(normalized,normalized);}
    static Instant instant(String value){return OffsetDateTime.parse(value.replace(' ','T')).toInstant();}
    static boolean due(String day,Instant now){return !now.isBefore(LocalDate.parse(day).atTime(19,45).atZone(ZONE).toInstant().minusSeconds(15));}
    // Tolerate an early/late :45 invocation; the UTC hourly cron still fires only once in this local window.
    static boolean snapshotWindow(Instant now){var local=now.atZone(ZONE);int minutes=local.getHour()*60+local.getMinute();return minutes>=19*60+30&&minutes<20*60+15;}
    static ObjectNode pending(String day){return obj("provider","Oddize","state","SCHEDULED","requested_day",day,"scheduled_at",LocalDate.parse(day).atTime(19,45).atZone(ZONE).toInstant().toString(),"scheduled_local_time","19:45","poll_timezone","Europe/Bratislava","market_count",0,"covered_games",0,"complete_snapshot",false);}
    @Override public synchronized ObjectNode collect(String day,JsonNode original){
        client.requests=0;client.creditsUsed=0;client.creditsRemaining=null;
        Schedule.day(day);Instant now=clock.instant();ObjectNode data=original.deepCopy();ArrayNode warnings=arr();Map<String,ObjectNode> games=new LinkedHashMap<>();Map<String,Map<String,ObjectNode>> prices=new LinkedHashMap<>();
        for(JsonNode item:data.path("games")){
            ObjectNode game=(ObjectNode)item;if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now)){if(text(game,"status").equals("scheduled"))game.put("status","live");game.set("markets",arr());continue;}
            String id=text(game,"game_id");games.put(id,game);prices.put(id,new LinkedHashMap<>());
        }
        if(games.isEmpty())return obj("dataset",data,"odds_import",obj("provider","Oddize","state","NO_UPCOMING_GAMES","requested_day",day,"upcoming_games",0,"covered_games",0,"market_count",0,"complete_snapshot",true,"checked_at",now.toString()));
        String mainPath="/odds/latest?sport=nhl&date="+day+"&limit=100";ObjectNode main=null;Map<String,JsonNode> events=new LinkedHashMap<>();int failures=0;
        try{
            main=client.get(day,mainPath,1);JsonNode body=main.path("body");if(!text(body,"sport").equals("nhl")||!body.path("events").isArray())throw new IllegalStateException("Unexpected Oddize NHL catalogue");
            for(JsonNode event:body.path("events")){
                String matched=match(day,games,event,now);if(matched==null)continue;
                if(events.putIfAbsent(matched,event)!=null)throw new IllegalStateException("Duplicate Oddize event for NHL game "+matched);
            }
            for(var entry:events.entrySet())addPrices(data,games.get(entry.getKey()),entry.getValue(),entry.getValue().path("odds"),null,null,main,prices.get(entry.getKey()),warnings,now);
        }catch(RuntimeException e){failures++;warnings.add("Main NHL prices: "+safeError(e));events.clear();}
        if(!events.isEmpty()){
            for(var entry:events.entrySet()){
                String eventId=text(entry.getValue(),"event_id");
                if(!eventId.matches("[A-Za-z0-9_-]{1,100}")){warnings.add(entry.getKey()+": unsupported provider event ID");failures++;continue;}
                for(String type:List.of("alt_t","alt_s"))try{
                    ObjectNode response=client.get(day,"/events/nhl/"+eventId+"/alts?alt_type="+type,2);JsonNode body=response.path("body");
                    if(!text(body,"event_id").equals(eventId)||!text(body,"sport").equals("nhl")||!text(body,"alt_type").equals(type)||!body.path("lines").isArray())throw new IllegalStateException("Alternate response does not match the requested event/type");
                    for(JsonNode line:body.path("lines")){
                        ArrayNode valid=arr();for(JsonNode price:line.path("prices"))if(line.path("line").isNumber()&&price.path("line").isNumber()&&number(line,"line")==number(price,"line"))valid.add(price);else warnings.add(entry.getKey()+": alternate parent/price line mismatch");
                        addPrices(data,games.get(entry.getKey()),entry.getValue(),valid,type,null,response,prices.get(entry.getKey()),warnings,now);
                    }
                }catch(RuntimeException e){failures++;warnings.add(entry.getKey()+"/"+type+": "+safeError(e));}
            }
            try{
                ObjectNode response=client.get(day,"/props/nhl?prop_type=tt_goals",2);JsonNode body=response.path("body");
                if(!text(body,"sport").equals("nhl")||!text(body,"prop_type").equals("tt_goals")||!body.path("events").isArray())throw new IllegalStateException("Unexpected team-total response");
                for(JsonNode event:body.path("events")){
                    String matched=match(day,games,event,now);if(matched==null||!events.containsKey(matched)||!text(events.get(matched),"event_id").equals(text(event,"event_id")))continue;
                    for(JsonNode subject:event.path("players"))addPrices(data,games.get(matched),event,subject.has("all_odds")?subject.path("all_odds"):subject.path("odds"),"tt",text(subject,"player_name"),response,prices.get(matched),warnings,now);
                }
            }catch(RuntimeException e){failures++;warnings.add("Team totals: "+safeError(e));}
        }
        String auditId=EVIDENCE_PREFIX+"catalogue-"+OAuth.hash(day+now).substring(0,16);DataCollector.mergeEvidence(data,obj("id",auditId,"url",OddizeClient.BASE+mainPath,"checked_at",now.toString()));
        int covered=0,count=0,selectable=0;Instant newest=null;
        for(var entry:games.entrySet()){
            ObjectNode game=entry.getValue();Map<String,ObjectNode> nativePrices=prices.get(entry.getKey());checkConsistency(nativePrices.values());
            ArrayNode markets=arr();for(JsonNode prior:game.path("markets"))if(!NhlOdds.managed(prior))markets.add(prior);
            markets.addAll(new ArrayList<>(nativePrices.values()));game.set("markets",markets);
            if(!nativePrices.isEmpty())covered++;count+=nativePrices.size();
            for(ObjectNode price:nativePrices.values()){Instant seen=instant(text(price,"observed_at"));if(newest==null||seen.isAfter(newest))newest=seen;}
            ObjectNode checks=(ObjectNode)game.path("checks").path("market_searches");
            for(String search:Checks.SEARCHES){
                Set<String> proofIds=new LinkedHashSet<>();for(JsonNode price:markets)if(Checks.matches(search,price))proofIds.addAll(strings(price.path("evidence_ids")));
                boolean found=!proofIds.isEmpty();if(!found)proofIds.add(auditId);
                String note=found?"Saved price evidence exists in this category. Only backend Oddize offers can be selected; historical non-Oddize prices remain context. Read freshness, settlement and quality blockers.":List.of("regulation_1x2","double_chance").contains(search)?"Not exposed by the Oddize NHL API catalogue. Do not derive or web-search a replacement.":"No identifiable Oddize offer in this category for this exact game. No replacement price was inferred.";
                checks.set(search,obj("status",found?"found":"unavailable","evidence_ids",proofIds,"note",note));
            }
            for(JsonNode price:MarketQuotes.all(game,now))if(price.path("selectable").asBoolean())selectable++;
        }
        data.put("as_of",now.toString());ObjectNode meta=pending(day);meta.put("state",failures==0?(covered==games.size()?"READY":"PARTIAL"):covered>0?"PARTIAL":"ERROR");
        meta.put("upcoming_games",games.size());meta.put("covered_games",covered);meta.put("market_count",count);meta.put("selectable_count",selectable);meta.put("complete_snapshot",failures==0);
        meta.put("checked_at",main==null?now.toString():text(main,"checked_at"));meta.put("last_attempt_at",now.toString());meta.put("source_updated_at",newest==null?"":newest.toString());meta.put("snapshot_id","oddize-"+OAuth.hash(day+"|"+meta.path("checked_at")).substring(0,20));
        meta.put("http_requests_this_attempt",client.requests);meta.put("credits_used_this_attempt",client.creditsUsed);if(client.creditsRemaining!=null)meta.put("credits_remaining",client.creditsRemaining);
        meta.set("warnings",warnings);meta.put("failed_requests",failures);meta.put("url",OddizeClient.BASE+mainPath);meta.set("unsupported_markets",M.valueToTree(List.of("regulation_1x2","double_chance")));
        return obj("dataset",data,"odds_import",meta);
    }
    static String safeError(RuntimeException e){return e instanceof IllegalStateException?e.getMessage():e.getClass().getSimpleName();}
    static String match(String day,Map<String,ObjectNode> games,JsonNode event,Instant now){
        try{
            var slate=games.values().stream().map(g->new OddsGameMatcher.OfficialGame(text(g,"game_id"),text(g,"home.name"),text(g,"away.name"),Checks.time(text(g,"starts_at")),true)).toList();
            var quote=new OddsGameMatcher.BookEvent(text(event,"event_id"),"NHL",team(text(event,"team2")),team(text(event,"team1")),instant(text(event,"start_date")),false,now);
            return OddsGameMatcher.match(LocalDate.parse(day),slate,quote,now).map(OddsGameMatcher.Match::gameId).orElse(null);
        }catch(DateTimeException e){return null;}
    }
    static void addPrices(ObjectNode data,ObjectNode game,JsonNode event,JsonNode rawPrices,String forcedType,String subject,ObjectNode response,Map<String,ObjectNode> prices,ArrayNode warnings,Instant now){
        String url=text(response,"endpoint"),checked=text(response,"checked_at"),proof=EVIDENCE_PREFIX+OAuth.hash(url+checked).substring(0,20);DataCollector.mergeEvidence(data,obj("id",proof,"url",url,"checked_at",checked));
        if(!rawPrices.isArray())return;
        for(JsonNode raw:rawPrices)try{
            String type=forcedType==null?text(raw,"offer_type"):forcedType,book=text(raw,"book"),selection,kind,side=null;
            if(book.isBlank())throw new IllegalArgumentException();
            Double line=null;
            switch(type){
                case "ml"->{kind="moneyline";selection=side(game,text(raw,"team"));}
                case "s","alt_s"->{kind="handicap";selection=side(game,text(raw,"team"));side=selection;line=line(raw);}
                case "t","alt_t"->{kind="total";selection=overUnder(raw);line=line(raw);}
                case "tt"->{kind="team_total";selection=overUnder(raw);side=side(game,subject==null?text(raw,"subject"):subject);line=line(raw);}
                default->{continue;}
            }
            if(line!=null&&(line< -10||line>15||!kind.equals("handicap")&&line<0))throw new IllegalArgumentException();
            double odds=NhlOdds.decimal(Double.parseDouble(text(raw,"american_odds")));
            if(!raw.path("timestamp").isTextual()&&!raw.path("ts").canConvertToLong())throw new IllegalArgumentException();
            Instant observed=raw.path("timestamp").isTextual()?instant(text(raw,"timestamp")):Instant.ofEpochSecond(raw.path("ts").asLong());
            if(observed.isAfter(now)||!observed.isBefore(Checks.time(text(game,"starts_at"))))throw new IllegalArgumentException();
            if(raw.has("ts")&&Math.abs(observed.getEpochSecond()-raw.path("ts").asLong())>1)throw new IllegalArgumentException();
            String rules=BookmakerRules.url(book),basis=BookmakerRules.basis(book),identity=book+"|"+kind+"|"+selection+"|"+side+"|"+line+"|"+basis;
            ObjectNode market=obj("id",PREFIX+text(game,"game_id")+"-"+OAuth.hash(identity).substring(0,18),"kind",kind,"selection",selection,"line",line,"odds",odds,"score_basis",basis,"source",obj("url",url,"checked_at",checked,"status","verified"),"settlement_verified",rules!=null,"rules_url",rules,
                "provider","Oddize","bookmaker",text(raw,"book_name").isBlank()?book:text(raw,"book_name"),"bookmaker_code",book,"provider_event_id",text(event,"event_id"),"offer_type",type,"observed_at",observed.toString(),"snapshot_at",checked,"quality_warnings",arr());
            if(side!=null)market.put("team",side);ArrayNode refs=arr();refs.add(proof);
            if(rules!=null){String ruleId=EVIDENCE_PREFIX+"rules-"+book;DataCollector.mergeEvidence(data,obj("id",ruleId,"url",rules,"checked_at",BookmakerRules.VERIFIED_AT));refs.add(ruleId);}
            market.set("evidence_ids",refs);
            ObjectNode old=prices.get(identity);if(old==null||observed.isAfter(instant(text(old,"observed_at"))))prices.put(identity,market);
            else if(observed.equals(instant(text(old,"observed_at")))&&old.path("odds").asDouble()!=odds)((ArrayNode)old.path("quality_warnings")).add("CONFLICTING_SAME_TIMESTAMP_PRICE");
        }catch(RuntimeException e){warnings.add(text(game,"game_id")+": rejected malformed/unidentified Oddize "+(forcedType==null?text(raw,"offer_type"):forcedType)+" offer from "+text(raw,"book"));}
    }
    static String side(JsonNode game,String team){String code=team(team==null?"":team);for(String side:List.of("away","home"))if(code.equals(text(game,side+".name")))return side;throw new IllegalArgumentException("Unknown side");}
    static String overUnder(JsonNode raw){String value=text(raw,"team").toLowerCase(Locale.ROOT);if(!Set.of("over","under").contains(value))throw new IllegalArgumentException();return value;}
    static double line(JsonNode raw){if(!raw.path("line").isNumber())throw new IllegalArgumentException();double line=number(raw,"line");if(!Double.isFinite(line)||Math.abs(line*2-Math.rint(line*2))>1e-8)throw new IllegalArgumentException();return line;}
    static void checkConsistency(Collection<ObjectNode> prices){
        for(ObjectNode left:prices)for(ObjectNode right:prices){
            if(left==right||!text(left,"bookmaker_code").equals(text(right,"bookmaker_code"))||!text(left,"kind").equals(text(right,"kind"))||!text(left,"team").equals(text(right,"team"))||!List.of("total","team_total").contains(text(left,"kind")))continue;
            if(number(left,"line")==number(right,"line")&&text(left,"selection").equals("over")&&text(right,"selection").equals("under")&&1/number(left,"odds")+1/number(right,"odds")>1.30){flag(left,"EXTREME_TWO_SIDED_MARGIN");flag(right,"EXTREME_TWO_SIDED_MARGIN");}
            if(number(left,"line")<number(right,"line")&&text(left,"selection").equals(text(right,"selection"))){boolean inconsistent=text(left,"selection").equals("over")?number(left,"odds")>number(right,"odds")*1.05:number(left,"odds")*1.05<number(right,"odds");if(inconsistent){flag(left,"INCONSISTENT_TOTAL_PRICE_ORDER");flag(right,"INCONSISTENT_TOTAL_PRICE_ORDER");}}
        }
    }
    static void flag(ObjectNode market,String warning){ArrayNode list=(ArrayNode)market.path("quality_warnings");if(!strings(list).contains(warning))list.add(warning);}
}
