package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.repository.NoteRepository;
import com.vivo.lanxin.campus.repository.ReminderRepository;
import com.vivo.lanxin.campus.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ReportController {
    private final NoteRepository noteRepo;
    private final ReminderRepository reminderRepo;
    private final AuthService auth;

    public ReportController(NoteRepository noteRepo, ReminderRepository reminderRepo, AuthService auth) {
        this.noteRepo = noteRepo;
        this.reminderRepo = reminderRepo;
        this.auth = auth;
    }

    @GetMapping("/reports/weekly")
    public Map<String, Object> weeklyReport(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        long noteCount = noteRepo.countByUserId(userId);
        long completed = reminderRepo.countByUserIdAndCompletedTrue(userId);
        return Map.of(
                "title", "本周学习周报",
                "noteCount", noteCount,
                "focusHours", 11.5,
                "completedTasks", completed,
                "highlights", List.of("课堂笔记归档率 92%", "高等数学复习连续 3 天", "DDL 风险已压到 2 项"),
                "message", "这周你把零散内容整理起来了，下一步重点是用自测题检查掌握程度。"
        );
    }

    @GetMapping("/reports/weekly/list")
    public List<Map<String, Object>> weeklyReports(@RequestHeader("Authorization") String authHeader) {
        return List.of(weeklyReport(authHeader));
    }

    @PostMapping("/reports/weekly/generate")
    public Map<String, Object> generateWeeklyReport(@RequestHeader("Authorization") String authHeader) {
        return weeklyReport(authHeader);
    }

    @GetMapping("/stats/dashboard")
    public Map<String, Object> dashboard(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        LocalDate today = LocalDate.now();
        long noteCount = noteRepo.countByUserId(userId);
        long openCount = reminderRepo.countByUserIdAndCompletedFalseAndDueDateBetween(userId, today, today.plusDays(30));
        long overdueCount = reminderRepo.countByUserIdAndCompletedFalseAndDueDateLessThan(userId, today);
        long urgentCount = reminderRepo.countByUserIdAndCompletedFalseAndDueDateBetween(userId, today, today.plusDays(3));
        long completedCount = reminderRepo.countByUserIdAndCompletedTrue(userId);
        long studyDays = computeStudyDays(noteRepo.findStudyDatesByUserId(userId));
        return Map.of(
                "noteCount", noteCount,
                "openReminderCount", openCount,
                "overdueReminderCount", overdueCount,
                "urgentReminderCount", urgentCount,
                "completedReminderCount", completedCount,
                "today", today.toString(),
                "offlineReady", true,
                "studyDays", studyDays
        );
    }

    @GetMapping("/stats/continuity")
    public Map<String, Object> continuity() {
        return Map.of("days", 9, "best", 15);
    }

    private long computeStudyDays(List<LocalDate> dates) {
        if (dates.isEmpty()) return 0;
        long streak = 1;
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i - 1).minusDays(1).equals(dates.get(i))) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }
}
