package src.neighbor;

import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import src.config.ConfigObject;
import src.sharing.Query;

public class IncomingNeighborConnectionThread implements Runnable {
	// Thread for communicating with a connected neighbor
	// An incoming neighbor is a peer that has this host in its neighbors.txt

	private ConfigObject config;
	private Socket clientSocket;
	private Thread t;
	private String clientIP;
	private ConcurrentLinkedQueue<String> qids;
	private boolean heartbeatSent = false, heartbeatTimeout = false;

	public IncomingNeighborConnectionThread(ConfigObject c, ConcurrentLinkedQueue<String> q, Socket s) {
		config = c;
		clientSocket = s;
		clientIP = s.getRemoteSocketAddress().toString();
		qids = q;
		System.out.println("Accepted neighbor connection request from " + clientIP);

		// Only wait 60 seconds for incoming request from neighbor
		// Heartbeats are sent after 60 seconds of no queries or heartbeats
		// Close connection when timer expires
		try {
			clientSocket.setSoTimeout(config.socketTimeout);
		} catch (SocketException e) {
			System.out.println("Couldn't set socket timout");
		}
	}

	public Thread start() {
		if (t == null) {
			t = new Thread (this, "Incoming Neighbor Connection");
			t.start();
		}
		return t;
	}

	public void run() {
		try {
			PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
			BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

			while (!Thread.currentThread().isInterrupted() && !heartbeatTimeout) {
				String line = null;

				// Wait for an incoming query or heartbeat from neighbor
				try {
					line = in.readLine();
				}
				catch (SocketTimeoutException e) {
					if (!heartbeatSent) {
						// The neighbor hasn't sent anything in awhile, send a heartbeat
						out.println("H:ClientAlive?");
						System.out.println("Sent heartbeat to neighbor " + clientIP);
						heartbeatSent = true;
					}
					else {
						// The neighbor did not respond to the heartbeat after 60 seconds
						heartbeatTimeout = true;
						System.out.println("Neighbor " + clientIP + " did not respond to heartbeat for 60 seconds, closing connection");
					}
					// Jump back to top of loop
					continue;
				}
				catch (IOException e) {
					// When the socket is closed, readline will throw an IOException
					// this happens from a call to stopThead which also interrupts this thread to exit
					continue;
				}

				// If the neighbor closes the connection, readline will return null
				if (line == null) {
					System.out.println("Incoming neighbor " + clientIP + " closed socket remotely");
					break;
				}

				String[] splitLine = line.replaceAll(";", ":").split(":");

				// Handle incoming request appropriately
				switch (splitLine[0]) {
				case "Q":
					handleQuery(splitLine, out);
					break;
				case "H":
					handleHeartbeat(splitLine, out);
					break;
				}
			}

			in.close();
			out.close();

		} catch (IOException e) {
			System.out.println("Error communicating with " + clientIP);
		}


	}

	public void handleQuery(String[] splitQuery, PrintWriter out) {
		// Check validity of query
		// Q:<QID>;<filename>
		if (splitQuery.length != 3) {
			System.out.println("Malformed query recieved from " + clientIP);
			return;
		}

		// Check if query was already recieved from another neighbor
		if (qids.contains(splitQuery[1])) {
			System.out.println("Duplicate query recieved from " + clientIP + ", will not forward to neighbors");
			return;
		}

		// Otherwise, this is a new query
		// Add this QID to the list of seen QIDs
		qids.add(splitQuery[1]);
		// If the list has too many elements, remove the oldest
		if (qids.size() > 20) {
			qids.poll();
		}

		System.out.println("Recieved new query from " + clientIP);

		// Check if file is present on this host
		if (config.shared_files.contains(splitQuery[2])) {
			// Construct and send the response message
			// R:<QID>;<peer IP>:<peer port>;<filename>
			System.out.println("File queried by " + clientIP + " is on this peer, sending response with sharing server information");
			String response = "R:" + splitQuery[1] + ";" + config.host_ip + ":" + config.sharing_port + ";" + splitQuery[2];
			out.println(response);
		}
		else {
			// Create Query for each outgoing neighbor to handle forwarding requests
			System.out.println("File queried by " + clientIP + " is not on this peer, forwarding to neighbors");
			String query = "Q:" + splitQuery[1] + ";" + splitQuery[2];
			for (Neighbor n: config.neighbors) {
				n.queries.add(new Query(out, splitQuery[1], query, splitQuery[2]));
			}
			// This thread can forget about the query, all responses will be handled by the OutgoingNeighborConnectionThreads
		}
	}

	public void handleHeartbeat(String[] splitLine, PrintWriter out) {
		// Check validity of heartbeat
		// H:<clientIP>
		if (splitLine.length != 2) {
			System.out.println("Unexpected heartbeat recieved from " + clientIP);
			out.println("H:NAK");
			return;
		}

		// If heartbeat is client checking on server, immediately reply to heartbeat with an ACK
		if (splitLine[1].equals("ServerAlive?")) {
			System.out.println("Recieved heartbeat from neighbor " + clientIP);
			out.println("H:ACK");
			System.out.println("Sent heartbeat back to neighbor " + clientIP);
		}
		// If heartbeat is responding to server check, set flags appropriately
		else if (splitLine[1].equals("ACK")) {
			System.out.println("Recieved heartbeat back from neighbor " + clientIP);
			heartbeatSent = false;
		}
	}

	public void stopThread() {
		try {
        	clientSocket.close();
			System.out.println("Closed incoming neighbor connection to " + clientIP);
		} catch (IOException e) {
			System.out.println("Unable to stop Incoming Neighbor connection thread");
		}
	}
}
