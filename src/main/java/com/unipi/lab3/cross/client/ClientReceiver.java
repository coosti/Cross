package com.unipi.lab3.cross.client;

import java.io.*;
import java.net.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.unipi.lab3.cross.json.response.*;
import com.unipi.lab3.cross.model.OrderBook;
import com.unipi.lab3.cross.model.trade.PriceHistory;

/**
 * class to handle incoming messages from the server on the TCP connection
 * continuously reads from the input stream and processes server responses
 * parsing json messages into the correct response objects and handling them
*/

public class ClientReceiver implements Runnable {

    // input stream to read received messages from server
    private BufferedReader in;

    // queue for user input to notify shutdown
    private LinkedBlockingQueue<String> userInput;

    // running flag
    private volatile boolean running = false;

    // flag for client state
    private final AtomicBoolean logged;
    private final AtomicBoolean registered;

    // flag to indicate server closure
    private final AtomicBoolean serverClosed;

    public static final String SHUTDOWN_MESSAGE = "__SHUTDOWN__";

    private Gson gson = new Gson();

    public ClientReceiver (BufferedReader in, AtomicBoolean logged, AtomicBoolean registered, AtomicBoolean serverClosed, LinkedBlockingQueue<String> userInput) {
        this.in = in;
        this.logged = logged;
        this.registered = registered;
        this.serverClosed = serverClosed;

        this.userInput = userInput;
    }

    public void run() {
        running = true;

        try {
            String responseMsg = null;

            // continuously read messages from server
            while (running && !Thread.currentThread().isInterrupted()) {

                try {
                    // read a line from the input stream
                    responseMsg = in.readLine();
                }
                // normal timeout
                catch (SocketTimeoutException e) {
                    continue;
                }
                // connection error -> notify shutdown and exit
                catch (SocketException e) {
                    if (running) {
                        System.err.println("socket error: " + e.getMessage());

                        try {
                            userInput.put(SHUTDOWN_MESSAGE);
                        }
                        catch (InterruptedException exc) {
                            System.err.println("exception: " + exc.getMessage());
                        }

                        serverClosed.set(true);
                        running = false;
                    }
                    break;
                }
                // general I/O error -> notify shutdown and exit
                catch (IOException e) {
                    if (running) {
                        System.err.println("connection stopped: " + e.getMessage());

                        try {
                            userInput.put(SHUTDOWN_MESSAGE);
                        }
                        catch (InterruptedException exc) {
                            System.err.println("exception: " + exc.getMessage());
                        }

                        serverClosed.set(true);
                        running = false;
                    }
                    break;
                }

                // server closed connection
                if (responseMsg == null) {
                    //System.out.println("connection closed");

                    try {
                        userInput.put(SHUTDOWN_MESSAGE);
                    }
                    catch (Exception e) {
                        System.err.println("exception: " + e.getMessage());
                    }

                    running = false;
                    serverClosed.set(true);
                    //logged.set(false);

                    break;
                }
                
                // ignore empty messages
                if (responseMsg.isBlank()) continue;
                
                // parse json message read
                JsonObject obj = JsonParser.parseString(responseMsg).getAsJsonObject();

                // determine response type based on json fields

                // order response
                if (obj.has("orderID")) {
                    OrderResponse orderResponse = gson.fromJson(responseMsg, OrderResponse.class);
                    handleResponse(orderResponse);
                }
                // user response
                else if (obj.has("operation") && obj.has("response") && obj.has("errorMessage")) {
                    UserResponse userResponse = gson.fromJson(responseMsg, UserResponse.class);
                    handleResponse(userResponse);
                }
                // response to show order book
                else if (obj.has("orderBook")) {
                    OrderBookResponse orderBookResponse = gson.fromJson(responseMsg, OrderBookResponse.class);
                    handleResponse(orderBookResponse);
                }
                // history trade response
                else if (obj.has("date") && obj.has("stats")) {
                    HistoryResponse historyResponse = gson.fromJson(responseMsg, HistoryResponse.class);
                    handleResponse(historyResponse);
                }
                // unknown response
                else {
                    System.out.println("unknown response from server" + responseMsg);
                }
            }
        }
        // ensure flags are reset on exit
        finally {
            running = false;
            logged.set(false);
            serverClosed.set(true);
        }
    }

    /**
     * handles a response message from the server based on its type
     * 
     * @param responseMsg response message object received from server
     */
    public void handleResponse (Response responseMsg) {
        // handle order response
        if (responseMsg instanceof OrderResponse) {
            // cast to order response
            OrderResponse orderResponse = (OrderResponse)responseMsg;

            // get order id field value to print message to client
            if (orderResponse.getOrderID() != -1) {
                System.out.println("order with ID: " + orderResponse.getOrderID());
            }
            else {
                System.out.println("order failed");
            }
        }
        // handle user response
        else if (responseMsg instanceof UserResponse) {
            // cast to user response
            UserResponse userResponse = (UserResponse)responseMsg;

            // get operation field value to determine type of user operation (registration, login, logout, order book operations, etc.)
            String op = userResponse.getOperation();

            switch (op) {
                // registration
                case "register":
                    // successful registration -> set registered flag
                    if (userResponse.getResponse() == 100) {
                        registered.set(true);
                        System.out.println("registration successful");
                    }
                    else {
                        System.out.println(userResponse.getErrorMessage());
                    }
                break;
                // update credentials
                case "updateCredentials":
                    if (userResponse.getResponse() == 100) {
                        System.out.println("update successful");
                    }
                    else {
                        System.out.println(userResponse.getErrorMessage());
                    }
                break;
                // login
                case "login":
                    // successful login -> set logged flag
                    if (userResponse.getResponse() == 100) {
                        registered.set(true);
                        logged.set(true);
                        System.out.println("login successful");
                    }
                    else {
                        logged.set(false);
                        System.out.println(userResponse.getErrorMessage());
                    }
                break;
                // logout
                case "logout":
                    // successful logout -> reset logged flag
                    if (userResponse.getResponse() == 100) {
                        logged.set(false);
                        System.out.println("logout successful");
                    }
                    else {
                        System.out.println(userResponse.getErrorMessage());
                    }

                break;
                // cancel order
                case "cancelOrder":
                    if (userResponse.getResponse() == 100) {
                        System.out.println("order successfully deleted");
                    }
                    else {
                        System.out.println(userResponse.getErrorMessage());
                    }
                break;

                case "insertLimitOrder":
                case "insertMarketOrder":
                case "insertStopOrder":
                    if (userResponse.getResponse() != 100) {
                        System.out.println(userResponse.getErrorMessage());
                    }
                break;

                /*case "exit":
                    if (userResponse.getResponse() == 100) {

                        try {
                            userInput.put(SHUTDOWN_MESSAGE);
                        }
                        catch (InterruptedException e) {
                            System.err.println("exception: " + e.getMessage());
                        }

                        serverClosed.set(true);
                        running = false;
                        //System.exit(0);
                    }
                    else {
                        System.out.println(userResponse.getErrorMessage());
                    }
                break;*/

                default:
                    System.out.println(userResponse.getErrorMessage());
                break;
            }
        }
        // handle order book response
        else if (responseMsg instanceof OrderBookResponse) {
            OrderBookResponse orderBookResponse = (OrderBookResponse) responseMsg;
            // get order book object to print
            OrderBook ob = orderBookResponse.getOrderBook();

            ob.printOrderBook();
        }
        // handle trades history response
        else if (responseMsg instanceof HistoryResponse) {
            HistoryResponse historyResponse = (HistoryResponse) responseMsg;

            System.out.println("Price history for " + historyResponse.getMonth() + " " + historyResponse.getYear() + ":");

            // get price history stats
            PriceHistory ph = new PriceHistory();

            ph.printPriceHistory(historyResponse.getStats());            
        }
        else {
            System.out.println("unknown response");
        }
    }

    /**
     * stops the receiver thread
     */
    public void stop() {
        // set running flag to false
        running = false;
        // set server closed flag to true
        serverClosed.set(true);

        try {
            // close input stream
            in.close();
        }
        catch (Exception e) {}
    }
}