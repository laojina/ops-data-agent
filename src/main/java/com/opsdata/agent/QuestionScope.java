package com.opsdata.agent;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

record QuestionScope(List<YearMonth> months) {

    private static final Pattern NAMED_MONTH =
            Pattern.compile("(?<!\\d)(?:(\\d{4})\\s*年\\s*)?(1[0-2]|0?[1-9])\\s*月");
    private static final YearMonth FIRST_DATA_MONTH = YearMonth.of(2026, 9);
    private static final YearMonth LAST_DATA_MONTH = YearMonth.of(2026, 10);

    static QuestionScope read(String question, LocalDate today) {
        Set<YearMonth> months = new LinkedHashSet<>();
        Matcher explicit = NAMED_MONTH.matcher(question);
        while (explicit.find()) {
            int year = explicit.group(1) == null ? 2026 : Integer.parseInt(explicit.group(1));
            months.add(YearMonth.of(year, Integer.parseInt(explicit.group(2))));
        }
        YearMonth current = YearMonth.from(today);
        if (question.contains("上个月") || question.contains("上月")) {
            months.add(current.minusMonths(1));
        }
        if (question.contains("本月") || question.contains("这个月")) {
            months.add(current);
        }
        return new QuestionScope(List.copyOf(months));
    }

    boolean outOfRange() {
        return months.stream().anyMatch(month -> month.isBefore(FIRST_DATA_MONTH) || month.isAfter(LAST_DATA_MONTH));
    }

    String label() {
        return months.stream().map(month -> month.getYear() + " 年 " + month.getMonthValue() + " 月")
                .collect(Collectors.joining("、"));
    }
}
