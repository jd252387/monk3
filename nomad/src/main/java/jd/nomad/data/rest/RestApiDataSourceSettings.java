package jd.nomad.data.rest;

import java.util.Map;
import java.util.Optional;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
public final class RestApiDataSourceSettings {

    String url;
    String method;
    Map<String, String> headers;
    Map<String, String> query;
    String bodyTemplate;
    String contentType;
    BasicAuth basicAuth;
    TokenAuth tokenAuth;

    public RestApiDataSourceSettings(
            String url,
            String method,
            Map<String, String> headers,
            Map<String, String> query,
            String bodyTemplate,
            String contentType,
            BasicAuth basicAuth,
            TokenAuth tokenAuth) {
        this.url = url;
        this.method = method == null || method.isBlank() ? "GET" : method;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.query = query == null ? Map.of() : Map.copyOf(query);
        this.bodyTemplate = bodyTemplate;
        this.contentType = contentType;
        this.basicAuth = basicAuth == null ? BasicAuth.EMPTY : basicAuth;
        this.tokenAuth = tokenAuth == null ? TokenAuth.EMPTY : tokenAuth;
    }

    public Optional<String> url() {
        return Optional.ofNullable(url);
    }

    public String method() {
        return method;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, String> query() {
        return query;
    }

    public Optional<String> bodyTemplate() {
        return Optional.ofNullable(bodyTemplate);
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    public BasicAuth basicAuth() {
        return basicAuth;
    }

    public TokenAuth tokenAuth() {
        return tokenAuth;
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode
    @ToString
    public static final class BasicAuth {

        static final BasicAuth EMPTY = new BasicAuth(null, null);

        String username;
        String password;

        public BasicAuth(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public Optional<String> username() {
            return Optional.ofNullable(username);
        }

        public Optional<String> password() {
            return Optional.ofNullable(password);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode
    @ToString
    public static final class TokenAuth {

        static final TokenAuth EMPTY = new TokenAuth("Authorization", "Bearer ", null);

        String header;
        String prefix;
        String token;

        public TokenAuth(String header, String prefix, String token) {
            this.header = header == null || header.isBlank() ? "Authorization" : header;
            this.prefix = prefix == null ? "Bearer " : prefix;
            this.token = token;
        }

        public String header() {
            return header;
        }

        public String prefix() {
            return prefix;
        }

        public Optional<String> token() {
            return Optional.ofNullable(token);
        }
    }
}
