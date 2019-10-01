import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Implementation of <a>https://en.wikipedia.org/wiki/Vector_clock</a>
 * for learning purposes not for any production use.
 *
 */
public class VectorClock {

    /**
     * all versions ordered by process id
     */
    private TreeMap<Short, Integer> versions;

    /**
     * time of the last update
     */
    private Long timestamp;

    public VectorClock() {
        this.timestamp = System.currentTimeMillis();
        this.versions = new TreeMap<>();
    }

    public void increment(int processId, long time) {
        if (processId < 0 || processId > Short.MAX_VALUE) {
            throw new RuntimeException("failed to increment process vector clock");
        }

        this.timestamp = System.currentTimeMillis();

        Integer version = versions.get((short) processId);
        if (version == null) {
            version = 1;
        } else {
            version += 1;
        }
        versions.put((short) processId, version);
    }

    public VectorClock copy(VectorClock v, long timestamp) {
        return this.copy(v, timestamp);
    }

    /**
     * Merge two vector clock for reconciliation.
     *
     * copy over first clock values and traverse second one to check
     * the versions. Max version wins in this case.
     *
     *
     * @param v1 {@link VectorClock} first clock values
     * @param v2 {@link VectorClock} second clock values
     * @return {@link VectorClock} copy of merge.
     */
    public static VectorClock merge(VectorClock v1, VectorClock v2) {
        VectorClock copy = v1.copy(v1, v1.timestamp);
        SortedSet<Short> v2Nodes = v2.versions.navigableKeySet();

        for (Short node : v2Nodes) {
            if (copy.versions.containsKey(node)) {
                Integer version = Math.max(copy.versions.get(node), v2.versions.get(node));
                copy.versions.put(node, version);
            }
        }

        return copy;
    }

    /**
     * Compare two vector clock data and return the {@link Causality}
     *
     * @param v {@link VectorClock} of another process
     * @return {@link Causality} of the comparison
     */
    public Causality compareTo(VectorClock v) {
        SortedSet<Short> firstVCNodes = this.versions.navigableKeySet();
        SortedSet<Short> secondVCNodes = v.versions.navigableKeySet();

        boolean firstBigger = false, secondBigger = false;

        SortedSet<Short> commonNodes = new TreeSet<>(firstVCNodes);
        commonNodes.retainAll(secondVCNodes);

        if (firstVCNodes.size() > commonNodes.size()) {
            firstBigger = true;
        }

        if (secondVCNodes.size() > commonNodes.size()) {
            secondBigger = true;
        }

        for (Short node : commonNodes) {
            if (firstBigger && secondBigger) {
                break;
            }

            Integer firstVersion = this.versions.get(node);
            Integer secondVersion = v.versions.get(node);

            if (firstVersion > secondVersion) {
                firstBigger = true;
            } else if (secondVersion > firstVersion) {
                secondBigger = true;
            }
        }

        if (firstBigger && secondBigger) {
            return Causality.CONCURRENT;
        } else if (firstBigger) {
            return Causality.AFTER;
        } else if (secondBigger) {
            return Causality.BEFORE;
        } else {
            return Causality.SAME;
        }
    }

    /**
     * enum to hold the causality relation between two process.
     */
    public enum Causality {
        SAME, BEFORE, AFTER, CONCURRENT;
    }
}