package com.unipi.lab3.cross.model;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Iterator;

import com.unipi.lab3.cross.model.orders.LimitOrder;

/**
    class representing a group of limit orders at a specific price
    the price is the key in the map for a group
*/

public class OrderGroup {

    // total size of all limit orders in the group
    private int size;
    // total value of all limit orders in the group
    private long total;

    // limit orders queue with that price
    // in the queue, orders are ordered by arriving time
    private ConcurrentLinkedQueue<LimitOrder> limitOrders = new ConcurrentLinkedQueue<>();

    public OrderGroup (int size, long total, ConcurrentLinkedQueue<LimitOrder> limitOrders) {
        this.size = size;
        this.total = total;
        this.limitOrders = limitOrders;
    }

    public OrderGroup () {
        this.size = 0;
        this.total = 0;
        this.limitOrders = new ConcurrentLinkedQueue<>();
    }

    public int getSize () {
        return this.size;
    }

    public long getTotal () {
        return this.total;
    }

    public ConcurrentLinkedQueue<LimitOrder> getLimitOrders () {
        return this.limitOrders;
    }

    public void setSize (int size) {
        this.size = size;
    }

    public void setTotal (long total) {
        this.total = total;
    }

    public boolean isEmpty () {
        return this.limitOrders.isEmpty();
    }

    // update the group parameters after an order has been executed
    public synchronized void updateGroup (int filledSize, int limitPrice) {
        this.size -= filledSize;
        this.total = (long) limitPrice * this.size;
    }

    /**
     * get the size of the order group excluding orders from a specific user
     * 
     * @param excludedUsername username to exclude from the size calculation
     * @return the size of the order group excluding orders from the given user
     */
    public int getFilteredSize (String excludedUsername) {
        int filteredSize = this.size;

        for (LimitOrder order : this.limitOrders) {
            if (order.getUsername().equals(excludedUsername)) {
                // if the order is from the given user, subtract its size from the total size
                filteredSize -= order.getSize();
            }
        }

        // return the remaining size
        return filteredSize;
    }

    /**
     * add a new limit order to the group
     * 
     * @param order limit order to add
     */
    public synchronized void addOrder (LimitOrder order) {
        // add the new order to the existing queue
        this.limitOrders.add(order);

        // update size of the group
        this.size += order.getSize();

        // update total value of the group
        long newTotal = (long) order.getLimitPrice() * this.size;
        this.total = newTotal;
    }

    /**
     * remove an order from the group
     * 
     * @param orderId id of the order to remove
     * @param username username of the user trying to remove the order
     * @return a boolean to indicate if the order was removed successfully, true if removed, false otherwise
     */
    public synchronized boolean removeOrder (int orderId, String username) {

        Iterator<LimitOrder> iterator = this.limitOrders.iterator();

        while (iterator.hasNext()) {
            LimitOrder order = iterator.next();

            // remove the order if it has the same id and it has been added by the same user who is trying to remove it
            if (order.getOrderId() == orderId && order.getUsername().equals(username)) {
                // remove the order from the queue
                iterator.remove();

                // update the size
                this.size -= order.getSize();

                // recalculate the total with the new size value
                long newTotal = (long) order.getLimitPrice() * this.size;
                setTotal(newTotal);

                return true;
            }
        }

        // order not found or not removed
        return false;
    }

    /**
     * print the order group details
     */
    public void printGroup () {
        System.out.println("Size: " + this.size + " Total: " + this.total);
        System.out.println("Orders:");
        for (LimitOrder order : this.limitOrders) {
            System.out.println(order.toString());
        }
    }
}
