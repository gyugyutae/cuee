package com.korail.talk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Deterministic KorailTalk-shaped app for CUEE end-to-end tests and demo capture. */
public class MockKorailActivity extends Activity {
    private static final int NAVY = Color.rgb(13, 67, 102);
    private static final int BLUE = Color.rgb(30, 88, 175);
    private static final int LIGHT_BLUE = Color.rgb(235, 246, 251);
    private static final int GREEN = Color.rgb(36, 158, 77);
    private static final int LIGHT_GRAY = Color.rgb(246, 248, 250);

    private String departure = "선택";
    private String arrival = "선택";
    private LocalDate travelDate = LocalDate.now();
    private int departureHour = 0;
    private int adults = 1;
    private int children = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showReservation();
    }

    private void showReservation() {
        LinearLayout root = base();
        header(root, "KORAIL", "승차권 예매");
        routeRow(root);
        root.addView(fieldButton(R.id.rl_going_date, "가는날", dateLabel() + "  " + twoDigits(departureHour) + ":00 이후", this::showDatePicker));
        root.addView(fieldButton(R.id.tv_value_passenger, "인원선택", passengerLabel(), this::showPassengerPicker));
        root.addView(primaryButton(R.id.search_trains, "열차조회", this::showTrainList));
        root.addView(note("CUEE 데모 환경 · 실제 예매와 결제는 발생하지 않습니다."));
        setContentView(wrap(root));
    }

    private void routeRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(12), dp(18), dp(12), dp(18));
        row.setBackgroundColor(Color.WHITE);

        TextView departureView = routeButton(R.id.v_departure_station, "출발", departure, () -> showStationSearch(true));
        TextView arrow = text("→", 26, BLUE, true);
        arrow.setGravity(Gravity.CENTER);
        TextView arrivalView = routeButton(R.id.v_arrival_station, "도착", arrival, () -> showStationSearch(false));
        row.addView(departureView, new LinearLayout.LayoutParams(0, dp(104), 1));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(48), dp(104)));
        row.addView(arrivalView, new LinearLayout.LayoutParams(0, dp(104), 1));
        root.addView(cardFrame(row));
    }

    private TextView routeButton(int id, String label, String value, Runnable action) {
        TextView view = text(label + "\n" + value, 20, NAVY, true);
        view.setId(id);
        view.setContentDescription(label + "역 " + value);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void showStationSearch(boolean selectingDeparture) {
        String target = selectingDeparture ? "진주" : "서울";
        LinearLayout root = base();
        header(root, selectingDeparture ? "출발역 선택" : "도착역 선택", "역 이름을 검색하세요");

        EditText search = new EditText(this);
        search.setId(R.id.stationNameEdit);
        search.setHint("역 이름 검색");
        search.setContentDescription("역 이름 검색");
        search.setTextSize(20);
        search.setSingleLine(true);
        search.setPadding(dp(18), dp(14), dp(18), dp(14));
        search.setBackgroundColor(LIGHT_GRAY);
        root.addView(search, lp(-1, dp(64), 0, 0, 0, 18));

        TextView result = secondaryButton(R.id.station_result, target, () -> {
            if (selectingDeparture) departure = target; else arrival = target;
            showReservation();
        });
        result.setContentDescription(target + "역 검색 결과");
        result.setVisibility(View.INVISIBLE);
        root.addView(result, lp(-1, dp(64), 0, 0, 0, 12));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                result.setVisibility(s.toString().contains(target) ? View.VISIBLE : View.INVISIBLE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        setContentView(wrap(root));
        search.requestFocus();
    }

    private void showDatePicker() {
        LinearLayout root = base();
        header(root, "가는날 선택", "날짜와 출발 시간을 선택하세요");
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        TextView day = secondaryButton(R.id.date_cell_tomorrow, tomorrow.getDayOfMonth() + "일 · 내일", () -> {
            travelDate = tomorrow;
            showDatePicker();
        });
        day.setContentDescription(
            tomorrow.getMonthValue() + "월 " + tomorrow.getDayOfMonth() + "일 내일" +
                (travelDate.equals(tomorrow) ? " 선택됨" : "")
        );
        root.addView(day, lp(-1, dp(64), 0, 0, 0, 10));
        root.addView(sectionLabel("출발 시간"));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER);
        times.addView(timeButton(R.id.hourTxt06, 6));
        times.addView(timeButton(R.id.hourTxt09, 9));
        times.addView(timeButton(R.id.hourTxt12, 12));
        root.addView(times, lp(-1, dp(64), 0, 0, 0, 18));
        root.addView(primaryButton(R.id.confirm_button, "확인", this::showReservation));
        setContentView(wrap(root));
    }

    private TextView timeButton(int id, int hour) {
        TextView button = secondaryButton(id, twoDigits(hour) + "시", () -> {
            departureHour = hour;
            showDatePicker();
        });
        button.setContentDescription(twoDigits(hour) + "시 출발" + (departureHour == hour ? " 선택됨" : ""));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void showPassengerPicker() {
        LinearLayout root = base();
        header(root, "인원선택", "탑승 인원을 선택하세요");
        root.addView(passengerRow("어른", R.id.adult_count, adults, R.id.adult_plus, () -> {
            adults += 1;
            showPassengerPicker();
        }));
        root.addView(passengerRow("어린이", R.id.child_count, children, R.id.child_plus, () -> {
            children += 1;
            showPassengerPicker();
        }));
        root.addView(primaryButton(R.id.passenger_confirm, "확인", this::showReservation));
        setContentView(wrap(root));
    }

    private LinearLayout passengerRow(String label, int countId, int count, int plusId, Runnable plusAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(12), dp(12));
        row.setBackgroundColor(LIGHT_GRAY);
        TextView labelView = text(label, 20, Color.BLACK, true);
        TextView countView = text(String.valueOf(count), 20, Color.BLACK, false);
        countView.setId(countId);
        countView.setGravity(Gravity.CENTER);
        TextView plus = secondaryButton(plusId, "+", plusAction);
        plus.setContentDescription(label + " 증가 더하기");
        row.addView(labelView, new LinearLayout.LayoutParams(0, dp(64), 1));
        row.addView(countView, new LinearLayout.LayoutParams(dp(56), dp(64)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(72), dp(64)));
        row.setLayoutParams(lp(-1, dp(88), 0, 0, 0, 12));
        return row;
    }

    private void showTrainList() {
        LinearLayout root = base();
        header(root, "열차 조회", "진주 → 서울 · " + dateLabel());
        LinearLayout list = new LinearLayout(this);
        list.setId(R.id.trainList);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(trainRow("KTX 204", "06:10", "09:52", "서울", "매진", R.id.reserveButton204, null));
        list.addView(trainRow("KTX-산천 206", "08:56", "12:25", "서울", "일반실 예매", R.id.reserveButton206, this::showTrainDetail));
        list.addView(trainRow("ITX-새마을 1112", "09:42", "14:50", "서울", "일반실 45,900원", R.id.reserveButton1112, this::showTrainDetail));
        root.addView(list);
        root.addView(note("CUEE는 매진·대기 후보를 제외하고 직접 예매 가능한 열차를 강조합니다."));
        setContentView(wrap(root));
    }

    private LinearLayout trainRow(String train, String depart, String arrive, String destination, String action, int actionId, Runnable runnable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        row.setBackgroundColor(LIGHT_GRAY);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(text(train, 18, NAVY, true));
        info.addView(text(depart + " 진주  →  " + arrive + " " + destination, 15, Color.DKGRAY, false));
        row.addView(info, new LinearLayout.LayoutParams(0, dp(72), 1));
        TextView button = secondaryButton(actionId, action, runnable == null ? () -> {} : runnable);
        button.setClickable(runnable != null);
        button.setEnabled(runnable != null);
        row.addView(button, new LinearLayout.LayoutParams(dp(132), dp(64)));
        row.setLayoutParams(lp(-1, dp(96), 0, 0, 0, 10));
        return row;
    }

    private void showTrainDetail() {
        LinearLayout root = base();
        header(root, "승차권 선택", "추천 열차를 직접 확인하세요");
        card(root, "KTX-산천 206", "진주 08:56  →  서울 12:25\n일반실 · 어른 2명 · 어린이 1명");
        card(root, "예상 운임", "총 128,400원");
        root.addView(primaryButton(R.id.booking_button, "예매", this::showPaymentPause));
        setContentView(wrap(root));
    }

    private void showPaymentPause() {
        LinearLayout root = base();
        header(root, "결제", "마지막 단계는 사용자가 직접 확인합니다");
        card(root, "결제 예정 금액", "128,400원");
        root.addView(primaryButton(R.id.payment_button, "결제하기", () -> {}));
        root.addView(note("CUEE는 이 화면에서 안내를 종료하며 결제를 대신 진행하지 않습니다."));
        setContentView(wrap(root));
    }

    private LinearLayout base() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        root.setBackgroundColor(Color.WHITE);
        return root;
    }

    private ScrollView wrap(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        return scroll;
    }

    private void header(LinearLayout root, String brand, String title) {
        TextView brandView = text(brand, 18, Color.WHITE, true);
        brandView.setGravity(Gravity.CENTER);
        brandView.setBackgroundColor(NAVY);
        root.addView(brandView, lp(-1, dp(48), 0, 0, 0, 18));
        root.addView(text(title, 30, Color.BLACK, true), lp(-1, -2, 0, 0, 0, 20));
    }

    private View cardFrame(View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackgroundColor(LIGHT_BLUE);
        card.addView(content);
        card.setLayoutParams(lp(-1, -2, 0, 0, 0, 14));
        return card;
    }

    private void card(LinearLayout root, String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(LIGHT_BLUE);
        card.addView(text(title, 20, BLUE, true));
        card.addView(text(body, 17, Color.rgb(40, 40, 40), false));
        root.addView(card, lp(-1, -2, 0, 0, 0, 14));
    }

    private TextView fieldButton(int id, String label, String value, Runnable action) {
        TextView view = text(label + "\n" + value, 18, Color.BLACK, false);
        view.setId(id);
        view.setContentDescription(label + " " + value);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setBackgroundColor(LIGHT_GRAY);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        view.setLayoutParams(lp(-1, dp(84), 0, 0, 0, 12));
        return view;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 18, Color.DKGRAY, true);
        view.setPadding(0, dp(18), 0, dp(10));
        return view;
    }

    private TextView primaryButton(int id, String label, Runnable action) {
        TextView button = button(id, label, Color.WHITE, GREEN, action);
        button.setLayoutParams(lp(-1, dp(64), 0, 4, 0, 12));
        return button;
    }

    private TextView secondaryButton(int id, String label, Runnable action) {
        return button(id, label, BLUE, Color.rgb(235, 241, 252), action);
    }

    private TextView button(int id, String label, int textColor, int backgroundColor, Runnable action) {
        TextView button = text(label, 18, textColor, true);
        button.setId(id);
        button.setContentDescription(label);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(backgroundColor);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        return button;
    }

    private TextView note(String value) {
        TextView note = text(value, 14, Color.rgb(100, 100, 100), false);
        note.setPadding(0, dp(8), 0, 0);
        return note;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.12f);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private String dateLabel() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E)", Locale.KOREAN);
        return travelDate.format(formatter);
    }

    private String passengerLabel() {
        String label = "어른 " + adults + "명";
        return children > 0 ? label + " · 어린이 " + children + "명" : label;
    }

    private String twoDigits(int value) {
        return String.format(Locale.US, "%02d", value);
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
