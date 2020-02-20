package src.sharing;

import java.io.*;
import java.net.*;

import src.config.ConfigObject;

class SharingConnectionThread implements Runnable {
	// Thread for communicating with a connected peer to exchange a file
	// Created by Sharing ServerSocket

	private ConfigObject config;
	private Socket clientSocket;
	private Thread t;
	private String clientIP;

	public SharingConnectionThread(ConfigObject c, Socket s) {
		config = c;
		clientSocket = s;
		clientIP = s.getRemoteSocketAddress().toString();

		try {
			// Only wait 60 seconds for file request from connected client
			// Close connection when timer expires
			clientSocket.setSoTimeout(config.socketTimeout);
		} catch (SocketException e) {
			try {
				clientSocket.close();
			} catch (IOException e1) {
				System.out.println("Unable to stop Sharing Server connection thread");
			}
			System.out.println("No file request recieved from " + clientIP + " after 60 seconds, connection closed");
		}
	}

	public Thread start() {
		if (t == null) {
			t = new Thread (this, "Sharing Connection");
			t.start();
		}
		return t;
	}

	public void run() {
		try {
			OutputStream out = clientSocket.getOutputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

			// Read file request from connected peer
			// T:<filename>
			String req = "";
			try {
				// Wait for an incoming query or heartbeat from neighbor
				req = in.readLine();
			}
			catch (SocketTimeoutException e) {
				// The client did not send a query after 60 seconds, null value of req is handled below
			}

			if (req == null) {
				System.out.println("Client " + clientIP + " did not send a query for 60 seconds, closing connection");
			}
			// Check validity of request
			else if (req.length() <= 2 || !req.substring(0, 2).equals("T:"))
				System.out.println("Malformed file request recieved from " + clientIP);
			// Check if requested file exists in shared folder
			else if (config.shared_files.contains(req.substring(2))) {
				if (sendFile(req.substring(2), out)) {
					System.out.println("Completed file transfer to " + clientIP);
				}
				else {
					System.out.println("Error sending file to " + clientIP);
				}
			}
			else {
				System.out.println("File requested by " + clientIP + " does not exist on this server");
			}

			in.close();
	        out.close();

		} catch (IOException e) {
			System.out.println("Error reading/writing to InputSteam/OutputStream of " + clientIP);
		}

        try {
        	clientSocket.close();
			System.out.println("Closed file transfer connection to " + clientIP);
		} catch (IOException e) {
			System.out.println("Unable to stop Sharing connection thread");
		}
	}

	public boolean sendFile(String filename, OutputStream out) {
		// Read file
		File file = new File(config.shared_dir + filename);
		InputStream in;
		try {
			in = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			System.out.println("File '" + filename + "' was not found on this peer");
			return false;
		}

		// Write file to output stream 8192 bytes at a time
		System.out.println("File request for '" + filename + "' recieved from " + clientIP);

		byte[] bytes = new byte[8192];
		int count;
		try {
			while ((count = in.read(bytes)) > 0) {
				out.write(bytes, 0, count);
	        }
	        in.close();
		} catch (IOException e) {
			System.out.println("Error writing file '" + filename + "' to " + clientIP);
			return false;
		}

		// File transfer completed
	    return true;
	}
}
