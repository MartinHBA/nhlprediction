package sk.nhl;

import java.util.*;

/** Audited North-American full-game rules. Unknown books remain unverified, never guessed. */
final class BookmakerRules {
    static final String VERIFIED_AT="2026-10-23T18:52:35Z";
    static final Map<String,String> FULL_GAME=Map.of(
        "fd","https://massgaming.com/wp-content/uploads/FanDuel-House-Rules-3.30.26.pdf",
        "mgm","https://massgaming.com/wp-content/uploads/BetMGM-House-Rules-4.22.26.pdf",
        "dk","https://massgaming.com/wp-content/uploads/DraftKings-House-Rules-10.7.26.pdf");
    static String url(String book){return FULL_GAME.get(book);}
    static String basis(String book){return FULL_GAME.containsKey(book)?"official_final":"unknown";}
}
