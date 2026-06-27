package jd.nomad.data;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;

import java.util.Set;

import jd.nomad.model.IndexEvent;

public interface DataFetcher {
    Uni<JsonNode> fetch(IndexEvent event, Set<String> fields);
}
