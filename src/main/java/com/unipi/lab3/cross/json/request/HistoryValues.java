package com.unipi.lab3.cross.json.request;

public class HistoryValues implements Values {
    private String date;

    public HistoryValues (String date) {
        this.date = date;
    }

    public String getDate () {
        return this.date;
    }

    public int getMonth() {
        return Integer.parseInt(date.substring(0, 2));
    }

    public int getYear() {
        return Integer.parseInt(date.substring(2, 6));
    }

    public void setDate (String date) {
        this.date = date;
    }

    public String toString () {
        return "{month: " + this.date + "}";
    }
    
}
