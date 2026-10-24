package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Read-only public standings. Never refreshes sources, computes ratings or exposes the private ledger. */
final class PowerRankings {
    static ObjectNode view(Store store,Instant now){
        String day=now.atZone(ZoneId.of("America/New_York")).toLocalDate().toString(),season=Elo.season(day);ObjectNode state=store.get(Elo.stateKey(day));
        if(state==null)return obj("state","AWAITING_DATA","season",season,"as_of_day",null,"teams",arr());
        JsonNode ratings=state.path("ratings"),registry=resource("nhl-teams.json");
        if(!text(state,"state").equals("READY")||!text(state,"season").equals(season)||!ratings.isObject()||ratings.size()!=32||LocalDate.parse(text(state,"cutoff_day")).isAfter(LocalDate.parse(day)))throw new IllegalStateException("Rankings snapshot is not valid");
        List<ObjectNode> teams=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonNode team:registry){String code=text(team,"code");JsonNode value=ratings.path(code);
            if(!seen.add(code)||!value.isNumber()||!Double.isFinite(value.asDouble()))throw new IllegalStateException("Incomplete rankings snapshot");
            teams.add(obj("code",code,"name",team.path("name"),"conference",team.path("conference"),"rating",value));
        }
        if(teams.size()!=32||teams.stream().filter(t->text(t,"conference").equals("Eastern")).count()!=16||teams.stream().filter(t->text(t,"conference").equals("Western")).count()!=16)throw new IllegalStateException("Rankings team registry is not valid");
        teams.sort(Comparator.comparingDouble((ObjectNode t)->number(t,"rating")).reversed().thenComparing(t->text(t,"name")));
        return obj("state","READY","season",season,"as_of_day",state.path("cutoff_day"),"updated_at",state.path("calculated_at"),"team_count",32,"teams",teams);
    }
}
