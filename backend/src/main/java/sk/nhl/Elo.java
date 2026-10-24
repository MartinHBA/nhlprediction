package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import static sk.nhl.Json.*;

/** Historical season rating, not a calibrated prediction or a source of betting probabilities. */
final class Elo {
    static final String VERSION="1",TEAMS_URL="https://api.nhle.com/stats/rest/en/team";
    static final int INITIAL=1500,K=20,HISTORY_DAYS=8;
    final Store store;final Function<String,JsonNode> fetch;
    Elo(Store store){this(store,url->parse(DataCollector.download(url).content()));}
    Elo(Store store,Function<String,JsonNode> fetch){this.store=store;this.fetch=fetch;}
    static String season(String day){int year=DataCollector.season(LocalDate.parse(day));return ""+year+(year+1);}
    static String gamesUrl(String day){return "https://api.nhle.com/stats/rest/en/game?cayenneExp=season="+season(day)+"%20and%20gameType=2&limit=-1";}
    static String stateKey(String day){return "cache/elo/v"+VERSION+"/seasons/"+season(day)+".json";}
    static String snapshotKey(String day){return "cache/elo/v"+VERSION+"/"+day+".json";}
    ObjectNode collect(String day,JsonNode dataset,Instant now){
        Schedule.day(day);
        if(LocalDate.parse(day).isAfter(now.atZone(ZoneId.of("America/New_York")).toLocalDate()))throw new IllegalArgumentException("Future NHL day: Elo snapshot is not ready");
        String key=snapshotKey(day);ObjectNode snapshot=store.get(key);
        ObjectNode state=seasonState(day,now);
        if(snapshot==null){
            state=advanceTo(day,state,now);
            ObjectNode calculated=snapshot(day,state,now);
            snapshot=store.locked("elo/"+day,()->{ObjectNode existing=store.get(key);if(existing!=null)return existing;store.put(key,calculated);return calculated;});
        }
        ObjectNode view=snapshot.deepCopy();view.remove(List.of("ratings","results_used","team_ids","generation","pending_days"));ObjectNode games=obj();
        for(JsonNode game:dataset.path("games")){
            ObjectNode ratings=obj();for(String side:List.of("away","home")){
                JsonNode value=snapshot.path("ratings").path(text(game,side+".name"));
                if(!value.isNumber())throw new IllegalArgumentException("Game team absent from Elo season: "+text(game,side+".name"));ratings.set(side,value);
            }
            games.set(text(game,"game_id"),ratings);
        }
        view.set("games",games);view.set("history",ensureHistory(day,state,now));return view;
    }
    ObjectNode advanceTo(String day,ObjectNode state,Instant now){
        if(text(state,"cutoff_day").compareTo(day)>=0)return state;
        SortedSet<String> dates=new TreeSet<>(strings(state.path("pending_days")));
        for(LocalDate date=LocalDate.parse(text(state,"cutoff_day"));date.isBefore(LocalDate.parse(day));date=date.plusDays(1))dates.add(date.toString());
        dates.removeIf(date->date.compareTo(day)>=0);List<ObjectNode> updates=new ArrayList<>();
        for(String date:dates)updates.add(results(date,state,now));
        ObjectNode advanced=advance(day,state,updates,now);int generation=state.path("generation").asInt();
        return store.locked("elo/season/"+season(day),()->{ObjectNode latest=store.get(stateKey(day));if(latest==null||latest.path("generation").asInt()!=generation)throw new BusyException();store.put(stateKey(day),advanced);return advanced;});
    }
    ObjectNode ensureHistory(String day,ObjectNode current,Instant now){
        LocalDate end=LocalDate.parse(day),start=end.minusDays(HISTORY_DAYS-1);Map<String,ObjectNode> seasons=new HashMap<>();seasons.put(season(day),current);
        for(LocalDate date=end;!date.isBefore(start);date=date.minusDays(1)){
            String target=date.toString(),key=snapshotKey(target);ObjectNode existing=store.get(key);
            if(existing!=null){if(!text(existing,"state").equals("READY")||!text(existing,"cutoff_day").equals(target)||!text(existing,"season").equals(season(target))||existing.path("ratings").size()!=32)throw new IllegalStateException("Stored rating history is incomplete: "+target);continue;}
            String season=season(target);ObjectNode state=seasons.get(season);
            if(state==null){state=advanceTo(target,seasonState(target,now),now);seasons.put(season,state);}
            if(text(state,"cutoff_day").compareTo(target)<0)throw new IllegalStateException("Rating history is ahead of stored results");
            ObjectNode calculated=snapshot(target,state,now);calculated.put("history_origin","RECONSTRUCTED");
            store.locked("elo/"+target,()->{if(store.get(key)==null)store.put(key,calculated);return null;});
        }
        return obj("state","READY","days",HISTORY_DAYS,"from",start.toString(),"to",day);
    }
    ObjectNode seasonState(String day,Instant now){
        String key=stateKey(day);ObjectNode current=store.get(key);if(current!=null)return current;
        ObjectNode seed=null;
        // Migrate an existing daily snapshot instead of downloading the season again.
        for(String cached:store.keys("cache/elo/v"+VERSION+"/")){
            if(!cached.matches("cache/elo/v"+VERSION+"/\\d{4}-\\d{2}-\\d{2}\\.json"))continue;
            ObjectNode candidate=store.get(cached);
            if(candidate!=null&&text(candidate,"season").equals(season(day))&&text(candidate,"state").equals("READY")&&candidate.path("ratings").size()==32&&candidate.path("results_used").isArray()
                &&(seed==null||text(candidate,"cutoff_day").compareTo(text(seed,"cutoff_day"))>0))seed=candidate;
        }
        ObjectNode teams=store.get("cache/elo/v"+VERSION+"/teams-"+season(day)+".json");
        if(seed!=null&&!seed.path("team_ids").isObject()){
            if(teams!=null)seed.set("team_ids",legacyTeamIds(seed,teams));
            if(seed.path("team_ids").size()!=32)seed=null;
        }
        if(seed==null){
            JsonNode games=fetch.apply(gamesUrl(day));
            if(teams==null){JsonNode source=fetch.apply(TEAMS_URL);if(!source.isObject()||!source.path("data").isArray())throw new IllegalArgumentException("NHL team directory missing");teams=source.deepCopy();store.put("cache/elo/v"+VERSION+"/teams-"+season(day)+".json",teams);}
            seed=calculate(day,games,teams,now);SortedSet<String> pending=new TreeSet<>();
            for(JsonNode game:games.path("data"))if(game.path("gameType").asInt()==2&&text(game,"gameDate").compareTo(day)<0&&!Set.of(6,7).contains(game.path("gameStateId").asInt()))pending.add(text(game,"gameDate"));
            seed.set("pending_days",M.valueToTree(pending));
        }
        seed.put("generation",0);if(!seed.path("pending_days").isArray())seed.set("pending_days",arr());
        ArrayNode normalized=arr();for(JsonNode game:seed.path("results_used"))normalized.add(normalize(game));seed.set("results_used",normalized);
        final ObjectNode initial=seed;
        return store.locked("elo/season/"+season(day),()->{ObjectNode existing=store.get(key);if(existing!=null)return existing;store.put(key,initial);return initial;});
    }
    static ObjectNode legacyTeamIds(JsonNode seed,JsonNode teams){
        ObjectNode ids=obj();Set<Integer> played=new HashSet<>();for(JsonNode game:seed.path("results_used")){played.add(game.path("homeTeamId").asInt());played.add(game.path("visitingTeamId").asInt());}
        for(var codes=seed.path("ratings").fieldNames();codes.hasNext();){String code=codes.next();List<JsonNode> candidates=teams.path("data").valueStream().filter(t->text(t,"triCode").equals(code)).toList();List<JsonNode> active=candidates.stream().filter(t->played.contains(t.path("id").asInt())).toList();
            if(active.size()==1)ids.put(text(active.getFirst(),"id"),code);else if(candidates.size()==1)ids.put(text(candidates.getFirst(),"id"),code);
        }
        return ids;
    }
    ObjectNode results(String date,JsonNode state,Instant now){
        String key="cache/elo/v"+VERSION+"/results/"+date+".json";ObjectNode cached=store.get(key);if(cached!=null&&cached.path("complete").asBoolean())return cached;
        ObjectNode saved=storedResults(date,state,now),result=saved!=null?saved:dailyResults(date,fetch.apply(GameScores.BASE+date),state,now);
        store.put(key,result);return result;
    }
    ObjectNode storedResults(String date,JsonNode state,Instant now){
        ObjectNode saved=store.get("days/"+date+".json");if(saved==null)return null;
        JsonNode games=saved.path("dataset").path("games"),scores=saved.path("scoreboard").path("games");
        if(!games.isArray()||!saved.path("dataset").path("expected_game_ids").isArray())return null;
        Set<String> expected=new HashSet<>(strings(saved.path("dataset").path("expected_game_ids")));Set<String> seen=new HashSet<>();ArrayNode events=arr();
        for(JsonNode game:games){
            String id=text(game,"game_id");List<JsonNode> matches=scores.valueStream().filter(s->text(s,"game_id").equals(id)&&text(s,"status").equals("final")&&text(s,"source_url").equals(GameScores.BASE+date)).toList();
            if(!seen.add(id)||matches.size()!=1)return null;JsonNode score=matches.getFirst();
            events.add(obj("id",id,"gameType",2,"startTimeUTC",game.path("starts_at"),"gameState","OFF","homeTeam",obj("abbrev",text(game,"home.name"),"score",score.path("home_score")),"awayTeam",obj("abbrev",text(game,"away.name"),"score",score.path("away_score"))));
        }
        if(!seen.equals(expected))return null;
        try{return dailyResults(date,obj("currentDate",date,"games",events),state,now);}catch(IllegalArgumentException error){return null;}
    }
    static ObjectNode dailyResults(String date,JsonNode feed,JsonNode state,Instant now){
        if(!text(feed,"currentDate").equals(date)||!feed.path("games").isArray())throw new IllegalArgumentException("Invalid daily NHL Elo results");
        ObjectNode ids=obj();state.path("team_ids").fields().forEachRemaining(e->ids.put(e.getValue().asText(),Integer.parseInt(e.getKey())));
        ArrayNode results=arr();Set<String> seen=new HashSet<>();boolean complete=true;
        for(JsonNode game:feed.path("games")){
            if(game.path("gameType").asInt()!=2)continue;String id=text(game,"id");
            if(!id.matches(text(state,"season").substring(0,4)+"02\\d{4}")||!seen.add(id))throw new IllegalArgumentException("Wrong season or duplicate daily NHL result");
            if(Set.of("PPD","PST","CNCL","CANCELLED").contains(text(game,"gameScheduleState")))continue;
            String status=text(game,"gameState");if(!Set.of("OFF","FINAL").contains(status)){if(!Set.of("FUT","PRE","LIVE","CRIT").contains(status))throw new IllegalArgumentException("Unknown daily NHL game state");complete=false;continue;}
            String home=text(game,"homeTeam.abbrev"),away=text(game,"awayTeam.abbrev");JsonNode h=game.path("homeTeam").path("score"),a=game.path("awayTeam").path("score");
            Instant start=Checks.time(text(game,"startTimeUTC"));
            if(!ids.has(home)||!ids.has(away)||home.equals(away)||!h.isIntegralNumber()||!a.isIntegralNumber()||h.asInt()<0||a.asInt()<0||h.asInt()==a.asInt()||!start.isBefore(now))throw new IllegalArgumentException("Invalid final daily NHL score");
            results.add(obj("id",id,"season",state.path("season"),"gameType",2,"gameDate",date,"easternStartTime",DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(start.atZone(ZoneId.of("America/New_York")).toLocalDateTime()),
                "homeTeamId",ids.path(home),"visitingTeamId",ids.path(away),"homeScore",h,"visitingScore",a,"gameStateId",7));
        }
        return obj("day",date,"complete",complete,"checked_at",now.toString(),"source_url",GameScores.BASE+date,"results",results);
    }
    static ObjectNode normalize(JsonNode game){ObjectNode result=obj();for(String key:List.of("id","season","gameType","gameDate","easternStartTime","homeTeamId","visitingTeamId","homeScore","visitingScore"))result.set(key,game.path(key));result.put("id",text(game,"id"));result.put("season",text(game,"season"));result.put("gameStateId",7);return result;}
    static final Comparator<JsonNode> ORDER=Comparator.comparing((JsonNode g)->text(g,"easternStartTime")).thenComparing(g->text(g,"id"));
    static void apply(ObjectNode ratings,JsonNode ids,JsonNode game){
        String home=text(ids,text(game,"homeTeamId")),away=text(ids,text(game,"visitingTeamId"));if(!ratings.has(home)||!ratings.has(away))throw new IllegalArgumentException("Unknown Elo season team");
        boolean won=game.path("homeScore").asInt()>game.path("visitingScore").asInt();String winner=won?home:away,loser=won?away:home;
        double delta=change(number(ratings,winner),number(ratings,loser));ratings.put(winner,number(ratings,winner)+delta);ratings.put(loser,number(ratings,loser)-delta);
    }
    static ObjectNode advance(String day,JsonNode previous,List<ObjectNode> updates,Instant now){
        ObjectNode state=previous.deepCopy();Map<String,JsonNode> ledger=new LinkedHashMap<>();previous.path("results_used").forEach(g->ledger.put(text(g,"id"),normalize(g)));
        List<JsonNode> added=new ArrayList<>();SortedSet<String> pending=new TreeSet<>(strings(previous.path("pending_days")));boolean appendOnly=true;
        JsonNode last=ledger.values().stream().max(ORDER).orElse(null);
        for(ObjectNode batch:updates){String date=text(batch,"day");pending.remove(date);if(!batch.path("complete").asBoolean())pending.add(date);
            for(JsonNode raw:batch.path("results")){
                JsonNode game=normalize(raw);if(text(game,"easternStartTime").substring(0,10).compareTo(day)>=0){pending.add(date);continue;}
                JsonNode old=ledger.put(text(game,"id"),game);if(old==null){added.add(game);if(last!=null&&ORDER.compare(game,last)<=0)appendOnly=false;}else if(!old.equals(game))appendOnly=false;
            }
        }
        List<JsonNode> ordered=new ArrayList<>(ledger.values());ordered.sort(ORDER);added.sort(ORDER);ObjectNode ratings=state.path("ratings").deepCopy();
        if(!appendOnly)ratings.fieldNames().forEachRemaining(code->ratings.put(code,(double)INITIAL));
        for(JsonNode game:appendOnly?added:ordered)apply(ratings,state.path("team_ids"),game);
        state.set("ratings",ratings);state.set("results_used",M.valueToTree(ordered));state.set("pending_days",M.valueToTree(pending));state.put("cutoff_day",day).put("games_processed",ordered.size()).put("calculated_at",now.toString()).put("generation",previous.path("generation").asInt()+1);
        state.set("last_result_sources",M.valueToTree(updates.stream().map(batch->text(batch,"source_url")).distinct().toList()));
        return state;
    }
    static ObjectNode snapshot(String day,JsonNode state,Instant now){
        ObjectNode snapshot=state.deepCopy();snapshot.remove(List.of("generation","pending_days"));snapshot.put("cutoff_day",day).put("calculated_at",now.toString());
        if(text(state,"cutoff_day").compareTo(day)>0){
            ObjectNode ratings=state.path("ratings").deepCopy();ratings.fieldNames().forEachRemaining(code->ratings.put(code,(double)INITIAL));ArrayNode results=arr();
            for(JsonNode game:state.path("results_used"))if(text(game,"gameDate").compareTo(day)<0&&text(game,"easternStartTime").substring(0,10).compareTo(day)<0){apply(ratings,state.path("team_ids"),game);results.add(game);}
            snapshot.set("ratings",ratings);snapshot.set("results_used",results);snapshot.put("games_processed",results.size());
        }
        return snapshot;
    }
    static double change(double winner,double loser){return K*(1-1/(1+Math.pow(10,(loser-winner)/400)));}
    static ObjectNode calculate(String day,JsonNode feed,JsonNode directory,Instant now){
        String season=season(day);JsonNode rows=feed.path("data");
        if(!rows.isArray()||!feed.path("total").isIntegralNumber()||feed.path("total").asInt()!=rows.size()||rows.isEmpty())throw new IllegalArgumentException("Incomplete NHL season game list");
        Map<Integer,String> names=new HashMap<>();for(JsonNode team:directory.path("data")){
            int id=team.path("id").asInt(-1);String code=text(team,"triCode");if(id<0||!code.matches("[A-Z]{3}")||names.putIfAbsent(id,code)!=null)throw new IllegalArgumentException("Invalid NHL team directory");
        }
        Set<Integer> active=new TreeSet<>();Set<String> ids=new HashSet<>();List<JsonNode> completed=new ArrayList<>();LocalDate cutoff=LocalDate.parse(day);
        for(JsonNode game:rows){
            if(game.path("gameType").asInt()!=2)continue;
            if(!text(game,"season").equals(season)||!text(game,"id").matches(season.substring(0,4)+"02\\d{4}")||!ids.add(text(game,"id")))throw new IllegalArgumentException("Wrong season or duplicate NHL Elo game");
            int home=game.path("homeTeamId").asInt(-1),away=game.path("visitingTeamId").asInt(-1);
            if(home==away||!names.containsKey(home)||!names.containsKey(away))throw new IllegalArgumentException("Unknown NHL Elo teams");active.add(home);active.add(away);
            LocalDate date=LocalDate.parse(text(game,"gameDate"));if(!date.isBefore(cutoff))continue;
            // Scheduled, live, postponed and cancelled games do not carry an Elo result.
            if(!Set.of(6,7).contains(game.path("gameStateId").asInt()))continue;
            Instant start=LocalDateTime.parse(text(game,"easternStartTime")).atZone(ZoneId.of("America/New_York")).toInstant();
            if(!start.isBefore(now))throw new IllegalArgumentException("Future final NHL result rejected");
            if(!start.atZone(ZoneId.of("America/New_York")).toLocalDate().isBefore(cutoff))continue;
            JsonNode h=game.path("homeScore"),a=game.path("visitingScore");
            if(!h.isIntegralNumber()||!a.isIntegralNumber()||h.asInt()<0||a.asInt()<0||h.asInt()==a.asInt())throw new IllegalArgumentException("Invalid final NHL Elo score");
            completed.add(game);
        }
        if(active.size()!=32||active.stream().map(names::get).distinct().count()!=32)throw new IllegalArgumentException("Expected 32 distinct NHL season teams");
        completed.sort(Comparator.comparing((JsonNode g)->text(g,"easternStartTime")).thenComparing(g->text(g,"id")));
        Map<Integer,Double> ratings=new TreeMap<>();active.forEach(id->ratings.put(id,(double)INITIAL));ArrayNode used=arr();
        for(JsonNode game:completed){
            int home=game.path("homeTeamId").asInt(),away=game.path("visitingTeamId").asInt();boolean homeWon=game.path("homeScore").asInt()>game.path("visitingScore").asInt();int winner=homeWon?home:away,loser=homeWon?away:home;
            double delta=change(ratings.get(winner),ratings.get(loser));ratings.put(winner,ratings.get(winner)+delta);ratings.put(loser,ratings.get(loser)-delta);used.add(game);
        }
        ObjectNode byCode=obj(),byId=obj();ratings.forEach((id,value)->{byCode.put(names.get(id),value);byId.put(Integer.toString(id),names.get(id));});
        return obj("state","READY","version",VERSION,"season",season,"cutoff_day",day,"initial",INITIAL,"k",K,"scale",400,"game_type",2,"team_count",32,
            "games_processed",completed.size(),"calculated_at",now.toString(),"source_urls",List.of(gamesUrl(day),TEAMS_URL),"ratings",byCode,"team_ids",byId,"results_used",used,
            "method","All 32 teams reset to 1500 each season. K=20; E=1/(1+10^((opponent-rating)/400)); R'=R+20*(S-E). Final wins including OT/SO score 1, losses 0. No home or goal-margin bonus. Completed regular-season games strictly before cutoff_day, ordered by Eastern start time then game ID. Full precision is retained; only website display is rounded.");
    }
}
