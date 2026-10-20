package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static sk.nhl.Json.*;

/** Deterministic, date-bounded import of published MoneyPuck downloads and official NHL data. No LLM calls. */
final class DataCollector {
    static final String VERSION="2.3.0";
    static final String BULK_TEAMS="https://moneypuck.com/moneypuck/playerData/careers/gameByGame/all_teams.csv";
    record Download(String content,String url,String checkedAt) {}
    interface Fetcher { Download get(String url); }
    static long nextMoneyPuck=0,blockedMoneyPuck=0;
    static synchronized void paceMoneyPuck(){long now=System.currentTimeMillis();if(now<blockedMoneyPuck)throw new IllegalStateException("MoneyPuck requested a download cooldown; retry later");long wait=nextMoneyPuck-now;if(wait>0)try{Thread.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Download interrupted",e);}nextMoneyPuck=System.currentTimeMillis()+1200;}
    final Store store;final Fetcher fetch;final Clock clock;boolean usePublishedBulk=false;MirrorSources mirrors;NhlOdds odds;
    final Map<Integer,Map<String,Download>> bulkCache=new HashMap<>();
    DataCollector(Store store){this(store,DataCollector::download,Clock.systemUTC());usePublishedBulk=true;mirrors=new MirrorSources(store,fetch,clock);odds=new NhlOdds(fetch,clock);}
    DataCollector(Store store,Fetcher fetch,Clock clock){this.store=store;this.fetch=fetch;this.clock=clock;}
    static String teamUrl(String team){if(!team.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid team code");return "https://moneypuck.com/moneypuck/playerData/careers/gameByGame/regular/teams/"+team+".csv";}
    static String clubUrl(String team,int season){if(!team.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid team code");return "https://api-web.nhle.com/v1/club-schedule-season/"+team+"/"+season+(season+1);}
    static int season(LocalDate day){return day.getMonthValue()>=7?day.getYear():day.getYear()-1;}
    static Download download(String url){
        URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||!Set.of("moneypuck.com","api-web.nhle.com","api.nhle.com").contains(uri.getHost())&&!url.equals(MirrorSources.FEED)&&!url.equals(MirrorSources.RELEASE))throw new IllegalArgumentException("Unsupported data host");
        if(uri.getHost().equals("moneypuck.com"))paceMoneyPuck();
        try{
            var r=Schedule.HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("User-Agent","NHL-Observatory/2.1 personal research; published data downloads").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var in=r.body()){if(r.statusCode()==429&&uri.getHost().equals("moneypuck.com")){long seconds=60;String retry=r.headers().firstValue("Retry-After").orElse("60");try{seconds=Math.max(60,Long.parseLong(retry));}catch(NumberFormatException e){try{seconds=Math.max(60,Duration.between(Instant.now(),ZonedDateTime.parse(retry,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds());}catch(DateTimeException ignored){}}synchronized(DataCollector.class){blockedMoneyPuck=System.currentTimeMillis()+Math.min(seconds,86400)*1000;}}if(r.statusCode()!=200)throw new IllegalStateException("HTTP "+r.statusCode()+" from "+url);byte[] bytes=in.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new IllegalStateException("Data export exceeds size limit");return new Download(new String(bytes,StandardCharsets.UTF_8),url,Instant.now().toString());}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Data download interrupted",e);}catch(java.io.IOException e){throw new IllegalStateException("Could not download "+url,e);}
    }
    ObjectNode enrich(String day,JsonNode input){
        LocalDate date=LocalDate.parse(Schedule.day(day));ObjectNode data=input.deepCopy();ArrayNode warnings=arr(),sources=arr();ObjectNode locked=obj();Set<String> teams=new LinkedHashSet<>();
        // A failed refresh must not relabel old native values as a successful current import.
        for(JsonNode game:data.path("games"))if(text(game,"status").equals("scheduled"))for(String side:List.of("home","away")){
            for(String period:List.of("current","prior")){
                for(String field:Checks.STATS)clearNative((ObjectNode)game,side+"."+period+"."+field);
                ((ObjectNode)game.path(side).path(period).path("source")).put("status","unverified");
            }
            for(String field:Checks.CONTEXT)clearNative((ObjectNode)game,side+".context."+field);
            for(String field:List.of("starts_current","starts_prior","gsax_per60_current","gsax_per60_prior"))clearNative((ObjectNode)game,side+".goalie."+field);
        }
        for(JsonNode game:data.path("games"))if(text(game,"status").equals("scheduled"))for(String side:List.of("home","away"))teams.add(text(game,side+".name"));
        Map<String,ObjectNode> fragments=new HashMap<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(4)){
            Map<String,Future<ObjectNode>> tasks=new LinkedHashMap<>();for(String team:teams)tasks.put(team,pool.submit(()->team(day,team)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            for(var entry:tasks.entrySet())try{fragments.put(entry.getKey(),entry.getValue().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS));}catch(Exception e){entry.getValue().cancel(true);warnings.add(entry.getKey()+": data import failed or timed out; retry start_day. "+rootMessage(e));}
        }
        for(JsonNode entry:data.path("games")){
            if(!text(entry,"status").equals("scheduled"))continue;ObjectNode game=(ObjectNode)entry;ArrayNode protectedPaths=arr();
            for(String side:List.of("home","away")){
                ObjectNode fragment=fragments.get(text(game,side+".name"));if(fragment==null)continue;
                for(JsonNode w:fragment.path("warnings"))warnings.add(w);
                for(String period:List.of("prior","current")){
                    JsonNode stats=fragment.path(period);JsonNode evidence=fragment.path("evidence");mergeEvidence(data,evidence);if(!evidence.isMissingNode())sources.add(evidence);
                    if(!stats.path("values").isEmpty()){((ObjectNode)game.path(side).path(period)).set("source",obj("url",text(evidence,"url"),"checked_at",text(evidence,"checked_at"),"status",fragment.path(period+"_sample_mismatch").asBoolean()?"unverified":"verified"));protectedPaths.add(side+"."+period+".source");}
                    stats.path("values").fields().forEachRemaining(f->set(game,side+"."+period+"."+f.getKey(),f.getValue(),evidence,text(stats.path("notes"),f.getKey()),protectedPaths));
                }
                JsonNode nhl=fragment.path("nhl");if(!nhl.isMissingNode()){
                    JsonNode nhlEvidence=fragment.path("nhl_evidence");mergeEvidence(data,nhlEvidence);sources.add(nhlEvidence);
                    addContext(game,side,date,nhl,nhlEvidence,protectedPaths,warnings);
                }
                for(String period:List.of("prior","current")){
                    JsonNode points=fragment.path(period+"_points");if(points.has("value")){JsonNode e=points.path("evidence");mergeEvidence(data,e);sources.add(e);set(game,side+"."+period+".points_pct",points.path("value"),e,text(points,"note"),protectedPaths);}
                }
            }
            locked.set(text(game,"game_id"),protectedPaths);
        }
        ObjectNode goalie=enrichGoalies(day,data);for(JsonNode w:goalie.path("warnings"))warnings.add(w);
        ObjectNode oddsMeta=null;if(odds!=null){ObjectNode imported=odds.collect(day,data);data=(ObjectNode)imported.path("dataset");oddsMeta=(ObjectNode)imported.path("odds_import");}
        data.put("as_of",clock.instant().toString());for(JsonNode g:data.path("games"))if(text(g,"status").equals("scheduled")&&!Checks.time(text(g,"starts_at")).isAfter(clock.instant())){((ObjectNode)g).put("status","live");((ObjectNode)g).set("markets",arr());}
        var validation=Checks.validate(data);if(!validation.isEmpty())throw new IllegalStateException("Imported data failed validation: "+String.join("; ",validation.subList(0,Math.min(8,validation.size()))));
        return obj("dataset",data,"auto_fields",locked,"version",VERSION,"updated_at",clock.instant().toString(),"warnings",unique(warnings),"sources",uniqueSources(sources),"mirror_sources",mirrors==null?arr():mirrors.status(),"odds_import",oddsMeta,"attribution","Team xG and goalie GSAx data: MoneyPuck.com, via public GitHub mirrors. NHL schedule and starts: NHL. Odds: DraftKings via NHL.","coverage",Checks.coverage(data));
    }
    ObjectNode team(String day,String team){
        String key="cache/prefill/"+VERSION+"/"+day+"/"+team;ObjectNode cached=store.get(key);
        if(cacheValid(cached))return cached;
        int season=season(LocalDate.parse(day));ObjectNode result=obj("warnings",arr());
        try{
            Download source=usePublishedBulk?bulk(season).get(team):fetch.get(teamUrl(team));if(source==null)throw new IllegalStateException("Team absent from published export");var rows=Csv.read(source.content);JsonNode e=evidence(source,"mp-team-"+team);result.set("evidence",e);
            result.set("current",aggregate(rows,team,season,LocalDate.parse(day)));result.set("prior",aggregate(rows,team,season-1,LocalDate.parse(day)));
        }catch(RuntimeException e){((ArrayNode)result.path("warnings")).add(team+": MoneyPuck import: "+rootMessage(e));}
        for(String period:List.of("current","prior"))try{
            Download source=fetch.get(clubUrl(team,period.equals("current")?season:season-1));JsonNode schedule=parse(source.content);JsonNode e=evidence(source,"nhl-"+team+"-"+period);
            ObjectNode points=points(schedule,team,LocalDate.parse(day),clock.instant());points.set("evidence",e);result.set(period+"_points",points);
            if(period.equals("current")){result.set("nhl",schedule);result.set("nhl_evidence",e);}
            if(result.has(period)){
                Set<String> official=new HashSet<>();for(JsonNode g:schedule.path("games"))if(eligible(g,LocalDate.parse(day),clock.instant()))official.add(text(g,"id"));
                Set<String> observed=new HashSet<>(strings(result.path(period).path("game_ids")));
                if(!official.isEmpty()&&!official.equals(observed)){result.put(period+"_sample_mismatch",true);((ArrayNode)result.path("warnings")).add(team+"/"+period+": MoneyPuck sample "+observed.size()+" games differs from NHL "+official.size()+". Metrics use the identified available sample; team input remains unverified for betting filters.");}
            }
        }catch(RuntimeException e){((ArrayNode)result.path("warnings")).add(team+"/"+period+": NHL import: "+rootMessage(e));}
        result.put("cached_at",clock.instant().toString());if(mirrors!=null)result.set("mirror_sources",mirrors.status());
        // Do not cache network failures for six hours: a subsequent run must be able to retry.
        if(result.path("warnings").isEmpty())store.put(key,result);
        return result;
    }
    static ObjectNode aggregate(List<Map<String,String>> rows,String team,int season,LocalDate cutoff){
        Map<String,List<Map<String,String>>> groups=new HashMap<>();Set<String> ids=new LinkedHashSet<>();
        for(var row:rows){if(!team.equals(row.get("team"))||!Integer.toString(season).equals(row.get("season")))continue;LocalDate date=LocalDate.parse(row.get("gamedate"),DateTimeFormatter.BASIC_ISO_DATE);String id=row.get("gameid");if(!date.isBefore(cutoff)||!id.matches(season+"02\\d{4}"))continue;groups.computeIfAbsent(row.get("situation"),k->new ArrayList<>()).add(row);if(row.get("situation").equals("all")&&!ids.add(id))throw new IllegalArgumentException("Duplicate team game row");}
        ObjectNode values=obj(),notes=obj();if(ids.isEmpty())return obj("values",values,"notes",notes,"game_ids",ids);
        values.put("games",ids.size());notes.put("games","Count of distinct regular-season gameId with gameDate < "+cutoff+"; season="+season+", situation=all, n="+ids.size()+".");
        rate(groups,ids,"5on5","xgoalsfor","xgf_5v5_per60",12,values,notes);rate(groups,ids,"5on5","xgoalsagainst","xga_5v5_per60",12,values,notes);
        rate(groups,ids,"5on4","xgoalsfor","pp_xgf_per60",30,values,notes);rate(groups,ids,"4on5","xgoalsagainst","pk_xga_per60",30,values,notes);
        rate(groups,ids,"5on5","highdangershotsfor","high_danger_for_per60",100,values,notes);rate(groups,ids,"5on5","highdangershotsagainst","high_danger_against_per60",100,values,notes);
        perGame(groups.get("all"),ids,"penaltiesagainst","pp_drawn_per_game",12,values,notes);perGame(groups.get("all"),ids,"penaltiesfor","penalties_taken_per_game",12,values,notes);
        perGame(groups.get("all"),ids,"shotsongoalfor","shots_for_per_game",100,values,notes);perGame(groups.get("all"),ids,"shotsongoalagainst","shots_against_per_game",100,values,notes);
        return obj("values",values,"notes",notes,"game_ids",ids);
    }
    synchronized Map<String,Download> bulk(int season){
        if(mirrors!=null)return mirrors.teams(season);
        Map<String,Download> cached=bulkCache.get(season);if(cached!=null&&!cached.isEmpty()&&Checks.time(cached.values().iterator().next().checkedAt).isAfter(clock.instant().minusSeconds(21600)))return cached;
        paceMoneyPuck();
        try{
            var response=Schedule.HTTP.send(HttpRequest.newBuilder(URI.create(BULK_TEAMS)).timeout(Duration.ofSeconds(25)).header("User-Agent","NHL-Observatory/2.1 personal non-commercial research; published CSV download").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var in=response.body()){
                if(response.statusCode()!=200){if(response.statusCode()==403||response.statusCode()==429)synchronized(DataCollector.class){blockedMoneyPuck=System.currentTimeMillis()+900000;}throw new IllegalStateException("HTTP "+response.statusCode()+" from published MoneyPuck bulk export");}
                ScheduledExecutorService timeout=Executors.newSingleThreadScheduledExecutor();var stop=timeout.schedule(()->{try{in.close();}catch(java.io.IOException ignored){}},120,TimeUnit.SECONDS);
                Map<String,String> selected;try{selected=selectBulk(new java.io.InputStreamReader(in,StandardCharsets.UTF_8),season);}finally{stop.cancel(false);timeout.shutdownNow();}
                String checked=clock.instant().toString();Map<String,Download> result=new HashMap<>();selected.forEach((team,csv)->result.put(team,new Download(csv,BULK_TEAMS,checked)));bulkCache.put(season,result);return result;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Bulk download interrupted",e);}catch(java.io.IOException e){throw new IllegalStateException("Bulk download failed",e);}
    }
    /** The published team file has unquoted team/season keys and no multiline team names. */
    static Map<String,String> selectBulk(java.io.Reader input,int season)throws java.io.IOException{
        java.io.BufferedReader reader=new java.io.BufferedReader(input,65536);String header=reader.readLine();if(header==null)throw new IllegalArgumentException("Empty team export");header=header.replace("\ufeff","");if(!header.toLowerCase(Locale.ROOT).startsWith("team,season,"))throw new IllegalArgumentException("Unexpected published team export keys");
        Map<String,StringBuilder> data=new HashMap<>();String line;long size=0;while((line=reader.readLine())!=null){size+=line.length();if(size>256L*1024*1024)throw new IllegalArgumentException("Team export exceeds size limit");String[] keys=line.split(",",3);if(keys.length<3||!keys[0].matches("[A-Z]{3}")||(!keys[1].equals(Integer.toString(season))&&!keys[1].equals(Integer.toString(season-1))))continue;String h=header;data.computeIfAbsent(keys[0],team->new StringBuilder(h).append('\n')).append(line).append('\n');}
        Map<String,String> result=new HashMap<>();data.forEach((team,csv)->result.put(team,csv.toString()));return result;
    }
    static void rate(Map<String,List<Map<String,String>>>groups,Set<String>ids,String situation,String column,String target,double max,ObjectNode values,ObjectNode notes){
        List<Map<String,String>>rows=groups.get(situation);if(!sameGames(rows,ids))return;Double seconds=sum(rows,"icetime"),numerator=sum(rows,column);if(seconds==null||numerator==null||seconds<=0)return;double value=numerator*3600/seconds;if(value<0||value>max)return;
        values.put(target,value);notes.put(target,"season gameDate cutoff applied; situation="+situation+", n="+ids.size()+"; sum("+column+")="+numerator+"; sum(iceTime seconds)="+seconds+"; per60=sum*3600/seconds. High-danger events follow MoneyPuck >=20% xG definition.");
    }
    static void perGame(List<Map<String,String>>rows,Set<String>ids,String column,String target,double max,ObjectNode values,ObjectNode notes){
        if(!sameGames(rows,ids))return;Double total=sum(rows,column);if(total==null)return;double value=total/ids.size();if(value>max)return;values.put(target,value);notes.put(target,"situation=all; sum("+column+")="+total+" / "+ids.size()+" distinct pre-day regular-season games. PenaltiesFor=taken, PenaltiesAgainst=drawn; penalty counts include majors/minors, not penalty minutes or power-play opportunities.");
    }
    static boolean sameGames(List<Map<String,String>>rows,Set<String>ids){return rows!=null&&rows.size()==ids.size()&&rows.stream().map(r->r.get("gameid")).collect(java.util.stream.Collectors.toSet()).equals(ids);}
    static Double sum(List<Map<String,String>>rows,String column){double value=0;for(var r:rows){String raw=r.get(column);if(raw==null||raw.isBlank())return null;double n=Double.parseDouble(raw);if(!Double.isFinite(n)||n<0)return null;value+=n;}return value;}
    static boolean eligible(JsonNode g,LocalDate cutoff,Instant now){return g.path("gameType").asInt()==2&&LocalDate.parse(text(g,"gameDate")).isBefore(cutoff)&&Checks.time(text(g,"startTimeUTC")).isBefore(now)&&Set.of("OFF","FINAL").contains(text(g,"gameState"));}
    static ObjectNode points(JsonNode schedule,String team,LocalDate cutoff,Instant now){
        int games=0,points=0;for(JsonNode g:schedule.path("games")){if(!eligible(g,cutoff,now))continue;JsonNode ours=text(g,"homeTeam.abbrev").equals(team)?g.path("homeTeam"):text(g,"awayTeam.abbrev").equals(team)?g.path("awayTeam"):null;if(ours==null||!ours.has("score"))continue;JsonNode other=ours==g.path("homeTeam")?g.path("awayTeam"):g.path("homeTeam");if(!other.has("score"))continue;games++;if(ours.path("score").asInt()>other.path("score").asInt())points+=2;else if(Set.of("OT","SO").contains(g.path("gameOutcome").path("lastPeriodType").asText(g.path("periodDescriptor").path("periodType").asText())))points++;}
        return games==0?obj():obj("value",(double)points/(2*games),"note","NHL final regular-season games before "+cutoff+": points="+points+", games="+games+"; points_pct=points/(2*games), fraction 0..1.");
    }
    static void addContext(ObjectNode game,String side,LocalDate day,JsonNode schedule,JsonNode evidence,ArrayNode protectedPaths,ArrayNode warnings){
        JsonNode previous=null,current=null;Instant start=Checks.time(text(game,"starts_at"));
        for(JsonNode g:schedule.path("games")){
            if(text(g,"id").equals(text(game,"game_id")))current=g;
            if(!Set.of("OFF","FINAL").contains(text(g,"gameState"))||!LocalDate.parse(text(g,"gameDate")).isBefore(day)||!Checks.time(text(g,"startTimeUTC")).isBefore(start))continue;
            if(previous==null||Checks.time(text(g,"startTimeUTC")).isAfter(Checks.time(text(previous,"startTimeUTC"))))previous=g;
        }
        if(previous==null)return;long rest=ChronoUnit.DAYS.between(LocalDate.parse(text(previous,"gameDate")),day)-1;
        if(rest>=0&&rest<=14)set(game,side+".context.rest_days",M.valueToTree(rest),evidence,"NHL previous completed game="+text(previous,"id")+" on "+text(previous,"gameDate")+"; full calendar rest days="+day+" - previous date - 1. Back-to-back=0.",protectedPaths);
        if(current==null)return;
        try{
            ZoneId from=ZoneId.of(text(previous,"venueTimezone")),to=ZoneId.of(text(current,"venueTimezone"));int seconds=Math.abs(from.getRules().getOffset(start).getTotalSeconds()-to.getRules().getOffset(start).getTotalSeconds());
            if(seconds%3600!=0){warnings.add("Non-whole-hour arena timezone change; not inferred");return;}
            set(game,side+".context.travel_timezones",M.valueToTree(seconds/3600),evidence,"Arena-to-arena timezone proxy: "+from+" -> "+to+" at current start, |UTC offset difference|/3600="+(seconds/3600)+". Previous game="+text(previous,"id")+". Actual flight itinerary is not observed.",protectedPaths);
        }catch(DateTimeException e){warnings.add(text(game,side+".name")+": arena timezone unavailable");}
    }
    ObjectNode enrichGoalies(String day,ObjectNode dataset){
        ArrayNode warnings=arr();Map<String,JsonNode> histories=new HashMap<>();Map<String,String> names=new LinkedHashMap<>();
        for(JsonNode g:dataset.path("games"))if(text(g,"status").equals("scheduled"))for(String side:List.of("home","away")){String name=text(g,side+".goalie.name");if(!name.isBlank()&&!name.equals("UNKNOWN"))names.put(normalize(name),name);}
        try(ExecutorService pool=Executors.newFixedThreadPool(4)){
            Map<String,Future<ObjectNode>> tasks=new LinkedHashMap<>();names.forEach((key,name)->tasks.put(key,pool.submit(()->goalie(day,name))));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            for(var entry:tasks.entrySet())try{JsonNode history=entry.getValue().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(history!=null)histories.put(entry.getKey(),history);else warnings.add(names.get(entry.getKey())+": goalie identity not found in published season exports; history not inferred.");}catch(Exception e){entry.getValue().cancel(true);warnings.add(names.get(entry.getKey())+": goalie history import failed: "+rootMessage(e));}
        }
        for(JsonNode g:dataset.path("games"))if(text(g,"status").equals("scheduled"))for(String side:List.of("home","away")){
            String name=text(g,side+".goalie.name");if(name.isBlank()||name.equals("UNKNOWN"))continue;
            try{
                JsonNode history=histories.get(normalize(name));if(history==null)continue;
                for(String period:List.of("prior","current")){
                    JsonNode p=history.path(period);for(JsonNode evidence:p.path("evidence"))mergeEvidence(dataset,evidence);
                    if(p.path("values").path("starts_"+period).asInt()>0&&!p.path("values").has("gsax_per60_"+period)){warnings.add(name+"/"+period+": MoneyPuck goalie coverage does not match NHL appearances; positive starts and GSAx were not imported separately.");continue;}
                    for(String field:List.of("starts_"+period,"gsax_per60_"+period))if(p.path("values").has(field)){
                        JsonNode e=p.path("evidence").get(field.startsWith("starts")?0:1);set((ObjectNode)g,side+".goalie."+field,p.path("values").path(field),e,text(p.path("notes"),field),arr());
                    }
                }
            }catch(RuntimeException e){warnings.add(name+": goalie history import failed: "+rootMessage(e));}
        }
        return obj("warnings",warnings);
    }
    ObjectNode goalie(String day,String name){
        int current=season(LocalDate.parse(day));String key="cache/goalie/"+VERSION+"/"+day+"/"+normalize(name);ObjectNode cached=store.get(key);if(cacheValid(cached))return cached;
        String id=null;
        for(int s:List.of(current,current-1)){
            ObjectNode index=goalieIndex(day,s);
            if(index.path("names").has(normalize(name))){id=index.path("names").path(normalize(name)).asText();break;}
        }
        if(id==null)return null;if(!id.matches("\\d{6,10}"))throw new IllegalArgumentException("Invalid goalie ID");
        Download career=null;List<Map<String,String>>rows=null;ObjectNode result=obj();boolean cacheable=true;
        for(String period:List.of("current","prior")){
            int s=period.equals("current")?current:current-1;Download nhl=fetch.get("https://api-web.nhle.com/v1/player/"+id+"/game-log/"+s+(s+1)+"/2");JsonNode log=parse(nhl.content);
            ObjectNode index=goalieIndex(day,s);JsonNode raw=index.path("entries").path(id);Map<String,String> row=new HashMap<>();raw.fields().forEachRemaining(f->row.put(f.getKey(),f.getValue().asText()));
            ObjectNode part=goalieSummary(row,log,s,LocalDate.parse(day),period);JsonNode mpEvidence=index.path("evidence");
            if(mirrors!=null&&part.path("values").path("starts_"+period).asInt()>0&&!part.path("values").has("gsax_per60_"+period))try{
                Download alternate=mirrors.goalies(s,false);for(var alternative:Csv.read(alternate.content))if(id.equals(alternative.get("playerid"))&&"all".equals(alternative.get("situation"))){ObjectNode attempt=goalieSummary(alternative,log,s,LocalDate.parse(day),period);if(attempt.path("values").has("gsax_per60_"+period)){part=attempt;mpEvidence=evidence(alternate,"mp-goalie-summary-"+s);}break;}
            }catch(RuntimeException ignored){ /* Retain exact NHL reconciliation and the date-bounded fallback. */ }
            // Historical days and lagging summaries require date-filtered game-by-game data.
            if(part.path("values").path("starts_"+period).asInt()>0&&!part.path("values").has("gsax_per60_"+period)){
                if(rows==null){career=fetch.get("https://moneypuck.com/moneypuck/playerData/careers/gameByGame/regular/goalies/"+id+".csv");rows=Csv.read(career.content);}
                part=goaliePeriod(rows,log,s,LocalDate.parse(day),period);mpEvidence=evidence(career,"mp-goalie-"+id);
                if(!part.path("values").has("gsax_per60_"+period))cacheable=false;
            }
            part.set("evidence",M.valueToTree(List.of(evidence(nhl,"nhl-goalie-"+id+"-"+period),mpEvidence)));result.set(period,part);
        }
        result.put("cached_at",clock.instant().toString());if(mirrors!=null)result.set("mirror_sources",mirrors.status());if(cacheable)store.put(key,result);return result;
    }
    synchronized ObjectNode goalieIndex(String day,int season){
        String key="cache/goalie-index-summary/"+VERSION+"/"+day+"/"+season;ObjectNode index=store.get(key);
        if(cacheValid(index))return index;
        Download download=mirrors==null?fetch.get("https://moneypuck.com/moneypuck/playerData/seasonSummary/"+season+"/regular/goalies.csv"):mirrors.goalies(season,true);ObjectNode names=obj(),entries=obj();
        for(var row:Csv.read(download.content))if("all".equals(row.get("situation"))){String n=normalize(row.get("name")),id=row.get("playerid");if(names.has(n)&&!names.path(n).asText().equals(id))throw new IllegalArgumentException("Ambiguous goalie name");names.put(n,id);if(entries.has(id))throw new IllegalArgumentException("Duplicate goalie season row");entries.set(id,M.valueToTree(row));}
        index=obj("names",names,"entries",entries,"evidence",evidence(download,"mp-goalie-summary-"+season),"cached_at",clock.instant().toString());if(mirrors!=null)index.set("mirror_sources",mirrors.status());store.put(key,index);return index;
    }
    boolean cacheValid(ObjectNode value){return value!=null&&Checks.time(text(value,"cached_at")).isAfter(clock.instant().minusSeconds(21600))&&(mirrors==null||mirrors.acceptCached(value.path("mirror_sources")));}
    static ObjectNode goalieSummary(Map<String,String>row,JsonNode log,int season,LocalDate day,String period){
        ObjectNode result=goaliePeriod(List.of(),log,season,day,period);if(row.isEmpty()||!Integer.toString(season).equals(row.get("season")))return result;
        // A season aggregate is safe only when every NHL appearance precedes the cutoff,
        // and BOTH its appearance count and exact playing time reconcile with NHL.
        long seconds=0;Set<String> ids=new HashSet<>();for(JsonNode g:log.path("gameLog")){if(!LocalDate.parse(text(g,"gameDate")).isBefore(day))return result;ids.add(text(g,"gameId"));String[] time=text(g,"toi").split(":");if(time.length!=2)return result;seconds+=Long.parseLong(time[0])*60+Long.parseLong(time[1]);}
        Double games=sum(List.of(row),"games_played"),ice=sum(List.of(row),"icetime"),xg=sum(List.of(row),"xgoals"),goals=sum(List.of(row),"goals");
        if(games==null||ice==null||xg==null||goals==null||ids.isEmpty()||games!=ids.size()||Math.abs(ice-seconds)>.1||ice<=0)return result;
        double gsax=(xg-goals)*3600/ice;if(gsax>=-3&&gsax<=3){((ObjectNode)result.path("values")).put("gsax_per60_"+period,gsax);((ObjectNode)result.path("notes")).put("gsax_per60_"+period,"MoneyPuck regular-season situation=all summary; all "+ids.size()+" NHL appearances are before "+day+", appearance count and iceTime="+ice+" seconds reconcile exactly with NHL gameLog. GSAx/60=(xGoals "+xg+" - goals "+goals+")*3600/iceTime. Includes all strengths; no post-cutoff appearances allowed.");}
        return result;
    }
    static ObjectNode goaliePeriod(List<Map<String,String>>rows,JsonNode log,int season,LocalDate day,String period){
        if(!log.path("gameLog").isArray())throw new IllegalArgumentException("NHL goalie gameLog absent; cannot infer zero starts");
        Set<String>ids=new HashSet<>();int starts=0;boolean known=true;
        for(JsonNode g:log.path("gameLog"))if(LocalDate.parse(text(g,"gameDate")).isBefore(day)){if(!ids.add(text(g,"gameId")))throw new IllegalArgumentException("Duplicate NHL goalie game");if(!g.path("gamesStarted").isIntegralNumber()||g.path("gamesStarted").asInt()<0||g.path("gamesStarted").asInt()>1)known=false;else starts+=g.path("gamesStarted").asInt();}
        ObjectNode values=obj(),notes=obj();if(known){values.put("starts_"+period,starts);notes.put("starts_"+period,"NHL gamesStarted summed before "+day+"; season="+season+", appearances="+ids.size()+", starts="+starts+". Appearances are not substituted for starts.");}
        List<Map<String,String>>selected=rows.stream().filter(r->Integer.toString(season).equals(r.get("season"))&&"all".equals(r.get("situation"))&&LocalDate.parse(r.get("gamedate"),DateTimeFormatter.BASIC_ISO_DATE).isBefore(day)).toList();
        if(!ids.isEmpty()&&sameGames(selected,ids)){
            Double xg=sum(selected,"xgoals"),goals=sum(selected,"goals"),seconds=sum(selected,"icetime");
            if(xg!=null&&goals!=null&&seconds!=null&&seconds>0){double gsax=(xg-goals)*3600/seconds;if(gsax>=-3&&gsax<=3){values.put("gsax_per60_"+period,gsax);notes.put("gsax_per60_"+period,"MoneyPuck situation=all, exactly matching "+ids.size()+" NHL pre-day appearances; GSAx/60=(sum(xGoals) "+xg+" - sum(goals) "+goals+")*3600/sum(icetime seconds) "+seconds+". Includes all strengths.");}}
        }
        return obj("values",values,"notes",notes);
    }
    static String normalize(String name){return Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    static void clearNative(ObjectNode game,String path){JsonNode check=game.path("checks").path("fields").path(path);List<String> refs=strings(check.path("evidence_ids"));if(!refs.isEmpty()&&refs.stream().allMatch(id->id.startsWith("auto-"))){String[] parts=path.split("\\.");((ObjectNode)game.path(parts[0]).path(parts[1])).putNull(parts[2]);((ObjectNode)game.path("checks").path("fields")).set(path,Schedule.check());}}
    static JsonNode evidence(Download source,String label){return obj("id","auto-"+label+"-"+OAuth.hash(source.url+source.checkedAt).substring(0,12),"url",source.url,"checked_at",source.checkedAt);}
    static void mergeEvidence(ObjectNode data,JsonNode evidence){if(evidence.isMissingNode())return;for(JsonNode e:data.path("evidence"))if(text(e,"id").equals(text(evidence,"id"))){if(!e.equals(evidence))throw new IllegalArgumentException("Conflicting automatic evidence ID");return;}((ArrayNode)data.path("evidence")).add(evidence);}
    static void set(ObjectNode game,String path,JsonNode value,JsonNode evidence,String note,ArrayNode protectedPaths){
        String[] parts=path.split("\\.");ObjectNode parent=game;for(int i=0;i<parts.length-1;i++)parent=(ObjectNode)parent.path(parts[i]);parent.set(parts[parts.length-1],value);
        ((ObjectNode)game.path("checks").path("fields")).set(path,obj("status","found","evidence_ids",List.of(text(evidence,"id")),"note",note));protectedPaths.add(path);
    }
    static ArrayNode unique(ArrayNode in){ArrayNode result=arr();new LinkedHashSet<>(strings(in)).forEach(result::add);return result;}
    static ArrayNode uniqueSources(ArrayNode in){ArrayNode result=arr();Set<String>ids=new HashSet<>();for(JsonNode e:in)if(ids.add(text(e,"id")))result.add(e);return result;}
    static String rootMessage(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
