package com.team01.uber.location.dto;

public class BatchLocationResponse {

    private int count;

    public BatchLocationResponse(int count) {
        this.count = count;
    }

    public int getCount() { return count; }
}
