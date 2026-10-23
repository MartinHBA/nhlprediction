package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static sk.nhl.Json.*;

/** Public display projection of recorded NHL counts and explicitly evidenced NHL debuts. */
final class GoalieAppearances {
    static final Pattern NOTE=Pattern.compile("^NHL (?:gamesStarted summed|regular-season gameLog) before (\\d{4}-\\d{2}-\\d{2}); season=(\\d{4}), appearances=(\\d+)(?:, starts=\\d+\\.| including relief;)");
    static ObjectNode view(JsonNode data,String day){
        ObjectNode out=obj();Map<String,JsonNode> proofs=new HashMap<>();for(JsonNode e:data.path("evidence"))proofs.put(text(e,"id"),e);
        for(JsonNode game:data.path("games")){ObjectNode sides=obj();for(String side:List.of("away","home"))sides.set(side,count(data,game,side,day,proofs));out.set(text(game,"game_id"),sides);}return out;
    }
    static JsonNode count(JsonNode data,JsonNode game,String side,String day,Map<String,JsonNode> proofs){
        Integer count=null;
        try{
            int season=DataCollector.season(LocalDate.parse(day));String seasonCode=""+season+(season+1);
            Instant cutoff=Checks.time(text(data,"as_of")),start=Checks.time(text(game,"starts_at"));if(start.isBefore(cutoff))cutoff=start;
            for(String field:List.of("starts_current","save_pct_current")){
                JsonNode check=game.path("checks").path("fields").path(side+".goalie."+field);
                if(!text(check,"status").equals("found")||missing(at(game,side+".goalie."+field)))continue;
                Matcher note=NOTE.matcher(text(check,"note"));if(!note.find()||!note.group(1).equals(day)||Integer.parseInt(note.group(2))!=season)continue;
                boolean nativeProof=false;
                for(String id:strings(check.path("evidence_ids"))){JsonNode proof=proofs.get(id);if(proof!=null&&id.startsWith("auto-nhl-goalie-")&&text(proof,"url").matches("https://api-web\\.nhle\\.com/v1/player/\\d{6,10}/game-log/"+seasonCode+"/2")&&!Checks.time(text(proof,"checked_at")).isAfter(cutoff))nativeProof=true;}
                if(!nativeProof)continue;int value=Integer.parseInt(note.group(3));if(count!=null&&count!=value)return NullNode.instance;count=value;
            }
        }catch(RuntimeException invalid){return NullNode.instance;}
        return count==null?researchedDebut(data,game,side,proofs):IntNode.valueOf(count);
    }
    /** Legacy research can prove an NHL debut without a native game log. Zero starts alone never proves zero appearances. */
    static JsonNode researchedDebut(JsonNode data,JsonNode game,String side,Map<String,JsonNode> proofs){
        try{
            Set<String> shared=null;
            for(String field:List.of("starts_current","starts_prior","save_pct_current","gsax_per60_current")){
                JsonNode value=at(game,side+".goalie."+field),check=game.path("checks").path("fields").path(side+".goalie."+field);
                if(field.startsWith("starts")){
                    if(!value.isIntegralNumber()||value.asInt()!=0||!text(check,"status").equals("found"))return NullNode.instance;
                }else{
                    if(!value.isNull()||!text(check,"status").equals("unavailable")||!text(check,"note").matches("(?is)^No NHL appearances before debut(?:[,. ;].*)?$"))return NullNode.instance;
                }
                Set<String> refs=new HashSet<>(strings(check.path("evidence_ids")));if(shared==null)shared=refs;else shared.retainAll(refs);
            }
            Instant cutoff=Checks.time(text(data,"as_of")),start=Checks.time(text(game,"starts_at"));if(start.isBefore(cutoff))cutoff=start;
            for(String id:shared){JsonNode proof=proofs.get(id);if(proof!=null&&text(proof,"url").matches("https://[^\\s/]+/\\S+")&&!Checks.time(text(proof,"checked_at")).isAfter(cutoff))return IntNode.valueOf(0);}
        }catch(RuntimeException invalid){return NullNode.instance;}
        return NullNode.instance;
    }
}
