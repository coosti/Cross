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

public class OrderBook {

    // map price - list ask limit orders
    // ascending order for keys
    private ConcurrentSkipListMap<Integer, OrderGroup> askOrders;

    private int spread;
    
    // map price - list bid limit orders
    // descending order for keys
    private ConcurrentSkipListMap<Integer, OrderGroup> bidOrders;

    // stop orders
    private ConcurrentLinkedQueue<StopOrder> stopAsks;
    private ConcurrentLinkedQueue<StopOrder> stopBids;

    private transient int bestAskPrice;
    private transient int bestBidPrice;

    private transient static final AtomicInteger idCounter = new AtomicInteger(0);
    private int lastId = 0;

    private transient TradeMap tradeMap;
    private transient LinkedList<Trade> bufferedTrades;

    private transient UdpNotifier udpNotifier;

    private transient boolean update = false;

    public OrderBook () {
        this.askOrders = new ConcurrentSkipListMap<>();
        this.bidOrders = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.spread = -1;
        this.bestAskPrice = 0;
        this.bestBidPrice = 0;
        this.stopAsks = new ConcurrentLinkedQueue<>();
        this.stopBids = new ConcurrentLinkedQueue<>();

        // trade map & trades

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

    public void setAskOrders (ConcurrentSkipListMap<Integer, OrderGroup> askOrders) {
        this.askOrders = askOrders;
        updateBestPrices();
    }

    public ConcurrentSkipListMap<Integer, OrderGroup> getLimitBids () {
        return this.bidOrders;
    }

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

    public int counterOrderId () {
        int newId = idCounter.getAndIncrement();
        // update last used id
        this.lastId = newId;
        return newId;
    }

    public void restoreId () {
        if (this.lastId >= idCounter.get()) {
            idCounter.set(this.lastId + 1);
        }
    }
 
    public int getAsksSize () {
        int totalSize = 0;

        for (OrderGroup group : this.askOrders.values()) {
            totalSize += group.getSize();
        }

        return totalSize;
    }

    public int getBidsSize () {
        int totalSize = 0;

        for (OrderGroup group : this.bidOrders.values()) {
            totalSize += group.getSize();
        }

        return totalSize;
    }

    public int getAvailableSize (String username, String type) {
        int availableSize = 0;

        ConcurrentSkipListMap<Integer, OrderGroup> selectedMap = type.equals("ask") ? this.askOrders : this.bidOrders;

        for (OrderGroup group : selectedMap.values()) {
            availableSize += group.getFilteredSize(username);
        }

        return availableSize;
    }
 
    private synchronized void updateBestPrices () {       
        // update best prices only if the maps aren't empty
        this.bestAskPrice = this.askOrders.isEmpty() ? 0 : this.askOrders.firstKey();
        this.bestBidPrice = this.bidOrders.isEmpty() ? 0 : this.bidOrders.firstKey();

        // both best prices must be valid to have spread
        if (this.bestAskPrice > 0 && this.bestBidPrice > 0) {
            if (this.bestAskPrice < this.bestBidPrice) {
                // negative spread
                this.spread = -1;
            }
            else {
                this.spread = this.bestAskPrice - this.bestBidPrice;
            }
        } 
        else {
            // if one of the prices is 0, spread is invalid
            this.spread = -1;
        }

        // execute stop orders if the map changed, avoiding recursion
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

    public synchronized ConcurrentLinkedQueue<StopOrder> getUserStopOrders (String username) {
        ConcurrentLinkedQueue<StopOrder> userStopOrders = new ConcurrentLinkedQueue<>();

        for (StopOrder order : this.stopAsks) {
            if (order.getUsername().equals(username)) {
                userStopOrders.add(order);
            }
        }

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

    // methods for execute a limit order
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

    // ask order
    public synchronized int execAskOrder (String username, int size, int price) {

        // create new order id
        int orderId = counterOrderId(); // generate unique order id

        int newSize = size;

        // try to match with existing bid orders
        Iterator<Map.Entry<Integer, OrderGroup>> iterator = this.bidOrders.entrySet().iterator();

        // iterate on bid list
        while (iterator.hasNext() && newSize > 0) {
            Map.Entry<Integer, OrderGroup> entry = iterator.next();

            Integer bidPrice = entry.getKey();
            OrderGroup bidGroup = entry.getValue();

            ConcurrentLinkedQueue<LimitOrder> bidLimitOrders = bidGroup.getLimitOrders();

            // check price condition
            if (bidPrice >= price) {
                newSize = matchingAlgorithm(bidGroup, bidLimitOrders, newSize, username);

                if (bidGroup.isEmpty()) {
                    // remove the group if it's empty
                    iterator.remove();
                }

            }
        }

        if (newSize == 0) {
            updateBestPrices();

            // when fully executed, add to trade map
            insertTrade(orderId, "ask", "limit", size, price, LocalDate.now(), username);

            System.out.println("order " + orderId + " fully executed");

            // order fully executed
            return orderId;
        }
        else if (newSize > 0) {
            // order not matched or partially executed
            // add the ask order to the order book

            if (newSize == size) {
                // order not matched
                System.out.println("order " + orderId + " not matched");
            }
            else {
                // order partially executed
                System.out.println("order " + orderId + " partially executed");
            }
            addLimitOrder(orderId, username, "ask", newSize, price);
        }

        return orderId;
    }

    // bid order
    public synchronized int execBidOrder (String username, int size, int price) {
        // create new order id
        int orderId = counterOrderId(); // generate unique order id

        int newSize = size;

        // try to match with existing bid orders

        Iterator<Map.Entry<Integer, OrderGroup>> iterator = this.askOrders.entrySet().iterator();

        // iterate on bid list
        while (iterator.hasNext() && newSize > 0) {
            Map.Entry<Integer, OrderGroup> entry = iterator.next();

            // check price condition
            int askPrice = entry.getKey();
            OrderGroup askGroup = entry.getValue();

            ConcurrentLinkedQueue<LimitOrder> askLimitOrders = askGroup.getLimitOrders();

            if (askPrice <= price) {
                
                newSize = matchingAlgorithm(askGroup, askLimitOrders, newSize, username);

                if (askGroup.isEmpty()) {
                    iterator.remove();
                }        
            }
        }

        if (newSize == 0) {
            updateBestPrices();

            // when fully executed, add to trade map
            insertTrade(orderId, "bid", "limit", size, price, LocalDate.now(), username);

            System.out.println("order " + orderId + " fully executed");

            // order fully executed
            return orderId;
        }
        else if (newSize > 0) {
            // order not matched or partially executed
            // add the ask order to the order book

            if (newSize == size) {
                // order not matched
                System.out.println("order " + orderId + " not matched");
            }
            else {
                // order partially executed
                System.out.println("order " + orderId + " partially executed");
            }

            addLimitOrder(orderId, username, "bid", newSize, price);
        }

        return orderId;
    }

    public synchronized int matchingAlgorithm (OrderGroup group, ConcurrentLinkedQueue<LimitOrder> orders, int size, String username) {

        // check in the order group
        Iterator<LimitOrder> iterator = orders.iterator();

        while (iterator.hasNext()) {
            // check for every order in the list
            LimitOrder order = iterator.next();

            if (!order.getUsername().equals(username)) {
                int orderSize = order.getSize();
                int orderPrice = order.getLimitPrice();

                if (orderSize < size) {
                    // order partially executed but opposite order executed
                    size -= orderSize;

                    // add the opposite order to the trade map
                    insertTrade(order.getOrderId(), order.getType(), "limit", orderSize, orderPrice, LocalDate.now(), order.getUsername());

                    System.out.println("order " + order.getOrderId() + " fully executed");

                    // remove the opposite order from the group
                    iterator.remove();
                            
                    // update the group size e total
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

                    return 0;
                }
                else if (orderSize > size) {
                    // opposite order partially executed
                    order.setSize(orderSize - size);
                    
                    group.updateGroup(size, orderPrice);

                    System.out.println("order " + order.getOrderId() + " partially executed");

                    // order fully executed
                    return 0;
                }
            }
        }

        return size;
    }

    // method to add a limit order
    public synchronized void addLimitOrder (int orderId, String username, String type, int size, int price) {
        // create a new LimitOrder object
        LimitOrder order = new LimitOrder(orderId, username, type, size, price);

        // select the right map
        ConcurrentSkipListMap<Integer, OrderGroup> selectedMap = type.equals("ask") ? this.askOrders : this.bidOrders;

        if (selectedMap.containsKey(price)) {
            // if the price already exists, add the order to the OrderGroup
            OrderGroup group = selectedMap.get(price);

            group.addOrder(order);
        } 
        else {
            // if the price doesn't exist, create a new OrderGroup
            OrderGroup newGroup = new OrderGroup();

            newGroup.addOrder(order);

            selectedMap.put(price, newGroup);
        }

        // update best prices + spread
        updateBestPrices();

        // debug
        printOrderBook();
    }

    // method to add a stop order
    public synchronized int addStopOrder (String username, int size, int price, String type) {
        // create new order id
        int orderId = counterOrderId(); // generate unique order id

        // create a new StopOrder object
        StopOrder order = new StopOrder(orderId, username, type, size, price);


        if ((type.equals("ask") && !this.bidOrders.isEmpty() && this.bestBidPrice <= price) || 
            (type.equals("bid") && !this.askOrders.isEmpty() && this.bestAskPrice >= price)) {
            // stop order is immediately executable
            execStopOrder(size, price, type, "stop", username, orderId);
        }
        else {
            // add the stop order to the stop orders queue
            ConcurrentLinkedQueue<StopOrder> selectedQueue = type.equals("ask") ? this.stopAsks : this.stopBids;
            selectedQueue.add(order);
        }

        // debug
        printOrderBook();

        return orderId;
    }

    public synchronized void execStopOrder (int size, int price, String type, String orderType, String username, int orderId) {
        int result = execMarketOrder (size, type, "stop", username, orderId);

        if (result == orderId) {
            System.out.println("stop order " + orderId + " executed");

            insertTrade(orderId, type, "stop", size, price, LocalDate.now(), username);
        }
        else {
            System.out.println("error! stop order " + orderId + " not executed");
        }
    }

    // periodic check to match stop orders, executed when spread and best prices change
    public synchronized void execStopOrders () {
        // check stop asks with bid map
        // bestAskPrice is the lowest ask price
        // stopAsks active when bestBidPrice <= stopAskPrice

        Iterator<StopOrder> askIterator = this.stopAsks.iterator();

        while (askIterator.hasNext()) {
            StopOrder order = askIterator.next();
            int stopPrice = order.getStopPrice();

            if (!this.bidOrders.isEmpty() && this.bestBidPrice <= stopPrice) {
                // activate the stop order -> execute it as a market order

                int result = execMarketOrder (order.getSize(), "ask", "stop", order.getUsername(), order.getOrderId());

                if (result == order.getOrderId()) {
                    // order executed successfully
                    System.out.println("stop order " + order.getOrderId() + " executed");

                    // when fully executed, add to trade map
                    insertTrade(order.getOrderId(), "ask", "stop", order.getSize(), order.getStopPrice(), LocalDate.now(), order.getUsername());

                    // remove stop order from the queue
                    askIterator.remove();
                }
                else {
                    // order not executed
                    System.out.println("error! stop order " + order.getOrderId() + " not executed");

                    askIterator.remove();
                }
            }
        }

        // check stop bids with ask map

        Iterator<StopOrder> bidIterator = this.stopBids.iterator();

        while (bidIterator.hasNext()) {
            StopOrder order = bidIterator.next();
            int stopPrice = order.getStopPrice();

            if (!this.askOrders.isEmpty() && this.bestAskPrice >= stopPrice) {

                int result = execMarketOrder (order.getSize(), "bid", "stop", order.getUsername(), order.getOrderId());

                if (result == order.getOrderId()) {
                    // order executed successfully
                    System.out.println("stop order " + order.getOrderId() + " executed");

                    // when fully executed, add to trade map
                    insertTrade(order.getOrderId(), "bid", "stop", order.getSize(), order.getStopPrice(), LocalDate.now(), order.getUsername());

                    bidIterator.remove(); // remove stop order from the queue

                }
                else {
                    // order not executed
                    System.out.println("error! stop order " + order.getOrderId() + " not executed");

                    bidIterator.remove(); // remove stop order from the queue
                }

            }
        }

    }

    public synchronized int execMarketOrder (int size, String type, String orderType, String username, int id) {

        int orderId = 0;
        
        if (orderType.equals("stop")) {
            orderId = id;
        }
        else if (orderType.equals("market")) {
            orderId = counterOrderId(); // generate unique order id
        }

        String oppositeType = type.equals("ask") ? "bid" : "ask";

        ConcurrentSkipListMap<Integer, OrderGroup> selectedMap = type.equals("ask") ? this.bidOrders : this.askOrders;

        // check if the map is empty
        if (selectedMap.isEmpty()) {
            // failed order
            return -1;
        }

        // check if there's enough size to execute the order
        if (this.getAvailableSize(username, oppositeType) >= size) {
            // select the right map based on the order type

            int newSize = size;

            Iterator<Map.Entry<Integer, OrderGroup>> iterator = selectedMap.entrySet().iterator();

            while (iterator.hasNext() && newSize > 0) {
                Map.Entry<Integer, OrderGroup> entry = iterator.next();

                OrderGroup group = entry.getValue();

                ConcurrentLinkedQueue<LimitOrder> orders = group.getLimitOrders();

                newSize = matchingAlgorithm(group, orders, newSize, username);
                
                if (group.isEmpty()) {
                    // remove the group if it's empty
                    iterator.remove();
                }
            }

            updateBestPrices();

            if (orderType.equals("market")) {
                System.out.println("market order " + orderId + " fully executed");

                // when fully executed, add to trade map
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

    // method for removing an order
    public synchronized int cancelOrder (int orderId, String username) {

        // check if the order is in the ask map
        Iterator<OrderGroup> askIterator = this.askOrders.values().iterator();

        while (askIterator.hasNext()) {
            OrderGroup group = askIterator.next();

            if (group.removeOrder(orderId, username)) {

                if (group.isEmpty()) {
                    askIterator.remove();
                }

                updateBestPrices();

                System.out.println("ask order " + orderId + " removed");

                // if the order has been removed successfully, return 100
                return 100;
            }
        }

        // check if the order is in the bid map
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

        // check if the order is in the stop ask queue
        Iterator<StopOrder> stopAskIterator = this.stopAsks.iterator();
        
        while (stopAskIterator.hasNext()) {
            StopOrder stopOrder = stopAskIterator.next();

            if (stopOrder.getOrderId() == orderId && stopOrder.getUsername().equals(username)) {
                // remove the stop order
                stopAskIterator.remove();

                updateBestPrices();

                System.out.println("stop order " + orderId + " removed");

                return 100; // success
            }
        }

        // check if the order is in the stop bid queue
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

        // error -> order not found or not removed
        return 101;
    }

    public synchronized void insertTrade (int tradeID, String type, String orderType, int size, int price, LocalDate date, String username) {
        Trade trade;

        if (price == 0)
            trade = new Trade(tradeID, type, orderType, size, username);
        else   
            trade = new Trade(tradeID, type, orderType, size, price, username);

        this.tradeMap.addTrade(date, trade);

        LinkedList<Trade> trades = new LinkedList<>();
        trades.add(trade);

        // also add to the buffered trades
        this.bufferedTrades.add(trade);

        this.udpNotifier.notifyClient(username, new Notification(trades));
    }

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