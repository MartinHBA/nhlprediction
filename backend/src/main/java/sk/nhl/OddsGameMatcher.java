package sk.nhl;

import java.time.*;
import java.util.*;

/** Matches bookmaker events to the NHL slate without guessing dates or swapping teams. */
final class OddsGameMatcher {
    static final ZoneId NHL_ZONE=ZoneId.of("America/New_York");
    static final Duration MAX_START_DIFFERENCE=Duration.ofMinutes(10);
    static final Duration MAX_QUOTE_AGE=Duration.ofHours(2);
    record OfficialGame(String id,String home,String away,Instant startsAt,boolean scheduled){}
    record BookEvent(String id,String league,String home,String away,Instant startsAt,boolean live,Instant checkedAt){}
    record Match(String gameId,String bookmakerEventId,Instant pregameCutoff,long startDifferenceSeconds){}

    /** Prefer the source's epoch/offset timestamp. Local times require an explicitly known zone. */
    static Instant localTime(LocalDate date,LocalTime time,ZoneId sourceZone){
        LocalDateTime local=LocalDateTime.of(date,time);var offsets=sourceZone.getRules().getValidOffsets(local);
        if(offsets.size()!=1)throw new IllegalArgumentException("Ambiguous or nonexistent bookmaker local time; an offset timestamp is required");
        return local.toInstant(offsets.getFirst());
    }
    static Optional<Match> match(LocalDate nhlDay,List<OfficialGame> slate,BookEvent event,Instant now){
        if(!"NHL".equals(event.league())||event.live()||event.checkedAt().isAfter(now)||event.checkedAt().isBefore(now.minus(MAX_QUOTE_AGE))||!event.startsAt().isAfter(now))return Optional.empty();
        List<OfficialGame> candidates=slate.stream().filter(game->
            game.scheduled()&&game.startsAt().isAfter(now)&&game.startsAt().atZone(NHL_ZONE).toLocalDate().equals(nhlDay)
            &&game.home().equals(event.home())&&game.away().equals(event.away())
            &&Duration.between(game.startsAt(),event.startsAt()).abs().compareTo(MAX_START_DIFFERENCE)<=0).toList();
        if(candidates.size()>1)throw new IllegalArgumentException("Ambiguous bookmaker event; more than one NHL game matches");
        if(candidates.isEmpty())return Optional.empty();
        OfficialGame game=candidates.getFirst();Instant cutoff=game.startsAt().isBefore(event.startsAt())?game.startsAt():event.startsAt();
        if(!event.checkedAt().isBefore(cutoff)||!now.isBefore(cutoff))return Optional.empty();
        return Optional.of(new Match(game.id(),event.id(),cutoff,Duration.between(game.startsAt(),event.startsAt()).abs().toSeconds()));
    }
}
