package jd.nomad.data.s3;

import java.util.Optional;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
public final class S3DataSourceSettings {

    String bucket;
    String keyTemplate;

    public S3DataSourceSettings(String bucket, String keyTemplate) {
        this.bucket = bucket;
        this.keyTemplate = keyTemplate == null || keyTemplate.isBlank() ? "{id}" : keyTemplate;
    }

    public Optional<String> bucket() {
        return Optional.ofNullable(bucket);
    }

    public String keyTemplate() {
        return keyTemplate;
    }

    public String resolveKey(String primaryKey) {
        if (primaryKey == null || primaryKey.isBlank()) {
            return null;
        }
        String template = keyTemplate();
        if (template.contains("{id}")) {
            return template.replace("{id}", primaryKey);
        }
        if (template.endsWith("/")) {
            return template + primaryKey;
        }
        return template + '/' + primaryKey;
    }
}
