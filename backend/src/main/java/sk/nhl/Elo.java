package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static sk.nhl.Json.*;

/** Historical season rating, not a calibrated prediction or a source of betting probabilities. */
final class Elo {
    static final String VERSION="1",TEAMS_URL="https://api.nhle.com/stats/rest/en/team";
    static final int INITIAL=1500,K=20;
    final Store store;final Function<String,JsonNode> fetch;
    Elo(Store store){this(store,url->parse(DataCollector.download(url).content()));}
    Elo(Store store,Function<String,JsonNode> fetch){this.store=store;this.fetch=fetch;}
    static String season(String day){int year=DataCollector.season(LocalDate.parse(day));return ""+year+(year+1);}
    static String gamesUrl(String day){return "https://api.nhle.com/stats/rest/en/game?cayenneExp=season="+season(day)+"%20and%20gameType=2&limit=-1";}
    ObjectNode collect(String day,JsonNode dataset,Instant now){
        Schedule.day(day);
        if(LocalDate.parse(day).isAfter(now.atZone(ZoneId.of("America/New_York")).toLocalDate()))throw new IllegalArgumentException("Future NHL day: Elo snapshot is not ready");
        String key="cache/elo/v"+VERSION+"/"+day+".json";ObjectNode snapshot=store.get(key);
        if(snapshot==null){
            JsonNode games=fetch.apply(gamesUrl(day));String teamKey="cache/elo/v"+VERSION+"/teams-"+season(day)+".json";ObjectNode teams=store.get(teamKey);
            if(teams==null){JsonNode source=fetch.apply(TEAMS_URL);if(!source.isObject()||!source.path("data").isArray())throw new IllegalArgumentException("NHL team directory missing");teams=source.deepCopy();store.put(teamKey,teams);}
            ObjectNode calculated=calculate(day,games,teams,now);
            snapshot=store.locked("elo/"+day,()->{ObjectNode existing=store.get(key);if(existing!=null)return existing;store.put(key,calculated);return calculated;});
        }
        ObjectNode view=snapshot.deepCopy();view.remove(List.of("ratings","results_used"));ObjectNode games=obj();
        for(JsonNode game:dataset.path("games")){
            ObjectNode ratings=obj();for(String side:List.of("away","home")){
                JsonNode value=snapshot.path("ratings").path(text(game,side+".name"));
                if(!value.isNumber())throw new IllegalArgumentException("Game team absent from Elo season: "+text(game,side+".name"));ratings.set(side,value);
            }
            games.set(text(game,"game_id"),ratings);
        }
        view.set("games",games);return view;
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
        ObjectNode byCode=obj();ratings.forEach((id,value)->byCode.put(names.get(id),value));
        return obj("state","READY","version",VERSION,"season",season,"cutoff_day",day,"initial",INITIAL,"k",K,"scale",400,"game_type",2,"team_count",32,
            "games_processed",completed.size(),"calculated_at",now.toString(),"source_urls",List.of(gamesUrl(day),TEAMS_URL),"ratings",byCode,"results_used",used,
            "method","All 32 teams reset to 1500 each season. K=20; E=1/(1+10^((opponent-rating)/400)); R'=R+20*(S-E). Final wins including OT/SO score 1, losses 0. No home or goal-margin bonus. Completed regular-season games strictly before cutoff_day, ordered by Eastern start time then game ID. Full precision is retained; only website display is rounded.");
    }
}
