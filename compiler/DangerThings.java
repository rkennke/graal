import jdk.internal.misc.Unsafe;
import java.util.*;

public class DangerThings {
    public static final Unsafe UNSAFE = Unsafe.getUnsafe();
    public static final long f1FieldOffset;
    public static volatile ArrayList<char[]> temp = new ArrayList<>();
    static {
        try {
            f1FieldOffset = UNSAFE.objectFieldOffset(TestObject.class.getDeclaredField("f1"));
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public static void main(String args[]) {
        try {
            TestObject tobj = new TestObject();
            while (true) {
                test1(tobj, new TestObject());
                test2(tobj, new TestObject());
                test3(tobj, new TestObject());
                test4(tobj, new TestObject());
                test5(tobj, new TestObject());
                test6(tobj, new TestObject());
                test7(tobj, new TestObject());
                test8(tobj, new TestObject());
                test9(tobj, new TestObject());
                test10(tobj, new TestObject());
                test11(tobj, new TestObject());
                test12(tobj, new TestObject());
                temp.add(new char[1024]);
                System.gc();
            }
        }
        catch (Exception e) {
            System.out.println(e);
        }
        //System.out.println("Done." + temp.size());
        //System.out.println(tobj);
    }

    public static void test1(TestObject t1, Object value) {
        UNSAFE.getAndSetReference(t1, f1FieldOffset, value);
    }

    public static void test2(TestObject t1, Object value) {
        UNSAFE.compareAndExchangeReference(t1, f1FieldOffset, value, value);
    }

    public static void test3(TestObject t1, Object value) {
        UNSAFE.compareAndExchangeReferenceAcquire(t1, f1FieldOffset, value, value);
    }

    public static void test4(TestObject t1, Object value) {
        UNSAFE.compareAndExchangeReferenceRelease(t1, f1FieldOffset, value, value);
    }

    public static void test5(TestObject t1, Object value) {
        UNSAFE.getReference(t1, f1FieldOffset);
    }

    public static Object test6(TestObject t1, Object value) {
        return UNSAFE.getReferenceOpaque(t1, f1FieldOffset);
    }

    public static Object test7(TestObject t1, Object value) {
        return UNSAFE.getReferenceVolatile(t1, f1FieldOffset);
    }

    public static void test8(TestObject t1, Object value) {
        UNSAFE.putReference(t1, f1FieldOffset, value);
    }

    public static void test9(TestObject t1, Object value) {
        UNSAFE.putReference(t1, f1FieldOffset, t1.f1);
    }

    public static void test10(TestObject t1, Object value) {
        t1.f1 = value;
    }

    public static void test11(TestObject t1, Object value) {
        t1.f1 = test7(t1, value);
    }

    public static Class<?> test12(TestObject t1, Object value) throws ClassNotFoundException {
        return Class.forName("java.lang.String");
    }

    static final class TestObject {
        public volatile Object f1;
    }
}
