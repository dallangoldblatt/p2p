package src.neighbor;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

import src.config.ConfigObject;
import src.sharing.Query;

public class OutgoingNeighborConnectionThread implements Runnable {
	// Thread for communicating with a connected neighbor
	// An outgoing neighbor is a peer that is in this host's neighbors.txt

	private ConfigObject config;
	private Neighbor neighbor;
	private Socket neighborSocket;
	private Semaphore download;
	private Thread t;
	private String neighborIP;
	private boolean heartbeatSent = false, heartbeatTimeout = false;
	private long heartbeatTime;
	private HashMap<String, Query> qidMap;
	private int localPort;
	private boolean connected;

	public OutgoingNeighborConnectionThread(ConfigObject c, Neighbor n, Semaphore d, int port) {
		config = c;
		neighbor = n;
		neighborIP = n.ip;
		download = d;
		heartbeatTime = nextHeartbeatTime();
		qidMap = new HashMap<String, Query>();
		localPort = port;

		try {
			neighborSocket = new Socket(neighborIP, neighbor.neighbor_port, config.host, localPort);
			neighborIP = neighborSocket.getRemoteSocketAddress().toString();
			System.out.println("Successfully created neighbor connection with " + neighborIP);
		} catch (IOException e) {
			System.out.println("Unable to create neighbor connection with " + neighborIP);
			connected = false;
			return;
		}

		try {
			// Wait 60 seconds for any incoming from neighbor
			// Heartbeats are sent after 60 seconds of no contact
			// Close connection when timer expires
			neighborSocket.setSoTimeout(config.socketTimeout);
		} catch (SocketException e) {
			System.out.println("Couldn't set socket timout");
		}

		connected = true;
	}

	public Thread start() {
		if (t == null) {
			t = new Thread (this, "Outgoing Neighbor Connection");
			t.start();
		}
		return t;
	}

	public void run() {
		if (!connected) {
			// Return port to available ports
			config.openPorts.add(localPort);
			// Couldn't create connection, re-try later
			return;
		}
		try {
			PrintWriter out = new PrintWriter(neighborSocket.getOutputStream(), true);
			BufferedReader in = new BufferedReader(new InputStreamReader(neighborSocket.getInputStream()));

			while (!Thread.currentThread().isInterrupted() && !heartbeatTimeout) {
				// If heartbeat was sent, wait for reply before sending any more queries
				if (heartbeatSent) {
					String reply = "";
					try {
						reply = in.readLine();
					}
					catch (SocketTimeoutException e) {
						// The neighbor did not respond to the heartbeat in 60 seconds
						heartbeatTimeout = true;
						System.out.println("Neighbor " + neighborIP + " did not respond to heartbeat for 60 seconds, closing connection");
						continue;
					}
					catch (IOException e) {
						// When the socket is closed, readline will throw an IOException
						// this happens from a call to stopThead which also interrupts this thread to exit
						continue;
					}

					if (reply == null) {
						// The neighbor closed the socket connection
						System.out.println("Outgoing neighbor " + neighborIP + " closed socket remotely");
						break;
					}

					if (reply.equals("H:ACK")) {
						// Heartbeat was acknowledged, set variables appropriately
						System.out.println("Recieved heartbeat back from neighbor " + neighborIP);
						heartbeatSent = false;
						heartbeatTime = nextHeartbeatTime();
					}
					else {
						// Resend heartbeat since this message doesn't match the expected
						out.println("H:ServerAlive?");
						continue;
					}
				}

				// If enough time has passed, send a heartbeat to the neighbor
				if (heartbeatTime < System.currentTimeMillis()) {
					out.println("H:ServerAlive?");
					System.out.println("Sent heartbeat to neighbor " + neighborIP);
					heartbeatSent = true;
					heartbeatTime = nextHeartbeatTime();
					continue;
				}

				// Check if the neighbor is trying to send us something without blocking
				if (in.ready()) {
					// Extend heartbeatTime since neighbor has communicated recently
					heartbeatTime = nextHeartbeatTime();

					String line = in.readLine();
					String[] splitLine = line.replaceAll(";", ":").split(":");

					// Handle incoming request appropriately
					switch (splitLine[0]) {
					case "R":
						handleResponse(splitLine, line, out);
						break;
					case "H":
						handleHeartbeat(splitLine, out);
						break;
					}
				}

				// Finally, check if any queries need to be made
				if (neighbor.queries.size() > 0) {
					Query query = neighbor.queries.poll();

					System.out.println("Sending query for '" + query.filename + "' to " + neighborIP);
					out.println(query.query);
					qidMap.put(query.qid, query);
				}
			}

			in.close();
			out.close();

		} catch (IOException e) {
			System.out.println("Error communicating with " + neighborIP);
		}

		// Return port to available ports
		config.openPorts.add(localPort);

	}

	public void handleHeartbeat(String[] splitLine, PrintWriter out) {
		if (splitLine.length != 2 || !splitLine[0].equals("H")) {
			System.out.println("Unexpected heartbeat recieved from " + neighborIP);
			out.println("H:NAK");
			return;
		}

		// If heartbeat is client checking on server, immediately reply to heartbeat with acknowledgement
		if (splitLine[1].equals("ClientAlive?")) {
			System.out.println("Recieved heartbeat from neighbor " + neighborIP);
			out.println("H:ACK");
			System.out.println("Sent heartbeat back to neighbor " + neighborIP);
		}
	}

	public void handleResponse(String[] splitLine, String line, PrintWriter out) {
		// R:<QID>;<peer IP>:<peer port>;<filename>

		// If this is a response to a query we have never seen, do nothing and return
		if (!qidMap.containsKey(splitLine[1])) {
			return;
		}

		// If this is a response to a forwarded query, forward the response to the original source
		// This traverses the overlay network in reverse
		Query query = qidMap.get(splitLine[1]);
		if (query.querySource != null) {
			System.out.println("Forwarding response for '" + splitLine[4] + "' back to incoming neighbor");
			query.querySource.println(line);
			qidMap.remove(splitLine[1]);
			return;
		}

		// Else, this is a query that originated from this host
		// Connect to the server in the response to download the file
		String fileServer = splitLine[2];
		int filePort = Integer.parseInt(splitLine[3]);
		String filepath = config.obtained_dir + splitLine[4];
		File f = new File(filepath);

		try {
			download.acquire();

			// If the file already exists, it must have been handled by a different neighbor connection thread
			// If it doesn't exist, contact the server containing it to download
			if (!f.exists()) {
				try {
					// Get available port for the new socket
					int nextPort = config.openPorts.poll();
					Socket fileSocket = new Socket(fileServer, filePort, config.host, nextPort);

					f.createNewFile();

					OutputStream fileOut = new FileOutputStream(filepath);
					PrintWriter serverOut = new PrintWriter(fileSocket.getOutputStream(), true);
					InputStream in = fileSocket.getInputStream();

					// Tell the file server what file to give us
					// T:<filename>
					System.out.println("Requesting file transfer for '" + splitLine[4] + "' from " + fileServer);
					serverOut.println("T:" + splitLine[4]);

					// Write input stream from server to file 8192 bytes at a time
					byte[] bytes = new byte[8192];
					int count;
			        while ((count = in.read(bytes)) > 0) {
			            fileOut.write(bytes, 0, count);
			        }

			        System.out.println("Successfully downloaded '" + splitLine[4] + "' from " + fileServer);

			        in.close();
			        fileOut.close();
			        serverOut.close();
			        fileSocket.close();

			        // Return port to available ports
			        config.openPorts.add(nextPort);

				} catch (IOException e) {
					e.printStackTrace();
					System.out.println("Error communicating with file server");
				}
			}

			download.release();
		} catch (InterruptedException e) {
			download.release();
		}
	}

	public long nextHeartbeatTime() {
		// Calculate the time 60 sec in the future that a heartbeat should be sent if there is no activity
		return heartbeatTime = System.currentTimeMillis() + config.socketTimeout;
	}

	public void stopThread() {
		try {
        	neighborSocket.close();
			System.out.println("Closed outging neighbor connection to " + neighborIP);
		} catch (IOException e) {
			System.out.println("Unable to stop Outgoing Neighbor connection thread");
		}
	}
}
