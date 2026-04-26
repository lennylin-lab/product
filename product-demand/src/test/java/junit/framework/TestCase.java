package junit.framework;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public abstract class TestCase extends Assert implements Test {
    private String name;

    public TestCase() {
    }

    public TestCase(String name) {
        this.name = name;
    }

    @Override
    public int countTestCases() {
        return 1;
    }

    @Override
    public void run(TestResult result) {
        result.startTest(this);
        try {
            runBare();
        } catch (AssertionFailedError error) {
            result.addFailure(this, error);
        } catch (Throwable throwable) {
            result.addError(this, throwable);
        } finally {
            result.endTest(this);
        }
    }

    public void runBare() throws Throwable {
        setUp();
        try {
            runTest();
        } finally {
            tearDown();
        }
    }

    protected void runTest() throws Throwable {
        if (name == null) {
            throw new IllegalStateException("test name is required");
        }
        Method method = getClass().getMethod(name);
        try {
            method.invoke(this);
        } catch (InvocationTargetException exception) {
            throw exception.getTargetException();
        }
    }

    protected void setUp() throws Exception {
    }

    protected void tearDown() throws Exception {
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
