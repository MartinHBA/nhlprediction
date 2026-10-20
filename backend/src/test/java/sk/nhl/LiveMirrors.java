package sk.nhl;

import java.time.*;
import static sk.nhl.Json.*;

/** Explicit local read-only integration check; never part of the offline test suite. */
public final class LiveMirrors {
    public static void main(String[]args){
        String day=args.length>0?args[0]:LocalDate.now(ZoneId.of("America/New_York")).toString();int season=DataCollector.season(LocalDate.parse(day));
        MirrorSources mirrors=new MirrorSources(new MemoryStore(),DataCollector::download,Clock.systemUTC());
        var teams=mirrors.teams(season);var json=mirrors.goalies(season,true);var parquet=mirrors.goalies(season,false);
        var reference=Csv.read(json.content());var alternate=Csv.read(parquet.content());int compared=0,different=0;
        for(var row:reference)if("all".equals(row.get("situation")))for(var candidate:alternate)if(row.get("playerid").equals(candidate.get("playerid"))){compared++;for(String key:java.util.List.of("games_played","icetime","xgoals","goals"))if(Double.parseDouble(row.get(key))!=Double.parseDouble(candidate.get(key)))different++;break;}
        if(teams.size()!=32||compared==0)throw new IllegalStateException("Incomplete real mirror coverage");
        var output=obj("teams",teams.size(),"goalies_compared",compared,"goalie_numeric_differences",different,"sources",mirrors.status());
        if(java.util.Arrays.asList(args).contains("--origin")){
            var original=Csv.read(DataCollector.download("https://moneypuck.com/moneypuck/playerData/seasonSummary/"+season+"/regular/goalies.csv").content());int same=0,changed=0;
            for(var row:reference)if("all".equals(row.get("situation")))for(var candidate:original)if("all".equals(candidate.get("situation"))&&row.get("playerid").equals(candidate.get("playerid"))){boolean equal=true;for(String key:java.util.List.of("games_played","icetime","xgoals","goals"))if(Double.parseDouble(row.get(key))!=Double.parseDouble(candidate.get(key)))equal=false;if(equal)same++;else changed++;break;}
            output.set("goalie_origin_comparison",obj("identical_goalies",same,"changed_since_feed",changed));
            var direct=new DataCollector(new MemoryStore(),DataCollector::download,Clock.systemUTC());var originalTeams=direct.bulk(season);int identical=0,changedTeams=0;
            for(var entry:teams.entrySet()){var a=DataCollector.aggregate(Csv.read(entry.getValue().content()),entry.getKey(),season,LocalDate.parse(day));var b=DataCollector.aggregate(Csv.read(originalTeams.get(entry.getKey()).content()),entry.getKey(),season,LocalDate.parse(day));if(a.equals(b))identical++;else changedTeams++;}
            output.set("team_origin_comparison",obj("identical_teams",identical,"changed_since_mirror",changedTeams));
        }
        System.out.println(output.toPrettyString());
    }
}
