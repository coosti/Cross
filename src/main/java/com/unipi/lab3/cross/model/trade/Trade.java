package com.unipi.lab3.cross.model.trade;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * class representing a trade, an executed order
 * for every trade keeps orderID, type (buy/sell), orderType (limit/market), size, price, timestamp and username
*/

public class Trade {
    private int orderID;
    private String type;
    private String orderType;
    private int size;
    private int price;
    private long timestamp;

    private transient String username;

    public Trade (int orderID, String type, String orderType, int size, String username) {
        this.orderID = orderID;
        this.type = type;
        this.orderType = orderType;
        this.size = size;
        this.username = username;
        this.timestamp = Instant.now().getEpochSecond();
    }
 
    public Trade (int orderID, String type, String orderType, int size, int price) {
        this.orderID = orderID;
        this.type = type;
        this.orderType = orderType;
        this.size = size;
        this.price = price;
        this.timestamp = Instant.now().getEpochSecond();
    }

    public Trade (int orderID, String type, String orderType, int size, int price, String username) {
        this.orderID = orderID;
        this.type = type;
        this.orderType = orderType;
        this.size = size;
        this.price = price;
        this.timestamp = Instant.now().getEpochSecond();

        this.username = username;
    }

    public int getOrderId () {
        return this.orderID;
    }

    public String getType () {
        return this.type;
    }

    public String getOrderType () {
        return this.orderType;
    }

    public int getSize () {
        return this.size;
    }   

    public int getPrice () {
        return this.price;
    }

    public long getTimestamp () {
        return this.timestamp;
    }

    public String getReadableTimestamp() {
        return Instant.ofEpochSecond(this.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public String getUsername () {
        return this.username;
    }

    public void setSize (int size) {
        this.size = size;
    }
}