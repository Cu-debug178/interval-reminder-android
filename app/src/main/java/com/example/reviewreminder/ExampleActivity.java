package com.example.reviewreminder;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/**
 * Adds example sentences to the words forgotten in a review round.
 *
 * Same focused modal language as review and spelling: a thin progress line, the headword in
 * serif on a white card, a roomy writing field, and a pinned primary action. AI generation is
 * an inline text action beside the field label — available, never shouting.
 */
public final class ExampleActivity extends ScreenActivity {
    private WordUi ui;
    private final Feedback interaction = new Feedback();
    private long taskId;
    private JSONArray words;
    private int[] indices;
    private int position;
    private int saved;
    private EditText exampleInput;
    private String draft = "";
    private java.util.concurrent.Future<?> pending;

    private LinearLayout head;
    private TextView counter;
    private ProgressLine progress;
    private LinearLayout stage;
    private TextView hint;

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
        interaction.prepare(this);
        taskId = getIntent().getLongExtra("taskId", 0);
        indices = getIntent().getIntArrayExtra("indices");
        if (indices == null) indices = new int[0];
        if (state != null) {
            position = Math.max(0, state.getInt("position"));
            draft = state.getString("draft", "");
            saved = Math.max(0, state.getInt("saved"));
        }
        try {
            JSONObject task = Store.find(Store.tasks(this), taskId);
            if (task == null) throw new IllegalStateException("复习任务已不存在");
            words = task.getJSONArray("words");
            chrome();
            show(false);
            // A result handed over by the screen that opened this one (e.g. the saved review round).
            String notice = getIntent().getStringExtra("notice");
            if (state == null && notice != null && !notice.isEmpty()) ui.body.post(() -> ui.toast(notice));
        } catch (Exception e) {
            // Finish first so the notice falls back to a system toast and survives the exit.
            finish();
            ui.error(e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("position", position);
        out.putInt("saved", saved);
        if (exampleInput != null) out.putString("draft", exampleInput.getText().toString());
        else out.putString("draft", draft);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        AiClient.cancel(pending);
        interaction.release();
        super.onDestroy();
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    private void chrome() {
        ui.startModal("补充例句");
        Notice.setBottomOffset(this, 84);

        // Same header as review, spelling and the sense quiz: the line, then a quiet counter.
        head = ui.col();
        progress = new ProgressLine(this);
        head.addView(progress, WordUi.lp());
        counter = ui.type("", Theme.T_CALLOUT);
        counter.setTextColor(Theme.INK_2);
        counter.setFontFeatureSettings("tnum");
        counter.setMinHeight(ui.dp(28));
        counter.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(counter, ui.lpMargin(Theme.SPACE_12, 0));
        ui.body.addView(head, WordUi.lp());

        stage = ui.col();
        ui.body.addView(stage, ui.lpMargin(Theme.SPACE_24, 0));
    }

    private void show(boolean forward) {
        if (position >= indices.length) {
            summary();
            return;
        }
        final int wordIndex = indices[position];
        final JSONObject word = words.optJSONObject(wordIndex);
        if (word == null) {
            advance();
            return;
        }

        head.setVisibility(View.VISIBLE);
        counter.setText((position + 1) + " / " + indices.length + " 个忘记的单词");
        progress.set(position / (float) Math.max(1, indices.length), forward);
        stage.removeAllViews();

        // The word, set like a dictionary entry.
        LinearLayout card = ui.card();
        card.setPadding(ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_24));
        TextView english = ui.type(word.optString("english"), Theme.T_DISPLAY);
        english.setTypeface(Theme.SERIF);
        english.setTextSize(32);
        english.setLetterSpacing(0);
        card.addView(english, WordUi.lp());
        String chinese = word.optString("chinese");
        if (!chinese.isEmpty()) {
            ui.gap(card, Theme.SPACE_8);
            TextView meaning = ui.type(chinese, Theme.T_BODY);
            meaning.setTextColor(Theme.INK_2);
            card.addView(meaning, WordUi.lp());
        }
        stage.addView(card, WordUi.lp());

        // Field label with the AI helper tucked to its right.
        LinearLayout labelRow = ui.row();
        labelRow.setPadding(ui.dp(Theme.SPACE_4), 0, 0, 0);
        labelRow.addView(ui.sectionLabel("为这个词写一个英文例句"), WordUi.weight());
        final Button ai = ui.textButton("AI 生成", null);
        ui.setIcon(ai, R.drawable.ic_sparkles, Theme.PRIMARY, 16);
        ai.setContentDescription("AI 生成例句");
        ai.setOnClickListener(v -> aiFill(word, ai));
        labelRow.addView(ai, new LinearLayout.LayoutParams(-2, -2));
        stage.addView(labelRow, ui.lpMargin(Theme.SPACE_20, Theme.SPACE_4));

        exampleInput = WordUi.input(this, "输入例句", draft);
        exampleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        exampleInput.setTypeface(Theme.SERIF);
        exampleInput.setTextSize(17);
        exampleInput.setHint(SpellingActivity.sansHint("输入例句", 0));
        exampleInput.setGravity(Gravity.TOP | Gravity.START);
        exampleInput.setMinLines(3);
        exampleInput.setMaxLines(6);
        exampleInput.setPadding(ui.dp(Theme.SPACE_16), ui.dp(14), ui.dp(Theme.SPACE_16), ui.dp(14));
        exampleInput.setSelection(exampleInput.getText().length());
        exampleInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft = s.toString();
                if (hint != null && hint.getVisibility() == View.VISIBLE && s.length() > 0) hint.setVisibility(View.GONE);
            }
            public void afterTextChanged(Editable e) {}
        });
        stage.addView(exampleInput, WordUi.lp());

        hint = ui.type("", Theme.T_CAPTION);
        hint.setTextColor(Theme.DANGER);
        hint.setPadding(ui.dp(Theme.SPACE_4), 0, 0, 0);
        hint.setVisibility(View.GONE);
        stage.addView(hint, ui.lpMargin(Theme.SPACE_8, 0));
        ui.note(stage, "例句会保存到这张单词卡，复习时一起显示。");

        boolean last = position + 1 == indices.length;
        Button later = ui.secondaryButton("稍后补充", v -> advance());
        Button save = ui.primaryButton(last ? "保存并完成" : "保存并继续", this::save);
        ui.pinPair(later, save);

        Motion.enter(stage, forward ? 1 : 0);
    }

    private void aiFill(JSONObject word, Button trigger) {
        if (!AiSettings.ready(this)) {
            ui.error(AiSettings.notReadyMessage(this));
            return;
        }
        final int at = position;
        busy(trigger, true);
        pending = AiClient.chatJson(this, AiPrompts.systemExample(), AiPrompts.userExample(word.optString("english"), word.optString("chinese")), new AiClient.Callback() {
            @Override public void onResult(JSONObject json) {
                // A request abandoned on advance may still complete; ignore it entirely.
                if (position != at) return;
                busy(trigger, false);
                String value = json.optString("example", "").trim();
                if (value.isEmpty()) {
                    ui.error("模型未返回例句");
                    return;
                }
                // Only fill the field the request was made for; the user may have moved on.
                if (exampleInput != null) {
                    exampleInput.setText(value);
                    exampleInput.setSelection(exampleInput.getText().length());
                    draft = value;
                    Feedback.tick(exampleInput);
                    Motion.enter(exampleInput);
                }
            }
            @Override public void onError(String message) {
                if (position != at) return;
                busy(trigger, false);
                ui.error(message);
            }
        });
    }

    /** Inline loading state on the AI action instead of a transient toast. */
    private static void busy(Button trigger, boolean on) {
        trigger.setText(on ? "生成中…" : "AI 生成");
        WordUi.enable(trigger, !on);
        if (on) trigger.setAlpha(.6f);
    }

    private void save(View source) {
        String example = exampleInput.getText().toString().trim();
        if (example.isEmpty()) {
            showHint("请输入例句，或选择稍后补充");
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
            saved++;
            interaction.respond(source, Feedback.KNOWN);
            ui.toast("例句已保存");
            advance();
        } catch (Exception e) {
            ui.error("例句未保存：" + e.getMessage());
        }
    }

    private void advance() {
        // Abandon any AI example still in flight for the word being left.
        AiClient.cancel(pending);
        pending = null;
        position++;
        draft = "";
        hideKeyboard(exampleInput);
        show(true);
    }

    private void showHint(String message) {
        Motion.layout(stage);
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        nudge(exampleInput);
        exampleInput.requestFocus();
    }

    // ── Result ───────────────────────────────────────────────────────────────

    private void summary() {
        hideKeyboard(exampleInput != null ? exampleInput : stage);
        exampleInput = null;
        head.setVisibility(View.GONE);
        stage.removeAllViews();

        int later = Math.max(0, indices.length - saved);
        TextView title = ui.type("例句完成", Theme.T_TITLE);
        stage.addView(title, WordUi.lp());
        ui.gap(stage, Theme.SPACE_8);
        stage.addView(ui.type("忘记单词的例句已处理。跳过的词仍可在复习卡片中补充。", Theme.T_CALLOUT), WordUi.lp());
        ui.gap(stage, Theme.SPACE_24);

        LinearLayout card = ui.card();
        card.setPadding(ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_20));
        LinearLayout stats = ui.row();
        stats.setGravity(Gravity.TOP);
        stat(stats, saved, "已保存", saved > 0 ? Theme.PRIMARY : Theme.INK);
        stat(stats, later, "稍后补充", Theme.INK_2);
        card.addView(stats, WordUi.lp());
        stage.addView(card, WordUi.lp());

        ui.pin(ui.primaryButton("返回主页", v -> finish()));
        Motion.stagger(stage);
    }

    private void stat(LinearLayout row, int value, String label, int color) {
        if (row.getChildCount() > 0) {
            View rule = new View(this);
            rule.setBackgroundColor(Theme.HAIRLINE);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Math.max(1, ui.dp(.75f)), ui.dp(44));
            rp.topMargin = ui.dp(6);
            row.addView(rule, rp);
        }
        LinearLayout column = ui.col();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView number = ui.type(String.valueOf(value), Theme.T_DISPLAY);
        number.setTextColor(color);
        number.setGravity(Gravity.CENTER);
        column.addView(number, WordUi.lp());
        TextView caption = ui.type(label, Theme.T_CAPTION);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = WordUi.lp();
        cp.topMargin = ui.dp(Theme.SPACE_4);
        column.addView(caption, cp);
        row.addView(column, WordUi.weight());
        Motion.count(number, value, "");
    }

    @Override public void onBackPressed() {
        boolean unsaved = exampleInput != null && !exampleInput.getText().toString().trim().isEmpty();
        if (!unsaved) {
            finish();
            return;
        }
        Sheet.confirm(this, "放弃这条例句？", "输入的例句还没有保存。", "继续编辑", "放弃", true, this::finish);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void nudge(View v) {
        if (v == null || !Motion.enabled(v.getContext())) return;
        float d = v.getResources().getDisplayMetrics().density;
        ObjectAnimator shake = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, -6 * d, 5 * d, -3 * d, 0);
        shake.setDuration(260);
        shake.setInterpolator(Motion.EASE);
        shake.start();
    }

    private void hideKeyboard(View anchor) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && anchor != null && anchor.getWindowToken() != null) imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
    }
}
