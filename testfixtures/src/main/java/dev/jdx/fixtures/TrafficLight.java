package dev.jdx.fixtures;

/** An enum with constant bodies: each constant gets an anonymous subclass. */
@ExpectedMembers({
    "public static final dev.jdx.fixtures.TrafficLight RED",
    "public static final dev.jdx.fixtures.TrafficLight YELLOW",
    "public static final dev.jdx.fixtures.TrafficLight GREEN",
    "private final int seconds",
    "private static final dev.jdx.fixtures.TrafficLight[] $VALUES",
    "public static dev.jdx.fixtures.TrafficLight[] values()",
    "public static dev.jdx.fixtures.TrafficLight valueOf(java.lang.String)",
    "private dev.jdx.fixtures.TrafficLight(int)",
    "public int seconds()",
    "public abstract boolean isStop()",
    "private static dev.jdx.fixtures.TrafficLight[] $values()",
})
public enum TrafficLight {
    RED(30) {
        @Override
        public boolean isStop() {
            return true;
        }
    },
    YELLOW(5) {
        @Override
        public boolean isStop() {
            return false;
        }
    },
    GREEN(30) {
        @Override
        public boolean isStop() {
            return false;
        }
    };

    private final int seconds;

    TrafficLight(int seconds) {
        this.seconds = seconds;
    }

    public int seconds() {
        return seconds;
    }

    public abstract boolean isStop();
}
