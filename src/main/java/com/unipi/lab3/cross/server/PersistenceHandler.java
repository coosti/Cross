package com.unipi.lab3.cross.server;

import java.util.*;
import java.io.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import com.unipi.lab3.cross.model.OrderBook;
import com.unipi.lab3.cross.model.user.UserManager;
import com.unipi.lab3.cross.model.trade.Trade;

/**
 * class that manages persistence of users, orders, and trades to JSON files
 * periodically to save current state to avoid data loss
 * and on server shutdown
 */

public class PersistenceHandler {

    // references to data structures
    private OrderBook orderBook;
    private UserManager userManager;
    private LinkedList<Trade> bufferedTrades;

    // json file paths
    private final String usersFile = "src/main/resources/users.json";
    private final String ordersFile = "src/main/resources/orders.json";
    private final String tradesFile = "src/main/resources/storicoOrdini.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public PersistenceHandler (OrderBook orderBook, UserManager userManager, LinkedList<Trade> bufferedTrades) {
        this.orderBook = orderBook;
        this.userManager = userManager;
        this.bufferedTrades = bufferedTrades;
    }

    /**
     * saves all data to respective JSON files calling the specific methods
     */
    public synchronized void saveAll () {
        // debug
        System.out.println("saving data");
        
        saveUsers();
        saveOrders();
        saveTrades();
    }

    /**
     * saves users to JSON file
     */
    private void saveUsers () {
        // try with resources to serialize
        try (FileWriter writer = new FileWriter(usersFile)) {
            // get users map and serialize to JSON, writing to file
            gson.toJson(userManager.getUsers(), writer);
        } catch (IOException e) {
            System.err.println("error saving users: " + e.getMessage());
        }
    }

    /**
     * saves orders to JSON file
     */
    private void saveOrders () {
        try (FileWriter writer = new FileWriter(ordersFile)) {
            // get order book, serialize it to JSON and write it to file
            gson.toJson(orderBook, writer);
        } catch (IOException e) {
            System.err.println("error saving orders: " + e.getMessage());
        }
    }

    /**
     * saves buffered trades to JSON file
     */
    private synchronized void saveTrades () {
        // stop if no trades to save
        if (bufferedTrades.isEmpty()) 
            return;

        File file = new File(tradesFile);

        try {
            JsonObject obj = null;

            // check if file exists and is not empty
            if (file.exists() && file.length() != 0) {
                // try to read existing trades
                try (FileReader reader = new FileReader(file)) {
                    // parse json file into json object
                    obj = gson.fromJson(reader, JsonObject.class);
                }
                // in case of malformed json or io error, create new json array
                catch (JsonSyntaxException e) {
                    System.err.println("malformed json: " + e.getMessage());
                    obj = new JsonObject();
                    obj.add("trades", new JsonArray());
                }
                catch (IOException e) {
                    System.err.println("error reading trades: " + e.getMessage());
                    obj = new JsonObject();
                    obj.add("trades", new JsonArray());
                }
            }
            else {
                // create new json object with trades array if file does not exist or is empty
                obj = new JsonObject();
                obj.add("trades", new JsonArray());
            }

            // get already stored trades array in the file in json object
            JsonArray trades = obj.getAsJsonArray("trades");

            // serialize every buffered trade in json and add to trades array
            for (Trade t : bufferedTrades) {
                trades.add(gson.toJsonTree(t));
            }

            // overwrite the file with updated trades array
            try (FileWriter writer = new FileWriter(file)) {
                // serialize entire json object back to file
                gson.toJson(obj, writer);
            }
            // I/O exception on writing
            catch (IOException e) {
                System.err.println("error re-saving trades: " + e.getMessage());
            }

            // clear buffered trades after successful save
            bufferedTrades.clear();
        }
        // generic exception catch
        catch (Exception e) {
            System.err.println("trades error: " + e.getMessage());
        }
    }
}