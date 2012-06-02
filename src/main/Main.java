package main;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.logging.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import main.bully_util.*;

/**
 * Main class for the Bully algorithm
 *
 * @author alex952
 */
public class Main implements Runnable {

	public enum BullyMessages {

		ElectionRequest("election"), ElectionAnswer("electionAnswer"), Master("master");
		private final String msg;

		BullyMessages(String msg) {
			this.msg = msg;
		}

		public String message() {
			return this.msg;
		}

		static public BullyMessages fromMsg(String msg) {
			if (msg.equals("election")) {
				return BullyMessages.ElectionRequest;
			} else if (msg.equals("electionAnswer")) {
				return BullyMessages.ElectionAnswer;
			} else if (msg.equals("master")) {
				return BullyMessages.Master;
			} else {
				return null;
			}
		}
	}

// <editor-fold desc="Instance variables" defaultstate="collapsed">	
	private final Logger logger = LoggerFactory.getLogger(Main.class);
	private MulticastSocket ms;
	private InetAddress group;
	private String ip;
	private String master = "";
	private String newMaster = "";
	private Boolean electionCasted = false;
	private int port = 4443;
	private String groupIp = "224.0.0.1";
	private Thread waitMessagesThread = null;
	private Thread masterTask = null;
// </editor-fold>
	

// <editor-fold desc="Setters and getters" defaultstate="collapsed">
	public String getNewMaster() {
		return newMaster;
	}

	public void setNewMaster(String newMaster) {
		this.newMaster = newMaster;
	}
	
	public Boolean getElectionCasted() {
		return electionCasted;
	}

	public InetAddress getGroup() {
		return group;
	}

	public MulticastSocket getMs() {
		return ms;
	}

	public int getPort() {
		return port;
	}
// </editor-fold>

	public Main(Runnable masterTask) {
		try {
			//Find out own ip
			InetAddress ownAddress = InetAddress.getLocalHost();
			ip = ownAddress.getHostAddress();
			this.logger.info("I'm node with ip {} and i'm joining the multicast group", this.ip);

			//Join multicast group
			ms = new MulticastSocket(this.port);
			ms.setSoTimeout(5000);
			group = InetAddress.getByName(this.groupIp);
			ms.joinGroup(group);

			//Create thread of master task
			this.masterTask = new Thread(masterTask);

			Thread.sleep(2000L);
		} catch (UnknownHostException e) {
			this.logger.error("Cannot get you local ip address");
		} catch (IOException e) {
			this.logger.error("Error joining node to group");
		} catch (Exception e) {
			this.logger.error("Error pausing thread");
		}
	}

	@Override
	public void run() {
		this.logger.info("Listening now on {}:{} for election messages from the multicast group", this.groupIp, this.port);
		this.waitMessagesThread = new Thread(new MessageWaitThread(this.ms, this.ip, this, this.group, this.port));
		this.waitMessagesThread.start();	
		
		this.logger.info("Casting raise election");
		this.election();
	}

	public void election() {
		if (electionCasted) {
			return;
		}

		String msg = BullyMessages.ElectionRequest.message();
		byte[] buf = msg.getBytes();

		DatagramPacket dp = new DatagramPacket(buf, buf.length, this.group, this.port);
		try {
			ElectionWaitThread electionThread = new ElectionWaitThread(ms, ip, BullyMessages.ElectionAnswer);
			Thread th = new Thread(electionThread);
			th.start();

			this.logger.info("Sending election request message");
			this.ms.send(dp);
			this.electionCasted = true;

			this.logger.info("Waiting for answers ({} seconds, more or less)", 5);

			while (th.isAlive()) {
			}

			if (!electionThread.responseReceived) {
				this.logger.info("No answers received. Sending master message");
				this.masterMessage();
			} else {
				this.logger.info("Answers received");
				this.waitForMaster();
			}

		} catch (IOException ex) {
			this.logger.error("Error sending multicast eleciton message");
		}
	}

	private void waitForMaster() {
		try {
			this.logger.info("Waiting for master messages");

			Thread.sleep(15000L);

			if (!this.newMaster.equals(this.master)) {
				this.electionCasted = false;
                this.master = this.newMaster;
				this.masterTask.interrupt();
				this.logger.info("Master message received. The new master is {}", this.master);
			} else {
				this.logger.info("No master message received. Re-casting election");
				this.election();
			}
		} catch (InterruptedException e) {
			this.logger.error("Couldn't wait for answers due to an error", e);
		}
	}

	private void masterMessage() {
		String msg = BullyMessages.Master.message();
		byte[] buf = msg.getBytes();
		DatagramPacket dp = new DatagramPacket(buf, buf.length, this.group, this.port);

		try {
			this.logger.info("Sending master message to multicast group");
			this.ms.send(dp);

			this.logger.info("Starting task of master");
			//Start task of master	
			if (!this.masterTask.isAlive())
				this.masterTask.start();

			this.electionCasted = false;
		} catch (IOException e) {
			this.logger.error("Couldn't send master message due to an error", e);
		}
	}

	public static void main(String[] args) {
		Main m = new Main(new Runnable() {

			private Logger logger = LoggerFactory.getLogger(this.getClass());

			@Override
			public void run() {
				InetAddress addr;
				try {
					addr = InetAddress.getLocalHost();
				} catch (UnknownHostException ex) {
					this.logger.error("Stupid bug");
					return;
				}

				String ip = addr.getHostAddress();

				while (true) {
					this.logger.info("I'm the master with ip {}", ip);
					try {
						Thread.sleep(2000L);
					} catch (InterruptedException e) {
						this.logger.error("Stupid bug");
						return;
					}
				}
			}
		});
		m.run();
	}
}
