package com.reddis.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A skip list ordered by (score, member bytes), with SPANS so that rank and rank-based access are O(log n).
 * The same structure Redis uses for sorted sets.
 *
 * Every node has 1..MAX_LEVEL levels. At each level i a node holds:
 *   forward[i] : the next node on that level (or null)
 *   span[i]    : how many level-0 positions that link jumps over
 *                (for a null link: how many nodes remain after this node, which keeps the arithmetic uniform)
 *
 * Contract: insert() must only be called for a (score, member) pair that is not already present.
 * NOT thread-safe: only use it inside DataStore.access().
 */
public final class SkipList {

    private static final int MAX_LEVEL = 32;
    private static final int PROMOTE_ONE_IN = 4; // probability 1/4 of growing one more level

    private static final class Node {
        final byte[] member;
        final double score;
        final Node[] forward;
        final int[] span;

        Node(byte[] member, double score, int levels) {
            this.member = member;
            this.score = score;
            this.forward = new Node[levels];
            this.span = new int[levels];
        }
    }

    private final Node header = new Node(null, 0, MAX_LEVEL); // sentinel before the first element
    private final Random random;
    private int level = 1;  // number of levels currently in use
    private int length = 0; // number of elements

    public SkipList() {
        this(new Random());
    }

    /** A seeded Random makes tests reproducible. */
    public SkipList(Random random) {
        this.random = random;
    }

    public int size() {
        return length;
    }

    // ------------------------------------------------------------- ordering

    /** Negative if node sorts before (score, member), 0 if equal, positive if after. */
    private static int compare(Node node, double score, byte[] member) {
        int byScore = Double.compare(node.score, score);
        return byScore != 0 ? byScore : Arrays.compareUnsigned(node.member, member);
    }

    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL && random.nextInt(PROMOTE_ONE_IN) == 0) {
            result++;
        }
        return result;
    }

    // --------------------------------------------------------------- insert

    public void insert(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL]; // update[i]: the node after which the new node goes on level i
        int[] rank = new int[MAX_LEVEL];     // rank[i]: position (1-based, header = 0) of update[i]

        // Walk from the top level down, remembering where we turned down on each level.
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && compare(x.forward[i], score, member) < 0) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }

        // Choose the new node's height. If it is taller than the list, the header links the extra levels.
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                rank[i] = 0;
                update[i] = header;
                header.span[i] = length; // a null link spans "everything after the header"
            }
            level = newLevel;
        }

        // Splice the node in on each of its levels and fix the spans around it.
        Node node = new Node(member, score, newLevel);
        for (int i = 0; i < newLevel; i++) {
            node.forward[i] = update[i].forward[i];
            update[i].forward[i] = node;

            // rank[0] - rank[i] = how many level-0 nodes lie between update[i] and the insertion point
            node.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }

        // On levels above the new node's height, links now jump over one more element.
        for (int i = newLevel; i < level; i++) {
            update[i].span[i]++;
        }
        length++;
    }

    // --------------------------------------------------------------- delete

    /** @return true if (score, member) was present and has been removed */
    public boolean delete(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && compare(x.forward[i], score, member) < 0) {
                x = x.forward[i];
            }
            update[i] = x;
        }

        Node target = x.forward[0];
        if (target == null || compare(target, score, member) != 0) {
            return false;
        }

        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == target) {
                // This level links to the target: bypass it and merge the spans (minus the removed node).
                update[i].span[i] += target.span[i] - 1;
                update[i].forward[i] = target.forward[i];
            } else {
                // This level jumps over the target: the jump is now one shorter.
                update[i].span[i]--;
            }
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--; // drop empty top levels
        }
        length--;
        return true;
    }

    // ----------------------------------------------------------------- rank

    /** @return the 0-based position of (score, member), or -1 if it is not present */
    public int rank(double score, byte[] member) {
        int traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && compare(x.forward[i], score, member) <= 0) {
                traversed += x.span[i]; // summing spans along the search path gives the rank
                x = x.forward[i];
            }
            if (x != header && compare(x, score, member) == 0) {
                return traversed - 1;
            }
        }
        return -1;
    }

    /** The node at a 0-based position, found by following spans. null if out of range. */
    private Node nodeAt(int index) {
        int target = index + 1; // spans count the header as position 0
        int traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= target) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == target) {
                return x;
            }
        }
        return null;
    }

    /**
     * The elements at 0-based positions first..last (inclusive), in ascending order. O(log n + count).
     * Out-of-range or inverted bounds give an empty list.
     */
    public List<ScoredMember> range(int first, int last) {
        if (first < 0 || first > last || first >= length) {
            return List.of();
        }
        int end = Math.min(last, length - 1);
        List<ScoredMember> result = new ArrayList<>(end - first + 1);
        Node node = nodeAt(first);                    // O(log n) jump using spans
        for (int i = first; i <= end && node != null; i++) {
            result.add(new ScoredMember(node.member, node.score));
            node = node.forward[0];                   // then walk level 0
        }
        return result;
    }

    // ------------------------------------------------------------ validation

    /**
     * Checks every structural invariant and throws IllegalStateException on the first violation.
     * Meant for tests and debugging (it is O(n * levels)).
     */
    public void validate() {
        Map<Node, Integer> position = new IdentityHashMap<>();
        position.put(header, 0);

        int count = 0;
        Node previous = null;
        for (Node n = header.forward[0]; n != null; n = n.forward[0]) {
            count++;
            position.put(n, count);
            if (previous != null && compare(previous, n.score, n.member) >= 0) {
                throw new IllegalStateException("level 0 is not strictly ascending at position " + count);
            }
            previous = n;
        }
        if (count != length) {
            throw new IllegalStateException("length is " + length + " but level 0 has " + count + " nodes");
        }

        for (int i = 0; i < level; i++) {
            for (Node n = header; n != null; n = n.forward[i]) {
                Node next = n.forward[i];
                int expected = (next == null ? length : position.get(next)) - position.get(n);
                if (n.span[i] != expected) {
                    throw new IllegalStateException("wrong span on level " + i + ": is " + n.span[i]
                            + ", should be " + expected);
                }
            }
        }
        for (int i = level; i < MAX_LEVEL; i++) {
            if (header.forward[i] != null) {
                throw new IllegalStateException("header links a level above the current level");
            }
        }
        if (level > 1 && header.forward[level - 1] == null) {
            throw new IllegalStateException("the top level is empty but still counted");
        }
    }
}