package main.bully_util;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import main.Main.BullyMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MasterAliveThread implements Runnable {
    private MulticastSocket ms;
    private InetAddress group;
    private int port;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public MasterAliveThread(MulticastSocket ms, InetAddress group, int port) {
        this.ms = ms;
        this.group = group;
        this.port = port;
    }

    @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ex) {
                    this.logger.error("Master alive thread interrupted", ex);
					return;
                }

                String msg = BullyMessages.MasterAlive.message();
                byte[] buf = msg.getBytes();

                DatagramPacket dp = new DatagramPacket(buf, buf.length, group, port);
                try {
                    this.ms.send(dp);
                } catch (IOException ex) {
                    this.logger.error("Error sending master alive message", ex);
                }
            }
        }


}
