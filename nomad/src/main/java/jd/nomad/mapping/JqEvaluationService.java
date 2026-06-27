package jd.nomad.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Version;
import net.thisptr.jackson.jq.exception.JsonQueryException;
import net.thisptr.jackson.jq.module.Module;
import net.thisptr.jackson.jq.module.loaders.BuiltinModuleLoader;

@ApplicationScoped
public class JqEvaluationService {

    private final Scope jqRootScope;
    private final Map<String, JsonQuery> jqCache = new ConcurrentHashMap<>();

    public JqEvaluationService() {
        this.jqRootScope = createRootScope();
    }

    /**
     * Evaluates a {@link SourceExpression} against the input document, dispatching on its extraction kind.
     *
     * <p>A {@code jq} expression runs the jackson-jq path. A {@code jsonPointer} resolves an RFC&nbsp;6901
     * pointer: an array result is expanded to its elements (the natural multi-valued result), any other node is
     * returned as a single-element result, and a missing node yields an empty result list. Note that JSON
     * Pointer cannot iterate a nested array the way JQ's {@code []} can, so multi-valued nested-array alignment
     * (one value per child document) still requires a {@code jq} expression.
     */
    public Optional<List<JsonNode>> evaluate(SourceExpression expression, JsonNode input) {
        if (expression.hasJsonPointer()) {
            JsonNode resolved = input.at(expression.jsonPointer());
            if (resolved.isMissingNode()) {
                return Optional.of(List.of());
            }
            if (resolved.isArray()) {
                List<JsonNode> elements = new ArrayList<>();
                resolved.forEach(elements::add);
                return Optional.of(elements);
            }
            return Optional.of(List.of(resolved));
        }
        return evaluate(expression.jq(), input);
    }

    public Optional<List<JsonNode>> evaluate(String expression, JsonNode input) {
        JsonQuery query = jqCache.computeIfAbsent(expression, this::compileQuery);
        Scope scope = Scope.newChildScope(jqRootScope);
        List<JsonNode> results = new ArrayList<>();
        try {
            query.apply(scope, input, results::add);
            return Optional.of(results);
        } catch (JsonQueryException e) {
            return Optional.empty();
        }
    }

    private JsonQuery compileQuery(String expression) {
        try {
            return JsonQuery.compile(expression, Version.LATEST);
        } catch (JsonQueryException e) {
            throw new MappingException("Invalid jq expression '" + expression + "'", e);
        }
    }

    private Scope createRootScope() {
        Scope root = Scope.newEmptyScope();
        BuiltinModuleLoader moduleLoader = BuiltinModuleLoader.getInstance();
        root.setModuleLoader(moduleLoader);
        for (Map.Entry<String, Module> entry : moduleLoader.loadAllModules().entrySet()) {
            root.addImportedModule(entry.getKey(), entry.getValue());
        }
        return root;
    }
}
