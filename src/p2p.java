package src;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import src.config.ConfigObject;
import src.neighbor.Neighbor;
import src.neighbor.NeighborServerThread;
import src.neighbor.OutgoingNeighborConnectionThread;
import src.sharing.Query;
import src.sharing.SharingServerThread;

public class p2p {

	public static ConfigObject config;
	public static SharingServerThread sharingServerThread;
	public static NeighborServerThread neighborServerThread;
	public static ConcurrentLinkedQueue<String> qids;
	public static boolean left;

	public static void main(String[] args) {
		// Semaphore for protecting the new file being transferred
		Semaphore download = new Semaphore(1);

		// Read configuration files and set values in config object
		if (!initializeConfig()) {
			System.out.println("Please fix configuation issue(s) and try again");
			return;
		}

		// Start list of qids that will be shared across threads to prevent broadcast storms
		qids = new ConcurrentLinkedQueue<String>();

		// Open two sockets, one for handling incoming neighbor connections, one for serving file requests
		sharingServerThread = new SharingServerThread(config);
		sharingServerThread.start();
		neighborServerThread = new NeighborServerThread(config, qids);
		neighborServerThread.start();
		System.out.println("Peer started. Listening for connections on ports " + config.neighbor_port + " and " + config.sharing_port);

		// Accept user input
		Scanner scan = new Scanner(System.in);
		String command = "";
		while (!command.toLowerCase().equals("exit")) {
			command = scan.nextLine();
			String[] splitCommand = command.split(" ");

			// The first segment in the operation
			switch (splitCommand[0].toLowerCase()) {
			case "get":
				// Extract and validate the filename from the command
				if (splitCommand.length <= 1 || splitCommand[1].equals("")) {
					System.out.println("Invalid command format for 'get'");
					break;
				}

				// Calculate a unique query ID using the IP of this host and the last 6 digits of the current time in milliseconds (over 15 minutes)
				String ipShort = config.host_ip.replaceAll("\\.", "");
				String currTime = Long.toString(System.currentTimeMillis());
				String qid = ipShort + currTime.substring(currTime.length()-6);

				// Add qid to the list of qids in case this query is forwarded back in a loop
				qids.add(qid);

				// Add the query to the command lists for each outgoing neighbor connection
				// The "out" param of the query is null to indicate this peer is the original source of the query
				String query = "Q:" + qid + ";" + splitCommand[1];
				for (Neighbor n: config.neighbors) {
					n.queries.add(new Query(null, qid, query, splitCommand[1]));
				}
				// The query has been sent and all status updates will come from the neighbor connection threads
				// Resume taking commands from user
				break;
			case "leave":
				leave();
				left = true;
				break;
			case "connect":
				left = false;
				for (Neighbor n: config.neighbors) {
					// If the neighbor connection thread does not exist or has terminated
					if (n.t == null || !n.t.isAlive()) {
						// Create a new connection
						System.out.println("Attempting to create neighbor connection with " + n.ip);
						int nextPort = config.openPorts.poll();
						n.nct = new OutgoingNeighborConnectionThread(config, n, download, nextPort);
						Thread nt = n.nct.start();
						n.t = nt;
					}
				}
				break;
			case "exit":
				// If "leave" was already given as a command, don't try to close connections again
				if (!left) {
					leave();
				}
				// Close the scanner, since command = "exit" the main loop will end, stopping the peer
				scan.close();
				break;
			default:
				System.out.println("Unrecognized command");
			}

		}

	}

	// Stop all threads hosting TCP connections
	private static void leave() {
		// Stop outgoing neighbor connection threads
		for (Neighbor n: config.neighbors) {
			// If the neighbor connection thread exists and is alive
			if (n.t != null) {
				// Interrupt threads to exit their main loop
				// Call stopThread to close the socket and stop any blocked readLine calls
				n.t.interrupt();
				n.nct.stopThread();
				// Set n.t to null so "connect" can be called again in the future
				n.t = null;
			}
		}
		// Stop sharing and neighbor servers
		sharingServerThread.stopServer();
		neighborServerThread.stopServer();
	}

	private static boolean initializeConfig() {

		// Declare buffered reader for config file reading
		BufferedReader br;
		String line;

		config = new ConfigObject();

		/** Get the local ports this peer will be using for sharing and neighbor servers
		 * config_peer.txt has the format:
		 *
		 *  neighbor connection port
		 *  file sharing port
		 */
		try {
			br = new BufferedReader(new FileReader("config/config_peer.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Could not find 'config_peer.txt'");
			return false;
		}
		try {
			// Lines are ports
			config.sharing_port = Integer.parseInt(br.readLine());
			config.neighbor_port = Integer.parseInt(br.readLine());
			br.close();
		} catch (IOException e) {
			System.out.println("Could not read 'config_peer.txt'");
			return false;
		}

		// Set up list of other available ports
		config.openPorts = new ConcurrentLinkedQueue<Integer>();
		for (int i = config.neighbor_port + 1; i <= config.neighbor_port + 18; i++) {
			config.openPorts.add(i);
		}

		/** Get the list of files that this peer can share with others
		 * config_sharing.txt has the format:
		 *
		 * file1
		 * file2
		 * ...
		 */
		try {
			br = new BufferedReader(new FileReader("config/config_sharing.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Could not find 'config_sharing.txt'");
			return false;
		}
		try {
			ArrayList<String> sharing = new ArrayList<String>();
			// Lines are files
			line = br.readLine();
			while(line != null) {
				sharing.add(line);
				line = br.readLine();
			}
			br.close();
			config.shared_files = sharing;
		} catch (IOException e) {
			System.out.println("Could not read 'config_sharing.txt'");
			return false;
		}

		/** Get the IP and ports of the neighbors of this peer
		 * config_neighbors.txt has the format:
		 *
		 * neighbor 1 IP
		 *   neighbor connection port
		 *   file sharing port
		 * neighbor 2 IP
		 *   neighbor connection port
		 *   file sharing port
		 */
		try {
			br = new BufferedReader(new FileReader("config/config_neighbors.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Could not find 'config_neighbors.txt'");
			return false;
		}
		try {
			ArrayList<Neighbor> neighbor_list = new ArrayList<Neighbor>();
			// First line is the IP of the first neighbor
			line = br.readLine();
			while(line != null) {
				// Create new Neighbor Object
				Neighbor neighbor = new Neighbor();
				// Fill in IP and port data
				neighbor.ip = line;
				neighbor.sharing_port = Integer.parseInt(br.readLine().replaceAll(" ", ""));
				neighbor.neighbor_port = Integer.parseInt(br.readLine().replaceAll(" ", ""));
				neighbor_list.add(neighbor);
				// Next line is next neighbor
				line = br.readLine();
			}
			br.close();
			config.neighbors = neighbor_list;
		} catch (IOException e) {
			System.out.println("Could not read 'config_neighbors.txt'");
			return false;
		}

		// Get IP and hostname of this host
		try {
			config.hostname = InetAddress.getLocalHost().getHostName();
			config.host = InetAddress.getByName(config.hostname);
			config.host_ip = config.host.getHostAddress();
		} catch (UnknownHostException e) {
			System.out.println("Could not get IP or hostname of this peer");
			return false;
		}

		// Initialize the lists that will be used to pass queries to the neighbor connection threads
		for (Neighbor n: config.neighbors) {
			n.queries = new ConcurrentLinkedQueue<Query>();
		}

		// All configuation variables were set successfully
		return true;
	}
}
