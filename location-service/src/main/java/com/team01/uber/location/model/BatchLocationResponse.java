package com.team01.uber.location.model;

public class BatchLocationResponse {

    private int count;

    public BatchLocationResponse(int count) {
        this.count = count;
    }

    public int getCount() { return count; }
}
