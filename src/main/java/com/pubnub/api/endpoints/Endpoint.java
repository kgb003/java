package com.pubnub.api.endpoints;


import com.google.gson.JsonElement;
import com.pubnub.api.PubNub;
import com.pubnub.api.PubNubException;
import com.pubnub.api.builder.PubNubErrorBuilder;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.enums.PNOperationType;
import com.pubnub.api.enums.PNStatusCategory;
import com.pubnub.api.managers.MapperManager;
import com.pubnub.api.models.consumer.PNErrorData;
import com.pubnub.api.models.consumer.PNStatus;
import lombok.AccessLevel;
import lombok.Getter;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class Endpoint<Input, Output> {

    @Getter(AccessLevel.PROTECTED)
    private PubNub pubnub;
    @Getter(AccessLevel.PROTECTED)
    private Retrofit retrofit;

    @Getter(AccessLevel.NONE)
    private PNCallback<Output> cachedCallback;

    @Getter(AccessLevel.NONE)
    private Call<Input> call;

    /**
     * If the endpoint failed to execute and we do not want to alert the user, flip this to true
     * This operation is handy if we internally cancelled the endpoint.
     */
    @Getter(AccessLevel.NONE)
    private boolean silenceFailures;

    private static final int SERVER_RESPONSE_SUCCESS = 200;
    private static final int SERVER_RESPONSE_FORBIDDEN = 403;
    private static final int SERVER_RESPONSE_BAD_REQUEST = 400;

    private MapperManager mapper;

    public Endpoint(final PubNub pubnubInstance, Retrofit retrofitInstance) {
        this.pubnub = pubnubInstance;
        this.retrofit = retrofitInstance;
        this.mapper = this.pubnub.getMapper();
    }


    public final Output sync() throws PubNubException {
        this.validateParams();

        call = doWork(createBaseParams());
        Response<Input> serverResponse;
        Output response;

        try {
            serverResponse = call.execute();
        } catch (IOException e) {
            throw PubNubException.builder()
                    .pubnubError(PubNubErrorBuilder.PNERROBJ_PARSING_ERROR)
                    .errormsg(e.toString())
                    .affectedCall(call)
                    .build();
        }

        if (!serverResponse.isSuccessful() || serverResponse.code() != SERVER_RESPONSE_SUCCESS) {
            String responseBodyText;
            JsonElement responseBody;

            try {
                responseBodyText = serverResponse.errorBody().string();
            } catch (IOException e) {
                responseBodyText = "N/A";
            }

            try {
                responseBody = mapper.fromJson(responseBodyText, JsonElement.class);
            } catch (PubNubException e) {
                responseBody = null;
            }

            throw PubNubException.builder()
                    .pubnubError(PubNubErrorBuilder.PNERROBJ_HTTP_ERROR)
                    .errormsg(responseBodyText)
                    .jso(responseBody)
                    .statusCode(serverResponse.code())
                    .affectedCall(call)
                    .build();
        }

        response = createResponse(serverResponse);

        return response;
    }

    public final void async(final PNCallback<Output> callback) {
        cachedCallback = callback;

        try {
            this.validateParams();
            call = doWork(createBaseParams());
        } catch (PubNubException pubnubException) {
            callback.onResponse(null, createStatusResponse(PNStatusCategory.PNBadRequestCategory, null, pubnubException, null, null));
            return;
        }

        call.enqueue(new retrofit2.Callback<Input>() {

            @Override
            public void onResponse(final Call<Input> performedCall, final Response<Input> response) {
                Output callbackResponse;

                if (!response.isSuccessful() || response.code() != SERVER_RESPONSE_SUCCESS) {

                    String responseBodyText;
                    JsonElement responseBody;
                    JsonElement responseBodyPayload = null;
                    ArrayList<String> affectedChannels = new ArrayList<>();
                    ArrayList<String> affectedChannelGroups = new ArrayList<>();

                    try {
                        responseBodyText = response.errorBody().string();
                    } catch (IOException e) {
                        responseBodyText = "N/A";
                    }

                    try {
                        responseBody = mapper.fromJson(responseBodyText, JsonElement.class);
                    } catch (PubNubException e) {
                        responseBody = null;
                    }

                    if (responseBody != null && responseBody.isJsonObject() && responseBody.getAsJsonObject().has("payload")) {
                        responseBodyPayload = responseBody.getAsJsonObject().get("payload");
                    }

                    PNStatusCategory pnStatusCategory = PNStatusCategory.PNUnknownCategory;
                    PubNubException ex = PubNubException.builder()
                            .pubnubError(PubNubErrorBuilder.PNERROBJ_HTTP_ERROR)
                            .errormsg(responseBodyText)
                            .jso(responseBody)
                            .statusCode(response.code())
                            .build();

                    if (response.code() == SERVER_RESPONSE_FORBIDDEN) {
                        pnStatusCategory = PNStatusCategory.PNAccessDeniedCategory;

                        if (responseBodyPayload != null && responseBodyPayload.getAsJsonObject().has("channels")) {
                            for (final JsonElement objNode : responseBodyPayload.getAsJsonObject().get("channels").getAsJsonArray()) {
                                affectedChannels.add(objNode.getAsString());
                            }
                        }

                        if (responseBodyPayload != null && responseBodyPayload.getAsJsonObject().has("channel-groups")) {
                            for (final JsonElement objNode : responseBodyPayload.getAsJsonObject().get("channel-groups").getAsJsonArray()) {
                                String channelGroupName = objNode.getAsString().substring(0, 1).equals(":") ? objNode.getAsString().substring(1) : objNode.getAsString();
                                affectedChannelGroups.add(channelGroupName);
                            }
                        }

                    }

                    if (response.code() == SERVER_RESPONSE_BAD_REQUEST) {
                        pnStatusCategory = PNStatusCategory.PNBadRequestCategory;
                    }

                    callback.onResponse(null, createStatusResponse(pnStatusCategory, response, ex, affectedChannels, affectedChannelGroups));
                    return;
                }

                try {
                    callbackResponse = createResponse(response);
                } catch (PubNubException pubnubException) {
                    callback.onResponse(null, createStatusResponse(PNStatusCategory.PNMalformedResponseCategory, response, pubnubException, null, null));
                    return;
                }

                callback.onResponse(callbackResponse, createStatusResponse(PNStatusCategory.PNAcknowledgmentCategory, response, null, null, null));
            }

            @Override
            public void onFailure(final Call<Input> performedCall, final Throwable throwable) {
                if (silenceFailures) {
                    return;
                }

                PNStatusCategory pnStatusCategory = PNStatusCategory.PNBadRequestCategory;
                PubNubException.PubNubExceptionBuilder pubnubException = PubNubException.builder()
                        .errormsg(throwable.getMessage());

                try {
                    throw throwable;
                } catch (UnknownHostException networkException) {
                    pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_CONNECTION_NOT_SET);
                    pnStatusCategory = PNStatusCategory.PNUnexpectedDisconnectCategory;
                } catch (ConnectException connectException) {
                    pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_CONNECT_EXCEPTION);
                    pnStatusCategory = PNStatusCategory.PNUnexpectedDisconnectCategory;
                } catch (SocketTimeoutException socketTimeoutException) {
                    pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_SUBSCRIBE_TIMEOUT);
                    pnStatusCategory = PNStatusCategory.PNTimeoutCategory;
                } catch (Throwable throwable1) {
                    pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_HTTP_ERROR);
                }

                callback.onResponse(null, createStatusResponse(pnStatusCategory, null, pubnubException.build(), null, null));

            }
        });
    }

    public void retry() {
        silenceFailures = false;
        async(cachedCallback);
    }

    /**
     * cancel the operation but do not alert anybody, useful for restarting the heartbeats and subscribe loops.
     */
    public void silentCancel() {
        if (call != null && !call.isCanceled()) {
            this.silenceFailures = true;
            call.cancel();
        }
    }

    private PNStatus createStatusResponse(PNStatusCategory category, Response<Input> response, Exception throwable, ArrayList<String> errorChannels, ArrayList<String> errorChannelGroups) {
        PNStatus.PNStatusBuilder pnStatus = PNStatus.builder();

        pnStatus.executedEndpoint(this);

        if (response == null || throwable != null) {
            pnStatus.error(true);
        }

        if (throwable != null) {
            PNErrorData pnErrorData = new PNErrorData(throwable.getMessage(), throwable);
            pnStatus.errorData(pnErrorData);
        }

        if (response != null) {
            pnStatus.statusCode(response.code());
            pnStatus.tlsEnabled(response.raw().request().url().isHttps());
            pnStatus.origin(response.raw().request().url().host());
            pnStatus.uuid(response.raw().request().url().queryParameter("uuid"));
            pnStatus.authKey(response.raw().request().url().queryParameter("auth"));
            pnStatus.clientRequest(response.raw().request());
        }

        pnStatus.operation(getOperationType());
        pnStatus.category(category);

        if (errorChannels != null && !errorChannels.isEmpty()) {
            pnStatus.affectedChannels(errorChannels);
        } else {
            pnStatus.affectedChannels(getAffectedChannels());
        }

        if (errorChannelGroups != null && !errorChannelGroups.isEmpty()) {
            pnStatus.affectedChannelGroups(errorChannelGroups);
        } else {
            pnStatus.affectedChannelGroups(getAffectedChannelGroups());
        }

        return pnStatus.build();
    }

    protected final Map<String, String> createBaseParams() {
        Map<String, String> params = new HashMap<>();

        params.put("pnsdk", "PubNub-Java-Unified/".concat(this.pubnub.getVersion()));
        params.put("uuid", this.pubnub.getConfiguration().getUuid());

        if (this.pubnub.getConfiguration().isIncludeInstanceIdentifier()) {
            params.put("instanceid", pubnub.getInstanceId());
        }

        if (this.pubnub.getConfiguration().isIncludeRequestIdentifier()) {
            params.put("requestid", pubnub.getRequestId());
        }

        // add the auth key for publish and subscribe.
        if (this.pubnub.getConfiguration().getAuthKey() != null && isAuthRequired()) {
                params.put("auth", pubnub.getConfiguration().getAuthKey());
        }

        return params;
    }

    protected List<String> getAffectedChannels() {
        return null;
    }

    protected List<String> getAffectedChannelGroups() {
        return null;
    }

    protected abstract void validateParams() throws PubNubException;

    protected abstract Call<Input> doWork(Map<String, String> baseParams) throws PubNubException;

    protected abstract Output createResponse(Response<Input> input) throws PubNubException;

    protected abstract PNOperationType getOperationType();

    protected abstract boolean isAuthRequired();

}
