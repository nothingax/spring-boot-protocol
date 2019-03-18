package com.github.netty.protocol.nrpc;

import com.github.netty.core.util.LoggerFactoryX;
import com.github.netty.core.util.LoggerX;
import com.github.netty.core.util.NamespaceUtil;
import com.github.netty.protocol.nrpc.exception.RpcResponseException;
import com.github.netty.protocol.nrpc.exception.RpcTimeoutException;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.github.netty.protocol.nrpc.RpcPacket.*;
import static com.github.netty.protocol.nrpc.RpcUtil.NO_SUCH_METHOD;
import static com.github.netty.protocol.nrpc.RpcClientChannelHandler.FUTURE_MAP_ATTR;

/**
 * RPC client instance
 * @author wangzihao
 */
public class RpcClientInstance implements InvocationHandler {
    protected LoggerX logger = LoggerFactoryX.getLogger(getClass());
    private int timeout;
    private String serviceName;
    private String instanceId = NamespaceUtil.newIdName(getClass());
    /**
     * Generate request id
     */
    private static final AttributeKey<AtomicInteger> REQUEST_ID_INCR_ATTR = AttributeKey.valueOf(AtomicInteger.class+"#AtomicInteger");
    /**
     * Data encoder decoder
     */
    private DataCodec dataCodec;
    /**
     * Channel
     */
    private Channel channel;

    private Map<String,RpcMethod> rpcMethodMap;

    protected RpcClientInstance(int timeout, String serviceName,
                                Channel channel,
                                DataCodec dataCodec,
                                Class interfaceClass, Function<Method,String[]> methodToParameterNamesFunction) {
        this.rpcMethodMap = RpcMethod.getMethodMap(interfaceClass,methodToParameterNamesFunction);
        if(rpcMethodMap.isEmpty()){
            throw new IllegalStateException("The RPC service interface must have at least one method, class=["+interfaceClass.getSimpleName()+"]");
        }
        this.timeout = timeout;
        this.serviceName = serviceName;
        this.channel = channel;
        this.dataCodec = dataCodec;
    }

    /**
     * New request id
     * @return
     */
    protected int newRequestId(Channel channel){
        Attribute<AtomicInteger> attr = channel.attr(REQUEST_ID_INCR_ATTR);
        AtomicInteger incr = attr.get();
        if(incr == null){
            incr = new AtomicInteger();
            attr.set(incr);
        }
        return Math.abs(incr.incrementAndGet());
    }

    /**
     * Increase method
     * @param rpcMethod rpcMethod
     * @return boolean success
     */
    public boolean addMethod(RpcMethod rpcMethod){
        return rpcMethodMap.put(rpcMethod.getMethodName(),rpcMethod) == null;
    }

    /**
     * Make RPC calls
     * @param proxy proxy
     * @param method method
     * @param args args
     * @return Object
     * @throws Throwable Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }
        if ("toString".equals(methodName) && parameterTypes.length == 0) {
            return this.toString();
        }
        if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
            return this.hashCode();
        }
        if ("equals".equals(methodName) && parameterTypes.length == 1) {
            return this.equals(args[0]);
        }

        RpcMethod rpcMethod = rpcMethodMap.get(methodName);
        if(rpcMethod == null){
            return method.invoke(this,args);
        }

        Channel channel = this.channel;

        RequestPacket rpcRequest = new RequestPacket();
        rpcRequest.setRequestId(newRequestId(channel));
        rpcRequest.setServiceName(serviceName);
        rpcRequest.setMethodName(methodName);
        rpcRequest.setData(dataCodec.encodeRequestData(args,rpcMethod));
        rpcRequest.setAck(ACK_YES);

        Map<Integer,RpcFuture> futureMap = channel.attr(FUTURE_MAP_ATTR).get();

        RpcFuture future = new RpcFuture(rpcRequest);
        futureMap.put(rpcRequest.getRequestId(),future);

        channel.writeAndFlush(rpcRequest);

        ResponsePacket rpcResponse = future.get(timeout, TimeUnit.MILLISECONDS);
        if(rpcResponse == null){
            futureMap.remove(rpcRequest.getRequestId());
            throw new RpcTimeoutException("RequestTimeout : maxTimeout = ["+timeout+"], rpcRequest = ["+rpcRequest+"]",true);
        }

        //All states above 400 are in error
        if(rpcResponse.getStatus() >= NO_SUCH_METHOD){
            throw new RpcResponseException(rpcResponse.getStatus(),rpcResponse.getMessage(),true);
        }

        //If the server is not encoded, return directly
        if(rpcResponse.getEncode() == DataCodec.Encode.BINARY) {
            return rpcResponse.getData();
        }else {
            return dataCodec.decodeResponseData(rpcResponse.getData());
        }
    }

    @Override
    public String toString() {
        return "RpcClientInstance{" +
                "instanceId='" + instanceId + '\'' +
                ", serviceName='" + serviceName + '\'' +
                '}';
    }

    public static String getTimeoutApis() {
        return String.join(",", RpcFuture.TIMEOUT_API.keySet());
    }

    public static long getTotalInvokeCount() {
        return RpcFuture.TOTAL_INVOKE_COUNT.get();
    }

    public static long getTotalTimeoutCount() {
        return RpcFuture.TIMEOUT_API.values().stream().reduce(0,Integer::sum);
    }

}
