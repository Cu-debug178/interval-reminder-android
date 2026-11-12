package com.example.reviewreminder;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spelling drill that follows a review round (or a single word from the wrong book).
 *
 * One focused question per screen: the Chinese meaning sits on a white card, the answer is
 * typed into a large serif field beneath it, and the single primary action is pinned to the
 * bottom. Checking tints the field (pine for right, terracotta for wrong) and reveals the
 * correct spelling in place, so the eye never has to leave the word.
 */
public final class SpellingActivity extends ScreenActivity {
    private WordUi ui;
    private final Feedback interaction = new Feedback();
    private long taskId;
    private int round;
    private long historyAt;
    private String attemptId;
    private JSONArray words;
    private int[] indices, exampleIndices;
    private int position, correct, wrong;
    private boolean answered, answerWasCorrect;
    private EditText answerInput;
    private String draft = "";

    // Persistent chrome: counter + progress stay put while the question beneath them changes.
    private LinearLayout head;
    private TextView counter, tally;
    private ProgressLine progress;
    private LinearLayout stage;
    // Per-question views updated in place when the answer is checked.
    private TextView exampleView, hint;
    private LinearLayout verdict;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new WordUi(this);
        interaction.prepare(this);
        taskId = getIntent().getLongExtra("taskId", 0);
        round = getIntent().getIntExtra("round", -1);
        historyAt = getIntent().getLongExtra("historyAt", 0);
        exampleIndices = getIntent().getIntArrayExtra("exampleIndices");
        if (exampleIndices == null) exampleIndices = new int[0];
        attemptId = getIntent().getStringExtra("attemptId");
        if (attemptId == null || attemptId.isEmpty()) {
            attemptId = UUID.randomUUID().toString();
            getIntent().putExtra("attemptId", attemptId);
        }
        try {
            JSONObject task = Store.find(Store.tasks(this), taskId);
            if (task == null) throw new Exception("复习任务已不存在");
            words = task.getJSONArray("words");
            indices = SpellingPlan.indices(SpellingPlan.WRONG_ONLY, words.length(), getIntent().getIntArrayExtra("indices"));
            if (state != null) {
                position = Math.max(0, Math.min(indices.length, state.getInt("position")));
                correct = Math.max(0, state.getInt("correct"));
                wrong = Math.max(0, state.getInt("wrong"));
                answered = state.getBoolean("answered");
                answerWasCorrect = state.getBoolean("answerWasCorrect");
                draft = state.getString("draft", "");
            }
            chrome();
            show(false);
        } catch (Exception e) {
            // Finish first so the notice falls back to a system toast and survives the exit.
            finish();
            ui.error(e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("position", position);
        out.putInt("correct", correct);
        out.putInt("wrong", wrong);
        out.putBoolean("answered", answered);
        out.putBoolean("answerWasCorrect", answerWasCorrect);
        if (answerInput != null) out.putString("draft", answerInput.getText().toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        interaction.release();
        super.onDestroy();
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    private void chrome() {
        ui.startModal("拼写练习");
        Notice.setBottomOffset(this, 84);

        head = ui.col();
        LinearLayout line = ui.row();
        counter = ui.type("", Theme.T_CAPTION);
        counter.setTypeface(Theme.MEDIUM);
        counter.setTextColor(Theme.INK_2);
        line.addView(counter, WordUi.weight());
        tally = ui.type("", Theme.T_CAPTION);
        line.addView(tally);
        head.addView(line, WordUi.lp());
        ui.gap(head, Theme.SPACE_8);
        progress = new ProgressLine(this);
        head.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(3)));
        ui.body.addView(head, WordUi.lp());

        stage = ui.col();
        ui.body.addView(stage, ui.lpMargin(Theme.SPACE_24, 0));
    }

    /** Renders the current question; {@code forward} slides it in from the right. */
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
        String english = word.optString("english").trim();
        String example = word.optString("example", "");

        head.setVisibility(View.VISIBLE);
        counter.setText((position + 1) + " / " + indices.length + " 个");
        updateTally();
        progress.set(position / (float) Math.max(1, indices.length), forward);

        stage.removeAllViews();

        // Question card: what the word means, never how it is spelled.
        LinearLayout card = ui.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setMinimumHeight(ui.dp(196));
        card.setPadding(ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_32), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_32));
        TextView overline = ui.type("根据释义拼写单词", Theme.T_OVERLINE);
        overline.setGravity(Gravity.CENTER);
        card.addView(overline, WordUi.lp());
        ui.gap(card, Theme.SPACE_20);
        TextView meaning = ui.type(word.optString("chinese", ""), Theme.T_TITLE);
        meaning.setTextSize(22);
        meaning.setTypeface(Theme.MEDIUM);
        meaning.setGravity(Gravity.CENTER);
        meaning.setLineSpacing(0, 1.25f);
        card.addView(meaning, WordUi.lp());
        exampleView = null;
        if (!example.isEmpty()) {
            ui.gap(card, Theme.SPACE_16);
            exampleView = ui.type(answered ? example : cloze(example, english), Theme.T_CALLOUT);
            exampleView.setTypeface(Theme.SERIF_ITALIC);
            exampleView.setTextSize(15);
            exampleView.setGravity(Gravity.CENTER);
            card.addView(exampleView, WordUi.lp());
        }
        stage.addView(card, WordUi.lp());

        // Answer field: large, centred, serif — the word is written like it would be printed.
        EditText answer = WordUi.input(this, "输入英文单词", draft);
        answerInput = answer;
        answer.setSingleLine(true);
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        answer.setImeOptions(EditorInfo.IME_ACTION_DONE);
        answer.setTypeface(Theme.SERIF);
        answer.setTextSize(24);
        answer.setGravity(Gravity.CENTER);
        answer.setPadding(ui.dp(Theme.SPACE_16), 0, ui.dp(Theme.SPACE_16), 0);
        answer.setSelection(answer.getText().length());
        answer.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft = s.toString();
                if (hint != null && hint.getVisibility() == View.VISIBLE && s.length() > 0) hint.setVisibility(View.GONE);
            }
            public void afterTextChanged(Editable e) {}
        });
        answer.setOnEditorActionListener((v, action, event) -> {
            if (answered) next(v);
            else check(word, wordIndex, v);
            return true;
        });
        LinearLayout.LayoutParams answerLp = ui.lpMargin(Theme.SPACE_16, 0);
        answerLp.height = ui.dp(64);
        stage.addView(answer, answerLp);

        hint = ui.type("", Theme.T_CAPTION);
        hint.setTextColor(Theme.DANGER);
        hint.setGravity(Gravity.CENTER);
        hint.setVisibility(View.GONE);
        stage.addView(hint, ui.lpMargin(Theme.SPACE_8, 0));

        verdict = ui.col();
        verdict.setGravity(Gravity.CENTER_HORIZONTAL);
        verdict.setVisibility(View.GONE);
        stage.addView(verdict, ui.lpMargin(Theme.SPACE_20, 0));

        if (answered) {
            applyResult(word, false);
        } else {
            Button skip = ui.secondaryButton("跳过此词", v -> {
                if (!answered) advance();
            });
            Button submit = ui.primaryButton("检查拼写", v -> check(word, wordIndex, v));
            ui.pinPair(skip, submit);
            showKeyboard(answer);
        }
        Motion.enter(stage, forward ? 1 : 0);
    }

    private void check(JSONObject word, int wordIndex, View source) {
        if (answered) {
            next(source);
            return;
        }
        String typed = answerInput.getText().toString().trim();
        if (typed.isEmpty()) {
            showHint("请输入英文单词");
            return;
        }
        String expected = word.optString("english").trim();
        if (expected.equalsIgnoreCase(typed)) {
            answered = true;
            answerWasCorrect = true;
            correct++;
            interaction.respond(source, Feedback.KNOWN);
        } else {
            try {
                String event = (historyAt > 0 ? "spelling:" + historyAt : "practice:" + attemptId) + ":" + position + ":" + wordIndex;
                WrongBook.record(this, taskId, wordIndex, round, words, event);
                answered = true;
                answerWasCorrect = false;
                wrong++;
            } catch (Exception e) {
                ui.error("错题记录保存失败：" + e.getMessage());
                return;
            }
            interaction.respond(source, Feedback.RETRY);
        }
        applyResult(word, true);
    }

    /** Shows the checked state: tinted field, correct spelling, and the next/result action. */
    private void applyResult(JSONObject word, boolean animate) {
        String expected = word.optString("english").trim();
        if (animate) Motion.layout(stage);
        hideKeyboard(answerInput);
        answerInput.clearFocus();
        answerInput.setEnabled(false);
        answerInput.setCursorVisible(false);
        hint.setVisibility(View.GONE);

        int fill = answerWasCorrect ? Theme.PRIMARY_SOFT : Theme.DANGER_SOFT;
        int ink = answerWasCorrect ? Theme.PRIMARY : Theme.DANGER;
        answerInput.setBackground(Theme.shape(this, fill, Theme.RADIUS_FIELD));
        answerInput.setTextColor(ink);
        int flags = answerInput.getPaintFlags();
        answerInput.setPaintFlags(answerWasCorrect ? flags & ~Paint.STRIKE_THRU_TEXT_FLAG : flags | Paint.STRIKE_THRU_TEXT_FLAG);
        if (exampleView != null) exampleView.setText(word.optString("example", ""));

        verdict.removeAllViews();
        if (answerWasCorrect) {
            LinearLayout line = ui.row();
            line.setGravity(Gravity.CENTER);
            ImageView mark = ui.icon(R.drawable.ic_circle_check, Theme.PRIMARY);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ui.dp(Theme.ICON_SMALL), ui.dp(Theme.ICON_SMALL));
            mp.rightMargin = ui.dp(Theme.SPACE_8);
            line.addView(mark, mp);
            TextView label = ui.type("拼写正确", Theme.T_LABEL);
            label.setTextColor(Theme.PRIMARY);
            line.addView(label);
            verdict.addView(line, WordUi.lp());
        } else {
            TextView over = ui.type("正确拼写", Theme.T_OVERLINE);
            over.setGravity(Gravity.CENTER);
            verdict.addView(over, WordUi.lp());
            ui.gap(verdict, Theme.SPACE_8);
            TextView spelled = ui.type(expected, Theme.T_TITLE);
            spelled.setTypeface(Theme.SERIF);
            spelled.setTextSize(28);
            spelled.setGravity(Gravity.CENTER);
            spelled.setTextIsSelectable(true);
            verdict.addView(spelled, WordUi.lp());
            ui.gap(verdict, Theme.SPACE_8);
            TextView noted = ui.type("已记入错题本", Theme.T_CAPTION);
            noted.setGravity(Gravity.CENTER);
            verdict.addView(noted, WordUi.lp());
        }
        verdict.setVisibility(View.VISIBLE);
        if (animate) Motion.enter(verdict);

        updateTally();
        progress.set((position + 1) / (float) Math.max(1, indices.length), animate);
        ui.pin(ui.primaryButton(position + 1 < indices.length ? "下一个" : "查看结果", this::next));
    }

    private void next(View source) {
        if (!answered) return;
        if (position + 1 >= indices.length) interaction.confirm(source);
        advance();
    }

    private void advance() {
        position++;
        answered = false;
        answerWasCorrect = false;
        draft = "";
        show(true);
    }

    private void updateTally() {
        if (correct + wrong == 0) {
            tally.setText("");
            return;
        }
        tally.setText("正确 " + correct + " · 拼错 " + wrong);
    }

    private void showHint(String message) {
        Motion.layout(stage);
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        nudge(answerInput);
        answerInput.requestFocus();
    }

    // ── Result ───────────────────────────────────────────────────────────────

    private void summary() {
        saveResult();
        hideKeyboard(answerInput != null ? answerInput : stage);
        answerInput = null;
        head.setVisibility(View.GONE);
        stage.removeAllViews();

        int total = indices.length;
        int skipped = Math.max(0, total - correct - wrong);

        TextView title = ui.type("拼写完成", Theme.T_TITLE);
        stage.addView(title, WordUi.lp());
        ui.gap(stage, Theme.SPACE_8);
        TextView line = ui.type("共 " + total + " 个单词，正确 " + correct + " 个，拼写错误 " + wrong + " 个", Theme.T_CALLOUT);
        stage.addView(line, WordUi.lp());
        ui.gap(stage, Theme.SPACE_24);

        LinearLayout card = ui.card();
        card.setPadding(ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_20));
        LinearLayout stats = ui.row();
        stats.setGravity(Gravity.TOP);
        stat(stats, correct, "正确", correct > 0 ? Theme.PRIMARY : Theme.INK);
        stat(stats, wrong, "拼错", wrong > 0 ? Theme.DANGER : Theme.INK);
        stat(stats, skipped, "跳过", Theme.INK_2);
        card.addView(stats, WordUi.lp());
        stage.addView(card, WordUi.lp());
        if (wrong > 0) ui.note(stage, "拼错的词已记入错题本，可在“我的 › 错题本”里再练。");

        final int[] missing = ExampleActivity.missingExampleIndices(words, exampleIndices);
        ui.pin(ui.primaryButton(missing.length > 0 ? "继续补充例句" : "完成", v -> continueAfterSpelling()));
        Motion.stagger(stage);
    }

    /** One column of the result row: a large figure over a quiet label, separated by hairlines. */
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

    private void continueAfterSpelling() {
        int[] missing = ExampleActivity.missingExampleIndices(words, exampleIndices);
        if (missing.length > 0) {
            Motion.open(this, new Intent(this, ExampleActivity.class).putExtra("taskId", taskId).putExtra("indices", missing));
        }
        finish();
    }

    private void saveResult() {
        if (historyAt <= 0) return;
        try {
            JSONArray tasks = Store.tasks(this);
            JSONObject task = Store.find(tasks, taskId);
            if (task == null) return;
            JSONArray history = task.optJSONArray("history");
            if (history == null) return;
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject entry = history.optJSONObject(i);
                if (entry != null && entry.optLong("at") == historyAt && entry.optInt("stage") == round) {
                    entry.put("spellingTotal", indices.length)
                            .put("spellingCorrect", correct)
                            .put("spellingWrong", wrong)
                            .put("spellingSkipped", Math.max(0, indices.length - correct - wrong));
                    Store.save(this, tasks);
                    return;
                }
            }
        } catch (Exception e) {
            ui.error("拼写成绩暂未写入：" + e.getMessage());
        }
    }

    @Override public void onBackPressed() {
        boolean midway = indices != null && position < indices.length && (position > 0 || answered);
        if (!midway) {
            finish();
            return;
        }
        String message = historyAt > 0
                ? "拼错的词已记入错题本；本次拼写成绩不会写入复习记录。"
                : "拼错的词已记入错题本。";
        Sheet.confirm(this, "结束拼写？", message, "继续拼写", "结束", true, this::finish);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Hides the target word (and its inflections) inside the example so it hints without telling. */
    private static String cloze(String example, String english) {
        if (english == null || english.isEmpty()) return example;
        try {
            Pattern word = Pattern.compile("(?i)(?<![\\p{L}])" + Pattern.quote(english) + "[\\p{L}]*");
            return word.matcher(example).replaceAll("_____");
        } catch (RuntimeException e) {
            return example;
        }
    }

    /** A short horizontal shake that says "not yet" without a dialog. */
    private static void nudge(View v) {
        if (v == null || !Motion.enabled(v.getContext())) return;
        float d = v.getResources().getDisplayMetrics().density;
        ObjectAnimator shake = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, -6 * d, 5 * d, -3 * d, 0);
        shake.setDuration(260);
        shake.setInterpolator(Motion.EASE);
        shake.start();
    }

    private void showKeyboard(EditText field) {
        field.requestFocus();
        field.postDelayed(() -> {
            if (!field.isAttachedToWindow() || !field.isEnabled() || isFinishing()) return;
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private void hideKeyboard(View anchor) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && anchor != null && anchor.getWindowToken() != null) imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
    }
}
