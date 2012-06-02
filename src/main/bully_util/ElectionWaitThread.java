package main.bully_util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.*;
import main.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElectionWaitThread implements Runnable {

	public MulticastSocket ms;
	public Boolean responseReceived = false;
	public DatagramPacket responsePacket = null;
	private Logger logger = LoggerFactory.getLogger(ElectionWaitThread.class);
	private String ip;
	private Main.BullyMessages msgExpected;

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

	public ElectionWaitThread(MulticastSocket ms, String ip, Main.BullyMessages msgExpected) {
		this.ms = ms;
		this.ip = ip;
		this.msgExpected = msgExpected;
	}

	@Override
	public void run() {
		ServerSocket ss = null;
		try {
			ss = new ServerSocket();
			ss.setReuseAddress(true);
			ss.bind(new InetSocketAddress(InetAddress.getLocalHost(), 4444));
			ss.setSoTimeout(5000);
			while (true) {
				Socket client = null;
				this.logger.info("Accepting client responses");
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
