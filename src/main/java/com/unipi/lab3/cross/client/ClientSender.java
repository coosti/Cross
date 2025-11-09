package com.unipi.lab3.cross.client;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.unipi.lab3.cross.json.request.HistoryValues;
import com.unipi.lab3.cross.json.request.NetworkValues;
import com.unipi.lab3.cross.json.request.OrderValues;
import com.unipi.lab3.cross.json.request.Request;
import com.unipi.lab3.cross.json.request.UserValues;
import com.unipi.lab3.cross.json.request.Values;
import com.unipi.lab3.cross.json.response.OrderResponse;

/**
 * class to send client requests to server
 * handles user input from a queue, validating and parsing requests
 * into the right operation with parameters
 * then builds a request object and sends it as json string to server
 */

public class ClientSender implements Runnable {
    
    // output stream to send requests to server
    private PrintWriter out;

    // scanner queue to read user input
    private LinkedBlockingQueue<String> scanner;

    // running flag
    private volatile boolean running = false;

    // active flag
    private final AtomicBoolean active;

    // flags for user state
    private final AtomicBoolean logged;
    private final AtomicBoolean registered;

    private String SHUTDOWN_MESSAGE = "__SHUTDOWN__";

    // reference to udp listener to start it
    private Thread listener;
    private UdpListener udpListener;

    private static final Gson gson = new GsonBuilder().create();

    public ClientSender(PrintWriter out, LinkedBlockingQueue<String> scanner, AtomicBoolean active, AtomicBoolean logged, AtomicBoolean registered, UdpListener udpListener, Thread listener) {
        this.out = out;
        this.scanner = scanner;
        
        this.active = active;
        this.logged = logged;
        this.registered = registered;
        
        this.udpListener = udpListener;
        this.listener = listener;
    }

    public void run() {
        running = true;

        try {
            // continuously read user input from scanner queue
            while (running && !Thread.currentThread().isInterrupted()) {

                try {
                    // take user input from queue
                    String userInput = scanner.take();

                    // check for input read

                    // shutdown message
                    if (userInput.equals(SHUTDOWN_MESSAGE)) {
                        //System.out.println("closing");
                        return;
                    }

                    // invalid input
                    if (userInput.isEmpty() || userInput.isBlank() || !isValid(userInput)) {
                        // command not valid
                        System.out.println("invalid command");
                        continue;
                    }

                    // parse command and parameters
                    String[] inputStrings = userInput.split("\\(", 2);
                    // split command and parameters
                    String command = inputStrings[0].trim();
                    String param = inputStrings[1].substring(0, inputStrings[1].length() - 1).trim();

                    // build request object
                    Request<?> request = null;
                    
                    switch(command) {
                        // handle help command locally, not sending it to server
                        case "help":
                            printMenu();
                        break;

                        default:
                            // parse parameters into list
                            List<String> params = new ArrayList<>();
                            if (!param.isEmpty()) {
                                String[] tokens = param.split("\\s*,\\s*");
                                params = Arrays.asList(tokens);
                            }

                            request = buildRequest(command, params);

                        break;
                    }

                    // serialize and send request to server
                    if (request != null) {
                        // convert to json message with gson builder
                        String jsonString = gson.toJson(request);

                        // send on tcp socket to server
                        out.println(jsonString);

                        // handle exit command locally
                        if (command.equals("exit")) {
                            // restore user state
                            if (logged.get()) {
                                logged.set(false);
                            }

                            running = false;
                            active.set(false);
                        }
                    }
                }
                catch (NoSuchElementException e) {
                    System.err.println("scanner closed");
                    break;
                }   
                catch (IllegalStateException e) {
                    System.out.println("invalid scanner");
                    break;
                }
                catch (Exception e) {
                    System.err.println("error: " + e.getMessage());
                    running = false;
                }
            }
        }
        catch (Exception e) {
            System.err.println("error: " + e.getMessage());
        }
    }

    /**
     * build request object based on operation and parameters
     * 
     * @param operation string that indicates the operation to perform
     * @param paramList list of parameters for the operation
     * @return request object to send to server
    */
    private Request<?> buildRequest (String operation, List<String> paramList) {
        Request<?> request = null;

        switch (operation) {
            // notify server that client is closing
            case "exit":
                request = new Request<Values>("exit", null);
            break;

            // register new user
            case "register":
                // check if already registered
                if (logged.get()) {
                    System.out.println("you are already logged in");
                    break;
                }

                // check number of parameters -> only username and password
                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters");
                    break;
                }
                
                // get username and password
                String username = paramList.get(0);
                String password = paramList.get(1);

                // create values object
                UserValues values = new UserValues(username, password);

                // create request object
                request = new Request<UserValues>("register", values);  

            break;

            // login user
            case "login":
                // check if already logged in
                if (logged.get()) {
                    System.out.println("you are already logged in");
                    break;
                }

                // check number of parameters -> only username and password
                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters, insert username and password");
                    break;
                }

                username = paramList.get(0);
                password = paramList.get(1);

                try {
                    // at login start udp listener to enable receiving udp messages from server
                    if (!listener.isAlive()) {
                        listener.start();
                    }

                    // wait for listener to start
                    int sleepTime = 500;
                    Thread.sleep(sleepTime);

                    // get udp port
                    int udpPort = udpListener.getPort();

                    // create network val object with udp port to send to server for notifications
                    NetworkValues netValues = new NetworkValues(udpPort);

                    // create user values with network info
                    values = new UserValues(username, password, netValues);

                    request = new Request<UserValues>("login", values);
                }
                catch (InterruptedException e) {
                    System.err.println("error waiting for udp listener to start" + e.getMessage());
                }
                catch (Exception e) {
                    System.err.println("error starting udp listener" + e.getMessage());
                }

            break;
            
            // update user credentials
            case "updateCredentials":
                // check if registered
                if (!registered.get()) {
                    System.out.println("you have to register first");
                    break;
                }
                // check if logged in -> cannot change credentials while logged in
                else if (logged.get()) {
                    System.out.println("you can't change credentials while logged in");
                    break;
                }

                // check number of parameters -> username, old password, new password
                if (paramList.size() != 3) {
                    System.out.println("invalid number of parameters, insert username, oldPassword and newPassword");
                    break;
                }

                username = paramList.get(0);
                String oldPassword = paramList.get(1);
                String newPassword = paramList.get(2);

                values = new UserValues(username, oldPassword, newPassword);

                request = new Request<UserValues>("updateCredentials", values);

            break;

            case "logout":
                if (!registered.get()) {
                    System.out.println("you have to sign in first");
                    break;
                }
                else if (!logged.get()) {
                    System.out.println("you are not logged in");
                    break;
                }

                if (!paramList.isEmpty()) {
                    System.out.println("invalid command");
                    break;
                }

                request = new Request<UserValues>("logout", null);
            
            break;
            
            // insert limit order
            case "insertLimitOrder":
                // check if registered and logged in
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }

                if (paramList.size() != 3) {
                    System.out.println("invalid number of parameters, insert type, size and limitPrice");
                    break;
                }

                // parse parameters from message

                String type = paramList.get(0);

                // check if type is valid
                if (!type.equals("ask") && !type.equals("bid")) {
                    System.out.println("type must be ask or bid");
                    break;
                }

                int size = 0;
                int limitPrice = 0;

                try {
                    // parse size and limit price in integers
                    size = Integer.parseInt(paramList.get(1));
                    limitPrice = Integer.parseInt(paramList.get(2));
                }
                // invalid values
                catch (NumberFormatException e) {
                    System.out.println("invalid number format");
                    break;
                }

                // check if size and limit price are greater than 0
                if (size <= 0 || limitPrice <= 0) {
                    System.out.println("invalid parameters");
                    break;
                }

                // create order object
                OrderValues orderVal = new OrderValues(type, size, limitPrice);

                request = new Request<OrderValues>("insertLimitOrder", orderVal);

            break;

            // insert market order
            case "insertMarketOrder":
                // check if registered and logged in
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }

                // check number of parameters -> type and size
                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters for market order, insert type and size");
                    break;
                }

                type = paramList.get(0);

                // check if type is valid
                if (!type.equals("ask") && !type.equals("bid")) {
                    System.out.println("type must be ask or bid");
                    break;
                }

                try {
                    // parse size
                    size = Integer.parseInt(paramList.get(1));
                }
                catch (NumberFormatException e) {
                    System.out.println("invalid number format");
                    break;
                }

                // check if size is valid
                if (size <= 0) {
                    System.out.println("invalid order parameters");
                    break;
                }

                // create order object, with price -1 (market orders don't have price)
                orderVal = new OrderValues(type, size, -1);

                request = new Request<OrderValues>("insertMarketOrder", orderVal);

            break;

            // insert stop order
            case "insertStopOrder":
                // check if registered and logged in
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }    
            
                // check number of parameters -> type, size and stop price
                if (paramList.size() != 3) {
                    System.out.println("invalid number of parameters for stop order, insert type, size and stopPrice");
                    break;
                }

                type = paramList.get(0);

                // check if type is valid
                if (!type.equals("ask") && !type.equals("bid")) {
                    System.out.println("type must be ask or bid");
                    break;
                }

                int stopPrice = 0;

                try {
                    // parse size and stop price
                    size = Integer.parseInt(paramList.get(1));
                    stopPrice = Integer.parseInt(paramList.get(2));
                }
                catch (NumberFormatException e) {
                    System.out.println("invalid number format");
                    break;
                }

                // check if size and stop price are valid
                if (size <= 0 || stopPrice <= 0) {
                    System.out.println("invalid order parameters");
                    break;
                }

                orderVal = new OrderValues(type, size, stopPrice);

                request = new Request<OrderValues>("insertStopOrder", orderVal);            

            break;

            // cancel order
            case "cancelOrder":
                // check if registered and logged in
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }

                // check number of parameters -> only order id
                if (paramList.size() != 1) {
                    System.out.println("invalid number of parameters, insert orderID");
                    break;
                }

                // parse order id
                int orderID = Integer.parseInt(paramList.get(0));

                // check if order id is valid
                if (orderID <= 0) {
                    System.out.println("invalid orderID");
                    break;
                }

                // build order response object with order id
                OrderResponse ID = new OrderResponse(orderID);

                request = new Request<OrderResponse>("cancelOrder", ID);

            break;

            // show order book
            case "getOrderBook":
                // this command does not require parameters and can be executed also when not registered or logged in
                if (!paramList.isEmpty()) {
                    System.out.println("invalid command");
                    break;
                }

                request = new Request<Values>("getOrderBook", null);

            break;

            // get price history
            case "getPriceHistory":
                // check if registered and logged in
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }
                
                // check number of parameters -> month and year
                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters, insert month and year");
                    break;
                }

                // parse month and year
                int month = Integer.parseInt(paramList.get(0));
                int year = Integer.parseInt(paramList.get(1));

                // check if month is between 1 and 12 and year is valid
                if (month < 1 || month > 12) {
                    System.out.println("invalid month parameters");
                    break;
                }
                
                if (year < 2000) {
                    System.out.println("invalid year parameter");
                    break;
                }

                // build date string in format MMYYYY
                String date = String.format("%02d%04d", month, year);

                // build history values object
                HistoryValues stats = new HistoryValues(date);

                request = new Request<HistoryValues>("getPriceHistory", stats);

            break;

            // command not valid or not recognized
            default:
                System.out.println("unknown command, respect the syntax");
        }

        return request;
    }

    /**
     * print available commands menu
     */
    private static void printMenu() {
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
    
    /**
     * validate user input with regex 
     * should be command(args,...) or command()
     * 
     * @param input user input string
     * @return boolean for input validity, true if valid, false otherwise
     */
    public static boolean isValid (String input) {
        return input.matches("^[a-zA-Z]+\\((\\s*[a-zA-Z0-9_]+\\s*(,\\s*[a-zA-Z0-9_]+\\s*)*)?\\)$");
    }

    /**
     * stop the sender thread
     */
    public void stop() {
        running = false;
    }
}
