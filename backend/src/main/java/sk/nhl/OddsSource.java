package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

interface OddsSource {
    ObjectNode collect(String day,JsonNode dataset);
}
