package sk.nhl;

import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class OddizeProbeTest {
    static OddizeProbe.Upstream response(int status,String body){return new OddizeProbe.Upstream(status,HttpHeaders.of(Map.of("X-Credits-Remaining",List.of("1499")),(k,v)->true),body);}
    @Test void triggerRequiresTheMasterKey(){
        var run=Arrays.stream(OddizeProbe.class.getMethods()).filter(m->m.getName().equals("run")).findFirst().orElseThrow();
        var trigger=run.getParameters()[0].getAnnotation(HttpTrigger.class);
        assertEquals(AuthorizationLevel.ADMIN,trigger.authLevel());assertEquals("api/diagnostics/oddize",trigger.route());
    }
    @Test void a401WithoutAnApiKeyProvesConnectivityButNotAuthentication(){
        var result=OddizeProbe.probe("connection","2026-10-23",null,request->{assertEquals("https://oddize.com/api/v1/sports",request.uri().toString());assertTrue(request.headers().firstValue("X-API-Key").isEmpty());return response(401,"Provider error with sensitive diagnostic content");});
        assertTrue(result.path("reachable").asBoolean());assertFalse(result.path("success").asBoolean());assertEquals(401,result.path("oddize_http_status").asInt());assertEquals("API_KEY_REQUIRED",text(result,"result"));assertFalse(result.toString().contains("sensitive"));assertEquals(0,result.path("expected_credit_cost").asInt());assertFalse(result.path("writes_daily_data").asBoolean());
    }
    @Test void sendsKeyOnlyAsHeaderAndDoesNotReturnRawBodiesOrKeyMetadata(){
        String key="ozk-test-secret";
        var result=OddizeProbe.probe("usage","2026-10-23",key,request->{assertEquals(key,request.headers().firstValue("X-API-Key").orElseThrow());assertFalse(request.uri().toString().contains(key));return response(200,obj("credits_remaining",1499,"credits_used",1,"plan_id","api_developer","key",obj("prefix",key),"extra",key).toString());});
        assertTrue(result.path("success").asBoolean());assertEquals(1499,result.path("summary").path("credits_remaining").asInt());assertFalse(result.toString().contains(key));assertTrue(result.has("elapsed_ms"));
    }
    @Test void nhlTestMakesOneBoundedRequestAndOnlyReturnsSelectedPriceFields(){
        var calls=new AtomicInteger();
        var result=OddizeProbe.probe("nhl","2026-10-23","ozk-test",request->{calls.incrementAndGet();assertEquals("sport=nhl&date=2026-10-23&limit=3",request.uri().getQuery());return response(200,obj("sport","nhl","events",List.of(obj("event_id","game-1","team1","BOS","team2","MTL","start_date","2026-10-23T23:00:00Z","odds",List.of(obj("book","dk","offer_type","t","market","total","team","Under","line",7.5,"american_odds","-400","timestamp","2026-10-23T17:00:00Z","unexpected","not-returned"))))).toString());});
        assertEquals(1,calls.get());assertTrue(result.path("success").asBoolean());assertEquals(1,result.path("expected_credit_cost").asInt());assertEquals(1,result.path("summary").path("quotes_returned").asInt());assertEquals("BOS",text(result.path("summary").path("sample_events").get(0),"away"));assertFalse(result.toString().contains("not-returned"));
    }
    @Test void reportsLimitsAndProviderFailuresWithoutLeakingTheBody(){
        for(var entry:Map.of(401,"API_KEY_REJECTED",402,"CREDITS_EXHAUSTED",403,"ACCESS_DENIED",429,"RATE_LIMITED",302,"REDIRECT_NOT_FOLLOWED").entrySet()){
            var result=OddizeProbe.probe("usage","2026-10-23","secret",request->response(entry.getKey(),"secret"));assertEquals(entry.getValue(),text(result,"result"));assertTrue(result.path("reachable").asBoolean());assertFalse(result.path("success").asBoolean());assertFalse(result.toString().contains("secret"));
        }
    }
    @Test void distinguishesNetworkFailuresAndInvalidResponses(){
        var timeout=OddizeProbe.probe("connection","2026-10-23",null,request->{throw new HttpTimeoutException("sensitive timeout details");});assertEquals("NETWORK_ERROR",text(timeout,"result"));assertFalse(timeout.path("reachable").asBoolean());assertFalse(timeout.toString().contains("sensitive"));
        var html=OddizeProbe.probe("connection","2026-10-23",null,request->response(200,"<html>challenge</html>"));assertEquals("INVALID_JSON_RESPONSE",text(html,"result"));assertFalse(html.path("success").asBoolean());
        var wrong=OddizeProbe.probe("nhl","2026-10-23",null,request->response(200,"{\"events\":[],\"sport\":\"mlb\"}"));assertEquals("UNEXPECTED_RESPONSE",text(wrong,"result"));
    }
    @Test void invalidParametersCannotTriggerAnyOutboundRequest(){
        OddizeProbe.Transport forbidden=request->{fail("Must not call the API");return null;};
        assertThrows(IllegalArgumentException.class,()->OddizeProbe.probe("https://example.com","2026-10-23",null,forbidden));
        for(String day:List.of("2026-02-30","2026-10-23&extra=1","bad"))assertThrows(IllegalArgumentException.class,()->OddizeProbe.probe("nhl",day,null,forbidden));
    }
}
