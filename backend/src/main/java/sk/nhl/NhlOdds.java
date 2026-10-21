package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static sk.nhl.Json.*;

/** Imports only real, fresh, pregame DraftKings quotes attached to the exact NHL game. */
final class NhlOdds {
    static final String URL="https://api-web.nhle.com/v1/partner-game/US/now";
    static final String RULES="https://sportsbook.draftkings.com/help/sport-rules/hockey";
    static final String PREFIX="nhl-dk-",EVIDENCE_PREFIX="auto-nhl-odds-";
    static final Set<String> TYPES=Set.of("MONEY_LINE_2_WAY","MONEY_LINE_3_WAY","OVER_UNDER","PUCK_LINE");
    static final Pattern TOTAL=Pattern.compile("([OU])([0-9]+(?:\\.[05])?)"),HANDICAP=Pattern.compile("([+-]?[0-9]+(?:\\.[05])?)");
    final DataCollector.Fetcher fetch;final Clock clock;
    NhlOdds(){this(DataCollector::download,Clock.systemUTC());}
    NhlOdds(DataCollector.Fetcher fetch,Clock clock){this.fetch=fetch;this.clock=clock;}
    static boolean managed(JsonNode market){return text(market,"id").startsWith(PREFIX);}
    static boolean nativeCheck(JsonNode check){return strings(check.path("evidence_ids")).stream().anyMatch(id->id.startsWith(EVIDENCE_PREFIX));}
    ObjectNode collect(String day,JsonNode original){
        ObjectNode data=original.deepCopy();Instant now=clock.instant();JsonNode feed=obj();String reason="";ArrayNode warnings=arr();
        boolean fetched=false,usable=false;Instant updated=Instant.EPOCH;
        try{
            feed=parse(fetch.get(URL).content());fetched=true;
            if(!feed.path("games").isArray()||feed.path("bettingPartner").path("partnerId").asInt()!=9||!text(feed,"bettingPartner.name").equals("DraftKings"))throw new IllegalArgumentException("Unexpected NHL partner feed");
            updated=Instant.parse(text(feed,"lastUpdatedUTC"));
            if(!text(feed,"currentOddsDate").equals(day))reason="NHL feed covers "+text(feed,"currentOddsDate")+", requested NHL day is "+day+".";
            else if(updated.isAfter(now))reason="NHL odds publication time is in the future.";
            else if(updated.isBefore(now.minus(OddsGameMatcher.MAX_QUOTE_AGE)))reason="NHL odds publication is older than two hours.";
            else usable=true;
        }catch(RuntimeException e){fetched=false;reason="NHL odds download/validation failed: "+DataCollector.rootMessage(e);warnings.add(reason);}
        String evidenceId=EVIDENCE_PREFIX+OAuth.hash(URL+now).substring(0,16);JsonNode evidence=obj("id",evidenceId,"url",URL,"checked_at",now.toString());DataCollector.mergeEvidence(data,evidence);
        int marketCount=0,covered=0,upcoming=0;Set<String> importedIds=new HashSet<>();
        for(JsonNode entry:data.path("games")){
            ObjectNode game=(ObjectNode)entry;ArrayNode markets=arr();
            for(JsonNode market:game.path("markets"))if(!managed(market))markets.add(market);
            if(!text(game,"status").equals("scheduled")||!Checks.time(text(game,"starts_at")).isAfter(now)){
                if(text(game,"status").equals("scheduled"))game.put("status","live");game.set("markets",arr());continue;
            }
            upcoming++;String gameId=text(game,"game_id");
            if(usable){
                List<JsonNode> events=new ArrayList<>();for(JsonNode event:feed.path("games"))if(text(event,"gameId").equals(gameId))events.add(event);
                if(events.size()>1)warnings.add(gameId+": duplicate NHL odds event; quotes rejected.");
                else if(events.size()==1)try{
                    JsonNode event=events.getFirst();var official=new OddsGameMatcher.OfficialGame(gameId,text(game,"home.name"),text(game,"away.name"),Checks.time(text(game,"starts_at")),true);
                    var quote=new OddsGameMatcher.BookEvent(text(event,"gameId"),event.path("gameType").asInt()==2?"NHL":"OTHER",text(event,"homeTeam.abbrev"),text(event,"awayTeam.abbrev"),Instant.parse(text(event,"startTimeUTC")),Set.of("LIVE","CRIT","FINAL","OFF").contains(text(event,"gameState")),updated);
                    var match=OddsGameMatcher.match(LocalDate.parse(day),List.of(official),quote,now);
                    if(match.isPresent()){
                        // A shared gameId also requires an exact NHL UTC start, not merely a similar clock time.
                        if(!official.startsAt().equals(quote.startsAt()))throw new IllegalArgumentException("NHL odds start differs from official schedule");
                        List<ObjectNode> parsed=markets(event,updated);markets.addAll(parsed);if(!parsed.isEmpty()){covered++;importedIds.add(gameId);}
                    }else warnings.add(gameId+": NHL odds teams, dates, status or pregame cutoff do not match.");
                }catch(RuntimeException e){warnings.add(gameId+": odds rejected: "+e.getMessage());}
            }
            game.set("markets",markets);marketCount+=(int)markets.valueStream().filter(NhlOdds::managed).count();
            ObjectNode checks=(ObjectNode)game.path("checks").path("market_searches");
            for(String search:Checks.SEARCHES){
                boolean nativeFound=false,manualFound=false;for(JsonNode market:markets)if(Checks.matches(search,market)){if(managed(market))nativeFound=true;else manualFound=true;}
                if(manualFound&&!nativeFound)continue;
                String note=nativeFound?"Actual NHL/DraftKings quote; gameId, home/away teams and UTC start match. Source publication="+updated+". No missing odds are inferred.":
                    Set.of("double_chance","team_total_under","team_total_over_other").contains(search)?"Outside the configured NHL feed scope: double chance and team totals are not derived from other quotes.":
                    reason.isBlank()?"No fresh, supported pregame quote for this exact game and market in the NHL feed.":reason;
                checks.set(search,obj("status",nativeFound?"found":"unavailable","evidence_ids",List.of(evidenceId),"note",note));
            }
        }
        data.put("as_of",now.toString());String status=upcoming==0?"NO_UPCOMING_GAMES":covered==upcoming?"READY":covered>0?"PARTIAL":fetched?"WAITING":"ERROR";
        ObjectNode meta=obj("state",status,"url",URL,"bookmaker","DraftKings","checked_at",now.toString(),"source_updated_at",text(feed,"lastUpdatedUTC"),"source_day",text(feed,"currentOddsDate"),"requested_day",day,"upcoming_games",upcoming,"covered_games",covered,"market_count",marketCount,"reason",reason,"warnings",warnings,"poll_timezone","Europe/Bratislava","poll_interval_minutes",30,"poll_from","18:00","poll_until","08:00");
        return obj("dataset",data,"odds_import",meta);
    }
    static double decimal(double american){
        if(!Double.isFinite(american)||Math.abs(american)<100)throw new IllegalArgumentException("Invalid American price");
        double value=american>0?1+american/100:1+100/Math.abs(american);
        if(value<=1||value>100)throw new IllegalArgumentException("Price outside supported decimal range");return value;
    }
    static List<ObjectNode> markets(JsonNode event,Instant updated){
        Map<String,ObjectNode> unique=new LinkedHashMap<>();String id=text(event,"gameId");
        for(String side:List.of("home","away"))for(JsonNode quote:event.path(side+"Team").path("odds")){
            String type=text(quote,"description"),qualifier=text(quote,"qualifier");if(!TYPES.contains(type))continue;
            String kind,selection,basis;Double line=null;boolean team=false;
            switch(type){
                case "MONEY_LINE_2_WAY" -> {if(!qualifier.isBlank())throw new IllegalArgumentException("Unexpected moneyline qualifier");kind="moneyline";selection=side;basis="official_final";}
                case "MONEY_LINE_3_WAY" -> {if(!qualifier.isBlank()&&!qualifier.equals("Draw"))throw new IllegalArgumentException("Unknown 3-way selection");kind="regulation_1x2";selection=qualifier.equals("Draw")?"draw":side;basis="regulation";}
                case "OVER_UNDER" -> {var match=TOTAL.matcher(qualifier);if(!match.matches())throw new IllegalArgumentException("Unsupported total qualifier");kind="total";selection=match.group(1).equals("O")?"over":"under";line=Double.valueOf(match.group(2));if(line>15)throw new IllegalArgumentException("Unsupported total line");basis="official_final";}
                case "PUCK_LINE" -> {var match=HANDICAP.matcher(qualifier);if(!match.matches())throw new IllegalArgumentException("Unsupported puck line");kind="handicap";selection=side;line=Double.valueOf(match.group(1));if(line< -10||line>10)throw new IllegalArgumentException("Unsupported handicap line");team=true;basis="official_final";}
                default -> throw new IllegalStateException();
            }
            if(!quote.path("value").isNumber())throw new IllegalArgumentException("Non-numeric American price");
            String key=kind+"-"+selection+"-"+(line==null?"none":line.toString());
            ObjectNode market=obj("id",PREFIX+id+"-"+key,"kind",kind,"selection",selection,"line",line,"odds",decimal(quote.path("value").asDouble()),"score_basis",basis,"source",obj("url",URL,"checked_at",updated.toString(),"status","verified"),"settlement_verified",true,"rules_url",RULES);
            if(team)market.put("team",side);
            ObjectNode old=unique.putIfAbsent(key,market);if(old!=null&&!old.equals(market))throw new IllegalArgumentException("Conflicting duplicate NHL quote");
        }
        // Only evaluate complete sets quoted on the same line; never fabricate a missing side.
        Set<String> remove=new HashSet<>();
        for(var entry:unique.entrySet()){
            JsonNode m=entry.getValue();String kind=text(m,"kind"),selection=text(m,"selection");String counterpart=switch(kind){
                case "moneyline" -> "moneyline-"+(selection.equals("home")?"away":"home")+"-none";
                case "total" -> "total-"+(selection.equals("over")?"under":"over")+"-"+m.path("line").asDouble();
                case "handicap" -> "handicap-"+(selection.equals("home")?"away":"home")+"-"+(-m.path("line").asDouble());
                default -> "";
            };
            if(!counterpart.isEmpty()&&!unique.containsKey(counterpart))remove.add(entry.getKey());
            if(kind.equals("regulation_1x2")&&(!unique.containsKey("regulation_1x2-home-none")||!unique.containsKey("regulation_1x2-away-none")||!unique.containsKey("regulation_1x2-draw-none")))remove.add(entry.getKey());
        }
        remove.forEach(unique::remove);return new ArrayList<>(unique.values());
    }
    // Accept a slightly early 18:00 trigger; the half-hour cron schedule is unchanged.
    static boolean pollingWindow(Instant now){LocalTime time=now.atZone(ZoneId.of("Europe/Bratislava")).toLocalTime();int minute=time.getHour()*60+time.getMinute();return minute>=17*60+45||minute<=8*60;}
    static String pollDay(Instant now){var local=now.atZone(ZoneId.of("Europe/Bratislava"));return (local.toLocalTime().isBefore(LocalTime.of(12,0))?local.toLocalDate().minusDays(1):local.toLocalDate()).toString();}
    static String day(Instant now){return now.atZone(OddsGameMatcher.NHL_ZONE).toLocalDate().toString();}
}
