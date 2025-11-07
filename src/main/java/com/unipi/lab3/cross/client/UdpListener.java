package com.unipi.lab3.cross.client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.unipi.lab3.cross.json.response.Notification;
import com.unipi.lab3.cross.model.trade.*;

/**
 * class that listens for UDP notifications about outcome of executed orders
 * continuously waits for UDP packets sent by the server and prints information to the console
*/

public class UdpListener implements Runnable {

    // UDP socket to listen for incoming notifications
    private DatagramSocket socket;

    // port number on which the listener is running
    private int port;

    // running flag to control the listener loop
    private volatile boolean running;

    // date formatter for printing timestamps
    private final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // constructor to initialize the UDP listener on a specified port
    public UdpListener(int port) throws Exception {
        if (port == 0)
            // bind to any available port
            this.socket = new DatagramSocket();
        else
            // bind to the specified port
            this.socket = new DatagramSocket(port);
        
        // set a timeout for the socket to allow periodic checks for the running flag
        this.socket.setSoTimeout(1000);
        this.port = this.socket.getLocalPort();
    }

    public int getPort() {
        return this.port;
    }

    public void run() {

        running = true;

        try {
            // main listener loop
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // buffer to hold incoming UDP packet data
                    byte [] buf = new byte[4096];

                    // datagram packet to receive incoming data in the buffer
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);

                    socket.receive(packet);

                    // convert the received data to a JSON string
                    String jsonString = new String(packet.getData(), 0, packet.getLength());

                    // build a notification object to deserialize the received JSON string
                    Notification notification = gson.fromJson(jsonString, Notification.class);

                    // print details of each trade in the notification
                    for (Trade trade : notification.getTrades()) {
                        System.out.println(trade.getOrderType() + " " + trade.getType() + " order " + trade.getOrderId() + " of " + trade.getSize() + " BTC at " + trade.getPrice() + " USD has been executed at " + formatDate(trade.getTimestamp()));
                    }
                }
                // timeout error
                catch (SocketTimeoutException e) {
                    continue;
                }
                // socket error
                catch (SocketException e) {
                    if (running) {
                        System.err.println("Socket error: " + e.getMessage());
                    }
                }
                // general I/O error
                catch (IOException e) {
                    if (running) {
                        System.err.println("UDP error: " + e.getMessage());
                    }
                }
            }
        }
        finally {
            // reset running flag when exiting the loop
            running = false;
        }
    }
    
    /**
     * stops the UDP listener by setting the running flag to false and closing the socket
     */
    public void stop() {
        running = false;
        socket.close();
    }

    /**
     * formats a timestamp (in seconds) to a date string
     * @param timestamp timestamp in seconds
     * @return formatted date string
     */
    public String formatDate (long timestamp) {
        // convert timestamp to local date time
        LocalDateTime date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime();

        return date.format(FORMAT);
    }
}
