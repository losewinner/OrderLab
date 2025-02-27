package runtime.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import runtime.time.TimePriorityTable;

import javax.json.JsonArray;
import javax.json.JsonObject;
import java.io.Serializable;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class PriorityGraph {
    private static final Logger LOG = LoggerFactory.getLogger(PriorityGraph.class);

    private final JsonObject json;
    protected final Map<Integer, Map<Integer, ArrayList<Integer>>> caller2callee2injections = new TreeMap<>();
    protected final Map<Integer, ArrayList<Integer>> outcome2cause = new TreeMap<>();
    private final int[] startValues;
    private final int[] starts;
    private final FeedbackKey[] starts1;
    public final int startNumber;
    public final TreeMap<Integer, Integer> w = new TreeMap<>();
    public final String specPath;

    public class FastGraph {
        public class Edge {
            public final int to;
            public final Edge next;
            public final ArrayList<Integer> v;
            public Edge(final int to, final Edge next, final ArrayList<Integer> v) {
                this.to = to;
                this.next = next;
                this.v = v;
            }
        }
        public final Edge[] firstEdge;

        void addEdge(final int x, final int y, final ArrayList<Integer> v) {
            final Edge edge = new Edge(y, firstEdge[x], v);
            firstEdge[x] = edge;
        }

        public FastGraph() {
            int max = 0;
            for (final Map.Entry<Integer, Map<Integer, ArrayList<Integer>>> entry : caller2callee2injections.entrySet()) {
                final int x = entry.getKey();
                max = Math.max(x, max);
                for (final Integer y : outcome2cause.get(x)) {
                    max = Math.max(y, max);
                }
                for (final Map.Entry<Integer, ArrayList<Integer>> entry2 : entry.getValue().entrySet()) {
                    final int y = entry2.getKey();
                    max = Math.max(y, max);
//                    addEdge(x, y, entry2.getValue());
                }
            }
            for (final Integer x : outcome2cause.keySet()) {
                max = Math.max(x, max);
                for (final Integer y : outcome2cause.get(x)) {
                    max = Math.max(y, max);
                }
            }
            firstEdge = new Edge[max + 1];
            final Map<Integer, ArrayList<Integer>> sentinal = new HashMap<>();
            final ArrayList<Integer> s2 = new ArrayList<>();
            for (final Integer x : outcome2cause.keySet()) {
                final Map<Integer, ArrayList<Integer>> m1 = caller2callee2injections.getOrDefault(x, sentinal);
                for (final Integer y : outcome2cause.get(x)) {
                    addEdge(x, y, m1.getOrDefault(y, s2));
                }
            }
        }

        public void calculatePriorities(final int start, final int initialPriority,
                                        final BiConsumer<Integer, Integer> consumer) {
            final LinkedList<Integer> queue = new LinkedList<>();
            final LinkedList<Integer> weights = new LinkedList<>();
            queue.add(start);
            weights.add(initialPriority);
            final Set<Integer> visited = new TreeSet<>(queue);
            while (!queue.isEmpty()) {
                final int node = queue.getFirst();
                final int weight = weights.getFirst() + 1;
                queue.removeFirst();
                weights.removeFirst();
                for (Edge e = firstEdge[node]; e != null; e = e.next) {
                    final int child = e.to;
                    if (!visited.contains(child)) {
                        visited.add(child);
                        queue.add(child);
                        weights.add(weight);
                    }
                    for (final Integer injectionId : e.v) {
                        consumer.accept(injectionId, weight);
                    }
                }
            }
        }
    }

    protected PriorityGraph(final String specPath, final JsonObject json, final int startNumber) {
        this.specPath = specPath;
        this.json = json;
        this.startNumber = startNumber;
        this.startValues = new int[startNumber];
        this.starts = new int[startNumber];
        this.starts1 = new FeedbackKey[startNumber];
    }

    public PriorityGraph(final JsonObject json) {
        this(null, json);
    }

    public PriorityGraph(final JsonObject json, final int startNumber) {
        this(null, json, startNumber);
    }

    public PriorityGraph(final String specPath, final JsonObject json) {
        this(specPath, json, json.getInt("start"));

        final JsonArray injectionsJson = this.json.getJsonArray("injections");
        for (int i = 0; i < injectionsJson.size(); i++) {
            final JsonObject spec = injectionsJson.getJsonObject(i);
            final int injectionId = spec.getInt("id");
            final int caller = spec.getInt("caller");
            final int callee = spec.getInt("callee");
            final Map<Integer, ArrayList<Integer>> map;
            if (caller2callee2injections.containsKey(caller)) {
                map = this.caller2callee2injections.get(caller);
            } else {
                map = new TreeMap<>();
                this.caller2callee2injections.put(caller, map);
            }
            if (!map.containsKey(callee)) {
                map.put(callee, new ArrayList<>());
            }
            map.get(callee).add(injectionId);
        }

        final JsonArray graphJson = this.json.getJsonArray("tree");
        for (int i = 0; i < graphJson.size(); i++) {
            final JsonObject spec = graphJson.getJsonObject(i);
            final int node = spec.getInt("id");
            final ArrayList<Integer> nodes = new ArrayList<>();
            this.outcome2cause.put(node, nodes);
            final JsonArray children = spec.getJsonArray("children");
            for (int j = 0; j < children.size(); j++) {
                nodes.add(children.getInt(j));
            }
        }
        this.fastGraph = new FastGraph();
    }

    public FastGraph fastGraph;

    public void setStartValue(final int i, final int v) {
        this.startValues[i] = v;
    }

    public final static class FeedbackKey implements Serializable {
        public final int start, now;
        public FeedbackKey(final int start, final int now) {
            this.now = now;
            this.start = start;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PriorityGraph.FeedbackKey)) return false;
            PriorityGraph.FeedbackKey that = (PriorityGraph.FeedbackKey) o;
            return start == that.start && now == that.now;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, now);
        }
    }

    public void calculatePriorities(final BiPredicate<Integer,Integer> terminator) {
        // the initialization must be here (not in the constructor) for the testing
        for (int i = 0; i < startNumber; i++) {
            starts1[i] = new FeedbackKey(i,i);
        }
        // O(n^2) is fine
        for (int i = 0; i < startNumber; i++) {
            for (int j = i + 1; j < startNumber; j++) {
                if (startValues[starts1[i].now] > startValues[starts1[j].now]) {
                    final FeedbackKey tmp = starts1[j];
                    starts1[j] = starts1[i];
                    starts1[i] = tmp;
                }
            }
        }
        final LinkedList<FeedbackKey> queue = new LinkedList<>();
        final LinkedList<Integer> weights = new LinkedList<>();
        final Set<Integer> visited = new TreeSet<>();
        queue.add(starts1[0]);
        weights.add(startValues[starts1[0].now]);
        for (int i = 1; i < startNumber; i++) {
            if (startValues[starts1[i].now] == startValues[starts1[0].now]) {
                queue.add(starts1[i]);
                weights.add(startValues[starts1[i].now]);
                visited.add(starts1[i].now);
            }
        }
        int index = queue.size();
        w.clear();
        while (!queue.isEmpty()) {
            final FeedbackKey node = queue.getFirst();
            final int weight = weights.getFirst() + 1;
            queue.removeFirst();
            weights.removeFirst();
            if (this.outcome2cause.containsKey(node.now)) {
                while (index < startNumber && startValues[starts1[index].now] == weight) {
                    queue.add(starts1[index]);
                    weights.add(startValues[starts1[index].now]);
                    index++;
                }
                final Map<Integer, ArrayList<Integer>> m1 = caller2callee2injections.get(node.now);
                for (final Integer child : outcome2cause.get(node.now)) {
                    if (!visited.contains(child)) {
                        visited.add(child);
                        queue.add(new FeedbackKey(node.start,child));
                        weights.add(weight);
                    }
                    if (m1 != null && m1.containsKey(child)) {
                        for (final Integer injectionId : m1.get(child)) {
                            w.putIfAbsent(injectionId, weight);
                            if (terminator.test(injectionId,node.start)) {
                                return;
                            }
                        }
                    }
                }
            }
            if (queue.isEmpty() && index < startNumber) {
                queue.add(starts1[index]);
                weights.add(startValues[starts1[index].now]);
                for (int i = index + 1; i < startNumber; i++) {
                    if (startValues[starts1[i].now] == startValues[starts1[index].now]) {
                        queue.add(starts1[i]);
                        weights.add(startValues[starts1[i].now]);
                    }
                }
                index += queue.size();
            }
        }
    }



    public void calculatePriorities(final Predicate<Integer> terminator) {
        // the initialization must be here (not in the constructor) for the testing
        for (int i = 0; i < startNumber; i++) {
            starts[i] = i;
        }
        // O(n^2) is fine
        for (int i = 0; i < startNumber; i++) {
            for (int j = i + 1; j < startNumber; j++) {
                if (startValues[starts[i]] > startValues[starts[j]]) {
                    final int tmp = starts[j];
                    starts[j] = starts[i];
                    starts[i] = tmp;
                }
            }
        }
        final LinkedList<Integer> queue = new LinkedList<>();
        final LinkedList<Integer> weights = new LinkedList<>();
        queue.add(starts[0]);
        weights.add(startValues[starts[0]]);
        for (int i = 1; i < startNumber; i++) {
            if (startValues[starts[i]] == startValues[starts[0]]) {
                queue.add(starts[i]);
                weights.add(startValues[starts[i]]);
            }
        }
        int index = queue.size();
        final Set<Integer> visited = new TreeSet<>(queue);
        w.clear();
        while (!queue.isEmpty()) {
            final int node = queue.getFirst();
            final int weight = weights.getFirst() + 1;
            queue.removeFirst();
            weights.removeFirst();
            if (this.outcome2cause.containsKey(node)) {
                while (index < startNumber && startValues[starts[index]] == weight) {
                    queue.add(starts[index]);
                    weights.add(startValues[starts[index]]);
                    index++;
                }
                final Map<Integer, ArrayList<Integer>> m1 = caller2callee2injections.get(node);
                for (final Integer child : outcome2cause.get(node)) {
                    if (!visited.contains(child)) {
                        visited.add(child);
                        queue.add(child);
                        weights.add(weight);
                    }
                    if (m1 != null && m1.containsKey(child)) {
                        for (final Integer injectionId : m1.get(child)) {
                            w.putIfAbsent(injectionId, weight);
                            if (terminator.test(injectionId)) {
                                return;
                            }
                        }
                    }
                }
            }
            if (queue.isEmpty() && index < startNumber) {
                queue.add(starts[index]);
                weights.add(startValues[starts[index]]);
                for (int i = index + 1; i < startNumber; i++) {
                    if (startValues[starts[i]] == startValues[starts[index]]) {
                        queue.add(starts[i]);
                        weights.add(startValues[starts[i]]);
                    }
                }
                index += queue.size();
            }
        }
    }


    public void calculatePriorities(final int start, final int initialPriority,
                                    final BiConsumer<Integer, Integer> consumer) {
        fastGraph.calculatePriorities(start, initialPriority, consumer);
//        final LinkedList<Integer> queue = new LinkedList<>();
//        final LinkedList<Integer> weights = new LinkedList<>();
//        queue.add(start);
//        weights.add(initialPriority);
//        final Set<Integer> visited = new TreeSet<>(queue);
//        while (!queue.isEmpty()) {
//            final int node = queue.getFirst();
//            final int weight = weights.getFirst() + 1;
//            queue.removeFirst();
//            weights.removeFirst();
//            if (this.outcome2cause.containsKey(node)) {
//                final Map<Integer, ArrayList<Integer>> m1 = caller2callee2injections.get(node);
//                for (final Integer child : outcome2cause.get(node)) {
//                    if (!visited.contains(child)) {
//                        visited.add(child);
//                        queue.add(child);
//                        weights.add(weight);
//                    }
//                    if (m1 != null && m1.containsKey(child)) {
//                        for (final Integer injectionId : m1.get(child)) {
//                            consumer.accept(injectionId, weight);
//                        }
//                    }
//                }
//            }
//        }
    }

    // Find the event leading from start to end using depth first search
    public boolean findPath(int start, int end, int depth, int limit, final Map<Integer,Integer> visitedToDepth, final Consumer<Integer> consumer) {
        if (depth == limit) {
            return false;
        }
        visitedToDepth.put(start,depth);
        if (start == end) {
            consumer.accept(end);
            return true;
        }
        if (!this.outcome2cause.containsKey(start)) {
            return false;
        }
        for (final Integer child : outcome2cause.get(start)) {
            Integer value = visitedToDepth.get(child);
            if (value == null || value > (depth + 1)) {
                if (findPath(child, end, depth + 1, limit, visitedToDepth, consumer)) {
                    consumer.accept(start);
                    return true;
                }
            }
        }
        return false;
    }
}
