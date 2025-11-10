package com.unipi.lab3.cross.model;

import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import java.util.concurrent.atomic.AtomicInteger;

import com.unipi.lab3.cross.model.orders.*;
import com.unipi.lab3.cross.model.trade.*;
import com.unipi.lab3.cross.server.UdpNotifier;
import com.unipi.lab3.cross.json.response.Notification;

/**
    class representing the order book
    containing map for limit orders and queues for stop orders
*/

public class OrderBook {

    // map price - list ask limit orders
    // ascending order for keys
    private ConcurrentSkipListMap<Integer, OrderGroup> askOrders;

    private int spread;
    
    // map price - list bid limit orders
    // descending order for keys
    private ConcurrentSkipListMap<Integer, OrderGroup> bidOrders;

    // stop orders queues
    private ConcurrentLinkedQueue<StopOrder> stopAsks;
    private ConcurrentLinkedQueue<StopOrder> stopBids;

    // best prices -> not included in json file
    private transient int bestAskPrice;
    private transient int bestBidPrice;

    // counter for unique order ids
    private transient static final AtomicInteger idCounter = new AtomicInteger(0);
    private int lastId = 0;

    // map date - list of trades executed on that date
    private transient TradeMap tradeMap;
    private transient LinkedList<Trade> bufferedTrades;

    private transient UdpNotifier udpNotifier;

    // flag to avoid recursive calls when updating best prices
    private transient boolean update = false;

    private transient final String NOTIFICATION_SUCCESS = "closedTrades";
    private transient final String NOTIFICATION_ERROR = "orderFailed";

    // constructors

    public OrderBook () {
        this.askOrders = new ConcurrentSkipListMap<>();
        this.bidOrders = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.spread = -1;
        this.bestAskPrice = 0;
        this.bestBidPrice = 0;
        this.stopAsks = new ConcurrentLinkedQueue<>();
        this.stopBids = new ConcurrentLinkedQueue<>();

        this.tradeMap = new TradeMap();
        this.bufferedTrades = new LinkedList<>();

        this.udpNotifier = null;
    }

    public OrderBook (ConcurrentSkipListMap<Integer, OrderGroup> askOrders, ConcurrentSkipListMap<Integer, OrderGroup> bidOrders, ConcurrentLinkedQueue<StopOrder> stopAsks, ConcurrentLinkedQueue<StopOrder> stopBids, UdpNotifier notifier, TradeMap tradeMap) {
        this.askOrders = askOrders;
        this.bidOrders = bidOrders;
        this.stopAsks = stopAsks;
        this.stopBids = stopBids;

        this.tradeMap = tradeMap;
        this.bufferedTrades = new LinkedList<>();
        
        this.udpNotifier = notifier;

        updateBestPrices();
    }

    public ConcurrentSkipListMap<Integer, OrderGroup> getLimitAsks () {
        return this.askOrders;
    }

    // setter for ask orders map
    public void setAskOrders (ConcurrentSkipListMap<Integer, OrderGroup> askOrders) {
        this.askOrders = askOrders;
        // update prices after setting the map
        updateBestPrices();
    }

    public ConcurrentSkipListMap<Integer, OrderGroup> getLimitBids () {
        return this.bidOrders;
    }

    // setter for bid orders map
    public void setBidOrders (ConcurrentSkipListMap<Integer, OrderGroup> bidOrders) {
        this.bidOrders = bidOrders;
        updateBestPrices();
    }

    public int getSpread () {
        return this.spread;
    }

    public ConcurrentLinkedQueue<StopOrder> getStopAsks () {
        return this.stopAsks;
    }

    public void setStopAsks (ConcurrentLinkedQueue<StopOrder> stopAsks) {
        this.stopAsks = stopAsks;
    }

    public ConcurrentLinkedQueue<StopOrder> getStopBids () {
        return this.stopBids;
    }

    public void setStopBids (ConcurrentLinkedQueue<StopOrder> stopBids) {
        this.stopBids = stopBids;
    }

    public OrderBook getOrderBook () {
        return this;
    }

    public int getBestAskPrice () {
        return this.bestAskPrice;
    }

    public int getBestBidPrice () {
        return this.bestBidPrice;
    }

    public int getLastId () {
        return this.lastId;
    }

    public void setLastId (int lastId) {
        this.lastId = lastId;
    }

    // generate unique order id using an incremental counter
    public int counterOrderId () {
        int newId = idCounter.getAndIncrement();
        // update last used id
        this.lastId = newId;
        return newId;
    }

    // restore id counter after loading order book from json
    public void restoreId () {
        if (this.lastId >= idCounter.get()) {
            idCounter.set(this.lastId + 1);
        }
    }
 
    // getter for total size of asks
    public int getAsksSize () {
        int totalSize = 0;

        for (OrderGroup group : this.askOrders.values()) {
            totalSize += group.getSize();
        }

        return totalSize;
    }

    // getter for total size of bids
    public int getBidsSize () {
        int totalSize = 0;

        for (OrderGroup group : this.bidOrders.values()) {
            totalSize += group.getSize();
        }

        return totalSize;
    }

    /**
     * getter for total size of all orders of the given type for a specific user
     * 
     * @param username username of the user
     * @param type type of orders to consider ("ask" or "bid")
    */
    public int getAvailableSize (String username, String type) {
        int availableSize = 0;

        // select the correct map
        ConcurrentSkipListMap<Integer, OrderGroup> selectedMap = type.equals("ask") ? this.askOrders : this.bidOrders;

        // for every order group, pick and sum the size of all user's orders in it
        for (OrderGroup group : selectedMap.values()) {
            availableSize += group.getFilteredSize(username);
        }

        return availableSize;
    }
 
    /**
     * updates the best ask and bid prices in the order book
     * recalculates the spread
     * 
     * triggers stop order execution if the book state has changed,
     * while preventing recursive updates.
    */
    private synchronized void updateBestPrices () {       
        // update best prices only if maps aren't empty
        this.bestAskPrice = this.askOrders.isEmpty() ? 0 : this.askOrders.firstKey();
        this.bestBidPrice = this.bidOrders.isEmpty() ? 0 : this.bidOrders.firstKey();

        // both best prices must be valid to calculate spread
        if (this.bestAskPrice > 0 && this.bestBidPrice > 0) {
            if (this.bestAskPrice < this.bestBidPrice) {
                // invalid spread
                this.spread = -1;
            }
            else {
                // valid spread -> difference between best ask price and best bid price
                this.spread = this.bestAskPrice - this.bestBidPrice;
            }
        } 
        else {
            // if one of the prices is 0, spread is invalid
            this.spread = -1;
        }
        
        // update stop orders
        if (!update) {
            update = true;
            try {
                execStopOrders();
            }
            finally {
                update = false;
            }
        }
    }

    /**
     * retrieves all stop orders placed by a specific user
     * 
     * @param username username of the user whose stop orders should be retrieved
     * @return a queue with all stop orders of the given user
     */ 
    public synchronized ConcurrentLinkedQueue<StopOrder> getUserStopOrders (String username) {
        ConcurrentLinkedQueue<StopOrder> userStopOrders = new ConcurrentLinkedQueue<>();

        // retrieve all ask stop orders of the user from ask queue
        for (StopOrder order : this.stopAsks) {
            if (order.getUsername().equals(username)) {
                userStopOrders.add(order);
            }
        }

        // retrieve all bid stop orders of the user from bid queue
        for (StopOrder order : this.stopBids) {
            if (order.getUsername().equals(username)) {
                userStopOrders.add(order);
            }
        }

        return userStopOrders;
    }

    public void setTradeMap (TradeMap tradeMap) {
        this.tradeMap = tradeMap;
    }

    public void setBufferedTrades (LinkedList<Trade> trades) {
        this.bufferedTrades = trades;
    }

    public void setUdpNotifier (UdpNotifier notifier) {
        this.udpNotifier = notifier;
    }

    /**
     * calls the method to execute a limit order
     * based on its type (ask or bid)
     * 
     * @param username username of the user placing the order
     * @param type type of the limit order placed
     * @param size size of the limit order
     * @param price price of the limit order
     * @return the unique order ID assigned to the limit order
     */
    public synchronized int execLimitOrder (String username, String type, int size, int price) {

        // check the type of the order
        if (type.equals("ask")) {
            return execAskOrder(username, size, price);
        }
        else if (type.equals("bid")) {
            return execBidOrder(username, size, price);
        }

        return -1;
    }

    /**
     * executes a new ask (sell) order, trying to match it with existing bid orders
     * 
     * @param username username of the user placing the order
     * @param size size of the ask order
     * @param price price of the ask order
     * @return the order ID assigned to the ask order
    */
    public synchronized int execAskOrder (String username, int size, int price) {

        // generate a new unique order id
        int orderId = counterOrderId();

        int newSize = size;

        // iterator for bid orders map
        Iterator<Map.Entry<Integer, OrderGroup>> iterator = this.bidOrders.entrySet().iterator();

        while (iterator.hasNext() && newSize > 0) {
            Map.Entry<Integer, OrderGroup> entry = iterator.next();

            Integer bidPrice = entry.getKey();
            OrderGroup bidGroup = entry.getValue();

            ConcurrentLinkedQueue<LimitOrder> bidLimitOrders = bidGroup.getLimitOrders();

            // check price condition
            // matching occurs when a bid price is greater than or equal to the ask price
            if (bidPrice >= price) {
                // execute matching algorithm
                newSize = matchingAlgorithm(bidGroup, bidLimitOrders, newSize, username);

                // remove empty bid group after execution
                if (bidGroup.isEmpty()) {
                    iterator.remove();
                }

            }
        }

        // order fully executed
        if (newSize == 0) {
            updateBestPrices();

            // add the ask order to trade map
            insertTrade(orderId, "ask", "limit", size, price, LocalDate.now(), username);

            System.out.println("order " + orderId + " fully executed");

            // return order id
            return orderId;
        }
        // order partially executed or not matched
        else if (newSize > 0) {
            // add the ask order to the order book

            if (newSize == size) {
                // order not matched
                System.out.println("order " + orderId + " not matched");
            }
            else {
                // order partially executed
                System.out.println("order " + orderId + " partially executed");
            }
            // add remaining size as ask limit order to the order book
            addLimitOrder(orderId, username, "ask", newSize, price);
        }

        return orderId;
    }

    /**
     * executes a new bid (buy) order, trying to match it with existing ask orders
     * 
     * @param username username of the user placing the order
     * @param size size of the bid order
     * @param price price of the bid order
     * @return the order ID assigned to the bid order
     */
    public synchronized int execBidOrder (String username, int size, int price) {
        // generate a new unique order id
        int orderId = counterOrderId();

        // keep the remaining size of the order after matching
        int newSize = size;

        // iterator for ask orders map
        Iterator<Map.Entry<Integer, OrderGroup>> iterator = this.askOrders.entrySet().iterator();

        while (iterator.hasNext() && newSize > 0) {
            Map.Entry<Integer, OrderGroup> entry = iterator.next();

            int askPrice = entry.getKey();
            OrderGroup askGroup = entry.getValue();

            ConcurrentLinkedQueue<LimitOrder> askLimitOrders = askGroup.getLimitOrders();

            // check price condition
            // matching occurs when a bid price is greater than or equal to the ask price
            if (askPrice <= price) {
                
                newSize = matchingAlgorithm(askGroup, askLimitOrders, newSize, username);

                if (askGroup.isEmpty()) {
                    iterator.remove();
                }        
            }
        }

        // order fully executed
        if (newSize == 0) {
            updateBestPrices();

            // add to trade map
            insertTrade(orderId, "bid", "limit", size, price, LocalDate.now(), username);

            System.out.println("order " + orderId + " fully executed");

            return orderId;
        }
        // order not matched or partially executed
        else if (newSize > 0) {
            // add the ask order to the order book

            if (newSize == size) {
                // order not matched
                System.out.println("order " + orderId + " not matched");
            }
            else {
                // order partially executed
                System.out.println("order " + orderId + " partially executed");
            }

            // add remaining size as bid limit order to the order book
            addLimitOrder(orderId, username, "bid", newSize, price);
        }

        return orderId;
    }

    /**
     * matching algorithm used to execute the order against an order group
     * iterates through existing limit orders in a group and executes them
     * against the incoming order until it is fully or partially filled
     * 
     * the algorithm is used for limit, stop and market orders
     * 
     * @param group price group of orders to check
     * @param orders orders queue of the group
     * @param size size of the placed order
     * @param username username of the user placing the order
     * @return remaining size of the placed order after matching
     */
    public synchronized int matchingAlgorithm (OrderGroup group, ConcurrentLinkedQueue<LimitOrder> orders, int size, String username) {

        // check in the order group
        Iterator<LimitOrder> iterator = orders.iterator();

        while (iterator.hasNext()) {
            // check for every order in the list
            LimitOrder order = iterator.next();

            // avoid orders placed by the user to avoid self trading
            if (!order.getUsername().equals(username)) {
                int orderSize = order.getSize();
                int orderPrice = order.getLimitPrice();

                if (orderSize < size) {
                    // order partially executed but opposite order executed
                    size -= orderSize;

                    // add the executed order to the trade map
                    insertTrade(order.getOrderId(), order.getType(), "limit", orderSize, orderPrice, LocalDate.now(), order.getUsername());

                    System.out.println("order " + order.getOrderId() + " fully executed");

                    // remove the executed order from the group
                    iterator.remove();
                            
                    // update group parameters
                    group.updateGroup(orderSize, orderPrice);
                }
                else if (orderSize == size) {
                    // both orders are fully executed

                    // add the opposite order to the trade map
                    insertTrade(order.getOrderId(), order.getType(), "limit", orderSize, orderPrice, LocalDate.now(), order.getUsername());

                    System.out.println("order " + order.getOrderId() + " fully executed");

                    // remove the opposite order from the group
                    iterator.remove();

                    group.updateGroup(orderSize, orderPrice);

                    // current order fully executed
                    return 0;
                }
                else if (orderSize > size) {
                    // opposite order partially executed
                    order.setSize(orderSize - size);
                    
                    // update group
                    group.updateGroup(size, orderPrice);

                    System.out.println("order " + order.getOrderId() + " partially executed");

                    // current order fully executed
                    return 0;
                }
            }
        }

        // current order partially executed -> return remaining size
        return size;
    }

    /**
     * adds new limit order to the order book
     * 
     * @param orderId id of the limit order
     * @param username username of the user that placed the order
     * @param type type of the limit order ("ask" or "bid")
     * @param size size of the limit order
     * @param price price of the limit order
     */
    public synchronized void addLimitOrder (int orderId, String username, String type, int size, int price) {
        // create a new limit order instance
        LimitOrder order = new LimitOrder(orderId, username, type, size, price);

        // select the right map
        ConcurrentSkipListMap<Integer, OrderGroup> selectedMap = type.equals("ask") ? this.askOrders : this.bidOrders;

        // check if exists a group for the order price
        if (selectedMap.containsKey(price)) {
            // group for the given price already exists -> add the order to the ìexisting group
            OrderGroup group = selectedMap.get(price);

            group.addOrder(order);
        } 
        else {
            // group for that price doesn't exist -> create a new group
            OrderGroup newGroup = new OrderGroup();

            // add the order to the list of the new group
            newGroup.addOrder(order);

            selectedMap.put(price, newGroup);
        }

        // update best prices and spread
        updateBestPrices();

        // debug
        printOrderBook();
    }

    /**
     * adds new stop order to the order book
     * 
     * @param username username of the user placing the stop order
     * @param size size of the stop order
     * @param price stop price of the stop order
     * @param type type of the stop order ("ask" or "bid")
     * @return unique order ID assigned to the stop order
     */
    public synchronized int addStopOrder (String username, int size, int price, String type) {
        // generate unique new order id
        int orderId = counterOrderId();

        // create a new stop order instance
        StopOrder order = new StopOrder(orderId, username, type, size, price);

        // check if the stop order is immediately executable
        if ((type.equals("ask") && !this.bidOrders.isEmpty() && this.bestBidPrice <= price) || 
            (type.equals("bid") && !this.askOrders.isEmpty() && this.bestAskPrice >= price)) {
            execStopOrder(size, price, type, "stop", username, orderId);
        }
        // if not executable, add it to stop orders queue
        else {
            ConcurrentLinkedQueue<StopOrder> selectedQueue = type.equals("ask") ? this.stopAsks : this.stopBids;
            selectedQueue.add(order);
        }

        // debug
        printOrderBook();

        return orderId;
    }

    /**
     * executes a stop order
     * if the stop price condition is met, the stop order is executed as a market order
     * and then added to the trade map
     * otherwise print an error message
     * 
     * @param size size of the stop order
     * @param price stop price of the stop order
     * @param type type of the stop order ("ask" or "bid")
     * @param orderType type of the order ("stop" in this case)
     * @param username username of the user placing the stop order
     * @param orderId id of the stop order
     */
    public synchronized void execStopOrder (int size, int price, String type, String orderType, String username, int orderId) {
        // execute as market order
        int result = execMarketOrder (size, type, "stop", username, orderId);

        // if successfully executed, add it to trade map
        if (result == orderId) {
            System.out.println("stop order " + orderId + " executed");

            insertTrade(orderId, type, "stop", size, price, LocalDate.now(), username);
        }
        else {
            System.out.println("error! stop order " + orderId + " not executed");
        }
    }

    // periodic check to match stop orders, executed when spread and best prices change

    /**
     * checks if any stop orders can be executed
     * executes them as market orders if trigger condition is met
     */
    public synchronized void execStopOrders () {

        // check stop asks with bid map
        Iterator<StopOrder> askIterator = this.stopAsks.iterator();

        while (askIterator.hasNext()) {
            StopOrder order = askIterator.next();
            int stopPrice = order.getStopPrice();

            // check price condition
            // best bid price is below or equal to stop price
            if (!this.bidOrders.isEmpty() && this.bestBidPrice <= stopPrice) {

                int result = execMarketOrder (order.getSize(), "ask", "stop", order.getUsername(), order.getOrderId());

                // if successfully executed, add it to trade map
                if (result == order.getOrderId()) {

                    System.out.println("stop order " + order.getOrderId() + " executed");

                    insertTrade(order.getOrderId(), "ask", "stop", order.getSize(), order.getStopPrice(), LocalDate.now(), order.getUsername());

                    // remove stop order from the queue
                    askIterator.remove();
                }
                else {
                    // execution failed
                    System.out.println("error! stop order " + order.getOrderId() + " not executed");

                    LinkedList<Trade> failedOrders = new LinkedList<>();
                    failedOrders.add(new Trade(order.getOrderId(), order.getType(), "stop", order.getSize(), order.getStopPrice(), order.getUsername()));

                    this.udpNotifier.notifyClient(order.getUsername(), new Notification(NOTIFICATION_ERROR, failedOrders));

                    // remove the failed order from the queue
                    askIterator.remove();
                }
            }
        }

        // check stop bids with ask map
        Iterator<StopOrder> bidIterator = this.stopBids.iterator();

        while (bidIterator.hasNext()) {
            StopOrder order = bidIterator.next();
            int stopPrice = order.getStopPrice();

            // check price condition
            // best ask price is above or equal to stop price
            if (!this.askOrders.isEmpty() && this.bestAskPrice >= stopPrice) {

                int result = execMarketOrder (order.getSize(), "bid", "stop", order.getUsername(), order.getOrderId());

                // if successfully executed, add it to trade map
                if (result == order.getOrderId()) {

                    System.out.println("stop order " + order.getOrderId() + " executed");

                    insertTrade(order.getOrderId(), "bid", "stop", order.getSize(), order.getStopPrice(), LocalDate.now(), order.getUsername());

                    // remove stop order from the queue
                    bidIterator.remove();

                }
                else {
                    // execution failed
                    System.out.println("error! stop order " + order.getOrderId() + " not executed");
                    
                    LinkedList<Trade> failedOrders = new LinkedList<>();
                    failedOrders.add(new Trade(order.getOrderId(), order.getType(), "stop", order.getSize(), order.getStopPrice(), order.getUsername()));

                    this.udpNotifier.notifyClient(order.getUsername(), new Notification(NOTIFICATION_ERROR, failedOrders));

                    bidIterator.remove();
                }

            }
        }
    }

    /**
     * executes a market order
     * tries to match it with existing limit orders in the order book
     * 
     * @param size size of the market order
     * @param type type of the market order ("ask" or "bid")
     * @param orderType type of the order ("market" or "stop")
     * @param username username of the user placing the market order
     * @param id id of the order (used for stop orders)
     * @return order ID if the market order is fully executed, -1 otherwise
     */
    public synchronized int execMarketOrder (int size, String type, String orderType, String username, int id) {

        int orderId = 0;
        
        // determine order id based on order type
        // stop orders keep their original id
        if (orderType.equals("stop")) {
            orderId = id;
        }
        // market orders get a new unique id
        else if (orderType.equals("market")) {
            orderId = counterOrderId();
        }

        // select the right map
        String oppositeType = type.equals("ask") ? "bid" : "ask";
        ConcurrentSkipListMap<Integer, OrderGroup> selectedMap = type.equals("ask") ? this.bidOrders : this.askOrders;

        // if the opposite type map is empty, market order fails immediately
        if (selectedMap.isEmpty()) {
            // execution failed
            return -1;
        }

        // check if there's enough available size to execute the order
        if (this.getAvailableSize(username, oppositeType) >= size) {

            int newSize = size;

            Iterator<Map.Entry<Integer, OrderGroup>> iterator = selectedMap.entrySet().iterator();

            // iterate through the opposite type map until order is fully executed
            while (iterator.hasNext() && newSize > 0) {
                Map.Entry<Integer, OrderGroup> entry = iterator.next();

                OrderGroup group = entry.getValue();

                ConcurrentLinkedQueue<LimitOrder> orders = group.getLimitOrders();

                newSize = matchingAlgorithm(group, orders, newSize, username);
                
                // remove the group if it's empty
                if (group.isEmpty()) {
                    iterator.remove();
                }
            }

            // update best prices and spread after execution
            updateBestPrices();

            // add executed market order to trade map
            if (orderType.equals("market")) {
                System.out.println("market order " + orderId + " fully executed");

                if (type.equals("ask"))
                    insertTrade(orderId, "ask", "market", size, 0, LocalDate.now(), username); 
                else
                    insertTrade(orderId, "bid", "market", size, 0, LocalDate.now(), username);
            }

            return orderId;
        }

        if (orderType.equals("market"))
            System.out.println("market order " + orderId + " failed");

        // failed order
        return -1;
    }


    /**
     * cancels an existing order (limit or stop) from the order book
     * searching for the given id within all order maps and queues
     * 
     * @param orderId id of the order to remove
     * @param username username of the user trying to cancel the order
     * @return 100 if the order was successfully removed, 101 otherwise
     */
    public synchronized int cancelOrder (int orderId, String username) {

        // check ask map
        Iterator<OrderGroup> askIterator = this.askOrders.values().iterator();

        while (askIterator.hasNext()) {
            OrderGroup group = askIterator.next();

            // remove the order from the group
            if (group.removeOrder(orderId, username)) {

                // if the group is empty after removal, remove it from the map
                if (group.isEmpty()) {
                    askIterator.remove();
                }

                updateBestPrices();

                System.out.println("ask order " + orderId + " removed");

                // order successfully removed
                return 100;
            }
        }

        // check bid map
        Iterator<OrderGroup> bidIterator = this.bidOrders.values().iterator();

        while (bidIterator.hasNext()) {
            OrderGroup group = bidIterator.next();

            if (group.removeOrder(orderId, username)) {

                if (group.isEmpty()) {
                    bidIterator.remove();
                }

                updateBestPrices();

                System.out.println("bid order " + orderId + " removed");

                return 100;
            }
        }

        // check stop ask queue
        Iterator<StopOrder> stopAskIterator = this.stopAsks.iterator();
        
        while (stopAskIterator.hasNext()) {
            StopOrder stopOrder = stopAskIterator.next();

            if (stopOrder.getOrderId() == orderId && stopOrder.getUsername().equals(username)) {
                
                stopAskIterator.remove();

                updateBestPrices();

                System.out.println("stop order " + orderId + " removed");

                return 100;
            }
        }

        // check stop bid queue
        Iterator<StopOrder> stopBidIterator = this.stopBids.iterator();
        while (stopBidIterator.hasNext()) {
            StopOrder stopOrder = stopBidIterator.next();

            if (stopOrder.getOrderId() == orderId && stopOrder.getUsername().equals(username)) {
                stopBidIterator.remove();

                updateBestPrices();

                System.out.println("stop order " + orderId + " removed");

                return 100;
            }
        }

        // order not found or not removed
        return 101;
    }

    /**
     * inserts a trade into the trade map and notifies the user via UDP
     * 
     * @param tradeID
     * @param type
     * @param orderType
     * @param size
     * @param price
     * @param date
     * @param username
     */
    
    public synchronized void insertTrade (int tradeID, String type, String orderType, int size, int price, LocalDate date, String username) {
        Trade trade;

        if (price == 0)
            // market trades with no price
            trade = new Trade(tradeID, type, orderType, size, username);
        else   
            trade = new Trade(tradeID, type, orderType, size, price, username);

        // add trade to trade map
        this.tradeMap.addTrade(date.toString(), trade);

        // create a temporary list for the trade for notification
        LinkedList<Trade> trades = new LinkedList<>();
        trades.add(trade);

        // store the trade to the buffered trades queue for perstistence
        this.bufferedTrades.add(trade);        

        // notify the user via UDP
        this.udpNotifier.notifyClient(username, new Notification(NOTIFICATION_SUCCESS, trades));
    }

    /**
     * prints the current state of the order book
     */
    public synchronized void printOrderBook () {
        System.out.println("\n=======================================================");
        System.out.println("                     ORDER BOOK                        ");
        System.out.println("=======================================================\n");
            
        if (this.askOrders.isEmpty() && this.bidOrders.isEmpty() && this.stopAsks.isEmpty() && this.stopBids.isEmpty()) {
            System.out.println("                   Empty order book!                   ");
            System.out.println("=======================================================\n");
            return;
        }

        if (this.askOrders.isEmpty()) {
            System.out.println("No ask orders");
        }
        else {
            System.out.println("ASKS:");
            System.out.printf("%-15s %-15s %-15s%n", "Price (USD)", "Size (BTC)", "Total");
            System.out.println("-------------------------------------------------------");
            for (ConcurrentSkipListMap.Entry<Integer, OrderGroup> entry : this.askOrders.entrySet()) {
                int price = entry.getKey();
                OrderGroup orderGroup = entry.getValue();
                
                System.out.printf("%-15d %-15d %-15d%n", price, orderGroup.getSize(), orderGroup.getTotal());
            }
        }
            
        System.out.println("-------------------------------------------------------");
        if (this.spread >= 0)
            System.out.printf("%-10s %s%n", "SPREAD: ", + this.spread);
        else
            System.out.println("invalid spread");

        System.out.println("-------------------------------------------------------");

        if (this.bidOrders.isEmpty()) {
            System.out.println("No bid orders");
        }
        else {
            System.out.println("BIDS:");
            System.out.printf("%-15s %-15s %-15s%n", "Price (USD)", "Size (BTC)", "Total");
            System.out.println("-------------------------------------------------------");
            for (ConcurrentSkipListMap.Entry<Integer, OrderGroup> entry : this.bidOrders.entrySet()) {
                int price = entry.getKey();
                OrderGroup orderGroup = entry.getValue();
                
                System.out.printf("%-15d %-15d %-15d%n", price, orderGroup.getSize(), orderGroup.getTotal());
            }
        }
        
        System.out.println("-------------------------------------------------------");

        if (this.stopAsks.isEmpty() && this.stopBids.isEmpty()) {
            System.out.println("No stop orders");
            System.out.println("-------------------------------------------------------\n");
            return;
        }
        else {
            System.out.println("STOP ORDERS:");
        }

        if (this.stopAsks.isEmpty()) {
            System.out.println("No stop ask orders");
        }
        else {
            System.out.println("STOP ASKS:");
            System.out.printf("%-15s %-15s%n", "Price (USD)", "Size (BTC)");
            System.out.println("-------------------------------------------------------");
            for (StopOrder order : this.stopAsks) {
                System.out.printf("%-15d %-15d%n", order.getStopPrice(), order.getSize());
            }
        }

        if (this.stopBids.isEmpty()) {
            System.out.println("No stop bid orders");
        }
        else {
            System.out.println("STOP BIDS:");
            System.out.printf("%-15s %-15s%n", "Price (USD)", "Size (BTC)");
            System.out.println("-------------------------------------------------------");
            for (StopOrder order : this.stopBids) {
                System.out.printf("%-15d %-15d%n", order.getStopPrice(), order.getSize());
            }
        }

        System.out.println("-------------------------------------------------------\n");
    }
}