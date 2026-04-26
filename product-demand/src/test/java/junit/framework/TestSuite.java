package junit.framework;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class TestSuite implements Test {
    private final List<Test> tests = new ArrayList<>();

    public TestSuite() {
    }

    public TestSuite(Class<?> testClass) {
        addTestsFromClass(testClass);
    }

    public void addTest(Test test) {
        tests.add(test);
    }

    private void addTestsFromClass(Class<?> testClass) {
        for (Method method : testClass.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() != 0 || method.getReturnType() != Void.TYPE) {
                continue;
            }
            if (!method.getName().startsWith("test")) {
                continue;
            }
            addTest(createTest(testClass, method.getName()));
        }
    }

    private Test createTest(Class<?> testClass, String methodName) {
        try {
            Constructor<?> stringConstructor = testClass.getConstructor(String.class);
            return (Test) stringConstructor.newInstance(methodName);
        } catch (Exception ignored) {
        }
        try {
            TestCase testCase = (TestCase) testClass.getDeclaredConstructor().newInstance();
            testCase.setName(methodName);
            return testCase;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create test: " + testClass.getName() + "#" + methodName, exception);
        }
    }

    @Override
    public int countTestCases() {
        int count = 0;
        for (Test test : tests) {
            count += test.countTestCases();
        }
        return count;
    }

    @Override
    public void run(TestResult result) {
        for (Test test : tests) {
            test.run(result);
        }
    }
}
