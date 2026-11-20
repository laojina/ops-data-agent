package com.opsdata.agent;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

record QuestionScope(List<DateRange> periods, String invalidDate) {
    private static final LocalDate FIRST_DATA_DAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate LAST_DATA_DAY = LocalDate.of(2026, 10, 31);
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("uuuu 年 M 月 d 日");
    private static final Pattern PERIOD = Pattern.compile(
            "(?<!\\d)(?<numericYear>\\d{4})(?<sep>[-/.])(?<numericMonth>\\d{1,2})"
                    + "(?:\\k<sep>(?<numericDay>\\d{1,2}))?(?!\\d)"
                    + "|(?<![\\d/])(?<shortMonth>\\d{1,2})[-/](?<shortDay>\\d{1,2})(?![\\d/]|\\s*月)"
                    + "|(?<![\\d/])(?<listedMonth>\\d{1,2})(?=(?:\\s*[、,，和及与至到-]\\s*\\d{1,2})+\\s*月)"
                    + "|(?<!\\d)(?:(?<year>\\d{4})\\s*年\\s*)?(?<month>\\d{1,2})\\s*月"
                    + "(?:\\s*(?<day>\\d{1,2})\\s*[日号])?"
                    + "|(?<relativeWeek>[上下本这]周|[上下这]个?(?:星期|礼拜)|本(?:星期|礼拜))(?<weekday>[一二三四五六日天])?"
                    + "|(?<relative>上个月|上月|本月|这个月|今天|昨天|昨日|前天|明天|明日)");
    private static final Pattern RANGE_SEPARATOR = Pattern.compile("\\s*(?:至|到|[-—–~～])\\s*");

    static QuestionScope read(String question, LocalDate today) {
        List<DateRange> periods = new ArrayList<>();
        Matcher match = PERIOD.matcher(question);
        int previousEnd = -1;
        while (match.find()) {
            try {
                DateRange range = readPeriod(match, today);
                if (previousEnd >= 0
                        && RANGE_SEPARATOR.matcher(question.substring(previousEnd, match.start())).matches()) {
                    DateRange previous = periods.remove(periods.size() - 1);
                    if (range.end().isBefore(previous.start())) {
                        return new QuestionScope(List.of(), previous.label() + " 至 " + range.label());
                    }
                    range = new DateRange(previous.start(), range.end());
                }
                periods.add(range);
                previousEnd = match.end();
            } catch (DateTimeException exception) {
                return new QuestionScope(List.of(), match.group());
            }
        }
        return new QuestionScope(periods.stream().distinct().toList(), null);
    }

    private static DateRange readPeriod(Matcher match, LocalDate today) {
        if (match.group("relativeWeek") != null) {
            int offset = switch (match.group("relativeWeek").charAt(0)) {
                case '上' -> -1;
                case '下' -> 1;
                default -> 0;
            };
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1).plusWeeks(offset);
            String weekday = match.group("weekday");
            if (weekday == null) {
                return new DateRange(monday, monday.plusDays(6));
            }
            return DateRange.day(monday.plusDays("一二三四五六日".indexOf(weekday.replace('天', '日'))));
        }
        if (match.group("relative") != null) {
            return switch (match.group("relative")) {
                case "上个月", "上月" -> DateRange.month(YearMonth.from(today).minusMonths(1));
                case "本月", "这个月" -> DateRange.month(YearMonth.from(today));
                case "昨天", "昨日" -> DateRange.day(today.minusDays(1));
                case "前天" -> DateRange.day(today.minusDays(2));
                case "明天", "明日" -> DateRange.day(today.plusDays(1));
                default -> DateRange.day(today);
            };
        }
        if (match.group("shortMonth") != null) {
            return DateRange.day(LocalDate.of(FIRST_DATA_DAY.getYear(),
                    Integer.parseInt(match.group("shortMonth")), Integer.parseInt(match.group("shortDay"))));
        }
        if (match.group("listedMonth") != null) {
            return DateRange.month(YearMonth.of(FIRST_DATA_DAY.getYear(), Integer.parseInt(match.group("listedMonth"))));
        }
        boolean numeric = match.group("numericYear") != null;
        String year = match.group(numeric ? "numericYear" : "year");
        String month = match.group(numeric ? "numericMonth" : "month");
        String day = match.group(numeric ? "numericDay" : "day");
        YearMonth parsed = YearMonth.of(year == null ? FIRST_DATA_DAY.getYear() : Integer.parseInt(year),
                Integer.parseInt(month));
        return day == null ? DateRange.month(parsed) : DateRange.day(parsed.atDay(Integer.parseInt(day)));
    }

    String missingEvidence() {
        if (invalidDate != null) {
            return "日期或日期范围无效，请确认：" + invalidDate + "。";
        }

        Set<String> missing = new LinkedHashSet<>();
        Set<String> covered = new LinkedHashSet<>();
        for (DateRange range : periods) {
            if (range.start().isBefore(FIRST_DATA_DAY)) {
                missing.add(new DateRange(range.start(), earlier(range.end(), FIRST_DATA_DAY.minusDays(1))).label());
            }
            if (range.end().isAfter(LAST_DATA_DAY)) {
                missing.add(new DateRange(later(range.start(), LAST_DATA_DAY.plusDays(1)), range.end()).label());
            }
            LocalDate start = later(range.start(), FIRST_DATA_DAY);
            LocalDate end = earlier(range.end(), LAST_DATA_DAY);
            if (!start.isAfter(end)) {
                covered.add(new DateRange(start, end).label());
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        String message = "样例库只覆盖 " + FIRST_DATA_DAY.format(DAY_LABEL) + " 至 " + LAST_DATA_DAY.format(DAY_LABEL)
                + "。" + String.join("、", missing) + "没有可用数据。";
        if (!covered.isEmpty()) {
            message += String.join("、", covered) + "在样例库覆盖范围内，可单独查询。";
        }
        return message;
    }

    String label() {
        return periods.stream().map(DateRange::label).collect(Collectors.joining("、"));
    }

    private static LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    record DateRange(LocalDate start, LocalDate end) {
        static DateRange month(YearMonth month) {
            return new DateRange(month.atDay(1), month.atEndOfMonth());
        }

        static DateRange day(LocalDate date) {
            return new DateRange(date, date);
        }

        String label() {
            if (start.equals(end)) {
                return start.format(DAY_LABEL);
            }
            YearMonth month = YearMonth.from(start);
            if (start.getDayOfMonth() == 1 && end.equals(month.atEndOfMonth())) {
                return month.getYear() + " 年 " + month.getMonthValue() + " 月";
            }
            return start.format(DAY_LABEL) + " 至 " + end.format(DAY_LABEL);
        }
    }
}
