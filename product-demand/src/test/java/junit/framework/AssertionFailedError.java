package junit.framework;

public class AssertionFailedError extends AssertionError {
    public AssertionFailedError() {
    }

    public AssertionFailedError(String message) {
        super(message);
    }
}
