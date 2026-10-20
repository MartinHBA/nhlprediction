package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static sk.nhl.Json.*;

/** Public third-party copies of MoneyPuck data. Source publication time survives cache reads. */
final class MirrorSources {
    static final String FEED="https://raw.githubusercontent.com/adanielmendozaz-glitch/NHL-Totals-Lab-Data/main/data/nhl_feed.json";
    static final String RELEASE="https://api.github.com/repos/mattkravec/moneypuck-data/releases/tags/data-v2";
    static final String RELEASE_PATH="/mattkravec/moneypuck-data/releases/download/data-v2/";
    static final String RELEASE_BASE="https://github.com"+RELEASE_PATH;
    static final List<String> TEAM_COLUMNS=List.of("team","season","gameid","gamedate","situation","icetime","xgoalsfor","xgoalsagainst","penaltiesfor","penaltiesagainst","shotsongoalfor","shotsongoalagainst","highdangershotsfor","highdangershotsagainst");
    record Asset(String url,String updatedAt,String digest){}
    interface Reader {void read(Asset asset,Set<String> columns,Consumer<Map<String,String>> consumer);}
    final Store store;final DataCollector.Fetcher fetch;final Clock clock;final Reader reader;
    final Map<String,ObjectNode> memory=new HashMap<>();final Map<String,Instant> failures=new HashMap<>();final Map<String,ObjectNode> observations=new LinkedHashMap<>();
    MirrorSources(Store store,DataCollector.Fetcher fetch,Clock clock){this(store,fetch,clock,ParquetData::read);}
    MirrorSources(Store store,DataCollector.Fetcher fetch,Clock clock,Reader reader){this.store=store;this.fetch=fetch;this.clock=clock;this.reader=reader;}
    void fresh(String updated){fresh(updated,false);}
    void fresh(String updated,boolean frozen){
        Instant time=Instant.parse(updated);
        if(time.isAfter(clock.instant().plusSeconds(300))||!frozen&&time.isBefore(clock.instant().minus(Duration.ofHours(48))))throw new IllegalArgumentException("Mirror publication missing, in the future or older than 48 hours: "+updated);
    }
    ObjectNode cached(String name){
        ObjectNode value=memory.get(name);if(value==null)value=store.get("cache/mirrors/"+DataCollector.VERSION+"/"+name);
        if(value!=null&&Checks.time(text(value,"cached_at")).isAfter(clock.instant().minusSeconds(21600)))try{fresh(text(value,"source_updated_at"),value.path("frozen").asBoolean());memory.put(name,value);return value;}catch(DateTimeException|IllegalArgumentException ignored){memory.remove(name);}
        if(failures.getOrDefault(name,Instant.EPOCH).isAfter(clock.instant().minusSeconds(60)))throw new IllegalStateException("Mirror temporarily unavailable; retry after cooldown");return null;
    }
    void save(String name,ObjectNode value){value.put("cached_at",clock.instant().toString());memory.put(name,value);store.put("cache/mirrors/"+DataCollector.VERSION+"/"+name,value);}
    void success(String url,String updated){success(url,updated,false);}
    void success(String url,String updated,boolean frozen){fresh(updated,frozen);observations.put(url,obj("url",url,"source_updated_at",updated,"checked_at",clock.instant().toString(),"status","available","frozen_season",frozen));}
    void failed(String name,String url,RuntimeException e){failures.put(name,clock.instant());observations.put(url,obj("url",url,"checked_at",clock.instant().toString(),"status","unavailable","reason",DataCollector.rootMessage(e)));}
    synchronized ObjectNode feed(){
        String name="feed";
        try{
            ObjectNode cached=cached(name);if(cached!=null){success(FEED,text(cached,"source_updated_at"));return cached;}
            JsonNode raw=parse(fetch.get(FEED).content());String updated=text(raw,"updatedAt");fresh(updated);
            if(!raw.path("files").isObject())throw new IllegalArgumentException("Mirror CSV files absent");
            ObjectNode value=obj("files",raw.path("files"),"errors",raw.path("errors"),"source_updated_at",updated);save(name,value);success(FEED,updated);return value;
        }catch(RuntimeException e){failed(name,FEED,e);throw e;}
    }
    synchronized Asset asset(String file){
        String name="release";
        try{
            ObjectNode release=cached(name);
            if(release==null){JsonNode raw=parse(fetch.get(RELEASE).content());release=obj("assets",raw.path("assets"),"source_updated_at",clock.instant().toString());save(name,release);}
            for(JsonNode asset:release.path("assets"))if(text(asset,"name").equals(file)){
                String url=RELEASE_BASE+file;if(!text(asset,"browser_download_url").equals(url)||asset.path("size").asLong()>ParquetData.MAX_BYTES)throw new IllegalArgumentException("Invalid GitHub asset metadata");
                boolean frozen=file.matches("goalies_\\d{4}\\.parquet")&&Integer.parseInt(file.substring(8,12))<DataCollector.season(LocalDate.now(clock));
                String updated=text(asset,"updated_at");fresh(updated,frozen);return new Asset(url,updated,text(asset,"digest"));
            }
            throw new IllegalArgumentException("Mirror asset absent: "+file);
        }catch(RuntimeException e){failed(name,RELEASE,e);throw e;}
    }
    synchronized Map<String,DataCollector.Download> teams(int season){
        String name="teams/"+season;
        try{
            ObjectNode value=cached(name);
            if(value==null){
                int latest=DataCollector.season(LocalDate.now(clock));Asset asset=asset("all_teams_2008_to"+latest+".parquet");
                Map<String,StringBuilder> csv=new HashMap<>();String header=String.join(",",TEAM_COLUMNS)+"\n";
                reader.read(asset,new HashSet<>(TEAM_COLUMNS),row->{
                    String team=row.getOrDefault("team","");String year=row.getOrDefault("season","");
                    if(!team.matches("[A-Z]{3}")||(!year.equals(""+season)&&!year.equals(""+(season-1))))return;
                    for(String c:List.of("gameid","gamedate","situation"))if(!row.containsKey(c))throw new IllegalArgumentException("Team mirror missing "+c);
                    csv.computeIfAbsent(team,k->new StringBuilder(header)).append(csvRow(TEAM_COLUMNS,row));
                });
                if(csv.isEmpty())throw new IllegalArgumentException("Team mirror has no requested season data");
                ObjectNode files=obj();csv.forEach((team,rows)->files.put(team,rows.toString()));value=obj("files",files,"url",asset.url(),"source_updated_at",asset.updatedAt());save(name,value);
            }
            success(text(value,"url"),text(value,"source_updated_at"));Map<String,DataCollector.Download> result=new HashMap<>();String url=text(value,"url");value.path("files").fields().forEachRemaining(f->result.put(f.getKey(),new DataCollector.Download(f.getValue().asText(),url,clock.instant().toString())));return result;
        }catch(RuntimeException e){failed(name,RELEASE,e);throw e;}
    }
    synchronized DataCollector.Download goalies(int season,boolean preferFeed){
        RuntimeException first=null;
        if(preferFeed)try{
            ObjectNode value=feed();String key=season+"/goalies.csv";
            if(value.path("errors").has(key))throw new IllegalStateException("Feed reports error for "+key);
            String csv=value.path("files").path(key).asText();
            if(csv.isBlank()||Csv.read(csv).stream().noneMatch(r->(""+season).equals(r.get("season"))&&"all".equals(r.get("situation"))))throw new IllegalArgumentException("Feed lacks requested goalie season");
            return new DataCollector.Download(csv,FEED,clock.instant().toString());
        }catch(RuntimeException e){first=e;}
        String name="goalies/"+season;
        try{
            ObjectNode value=cached(name);
            if(value==null){Asset asset=asset("goalies_"+season+".parquet");List<String> columns=List.of("playerid","season","name","situation","games_played","icetime","xgoals","goals");StringBuilder csv=new StringBuilder(String.join(",",columns)+"\n");
                reader.read(asset,new HashSet<>(columns),row->{if((""+season).equals(row.get("season"))&&"all".equals(row.get("situation")))csv.append(csvRow(columns,row));});
                if(Csv.read(csv.toString()).isEmpty())throw new IllegalArgumentException("No goalie rows in mirror");value=obj("csv",csv.toString(),"url",asset.url(),"source_updated_at",asset.updatedAt(),"frozen",season<DataCollector.season(LocalDate.now(clock)));save(name,value);
            }
            success(text(value,"url"),text(value,"source_updated_at"),value.path("frozen").asBoolean());return new DataCollector.Download(text(value,"csv"),text(value,"url"),clock.instant().toString());
        }catch(RuntimeException e){failed(name,RELEASE,e);if(first!=null)e.addSuppressed(first);throw e;}
    }
    static String csvRow(List<String> columns,Map<String,String> row){List<String> values=new ArrayList<>();for(String column:columns){String v=row.getOrDefault(column,"");values.add("\""+v.replace("\"","\"\"")+"\"");}return String.join(",",values)+"\n";}
    synchronized ArrayNode status(){return M.valueToTree(observations.values());}
    synchronized boolean acceptCached(JsonNode sources){
        boolean available=false;
        for(JsonNode source:sources)if(text(source,"status").equals("available")){try{fresh(text(source,"source_updated_at"),source.path("frozen_season").asBoolean());available=true;}catch(DateTimeException|IllegalArgumentException e){return false;}}
        if(available)for(JsonNode source:sources)observations.put(text(source,"url"),(ObjectNode)source.deepCopy());return available;
    }
}
