package dev.jdx.fixtures.mr;

/**
 * Exists only under META-INF/versions/11. Higher trees omit it, so it stays
 * visible at 17 and 21: the highest applicable release at or below the
 * selection wins, and there is no newer copy.
 */
public class Since11 {
    public String added() {
        return "11";
    }
}
