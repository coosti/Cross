package com.unipi.lab3.cross.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.time.Year;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.unipi.lab3.cross.model.*;
import com.unipi.lab3.cross.model.user.*;
import com.unipi.lab3.cross.model.trade.*;
import com.unipi.lab3.cross.json.request.*;
import com.unipi.lab3.cross.json.response.*;
import com.unipi.lab3.cross.main.ServerMain;

/**
 * class to handle a client connection as a thread
 * manage every client request and response 
 */

public class ClientHandler implements Runnable {

    // socket of the connected client
    private final Socket clientSocket;

    // istance of the user connected to this client
    private User user;
    private volatile long lastActivityTime;

    // useful shared resources
    private OrderBook orderBook;
    private UserManager userManager;
    private TradeMap tradeMap;

    private PriceHistory priceHistory;

    private UdpNotifier udpNotifier;
    private int udpPort;

    // flag to control the running state of the thread
    private volatile boolean running;

    // limits to check for order values
    private static final int MAX_VALUE = Integer.MAX_VALUE - 1; // (2^31)-1
    private static final int MIN_VALUE = 1;

    private final Gson gson = new GsonBuilder().create();

    public ClientHandler(Socket clientSocket, UserManager userManager, OrderBook orderBook, TradeMap tradeMap, UdpNotifier udpNotifier, InactivityHandler inactivityHandler) {
        this.clientSocket = clientSocket;

        this.user = null; // initially not authenticated

        this.userManager = userManager;
        this.orderBook = orderBook;
        this.tradeMap = tradeMap;
        this.udpNotifier = udpNotifier;
        this.priceHistory = new PriceHistory();

        this.lastActivityTime = System.currentTimeMillis();
    }

    @Override
    public void run() {

        running = true;

        try (
            // try with resources to for input and output streams
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                // variable to store received message and response
                String receivedMsg;
                Response response;

                // main loop listening for client messages
                while (running && ((receivedMsg = in.readLine()) != null)) {
                    try {
                        // update last activity time when a message is received
                        updateLastActivityTime();

                        // function to handle messages
                        response = handleRequest(receivedMsg);

                        if (response != null) {
                            
                            // serialize processed response to json
                            String jsonString = gson.toJson(response);

                            // send response back to client
                            out.println(jsonString);

                            /*if (response instanceof UserResponse) {
                                if (((UserResponse)response).getOperation().equals("exit")) {
                                    stop();
                                    break;
                                }
                            }*/
                        }  
                    }
                    // malformed json input
                    catch (JsonSyntaxException e) {
                        System.err.println(e.getMessage());
                        out.println(gson.toJson(new UserResponse("error", -1, "json error")));
                    }
                    // generic exception
                    catch (Exception e) {
                        System.err.println(e.getMessage());
                        out.println(gson.toJson(new UserResponse("error", -1, "server error")));
                    }

                }
        }
        // handle server termination
        catch (IOException e) {
            System.err.println("socket error: " + e.getMessage());
        }
        finally {
            // set running to false to exit to main loop
            running = false;

            // logout user and remove it from active clients
            logoutUser();

            try {
                // close the socket for this client
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("socket error" + e.getMessage());
            }
        }
    }

    /**
     * parses and processes every client request expressed as JSON
     * elaborates the request and the appropriate response
     *  
     * @param request the request string by the client in JSON format
     * @return response object
     */
    public Response handleRequest (String request) {
        Response response = null;

        try {
            // parse the request string to a json object
            JsonObject obj = JsonParser.parseString(request).getAsJsonObject();

            // get operation from the json object
            String op = obj.get("operation").getAsString();

            // return code from the execution of the operation
            int code = -1;
            // message associated to the return code
            String msg = "";

            switch (op) {
                // client requests to exit
                case "exit":
                    response = new UserResponse("exit", 100, "exited successfully");
                break;

                // client requests to register as a new user
                case "register":
                    // parse user credentials
                    UserValues userVal = gson.fromJson(obj.get("values"), UserValues.class);

                    // avoid registering if already logged in
                    if (this.user != null && this.user.getLogged())
                        return new UserResponse("register", 103, "user already logged in");

                    // execute registration with given credentials and save return code
                    code = userManager.register(userVal.getUsername(), userVal.getPassword());

                    // successful registration
                    if (code == 100) {
                        msg = "OK";

                        // set the user for this client handler
                        this.user = userManager.getUser(userVal.getUsername());

                        System.out.println("user " + userVal.getUsername() + " registered");
                    }
                    // error cases
                    else if (code == 101)
                        msg = "invalid password";
                    else if (code == 102)
                        msg = "username not available";
                    else if (code == 103)
                        msg = "invalid username";
                    
                    updateLastActivityTime();

                    // return response to client, with operation, code and message
                    response = new UserResponse("register", code, msg);

                break;

                // client requests to update their credentials
                case "updateCredentials":
                    // parse user credentials
                    userVal = gson.fromJson(obj.get("values"), UserValues.class);

                    // credentials update not allowed if user is logged in
                    if (this.user != null && this.user.getLogged())
                        return new UserResponse("updateCredentials", 104, "user currently logged");

                    // check if username exists
                    if (userManager.getUser(userVal.getUsername()) == null)
                        return new UserResponse("updateCredentials", 102, "non existent username");

                    // execute credentials update
                    code = userManager.updateCredentials(userVal.getUsername(), userVal.getNewPassword(), userVal.getPassword());

                    // successful update
                    if (code == 100) {
                        msg = "OK";

                        if (this.user == null)
                            this.user = userManager.getUser(userVal.getUsername());

                        System.out.println("user " + userVal.getUsername() + " updated credentials");
                    }
                    // error cases
                    else if (code == 101)
                        msg = "invalid new password";
                    else if (code == 102)
                        msg = "old password mismatch";
                    else if (code == 103)
                        msg = "new passord equal to old one";

                    updateLastActivityTime();

                    response = new UserResponse("updateCredentials", code, msg);

                break;
                
                // client requests to login
                case "login":
                    // parse user credentials
                    userVal = gson.fromJson(obj.get("values"), UserValues.class);

                    // not allowed to login if already logged in
                    if (this.user != null && this.user.getLogged())
                        return new UserResponse("login", 102, "user already logged in");

                    // check if the given username exists
                    if (userManager.getUser(userVal.getUsername()) == null)
                        return new UserResponse("login", 101, "non existent username");
                    
                    // check if network values are valid
                    if (userVal.getNetworkValues() == null || userVal.getNetworkValues().getPort() <= 1024)
                        return new UserResponse("login", 104, "invalid network values");

                    // execute login with given credentials
                    code = userManager.login(userVal.getUsername(), userVal.getPassword());

                    // successful login
                    if (code == 100) {
                        msg = "OK";

                        // register client for udp notifications
                        udpPort = userVal.getNetworkValues().getPort();
                        udpNotifier.registerClient(userVal.getUsername(), clientSocket.getInetAddress(), udpPort);

                        // update local user instance
                        if (this.user == null) {
                            // if user instance not set, get it from user manager
                            this.user = userManager.getUser(userVal.getUsername());
                            // set user as logged in
                            this.user.setLogged(true);
                        }
                        else {
                            // just set user as logged in
                            this.user.setLogged(true);
                        }

                        updateLastActivityTime();

                        System.out.println("user " + userVal.getUsername() + " logged in");
                    } 
                    // error cases
                    else if (code == 101)
                        msg = "password mismatch";
                    else if (code == 102)
                        msg = "user already logged in";
                    else if (code == 103)
                        msg = "invalid password";

                    response = new UserResponse("login", code, msg);

                break;
                
                // client requests to logout
                case "logout":
                    // check if user instance is inizialized
                    if (this.user == null)
                        return new UserResponse("logout", 101, "user error");

                    // cannot logout if not logged in
                    if (this.user.getLogged() == false)
                        return new UserResponse("logout", 101, "user not logged in");

                    // execute logout
                    code = userManager.logout(this.user.getUsername());

                    // successful logout
                    if (code == 100) {
                        msg = "OK";

                        // unregister client from udp notifications
                        udpNotifier.removeClient(this.user.getUsername());
                        
                        // set user as logged out
                        this.user.setLogged(false);

                        System.out.println("user logged out");
                    }

                    response = new UserResponse("logout", code, msg);
                
                break;
                
                // client requests to insert a limit order
                case "insertLimitOrder":
                    // check if user instance is initialized
                    if (this.user == null)
                        return new UserResponse("insertLimitOrder", 101, "user error");

                    // cannot insert orders if not logged in
                    if (this.user.getLogged() == false)
                        return new UserResponse("insertLimitOrder", 102, "you can't insert orders if not logged in");
                    
                    // parse order values
                    OrderValues orderVal = gson.fromJson(obj.get("values"), OrderValues.class);

                    // check if order values (size and price) are valid
                    if (!isValidSize(orderVal.getSize()))
                        return new UserResponse("insertLimitOrder", 103, "invalid order values: size exceeds limits");

                    if (!isValidPrice(orderVal.getPrice()))
                        return new UserResponse("insertLimitOrder", 103, "invalid order values: price exceeds limits");

                    // try to execute limit order 
                    code = orderBook.execLimitOrder(this.user.getUsername(), orderVal.getType(), orderVal.getSize(), orderVal.getPrice());

                    // error executing limit order
                    if (code == -1) {
                        System.out.println("error with limit order inserted by user " + this.user.getUsername());
                    }

                    // succesful execution with order id as return code
                    response = new OrderResponse(code);
                break;
                
                // client requests to insert a market order
                case "insertMarketOrder":
                    // check if user instance is initialized
                    if (this.user == null)
                        return new UserResponse("insertMarketOrder", 101, "user error");

                    // cannot insert orders if not logged in
                    if (this.user.getLogged() == false)
                        return new UserResponse("insertMarketOrder", 102, "you can't insert orders if not logged in");
                    
                    // parse order values
                    orderVal = gson.fromJson(obj.get("values"), OrderValues.class);

                    // check if market order size is valid
                    if (!isValidSize(orderVal.getSize()))
                        return new UserResponse("insertMarketOrder", 103, "invalid order values: size exceeds limits");

                    // try to execute market order
                    code = orderBook.execMarketOrder(orderVal.getSize(), orderVal.getType(), "market", this.user.getUsername(), -1);

                    // market order execution failed
                    if (code == -1) {
                        System.out.println("market order inserted by user " + this.user.getUsername() + "cannot be executed");
                    }

                    response = new OrderResponse(code);

                break;

                // client requests to insert a stop order
                case "insertStopOrder":
                    // check if user instance is initialized
                    if (this.user == null)
                        return new UserResponse("insertStopOrder", 101, "user error");

                    // cannot insert orders if not logged in
                    if (this.user.getLogged() == false)
                        return new UserResponse("insertStopOrder", 102, "you can't insert orders if not logged in");
                    
                    // parse order values
                    orderVal = gson.fromJson(obj.get("values"), OrderValues.class);

                    // check if order values (size and price) are valid
                    if (!isValidSize(orderVal.getSize()))
                        return new UserResponse("insertStopOrder", 103, "invalid order values: size exceeds limits");

                    if (!isValidPrice(orderVal.getPrice()))
                        return new UserResponse("insertStopOrder", 103, "invalid order values: price exceeds limits");

                    // try to execute stop order
                    code = orderBook.addStopOrder(this.user.getUsername(), orderVal.getSize(), orderVal.getPrice(), orderVal.getType());

                    // error inserting stop order
                    if (code == -1) {
                        System.out.println("error with stop order inserted by user " + this.user.getUsername());
                    }

                    response = new OrderResponse(code);

                break;
                
                // client requests to cancel an order inserted
                case "cancelOrder":
                    // check if user instance is initialized
                    if (this.user == null)
                        return new UserResponse("cancelOrder", 101, "user error");
                    
                    // cannot execute operation if not logged in
                    if (this.user.getLogged() == false)
                        return new UserResponse("cancelOrder", 102, "you can't cancel orders if not logged in");

                    // parse order id to find the order in the order book
                    int orderID = gson.fromJson(obj.get("values"), OrderResponse.class).getOrderID();

                    // execute order cancellation
                    code = orderBook.cancelOrder(orderID, this.user.getUsername());

                    // successful cancellation
                    if (code == 100) {
                        msg = "OK";
                        System.out.println("order with ID: " + orderID + " cancelled by user " + this.user.getUsername());
                    }
                    // order not found or not owned by user
                    else if (code == 101) {
                        msg = "order does not exist";
                    }

                    response = new UserResponse("cancelOrder", code, msg);
                break;

                // client requests to get the current order book
                // anyone can request the order book
                case "getOrderBook":
                    /*if (this.user == null)
                        return new UserResponse("getOrderBook", 101, "user error");*/

                    OrderBook ob = orderBook.getOrderBook();
                    
                    response = new OrderBookResponse(ob);
                break;

                // client requests to get price history for a given month and year
                case "getPriceHistory":
                    // check if user instance is initialized
                    if (this.user == null)
                        return new UserResponse("getPriceHistory", 101, "user error");

                    // cannot request history if not logged in
                    if (this.user.getLogged() == false)
                        return new UserResponse("getPriceHistory", 102, "you can't get price history if not logged in");

                    // parse month and year values
                    HistoryValues historyVal = gson.fromJson(obj.get("values"), HistoryValues.class);

                    String date = historyVal.getDate();

                    // validate date format MMYYYY
                    if (date.length() != 6)
                        return new UserResponse("getPriceHistory", 103, "invalid date format");

                    int month = historyVal.getMonth();
                    int year = historyVal.getYear();  

                    // check if month and year values are valid
                    if (month < 1 || month > 12)
                        return new UserResponse("getPriceHistory", 104, "invalid month");
                        
                    if (year < 1970 || year > Year.now().getValue())
                        return new UserResponse("getPriceHistory", 105, "invalid year");

                    // get price history for given month and year
                    ArrayList<DailyTradingStats> result = priceHistory.getPriceHistory(month, year, tradeMap);

                    if (result.isEmpty())
                        return new UserResponse("getPriceHistory", 106, "no trading data available for given date");

                    response = new HistoryResponse(date, result);
                    
                break;
                
                // unknown operation requested
                default:
                    response = new UserResponse("unknown", 101, "unknown operation");
                break;
            }
        }
        catch (JsonSyntaxException e) {
            System.err.println(e.getMessage());
            return new UserResponse("error", -1, "json error");
        }
        catch (Exception e) {
            System.err.println(e.getMessage());
            return new UserResponse("error", -1, "server error");
        }

        return response;
    }

    public Socket getClientSocket() {
        return this.clientSocket;
    }

    public User getUser() {
        return this.user;
    }

    public String getUsername() {
        if (this.user != null)
            return this.user.getUsername();
        else
            return null;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public void updateLastActivityTime () {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * checks if the user is logged in
    */
    public boolean isLoggedIn() {
        if (this.user != null)
            return this.user.getLogged();
        else
            return false;
    }

    /**
     * checks if size value is within valid limits
     * @param size the size value to check
     * @return true if size is valid, false otherwise
     */
    public boolean isValidSize (int size) {
        return size >= MIN_VALUE && size <= MAX_VALUE;
    }

    /**
     * checks if price value is within valid limits
     * @param price the price value to check
     * @return true if price is valid, false otherwise
     */
    public boolean isValidPrice (int price) {
        return price >= MIN_VALUE && price <= MAX_VALUE;
    }

    /**
     * logs out the user associated to this client handler
     */
    public void logoutUser () {
        if (this.user != null && this.user.getLogged()) {
            try {
                // change the user status in the user manager map
                userManager.logout(this.user.getUsername());
                // set local user instance as logged out
                this.user.setLogged(false);

                // unregister client from udp notifications
                udpNotifier.removeClient(this.user.getUsername());
            }
            catch (Exception e) {
                System.err.println("error logging out user: " + e.getMessage());
            }
        }
    }
    
    /**
     * stops the client handler thread and closes the client socket
     */
    public void stop() {
        running = false;

        // remove this client from the active clients list
        ServerMain.removeActiveClient(clientSocket);

        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                // close the client socket
                clientSocket.close();
            }
        } 
        catch (IOException e) {
            System.err.println("error closing client socket: " + e.getMessage());
        }

        System.out.println("client closed");
    }
}