package com.unipi.lab3.cross.model.trade;

import java.util.*;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * class that calculates and displays daily price statistics for trades
 * for each day within a given month, computes opening, closing,
 * maximum, and minimum prices based on executed trades stored in trade map
 */

public class PriceHistory {

    public PriceHistory () {}

    /**
     * builds the list of daily trading stast for all days
     * within the given month of the given year
     * 
     * @param date year and month for which the price history is requested
     * @param map trade map containing all executed trades
     * @return list of daily trading stats for the given month
     */
    public synchronized ArrayList<DailyTradingStats> getPriceHistory (YearMonth date, TradeMap map) {
        // list to hold daily trading stats
        ArrayList<DailyTradingStats> history = new ArrayList<>();

        // iterate over each entry in the trade map
        for (Map.Entry<LocalDate, LinkedList<Trade>> entry : map.getDailyTrades().entrySet()) {
            // extract the trade date from the entry
            LocalDate tradeDate = entry.getKey();

            // check if the trade date equals the specified month and year
            if (YearMonth.from(tradeDate).equals(date)) {

                // calculate daily trading stats for that date
                DailyTradingStats stats = this.calculateDailyTradingStats(tradeDate, map);

                // add the calculated stats to the history list
                history.add(stats);
            }
        }

        // return the compiled price history for the month
        return history;
    }

    /**
     * calculates open, close, max, min prices for a given date
     * 
     * @param date year and month for which the price history is requested
     * @param map trade map containing all executed trades
     * @return daily trading stats for the given date
     */
    public synchronized DailyTradingStats calculateDailyTradingStats (LocalDate date, TradeMap map) {
        // get all trades for the specified date
        LinkedList<Trade> trades = map.getTradesByDate(date);

        // sort trades by timestamp to determine open and close prices (chronological order)
        LinkedList<Trade> sortedTrades = new LinkedList<>(trades);
        sortedTrades.sort(Comparator.comparingLong(Trade::getTimestamp));

        // if no trades for that day return empty stats
        if (sortedTrades.isEmpty())
            return new DailyTradingStats(date, 0, 0, 0, 0);

        // first trade price is the open price
        int openPrice = sortedTrades.getFirst().getPrice();
        // last trade price is the close price
        int closePrice = sortedTrades.getLast().getPrice();

        // find max and min prices from the trades of that day
        int maxPrice = Collections.max(sortedTrades, Comparator.comparingInt(Trade::getPrice)).getPrice();
        int minPrice = Collections.min(sortedTrades, Comparator.comparingInt(Trade::getPrice)).getPrice();

        // return new daily trading stats object
        return new DailyTradingStats(date, openPrice, closePrice, maxPrice, minPrice);
    }

    /**
     * prints the price history in a formatted table
     * @param history list of daily trading stats to be printed
     */
    public void printPriceHistory (ArrayList<DailyTradingStats> history) {
        System.out.printf("%-12s %-12s %-12s %-12s %-12s%n", "Date", "Open", "Close", "Max", "Min");
        System.out.println("---------------------------------------------------------------");

        // iterate over each day's trading stats and print them
        for (DailyTradingStats stats : history) {
            // skip days with no trading activity
            if (stats.getOpenPrice() == 0 && stats.getClosePrice() == 0 && stats.getMaxPrice() == 0 && stats.getMinPrice() == 0)
                continue;
            else
                System.out.printf("%-12s %-12.2f %-12.2f %-12.2f %-12.2f%n", stats.getDate().toString(), stats.getOpenPrice()/1000, stats.getClosePrice()/1000, stats.getMaxPrice()/1000, stats.getMinPrice()/1000);
        }
    } 
}
