package com.unipi.lab3.cross.model.trade;

/**
 * class that holds price stats for trades of a specific day,
 * as opening, closing, maximum, and minimum prices
*/

public class DailyTradingStats {
    private String date; // in format yyyy-mm-dd
    private int openPrice;
    private int closePrice;
    private int maxPrice;
    private int minPrice;

    public DailyTradingStats (String date, int openPrice, int closePrice, int maxPrice, int minPrice) {
        this.date = date;
        this.openPrice = openPrice;
        this.closePrice = closePrice;
        this.maxPrice = maxPrice;
        this.minPrice = minPrice;
    }

    public String getDate () {
        return this.date;
    }

    public int getOpenPrice () {
        return this.openPrice;
    }

    public int getClosePrice () {
        return this.closePrice;
    }

    public int getMaxPrice () {
        return this.maxPrice;
    }

    public int getMinPrice () {
        return this.minPrice;
    }

    public String toString () {
        return "{date: " + this.getDate() + " = openPrice: " + this.getOpenPrice() + ", closePrice: " + this.getClosePrice() + ", maxPrice: " + this.getMaxPrice() + ", minPrice: " + this.getMinPrice() + "}";
    }
}