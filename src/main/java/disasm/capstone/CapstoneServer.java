package disasm.capstone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CapstoneServer implements DisassemblerInterface {
	private static final Logger logger = LoggerFactory.getLogger(CapstoneServer.class);


	private InputStream instream;
	private OutputStream outstream;
	private Socket socket;
	private InputStreamReader insReader;
	private BufferedReader br;
	
	@Override
	public List<String> getDisassembly(int architecture, int bitness, byte[] opcodes, String host, int port) throws Exception {
		List<String> ret = new ArrayList<>();
		String disassemblyLine = new String();
		try {
			outstream.write(opcodes);
			
        	disassemblyLine = br.readLine();
			//System.out.println(Hex.encodeHexString(opcodes) + "\t\t" + disassemblyLine);
        	
        	if(disassemblyLine == null || disassemblyLine.isEmpty()) {
        		throw new ExceptionInInitializerError("disassembler returned null");
        	}
        	
			if(disassemblyLine.contains("INVALID")) {
				ret.add("");
				ret.add("");
			} else {
	        	String[] disasm = disassemblyLine.split("\t");
        		if(disasm.length > 2) {
        			ret.add(disasm[1]);
        			ret.add(disasm[2]);
        		} else if(disasm.length <=2) {
        			ret.add(disasm[1]);
        			ret.add("");
        		} else {
        			logger.error("this should never be reached");
        		}
	        }
		} catch (IOException e) {
			logger.error("IO Exception - " + e.getMessage());
			closeHandle();
			createHandle(host, port);
		}
		return ret;
	}

	@Override
	public void createHandle(String ip, int port) throws UnknownHostException, IOException {
		handleTCPRequest(ip, port);
		insReader = new InputStreamReader(instream);
		br = new BufferedReader(insReader);
	}

	@Override
	public void closeHandle() throws IOException {
		br.close();
		insReader.close();
		instream.close();
		outstream.close();
		socket.close();
	}

	private void handleTCPRequest(String ip, int port) throws UnknownHostException, IOException {
		socket = new Socket(InetAddress.getByName(ip), port);
		socket.setSoTimeout(1000);
		socket.setKeepAlive(true);

		instream = socket.getInputStream();
		outstream = socket.getOutputStream();
	}
}
