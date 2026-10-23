package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class GoalieAppearancesTest {
    static final String DAY="2026-10-22",PROOF="auto-nhl-goalie-8471234-current-test";
    static ObjectNode entry(String id,String date,int started){return obj("gameId",id,"gameDate",date,"gamesStarted",started,"shotsAgainst",20,"goalsAgainst",1);}
    static ObjectNode data(JsonNode log){
        ObjectNode history=DataCollector.goaliePeriod(List.of(),log,2026,LocalDate.parse(DAY),"current");
        ObjectNode goalie=obj("name","Sample goalie"),fields=obj();
        history.path("values").fields().forEachRemaining(f->{goalie.set(f.getKey(),f.getValue());fields.set("away.goalie."+f.getKey(),obj("status","found","note",text(history.path("notes"),f.getKey()),"evidence_ids",List.of(PROOF)));});
        return obj("as_of",DAY+"T18:00:00Z","games",List.of(obj("game_id","123","starts_at","2026-10-23T00:00:00Z","away",obj("goalie",goalie),"home",obj("goalie",obj("name","Unknown")),"checks",obj("fields",fields))),
            "evidence",List.of(obj("id",PROOF,"url","https://api-web.nhle.com/v1/player/8471234/game-log/20262027/2","checked_at",DAY+"T17:59:00Z")));
    }
    static JsonNode count(ObjectNode data){return GoalieAppearances.view(data,DAY).path("123").path("away");}
    @Test void countsReliefAppearancesAndExcludesTheDisplayedGameAndLaterGames(){
        ObjectNode d=data(obj("gameLog",List.of(entry("1","2026-10-19",1),entry("2","2026-10-20",0),entry("3",DAY,1),entry("4","2026-10-23",1))));
        assertEquals(1,d.path("games").get(0).path("away").path("goalie").path("starts_current").asInt());assertEquals(2,count(d).asInt());
        ObjectNode before=d.deepCopy();GoalieAppearances.view(d,DAY);assertEquals(before,d);
    }
    @Test void verifiedEmptyNhlLogGivesZeroButUnverifiedOrMissingDataDoesNot(){
        ObjectNode d=data(obj("gameLog",arr()));assertEquals(IntNode.valueOf(0),count(d));
        ((ObjectNode)d.path("games").get(0).path("checks").path("fields").path("away.goalie.starts_current")).put("status","not_checked");assertTrue(count(d).isNull());
        assertTrue(GoalieAppearances.view(d,DAY).path("123").path("home").isNull());
    }
    @Test void independentSavePercentageEvidenceCanSupplyTheAppearanceCount(){
        ObjectNode d=data(obj("gameLog",List.of(entry("1","2026-10-20",0))));
        ((ObjectNode)d.path("games").get(0).path("checks").path("fields")).remove("away.goalie.starts_current");assertEquals(1,count(d).asInt());
    }
    @Test void rejectsWrongSeasonCutoffUnknownProofAndConflictingCounts(){
        ObjectNode base=data(obj("gameLog",List.of(entry("1","2026-10-20",1))));
        for(String url:List.of("https://api-web.nhle.com/v1/player/8471234/game-log/20252026/2","https://other.example/game-log/20262027/2")){
            ObjectNode d=base.deepCopy();((ObjectNode)d.path("evidence").get(0)).put("url",url);assertTrue(count(d).isNull());
        }
        ObjectNode d=base.deepCopy();((ObjectNode)d.path("evidence").get(0)).put("checked_at","2026-10-23T01:00:00Z");assertTrue(count(d).isNull());
        assertTrue(GoalieAppearances.view(base,"2026-10-23").path("123").path("away").isNull());
        d=base.deepCopy();d.set("evidence",arr());assertTrue(count(d).isNull());
        d=base.deepCopy();ObjectNode check=(ObjectNode)d.path("games").get(0).path("checks").path("fields").path("away.goalie.save_pct_current");check.put("note",text(check,"note").replace("appearances=1","appearances=2"));assertTrue(count(d).isNull());
    }
    static ObjectNode researchedDebut(){
        ObjectNode d=data(obj("gameLog",arr())),g=(ObjectNode)d.path("games").get(0),goalie=(ObjectNode)g.path("away").path("goalie"),checks=(ObjectNode)g.path("checks").path("fields");
        for(String field:List.of("starts_current","starts_prior","save_pct_current","gsax_per60_current")){
            boolean starts=field.startsWith("starts");if(starts)goalie.put(field,0);else goalie.putNull(field);
            checks.set("away.goalie."+field,obj("status",starts?"found":"unavailable","evidence_ids",List.of("researched-debut-proof"),"note",starts?"First NHL debut report implies 0 NHL games started before game.":"No NHL appearances before debut, so NHL GSAx and SV% rate undefined; no substituted AHL data."));
        }
        d.set("evidence",arr().add(obj("id","researched-debut-proof","url","https://news.example/goalie-nhl-debut","checked_at",DAY+"T17:59:00Z")));return d;
    }
    @Test void sourcedDebutResearchShowsZeroWithoutRewritingTheSavedSnapshot(){
        ObjectNode d=researchedDebut(),before=d.deepCopy();assertEquals(IntNode.valueOf(0),count(d));assertEquals(before,d);
    }
    @Test void zeroStartsAndUnavailableRatesWithoutExplicitDebutEvidenceStayUnknown(){
        ObjectNode d=researchedDebut();ObjectNode check=(ObjectNode)d.path("games").get(0).path("checks").path("fields").path("away.goalie.save_pct_current");
        check.put("note","SV% unavailable; goalie may have played in relief.");assertTrue(count(d).isNull());
        d=researchedDebut();((ObjectNode)d.path("games").get(0).path("away").path("goalie")).put("starts_prior",1);assertTrue(count(d).isNull());
    }
    @Test void debutNeedsSharedExistingEvidenceBeforeThePredictionCutoff(){
        ObjectNode d=researchedDebut();d.set("evidence",arr());assertTrue(count(d).isNull());
        d=researchedDebut();((ObjectNode)d.path("evidence").get(0)).put("checked_at","2026-10-23T01:00:00Z");assertTrue(count(d).isNull());
        d=researchedDebut();((ObjectNode)d.path("games").get(0).path("checks").path("fields").path("away.goalie.starts_prior")).set("evidence_ids",arr().add("unrelated-proof"));assertTrue(count(d).isNull());
    }
}
