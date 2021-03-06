/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.client.live.internal;

import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.ditto.client.internal.OutgoingMessageFactory;
import org.eclipse.ditto.client.internal.bus.PointerWithData;
import org.eclipse.ditto.client.live.messages.MessageSerializationException;
import org.eclipse.ditto.client.live.messages.MessageSerializerRegistry;
import org.eclipse.ditto.client.live.messages.RepliableMessage;
import org.eclipse.ditto.client.live.messages.internal.ImmutableDeserializingMessage;
import org.eclipse.ditto.client.live.messages.internal.ImmutableRepliableMessage;
import org.eclipse.ditto.client.messaging.MessagingProvider;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.DittoHeadersBuilder;
import org.eclipse.ditto.model.messages.Message;
import org.eclipse.ditto.model.messages.MessageBuilder;
import org.eclipse.ditto.model.messages.MessagesModelFactory;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.protocoladapter.Adaptable;
import org.eclipse.ditto.protocoladapter.ProtocolAdapter;
import org.eclipse.ditto.protocoladapter.TopicPath;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.messages.MessageCommand;
import org.eclipse.ditto.signals.commands.messages.MessageCommandResponse;
import org.eclipse.ditto.signals.commands.messages.SendFeatureMessage;
import org.eclipse.ditto.signals.commands.messages.SendFeatureMessageResponse;
import org.eclipse.ditto.signals.commands.messages.SendThingMessage;
import org.eclipse.ditto.signals.commands.messages.SendThingMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @since 1.0.0
 */
final class LiveMessagesUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveMessagesUtil.class);

    private LiveMessagesUtil() {
        throw new AssertionError();
    }

    static <T> void checkSerializerExistForMessageType(final MessageSerializerRegistry serializerRegistry,
            final Class<T> type) {
        if (!serializerRegistry.containsMessageSerializerFor(type)) {
            throw new MessageSerializationException("No deserializer for payload type '" + type + "' is registered");
        }
    }

    static <T> void checkSerializerExistForMessageType(final MessageSerializerRegistry serializerRegistry,
            final Class<T> type, final String subject) {
        if (!(serializerRegistry.containsMessageSerializerFor(type, subject)
                || serializerRegistry.containsMessageSerializerFor(type))) {
            throw new MessageSerializationException(
                    "No deserializer for subject '" + subject + "' and payload type '" + type + "' is registered");
        }
    }

    static <T, U> Consumer<PointerWithData> createEventConsumerForRepliableMessage(
            final ProtocolAdapter protocolAdapter,
            final MessagingProvider messagingProvider,
            final OutgoingMessageFactory outgoingMessageFactory,
            final MessageSerializerRegistry messageSerializerRegistry,
            final Class<T> type, final Consumer<RepliableMessage<T, U>> handler) {

        return e ->
        {
            final Message<T> message = LiveMessagesUtil.eventToMessage(e, type, false);
            final Message<T> deserializedMessage =
                    ImmutableDeserializingMessage.of(message, type, messageSerializerRegistry);

            final RepliableMessage<T, U> repliableMessage = ImmutableRepliableMessage.of(deserializedMessage, msg -> {
                final Message<U> toBeSentMessage = outgoingMessageFactory.sendMessage(messageSerializerRegistry, msg);
                LOGGER.trace("Response Message about to send: {}", toBeSentMessage);
                messagingProvider.emitAdaptable(
                        LiveMessagesUtil.constructAdaptableFromMessage(toBeSentMessage, protocolAdapter));
            });
            handler.accept(repliableMessage);
        };
    }

    static <U> Consumer<PointerWithData> createEventConsumerForRepliableMessage(
            final ProtocolAdapter protocolAdapter,
            final MessagingProvider messagingProvider,
            final OutgoingMessageFactory outgoingMessageFactory,
            final MessageSerializerRegistry messageSerializerRegistry,
            final Consumer<RepliableMessage<?, U>> handler) {

        return e ->
        {
            final Message<?> message = LiveMessagesUtil.eventToMessage(e, Object.class, true);

            final RepliableMessage<?, U> repliableMessage = ImmutableRepliableMessage.of(message, msg ->
            {
                final Message<U> toBeSentMessage = outgoingMessageFactory.sendMessage(messageSerializerRegistry, msg);
                LOGGER.trace("Response Message about to send: {}", toBeSentMessage);
                messagingProvider.emitAdaptable(constructAdaptableFromMessage(toBeSentMessage, protocolAdapter));
            });
            handler.accept(repliableMessage);
        };
    }

    private static <T> Message<T> eventToMessage(final PointerWithData e, final Class<T> type, final boolean
            copyRawPayloadToPayload) {
        final Message<?> incomingMessage = (Message<?>) e.getData();
        LOGGER.trace("Received message {} for message handler.", incomingMessage);

        @SuppressWarnings("unchecked") final MessageBuilder<T> messageBuilder =
                MessagesModelFactory.<T>newMessageBuilder(incomingMessage.getHeaders())
                        .payload((T) incomingMessage.getPayload().orElse(null))
                        .rawPayload(incomingMessage.getRawPayload().orElse(null));

        if (copyRawPayloadToPayload && !incomingMessage.getPayload().isPresent()) {
            messageBuilder.payload(type.cast(incomingMessage.getRawPayload().orElse(null)));
        }
        incomingMessage.getExtra().ifPresent(messageBuilder::extra);

        return messageBuilder.build();
    }

    public static Adaptable constructAdaptableFromMessage(final Message<?> message,
            final ProtocolAdapter protocolAdapter) {
        final TopicPath.Channel channel = TopicPath.Channel.LIVE;
        final DittoHeadersBuilder headersBuilder = DittoHeaders.newBuilder().channel(channel.getName());
        final Optional<String> optionalCorrelationId = message.getCorrelationId();
        optionalCorrelationId.ifPresent(headersBuilder::correlationId);
        final DittoHeaders dittoHeaders = headersBuilder.build();

        final ThingId thingId = message.getThingEntityId();
        final Optional<HttpStatusCode> statusCodeOptional = message.getStatusCode();
        final Optional<String> featureIdOptional = message.getFeatureId();
        final Adaptable adaptable;
        if (statusCodeOptional.isPresent()) {
            // this is treated as a response message
            final HttpStatusCode statusCode = statusCodeOptional.get();
            final MessageCommandResponse<?, ?> messageCommandResponse = featureIdOptional.isPresent()
                    ? SendFeatureMessageResponse.of(thingId, featureIdOptional.get(), message, statusCode, dittoHeaders)
                    : SendThingMessageResponse.of(thingId, message, statusCode, dittoHeaders);
            adaptable = protocolAdapter.toAdaptable((Signal<?>) messageCommandResponse);
        } else {
            final MessageCommand<?, ?> messageCommand = featureIdOptional.isPresent()
                    ? SendFeatureMessage.of(thingId, featureIdOptional.get(), message, dittoHeaders)
                    : SendThingMessage.of(thingId, message, dittoHeaders);
            adaptable = protocolAdapter.toAdaptable((Signal<?>) messageCommand);
        }
        return adaptable;
    }
}
