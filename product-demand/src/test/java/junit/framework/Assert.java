package junit.framework;

public class Assert {
    public static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail("expected:<" + expected + "> but was:<" + actual + ">");
        }
    }

    public static void assertNotNull(Object actual) {
        if (actual == null) {
            fail("expected object to be non-null");
        }
    }

    public static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            fail("expected same instance");
        }
    }

    public static void assertTrue(boolean condition) {
        if (!condition) {
            fail("expected condition to be true");
        }
    }

    public static void fail(String message) {
        throw new AssertionFailedError(message);
    }
}
