package main.bully_util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.*;
import java.util.logging.Level;
import main.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElectionWaitThread implements Runnable {

	private class ClientSocketThread implements Runnable {

		private Logger logger = LoggerFactory.getLogger(this.getClass());
		private ElectionWaitThread instance = null;
		private Socket client = null;

		public ClientSocketThread(Socket client, ElectionWaitThread instance) {
			this.client = client;
			this.instance = instance;
		}

		@Override
		public void run() {
			try {
				BufferedInputStream bis = new BufferedInputStream(client.getInputStream());
				byte[] buf = new byte[256];

				bis.read(buf);
				if (!client.isClosed())
					client.close();
				String msg = (new String(buf)).trim();
				Main.BullyMessages msgBully = Main.BullyMessages.fromMsg(msg);

				if (msgBully == Main.BullyMessages.ElectionAnswer) {
					this.logger.info("Node {} has answered to the election", client.getInetAddress().getHostAddress());
					this.instance.responseReceived = true;
				}
			} catch (IOException ex) {
				this.logger.error("Error with communication with the client {}", client.getInetAddress().getHostAddress());
			}
		}
	}
	
	public MulticastSocket ms;
	public Boolean responseReceived = false;
	public DatagramPacket responsePacket = null;
	private Logger logger = LoggerFactory.getLogger(ElectionWaitThread.class);
	private String ip;
	
	private boolean activated;
	private ServerSocket ss;
	private int portServer;

	public boolean isActivated() {
		return activated;
	}

	public void setActivated(boolean activated) {
		if (activated = true)
			this.responseReceived = false;
		this.activated = activated;
	}

	public ElectionWaitThread(MulticastSocket ms, String ip, boolean activated) {
		
		this.ms = ms;
		this.ip = ip;
		this.activated = activated;
			
		try {	
			ss = new ServerSocket();
			ss.setReuseAddress(true);
			portServer = Integer.parseInt((ip.split("\\."))[3])+2000;
			
			ss.bind(new InetSocketAddress(InetAddress.getLocalHost(), portServer));
			ss.setSoTimeout(5000);
		} catch (IOException ex) {
			this.logger.error("I/O exception", ex);
		}
	}

	@Override
	public void run() {
		try {
			while (true) {
				if(!activated)
					continue;
				
				Socket client = null;
				this.logger.info("Accepting client responses");
				ss.setSoTimeout(5000);
				client = ss.accept();
				this.logger.info("Client {} accepted", client.getInetAddress().getHostAddress());
				Thread t = new Thread(new ClientSocketThread(client, this));
				t.start();
			}
		} catch (SocketTimeoutException e) {
		} catch (IOException ex) {
			this.logger.error("I/O problem", ex);
		} finally {
			if (!ss.isClosed()) {
				try {
					ss.close();
				} catch (IOException ex) {
					this.logger.error("Problem closing connection", ex);
				}
			}
		}
	}
}
