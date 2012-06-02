package main.delete;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author alex952
 */
public class MasterTaskThread implements Runnable {

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
				this.logger.error("Master task thread interrupted");
				return;
			}
		}
	}
}
