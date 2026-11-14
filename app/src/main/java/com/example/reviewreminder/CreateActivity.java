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
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Random;

/**
 * "创造力造句": write one English sentence with a given word and let AI grade it.
 *
 * Layout reads top to bottom like a writing exercise: the prompt word on a white card, the
 * writing field, then the teacher's notes in a calm result card (verdict, suggested rewrite,
 * issues). The pinned footer keeps "换一个词" beside the single primary "提交批改".
 */
public final class CreateActivity extends ScreenActivity {
    private WordUi ui;
    private final Feedback interaction = new Feedback();
    private JSONArray pool;
    private int wordIndex = -1;
    private EditText sentence;
    private TextView prompt, hint;
    private LinearLayout result;
    private Button submit;
    /** Sentence restored after rotation. Cleared once the field is rebuilt. */
    private String draft;
    /** Bumps on "换一个词" so a grade for the previous visit cannot change the new screen. */
    private int generation;
    /** Last shown grade, restored after rotation so the screen does not look unsent. */
    private String savedResult;
    /**
     * One grade survives the activity that started it. Rotation attaches the new screen;
     * leaving the activity still records a failing sentence exactly once.
     */
    private static GradeJob inflight;
    /** Grade currently drawn on this screen, so rotation can put the same card back. */
    private GradeJob shown;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new WordUi(this);
        interaction.prepare(this);
        try {
            pool = buildPool();
            if (pool.length() == 0) throw new Exception("还没有单词卡，请先录词并创建复习任务");
            if (state != null) {
                wordIndex = state.getInt("wordIndex", -1);
                draft = state.getString("draft", "");
                savedResult = state.getString("result");
                generation = state.getInt("generation", 0);
            }
            if (wordIndex < 0 || wordIndex >= pool.length()) pickWord();
            ui.startModal("创造力造句");
            Notice.setBottomOffset(this, 84);
            show(false);
        } catch (Exception e) {
            // Finish first so the notice falls back to a system toast and survives the exit.
            finish();
            ui.error(e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("wordIndex", wordIndex);
        out.putInt("generation", generation);
        if (sentence != null) out.putString("draft", sentence.getText().toString());
        if (shown != null && shown.finished && shown.error == null) {
            try {
                JSONObject saved = new JSONObject()
                        .put("word", shown.wordIndex)
                        .put("text", shown.text)
                        .put("ok", shown.ok)
                        .put("corrected", shown.corrected)
                        .put("tip", shown.tip)
                        .put("recorded", shown.recorded)
                        .put("issues", shown.issues == null ? new JSONArray() : shown.issues);
                out.putString("result", saved.toString());
            } catch (Exception ignored) {}
        }
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        if (inflight != null && inflight.owner == this) {
            inflight.recreating = isChangingConfigurations();
            inflight.owner = null;
            if (!inflight.recreating && inflight.finished) finishJob(inflight);
        }
        interaction.release();
        super.onDestroy();
    }

    private JSONArray buildPool() throws Exception {
        JSONArray out = new JSONArray();
        JSONArray tasks = Store.tasks(this);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null) continue;
            JSONArray words = task.optJSONArray("words");
            if (words == null) continue;
            for (int j = 0; j < words.length(); j++) {
                JSONObject w = words.optJSONObject(j);
                if (w == null) continue;
                String en = w.optString("english", "").trim();
                if (en.isEmpty()) continue;
                out.put(new JSONObject()
                        .put("english", en)
                        .put("chinese", w.optString("chinese"))
                        .put("taskId", task.optLong("id"))
                        .put("wordIndex", j));
            }
        }
        return out;
    }

    private void pickWord() {
        wordIndex = new Random().nextInt(pool.length());
    }

    /** "换一个词": a different word whenever there is more than one to choose from. */
    private void nextWord() {
        // A grade still running for the word being left keeps running and is recorded once.
        // It is not drawn on the new word.
        generation++;
        shown = null;
        int before = wordIndex;
        pickWord();
        for (int tries = 0; pool.length() > 1 && wordIndex == before && tries < 8; tries++) pickWord();
        hideKeyboard();
        show(true);
    }

    private void show(boolean forward) {
        final JSONObject item = pool.optJSONObject(wordIndex);
        if (item == null) {
            finish();
            return;
        }
        String english = item.optString("english");
        ui.body.removeAllViews();

        TextView intro = ui.type("用指定单词写一句英文，AI 会批改并记录问题。", Theme.T_CALLOUT);
        intro.setPadding(ui.dp(Theme.SPACE_4), 0, ui.dp(Theme.SPACE_4), 0);
        ui.body.addView(intro, WordUi.lp());

        // Prompt card: the word to use, set in serif like a dictionary headword.
        LinearLayout card = ui.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_32), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_32));
        TextView over = ui.type("用这个词造句", Theme.T_OVERLINE);
        over.setGravity(Gravity.CENTER);
        card.addView(over, WordUi.lp());
        ui.gap(card, Theme.SPACE_16);
        prompt = ui.type(english, Theme.T_DISPLAY);
        prompt.setTypeface(Theme.SERIF);
        prompt.setTextSize(34);
        prompt.setLetterSpacing(0);
        prompt.setGravity(Gravity.CENTER);
        card.addView(prompt, WordUi.lp());
        String chinese = item.optString("chinese");
        if (!chinese.isEmpty()) {
            ui.gap(card, Theme.SPACE_8);
            TextView zh = ui.type(chinese, Theme.T_CALLOUT);
            zh.setGravity(Gravity.CENTER);
            card.addView(zh, WordUi.lp());
        }
        ui.body.addView(card, ui.lpMargin(Theme.SPACE_16, 0));

        // Writing field.
        TextView label = ui.sectionLabel("你的造句");
        label.setPadding(ui.dp(Theme.SPACE_4), 0, 0, 0);
        ui.body.addView(label, ui.lpMargin(Theme.SPACE_24, Theme.SPACE_8));
        String restored = draft == null ? "" : draft;
        draft = null;
        sentence = WordUi.input(this, "用 " + english + " 写一句英文", restored);
        if (!restored.isEmpty()) sentence.setSelection(restored.length());
        sentence.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sentence.setTypeface(Theme.SERIF);
        sentence.setTextSize(17);
        sentence.setHint(SpellingActivity.sansHint("用 " + english + " 写一句英文", 0));
        sentence.setGravity(Gravity.TOP | Gravity.START);
        sentence.setMinLines(3);
        sentence.setMaxLines(6);
        sentence.setPadding(ui.dp(Theme.SPACE_16), ui.dp(14), ui.dp(Theme.SPACE_16), ui.dp(14));
        sentence.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (hint != null && hint.getVisibility() == View.VISIBLE && s.length() > 0) hint.setVisibility(View.GONE);
            }
            public void afterTextChanged(Editable e) {}
        });
        ui.body.addView(sentence, WordUi.lp());

        hint = ui.type("", Theme.T_CAPTION);
        hint.setTextColor(Theme.DANGER);
        hint.setPadding(ui.dp(Theme.SPACE_4), 0, 0, 0);
        hint.setVisibility(View.GONE);
        ui.body.addView(hint, ui.lpMargin(Theme.SPACE_8, 0));

        // Teacher's notes appear here after grading.
        result = ui.col();
        result.setVisibility(View.GONE);
        ui.body.addView(result, ui.lpMargin(Theme.SPACE_16, 0));

        Button change = ui.secondaryButton("换一个词", v -> nextWord());
        submit = ui.primaryButton("提交批改", v -> grade(item));
        ui.pinPair(change, submit);

        if (forward) Motion.enter(ui.body, 1);
        else Motion.stagger(ui.body);
        adoptGrade();
    }

    private void grade(JSONObject item) {
        if (!AiSettings.ready(this)) {
            ui.error(AiSettings.notReadyMessage(this));
            return;
        }
        String text = sentence.getText().toString().trim();
        if (text.isEmpty()) {
            showHint("请先写一句");
            return;
        }
        // This visit already has a request running (including one restored after rotation).
        // Editing the field must not start a second grade that the first result could dismiss.
        if (inflight != null && !inflight.finished && inflight.generation == generation && inflight.wordIndex == wordIndex) {
            inflight.owner = this;
            showWaiting();
            return;
        }
        hideKeyboard();
        showWaiting();
        GradeJob job = new GradeJob(getApplicationContext(), wordIndex, item.optString("english"), text);
        job.generation = generation;
        job.owner = this;
        inflight = job;
        shown = null;
        AiClient.chatJson(this, AiPrompts.systemCreateGrade(), AiPrompts.userCreateGrade(job.english, text), new AiClient.Callback() {
            @Override public void onResult(JSONObject json) {
                job.ok = json.optBoolean("ok", false);
                job.corrected = json.optString("corrected", text).trim();
                job.tip = json.optString("feedback", "").trim();
                job.issues = json.optJSONArray("issues");
                StringBuilder body = new StringBuilder();
                body.append(job.ok ? "通过：" : "需改进：").append(job.tip.isEmpty() ? (job.ok ? "用法正确" : "请看改正建议") : job.tip);
                if (!job.corrected.isEmpty() && !job.corrected.equals(text)) body.append("\n建议：").append(job.corrected);
                if (job.issues != null) for (int i = 0; i < job.issues.length(); i++) {
                    String issue = job.issues.optString(i).trim();
                    if (!issue.isEmpty()) body.append("\n· ").append(issue);
                }
                if (!job.ok) {
                    try {
                        AiNotes.addMistake(job.app, job.english, text, job.tip.isEmpty() ? body.toString() : job.tip);
                        job.recorded = true;
                    } catch (Exception e) {
                        job.recordError = e.getMessage();
                    }
                }
                job.finished = true;
                deliver(job);
            }

            @Override public void onError(String message) {
                job.error = message;
                job.finished = true;
                deliver(job);
            }
        });
    }

    /** Show the grade for this visit, even if the field was edited after it was submitted. */
    private void adoptGrade() {
        GradeJob job = inflight;
        if (job != null && job.generation == generation && job.wordIndex == wordIndex) {
            job.owner = this;
            if (!job.finished) {
                showWaiting();
                return;
            }
            present(job, true);
            if (inflight == job) inflight = null;
            return;
        }
        if (savedResult == null) return;
        try {
            JSONObject saved = new JSONObject(savedResult);
            savedResult = null;
            if (saved.optInt("word", -1) != wordIndex) return;
            GradeJob restored = new GradeJob(getApplicationContext(), wordIndex, "", saved.optString("text"));
            restored.ok = saved.optBoolean("ok");
            restored.corrected = saved.optString("corrected");
            restored.tip = saved.optString("tip");
            restored.recorded = saved.optBoolean("recorded");
            restored.issues = saved.optJSONArray("issues");
            restored.finished = true;
            present(restored, false);
        } catch (Exception ignored) {
            savedResult = null;
        }
    }

    private void showWaiting() {
        busy(submit, true);
        Motion.layout(ui.body);
        result.removeAllViews();
        TextView waiting = ui.type("正在批改…", Theme.T_CALLOUT);
        waiting.setTextColor(Theme.INK_3);
        waiting.setPadding(ui.dp(Theme.SPACE_4), 0, 0, 0);
        result.addView(waiting, WordUi.lp());
        result.setVisibility(View.VISIBLE);
    }

    /**
     * Draw a finished grade only when it is still the request for this visit.
     * An older request must not clear a newer "正在批改…" or replace its card.
     */
    private static void deliver(GradeJob job) {
        CreateActivity owner = job.owner;
        boolean alive = owner != null && !owner.isFinishing() && !owner.isDestroyed();
        boolean current = inflight == job;
        if (alive && current && owner.generation == job.generation && owner.wordIndex == job.wordIndex) {
            owner.present(job, true);
            inflight = null;
            return;
        }
        if (alive) {
            owner.noteOffscreen(job);
            if (current) inflight = null;
            return;
        }
        boolean recreating = current && (job.recreating || (owner != null && owner.isChangingConfigurations()));
        if (recreating) {
            job.recreating = true;
            job.owner = null;
            return;
        }
        finishJob(job);
    }

    private static void finishJob(GradeJob job) {
        if (job.recorded) Toast.makeText(job.app, "已记入造句问题，可在错题本查看", Toast.LENGTH_LONG).show();
        else if (job.recordError != null) Toast.makeText(job.app, job.recordError, Toast.LENGTH_LONG).show();
        if (inflight == job) inflight = null;
    }

    private void present(GradeJob job, boolean announce) {
        shown = job;
        busy(submit, false);
        if (job.error != null) {
            Motion.layout(ui.body);
            result.removeAllViews();
            result.setVisibility(View.GONE);
            if (announce) ui.error(job.error);
            return;
        }
        renderResult(result, job.ok, job.tip, job.corrected, job.text, job.issues, job.recorded);
        if (!announce) return;
        interaction.respond(submit, job.ok ? Feedback.KNOWN : Feedback.RETRY);
        if (job.recorded) ui.toast("已记入造句问题，可在错题本查看");
        else if (job.recordError != null) ui.error(job.recordError);
    }

    /** The grade belongs to a word this screen is no longer showing. */
    private void noteOffscreen(GradeJob job) {
        if (job.recorded) ui.toast("已记入造句问题，可在错题本查看");
        else if (job.recordError != null) ui.error(job.recordError);
        else if (job.error != null) ui.error(job.error);
    }

    /** One in-flight or just-finished grade, independent of any single activity instance. */
    private static final class GradeJob {
        final Context app;
        final int wordIndex;
        final String english;
        final String text;
        int generation;
        CreateActivity owner;
        boolean recreating;
        boolean finished;
        boolean ok;
        boolean recorded;
        String corrected = "";
        String tip = "";
        String recordError;
        String error;
        JSONArray issues;

        GradeJob(Context app, int wordIndex, String english, String text) {
            this.app = app;
            this.wordIndex = wordIndex;
            this.english = english;
            this.text = text;
        }
    }

    /** Result card: verdict chip + note, then the suggested rewrite and any issues. */
    private void renderResult(LinearLayout target, boolean ok, String tip, String corrected, String text, JSONArray issues, boolean recorded) {
        Motion.layout(ui.body);
        target.removeAllViews();
        LinearLayout card = ui.card();

        LinearLayout verdictRow = ui.row();
        verdictRow.addView(ui.chip(ok ? "通过" : "需改进", ok ? Theme.PRIMARY_SOFT : Theme.WARN_SOFT, ok ? Theme.PRIMARY : Theme.WARN));
        card.addView(verdictRow, WordUi.lp());
        ui.gap(card, Theme.SPACE_12);
        TextView note = ui.type(tip.isEmpty() ? (ok ? "用法正确" : "请看改正建议") : tip, Theme.T_BODY);
        card.addView(note, WordUi.lp());

        if (!corrected.isEmpty() && !corrected.equals(text)) {
            ui.gap(card, Theme.SPACE_20);
            card.addView(ui.type("建议改写", Theme.T_OVERLINE), WordUi.lp());
            ui.gap(card, Theme.SPACE_8);
            TextView rewrite = ui.type(corrected, Theme.T_BODY);
            rewrite.setTypeface(Theme.SERIF);
            rewrite.setTextSize(17);
            rewrite.setLineSpacing(0, 1.3f);
            rewrite.setTextIsSelectable(true);
            card.addView(rewrite, WordUi.lp());
        }

        boolean headed = false;
        if (issues != null) for (int i = 0; i < issues.length(); i++) {
            String issue = issues.optString(i).trim();
            if (issue.isEmpty()) continue;
            if (!headed) {
                ui.gap(card, Theme.SPACE_20);
                card.addView(ui.type("问题", Theme.T_OVERLINE), WordUi.lp());
                headed = true;
            }
            LinearLayout line = ui.row();
            line.setGravity(Gravity.TOP);
            View dot = new View(this);
            dot.setBackground(Theme.oval(Theme.INK_3));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.dp(5), ui.dp(5));
            dotLp.topMargin = ui.dp(9);
            dotLp.rightMargin = ui.dp(Theme.SPACE_12);
            line.addView(dot, dotLp);
            TextView t = ui.type(issue, Theme.T_CALLOUT);
            t.setTextColor(Theme.INK);
            line.addView(t, WordUi.weight());
            card.addView(line, ui.lpMargin(Theme.SPACE_8, 0));
        }

        if (recorded) {
            ui.gap(card, Theme.SPACE_16);
            card.addView(ui.type("已记入错题本 · 造句问题", Theme.T_CAPTION), WordUi.lp());
        }

        target.addView(card, WordUi.lp());
        target.setVisibility(View.VISIBLE);
        Motion.enter(card);
        ui.scroll.post(() -> {
            int y = Math.max(0, target.getTop() - ui.dp(Theme.SPACE_16));
            if (Motion.enabled(this)) ui.scroll.smoothScrollTo(0, y);
            else ui.scroll.scrollTo(0, y);
        });
    }

    private void busy(Button trigger, boolean on) {
        if (trigger == null) return;
        trigger.setText(on ? "批改中…" : "提交批改");
        trigger.setEnabled(!on);
        trigger.setAlpha(on ? .72f : 1f);
    }

    private void showHint(String message) {
        Motion.layout(ui.body);
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        nudge(sentence);
        sentence.requestFocus();
    }

    @Override public void onBackPressed() {
        boolean unsaved = sentence != null && !sentence.getText().toString().trim().isEmpty()
                && (result == null || result.getVisibility() != View.VISIBLE);
        if (!unsaved) {
            finish();
            return;
        }
        Sheet.confirm(this, "放弃这句造句？", "还没有提交批改，离开后不会保留。", "继续写", "放弃", true, this::finish);
    }

    private static void nudge(View v) {
        if (v == null || !Motion.enabled(v.getContext())) return;
        float d = v.getResources().getDisplayMetrics().density;
        ObjectAnimator shake = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, -6 * d, 5 * d, -3 * d, 0);
        shake.setDuration(260);
        shake.setInterpolator(Motion.EASE);
        shake.start();
    }

    private void hideKeyboard() {
        View anchor = sentence != null ? sentence : ui.body;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && anchor != null && anchor.getWindowToken() != null) imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
    }
}
