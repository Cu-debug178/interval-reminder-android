package com.example.reviewreminder;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public final class ExampleActivity extends ScreenActivity {
    private WordUi ui;
    private long taskId;
    private JSONArray words;
    private int[] indices;
    private int position;
    private EditText exampleInput;
    private String draft = "";
    private java.util.concurrent.Future<?> pending;

    static int[] missingExampleIndices(JSONArray words, int[] candidates) {
        if (words == null || candidates == null) return new int[0];
        boolean[] seen = new boolean[words.length()];
        ArrayList<Integer> missing = new ArrayList<>();
        for (int index : candidates) {
            JSONObject word = index >= 0 && index < words.length() ? words.optJSONObject(index) : null;
            if (word != null && !seen[index] && word.optString("example", "").trim().isEmpty()) {
                seen[index] = true;
                missing.add(index);
            }
        }
        int[] out = new int[missing.size()];
        for (int i = 0; i < missing.size(); i++) out[i] = missing.get(i);
        return out;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new WordUi(this);
        taskId = getIntent().getLongExtra("taskId", 0);
        indices = getIntent().getIntArrayExtra("indices");
        if (indices == null) indices = new int[0];
        if (state != null) {
            position = Math.max(0, state.getInt("position"));
            draft = state.getString("draft", "");
        }
        try {
            JSONObject task = Store.find(Store.tasks(this), taskId);
            if (task == null) throw new IllegalStateException("复习任务已不存在");
            words = task.getJSONArray("words");
            show();
        } catch (Exception e) {
            ui.toast(e.getMessage());
            finish();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("position", position);
        if (exampleInput != null) out.putString("draft", exampleInput.getText().toString());
        else out.putString("draft", draft);
        super.onSaveInstanceState(out);
    }

    private void show() {
        if (position >= indices.length) {
            summary();
            return;
        }
        int wordIndex = indices[position];
        JSONObject word = words.optJSONObject(wordIndex);
        if (word == null) {
            advance();
            return;
        }
        ui.start("补充例句");
        ui.body.addView(ui.text((position + 1) + " / " + indices.length + " 个忘记的单词", 14, WordUi.MUTED));
        ui.gap(ui.body, 18);
        LinearLayout card = ui.col();
        card.setGravity(Gravity.CENTER);
        card.setMinimumHeight(ui.dp(180));
        card.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24));
        card.setBackground(ui.bg(Color.WHITE, 18));
        TextView english = ui.text(word.optString("english"), 27, WordUi.INK);
        english.setGravity(Gravity.CENTER);
        english.setTypeface(null, 1);
        card.addView(english);
        ui.gap(card, 10);
        TextView meaning = ui.text(word.optString("chinese"), 17, WordUi.GREEN);
        meaning.setGravity(Gravity.CENTER);
        card.addView(meaning);
        ui.body.addView(card);
        ui.gap(ui.body, 18);
        ui.body.addView(ui.text("为这个词写一个英文例句", 14, WordUi.MUTED));
        ui.gap(ui.body, 6);
        exampleInput = new EditText(this);
        exampleInput.setTextSize(17);
        exampleInput.setTextColor(WordUi.INK);
        exampleInput.setHint("输入例句");
        exampleInput.setGravity(Gravity.TOP | Gravity.START);
        exampleInput.setMinLines(3);
        exampleInput.setMaxLines(6);
        exampleInput.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        exampleInput.setBackground(ui.bg(Color.WHITE, 14));
        exampleInput.setText(draft);
        ui.body.addView(exampleInput, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(ui.body, 14);
        ui.body.addView(ui.button("AI 生成例句", false, v -> aiFill(word)));
        ui.gap(ui.body, 8);
        ui.body.addView(ui.button(position + 1 == indices.length ? "保存并完成" : "保存并继续", true, v -> save()));
        ui.gap(ui.body, 8);
        ui.body.addView(ui.button("稍后补充", false, v -> advance()));
        Motion.enter(card);
    }
    private void aiFill(JSONObject word){
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        ui.toast("正在生成例句…");
        pending=AiClient.chatJson(this,AiPrompts.systemExample(),AiPrompts.userExample(word.optString("english"),word.optString("chinese")),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                String value=json.optString("example","").trim();
                if(value.isEmpty()){ui.toast("模型未返回例句");return;}
                if(exampleInput!=null){exampleInput.setText(value);draft=value;}
            }
            @Override public void onError(String message){ui.toast(message);}
        });
    }
    @Override protected void onDestroy(){AiClient.cancel(pending);super.onDestroy();}

    private void save() {
        String example = exampleInput.getText().toString().trim();
        if (example.isEmpty()) {
            exampleInput.setError("请输入例句，或选择稍后补充");
            return;
        }
        try {
            JSONArray all = Store.tasks(this);
            JSONObject task = Store.find(all, taskId);
            if (task == null) throw new IllegalStateException("复习任务已不存在");
            JSONArray currentWords = task.getJSONArray("words");
            JSONObject word = currentWords.optJSONObject(indices[position]);
            if (word == null) throw new IllegalStateException("单词卡已不存在");
            word.put("example", example);
            Store.save(this, all);
            words = currentWords;
            advance();
        } catch (Exception e) {
            ui.toast("例句未保存：" + e.getMessage());
        }
    }

    private void advance() {
        position++;
        draft = "";
        show();
    }

    private void summary() {
        ui.start("例句完成");
        ui.body.addView(ui.text("忘记单词的例句已处理。跳过的词仍可在复习卡片中补充。", 16, WordUi.INK));
        ui.gap(ui.body, 16);
        ui.body.addView(ui.button("返回主页", true, v -> finish()));
    }
}
