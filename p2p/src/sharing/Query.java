package src.sharing;

import java.io.PrintWriter;

public class Query {
	// Utility class for storing information about a query and its source

	public PrintWriter querySource;
	public String qid, query, filename;

	public Query(PrintWriter qs, String id, String q, String f) {
		querySource = qs;
		qid = id;
		query = q;
		filename = f;
	}
}
