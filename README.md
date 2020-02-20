## File Structure

/config stores the network information for this peer and its neighbors. It
    also stores a list of the files that it is currently sharing.

/files stores copies of the files that the peer can share and the files that
    the peer has recieved from other peers

/src contains the code for a peer
A description for each class that makes up a peer follows:

## Class Descriptions

p2p: This is the main class and entrypoint. It completes the set-up and starts 
    the initial server threads. It then handles all user input/commands.

SharingServerThread: This class handles listening on a welcome socket for new
    incoming file transfer connection requests. When accepting a new request, it
    forks a new thread dedicated to the communication between the two peers.

SharingConnectionThread: This class handles the transfer of a file from one peer
    to another. This connection thread only exists for the duration of the file
    transfer.

NeighborServerThread: This class handles listening on a welcome socket for new
    incoming neighbor connection requests from peers with this host in their
    config_neighbors.txt. When accepting a new request, it forks a new thread
    dedicated to the communication between the two peers.

IncomingNeighborConnetionThread: This class handles the communication between
    this host and a peer who initiated a neighbor connection with the
    NeighborServerThread. It listens for heartbeats and new queries. If the
    queried file is on this host it sends the appropriate response, and if the
    queried is not on this host it passes the query to the
    OutgoingNeighborConnetionThreads to be forwarded. It uses a shared list of
    QIDs to prevent re-forwarding duplicate queries.

OutgoingNeighborConnetionThread: This class handles the communication between
    this host and a peer listed in this host's config_neighbors.txt file. It
    listens for heartbeats and handles sending queries to its neighbor. It also
    listens for responses to queries. If the response is for a query that came
    from another neighbor, it forwards it to them. Otherwise, it connects to
    the peer in the response and downloads the requested file. Since multiple
    responses may arrive for the same file, the peer only makes a file transfer
    request if the file doesn't exist yet on this peer, and the check and
    download section is protected by a shared semaphore to prevent races.

The remaining classes have no functions but are used as objects to store useful
information:

ConfigObject: This class stores configuration data about this peer.

Neighbor: This class stores information about this peer's neighbor. A new
    instance is made for each neighbor peer.

Query: This class stores information about a query that has been forwarded or
    created by OutgoingNeighborConnetionThread. A new instance is made for each
    query.
