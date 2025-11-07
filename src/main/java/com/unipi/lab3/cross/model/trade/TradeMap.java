package com.unipi.lab3.cross.model.trade;

import java.util.TreeMap;
import java.time.LocalDate;
import java.util.*;

/**
 * class representing a map of trades organized by date
 * keeps trades in a tree map with local date as key and a linked list of trades as value
*/

public class TradeMap {
    
    // map date - list of trades on that date
    // in each list, trades are ordered by insertion time (newest first)
    private TreeMap<LocalDate, LinkedList<Trade>> dailyTrades;

    public TradeMap() {
        this.dailyTrades = new TreeMap<>();
    }

    public TradeMap(TreeMap<LocalDate, LinkedList<Trade>> dailyTrades) {
        this.dailyTrades = dailyTrades;
    }

    public TreeMap<LocalDate, LinkedList<Trade>> getDailyTrades() {
        return this.dailyTrades;
    }

    /**
     * get processed trades in a given date
     *  
     * @param date the date to get trades from
     * @return list of trades on that date
     */
    public synchronized LinkedList<Trade> getTradesByDate(LocalDate date) {
        // check if date exists in map
        if (!this.dailyTrades.containsKey(date)) {
            // return empty list
            return new LinkedList<>();
        }

        // return trades on that date
        return this.dailyTrades.get(date);
    }

    /**
     * get trade by a given id
     * 
     * @param tradeId the id of the trade to get
     * @return the trade with the given id, null if not found
     */
    public synchronized Trade getTrade (int tradeId) {
        // iterate over all trades in the map
        for (Map.Entry<LocalDate, LinkedList<Trade>> entry : this.dailyTrades.entrySet()) {
            // iterate over all trades in the entry
            for (Trade trade : entry.getValue()) {
                // check if trade id matches
                if (trade.getOrderId() == tradeId) {
                    return trade;
                }
            }
        }

        return null;
    }

    /**
     * add a trade to the map
     * 
     * @param date the date of the trade
     * @param trade the trade to add
     */
    public synchronized void addTrade(LocalDate date, Trade trade) {
        // check if date exists in map
        if (!this.dailyTrades.containsKey(date)) {
            // create new list of trades for that date
            LinkedList<Trade> trades = new LinkedList<>();
            // add trade to the head of the list
            trades.addFirst(trade);
            // put the list in the map
            this.dailyTrades.put(date, trades);
        }
        else {
            // add trade to the head of the existing list
            this.dailyTrades.get(date).addFirst(trade);
        }
    }  
}