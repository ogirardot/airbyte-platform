/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.general;

import static io.airbyte.workers.test_utils.TestConfigHelpers.DESTINATION_IMAGE;
import static io.airbyte.workers.test_utils.TestConfigHelpers.SOURCE_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.WorkloadApiClient;
import io.airbyte.api.client.generated.DestinationApi;
import io.airbyte.api.client.generated.DestinationDefinitionApi;
import io.airbyte.api.client.generated.SourceApi;
import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.api.client.model.generated.DestinationDefinitionRead;
import io.airbyte.commons.concurrency.VoidCallable;
import io.airbyte.commons.converters.ThreadedTimeTracker;
import io.airbyte.commons.json.Jsons;
import io.airbyte.persistence.job.models.ReplicationInput;
import io.airbyte.protocol.models.AirbyteAnalyticsTraceMessage;
import io.airbyte.protocol.models.AirbyteLogMessage;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.AirbyteStreamStatusRateLimitedReason;
import io.airbyte.protocol.models.AirbyteStreamStatusReason;
import io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage;
import io.airbyte.protocol.models.AirbyteTraceMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.StreamDescriptor;
import io.airbyte.workers.context.ReplicationContext;
import io.airbyte.workers.context.ReplicationFeatureFlags;
import io.airbyte.workers.helper.AirbyteMessageDataExtractor;
import io.airbyte.workers.helper.StreamStatusCompletionTracker;
import io.airbyte.workers.internal.AirbyteDestination;
import io.airbyte.workers.internal.AirbyteMapper;
import io.airbyte.workers.internal.AirbyteSource;
import io.airbyte.workers.internal.AnalyticsMessageTracker;
import io.airbyte.workers.internal.FieldSelector;
import io.airbyte.workers.internal.bookkeeping.AirbyteMessageOrigin;
import io.airbyte.workers.internal.bookkeeping.AirbyteMessageTracker;
import io.airbyte.workers.internal.bookkeeping.SyncStatsTracker;
import io.airbyte.workers.internal.bookkeeping.events.ReplicationAirbyteMessageEvent;
import io.airbyte.workers.internal.bookkeeping.events.ReplicationAirbyteMessageEventPublishingHelper;
import io.airbyte.workers.internal.syncpersistence.SyncPersistence;
import io.airbyte.workload.api.client.generated.WorkloadApi;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class ReplicationWorkerHelperTest {

  private ReplicationWorkerHelper replicationWorkerHelper;
  private AirbyteMapper mapper;
  private SyncStatsTracker syncStatsTracker;
  private AirbyteMessageTracker messageTracker;
  private SyncPersistence syncPersistence;
  private AnalyticsMessageTracker analyticsMessageTracker;
  private StreamStatusCompletionTracker streamStatusCompletionTracker;
  private WorkloadApiClient workloadApiClient;
  private AirbyteApiClient airbyteApiClient;
  private DestinationDefinitionApi destinationDefinitionApi;

  private ReplicationAirbyteMessageEventPublishingHelper replicationAirbyteMessageEventPublishingHelper;

  @BeforeEach
  void setUp() {
    mapper = mock(AirbyteMapper.class);
    syncStatsTracker = mock(SyncStatsTracker.class);
    syncPersistence = mock(SyncPersistence.class);
    messageTracker = mock(AirbyteMessageTracker.class);
    analyticsMessageTracker = mock(AnalyticsMessageTracker.class);
    streamStatusCompletionTracker = mock(StreamStatusCompletionTracker.class);
    workloadApiClient = mock(WorkloadApiClient.class);
    airbyteApiClient = mock(AirbyteApiClient.class);
    replicationAirbyteMessageEventPublishingHelper = mock(ReplicationAirbyteMessageEventPublishingHelper.class);
    when(messageTracker.getSyncStatsTracker()).thenReturn(syncStatsTracker);
    when(workloadApiClient.getWorkloadApi()).thenReturn(mock(WorkloadApi.class));
    when(airbyteApiClient.getDestinationApi()).thenReturn(mock(DestinationApi.class));
    when(airbyteApiClient.getSourceApi()).thenReturn(mock(SourceApi.class));
    destinationDefinitionApi = mock(DestinationDefinitionApi.class);
    when(airbyteApiClient.getDestinationDefinitionApi()).thenReturn(destinationDefinitionApi);
    replicationWorkerHelper = spy(new ReplicationWorkerHelper(
        new AirbyteMessageDataExtractor(),
        mock(FieldSelector.class),
        mapper,
        messageTracker,
        syncPersistence,
        replicationAirbyteMessageEventPublishingHelper,
        mock(ThreadedTimeTracker.class),
        mock(VoidCallable.class),
        workloadApiClient,
        false,
        analyticsMessageTracker,
        Optional.empty(),
        airbyteApiClient,
        streamStatusCompletionTracker, true));
  }

  @AfterEach
  void tearDown() {
    Mockito.framework().clearInlineMocks();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testGetReplicationOutput(final boolean supportRefreshes) throws JsonProcessingException, ApiException {
    mockSupportRefreshes(supportRefreshes);
    // Need to pass in a replication context
    final ConfiguredAirbyteCatalog catalog = new ConfiguredAirbyteCatalog().withAdditionalProperty("test", "test");
    final ReplicationContext replicationContext = new ReplicationContext(true, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0L,
        1, UUID.randomUUID(), SOURCE_IMAGE, DESTINATION_IMAGE, UUID.randomUUID(), UUID.randomUUID());
    replicationWorkerHelper.initialize(
        replicationContext,
        mock(ReplicationFeatureFlags.class),
        mock(Path.class),
        catalog);
    verify(streamStatusCompletionTracker).startTracking(catalog, replicationContext, supportRefreshes);
    // Need to have a configured catalog for getReplicationOutput
    replicationWorkerHelper.startDestination(
        mock(AirbyteDestination.class),
        new ReplicationInput().withCatalog(new ConfiguredAirbyteCatalog()),
        mock(Path.class));

    when(syncStatsTracker.getTotalBytesEmitted()).thenReturn(100L);
    when(syncStatsTracker.getTotalRecordsEmitted()).thenReturn(10L);
    when(syncStatsTracker.getTotalBytesCommitted()).thenReturn(50L);
    when(syncStatsTracker.getTotalRecordsCommitted()).thenReturn(5L);

    final var summary = replicationWorkerHelper.getReplicationOutput();
    assertEquals(50L, summary.getReplicationAttemptSummary().getBytesSynced());
    assertEquals(5L, summary.getReplicationAttemptSummary().getRecordsSynced());
  }

  @Test
  void testRateLimitedStreamStatusMessages() throws ApiException {
    mockSupportRefreshes(false);
    final Instant rateLimitedTimeStamp = Instant.parse("2024-05-29T09:25:27.000000Z");
    final Instant recordMessageTimestamp = Instant.parse("2024-05-29T09:25:28.000000Z");
    final StreamDescriptor streamDescriptor = new StreamDescriptor().withNamespace("namespace").withName("name");
    final ReplicationContext context =
        new ReplicationContext(true, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0L,
            1, UUID.randomUUID(), SOURCE_IMAGE, DESTINATION_IMAGE, UUID.randomUUID(), UUID.randomUUID());
    // Need to pass in a replication context
    replicationWorkerHelper.initialize(
        context,
        mock(ReplicationFeatureFlags.class),
        mock(Path.class),
        mock(ConfiguredAirbyteCatalog.class));
    // Need to have a configured catalog for getReplicationOutput
    replicationWorkerHelper.startDestination(
        mock(AirbyteDestination.class),
        new ReplicationInput().withCatalog(new ConfiguredAirbyteCatalog()),
        mock(Path.class));

    replicationWorkerHelper.startSource(
        mock(AirbyteSource.class),
        new ReplicationInput().withCatalog(new ConfiguredAirbyteCatalog()),
        mock(Path.class));

    final AirbyteMessage rateLimitedMessage = new AirbyteMessage()
        .withType(Type.TRACE)
        .withTrace(new AirbyteTraceMessage()
            .withType(AirbyteTraceMessage.Type.STREAM_STATUS)
            .withEmittedAt((double) rateLimitedTimeStamp.toEpochMilli())
            .withStreamStatus(new AirbyteStreamStatusTraceMessage()
                .withStreamDescriptor(streamDescriptor)
                .withStatus(AirbyteStreamStatusTraceMessage.AirbyteStreamStatus.RUNNING)
                .withReasons(Collections.singletonList(new AirbyteStreamStatusReason()
                    .withType(AirbyteStreamStatusReason.AirbyteStreamStatusReasonType.RATE_LIMITED)
                    .withRateLimited(new AirbyteStreamStatusRateLimitedReason().withQuotaReset(rateLimitedTimeStamp.toEpochMilli()))))));

    final AirbyteMessage recordMessage = new AirbyteMessage()
        .withType(Type.RECORD)
        .withRecord(new AirbyteRecordMessage()
            .withNamespace(streamDescriptor.getNamespace())
            .withStream(streamDescriptor.getName())
            .withData(Jsons.jsonNode(ImmutableMap.of("col", 1)))
            .withEmittedAt(recordMessageTimestamp.toEpochMilli()));

    replicationWorkerHelper.internalProcessMessageFromSource(rateLimitedMessage);
    replicationWorkerHelper.internalProcessMessageFromSource(recordMessage);

    final ReplicationAirbyteMessageEvent rateLimitedEvent = new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE,
        rateLimitedMessage, context);
    verify(replicationAirbyteMessageEventPublishingHelper).publishStatusEvent(rateLimitedEvent);
    verify(replicationAirbyteMessageEventPublishingHelper).publishRunningStatusEvent(streamDescriptor, context, AirbyteMessageOrigin.SOURCE,
        recordMessage.getRecord().getEmittedAt());
  }

  @Test
  void testAnalyticsMessageHandling() throws ApiException {
    mockSupportRefreshes(false);
    final ReplicationContext context =
        new ReplicationContext(true, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0L,
            1, UUID.randomUUID(), SOURCE_IMAGE, DESTINATION_IMAGE, UUID.randomUUID(), UUID.randomUUID());
    // Need to pass in a replication context
    replicationWorkerHelper.initialize(
        context,
        mock(ReplicationFeatureFlags.class),
        mock(Path.class),
        mock(ConfiguredAirbyteCatalog.class));
    // Need to have a configured catalog for getReplicationOutput
    replicationWorkerHelper.startDestination(
        mock(AirbyteDestination.class),
        new ReplicationInput().withCatalog(new ConfiguredAirbyteCatalog()),
        mock(Path.class));

    replicationWorkerHelper.startSource(
        mock(AirbyteSource.class),
        new ReplicationInput().withCatalog(new ConfiguredAirbyteCatalog()),
        mock(Path.class));

    final AirbyteMessage sourceMessage = new AirbyteMessage().withType(Type.TRACE).withTrace(new AirbyteTraceMessage()
        .withType(AirbyteTraceMessage.Type.ANALYTICS).withAnalytics(new AirbyteAnalyticsTraceMessage().withType("from").withValue("source")));
    final AirbyteMessage logMessage = new AirbyteMessage().withType(Type.LOG).withLog(new AirbyteLogMessage().withMessage("test"));
    final AirbyteMessage destinationMessage = new AirbyteMessage().withType(Type.TRACE).withTrace(new AirbyteTraceMessage()
        .withType(AirbyteTraceMessage.Type.ANALYTICS).withAnalytics(new AirbyteAnalyticsTraceMessage().withType("from").withValue("destination")));
    when(mapper.mapMessage(any())).thenAnswer(i -> i.getArgument(0));
    when(mapper.revertMap(any())).thenAnswer(i -> i.getArgument(0));

    replicationWorkerHelper.processMessageFromSource(sourceMessage);
    // this shouldn't be passed to the message tracker
    replicationWorkerHelper.processMessageFromSource(logMessage);
    replicationWorkerHelper.processMessageFromDestination(destinationMessage);

    replicationWorkerHelper.endOfReplication();
    verify(analyticsMessageTracker, times(1)).addMessage(sourceMessage, AirbyteMessageOrigin.SOURCE);
    verify(analyticsMessageTracker, times(1)).addMessage(destinationMessage, AirbyteMessageOrigin.DESTINATION);
    verify(analyticsMessageTracker, times(1)).flush();
  }

  @Test
  void testMessageIsMappedAfterProcessing() {
    final AirbyteMessage sourceRawMessage = mock(AirbyteMessage.class);
    final AirbyteMessage mappedSourceMessage = mock(AirbyteMessage.class);

    doReturn(sourceRawMessage).when(replicationWorkerHelper).internalProcessMessageFromSource(sourceRawMessage);
    when(mapper.mapMessage(sourceRawMessage)).thenReturn(mappedSourceMessage);

    final Optional<AirbyteMessage> processedMessageFromSource = replicationWorkerHelper.processMessageFromSource(sourceRawMessage);

    assertEquals(Optional.of(mappedSourceMessage), processedMessageFromSource);
  }

  @Test
  void testMessageMapIsRevertedBeforeProcessing() {
    final AirbyteMessage destinationRawMessage = mock(AirbyteMessage.class);
    final AirbyteMessage mapRevertedDestinationMessage = mock(AirbyteMessage.class);

    when(mapper.revertMap(destinationRawMessage)).thenReturn(mapRevertedDestinationMessage);
    doNothing().when(replicationWorkerHelper).internalProcessMessageFromDestination(mapRevertedDestinationMessage);

    replicationWorkerHelper.processMessageFromDestination(destinationRawMessage);

    verify(replicationWorkerHelper, times(1)).internalProcessMessageFromDestination(mapRevertedDestinationMessage);
  }

  private void mockSupportRefreshes(final boolean supportRefreshes) throws ApiException {
    when(destinationDefinitionApi.getDestinationDefinition(any())).thenReturn(new DestinationDefinitionRead().supportRefreshes(supportRefreshes));
  }

}
