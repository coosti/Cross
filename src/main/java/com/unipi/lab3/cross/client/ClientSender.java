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

public class ClientSender implements Runnable {
    
    private PrintWriter out;
    private LinkedBlockingQueue<String> scanner;

    private volatile boolean running = false;

    private final AtomicBoolean active;

    private final AtomicBoolean logged;
    private final AtomicBoolean registered;

    private Thread listener;
    private UdpListener udpListener;

    private String SHUTDOWN_WARNING = "__SHUTDOWN__";

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

    public void run(){
        running = true;

        try {
            while (running && !Thread.currentThread().isInterrupted()) {

                try {
                    //questa è bloccante però la sblocchiamo con un valore sentinella mandato dal server
                    String userInput = this.scanner.take();

                    if(userInput.equals(SHUTDOWN_WARNING)){
                        System.out.println("Messaggio chiusura Sender");
                        return;
                    }

                    if (userInput.isEmpty() || userInput.isBlank() || !isValid(userInput)) {
                        // command not valid
                        System.out.println("invalid command");
                        continue;
                    }

                    String[] inputStrings = userInput.split("\\(", 2);

                    String command = inputStrings[0].trim();

                    String param = inputStrings[1].substring(0, inputStrings[1].length() - 1).trim();

                    Request<?> request = null;
                    
                    switch(command) {
                        case "help":
                            printMenu();
                        break;

                        default:
                            List<String> params = new ArrayList<>();

                            if (!param.isEmpty()) {
                                String[] tokens = param.split("\\s*,\\s*");
                                params = Arrays.asList(tokens);
                            }

                            request = buildRequest(command, params);

                        break;
                    }

                    if (request != null) {
                        // convert to json message with gson builder
                        
                        String jsonString = gson.toJson(request);

                        // send on tcp socket to server
                        out.println(jsonString);

                        if (command.equals("exit")) {

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

    private Request<?> buildRequest (String operation, List<String> paramList) {
        Request<?> request = null;

        switch (operation) {
            case "exit":
                request = new Request<Values>("exit", null);
            break;

            case "register":
                if (logged.get()) {
                    System.out.println("you are already logged in");
                    break;
                }

                // check number of params
                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters");
                    break;
                }

                String username = paramList.get(0);
                String password = paramList.get(1);

                // create values object
                UserValues values = new UserValues(username, password);

                // create request object
                request = new Request<UserValues>("register", values);  

            break;

            case "login":
                if (logged.get()) {
                    System.out.println("you are already logged in");
                    break;
                }

                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters, insert username and password");
                    break;
                }

                username = paramList.get(0);
                password = paramList.get(1);

                try {
                    // start udp listener
                    if (!listener.isAlive()) {
                        listener.start();
                    }

                    // wait for listener to start
                    int sleepTime = 500;
                    Thread.sleep(sleepTime);

                    int udpPort = udpListener.getPort();

                    NetworkValues netValues = new NetworkValues(udpPort);

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

            case "updateCredentials":
                if (!registered.get()) {
                    System.out.println("you have to register first");
                    break;
                }
                else if (logged.get()) {
                    System.out.println("you can't change credentials while logged in");
                    break;
                }

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

            case "insertLimitOrder":
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }

                if (paramList.size() != 3) {
                    System.out.println("invalid number of parameters, insert type, size and limitPrice");
                    break;
                }

                String type = paramList.get(0);

                if (!type.equals("ask") && !type.equals("bid")) {
                    System.out.println("type must be ask or bid");
                    break;
                }

                int size = 0;
                int limitPrice = 0;

                try {
                    size = Integer.parseInt(paramList.get(1));
                    limitPrice = Integer.parseInt(paramList.get(2));
                }
                catch (NumberFormatException e) {
                    System.out.println("invalid number format");
                    break;
                }

                if (size <= 0 || limitPrice <= 0) {
                    System.out.println("invalid parameters");
                    break;
                }

                OrderValues orderVal = new OrderValues(type, size, limitPrice);

                request = new Request<OrderValues>("insertLimitOrder", orderVal);

            break;

            case "insertMarketOrder":
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }

                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters for market order, insert type and size");
                    break;
                }

                type = paramList.get(0);

                if (!type.equals("ask") && !type.equals("bid")) {
                    System.out.println("type must be ask or bid");
                    break;
                }

                try {
                    size = Integer.parseInt(paramList.get(1));
                }
                catch (NumberFormatException e) {
                    System.out.println("invalid number format");
                    break;
                }

                if (size <= 0) {
                    System.out.println("invalid order parameters");
                    break;
                }

                orderVal = new OrderValues(type, size, -1);

                request = new Request<OrderValues>("insertMarketOrder", orderVal);

            break;

            case "insertStopOrder":
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }    
            
                if (paramList.size() != 3) {
                    System.out.println("invalid number of parameters for stop order, insert type, size and stopPrice");
                    break;
                }

                type = paramList.get(0);

                if (!type.equals("ask") && !type.equals("bid")) {
                    System.out.println("type must be ask or bid");
                    break;
                }

                int stopPrice = 0;

                try {
                    size = Integer.parseInt(paramList.get(1));
                    stopPrice = Integer.parseInt(paramList.get(2));
                }
                catch (NumberFormatException e) {
                    System.out.println("invalid number format");
                    break;
                }

                if (size <= 0 || stopPrice <= 0) {
                    System.out.println("invalid order parameters");
                    break;
                }

                orderVal = new OrderValues(type, size, stopPrice);

                request = new Request<OrderValues>("insertStopOrder", orderVal);            

            break;

            case "cancelOrder":
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }

                if (paramList.size() != 1) {
                    System.out.println("invalid number of parameters, insert orderID");
                    break;
                }

                int orderID = Integer.parseInt(paramList.get(0));

                if (orderID <= 0) {
                    System.out.println("invalid orderID");
                    break;
                }

                OrderResponse ID = new OrderResponse(orderID);

                request = new Request<OrderResponse>("cancelOrder", ID);

            break;

            case "getOrderBook":
                if (!paramList.isEmpty()) {
                    System.out.println("invalid command");
                    break;
                }

                request = new Request<Values>("getOrderBook", null);

            break;

            case "getPriceHistory":
                if (!registered.get() || !logged.get()) {
                    System.out.println("operation not allowed");
                    break;
                }
                
                if (paramList.size() != 2) {
                    System.out.println("invalid number of parameters, insert month and year");
                    break;
                }

                int month = Integer.parseInt(paramList.get(0));
                int year = Integer.parseInt(paramList.get(1));

                if (month < 1 || month > 12 || year < 2000) {
                    System.out.println("invalid year and month parameters");
                    break;
                }

                HistoryValues stats = new HistoryValues(month, year);

                request = new Request<HistoryValues>("getPriceHistory", stats);

            break;

            default:
                System.out.println("unknown command, respect the syntax");
        }

        return request;
    }

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
    
    // check if input is valid
    // should be command(args,...) or command()
    public static boolean isValid (String input) {
        return input.matches("^[a-zA-Z]+\\((\\s*[a-zA-Z0-9_]+\\s*(,\\s*[a-zA-Z0-9_]+\\s*)*)?\\)$");
    }

    public void stop() {
        running = false;
    }
}
