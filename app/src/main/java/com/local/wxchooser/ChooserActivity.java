package com.local.wxchooser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Started by system_server (Hook) to let the user pick which profile's WeChat handles a request. */
public class ChooserActivity extends Activity {
    private boolean picked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String token = in.getStringExtra(Hook.EX_TOKEN);
        int[] users = in.getIntArrayExtra(Hook.EX_USERS);
        String[] labels = in.getStringArrayExtra(Hook.EX_LABELS);
        String app = in.getStringExtra(Hook.EX_APP);
        ComponentName target = ComponentName.unflattenFromString(
                String.valueOf(in.getStringExtra(Hook.EX_COMPONENT)));
        if (token == null || users == null || labels == null
                || users.length != labels.length || target == null) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle((app == null ? "" : app + " ") + "使用哪个微信？")
                .setItems(labels, (d, which) -> {
                    picked = true;
                    Intent out = new Intent()
                            .setComponent(target)
                            .putExtra(Hook.EX_TOKEN, token)
                            .putExtra(Hook.EX_USER, users[which]);
                    try {
                        startActivity(out);
                    } catch (Exception e) {
                        Log.w("WxChooser", "forward failed", e);
                    }
                    finish();
                })
                .setOnDismissListener(d -> {
                    if (!picked) finish();
                })
                .show();
    }
}
