package main.bully_util;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.*;
import main.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageWaitThread implements Runnable {

	private MulticastSocket ms;
	private InetAddress group;
	private int port;
	private String ip;
	private Main instance;
	private Logger logger = LoggerFactory.getLogger(MessageWaitThread.class);
	
	private final Integer MAX_MASTER = 3;
	private int countMaster = MAX_MASTER;

	public MessageWaitThread(MulticastSocket ms, String ip, Main instance, InetAddress group, int port) {
		this.ms = ms;
		this.ip = ip;
		this.instance = instance;
		this.group = group;
		this.port = port;
	}

	@Override
	public void run() {
		while (true) {
			byte[] buf = new byte[256];
			DatagramPacket dp = new DatagramPacket(buf, buf.length);
			try {
				this.ms.receive(dp);

				final String sourceIp = dp.getAddress().getHostAddress();
				if (this.ip.equals(sourceIp)) {
					continue;
				}

				final String msg = (new String(dp.getData())).trim();
				Main.BullyMessages bullyMsg = Main.BullyMessages.fromMsg(msg);

				if (bullyMsg == Main.BullyMessages.ElectionRequest && this.ip.compareTo(sourceIp) > 0) {
					this.logger.info("Received election message from a precedent ip ({}). Answering election and casting an election from here", sourceIp);

					String resp = Main.BullyMessages.ElectionAnswer.message();
					final byte[] respB = resp.getBytes();

					Thread t = new Thread(new Runnable() {

						Logger logger = LoggerFactory.getLogger(this.getClass());

						@Override
						public void run() {
							try {
								Socket client = new Socket(sourceIp, 4444);
								BufferedOutputStream bos = new BufferedOutputStream(client.getOutputStream());

								bos.write(respB);
								bos.close();
								//client.close();
							} catch (IOException e) {
								this.logger.error("Error contacting with the server answering election", e);
							}
						}
					});
					t.run();

					//DatagramPacket respP = new DatagramPacket(respB, respB.length, this.group, this.port);
					//this.ms.send(respP);

					this.instance.election();
				} else if (bullyMsg == Main.BullyMessages.ElectionRequest && this.ip.compareTo(sourceIp) < 0) {
					this.logger.info("Received election message from a following ip ({}), not answering", sourceIp);
				} else if (bullyMsg == Main.BullyMessages.Master) {
					this.instance.setNewMaster(sourceIp);
					this.logger.info("Master message received from {}", sourceIp);
					if (!this.instance.getElectionCasted()) {
						this.instance.masterReceived();
					}
				} else if (bullyMsg == Main.BullyMessages.MasterAlive) {
					this.countMaster = MAX_MASTER;
				}
			} catch (SocketTimeoutException ex) {
				if (!this.instance.getMaster().equals(this.ip)) {
					if (--this.countMaster ==  0) {
						this.instance.election();
					}
				}
				
				continue;
			} catch (IOException ex) {
				this.logger.error("Listener thread stopped due to an error receiving messages", ex);
			}
		}
	}
}
