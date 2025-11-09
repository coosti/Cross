package com.unipi.lab3.cross.json.response;

import java.util.ArrayList;

import com.unipi.lab3.cross.model.trade.DailyTradingStats;

public class HistoryResponse extends Response {

    private String date; // in format "MMYYYY"
    private ArrayList<DailyTradingStats> stats;

    public HistoryResponse() {}

    public HistoryResponse(String date, ArrayList<DailyTradingStats> stats) {
        this.date = date;
        this.stats = new ArrayList<>(stats);
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public int getMonth() {
        return Integer.parseInt(this.date.substring(0, 2));
    }

    public int getYear() {
        return Integer.parseInt(this.date.substring(2, 6));
    }

    public ArrayList<DailyTradingStats> getStats() {
        return stats;
    }
}
