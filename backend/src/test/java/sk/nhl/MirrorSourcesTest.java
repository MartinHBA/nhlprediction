package sk.nhl;

import org.junit.jupiter.api.*;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class MirrorSourcesTest {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"),ZoneOffset.UTC);
    static final String GOALIES="playerId,season,name,situation,games_played,icetime,xGoals,goals\n8478048,2026,Igor Shesterkin,all,2,4800,4,3\n";
    static String release(String name,String updated){return obj("assets",List.of(obj("name",name,"updated_at",updated,"size",500,"browser_download_url",MirrorSources.RELEASE_BASE+name))).toString();}
    static DataCollector.Download download(String url,String body){return new DataCollector.Download(body,url,CLOCK.instant().toString());}
    @Test void rawCsvFeedIsPreferredAndCachedWithItsPublicationTime(){
        AtomicInteger fetches=new AtomicInteger();MirrorSources m=new MirrorSources(new MemoryStore(),url->{fetches.incrementAndGet();assertEquals(MirrorSources.FEED,url);return download(url,obj("updatedAt","2026-10-20T04:44:56Z","files",obj("2026/goalies.csv",GOALIES),"errors",obj()).toString());},CLOCK,(a,c,r)->fail("Parquet should not be required"));
        assertEquals(1,Csv.read(m.goalies(2026,true).content()).size());assertEquals(MirrorSources.FEED,m.goalies(2026,true).url());assertEquals(1,fetches.get());assertEquals("2026-10-20T04:44:56Z",text(m.status().get(0),"source_updated_at"));
    }
    @Test void staleOrFailedFeedFallsBackToParquetAndPreservesAttribution(){
        for(boolean stale:List.of(false,true)){
            MirrorSources m=new MirrorSources(new MemoryStore(),url->{if(url.equals(MirrorSources.FEED)){if(!stale)throw new IllegalStateException("HTTP 503");return download(url,obj("updatedAt","2026-10-17T00:00:00Z","files",obj("2026/goalies.csv",GOALIES)).toString());}return download(url,release("goalies_2026.parquet","2026-10-19T16:00:00Z"));},CLOCK,(a,c,rows)->Csv.read(GOALIES).forEach(rows));
            var result=m.goalies(2026,true);assertTrue(result.url().endsWith("goalies_2026.parquet"));assertEquals("8478048",Csv.read(result.content()).get(0).get("playerid"));
        }
    }
    @Test void fileSpecificFeedErrorsDoNotImportThatCsv(){
        MirrorSources m=new MirrorSources(new MemoryStore(),url->download(url,url.equals(MirrorSources.FEED)?obj("updatedAt",CLOCK.instant().toString(),"files",obj("2026/goalies.csv",GOALIES),"errors",obj("2026/goalies.csv","source failed")).toString():release("goalies_2026.parquet","2026-10-19T16:00:00Z")),CLOCK,(a,c,rows)->Csv.read(GOALIES).forEach(rows));
        assertTrue(m.goalies(2026,true).url().endsWith(".parquet"));
    }
    @Test void staleCurrentParquetAndFutureFeedAreRejected(){
        MirrorSources m=new MirrorSources(new MemoryStore(),url->download(url,release("goalies_2026.parquet","2026-10-17T16:00:00Z")),CLOCK,(a,c,rows)->fail());
        assertThrows(IllegalArgumentException.class,()->m.goalies(2026,false));assertThrows(IllegalArgumentException.class,()->m.fresh("2026-10-21T00:00:00Z"));
    }
    @Test void closedPreviousSeasonIsAllowedWithoutPretendingItWasRefreshedToday(){
        MirrorSources m=new MirrorSources(new MemoryStore(),url->download(url,release("goalies_2025.parquet","2026-08-01T00:00:00Z")),CLOCK,(a,c,rows)->Csv.read(GOALIES.replace(",2026,",",2025,")).forEach(rows));
        assertTrue(m.goalies(2025,false).content().contains("2025"));assertTrue(m.goalies(2025,false).content().contains("2025"));assertTrue(m.status().get(0).path("frozen_season").asBoolean());assertEquals("2026-08-01T00:00:00Z",text(m.status().get(0),"source_updated_at"));
    }
    @Test void teamMirrorKeepsGameIdsForTheExistingStrictCutoff(){
        String data=DataCollectorTest.header+DataCollectorTest.gameRows("BOS",2026,"20261017",1)+DataCollectorTest.gameRows("BOS",2026,"20261020",2)+DataCollectorTest.gameRows("BOS",2025,"20251017",1)+DataCollectorTest.gameRows("BOS",2024,"20241017",1);
        MirrorSources m=new MirrorSources(new MemoryStore(),url->download(url,release("all_teams_2008_to2026.parquet","2026-10-19T16:00:00Z")),CLOCK,(a,c,rows)->Csv.read(data).forEach(rows));
        var result=m.teams(2026).get("BOS");assertEquals(12,Csv.read(result.content()).size());var aggregate=DataCollector.aggregate(Csv.read(result.content()),"BOS",2026,LocalDate.parse("2026-10-20"));assertEquals(1,aggregate.path("values").path("games").asInt());assertEquals(3,aggregate.path("values").path("xgf_5v5_per60").asDouble());
    }
    @Test void cachedDataCannotBecomeFreshByReadingItAgain(){
        MemoryStore store=new MemoryStore();store.put("cache/mirrors/"+DataCollector.VERSION+"/feed",obj("cached_at",CLOCK.instant().toString(),"source_updated_at","2026-10-17T00:00:00Z","files",obj("2026/goalies.csv",GOALIES)));
        MirrorSources m=new MirrorSources(store,url->{throw new IllegalStateException("Fresh source unavailable");},CLOCK);
        assertThrows(IllegalStateException.class,m::feed);
        assertFalse(m.acceptCached(arr().add(obj("url",MirrorSources.FEED,"status","available","source_updated_at","2026-10-17T00:00:00Z"))));
    }
    @Test void releaseRedirectsCannotLeaveApprovedGithubHosts(){
        ParquetData.allowed(URI.create(MirrorSources.RELEASE_BASE+"goalies_2026.parquet"));ParquetData.allowed(URI.create("https://release-assets.githubusercontent.com/test"));
        for(String url:List.of("http://github.com/test","https://example.com/file","https://github.com/another/repo/releases/file","https://github.com:8443"+MirrorSources.RELEASE_PATH+"file"))assertThrows(IllegalArgumentException.class,()->ParquetData.allowed(URI.create(url)));
    }
    @Test void failedRefreshClearsOldNativeNumbersAndPreservesResearch(){
        var input=DataCollectorTest.template("2099-01-01","2099-01-02T00:00:00Z");var game=(com.fasterxml.jackson.databind.node.ObjectNode)input.path("games").get(0);
        DataCollector.set(game,"home.current.xgf_5v5_per60",M.valueToTree(3),obj("id","auto-old"),"old",arr());
        ((com.fasterxml.jackson.databind.node.ObjectNode)game.path("home").path("roster")).set("returns",M.valueToTree(List.of("keep researched player")));
        var collector=new DataCollector(new MemoryStore(),url->{throw new IllegalStateException("source offline");},Clock.systemUTC());
        var result=collector.enrich("2099-01-01",input);var actual=result.path("dataset").path("games").get(0);
        assertTrue(at(actual,"home.current.xgf_5v5_per60").isNull());assertEquals("not_checked",text(actual.path("checks").path("fields").path("home.current.xgf_5v5_per60"),"status"));assertEquals("keep researched player",at(actual,"home.roster.returns").get(0).asText());
    }
}
