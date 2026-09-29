package com.daosheng.selenelivetv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 50, 60, 50);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xFF101010);

        TextView title = new TextView(this);
        title.setText("直播订阅设置");
        title.setTextSize(30);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView tip = new TextView(this);
        tip.setText("订阅为空、下载失败或解析不到有效频道时会自动进入这里。\n修改后按“保存并返回”。");
        tip.setTextSize(18);
        tip.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.topMargin = 20;
        root.addView(tip, tipLp);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(MainActivity.getSubscriptionUrl(this));
        edit.setTextColor(0xFFFFFFFF);
        edit.setTextSize(18);
        edit.setHintTextColor(0xFF888888);
        edit.setHint(MainActivity.DEFAULT_SUB_URL);
        edit.setBackgroundColor(0xFF303030);
        edit.setPadding(20, 16, 20, 16);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(-1, -2);
        editLp.topMargin = 26;
        root.addView(edit, editLp);

        Button save = new Button(this);
        save.setText("保存并返回");
        save.setTextSize(19);
        save.setFocusable(true);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(300, 70);
        btnLp.topMargin = 30;
        root.addView(save, btnLp);

        save.setOnClickListener(v -> {
            String value = edit.getText().toString().trim();
            if (value.isEmpty()) value = MainActivity.DEFAULT_SUB_URL;
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .edit().putString(MainActivity.KEY_SUB_URL, value).apply();
            setResult(RESULT_OK);
            finish();
        });

        setContentView(root);
        save.requestFocus();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
