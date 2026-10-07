package dev.engine_room.flywheel.backend;

public class BackendUnavailableException extends RuntimeException {
    public BackendUnavailableException(String message) {
        super(message);
    }

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
