package jd.nomad.data.mongo;

import java.util.Optional;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class MongoDataSourceSettings {

    String database;
    String collection;
    String client;

    public Optional<String> database() {
        return Optional.ofNullable(database);
    }

    public Optional<String> collection() {
        return Optional.ofNullable(collection);
    }

    public String client() {
        return client;
    }
}
