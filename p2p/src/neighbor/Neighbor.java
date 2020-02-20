package src.neighbor;

import java.util.concurrent.ConcurrentLinkedQueue;

import src.sharing.Query;

public class Neighbor {
	// Utility class for storing information about a peer's neighbor

	public String ip;
	public int neighbor_port, sharing_port;
	public OutgoingNeighborConnectionThread nct;
	public Thread t;
	public ConcurrentLinkedQueue<Query> queries;

	public Neighbor() {}

}
