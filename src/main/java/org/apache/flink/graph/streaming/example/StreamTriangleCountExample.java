package org.apache.flink.graph.streaming.example;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.graph.Edge;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.IterativeDataStream;
import org.apache.flink.streaming.api.datastream.SplitDataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class StreamTriangleCountExample {

	public static final long MAX_ITERATION = 5000;

	// We assume that we know all vertices in advance
	public static final ArrayList<Long> vertices = new ArrayList<>();

	// TODO: purge old state?
	public static ConcurrentHashMap<Integer, SampleTriangleState> states;

	public StreamTriangleCountExample() throws Exception {

		StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
		env.setParallelism(1);

		// A random graph I generated, details:
		//   - 100 vertices
		//   - 954 edges
		//   - 884 triangles
		DataStream<Edge<Long, NullValue>> triplets = env.readTextFile("random_graph.txt")
				.flatMap(new FlatMapFunction<String, Edge<Long, NullValue>>() {
					@Override
					public void flatMap(String s, Collector<Edge<Long, NullValue>> out) throws Exception {

						// Parse lines from the text file
						String[] args = s.split(" ");
						long src = Long.parseLong(args[0]);
						long trg = Long.parseLong(args[1]);

						if (!vertices.contains(src)) {
							vertices.add(src);
						}

						if (!vertices.contains(trg)) {
							vertices.add(trg);
						}

						out.collect(new Edge<>(src, trg, NullValue.getInstance()));
					}
				});

		// Convert the triplets to <triplet, sequence number, deltaBeta> format for the iteration
		DataStream<Tuple3<Edge<Long, NullValue>, Integer, Integer>> tripletsWithValue = triplets
				.map(new MapFunction<Edge<Long, NullValue>, Tuple3<Edge<Long, NullValue>, Integer, Integer>>() {
					@Override
					public Tuple3<Edge<Long, NullValue>, Integer, Integer> map(Edge<Long, NullValue> triplet)
							throws Exception {
						return new Tuple3<>(triplet, 0, 0);
					}
				});

		// Iterate the stream
		IterativeDataStream<Tuple3<Edge<Long, NullValue>, Integer, Integer>> iteration = tripletsWithValue.iterate();

		// The step function performs the triangle sampling
		// The split function loops everything back until the sequence number reaches MAX_ITERATION
		// When deltaBeta != 0, the splitter assigns the `output` label, so these can be summed
		SplitDataStream<Tuple3<Edge<Long, NullValue>, Integer, Integer>> step = iteration
				.map(new SampleTriangleInstance())
				.split(new SampleTriangleSplitter());

		iteration.closeWith(step.select("iterate"));

		// Extract deltaBeta and sequence number
		step.select("output").map(new MapFunction<Tuple3<Edge<Long,NullValue>,Integer,Integer>,
				Tuple2<Integer, Integer>>() {
			@Override
			public Tuple2<Integer, Integer> map(Tuple3<Edge<Long, NullValue>, Integer, Integer>
					                                    result) throws Exception {
				return new Tuple2<>(result.f1, result.f2);
			}
		}).sum(1).map(new MapFunction<Tuple2<Integer,Integer>, Tuple2<Integer, Integer>>() {
			@Override
			public Tuple2<Integer, Integer> map(Tuple2<Integer, Integer> sumBeta) throws Exception {
				int e = 954;
				int v = 100;
				int estimate = (int) ((1.0 / (double) sumBeta.f0) * sumBeta.f1 * e * (v - 2));

				return new Tuple2<>(sumBeta.f0, estimate);
			}
		}).print();

		// The output format is <sequence number, triangle estimate>
		env.execute("Streaming Triangle Count (Estimate)");
	}

	private static final class SampleTriangleInstance
			extends RichMapFunction<Tuple3<Edge<Long, NullValue>, Integer, Integer>,
			Tuple3<Edge<Long, NullValue>, Integer, Integer>> {

		public SampleTriangleInstance() {
			states = new ConcurrentHashMap<>();
		}

		@Override
		public Tuple3<Edge<Long, NullValue>, Integer, Integer>
				map(Tuple3<Edge<Long, NullValue>, Integer, Integer> edgeWithValue) throws Exception {

			Edge<Long, NullValue> edge = edgeWithValue.f0;
			int sequenceNumber = edgeWithValue.f1;
			SampleTriangleState state;

			if (!states.containsKey(sequenceNumber)) {
				// System.out.println(getRuntimeContext().getIndexOfThisSubtask() + "> Creating state for " + sequenceNumber);

				state = new SampleTriangleState();
				states.put(sequenceNumber, state);
			} else {
				state = states.get(sequenceNumber);
			}

			// Flip a coin and with probability 1/i sample a candidate
			if (Coin.flip(state.i)) {

				state.srcVertex = edge.getSource();
				state.trgVertex = edge.getTarget();

				// Randomly sample the third vertex from V \ {src, trg}
				while (true) {
					state.thirdVertex = vertices.get((int) Math.floor(Math.random() * vertices.size()));

					if (state.thirdVertex != state.srcVertex && state.thirdVertex != state.trgVertex) {
						break;
					}
				}

				state.srcEdgeFound = false;
				state.trgEdgeFound = false;
			}

			// Check if any of the two remaining edges in the candidate has been found
			if ((edge.getSource() == state.srcVertex && edge.getTarget() == state.thirdVertex)
					|| (edge.getSource() == state.thirdVertex
					&& edge.getTarget() == state.srcVertex)){
				state.srcEdgeFound = true;
			}

			if ((edge.getSource() == state.trgVertex && edge.getTarget() == state.thirdVertex)
					|| (edge.getSource() == state.thirdVertex
					&& edge.getTarget() == state.trgVertex)){
				state.trgEdgeFound = true;
			}

			// Increase i
			state.i++;

			long oldBeta = state.beta;
			state.beta = (state.srcEdgeFound && state.trgEdgeFound) ? 1 : 0;

			if (state.beta < oldBeta) {
				return new Tuple3<>(edge, sequenceNumber + 1, -1);
			} else if (state.beta > oldBeta) {
				return new Tuple3<>(edge, sequenceNumber + 1, 1);
			}

			return new Tuple3<>(edge, sequenceNumber + 1, 0);
		}
	}

	private static final class SampleTriangleSplitter implements
			OutputSelector<Tuple3<Edge<Long, NullValue>, Integer, Integer>> {
		@Override
		public Iterable<String> select(Tuple3<Edge<Long, NullValue>, Integer, Integer> tripletWithValue) {

			List<String> labels = new ArrayList<>();

			// Loop back until a sequence number limit is reached
			long sequenceNumber = tripletWithValue.f1;
			if (sequenceNumber < MAX_ITERATION) {
				labels.add("iterate");
			}

			// Output all results where betaUpdate is not zero
			long betaUpdate = tripletWithValue.f2;
			if (betaUpdate != 0) {
				labels.add("output");
			}

			return labels;
		}
	}

	private static final class SampleTriangleState implements Serializable {

		public long beta;

		public long srcVertex;
		public long trgVertex;
		public long thirdVertex;

		public boolean srcEdgeFound;
		public boolean trgEdgeFound;

		public int i;

		public SampleTriangleState() {
			this.beta = 0L;

			this.thirdVertex = -1L;
			this.srcEdgeFound = false;
			this.trgEdgeFound = false;

			i = 1;
		}
	}

	private static final class Coin {
		public static boolean flip(int sides) {
			return (Math.random() * (sides) < 1);
		}
	}

	public static void main(String[] args) throws Exception {
		new StreamTriangleCountExample();
	}
}