package com.unipi.lab3.cross.main;

import com.unipi.lab3.cross.client.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * main class for cross client application
 * 
 * sets up the client, connects to server, starts sender and receiver threads,
 * manages user input
 */

public class ClientMain {

    // client configuration
    private static final String configFile = "src/main/resources/client.properties";

    // client properties
    private static int tcpPort;
    private static String address;

    // client socket
    private static Socket socket;

    // input and output streams
    private static BufferedReader in;
    private static PrintWriter out;

    // scanner for user input
    private static Scanner scanner;

    // queue for user input
    public static LinkedBlockingQueue<String> userInput = new LinkedBlockingQueue<String>();

    // receiver thread
    private static Thread receiver;
    private static ClientReceiver clientReceiver;

    // sender thread
    private static Thread sender;
    private static ClientSender clientSender;

    // udp listener thread
    private static Thread listener;
    private static UdpListener udpListener;

    // flag for client state
    private static final AtomicBoolean active = new AtomicBoolean(false);

    // flags for client management
    private static final AtomicBoolean registered = new AtomicBoolean(false);
    private static final AtomicBoolean logged = new AtomicBoolean(false);

    // flag for server disconnection
    private static final AtomicBoolean serverClosed = new AtomicBoolean(false);

    // flag to avoid double shutdown
    private static final AtomicBoolean shutdown = new AtomicBoolean(false);

    private static final String BANNER =
        "\n" +
        "============================================\n" +
        "   CROSS: an exChange oRder bOokS Service\n" +
        "============================================\n";

    private static void printWelcome() {

        System.out.println(BANNER);

        System.out.println("available commands:");
        System.out.println("----------------------------------------");
        System.out.printf("%-30s %s%n", "register(username,password)", "create your account");
        System.out.printf("%-30s %s%n", "login(username,password)", "login to cross");
        System.out.printf("%-30s %s%n", "updateCredentials(username,oldPassword,newPassword)", "update your credentials");
        System.out.printf("%-30s %s%n", "logout()", "logout from cross");
        System.out.printf("%-30s %s%n", "insertLimitOrder(type,size,price)", "insert an ask or bid limit order, with size and limit price");
        System.out.printf("%-30s %s%n", "insertMarketOrder(type,size)", "insert a market order");
        System.out.printf("%-30s %s%n", "insertStopOrder(type,size,price)", "insert an ask or bid stop order, with size and stop price");
        System.out.printf("%-30s %s%n", "cancelOrder(orderID)", "cancel an order with given orderID");
        System.out.printf("%-30s %s%n", "getOrderBook()", "show the order book");
        System.out.printf("%-30s %s%n", "getPriceHistory(month,year)", "show history for given month and year");
        System.out.printf("%-30s%n", "help()");
        System.out.printf("%-30s%n", "exit()");
        System.out.println("----------------------------------------\n");
    }

    public static void main (String[] args) throws Exception {

        printWelcome();

        // shutdown hook to handle client termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // execute shutdown hook if client is active and not already shutting down
            if (active.get() && shutdown.compareAndSet(false, true)) {

                // set active to false to stop main loop
                active.set(false);;

                // set logged flag of user to false
                if (logged.get()) {
                    logged.set(false);
                }

                //System.out.println("client shutting down");
                try {
                    // call shutdown procedure
                    shutdown();
                }
                catch (Exception e) {
                    System.err.println("error during shutdown: " + e.getMessage());
                }
                
            }
        }));

        // load client properties from file
        getProperties();

        try {
            // create TCP socket to connect to server
            socket = new Socket(address, tcpPort);
            socket.setSoTimeout(1000);

            // inizialize streams for TCP communication
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // inizialize scanner for read user input from console
            scanner = new Scanner(System.in);

            // client receiver thread to handle incoming messages from server
            clientReceiver = new ClientReceiver(in, logged, registered, serverClosed, userInput);

            receiver = new Thread(clientReceiver);
            receiver.start();

            // udp listener thread to handle incoming asincronous UDP notifications from server
            udpListener = new UdpListener(0);
            listener = new Thread(udpListener);

            // client sender thread to manage commands and send messages to server
            clientSender = new ClientSender(out, userInput, active, logged, registered, udpListener, listener);

            sender = new Thread(clientSender);
            sender.start();

            // daemon thread that continuously reads user input from console and adds it to the queue
            Thread t = new Thread(() -> {
                while (true) {
                    if (scanner.hasNext()) {

                        String input = scanner.nextLine();

                        try {
                            // add user command to blocking queue for processing by client sender
                            userInput.put(input);
                        } 
                        catch (InterruptedException e) {
                            System.err.println("error adding user input to queue: " + e.getMessage());
                        }
                    }
                }
            }
            );

            t.setDaemon(true);
            t.start();

            // set client as active
            active.set(true);

            // main loop that keeps the client running until user termination or server disconnection
            while (active.get()) {
                try  {
                    if (serverClosed.get()) {
                        System.out.println("connection closed by server");
                        active.set(false);
                        
                        if (logged.get())
                            logged.set(false);

                        break;
                    } 
                }
                catch (Exception e) {
                    System.err.println("error: " + e.getMessage());
                }
            }

            // check shutdown flag to avoid multiple shutdown calls
            if (shutdown.compareAndSet(false, true))
                shutdown();

            // exit program
            System.exit(0);
        }
        catch (IOException e) {
            System.err.println("network error:" + e.getMessage());
        }
        catch (NumberFormatException e) {
            System.err.println("invalid number format:" + e.getMessage());
        }
    }

    public static void shutdown() {
        stopThreads();
        close();
    }

    public static void stopThreads() {

        System.out.println("stopping threads");

        try {
            if (clientReceiver != null) {
                clientReceiver.stop();
            }
            if (receiver != null && receiver.isAlive()) {
                receiver.interrupt();
            }
        }
        catch (Exception e) {
            System.err.println("error stopping receiver: " + e.getMessage());
        }

        try {
            /*if(scanner != null) {
                System.out.println("closing scanner");
                clientSender.stop();
                scanner.close();
            }*/

            if (sender != null && sender.isAlive()) {
                System.out.println("stopping sender");
                sender.interrupt();
            }
        }
        catch (Exception e) {
            System.err.println("error stopping sender: " + e.getMessage());
        }

        try {
            if (udpListener != null) {
                udpListener.stop();
            }
            if (listener != null && listener.isAlive()) {
                listener.interrupt();
            }
        }
        catch (Exception e) {
            System.err.println("error stopping UDP listener: " + e.getMessage());
        }
    }

    public static void close() {

        System.out.println("closing resources");

        try {
            if (socket != null && !socket.isClosed()){
                System.out.println("closing socket");
                socket.close();
            }
        }
        catch (IOException e) {
            System.err.println("error closing socket: " + e.getMessage());
        }

        try {
            if (in != null){
                System.out.println("closing input stream");
                in.close();
            }
        } 
        catch (IOException e) {
            System.err.println("error closing input stream: " + e.getMessage());
        } 
        
        try {
            if (out != null){
                System.out.println("closing output stream");
                out.close();
            }

        } 
        catch (Exception e) {
            System.err.println("error closing output stream: " + e.getMessage());
        }

        System.out.println("client closed");
    }

    public static void getProperties () throws FileNotFoundException, IOException {
        Properties props = new Properties();

        FileInputStream inputFile = new FileInputStream(configFile);
        props.load(inputFile);

        tcpPort = Integer.parseInt(props.getProperty("tcpPort"));
        address = props.getProperty("address");

        inputFile.close();
    }
}