package jd.nomad.index.exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.camel.http.base.HttpOperationFailedException;

/**
 * Utility helpers for classifying failures when communicating with a search engine.
 */
public final class SearchEngineExceptions {

    private static final Set<Class<? extends Throwable>> AVAILABILITY_EXCEPTIONS = Set.of(
            SearchEngineUnavailableException.class,
            IOException.class,
            UncheckedIOException.class,
            TimeoutException.class,
            SocketTimeoutException.class,
            ConnectException.class,
            NoRouteToHostException.class,
            UnknownHostException.class,
            SocketException.class);

    private SearchEngineExceptions() {
    }

    public static RuntimeException classify(String message, Throwable cause) {
        return isAvailabilityIssue(cause)
                ? new SearchEngineUnavailableException(message, cause)
                : new SearchEngineRequestException(message, cause);
    }

    private static boolean isAvailabilityIssue(Throwable cause) {
        return causeChain(cause)
                .map(SearchEngineExceptions::signalFor)
                .reduce(State.initial(), State::accumulate, State::combine)
                .isAvailability();
    }

    private static Stream<Throwable> causeChain(Throwable throwable) {
        return throwable == null ? Stream.empty() : Stream.iterate(throwable, Objects::nonNull, Throwable::getCause);
    }

    private static final Predicate<Throwable> AVAILABILITY_MATCHER = throwable -> {
        if (AVAILABILITY_EXCEPTIONS.stream().anyMatch(type -> type.isInstance(throwable))) {
            return true;
        }
        if (throwable instanceof HttpOperationFailedException httpException) {
            int status = httpException.getStatusCode();
            return status >= 500 || status == 429;
        }
        return false;
    };

    private static Signal signalFor(Throwable throwable) {
        if (throwable instanceof SearchEngineUnavailableException) {
            return Signal.UNAVAILABLE;
        }
        if (throwable instanceof SearchEngineRequestException) {
            return Signal.REQUEST;
        }
        return AVAILABILITY_MATCHER.test(throwable) ? Signal.AVAILABILITY : Signal.NONE;
    }

    private enum Signal {
        UNAVAILABLE,
        AVAILABILITY,
        REQUEST,
        NONE
    }

    private record State(boolean availability, boolean unavailable, boolean request) {

        private static State initial() {
            return new State(false, false, false);
        }

        private State accumulate(Signal signal) {
            return switch (signal) {
                case UNAVAILABLE -> new State(true, true, request);
                case AVAILABILITY -> new State(true, unavailable, request);
                case REQUEST -> new State(availability, unavailable, true);
                case NONE -> this;
            };
        }

        private State combine(State other) {
            return new State(
                    availability || other.availability, unavailable || other.unavailable, request || other.request);
        }

        private boolean isAvailability() {
            if (unavailable) {
                return true;
            }
            return !request && availability;
        }
    }
}
