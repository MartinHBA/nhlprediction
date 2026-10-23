package sk.nhl;

import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class OddizeClientTest {
    @Test void providerErrorsAreNotCachedAndNeverExposeCredentialsOrResponseBodies(){
        for(int status:List.of(401,402,429,500)){
            MemoryStore store=new MemoryStore();AtomicInteger count=new AtomicInteger();
            var c=new OddizeClient(store,OddizeOddsTest.CLOCK,"private-key",r->{count.incrementAndGet();return new OddizeProbe.Upstream(status,HttpHeaders.of(Map.of(),(a,b)->true),"private-key sensitive provider diagnostic");},"test",false);
            var error=assertThrows(IllegalStateException.class,()->c.get(OddizeOddsTest.DAY,"/odds/latest?sport=nhl",1));
            assertTrue(error.getMessage().contains("HTTP "+status));assertFalse(error.getMessage().contains("private-key"));assertFalse(error.getMessage().contains("sensitive"));assertTrue(store.keys("cache/").isEmpty());
            assertThrows(IllegalStateException.class,()->c.get(OddizeOddsTest.DAY,"/odds/latest?sport=nhl",1));assertEquals(2,count.get());
        }
    }
    @Test void cachedPricesRetainTheOriginalObservationTimeAcrossClockChanges(){
        MemoryStore store=new MemoryStore();var first=OddizeOddsTest.importer(store,new AtomicInteger(),OddizeOddsTest::response);first.collect(OddizeOddsTest.DAY,OddizeOddsTest.data());
        Clock later=Clock.fixed(OddizeOddsTest.NOW.plusSeconds(3600),ZoneOffset.UTC);
        var c=new OddizeClient(store,later,"",r->{throw new AssertionError("Cache must not need a key or network");},"test",false);
        var value=c.get(OddizeOddsTest.DAY,"/odds/latest?sport=nhl&date="+OddizeOddsTest.DAY+"&limit=100",1);
        assertEquals(OddizeOddsTest.NOW.toString(),text(value,"checked_at"));assertEquals(0,c.creditsUsed);
    }
    @Test void missingKeyAndUnsafeRequestsNeverReachTheProvider(){
        var c=new OddizeClient(new MemoryStore(),OddizeOddsTest.CLOCK,"",r->{throw new AssertionError("No request permitted");},"test",false);
        assertThrows(IllegalStateException.class,()->c.get(OddizeOddsTest.DAY,"/odds/latest?sport=nhl",1));
        for(String path:List.of("https://other.example","/../secret","/odds#key"))assertThrows(IllegalArgumentException.class,()->c.get(OddizeOddsTest.DAY,path,1));
    }
}
