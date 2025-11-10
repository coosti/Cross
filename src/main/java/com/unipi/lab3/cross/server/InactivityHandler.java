package com.unipi.lab3.cross.server;

import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.unipi.lab3.cross.model.OrderBook;
import com.unipi.lab3.cross.model.orders.StopOrder;
import com.unipi.lab3.cross.model.user.User;
import com.unipi.lab3.cross.model.user.UserManager;

/**
 * class that manage inactive client connections
 * periodically checks for inactive clients and logs them out after a configured timeout period
 */

public class InactivityHandler implements Runnable {

    // map socket - related client handler for active clients
    private ConcurrentHashMap<Socket, ClientHandler> activeClients;

    // useful shared references
    private OrderBook orderBook;
    private UserManager userManager;

    // inactivity timeout in milliseconds
    private final long timeout;

    // scan interval in milliseconds
    private final long scanInterval;

    // flag to control the running state of the handler
    private volatile boolean running = true;

    public InactivityHandler (ConcurrentHashMap<Socket, ClientHandler> activeClients, UserManager userManager, OrderBook orderBook, long timeout, long scanInterval) {
        this.activeClients = activeClients;
        this.userManager = userManager;
        this.orderBook = orderBook;
        this.timeout = timeout;
        this.scanInterval = scanInterval;
    }

    public void run () {
        // main loop -> periodically scans active client to detect inactive ones
        while (running) {
            long now = System.currentTimeMillis();

            try {
                // iterate over all active clients
                for (ConcurrentHashMap.Entry<Socket, ClientHandler> entry : activeClients.entrySet()) {
                    Socket socket = entry.getKey();
                    ClientHandler handler = entry.getValue();

                    // if client's last activity exceeds the timeout handle the client
                    if (now - handler.getLastActivityTime() > timeout) {
                        handleTimeout(socket, handler);
                    }
                }
            }
            catch (Exception e) {
                System.err.println(e.getMessage());
            }

            try {
                // pause between scans
                Thread.sleep(scanInterval);
            }
            // thread interrupted
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("thread interrotto " + e.getMessage());
                break;
            }
        }
    }

    /**
     * handles an inactive client by logging them out and closing the connection
     * 
     * @param socket client's socket
     * @param handler client's handler reference
     */
    public synchronized void handleTimeout (Socket socket, ClientHandler handler) {
        try {
            // log out user if logged in
            if (!handler.isLoggedIn()) {
                System.out.println("not logged inactive client");
            }
            else {
                // get username
                String username = handler.getUsername();

                // check if user has pending stop orders
                if (hasStopOrders(username)) {
                    System.out.println("user " + username + " has pending stop orders, not logging out");
                    return;
                }
                else {
                    // update user status to logged out in the user manager map
                    User user = userManager.getUser(username);
                    if (user != null) {
                        user.setLogged(false);
                        
                        System.out.println("inactive user " + username);
                    }
                }
            }

            // close socket
            socket.close();
            // remove client from active clients map
            activeClients.remove(socket);
            System.out.println("closed inactive connection");
        }
        catch (Exception e) {
            System.err.println("errore chiusura socket: " + e.getMessage());
        }
    }

    /**
     * checks if a user has pending stop orders in the order book
     * 
     * @param username the username of the user to check
     * @return true if the user has pending stop orders, false otherwise
     */
    public synchronized boolean hasStopOrders (String username) {
        ConcurrentLinkedQueue<StopOrder> userStopOrders = orderBook.getUserStopOrders(username);
        
        if (userStopOrders != null && !userStopOrders.isEmpty())
            return true;
        
        return false;
    }

    /**
     * stops the inactivity handler setting the running flag to false
     */
    public void stop () {
        running = false;
    }
}