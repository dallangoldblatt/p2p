package src.neighbor;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import src.config.ConfigObject;

public class NeighborServerThread implements Runnable {
	// Thread for accepting TCP connection requests from incoming neighbors

	private ConfigObject config;
	private ServerSocket neighborSocket;
	private Thread t;
	private ArrayList<IncomingNeighborConnectionThread> clients;
	private ArrayList<Thread> clientThreads;
	private ConcurrentLinkedQueue<String> qids;

	public NeighborServerThread(ConfigObject c, ConcurrentLinkedQueue<String> q) {
		config = c;
		qids = q;
		clients = new ArrayList<IncomingNeighborConnectionThread>();
		clientThreads = new ArrayList<Thread>();
	}

	public void start() {
		if (t == null) {
			t = new Thread (this, "Neighbor Server");
			t.start();
		}
   }

	public void run() {
		try {
			neighborSocket = new ServerSocket(config.neighbor_port);
		} catch (IOException e) {
			System.out.println("Could not open socket for incoming neighbor connections");
			return;
		}
		while (!Thread.currentThread().isInterrupted()) {
			try {
				// Create separate connection thread for handling incoming neighbor
				IncomingNeighborConnectionThread client = new IncomingNeighborConnectionThread(config, qids, neighborSocket.accept());
				Thread clientThread = client.start();
				// Keep list of clients to interrupt threads later when leaving
				clients.add(client);
				clientThreads.add(clientThread);
			} catch (IOException e) {
				// Closing the socket causes an IO exception
				// Thread is now interrupted and will exit
			}
		}

		// Interrupt threads to exit their main loop
		// Call stopThread to close the socket and stop any blocked readLine calls
		for (Thread t: clientThreads) {
			t.interrupt();
		}
		for (IncomingNeighborConnectionThread c: clients) {
			c.stopThread();
		}

	}

	public void stopServer() {
		t.interrupt();
		try {
			neighborSocket.close();
			System.out.println("Stopped listening for incoming neighbor connection requests on Neighbor Server");
		} catch (IOException e) {
			System.out.println("Unable to stop Neighbor Server");
		}
	}
}
