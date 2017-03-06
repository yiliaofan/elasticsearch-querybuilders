/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.codelibs.elasticsearch.action.bulk;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.codelibs.elasticsearch.ElasticsearchException;
import org.codelibs.elasticsearch.ExceptionsHelper;
import org.codelibs.elasticsearch.action.ActionRequest;
import org.codelibs.elasticsearch.action.delete.DeleteRequest;
import org.codelibs.elasticsearch.action.delete.DeleteResponse;
import org.codelibs.elasticsearch.action.delete.TransportDeleteAction;
import org.codelibs.elasticsearch.action.index.IndexRequest;
import org.codelibs.elasticsearch.action.index.IndexResponse;
import org.codelibs.elasticsearch.action.index.TransportIndexAction;
import org.codelibs.elasticsearch.action.support.ActionFilters;
import org.codelibs.elasticsearch.action.support.replication.ReplicationRequest;
import org.codelibs.elasticsearch.action.support.replication.ReplicationResponse.ShardInfo;
import org.codelibs.elasticsearch.action.support.replication.TransportWriteAction;
import org.codelibs.elasticsearch.action.update.UpdateHelper;
import org.codelibs.elasticsearch.action.update.UpdateRequest;
import org.codelibs.elasticsearch.action.update.UpdateResponse;
import org.codelibs.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.codelibs.elasticsearch.cluster.action.shard.ShardStateAction;
import org.codelibs.elasticsearch.cluster.metadata.IndexMetaData;
import org.codelibs.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.codelibs.elasticsearch.cluster.metadata.MappingMetaData;
import org.codelibs.elasticsearch.cluster.service.ClusterService;
import org.codelibs.elasticsearch.common.bytes.BytesReference;
import org.codelibs.elasticsearch.common.collect.Tuple;
import org.codelibs.elasticsearch.common.inject.Inject;
import org.codelibs.elasticsearch.common.settings.Settings;
import org.codelibs.elasticsearch.common.xcontent.XContentHelper;
import org.codelibs.elasticsearch.common.xcontent.XContentType;
import org.codelibs.elasticsearch.index.VersionType;
import org.codelibs.elasticsearch.index.engine.Engine;
import org.codelibs.elasticsearch.index.engine.VersionConflictEngineException;
import org.codelibs.elasticsearch.index.shard.IndexShard;
import org.codelibs.elasticsearch.index.shard.ShardId;
import org.codelibs.elasticsearch.index.translog.Translog;
import org.codelibs.elasticsearch.index.translog.Translog.Location;
import org.codelibs.elasticsearch.indices.IndicesService;
import org.codelibs.elasticsearch.rest.RestStatus;
import org.codelibs.elasticsearch.threadpool.ThreadPool;
import org.codelibs.elasticsearch.transport.TransportRequestOptions;
import org.codelibs.elasticsearch.transport.TransportService;

import java.util.Map;

import static org.codelibs.elasticsearch.action.support.replication.ReplicationOperation.ignoreReplicaException;
import static org.codelibs.elasticsearch.action.support.replication.ReplicationOperation.isConflictException;

/**
 * Performs the index operation.
 */
public class TransportShardBulkAction extends TransportWriteAction<BulkShardRequest, BulkShardResponse> {

    private static final String OP_TYPE_UPDATE = "update";
    private static final String OP_TYPE_DELETE = "delete";

    public static final String ACTION_NAME = BulkAction.NAME + "[s]";

    private final UpdateHelper updateHelper;
    private final boolean allowIdGeneration;
    private final MappingUpdatedAction mappingUpdatedAction;

    @Inject
    public TransportShardBulkAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                    IndicesService indicesService, ThreadPool threadPool, ShardStateAction shardStateAction,
                                    MappingUpdatedAction mappingUpdatedAction, UpdateHelper updateHelper, ActionFilters actionFilters,
                                    IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ACTION_NAME, transportService, clusterService, indicesService, threadPool, shardStateAction, actionFilters,
                indexNameExpressionResolver, BulkShardRequest::new, ThreadPool.Names.BULK);
        this.updateHelper = updateHelper;
        this.allowIdGeneration = settings.getAsBoolean("action.allow_id_generation", true);
        this.mappingUpdatedAction = mappingUpdatedAction;
    }

    @Override
    protected TransportRequestOptions transportOptions() {
        return BulkAction.INSTANCE.transportOptions(settings);
    }

    @Override
    protected BulkShardResponse newResponseInstance() {
        return new BulkShardResponse();
    }

    @Override
    protected boolean resolveIndex() {
        return false;
    }

    @Override
    protected WriteResult<BulkShardResponse> onPrimaryShard(BulkShardRequest request, IndexShard primary) throws Exception {
        final IndexMetaData metaData = primary.indexSettings().getIndexMetaData();

        long[] preVersions = new long[request.items().length];
        VersionType[] preVersionTypes = new VersionType[request.items().length];
        Translog.Location location = null;
        for (int requestIndex = 0; requestIndex < request.items().length; requestIndex++) {
            BulkItemRequest item = request.items()[requestIndex];
            location = handleItem(metaData, request, primary, preVersions, preVersionTypes, location, requestIndex, item);
        }

        BulkItemResponse[] responses = new BulkItemResponse[request.items().length];
        BulkItemRequest[] items = request.items();
        for (int i = 0; i < items.length; i++) {
            responses[i] = items[i].getPrimaryResponse();
        }
        BulkShardResponse response = new BulkShardResponse(request.shardId(), responses);
        return new WriteResult<>(response, location);
    }

    private Translog.Location handleItem(IndexMetaData metaData, BulkShardRequest request, IndexShard indexShard, long[] preVersions, VersionType[] preVersionTypes, Translog.Location location, int requestIndex, BulkItemRequest item) {
        if (item.request() instanceof IndexRequest) {
            location = index(metaData, request, indexShard, preVersions, preVersionTypes, location, requestIndex, item);
        } else if (item.request() instanceof DeleteRequest) {
            location = delete(request, indexShard, preVersions, preVersionTypes, location, requestIndex, item);
        } else if (item.request() instanceof UpdateRequest) {
            Tuple<Translog.Location, BulkItemRequest> tuple = update(metaData, request, indexShard, preVersions, preVersionTypes, location, requestIndex, item);
            location = tuple.v1();
            item = tuple.v2();
        } else {
            throw new IllegalStateException("Unexpected index operation: " + item.request());
        }

        assert item.getPrimaryResponse() != null;
        assert preVersionTypes[requestIndex] != null;
        return location;
    }

    private Translog.Location index(IndexMetaData metaData, BulkShardRequest request, IndexShard indexShard, long[] preVersions, VersionType[] preVersionTypes, Translog.Location location, int requestIndex, BulkItemRequest item) {
        IndexRequest indexRequest = (IndexRequest) item.request();
        preVersions[requestIndex] = indexRequest.version();
        preVersionTypes[requestIndex] = indexRequest.versionType();
        try {
            WriteResult<IndexResponse> result = shardIndexOperation(request, indexRequest, metaData, indexShard, true);
            location = locationToSync(location, result.getLocation());
            // add the response
            IndexResponse indexResponse = result.getResponse();
            setResponse(item, new BulkItemResponse(item.id(), indexRequest.opType().lowercase(), indexResponse));
        } catch (Exception e) {
            // rethrow the failure if we are going to retry on primary and let parent failure to handle it
            if (retryPrimaryException(e)) {
                // restore updated versions...
                for (int j = 0; j < requestIndex; j++) {
                    applyVersion(request.items()[j], preVersions[j], preVersionTypes[j]);
                }
                throw (ElasticsearchException) e;
            }
            logFailure(e, "index", request.shardId(), indexRequest);
            // if its a conflict failure, and we already executed the request on a primary (and we execute it
            // again, due to primary relocation and only processing up to N bulk items when the shard gets closed)
            // then just use the response we got from the successful execution
            if (item.getPrimaryResponse() != null && isConflictException(e)) {
                setResponse(item, item.getPrimaryResponse());
            } else {
                setResponse(item, new BulkItemResponse(item.id(), indexRequest.opType().lowercase(),
                        new BulkItemResponse.Failure(request.index(), indexRequest.type(), indexRequest.id(), e)));
            }
        }
        return location;
    }

    private <ReplicationRequestT extends ReplicationRequest<ReplicationRequestT>> void logFailure(Throwable t, String operation, ShardId shardId, ReplicationRequest<ReplicationRequestT> request) {
        if (ExceptionsHelper.status(t) == RestStatus.CONFLICT) {
            logger.trace((Supplier<?>) () -> new ParameterizedMessage("{} failed to execute bulk item ({}) {}", shardId, operation, request), t);
        } else {
            logger.debug((Supplier<?>) () -> new ParameterizedMessage("{} failed to execute bulk item ({}) {}", shardId, operation, request), t);
        }
    }

    private Translog.Location delete(BulkShardRequest request, IndexShard indexShard, long[] preVersions, VersionType[] preVersionTypes, Translog.Location location, int requestIndex, BulkItemRequest item) {
        DeleteRequest deleteRequest = (DeleteRequest) item.request();
        preVersions[requestIndex] = deleteRequest.version();
        preVersionTypes[requestIndex] = deleteRequest.versionType();

        try {
            // add the response
            final WriteResult<DeleteResponse> writeResult = TransportDeleteAction.executeDeleteRequestOnPrimary(deleteRequest, indexShard);
            DeleteResponse deleteResponse = writeResult.getResponse();
            location = locationToSync(location, writeResult.getLocation());
            setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_DELETE, deleteResponse));
        } catch (Exception e) {
            // rethrow the failure if we are going to retry on primary and let parent failure to handle it
            if (retryPrimaryException(e)) {
                // restore updated versions...
                for (int j = 0; j < requestIndex; j++) {
                    applyVersion(request.items()[j], preVersions[j], preVersionTypes[j]);
                }
                throw (ElasticsearchException) e;
            }
            logFailure(e, "delete", request.shardId(), deleteRequest);
            // if its a conflict failure, and we already executed the request on a primary (and we execute it
            // again, due to primary relocation and only processing up to N bulk items when the shard gets closed)
            // then just use the response we got from the successful execution
            if (item.getPrimaryResponse() != null && isConflictException(e)) {
                setResponse(item, item.getPrimaryResponse());
            } else {
                setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_DELETE,
                        new BulkItemResponse.Failure(request.index(), deleteRequest.type(), deleteRequest.id(), e)));
            }
        }
        return location;
    }

    private Tuple<Translog.Location, BulkItemRequest> update(IndexMetaData metaData, BulkShardRequest request, IndexShard indexShard, long[] preVersions, VersionType[] preVersionTypes, Translog.Location location, int requestIndex, BulkItemRequest item) {
        UpdateRequest updateRequest = (UpdateRequest) item.request();
        preVersions[requestIndex] = updateRequest.version();
        preVersionTypes[requestIndex] = updateRequest.versionType();
        //  We need to do the requested retries plus the initial attempt. We don't do < 1+retry_on_conflict because retry_on_conflict may be Integer.MAX_VALUE
        for (int updateAttemptsCount = 0; updateAttemptsCount <= updateRequest.retryOnConflict(); updateAttemptsCount++) {
            UpdateResult updateResult;
            try {
                updateResult = shardUpdateOperation(metaData, request, updateRequest, indexShard);
            } catch (Exception t) {
                updateResult = new UpdateResult(null, null, false, t, null);
            }
            if (updateResult.success()) {
                if (updateResult.writeResult != null) {
                    location = locationToSync(location, updateResult.writeResult.getLocation());
                }
                switch (updateResult.result.getResponseResult()) {
                    case CREATED:
                    case UPDATED:
                        @SuppressWarnings("unchecked")
                        WriteResult<IndexResponse> result = updateResult.writeResult;
                        IndexRequest indexRequest = updateResult.request();
                        BytesReference indexSourceAsBytes = indexRequest.source();
                        // add the response
                        IndexResponse indexResponse = result.getResponse();
                        UpdateResponse updateResponse = new UpdateResponse(indexResponse.getShardInfo(), indexResponse.getShardId(), indexResponse.getType(), indexResponse.getId(), indexResponse.getVersion(), indexResponse.getResult());
                        if ((updateRequest.fetchSource() != null && updateRequest.fetchSource().fetchSource()) ||
                            (updateRequest.fields() != null && updateRequest.fields().length > 0)) {
                            Tuple<XContentType, Map<String, Object>> sourceAndContent = XContentHelper.convertToMap(indexSourceAsBytes, true);
                            updateResponse.setGetResult(updateHelper.extractGetResult(updateRequest, request.index(), indexResponse.getVersion(), sourceAndContent.v2(), sourceAndContent.v1(), indexSourceAsBytes));
                        }
                        item = request.items()[requestIndex] = new BulkItemRequest(request.items()[requestIndex].id(), indexRequest);
                        setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_UPDATE, updateResponse));
                        break;
                    case DELETED:
                        @SuppressWarnings("unchecked")
                        WriteResult<DeleteResponse> writeResult = updateResult.writeResult;
                        DeleteResponse response = writeResult.getResponse();
                        DeleteRequest deleteRequest = updateResult.request();
                        updateResponse = new UpdateResponse(response.getShardInfo(), response.getShardId(), response.getType(), response.getId(), response.getVersion(), response.getResult());
                        updateResponse.setGetResult(updateHelper.extractGetResult(updateRequest, request.index(), response.getVersion(), updateResult.result.updatedSourceAsMap(), updateResult.result.updateSourceContentType(), null));
                        // Replace the update request to the translated delete request to execute on the replica.
                        item = request.items()[requestIndex] = new BulkItemRequest(request.items()[requestIndex].id(), deleteRequest);
                        setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_UPDATE, updateResponse));
                        break;
                    case NOOP:
                        setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_UPDATE, updateResult.noopResult));
                        item.setIgnoreOnReplica(); // no need to go to the replica
                        break;
                    default:
                        throw new IllegalStateException("Illegal operation " + updateResult.result.getResponseResult());
                }
                // NOTE: Breaking out of the retry_on_conflict loop!
                break;
            } else if (updateResult.failure()) {
                Throwable e = updateResult.error;
                if (updateResult.retry) {
                    // updateAttemptCount is 0 based and marks current attempt, if it's equal to retryOnConflict we are going out of the iteration
                    if (updateAttemptsCount >= updateRequest.retryOnConflict()) {
                        setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_UPDATE,
                            new BulkItemResponse.Failure(request.index(), updateRequest.type(), updateRequest.id(), e)));
                    }
                } else {
                    // rethrow the failure if we are going to retry on primary and let parent failure to handle it
                    if (retryPrimaryException(e)) {
                        // restore updated versions...
                        for (int j = 0; j < requestIndex; j++) {
                            applyVersion(request.items()[j], preVersions[j], preVersionTypes[j]);
                        }
                        throw (ElasticsearchException) e;
                    }
                    // if its a conflict failure, and we already executed the request on a primary (and we execute it
                    // again, due to primary relocation and only processing up to N bulk items when the shard gets closed)
                    // then just use the response we got from the successful execution
                    if (item.getPrimaryResponse() != null && isConflictException(e)) {
                        setResponse(item, item.getPrimaryResponse());
                    } else if (updateResult.result == null) {
                        setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_UPDATE, new BulkItemResponse.Failure(request.index(), updateRequest.type(), updateRequest.id(), e)));
                    } else {
                        switch (updateResult.result.getResponseResult()) {
                            case CREATED:
                            case UPDATED:
                                IndexRequest indexRequest = updateResult.request();
                                logFailure(e, "index", request.shardId(), indexRequest);
                                setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_UPDATE,
                                    new BulkItemResponse.Failure(request.index(), indexRequest.type(), indexRequest.id(), e)));
                                break;
                            case DELETED:
                                DeleteRequest deleteRequest = updateResult.request();
                                logFailure(e, "delete", request.shardId(), deleteRequest);
                                setResponse(item, new BulkItemResponse(item.id(), OP_TYPE_DELETE,
                                    new BulkItemResponse.Failure(request.index(), deleteRequest.type(), deleteRequest.id(), e)));
                                break;
                            default:
                                throw new IllegalStateException("Illegal operation " + updateResult.result.getResponseResult());
                        }
                    }
                    // NOTE: Breaking out of the retry_on_conflict loop!
                    break;
                }

            }
        }
        return Tuple.tuple(location, item);
    }

    private void setResponse(BulkItemRequest request, BulkItemResponse response) {
        request.setPrimaryResponse(response);
        if (response.isFailed()) {
            request.setIgnoreOnReplica();
        } else {
            // Set the ShardInfo to 0 so we can safely send it to the replicas. We won't use it in the real response though.
            response.getResponse().setShardInfo(new ShardInfo());
        }
    }

    private WriteResult<IndexResponse> shardIndexOperation(BulkShardRequest request, IndexRequest indexRequest, IndexMetaData metaData,
            IndexShard indexShard, boolean processed) throws Exception {

        MappingMetaData mappingMd = metaData.mappingOrDefault(indexRequest.type());
        if (!processed) {
            indexRequest.process(mappingMd, allowIdGeneration, request.index());
        }
        return TransportIndexAction.executeIndexRequestOnPrimary(indexRequest, indexShard, mappingUpdatedAction);
    }

    static class UpdateResult {

        final UpdateHelper.Result result;
        final ActionRequest actionRequest;
        final boolean retry;
        final Throwable error;
        final WriteResult writeResult;
        final UpdateResponse noopResult;

        UpdateResult(UpdateHelper.Result result, ActionRequest actionRequest, boolean retry, Throwable error, WriteResult writeResult) {
            this.result = result;
            this.actionRequest = actionRequest;
            this.retry = retry;
            this.error = error;
            this.writeResult = writeResult;
            this.noopResult = null;
        }

        UpdateResult(UpdateHelper.Result result, ActionRequest actionRequest, WriteResult writeResult) {
            this.result = result;
            this.actionRequest = actionRequest;
            this.writeResult = writeResult;
            this.retry = false;
            this.error = null;
            this.noopResult = null;
        }

        public UpdateResult(UpdateHelper.Result result, UpdateResponse updateResponse) {
            this.result = result;
            this.noopResult = updateResponse;
            this.actionRequest = null;
            this.writeResult = null;
            this.retry = false;
            this.error = null;
        }


        boolean failure() {
            return error != null;
        }

        boolean success() {
            return noopResult != null || writeResult != null;
        }

        @SuppressWarnings("unchecked")
        <T extends ActionRequest> T request() {
            return (T) actionRequest;
        }


    }

    private UpdateResult shardUpdateOperation(IndexMetaData metaData, BulkShardRequest bulkShardRequest, UpdateRequest updateRequest, IndexShard indexShard) {
        UpdateHelper.Result translate = updateHelper.prepare(updateRequest, indexShard, threadPool::estimatedTimeInMillis);
        switch (translate.getResponseResult()) {
            case CREATED:
            case UPDATED:
                IndexRequest indexRequest = translate.action();
                try {
                    WriteResult result = shardIndexOperation(bulkShardRequest, indexRequest, metaData, indexShard, false);
                    return new UpdateResult(translate, indexRequest, result);
                } catch (Exception e) {
                    final Throwable cause = ExceptionsHelper.unwrapCause(e);
                    boolean retry = false;
                    if (cause instanceof VersionConflictEngineException) {
                        retry = true;
                    }
                    return new UpdateResult(translate, indexRequest, retry, cause, null);
                }
            case DELETED:
                DeleteRequest deleteRequest = translate.action();
                try {
                    WriteResult<DeleteResponse> result = TransportDeleteAction.executeDeleteRequestOnPrimary(deleteRequest, indexShard);
                    return new UpdateResult(translate, deleteRequest, result);
                } catch (Exception e) {
                    final Throwable cause = ExceptionsHelper.unwrapCause(e);
                    boolean retry = false;
                    if (cause instanceof VersionConflictEngineException) {
                        retry = true;
                    }
                    return new UpdateResult(translate, deleteRequest, retry, cause, null);
                }
            case NOOP:
                UpdateResponse updateResponse = translate.action();
                indexShard.noopUpdate(updateRequest.type());
                return new UpdateResult(translate, updateResponse);
            default:
                throw new IllegalStateException("Illegal update operation " + translate.getResponseResult());
        }
    }

    @Override
    protected Location onReplicaShard(BulkShardRequest request, IndexShard indexShard) {
        Translog.Location location = null;
        for (int i = 0; i < request.items().length; i++) {
            BulkItemRequest item = request.items()[i];
            if (item == null || item.isIgnoreOnReplica()) {
                continue;
            }
            if (item.request() instanceof IndexRequest) {
                IndexRequest indexRequest = (IndexRequest) item.request();
                try {
                    Engine.Index operation = TransportIndexAction.executeIndexRequestOnReplica(indexRequest, indexShard);
                    location = locationToSync(location, operation.getTranslogLocation());
                } catch (Exception e) {
                    // if its not an ignore replica failure, we need to make sure to bubble up the failure
                    // so we will fail the shard
                    if (!ignoreReplicaException(e)) {
                        throw e;
                    }
                }
            } else if (item.request() instanceof DeleteRequest) {
                DeleteRequest deleteRequest = (DeleteRequest) item.request();
                try {
                    Engine.Delete delete = TransportDeleteAction.executeDeleteRequestOnReplica(deleteRequest, indexShard);
                    indexShard.delete(delete);
                    location = locationToSync(location, delete.getTranslogLocation());
                } catch (Exception e) {
                    // if its not an ignore replica failure, we need to make sure to bubble up the failure
                    // so we will fail the shard
                    if (!ignoreReplicaException(e)) {
                        throw e;
                    }
                }
            } else {
                throw new IllegalStateException("Unexpected index operation: " + item.request());
            }
        }
        return location;
    }

    private void applyVersion(BulkItemRequest item, long version, VersionType versionType) {
        if (item.request() instanceof IndexRequest) {
            ((IndexRequest) item.request()).version(version).versionType(versionType);
        } else if (item.request() instanceof DeleteRequest) {
            ((DeleteRequest) item.request()).version(version).versionType();
        } else if (item.request() instanceof UpdateRequest) {
            ((UpdateRequest) item.request()).version(version).versionType();
        } else {
            // log?
        }
    }

    private Translog.Location locationToSync(Translog.Location current, Translog.Location next) {
        /* here we are moving forward in the translog with each operation. Under the hood
         * this might cross translog files which is ok since from the user perspective
         * the translog is like a tape where only the highest location needs to be fsynced
         * in order to sync all previous locations even though they are not in the same file.
         * When the translog rolls over files the previous file is fsynced on after closing if needed.*/
        assert next != null : "next operation can't be null";
        assert current == null || current.compareTo(next) < 0 : "translog locations are not increasing";
        return next;
    }
}