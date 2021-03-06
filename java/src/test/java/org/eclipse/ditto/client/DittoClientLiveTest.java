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
package org.eclipse.ditto.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.ditto.client.TestConstants.Policy.POLICY_ID;
import static org.eclipse.ditto.client.TestConstants.Thing.THING_ID;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.ditto.client.internal.AbstractDittoClientTest;
import org.eclipse.ditto.client.live.commands.FeaturesCommandHandling;
import org.eclipse.ditto.client.live.commands.ThingCommandHandling;
import org.eclipse.ditto.client.live.events.FeatureEventFactory;
import org.eclipse.ditto.client.live.messages.MessageSender;
import org.eclipse.ditto.client.live.messages.RepliableMessage;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.Feature;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingsModelFactory;
import org.eclipse.ditto.protocoladapter.TopicPath;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.live.modify.CreateThingLiveCommandAnswerBuilder;
import org.eclipse.ditto.signals.commands.live.modify.DeleteFeatureLiveCommandAnswerBuilder;
import org.eclipse.ditto.signals.commands.messages.MessageCommand;
import org.eclipse.ditto.signals.commands.messages.MessageCommandResponse;
import org.eclipse.ditto.signals.commands.messages.SendClaimMessage;
import org.eclipse.ditto.signals.commands.messages.SendClaimMessageResponse;
import org.eclipse.ditto.signals.commands.messages.SendFeatureMessage;
import org.eclipse.ditto.signals.commands.messages.SendFeatureMessageResponse;
import org.eclipse.ditto.signals.commands.messages.SendThingMessage;
import org.eclipse.ditto.signals.commands.messages.SendThingMessageResponse;
import org.eclipse.ditto.signals.commands.things.modify.CreateThing;
import org.eclipse.ditto.signals.commands.things.modify.CreateThingResponse;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeature;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeatureResponse;
import org.eclipse.ditto.signals.events.things.FeatureDeleted;
import org.eclipse.ditto.signals.events.things.ThingCreated;
import org.junit.Test;

/**
 * Test live channel interactions not covered by tests conforming to the twin-channel interaction patterns
 * (send-command-get-response, subscribe-for-events), namely:
 * - emit event
 * - send message, receive response
 * - subscribe for message, send message
 * - subscribe for command, send response and/or event
 */
public final class DittoClientLiveTest extends AbstractDittoClientTest {

    private static final String FEATURE_ID = "someFeature";
    private static final JsonPointer ATTRIBUTE_KEY_NEW = JsonFactory.newPointer("new");
    private static final String ATTRIBUTE_VALUE = "value";
    private static final Feature FEATURE = ThingsModelFactory.newFeatureBuilder()
            .properties(ThingsModelFactory.newFeaturePropertiesBuilder()
                    .set("propertyPointer", "propertyValue")
                    .build())
            .withId(FEATURE_ID)
            .build();

    private static final Thing THING = ThingsModelFactory.newThingBuilder()
            .setId(THING_ID)
            .setPolicyId(POLICY_ID)
            .setAttribute(ATTRIBUTE_KEY_NEW, JsonFactory.newValue(ATTRIBUTE_VALUE))
            .setFeature(FEATURE)
            .build();

    @Test
    public void sendClaimMessage() {
        testMessageSending(client.live().message().to(THING_ID), claim(), claimResponse());
        testMessageSending(client.live().forId(THING_ID).message().to(), claim(), claimResponse());
    }

    @Test
    public void sendThingMessage() {
        testMessageSending(client.live().message().to(THING_ID), thingMessage(), thingMessageResponse());
        testMessageSending(client.live().forId(THING_ID).message().to(), thingMessage(), thingMessageResponse());
    }

    @Test
    public void sendFeatureMessage() {
        testMessageSending(client.live().message().to(THING_ID).featureId(FEATURE_ID), featureMessage(),
                featureMessageResponse());
        testMessageSending(client.live().forId(THING_ID).forFeature(FEATURE_ID).message().to(), featureMessage(),
                featureMessageResponse());
    }

    @Test
    public void emitEvent() {
        client.live().emitEvent(factory -> factory.featureDeleted(THING_ID, FEATURE_ID));
        assertFeatureDeletedEmitted();
    }

    @Test
    public void emitThingEvent() {
        client.live().forId(THING_ID).emitEvent(factory -> factory.featureDeleted(FEATURE_ID));
        assertFeatureDeletedEmitted();
    }

    @Test
    public void emitFeatureEvent() {
        client.live().forId(THING_ID).forFeature(FEATURE_ID).emitEvent(FeatureEventFactory::featureDeleted);
        assertFeatureDeletedEmitted();
    }

    @Test
    public void subscribeForClaimMessage() {
        subscribeForMessageAndSendMessage(claim(), claimResponse(),
                (id, sub, clazz, handler) -> client.live().registerForClaimMessage(id, clazz, handler));
    }

    @Test
    public void subscribeForClaimMessageAsThing() {
        subscribeForMessageAndSendMessage(claim(), claimResponse(),
                (id, sub, clazz, handler) -> client.live().forId(THING_ID).registerForClaimMessage(id, clazz, handler));
    }

    @Test
    public void subscribeForThingMessage() {
        subscribeForMessageAndSendMessage(thingMessage(), thingMessageResponse(), client.live()::registerForMessage);
    }

    @Test
    public void subscribeForThingMessageAsThing() {
        subscribeForMessageAndSendMessage(thingMessage(), thingMessageResponse(),
                client.live().forId(THING_ID)::registerForMessage);
    }

    @Test
    public void subscribeForFeatureMessage() {
        subscribeForMessageAndSendMessage(featureMessage(), featureMessageResponse(),
                client.live()::registerForMessage);
    }

    @Test
    public void subscribeForFeatureMessageAsFeature() {
        subscribeForMessageAndSendMessage(featureMessage(), featureMessageResponse(),
                client.live().forId(THING_ID).forFeature(FEATURE_ID)::registerForMessage);
    }

    @Test
    public void subscribeForCreateThing() {
        testHandleCreateThing(client.live());
    }

    @Test
    public void subscribeForCreateThingAsThing() {
        testHandleCreateThing(client.live().forId(THING_ID));
    }

    @Test
    public void subscribeForDeleteFeature() {
        testHandleDeleteFeature(client.live());
    }

    @Test
    public void subscribeForDeleteFeatureAsThing() {
        testHandleDeleteFeature(client.live().forId(THING_ID));
    }

    @Test
    public void subscribeForDeleteFeatureAsFeature() {
        testHandleDeleteFeature(client.live().forId(THING_ID).forFeature(FEATURE_ID));
    }

    private void testHandleCreateThing(final ThingCommandHandling thingCommandHandling) {
        startConsumption();
        thingCommandHandling.handleCreateThingCommands(c -> c.answer()
                .withResponse(CreateThingLiveCommandAnswerBuilder.ResponseFactory::created)
                .withEvent(CreateThingLiveCommandAnswerBuilder.EventFactory::created)
        );
        final String correlationId = UUID.randomUUID().toString();
        reply(setHeaders(CreateThing.of(THING, null, DittoHeaders.empty()), correlationId));
        assertHeaders(expectMsgClass(CreateThingResponse.class), correlationId);
        assertHeaders(expectMsgClass(ThingCreated.class), correlationId);
    }

    private void testHandleDeleteFeature(final FeaturesCommandHandling featuresCommandHandling) {
        startConsumption();
        featuresCommandHandling.handleDeleteFeatureCommands(c -> c.answer()
                .withResponse(DeleteFeatureLiveCommandAnswerBuilder.ResponseFactory::deleted)
                .withEvent(DeleteFeatureLiveCommandAnswerBuilder.EventFactory::deleted)
        );
        final String correlationId = UUID.randomUUID().toString();
        reply(setHeaders(DeleteFeature.of(THING_ID, FEATURE_ID, DittoHeaders.empty()), correlationId));
        assertHeaders(expectMsgClass(DeleteFeatureResponse.class), correlationId);
        assertHeaders(expectMsgClass(FeatureDeleted.class), correlationId);
    }

    private void assertHeaders(final Signal<?> signal, final String correlationId) {
        assertThat(signal.getDittoHeaders().getCorrelationId()).contains(correlationId);
        assertThat(signal.getDittoHeaders().getChannel()).contains(TopicPath.Channel.LIVE.getName());
    }

    private Signal<?> setHeaders(final Signal<?> signal, final String correlationId) {
        return signal.setDittoHeaders(signal.getDittoHeaders()
                .toBuilder()
                .correlationId(correlationId)
                .channel(TopicPath.Channel.LIVE.getName())
                .build());
    }

    private void subscribeForMessageAndSendMessage(final MessageCommand<?, ?> incomingCommand,
            final MessageCommandResponse<String, ?> expectedResponse,
            final MessageHandlerRegistration<String, String> registerForMessage) {
        assertEventualCompletion(startConsumption());
        final String subject = incomingCommand.getMessage().getSubject();
        final String correlationId = UUID.randomUUID().toString();
        registerForMessage.accept(correlationId, subject, String.class, msg -> msg.reply()
                .statusCode(expectedResponse.getStatusCode())
                .payload(expectedResponse.getMessage().getPayload().orElse(null))
                .send()
        );
        reply(toAdaptableJsonString(incomingCommand.setDittoHeaders(incomingCommand.getDittoHeaders().toBuilder()
                .correlationId(correlationId)
                .build())));
        final MessageCommandResponse<?, ?> response = expectMsgClass(expectedResponse.getClass());
        assertThat(response.getDittoHeaders().getCorrelationId()).contains(correlationId);
        assertThat(response.getStatusCode()).isEqualTo(expectedResponse.getStatusCode());
        assertThat(response.getMessage().getPayload()).isEqualTo(expectedResponse.getMessage().getPayload());
    }

    private SendClaimMessage<String> claim() {
        final String subject = "claim";
        final String payload = "THOU BELONGEST TO ME!";
        return SendClaimMessage.of(THING_ID,
                AbstractDittoClientTest.<String>newMessageBuilder(subject)
                        .payload(payload)
                        .build(),
                DittoHeaders.newBuilder().randomCorrelationId().build());
    }

    private SendClaimMessageResponse<String> claimResponse() {
        final String subject = "claim";
        final String responsePayload = "THOU WISHEST!";
        final HttpStatusCode responseStatus = HttpStatusCode.PAYMENT_REQUIRED;
        return SendClaimMessageResponse.of(THING_ID,
                AbstractDittoClientTest.<String>newMessageBuilder(subject)
                        .payload(responsePayload)
                        .build(),
                responseStatus,
                DittoHeaders.empty());
    }

    private SendFeatureMessage<String> featureMessage() {
        final String payload = "MAKE COFFEE!";
        return SendFeatureMessage.of(THING_ID, FEATURE_ID,
                AbstractDittoClientTest.<String>newFeatureMessageBuilder(FEATURE_ID)
                        .payload(payload)
                        .build(),
                DittoHeaders.newBuilder().randomCorrelationId().build()
        );
    }

    private SendFeatureMessageResponse<String> featureMessageResponse() {
        final String responsePayload = "MAKE IT THYSELFE.";
        final HttpStatusCode responseStatus = HttpStatusCode.IM_A_TEAPOT;
        return SendFeatureMessageResponse.of(THING_ID, FEATURE_ID,
                AbstractDittoClientTest.<String>newFeatureMessageBuilder(FEATURE_ID)
                        .payload(responsePayload)
                        .build(),
                responseStatus,
                DittoHeaders.newBuilder().randomCorrelationId().build());
    }

    private SendThingMessage<String> thingMessage() {
        final String subject = "request";
        final String payload = "MAKE COFFEE!";
        return SendThingMessage.of(THING_ID,
                AbstractDittoClientTest.<String>newMessageBuilder(subject)
                        .payload(payload)
                        .build(),
                DittoHeaders.newBuilder().randomCorrelationId().build()
        );
    }

    private SendThingMessageResponse<String> thingMessageResponse() {
        final String subject = "request";
        final String responsePayload = "MAKE IT THYSELFE.";
        final HttpStatusCode responseStatus = HttpStatusCode.IM_A_TEAPOT;
        return SendThingMessageResponse.of(THING_ID,
                AbstractDittoClientTest.<String>newMessageBuilder(subject)
                        .payload(responsePayload)
                        .build(),
                responseStatus,
                DittoHeaders.empty());
    }

    private void assertFeatureDeletedEmitted() {
        final FeatureDeleted featureDeleted = expectMsgClass(FeatureDeleted.class);
        assertThat(featureDeleted.getDittoHeaders().getChannel()).contains(TopicPath.Channel.LIVE.getName());
        assertThat((CharSequence) featureDeleted.getThingEntityId()).isEqualTo(THING_ID);
    }

    private void testMessageSending(
            final MessageSender.SetSubject<Object> sender,
            final MessageCommand<String, ?> command,
            final MessageCommandResponse<String, ?> expectedResponse) {
        final String subject = command.getMessage().getSubject();
        final String payload = command.getMessage().getPayload().orElse(null);
        final Class<?> messageCommandClass = command.getClass();

        final CountDownLatch latch = new CountDownLatch(1);
        sender.subject(subject).payload(payload).send((response, error) -> {
            assertThat(response.getSubject()).isEqualTo(expectedResponse.getMessage().getSubject());
            assertThat(response.getStatusCode()).contains(expectedResponse.getStatusCode());
            assertThat(response.getPayload().map(buffer -> new String(buffer.array())))
                    .isEqualTo(expectedResponse.getMessage().getPayload());
            latch.countDown();
        });

        final MessageCommand<?, ?> messageCommand = (MessageCommand<?, ?>) expectMsgClass(messageCommandClass);
        final String correlationId = messageCommand.getDittoHeaders().getCorrelationId().orElse(null);
        reply(expectedResponse.setDittoHeaders(
                expectedResponse.getDittoHeaders().toBuilder().correlationId(correlationId).build()
        ));
        waitForCountDown(latch);
    }

    private void waitForCountDown(final CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (final Throwable error) {
            throw new AssertionError(error);
        }
        assertThat(latch.getCount()).isEqualTo(0L);
    }

    private CompletableFuture<Void> startConsumption() {
        final CompletableFuture<Void> result = client.live().startConsumption();
        expectMsg("START-SEND-LIVE-EVENTS");
        expectMsg("START-SEND-MESSAGES");
        expectMsg("START-SEND-LIVE-COMMANDS");
        reply("START-SEND-LIVE-EVENTS:ACK");
        reply("START-SEND-MESSAGES:ACK");
        reply("START-SEND-LIVE-COMMANDS:ACK");
        return result;
    }

    @FunctionalInterface
    private interface MessageHandlerRegistration<T, U> {

        void accept(String id, String subject, Class<T> clazz, Consumer<RepliableMessage<T, U>> handler);
    }
}
