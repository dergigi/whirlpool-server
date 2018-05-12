package com.samourai.whirlpool.server.services;

import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

@Component
public class WebSocketService {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private WhirlpoolProtocol whirlpoolProtocol;
    private SimpMessagingTemplate messagingTemplate;
    private TaskExecutor taskExecutor;

    @Autowired
    public WebSocketService(WhirlpoolProtocol whirlpoolProtocol, SimpMessagingTemplate messagingTemplate, TaskExecutor taskExecutor) {
        this.whirlpoolProtocol = whirlpoolProtocol;
        this.messagingTemplate = messagingTemplate;
        this.taskExecutor = taskExecutor;
        messagingTemplate.setMessageConverter(new MappingJackson2MessageConverter());
    }

    public void broadcast(Object payload){
        //log.info("(broadcast) --> "+payload);
        taskExecutor.execute(() -> messagingTemplate.convertAndSend(whirlpoolProtocol.SOCKET_SUBSCRIBE_QUEUE, payload, buildHeaders(payload)));
    }

    public void sendPrivate(String username, Object payload){
        //log.info("(sendPrivate:"+username+") --> "+payload);
        taskExecutor.execute(() -> messagingTemplate.convertAndSendToUser(username, whirlpoolProtocol.SOCKET_SUBSCRIBE_USER_REPLY, payload, buildHeaders(payload)));
    }

    private Map<String,Object> buildHeaders(Object payload) {
        Map<String,Object> headers = new HashMap<>();
        headers.put(whirlpoolProtocol.HEADER_MESSAGE_TYPE, payload.getClass().getName());
        return headers;
    }

}