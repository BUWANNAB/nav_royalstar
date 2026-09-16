package com.ant.robot.event;

public class ConnectionEvent {
    private final String serviceName;
    private final boolean isConnected;

    public ConnectionEvent(String serviceName, boolean isConnected) {
        this.serviceName = serviceName;
        this.isConnected = isConnected;
    }

    public String getServiceName() {
        return serviceName;
    }

    public boolean isConnected() {
        return isConnected;
    }
}