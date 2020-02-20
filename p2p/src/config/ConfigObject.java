package src.config;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import src.neighbor.Neighbor;

public class ConfigObject {
	// Utility class for storing information about this peer

	public String hostname, host_ip;
	public InetAddress host;
	public int neighbor_port, sharing_port;
	public ArrayList<String> shared_files, obtained_files;
	public ArrayList<Neighbor> neighbors;
	public ConcurrentLinkedQueue<Integer> openPorts;

	public final String shared_dir = "files/shared/";
	public final String obtained_dir = "files/obtained/";

	public final int socketTimeout = 60000;

	public ConfigObject() {}

}
