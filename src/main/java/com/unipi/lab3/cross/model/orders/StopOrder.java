package com.unipi.lab3.cross.model.orders;

/**
 * class representing a stop order, extending the generic order class
 * includes a stop price attribute
 */

public class StopOrder extends Order{

    private int stopPrice;

    public StopOrder (int orderID, String username, String type, int size, int stopPrice) {
        super(orderID, username, type, size);
        this.stopPrice = stopPrice;
    }

    public int getStopPrice () {
        return stopPrice;
    }

    public String toString () {
        return "Order ID: " + this.getOrderId() + " Username: " + this.getUsername() + " Type: " + this.getType() + " Size: " + this.getSize() + " Stop Price: " + this.getStopPrice();
    }

}
