package com.billy.cc.core.component;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * app之间的组件调用
 * 兼容同步实现的异步调用 & 异步实现的同步调用
 * @author billy.qi
 * @since 17/6/29 11:45
 */
class RemoteCCInterceptor implements ICCInterceptor, ICaller {
    /**
     * 组件app之间建立socket连接的最大等待时间
     */
    private static final int CONNECT_DELAY = 900;
    static final String MSG_CANCEL = "cancel";
    static final String MSG_TIMEOUT = "timeout";

    static final String KEY_CALL_ID = "component_key_call_id";
    static final String KEY_PARAMS = "component_key_params";
    static final String KEY_ACTION_NAME = "component_key_action_name";
    static final String KEY_COMPONENT_NAME = "component_key_component_name";
    static final String KEY_TIMEOUT = "component_key_timeout";
    static final String KEY_SOCKET_NAME = "component_key_local_socket_name";

    /**
     * 调用结果
     */
    private CCResult result;
    /**
     * 同步锁
     */
    private final byte[] lock = new byte[0];
    /**
     * 发起组件调用的信息
     */
    private final CC cc;
    /**
     * 是否正在被其它app的组件进行处理
     */
    private volatile boolean ccProcessing = false;
    /**
     * 回调控制器，避免超时后的重复回调
     */
    private AtomicBoolean resultReceived = new AtomicBoolean(false);
    /**
     * socket通信是否已停止
     */
    private boolean stopped;
    private long startTime;
    private BufferedWriter out;
    private String socketName;
    private static String receiverPermission;
    private static String receiverIntentFilterAction;

    RemoteCCInterceptor(CC cc) {
        this.cc = cc;
        this.cc.setCaller(this);
    }

    @Override
    public CCResult intercept(Chain chain) {
        //是否需要wait：异步调用且未设置回调，则不需要wait
        boolean callbackNecessary = !cc.isAsync() || cc.getCallback() != null;
        startTime = System.currentTimeMillis();
        //未被cancel
        if (!resultReceived.get()) {
            new ConnectTask().start();
            if (!resultReceived.get() && callbackNecessary) {
                String callId = cc.getCallId();
                if (CC.VERBOSE_LOG) {
                    CC.verboseLog(callId, "start waiting for CC.sendCCResult(\"" + callId
                            + "\", ccResult)");
                }
                synchronized (lock) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                if (CC.VERBOSE_LOG) {
                    CC.verboseLog(callId, "end waiting for CC.sendCCResult(\"" + callId
                            + "\", ccResult), resultReceived=" + resultReceived);
                }
            }
        }
        if (result == null) {
            result = CCResult.defaultNullResult();
        }
        cc.setCaller(null);
        if (out != null) {
            try{
                out.close();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    private void receiveCCResult(CCResult ccResult) {
        if (!resultReceived.compareAndSet(false, true)) {
            return;
        }
        if (CC.VERBOSE_LOG) {
            CC.verboseLog(cc.getCallId(), "final CCResult:" + ccResult);
        }
        synchronized (lock) {
            result = ccResult;
            lock.notifyAll();
        }
    }

    private void stopCaller(int errorCode, String msg) {
        if (resultReceived.get()) {
            return;
        }
        if (CC.VERBOSE_LOG) {
            CC.verboseLog(cc.getCallId(), "RemoteCC stopped (%s).", msg);
        }
        if (!ccProcessing) {
            stopConnection();
            receiveCCResult(CCResult.error(errorCode));
        } else {
            sendToRemote(msg);
        }
    }

    @Override
    public void cancel() {
        stopCaller(CCResult.CODE_ERROR_CANCELED, MSG_CANCEL);
    }
    @Override
    public void timeout() {
        stopCaller(CCResult.CODE_ERROR_TIMEOUT, MSG_TIMEOUT);
    }

    private void sendToRemote(String str) {
        if (CC.VERBOSE_LOG) {
            CC.verboseLog(cc.getCallId(), "send message to remote app by localSocket:\"" + str + "\"");
        }
        if (out != null) {
            try {
                out.write(str);
                out.newLine();
                out.flush();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class ConnectTask extends Thread {

        @Override
        public void run() {
            Context context = cc.getContext();
            if (context == null) {
                receiveCCResult(CCResult.error(CCResult.CODE_ERROR_CONTEXT_NULL));
                return;
            }
            //retrieve ComponentBroadcastReceiver permission
            if (TextUtils.isEmpty(receiverIntentFilterAction)) {
                try{
                    ComponentName receiver = new ComponentName(context.getPackageName(), ComponentBroadcastReceiver.class.getName());
                    ActivityInfo receiverInfo = context.getPackageManager().getReceiverInfo(receiver, PackageManager.GET_META_DATA);
                    receiverPermission = receiverInfo.permission;
                    receiverIntentFilterAction = "cc.action.com.billy.cc.libs.component.REMOTE_CC";
                } catch(Exception e) {
                    e.printStackTrace();
                    receiveCCResult(CCResult.error(CCResult.CODE_ERROR_CONNECT_FAILED));
                    return;
                }
            }
            Intent intent = new Intent(receiverIntentFilterAction);
            if (CC.DEBUG) {
                intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            }
            intent.putExtra(KEY_COMPONENT_NAME, cc.getComponentName());
            intent.putExtra(KEY_ACTION_NAME, cc.getActionName());
            intent.putExtra(KEY_TIMEOUT, cc.getTimeout());
            JSONObject params = new JSONObject(cc.getParams());
            intent.putExtra(KEY_PARAMS, params.toString());
            String callId = cc.getCallId();
            intent.putExtra(KEY_CALL_ID, callId);
            socketName = "lss:" + callId;
            intent.putExtra(KEY_SOCKET_NAME, socketName);
            LocalServerSocket lss = null;
            //send broadcast for remote cc connection
            BufferedReader in = null;
            try {
                lss = new LocalServerSocket(socketName);
                if (CC.VERBOSE_LOG) {
                    CC.verboseLog(callId, "sendBroadcast to call component from other App."
                            + " permission:" + receiverPermission);
                }
                context.sendBroadcast(intent, receiverPermission);
                new CheckConnectTask().start();
                LocalSocket socket = lss.accept();
                ccProcessing = true;
                if (stopped) {
                    return;
                }

                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                if (CC.VERBOSE_LOG) {
                    CC.verboseLog(callId, "localSocket connect success. " +
                            "start to wait for remote CCResult.");
                }
                //blocking for CCResult
                String str = in.readLine();
                if (CC.VERBOSE_LOG) {
                    CC.verboseLog(callId, "localSocket received remote CCResult:" + str);
                }
                receiveCCResult(CCResult.fromString(str));
            } catch(Exception e) {
                e.printStackTrace();
                receiveCCResult(CCResult.error(CCResult.CODE_ERROR_CONNECT_FAILED));
            } finally {
                if (lss != null) {
                    try {
                        lss.close();
                    } catch (Exception ignored) {
                    }
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private class CheckConnectTask extends Thread {
        @Override
        public void run() {
            while (!ccProcessing && System.currentTimeMillis() - startTime < CONNECT_DELAY) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            if (!ccProcessing) {
                stopCaller(CCResult.CODE_ERROR_NO_COMPONENT_FOUND, "no component found");
                String cName = cc.getComponentName();
                //跨app组件调用需要组件所在app满足2个条件：
                // 1. 开启支持跨app调用 (CC.RESPONSE_FOR_REMOTE_CC = true;//默认为true，设置为false则关闭)
                // 2. 在系统设置页面中开启自启动权限（根据rom不同，一般在系统的权限设置页面，也可能在一个管家类app中）
                CC.log("call component:" + cName
                        + " failed. Could not found that component. "
                        + "\nPlease make sure the app which contains component(\"" + cName + "\") as below:"
                        + "\n1. " + cName + " set CC.enableRemoteCC(true) "
                        + "\n2. Turn on auto start permission in System permission settings page ");
            }
        }
    }

    private void stopConnection() {
        if (TextUtils.isEmpty(socketName)) {
            return;
        }
        if (CC.VERBOSE_LOG) {
            CC.verboseLog(cc.getCallId(), "stop localServerSocket.accept()");
        }
        stopped = true;
        //通过创建一个无用的client来让ServerSocket跳过accept阻塞，从而中止
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(socketName));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

}