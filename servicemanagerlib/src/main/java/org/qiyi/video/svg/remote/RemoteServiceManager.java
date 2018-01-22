package org.qiyi.video.svg.remote;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.qiyi.video.svg.BinderWrapper;
import org.qiyi.video.svg.IDispatcherRegister;
import org.qiyi.video.svg.IServiceDispatcher;
import org.qiyi.video.svg.config.Constants;
import org.qiyi.video.svg.dispatcher.DispatcherService;
import org.qiyi.video.svg.dispatcher.ServiceDispatcher;
import org.qiyi.video.svg.log.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by wangallen on 2018/1/9.
 */
//TODO 注意:所有startService()的地方都要加上try...catch,因为有些手机在后台如果startService()会抛出异常,比如Oppo
public class RemoteServiceManager extends IDispatcherRegister.Stub implements IRemoteServiceManager {

    private static final String TAG = "ServiceManager";

    private static RemoteServiceManager sInstance;

    public static void init(Context context) {
        getInstance().setContext(context);

        //TODO 如果是主进程就可以直接调用ServiceDispatcher
        getInstance().sendRegisterInfo();
    }

    public static RemoteServiceManager getInstance() {
        if (null == sInstance) {
            synchronized (RemoteServiceManager.class) {
                if (null == sInstance) {
                    sInstance = new RemoteServiceManager();
                }
            }
        }
        return sInstance;
    }

    private Context context;

    private IServiceDispatcher serviceDispatcherProxy;

    /**
     * 本地的Binder,需要给其他进程使用的,key为inteface的完整名称
     */
    //TODO 这个还是改成让他们注册IBinder,这样子也能保持注册和取出来的是一样的东西
    private Map<String, IBinder> stubBinderCache = new ConcurrentHashMap<>();
    //private Map<String, Binder> stubBinderCache = new ConcurrentHashMap<>();

    private Map<String, IBinder> remoteBinderCache = new ConcurrentHashMap<>();
    private final Object lock = new Object();


    private RemoteServiceManager() {
    }

    public void setContext(Context context) {
        this.context = context;
    }

    private boolean isMainProcess() {
        String processName = getCurrentProcessName();
        if (processName.equals(context.getPackageName())) {
            return true;
        }
        return false;
    }

    public String getCurrentProcessName() {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return null;
        }
        for (ActivityManager.RunningAppProcessInfo processInfo : activityManager.getRunningAppProcesses()) {
            if (processInfo.pid == android.os.Process.myPid()) {
                return processInfo.processName;
            }
        }
        return null;
    }


    //让ServiceDispatcher注册到当前进程
    public void sendRegisterInfo() {
        if(isMainProcess()){
            //KP 如果是主进程就走捷径,不然直接杀进程时会导致crash
            serviceDispatcherProxy= ServiceDispatcher.getInstance(context);
            return;
        }

        if (serviceDispatcherProxy == null) {
            //后面考虑还是采用"has-a"的方式会更好
            BinderWrapper wrapper = new BinderWrapper(this.asBinder());
            Intent intent = new Intent(context, DispatcherService.class);
            intent.setAction(Constants.DISPATCH_ACTION);
            intent.putExtra(Constants.KEY_DISPATHCER_REGISTER_WRAPPER, wrapper);
            context.startService(intent);
        }
    }

    //TODO 是在这里传context好呢?还是在初始化时传context更好?
    @Override
    public IBinder getRemoteService(String serviceName) {
        Log.d(TAG, "ServiceManager-->getRemoteService,pid=" + android.os.Process.myPid() + ",thread:" + Thread.currentThread().getName());

        //TODO 这里需要利用由AnnotationProcessor生成的代码的一个类,在那个类中可将IBinder转换为Interface
        IBinder binder = getIBinder(serviceName, context);
        //TODO 这部分代码后面要改成自动生成，可以利用注解解释器或者gradle插件来生成
        //TODO 目前的考虑是参与编译的就自动生成代码，不参与编译的只能用反射来调用asInterface了
        // GlobalInterface.asInterface();
        //return MatchPolicy.asInterface(serviceName, binder);
        return binder;
    }

    private IBinder getIBinder(String serviceName, Context context) {
        //KP 首先检查是否就在本地!这非常重要，否则有可能导致死锁!
        if (stubBinderCache.get(serviceName) != null) {
            return stubBinderCache.get(serviceName);
        }

        if (remoteBinderCache.get(serviceName) != null) {
            return remoteBinderCache.get(serviceName);
        }


        //TODO 这部分逻辑是不是要先去掉呢?
        synchronized (lock) {
            if (null == serviceDispatcherProxy) {
                sendRegisterInfo();
                try {
                    lock.wait(3000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }

            }
        }

        try {
            //TODO 这里是要改成获取更多的信息，比如类名，还是改成调用AsInterfaceHelper呢?
            //TODO 好像对于插件只能是通过反射来做，所以还是要获取实现类的完整名称,所以需要两者结合的方式! 而且注意这个是不能缓存的，因为你不确定当前这个调用是否一定跟服务端在不同的进程!
            IBinder binder = serviceDispatcherProxy.getTargetBinder(serviceName);
            Log.d(TAG, "get IBinder from ServiceDispatcher");
            remoteBinderCache.put(serviceName, binder);
            return binder;
        } catch (RemoteException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    //注意这个和registerRemoteService的区别，这里其实只是register本进程中有IPC能力的接口,它的名字其实叫registerStubService更合适
    //TODO 考虑还是在每个进程的Application中进行初始化，这样有两个目的，一个是获取Context,就不用后面每次调用都传递Context;另外一个是通过startService让ServiceDispatcher反向注册到当前进程
    @Override
    public void registerStubService(String serviceCanonicalName, IBinder stubBinder) {
        stubBinderCache.put(serviceCanonicalName, stubBinder);
        if (serviceDispatcherProxy == null) {
            BinderWrapper wrapper = new BinderWrapper(this.asBinder());
            Intent intent = new Intent(context, DispatcherService.class);
            intent.setAction(Constants.DISPATCH_ACTION);
            intent.putExtra(Constants.KEY_DISPATHCER_REGISTER_WRAPPER, wrapper);
            intent.putExtra(Constants.KEY_BUSINESS_BINDER_WRAPPER, new BinderWrapper(stubBinder));
            intent.putExtra(Constants.KEY_SERVICE_NAME, serviceCanonicalName);
            context.startService(intent);
        } else {
            try {
                serviceDispatcherProxy.registerRemoteService(serviceCanonicalName, stubBinder);
            } catch (RemoteException ex) {
                ex.printStackTrace();
            }
        }
    }

    ///////////////////
    @Override
    public void registerDispatcher(IBinder dispatcherBinder) throws RemoteException {
        Log.d(TAG, "RemoteServiceManager-->registerDispatcher");
        dispatcherBinder.linkToDeath(new DeathRecipient() {
            @Override
            public void binderDied() {
                Logger.d("RemoteServiceManager-->dispatcherBinder binderDied");
                serviceDispatcherProxy = null;
            }
        }, 0);
        //这里实现IServiceRegister仅仅是为了给RemoteServiceManager提供注册到当前进程的机会
        serviceDispatcherProxy = IServiceDispatcher.Stub.asInterface(dispatcherBinder);
        synchronized (lock) {
            lock.notify();
        }
    }
}