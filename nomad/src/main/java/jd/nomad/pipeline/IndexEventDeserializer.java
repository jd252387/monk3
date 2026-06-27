package jd.nomad.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jd.nomad.model.IndexEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class IndexEventDeserializer {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public Optional<IndexEvent> deserialize(String payload) {
        try {
            IndexEvent event = objectMapper.readValue(payload, IndexEvent.class);
            validate(event);
            return Optional.of(event);
        } catch (ConstraintViolationException | JsonProcessingException ex) {
            LoggingEventBuilder logBuilder = log.atError().addKeyValue("payload", payload);

            switch (ex) {
                case ConstraintViolationException ce -> logBuilder =
                        logBuilder.addKeyValue("violations", formatViolations(ce.getConstraintViolations()));
                case JsonProcessingException jpe -> logBuilder =
                        logBuilder.addKeyValue("error", jpe.getOriginalMessage());
                default -> logBuilder = logBuilder.setCause(ex);
            }

            logBuilder.log("Failed to deserialize Kafka event");

            return Optional.empty();
        }
    }

    private void validate(IndexEvent event) {
        Set<ConstraintViolation<IndexEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private String formatViolations(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining(", "));
    }
}
