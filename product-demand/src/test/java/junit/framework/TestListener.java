package junit.framework;

public interface TestListener {
    default void addError(Test test, Throwable throwable) {
    }

    default void addFailure(Test test, AssertionFailedError error) {
    }

    default void endTest(Test test) {
    }

    default void startTest(Test test) {
    }
}
