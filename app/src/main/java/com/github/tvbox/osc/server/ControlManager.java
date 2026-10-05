package com.github.tvbox.osc.server;

import android.content.Context;

import java.io.IOException;

/**
 * @author pj567
 * @date :2021/1/4
 * @description:
 */
public class ControlManager {
    // volatile:DCL 单例必须(2026-09-12 修复 Bug)。jar 加载线程(JarLoader.injectProxyPort)与主线程
    // (AppBootstrap/MainScreen)都会调 get()
    private static volatile ControlManager instance;
    private RemoteServer mServer = null;
    public static Context mContext;

    private ControlManager() {

    }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) {
                    instance = new ControlManager();
                }
            }
        }
        return instance;
    }

    public static void init(Context context) {
        mContext = context;
    }

    public String getAddress(boolean local) {
        if (mServer == null || !mServer.isStarting()) {
            startServer();
        }
        if (mServer == null || !mServer.isStarting()) {
            return "";
        }
        return local ? mServer.getLoadAddress() : mServer.getServerAddress();
    }

    public void startServer() {
        if (mServer != null && mServer.isStarting()) {
            return;
        }
        do {
            mServer = new RemoteServer(RemoteServer.serverPort, mContext);
            try {
                mServer.start();
                com.github.catvod.Proxy.set(RemoteServer.serverPort);
                break;
            } catch (IOException ex) {
                RemoteServer.serverPort++;
                mServer.stop();
            }
        } while (RemoteServer.serverPort < 9999);
    }

    public void stopServer() {
        if (mServer != null && mServer.isStarting()) {
            mServer.stop();
        }
        mServer = null;
    }
}
