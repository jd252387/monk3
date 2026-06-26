package jd.nomad.index;

import io.smallrye.mutiny.Uni;

import java.util.List;

import jd.nomad.model.IndexCommand;

public interface IndexSink {
    Uni<Void> indexBatch(List<IndexCommand> commands);
}
