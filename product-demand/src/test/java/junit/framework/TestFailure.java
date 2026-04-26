package junit.framework;

public class TestFailure {
    private final Test failedTest;
    private final Throwable thrownException;

    public TestFailure(Test failedTest, Throwable thrownException) {
        this.failedTest = failedTest;
        this.thrownException = thrownException;
    }

    public Test failedTest() {
        return failedTest;
    }

    public Throwable thrownException() {
        return thrownException;
    }
}
