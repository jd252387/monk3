# Agent Guidelines

## Java Style

- Target Java 17 and follow the existing Quarkus project structure.
- Prefer clear, immutable models and small methods with explicit names.
- Keep business logic out of REST resource classes when it grows beyond simple request handling.

## Lombok

- Do not introduce Lombok annotations unless the project has Lombok configured in `build.gradle.kts` with both `compileOnly` and `annotationProcessor` dependencies.
- Use Lombok to remove low-value boilerplate, not to hide behavior.
- Prefer focused annotations such as `@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Builder`, `@EqualsAndHashCode`, and `@ToString`.
- Avoid `@Data` on domain or API types unless all generated methods are intentionally desired.
- Prefer constructor injection with `final` fields and `@RequiredArgsConstructor` when dependencies or required collaborators are present.
- Exclude sensitive fields from `@ToString` and avoid generated equality on mutable identifiers unless that is the intended model contract.

## Java Streams

- Use Streams for collection transformations, filtering, grouping, and short declarative pipelines.
- Prefer ordinary loops when mutation, branching, exception handling, or debugging would make a stream harder to read.
- Keep stream pipelines small and readable. Extract named helper methods instead of nesting complex lambdas.
- Avoid side effects inside `map`, `filter`, `flatMap`, and `peek`. Use `peek` only for temporary diagnostics, not application behavior.
- Prefer terminal operations that communicate intent, such as `toList`, `findFirst`, `anyMatch`, `allMatch`, `noneMatch`, `collect(groupingBy(...))`, or `collect(toMap(...))`.
- Be explicit about duplicate-key handling when using `Collectors.toMap`.
- Do not use parallel streams unless the workload is proven CPU-bound, thread-safe, and worth the added complexity.

## Optional

- Use `Optional<T>` as a return type when a value may legitimately be absent.
- Do not use `Optional` for fields, method parameters, DTO properties, or serialization contracts unless an existing framework integration requires it.
- Prefer `map`, `flatMap`, `filter`, `orElseGet`, `orElseThrow`, and `ifPresentOrElse` over manual presence checks when the result remains readable.
- Use `orElseGet` when the fallback value is expensive or has side effects.
- Avoid `Optional.get()` unless presence has already been guaranteed in the same local scope.
- Do not wrap nullable collections in `Optional`; return an empty collection instead.
- Use absence to represent "not found" or "not provided", not failure. Use exceptions or result types for errors that need diagnostics.
