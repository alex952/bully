package main;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.logging.Level;
import javax.sound.midi.Receiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
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
		
		String message() {
			return this.msg;
		}
		
		static BullyMessages fromMsg(String msg) {
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
	
	private class ElectionWaitThread implements Runnable {

		public MulticastSocket ms;
		public Boolean responseReceived = false;
		public DatagramPacket responsePacket = null;
		private Logger logger = LoggerFactory.getLogger(ElectionWaitThread.class);
		private String ip;
		private BullyMessages msgExpected;
		
		public ElectionWaitThread(MulticastSocket ms, String ip, BullyMessages msgExpected) {
			this.ms = ms;
			this.ip = ip;
			this.msgExpected = msgExpected;
		}
		
		@Override
		public void run() {
			byte[] buf = new byte[256];
			DatagramPacket dp = new DatagramPacket(buf, buf.length);
			
			try {
				while(!this.responseReceived) {
					ms.receive(dp);
					
					String sourceIp = dp.getAddress().getHostAddress();
					if (this.ip.equals(sourceIp))
						continue;
					
					String msg = new String(dp.getData());
					BullyMessages msgBully = BullyMessages.fromMsg(msg);

					if (msgBully == this.msgExpected) {
						this.responseReceived = true;
						this.responsePacket = dp;
					}
				}
			} catch (IOException ex) {
				this.logger.info("Closing socket");
			}
		}
		
	}
	
	private class MessageWaitThread implements Runnable {
		private MulticastSocket ms;
		private InetAddress group;
		private int port;
		private String ip;
		private Main instance;
		private Logger logger = LoggerFactory.getLogger(MessageWaitThread.class);
		
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
					
					String sourceIp = dp.getAddress().getHostAddress();
					if (this.ip.equals(sourceIp))
						continue;
					
					String msg = new String(dp.getData());
					BullyMessages bullyMsg = BullyMessages.fromMsg(msg);
					if (bullyMsg == BullyMessages.ElectionRequest && this.ip.compareTo(sourceIp) > 0) {
						this.logger.info("Received election message from a following ip ({}). Answering election and casting an election from here", sourceIp);
						
						String resp = BullyMessages.ElectionAnswer.message();
						byte[] respB = resp.getBytes();
						DatagramPacket respP = new DatagramPacket(respB, respB.length, this.group, this.port);
						this.ms.send(respP);
						
						this.instance.election();
					} else if (bullyMsg == BullyMessages.ElectionRequest && this.ip.compareTo(sourceIp) < 0) {
						this.logger.info("Received election message from a precedent ip ({}), not answering", sourceIp);
					}
				} catch (IOException ex) {
					this.logger.error("Listener thread stopped due to an error receiving messages", ex);
				}
			}
		}
	}
	
	private final Logger logger = LoggerFactory.getLogger(Main.class);
	private MulticastSocket ms;
	private InetAddress group;
	private String ip;
	private Boolean electionCasted = false;
	private int port = 4443;
	private String groupIp = "224.0.0.1";
	
	private Thread waitMessagesThread = null;
	private Thread masterTask = null;
	
	public Main() {
		try {
			//Find out own ip
			InetAddress ownAddress = InetAddress.getLocalHost();
			ip = ownAddress.getHostAddress();
			this.logger.info("I'm node with ip {} and i'm joining the multicast group", this.ip);
			
			//Join multicast group
			ms = new MulticastSocket(4663);
			group = InetAddress.getByName(this.groupIp);
			ms.joinGroup(group);
			
			
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
		this.logger.info("Casting raise election");
		this.election();
		
		this.logger.info("Listening now on {}:{} for election messages from the multicast group", this.groupIp, this.port);
		this.waitMessagesThread = new Thread(new MessageWaitThread(this.ms, this.ip, this, this.group, this.port));
		this.waitMessagesThread.start();
	}
	
	private void election() {
		if (electionCasted)
			return;
		
		String msg = BullyMessages.ElectionRequest.message();
		byte[] buf = msg.getBytes();
		
		DatagramPacket dp = new DatagramPacket(buf, buf.length, this.group, this.port);
		try {
			this.logger.info("Sending election request message");
			this.ms.send(dp);
			this.electionCasted = true;
			
			ElectionWaitThread electionThread = new ElectionWaitThread(ms, ip, BullyMessages.ElectionAnswer);
			Thread th = new Thread(electionThread);
			th.start();
			
			this.logger.info("Waiting for answers {} seconds", 5);
			Thread.sleep(5000L);
			
			if (th.isAlive()) {
				this.ms.close();
				th.interrupt();
				this.ms = new MulticastSocket(this.port);
				this.ms.joinGroup(this.group);
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
		} catch (InterruptedException e) {
			this.logger.error("Couldn't wait for answers due to an error", e);
		}
	}
	
	private void waitForMaster() {
		try {
			ElectionWaitThread masterWaitThread = new ElectionWaitThread(ms, ip, BullyMessages.ElectionAnswer);
			Thread th = new Thread(masterWaitThread);
			th.start();

			this.logger.info("Waiting for master messages");
			Thread.sleep(5000L);

			if (th.isAlive()) {
				this.ms.close();
				th.interrupt();
				this.ms = new MulticastSocket(port);
				this.ms.joinGroup(this.group);
			}
			
			if (!masterWaitThread.responseReceived) {
				this.logger.info("No master message received. Re-casting election");
				this.election();
			} else {
				this.logger.info("Master message received. The new master is {}", new String(masterWaitThread.responsePacket.getAddress().getAddress()));
				this.electionCasted = false;
				this.masterTask.interrupt();
				this.masterTask = null;
			}
		} catch (InterruptedException e) {
			this.logger.error("Couldn't wait for answers due to an error", e);
		} catch (IOException e) {
			this.logger.error("Error closing the socket");
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
			this.masterTask = new Thread(new Runnable() {
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
					
					while(true) {
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
			
			this.masterTask.start();
			this.electionCasted = false;
		} catch (IOException e) {
			this.logger.error("Couldn't send master message due to an error", e);
		}
	}
	
	public static void main(String[] args) {
		Main m = new Main();
		m.run();
	}
}
