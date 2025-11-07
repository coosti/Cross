package com.unipi.lab3.cross.model.orders;

/**
 * class representing a limit order, extending the generic order class
 * includes a limit price attribute
*/

public class LimitOrder extends Order {

    private int limitPrice;

    public LimitOrder (int orderID, String username, String type, int size, int limitPrice) {
        super(orderID, username, type, size);
        this.limitPrice = limitPrice;
    }

    public int getLimitPrice () {
        return limitPrice;
    }

    public String toString () {
        return "Order ID: " + this.getOrderId() + " Username: " + this.getUsername() + " Type: " + this.getType() + " Size: " + this.getSize() + " Limit Price: " + this.getLimitPrice();
    }
}
