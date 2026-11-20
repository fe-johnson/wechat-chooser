package com.local.wxchooser;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs in system_server. Intercepts WeChat OpenSDK requests (login / share / pay / mini program)
 * sent to com.tencent.mm stub activities and lets the user pick which profile's WeChat handles it.
 * Callbacks from a WeChat in another profile are routed back to the requesting app's own profile.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "[WxChooser] ";
    static final String WX = "com.tencent.mm";
    static final String WX_STUB_PREFIX = "com.tencent.mm.plugin.base.stub.";
    static final String SELF = "com.local.wxchooser";

    static final String EX_TOKEN = "wxchooser_token";
    static final String EX_USER = "wxchooser_user";
    static final String EX_USERS = "wxchooser_users";
    static final String EX_LABELS = "wxchooser_labels";
    static final String EX_APP = "wxchooser_app";
    static final String EX_COMPONENT = "wxchooser_component";

    private static final int PER_USER_RANGE = 100000;
    private static final long PENDING_TTL_MS = 5 * 60 * 1000L;
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L;
    private static final int START_SUCCESS = 0;

    /** Requests waiting for the user to pick a WeChat. Key: one-time token. */
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    /** Apps whose request went to a WeChat in another profile. Key: app package. */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static volatile int selfAppId = -1;

    private static final class Pending {
        final Intent intent;
        final int callerUser;
        final String appPkg;
        final long expiry;

        Pending(Intent intent, int callerUser, String appPkg) {
            this.intent = intent;
            this.callerUser = callerUser;
            this.appPkg = appPkg;
            this.expiry = System.currentTimeMillis() + PENDING_TTL_MS;
        }
    }

    private static final class Session {
        final int wxUser;
        final int homeUser;
        final long expiry;

        Session(int wxUser, int homeUser) {
            this.wxUser = wxUser;
            this.homeUser = homeUser;
            this.expiry = System.currentTimeMillis() + SESSION_TTL_MS;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(atms, "startActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onStartActivity(param);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "hook error: " + t);
                    }
                }
            }).size();
            XposedBridge.log(TAG + "hooked ActivityTaskManagerService.startActivity x" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "init failed: " + t);
        }
    }

    private static void onStartActivity(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        Intent intent = null;
        String callingPkg = null;
        for (Object a : args) {
            if (a instanceof Intent) {
                intent = (Intent) a;
                break;
            }
            if (callingPkg == null && a instanceof String) callingPkg = (String) a;
        }
        if (intent == null) return;
        ComponentName cn = intent.getComponent();
        if (cn == null) return;

        int callingUid = Binder.getCallingUid();
        int callingUser = callingUid / PER_USER_RANGE;
        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
        cleanup();

        // 1) The user picked a WeChat in our chooser.
        if (SELF.equals(callingPkg) && isSelfUid(ctx, callingUid)) {
            String token = intent.getStringExtra(EX_TOKEN);
            if (token == null) return;
            Pending p = PENDING.remove(token);
            param.setResult(START_SUCCESS);
            if (p == null) {
                XposedBridge.log(TAG + "unknown or expired token");
                return;
            }
            int target = intent.getIntExtra(EX_USER, p.callerUser);
            launch(ctx, p.intent, target, p.callerUser, p.appPkg);
            return;
        }

        // 2) An app asks WeChat to log in / share / pay.
        if (WX.equals(cn.getPackageName()) && cn.getClassName().startsWith(WX_STUB_PREFIX)
                && !WX.equals(callingPkg) && !SELF.equals(callingPkg)) {
            List<Integer> others = otherWxUsers(ctx, callingUser);
            if (others.isEmpty()) return;

            String appPkg = intent.getStringExtra("_mmessage_appPackage");
            if (appPkg == null) appPkg = callingPkg;

            String token = UUID.randomUUID().toString();
            PENDING.put(token, new Pending(new Intent(intent), callingUser, appPkg));

            int[] users = new int[others.size() + 1];
            String[] labels = new String[users.length];
            users[0] = callingUser;
            labels[0] = userLabel(ctx, callingUser, true);
            for (int i = 0; i < others.size(); i++) {
                users[i + 1] = others.get(i);
                labels[i + 1] = userLabel(ctx, others.get(i), false);
            }

            Intent chooser = new Intent()
                    .setClassName(SELF, SELF + ".ChooserActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_HISTORY)
                    .putExtra(EX_TOKEN, token)
                    .putExtra(EX_USERS, users)
                    .putExtra(EX_LABELS, labels)
                    .putExtra(EX_APP, appLabel(ctx, appPkg, callingUser))
                    .putExtra(EX_COMPONENT, cn.flattenToString());
            if (startAsUser(ctx, chooser, callingUser)) {
                param.setResult(START_SUCCESS);
                XposedBridge.log(TAG + "chooser shown for " + appPkg + " (user " + callingUser + ")");
            } else {
                PENDING.remove(token);
            }
            return;
        }

        // 3) WeChat in another profile calls back the app: route it to the app's own profile.
        Session s = SESSIONS.get(cn.getPackageName());
        if (s != null && WX.equals(callingPkg) && callingUser == s.wxUser
                && callingUser != s.homeUser) {
            Intent back = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startAsUser(ctx, back, s.homeUser)) {
                param.setResult(START_SUCCESS);
                XposedBridge.log(TAG + "callback " + cn.flattenToShortString()
                        + " routed user " + callingUser + " -> " + s.homeUser);
            }
        }
    }

    private static void launch(Context ctx, Intent orig, int target, int home, String appPkg) {
        Intent i = new Intent(orig).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (target != home) {
            if (!ensureInstalled(ctx, appPkg, target)) {
                XposedBridge.log(TAG + appPkg + " missing in user " + target + ", fallback to " + home);
                target = home;
            } else {
                SESSIONS.put(appPkg, new Session(target, home));
            }
        }
        boolean ok = startAsUser(ctx, i, target);
        XposedBridge.log(TAG + "request from " + appPkg + " -> WeChat user " + target + (ok ? "" : " FAILED"));
    }

    private static boolean startAsUser(Context ctx, Intent intent, int userId) {
        long id = Binder.clearCallingIdentity();
        try {
            XposedHelpers.callMethod(ctx, "startActivityAsUser", intent, userHandle(userId));
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "startActivityAsUser(" + userId + ") failed: " + t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    /** The requesting app must exist in WeChat's profile, otherwise WeChat rejects its signature. */
    private static boolean ensureInstalled(Context ctx, String pkg, int userId) {
        if (pkg == null) return false;
        long id = Binder.clearCallingIdentity();
        try {
            PackageManager pm = userContext(ctx, userId).getPackageManager();
            if (isInstalled(pm, pkg)) return true;
            XposedHelpers.callMethod(pm, "installExistingPackage", pkg);
            boolean ok = isInstalled(pm, pkg);
            XposedBridge.log(TAG + "install-existing " + pkg + " into user " + userId + ": " + ok);
            return ok;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "install-existing " + pkg + " failed: " + t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    /** Other users in the caller's profile group that have WeChat installed and are not paused. */
    private static List<Integer> otherWxUsers(Context ctx, int callingUser) {
        List<Integer> result = new ArrayList<>();
        long id = Binder.clearCallingIdentity();
        try {
            UserManager um = ctx.getSystemService(UserManager.class);
            int[] ids = (int[]) XposedHelpers.callMethod(um, "getProfileIds", callingUser, true);
            for (int u : ids) {
                if (u == callingUser) continue;
                if (um.isQuietModeEnabled(userHandle(u))) continue;
                if (isInstalled(userContext(ctx, u).getPackageManager(), WX)) result.add(u);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "list profiles failed: " + t);
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return result;
    }

    private static String userLabel(Context ctx, int userId, boolean isCaller) {
        String name = null;
        long id = Binder.clearCallingIdentity();
        try {
            UserManager um = ctx.getSystemService(UserManager.class);
            Object info = XposedHelpers.callMethod(um, "getUserInfo", userId);
            if (info != null) name = (String) XposedHelpers.getObjectField(info, "name");
        } catch (Throwable ignored) {
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        if (userId == 0) name = "主空间";
        if (name == null || name.trim().isEmpty()) name = "用户 " + userId;
        return name.trim() + " 的微信" + (isCaller ? "（当前）" : "");
    }

    private static String appLabel(Context ctx, String pkg, int userId) {
        long id = Binder.clearCallingIdentity();
        try {
            PackageManager pm = userContext(ctx, userId).getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable t) {
            return pkg;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    private static UserHandle userHandle(int userId) {
        return UserHandle.getUserHandleForUid(userId * PER_USER_RANGE);
    }

    private static Context userContext(Context ctx, int userId) {
        return (Context) XposedHelpers.callMethod(ctx, "createContextAsUser", userHandle(userId), 0);
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean isSelfUid(Context ctx, int uid) {
        if (selfAppId < 0) {
            long id = Binder.clearCallingIdentity();
            try {
                selfAppId = ctx.getPackageManager().getPackageUid(SELF, 0) % PER_USER_RANGE;
            } catch (Throwable t) {
                return false;
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
        return uid % PER_USER_RANGE == selfAppId;
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext(); ) {
            if (it.next().expiry < now) it.remove();
        }
        for (Iterator<Session> it = SESSIONS.values().iterator(); it.hasNext(); ) {
            if (it.next().expiry < now) it.remove();
        }
    }
}
