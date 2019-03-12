/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.siddhi.operator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeutils.base.array.BytePrimitiveArraySerializer;
import org.apache.flink.streaming.siddhi.control.MetadataControlEvent;
import org.apache.flink.streaming.siddhi.control.OperationControlEvent;
import org.apache.flink.streaming.siddhi.exception.UndefinedStreamException;
import org.apache.flink.streaming.siddhi.control.ControlEventListener;
import org.apache.flink.streaming.siddhi.control.ControlEvent;
import org.apache.flink.streaming.siddhi.router.StreamRoute;
import org.apache.flink.streaming.siddhi.schema.StreamSchema;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamElementSerializer;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.siddhi.core.SiddhiAppRuntime;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.stream.input.InputHandler;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

/**
 * <h1>Siddhi Runtime Operator</h1>
 *
 * A flink Stream Operator to integrate with native siddhi execution runtime, extension and type schema mechanism/
 *
 * <ul>
 * <li>
 * Create Siddhi {@link org.wso2.siddhi.core.SiddhiAppRuntime} according predefined execution plan and integrate with Flink Stream Operator lifecycle.
 * </li>
 * <li>
 * Connect Flink DataStreams with predefined Siddhi Stream according to unique streamId
 * </li>
 * <li>
 * Convert native {@link StreamRecord} to Siddhi {@link org.wso2.siddhi.core.event.Event} according to {@link StreamSchema}, and send to Siddhi Runtime.
 * </li>
 * <li>
 * Listen output callback event and convert as expected output type according to output {@link org.apache.flink.api.common.typeinfo.TypeInformation}, then output as typed DataStream.
 * </li>
 * </li>
 * <li>
 * Integrate siddhi runtime state management with Flink state (See `AbstractSiddhiOperator`)
 * </li>
 * <li>
 * Support siddhi plugin management to extend CEP functions. (See `SiddhiCEP#registerExtension`)
 * </li>
 * </ul>
 *
 * @param <IN>  Input Element Type
 * @param <OUT> Output Element Type
 */
public abstract class AbstractSiddhiOperator<IN, OUT> extends AbstractStreamOperator<OUT>
    implements OneInputStreamOperator<IN, OUT>, ControlEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSiddhiOperator.class);
    private static final int INITIAL_PRIORITY_QUEUE_CAPACITY = 11;
    private static final String SIDDHI_RUNTIME_STATE_NAME = "siddhiRuntimeState";
    private static final String QUEUED_RECORDS_STATE_NAME = "queuedRecordsState";

    private final SiddhiOperatorContext siddhiPlan;
    private final boolean isProcessingTime;
    private final Map<String, StreamElementSerializer<IN>> streamRecordSerializers;

    private transient SiddhiManager siddhiManager;

    // queue to buffer out of order stream records
    private transient PriorityQueue<StreamRecord<IN>> priorityQueue;

    private transient ListState<byte[]> siddhiRuntimeState;
    private transient ListState<byte[]> queuedRecordsState;

    private transient ConcurrentHashMap<String, QueryRuntimeHandler> siddhiRuntimeHandlers;

    private class QueryRuntimeHandler {
        private final SiddhiAppRuntime siddhiRuntime;
        private final Map<String, InputHandler> inputStreamHandlers = new HashMap<>();
        private AtomicLong count = new AtomicLong(0);
        private AtomicBoolean enabled = new AtomicBoolean(false);

        QueryRuntimeHandler(String executionPlan) {
            this.siddhiRuntime = siddhiManager.createSiddhiAppRuntime(executionPlan);
        }

        /**
         * Send input data to siddhi runtime
         */
        void send(String streamId, Object[] data, long timestamp) throws InterruptedException {
            if (this.enabled.get()) {
                count.incrementAndGet();
                inputStreamHandlers.get(streamId).send(timestamp, data);
            }
        }

        /**
         * Create and start execution runtime
         */
        private void start() {
            this.enable();
            this.siddhiRuntime.start();
            registerInputAndOutput();
            LOGGER.info("Siddhi {} started", siddhiRuntime.getName());
        }

        private void shutdown() {
            this.siddhiRuntime.shutdown();
            this.disable();
            LOGGER.info("Siddhi {} shutdown, processed {} events", this.siddhiRuntime.getName(), count.get());
        }

        public void enable() {
            this.enabled.set(true);
        }

        public void disable() {
            this.enabled.set(false);
        }

        @SuppressWarnings("unchecked")
        private void registerInputAndOutput() {
            Map<String, StreamDefinition> streamDefinitionMap = siddhiRuntime.getStreamDefinitionMap();

            for (String outputStreamId : siddhiPlan.getOutputStreamTypes().keySet()) {
                AbstractDefinition definition = this.siddhiRuntime.getStreamDefinitionMap().get(outputStreamId);
                if (streamDefinitionMap.containsKey(outputStreamId)) {
                    siddhiRuntime.addCallback(outputStreamId,
                        new StreamOutputHandler<>(outputStreamId, siddhiPlan.getOutputStreamType(outputStreamId), definition, output));
                }
            }

            for (String inputStreamId : siddhiPlan.getInputStreams()) {
                if (streamDefinitionMap.containsKey(inputStreamId)) {
                    inputStreamHandlers.put(inputStreamId, siddhiRuntime.getInputHandler(inputStreamId));
                }
            }
        }
    }

    /**
     * @param siddhiPlan Siddhi CEP  Execution Plan
     */
    public AbstractSiddhiOperator(SiddhiOperatorContext siddhiPlan) {
        validate(siddhiPlan);
        this.siddhiPlan = siddhiPlan;
        this.isProcessingTime = this.siddhiPlan.getTimeCharacteristic() == TimeCharacteristic.ProcessingTime;
        this.streamRecordSerializers = new HashMap<>();

        registerStreamRecordSerializers();
    }

    /**
     * Register StreamRecordSerializer based on {@link StreamSchema}
     */
    private void registerStreamRecordSerializers() {
        for (String streamId : this.siddhiPlan.getInputStreams()) {
            streamRecordSerializers.put(streamId, createStreamRecordSerializer(this.siddhiPlan.getInputStreamSchema(streamId), this.siddhiPlan.getExecutionConfig()));
        }
    }

    protected abstract StreamElementSerializer<IN> createStreamRecordSerializer(StreamSchema streamSchema, ExecutionConfig executionConfig);

    protected StreamElementSerializer<IN> getStreamRecordSerializer(String streamId) {
        if (streamRecordSerializers.containsKey(streamId)) {
            return streamRecordSerializers.get(streamId);
        } else {
            throw new UndefinedStreamException("Stream " + streamId + " not defined");
        }
    }

    @Override
    public void processElement(StreamRecord<IN> element) throws Exception {
        if (isControlStream(element.getValue())) {
            this.onEventReceived(getControlEvent(element.getValue()));
            return;
        }
        String streamId = getStreamId(element.getValue());
        StreamSchema<IN> schema = siddhiPlan.getInputStreamSchema(streamId);

        if (isProcessingTime) {
            processEvent(streamId, schema, element.getValue(), System.currentTimeMillis());
            this.checkpointSiddhiRuntimeState();
        } else {
            PriorityQueue<StreamRecord<IN>> priorityQueue = getPriorityQueue();
            // event time processing
            // we have to buffer the elements until we receive the proper watermark
            if (getExecutionConfig().isObjectReuseEnabled()) {
                // copy the StreamRecord so that it cannot be changed
                priorityQueue.offer(new StreamRecord<>(schema.getTypeSerializer().copy(element.getValue()), element.getTimestamp()));
            } else {
                priorityQueue.offer(element);
            }
            this.checkpointRecordQueueState();
        }
    }

    protected abstract void processEvent(String streamId, StreamSchema<IN> schema, IN value, long timestamp) throws Exception;

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        while (!priorityQueue.isEmpty() && priorityQueue.peek().getTimestamp() <= mark.getTimestamp()) {
            StreamRecord<IN> streamRecord = priorityQueue.poll();
            String streamId = getStreamId(streamRecord.getValue());
            long timestamp = streamRecord.getTimestamp();
            StreamSchema<IN> schema = siddhiPlan.getInputStreamSchema(streamId);
            processEvent(streamId, schema, streamRecord.getValue(), timestamp);
        }
        output.emitWatermark(mark);
    }

    public abstract String getStreamId(IN record);

    public abstract boolean isControlStream(IN record);

    public abstract ControlEvent getControlEvent(IN record);

    public PriorityQueue<StreamRecord<IN>> getPriorityQueue() {
        return priorityQueue;
    }

    protected SiddhiOperatorContext getSiddhiPlan() {
        return this.siddhiPlan;
    }

    @Override
    public void setup(StreamTask<?, ?> containingTask, StreamConfig config, Output<StreamRecord<OUT>> output) {
        super.setup(containingTask, config, output);
        if (priorityQueue == null) {
            priorityQueue = new PriorityQueue<>(INITIAL_PRIORITY_QUEUE_CAPACITY, new StreamRecordComparator<IN>());
        }
        if (siddhiRuntimeHandlers == null) {
            siddhiRuntimeHandlers = new ConcurrentHashMap<>();
        }
    }

    @Override
    public void open() throws Exception {
        super.open();
        startSiddhiManager();
    }

    /**
     * Send input data to siddhi runtime
     */
    void send(StreamRoute streamRoute, Object[] data, long timestamp) throws InterruptedException {
        for (String executionPlanId : streamRoute.getExecutionPlanIds()) {
            this.siddhiRuntimeHandlers.get(executionPlanId).send(streamRoute.getInputStreamId(), data, timestamp);
        }
    }

    /**
     * Validate execution plan during building DAG before submitting to execution environment and fail-fast.
     */
    private static void validate(final SiddhiOperatorContext siddhiPlan) {
        SiddhiManager siddhiManager = siddhiPlan.createSiddhiManager();
        try {
            siddhiManager.validateSiddhiApp(siddhiPlan.getAllEnrichedExecutionPlan());
        } finally {
            siddhiManager.shutdown();
        }
    }

    private void startSiddhiManager() {
        this.siddhiManager = this.siddhiPlan.createSiddhiManager();

        for (Map.Entry<String, Class<?>> entry : this.siddhiPlan.getExtensions().entrySet()) {
            this.siddhiManager.setExtension(entry.getKey(), entry.getValue());
        }

        for (String id: this.siddhiPlan.getExecutionPlanMap().keySet()) {
            QueryRuntimeHandler handler = new QueryRuntimeHandler(this.siddhiPlan.getEnrichedExecutionPlan(id));
            handler.start();
            this.siddhiRuntimeHandlers.put(id, handler);
        }
    }

    @Override
    public void close() throws Exception {
        for (QueryRuntimeHandler executor: this.siddhiRuntimeHandlers.values()) {
            executor.shutdown();
        }
        this.siddhiRuntimeHandlers.clear();
        super.close();
    }

    @Override
    public void dispose() throws Exception {
        this.siddhiRuntimeState.clear();
        super.dispose();
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        checkpointSiddhiRuntimeState();
        checkpointRecordQueueState();
    }

    private void restoreState() throws Exception {
        LOGGER.info("Restore siddhi state");
        final Iterator<byte[]> siddhiState = siddhiRuntimeState.get().iterator();
        if (siddhiState.hasNext()) {
            // TODO this.siddhiRuntime.restore(siddhiState.next());
        }

        LOGGER.info("Restore queued records state");
        final Iterator<byte[]> queueState = queuedRecordsState.get().iterator();
        if (queueState.hasNext()) {
            final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(queueState.next());
            final DataInputViewStreamWrapper dataInputView = new DataInputViewStreamWrapper(byteArrayInputStream);
            try {
                this.priorityQueue = restoreQueueState(dataInputView);
            } finally {
                dataInputView.close();
                byteArrayInputStream.close();
            }
        }
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);
        if (siddhiRuntimeState == null) {
            siddhiRuntimeState = context.getOperatorStateStore().getUnionListState(new ListStateDescriptor<>(SIDDHI_RUNTIME_STATE_NAME,
                    new BytePrimitiveArraySerializer()));
        }
        if (queuedRecordsState == null) {
            queuedRecordsState = context.getOperatorStateStore().getListState(
                new ListStateDescriptor<>(QUEUED_RECORDS_STATE_NAME, new BytePrimitiveArraySerializer()));
        }
        if (context.isRestored()) {
            restoreState();
        }
    }

    private void checkpointSiddhiRuntimeState() throws Exception {
        this.siddhiRuntimeState.clear();
        for (Map.Entry<String, QueryRuntimeHandler> entry : this.siddhiRuntimeHandlers.entrySet()) {
            this.siddhiRuntimeState.add(entry.getValue().siddhiRuntime.snapshot());
        }
        this.queuedRecordsState.clear();
    }

    private void checkpointRecordQueueState() throws Exception {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final DataOutputViewStreamWrapper dataOutputView = new DataOutputViewStreamWrapper(byteArrayOutputStream);
        try {
            snapshotQueueState(this.priorityQueue, dataOutputView);
            this.queuedRecordsState.clear();
            this.queuedRecordsState.add(byteArrayOutputStream.toByteArray());
        } finally {
            dataOutputView.close();
            byteArrayOutputStream.close();
        }
    }

    protected abstract void snapshotQueueState(PriorityQueue<StreamRecord<IN>> queue, DataOutputView dataOutputView) throws IOException;

    protected abstract PriorityQueue<StreamRecord<IN>> restoreQueueState(DataInputView dataInputView) throws IOException;

    @Override
    public void onEventReceived(ControlEvent event) {
        if (event == null) {
            LOGGER.warn("Null control event received and ignored");
        }
        if (event instanceof MetadataControlEvent) {
            final MetadataControlEvent metadataControlEvent = (MetadataControlEvent) event;
            if (metadataControlEvent.getDeletedExecutionPlanId() != null) {
                for (String planId : metadataControlEvent.getDeletedExecutionPlanId()) {
                    this.siddhiPlan.removeExecutionPlan(planId);
                    final QueryRuntimeHandler handler = siddhiRuntimeHandlers.remove(planId);
                    if (handler != null) {
                        handler.shutdown();
                    }
                }
            }

            if (metadataControlEvent.getAddedExecutionPlanMap() != null) {
                for (Map.Entry<String, String> entry : metadataControlEvent.getAddedExecutionPlanMap().entrySet()) {
                    this.siddhiPlan.addExecutionPlan(entry.getKey(), entry.getValue());
                    final QueryRuntimeHandler handler =
                        new QueryRuntimeHandler(this.siddhiPlan.getEnrichedExecutionPlan(entry.getKey()));
                    handler.start();
                    siddhiRuntimeHandlers.put(entry.getKey(), handler);
                }
            }

            if (metadataControlEvent.getUpdatedExecutionPlanMap() != null) {
                for (Map.Entry<String, String> entry : metadataControlEvent.getUpdatedExecutionPlanMap().entrySet()) {
                    this.siddhiPlan.updateExecutionPlan(entry.getKey(), entry.getValue());
                    QueryRuntimeHandler oldHandler = siddhiRuntimeHandlers.get(entry.getKey());
                    final QueryRuntimeHandler handler =
                        new QueryRuntimeHandler(this.siddhiPlan.getEnrichedExecutionPlan(entry.getKey()));
                    handler.start();
                    siddhiRuntimeHandlers.put(entry.getKey(), handler);
                    if (oldHandler != null) {
                        oldHandler.shutdown();
                    }
                }
            }
        } else if (event instanceof OperationControlEvent) {
            final OperationControlEvent.Action action = ((OperationControlEvent) event).getAction();
            if (action == null) {
                LOGGER.warn("OperationControlEvent.Action is null");
                return;
            }
            switch (action) {
                case ENABLE_QUERY:
                    // Pause query
                    QueryRuntimeHandler handler = siddhiRuntimeHandlers
                        .get(((OperationControlEvent) event).getQueryId());
                    if (handler != null) {
                        handler.enable();
                    }
                    break;
                case DISABLE_QUERY:
                    // Resume query
                    handler = siddhiRuntimeHandlers.get(((OperationControlEvent) event).getQueryId());
                    if (handler != null) {
                        handler.disable();
                    }
                    break;
                default:
                    throw new IllegalStateException("Illegal action type " + action + ": " + event);
            }
        } else {
            throw new IllegalStateException("Illegal event type " + event);
        }
    }
}
