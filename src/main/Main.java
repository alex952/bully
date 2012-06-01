package main;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.logging.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
					String msg = (new String(buf)).trim();
					BullyMessages msgBully = BullyMessages.fromMsg(msg);

					if (msgBully == BullyMessages.ElectionAnswer) {
						this.logger.info("Node {} has answered to the election", client.getInetAddress().getHostAddress());
						this.instance.responseReceived = true;
					}
				} catch (IOException ex) {
					this.logger.error("Error with communication with the client {}", client.getInetAddress().getHostAddress());
				}
			}
		}

		public ElectionWaitThread(MulticastSocket ms, String ip, BullyMessages msgExpected) {
			this.ms = ms;
			this.ip = ip;
			this.msgExpected = msgExpected;
		}

		@Override
		public void run() {
			try {
				ServerSocket ss = new ServerSocket(4444);
				ss.setSoTimeout(5000);
				while (true) {
					Socket client = null;
					try {
						client = ss.accept();
					} catch (SocketTimeoutException e) {
						break;
					} finally {
						ss.close();
					}

					Thread t = new Thread(new ClientSocketThread(client, this));
					t.start();
				}
			} catch (IOException ex) {
				java.util.logging.Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
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

					final String sourceIp = dp.getAddress().getHostAddress();
					if (this.ip.equals(sourceIp)) {
						continue;
					}

					final String msg = (new String(dp.getData())).trim();
					BullyMessages bullyMsg = BullyMessages.fromMsg(msg);

					if (bullyMsg == BullyMessages.ElectionRequest && this.ip.compareTo(sourceIp) > 0) {
						this.logger.info("Received election message from a precedent ip ({}). Answering election and casting an election from here", sourceIp);

						String resp = BullyMessages.ElectionAnswer.message();
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
                                    client.close();
                                } catch (IOException e) {
                                    this.logger.error("Error contacting with the server answering election");
                                }
                            }
                        });
                        t.run();

						//DatagramPacket respP = new DatagramPacket(respB, respB.length, this.group, this.port);
						//this.ms.send(respP);

						this.instance.election();
					} else if (bullyMsg == BullyMessages.ElectionRequest && this.ip.compareTo(sourceIp) < 0) {
						this.logger.info("Received election message from a following ip ({}), not answering", sourceIp);
					} else if (bullyMsg == BullyMessages.Master) {
						if (this.instance.electionCasted == true) {
							this.instance.newMaster = sourceIp;
						}
					}
				} catch (SocketTimeoutException ex) {
					continue;
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
	private String master = "";
    private String newMaster = "";
	private Boolean electionCasted = false;
	private int port = 4443;
	private String groupIp = "224.0.0.1";
	private Thread waitMessagesThread = null;
	private Thread masterTask = null;

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
		this.logger.info("Casting raise election");
		this.election();

		this.logger.info("Listening now on {}:{} for election messages from the multicast group", this.groupIp, this.port);
		this.waitMessagesThread = new Thread(new MessageWaitThread(this.ms, this.ip, this, this.group, this.port));
		this.waitMessagesThread.start();
	}

	private void election() {
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

			Thread.sleep(5000L);

			if (!this.newMaster.equals(this.master)) {
				this.logger.info("Master message received. The new master is {}", this.master);
				this.electionCasted = false;
                this.master = this.newMaster;
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
