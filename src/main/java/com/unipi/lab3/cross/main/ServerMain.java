package com.unipi.lab3.cross.main;

import com.unipi.lab3.cross.model.*;
import com.unipi.lab3.cross.model.trade.*;
import com.unipi.lab3.cross.model.user.*;
import com.unipi.lab3.cross.server.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.reflect.Type;
import java.time.*;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

/**
 * main class for cross server application
 * 
 * sets up the server and all settings, loads data from files,
 * listens for client connections and manages active clients
 */

public class ServerMain {

    // server configuration
    public static final String configFile = "src/main/resources/server.properties";

    // server properties
    public static int tcpPort;
    public static int udpPort;

    // server socket
    public static ServerSocket serverSocket;

    // map of registered users
    private static ConcurrentHashMap<String, User> users;

    // user manager for handling user operations
    private static UserManager userManager;

    // order book
    private static OrderBook orderBook;

    // trade map
    private static TradeMap tradeMap;
    private static LinkedList<Trade> bufferedTrades;

    // map of active clients (for inactivity handling)
    public static ConcurrentHashMap<Socket, ClientHandler> activeClients;

    // UDP notifier for instant notification to clients
    public static UdpNotifier udpNotifier;

    // handler for inactive clients
    public static InactivityHandler inactivityHandler;
    public static Thread inactivityThread;

    // inactivity timeout for users in seconds
    public static int inactivityTimeout;

    // scan interval for inactivity handler in seconds
    public static int scanInterval;

    // persistence handler
    public static ScheduledExecutorService scheduler;
    public static PersistenceHandler persistenceHandler;

    public static Gson gson = new Gson();

    //threadpool
    public static final ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws Exception{

        // shutdown hook to handle server shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("server shutting down...");
            }
            catch (Exception e) {}                

            shutdown();
        }));

        // read config file with server properties
        getServerProperties();

        System.out.println("server configuration loaded!");

        try {
            // create TCP socket
            serverSocket = new ServerSocket(tcpPort);

            System.out.println("server started on port " + tcpPort);
            System.out.println("waiting for connections...");

            // UDP notifier initialization
            udpNotifier = new UdpNotifier(udpPort);

            // load users, trades and orders from files

            loadUsers();

            System.out.println("loaded users:");
            for (Map.Entry<String, User> entry : users.entrySet()) {
                System.out.println("username: " + entry.getKey() + ", " + entry.getValue());
            }

            // load existing users into user manager
            userManager = new UserManager(users);

            loadTrades();

            loadOrderBook();

            // initialize all data structures

            bufferedTrades = new LinkedList<>();

            orderBook.setUdpNotifier(udpNotifier);
            orderBook.setTradeMap(tradeMap);
            orderBook.setBufferedTrades(bufferedTrades);

            activeClients = new ConcurrentHashMap<>();

            // persistence thread
            persistenceHandler = new PersistenceHandler(orderBook, userManager, bufferedTrades);

            // schedule periodic (every minute) persistence task, saving data
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    persistenceHandler.saveAll();
                } catch (Exception e) {
                    System.err.println("Error during persistence: " + e.getMessage());
                }
            }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);

            // inactivity handler thread
            inactivityHandler = new InactivityHandler(activeClients, userManager, orderBook, inactivityTimeout, scanInterval);
            inactivityThread = new Thread(inactivityHandler);
            inactivityThread.start();

            // listening loop to accept new client connections on TCP socket
            while (true) {
                try { 
                        
                    Socket clientSocket = serverSocket.accept();

                    // create a client handler for the new connected client
                    ClientHandler handler = new ClientHandler(clientSocket, userManager, orderBook, tradeMap, udpNotifier, inactivityHandler);

                    // add handler to active clients map
                    addActiveClient(clientSocket, handler);

                    System.out.println("new client connected: " + clientSocket.getInetAddress() + ":" + clientSocket.getPort());

                    // submit client handler to the thread pool for execution
                    pool.execute(handler);
                }
                // server socket closed
                catch (SocketException se) {
                    break;
                }
                // generic I/O error
                catch (IOException e) {
                    System.err.println(e.getMessage());
                }

            }
        }
        catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            return;
        }
    }

    /**
     * loads registered users from the users.json file into the users map
    */
    public static void loadUsers () {
        // path to users file
        File file = new File("src/main/resources/users.json");

        try (FileReader fr = new FileReader(file)) {
            // deserialize users data from json into a concurrent hash map

            // define map type for deserialization (get as map of users)
            Type type = new TypeToken<ConcurrentHashMap<String, User>>() {}.getType();

            // check if file is not empty
            if (file.length() != 0) {
                // parse json file into map within gson
                users = gson.fromJson(fr, type);

                if (users == null)
                    // empty but valid json file
                    users = new ConcurrentHashMap<>();
            }
            else
                // empty file
                users = new ConcurrentHashMap<>();
            
        } 
        // file doesn't exist
        catch (FileNotFoundException e) {
            System.err.println("users file not found");
            users = new ConcurrentHashMap<>();
        } 
        // error during file reading
        catch (JsonIOException e) {
            System.err.println("error parsing users file: " + e.getMessage());
            users = new ConcurrentHashMap<>();
        }
        // malformed json file
        catch (JsonSyntaxException e) {
            System.err.println("error in users file syntax: " + e.getMessage());
            users = new ConcurrentHashMap<>();
        }
        // generic I/O error
        catch (IOException e) {
            System.err.println("error reading users file: " + e.getMessage());
            users = new ConcurrentHashMap<>();
        }
    }

    /**
     * loads trade history from the storicoOrdini.json file into the trade map
    */
    public static void loadTrades () {
        // path to trades file
        File file = new File("src/main/resources/storicoOrdini.json");

        try (FileReader fr = new FileReader(file)) {
            // check if file is not empty
            if (file.length() != 0) {

                // parse data from json file into a json object
                JsonObject obj = JsonParser.parseReader(fr).getAsJsonObject();

                // get trades array from json object, as json array
                JsonArray tradesArray = obj.getAsJsonArray("trades");

                // define list type for deserialization (get as list of trades)
                Type tradeListType = new TypeToken<LinkedList<Trade>>() {}.getType();

                // deserialize json array into list of trades within gson
                LinkedList<Trade> trades = gson.fromJson(tradesArray, tradeListType);

                // create trade map and add loaded trades
                tradeMap = new TradeMap();

                // add trades by date
                for (Trade trade : trades) {
                    // convert trade timestamp to local date
                    LocalDate date = Instant.ofEpochSecond(trade.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDate();
                    String dateStr = date.toString();
                    tradeMap.addTrade(dateStr, trade);
                }
            }
            else
                // empty file
                tradeMap = new TradeMap();
        }
        catch (FileNotFoundException e) {
            System.err.println("trades file not found");
            tradeMap = new TradeMap();
        } 
        catch (JsonIOException e) {
            System.err.println("error parsing trades file: " + e.getMessage());
            tradeMap = new TradeMap();
        }
        catch (JsonSyntaxException e) {
            System.err.println("error in trades file syntax: " + e.getMessage());
            tradeMap = new TradeMap();
        }
        catch (IOException e) {
            System.err.println("error reading trades file: " + e.getMessage());
            tradeMap = new TradeMap();
        }
    }

    /**
     * loads order book from the orders.json file into the order book object
    */
    public static void loadOrderBook () {
        // path to orders file
        File file = new File ("src/main/resources/orders.json");

        try (FileReader fr = new FileReader(file)) {
            if (file.length() != 0) {
                // define type for deserialization
                Type type = new TypeToken<OrderBook>() {}.getType();

                // parse json file into order book object within gson
                orderBook = gson.fromJson(fr, type);

                // initialize any missing data structures
                if (orderBook.getLimitAsks() == null)
                    orderBook.setAskOrders(new ConcurrentSkipListMap<>());

                if (orderBook.getLimitBids() == null)
                    orderBook.setBidOrders(new ConcurrentSkipListMap<>(Comparator.reverseOrder()));

                if (orderBook.getStopAsks() == null)
                    orderBook.setStopAsks(new ConcurrentLinkedQueue<>());

                if (orderBook.getStopBids() == null)
                    orderBook.setStopBids(new ConcurrentLinkedQueue<>());

                // restore static id counter for orders
                orderBook.restoreId();
            }
            else
                // empty file
                orderBook = new OrderBook();
        }
        catch (FileNotFoundException e) {
            System.err.println("orderBook file not found");
            orderBook = new OrderBook();
        }
        catch (JsonIOException e) {
            System.err.println("error parsing order book file: " + e.getMessage());
            orderBook = new OrderBook();
        } 
        catch (JsonSyntaxException e) {
            System.err.println("error in order book file syntax: " + e.getMessage());
            orderBook = new OrderBook();
        }
        catch (IOException e) {
            System.err.println("error reading order book file: " + e.getMessage());
            orderBook = new OrderBook();
        }  
    }

    /**
     * adds a new active client to the active clients map
     * 
     * @param socket client socket for new connection
     * @param handler client handler thread 
     */
    public static synchronized void addActiveClient (Socket socket, ClientHandler handler) {
        activeClients.put(socket, handler);
    }

    /**
     * removes the specified client to the active clients map
     *  
     * @param socket client socket of the client to be removed
     */
    public static synchronized void removeActiveClient (Socket socket) {
        activeClients.remove(socket);
    }

    /**
     * shuts down the socket and performs a clean up routing
     * cleaning all defined data structures and threads
     */
    public static void shutdown() {

        // close main server socket to stop accepting new client connections
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } 
        catch (IOException e) {
            System.err.println("error closing server socket: " + e.getMessage());
        }

        // logout all active users
        if (activeClients != null && !activeClients.isEmpty()) {
            for (ClientHandler handler : activeClients.values()) {
                try {
                    handler.logoutUser();
                } 
                catch (Exception e) {
                    System.err.println("error shutting down client: " + e.getMessage());
                }
            }
        }

        // save all data to file
        try {
            if (persistenceHandler != null) {
                persistenceHandler.saveAll();
            }
        }
        catch (Exception e) {
            System.err.println("error while saving data: " + e.getMessage());
        }

        // stop all client handler threads of active users
        if (activeClients != null && !activeClients.isEmpty()) {
            for (ClientHandler handler : activeClients.values()) {
                try {
                    handler.stop();
                } 
                catch (Exception e) {
                    System.err.println("error shutting down client: " + e.getMessage());
                }
            }
            activeClients.clear();
        }

        // stop background threads

        // close inactivity handler thread
        if (inactivityHandler != null) {
            inactivityHandler.stop();
        }
        if (inactivityThread != null && inactivityThread.isAlive()) {
            inactivityThread.interrupt();
        }

        // close scheduler for persistence handler
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } 
            catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }

        // close thread pool for client handlers
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } 
        catch (InterruptedException e) {
            pool.shutdownNow();
        }

        // close UDP notifier
        if (udpNotifier != null) {
            udpNotifier.close();
        }

        System.out.println("server closed");
    }

    /**
     * loads server configuration parameters from the properties file
     * 
     * @throws FileNotFoundException exception if file cannot be found
     * @throws IOException exception if an error occurs reading file
     */
    public static void getServerProperties () throws FileNotFoundException, IOException {
        Properties props = new Properties();

        FileInputStream inputFile = new FileInputStream(configFile);
        props.load(inputFile);

        tcpPort = Integer.parseInt(props.getProperty("tcpPort"));
        udpPort = Integer.parseInt(props.getProperty("udpPort"));
        inactivityTimeout = Integer.parseInt(props.getProperty("timeout"));
        scanInterval = Integer.parseInt(props.getProperty("interval"));
        // other properties ...

        inputFile.close();
    }
}
