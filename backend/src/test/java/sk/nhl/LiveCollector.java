package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Explicit owner-run probe of actual data downloads. Never runs in the offline test suite. */
public final class LiveCollector {
    public static void main(String[] args)throws Exception {
        JsonNode publication=parse(Files.readString(Path.of(args[0])));String day=text(publication,"day");
        ObjectNode data=obj("as_of",Instant.now().toString(),"synthetic_data",false,"expected_game_ids",publication.path("games").findValuesAsText("game_id"),"evidence",publication.path("evidence"),"games",publication.path("games"));
        long start=System.nanoTime();ObjectNode imported=new DataCollector(new MemoryStore()).enrich(day,data);
        ArrayNode metrics=arr();for(JsonNode g:imported.path("dataset").path("games")){metrics.add(obj("game_id",text(g,"game_id"),"home",g.path("home"),"away",g.path("away")));}
        Files.writeString(Path.of(args[1]),imported.toPrettyString());
        System.out.println(obj("seconds",(System.nanoTime()-start)/1e9,"coverage",imported.path("coverage"),"warnings",imported.path("warnings"),"validation_errors",Checks.validate(imported.path("dataset")),"checklist_issues",Checks.audit(imported.path("dataset")).path("issues")).toPrettyString());
    }
}
