package com.korail.talk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MockKorailActivity extends Activity {
    private static final int BLUE = Color.rgb(30, 88, 175);
    private static final int LIGHT_BLUE = Color.rgb(239, 245, 255);
    private static final int GREEN = Color.rgb(36, 158, 77);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showHome();
    }

    private void showHome() {
        LinearLayout root = base();
        header(root, "코레일톡", "2026.05.20 수");
        card(root, "빠른 예매", "출발역과 도착역을 선택해 승차권을 예매합니다.");
        root.addView(primaryButton("승차권 예매", this::showReservation));
        root.addView(secondaryButton("승차권 확인", this::showTicket));
        root.addView(secondaryButton("예매내역", this::showTicket));
        root.addView(note("MVP 시연용 화면입니다. 실제 큐 오버레이는 이 앱을 코레일톡 패키지로 인식해 동작합니다."));
        setContentView(wrap(root));
    }

    private void showReservation() {
        LinearLayout root = base();
        header(root, "승차권 예매", "어디로 이동하시나요?");
        field(root, "출발", "서울");
        field(root, "도착", "부산");
        field(root, "날짜", "2026.05.20 수");
        field(root, "인원", "어른 1명");
        root.addView(primaryButton("열차 조회하기", this::showTrainList));
        setContentView(wrap(root));
    }

    private void showTrainList() {
        LinearLayout root = base();
        header(root, "열차 선택", "서울 → 부산");
        train(root, "KTX 123", "09:20", "12:05", "59,800원");
        train(root, "KTX 145", "10:00", "12:44", "59,800원");
        root.addView(primaryButton("다음", this::showPaymentPause));
        setContentView(wrap(root));
    }

    private void showTicket() {
        LinearLayout root = base();
        header(root, "승차권 확인", "예매한 표를 확인합니다.");
        card(root, "오늘의 승차권", "서울 → 부산\nKTX 123\n09:20 출발");
        root.addView(primaryButton("승차권 상세보기", this::showTicketDetail));
        setContentView(wrap(root));
    }

    private void showTicketDetail() {
        LinearLayout root = base();
        header(root, "승차권", "직접 확인이 필요한 화면");
        card(root, "KTX 123", "서울 → 부산\n1호차 08A\n승차권 정보");
        root.addView(primaryButton("확인", this::showHome));
        setContentView(wrap(root));
    }

    private void showPaymentPause() {
        LinearLayout root = base();
        header(root, "결제", "직접 확인이 필요한 화면");
        card(root, "결제 예정 금액", "59,800원");
        root.addView(primaryButton("결제하기", this::showHome));
        setContentView(wrap(root));
    }

    private LinearLayout base() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(42), dp(22), dp(24));
        root.setBackgroundColor(Color.WHITE);
        return root;
    }

    private ScrollView wrap(LinearLayout root) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.WHITE);
        scrollView.addView(root);
        return scrollView;
    }

    private void header(LinearLayout root, String title, String subtitle) {
        TextView titleView = text(title, 30, Color.BLACK, true);
        root.addView(titleView, lp(-1, -2, 0, 0, 0, 8));
        root.addView(text(subtitle, 17, Color.rgb(80, 80, 80), false), lp(-1, -2, 0, 0, 0, 22));
    }

    private void card(LinearLayout root, String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(LIGHT_BLUE);
        card.addView(text(title, 20, BLUE, true));
        card.addView(text(body, 17, Color.rgb(40, 40, 40), false));
        root.addView(card, lp(-1, -2, 0, 0, 0, 16));
    }

    private void field(LinearLayout root, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(Color.rgb(246, 248, 250));
        TextView left = text(label, 16, Color.rgb(90, 90, 90), false);
        TextView right = text(value, 20, Color.BLACK, true);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(right);
        root.addView(row, lp(-1, -2, 0, 0, 0, 10));
    }

    private void train(LinearLayout root, String name, String depart, String arrive, String price) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(Color.rgb(246, 248, 250));
        row.addView(text(name, 19, BLUE, true));
        row.addView(text(depart + " 출발  ·  " + arrive + " 도착  ·  " + price, 16, Color.rgb(40, 40, 40), false));
        root.addView(row, lp(-1, -2, 0, 0, 0, 10));
    }

    private TextView primaryButton(String label, Runnable action) {
        TextView button = button(label, Color.WHITE, GREEN);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView secondaryButton(String label, Runnable action) {
        TextView button = button(label, BLUE, Color.rgb(235, 241, 252));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView button(String label, int textColor, int backgroundColor) {
        TextView button = text(label, 20, textColor, true);
        button.setClickable(true);
        button.setFocusable(true);
        button.setText(label);
        button.setBackgroundColor(backgroundColor);
        button.setMinHeight(dp(64));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setLayoutParams(lp(-1, dp(64), 0, 0, 0, 12));
        return button;
    }

    private TextView note(String value) {
        TextView note = text(value, 14, Color.rgb(105, 105, 105), false);
        note.setPadding(0, dp(8), 0, 0);
        return note;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.16f);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
