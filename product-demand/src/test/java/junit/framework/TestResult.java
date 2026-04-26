package junit.framework;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestResult {
    private final List<TestFailure> failures = new ArrayList<>();
    private final List<TestFailure> errors = new ArrayList<>();
    private final List<TestListener> listeners = new ArrayList<>();

    public synchronized void addError(Test test, Throwable throwable) {
        errors.add(new TestFailure(test, throwable));
        for (TestListener listener : listeners) {
            listener.addError(test, throwable);
        }
    }

    public synchronized void addFailure(Test test, AssertionFailedError error) {
        failures.add(new TestFailure(test, error));
        for (TestListener listener : listeners) {
            listener.addFailure(test, error);
        }
    }

    public synchronized void addListener(TestListener listener) {
        listeners.add(listener);
    }

    public synchronized void endTest(Test test) {
        for (TestListener listener : listeners) {
            listener.endTest(test);
        }
    }

    public synchronized int errorCount() {
        return errors.size();
    }

    public synchronized int failureCount() {
        return failures.size();
    }

    public synchronized List<TestFailure> errors() {
        return Collections.unmodifiableList(errors);
    }

    public synchronized List<TestFailure> failures() {
        return Collections.unmodifiableList(failures);
    }

    public synchronized boolean shouldStop() {
        return false;
    }

    public synchronized void startTest(Test test) {
        for (TestListener listener : listeners) {
            listener.startTest(test);
        }
    }

    public synchronized boolean wasSuccessful() {
        return failures.isEmpty() && errors.isEmpty();
    }
}
