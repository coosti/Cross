package com.unipi.lab3.cross.server;

import java.net.*;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.unipi.lab3.cross.server.UdpNotifier;
import com.unipi.lab3.cross.json.response.Notification;

/**
 * class that sends async UDP notifications to clients
 * when an order is executed
 */

public class UdpNotifier {

    // UDP socket
    private DatagramSocket socket;

    // map username - network info (ip, port)
    private ConcurrentHashMap<String, InetSocketAddress> udpClients;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public UdpNotifier (int serverPort) throws Exception {
        this.socket = new DatagramSocket(serverPort);
        this.udpClients = new ConcurrentHashMap<>();
    }

    public InetSocketAddress getClient (String username) {
        return this.udpClients.get(username);
    }

    /**
     * register a client in the map to receive udp notifications
     * 
     * @param username username of the client
     * @param address ip address of the client
     * @param port udp port of the client
     */
    public void registerClient (String username, InetAddress address, int port) {
        // add client udp info to the map with username as key
        InetSocketAddress socketAddress = new InetSocketAddress(address, port);
        this.udpClients.put(username, socketAddress);
    }

    /**
     * logout a client from the udp clients map
     * 
     * @param username username of the client to remove
     */
    public void removeClient (String username) {
        this.udpClients.remove(username);
    }

    /**
     * send a notification to a specific user via udp
     * 
     * @param username
     * @param notification
     */
    public synchronized void notifyClient (String username, Notification notification) {
        // check if this username occurs in the map
        if (!this.udpClients.containsKey(username)) 
            return;

        try {
            // get user info from map
            InetSocketAddress clientAddress = this.udpClients.get(username);

            // get address and port
            InetAddress addr = clientAddress.getAddress();
            int clientPort = clientAddress.getPort();

            // convert notification to json
            String jsonString = gson.toJson(notification);
    
            // convert json string to bytes for UDP transmission
            byte [] buf = jsonString.getBytes();

            // create UDP packet to send data
            DatagramPacket packet = new DatagramPacket(buf, buf.length, addr, clientPort);

            // send packet 
            socket.send(packet);
        }
        // send failure
        catch (IOException e) {
            System.err.println("UDP error to " + username + ": " + e.getMessage());
        }

    }

    /**
     * close the UDP socket when server shuts down
     */
    public void close() {
    if (socket != null && !socket.isClosed())
        socket.close();
    }

}
