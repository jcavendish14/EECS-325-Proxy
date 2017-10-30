import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates a proxy server that can forward HTTP GET and POST requests.
 * 
 * @author jmc242
 */
public class proxyd {
	/**
	 * Main function that is run. Creates a server socket that will listen for requests from the user's browser and will try to retrieve
	 * a response for the browser.
	 * 
	 * @param args an array of strings that can accept -port <number>
	 * @throws IOException
	 */
    public static void main(String[] args) throws IOException {
        ServerSocket client = null;
        boolean listening = true;
        int port = 5002;
        try {
            String argument = args[0];
            if(argument.equals("-port")) {
            	port = Integer.parseInt(args[1]);
            }
        } catch (Exception e) {
            //ignore me
        }

        try {
        	client = new ServerSocket(port);
            System.out.println("Started on port: " + port);
        } catch (IOException e) {
            System.err.println("Could not listen on port: " + port);
            System.exit(-1);
        }

        while (listening) {
            new ProxyRequest(client.accept()).start(); // creates a new thread for each new request
        }
        client.close();
    }
}

/**
 * A thread that can handle an HTTP request from the browser.
 * 
 * @author jmc242
 */
class ProxyRequest extends Thread {
	private enum Type {
		HEADERS, BODY;
	}
	private Socket client, server;
	private String header, hostHeader, host;
	int port = 80; // default internet socket
	
	public ProxyRequest(Socket client) {
		super("ProxyRequest");
		this.client = client;
	}
	
	/**
	 * Automatically runs when the proxy request thread is created.
	 */
	@Override
	public void run() {
		try {
			InputStream clientInput = client.getInputStream(); // get the request from the browser
			List<Byte> request = new ArrayList<Byte>();
			byte b1;
			while((b1 = (byte) clientInput.read()) != -1 && clientInput.available() > 0) { // reads in the initial request
				request.add(b1);
			}
			if(!request.isEmpty()) {
				int headerEnd = seperateHeaderAndBody(request);			
				header = new String(listToArray(request, headerEnd, Type.HEADERS), "UTF-8");				
				hostHeader = getHostFromHeader();
				if(hostHeader.indexOf(":") != -1) {
					String[] hostSplit = hostHeader.split(":");
					host = hostSplit[0];
					port = Integer.parseInt(hostSplit[1]);
				} else {
					host = hostHeader;
				}
				String modifiedHeader = modifyHeaders(); // changes headers
				
				byte[] modifiedHeaderBytes = modifiedHeader.getBytes("ASCII");
				byte[] bodyBytes = listToArray(request, headerEnd, Type.BODY);
				
				byte[] modifiedRequest = new byte[modifiedHeaderBytes.length + bodyBytes.length];
				System.arraycopy(modifiedHeaderBytes, 0, modifiedRequest, 0, modifiedHeaderBytes.length);
				System.arraycopy(bodyBytes, 0, modifiedRequest, modifiedHeaderBytes.length, bodyBytes.length); // concatenates the new header and old body
				
				InetAddress address = DNSCache.getCachedAddress(host); // checks if the host has been recently cached
				if(address != null) {
					server = new Socket(address, port); // connect to the server's socket
				} else {
					server = new Socket(host, port); // connect to the server's socket
					address = server.getInetAddress();
				}				
				DNSCache.add(host, address); // adds host to the cache
				System.out.println("Connecting to: " + host);
				OutputStream serverOutput = server.getOutputStream();
				for(byte b2 : modifiedRequest) { // write to the server
					serverOutput.write(b2);
					serverOutput.flush();
				}
				new ProxyResponse(client, server).start(); // creates a new thread for the response from the server
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Modifies the headers by changing the GET or POST absolute urls to relative ones. Also tells the connection to close when done.
	 * 
	 * @return a string of the modified headers
	 */
	private String modifyHeaders() {
		StringBuilder sb = new StringBuilder();
		Pattern p1 = Pattern.compile("GET http://" + host);
		Pattern p2 = Pattern.compile("POST http://" + host);
		Pattern p3 = Pattern.compile("(Connection|Proxy-Connection): keep-alive");
		Matcher m = p1.matcher(header);
		if(m.find()) {
			header = m.replaceFirst("GET ").toString(); // convert absolute url to relative
		}
		m = p2.matcher(header);
		if(m.find()) {
			header = m.replaceFirst("POST ").toString(); // convert absolute url to relative
		}
		m = p3.matcher(header);
		if(m.find()) {
			sb.append(m.replaceFirst("Connection: close"));
		} else {
			sb.append(header);
			sb.append("Connection: close\r\n");
		}
		return sb.toString();
	}
	
	/**
	 * Returns the host header from the request headers.
	 * 
	 * @return a string of the value of the host header
	 * @throws Exception
	 */
	private String getHostFromHeader() throws Exception {
		Pattern p = Pattern.compile("Host:\\s(.*?)\r");
		Matcher m = p.matcher(header);
		if(m.find()) {
			return m.group(1); // gets the value of the capture group (the host)
		}
		throw new Exception("No host header was found"); // if this is reached, the request is impossible
	}
	
	/**
	 * A static method that converts a list of bytes to an array of bytes.
	 * 
	 * @param requestBytes a list of bytes to convert
	 * @param headerEnd an int that indexes where the headers end 
	 * @param type an enum that determines if converting the headers or the body
	 * @return
	 */
	static byte[] listToArray(List<Byte> requestBytes, int headerEnd, Type type) {
		byte[] array;
		if(type.equals(Type.HEADERS)) { // if converting the headers to an array
			array = new byte[headerEnd + 1];
			for(int i = 0; i < headerEnd + 1; i++) {
				array[i] = requestBytes.get(i);
			}
		} else { // if converthing the body to an array
			array = new byte[requestBytes.size() - headerEnd];
			int index = 0;
			for(int i = headerEnd - 1; i < requestBytes.size() - 1; i++) {
				array[index] = requestBytes.get(i);
				index++;
			}
		}
		return array;
	}
	
	/**
	 * Seperates the headers from the body in an HTTP request.
	 * 
	 * @param bytes a list of bytes that represents an HTTP request
	 * @return an int where the seperation occurs
	 */
	static int seperateHeaderAndBody(List<Byte> bytes) {
		byte[] pattern = {13, 10, 13, 10}; // the pattern that splits the headers from the body
		int patternLocation = 0;
		int headerEnd = 0;
		boolean headerFound = false;
		int i = 0;
		while(!headerFound && i < bytes.size()) {		
			byte currentByte = bytes.get(i).byteValue();
			if(currentByte == pattern[patternLocation]) {
				if(patternLocation == 2) { // if the pattern has been matched
					headerEnd = i - 1;
					headerFound = true;
				} else {
					patternLocation++;
				}
			} else {
				patternLocation = 0;
			}
			i++;
		}
		return headerEnd;
	}
}

/**
 * A thread that can handle a HTTP response and then pass it back to the client.
 * 
 * @author jmc242
 */
class ProxyResponse extends Thread {
	private Socket client;
	private Socket server;
	private final byte[] buffer = new byte[4096]; // buffer for the bytes in the response
	
	public ProxyResponse(Socket client, Socket server) {
		super("ProxyResponse");
		this.client = client;
		this.server = server;
	}
	
	/**
	 * Automatically runs when the proxy response thread is created.
	 */
	@Override
	public void run() {
		try {
			InputStream serverInput = server.getInputStream(); // get the input from the server
			OutputStream clientOutput = client.getOutputStream(); // get the output of the client
			int bytes;
			while((bytes = serverInput.read(buffer)) != -1) { // read the bytes from the server to the client
				clientOutput.write(buffer, 0, bytes);
				clientOutput.flush();
			}
			// if done, close the client and the server socket
			if(client != null) {
				client.close();
			}
			if(server != null) {
				server.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

/**
 * A 30 second DNS cache for the proxy so it can locally resolve DNS requests. 
 * 
 * @author jmc242
 */
class DNSCache {
	private static Map<String, CachedHost> DNSCache = new HashMap<String, CachedHost>();
	
	/**
	 * Add a new cached host based on the host and its IP address.
	 * 
	 * @param host a string of the host name of the server
	 * @param address an inetaddress that contains the IP address of the server
	 */
	static void add(String host, InetAddress address) {
		DNSCache.put(host, new CachedHost(address));
	}
	
	/**
	 * Gets the cached address if it exists in the cache and is not older than 30 seconds.
	 * 
	 * @param host a string of the host
	 * @return the inetaddress of the server or null
	 */
	static InetAddress getCachedAddress(String host) {
		CachedHost cachedHost = DNSCache.get(host);
		if(cachedHost != null) {
			Date current = new Date();
			long diffInSec = (current.getTime() - cachedHost.getTimestamp().getTime()) / 1000; // check how long the cached example has been cached
			if(diffInSec <= 30) {
				System.out.println("Cache hit: " + host + " -> " + cachedHost.getAddress().getHostAddress());
				return cachedHost.getAddress();
			} else {
				DNSCache.remove(host); // remove the cached result if it is too old
				return null;
			}
		} else {
			return null;
		}
	}
}

/**
 * A representation of a cached result.
 * 
 * @author jmc242
 */
class CachedHost {
	private InetAddress address;
	private Date timestamp;

	CachedHost(InetAddress address) {
		this.address = address;
		this.timestamp = new Date();
	}

	public InetAddress getAddress() {
		return address;
	}

	public Date getTimestamp() {
		return timestamp;
	}
	
	
}