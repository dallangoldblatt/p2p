package src.sharing;

import java.io.*;
import java.net.*;
import java.util.ArrayList;

import src.config.ConfigObject;

public class SharingServerThread implements Runnable {
	// Thread for accepting TCP connections from peers requesting a file
	// Creates a ServerSocket and forks new threads for incoming file connection requests

	private ConfigObject config;
	private ServerSocket sharingSocket;
	private Thread t;
	private ArrayList<Thread> clients;

	public SharingServerThread(ConfigObject c) {
		config = c;
		clients = new ArrayList<Thread>();
	}

	public void start() {
		if (t == null) {
			t = new Thread (this, "Sharing Server");
			t.start();
		}
	}

	public void run() {
		try {
			sharingSocket = new ServerSocket(config.sharing_port);
		} catch (IOException e) {
			System.out.println("Could not open socket for sharing files");
			return;
		}
		while (!Thread.currentThread().isInterrupted()) {
			// Continously listen for incoming file requests
			try {
				// Create separate connection thread for handling file transfer
				Thread client = new SharingConnectionThread(config, sharingSocket.accept()).start();
				// Keep list of clients to interrupt threads later when leaving
				clients.add(client);
			} catch (IOException e) {
				// Closing the socket causes an IO exception
				// Thread is now interrupted and will exit
			}
		}

		// accept() was interrupted by a call to stopServer
		// Now stop clent threads
		for(Thread c: clients) {
			c.interrupt();
		}

	}

	public void stopServer() {
		t.interrupt();
		try {
			sharingSocket.close();
			System.out.println("Stopped listening for file requests on Sharing Server");
		} catch (IOException e) {
			System.out.println("Unable to stop Sharing Server");
		}
	}
}
