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

public class proxyd {
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
            new ProxyRequest(client.accept()).start();
        }
        client.close();
    }
}

class ProxyRequest extends Thread {
	private Socket client, server;
	private String header, hostHeader, host;
	int port = 80;
	
	public ProxyRequest(Socket client) {
		super("ProxyRequest");
		this.client = client;
	}
	
	@Override
	public void run() {
		try {
			InputStream clientInput = client.getInputStream();
			List<Byte> request = new ArrayList<Byte>();
			byte b1;
			while((b1 = (byte) clientInput.read()) != -1 && clientInput.available() > 0) {
				request.add(b1);
			}
			if(!request.isEmpty()) {
				int headerEnd = seperateHeaderAndBody(request);			
				header = new String(listToArray(request, headerEnd, 0), "UTF-8");				
				hostHeader = getHostFromHeader();
				if(hostHeader.indexOf(":") != -1) {
					String[] hostSplit = hostHeader.split(":");
					host = hostSplit[0];
					port = Integer.parseInt(hostSplit[1]);
				} else {
					host = hostHeader;
				}
				String modifiedHeader = modifyHeader();
				
				byte[] modifiedHeaderBytes = modifiedHeader.getBytes("ASCII");
				byte[] bodyBytes = listToArray(request, headerEnd, 1);
				
				byte[] modifiedRequest = new byte[modifiedHeaderBytes.length + bodyBytes.length];
				System.arraycopy(modifiedHeaderBytes, 0, modifiedRequest, 0, modifiedHeaderBytes.length);
				System.arraycopy(bodyBytes, 0, modifiedRequest, modifiedHeaderBytes.length, bodyBytes.length);
				
				InetAddress address = DNSCache.getCachedAddress(host);
				if(address != null) {
					server = new Socket(address, port);
				} else {
					server = new Socket(host, port);
					address = server.getInetAddress();
				}				
				DNSCache.add(host, address);
				System.out.println("Connecting to: " + host);
				OutputStream serverOutput = server.getOutputStream();
				for(byte b2 : modifiedRequest) {
					serverOutput.write(b2);
					serverOutput.flush();
				}
				new ProxyResponse(client, server).start();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private String modifyHeader() {
		StringBuilder sb = new StringBuilder();
		Pattern p1 = Pattern.compile("GET http://" + host);
		Pattern p2 = Pattern.compile("POST http://" + host);
		Pattern p3 = Pattern.compile("(Connection|Proxy-Connection): keep-alive");
		Matcher m = p1.matcher(header);
		if(m.find()) {
			header = m.replaceFirst("GET ").toString();
		}
		m = p2.matcher(header);
		if(m.find()) {
			header = m.replaceFirst("POST ").toString();
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
	
	private String getHostFromHeader() throws Exception {
		Pattern p = Pattern.compile("Host:\\s(.*?)\r");
		Matcher m = p.matcher(header);
		if(m.find()) {
			return m.group(1);
		}
		throw new Exception("No host header was found");
	}
	
	static byte[] listToArray(List<Byte> requestBytes, int headerEnd, int type) {
		byte[] array;
		if(type == 0) {
			array = new byte[headerEnd + 1];
			for(int i = 0; i < headerEnd + 1; i++) {
				array[i] = requestBytes.get(i);
			}
		} else {
			array = new byte[requestBytes.size() - headerEnd];
			int index = 0;
			for(int i = headerEnd - 1; i < requestBytes.size() - 1; i++) {
				array[index] = requestBytes.get(i);
				index++;
			}
		}
		return array;
	}
	
	static int seperateHeaderAndBody(List<Byte> bytes) {
		byte[] pattern = {13, 10, 13, 10};
		int patternLocation = 0;
		int headerEnd = 0;
		boolean headerFound = false;
		int i = 0;
		while(!headerFound && i < bytes.size()) {		
			byte currentByte = bytes.get(i).byteValue();
			if(currentByte == pattern[patternLocation]) {
				if(patternLocation == 2) {
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

class ProxyResponse extends Thread {
	private Socket client;
	private Socket server;
	private final byte[] buffer = new byte[4096];
	
	public ProxyResponse(Socket client, Socket server) {
		super("ProxyResponse");
		this.client = client;
		this.server = server;
	}
	
	@Override
	public void run() {
		try {
			InputStream serverInput = server.getInputStream();
			OutputStream clientOutput = client.getOutputStream();
			int bytes;
			while((bytes = serverInput.read(buffer)) != -1) {
				clientOutput.write(buffer, 0, bytes);
				clientOutput.flush();
			}
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

class DNSCache {
	private static Map<String, CachedHost> DNSCache = new HashMap<String, CachedHost>();
	
	static void add(String host, InetAddress address) {
		if(!DNSCache.containsKey(host)) {
			DNSCache.put(host, new CachedHost(address));
		}
	}
	
	static InetAddress getCachedAddress(String host) {
		CachedHost cachedHost = DNSCache.get(host);
		if(cachedHost != null) {
			Date current = new Date();
			long diffInSec = (current.getTime() - cachedHost.getTimestamp().getTime()) / 1000;
			if(diffInSec <= 30) {
				System.out.println("Cache hit: " + host + " -> " + cachedHost.getAddress().getHostAddress());
				return cachedHost.getAddress();
			} else {
				DNSCache.remove(host);
				return null;
			}
		} else {
			return null;
		}
	}
}

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