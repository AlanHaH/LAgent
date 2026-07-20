package com.adaptivelearning.profile.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AvailabilityPolicy {
    private AvailabilityPolicy() {}

    public record Slot(int weekday, LocalTime start, LocalTime end, String energyLevel) {}
    public record NormalizedSlot(int weekday, LocalTime start, LocalTime end, int minutes, String energyLevel) {}

    public static List<NormalizedSlot> normalizeAndValidate(List<Slot> input) {
        List<NormalizedSlot> result = new ArrayList<>();
        for (Slot slot : input) {
            if (slot.weekday() < 1 || slot.weekday() > 7 || slot.start() == null || slot.end() == null) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "可用时间字段不完整");
            }
            if (slot.start().equals(slot.end())) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "开始时间不能等于结束时间");
            }
            if (slot.end().isAfter(slot.start())) {
                result.add(create(slot.weekday(), slot.start(), slot.end(), slot.energyLevel()));
            } else {
                result.add(create(slot.weekday(), slot.start(), LocalTime.MAX, slot.energyLevel()));
                int nextDay = slot.weekday() == 7 ? 1 : slot.weekday() + 1;
                result.add(create(nextDay, LocalTime.MIN, slot.end(), slot.energyLevel()));
            }
        }
        for (int day = 1; day <= 7; day++) {
            final int weekday = day;
            List<NormalizedSlot> daily = result.stream().filter(s -> s.weekday() == weekday)
                    .sorted(Comparator.comparing(NormalizedSlot::start)).toList();
            int total = 0;
            for (int i = 0; i < daily.size(); i++) {
                total += daily.get(i).minutes();
                if (i > 0 && daily.get(i).start().isBefore(daily.get(i - 1).end())) {
                    throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                            "星期 " + day + " 的可用时间段发生重叠");
                }
            }
            if (total > 960) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                        "星期 " + day + " 的每日可用时间不能超过 960 分钟");
            }
        }
        return result;
    }

    private static NormalizedSlot create(int weekday, LocalTime start, LocalTime end, String energy) {
        long minutes = Duration.between(start, end).toMinutes();
        if (end.equals(LocalTime.MAX)) minutes++;
        if (minutes <= 0) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "可用时间段必须大于 0 分钟");
        }
        return new NormalizedSlot(weekday, start, end, Math.toIntExact(minutes),
                energy == null ? "MEDIUM" : energy);
    }
}

