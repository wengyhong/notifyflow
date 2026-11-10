package com.wenghong.notifyflow.delivery.application;

public class DeliveryReplayNotAllowedException extends RuntimeException {

    public DeliveryReplayNotAllowedException(){
        super("Only failed deliveries can be replayed");
    }

}
