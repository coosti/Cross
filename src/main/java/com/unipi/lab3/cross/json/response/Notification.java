package com.unipi.lab3.cross.json.response;

import java.util.*;

import com.unipi.lab3.cross.model.trade.Trade;

public class Notification {

    private final String notification;
    private final LinkedList<Trade> trades;

    public Notification (String notification, LinkedList<Trade> trades) {
        this.notification = notification;
        this.trades = new LinkedList<>(trades);
    }

    public String getNotification () {
        return this.notification;
    }

    public LinkedList<Trade> getTrades () {
        return this.trades;
    }

}