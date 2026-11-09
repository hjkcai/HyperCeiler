/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.recent;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import android.app.KeyguardManager;
import com.sevtinge.hyperceiler.common.log.ModuleLog;
import com.sevtinge.hyperceiler.libhook.provider.SharedPrefsProvider;
import com.sevtinge.hyperceiler.libhook.rules.home.gesture.HomeQuickSwitchOS4;
import com.sevtinge.hyperceiler.libhook.utils.api.ContextUtils;

/**
 * HCV1 scroll compatibility and HCV2 independent close channel, both use one provider snapshot.
 *
 * <p>The snapshot is cached in this process. This runs inside a WindowManager Binder transaction,
 * so a synchronous provider query here would block that transaction behind a cross-process call -
 * and because the module app is usually already reclaimed, that call is a cold start of another
 * process. The cache is refreshed by a content observer on the snapshot's own URI, which the
 * settings page notifies when it writes.
 */
public final class HomeRecentsHapticEndpointOS4 {
    public static final int TRANSACTION=HomeRecentsHapticProtocol.TRANSACTION,ACK=HomeRecentsHapticProtocol.ACK;
    public static final int TRANSACTION_V2=HomeRecentsHapticProtocol.TRANSACTION_V2;
    private static final Uri PREFS=Uri.parse(
        "content://" + SharedPrefsProvider.AUTHORITY + "/home_recents_haptic");
    private static int reported;

    /** Last successful snapshot. Guarded by {@link #cacheLock}; null means "not read yet". */
    private static volatile Snapshot cache;
    private static final Object cacheLock=new Object();
    private static volatile boolean observerInstalled;

    /** Immutable value of one provider read; {@code available=0} means the read failed. */
    private record Snapshot(int available,int enabled,int close,int type,int strength) {
        static final Snapshot UNAVAILABLE=new Snapshot(0,0,0,0,50);
    }

    private HomeRecentsHapticEndpointOS4() {}

    public static void receive(Parcel data,Parcel reply,int flags) {receive(data,reply,flags,false);}
    public static void receive(Parcel data,Parcel reply,int flags,boolean extended) {
        int available=0,enabled=0,close=0,type=0,strength=50,result=0;
        try {
            data.enforceInterface("android.view.IWindowManager");
            int operation=data.readInt();long timestamp=data.readLong();int count=data.readInt();
            var context=ContextUtils.getContextNoError(ContextUtils.FlAG_ONLY_ANDROID);
            if(!(extended?HomeRecentsHapticProtocol.validExtendedRequest(operation,timestamp,count,flags,data.dataAvail(),SystemClock.uptimeMillis())
                :HomeRecentsHapticProtocol.validRequest(operation,timestamp,count,flags,data.dataAvail(),SystemClock.uptimeMillis()))
                ||context==null||!HomeQuickSwitchOS4.isLauncher(context,Binder.getCallingUid(),Binder.getCallingPid())){
                writeReply(reply,extended,0,0,0,0,50,0);return;
            }
            long token=Binder.clearCallingIdentity();
            try {
                Snapshot snapshot=readSnapshot(context);
                if(snapshot.available()==1){
                    enabled=snapshot.enabled();type=snapshot.type();strength=snapshot.strength();
                    close=snapshot.close();
                    available=1;
                    if(operation==0)result=1;
                    else if((operation==2?close:enabled)==1&&SystemClock.uptimeMillis()-timestamp<=200
                        &&context.getSystemService(PowerManager.class).isInteractive()
                        &&!context.getSystemService(KeyguardManager.class).isKeyguardLocked())
                        result=HomeRecentsHapticOS4.perform(context,type,strength,count)?1:0;
                }
            } finally {Binder.restoreCallingIdentity(token);}
        } catch(Exception failure){
            if(reported++<3)ModuleLog.w("HyperCeiler.HomeLayout","recents haptic endpoint unavailable",failure);
        }
        writeReply(reply,extended,available,enabled,close,type,strength,result);
    }

    /**
     * Return the cached snapshot. Never performs the provider read itself.
     *
     * <p>This runs inside a WindowManager Binder transaction. A synchronous provider query here
     * would block that transaction behind a cross-process call, and since the module app is
     * usually reclaimed, that call is a cold start of another process - the exact cost this
     * cache exists to avoid. The cache is filled by {@link #prime} at hook init and refreshed by
     * a content observer afterwards.
     */
    private static Snapshot readSnapshot(Context context) {
        installObserverIfNeeded(context);
        return cache == null ? Snapshot.UNAVAILABLE : cache;
    }

    /** 在 hook 初始化时预热，避免首次手势落在空缓存上。调用方保证不在 Binder 线程。 */
    public static void prime(Context context) {
        if(context==null)return;
        installObserverIfNeeded(context);
        if(cache==null)refresh(context);
    }

    /** One content observer per process, refreshing on the provider's own notification. */
    private static void installObserverIfNeeded(Context context) {
        if(observerInstalled)return;
        synchronized(cacheLock){
            if(observerInstalled)return;
            try {
                HandlerThread thread=new HandlerThread("HC-RecentsHaptic");
                thread.start();
                Handler handler=new Handler(thread.getLooper());
                handler.post(() -> refresh(context));
                context.getContentResolver().registerContentObserver(
                    PREFS,true,new ContentObserver(handler){
                        @Override public void onChange(boolean self){refresh(context);}
                    });
                observerInstalled=true;
            }catch(Exception failure){
                if(reported++<3)ModuleLog.w("HyperCeiler.HomeLayout","haptic observer declined",failure);
            }
        }
    }

    /** Read the provider and publish the result. Runs on the observer thread, never on a binder one. */
    private static void refresh(Context context) {
        try(var cursor=context.getContentResolver().query(PREFS,null,null,null,null)){
            if(cursor==null||cursor.getColumnCount()!=4||!cursor.moveToFirst()||cursor.getInt(0)!=1)
                return;
            int enabled=cursor.getInt(1),type=cursor.getInt(2),strength=cursor.getInt(3);
            var extras=cursor.getExtras();
            int close=extras!=null&&extras.getInt("close_version",0)==1?extras.getInt("close_enabled",0):0;
            if(enabled<0||enabled>1||close<0||close>1||type<0||type>3||strength<10||strength>100)
                return;
            cache=new Snapshot(1,enabled,close,type,strength);
        }catch(Exception failure){
            if(reported++<3)ModuleLog.w("HyperCeiler.HomeLayout","haptic snapshot declined",failure);
        }
    }

    private static void writeReply(Parcel reply,boolean extended,int available,int enabled,int close,int type,int strength,int result) {
        if(available==0){enabled=close=type=result=0;strength=50;}
        reply.writeInt(extended?HomeRecentsHapticProtocol.ACK_V2:ACK);reply.writeInt(available);reply.writeInt(enabled);
        if(extended)reply.writeInt(close);
        reply.writeInt(type);reply.writeInt(strength);reply.writeInt(result);
    }
}
