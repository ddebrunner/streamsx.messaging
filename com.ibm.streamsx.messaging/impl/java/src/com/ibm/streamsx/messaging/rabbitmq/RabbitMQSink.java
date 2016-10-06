/*******************************************************************************
 * Copyright (C) 2015, MOHAMED-ALI SAID
 * All Rights Reserved
 *******************************************************************************/
/* Generated by Streams Studio: March 26, 2014 11:37:11 AM EDT */
package com.ibm.streamsx.messaging.rabbitmq;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.rabbitmq.client.AMQP.BasicProperties;

/**
 * This operator was originally contributed by Mohamed-Ali Said @saidmohamedali
 */
@InputPorts(@InputPortSet(cardinality = 1, optional = false, description = ""))
@PrimitiveOperator(name = "RabbitMQSink", description = RabbitMQSink.DESC)
public class RabbitMQSink extends RabbitMQBaseOper {

	private final Logger trace = Logger.getLogger(RabbitMQSink.class
			.getCanonicalName());
	Integer deliveryMode = 1;
	int maxMessageSendRetries = 0;
	int messageSendRetryDelay = 10000;
	private boolean firstConnection = true;
	
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {

		// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
		super.initSchema(getInput(0).getStreamSchema());
		trace.log(TraceLevel.INFO, "Operator " + context.getName() + " initializing in PE: "
				+ context.getPE().getPEId() + " in Job: "
				+ context.getPE().getJobId());

	}

	@Override
	public synchronized void allPortsReady() throws Exception {
		OperatorContext context = getOperatorContext();
		trace.log(TraceLevel.INFO, "Operator " + context.getName()
				+ " all ports are ready in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());

	}

	@SuppressWarnings("unchecked")
	@Override
	public void process(StreamingInput<Tuple> stream, Tuple tuple) throws Exception {

		// Our first time, we need to setup our connection
		if (firstConnection) {
			firstConnection = false;
			try {
				readyForShutdown = false;
				initializeRabbitChannelAndConnection();
			} finally {
				readyForShutdown = true;
			}
		}
		
		// Handle case of lost connection/failed authentication
		// but we have new credentials from appConfig
		if (isConnected.getValue() == 0
				&& newCredentialsExist()){
			try {
				readyForShutdown = false;
				resetRabbitClient();
			} finally {
				readyForShutdown = true;
			}
		}
		
		byte[] message = messageAH.getBytes(tuple);
		String routingKey = "";
		Map<String, Object> headers = new HashMap<String, Object>();
		if (routingKeyAH.isAvailable()) {
			routingKey = tuple.getString(routingKeyAH.getName());
		}
		
		BasicProperties.Builder propsBuilder = new BasicProperties.Builder();
		if (messageHeaderAH.isAvailable()) {
			headers = (Map<String, Object>) tuple.getMap(messageHeaderAH.getName());
			propsBuilder.headers(headers);
		}
		propsBuilder.deliveryMode(deliveryMode);
		
		try {
			if (trace.isLoggable(TraceLevel.DEBUG))
				trace.log(TraceLevel.DEBUG,
						"Producing message: " + message.toString() + " in thread: " + Thread.currentThread().getName());
			channel.basicPublish(exchangeName, routingKey, propsBuilder.build(), message);
			if (isConnected.getValue() == 0){
				// We succeeded at publish, so we must be connected
				// Adding this to deal with an issue where we catch 
				// a stale AuthorizationException that makes us look 
				// disconnected
				isConnected.setValue(1); 
			}
		} catch (Exception e) {
			trace.log(TraceLevel.ERROR, "Exception message:" + e.getMessage() + "\r\n");
			handleFailedPublish(message, routingKey, propsBuilder);
		}
	}

	private void handleFailedPublish(byte[] message, String routingKey, BasicProperties.Builder propsBuilder) {
		Boolean failedToSend = true;
		int attemptCount = 0;
		while (failedToSend && attemptCount < maxMessageSendRetries) {
			attemptCount++;
			try {
				Thread.sleep(messageSendRetryDelay);
				trace.log(TraceLevel.ERROR, "Attempting to resend. Try number: " + attemptCount);
				channel.basicPublish(exchangeName, routingKey, propsBuilder.build(), message);
				failedToSend = false;
			} catch (Exception e1) {
				e1.printStackTrace();
			}
		}

		// if we still can't send after the number of maxMessageSendRetries,
		// we want to log error and move on
		if (failedToSend) {
			trace.log(TraceLevel.ERROR, "Failed to send message after " + attemptCount + " attempts.");
		}
	}

	@Parameter(optional = true, description = "Name of the RabbitMQ exchange to send messages to. To use default RabbitMQ exchange, use empty quotes or do not specify: \\\"\\\".")
	public void setExchangeName(String value) {
		exchangeName = value;
	}
	
	@Parameter(optional = true, description = "Marks message as persistent(2) or non-persistent(1). Default as 1. ")
	public void setDeliveryMode(Integer value) {
		deliveryMode = value; 
	}
	
	@Parameter(optional = true, description = "This optional parameter specifies the number of successive retries that are attempted for a message if a failure occurs when the message is sent. The default value is zero; no retries are attempted.")
	public void setMaxMessageSendRetries(int value) {
		maxMessageSendRetries = value; 
	}
	
	@Parameter(optional = true, description = "This optional parameter specifies the time in milliseconds to wait before the next delivery attempt. If the maxMessageSendRetries is specified, you must also specify a value for this parameter.")
	public void setMessageSendRetryDelay(int value) {
		messageSendRetryDelay = value; 
	}

	@Override
	public synchronized void shutdown() throws Exception {
		super.shutdown(); 
	}
	
	public static final String DESC = 
			"This operator acts as a RabbitMQ producer, sending messages to a RabbitMQ broker. " + 
			"The broker is assumed to be already configured and running. " +
			"The incoming stream can have three attributes: message, routing_key, and messageHeader. " +
			"The message is a required attribute. " +
			"The exchange name, queue name, and routing key can be specified using parameters. " +
			"If a specified exchange does not exist, it will be created as a non-durable exchange. " + 
			"All exchanges created by this operator are non-durable and auto-delete."  +  
			"This operator supports direct, fanout, and topic exchanges. It does not support header exchanges. " +
			"Messages are non-persistent and sending will only be attempted once by default. " + 
			"This behavior can be modified using the deliveryMode and maxMessageSendRetries parameters. " + 
			"\\n\\n**Behavior in a Consistent Region**" + 
			"\\nThis operator can participate in a consistent region. It cannot be the start of a consistent region. " + 
			BASE_DESC
			;
}
