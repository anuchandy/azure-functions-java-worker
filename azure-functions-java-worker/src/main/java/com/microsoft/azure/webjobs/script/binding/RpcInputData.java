package com.microsoft.azure.webjobs.script.binding;

import java.net.*;
import java.util.*;

import com.google.gson.*;
import com.google.protobuf.*;

import com.microsoft.azure.serverless.functions.*;
import com.microsoft.azure.webjobs.script.*;
import com.microsoft.azure.webjobs.script.rpc.messages.*;

abstract class RpcInputData<T> extends InputData<T> {
    RpcInputData(String name, T value) { super(name, new Value<>(value)); }

    static RpcInputData<?> parse(ParameterBinding parameter) { return parse(parameter.getName(), parameter.getData()); }
    static RpcInputData<?> parse(TypedData data) { return parse(null, data); }
    private static RpcInputData<?> parse(String name, TypedData data) {
        switch (data.getDataCase()) {
            case STRING: return new RpcStringInputData(name, data.getString());
            case JSON:   return new RpcJsonInputData(name, data.getJson());
            case BYTES:  return new RpcBytesInputData(name, data.getBytes());
            case HTTP:   return new RpcHttpInputData(name, data.getHttp());
        }
        throw new UnsupportedOperationException("Input data type \"" + data.getDataCase() + "\" is not supported");
    }
}

class RpcStringInputData extends RpcInputData<String> {
    RpcStringInputData(String name, String value) {
        super(name, value);
        this.setOrElseConversion(target -> Optional.of(new Value<>(new Gson().fromJson(this.getActualValue(), target))));
    }
}

class RpcJsonInputData extends RpcInputData<JsonElement> {
    RpcJsonInputData(String name, String jsonString) {
        super(name, new JsonParser().parse(jsonString));
        this.setOrElseAssignment(target -> Optional.of(new Value<>(new Gson().fromJson(this.getActualValue(), target))));
    }
}

class RpcBytesInputData extends RpcInputData<ByteString> {
    RpcBytesInputData(String name, ByteString value) {
        super(name, value);
        this.registerAssignment(byte[].class, () -> this.getActualValue().toByteArray());
    }
}

class RpcHttpInputData extends RpcInputData<RpcHttp> {
    RpcHttpInputData(String name, RpcHttp value) {
        super(name, value);
        this.registerAssignment(HttpRequestMessage.class, this::toHttpRequestMessage);
        this.body = (value.hasBody() ? parse(value.getBody()) : new NullInputData());
        this.setOrElseConversion(target -> this.body.convertTo(target));
        this.fieldMaps.add(value.getHeadersMap());
        this.fieldMaps.add(value.getQueryMap());
        this.fieldMaps.add(value.getParamsMap());
    }

    @Override
    Optional<InputData<?>> lookupSingleChildByName(String name) {
        return Utility.single(this.fieldMaps, map -> {
            String value = map.get(name);
            return (value != null ? Optional.of(new RpcStringInputData(name, value)) : Optional.empty());
        });
    }

    private HttpRequestMessage toHttpRequestMessage() {
        // TODO Strongly-typed body
        Optional<String> bodyValue = this.body != null ? this.body.convertTo(String.class).map(v -> v.getActual().toString()) : Optional.empty();
        return new HttpRequestMessage.Builder()
            .setMethod(this.getActualValue().getMethod())
            .setUri(URI.create(this.getActualValue().getUrl()))
            .setBody(bodyValue.orElse(null))
            .putAllHeaders(this.getActualValue().getHeadersMap())
            .putAllQueryParameters(this.getActualValue().getQueryMap())
            .build();
    }

    private InputData<?> body;
    private List<Map<String, String>> fieldMaps = new ArrayList<>();
}