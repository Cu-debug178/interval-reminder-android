package com.example.reviewreminder;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.TypefaceSpan;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spelling drill. The queue is a {@link SpellingSession}: an ordinary word is finished by one
 * independent correct answer, and a wrong-book word needs two in a row. A pronunciation hint,
 * a revealed answer, or a skip never counts as independent. The hint belongs to the word that
 * asked for it, so a rotation cannot leak it onto the next card.
 */
public final class SpellingActivity extends ScreenActivity {
    private WordUi ui;
    private final Feedback interaction = new Feedback();
    private SpeechManager speech;
    private long taskId, historyAt;
    private int round;
    private String attemptId, sessionId, selectionId;
    private JSONArray words;
    private int[] exampleIndices = new int[0];
    private SpellingSession session;
    private boolean wrongMode, answered, answerWasCorrect, hintUsed;
    private EditText answerInput;
    private String draft = "", draftWordId = "";

    private LinearLayout head;
    private TextView counter, tally, hintLabel;
    private ProgressLine progress;
    private LinearLayout stage;
    private TextView exampleView, hint;
    private LinearLayout verdict;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new WordUi(this);
        interaction.prepare(this);
        speech = new SpeechManager(this);
        taskId = getIntent().getLongExtra("taskId", 0);
        round = getIntent().getIntExtra("round", -1);
        historyAt = getIntent().getLongExtra("historyAt", 0);
        exampleIndices = getIntent().getIntArrayExtra("exampleIndices");
        if (exampleIndices == null) exampleIndices = new int[0];
        attemptId = getIntent().getStringExtra("attemptId");
        if (attemptId == null || attemptId.isEmpty()) attemptId = UUID.randomUUID().toString();
        sessionId = state == null ? getIntent().getStringExtra("sessionId") : state.getString("sessionId");
        if (sessionId == null || sessionId.isEmpty()) sessionId = UUID.randomUUID().toString();
        selectionId = getIntent().getStringExtra("selectionId");
        wrongMode = getIntent().getBooleanExtra("wrongMode", false);
        try {
            JSONObject saved = TrainingStore.spelling(this, sessionId);
            if (saved != null) {
                selectionId = saved.optString("selectionId", selectionId);
                wrongMode = saved.optBoolean("wrongMode", wrongMode);
                taskId = saved.optLong("taskId", taskId);
                round = saved.optInt("round", round);
                historyAt = saved.optLong("historyAt", historyAt);
                attemptId = saved.optString("attemptId", attemptId);
            }
            JSONObject selection = TrainingStore.selection(this, selectionId);
            if (selection == null && taskId > 0) {
                int[] indices = getIntent().getIntArrayExtra("indices");
                if (indices == null) indices = new int[0];
                selectionId = TrainingStore.forTask(this, taskId, indices);
                selection = TrainingStore.selection(this, selectionId);
            }
            if (selection == null) throw new IllegalStateException("拼写选词已失效，请重新开始");
            words = selection.getJSONArray("words");
            selectionId = selection.optString("id", selectionId);
            if (saved != null) session = SpellingSession.restore(saved.getString("snapshot"));
            else session = new SpellingSession(items(), wrongMode);
            String currentId = session.ended() ? "" : session.current();
            boolean storedHint = saved != null && !currentId.isEmpty() && currentId.equals(saved.optString("hintWord")) && saved.optBoolean("hintUsed");
            if (state != null) {
                String owned = state.getString("draftWordId", "");
                if (currentId.equals(owned)) {
                    draft = state.getString("draft", "");
                    draftWordId = owned;
                    hintUsed = state.getBoolean("hintUsed", false) || storedHint;
                } else if (storedHint) {
                    hintUsed = true;
                    draftWordId = currentId;
                }
            } else if (storedHint) {
                hintUsed = true;
                draftWordId = currentId;
            }
            if (session.initialTotal() == 0) {
                finish();
                ui.toast(wrongMode ? "这些单词已掌握，无需再练" : "没有需要拼写的单词");
                return;
            }
            chrome();
            show(false);
            if (state == null && historyAt > 0 && round > 0) ui.body.post(() -> ui.toast("第 " + round + " 轮已记录"));
        } catch (Exception e) {
            finish();
            ui.error(e.getMessage());
        }
    }

    private List<SpellingSession.Item> items() throws Exception {
        List<SpellingSession.Item> list = new ArrayList<>();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.optJSONObject(i);
            if (word == null) continue;
            JSONObject state = WrongBook.stateForWord(this, word);
            list.add(new SpellingSession.Item(Words.stableId(word), state.optInt("streak", word.optInt("spellingStreak", 0))));
        }
        return list;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("sessionId", sessionId);
        if (answerInput != null) out.putString("draft", answerInput.getText().toString());
        else out.putString("draft", draft);
        out.putString("draftWordId", draftWordId);
        out.putBoolean("hintUsed", hintUsed);
        saveProgress();
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        if (speech != null) speech.shutdown();
        interaction.release();
        super.onDestroy();
    }

    private void chrome() {
        ui.startModal(wrongMode ? "错词强化" : "拼写练习");
        Notice.setBottomOffset(this, 84);
        head = ui.col();
        progress = new ProgressLine(this);
        head.addView(progress, WordUi.lp());
        LinearLayout line = ui.row();
        line.setMinimumHeight(ui.dp(28));
        counter = ui.type("", Theme.T_CALLOUT);
        counter.setTextColor(Theme.INK_2);
        counter.setFontFeatureSettings("tnum");
        line.addView(counter, WordUi.weight());
        tally = ui.type("", Theme.T_CAPTION);
        tally.setFontFeatureSettings("tnum");
        line.addView(tally);
        head.addView(line, ui.lpMargin(Theme.SPACE_12, 0));
        ui.body.addView(head, WordUi.lp());
        stage = ui.col();
        ui.body.addView(stage, ui.lpMargin(Theme.SPACE_24, 0));
    }

    private void show(boolean forward) {
        int dropped = 0;
        while (session != null && !session.ended() && find(session.current()) == null) {
            if (!session.abandon(session.current()) || ++dropped > 10000) {
                ui.error("有单词已不在本次练习中");
                finish();
                return;
            }
        }
        if (dropped > 0) {
            String now = session.ended() ? "" : session.current();
            if (!now.equals(draftWordId)) {
                draft = "";
                hintUsed = false;
                draftWordId = now;
            }
            saveProgress();
        }
        if (session.ended()) {
            summary();
            return;
        }
        String wordId = session.current();
        JSONObject word = find(wordId);
        draftWordId = wordId;
        answered = false;
        answerWasCorrect = false;
        String english = word.optString("english").trim();
        String example = word.optString("example", "");
        head.setVisibility(View.VISIBLE);
        String progressText = "已完成 " + session.finishedCount() + " / " + session.initialTotal();
        if (wrongMode) progressText += " · 连续 " + session.currentStreak(wordId) + "/2";
        counter.setText(progressText);
        updateTally();
        progress.set(session.finishedCount() / (float) Math.max(1, session.initialTotal()), forward);
        stage.removeAllViews();

        LinearLayout card = ui.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setMinimumHeight(ui.dp(196));
        card.setPadding(ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_32), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_32));
        TextView overline = ui.type(wrongMode ? "错词强化 · 根据释义拼写" : "根据释义拼写单词", Theme.T_OVERLINE);
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
            exampleView = ui.type(cloze(example, english), Theme.T_CALLOUT);
            exampleView.setTypeface(Theme.SERIF_ITALIC);
            exampleView.setTextSize(15);
            exampleView.setGravity(Gravity.CENTER);
            card.addView(exampleView, WordUi.lp());
        }
        stage.addView(card, WordUi.lp());

        EditText answer = WordUi.input(this, "输入英文单词", draft);
        answerInput = answer;
        answer.setSingleLine(true);
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        answer.setImeOptions(EditorInfo.IME_ACTION_DONE);
        answer.setTypeface(Theme.SERIF);
        answer.setTextSize(24);
        answer.setHint(sansHint("输入英文单词", 16));
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
        answer.setOnEditorActionListener((v, actionId, event) -> {
            if (answered) next();
            else check(word, v);
            return true;
        });
        LinearLayout.LayoutParams answerLp = ui.lpMargin(Theme.SPACE_16, 0);
        answerLp.height = ui.dp(64);
        stage.addView(answer, answerLp);

        hintLabel = ui.type(hintUsed ? "已使用读音提示，答对后仍需独立拼写" : "", Theme.T_CAPTION);
        hintLabel.setGravity(Gravity.CENTER);
        hintLabel.setTextColor(Theme.INK_2);
        stage.addView(hintLabel, ui.lpMargin(Theme.SPACE_8, 0));

        hint = ui.type("", Theme.T_CAPTION);
        hint.setTextColor(Theme.DANGER);
        hint.setGravity(Gravity.CENTER);
        hint.setVisibility(View.GONE);
        stage.addView(hint, ui.lpMargin(Theme.SPACE_8, 0));

        verdict = ui.col();
        verdict.setGravity(Gravity.CENTER_HORIZONTAL);
        verdict.setVisibility(View.GONE);
        stage.addView(verdict, ui.lpMargin(Theme.SPACE_20, 0));

        LinearLayout tools = ui.row();
        tools.setGravity(Gravity.CENTER);
        tools.addView(ui.textButton(hintUsed ? "再听一次" : "读音提示", v -> useHint(word)));
        tools.addView(ui.textButton("查看答案", v -> { if (!answered) reveal(word); }));
        stage.addView(tools, ui.lpMargin(Theme.SPACE_4, 0));

        Button skip = ui.secondaryButton("跳过此词", v -> { if (!answered) skip(word); });
        Button submit = ui.primaryButton("检查拼写", v -> check(word, v));
        ui.pinPair(skip, submit);
        showKeyboard(answer);
        Motion.enter(stage, forward ? 1 : 0);
    }

    private void useHint(JSONObject word) {
        hintUsed = true;
        draftWordId = Words.stableId(word);
        saveProgress();
        if (hintLabel != null) hintLabel.setText("已使用读音提示，答对后仍需独立拼写");
        speech.speak(word.optString("english"), "spelling_hint");
    }

    private void check(JSONObject word, View source) {
        if (answered) {
            next();
            return;
        }
        String typed = answerInput.getText().toString().trim();
        if (typed.isEmpty()) {
            showHint("请输入英文单词");
            return;
        }
        String before = session.snapshot();
        String expected = word.optString("english").trim();
        boolean correct = expected.equalsIgnoreCase(typed);
        String id = Words.stableId(word);
        boolean assisted = hintUsed;
        SpellingSession.Result outcome = session.submit(id, correct, assisted);
        if (!outcome.accepted) return;
        try {
            applyOutcome(word, outcome);
        } catch (Exception e) {
            session = SpellingSession.restore(before);
            ui.error("结果保存失败，请重试：" + e.getMessage());
            return;
        }
        answered = true;
        answerWasCorrect = correct;
        hintUsed = false;
        saveProgress();
        interaction.respond(source, correct ? Feedback.KNOWN : Feedback.RETRY);
        if (correct) speech.speak(expected, "spelling_success");
        applyResult(word, outcome, true);
    }

    private void applyOutcome(JSONObject word, SpellingSession.Result outcome) throws Exception {
        long sourceTask = word.optLong("_taskId", taskId);
        int index = word.optInt("_wordIndex", -1);
        if (sourceTask <= 0 || index < 0) return;
        String event = "spelling:" + attemptId + ":" + session.attempts() + ":" + outcome.wordId;
        if (!outcome.correct) WrongBook.record(this, sourceTask, index, round, null, event, outcome.wordId);
        else if (outcome.independent) WrongBook.independent(this, sourceTask, index, null, event, outcome.wordId);
        else WrongBook.assisted(this, sourceTask, index, null, event, outcome.wordId);
    }

    private void reveal(JSONObject word) {
        String before = session.snapshot();
        String id = Words.stableId(word);
        if (!session.skip(id)) return;
        try {
            long sourceTask = word.optLong("_taskId", taskId);
            int index = word.optInt("_wordIndex", -1);
            if (sourceTask > 0 && index >= 0) WrongBook.reset(this, sourceTask, index, null, "spelling:" + attemptId + ":" + session.attempts() + ":" + id, id);
        } catch (Exception e) {
            session = SpellingSession.restore(before);
            ui.error(e.getMessage());
            return;
        }
        answered = true;
        answerWasCorrect = false;
        hintUsed = false;
        draft = "";
        saveProgress();
        applyResult(word, null, true);
    }

    private void skip(JSONObject word) {
        String before = session.snapshot();
        String id = Words.stableId(word);
        if (!session.skip(id)) return;
        try {
            long sourceTask = word.optLong("_taskId", taskId);
            int index = word.optInt("_wordIndex", -1);
            if (sourceTask > 0 && index >= 0) WrongBook.reset(this, sourceTask, index, null, "spelling:" + attemptId + ":" + session.attempts() + ":" + id, id);
        } catch (Exception e) {
            session = SpellingSession.restore(before);
            ui.error(e.getMessage());
            return;
        }
        hintUsed = false;
        draft = "";
        saveProgress();
        show(true);
    }

    private void applyResult(JSONObject word, SpellingSession.Result outcome, boolean animate) {
        String expected = word.optString("english").trim();
        boolean correct = outcome != null && outcome.correct;
        boolean assisted = outcome != null && outcome.correct && !outcome.independent;
        if (animate) Motion.layout(stage);
        hideKeyboard(answerInput);
        answerInput.clearFocus();
        answerInput.setEnabled(false);
        answerInput.setCursorVisible(false);
        hint.setVisibility(View.GONE);
        int fill = correct ? Theme.PRIMARY_SOFT : Theme.DANGER_SOFT;
        int ink = correct ? Theme.PRIMARY : Theme.DANGER;
        answerInput.setBackground(Theme.shape(this, fill, Theme.RADIUS_FIELD));
        answerInput.setTextColor(ink);
        int flags = answerInput.getPaintFlags();
        answerInput.setPaintFlags(correct ? flags & ~Paint.STRIKE_THRU_TEXT_FLAG : flags | Paint.STRIKE_THRU_TEXT_FLAG);
        if (exampleView != null) exampleView.setText(word.optString("example", ""));
        verdict.removeAllViews();
        if (correct && !assisted) {
            LinearLayout line = ui.row();
            line.setGravity(Gravity.CENTER);
            ImageView mark = ui.icon(R.drawable.ic_circle_check, Theme.PRIMARY);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ui.dp(Theme.ICON_SMALL), ui.dp(Theme.ICON_SMALL));
            mp.rightMargin = ui.dp(Theme.SPACE_8);
            line.addView(mark, mp);
            TextView label = ui.type(outcome.completedWord ? "独立拼写完成" : "正确 · 还需再独立拼对一次", Theme.T_LABEL);
            label.setTextColor(Theme.PRIMARY);
            line.addView(label);
            verdict.addView(line, WordUi.lp());
        } else if (assisted) {
            TextView label = ui.type("拼写正确 · 使用了提示，稍后仍需独立完成", Theme.T_CALLOUT);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(Theme.INK_2);
            verdict.addView(label, WordUi.lp());
        } else {
            TextView over = ui.type(outcome == null ? "已查看答案，本题不计入完成" : "正确拼写", Theme.T_OVERLINE);
            over.setGravity(Gravity.CENTER);
            verdict.addView(over, WordUi.lp());
            ui.gap(verdict, Theme.SPACE_8);
            TextView spelled = ui.type(expected, Theme.T_TITLE);
            spelled.setTypeface(Theme.SERIF);
            spelled.setTextSize(28);
            spelled.setGravity(Gravity.CENTER);
            spelled.setTextIsSelectable(true);
            verdict.addView(spelled, WordUi.lp());
            if (outcome != null) {
                ui.gap(verdict, Theme.SPACE_8);
                TextView noted = ui.type("已记入错题本，稍后会再次出现", Theme.T_CAPTION);
                noted.setGravity(Gravity.CENTER);
                verdict.addView(noted, WordUi.lp());
            }
        }
        verdict.setVisibility(View.VISIBLE);
        if (animate) Motion.enter(verdict);
        updateTally();
        progress.set(session.finishedCount() / (float) Math.max(1, session.initialTotal()), animate);
        ui.pin(ui.primaryButton(session.ended() ? "查看结果" : "下一个", v -> next()));
    }

    private void next() {
        if (!answered) return;
        if (session.ended()) interaction.confirm(stage);
        draft = "";
        show(true);
    }

    private void updateTally() {
        if (session == null || session.attempts() == 0) {
            tally.setText("");
            return;
        }
        tally.setText("答对 " + session.correctCount() + " · 拼错 " + session.wrongCount());
    }

    private void showHint(String message) {
        Motion.layout(stage);
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        nudge(answerInput);
        answerInput.requestFocus();
    }

    private JSONObject find(String id) {
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.optJSONObject(i);
            if (word != null && id.equals(Words.stableId(word))) return word;
        }
        return null;
    }

    private void saveProgress() {
        try {
            if (session != null) TrainingStore.saveSpelling(this, sessionId, selectionId, wrongMode, taskId, round, historyAt, attemptId, session.snapshot(), session.ended() ? "" : session.current(), hintUsed && !session.ended() && session.current().equals(draftWordId));
        } catch (Exception e) {
            ui.error(e.getMessage());
        }
    }

    private void summary() {
        saveResult();
        TrainingStore.clearSpelling(this, sessionId);
        hideKeyboard(answerInput != null ? answerInput : stage);
        answerInput = null;
        head.setVisibility(View.GONE);
        stage.removeAllViews();
        interaction.confirm(ui.body);
        TextView title = ui.type("拼写完成", Theme.T_TITLE);
        stage.addView(title, WordUi.lp());
        ui.gap(stage, Theme.SPACE_8);
        TextView line = ui.type(session.initialTotal() + " 个目标词均已独立完成", Theme.T_CALLOUT);
        stage.addView(line, WordUi.lp());
        ui.gap(stage, Theme.SPACE_24);
        LinearLayout card = ui.card();
        card.setPadding(ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_24), ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_20));
        LinearLayout stats = ui.row();
        stats.setGravity(Gravity.TOP);
        stat(stats, session.correctCount(), "答对", session.correctCount() > 0 ? Theme.PRIMARY : Theme.INK);
        stat(stats, session.wrongCount(), "拼错", session.wrongCount() > 0 ? Theme.DANGER : Theme.INK);
        stat(stats, session.skippedCount(), "跳过", Theme.INK_2);
        card.addView(stats, WordUi.lp());
        stage.addView(card, WordUi.lp());
        if (session.wrongCount() > 0) ui.note(stage, "拼错的词已记入错题本。错词需要连续两次独立拼对才算掌握。");
        JSONArray taskWords = taskWords();
        final int[] missing = ExampleActivity.missingExampleIndices(taskWords, exampleIndices);
        ui.pin(ui.primaryButton(missing.length > 0 ? "继续补充例句" : "完成", v -> continueAfterSpelling()));
        Motion.stagger(stage);
    }

    private JSONArray taskWords() {
        if (taskId <= 0) return words;
        JSONObject task = Store.find(Store.tasks(this), taskId);
        JSONArray stored = task == null ? null : task.optJSONArray("words");
        return stored == null ? words : stored;
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

    private void continueAfterSpelling() {
        int[] missing = ExampleActivity.missingExampleIndices(taskWords(), exampleIndices);
        if (missing.length == 0) {
            finish();
            return;
        }
        Motion.replace(this, new Intent(this, ExampleActivity.class).putExtra("taskId", taskId).putExtra("indices", missing));
    }

    private void saveResult() {
        if (historyAt <= 0 || session == null) return;
        try {
            JSONArray tasks = Store.tasks(this);
            JSONObject task = Store.find(tasks, taskId);
            if (task == null) return;
            JSONArray history = task.optJSONArray("history");
            if (history == null) return;
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject entry = history.optJSONObject(i);
                if (entry != null && entry.optLong("at") == historyAt && entry.optInt("stage") == round) {
                    entry.put("spellingTotal", session.initialTotal())
                            .put("spellingCorrect", session.correctCount())
                            .put("spellingWrong", session.wrongCount())
                            .put("spellingSkipped", session.skippedCount())
                            .put("spellingCompleted", session.ended());
                    Store.save(this, tasks);
                    return;
                }
            }
        } catch (Exception e) {
            ui.error("拼写成绩暂未写入：" + e.getMessage());
        }
    }

    @Override public void onBackPressed() {
        if (session == null || session.ended()) {
            finish();
            return;
        }
        saveProgress();
        String message = historyAt > 0
                ? "进度已保存。拼错的词已记入错题本；离开后本次仍记为未完成。"
                : "进度已保存。拼错的词已记入错题本。";
        Sheet.confirm(this, "结束拼写？", message, "继续拼写", "结束", true, this::finish);
    }

    static CharSequence sansHint(String text, int sizeDp) {
        SpannableString value = new SpannableString(text);
        value.setSpan(new TypefaceSpan("sans-serif"), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (sizeDp > 0) value.setSpan(new AbsoluteSizeSpan(sizeDp, true), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return value;
    }

    private static String cloze(String example, String english) {
        if (english == null || english.isEmpty()) return example;
        try {
            Pattern word = Pattern.compile("(?i)(?<![\\p{L}])" + Pattern.quote(english) + "[\\p{L}]*");
            return word.matcher(example).replaceAll("_____");
        } catch (RuntimeException e) {
            return example;
        }
    }

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
