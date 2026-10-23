package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class WebCardsTest {
    @Test void cardProjectionKeepsPublishedChoicesWithoutStatisticsOrHiddenResearch(){
        TipsTest f=new TipsTest();f.setup();f.workflow.postTips(f.date,f.body(true));
        ObjectNode before=f.workflow.require(f.date),full=f.workflow.read(f.date),cards=f.workflow.readCards(before);
        assertEquals(full.path("data_snapshot_id"),cards.path("data_snapshot_id"));
        assertFalse(cards.path("details_loaded").asBoolean());assertTrue(full.path("details_loaded").asBoolean());
        assertEquals(3,cards.path("games").size());assertEquals(full.path("final_tips").path("tip_count"),cards.path("final_tips").path("tip_count"));
        assertFalse(cards.path("games").get(0).path("home").has("current"));
        assertFalse(cards.path("games").get(0).has("checks"));assertFalse(cards.has("evidence"));
        assertFalse(cards.path("final_tips").path("games").get(0).has("rationale"));
        assertFalse(cards.path("enrichment").has("sources"));
        assertEquals(before,f.workflow.require(f.date));
        assertTrue(cards.toString().length()<full.toString().length()/2);
        ObjectNode changed=f.workflow.require(f.date);changed.put("revision",99);
        ((ObjectNode)changed.path("dataset").path("games").get(0).path("home").path("current")).put("xgf_5v5_per60",9);
        f.store.put("days/"+f.date+".json",changed);
        assertEquals(cards.path("data_snapshot_id"),f.workflow.readCards(changed).path("data_snapshot_id"));
        assertNotEquals(9,f.workflow.read(f.date).path("games").get(0).path("home").path("current").path("xgf_5v5_per60").asDouble());
    }
    @Test void firstPaintAndFullDetailsUseTheSameFrozenGoalieAppearanceCounts(){
        TipsTest f=new TipsTest();f.setup();JsonNode g=f.day.path("dataset").path("games").get(0);String id=text(g,"game_id");
        ((ObjectNode)g.path("away").path("goalie")).put("starts_current",0);
        ((ObjectNode)g.path("checks").path("fields")).set("away.goalie.starts_current",obj("status","found","note","NHL gamesStarted summed before 2099-01-01; season=2098, appearances=0, starts=0. Appearances are not substituted for starts.","evidence_ids",java.util.List.of("auto-nhl-goalie-8471234-current-zero")));
        ((ArrayNode)f.day.path("dataset").path("evidence")).add(obj("id","auto-nhl-goalie-8471234-current-zero","url","https://api-web.nhle.com/v1/player/8471234/game-log/20982099/2","checked_at",f.now.minusSeconds(30).toString()));
        f.store.put("days/"+f.date+".json",f.day);f.workflow.postTips(f.date,f.body(true));
        JsonNode first=f.workflow.readCards(f.workflow.require(f.date)).path("goalie_appearances");assertEquals(IntNode.valueOf(0),first.path(id).path("away"));
        ObjectNode changed=f.workflow.require(f.date);((ObjectNode)changed.path("dataset").path("games").get(0).path("away").path("goalie")).put("starts_current",99);
        ((ObjectNode)changed.path("dataset").path("games").get(0).path("checks").path("fields").path("away.goalie.starts_current")).put("note","Changed after publication");f.store.put("days/"+f.date+".json",changed);
        assertEquals(first,f.workflow.read(f.date).path("goalie_appearances"));assertEquals(first,f.workflow.readCards(changed).path("goalie_appearances"));
    }
    @Test void archiveOnlyReturnsSmallIndexAndUnpublishedSnapshotsChangeWithRevision(){
        TipsTest f=new TipsTest();f.setup();var first=f.workflow.readCards(f.day);
        ObjectNode changed=f.day.deepCopy();changed.put("revision",8);f.store.put("days/"+f.date+".json",changed);
        assertNotEquals(first.path("data_snapshot_id"),f.workflow.readCards(changed).path("data_snapshot_id"));
        JsonNode archive=f.workflow.archive().get(0);assertEquals(4,archive.size());assertFalse(archive.has("final_tips"));assertFalse(archive.has("coverage"));
    }
}
